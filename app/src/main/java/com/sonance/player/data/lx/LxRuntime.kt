package com.sonance.player.data.lx

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "LxRuntime"

/**
 * 洛雪脚本初始化结果：声明支持的平台、能力、音质列表与 API 版本。
 */
data class LxInitResult(
    val ok: Boolean,
    val apiVersion: String = "2.0.0",
    val platforms: Map<String, LxPlatformCapability> = emptyMap(),
    val error: String? = null
)

/**
 * 一次洛雪脚本取流结果。
 */
data class LxStreamResolution(
    val url: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val error: String? = null
)

/**
 * 洛雪自定义音源（`globalThis.lx` v2）独立离线 WebView 运行时。
 *
 * 与 MusicFree 插件运行时隔离：
 * - 禁用了浏览器直接外连（`blockNetworkLoads = true`），脚本的一切网络请求必须经由
 *   `lx.request(url, options, cb)` 走原生 `HttpURLConnection`，返回完整的
 *   `statusCode` / `headers` / `body`，并支持 `form` / `formData` / 重定向与取消。
 * - 提供原生 `utils.crypto`（MD5 / AES-CBC / AES-ECB / RSA 公钥加密 / randomBytes）与
 *   `utils.zlib`（inflate / deflate）。
 * - 短时缓存已解析出来的播放流，使「开始播放 → 8 秒后边听边存」复用同一条流，
 *   避免中继服务触发防爬或并发限流。
 */
