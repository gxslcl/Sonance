package com.sonance.player.data.netease

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网易云接口的统一出口。
 *
 * ## 为什么只有 GET
 *
 * 2026-11-13 实测：登录（二维码两步）、会员信息、取流 v1、搜索、详情全部可以用
 * `/api/...` 的兼容 GET 入口完成，**不需要 weapi/eapi 加密**。所以这一层刻意只做 GET，
 * 把"要不要上加密"这个决定留到真有写操作（歌单、收藏、云盘上传）时再面对。
 *
 * ## 两个失败要分开
 *
 * - [Response.body] 为 null：请求本身没成功（超时、非 2xx、响应过大）→ 给用户的是"网络问题"
 * - body 有值但 `code != 200`：接口业务失败（未登录、无版权、频率限制）→ 要按 code 说话
 *
 * 混成一种的话，未登录时会提示"检查网络"，用户怎么试都没用。
 */
internal object NeteaseHttp {

    private const val TAG = "SonanceNetease"

    const val BASE = "https://music.163.com"

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

    /** 不带任何账号信息的请求头（匿名可用） */
    private const val ANONYMOUS_COOKIE = "os=pc; appver=2.10.13"

    /**
     * 一次请求的结果。
     *
     * [setCookies] 原样保留：二维码登录的第 803 步就是靠它拿到 `MUSIC_U` 的，
     * 而那个响应体里不一定带 userId，必须从 Cookie 侧取。
     */
    data class Response(
        val body: String?,
        val httpCode: Int,
        val setCookies: List<String> = emptyList()
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
    ): Response {
        val url = if (path.startsWith("http", ignoreCase = true)) path else BASE + path
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                // 取流接口会 302 到 CDN；这里不需要跟，只要状态码和 Location。
                // 真正要下载时由 OnlineDownloader 自己跟随（它必须处理 https→http）
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "$BASE/")
                setRequestProperty("Cookie", cookie?.takeIf { it.isNotBlank() } ?: ANONYMOUS_COOKIE)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code  $url")
                return Response(null, code)
            }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
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

    /** GET 并解析成 JSON。失败（含不是 JSON）返回 null */
    fun getJson(path: String, cookie: String? = null, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject? {
        val body = get(path, cookie, readTimeoutMs).body ?: return null
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
