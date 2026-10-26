package com.sonance.player.data.netease

/**
 * 一条网易云缓存按**文件头魔数**分出的类别。
 *
 * 网易云的缓存目录里什么都有：真正的 NCM 加密容器、没有加密的明文音频
 * （老版本直接把 mp3/m4a 分片堆在 cache 里）、以及大量与音乐无关的
 * json / 图片 / 数据库文件。分类的目的就是把第三类挡在门外。
 */
enum class NcKind {
    /** 整文件 NCM 容器（魔数 CTENFDAM），走私有格式还原的解密管线 */
    NCM,

    /** 现行播放缓存 `.nmsf`：明文音频整体 XOR 0xA3，同样走解密管线 */
    NMS,

    /** 明文音频，直接复制到输出文件夹即可 */
    PLAIN,

    /** 认不出来的魔数（json、图片、数据库、残片…），扫描结果里直接丢弃 */
    UNKNOWN
}

/** 扫描到的单个缓存文件。分片场景下它只是组员，还不是可转换的条目 */
data class NcFile(
    val path: String,
    val size: Long,
    val kind: NcKind,
    /** [NcKind.PLAIN] 时的后缀（带点）；其余为空串 */
    val plainExt: String
)

/**
 * 一条可处理的缓存条目：要么是单个完整文件，要么是同目录下
 * 同名分片的组合（按片号顺序拼回一个完整音频）。
 *
 * 网易云明文缓存的典型形态是 `<哈希>-0.m4a / <哈希>-1.m4a / …`，
 * 单片叫什么无从知晓（哈希名），所以 [displayName] 大多是哈希 stem；
 * NCM 文件名通常是真名（元数据要解密后才读得出来，列表阶段先用文件名）。
 */
data class NeteaseCacheEntry(
    /** 稳定主键：单文件用文件路径；分片组用第一片的路径 */
    val key: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: NcKind,
    /** [NcKind.PLAIN] 的后缀（带点），由第一片 / 单文件的魔数决定 */
    val plainExt: String,
    /** 按片号升序的绝对路径；单文件时只有一个元素 */
    val fragments: List<String>,
    /** 参与本条的分片个数（1 = 完整单文件） */
    val fragmentCount: Int,
    /**
     * 疑似试听残曲（嗅探阶段按**文件大小**预判，最终裁决在转换时按时长做，
     * 阈值见 [NcDuration.TRIAL_MIN_SECONDS] = 150 秒）。
     *
     * 网易云按 320KB 固定块缓存，没播完 / 只试听的歌只留 1~4 块：
     * 真机实测残曲全部落在 320,00x 与 1,280,0xx 字节两档，
     * 完整歌曲都 ≥ 2.5MB。预判阈值取 150s × 128kbps ≈ 2.4MB ——
     * 覆盖全部实测残曲档位，完整歌（≥2.5MB）不会被误标。
     * 只影响展示，不拦截转换（低码率短歌由时长裁决兜底）。
     */
    val suspectTrial: Boolean = false
) {
    val isFragmentGroup: Boolean get() = fragments.size > 1

    val typeLabel: String
        get() = buildString {
            if (isFragmentGroup) append("分片 ×$fragmentCount · ")
            when (kind) {
                NcKind.NCM -> append("NCM 加密")
                NcKind.NMS -> append("NMSF 加密")
                NcKind.PLAIN -> append(plainExt.removePrefix(".").uppercase() + " 明文")
                NcKind.UNKNOWN -> append("无法识别")
            }
            if (suspectTrial) append(" · 疑似残曲")
        }
}

/**
 * 网易云缓存扫描结果的**纯解析层**。
 *
 * 全部函数不碰 Android / shell / 文件系统，JVM 上可以直接单测 ——
 * 缓存目录的真实形态见不着（没有实机），只能靠这里把各种可能都钉死：
 * 整文件 NCM、明文整文件、明文分片组、混入的杂文件、空结果、
 * 路径带空格引号、magic 读取失败……
 */
object NeteaseCacheParser {

    /** NCM 容器的 8 字节魔数："CTENFDAM" */
    private val NCM_MAGIC = byteArrayOf(
        0x43, 0x54, 0x45, 0x4E, 0x46, 0x44, 0x41, 0x4D
    )

    /** 片号后缀：`<前缀>-<纯数字>`。限 1~5 位，避免把「歌名-2020」这种真名误当分片 */
    private val FRAGMENT_SUFFIX = Regex("""^(.*)-([0-9]{1,5})$""")

