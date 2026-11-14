package com.sonance.player.data.netease

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云接口的统一出口。
 *
 * 常规只读与登录接口可直接走 `/api/...`；高音质与会员曲目取流在实测中需要走
 * `interface3.music.163.com/eapi/...`（AES-ECB + MD5 签名）才会稳定带上会员权益。
 */
internal object NeteaseHttp {

    private const val TAG = "SonanceNetease"

    const val BASE = "https://music.163.com"
    private const val EAPI_BASE = "https://interface3.music.163.com"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * 上限 512KB。搜索 / 歌单详情都在这以内；超了说明响应形态变了
     * （比如被重定向到一个 HTML 页面），按失败处理比拿半截 JSON 去解析更安全。
     */
    private const val MAX_BODY_CHARS = 512 * 1024

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    internal const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "NeteaseMusicDesktop/3.0.18.203097 Chrome/120.0.0.0 Safari/537.36"

    internal const val ANDROID_UA =
        "NeteaseMusic/8.10.05.230512172549(8010005);Dalvik/2.1.0 (Linux; U; Android 13)"

    /** 不带任何账号信息的请求头（匿名可用） */
    private const val ANONYMOUS_COOKIE = "os=pc; appver=3.0.18.203097"

    /**
     * 一次请求的结果。
     *
     * [setCookies] 原样保留：二维码登录的第 803 步就是靠它拿到 `MUSIC_U` 的，
     * 而那个响应体里不一定带 userId，必须从 Cookie 侧取。
     */
    data class Response(
        val body: String?,
        val httpCode: Int,
        val setCookies: List<String> = emptyList(),
        val errorBody: String? = null
    ) {
        val failed: Boolean get() = body == null
    }

    /**
     * 发一次 GET。
     *
     * @param path       以 `/` 开头的路径，或完整的 `http(s)://` 地址
     * @param cookie     要带的 Cookie；null 时只带 `os=pc; appver=2.10.13`
     */
    fun get(
        path: String,
        cookie: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Response = execute(
        method = "GET",
        path = path,
        formBody = null,
        cookie = cookie,
        readTimeoutMs = readTimeoutMs
    )

    /** 发一次 application/x-www-form-urlencoded POST */
    fun postForm(
        path: String,
        params: Map<String, String>,
        cookie: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Response {
        val encoded = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return execute(
            method = "POST",
            path = path,
            formBody = encoded,
            cookie = cookie,
            readTimeoutMs = readTimeoutMs
        )
    }

    /** 发一次 EAPI 加密 POST（响应不带 e_r=true，因此服务端直接回明文 JSON） */
    fun postEapi(
        apiPath: String,
        payload: JSONObject,
        cookie: String? = null,
        userAgent: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Response {
        val paramsHex = encryptEapiParams(apiPath, payload)
        val eapiPath = apiPath.replaceFirst("/api/", "/eapi/")
        return execute(
            method = "POST",
            path = "$EAPI_BASE$eapiPath",
            formBody = "params=$paramsHex",
            cookie = cookie,
            userAgent = userAgent,
            readTimeoutMs = readTimeoutMs
        )
    }

    /** 标准 EAPI 请求加密：`AES-128-ECB(PKCS5)` + MD5 校验串 → 大写十六进制 `params`。 */
    internal fun encryptEapiParams(apiPath: String, payload: JSONObject): String {
        val dataJson = payload.toString()
        val digestBytes = MessageDigest.getInstance("MD5")
            .digest("nobody${apiPath}use${dataJson}md5forencrypt".toByteArray(Charsets.UTF_8))
        val digestHex = buildString(digestBytes.size * 2) {
            for (b in digestBytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
        val plain = "$apiPath-36cd479b6b5-$dataJson-36cd479b6b5-$digestHex"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.US_ASCII), "AES"))
        }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return buildString(encrypted.size * 2) {
            for (b in encrypted) {
                val v = b.toInt() and 0xFF
                append("0123456789ABCDEF"[v ushr 4])
                append("0123456789ABCDEF"[v and 0x0F])
            }
        }
    }

    private fun execute(
        method: String,
        path: String,
        formBody: String?,
        cookie: String?,
        userAgent: String? = null,
        readTimeoutMs: Int
    ): Response {
        val url = if (path.startsWith("http", ignoreCase = true)) path else BASE + path
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                // 取流接口会 302 到 CDN；这里不需要跟，只要状态码和 Location。
                // 真正要下载时由 OnlineDownloader 自己跟随（它必须处理 https→http）
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent ?: UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "$BASE/")
                setRequestProperty("Cookie", cookie?.takeIf { it.isNotBlank() } ?: ANONYMOUS_COOKIE)
                if (formBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
            }
            if (formBody != null) {
                conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val errBody = runCatching {
                    stream?.bufferedReader()?.use(BufferedReader::readText)?.take(MAX_BODY_CHARS)
                }.getOrNull()
                Log.w(TAG, "HTTP $code  $url")
                return Response(null, code, collectSetCookies(conn), errBody)
            }
            val body = stream.bufferedReader().use(BufferedReader::readText)
            if (body.length > MAX_BODY_CHARS) {
                Log.w(TAG, "响应过大（${body.length} 字符），丢弃")
                Response(null, code)
            } else {
                Response(body, code, collectSetCookies(conn))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "请求失败 ${t.javaClass.simpleName}: ${t.message}")
            Response(null, -1)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** GET 并解析成 JSON（仅 2xx 成功响应）。失败（含不是 JSON / 非 2xx）返回 null */
    fun getJson(path: String, cookie: String? = null, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject? {
        val res = get(path, cookie, readTimeoutMs)
        if (res.httpCode !in 200..299) return null
        val body = res.body ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /**
     * 从响应头里取指定名的 Cookie 值。
     *
     * `HttpURLConnection` 把多个 `Set-Cookie` 塞在同一个 header 名下的列表里，
     * 但中间层也可能把它们用逗号拼成一条 —— 两种形态都要认，所以这里按
     * `,` 和 `;` 都切一遍再找 `name=`。
     */
    fun cookieValue(setCookies: List<String>, name: String): String? {
        for (raw in setCookies) {
            for (part in raw.split(',', ';')) {
                val piece = part.trim()
                if (!piece.startsWith("$name=", ignoreCase = true)) continue
                val value = piece.substring(name.length + 1).trim().trim('"')
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun collectSetCookies(conn: HttpURLConnection): List<String> {
        val fields = runCatching { conn.headerFields }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(4)
        for ((key, values) in fields) {
            if (key == null) continue
            if (!key.equals("Set-Cookie", ignoreCase = true)) continue
            out += values
        }
        return out
    }
}
