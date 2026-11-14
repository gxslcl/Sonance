package com.sonance.player.data.netease

import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemotePlaylistPage
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.neteasePlayable
import com.sonance.player.data.neteaseVip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 登录后在线音乐「杂志画报 Hero 版」的数据入口：
 * 每日私人推荐、个人云歌单（含我喜欢的音乐）、私人 FM 与排行榜。
 */
internal object NeteaseExplore {

    /** 虚拟歌单 ID：用于点开「每日私人推荐」进统一详情页，避免新增套壳详情页 */
    const val DAILY_RECOMMEND_PLAYLIST_ID = "sonance:daily_recommend"

    data class UserPlaylistsResult(
        val likedPlaylist: RemotePlaylist?,
        val otherPlaylists: List<RemotePlaylist>
    )

    suspend fun fetchUserPlaylists(account: NeteaseAccount): UserPlaylistsResult? =
        withContext(Dispatchers.IO) {
            if (account.userId <= 0L) return@withContext null
            val body = NeteaseHttp.get(
                path = "/api/user/playlist?uid=${account.userId}&limit=30&offset=0",
                cookie = account.cookie
            ).body ?: return@withContext null
            parseUserPlaylists(body, account.userId)
        }

    suspend fun fetchDailyRecommend(account: NeteaseAccount): List<RemoteTrack>? =
        withContext(Dispatchers.IO) {
            val v3 = NeteaseHttp.get(
                path = "/api/v3/discovery/recommend/songs",
                cookie = account.cookie
            ).body
            val fromV3 = v3?.let { parseDailyRecommendTracks(it, account.isVip) }
            if (!fromV3.isNullOrEmpty()) return@withContext fromV3

            val v1 = NeteaseHttp.get(
                path = "/api/v1/discovery/recommend/songs",
                cookie = account.cookie
            ).body
            v1?.let { parseDailyRecommendTracks(it, account.isVip) } ?: fromV3
        }

    suspend fun fetchPersonalFm(account: NeteaseAccount): List<RemoteTrack>? =
        withContext(Dispatchers.IO) {
            val body = NeteaseHttp.get(
                path = "/api/v1/radio/get",
                cookie = account.cookie
            ).body ?: return@withContext null
            parsePersonalFmTracks(body, account.isVip)
        }

    suspend fun fetchToplists(limit: Int = 6): List<RemotePlaylist>? =
        withContext(Dispatchers.IO) {
            val body = NeteaseHttp.get(path = "/api/toplist").body ?: return@withContext null
            parseToplists(body, limit)
        }

