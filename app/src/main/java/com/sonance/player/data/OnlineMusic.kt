package com.sonance.player.data

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
     *
     * ## 为什么要连总数一起返回
     *
     * 分页要判断"后面还有没有"，而**不能**用"这一页返回满了吗"来猜 ——
     * 实测网易云在 `offset` 超过总数时会**绕回第一页**（返回和 offset=0 完全相同的
     * 一批），于是"满页"永远成立，翻页会一直翻下去。必须拿接口报的总数来算。
     *
     * ## 为什么没有 `context` 参数
     *
     * 原来签名里有，但**没有任何实现用到它**（当时的想法是"以后可能有音源需要
     * 缓存目录"）。而它正好挡住了真实网络测试：JVM 单测里造不出 `Context`
     * （`isReturnDefaultValues` 下所有 framework 方法都返回默认值），
     * 于是整个音源层无法做集成测试。真有音源需要 Context，让它自己在构造器里持有。
     */
    suspend fun search(query: String, limit: Int, offset: Int): RemoteSearchPage?

    /**
     * 搜索歌单。
     *
     * 和 [search] 同一套约定：`null` = 请求失败，空列表 = 确实没有。
     */
    suspend fun searchPlaylists(query: String, limit: Int, offset: Int): RemotePlaylistPage?

    /**
     * 首页推荐歌单。
     *
     * `null` = 请求失败（和 [search] 一致）；空列表 = 音源确实没给推荐。
     */
    suspend fun recommendedPlaylists(limit: Int): List<RemotePlaylist>?

    /**
     * 一个歌单里的**全部**曲目，元数据已补全，可直接进播放 / 下载队列。
     *
     * `null` = 请求失败；空列表 = 歌单确实是空的。
     *
     * ⚠️ 网易云的歌单详情接口只给歌曲 id 列表（`trackIds`），不带歌名 / 歌手 /
     * 时长 / fee，所以实现里必须再按批补一次详情 —— 大歌单会产生多次请求。
     */
    suspend fun playlistTracks(playlistId: String): List<RemoteTrack>?

    /**
     * 这首歌的取流地址。
     *
     * 返回 **String** 而不是 `Uri`：播放和下载共用同一个地址，而下载走
     * `HttpURLConnection`（要字符串）。统一成字符串之后，
     * 音源的地址拼装逻辑就**可以在 JVM 单测里直接验**（`Uri.parse` 在单测里是桩，
     * 返回 null，包成 Uri 反而测不了）。
     *
     * ⚠️ 地址带签名会过期，**每次播放/下载都要重新取**。
     */
    fun streamUrl(remoteId: String): String

    /** [streamUrl] 的 Uri 包装，给播放器用 */
    fun streamUri(track: RemoteTrack): Uri = Uri.parse(streamUrl(track.remoteId))

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

/**
 * 一次搜索请求的结果。
 *
 * [total] 是音源报的**总条数**，分页要靠它判断"后面还有没有"。
 * 拿不到时给 -1，调用方按"不知道"处理（只翻到上限为止）。
 */
data class RemoteSearchPage(
    val tracks: List<RemoteTrack>,
    /** 总条数；-1 表示音源没报 */
    val total: Int = -1
) {
    /**
     * 从 [offset] 起取了这一页之后，后面还有没有。
     *
     * **不能用 `tracks.size >= limit` 来猜**：实测网易云在 offset 超过总数时
     * 会绕回第一页返回满页，"满页"永远成立，翻页就停不下来了。
     */
    fun hasMore(offset: Int, limit: Int): Boolean = when {
        total < 0 -> tracks.size >= limit      // 音源没报总数：退回保守的猜测
        else -> offset + tracks.size < total
    }
}

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
 * 一个歌单（搜索 / 推荐结果里的一条）。
 *
 * 只带**列表展示够用**的字段。曲目要等进详情页时再按需拉 —— 一次歌单搜索
 * 会回来几十个歌单，每个都顺手去拉曲目列表就是几十次请求，没有必要。
 */
data class RemotePlaylist(
    val remoteId: String,
    val name: String,
    val coverUrl: String?,
    /** 歌单曲目数（音源报的，可能和实际能取回的有出入） */
    val trackCount: Int,
    /** 创建者昵称；推荐接口不返回，此时为空串 */
    val creatorName: String
)

