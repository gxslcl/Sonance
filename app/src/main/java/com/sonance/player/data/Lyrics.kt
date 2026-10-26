package com.sonance.player.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一行带时间轴的歌词 */
data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

/** 歌词内容：可能是带时间轴的（能跟着播滚动），也可能只有纯文本 */
sealed interface Lyrics {
    /** 原始文本，调试/复制用 */
    val raw: String

    /** 带 `[mm:ss.xx]` 时间轴，可跟随播放高亮 */
    data class Synced(override val raw: String, val lines: List<LyricLine>) : Lyrics

    /** 只有纯文本，按行展示 */
    data class Plain(override val raw: String, val lines: List<String>) : Lyrics
}

/** 歌词来源 */
enum class LyricsSource { EMBEDDED, ONLINE }

/** 加载结果：歌词本体 + 来源（[lyrics] 为 null 表示没找到） */
data class LyricsResult(val lyrics: Lyrics?, val source: LyricsSource?)

/**
 * 歌词仓库。
 *
 * 查找顺序（**越靠前越优先，且只有第 3 步会联网**）：
 * 1. **内嵌歌词**（[AudioTags.readLyrics]）—— 随文件走，任何机型任何目录都能读，且免费
 * 2. **本地在线歌词缓存**（[LyricsCache]）—— 之前联网拿到的，落盘了
 * 3. **在线歌词**（[OnlineLyrics]，LrcAPI）—— 仅当用户显式开启，且**只在打开歌词页时**触发
 *
 * 关键约束：不做任何预取 / 批量抓取。一次只为一首歌发一次请求，拿到就落盘，
 * 之后同一首歌永远走第 2 步。
 *
 * 为什么不做同目录 `.lrc`：Android 13+ 拿了 `READ_MEDIA_AUDIO` 也读不到 `.lrc`
 * （它不是音频，不在授权范围内），要靠 SAF 目录授权或 MANAGE_EXTERNAL_STORAGE，
 * 代价和权限弹窗都不划算。
 */
object LyricsRepository {

    /** 命中率很高（同一首歌反复看），用一个小的 LRU 缓存；null 也缓存，避免反复开文件/发请求 */
    private val cache = LruCache<String, LyricsResult>(48)

