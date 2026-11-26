package com.sonance.player.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 逐字/逐词片段。
 *
 * 来自**增强型 LRC**（一行内多个 `<mm:ss.xxx>` 标签），每个标签后面那段文字
 * 归它自己的时间。普通 LRC 没有这个结构，[LyricLine.segments] 为 null。
 */
data class LyricSegment(val timeMs: Long, val text: String)

/** 一行带时间轴的歌词 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    /**
     * 增强型 LRC 的逐字片段；普通 LRC 为 null。
     *
     * 有值时 [text] 是各片段拼起来的完整正文（时间标签已剥掉），渲染层可以按
     * 片段逐字高亮；没有值时退化为整行高亮。
     */
    val segments: List<LyricSegment>? = null
)

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
enum class LyricsSource { EMBEDDED, ONLINE, IMPORTED }

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

    /** 命中率很高（同一首歌反复看），用一个小的 LRU 缓存；null 仅在明确无歌词或联网确认后缓存 */
    private val cache = LruCache<String, LyricsResult>(48)

    /** 合并同一首歌并发进入的在线/本地加载，避免 UI 与后台服务重复请求 */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLoads = ConcurrentHashMap<String, Deferred<LyricsResult>>()

    /** 导入 / 删除歌词后自增，通知单行歌词条等其它订阅方即时刷新 */
    val revision = MutableStateFlow(0L)

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
        neteaseEnabled: Boolean = false,
        /** 在线曲目的来源标识（如 `"lx:kw"`），用于直取洛雪对应平台官方歌词 */
        remoteSource: String = "",
        /** 在线曲目的远端 id */
        remoteId: String? = null
    ): LyricsResult {
        val lxPlatform = com.sonance.player.data.lx.LxPlatform.platformIdFromSource(remoteSource)
        // 缓存键带服务地址（洛雪平台曲目隔离独立命名空间，避免被本地 LrcAPI 哨兵短路）
        val effectiveBase = if (lxPlatform != null) "$apiBase|$remoteSource" else apiBase
        val cacheKey = LyricsCache.keyOf(title, artist, effectiveBase) ?: "id:$songId"
        val rev = revision.value
        val scopedKey = "$cacheKey|id:$songId|t2s:$convertToSimplified|rev:$rev"
        cache.get(scopedKey)?.let { return it }

        val inFlightKey = "$scopedKey|on:$onlineEnabled|ne:$neteaseEnabled"
        val existing = inFlightLoads[inFlightKey]
        val deferred = if (existing != null) {
            existing
        } else {
            val appCtx = context.applicationContext
            val created = repoScope.async {
                loadInternal(
                    context = appCtx,
                    songId = songId,
                    uri = uri,
                    title = title,
                    artist = artist,
                    album = album,
                    onlineEnabled = onlineEnabled,
                    apiBase = apiBase,
                    convertToSimplified = convertToSimplified,
                    durationMs = durationMs,
                    neteaseEnabled = neteaseEnabled,
                    remoteSource = remoteSource,
                    remoteId = remoteId,
                    lxPlatform = lxPlatform,
                    cacheKey = cacheKey,
                    scopedKey = scopedKey
                )
            }
            val prev = inFlightLoads.putIfAbsent(inFlightKey, created)
            if (prev != null) {
                created.cancel()
                prev
            } else {
                created.invokeOnCompletion { inFlightLoads.remove(inFlightKey, created) }
                created
            }
        }
        return deferred.await()
    }

    private suspend fun loadInternal(
        context: Context,
        songId: Long,
        uri: Uri,
        title: String,
        artist: String,
        album: String,
        onlineEnabled: Boolean,
        apiBase: String,
        convertToSimplified: Boolean,
        durationMs: Long,
        neteaseEnabled: Boolean,
        remoteSource: String,
        remoteId: String?,
        lxPlatform: String?,
        cacheKey: String,
        scopedKey: String
    ): LyricsResult = withContext(Dispatchers.IO) {
        cache.get(scopedKey)?.let { return@withContext it }

        var text: String? = null
        var source: LyricsSource? = null
        var authoritativeMiss = false

        // ---- 0) 用户手动导入 / 显式删除覆盖（优先级最高）----
        when (val ov = LyricsCache.lookupOverride(context, songId)) {
            is LyricsCache.Override.Deleted -> {
                val deleted = LyricsResult(null, null)
                cache.put(scopedKey, deleted)
                return@withContext deleted
            }
            is LyricsCache.Override.Imported -> {
                text = ov.text
                source = LyricsSource.IMPORTED
            }
            LyricsCache.Override.None -> Unit
        }

        // ---- 1) 内嵌与同目录侧车歌词（免费且权威）----
        if (source == null) {
            val embedded = runCatching { AudioTags.readLyrics(context, uri) }.getOrNull()
            if (!embedded.isNullOrBlank()) {
                text = embedded
                source = LyricsSource.EMBEDDED
            } else if (!isRemoteUri(uri)) {
                val sidecar = runCatching {
                    SidecarAssetResolver.resolveLyrics(context, uri, title)
                }.getOrNull()
                if (!sidecar.isNullOrBlank()) {
                    text = sidecar
                    source = LyricsSource.EMBEDDED
                }
            }
        }

        // ---- 2) 本地在线缓存 ----
        var upgradedFromLegacyCache = false
        if (source == null) {
            val primary = LyricsCache.lookup(context, cacheKey)
            val aliasKey = "id:$songId"
            val alias = if (cacheKey != aliasKey) LyricsCache.lookup(context, aliasKey) else null
            when {
                primary is LyricsCache.Lookup.Hit -> {
                    text = primary.text
                    source = LyricsSource.ONLINE
                }
                alias is LyricsCache.Lookup.Hit -> {
                    text = alias.text
                    source = LyricsSource.ONLINE
                    LyricsCache.store(context, cacheKey, alias.text)
                }
                primary is LyricsCache.Lookup.KnownMiss -> {
                    val r = LyricsResult(null, null)
                    cache.put(scopedKey, r)
                    return@withContext r
                }
                else -> Unit
            }
            // 旧缓存只有逐行 LRC 且未含 `<mm:ss.xxx>` 逐字时间轴时，按需尝试一次升级网易云 yrc 逐字歌词
            val curText = text
            if (source == LyricsSource.ONLINE &&
                curText != null &&
                !hasWordTimingTag(curText) &&
                onlineEnabled &&
                neteaseEnabled &&
                yrcUpgradeAttempted.add(cacheKey)
            ) {
                val upgraded = fetchFromNeteaseDirectOrSearch(remoteSource, remoteId, title, artist, durationMs)
                if (!upgraded.isNullOrBlank() &&
                    (hasWordTimingTag(upgraded) || (!curText.contains(TRANSLATION_SEP) && upgraded.contains(TRANSLATION_SEP)))
                ) {
                    text = upgraded
                    upgradedFromLegacyCache = true
                }
            }
        }

        if (upgradedFromLegacyCache && !text.isNullOrBlank()) {
            LyricsCache.store(context, cacheKey, text)
            if (cacheKey != "id:$songId") LyricsCache.store(context, "id:$songId", text)
        }

        // ---- 3) 在线（用户开启时才走，一次只请求这一首）----
        val canFetchLx = onlineEnabled && lxPlatform != null && !remoteId.isNullOrBlank()
        if (source == null && onlineEnabled && (apiBase.isNotBlank() || canFetchLx || (neteaseEnabled && remoteSource == NeteaseSource.id && !remoteId.isNullOrBlank()))) {
            authoritativeMiss = true
            var online: String? = null
            if (remoteSource == NeteaseSource.id && !remoteId.isNullOrBlank()) {
                online = NeteaseSource.lyrics(remoteId)
            }
            if (online.isNullOrBlank() && canFetchLx) {
                online = com.sonance.player.data.lx.LxCatalogProvider.fetchLyric(
                    platform = lxPlatform!!,
                    remoteId = remoteId!!,
                    title = title,
                    artist = artist,
                    durationMs = durationMs
                )
            }
            if (online.isNullOrBlank() && apiBase.isNotBlank()) {
                // 洛雪平台无结果或普通曲目，走 LrcAPI
                online = OnlineLyrics.fetch(apiBase, title, artist, album)
            }

            // 没拿到、缺译文或缺少逐字时间轴时，走网易云 yrc/tlyric 补全
            if (neteaseEnabled && (online.isNullOrBlank() || !hasWordTimingTag(online) || !online.contains(TRANSLATION_SEP))) {
                val withTr = fetchFromNeteaseDirectOrSearch(remoteSource, remoteId, title, artist, durationMs)
                if (!withTr.isNullOrBlank() &&
                    (online.isNullOrBlank() ||
                        hasWordTimingTag(withTr) ||
                        (!online.contains(TRANSLATION_SEP) && withTr.contains(TRANSLATION_SEP)))
                ) {
                    online = withTr
                }
            }

            if (!online.isNullOrBlank()) {
                text = online
                source = LyricsSource.ONLINE
            }
            LyricsCache.store(context, cacheKey, text)
            if (!text.isNullOrBlank() && cacheKey != "id:$songId") {
                LyricsCache.store(context, "id:$songId", text)
            }
        }

        // 繁转简只作用于**展示**：缓存里存的仍是原始文本，
        // 所以开关关掉后重新打开歌词页立刻就是原文，不需要再联网。
        val shown = text?.let {
            if (convertToSimplified) ChineseConverter.toSimplified(context, it) else it
        }
        val parsed = shown?.takeIf { it.isNotBlank() }?.let { parse(it) }
        val result = LyricsResult(parsed, if (parsed != null) source else null)
        // 仅联网/权威确认的结果才写内存 LRU，避免 offline 探测 miss 短路后续的在线加载
        if (parsed != null || authoritativeMiss) {
            cache.put(scopedKey, result)
        }
        result
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
        convertToSimplified: Boolean = true,
        /**
         * 歌曲**原始**元数据（不是用户手填的内容）。成功后按它再落一份缓存，
         * 下次进入歌词页用原始元数据算 key 才能命中。
         */
        originTitle: String = "",
        originArtist: String = "",
        originSongId: Long = 0L
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
        // 用户手填的歌名/歌手常常和媒体标签不一样（改错别字、去掉「(Live)」后缀）。
        // 只按手填内容存，下次 load 用原始元数据算 key 永远命中不了 —— 表现就是
        // 「这次搜到了，关掉 App 再进来又没了」。这里按原始元数据和 songId 各补一份。
        val originKey = LyricsCache.keyOf(originTitle, originArtist, apiBase)
        if (originKey != null && originKey != key) {
            LyricsCache.store(context, originKey, online)
        }
        if (originSongId != 0L) {
            LyricsCache.clearOverride(context, originSongId)
            LyricsCache.store(context, "id:$originSongId", online)
        }
        val shown = if (convertToSimplified) ChineseConverter.toSimplified(context, online) else online
        val parsed = shown.takeIf { it.isNotBlank() }?.let { parse(it) }
        val result = LyricsResult(parsed, if (parsed != null) LyricsSource.ONLINE else null)
        cache.put(key, result)
        if (originKey != null && originKey != key) {
            cache.put(originKey, result)
            cache.put("$originKey|id:$originSongId", result)
        }
        if (originSongId != 0L) cache.put("id:$originSongId", result)
        revision.value++
        result
    }

    /** 手动导入歌词文本（支持外部 .lrc/.txt 文件或粘贴），优先于内嵌与在线歌词 */
    suspend fun importManual(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        apiBase: String,
        rawText: String,
        convertToSimplified: Boolean = true
    ): LyricsResult = withContext(Dispatchers.IO) {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        val shown = if (convertToSimplified && cleaned.isNotEmpty()) {
            ChineseConverter.toSimplified(context, cleaned)
        } else {
            cleaned
        }
        val parsed = shown.takeIf { it.isNotBlank() }?.let { parse(it) }
            ?: return@withContext LyricsResult(null, null)

        LyricsCache.saveImported(context, songId, cleaned)
        val result = LyricsResult(parsed, LyricsSource.IMPORTED)
        val key = LyricsCache.keyOf(title, artist, apiBase) ?: "id:$songId"
        cache.put("$key|id:$songId", result)
        cache.put(key, result)
        if (songId != 0L) cache.put("id:$songId", result)
        revision.value++
        result
    }

    /** 删除当前歌曲的歌词（清除缓存并记录删除标记，防止切回时再次自动显示旧内嵌/旧缓存歌词） */
    suspend fun deleteLyrics(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        apiBase: String
    ) = withContext(Dispatchers.IO) {
        val key = LyricsCache.keyOf(title, artist, apiBase)
        if (key != null) {
            cache.remove(key)
            cache.remove("$key|id:$songId")
            LyricsCache.remove(context, key)
        }
        if (songId != 0L) {
            val idKey = "id:$songId"
            cache.remove(idKey)
            cache.remove("$idKey|id:$songId")
            LyricsCache.remove(context, idKey)
            LyricsCache.markDeleted(context, songId)
        }
        revision.value++
    }

    /**
     * 解码外部 `.lrc` / `.txt` 字节流：先按严格 UTF-8（跳过 BOM）校验，
     * 遇到国内常见老式 GBK / GB18030 编码自动回退到 GB18030，避免中文歌词乱码。
     */
    fun decodeLyricsBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val hasUtf8Bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        if (hasUtf8Bom) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Throwable) {
            runCatching { String(bytes, Charset.forName("GB18030")) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }
    }

    /** 忘掉这一首的在线歌词缓存（拿到错误歌词后先清掉，再重新搜） */
    fun forget(context: Context, title: String, artist: String, apiBase: String) {
        val key = LyricsCache.keyOf(title, artist, apiBase) ?: return
        cache.remove(key)
        LyricsCache.remove(context, key)
    }

    private val yrcUpgradeAttempted = ConcurrentHashMap.newKeySet<String>()

    private fun hasWordTimingTag(text: String): Boolean =
        INLINE_TIME_TAG.containsMatchIn(text) || looksLikeYrc(text)

    private suspend fun fetchFromNeteaseDirectOrSearch(
        remoteSource: String,
        remoteId: String?,
        title: String,
        artist: String,
        durationMs: Long
    ): String? {
        if (remoteSource == NeteaseSource.id && !remoteId.isNullOrBlank()) {
            NeteaseSource.lyrics(remoteId)?.let { return it }
        }
        return NeteaseSource.findTrackFor(title, artist, durationMs)?.let {
            NeteaseSource.lyrics(it.remoteId)
        }
    }

    fun clear() {
        cache.evictAll()
        yrcUpgradeAttempted.clear()
        SidecarAssetResolver.clear()
    }

    fun parse(text: String): Lyrics? {
        val trimmedBom = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (looksLikeTtml(trimmedBom)) {
            parseTtml(trimmedBom, text)?.let { return it }
        }
        val normalized = if (looksLikeYrc(trimmedBom)) {
            convertYrcToEnhancedLrc(trimmedBom) ?: trimmedBom
        } else {
            trimmedBom
        }
        val rawLines = normalized.split('\n')
        val out = ArrayList<LyricLine>(rawLines.size)
        var offsetMs = 0L
        var sawTimeTag = false

        for (rawLine in rawLines) {
            // `[!text]` 是部分歌词接口给「无时间轴的纯文本」打的标记，不是歌词内容。
            val line = rawLine.trim().removePrefix("[!text]").trim()
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
                // 没有行级时间标签：可能是纯文本行，也可能是只有行内 `<...>` 的增强型 LRC
                if (!onlyMeta && content.isNotEmpty()) {
                    val (main, tr) = splitTranslation(content)
                    val (clean, segments) = extractSegments(main, offsetMs)
                    if (segments != null) {
                        // 整行没有 [..] 但有逐字标签：行级时间取第一个片段
                        sawTimeTag = true
                        out.add(LyricLine(segments.first().timeMs, clean, tr, segments))
                    } else if (clean.isNotEmpty()) {
                        out.add(LyricLine(-1L, clean, tr))
                    }
                }
                continue
            }
            if (content.isEmpty()) continue
            val (main, tr) = splitTranslation(content)
            val lineTime = (times.first() + offsetMs).coerceAtLeast(0L)
            val (clean, segments) = extractSegments(main, offsetMs, lineTime)
            if (clean.isEmpty()) continue
            for (t in times) {
                out.add(
                    LyricLine(
                        timeMs = (t + offsetMs).coerceAtLeast(0L),
                        text = clean,
                        translation = tr,
                        segments = segments
                    )
                )
            }
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

    /**
     * 增强型 LRC 的行内逐字时间标签，如 `<00:27.720>`。
     * 比行级 `[..]` 更严格：必须有「分:秒」结构，避免把歌词里的普通尖括号当标签。
     */
    private val INLINE_TIME_TAG = Regex("<(\\d{1,4}):(\\d{1,2}(?:\\.\\d{1,3})?)>")

    /**
     * 「时间戳样子」的残留标签。解析失败（比如 `<00:27>` 写坏了、或被别的正则漏掉）
     * 也一律从正文里剥掉 —— 用户任何情况下都不该看到 `<00:27.720>` 这种字符串。
     */
    private val RESIDUAL_TIME_TAG = Regex("<\\d{1,4}:\\d{1,2}(?:\\.\\d{1,3})?>")

    /**
     * 行级 `[...]` 时间戳残留在正文里的兜底清理。
     * 正常解析会在行首把 `[mm:ss]` 吃掉；但当文件带 BOM、或标签出现在行中间时，
     * 它就会漏出来。既然它「长得就是时间戳」，就不该显示给用户。
     */
    private val RESIDUAL_BRACKET_TIME = Regex("\\[\\d{1,4}:\\d{1,2}(?::\\d{1,2})?(?:\\.\\d{1,3})?\\]")

    /** 把正文里任何形似时间戳的残留标签（`<..>` / `[..]`）都剥掉 */
    private fun scrubTags(text: String): String =
        RESIDUAL_TIME_TAG.replace(RESIDUAL_BRACKET_TIME.replace(text, ""), "")

    /**
     * 把一行正文切成逐字片段（增强型 LRC）。
     *
     * 形态是 `而<00:27.990>人<00:28.330>生...` —— 标签在文字**前面**，
     * 表示「从这一刻开始唱标签后面的字」。按标签切：每个标签之后、下一个标签之前
     * 的文字归这个标签的时间；行首标签之前若有文字，并入第一段。
     *
     * [offsetMs] 是文件里的 `[offset:]`，和行级时间用同一套偏移。
     *
     * @return (剥掉全部时间标签的正文, 片段序列)；没有可解析的行内标签时
     *         返回 `(剥掉残留标签的正文, null)` —— 普通 LRC 走这一支，行为不变。
     */
    private fun extractSegments(
        text: String,
        offsetMs: Long,
        lineTimeMs: Long? = null
    ): Pair<String, List<LyricSegment>?> {
        var hasTimedTag = false
        for (m in INLINE_TIME_TAG.findAll(text)) {
            if (parseTimeTag(m.groupValues[1] + ":" + m.groupValues[2]) != null) {
                hasTimedTag = true
                break
            }
        }
        if (!hasTimedTag) {
            return scrubTags(text).trim() to null
        }

        val segments = ArrayList<LyricSegment>()
        var cursor = 0
        var pendingTime: Long? = null
        var prefix = ""
        for (m in INLINE_TIME_TAG.findAll(text)) {
            val t = parseTimeTag(m.groupValues[1] + ":" + m.groupValues[2]) ?: continue
            // 上一个标签到这一个标签之间的文字，归上一个标签的时间
            val between = scrubTags(text.substring(cursor, m.range.first))
            if (pendingTime == null) {
                prefix = between
                // 行首标签之前还有字（混合写法）：它属于行级时间，单独成段，
                // 不能并到第一段去 —— 否则这几个字会晚唱。
                if (prefix.isNotEmpty() && lineTimeMs != null) {
                    segments.add(LyricSegment(lineTimeMs, prefix))
                    prefix = ""
                }
            } else if (between.isNotEmpty()) {
                segments.add(
                    LyricSegment((pendingTime + offsetMs).coerceAtLeast(0L), between)
                )
            }
            pendingTime = t
            cursor = m.range.last + 1
        }
        val lastTime = pendingTime ?: return scrubTags(text).trim() to null
        val tail = scrubTags(text.substring(cursor))
        val lastText = prefix + tail
        if (lastText.isNotEmpty()) {
            segments.add(LyricSegment((lastTime + offsetMs).coerceAtLeast(0L), lastText))
        }
        if (segments.isEmpty()) {
            return scrubTags(text).trim() to null
        }
        return segments.joinToString("") { it.text } to segments
    }

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

    private fun looksLikeTtml(normalized: String): Boolean {
        val head = normalized.trimStart()
        if (!head.startsWith("<")) return false
        return head.contains("<tt", ignoreCase = true) ||
            (head.contains("<p ", ignoreCase = true) && head.contains("begin=", ignoreCase = true))
    }

    /**
     * 解析 W3C TTML / Apple Music (AMLL) `.ttml` 歌词：
     * - 支持 `<p begin="..." end="...">` 行级时间轴；
     * - 支持行内逐字 `<span begin="..." end="...">` 片段（用于逐字高亮）；
     * - 支持 `<span ttm:role="x-translation">` 内嵌翻译与跳过 `x-roman` 音译。
     */
    private fun parseTtml(normalized: String, originalText: String): Lyrics? {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(normalized)))
        }.getOrNull() ?: return null

        val pNodes = doc.getElementsByTagNameNS("*", "p").let { nsList ->
            if (nsList.length > 0) nsList else doc.getElementsByTagName("p")
        }
        if (pNodes.length == 0) return null

        val out = ArrayList<LyricLine>(pNodes.length)
        var sawTime = false

        for (i in 0 until pNodes.length) {
            val pElem = pNodes.item(i) as? Element ?: continue
            val role = ttmlRole(pElem)
            if (role == "x-roman" || role == "x-translation") continue

            val pBeginMs = parseTtmlTimeMs(pElem.getAttribute("begin"))
            val mainBuf = StringBuilder()
            val trBuf = StringBuilder()
            val segments = ArrayList<LyricSegment>()

            collectTtmlNode(pElem, mainBuf, trBuf, segments)

            val mainText = collapseTtmlWhitespace(mainBuf.toString())
            if (mainText.isEmpty()) continue
            val trText = collapseTtmlWhitespace(trBuf.toString()).takeIf { it.isNotEmpty() }

            val validSegments = segments.filter { it.text.isNotEmpty() }
            val lineTime = pBeginMs ?: validSegments.firstOrNull()?.timeMs ?: -1L
            if (lineTime >= 0L) sawTime = true

            val finalSegments = if (validSegments.size >= 2) validSegments else null
            out.add(
                LyricLine(
                    timeMs = lineTime,
                    text = mainText,
                    translation = trText,
                    segments = finalSegments
                )
            )
        }

        if (out.isEmpty()) return null
        return if (sawTime) {
            val synced = out.filter { it.timeMs >= 0L }.sortedBy { it.timeMs }
            if (synced.isNotEmpty()) Lyrics.Synced(originalText, synced)
            else Lyrics.Plain(originalText, plainLines(out))
        } else {
            Lyrics.Plain(originalText, plainLines(out))
        }
    }

    private fun collectTtmlNode(
        node: Node,
        mainBuf: StringBuilder,
        trBuf: StringBuilder,
        segments: MutableList<LyricSegment>
    ) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val txt = child.nodeValue ?: ""
                    if (txt.isNotEmpty()) mainBuf.append(txt)
                }
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    val local = (el.localName ?: el.tagName).lowercase()
                    if (local == "br") {
                        mainBuf.append(' ')
                        continue
                    }
                    val role = ttmlRole(el)
                    when (role) {
                        "x-roman" -> continue
                        "x-translation" -> {
                            val t = el.textContent ?: ""
                            if (t.isNotBlank()) {
                                if (trBuf.isNotEmpty()) trBuf.append(' ')
                                trBuf.append(t.trim())
                            }
                        }
                        else -> {
                            val segBegin = parseTtmlTimeMs(el.getAttribute("begin"))
                            if (segBegin != null && !hasChildTimedSpan(el)) {
                                val segBuf = StringBuilder()
                                val dummyTr = StringBuilder()
                                collectTtmlNode(el, segBuf, dummyTr, mutableListOf())
                                val rawSegText = segBuf.toString().replace(Regex("[\\r\\n\\t]+"), " ")
                                val cleanSegText = rawSegText.replace(Regex(" {2,}"), " ")
                                if (cleanSegText.isNotEmpty()) {
                                    mainBuf.append(cleanSegText)
                                    segments.add(LyricSegment(segBegin, cleanSegText))
                                }
                                if (dummyTr.isNotEmpty()) {
                                    if (trBuf.isNotEmpty()) trBuf.append(' ')
                                    trBuf.append(dummyTr)
                                }
                            } else {
                                collectTtmlNode(el, mainBuf, trBuf, segments)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasChildTimedSpan(el: Element): Boolean {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val ce = child as Element
                if (ce.getAttribute("begin").isNotBlank() || hasChildTimedSpan(ce)) return true
            }
        }
        return false
    }

    private fun ttmlRole(el: Element): String {
        val nsRole = el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
        if (nsRole.isNotBlank()) return nsRole.trim().lowercase()
        val rawRole = el.getAttribute("ttm:role").ifBlank { el.getAttribute("role") }
        return rawRole.trim().lowercase()
    }

    private fun collapseTtmlWhitespace(s: String): String =
        s.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    /**
     * 解析 TTML 时间字符串：`hh:mm:ss.mmm`、`mm:ss.mmm`、`12.345s`、`12345ms`、`hh:mm:ss:ff`。
     */
    private fun parseTtmlTimeMs(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).trim().toDoubleOrNull()?.toLong()?.coerceAtLeast(0L)
        }
        if (s.endsWith("s", ignoreCase = true)) {
            return s.dropLast(1).trim().toDoubleOrNull()?.let { (it * 1000.0).toLong().coerceAtLeast(0L) }
        }
        val parts = s.split(':')
        return when (parts.size) {
            1 -> s.toDoubleOrNull()?.let { (it * 1000.0).toLong().coerceAtLeast(0L) }
            2, 3 -> parseTimeTag(s)
            4 -> {
                val h = parts[0].trim().toLongOrNull() ?: return null
                val m = parts[1].trim().toLongOrNull() ?: return null
                val sec = parts[2].trim().toLongOrNull() ?: return null
                val frames = parts[3].trim().toLongOrNull() ?: 0L
                (((h * 3600L) + (m * 60L) + sec) * 1000L) + (frames * 1000L / 30L)
            }
            else -> null
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
