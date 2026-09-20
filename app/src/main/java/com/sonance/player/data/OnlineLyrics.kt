package com.sonance.player.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线歌词（默认关闭）。
 *
 * 用的是 [LrcAPI](https://docs.lrc.cx) 的公开实例：`GET {base}?title=&artist=&album=`
 * 直接返回 LRC 文本。它自己也支持私有部署，所以 [Prefs.lyricsApiBase] 是可配置的。
 *
 * 三条硬约束（都是为了避免被接口限流 / 浪费流量）：
 * 1. **只在用户点开歌词页时才请求**，不做任何预取、不做批量抓取
 * 2. **一次只请求一首**（在协程里串行，没有并发风暴）
 * 3. **拿到就落盘**（见 [LyricsCache]），下次同一首歌直接读本地，不再发请求
 *
 * 为什么默认关闭：这个播放器的定位是「纯本地、不联网、不上传任何数据」。
 * 联网取词属于改变产品承诺的行为，必须由用户显式打开。
 *
 * 只用 `HttpURLConnection`，不引第三方网络库 —— 需求就这么点，不值得为它加依赖。
 */
object OnlineLyrics {

    private const val TAG = "SonanceLyrics"
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_BODY_CHARS = 512 * 1024

    /**
     * 拼请求 URL。抽成纯函数是为了能单测 —— 中文 / 空格 / 特殊字符的编码
     * 一旦拼错，接口就会返回空，而现象是"一首歌词都拿不到"，很难查。
     *
     * @return 完整 URL；参数不合法时返回 null
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildLyricsUrl(
        baseUrl: String,
        title: String,
        artist: String,
        album: String
    ): String? {
        val base = baseUrl.trim()
        if (base.isEmpty() || title.isBlank()) return null
        return buildString {
            append(base)
            append(if (base.contains('?')) '&' else '?')
            append("title=").append(enc(title))
            // 空参数直接不发，省得接口拿空串去匹配、反而命中错误的歌词
            if (artist.isNotBlank()) append("&artist=").append(enc(artist))
            if (album.isNotBlank()) append("&album=").append(enc(album))
        }
    }

    /**
     * 拉取歌词。
     * @return LRC 文本；失败 / 没找到返回 null（不抛异常，调用方不用 try）
     */
    suspend fun fetch(
        baseUrl: String,
        title: String,
        artist: String,
        album: String
    ): String? = withContext(Dispatchers.IO) {
        val url = buildLyricsUrl(baseUrl, title, artist, album)
        if (url == null) {
            Log.w(TAG, "歌词请求参数不合法: title='$title' base='$baseUrl'")
            return@withContext null
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Sonance/1.0 (local music player)")
                setRequestProperty("Accept", "text/plain, application/json, */*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "歌词接口返回 HTTP $code  url=$url")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (body.isBlank()) {
                Log.i(TAG, "歌词接口返回空内容: $title - $artist")
                return@withContext null
            }
            if (body.length > MAX_BODY_CHARS) {
                Log.w(TAG, "歌词响应过大（${body.length} 字符），丢弃")
                return@withContext null
            }

            // 公开实例的 /lyrics 返回纯 LRC；如果用户把地址配成 advance 那个 JSON 端点，
            // 这里也顺手兼容一下。
            //
            // ⚠️ 别用"首字符是不是 [ 或 {"来判断 JSON —— **LRC 每行都以 [mm:ss] 开头**，
            // 那样会把所有正常歌词误判成 JSON 然后解析失败丢掉（踩过这个坑：
            // 现象就是"一首歌词都拿不到"）。这里改成"先尝试解析，失败就原样当 LRC"。
            val text = extractText(body)
            if (text.isNullOrBlank()) {
                Log.i(TAG, "歌词响应里没有可用文本: $title - $artist")
                return@withContext null
            }

            Log.i(TAG, "在线歌词命中: $title - $artist (${text.length} 字符)")
            text
        } catch (t: Throwable) {
            Log.w(TAG, "取在线歌词失败: ${t.javaClass.simpleName}: ${t.message}  url=$url")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 与 Java 的 URLEncoder 一致：空格编码成 `+`，UTF-8 百分号转义 */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * 从响应体里取出歌词文本。
     *
     * 先尝试按 JSON 解析（兼容 `advance` 端点），**解析失败就原样返回** ——
     * 这条兜底路径才是常态，因为 `/lyrics` 返回的就是纯 LRC 文本。
     */
    internal fun extractText(body: String): String {
        val trimmed = body.trimStart()
        val looksJsonish = trimmed.startsWith("[") || trimmed.startsWith("{")
        if (looksJsonish) {
            val fromJson = extractLyricsFromJson(trimmed)
            if (!fromJson.isNullOrBlank()) return fromJson
        }
        return body
    }

    private fun extractLyricsFromJson(trimmed: String): String? = runCatching {
        when {
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) null
                else arr.optJSONObject(0)?.optString("lyrics")?.takeIf { it.isNotBlank() }
            }

            else -> JSONObject(trimmed).optString("lyrics").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