    suspend fun load(
        context: Context,
        songId: Long,
        uri: Uri,
        title: String = "",
        artist: String = "",
        album: String = "",
        onlineEnabled: Boolean = false,
        apiBase: String = "",
        convertToSimplified: Boolean = true,
        /** 这首歌的时长（毫秒）。**匹配网易云时必须用** —— 见 [NeteaseMatch] 的说明 */
        durationMs: Long = 0L,
        /** 是否允许用网易云兜底（用户开了「网易云补全」才为 true） */
        neteaseEnabled: Boolean = false
    ): LyricsResult {
        // 缓存键带服务地址：换了歌词源就自然走新命名空间，不会读到旧源的结果
        val cacheKey = LyricsCache.keyOf(title, artist, apiBase) ?: "id:$songId"
        cache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            cache.get(cacheKey)?.let { return@withContext it }

            var text: String? = null
            var source: LyricsSource? = null

            // ---- 1) 内嵌（免费且权威）----
            val embedded = runCatching { AudioTags.readLyrics(context, uri) }.getOrNull()
            if (!embedded.isNullOrBlank()) {
                text = embedded
                source = LyricsSource.EMBEDDED
            }

            // ---- 2) 本地在线缓存 ----
            if (source == null) {
                when (val hit = LyricsCache.lookup(context, cacheKey)) {
                    is LyricsCache.Lookup.Hit -> {
                        text = hit.text
                        source = LyricsSource.ONLINE
                    }
                    // 之前查过、确认没有：直接返回"没有"，不再打接口
                    LyricsCache.Lookup.KnownMiss -> {
                        val r = LyricsResult(null, null)
                        cache.put(cacheKey, r)
                        return@withContext r
                    }
                    LyricsCache.Lookup.Unknown -> Unit
                }
            }

            // ---- 3) 在线（用户开启时才走，一次只请求这一首）----
            if (source == null && onlineEnabled && apiBase.isNotBlank()) {
                // 先问 LrcAPI
                var online = OnlineLyrics.fetch(apiBase, title, artist, album)

                // 没拿到就用网易云兜底（用户开了「网易云补全」时）。
                // 两个源互补：实测 LrcAPI 偶发没有的歌，网易云那边是有的。
                //
                // 匹配带**时长校验**很关键 —— 网易云搜"晴天"第一条是「晴天(深情版)」，
                // 直接取第一条会拿到完全不同版本的歌词、时间轴整体错位。
                //
                // LrcAPI 是纯 LRC 文本、永远没有翻译；拿到了歌词但**不含译文**
                // 时也要再问一次网易云 —— 它的歌词接口带 tlyric（逐行中文翻译），
                // 拿得到带翻译的版本就整体替换。否则外文歌永远只有原文。
                if (neteaseEnabled && (online.isNullOrBlank() || !online.contains(TRANSLATION_SEP))) {
                    val withTr = NeteaseSource.findTrackFor(title, artist, durationMs)
                        ?.let { NeteaseSource.lyrics(it.remoteId) }
                    if (!withTr.isNullOrBlank() &&
                        (online.isNullOrBlank() || withTr.contains(TRANSLATION_SEP))
                    ) {
                        online = withTr
                    }
                }

                if (!online.isNullOrBlank()) {
                    text = online
                    source = LyricsSource.ONLINE
                }
                // 不管有没有拿到都落盘：拿到存歌词，没拿到存空文件当哨兵，
                // 下次同一首歌直接走第 2 步，不会反复打接口（避免被限流）。
                // ⚠️ 必须**两个源都试完**才写哨兵 —— 提前写会把网易云那条路也短路掉。
                LyricsCache.store(context, cacheKey, text)
            }

            // 繁转简只作用于**展示**：缓存里存的仍是原始文本，
            // 所以开关关掉后重新打开歌词页立刻就是原文，不需要再联网。
            val shown = text?.let {
                if (convertToSimplified) ChineseConverter.toSimplified(context, it) else it
            }
            val parsed = shown?.takeIf { it.isNotBlank() }?.let { parse(it) }
            val result = LyricsResult(parsed, if (parsed != null) source else null)
            cache.put(cacheKey, result)
            result
        }
    }

    /**
     * 用**用户手填的歌名 / 歌手**重新联网搜一次（自动匹配到错误歌词时的补救入口）。
     *
     * 与 [load] 的三步流程不同：这里跳过内嵌歌词和旧缓存，直接用新信息打接口。
     *
     * 两个刻意的设计：
     * - **失败时不写 KnownMiss 哨兵**。手填本身就是试错过程，写哨兵会让下一次
     *   同样关键词直接短路成"没找到"，用户改个错别字重试就再也搜不到了。
     * - **成功时按新的 title+artist 落盘**，于是这首歌下次打开会直接命中缓存，
     *   等于把"正确的搜索方式"记住了。
     */
    suspend fun searchOnline(
        context: Context,
        title: String,
        artist: String,
        album: String,
        apiBase: String,
        convertToSimplified: Boolean = true
    ): LyricsResult = withContext(Dispatchers.IO) {
        val key = LyricsCache.keyOf(title, artist, apiBase)
            ?: "manual:${title.lowercase()}|${artist.lowercase()}"
        val online = OnlineLyrics.fetch(apiBase, title, artist, album)

        if (online.isNullOrBlank()) {
            val miss = LyricsResult(null, null)
            // 不落盘、不写哨兵，只放内存缓存（避免同一次会话里重发请求）
            cache.put(key, miss)
            return@withContext miss
        }

        LyricsCache.store(context, key, online)
        val shown = if (convertToSimplified) ChineseConverter.toSimplified(context, online) else online
        val parsed = shown.takeIf { it.isNotBlank() }?.let { parse(it) }
        val result = LyricsResult(parsed, if (parsed != null) LyricsSource.ONLINE else null)
        cache.put(key, result)
        result
    }

    /** 忘掉这一首的在线歌词缓存（拿到错误歌词后先清掉，再重新搜） */
    fun forget(context: Context, title: String, artist: String, apiBase: String) {
        val key = LyricsCache.keyOf(title, artist, apiBase) ?: return
        cache.remove(key)
        LyricsCache.remove(context, key)
    }

    fun clear() = cache.evictAll()

    /**
     * 解析歌词文本。
     *
     * 优先按 LRC 解析（内嵌歌词里塞 LRC 非常常见）；一行都没有时间轴就退化成纯文本。
     * 支持：
     * - `[mm:ss.xx]` / `[mm:ss]` / `[mm:ss.xxx]`
     * - 一行多个时间标签 `[00:12.34][01:20.00]词`
     * - `[offset:+/-毫秒]` 全局偏移
     * - 忽略 `[ti:]` `[ar:]` `[al:]` `[by:]` 等元信息标签
     */
    fun parse(text: String): Lyrics? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalized.split('\n')
        val out = ArrayList<LyricLine>(rawLines.size)
        var offsetMs = 0L
        var sawTimeTag = false

        for (rawLine in rawLines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            var i = 0
            val times = ArrayList<Long>(2)
            var textStart = 0
            var onlyMeta = false

            // 逐个吃掉行首的 [...] 标签
            while (i < line.length && line[i] == '[') {
                val close = line.indexOf(']', i)
                if (close < 0) break
                val tag = line.substring(i + 1, close)
                val t = parseTimeTag(tag)
                if (t != null) {
                    times.add(t)
                    sawTimeTag = true
                } else if (tag.contains(':')) {
                    // [ti:] / [ar:] / [al:] / [by:] / [offset:]
                    val key = tag.substringBefore(':').trim().lowercase()
                    if (key == "offset") {
                        offsetMs = tag.substringAfter(':').trim().toLongOrNull() ?: offsetMs
                    }
                    onlyMeta = true
                } else {
                    break
                }
                i = close + 1
                textStart = i
            }

            val content = line.substring(textStart).trim()
            if (times.isEmpty()) {
                // 没有时间标签：纯文本行（onlyMeta 的行是元信息，丢掉）
                if (!onlyMeta && content.isNotEmpty()) {
                    val (main, tr) = splitTranslation(content)
                    out.add(LyricLine(-1L, main, tr))
                }
                continue
            }
            if (content.isEmpty()) continue
            val (main, tr) = splitTranslation(content)
            for (t in times) out.add(LyricLine((t + offsetMs).coerceAtLeast(0L), main, tr))
        }

        if (out.isEmpty()) return null

        return if (sawTimeTag) {
            val synced = out.filter { it.timeMs >= 0L }.sortedBy { it.timeMs }
            if (synced.size >= 2) Lyrics.Synced(text, synced) else Lyrics.Plain(text, plainLines(out))
        } else {
            Lyrics.Plain(text, plainLines(out))
        }
    }

    /**
     * 把「原文 / 译文」拆开（[TRANSLATION_SEP] 是网易云 tlyric 合并时写入的）。
     * 只在**第一个**分隔符处拆：译文自己再含斜杠时，剩下的整段都归译文。
     * 拆出来任何一边为空都不算翻译行，保持原文原样。
     */
    private fun splitTranslation(content: String): Pair<String, String?> {
        val sep = content.indexOf(TRANSLATION_SEP)
        if (sep <= 0 || sep + TRANSLATION_SEP.length >= content.length) return content to null
        val main = content.substring(0, sep).trim()
        val tr = content.substring(sep + TRANSLATION_SEP.length).trim()
        if (main.isEmpty() || tr.isEmpty()) return content to null
        return main to tr
    }

    private fun plainLines(lines: List<LyricLine>): List<String> = lines.map { it.text }

    /** `mm:ss`、`mm:ss.xx`、`mm:ss.xxx`、`hh:mm:ss` 都能吃 */
    private fun parseTimeTag(tag: String): Long? {
        val parts = tag.split(':')
        if (parts.size < 2 || parts.size > 3) return null
        return try {
            if (parts.size == 3) {
                val h = parts[0].trim().toLong()
                val m = parts[1].trim().toLong()
                val s = parts[2].trim().toDouble()
                ((h * 3600 + m * 60) * 1000L) + (s * 1000).toLong()
            } else {
                val m = parts[0].trim().toLong()
                val s = parts[1].trim().toDouble()
                (m * 60 * 1000L) + (s * 1000).toLong()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/**
 * 在已排序的歌词里二分找"当前应该高亮哪一行"。
 * 传入 [positionMs]，返回最后一个 `timeMs <= positionMs` 的下标；都没有就返回 -1。
 */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    if (isEmpty()) return -1
    var lo = 0
    var hi = size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timeMs <= positionMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}