    /** 探索广场热门歌单分页：支持无限滚动持续加载。 */
    suspend fun fetchExplorePlaylists(
        limit: Int = 20,
        offset: Int = 0,
        cat: String = "全部"
    ): RemotePlaylistPage? = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        val encCat = URLEncoder.encode(cat, "UTF-8")
        val path = "/api/playlist/list?cat=$encCat&order=hot&limit=$safeLimit&offset=$safeOffset"
        val body = NeteaseHttp.get(path = path, cookie = NeteaseAuth.account?.cookie).body
            ?: return@withContext null
        parseExplorePlaylists(body, safeOffset)
    }

    /** 取歌单里可用作心动模式种子的曲目 id（优先随机挑选）。 */
    suspend fun fetchPlaylistSeedTrackId(account: NeteaseAccount, playlistId: String): String? =
        withContext(Dispatchers.IO) {
            if (playlistId.isBlank()) return@withContext null
            val body = NeteaseHttp.get(
                path = "/api/v6/playlist/detail?id=$playlistId&n=20",
                cookie = account.cookie
            ).body ?: return@withContext null
            val ids = runCatching {
                val arr = JSONObject(body).optJSONObject("playlist")?.optJSONArray("trackIds")
                    ?: return@runCatching emptyList<String>()
                buildList {
                    for (i in 0 until arr.length()) {
                        val tid = arr.optJSONObject(i)?.optLong("id") ?: continue
                        if (tid > 0L) add(tid.toString())
                    }
                }
            }.getOrDefault(emptyList())
            if (ids.isEmpty()) null else ids.random()
        }

    /**
     * 心动模式（Intelligence / 私人漫游雷达）：基于红心歌单与种子歌曲生成相似推荐队列。
     * 先走官方 EAPI，若未返回有效列表则回落到 `/api/playmode/intelligence/list` POST。
     */
    suspend fun fetchIntelligenceList(
        account: NeteaseAccount,
        songId: String,
        playlistId: String,
        count: Int = 1
    ): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        if (songId.isBlank() || playlistId.isBlank()) return@withContext null
        val headerObj = JSONObject().apply {
            put("os", "pc")
            put("appver", "3.0.18.203097")
            put("osver", "10")
            put("channel", "netease")
            if (account.musicU.isNotBlank()) put("MUSIC_U", account.musicU)
            if (account.csrf.isNotBlank()) put("__csrf", account.csrf)
        }
        val payload = JSONObject().apply {
            put("songId", songId)
            put("type", "fromPlayOne")
            put("playlistId", playlistId)
            put("startMusicId", songId)
            put("count", count.coerceAtLeast(1))
            put("header", headerObj.toString())
        }
        val eapiBody = NeteaseHttp.postEapi(
            apiPath = "/api/playmode/intelligence/list",
            payload = payload,
            cookie = account.cookie,
            userAgent = NeteaseHttp.PC_UA
        ).body
        val fromEapi = eapiBody?.let { parseIntelligenceTracks(it, account.isVip) }
        if (!fromEapi.isNullOrEmpty()) return@withContext fromEapi

        val postBody = NeteaseHttp.postForm(
            path = "/api/playmode/intelligence/list",
            params = mapOf(
                "songId" to songId,
                "type" to "fromPlayOne",
                "playlistId" to playlistId,
                "startMusicId" to songId,
                "count" to count.coerceAtLeast(1).toString()
            ),
            cookie = account.cookie
        ).body
        postBody?.let { parseIntelligenceTracks(it, account.isVip) } ?: fromEapi
    }

    internal fun parseUserPlaylists(body: String, uid: Long): UserPlaylistsResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONArray("playlist") ?: return null
        var liked: RemotePlaylist? = null
        val others = ArrayList<RemotePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pid = o.optLong("id")
            if (pid <= 0L) continue
            val item = RemotePlaylist(
                remoteId = pid.toString(),
                name = o.optString("name"),
                coverUrl = o.optString("coverImgUrl").takeIf { it.isNotBlank() },
                trackCount = o.optInt("trackCount", 0),
                creatorName = o.optJSONObject("creator")?.optString("nickname").orEmpty()
            )
            val specialType = o.optInt("specialType", 0)
            val creatorId = o.optLong("userId", o.optJSONObject("creator")?.optLong("userId") ?: 0L)
            val isLiked = liked == null &&
                (specialType == 5 || (creatorId == uid && item.name.contains("喜欢的音乐")))
            if (isLiked) {
                liked = item
            } else {
                others += item
            }
        }
        return UserPlaylistsResult(liked, others)
    }

    internal fun parseDailyRecommendTracks(body: String, isVip: Boolean): List<RemoteTrack>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONObject("data")?.optJSONArray("dailySongs")
            ?: json.optJSONArray("recommend")
            ?: return null
        return parseTrackArray(arr, isVip)
    }

    internal fun parsePersonalFmTracks(body: String, isVip: Boolean): List<RemoteTrack>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONArray("data") ?: return null
        return parseTrackArray(arr, isVip)
    }

    internal fun parseToplists(body: String, limit: Int = 6): List<RemotePlaylist>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONArray("list") ?: return null
        return buildList {
            for (i in 0 until arr.length()) {
                if (size >= limit) break
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optLong("id")
                if (pid <= 0L) continue
                add(
                    RemotePlaylist(
                        remoteId = pid.toString(),
                        name = o.optString("name"),
                        coverUrl = o.optString("coverImgUrl").takeIf { it.isNotBlank() },
                        trackCount = o.optInt("trackCount", 0),
                        creatorName = o.optString("updateFrequency").ifBlank { "官方榜单" }
                    )
                )
            }
        }
    }

    internal fun parseExplorePlaylists(body: String, offset: Int = 0): RemotePlaylistPage? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONArray("playlists") ?: return null
        val list = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optLong("id")
                if (pid <= 0L) continue
                add(
                    RemotePlaylist(
                        remoteId = pid.toString(),
                        name = o.optString("name"),
                        coverUrl = o.optString("coverImgUrl").takeIf { it.isNotBlank() },
                        trackCount = o.optInt("trackCount", 0),
                        creatorName = o.optJSONObject("creator")?.optString("nickname").orEmpty()
                    )
                )
            }
        }
        val more = json.optBoolean("more", false)
        val rawTotal = json.optInt("total", -1)
        val total = if (!more) offset + list.size else rawTotal
        return RemotePlaylistPage(list, total)
    }

    internal fun parseIntelligenceTracks(body: String, isVip: Boolean): List<RemoteTrack>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 200) return null
        val arr = json.optJSONArray("data") ?: return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val songObj = item.optJSONObject("songInfo") ?: item
                parseSingleTrack(songObj, isVip)?.let { add(it) }
            }
        }
    }

    private fun parseTrackArray(arr: JSONArray, isVip: Boolean): List<RemoteTrack> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseSingleTrack(o, isVip)?.let { add(it) }
        }
    }

    private fun parseSingleTrack(o: JSONObject, isVip: Boolean): RemoteTrack? {
        val sid = o.optLong("id")
        if (sid <= 0L) return null
        val fee = o.optInt("fee", 0)
        val album = o.optJSONObject("al") ?: o.optJSONObject("album")
        val artists = o.optJSONArray("ar") ?: o.optJSONArray("artists")
        val duration = o.optLong("dt").takeIf { it > 0L } ?: o.optLong("duration")
        return RemoteTrack(
            remoteId = sid.toString(),
            title = o.optString("name"),
            artist = joinNames(artists),
            album = album?.optString("name").orEmpty(),
            durationMs = duration,
            coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() },
            playable = neteasePlayable(fee, isVip),
            vip = neteaseVip(fee)
        )
    }

    private fun joinNames(arr: JSONArray?): String {
        if (arr == null) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotEmpty()) names += n
        }
        return names.joinToString("、")
    }
}
