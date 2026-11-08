package com.sonance.player.data.plugin

import com.sonance.player.data.OnlineSource
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemotePlaylistPage
import com.sonance.player.data.RemoteSearchPage
import com.sonance.player.data.RemoteTrack
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 把一个内容源插件包装成 [OnlineSource]。
 *
 * 这样 `OnlineSources.all` 里的 chips 天然变成「网易云 / 插件A / 插件B」，
 * 切换 `onlineSourceId` 后 `search()` 走的就是不同实现 —— 搜索结果真的来自
 * 不同的源，`Song.source` 也各自独立，不会串。
 *
 * ## 分页换算
 *
 * 现有 [OnlineSource] 用 `limit/offset`，MusicFree 插件协议用 1-based `page`：
 * `page = offset / limit + 1`。插件返回 `{isEnd, data}`，`isEnd` 用来推导总数，
 * 避免现有接口 `hasMore()` 用「满页猜」在插件源上失效。
 *
 * ## 取流地址是异步的
 *
 * 插件的 `getMediaSource(musicItem, quality)` 返回 Promise，而 [OnlineSource.streamUrl]
 * 是同步方法。这里用一个进程内缓存承接：播放/下载前先 [prepareStreams] 批量预热，
 * 同步方法从缓存读；没预热到或插件失败时返回空串，由上层回退/报错。
 * **绝不缓存会过期的地址到磁盘**，缓存只在本次进程生命周期内有效。
 */
