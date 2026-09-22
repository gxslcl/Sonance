package com.sonance.player.data

/**
 * 从一堆搜索结果里挑出「和本地这首歌是同一首」的那条。
 *
 * ## 为什么必须有打分，不能直接用第一条
 *
 * 实测：拿本地歌名歌手去搜，**第一条经常不是同一首歌**。
 * 例如搜 `500 Miles The Proclaimers`，第一条返回的是一个同名的翻唱，
 * 时长对不上、歌词也是空的；搜 `晴天` 第一条是「晴天(深情版)」。
 *
 * 所以要把「标题 + 歌手 + 时长 + 版本标记」四个维度合起来打分。
 * 其中**时长是最可靠的判据** —— 本地文件的时长来自 MediaStore，是准的；
 * 同一首歌不同版本（Live / 翻唱 / 伴奏）的时长几乎必然对不上。
 *
 * ## 为什么是纯函数
 *
 * 打分规则错了不会报错，只会**静默地匹配到另一首歌**：封面贴错、歌词对不上，
 * 而且用户很难发现是匹配问题（会以为是"网易云的数据就这样"）。
 * 抽成纯函数就能把各种边界穷举在 JVM 单测里，不用真机。
 */
internal object NeteaseMatch {

    /** 认不出来就返回 null。宁可"没找到"，也不要贴一张错的封面 */
    const val MIN_SCORE = 90

    /**
     * 版本标记，**归并到规范组**。
     *
     * 必须归并：本地标签写 `味道 (live)`、线上写 `味道 演唱会现场版` ——
     * 字面不同但指的是同一种版本。按字面比较的话会互罚 45 分，
     * 明明是同一个版本却匹配不上。
     *
     * 组内关键词用「包含」匹配，组间按顺序取第一个命中的。
     */
    private val VERSION_GROUPS: List<Pair<String, List<String>>> = listOf(
        "live" to listOf("live", "现场", "演唱会"),
        "instrumental" to listOf("instrumental", "伴奏", "offvocal"),
        "remix" to listOf("remix", "混音", "dj"),
        "acoustic" to listOf("acoustic", "不插电"),
        "cover" to listOf("cover", "翻唱"),
        "demo" to listOf("demo"),
        "remaster" to listOf("remaster", "重制")
    )

    /**
     * 归一化：小写 + 只留字母数字（含中日韩）。
     *
     * 顺带去掉本地文件名常见的**开头序号**（`01 晴天` / `01. 晴天` / `[01] 晴天`）——
     * 这类前缀会把标题相似度整体拉低，导致本该匹配上的歌被打低分。
     */
    internal fun normalize(raw: String): String {
        var s = raw.trim()
        // 去掉开头的音轨序号
        s = s.replace(Regex("^\\[?\\d{1,3}\\]?\\s*[-._、)）]?\\s*"), "")
        return s.lowercase().filter { it.isLetterOrDigit() }
    }

    /**
     * 标题里的版本标记，返回**规范组名**（没有就返回 null）。
     *
     * 用于"两边版本要一致"的判据。返回组名而不是原始词，是为了让
     * `live` / `现场` / `演唱会` 这类同义写法能对上。
     */
    internal fun versionTag(raw: String): String? {
        val s = raw.lowercase()
        return VERSION_GROUPS.firstOrNull { (_, keys) -> keys.any { it in s } }?.first
    }

