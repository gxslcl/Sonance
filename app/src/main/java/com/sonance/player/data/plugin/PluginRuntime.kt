package com.sonance.player.data.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件运行时：一个离屏 WebView + CommonJS 垫片 + 网络桥。
 *
 * ## 为什么用 WebView 而不是 QuickJS / Rhino
 *
 * - Rhino 只到 ES5，跑不了插件里普遍使用的 `async/await`、`Promise`、箭头函数；
 * - QuickJS 的 Android 产物不在 Maven Central，JitPack 坐标也不可达（本项目
 *   仓库只有 google() + mavenCentral()），加仓库等于给构建埋一个网络依赖；
 * - 项目里 `AudioFingerprinter` 已经用 WebView 跑过 wasm，这套「离屏 WebView
 *   + JavascriptInterface 桥 + evaluateJavascript」是验证过的路子。
 *
 * ## 安全边界
 *
 * WebView **自身不加载任何网络资源**（`setBlockNetworkLoads(true)`、
 * `about:blank` 胶水页、拦截所有导航），插件要发请求只能走 `SonanceBridge.http`，
 * 由 native 的 [HttpURLConnection] 代发。也就是说脚本没有 DOM、没有 cookie、
 * 没有本地文件访问，只有一个受控的 HTTP 出口和它自己导出的几个方法。
 *
 * 一个进程只保留一个 WebView 实例，多个插件按需切换脚本，用 [mutex] 串行化。
 */
