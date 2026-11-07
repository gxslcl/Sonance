package com.sonance.player.data.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 网易云音频指纹计算：48kHz 单声道 PCM → 指纹 Base64。
 *
 * ## 为什么指纹算法跑在 WebView 里
 *
 * 网易云的指纹算法在官方 afp.wasm（231KB）里，入口 `ExtractQueryFP(Float32Array)`。
 * 重写 WASM 逻辑不现实，Chrome 官方识曲插件（参考实现）就是把它装进 JS 环境跑——
 * Android WebView 正好是现成的 JS 容器，离屏加载 assets 里的胶水页即可，
 * 无网络请求（wasm 由 shouldInterceptRequest 从 assets 提供）。
 *
 * ## 数据流
 *
 * 1. [fingerprint] 把 PCM 编成 base64 存进桥，主线程 `evaluateJavascript("__fpRun()")`；
 * 2. JS 拉走 PCM → `processor.start()` 轮询等 `ExtractQueryFP` 出现 → 算指纹；
 * 3. JS 标准字母表 Base64 编码后 `AndroidBridge.onResult` 回传，协程恢复；
 * 4. Kotlin 侧 [TIMEOUT_MS] 看门狗兜底，页面加载失败/JS 异常都如实回传错误文案。
 *
 * ## 线程模型
 *
 * WebView 必须建在主线程；`@JavascriptInterface` 回调跑在 JavaBridge 线程，
 * 恢复协程用 Handler 切回主线程再 resume（suspendCancellableCoroutine 的
 * resume 本身线程安全，这里统一走主线程避免 pending 状态的并发写）。
 *
 * 单例保活：wasm 初始化一次后常驻内存，识别多首歌不重复加载。
 */
object AudioFingerprinter {

    private const val TAG = "SonanceFp"

    /** 胶水页地址（path 由 [serveAsset] 映射到 assets；?v= 纯粹用于击穿 WebView 缓存） */
    private const val PAGE_URL = "https://appassets.androidplatform.net/fp_host.html?v=8"

    /** 整体看门狗：页面加载 + wasm 初始化 + 计算，留足余量 */
    private const val TIMEOUT_MS = 40_000L

    /** 一次识别的结果：[fp] 是指纹 Base64；失败时 [error] 给出面向用户的文案 */
    class FpResult(val fp: String?, val error: String?)

    private val main = Handler(Looper.getMainLooper())

    private var webView: WebView? = null

    /** 当前待恢复的协程；同一时刻只允许一次识别（VM 状态机已保证，这里兜底） */
    private var pending: ((FpResult) -> Unit)? = null

    /** 待拉取的 PCM（Float32 LE 字节序列的 base64），由 [Bridge.getPcm] 消费 */
    @Volatile
    private var pcmBase64: String? = null

    /** 胶水页脚本已全部执行完（onPageFinished），此后 evaluateJavascript 才有意义 */
    private var pageReady = false

    /** 页面未就绪时挂起的 __fpRun 触发脚本；onPageFinished 里补执行（仅主线程访问） */
    private var pendingEval: String? = null

    private var watchdog: Runnable? = null