/**
 * 一次歌单搜索的结果。
 *
 * [total] 语义和 [RemoteSearchPage.total] 完全一致（分页靠它判断"后面还有没有"），
 * 只是 [RemoteSearchPage.hasMore] 那句警告同样适用 —— 不能拿"这页满没满"去猜。
 */
data class RemotePlaylistPage(
    val playlists: List<RemotePlaylist>,
    /** 总条数；-1 表示音源没报 */
    val total: Int = -1
) {
    fun hasMore(offset: Int, limit: Int): Boolean = when {
        total < 0 -> playlists.size >= limit
        else -> offset + playlists.size < total
    }
}

/**
 * 在线歌的**可持久化**形态。
 *
 * 为什么不直接把 [Song] 存下来：
 * - [Song.uri] 是**带签名、会过期**的播放地址，存了也是废数据；
 * - [Song.id] 是从 (音源, 远端 id) 哈希出来的，随时能重算，不必存；
 * - [Song] 里带 `android.net.Uri`，而 JVM 单测里 `Uri.parse` 是返回 null 的桩 ——
 *   存它，这套"存了什么、恢复了什么"的规则就测不了。
 *
 * 所以只留"足够把这首歌重新找回来"的最小信息。恢复走 [toSong]，
 * 播放地址**当场重新取** —— 正好也符合音源"地址会过期"的约定。
 */
data class StoredOnlineSong(
    val source: String,
    val remoteId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val playable: Boolean,
    val vip: Boolean
) {
    /** 变回搜索结果，好复用 [OnlineSource.toSong] 拼出完整 [Song] */
    fun toTrack(): RemoteTrack = RemoteTrack(
        remoteId = remoteId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        playable = playable,
        vip = vip
    )

    /** 恢复成能播的 [Song]。音源不在清单里（配置被改坏）时返回 null */
    fun toSong(): Song? = OnlineSources.byId(source)?.toSong(toTrack())

    /**
     * 它对应的 [Song.id]，和播放时用的是同一个值 ——
     * 「正在播的是不是这一首」这类判断靠它对上。
     */
    fun songId(): Long = remoteSongId(source, remoteId)
}

/** 搜索结果 → 存档（列表里点播放时用，[RemoteTrack] 的信息最全） */
fun RemoteTrack.toStored(source: String): StoredOnlineSong = StoredOnlineSong(
    source = source,
    remoteId = remoteId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    coverUrl = coverUrl,
    playable = playable,
    vip = vip
)

/**
 * 正在播的 [Song] → 存档。本地歌返回 null。
 *
 * `playable` 写 true、`vip` 写 false：**能播起来本身就说明它可播**，
 * 而这两个字段只在列表展示时有用（打徽标 / 过滤），存档是用来恢复播放和
 * 记「最近播放」的，用不上精确值。
 */
fun Song.toStoredOrNull(): StoredOnlineSong? {
    val rid = remoteId ?: return null
    if (isLocal) return null
    return StoredOnlineSong(source, rid, title, artist, album, durationMs, coverUrl, true, false)
}

/** 存档 → JSON */
fun storedOnlineSongToJson(s: StoredOnlineSong): JSONObject = JSONObject().apply {
    put("source", s.source)
    put("remoteId", s.remoteId)
    put("title", s.title)
    put("artist", s.artist)
    put("album", s.album)
    put("durationMs", s.durationMs)
    if (s.coverUrl != null) put("coverUrl", s.coverUrl)
    put("playable", s.playable)
    put("vip", s.vip)
}

/**
 * JSON → 存档。字段缺失 / 类型不对就返回 null。
 *
 * 这是从 SharedPreferences 读回来的东西，版本升级、手改配置都可能留下脏数据，
 * 不值得为它崩一次 —— 丢一条记录比整个界面挂掉好。
 */
fun storedOnlineSongFromJson(o: JSONObject): StoredOnlineSong? {
    val source = o.optString("source")
    val remoteId = o.optString("remoteId")
    // 这两个是"把歌找回来"的最小凭据，缺一个就等于这条废了
    if (source.isBlank() || remoteId.isBlank()) return null
    return StoredOnlineSong(
        source = source,
        remoteId = remoteId,
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optString("album"),
        durationMs = o.optLong("durationMs"),
        coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
        playable = o.optBoolean("playable", true),
        vip = o.optBoolean("vip", false)
    )
}

