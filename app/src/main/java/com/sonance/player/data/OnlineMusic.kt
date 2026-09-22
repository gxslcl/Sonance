package com.sonance.player.data

import android.content.Context
import android.net.Uri
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
 * 在线音源。
 *
 * 抽成接口是为了以后能加第二个源（Audius / WebDAV / …）而不用动 UI 和播放层 ——
 * 加一个实现类 + 在 [OnlineSources.all] 里登记一行就够了。
 */
interface OnlineSource {

    /** 稳定标识，会写进 [Song.source]。改了会让已存的歌对不上，别随便动 */
    val id: String

    /** 显示名，给 UI 用 */
    val label: String

    /**
     * 搜索。
     *
     * **不抛异常**（网络问题不该把界面搞崩），但要区分两种"没结果"：
     *
     * | 返回 | 含义 | UI 该说 |
     * |---|---|---|
     * | `null` | 请求失败（超时 / 非 2xx / 响应看不懂） | 「搜索失败，检查一下网络」 |
     * | 空列表 | 请求成功、确实没这首歌 | 「没搜到「X」」 |
     *
     * 混成一种的话，断网时用户会看到「没搜到「周杰伦」」—— 那是会让人以为
     * 搜索坏了的说法。这套约定和 [OnlineCover.search] 一致。
     */
    suspend fun search(context: Context, query: String, limit: Int, offset: Int): List<RemoteTrack>?

    /** 这首歌的播放地址。注意：地址可能带签名会过期，**每次播放都要重新取** */
    fun streamUri(track: RemoteTrack): Uri

    /**
     * 列表缩略图地址。
     *
     * 默认原样返回；音源支持缩放约定的话可以覆写，把大图换成小图。
     * 不覆写也能用，只是列表会白白拉一整张原图。
     */
    fun thumbUrl(url: String, px: Int): String = url

    /** 把搜索结果变成能进播放队列的 [Song] */
    fun toSong(track: RemoteTrack): Song = Song(
        id = remoteSongId(id, track.remoteId),
        title = track.title,
        artist = track.artist,
        album = track.album,
        albumId = REMOTE_ALBUM_ID,
        durationMs = track.durationMs,
        uri = streamUri(track),
        folderPath = "",
        folderName = "",
        displayName = "${track.title}.mp3",
        sizeBytes = 0L,
        dateAddedSec = 0L,
        track = 0,
        source = id,
        remoteId = track.remoteId,
        coverUrl = track.coverUrl
    )
}

/**
 * 在线歌用的 [Song.albumId]。
 *
 * 必须是**负数**，两个原因：
 *
 * 1. **不能和 MediaStore 的专辑 id 撞车。** 撞了的后果不是报错，而是"静默错封面" ——
 *    [ArtworkLoader] 是按 albumId 索引缓存的，在线歌会直接显示成某个本地专辑的封面。
 * 2. **让本地封面探测直接短路。** [ArtworkLoader.isMissing] 把 `albumId <= 0`
 *    当作"本地没有封面"，于是不会为了在线歌去 MediaStore 白跑一趟。
 *
 * 抽成常量而不是就地写 `-1L`，是为了让这条约定有个名字、单测能钉住它。
 * （`toSong` 本身在 JVM 单测里构造不出来 —— 它要建 [Song]，而 `Song.uri` 非空，
 * 单测里所有拿 `Uri` 的途径都返回 null。详见 `OnlineMusicTest` 里的说明。）
 */
internal const val REMOTE_ALBUM_ID = -1L

/** 搜索结果里的一首歌（还没变成播放队列里的 [Song]） */
data class RemoteTrack(
    val remoteId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    /**
     * 能不能直接播。
     *
     * ⚠️ 这个值是从搜索结果的 `fee` 字段推出来的**预判**，不是保证。
     * 实测会员歌（`fee=1`）的外链会 302 到一张 HTML 下载页，ExoPlayer 解析不了、
     * 报的是"容器不受支持" —— 用户会误以为是文件格式问题，跑去翻「格式与兼容性」。
     * 所以 UI **必须**把不可播的标出来，不能静默过滤也不能让它进队列。
     */
    val playable: Boolean,
    /** 需要会员 */
    val vip: Boolean
)

/**
 * 在线歌在本地用的 id。
 *
 * 压成**负数**：MediaStore 给的 id 全是正数，这样两者永远不会撞。
 * 也永远不会返回 0（0 被上层当作"没有歌曲"的哨兵）。
 */
fun remoteSongId(source: String, remoteId: String): Long {
    val h = (source + ":" + remoteId).hashCode().toLong() and 0x7FFFFFFFL
    return -h - 1L
}