    /**
     * 按头部字节判类别。
     *
     * 只认可靠的容器签名（与 [com.sonance.player.data.AudioDecoder.sniffExtension]
     * 同一套判断标准，但作用在**加密前的明文头**上），认不出就返回 UNKNOWN，
     * 宁可漏掉也不把 json / 图片当成音频塞给用户。
     */
    fun classify(header: ByteArray): Pair<NcKind, String> {
        if (header.size >= 8 && header.copyOfRange(0, 8).contentEquals(NCM_MAGIC)) {
            return NcKind.NCM to ""
        }
        // .nmsf：网易云现行播放缓存，明文音频整体 XOR 0xA3（判定逻辑见 NmsDecoder）
        if (com.sonance.player.data.NmsDecoder.looksLike(header)) {
            return NcKind.NMS to ""
        }
        val b0 = header.getOrNull(0)?.toInt()?.and(0xFF) ?: return NcKind.UNKNOWN to ""
        val b1 = header.getOrNull(1)?.toInt()?.and(0xFF) ?: return NcKind.UNKNOWN to ""
        val b2 = header.getOrNull(2)?.toInt()?.and(0xFF)
        val b3 = header.getOrNull(3)?.toInt()?.and(0xFF)

        // ID3 —— mp3 家族
        if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return NcKind.PLAIN to ".mp3"
        // MPEG 帧同步
        if (b0 == 0xFF && b1 and 0xE0 == 0xE0) return NcKind.PLAIN to ".mp3"
        // fLaC
        if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return NcKind.PLAIN to ".flac"
        // OggS
        if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return NcKind.PLAIN to ".ogg"
        // RIFF (wav)
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return NcKind.PLAIN to ".wav"
        // ftyp（第 4~7 字节）—— m4a
        val b4 = header.getOrNull(4)?.toInt()?.and(0xFF)
        val b5 = header.getOrNull(5)?.toInt()?.and(0xFF)
        val b6 = header.getOrNull(6)?.toInt()?.and(0xFF)
        val b7 = header.getOrNull(7)?.toInt()?.and(0xFF)
        if (b4 == 0x66 && b5 == 0x74 && b6 == 0x79 && b7 == 0x70) return NcKind.PLAIN to ".m4a"

        return NcKind.UNKNOWN to ""
    }