/** 一批存档 → JSON 数组 */
fun storedOnlineSongsToJson(list: List<StoredOnlineSong>): String {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(storedOnlineSongToJson(it)) }
    return arr.toString()
}

/** JSON 数组 → 一批存档。坏条目跳过 */
fun storedOnlineSongsFromJson(raw: String?): List<StoredOnlineSong> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                storedOnlineSongFromJson(o)?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 在线歌在本地用的 id。
 *
 * ## 为什么不用 `String.hashCode()`
 *
 * `hashCode()` 只有 **32 位**，几千首歌就有约 1% 的撞车概率（生日问题：
 * `1 - exp(-N²/2³³)`，N=10000 时约 1.2%）。撞了之后的症状是**静默的**：
 * `onlineSongMap` 里后一首覆盖前一首，用户点 A 会播成 B，而且只在
 * 特定的两首歌之间出现，几乎不可能复现。
 *
 * 这里用 **FNV-1a 64 位**，把概率压到可忽略。顺带还消掉了原实现里
 * `"$source:$remoteId"` 的字符串拼接 —— 这个函数在搜索列表的每行每次重组
 * 都会被调用，是无谓的分配。
 *
 * ## 为什么结果必须落在特定的负数区间
 *
 * - **恒为负**：MediaStore 的 id 恒为正，「符号」就是本地/在线的判据
 *   （见 [isRemoteSongId]），本地统计和收藏靠它挡掉在线歌。
 * - **不是 0**：0 在这个项目里是「没有歌曲」的保留值。
 * - **不是 -1**：`PlayerUiState.mediaId` 和几个对话框的默认值都用它当哨兵，
 *   撞上会让「没有歌曲」被误判成「正在播这首歌」。
 *
 * 所以先取 62 位，再整体挪到 `[-2⁶² - 1, -2]`。
 */
fun remoteSongId(source: String, remoteId: String): Long {
    var h = FNV_OFFSET_BASIS
    for (c in source) {
        h = (h xor c.code.toLong()) * FNV_PRIME
    }
    h = (h xor SEPARATOR) * FNV_PRIME
    for (c in remoteId) {
        h = (h xor c.code.toLong()) * FNV_PRIME
    }
    // 取 62 位：留出余量，`+2` 之后不会溢出 Long
    val mag = h and 0x3FFFFFFFFFFFFFFFL
    return -(mag + 2L)
}

/** FNV-1a 64 位偏移基数 */
private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL

/** FNV-1a 64 位质数 */
private const val FNV_PRIME = 0x100000001b3L

/** 拼 source 和 remoteId 时的分隔符，避免 ("ab","c") 和 ("a","bc") 撞上 */
private const val SEPARATOR = 0x3AL // ':'

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
 * 这个地址是不是**远程**的（在线歌的取流 URL）。
 *
 * 本地封面探测必须把这类地址挡在外面 —— `MediaMetadataRetriever.setDataSource(url)`
 * 和 `ContentResolver.loadThumbnail(httpUri)` 都会**真的把整个音频流拉下来**
 * 再去找内嵌图：一首歌几十 MB、耗时十几秒。
 *
 * 两处消费方都靠它：
 *
 * | 谁 | 不挡会怎样 |
 * |---|---|
 * | `ArtworkLoader` | 占着解码许可（只有 2 个）十几秒 → 整条封面流水线堵死，表现是"封面都不出来了" |
 * | `WidgetCover` | 撑爆小组件 `onUpdate` 约 10 秒的预算 → 被系统判 ANR |
 *
 * 在线歌的封面本来就走 `coverKey` → 磁盘缓存那条路，不需要探测流本身。
 */
fun isRemoteUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
}