/**
 * 这个 id 是不是在线歌。
 *
 * [remoteSongId] 生成的 id **恒为负数**，而 MediaStore 给的 id 恒为正 ——
 * 所以判符号就够了，不用去查表。
 *
 * 本地才有意义的记账（最近播放 / 播放次数 / 上次播放的歌）都得靠它挡掉在线歌：
 * 那些负数 id 冷启动后查不到任何歌，记进去只会让「最近听过 N 首」虚高、
 * 把 SharedPreferences 撑大，而且**永远清不掉**（用户看不到它们，自然也不会去清）。
 */
fun isRemoteSongId(id: Long): Boolean = id < 0

/**
 * 在线歌映射该保留哪些 id —— 用来淘汰用不上的条目。
 *
 * 三类**必须**留：
 *
 * | 类别 | 为什么 |
 * |---|---|
 * | 这次搜索的结果 | 列表要显示 |
 * | 播放队列里的 | 队列页 / 播放页 / 收藏判断要按 id 反查；漏了队列页会凭空少几首 |
 * | 正在播的那首 | 它可能刚被移出队列但还在放 |
 *
 * 抽成纯函数是为了可测：[SonanceViewModel] 要 `Application`，JVM 单测里起不来。
 */
fun onlineSongIdsToKeep(
    resultIds: List<Long>,
    queueIds: List<Long>,
    currentMediaId: Long
): Set<Long> {
    val keep = HashSet<Long>(resultIds.size + queueIds.size + 1)
    keep.addAll(resultIds)
    keep.addAll(queueIds)
    // 0 是"没有歌曲"的哨兵，别把它塞进保留集
    if (currentMediaId != 0L) keep.add(currentMediaId)
    return keep
}

/** 音源清单。加新音源只要在这里登记 */
object OnlineSources {
    val all: List<OnlineSource> = listOf(NeteaseSource)
    fun byId(id: String?): OnlineSource? = all.firstOrNull { it.id == id }
}

/**
 * 网易云的 `fee` 字段 → 「外链能不能播」。
 *
 * 实测（2026-09，见 [NeteaseSource] 的说明）：
 *
 * | fee | 含义 | 外链结果 |
 * |---|---|---|
 * | 0 | 免费 | ✅ 302 → 真实 MP3（`audio/mpeg`） |
 * | 8 | 免费（低音质） | ✅ 同上 |
 * | 1 | 需要会员 | ❌ 302 → 下载页（`text/html`） |
 * | 4 | 需要购买 | ❌ 同上 |
 *
 * 这是**预判**不是保证 —— 版权变动会让同一首歌的 `fee` 变，所以 UI 必须把
 * 「不可播」标出来而不是静默过滤，否则用户会以为是搜索坏了。
 */
internal fun neteasePlayable(fee: Int): Boolean = fee == 0 || fee == 8

/** 网易云的 `fee` → 「是不是要会员/购买」。用于给列表打徽标 */
internal fun neteaseVip(fee: Int): Boolean = fee == 1 || fee == 4

/**
 * 网易云音源。
 *
 * ## 为什么没有加密、没有登录
 *
 * 网易云的官方开放平台是 B2B 的（音箱 / 车机 / 游戏联运），不提供音频流；
 * 社区那些 `NeteaseCloudMusicApi` 走的是 weapi/eapi 加密（要复刻 AES+RSA 签名）。
 *
 * 但**播放器真正需要的三个端点恰好都不校验签名**，实测直接可调：
 *
 * | 用途 | 端点 |
 * |---|---|
 * | 搜索 | `/api/search/get?s=&type=1&limit=&offset=` |
 * | 详情（封面） | `/api/song/detail?ids=[...]` |
 * | 播放 | `/song/media/outer/url?id=<id>.mp3` → **302 到真实 MP3** |
 *
 * 所以这里零加密、零登录、零第三方服务。代价是：
 * - **会员歌拿不到**（`fee=1` / `4` 的外链 302 到一张 HTML 下载页）→ 用 [RemoteTrack.vip] 标出来
 * - 音质是未登录的标准音质
 * - 这些都是未公开接口，网易改版就可能失效 —— 属于"能用就用，坏了再修"
 *
 * ## 两个环境前提（少一个都播不了）
 *
 * 1. **数据源要允许跨协议重定向。** 取流接口是 https，但 302 的目标是
 *    `http://m701.music.126.net/...` —— ExoPlayer 默认禁止 https→http 跳转。
 *    见 `PlaybackService.onlineDataSource()`。
 * 2. **网络安全配置要对 CDN 放行明文。** Android 9+ 默认禁止明文 HTTP，
 *    见 `res/xml/network_security_config.xml`。
 */
