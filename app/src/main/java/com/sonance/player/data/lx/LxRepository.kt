package com.sonance.player.data.lx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.Prefs
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * 洛雪自定义音源脚本仓库。
 *
 * 职责：
 * - 将用户导入的 `.js` 脚本持久保存在 `filesDir/lx_sources/`，并在 `lx_sources.json` 记录能力索引；
 * - 导入时立即在 [LxRuntime] 试跑 `inited` 握手，只有真正兼容的洛雪音源才写入清单；
 * - 维护曲目元数据缓存 [trackHints]，确保恢复队列 / 下载 / 换音源时传给脚本完整的 `name`/`singer`。
 */
class LxRepository private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val runtime: LxRuntime = LxRuntime(appContext)
    private val prefs = Prefs(appContext)

    private val rootDir = File(appContext.filesDir, "lx_sources").apply { mkdirs() }
    private val indexFile = File(rootDir, "lx_sources.json")
    private val fileLock = Any()

    private val trackHints = ConcurrentHashMap<String, LxTrackHint>()

    private val _scripts = MutableStateFlow(loadRecords())
    val scripts: StateFlow<List<LxScriptRecord>> = _scripts.asStateFlow()

    /** 将 `lx:kw` / `lx:tx` / `lx:kg` / `lx:wy` 注册进 [OnlineSources] 的 id 查找表。 */
    fun refreshOnlineSources() {
        val sources = LxPlatform.ALL.map { platform ->
            LxPlatformSource(repo = this, runtime = runtime, platform = platform)
        }
        OnlineSources.replaceLxSources(sources)
    }

    fun activeScript(): LxScriptRecord? {
        val list = _scripts.value
        if (list.isEmpty()) return null
        val savedId = prefs.lxActiveSourceId
        return list.firstOrNull { it.id == savedId } ?: list.first().also {
            prefs.lxActiveSourceId = it.id
        }
    }

    fun readRawScript(scriptId: String): String? = runCatching {
        val f = scriptFile(scriptId)
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }.getOrNull()

    suspend fun importFromUri(uri: Uri): Result<LxScriptRecord> = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "lx_source.js"
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("无法读取所选脚本文件"))
        if (bytes.size > MAX_SCRIPT_BYTES) {
            return@withContext Result.failure(IllegalStateException("脚本文件过大（超过 2 MB）"))
        }
        val content = LyricsRepository.decodeLyricsBytes(bytes)
        importScript(content, displayName)
    }

    /**
     * 通过直链 URL（http/https）拉取远程洛雪 `.js` 脚本并自动校验、持久化启用。
     * 支持跨协议重定向、gzip 压缩、字节上限保护及常见 HTML 网页链接误填提示。
     */
    suspend fun importFromUrl(rawUrl: String): Result<LxScriptRecord> = withContext(Dispatchers.IO) {
        val initialUrl = validateAndNormalizeScriptUrl(rawUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val fetched = fetchRemoteScript(initialUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val content = LyricsRepository.decodeLyricsBytes(fetched.bytes)
        if (looksLikeHtmlDocument(fetched.contentType, content)) {
            return@withContext Result.failure(
                IllegalStateException("该链接返回的是网页（HTML）而非 .js 脚本，请使用脚本直链（如 Raw 链接）")
            )
        }
        importScript(content, fetched.fileName)
    }

    private data class RemoteScriptPayload(
        val bytes: ByteArray,
        val fileName: String,
        val contentType: String?
    )

    private fun fetchRemoteScript(initialUrl: URL): Result<RemoteScriptPayload> {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = URL_CONNECT_TIMEOUT_MS
                    readTimeout = URL_READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Sonance/2.5"
                    )
                    setRequestProperty("Accept", "application/javascript, text/javascript, text/plain, */*")
                    setRequestProperty("Accept-Encoding", "gzip, identity")
                }

                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    if (redirects >= MAX_URL_REDIRECTS) {
                        return Result.failure(IllegalStateException("链接重定向次数过多"))
                    }
                    val nextUrl = runCatching { URL(currentUrl, location) }.getOrNull()
                        ?: return Result.failure(IllegalStateException("重定向地址无效"))
                    if (nextUrl.protocol.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                        return Result.failure(IllegalStateException("重定向目标协议不受支持（${nextUrl.protocol}）"))
                    }
                    redirects++
                    currentUrl = nextUrl
                    continue
                }

                if (code !in 200..299) {
                    val hint = conn.responseMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                    return Result.failure(IllegalStateException("下载脚本失败（HTTP $code$hint）"))
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > MAX_SCRIPT_BYTES) {
                    return Result.failure(IllegalStateException("远程脚本文件过大（超过 2 MB）"))
                }

                val encoding = conn.contentEncoding.orEmpty().lowercase(Locale.ROOT)
                val rawStream = conn.inputStream
                    ?: return Result.failure(IllegalStateException("服务器未返回脚本内容"))
                val decodedStream = BufferedInputStream(
                    if ("gzip" in encoding) GZIPInputStream(rawStream) else rawStream
                )
                val bytes = decodedStream.use { stream ->
                    readBytesCapped(stream, MAX_SCRIPT_BYTES)
                } ?: return Result.failure(IllegalStateException("远程脚本文件过大（超过 2 MB）"))

                val fileName = deriveScriptFileName(currentUrl, conn.getHeaderField("Content-Disposition"))
                return Result.success(
                    RemoteScriptPayload(
                        bytes = bytes,
                        fileName = fileName,
                        contentType = conn.contentType
                    )
                )
            } catch (t: Throwable) {
                return Result.failure(
                    IllegalStateException("下载脚本失败：${t.message ?: t.javaClass.simpleName}", t)
                )
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun readBytesCapped(stream: BufferedInputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    suspend fun importScript(rawContent: String, fileName: String): Result<LxScriptRecord> =
        withContext(Dispatchers.IO) {
            val clean = rawContent.removePrefix("\uFEFF").trim()
            if (clean.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("脚本内容为空"))
            }
            if (clean.length > MAX_SCRIPT_BYTES) {
                return@withContext Result.failure(IllegalStateException("脚本体积过大，请确认导入的是纯文本 .js 音源"))
            }
            if (looksLikeMusicFreePlugin(clean)) {
                return@withContext Result.failure(
                    IllegalStateException("该文件是 MusicFree 插件格式，当前仅支持标准洛雪（globalThis.lx）.js 音源脚本")
                )
            }

            val meta = parseLxScriptMeta(clean, fileName)
            val id = "lx_${sha256Prefix(clean)}"
            val init = runtime.ensureInitialized(id, clean, meta, forceReload = true)
            if (!init.ok) {
                return@withContext Result.failure(
                    IllegalStateException(init.error ?: "洛雪脚本初始化失败，请检查脚本是否兼容 lx API v2")
                )
            }

            val record = LxScriptRecord(
                id = id,
                meta = meta,
                fileName = fileName,
                addedAtMs = System.currentTimeMillis(),
                platforms = init.platforms,
                apiVersion = init.apiVersion
            )
            if (record.supportedCatalogPlatforms().isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("脚本已完成初始化，但未声明任何受支持的平台（kw / tx / kg / wy）")
                )
            }

            synchronized(fileLock) {
                rootDir.mkdirs()
                scriptFile(id).writeText(clean, Charsets.UTF_8)
                val updated = _scripts.value.filterNot { it.id == id } + record
                saveRecordsLocked(updated)
                _scripts.value = updated
            }

            prefs.lxActiveSourceId = id
            alignPlatformFor(record)
            runtime.clearCaches()
            refreshOnlineSources()
            Result.success(record)
        }

    fun selectScript(id: String): LxScriptRecord? {
        val target = _scripts.value.firstOrNull { it.id == id } ?: return null
        prefs.lxActiveSourceId = target.id
        alignPlatformFor(target)
        runtime.clearCaches()
        return target
    }

    fun removeScript(id: String) {
        synchronized(fileLock) {
            runCatching { scriptFile(id).delete() }
            val updated = _scripts.value.filterNot { it.id == id }
            saveRecordsLocked(updated)
            _scripts.value = updated
            if (prefs.lxActiveSourceId == id) {
                val next = updated.firstOrNull()
                prefs.lxActiveSourceId = next?.id.orEmpty()
                if (next != null) alignPlatformFor(next)
            }
        }
        runtime.invalidateScript(id)
    }

    fun rememberTrackHints(platform: String, tracks: List<RemoteTrack>) {
        if (trackHints.size > 2_000) trackHints.clear()
        for (t in tracks) {
            trackHints["$platform:${t.remoteId}"] = LxTrackHint(
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationMs = t.durationMs
            )
        }
    }

    fun rememberSongHint(song: Song) {
        val platform = LxPlatform.platformIdFromSource(song.source) ?: return
        val rid = song.remoteId ?: return
        if (trackHints.size > 2_000) trackHints.clear()
        trackHints["$platform:$rid"] = LxTrackHint(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs
        )
    }

    fun getTrackHint(platform: String, remoteId: String): LxTrackHint? =
        trackHints["$platform:$remoteId"]

    private fun alignPlatformFor(record: LxScriptRecord) {
        val supported = record.supportedCatalogPlatforms().map { it.id }
        if (supported.isNotEmpty() && prefs.lxPlatform !in supported) {
            prefs.lxPlatform = supported.first()
        }
    }

    private fun scriptFile(id: String): File = File(rootDir, "$id.js")

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun loadRecords(): List<LxScriptRecord> = synchronized(fileLock) {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty() || !scriptFile(id).exists()) continue
                    val metaObj = o.optJSONObject("meta") ?: JSONObject()
                    val meta = LxScriptMeta(
                        name = metaObj.optString("name", id),
                        version = metaObj.optString("version"),
                        author = metaObj.optString("author"),
                        description = metaObj.optString("description"),
                        homepage = metaObj.optString("homepage")
                    )
                    val platformsObj = o.optJSONObject("platforms") ?: JSONObject()
                    val platforms = buildMap {
                        val keys = platformsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val pObj = platformsObj.optJSONObject(k) ?: continue
                            val actions = pObj.optJSONArray("actions")?.let { a ->
                                (0 until a.length()).mapNotNull { idx -> a.optString(idx).takeIf { it.isNotBlank() } }
                            }.orEmpty()
                            val qualitys = pObj.optJSONArray("qualitys")?.let { q ->
                                (0 until q.length()).mapNotNull { idx -> q.optString(idx).takeIf { it.isNotBlank() } }
                            }.orEmpty()
                            put(
                                k,
                                LxPlatformCapability(
                                    name = pObj.optString("name", k),
                                    type = pObj.optString("type", "music"),
                                    actions = actions,
                                    qualitys = qualitys
                                )
                            )
                        }
                    }
                    add(
                        LxScriptRecord(
                            id = id,
                            meta = meta,
                            fileName = o.optString("fileName", "$id.js"),
                            addedAtMs = o.optLong("addedAtMs", 0L),
                            platforms = platforms,
                            apiVersion = o.optString("apiVersion", "2.0.0")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecordsLocked(list: List<LxScriptRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("fileName", r.fileName)
                    put("addedAtMs", r.addedAtMs)
                    put("apiVersion", r.apiVersion)
                    put(
                        "meta",
                        JSONObject().apply {
                            put("name", r.meta.name)
                            put("version", r.meta.version)
                            put("author", r.meta.author)
                            put("description", r.meta.description)
                            put("homepage", r.meta.homepage)
                        }
                    )
                    put(
                        "platforms",
                        JSONObject().apply {
                            r.platforms.forEach { (k, cap) ->
                                put(
                                    k,
                                    JSONObject().apply {
                                        put("name", cap.name)
                                        put("type", cap.type)
                                        put("actions", JSONArray(cap.actions))
                                        put("qualitys", JSONArray(cap.qualitys))
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        indexFile.writeText(arr.toString(2), Charsets.UTF_8)
    }

    private fun looksLikeMusicFreePlugin(raw: String): Boolean {
        val hasMusicFree = "getMediaSource" in raw && "module.exports" in raw
        val hasLx = "EVENT_NAMES" in raw || "globalThis.lx" in raw || "window.lx" in raw || "lx.on" in raw || "lx.send" in raw
        return hasMusicFree && !hasLx
    }

    private fun sha256Prefix(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
        private const val MAX_URL_REDIRECTS = 5
        private const val URL_CONNECT_TIMEOUT_MS = 15_000
        private const val URL_READ_TIMEOUT_MS = 20_000

        private val DISPOSITION_EXT_FILENAME_RE =
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\s]+)""")
        private val DISPOSITION_FILENAME_RE =
            Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

        internal fun validateAndNormalizeScriptUrl(rawUrl: String): Result<URL> {
            val cleaned = rawUrl.trim().trim('"', '\'', '“', '”', '<', '>')
            if (cleaned.isEmpty()) {
                return Result.failure(IllegalStateException("请输入音源脚本 URL"))
            }
            val parsedUri = runCatching { URI(cleaned) }.getOrElse {
                return Result.failure(IllegalStateException("URL 格式无效，请检查是否包含非法字符"))
            }
            val scheme = parsedUri.scheme?.lowercase(Locale.ROOT).orEmpty()
            if (scheme !in setOf("http", "https")) {
                return Result.failure(IllegalStateException("仅支持 http:// 或 https:// 开头的脚本链接"))
            }
            if (parsedUri.host.isNullOrBlank()) {
                return Result.failure(IllegalStateException("URL 缺少有效的域名或主机地址"))
            }
            val parsedUrl = runCatching { parsedUri.toURL() }.getOrElse {
                return Result.failure(IllegalStateException("无法解析脚本链接地址"))
            }
            return Result.success(parsedUrl)
        }

        internal fun deriveScriptFileName(finalUrl: URL, contentDisposition: String?): String {
            val dispName = contentDisposition?.let { header ->
                val extMatch = DISPOSITION_EXT_FILENAME_RE.find(header)?.groupValues?.getOrNull(1)
                val decoded = extMatch?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                decoded ?: DISPOSITION_FILENAME_RE.find(header)?.groupValues?.getOrNull(1)?.trim()
            }
            val urlTail = finalUrl.path
                ?.substringAfterLast('/')
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
                ?.trim()
            val chosen = dispName?.takeIf { it.isNotBlank() }
                ?: urlTail?.takeIf { it.isNotBlank() }
                ?: "lx_source.js"
            val sanitized = chosen.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            if (sanitized.isEmpty()) return "lx_source.js"
            return if (sanitized.endsWith(".js", ignoreCase = true)) sanitized else "$sanitized.js"
        }

        internal fun looksLikeHtmlDocument(contentType: String?, content: String): Boolean {
            if (contentType != null && contentType.contains("text/html", ignoreCase = true)) {
                return true
            }
            val head = content.removePrefix("\uFEFF").trimStart().take(64).lowercase(Locale.ROOT)
            return head.startsWith("<!doctype html") || head.startsWith("<html")
        }

        @Volatile
        private var instance: LxRepository? = null

        fun get(context: Context): LxRepository =
            instance ?: synchronized(this) {
                instance ?: LxRepository(context.applicationContext).also { instance = it }
            }
    }
}