/** [isRemoteUrl] 的 Uri 版本 */
fun isRemoteUri(uri: Uri?): Boolean = isRemoteUrl(uri?.toString())

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

    /** 搜索接口的 `type`：1 = 单曲，1000 = 歌单 */
    private const val TYPE_PLAYLIST = 1000

    /**
     * 批量补歌曲元数据时一次带多少个 id。
     *
     * 100 是实测的稳妥值：再往上（试过 200）响应体接近 [MAX_BODY_CHARS]，
     * 偶发被截断，而截断在解析层只表现为"少了几首"，很难发现。
     */
    private const val DETAIL_BATCH = 100

    /**
     * 带一个像浏览器的 UA。
     *
     * 不带的话外链接口偶尔会返回一个 HTML 页面而不是 302 —— 这是它区分
     * "网页播放器"和"脚本"的方式，不是必须登录。
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    override fun streamUrl(remoteId: String): String =
        "$BASE/song/media/outer/url?id=$remoteId.mp3"

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
        query: String,
        limit: Int,
        offset: Int
    ): RemoteSearchPage? = withContext(Dispatchers.IO) {
        val kw = query.trim()
        if (kw.isEmpty()) return@withContext RemoteSearchPage(emptyList(), 0)

        val url = "$BASE/api/search/get?s=${enc(kw)}&type=1&limit=$limit&offset=$offset"
        // null = 请求失败（不是"没搜到"），见接口上的说明
        val body = get(url) ?: return@withContext null

        // 搜不到时接口返回的是 `{"result":{"songCount":0,"songs":[]}}` ——
        // `songs` 是个空数组而不是缺失，所以能走到这里；真拿不到这个字段
        // 说明响应形态变了，按失败处理比按"没结果"处理更诚实。
        val result = runCatching { JSONObject(body).optJSONObject("result") }.getOrNull()
            ?: return@withContext null
        val songs = result.optJSONArray("songs") ?: return@withContext null
        // 总条数：分页靠它判断"后面还有没有"。拿不到给 -1，调用方按"不知道"处理
        val total = if (result.has("songCount")) result.optInt("songCount", -1) else -1

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
        if (list.isEmpty()) return@withContext RemoteSearchPage(emptyList(), total)

        // 批量补封面：搜索接口只给 picId，而封面 URL 的前半段是加密串、推不出来，
        // 必须问一次详情接口。一次请求带上所有 id，不逐个查。
        val covers = fetchCovers(list.map { it.remoteId })
        val filled = if (covers.isEmpty()) {
            list
        } else {
            list.map { if (covers[it.remoteId] != null) it.copy(coverUrl = covers[it.remoteId]) else it }
        }
        RemoteSearchPage(filled, total)
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

    // ===== 歌单 =====

    /**
     * 歌单搜索：和单曲搜索**同一个端点**，只是 `type` 从 1 换成 [TYPE_PLAYLIST]。
     *
     * 返回字段和单曲搜索不同：这里给 `coverImgUrl` 和 `creator.nickname`。
     */
    override suspend fun searchPlaylists(
        query: String,
        limit: Int,
        offset: Int
    ): RemotePlaylistPage? = withContext(Dispatchers.IO) {
        val kw = query.trim()
        if (kw.isEmpty()) return@withContext RemotePlaylistPage(emptyList(), 0)

        val url = "$BASE/api/search/get?s=${enc(kw)}&type=$TYPE_PLAYLIST&limit=$limit&offset=$offset"
        val body = get(url) ?: return@withContext null
        val result = runCatching { JSONObject(body).optJSONObject("result") }.getOrNull()
            ?: return@withContext null
        val arr = result.optJSONArray("playlists") ?: return@withContext null
        val total = if (result.has("playlistCount")) result.optInt("playlistCount", -1) else -1

        val list = ArrayList<RemotePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pid = o.optLong("id")
            if (pid <= 0L) continue
            list += RemotePlaylist(
                remoteId = pid.toString(),
                name = o.optString("name"),
                coverUrl = o.optString("coverImgUrl").takeIf { it.isNotBlank() },
                trackCount = o.optInt("trackCount", 0),
                creatorName = o.optJSONObject("creator")?.optString("nickname").orEmpty()
            )
        }
        RemotePlaylistPage(list, total)
    }

    /**
     * 推荐歌单：`/api/personalized/playlist`。
     *
     * ⚠️ 这个接口给的是 `picUrl`（不是搜索接口的 `coverImgUrl`），而且**没有
     * `creator` 字段** —— 两个接口的字段名不一样，混用会静默拿到空封面。
     */
    override suspend fun recommendedPlaylists(limit: Int): List<RemotePlaylist>? =
        withContext(Dispatchers.IO) {
            val body = get("$BASE/api/personalized/playlist?limit=$limit")
                ?: return@withContext null
            val arr = runCatching { JSONObject(body).optJSONArray("result") }.getOrNull()
                ?: return@withContext null
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pid = o.optLong("id")
                    if (pid <= 0L) continue
                    add(
                        RemotePlaylist(
                            remoteId = pid.toString(),
                            name = o.optString("name"),
                            coverUrl = o.optString("picUrl").takeIf { it.isNotBlank() },
                            trackCount = o.optInt("trackCount", 0),
                            creatorName = ""
                        )
                    )
                }
            }
        }

    /**
     * 歌单全部曲目。
     *
     * ## 为什么是两次请求
     *
     * `/api/v6/playlist/detail` 的 `tracks` 字段只给**前若干首**（`n` 控制），
     * 而 `trackIds` 是完整的 —— 但 `trackIds` 里只有 id，没有歌名 / 歌手 / 时长 / fee。
     * 所以拿完 id 还要走 [fetchTrackDetails] 批量补全，每 [DETAIL_BATCH] 个一批。
     * 500 首的歌单 ≈ 1 次详情 + 5 次批量，这是接口形态决定的，绕不开。
     *
     * `n=1` 是故意的：只要 `trackIds`，`tracks` 那 1 首白送的不用，省流量。
     */
    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack>? =
        withContext(Dispatchers.IO) {
            val body = get("$BASE/api/v6/playlist/detail?id=$playlistId&n=1")
                ?: return@withContext null
            val playlist = runCatching {
                JSONObject(body).optJSONObject("playlist")
            }.getOrNull() ?: return@withContext null
            val idsArr = playlist.optJSONArray("trackIds") ?: return@withContext emptyList()

            val ids = ArrayList<String>(idsArr.length())
            for (i in 0 until idsArr.length()) {
                val id = idsArr.optJSONObject(i)?.optLong("id") ?: continue
                if (id > 0L) ids += id.toString()
            }
            if (ids.isEmpty()) return@withContext emptyList()

            val out = ArrayList<RemoteTrack>(ids.size)
            for (chunk in ids.chunked(DETAIL_BATCH)) {
                // 某一批失败就整体按失败处理 —— 返回"半个歌单"比报错更让人困惑：
                // 用户会以为歌单本来就这么短，然后去下一半
                val part = fetchTrackDetails(chunk) ?: return@withContext null
                out += part
            }
            out
        }

    /**
     * `/api/song/detail` 批量取完整元数据。
     *
     * 失败返回 null（**不是**空列表）：这里缺数据就拼不出可用的曲目列表，
     * 必须让调用方知道是"请求失败"而不是"歌单是空的"。
     */
    private suspend fun fetchTrackDetails(ids: List<String>): List<RemoteTrack>? {
        if (ids.isEmpty()) return emptyList()
        val url = "$BASE/api/song/detail?ids=%5B${ids.joinToString(",")}%5D"
        val body = get(url) ?: return null
        return runCatching {
            val arr = JSONObject(body).optJSONArray("songs") ?: return@runCatching null
            // 显式给 buildList 类型参数：runCatching 的 R 同时被 `null` 和这里推，
            // 新推理器会先定成 `List<RemoteTrack>?`，导致 add() 的实参对不上 E
            buildList<RemoteTrack> {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sid = o.optLong("id")
                    if (sid <= 0L) continue
                    val fee = o.optInt("fee", 0)
                    val album = o.optJSONObject("album")
                    add(
                        RemoteTrack(
                            remoteId = sid.toString(),
                            title = o.optString("name"),
                            artist = joinNames(o.optJSONArray("artists")),
                            album = album?.optString("name").orEmpty(),
                            durationMs = o.optLong("duration"),
                            coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() },
                            // 和搜索接口同一套 fee 判据（见 neteasePlayable 的说明）
                            playable = neteasePlayable(fee),
                            vip = neteaseVip(fee)
                        )
                    )
                }
            }
        }.getOrNull()
    }

    // ===== 给**本地歌**补封面 / 歌词 =====

    /**
     * 按歌曲 ID 精确取**单首**歌的完整元数据（给网易云缓存转换补全信息用）。
     *
     * 与 [findTrackFor] 的搜索-打分路线不同：NMSF 缓存文件名里带的就是歌曲 ID，
     * 直接走详情接口是**零歧义**的，不需要打分也不需要防同名翻唱。
     *
     * @return 元数据；请求失败 / ID 无效 / 平台查无此歌返回 null
     */
    suspend fun songDetail(remoteId: String): RemoteTrack? =
        withContext(Dispatchers.IO) {
            if (remoteId.isBlank()) return@withContext null
            fetchTrackDetails(listOf(remoteId))?.firstOrNull()
        }

    /**
     * 给一首本地歌找它在网易云上的对应条目。
     *
     * 本地曲库的封面和歌词经常取不到（内嵌标签缺失、iTunes 对华语歌命中率低、
     * LrcAPI 偶发没有），网易云这边命中率高得多，所以拿它当兜底来源。
     *
     * **不能直接用搜索结果第一条** —— 实测搜 `500 Miles The Proclaimers`，
     * 第一条是个同名翻唱（时长对不上、歌词为空）；搜 `晴天` 第一条是「晴天(深情版)」。
     * 打分挑选的逻辑在 [NeteaseMatch]（纯函数，有单测）。
     *
     * 返回的 [RemoteTrack] 里 `coverUrl` **已经填好**了 —— [search] 内部会批量补封面，
     * 所以拿到匹配就等于同时拿到了封面地址，不用再打一次详情接口。
     */
    suspend fun findTrackFor(
        title: String,
        artist: String,
        durationMs: Long
    ): RemoteTrack? {
        if (title.isBlank()) return null
        // 搜索词只带歌名 + 歌手。**不加专辑名** —— 本地专辑标签常和线上对不上
        // （"未知专辑"、合集名、本地化过的译名），带上只会缩窄命中范围。
        val kw = listOf(title, artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val page = search(kw, MATCH_LIMIT, 0) ?: return null
        return NeteaseMatch.pickBest(page.tracks, title, artist, durationMs)
    }

    /**
     * 取歌词。
     *
     * `lv=-1` 拿**行级**原文、`tv=-1` 附带翻译 —— 实测两者都**免登录、不校验签名**，
     * 连会员歌（`fee=1`）的歌词也能拿到（歌词不受会员限制）。
     *
     * ⚠️ 别用 `lv=1`：实测它给的是**逐字时间轴**（每个字一个时间戳），
     * 和译文的行级时间戳完全对不上，合并翻译会整段错位。
     *
     * @param withTranslation 有翻译时把译文并到同一行（见 [mergeTranslation]）
     * @return LRC 文本；**没有歌词时返回 null**（接口用空字符串表示"确认没有"）
     */
    suspend fun lyrics(remoteId: String, withTranslation: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val tv = if (withTranslation) -1 else 0
            val body = get("$BASE/api/song/lyric?id=$remoteId&lv=-1&kv=1&tv=$tv")
                ?: return@withContext null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null

            // 没有歌词时接口返回的是 `"lrc":{"version":1,"lyric":""}` ——
            // 空字符串就是"确认没有"，据此返回 null，调用方好写 KnownMiss 哨兵
            val raw = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // 先摘掉制作人员名单，再合并翻译（名单行没有对应译文，留着只会占头几行）
            val lrc = stripCreditLines(raw)
            if (lrc.isBlank()) return@withContext null
            if (!withTranslation) return@withContext lrc

            val tlrc = json.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            if (tlrc.isBlank()) lrc else mergeTranslation(lrc, tlrc)
        }

    /**
     * 匹配时取多少条候选。
     *
     * 10 条是权衡：够覆盖"同名不同版本"的几种情况（原唱 / Live / 翻唱 / 伴奏），
     * 而每条的封面缩略图又要多一次批量请求 —— 再多不划算。
     *
     * 直接写在 object 里，**不能包在 companion object 里** ——
     * `NeteaseSource` 本身已经是 `object`，再套一层 companion 编译不过。
     */
    private const val MATCH_LIMIT = 10

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