object NeteaseSource : OnlineSource {

    private const val TAG = "SonanceOnline"

    override val id = "netease"
    override val label = "网易云"

    private const val BASE = "https://music.163.com"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_BODY_CHARS = 512 * 1024

    /**
     * 带一个像浏览器的 UA。
     *
     * 不带的话外链接口偶尔会返回一个 HTML 页面而不是 302 —— 这是它区分
     * "网页播放器"和"脚本"的方式，不是必须登录。
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    override fun streamUri(track: RemoteTrack): Uri =
        Uri.parse("$BASE/song/media/outer/url?id=${track.remoteId}.mp3")

    /**
     * 网易云图床认 `?param=宽y高` 这个约定，会**真的缩放**再返回。
     *
     * 实测同一张专辑图（原图 1.9 MB）：
     * `param=60y60` → 12 KB，`100y100` → 30 KB，`200y200` → 100 KB，`300y300` → 205 KB。
     *
     * 搜索列表一次十几条，用原图就是 20 MB 起步 —— 所以列表**必须**走这个。
     * 参数不认识时图床会原样返回原图，不会报错，属于安全的降级。
     */
    override fun thumbUrl(url: String, px: Int): String {
        if (url.isBlank()) return url
        val size = px.coerceIn(40, 640)
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}param=${size}y$size"
    }

    override suspend fun search(
        context: Context,
        query: String,
        limit: Int,
        offset: Int
    ): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val kw = query.trim()
        if (kw.isEmpty()) return@withContext emptyList()

        val url = "$BASE/api/search/get?s=${enc(kw)}&type=1&limit=$limit&offset=$offset"
        // null = 请求失败（不是"没搜到"），见接口上的说明
        val body = get(url) ?: return@withContext null

        // 搜不到时接口返回的是 `{"result":{"songCount":0,"songs":[]}}` ——
        // `songs` 是个空数组而不是缺失，所以能走到这里；真拿不到这个字段
        // 说明响应形态变了，按失败处理比按"没结果"处理更诚实。
        val songs = runCatching {
            JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
        }.getOrNull() ?: return@withContext null

        val list = ArrayList<RemoteTrack>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val sid = o.optLong("id")
            if (sid <= 0L) continue
            val fee = o.optInt("fee", 0)
            list += RemoteTrack(
                remoteId = sid.toString(),
                title = o.optString("name"),
                artist = joinNames(o.optJSONArray("artists")),
                album = o.optJSONObject("album")?.optString("name").orEmpty(),
                durationMs = o.optLong("duration"),
                // 搜索接口不给 picUrl，下面批量补
                coverUrl = null,
                // 实测：fee=1（会员）/ 4（需购买）的外链会 302 到下载页（HTML），
                // fee=0（免费）/ 8（低音质免费）能正常播
                playable = neteasePlayable(fee),
                vip = neteaseVip(fee)
            )
        }
        if (list.isEmpty()) return@withContext emptyList()

        // 批量补封面：搜索接口只给 picId，而封面 URL 的前半段是加密串、推不出来，
        // 必须问一次详情接口。一次请求带上所有 id，不逐个查。
        val covers = fetchCovers(list.map { it.remoteId })
        if (covers.isEmpty()) {
            list
        } else {
            list.map { if (covers[it.remoteId] != null) it.copy(coverUrl = covers[it.remoteId]) else it }
        }
    }

    /** `/api/song/detail` 批量取封面。失败返回空 map，不影响搜索结果 */
    private suspend fun fetchCovers(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        // 方括号要编码，不编码会被某些中间层截断
        val url = "$BASE/api/song/detail?ids=%5B${ids.joinToString(",")}%5D"
        val body = get(url) ?: return emptyMap()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("songs") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sid = o.optLong("id")
                    val pic = o.optJSONObject("album")?.optString("picUrl").orEmpty()
                    if (sid > 0L && pic.isNotEmpty()) put(sid.toString(), pic)
                }
            }
        }.getOrDefault(emptyMap())
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 简单 GET。任何异常都返回 null（含超时、非 2xx、响应过大） */
    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "$BASE/")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "网易云接口 HTTP $code  url=$url")
                return null
            }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (body.length > MAX_BODY_CHARS) {
                Log.w(TAG, "网易云响应过大（${body.length} 字符），丢弃")
                null
            } else {
                body
            }
        } catch (t: Throwable) {
            Log.w(TAG, "网易云请求失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