class PluginOnlineSource(
    private val runtime: PluginRuntime,
    private val record: PluginRecord,
    private val script: String,
) : OnlineSource {

    override val id: String get() = record.meta.id
    override val label: String get() = record.meta.platform

    private val urlCache = ConcurrentHashMap<String, String>()
    private val itemCache = ConcurrentHashMap<String, JSONObject>()

    /** 供 getMediaSource 回填 musicItem 用：插件返回的原始媒体项 */
    private fun remember(item: JSONObject) {
        val key = itemKey(item)
        if (key.isNotBlank()) itemCache[key] = item
    }

    private fun itemKey(item: JSONObject): String {
        val keys = record.meta.primaryKey
        if (keys.isNotEmpty()) return keys.joinToString("|") { item.optString(it) }
        return listOf("id", "mid", "songmid", "hash", "bvid", "aid")
            .firstNotNullOfOrNull { item.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: item.optString("title")
    }

    private suspend fun ensureLoaded(): Result<Unit> =
        runtime.load(record.file, script).map { }

    override suspend fun search(query: String, limit: Int, offset: Int): RemoteSearchPage? {
        val page = offset / limit.coerceAtLeast(1) + 1
        ensureLoaded().getOrNull() ?: return null
        val raw = runtime.call("search", JSONArray().apply {
            put(query); put(page); put("music")
        }.toString()).getOrElse { return null }
        return runCatching {
            val arr = itemsOf(raw)
            val tracks = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                remember(o)
                RemoteTrack(
                    remoteId = itemKey(o),
                    title = o.optString("title").ifBlank { o.optString("name") },
                    artist = o.optString("artist").ifBlank { o.optString("singer") },
                    album = o.optString("album"),
                    durationMs = durationMsOf(o),
                    coverUrl = listOf("artwork", "cover", "coverUrl", "picUrl")
                        .firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotBlank() } },
                    playable = true,
                    vip = false,
                ).takeIf { it.title.isNotBlank() }
            }
            val isEnd = runCatching { JSONObject(raw).optBoolean("isEnd", true) }.getOrDefault(true)
            RemoteSearchPage(
                tracks = tracks,
                total = if (isEnd) offset + tracks.size else -1,
            )
        }.getOrNull()
    }

    override suspend fun searchPlaylists(query: String, limit: Int, offset: Int): RemotePlaylistPage? {
        ensureLoaded().getOrNull() ?: return null
        // 搜出来的"歌单"要能打开才有意义 —— 而打开歌单靠 getMusicSheetInfo。
        // 没实现它的插件（如 kg.js 只做歌曲搜索），把歌曲伪装成歌单只会
        // 产出点进去全空的假条目，不如直接报告"这个源没有歌单"。
        if (!record.meta.methods.contains("getMusicSheetInfo")) {
            return RemotePlaylistPage(emptyList(), 0)
        }
        val page = offset / limit.coerceAtLeast(1) + 1
        val raw = runtime.call("search", JSONArray().apply {
            put(query); put(page); put("sheet")
        }.toString()).getOrElse { return null }
        return runCatching {
            val arr = itemsOf(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RemotePlaylist(
                    remoteId = o.optString("id").ifBlank { itemKey(o) },
                    name = o.optString("title").ifBlank { o.optString("name") },
                    coverUrl = o.optString("artwork").takeIf { it.isNotBlank() }
                        ?: o.optString("coverUrl").takeIf { it.isNotBlank() },
                    trackCount = o.optInt("count", -1),
                    creatorName = o.optString("artist"),
                ).takeIf { it.name.isNotBlank() }
            }
            RemotePlaylistPage(list, total = if (list.isEmpty()) 0 else -1)
        }.getOrNull()
    }

    override suspend fun recommendedPlaylists(limit: Int): List<RemotePlaylist>? {
        ensureLoaded().getOrNull() ?: return emptyList()
        return runtime.call("getTopLists", "[]").mapCatching { raw ->
            val arr = itemsOf(raw)
            val out = ArrayList<RemotePlaylist>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // MusicFree 的 getTopLists 有两种形态：
                //   分组结构 [{title, data:[榜单...]}]  —— kg.js / 官方 bilibili 用这种
                //   扁平数组 [{id, title, coverUrl}]    —— 简单插件用这种
                // 用"有没有 data/list 子数组"区分，两种都摊平成 RemotePlaylist。
                val children = o.optJSONArray("data") ?: o.optJSONArray("list")
                if (children != null) {
                    val group = o.optString("title")
                    for (j in 0 until children.length()) {
                        val c = children.optJSONObject(j) ?: continue
                        remember(c)
                        out += toPlaylist(c).copy(creatorName = group)
                    }
                } else {
                    remember(o)
                    out += toPlaylist(o)
                }
                if (out.size >= limit) break
            }
            out.take(limit)
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack>? {
        ensureLoaded().getOrNull() ?: return null
        // 用 remoteId 找回插件原始 mediaItem（列表阶段已缓存）
        val item = itemCache[playlistId] ?: JSONObject().put("id", playlistId)
        // 按插件的能力路由取法：
        //   getMusicSheetInfo —— 标准歌单（sheet, page）
        //   getTopListDetail  —— 榜单/推荐位（sheetItem），kg.js 只有这一个
        //   两者都没有时再硬调一次 getMusicSheetInfo，让插件自己报"没实现"
        val method = when {
            "getMusicSheetInfo" in record.meta.methods -> "getMusicSheetInfo"
            "getTopListDetail" in record.meta.methods -> "getTopListDetail"
            else -> "getMusicSheetInfo"
        }
        val args = JSONArray().apply {
            put(item)
            if (method == "getMusicSheetInfo") put(1)
        }
        val raw = runtime.call(method, args.toString()).getOrElse { return null }
        return runCatching {
            val arr = itemsOf(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                remember(o)
                RemoteTrack(
                    remoteId = itemKey(o),
                    title = o.optString("title").ifBlank { o.optString("name") },
                    artist = o.optString("artist").ifBlank { o.optString("singer") },
                    album = o.optString("album"),
                    durationMs = durationMsOf(o),
                    coverUrl = listOf("artwork", "cover", "coverUrl", "picUrl")
                        .firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotBlank() } },
                    playable = true,
                    vip = false,
                ).takeIf { it.title.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 播放/下载前批量预热取流地址 */
    override suspend fun prepareStreams(ids: List<String>) {
        ensureLoaded().getOrNull() ?: return
        ids.filter { !urlCache.containsKey(it) }.forEach { id ->
            val item = itemCache[id] ?: JSONObject().put("id", id)
            val raw = runtime.call("getMediaSource", JSONArray().apply {
                put(item); put("standard")
            }.toString()).getOrNull() ?: return@forEach
            val url = runCatching {
                val o = JSONObject(raw)
                o.optString("url").ifBlank { o.optString("src") }
            }.getOrElse { raw.trim('"') }
            // 插件可以返回网络地址，也可以返回自己的本地/内容提供者地址
            // （本地演示源、用户自建曲库都走这条），别只认 http。
            if (url.startsWith("http") ||
                url.startsWith("file://") ||
                url.startsWith("content://")
            ) urlCache[id] = url
        }
    }

    override fun streamUrl(remoteId: String): String = urlCache[remoteId].orEmpty()

    override fun thumbUrl(url: String, px: Int): String = url

    private fun itemsOf(raw: String): JSONArray = when {
        raw.isBlank() -> JSONArray()
        raw.trim().startsWith("[") -> JSONArray(raw)
        else -> JSONObject(raw).let { o ->
            // data/list 是 MusicFree 惯用的列表字段；musicList 是
            // getTopListDetail 的返回字段（kg.js 这类榜单型源）
            o.optJSONArray("data")
                ?: o.optJSONArray("list")
                ?: o.optJSONArray("musicList")
                ?: JSONArray()
        }
    }

    private fun durationMsOf(o: JSONObject): Long {
        val d = o.optDouble("duration", 0.0)
        if (d > 0) {
            // MusicFree 协议里 duration 单位是秒，但有的插件（kg.js）直接塞毫秒。
            // 一首歌不可能超过 100000 秒（≈27 小时），超过就按毫秒理解，
            // 否则 269 秒会被放大成 74 小时。
            return if (d > 100_000) d.toLong() else (d * 1000).toLong()
        }
        val ms = o.optLong("durationMs", 0L)
        if (ms > 0) return ms
        return o.optLong("duration", 0L)
    }

    /** 插件的榜单/歌单项 → 宿主的 RemotePlaylist（字段名做一轮兼容） */
    private fun toPlaylist(o: JSONObject): RemotePlaylist = RemotePlaylist(
        remoteId = o.optString("id").ifBlank { itemKey(o) },
        name = o.optString("title").ifBlank { o.optString("name") },
        coverUrl = listOf("coverImg", "coverUrl", "artwork", "cover", "pic")
            .firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: "",
        trackCount = -1,
        creatorName = "",
    )
}