    /** `od -An -tx1 -N8 | tr -d ' \n'` 产出的 hex 串还原成字节；异常输入返回空数组 */
    fun hexToBytes(hex: String): ByteArray {
        val s = hex.lowercase().replace(Regex("""\s+"""), "")
        if (s.length < 2 || s.length % 2 != 0 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return ByteArray(0)
        }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    /**
     * 解析扫描脚本的原始输出。
     *
     * 每行约定：`<大小>\t<路径>\t<头 8 字节 hex>`。
     * 路径放在中间列、hex 放最后，是为了解析时按 tab split 后
     * **路径里混入空格 / 单引号都不会错位**；hex 列可能为空
     * （od 失败），此时按 UNKNOWN 处理。大小非正、格式不对的行直接丢弃。
     */
    fun parseScan(raw: String): List<NcFile> {
        val out = LinkedHashMap<String, NcFile>()
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size < 3) return@forEach
            val size = parts[0].trim().toLongOrNull() ?: return@forEach
            val path = parts[1]
            if (path.isBlank() || size <= 0) return@forEach
            if (out.containsKey(path)) return@forEach
            val (kind, ext) = classify(hexToBytes(parts[2]))
            out[path] = NcFile(path, size, kind, ext)
        }
        return out.values.toList()
    }

    /**
     * 嗅探阶段的残曲预判（见 [NeteaseCacheEntry.suspectTrial]）。
     *
     * 阈值 = 150 秒 × 128kbps ÷ 8 ≈ 2.4MB（128kbps 是网易云缓存的最低常见码率）：
     * 真机实测残曲（≤1.28MB）全覆盖，完整歌（≥2.5MB）不误标。
     * 最终裁决在转换时按时长做（150 秒），这里只影响展示。
     */
    fun isSuspectTrial(sizeBytes: Long): Boolean = sizeBytes < 2_400_000

    /**
     * 把散文件归并成可转换的条目。
     *
     * 分片识别：文件名（去扩展名后）匹配 `<前缀>-<数字>`，且**同一目录下**
     * 同前缀的凑够 2 个以上才算一组 —— 只有单片说明只是个恰好带连字符数字的
     * 正常文件，照单文件处理。组内按片号数值排序（不是字典序，`-10` 要排在
     * `-2` 后面），组的类别由第一片决定，第一片认不出来就整组丢弃。
     */
    fun buildEntries(files: List<NcFile>): List<NeteaseCacheEntry> {
        data class Frag(
            val parent: String,
            val prefix: String,
            val index: Int,
            val file: NcFile
        )

        val fragmentCandidates = mutableListOf<Frag>()
        val singles = mutableListOf<NeteaseCacheEntry>()

        for (f in files) {
            if (f.kind == NcKind.UNKNOWN) continue
            val name = f.path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val m = FRAGMENT_SUFFIX.matchEntire(stem)
            if (m == null) {
                singles += singleEntry(f)
            } else {
                fragmentCandidates += Frag(
                    parent = f.path.substringBeforeLast('/', ""),
                    prefix = m.groupValues[1],
                    index = m.groupValues[2].toInt(),
                    file = f
                )
            }
        }

        val result = mutableListOf<NeteaseCacheEntry>()
        for ((_, frags) in fragmentCandidates.groupBy { "${it.parent}\u0000${it.prefix}" }) {
            if (frags.size >= 2) {
                val ordered = frags.sortedBy { it.index }
                val first = ordered.first()
                if (first.file.kind == NcKind.UNKNOWN) continue
                val size = ordered.sumOf { it.file.size }
                result += NeteaseCacheEntry(
                    key = first.file.path,
                    displayName = first.prefix.ifBlank { "未命名缓存" },
                    sizeBytes = size,
                    kind = first.file.kind,
                    plainExt = first.file.plainExt,
                    fragments = ordered.map { it.file.path },
                    fragmentCount = ordered.size,
                    suspectTrial = isSuspectTrial(size)
                )
            } else {
                result += singleEntry(frags.first().file)
            }
        }
        result += singles
        return result.sortedByDescending { it.sizeBytes }
    }

    /** 扫描原文 → 条目列表，一步到位 */
    fun buildEntriesFromScan(raw: String): List<NeteaseCacheEntry> = buildEntries(parseScan(raw))

    /**
     * 从 NMSF 条目里提取**网易云歌曲 ID**。
     *
     * NMSF 缓存文件名是 `<歌曲id>_<码率>_<md5>_<分片>.nmsf`（真机实测：
     * `422094253_256003_<md5>_0.nmsf`），第一段就是歌曲 ID —— 它是转换后
     * 在线补全歌名 / 歌手 / 专辑 / 封面的唯一线索。
     *
     * 只认 [NcKind.NMS]：NCM 自带元数据不需要查，明文分片的文件名是
     * 哈希或真名，纯数字开头的情况极罕见，宁可不补也不能张冠李戴。
     * 第一段不是 5~12 位纯数字（网易云歌曲 ID 的实际范围）返回 null。
     */
    fun songIdOf(entry: NeteaseCacheEntry): String? {
        if (entry.kind != NcKind.NMS) return null
        val head = entry.displayName.substringBefore('_')
        return if (head.matches(Regex("""\d{5,12}"""))) head else null
    }

    private fun singleEntry(f: NcFile): NeteaseCacheEntry {
        val name = f.path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return NeteaseCacheEntry(
            key = f.path,
            displayName = stem.ifBlank { "未命名缓存" },
            sizeBytes = f.size,
            kind = f.kind,
            plainExt = f.plainExt,
            fragments = listOf(f.path),
            fragmentCount = 1,
            suspectTrial = isSuspectTrial(f.size)
        )
    }
}

/**
 * 条目的本地序列化（一行一条）。
 *
 * 用途与 B 站模块一致：把上次扫描结果存下来，进设置页先显示、
 * 用户点了扫描再刷新。特意不用 org.json —— 这段要进本地单测，
 * android.jar 的桩实现跑不了。字段分隔 \t，分片路径之间用 \u0001
 * （正常路径里不会出现的控制字符）。
 */
object NeteaseCacheCodec {

    private const val FRAG_SEP = "\u0001"

    private const val FIELD_SEP = "\t"

    fun toLine(e: NeteaseCacheEntry): String = listOf(
        e.key,
        e.displayName,
        e.sizeBytes.toString(),
        e.kind.name,
        e.plainExt,
        e.fragments.joinToString(FRAG_SEP),
        if (e.suspectTrial) "1" else "0"
    ).joinToString(FIELD_SEP)

    fun fromLine(line: String): NeteaseCacheEntry? {
        val p = line.split(FIELD_SEP)
        if (p.size < 6) return null
        val kind = runCatching { NcKind.valueOf(p[3]) }.getOrNull() ?: return null
        val size = p[2].toLongOrNull() ?: return null
        val fragments = p[5].split(FRAG_SEP).filter { it.isNotBlank() }
        if (fragments.isEmpty() || p[0].isBlank()) return null
        return NeteaseCacheEntry(
            key = p[0],
            displayName = p[1],
            sizeBytes = size,
            kind = kind,
            plainExt = p[4],
            fragments = fragments,
            fragmentCount = fragments.size,
            // 旧版缓存文件没有第 7 列，按非残曲处理（转换时还有时长裁决兜底）
            suspectTrial = p.getOrNull(6) == "1"
        )
    }
}