@SuppressLint("SetJavaScriptEnabled")
class PluginRuntime(private val app: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CallResult>>()
    private var seq = 0

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var loadedFile: String? = null
    @Volatile
    private var currentScript: String = ""

    private data class CallResult(val json: String, val error: String)

    private val bridge = object {
        @JavascriptInterface
        fun script(): String = currentScript

        @JavascriptInterface
        fun result(id: Int, json: String, error: String) {
            pending.remove(id)?.complete(CallResult(json, error))
        }

        @JavascriptInterface
        fun http(id: Int, url: String, optionsJson: String) {
            // JavaBridge 线程 → 网络放 IO 线程，回调必须切回主线程
            Thread {
                val (body, err) = runCatching {
                    val o = JSONObject(optionsJson)
                    val conn = URL(url).openConnection() as HttpURLConnection
                    // 插件可以按 axios 的习惯传 timeout；没传就用默认。
                    // 上限 60s，避免插件随手写一个巨大值把 WebView 调用永久挂住
                    val pluginTimeout = o.optInt("timeout", 0)
                        .coerceIn(0, 60_000)
                        .takeIf { it > 0 }
                    conn.connectTimeout = pluginTimeout ?: 12_000
                    conn.readTimeout = pluginTimeout ?: 15_000
                    conn.requestMethod = o.optString("method", "GET").uppercase()
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                    )
                    val headers = o.optJSONObject("headers")
                    if (headers != null) {
                        keys(headers).forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
                    }
                    val bodyText = o.optString("body", "")
                    if (bodyText.isNotEmpty() && conn.requestMethod == "POST") {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        return@runCatching "" to "HTTP $code"
                    }
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    text to ""
                }.getOrElse { "" to (it.message ?: "请求失败") }
                main.post {
                    val b64 = encodeBase64(body)
                    val eJs = quoteJs(err)
                    webView?.evaluateJavascript(
                        "__resolveHttp($id, '$b64', $eJs)", null
                    )
                }
            }.start()
        }
    }

    private val pageReady = CompletableDeferred<Unit>()

    /** 确保 WebView 已建好、胶水页已加载完。必须在主线程创建并等 onPageFinished */
    private suspend fun ensure(): WebView = withContext(Dispatchers.Main) {
        val existing = webView
        if (existing != null) {
            pageReady.await()
            return@withContext existing
        }
        WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.setBlockNetworkLoads(true)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean = true
                override fun onPageFinished(v: WebView?, url: String?) {
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                }
            }
            addJavascriptInterface(bridge, "SonanceBridge")
            loadDataWithBaseURL("about:blank", GLUE_HTML, "text/html", "utf-8", null)
        }.also { webView = it }
        pageReady.await()
        webView!!
    }

    /**
     * 载入（或切换到）某个插件脚本，返回它声明的元信息与可用方法。
     * `file` 是稳定标识：同一个插件反复调用不会重新 eval。
     */
    suspend fun load(file: String, script: String): Result<PluginMeta> = mutex.withLock {
        runCatching {
            val wv = ensure()
            if (loadedFile == file) return@runCatching currentMeta
            currentScript = encodeBase64(script)
            val infoJson = evalOnce(wv, "__loadScript()")
            val o = JSONObject(infoJson)
            val platform = o.optString("platform").ifBlank { "未命名插件" }
            val meta = PluginMeta(
                platform = platform,
                version = o.optString("version", "0.0.0"),
                author = o.optString("author"),
                srcUrl = o.optString("srcUrl").takeIf { it.isNotBlank() },
                primaryKey = o.optJSONArray("primaryKey")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
            )
            loadedFile = file
            currentMeta = meta
            meta
        }
    }

    @Volatile
    private var currentMeta = PluginMeta("未命名插件", "0.0.0", "", null, emptyList())

    /** 调用插件导出的一个 async 方法，返回序列化后的 JSON 文本 */
    suspend fun call(method: String, argsJson: String, timeoutMs: Long = 20_000): Result<String> =
        mutex.withLock {
            runCatching {
                val wv = ensure()
                val id = ++seq
                val deferred = CompletableDeferred<CallResult>()
                pending[id] = deferred
                try {
                    main.post {
                        wv.evaluateJavascript("__call($id, ${quoteJs(method)}, ${quoteJs(argsJson)})", null)
                    }
                    val r = withTimeout(timeoutMs) { deferred.await() }
                    if (r.error.isNotEmpty()) error("${r.error}")
                    r.json
                } finally {
                    pending.remove(id)
                }
            }
        }

    private suspend fun evalOnce(wv: WebView, expression: String): String {
        val id = ++seq
        val deferred = CompletableDeferred<CallResult>()
        pending[id] = deferred
        return try {
            main.post {
                wv.evaluateJavascript(
                    "__result($id, (function(){ try { return String($expression) } catch(e) { return '{\"__error\":\"' + String(e) + '\"}' } })())",
                    null
                )
            }
            withTimeout(20_000) { deferred.await() }.json
        } finally {
            pending.remove(id)
        }
    }

    private fun keys(o: JSONObject): List<String> =
        o.keys().asSequence().toList()

    private fun quoteJs(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\""

    private fun encodeBase64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    companion object {
        /**
         * 胶水页。脚本通过 `SonanceBridge.script()` 取 base64 后在 JS 里 UTF-8 解码再 eval，
         * 避免把整段插件代码拼进 `evaluateJavascript` 的字符串（引号、换行、`</script>`
         * 都会出问题）。
         *
         * `require` 只提供一个精简 axios 垫片：不依赖 cheerio/crypto-js 等 npm 包的插件
         * 可以直接跑。用到那些包的插件会在导入时明确报「缺少模块」，不静默失败。
         */
        private val GLUE_HTML = """
<!doctype html><html><body><script>
(function () {
  window.module = { exports: {} };
  window.exports = window.module.exports;
  window.__pending = {}; window.__seq = 0;

  function utf8(b64) {
    var bin = atob(b64 || '');
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  }

  window.__http = function (url, optionsJson) {
    return new Promise(function (resolve, reject) {
      var id = ++window.__seq;
      window.__pending[id] = { resolve: resolve, reject: reject };
      SonanceBridge.http(id, url, optionsJson);
    });
  };
  window.__resolveHttp = function (id, b64, err) {
    var p = window.__pending[id]; if (!p) return;
    delete window.__pending[id];
    if (err) p.reject(new Error(err)); else p.resolve(utf8(b64));
  };

  // axios 垫片：按 axios 的常用语义实现 GET/POST。
  // 之前把 cfg 塞进 config 字段，而 native 桥只认 method/headers/body，
  // params / headers / timeout 全丢、请求发到裸 URL —— 任何用
  // axios.get(url, { params: {...} }) 的插件都跑不起来。这里补齐：
  //   params  -> 序列化进 query string
  //   headers -> 透传给 native 桥
  //   timeout -> 交给 HttpURLConnection
  //   response-> JSON 自动解析成对象（axios 默认行为），非 JSON 保留文本
  window.require = function (name) {
    if (name === 'axios') {
      function buildUrl(url, params) {
        if (!params) return url;
        var parts = [];
        Object.keys(params).forEach(function (k) {
          var v = params[k];
          if (v === undefined || v === null) return;
          parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
        });
        if (!parts.length) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
      }
      function request(method, url, cfg, body) {
        cfg = cfg || {};
        var options = { method: method, headers: cfg.headers || {}, body: body || '' };
        if (cfg.timeout) options.timeout = cfg.timeout;
        return window.__http(buildUrl(url, cfg.params), JSON.stringify(options))
          .then(function (text) {
            var data = text;
            if (typeof text === 'string') {
              try { data = JSON.parse(text); } catch (e) { /* 非 JSON 保留原文 */ }
            }
            return { data: data, status: 200 };
          });
      }
      return {
        get: function (url, cfg) { return request('GET', url, cfg); },
        post: function (url, body, cfg) {
          return request('POST', url, cfg,
            typeof body === 'string' ? body : JSON.stringify(body || {}));
        }
      };
    }
    throw new Error('module not available: ' + name);
  };

  window.__loadScript = function () {
    var code = utf8(SonanceBridge.script());
    (0, eval)(code);
    var m = window.module.exports || {};
    var methods = [];
    for (var k in m) if (typeof m[k] === 'function') methods.push(k);
    return JSON.stringify({
      platform: m.platform || '', version: m.version || '', author: m.author || '',
      srcUrl: m.srcUrl || '', primaryKey: m.primaryKey || [], methods: methods
    });
  };

  window.__call = function (id, method, argsJson) {
    (async function () {
      try {
        var m = window.module.exports || {};
        var fn = m[method];
        if (typeof fn !== 'function') throw new Error('插件没有实现 ' + method);
        var r = await fn.apply(m, JSON.parse(argsJson));
        var text = JSON.stringify(r === undefined ? null : r);
        SonanceBridge.result(id, text, '');
      } catch (e) {
        SonanceBridge.result(id, '', String(e && e.message ? e.message : e));
      }
    })();
  };
  window.__result = function (id, text) {
    SonanceBridge.result(id, String(text), '');
  };
})();
</script></body></html>
        """.trimIndent()
    }
}