    /**
     * 给一个候选打分。**分数越高越像**；[MIN_SCORE] 以下视为不是同一首。
     *
     * 权重设计（满分约 190）：
     * - 标题：完全相等 100 / 一方包含另一方 60 / 否则 0
     * - 歌手：任一方包含另一方 50 / 否则 0
     * - 时长：±3 秒内 40 / ±8 秒内 15 / 差 20 秒以上 **-80**（基本可以断定不是同一首）
     * - 版本标记：两边一致 +10 / 一边有一边没有 -45
     *
     * 时长的负分刻意给得很重：本地是 `味道 (live)`、候选是录音室版，
     * 标题能拿 100 分，但时长差几十秒必须把它压下去 —— 否则歌词时间轴会全错。
     */
    internal fun score(
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long,
        title: String,
        artist: String,
        durationMs: Long
    ): Int {
        var score = 0

        // ---- 标题 ----
        val ct = normalize(candidateTitle)
        val t = normalize(title)
        score += when {
            ct.isEmpty() || t.isEmpty() -> 0
            ct == t -> 100
            ct.contains(t) || t.contains(ct) -> 60
            else -> 0
        }

        // ---- 歌手 ----
        val ca = normalize(candidateArtist)
        val a = normalize(artist)
        // 本地文件的歌手标签经常是空的 / 是"未知艺术家"，这时不给分也不扣分，
        // 靠标题和时长判断就够了
        if (ca.isNotEmpty() && a.isNotEmpty() && (ca.contains(a) || a.contains(ca))) {
            score += 50
        }

        // ---- 时长 ----
        if (durationMs > 0 && candidateDurationMs > 0) {
            val diff = kotlin.math.abs(durationMs - candidateDurationMs)
            score += when {
                diff <= 3_000 -> 40
                diff <= 8_000 -> 15
                diff > 20_000 -> -80
                else -> 0
            }
        }

        // ---- 版本标记 ----
        val cv = versionTag(candidateTitle)
        val v = versionTag(title)
        score += if (cv == v) 10 else -45

        return score
    }

    /**
     * 从候选里挑最像的一条。
     *
     * @return 最高分那条；**最高分也低于 [MIN_SCORE] 时返回 null**（认不出来）
     */
    internal fun pickBest(
        candidates: List<RemoteTrack>,
        title: String,
        artist: String,
        durationMs: Long
    ): RemoteTrack? {
        var best: RemoteTrack? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            val s = score(c.title, c.artist, c.durationMs, title, artist, durationMs)
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        return if (bestScore >= MIN_SCORE) best else null
    }
}

/**
 * 标题或歌手里有**中日韩字符**吗。
 *
 * 用来决定补封面时先问哪个源：iTunes 的华语曲库覆盖差，而且经常返回
 * **同名翻唱**的封面；华语歌先问网易云命中率高得多。西洋歌反过来。
 *
 * 判据用码点范围而不是"有没有汉字"—— 日文歌名（`夢ならば…`）和韩文歌名
 * 同样属于"东亚曲库"，用网易云命中率也更高。
 */
internal fun hasCjkText(vararg parts: String): Boolean = parts.any { s ->
    s.any { c ->
        val v = c.code
        v in 0x3040..0x30FF ||   // 平假名 / 片假名
            v in 0x3400..0x9FFF || // 汉字（含扩展 A）
            v in 0xAC00..0xD7AF    // 谚文
    }
}

/**
 * 摘掉歌词开头的**制作人员名单**。
 *
 * 网易云的行级歌词前几行常常是这些，而且**带着假时间戳**：
 *
 * ```
 * [00:00.000] 作词 : 米津玄師
 * [00:00.212] 作曲 : 米津玄師
 * [00:00.424] 编曲 : 米津玄師/室屋光一郎
 * [00:00.636] 制作人 : 米津玄師
 * [00:00.851]夢ならばどれほどよかったでしょう   ← 正文从这里开始
 * ```
 *
 * 解析器会把这些当成**有时间的歌词行**渲染出来，于是歌词页头四行是名单，
 * 而且它们的时间戳是假的（0.0~0.6 秒），滚动时会挤在最上面。
 *
 * 判据是「`[时间]` 后面紧跟 `关键词 :`」—— 正文里出现 `作词 :` 这种写法的概率极低，
 * 而且**只清开头连续的若干行**，中间万一有也不会误伤。
 */
internal fun stripCreditLines(lrc: String): String {
    val lines = lrc.split('\n')
    var start = 0
    while (start < lines.size) {
        val line = lines[start].trim()
        if (line.isEmpty()) {
            start++
            continue
        }
        if (!isCreditLine(line)) break
        start++
    }
    return lines.drop(start).joinToString("\n").trim()
}

/** `[00:00.000] 作词 : xxx` 这种 */
private fun isCreditLine(line: String): Boolean {
    val close = line.indexOf(']')
    if (!line.startsWith("[") || close <= 1) return false
    val body = line.substring(close + 1).trim()
    val head = body.substringBefore(':').substringBefore('：').trim()
    return head in CREDIT_KEYWORDS
}