    /**
     * 计算音频指纹。
     *
     * @param samples 48kHz 单声道 Float32，48000 个值（见 [PcmExtractor.toFingerprintSamples]）
     */
    suspend fun fingerprint(context: Context, samples: FloatArray): FpResult =
        suspendCancellableCoroutine { cont ->
            val task = Runnable {
                if (pending != null) {
                    // 同一时刻只允许一次识别（VM 状态机已保证，这里兜底）
                    cont.resume(FpResult(null, "已有识别任务进行中")) { }
                    return@Runnable
                }
                val timeout = Runnable { deliver(FpResult(null, "指纹引擎响应超时")) }
                watchdog = timeout
                main.postDelayed(timeout, TIMEOUT_MS)
                pending = { r ->
                    watchdog = null
                    main.removeCallbacks(timeout)
                    // resume 带 onCancellation：协程已取消时静默忽略结果
                    cont.resume(r) { }
                }
                try {
                    val wv = ensureWebView(context.applicationContext)
                    pcmBase64 = encodeF32(samples)
                    // loadUrl 是异步的：立刻 evaluate 会落在页面脚本执行前，
                    // __fpRun 尚未定义、表达式空转，识别就挂死到看门狗超时。
                    // 页面就绪前先挂起，onPageFinished 里补触发。
                    val script = "window.__fpRun && window.__fpRun()"
                    if (pageReady) {
                        wv.evaluateJavascript(script, null)
                    } else {
                        pendingEval = script
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "指纹 WebView 启动失败: ${t.javaClass.simpleName}: ${t.message}")
                    deliver(FpResult(null, "指纹引擎启动失败"))
                }
            }
            main.post(task)
            cont.invokeOnCancellation { main.removeCallbacks(task) }
        }

    // ===== WebView 生命周期 =====

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = false
        }
        wv.addJavascriptInterface(Bridge, "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = serveAsset(context, request.url.path)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "指纹胶水页加载失败: ${error.description}")
                    deliver(FpResult(null, "指纹页面加载失败"))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // 首次加载期间进来的指纹任务在这里补触发（仅主线程，无竞态）
                pendingEval?.let { script ->
                    pendingEval = null
                    view.evaluateJavascript(script, null)
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                // Log.i：Log.d 在部分 ColorOS/ZUI 系统上被整标签丢弃，真机排查全靠它
                Log.i(TAG, "fp_webview: ${message.message()}")
                return true
            }
        }
        wv.loadUrl(PAGE_URL)
        webView = wv
        return wv
    }

    /** assets 虚拟服务器：胶水页 / 胶水包 / wasm 三条固定路径 */
    private fun serveAsset(context: Context, path: String?): WebResourceResponse? {
        val (asset, mime) = when (path) {
            "/fp_host.html" -> "fp_host.html" to "text/html"
            "/afp_bundle.js" -> "afp_bundle.js" to "application/javascript"
            "/afp.wasm" -> "afp_fp.wasm" to "application/wasm"
            else -> return null
        }
        return try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val resp = WebResourceResponse(
                mime,
                if (mime == "application/wasm") null else "utf-8",
                ByteArrayInputStream(bytes)
            )
            // no-store：assets 内容随 APK 更新，绝不允许 WebView 用旧缓存页
            resp.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            )
            resp
        } catch (t: Throwable) {
            Log.w(TAG, "assets 响应失败 $asset: ${t.message}")
            null
        }
    }

    // ===== 桥（JavaBridge 线程调用）=====

    private object Bridge {
        @JavascriptInterface
        fun getPcm(): String = pcmBase64 ?: ""

        /** 页内诊断日志（onerror / 垫片状态），真机排查用 */
        @JavascriptInterface
        fun onLog(msg: String) {
            Log.i(TAG, "fp_page: $msg")
        }

        @JavascriptInterface
        fun onResult(fpBase64: String, error: String) {
            Log.i(TAG, "onResult: fp=${fpBase64.length} chars, error=$error")
            val r = if (error.isNullOrEmpty() && !fpBase64.isNullOrEmpty()) {
                FpResult(fpBase64, null)
            } else {
                FpResult(null, error.ifEmpty { "指纹计算失败" })
            }
            main.post { deliver(r) }
        }

        @JavascriptInterface
        fun onReady() {
            // 胶水页脚本执行完毕的信号； readiness 由首次 __fpRun 的结果体现，
            // 这里仅用于在日志里确认页面活着
            Log.d(TAG, "fp_host 就绪")
        }
    }

    private fun deliver(r: FpResult) {
        val cb = pending ?: return
        pending = null
        cb(r)
    }

    // ===== 编码 =====

    /** Float32Array → base64（JS 侧 Float32Array 是小端，这里同序编码） */
    private fun encodeF32(samples: FloatArray): String {
        val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) buf.putFloat(v)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }
}