class LxRuntime(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleMutex = Mutex()
    private val httpExecutor = Executors.newCachedThreadPool()

    private val callSeq = AtomicInteger(1)
    private val pendingInits = ConcurrentHashMap<String, CompletableDeferred<LxInitResult>>()
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelledReqIds = ConcurrentHashMap.newKeySet<String>()

    private data class CachedStream(
        val url: String,
        val quality: String,
        val format: String,
        val expiresAtMs: Long
    )

    private data class LastTrackMeta(
        val url: String? = null,
        val qualityLabel: String? = null,
        val format: String? = null,
        val error: String? = null,
        val updatedAtMs: Long = 0L
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val lastTrackMeta = ConcurrentHashMap<String, LastTrackMeta>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var loadedScriptId: String? = null

    @Volatile
    private var loadedScriptHash: Int = 0

    @Volatile
    private var loadedInitResult: LxInitResult? = null

    @Volatile
    private var activeInitToken: String? = null

    /**
     * 验证并初始化一份洛雪脚本，返回它通过 `lx.send('inited', ...)` 上报的平台能力表。
     * 仅在导入验证或切换脚本时重载 WebView 上下文；同一脚本热复用已加载实例。
     */
    suspend fun ensureInitialized(
        scriptId: String,
        rawScript: String,
        meta: LxScriptMeta,
        forceReload: Boolean = false
    ): LxInitResult = lifecycleMutex.withLock {
        val hash = rawScript.hashCode()
        val cachedInit = loadedInitResult
        if (!forceReload &&
            loadedScriptId == scriptId &&
            loadedScriptHash == hash &&
            cachedInit != null &&
            cachedInit.ok &&
            webView != null
        ) {
            return@withLock cachedInit
        }

        cancelAllPending("切换到新的洛雪音源脚本")
        val initToken = UUID.randomUUID().toString()
        val initDeferred = CompletableDeferred<LxInitResult>()
        activeInitToken = initToken
        pendingInits[initToken] = initDeferred

        val pageReady = CompletableDeferred<Unit>()
        mainHandler.post {
            try {
                val wv = getOrCreateWebViewLocked()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!pageReady.isCompleted) pageReady.complete(Unit)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && !pageReady.isCompleted) {
                            pageReady.completeExceptionally(
                                IllegalStateException("WebView 页面加载失败: ${error?.description}")
                            )
                        }
                    }
                }
                wv.loadDataWithBaseURL(
                    "https://sonance.lx.local/",
                    "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>",
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (t: Throwable) {
                if (!pageReady.isCompleted) pageReady.completeExceptionally(t)
            }
        }

        try {
            withTimeout(5_000L) { pageReady.await() }
            val scriptInfoJson = JSONObject().apply {
                put("name", meta.name)
                put("description", meta.description)
                put("version", meta.version)
                put("author", meta.author)
                put("homepage", meta.homepage)
                put("rawScript", rawScript)
            }
            val bootstrap = buildLxBootstrapJs(initToken, scriptInfoJson)
            val evalError = evalOnMain(bootstrap)
            if (evalError != null) {
                val res = LxInitResult(ok = false, error = "注入洛雪运行时失败: $evalError")
                loadedInitResult = res
                return@withLock res
            }

            val userExecError = evalOnMain(
                """
                (function () {
                    try {
                        (0, eval)(${jsString(rawScript.removePrefix("\uFEFF"))});
                    } catch (__sonanceErr) {
                        SonanceLxBridge.reportInitError(
                            ${jsString(initToken)},
                            String(__sonanceErr && __sonanceErr.stack ? __sonanceErr.stack : __sonanceErr)
                        );
                    }
                })();
                """.trimIndent()
            )
            if (userExecError != null && !initDeferred.isCompleted) {
                initDeferred.complete(LxInitResult(ok = false, error = userExecError))
            }

            val res = withTimeout(INIT_TIMEOUT_MS) { initDeferred.await() }
            if (res.ok) {
                loadedScriptId = scriptId
                loadedScriptHash = hash
                loadedInitResult = res
            } else {
                loadedScriptId = null
                loadedScriptHash = 0
                loadedInitResult = res
            }
            res
        } catch (t: Throwable) {
            Log.w(TAG, "init script $scriptId failed", t)
            loadedScriptId = null
            loadedScriptHash = 0
            val msg = t.message?.takeIf { it.isNotBlank() } ?: "洛雪脚本初始化超时或异常"
            LxInitResult(ok = false, error = msg).also { loadedInitResult = it }
        } finally {
            pendingInits.remove(initToken)
        }
    }

    /**
     * 通过已激活的洛雪脚本解析指定曲目的播放地址；若高音质不可用则按支持列表自动降级。
     */
    suspend fun resolveMusicUrl(
        scriptId: String,
        rawScript: String,
        meta: LxScriptMeta,
        platform: String,
        remoteId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        requestedQuality: String,
        supportedQualitys: List<String>
    ): LxStreamResolution {
        val trackKey = trackMetaKey(platform, remoteId)
        val cacheKey = "$scriptId|$trackKey|$requestedQuality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now && cached.url.isNotBlank()) {
                val qLabel = LxQuality.byId(cached.quality).badge
                lastTrackMeta[trackKey] = LastTrackMeta(
                    url = cached.url,
                    qualityLabel = qLabel,
                    format = cached.format,
                    error = null,
                    updatedAtMs = now
                )
                return LxStreamResolution(
                    url = cached.url,
                    quality = cached.quality,
                    format = cached.format
                )
            } else {
                streamCache.remove(cacheKey)
            }
        }

        val init = ensureInitialized(scriptId, rawScript, meta)
        if (!init.ok) {
            val err = init.error ?: "洛雪音源初始化失败"
            lastTrackMeta[trackKey] = LastTrackMeta(error = err, updatedAtMs = System.currentTimeMillis())
            return LxStreamResolution(error = err)
        }

        val effectiveSupported = init.platforms[platform]?.qualitys?.takeIf { it.isNotEmpty() }
            ?: supportedQualitys
        val candidates = LxQuality.resolveCandidates(requestedQuality, effectiveSupported)
        val musicInfo = LxRemoteId.buildMusicInfoJson(
            platform = platform,
            remoteId = remoteId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )

        var lastError: String? = null
        for (q in candidates) {
            val callRes = invokeMusicUrlOnce(platform, q, musicInfo)
            val url = callRes.getOrNull()?.trim().orEmpty()
            if (callRes.isSuccess && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                val format = LxQuality.formatFor(q, url)
                val qLabel = LxQuality.byId(q).badge
                val resolvedNow = System.currentTimeMillis()
                streamCache[cacheKey] = CachedStream(
                    url = url,
                    quality = q,
                    format = format,
                    expiresAtMs = resolvedNow + STREAM_CACHE_TTL_MS
                )
                lastTrackMeta[trackKey] = LastTrackMeta(
                    url = url,
                    qualityLabel = qLabel,
                    format = format,
                    error = null,
                    updatedAtMs = resolvedNow
                )
                return LxStreamResolution(url = url, quality = q, format = format)
            }

            val err = callRes.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: if (url.isNotEmpty()) "无效音频地址" else "脚本返回空地址"
            lastError = err
            if (isNonRetryableError(err)) break
        }

        val finalError = lastError ?: "无法获取播放链接"
        lastTrackMeta[trackKey] = LastTrackMeta(error = finalError, updatedAtMs = System.currentTimeMillis())
        return LxStreamResolution(error = finalError)
    }

    /** 仅查询缓存中未过期的流地址（供同步 `OnlineSource.streamUrl` / 下载器使用）。 */
    fun cachedStreamUrl(platform: String, remoteId: String): String {
        val trackKey = trackMetaKey(platform, remoteId)
        val meta = lastTrackMeta[trackKey] ?: return ""
        val url = meta.url.orEmpty()
        if (url.isBlank()) return ""
        return if (System.currentTimeMillis() - meta.updatedAtMs <= STREAM_CACHE_TTL_MS) url else ""
    }

    fun hasFreshStream(platform: String, remoteId: String): Boolean =
        cachedStreamUrl(platform, remoteId).isNotBlank()

    fun lastStreamError(platform: String, remoteId: String): String? =
        lastTrackMeta[trackMetaKey(platform, remoteId)]?.error

    fun streamQualityLabel(platform: String, remoteId: String): String? =
        lastTrackMeta[trackMetaKey(platform, remoteId)]?.qualityLabel

    fun streamFormat(platform: String, remoteId: String): String? =
        lastTrackMeta[trackMetaKey(platform, remoteId)]?.format

    fun clearCaches() {
        streamCache.clear()
        lastTrackMeta.clear()
    }

    fun invalidateScript(scriptId: String) {
        streamCache.keys.filter { it.startsWith("$scriptId|") }.forEach { streamCache.remove(it) }
        if (loadedScriptId == scriptId) {
            loadedScriptId = null
            loadedScriptHash = 0
            loadedInitResult = null
        }
    }

    private suspend fun invokeMusicUrlOnce(
        platform: String,
        quality: String,
        musicInfo: JSONObject
    ): Result<String> {
        val callId = "call_${callSeq.getAndIncrement()}"
        val deferred = CompletableDeferred<Result<String>>()
        pendingCalls[callId] = deferred
        return try {
            val payload = JSONObject().apply {
                put("type", quality)
                put("musicInfo", musicInfo)
            }
            val js = """
                window.__sonanceLxCall(
                    ${jsString(callId)},
                    ${jsString(platform)},
                    'musicUrl',
                    ${payload}
                );
            """.trimIndent()
            mainHandler.post {
                val wv = webView
                if (wv == null) {
                    pendingCalls.remove(callId)?.complete(
                        Result.failure(IllegalStateException("洛雪运行时尚未初始化"))
                    )
                } else {
                    wv.evaluateJavascript(js, null)
                }
            }
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            pendingCalls.remove(callId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebViewLocked(): WebView {
        webView?.let { return it }
        return WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(SonanceLxBridge(), "SonanceLxBridge")
            webView = this
        }
    }

    private suspend fun evalOnMain(js: String): String? {
        val done = CompletableDeferred<String?>()
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                done.complete("WebView 未就绪")
            } else {
                wv.evaluateJavascript(js) { _ ->
                    if (!done.isCompleted) done.complete(null)
                }
            }
        }
        return withTimeout(4_000L) { done.await() }
    }

    private fun cancelAllPending(reason: String) {
        activeInitToken = null
        pendingInits.values.forEach { if (!it.isCompleted) it.complete(LxInitResult(ok = false, error = reason)) }
        pendingInits.clear()
        pendingCalls.values.forEach { if (!it.isCompleted) it.complete(Result.failure(IllegalStateException(reason))) }
        pendingCalls.clear()
        activeConnections.keys.toList().forEach { reqId ->
            cancelledReqIds += reqId
            activeConnections.remove(reqId)?.let { runCatching { it.disconnect() } }
        }
    }

    private inner class SonanceLxBridge {

        @JavascriptInterface
        fun reportInited(token: String, payloadJson: String) {
            if (token != activeInitToken) return
            val deferred = pendingInits[token] ?: return
            val result = parseInitPayload(payloadJson)
            if (!deferred.isCompleted) deferred.complete(result)
        }

        @JavascriptInterface
        fun reportInitError(token: String, message: String) {
            if (token != activeInitToken) return
            val deferred = pendingInits[token] ?: return
            val clean = message.trim().ifBlank { "脚本执行出错" }
            if (!deferred.isCompleted) deferred.complete(LxInitResult(ok = false, error = clean))
        }

        @JavascriptInterface
        fun resolveCall(callId: String, ok: Boolean, valueOrError: String) {
            val deferred = pendingCalls.remove(callId) ?: return
            if (deferred.isCompleted) return
            if (ok) {
                deferred.complete(Result.success(valueOrError))
            } else {
                deferred.complete(Result.failure(IllegalStateException(valueOrError.ifBlank { "获取音频地址失败" })))
            }
        }

        @JavascriptInterface
        fun requestAsync(reqId: String, url: String, optionsJson: String) {
            httpExecutor.execute {
                if (cancelledReqIds.remove(reqId)) return@execute
                val response = executeHttpRequest(reqId, url, optionsJson)
                if (cancelledReqIds.remove(reqId)) return@execute
                val callbackJs = """
                    window.__sonanceLxHttpDone(${jsString(reqId)}, ${jsString(response)});
                """.trimIndent()
                mainHandler.post {
                    webView?.evaluateJavascript(callbackJs, null)
                }
            }
        }

        @JavascriptInterface
        fun cancelHttp(reqId: String) {
            cancelledReqIds += reqId
            activeConnections.remove(reqId)?.let { conn ->
                httpExecutor.execute { runCatching { conn.disconnect() } }
            }
        }

        @JavascriptInterface
        fun md5Base64(dataB64: String): String {
            val bytes = Base64.decode(dataB64, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        @JavascriptInterface
        fun aesEncryptBase64(dataB64: String, mode: String, keyB64: String, ivB64: String): String {
            val data = Base64.decode(dataB64, Base64.DEFAULT)
            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val cipherName = when (mode.lowercase(Locale.ROOT).trim()) {
                "aes-128-ecb", "aes-192-ecb", "aes-256-ecb", "ecb" -> "AES/ECB/PKCS5Padding"
                else -> "AES/CBC/PKCS5Padding"
            }
            val cipher = Cipher.getInstance(cipherName)
            val secretKey = SecretKeySpec(key, "AES")
            if (cipherName.contains("/CBC/")) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            }
            return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun rsaEncryptBase64(dataB64: String, pemPublicKey: String): String {
            val data = Base64.decode(dataB64, Base64.DEFAULT)
            val cleaned = pemPublicKey
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
            val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun randomBytesBase64(size: Int): String {
            val bytes = ByteArray(size.coerceIn(0, 65_536))
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun inflateBase64(dataB64: String): String {
            val input = Base64.decode(dataB64, Base64.DEFAULT)
            return Base64.encodeToString(zlibInflate(input), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun deflateBase64(dataB64: String): String {
            val input = Base64.decode(dataB64, Base64.DEFAULT)
            val deflater = Deflater()
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            deflater.end()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun executeHttpRequest(reqId: String, rawUrl: String, optionsJson: String): String {
        val opts = runCatching { JSONObject(optionsJson) }.getOrElse { JSONObject() }
        val method = opts.optString("method", "GET").trim().uppercase(Locale.ROOT).ifBlank { "GET" }
        val timeoutMs = opts.optInt("timeout", 15_000).coerceIn(2_000, 60_000)
        val followMax = opts.optInt("follow_max", 5).coerceIn(0, 10)
        val headersObj = opts.optJSONObject("headers")

        return try {
            var currentUrl = rawUrl
            var redirects = 0
            var currentMethod = method
            var responseJson: String? = null

            while (responseJson == null) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = currentMethod
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("Accept-Encoding", "gzip, identity")
                    var hasUserAgent = false
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = headersObj.opt(k)?.toString() ?: continue
                            if (k.equals("user-agent", ignoreCase = true)) hasUserAgent = true
                            setRequestProperty(k, v)
                        }
                    }
                    if (!hasUserAgent) {
                        setRequestProperty("User-Agent", DEFAULT_UA)
                    }
                }
                activeConnections[reqId] = conn

                try {
                    writeRequestBodyIfNeeded(conn, currentMethod, opts, headersObj)
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && !location.isNullOrBlank() && redirects < followMax) {
                        redirects++
                        currentUrl = URL(URL(currentUrl), location).toString()
                        if (code == 301 || code == 302 || code == 303) currentMethod = "GET"
                        continue
                    }

                    val respHeaders = JSONObject()
                    conn.headerFields?.forEach { (k, values) ->
                        if (k != null && values != null) {
                            respHeaders.put(
                                k.lowercase(Locale.ROOT),
                                if (values.size == 1) values[0] else values.joinToString(", ")
                            )
                        }
                    }

                    val rawStream = runCatching {
                        if (code >= 400) conn.errorStream else conn.inputStream
                    }.getOrNull()
                    val encoding = conn.contentEncoding.orEmpty().lowercase(Locale.ROOT)
                    val bodyBytes = rawStream?.use { stream ->
                        val decodedStream = if (encoding.contains("gzip")) GZIPInputStream(stream) else stream
                        decodedStream.readBytes()
                    } ?: ByteArray(0)

                    responseJson = JSONObject().apply {
                        put("ok", true)
                        put("statusCode", code)
                        put("statusMessage", conn.responseMessage ?: "")
                        put("headers", respHeaders)
                        put("bodyB64", Base64.encodeToString(bodyBytes, Base64.NO_WRAP))
                    }.toString()
                } finally {
                    activeConnections.remove(reqId)
                    runCatching { conn.disconnect() }
                }
            }
            responseJson
        } catch (t: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", t.message ?: t.javaClass.simpleName)
            }.toString()
        }
    }

    private fun writeRequestBodyIfNeeded(
        conn: HttpURLConnection,
        method: String,
        opts: JSONObject,
        headersObj: JSONObject?
    ) {
        if (method == "GET" || method == "HEAD") return
        val hasExplicitContentType = headersObj?.let { h ->
            h.keys().asSequence().any { it.equals("content-type", ignoreCase = true) }
        } == true

        val payloadBytes: ByteArray?
        var autoContentType: String? = null

        val formObj = opts.optJSONObject("form")
        val formDataObj = opts.optJSONObject("formData")
        val rawBody = if (opts.has("body") && !opts.isNull("body")) opts.opt("body") else null

        when {
            formDataObj != null -> {
                val boundary = "----SonanceLxForm${UUID.randomUUID().toString().replace("-", "")}"
                autoContentType = "multipart/form-data; boundary=$boundary"
                val out = ByteArrayOutputStream()
                val keys = formDataObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = formDataObj.opt(k)?.toString().orEmpty()
                    out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    out.write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.write(v.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                payloadBytes = out.toByteArray()
            }
            formObj != null -> {
                autoContentType = "application/x-www-form-urlencoded; charset=utf-8"
                val encoded = buildString {
                    val keys = formObj.keys()
                    var first = true
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = formObj.opt(k)?.toString().orEmpty()
                        if (!first) append('&')
                        first = false
                        append(URLEncoder.encode(k, "UTF-8"))
                        append('=')
                        append(URLEncoder.encode(v, "UTF-8"))
                    }
                }
                payloadBytes = encoded.toByteArray(Charsets.UTF_8)
            }
            rawBody is JSONObject || rawBody is JSONArray -> {
                autoContentType = "application/json; charset=utf-8"
                payloadBytes = rawBody.toString().toByteArray(Charsets.UTF_8)
            }
            rawBody != null -> {
                payloadBytes = rawBody.toString().toByteArray(Charsets.UTF_8)
            }
            else -> payloadBytes = null
        }

        if (payloadBytes != null) {
            if (!hasExplicitContentType && autoContentType != null) {
                conn.setRequestProperty("Content-Type", autoContentType)
            }
            conn.doOutput = true
            conn.outputStream.use { it.write(payloadBytes) }
        }
    }

    private fun parseInitPayload(payloadJson: String): LxInitResult = runCatching {
        val obj = JSONObject(payloadJson)
        val ok = obj.optBoolean("status", true)
        if (!ok) {
            val err = obj.optString("errorMessage").ifBlank { obj.optString("message") }
                .ifBlank { "脚本声明初始化失败" }
            return@runCatching LxInitResult(ok = false, error = err)
        }
        val dataObj = obj.optJSONObject("data") ?: obj
        val sourcesObj = dataObj.optJSONObject("sources") ?: obj.optJSONObject("sources")
        val platforms = buildMap {
            if (sourcesObj != null) {
                val keys = sourcesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val item = sourcesObj.optJSONObject(k) ?: continue
                    val actionsArr = item.optJSONArray("actions")
                    val actions = buildList {
                        if (actionsArr != null) {
                            for (i in 0 until actionsArr.length()) {
                                val act = actionsArr.optString(i).trim()
                                if (act.isNotEmpty()) add(act)
                            }
                        }
                    }
                    val qualArr = item.optJSONArray("qualitys")
                    val qualitys = buildList {
                        if (qualArr != null) {
                            for (i in 0 until qualArr.length()) {
                                val q = qualArr.optString(i).trim()
                                if (q.isNotEmpty()) add(q)
                            }
                        }
                    }
                    put(
                        k.trim().lowercase(Locale.ROOT),
                        LxPlatformCapability(
                            name = item.optString("name", k),
                            type = item.optString("type", "music"),
                            actions = actions,
                            qualitys = qualitys
                        )
                    )
                }
            }
        }
        if (platforms.isEmpty()) {
            return@runCatching LxInitResult(ok = false, error = "脚本未声明任何有效音源平台")
        }
        LxInitResult(ok = true, platforms = platforms)
    }.getOrElse { t ->
        LxInitResult(ok = false, error = "解析脚本初始化数据失败: ${t.message}")
    }

    private fun buildLxBootstrapJs(initToken: String, scriptInfo: JSONObject): String = """
        (function () {
            const initToken = ${jsString(initToken)};
            const EVENT_NAMES = {
                inited: 'inited',
                request: 'request',
                updateAlert: 'updateAlert'
            };

            function bytesToB64(bytes) {
                let bin = '';
                const step = 8192;
                for (let i = 0; i < bytes.length; i += step) {
                    const chunk = bytes.subarray(i, Math.min(i + step, bytes.length));
                    bin += String.fromCharCode.apply(null, chunk);
                }
                return btoa(bin);
            }

            function b64ToBytes(b64) {
                if (!b64) return new Uint8Array(0);
                const bin = atob(b64);
                const out = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 0xff;
                return out;
            }

            const utf8Encoder = new TextEncoder();
            const utf8Decoder = new TextDecoder('utf-8', { fatal: false });

            function toBytes(input, encoding) {
                if (input instanceof Uint8Array) return input;
                if (input instanceof ArrayBuffer) return new Uint8Array(input);
                if (Array.isArray(input)) return new Uint8Array(input);
                const str = String(input == null ? '' : input);
                const enc = String(encoding || 'utf8').toLowerCase();
                if (enc === 'base64') return b64ToBytes(str);
                if (enc === 'hex') {
                    const clean = str.replace(/[^0-9a-fA-F]/g, '');
                    const out = new Uint8Array(Math.floor(clean.length / 2));
                    for (let i = 0; i < out.length; i++) {
                        out[i] = parseInt(clean.substr(i * 2, 2), 16) & 0xff;
                    }
                    return out;
                }
                if (enc === 'latin1' || enc === 'binary') {
                    const out = new Uint8Array(str.length);
                    for (let i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 0xff;
                    return out;
                }
                return utf8Encoder.encode(str);
            }

            function bufToString(buf, format) {
                const bytes = toBytes(buf);
                const fmt = String(format || 'utf8').toLowerCase();
                if (fmt === 'base64') return bytesToB64(bytes);
                if (fmt === 'hex') {
                    let hex = '';
                    for (let i = 0; i < bytes.length; i++) {
                        hex += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
                    }
                    return hex;
                }
                if (fmt === 'latin1' || fmt === 'binary') {
                    let s = '';
                    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
                    return s;
                }
                return utf8Decoder.decode(bytes);
            }

            function wrapBuf(bytes) {
                const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
                try {
                    Object.defineProperty(arr, 'toString', {
                        value: function (fmt) { return fmt ? bufToString(arr, fmt) : utf8Decoder.decode(arr); },
                        enumerable: false,
                        configurable: true
                    });
                } catch (_) {}
                return arr;
            }

            let reqSeq = 1;
            const pendingHttp = Object.create(null);
            let requestHandler = null;

            window.__sonanceLxHttpDone = function (reqId, payloadJson) {
                const cb = pendingHttp[reqId];
                if (!cb) return;
                delete pendingHttp[reqId];
                let res;
                try {
                    res = JSON.parse(payloadJson);
                } catch (e) {
                    cb(e, null, null);
                    return;
                }
                if (!res.ok) {
                    cb(new Error(res.error || 'Network request failed'), null, null);
                    return;
                }
                const bytes = b64ToBytes(res.bodyB64 || '');
                const rawText = utf8Decoder.decode(bytes);
                let parsedBody = rawText;
                const trimmed = rawText.trim();
                const ct = String((res.headers && res.headers['content-type']) || '').toLowerCase();
                if (ct.indexOf('json') >= 0 || trimmed.startsWith('{') || trimmed.startsWith('[')) {
                    try { parsedBody = JSON.parse(rawText); } catch (_) {}
                }
                const respObj = {
                    statusCode: res.statusCode || 0,
                    statusMessage: res.statusMessage || '',
                    headers: res.headers || {},
                    raw: rawText,
                    body: parsedBody,
                    bytes: bytes.length
                };
                cb(null, respObj, parsedBody);
            };

            window.__sonanceLxCall = function (callId, source, action, info) {
                if (typeof requestHandler !== 'function') {
                    SonanceLxBridge.resolveCall(callId, false, '脚本未注册 request 事件监听');
                    return;
                }
                try {
                    Promise.resolve(requestHandler({ source: source, action: action, info: info }))
                        .then(function (val) {
                            const out = val == null ? '' : (typeof val === 'string' ? val : (val.url || JSON.stringify(val)));
                            SonanceLxBridge.resolveCall(callId, true, String(out));
                        })
                        .catch(function (err) {
                            const msg = err && err.message ? err.message : String(err || '脚本返回失败');
                            SonanceLxBridge.resolveCall(callId, false, msg);
                        });
                } catch (e) {
                    const msg = e && e.message ? e.message : String(e || '脚本执行异常');
                    SonanceLxBridge.resolveCall(callId, false, msg);
                }
            };

            const lxApi = {
                EVENT_NAMES: EVENT_NAMES,
                env: 'mobile',
                version: '2.0.0',
                currentScriptInfo: ${scriptInfo},
                on: function (eventName, handler) {
                    if (eventName === EVENT_NAMES.request && typeof handler === 'function') {
                        requestHandler = handler;
                    }
                },
                send: function (eventName, data) {
                    if (eventName === EVENT_NAMES.inited) {
                        SonanceLxBridge.reportInited(initToken, JSON.stringify(data || {}));
                    }
                    return Promise.resolve();
                },
                request: function (url, options, callback) {
                    let opts = options;
                    let cb = callback;
                    if (typeof opts === 'function') {
                        cb = opts;
                        opts = {};
                    }
                    opts = opts || {};
                    const reqId = 'r_' + (reqSeq++);
                    pendingHttp[reqId] = typeof cb === 'function' ? cb : function () {};
                    SonanceLxBridge.requestAsync(reqId, String(url || ''), JSON.stringify(opts));
                    return function cancelRequest() {
                        delete pendingHttp[reqId];
                        SonanceLxBridge.cancelHttp(reqId);
                    };
                },
                utils: {
                    buffer: {
                        from: function (input, encoding) { return wrapBuf(toBytes(input, encoding)); },
                        bufToString: bufToString
                    },
                    crypto: {
                        md5: function (str) {
                            return SonanceLxBridge.md5Base64(bytesToB64(toBytes(str)));
                        },
                        aesEncrypt: function (buffer, mode, key, iv) {
                            const resB64 = SonanceLxBridge.aesEncryptBase64(
                                bytesToB64(toBytes(buffer)),
                                String(mode || 'aes-128-cbc'),
                                bytesToB64(toBytes(key)),
                                bytesToB64(toBytes(iv || ''))
                            );
                            return wrapBuf(b64ToBytes(resB64));
                        },
                        rsaEncrypt: function (buffer, key) {
                            const resB64 = SonanceLxBridge.rsaEncryptBase64(
                                bytesToB64(toBytes(buffer)),
                                String(key || '')
                            );
                            return wrapBuf(b64ToBytes(resB64));
                        },
                        randomBytes: function (size) {
                            return wrapBuf(b64ToBytes(SonanceLxBridge.randomBytesBase64(Number(size) || 0)));
                        }
                    },
                    zlib: {
                        inflate: function (buf) {
                            return Promise.resolve(wrapBuf(b64ToBytes(SonanceLxBridge.inflateBase64(bytesToB64(toBytes(buf))))));
                        },
                        deflate: function (buf) {
                            return Promise.resolve(wrapBuf(b64ToBytes(SonanceLxBridge.deflateBase64(bytesToB64(toBytes(buf))))));
                        }
                    }
                }
            };

            globalThis.lx = lxApi;
            window.lx = lxApi;
        })();
    """.trimIndent()

    private fun trackMetaKey(platform: String, remoteId: String): String = "$platform:$remoteId"

    private fun isNonRetryableError(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        return "频繁" in m || "批量" in m || "429" in m || "rate" in m || "ban" in m || "封" in m
    }

    private fun zlibInflate(input: ByteArray): ByteArray {
        return try {
            runInflate(input, nowrap = false)
        } catch (_: Throwable) {
            runInflate(input, nowrap = true)
        }
    }

    private fun runInflate(input: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        inflater.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun jsString(s: String): String =
        JSONObject.quote(s)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    companion object {
        private const val INIT_TIMEOUT_MS = 12_000L
        private const val CALL_TIMEOUT_MS = 20_000L
        private const val STREAM_CACHE_TTL_MS = 4 * 60_000L
        private const val DEFAULT_UA =
            "lx-music-request/2.0.0"
    }
}
