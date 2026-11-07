package com.sonance.player.data.recognition

import android.util.Log
import com.sonance.player.data.netease.NcMetaFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * 网易云**听歌识曲**接口：指纹 Base64 → 候选歌曲。
 *
 * ## 接口形态（逆向自 Chrome 官方识曲插件，明文无加密无登录）
 *
 * POST `https://interface.music.163.com/api/music/audio/match`，
 * 请求体是 `application/x-www-form-urlencoded` 明文表单：
 * `sessionId`（随机 UUID）/ `algorithmCode=shazam_v2` / `duration=6` /
 * `rawdata=<指纹 base64>` / `times=2` / `decrypt=1`。
 * 响应是明文 JSON：`data.result[]` 里每项带 `song{id, name, artists, album, duration}`。
 *
 * ## 候选挑选
 *
 * 指纹是音频特征，接口返回的通常就是对的，但**返回多条时不能闭眼拿第一条**：
 * 按本地文件时长与候选 `song.duration` 的差距挑最近的（时长是本场景下唯一
 * 独立于指纹的判据）；差距超过 [DURATION_FALLBACK_MS] 时说明指纹结果整体
 * 都不像，退回第一条 —— 指纹比时长可信。
 *
 * 拿到 song id 后走 [NcMetaFetcher.fetch]（与缓存转换同一套详情+封面补全），
 * 返回的元数据交给 UI 做确认卡片，用户点头才写盘。
 */
object NeteaseRecognizer {

    private const val TAG = "SonanceFp"

    private const val ENDPOINT = "https://interface.music.163.com/api/music/audio/match"

    /** 指纹引擎的常量参数（与参考实现一致，见 fp_host.html / PcmExtractor） */
    private const val ALGORITHM_CODE = "shazam_v2"
    private const val WINDOW_SECONDS = 6
    private const val TIMES = 2

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BODY_CHARS = 256 * 1024

    /** 带浏览器 UA（同 [com.sonance.player.data.NeteaseSource]，防接口挑 UA） */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 参考实现伪装成 Chrome 扩展页发起的请求，接口认这个 origin */
    private const val ORIGIN = "chrome-extension://pgphbbekcgpfaekhcbjamjjkegcclhhd"

    /** 指纹引擎返回的时长差超过这个值就不再信任时长判据，退回第一条 */
    internal const val DURATION_FALLBACK_MS = 8_000L

    /** 一次识别的最终产出：确认卡片要展示的元数据（含已下载封面字节） */
    class Recognized(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: ByteArray?
    )

    /** 识别结果：[recognized] 成功；失败时 [error] 是面向用户的文案 */
    class MatchResult(val recognized: Recognized?, val error: String?)

    /**
     * 识别一条指纹。
     *
     * @param fpBase64 指纹（[AudioFingerprinter] 的产出）
     * @param durationMs 本地文件总时长（MediaStore 值），用于候选挑选；未知传 0
     */
    suspend fun match(fpBase64: String, durationMs: Long): MatchResult =
        withContext(Dispatchers.IO) {
            if (fpBase64.isBlank()) return@withContext MatchResult(null, "指纹为空")

            val body = buildFormBody(fpBase64, UUID.randomUUID().toString())
            val response = runCatching { post(body) }
                .onFailure { Log.w(TAG, "识曲请求失败: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
                ?: return@withContext MatchResult(null, "识别服务连接失败")

            val candidates = parseCandidates(response)
            if (candidates.isEmpty()) return@withContext MatchResult(null, "未识别出歌曲")

            val pick = pickByDuration(candidates, durationMs)
            val detail = runCatching { NcMetaFetcher.fetch(pick.id) }
                .onFailure { Log.w(TAG, "识别详情获取失败: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (detail == null || detail.title.isBlank()) {
                return@withContext MatchResult(null, "识别结果详情获取失败")
            }
            MatchResult(
                Recognized(
                    songId = pick.id,
                    title = detail.title,
                    artist = detail.artist,
                    album = detail.album,
                    cover = detail.cover
                ),
                null
            )
        }

    // ===== 纯函数（JVM 单测覆盖）=====

    /** 明文表单体；rawdata 的 base64 含 `+/=`，必须整体 URL 编码 */
    internal fun buildFormBody(fpBase64: String, sessionId: String): String =
        "sessionId=" + enc(sessionId) +
            "&algorithmCode=" + enc(ALGORITHM_CODE) +
            "&duration=" + WINDOW_SECONDS +
            "&rawdata=" + enc(fpBase64) +
            "&times=" + TIMES +
            "&decrypt=1"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 识别接口的一个候选（`data.result[].song` 的必要字段） */
    internal data class Candidate(val id: String, val durationMs: Long)

    /**
     * 解析响应 JSON 里的候选列表。
     *
     * 结构：`data.result[].song{id, duration}`。任何一层缺失都按「无候选」处理
     * —— 识别失败的形态就是 result 缺失或为空。
     */
    internal fun parseCandidates(body: String): List<Candidate> = runCatching {
        val root = JSONObject(body)
        val result = root.optJSONObject("data")?.optJSONArray("result")
            ?: return emptyList()
        buildList {
            for (i in 0 until result.length()) {
                val song = result.optJSONObject(i)?.optJSONObject("song") ?: continue
                val id = song.optLong("id")
                if (id <= 0L) continue
                add(Candidate(id.toString(), song.optLong("duration")))
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 按时长挑候选：取与 [durationMs] 差距最小的一条；
     * [durationMs] 未知（≤0）或最小差距超过 [DURATION_FALLBACK_MS] 退回第一条。
     */
    internal fun pickByDuration(candidates: List<Candidate>, durationMs: Long): Candidate {
        val first = candidates.first()
        if (durationMs <= 0L || candidates.size == 1) return first
        var best = first
        var bestDiff = Long.MAX_VALUE
        for (c in candidates) {
            val d = kotlin.math.abs(durationMs - c.durationMs)
            if (d < bestDiff) {
                bestDiff = d
                best = c
            }
        }
        return if (bestDiff > DURATION_FALLBACK_MS) first else best
    }

    // ===== 网络 =====

    private fun post(formBody: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "识曲接口 HTTP $code")
                throw IllegalStateException("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (text.length > MAX_BODY_CHARS) throw IllegalStateException("响应过大")
            text
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
