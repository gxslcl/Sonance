package com.sonance.player.data

import com.sonance.player.data.lx.LxCatalogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

enum class PlaylistLinkPlatform {
    NETEASE,
    KUGOU,
    QQ
}

internal data class KugouPlaylistTarget(
    val specialId: String? = null,
    val globalCollectionId: String? = null,
    val chainCode: String? = null
)

data class ImportedRemotePlaylist(
    val name: String,
    val sourceId: String,
    val lxPlatformId: String?,
    val tracks: List<RemoteTrack>
)

/**
 * 在线歌单链接解析与曲目抓取器：支持网易云音乐、酷狗音乐（含 `t1.kugou.com` 短链与 `global_specialid`）、QQ 音乐。
 *
 * URL 提取、目标参数解析与酷狗 H5 MD5 签名均为纯逻辑，可在 JVM 单测直接覆盖。
 */
object OnlinePlaylistImporter {

    private const val KUGOU_H5_SECRET = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val KUGOU_PAGE_SIZE = 100
    private const val KUGOU_MAX_PAGES = 20
    private const val QQ_PAGE_SIZE = 1000
    private const val QQ_MAX_PAGES = 5
    private const val MAX_REDIRECTS = 8
    private const val TIMEOUT_MS = 12_000

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9.\\-:]+(?:/[A-Za-z0-9\\-._~:/?#\\[\\]!$&'()*+,;=%]*)?",
        RegexOption.IGNORE_CASE
    )
    private val KG_SPECIAL_PATH_REGEX = Regex(
        "/(?:yy/special/single|plist/list|songlist)/(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val QQ_PLAYLIST_PATH_REGEX = Regex(
        "/(?:playsquare|playlist)/(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val DIGITS_ONLY = Regex("^\\d{4,}$")
    private val POSITIVE_INT = Regex("^[1-9]\\d*$")

    /** 从任意分享文案或纯链接中抽出第一条 http/https URL，并剔除句尾标点。 */
    internal fun extractFirstHttpUrl(rawText: String): String? {
        val m = URL_REGEX.find(rawText.trim()) ?: return null
        return m.value.trimEnd { ch ->
            ch in ",.;!?)]}）】》\"'，。；！？、"
        }.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    internal fun detectPlatform(url: String): PlaylistLinkPlatform? {
        val host = runCatching { URL(url).host.lowercase(Locale.ROOT) }.getOrDefault("")
        val target = host.ifBlank { url.lowercase(Locale.ROOT) }
        return when {
            target.contains("163.com") || target.contains("163cn.tv") || target.contains("126.net") ->
                PlaylistLinkPlatform.NETEASE
            target.contains("kugou.com") -> PlaylistLinkPlatform.KUGOU
            target.contains("qq.com") || target.contains("url.cn") -> PlaylistLinkPlatform.QQ
            else -> null
        }
    }

    /** 解析 URL 查询串与 `#/route?k=v` 哈希参数。 */
    internal fun parseQueryParams(url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val queries = buildList {
            val hashIdx = url.indexOf('#')
            val beforeHash = if (hashIdx >= 0) url.substring(0, hashIdx) else url
            val afterHash = if (hashIdx >= 0) url.substring(hashIdx + 1) else ""
            val q1 = beforeHash.indexOf('?')
            if (q1 >= 0 && q1 + 1 < beforeHash.length) add(beforeHash.substring(q1 + 1))
            val q2 = afterHash.indexOf('?')
            if (q2 >= 0 && q2 + 1 < afterHash.length) add(afterHash.substring(q2 + 1))
        }
        for (rawQuery in queries) {
            for (pair in rawQuery.split('&')) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = decodeUrlPart(pair.substring(0, eq)).lowercase(Locale.ROOT)
                val value = decodeUrlPart(pair.substring(eq + 1))
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    out[key] = value
                }
            }
        }
        return out
    }

    private fun decodeUrlPart(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    /**
     * 解析酷狗歌单目标标识。
     *
     * 酷狗分享落地页可能同时携带无效哨兵 `specialid=-2147483648` 与有效 `global_specialid=collection_...`；
     * 因此优先提取 `global_specialid` / `global_collection_id`，`specialid` 仅接受正整数。
     * 若 PC 二维码兜底链接包了一层 `qrcode=...`，会自动解包内部真实分享地址。
     */
    internal fun parseKugouTarget(url: String): KugouPlaylistTarget? {
        val params = parseQueryParams(url)
        val nested = params["qrcode"] ?: params["target"]
        if (!nested.isNullOrBlank() && nested.startsWith("http", ignoreCase = true) && nested != url) {
            parseKugouTarget(nested)?.let { return it }
        }
        val globalId = params["global_specialid"].orEmpty()
            .ifBlank { params["global_collection_id"].orEmpty() }
            .trim()
            .takeIf { it.isNotEmpty() && it != "-" && it != "0" }
        val querySpecialId = params["specialid"]?.trim()
            ?.takeIf { POSITIVE_INT.matches(it) }
        val pathSpecialId = KG_SPECIAL_PATH_REGEX.find(url)?.groupValues?.getOrNull(1)
            ?.takeIf { POSITIVE_INT.matches(it) }
        val specialId = querySpecialId ?: pathSpecialId

        val uid = params["uid"].orEmpty().ifBlank { params["userid"].orEmpty() }.trim()
        val listId = params["listid"].orEmpty().trim()
        val zlistGlobal = if (globalId == null && POSITIVE_INT.matches(uid) && POSITIVE_INT.matches(listId)) {
            "collection_3_${uid}_${listId}_0"
        } else null

        val chain = params["chain"]?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedGlobal = globalId ?: zlistGlobal
        if (resolvedGlobal == null && specialId == null && chain == null) return null
        return KugouPlaylistTarget(
            specialId = specialId,
            globalCollectionId = resolvedGlobal,
            chainCode = chain
        )
    }

    /** 解析 QQ 音乐歌单 ID（兼容 playsquare / ryqq / taoge.html / disstid / tid 参数，拒绝单曲链接）。 */
    internal fun parseQqPlaylistId(url: String): String? {
        val lower = url.lowercase(Locale.ROOT)
        if ((lower.contains("songdetail") || lower.contains("/playsong")) &&
            !lower.contains("playlist") && !lower.contains("playsquare") && !lower.contains("taoge")
        ) {
            return null
        }
        val params = parseQueryParams(url)
        val nested = params["url"] ?: params["target"]
        if (!nested.isNullOrBlank() && nested.startsWith("http", ignoreCase = true) && nested != url) {
            parseQqPlaylistId(nested)?.let { return it }
        }
        for (key in listOf("disstid", "tid", "id")) {
            val candidate = params[key]?.trim() ?: continue
            if (DIGITS_ONLY.matches(candidate)) {
                if (key != "id" || lower.contains("taoge") || lower.contains("playlist") ||
                    lower.contains("playsquare") || lower.contains("diss")
                ) {
                    return candidate
                }
            }
        }
        val pathId = QQ_PLAYLIST_PATH_REGEX.find(url)?.groupValues?.getOrNull(1)
        if (!pathId.isNullOrBlank() && DIGITS_ONLY.matches(pathId)) return pathId
        return null
    }

    /** 酷狗分享页 H5 接口签名：MD5(secret + sorted(k=v) + optionalPostBody + secret)。 */
    internal fun signKugouH5Params(params: Map<String, String>, postBody: String = ""): String {
        val sb = StringBuilder(KUGOU_H5_SECRET)
        params.keys.sorted().forEach { key ->
            sb.append(key).append('=').append(params.getValue(key))
        }
        if (postBody.isNotEmpty()) sb.append(postBody)
        sb.append(KUGOU_H5_SECRET)
        val digest = MessageDigest.getInstance("MD5").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 从任意分享文本或链接抓取出歌单名称、适配音源及曲目列表。 */
    suspend fun fetchFromShareText(rawText: String): Result<ImportedRemotePlaylist> = withContext(Dispatchers.IO) {
        val extracted = extractFirstHttpUrl(rawText)
            ?: return@withContext Result.failure(IllegalArgumentException("请输入或粘贴有效歌单链接"))
        val finalUrl = resolveFinalUrl(extracted)
            ?: return@withContext Result.failure(IllegalStateException("短链接展开失败，请检查网络连接后重试"))
        val platform = detectPlatform(finalUrl) ?: detectPlatform(extracted)
            ?: return@withContext Result.failure(IllegalArgumentException("暂不支持该链接（支持网易云、酷狗、QQ 音乐歌单）"))

        when (platform) {
            PlaylistLinkPlatform.NETEASE -> {
                val id = PlaylistImportParser.neteasePlaylistId(finalUrl)
                    ?: PlaylistImportParser.neteasePlaylistId(extracted)
                    ?: return@withContext Result.failure(IllegalArgumentException("无法从网易云链接中识别歌单 ID"))
                val detail = NeteaseSource.playlistDetailWithName(id)
                    ?: return@withContext Result.failure(IllegalStateException("未能获取网易云歌单曲目，请检查网络或确认歌单公开"))
                Result.success(
                    ImportedRemotePlaylist(
                        name = detail.first.ifBlank { "网易云导入歌单" },
                        sourceId = NeteaseSource.id,
                        lxPlatformId = null,
                        tracks = detail.second
                    )
                )
            }

            PlaylistLinkPlatform.KUGOU -> {
                val target = resolveKugouTarget(finalUrl, extracted)
                    ?: return@withContext Result.failure(IllegalArgumentException("无法从酷狗链接中识别歌单 ID"))
                val result = fetchKugouPlaylist(target)
                    ?: return@withContext Result.failure(IllegalStateException("未能获取酷狗歌单曲目，可能是私密歌单或网络异常"))
                Result.success(
                    ImportedRemotePlaylist(
                        name = result.first.ifBlank { "酷狗导入歌单" },
                        sourceId = "lx:kg",
                        lxPlatformId = "kg",
                        tracks = result.second
                    )
                )
            }

            PlaylistLinkPlatform.QQ -> {
                val disstid = parseQqPlaylistId(finalUrl) ?: parseQqPlaylistId(extracted)
                    ?: return@withContext Result.failure(IllegalArgumentException("无法从 QQ 音乐链接中识别歌单 ID"))
                val result = fetchQqPlaylist(disstid)
                    ?: return@withContext Result.failure(IllegalStateException("未能获取 QQ 音乐歌单曲目，可能是私密歌单或网络异常"))
                Result.success(
                    ImportedRemotePlaylist(
                        name = result.first.ifBlank { "QQ音乐导入歌单" },
                        sourceId = "lx:tx",
                        lxPlatformId = "tx",
                        tracks = result.second
                    )
                )
            }
        }
    }

    /** 供洛雪平台曲库页读取酷狗歌单曲目（兼容 `collection_...` 与数字 `specialid`）。 */
    internal suspend fun fetchKugouTracksByTarget(idOrGid: String): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val trimmed = idOrGid.trim()
        if (trimmed.isEmpty()) return@withContext null
        val target = if (trimmed.startsWith("collection_", ignoreCase = true)) {
            KugouPlaylistTarget(globalCollectionId = trimmed)
        } else if (POSITIVE_INT.matches(trimmed)) {
            KugouPlaylistTarget(specialId = trimmed)
        } else {
            null
        } ?: return@withContext null
        fetchKugouTracksOnly(target)
    }

    private fun resolveKugouTarget(finalUrl: String, initialUrl: String): KugouPlaylistTarget? {
        val rawTarget = parseKugouTarget(finalUrl) ?: parseKugouTarget(initialUrl) ?: return null
        if (rawTarget.globalCollectionId != null || rawTarget.specialId != null) return rawTarget
        val chain = rawTarget.chainCode ?: return null
        return fetchKugouTargetFromChain(chain)
    }

    private fun fetchKugouTargetFromChain(chain: String): KugouPlaylistTarget? {
        val reqBody = JSONObject().apply {
            put("appid", 1001)
            put("clientver", 9020)
            put("mid", "21511157a05844bd085308bc76ef3343")
            put("clienttime", 640612895)
            put("key", "36164c4015e704673c588ee202b9ecb8")
            put("data", chain)
        }.toString()
        val resp = httpRequest(
            url = "http://t.kugou.com/command/",
            method = "POST",
            body = reqBody,
            headers = mapOf("Content-Type" to "application/json")
        ) ?: return null
        return runCatching {
            val info = JSONObject(resp).optJSONObject("data")?.optJSONObject("info") ?: return@runCatching null
            val listId = info.optString("listid").trim().takeIf { POSITIVE_INT.matches(it) }
            val uid = info.optString("userid").trim().takeIf { POSITIVE_INT.matches(it) }
            val gid = info.optString("global_collection_id").trim().takeIf { it.isNotEmpty() }
                ?: if (uid != null && listId != null) "collection_3_${uid}_${listId}_0" else null
            val sid = info.optString("specialid").trim().takeIf { POSITIVE_INT.matches(it) }
            if (gid != null || sid != null) KugouPlaylistTarget(specialId = sid, globalCollectionId = gid) else null
        }.getOrNull()
    }

    private fun fetchKugouPlaylist(target: KugouPlaylistTarget): Pair<String, List<RemoteTrack>>? {
        val info = fetchKugouListMeta(target)
        val effectiveTarget = if (info?.second != null && target.globalCollectionId == null) {
            target.copy(globalCollectionId = info.second)
        } else target
        val tracks = fetchKugouTracksOnly(effectiveTarget) ?: return null
        val name = info?.first.orEmpty()
        return name to tracks
    }

    private fun fetchKugouListMeta(target: KugouPlaylistTarget): Pair<String, String?>? {
        val ts = System.currentTimeMillis().toString()
        val item = JSONObject().apply {
            put("specialid", target.specialId?.toLongOrNull() ?: 0L)
            if (!target.globalCollectionId.isNullOrBlank()) {
                put("global_collection_id", target.globalCollectionId)
            }
        }
        val body = JSONObject().apply {
            put("data", org.json.JSONArray().put(item))
        }.toString()
        val query = linkedMapOf(
            "srcappid" to "2919",
            "clientver" to "1000",
            "clienttime" to ts,
            "mid" to ts,
            "uuid" to ts,
            "dfid" to "-"
        )
        query["signature"] = signKugouH5Params(query, body)
        val qs = encodeQuery(query)
        val raw = httpRequest(
            url = "https://pubsongs.kugou.com/v1/get_list_info?$qs",
            method = "POST",
            body = body,
            headers = mapOf("Content-Type" to "application/json")
        ) ?: httpRequest(
            url = "https://pubsongsretry.kugou.com/v1/get_list_info?$qs",
            method = "POST",
            body = body,
            headers = mapOf("Content-Type" to "application/json")
        ) ?: return null
        return runCatching {
            val first = JSONObject(raw).optJSONArray("data")?.optJSONObject(0) ?: return@runCatching null
            val name = LxCatalogProvider.cleanKgField(first.optString("name"))
            val gid = first.optString("global_collection_id").trim().takeIf { it.isNotEmpty() }
            name to gid
        }.getOrNull()
    }

    private fun fetchKugouTracksOnly(target: KugouPlaylistTarget): List<RemoteTrack>? {
        val out = ArrayList<RemoteTrack>()
        for (page in 1..KUGOU_MAX_PAGES) {
            val ts = System.currentTimeMillis().toString()
            val params = linkedMapOf(
                "srcappid" to "2919",
                "clientver" to "1000",
                "clienttime" to ts,
                "mid" to ts,
                "uuid" to ts,
                "dfid" to "-",
                "appid" to "1058",
                "uid" to "0",
                "token" to "",
                "module" to "playlist",
                "specialid" to (if (target.globalCollectionId != null) "0" else (target.specialId ?: "0")),
                "page" to page.toString(),
                "pagesize" to KUGOU_PAGE_SIZE.toString()
            )
            target.globalCollectionId?.let { params["global_collection_id"] = it }
            params["signature"] = signKugouH5Params(params)
            val qs = encodeQuery(params)
            val raw = httpRequest("https://pubsongscdn.kugou.com/v2/get_other_list_file?$qs")
                ?: httpRequest("https://pubsongscdnretry.kugou.com/v2/get_other_list_file?$qs")
                ?: if (page == 1 && target.specialId != null) {
                    val legacy = httpRequest(
                        "http://mobilecdn.kugou.com/api/v3/special/song?specialid=${enc(target.specialId)}&plat=0&page=1&pagesize=$KUGOU_PAGE_SIZE&version=9108"
                    )
                    return legacy?.let { LxCatalogProvider.parseKgSheetTracks(it) }
                } else {
                    return if (out.isNotEmpty()) out else null
                }
            val parsed = runCatching {
                val root = JSONObject(raw)
                if (root.optInt("status", 0) != 1) return@runCatching null
                val data = root.optJSONObject("data") ?: return@runCatching null
                val arr = data.optJSONArray("info") ?: return@runCatching null
                val total = data.optInt("count", -1)
                LxCatalogProvider.parseKgTrackInfoArray(arr) to total
            }.getOrNull() ?: return if (out.isNotEmpty()) out else null

            val pageTracks = parsed.first
            val total = parsed.second
            out += pageTracks
            if (pageTracks.size < KUGOU_PAGE_SIZE || (total > 0 && out.size >= total)) break
        }
        return if (out.isEmpty()) null else out
    }

    private fun fetchQqPlaylist(disstid: String): Pair<String, List<RemoteTrack>>? {
        val numericId = disstid.toLongOrNull() ?: return null
        val out = ArrayList<RemoteTrack>()
        var playlistTitle = ""

        for (pageIdx in 0 until QQ_MAX_PAGES) {
            val begin = pageIdx * QQ_PAGE_SIZE
            val body = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("cv", 4747474)
                    put("ct", 24)
                    put("format", "json")
                    put("inCharset", "utf-8")
                    put("outCharset", "utf-8")
                    put("platform", "yqq.json")
                    put("needNewCode", 1)
                    put("uin", 0)
                })
                put("req_1", JSONObject().apply {
                    put("module", "music.srfDissInfo.aiDissInfo")
                    put("method", "uniform_get_Dissinfo")
                    put("param", JSONObject().apply {
                        put("disstid", numericId)
                        put("userinfo", 1)
                        put("tag", 1)
                        put("orderlist", 1)
                        put("song_begin", begin)
                        put("song_num", QQ_PAGE_SIZE)
                        put("onlysonglist", 0)
                        put("enc_host_uin", "")
                    })
                })
            }.toString()

            val raw = httpRequest(
                url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
                method = "POST",
                body = body,
                headers = mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Origin" to "https://y.qq.com",
                    "Referer" to "https://y.qq.com/n/yqq/playsquare/$disstid.html"
                )
            )
            if (raw == null) {
                if (pageIdx == 0) break else return playlistTitle to out
            }
            val parsed = runCatching {
                val req1 = JSONObject(raw).optJSONObject("req_1") ?: return@runCatching null
                if (req1.optInt("code", -1) != 0) return@runCatching null
                val data = req1.optJSONObject("data") ?: return@runCatching null
                val dirInfo = data.optJSONObject("dirinfo")
                val title = dirInfo?.optString("title")?.trim().orEmpty()
                val total = dirInfo?.optInt("songnum", -1)
                    ?.takeIf { it > 0 }
                    ?: data.optInt("total_song_num", -1)
                val arr = data.optJSONArray("songlist") ?: return@runCatching null
                Triple(title, total, LxCatalogProvider.parseTxSongListArray(arr))
            }.getOrNull()

            if (parsed == null) {
                if (pageIdx == 0) break else return playlistTitle to out
            }
            if (playlistTitle.isBlank() && parsed.first.isNotBlank()) {
                playlistTitle = LxCatalogProvider.cleanKgField(parsed.first)
            }
            val pageTracks = parsed.third
            out += pageTracks
            if (pageTracks.size < QQ_PAGE_SIZE || (parsed.second > 0 && out.size >= parsed.second)) {
                return if (out.isEmpty()) null else (playlistTitle to out)
            }
        }
        if (out.isNotEmpty()) return playlistTitle to out

        // 兜底：旧 Qzone cdinfo 接口
        val legacyRaw = httpRequest(
            url = "https://i.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=${enc(disstid)}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
            headers = mapOf(
                "Referer" to "https://y.qq.com/n/yqq/playlist",
                "Cookie" to "uin=0"
            )
        ) ?: return null
        return runCatching {
            val cd0 = JSONObject(legacyRaw).optJSONArray("cdlist")?.optJSONObject(0) ?: return@runCatching null
            val title = LxCatalogProvider.cleanKgField(cd0.optString("dissname"))
            val arr = cd0.optJSONArray("songlist") ?: return@runCatching null
            val tracks = LxCatalogProvider.parseTxSongListArray(arr)
            if (tracks.isEmpty()) null else (title to tracks)
        }.getOrNull()
    }

    /** 手动跟随跨协议 30x 重定向，直到拿到最终落地 URL（若 URL 本身已包含有效 ID 则免请求直接返回）。 */
    private fun resolveFinalUrl(initialUrl: String): String? {
        if (PlaylistImportParser.neteasePlaylistId(initialUrl) != null ||
            parseKugouTarget(initialUrl) != null ||
            parseQqPlaylistId(initialUrl) != null
        ) {
            return initialUrl
        }
        var current = initialUrl
        for (step in 0 until MAX_REDIRECTS) {
            var conn: HttpURLConnection? = null
            try {
                val parsed = URL(current)
                conn = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", MOBILE_UA)
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val next = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return current
                    current = next
                    if (PlaylistImportParser.neteasePlaylistId(current) != null ||
                        parseKugouTarget(current) != null ||
                        parseQqPlaylistId(current) != null
                    ) {
                        return current
                    }
                    continue
                }
                return current
            } catch (_: Exception) {
                return if (step == 0) null else current
            } finally {
                conn?.disconnect()
            }
        }
        return current
    }

    private fun httpRequest(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MOBILE_UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