/**
 * 制作人员名单里会出现的关键词。
 *
 * 刻意列得**窄**：只收「一眼就能确定是名单」的词。像"和声"这种既可能是名单
 * 也可能是歌词正文的词，宁可漏掉（留着最多多一行），也不要误删歌词。
 */
private val CREDIT_KEYWORDS = setOf(
    "作词", "作曲", "编曲", "制作人", "监制", "出品", "录音", "混音", "母带",
    "吉他", "贝斯", "鼓", "键盘", "弦乐", "配唱", "和声编写", "人声编辑",
    "OP", "SP", "统筹", "企划", "封面", "发行", "词", "曲"
)

/**
 * 把翻译歌词并到原文的**同一行**。
 *
 * ## 为什么并到同一行，而不是另起一行
 *
 * 另起一行有两种写法，在现有的歌词解析器上都不行：
 *
 * - **不带时间戳的裸行**：解析器把 `timeMs = -1` 的行**整个丢掉**
 *   （同步滚动只保留有时间戳的行），译文会直接消失
 * - **同一个时间戳写两行**：解析器会生成两个 `LyricLine`，而高亮只认其中一个，
 *   另一行会被当成"已经唱过的"渲染成灰色 —— 看着像错位
 *
 * 并到同一行就没这些问题：一个时间戳、一行文字、一个高亮。
 * 歌词行没有 `maxLines` 限制，长句子会自动折行。
 *
 * ## 时间戳要按毫秒配对，不能按字符串
 *
 * 网易云原文和译文的时间戳**格式可能不一致**（`[00:30.542]` vs `[00:30.54]`），
 * 按字符串配对会整段对不上。这里把两边都解析成毫秒再配对。
 */
internal fun mergeTranslation(lrc: String, translation: String): String {
    val translations = HashMap<Int, String>()
    for (raw in translation.split('\n')) {
        val (ms, text) = splitTaggedLine(raw) ?: continue
        if (text.isNotEmpty()) translations[ms] = text
    }
    if (translations.isEmpty()) return lrc

    val out = StringBuilder(lrc.length * 2)
    for (raw in lrc.split('\n')) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val parsed = splitTaggedLine(line)
        if (parsed == null) {
            // 元信息行（`[ti:]` / `[ar:]` …）原样保留
            out.append(line).append('\n')
            continue
        }
        val (ms, text) = parsed
        out.append(line)
        val tr = translations[ms]
        // 译文和原文一模一样就不重复贴（有些歌的"翻译"其实是原文）
        if (tr != null && tr != text) out.append(TRANSLATION_SEP).append(tr)
        out.append('\n')
    }
    return out.toString().trimEnd()
}

/**
 * 拆出 `[时间戳]正文` 的时间（毫秒）和正文。
 *
 * 认不出来（没有标签 / 标签不是时间 / 时间格式怪）返回 null。
 */
private fun splitTaggedLine(raw: String): Pair<Int, String>? {
    val line = raw.trim()
    if (!line.startsWith("[")) return null
    val close = line.indexOf(']')
    if (close <= 1) return null
    val ms = timeKeyMs(line.substring(1, close)) ?: return null
    return ms to line.substring(close + 1).trim()
}

/** `mm:ss` / `mm:ss.xx` / `mm:ss.xxx` / `hh:mm:ss` → 毫秒；认不出来返回 null */
private fun timeKeyMs(tag: String): Int? {
    val parts = tag.trim().split(':')
    if (parts.size < 2 || parts.size > 3) return null
    return try {
        val ms = if (parts.size == 3) {
            ((parts[0].trim().toLong() * 3600 + parts[1].trim().toLong() * 60) * 1000L) +
                (parts[2].trim().toDouble() * 1000).toLong()
        } else {
            (parts[0].trim().toLong() * 60 * 1000L) + (parts[1].trim().toDouble() * 1000).toLong()
        }
        ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    } catch (t: Throwable) {
        null
    }
}

/**
 * 原文和译文之间的分隔符。
 *
 * 用 ` / ` 而不是换行 —— 换行会让解析器把译文当成"没有时间戳的裸行"丢掉。
 */
private const val TRANSLATION_SEP = " / "
