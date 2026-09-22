package com.sonance.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonance.player.data.Album
import com.sonance.player.data.AppIconSwitcher
import com.sonance.player.data.Artist
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverCache
import com.sonance.player.data.CoverCandidate
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.OnlineDownloader
import com.sonance.player.data.NeteaseSource
import com.sonance.player.data.OnlineSource
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.ResolvedQueue
import com.sonance.player.data.hasCjkText
import com.sonance.player.data.isRemoteSongId
import org.json.JSONObject
import com.sonance.player.data.storedOnlineSongFromJson
import com.sonance.player.data.storedOnlineSongToJson
import com.sonance.player.data.storedOnlineSongsFromJson
import com.sonance.player.data.storedOnlineSongsToJson
import com.sonance.player.data.toStoredOrNull
import com.sonance.player.data.onlineSongIdsToKeep
import com.sonance.player.data.remoteSongId
import com.sonance.player.data.PlaybackRestore
import com.sonance.player.data.LibraryTab
import com.sonance.player.data.DuplicateReport
import com.sonance.player.data.LyricsCache
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.MediaRepository
import com.sonance.player.data.MusicFolder
import com.sonance.player.data.Prefs
import com.sonance.player.data.ScanFilters
import com.sonance.player.data.decodeSongsFromJson
import com.sonance.player.data.findDuplicates
import com.sonance.player.data.encodeSongsToJson
import com.sonance.player.data.ScanOutcome
import com.sonance.player.data.RestoreEngine
import com.sonance.player.data.RestoreResult
import com.sonance.player.data.Song
import com.sonance.player.data.SongStats
import com.sonance.player.data.SortField
import com.sonance.player.data.UnplayableFileScanner
import com.sonance.player.data.UnplayableScanResult
import com.sonance.player.data.ViewMode
import com.sonance.player.playback.EQ_BAND_COUNT
import com.sonance.player.playback.EQ_CENTER_FREQS
import com.sonance.player.playback.EQ_MAX_DB
import com.sonance.player.playback.EQ_MIN_DB
import com.sonance.player.playback.EqBus
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.PlayerController
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class ScanState { IDLE, SCANNING, READY }

class SonanceViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repo = MediaRepository(app)
    val player = PlayerController(app)

    // ===== 媒体库 =====
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var scanState by mutableStateOf(ScanState.IDLE)
        private set

    /**
     * 专辑 / 艺术家 / 文件夹都是对 songs 的聚合结果，扫描结束后算一次就好。
     *
     * 之前写成 getter，界面每重组一次就要 groupBy + 排序几百首歌，而播放进度
     * 每 500ms 跳一次就会触发一轮重组 —— 这是列表页掉帧的主要来源之一。
     */
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var artists by mutableStateOf<List<Artist>>(emptyList())
        private set
    var folders by mutableStateOf<List<MusicFolder>>(emptyList())
        private set

    // ===== 偏好 =====
    var skin by mutableStateOf(Skins.byId(prefs.skinId))
        private set
    var sortField by mutableStateOf(runCatching { SortField.valueOf(prefs.sortField) }.getOrDefault(SortField.TITLE))
        private set
    var sortAscending by mutableStateOf(prefs.sortAscending)
        private set
    var viewMode by mutableStateOf(runCatching { ViewMode.valueOf(prefs.viewMode) }.getOrDefault(ViewMode.LIST))
        private set
    var tab by mutableStateOf(LibraryTab.fromId(prefs.lastTab))
        private set
    var searchQuery by mutableStateOf("")
        private set
    var filterShortAudio by mutableStateOf(prefs.scanFilterShortAudio)
        private set

    // ===== 曲库过滤开关（默认全开，可关）=====
    var scanExcludeLocked by mutableStateOf(prefs.scanExcludeLocked)
        private set
    var scanExcludeUnsupported by mutableStateOf(prefs.scanExcludeUnsupported)
        private set
    var scanExcludeNonSongFolders by mutableStateOf(prefs.scanExcludeNonSongFolders)
        private set

    /** 最近一次扫描的排除明细，供「曲库清理」区块展示 */
    var lastScanOutcome by mutableStateOf<ScanOutcome?>(null)
        private set

    fun changeScanExcludeLocked(value: Boolean) {
        scanExcludeLocked = value
        prefs.scanExcludeLocked = value
        scan()
    }

    fun changeScanExcludeUnsupported(value: Boolean) {
        scanExcludeUnsupported = value
        prefs.scanExcludeUnsupported = value
        scan()
    }

    fun changeScanExcludeNonSongFolders(value: Boolean) {
        scanExcludeNonSongFolders = value
        prefs.scanExcludeNonSongFolders = value
        scan()
    }

    private fun currentScanFilters(): ScanFilters = ScanFilters(
        excludeLocked = scanExcludeLocked,
        excludeUnsupported = scanExcludeUnsupported,
        excludeNonSongFolders = scanExcludeNonSongFolders
    )
    var favorites by mutableStateOf(prefs.favorites)
        private set
    var recentIds by mutableStateOf(prefs.recentIds())
        private set

    /**
     * 最近播放的完整条目，**含在线歌**。
     *
     * [recentIds] 只留本地 id（它给曲库排序用），列表显示要用这个 ——
     * 在线歌不在本地曲库里，靠 id 是反查不到的。
     */
    var recentEntries by mutableStateOf(prefs.recentEntries())
        private set

    // ===== 提示 =====
    var toastMessage by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null

    /** 歌曲 id → 最近播放顺序 */
    private val recentOrder: Map<Long, Int> by lazy {
        recentIds.withIndex().associate { (i, id) -> id to i }
    }

    // ===== 播放现场：冷启动恢复 =====

    /**
     * 只恢复一次。扫描完成后再调一次，靠它避免把用户刚做的操作顶掉。
     *
     * ⚠️ 这两个字段**必须声明在 init 块之前**：Kotlin 的初始化按源码顺序执行，
     * 写在 init 后面的话，init 里设好的值会被属性初始化 `= false` 覆盖回去 ——
     * 结果是扫描完成后又恢复一遍（把用户正在听的东西顶掉），
     * 而且列表定位也永远不会触发。
     */
    private var playbackRestored = false

    /** 恢复完成、等播放器报出 mediaId 后要请求一次列表定位 */
    private var pendingStartupScroll = false

    init {
        player.onSongStarted = { id ->
            // 在线歌不进本地记账。
            //
            // 它们的 id 是负数、冷启动后查不到任何歌，记进「最近播放」会让顶栏的
            // 「最近听过 N 首」虚高，而且那些条目**用户永远看不到也清不掉**
            // （「清空最近播放」按钮是给本地歌用的，没人会想到它被在线歌撑大了）。
            // 播放次数同理：只在「播放最多」排序里被读，在线歌根本不参与排序。
            if (!isRemoteSongId(id)) {
                prefs.pushRecentLocal(id)
                prefs.bumpPlayCount(id)
                recentIds = prefs.recentIds()
                prefs.lastSongId = id
                prefs.lastPlayedOnline = false
                // 现在播的是本地歌，在线那套存档就用不上了 ——
                // 不清的话恢复时会读到一份过期的在线现场，走错分支
                prefs.lastOnlineSongJson = null
                prefs.onlineQueueJson = null
            } else {
                // 在线歌**也要进最近播放**。
                //
                // 必须存完整信息而不是只存 id：在线歌的 id 是哈希出来的负数，
                // 冷启动后本地曲库里查不到任何歌，只存 id 会留下一条
                // "看得见、点不开、还删不掉"的幽灵记录。
                songById(id)?.toStoredOrNull()?.let { stored ->
                    prefs.pushRecentOnline(stored)
                    prefs.lastOnlineSongJson = storedOnlineSongToJson(stored).toString()
                    prefs.lastPlayedOnline = true
                }
            }
            recentEntries = prefs.recentEntries()
            // 顺手把队列和序号存下来 —— 冷启动恢复要用，而 onStop 不一定每次都会走到
            // （进程被系统直接回收时就不会）。此刻 refreshQueue 已经跑过，queueIds 是新的。
            //
            // 在线队列**不能**只存负数 id：恢复时 PlaybackRestore 按「在不在本地曲库里」
            // 过滤，那些 id 会被整条滤掉（那是给本地歌设计的规则，在线歌本来就不在曲库里）。
            // 所以在线队列每首都存成"能重新找回来"的形态，恢复时重新取播放地址。
            val st = player.state.value
            if (st.queueIds.isNotEmpty()) {
                if (isRemoteSongId(st.mediaId)) {
                    val stored = st.queueIds.mapNotNull { songById(it)?.toStoredOrNull() }
                    if (stored.isNotEmpty()) {
                        prefs.onlineQueueJson = storedOnlineSongsToJson(stored)
                        prefs.onlineQueueIndex = st.queueIndex.coerceIn(0, stored.lastIndex)
                    }
                } else {
                    prefs.playQueueIds = st.queueIds.joinToString(",")
                    prefs.playQueueIndex = st.queueIndex
                }
            }
            prefs.playPositionMs = 0L
            // 本地没有封面就顺手联网补一张（开关没开时内部直接返回）
            // 刷新通知栏封面不受开关影响：下载过的封面是本地数据，随时该显示
            songById(id)?.let {
                player.refreshCurrentArtwork(it)
                ensureCover(it)
                // 在线歌：确认真的在听之后存一份到本地（开关没开时内部直接返回）
                maybeDownload(it)
            }
        }

        // 优先读上一次扫描的缓存：冷启动不再强制扫一遍 MediaStore。
        // PermissionGate 只在 scanState == IDLE 时才自动扫描，所以加载到缓存
        // 就直接置为 READY，PermissionGate 不会再触发 scan。
        val cachedJson = prefs.songsCacheJson
        if (!cachedJson.isNullOrBlank()) {
            runCatching {
                val list = decodeSongsFromJson(cachedJson)
                if (list.isNotEmpty()) {
                    val visible = filterHidden(list)
                    songs = visible
                    albums = MediaRepository.groupAlbums(visible)
                    artists = MediaRepository.groupArtists(visible)
                    folders = MediaRepository.groupFolders(visible)
                    scanState = ScanState.READY
                }
            }
        }

        // 曲库（缓存）就绪之后恢复上次的播放现场。
        // 放在最后：它要按歌曲 id 去 songs 里查，曲库没加载出来时无从恢复。
        restorePlaybackState()

        // 恢复完成、播放器真正报出 mediaId 之后再请求列表定位。
        // 不能恢复完直接 requestScrollToCurrent()：那一刻 mediaId 还是 -1，
        // SongList 里那个 effect 会因为 `currentId <= 0` 提前 return，白请求一次。
        //
        // `> 0` 也顺带排除了在线歌（负数 id）—— 这是对的：它们不在本地曲库列表里，
        // 没有"滚到那一行"可言。
        viewModelScope.launch {
            player.state.collect { st ->
                if (pendingStartupScroll && st.mediaId > 0L) {
                    pendingStartupScroll = false
                    requestScrollToCurrent()
                }
            }
        }
    }

    /**
     * 把播放器摆回上次退出时的样子：同一个队列、同一首、同一个进度。
     *
     * **不自动播放** —— 冷启动就出声很吓人。恢复成「暂停在这首歌上」，按播放键才继续。
     *
     * 曲库还没加载出来时直接返回（没什么可恢复的）；[scan] 完成后会再调一次，
     * 所以两个入口都调它，靠 [playbackRestored] 保证只做一次。
     */
    /**
     * 恢复**在线**播放现场 —— 上次退出时正在播的是在线歌。
     *
     * @return 成功返回 true；失败（存档没了 / 音源下线了）返回 false，
     *         让调用方接着走本地恢复那条路。
     *
     * 播放地址是**当场重新取**的：存档里只有"音源 + 远端 id"这类能把歌找回来的
     * 信息，没有地址本身 —— 地址带签名会过期（见 [OnlineSource.streamUrl] 的说明）。
     */
    private fun restoreOnlinePlayback(): Boolean {
        val queue = storedOnlineSongsFromJson(prefs.onlineQueueJson).mapNotNull { it.toSong() }
        val last = prefs.lastOnlineSongJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { storedOnlineSongFromJson(it) }
            ?.toSong()
        // 队列整条恢复不出来就退一步：至少把"上次听的那一首"摆回来
        val list = queue.ifEmpty { listOfNotNull(last) }
        if (list.isEmpty()) return false

        playbackRestored = true
        pendingStartupScroll = true
        // autoplay=false：冷启动就出声很吓人，摆成"暂停在这首歌"等用户按播放。
        // notifyStart=false：这只是把上次的歌摆回来，不该给播放次数 +1。
        player.play(
            songs = list,
            index = prefs.onlineQueueIndex.coerceIn(0, list.lastIndex),
            autoplay = false,
            startPositionMs = 0L,
            notifyStart = false
        )
        return true
    }

    private fun restorePlaybackState() {
        if (playbackRestored) return

        // 上次播的是在线歌 → 先走在线恢复。它**不依赖本地曲库** ——
        // 在线歌的 id 是负数，按"在不在曲库里"过滤会把它们整条滤掉，
        // 那是给本地歌设计的规则。
        if (prefs.lastPlayedOnline && restoreOnlinePlayback()) return

        if (songs.isEmpty()) return

        val position = prefs.playPositionMs.coerceAtLeast(0L)
        val map = songs.associateBy { it.id }

        // 只保留还在曲库里的：重新扫描之后 id 会变，旧队列可能整条都失效。
        // 这套「过滤 + 下标夹紧」的规则在 PlaybackRestore 里，有单测覆盖。
        val resolved = PlaybackRestore.resolveIds(
            savedIds = PlaybackRestore.parseIdList(prefs.playQueueIds),
            savedIndex = prefs.playQueueIndex,
            libraryIds = map.keys
        )

        // 队列整条失效就退一步：至少把「上次听的那一首」摆回来。
        // 一首都找不回来说明这个用户还没放过歌（或曲库整个换了），那就什么都不做。
        val queue = resolved?.first?.mapNotNull(map::get) ?: run {
            // 只查本地曲库（[map]），**不要**用 [songById]。
            //
            // 这里是**本地**播放现场的恢复（在线歌走上面那条路），而 [songById]
            // 查不到时会去翻 `onlineSongMap` —— 那个 map 声明在类的后半部分，
            // ViewModel 构造期间（init 里就会走到这儿）它**还没初始化**，
            // 一访问就是 NPE，表现是应用一启动就崩、直接退到桌面。
            val last = map[prefs.lastSongId] ?: return
            listOf(last)
        }
        val index = resolved?.second ?: 0

        playbackRestored = true
        pendingStartupScroll = true
        // autoplay = false：冷启动就出声很吓人。摆成「暂停在这首歌上」，按播放键才继续。
        // notifyStart = false：这只是把上次的歌摆回来，不该给播放次数 +1。
        player.play(
            songs = queue,
            index = index,
            autoplay = false,
            startPositionMs = position,
            notifyStart = false
        )
    }

    /**
     * 把当前播放现场写盘。由 `MainActivity.onStop()` 调用。
     *
     * 选这个时机而不是每 500ms 写一次：离开界面时进度已经稳定，
     * 写一次就够；而播放中每帧写盘纯属浪费（SharedPreferences 每次都要重写整份 XML）。
     */
    fun persistPlayback() {
        val st = player.state.value
        // 什么都没加载时**一个字段都不要写** —— 否则「打开就立刻关掉」这种操作
        // 会把上次存好的进度抹成 0（那一刻播放器还没连上，positionMs 是 0）。
        if (st.mediaId > 0L) {
            prefs.lastSongId = st.mediaId
            prefs.playPositionMs = st.positionMs.coerceAtLeast(0L)
            prefs.lastPlayedOnline = false
            // 现在播的是本地歌，在线那套存档就用不上了 ——
            // 不清的话恢复时会读到一份过期的在线现场，走错分支
            prefs.lastOnlineSongJson = null
            prefs.onlineQueueJson = null
        } else if (isRemoteSongId(st.mediaId)) {
            // 在线歌。
            //
            // 以前这里靠 `> 0` 把在线歌整个挡掉了（注释还写着"这是想要的"）——
            // 结果就是回到软件时 `lastSongId` 仍指向**上一次的本地歌**，
            // 播放页摆的是本地歌，在线歌看着像凭空消失了一样。
            //
            // 现在存"能重新找回来"的信息（音源 + 远端 id + 展示字段），并记下
            // "最后播的是在线歌"。**地址不存** —— 它带签名会过期，恢复时当场重新取。
            songById(st.mediaId)?.toStoredOrNull()?.let {
                prefs.lastOnlineSongJson = storedOnlineSongToJson(it).toString()
                prefs.lastPlayedOnline = true
            }
            prefs.playPositionMs = 0L
        }
        if (st.queueIds.isNotEmpty()) {
            if (isRemoteSongId(st.mediaId)) {
                val stored = st.queueIds.mapNotNull { songById(it)?.toStoredOrNull() }
                if (stored.isNotEmpty()) {
                    prefs.onlineQueueJson = storedOnlineSongsToJson(stored)
                    prefs.onlineQueueIndex = st.queueIndex.coerceIn(0, stored.lastIndex)
                }
            } else {
                prefs.playQueueIds = st.queueIds.joinToString(",")
                prefs.playQueueIndex = st.queueIndex
            }
        }
    }

    // ===== 格式兼容性检查 =====
    var compatTreeUri by mutableStateOf(prefs.compatTreeUri)
        private set
    var compatTreeLabel by mutableStateOf(prefs.compatTreeLabel)
        private set
    var compatScanning by mutableStateOf(false)
        private set
    var compatProgress by mutableStateOf(0)
        private set
    var compatResult by mutableStateOf<UnplayableScanResult?>(null)
        private set
    private var compatJob: Job? = null

    /** 记录用户通过 SAF 授权的目录，下次可以一键重扫 */
    fun setCompatTree(uri: android.net.Uri, label: String) {
        prefs.compatTreeUri = uri.toString()
        prefs.compatTreeLabel = label
        compatTreeUri = uri.toString()
        compatTreeLabel = label
    }

    fun scanUnplayable() {
        val uriStr = compatTreeUri
        if (uriStr == null) {
            showToast("请先选择一个文件夹")
            return
        }
        compatJob?.cancel()
        compatJob = viewModelScope.launch {
            compatScanning = true
            compatProgress = 0
            val result = runCatching {
                UnplayableFileScanner(getApplication()).scan(android.net.Uri.parse(uriStr)) { folders ->
                    compatProgress = folders
                }
            }.getOrNull()
            compatResult = result
            compatScanning = false
            showToast(
                when {
                    result == null -> "扫描失败，请重新选择文件夹"
                    result.totalCount == 0 -> "这个文件夹里没有发现无法播放的文件"
                    else -> "扫描完成：发现 ${result.totalCount} 个无法播放的文件"
                }
            )
        }
    }

    fun clearCompatResult() {
        compatResult = null
    }

    // ===== 私有格式还原 =====

    private var restoreDryRunState by mutableStateOf(false)

    /** 仅预览：只分析不写盘。对外只读，统一通过 setRestoreDryRun 修改 */
    val restoreDryRun: Boolean
        get() = restoreDryRunState

    // 只读状态
    var restoreOutputUri by mutableStateOf(prefs.restoreOutputTreeUri)
        private set
    var restoreOutputLabel by mutableStateOf(prefs.restoreOutputTreeLabel)
        private set
    var restoring by mutableStateOf(false)
        private set
    var restoreDone by mutableStateOf(0)
        private set
    var restoreTotal by mutableStateOf(0)
        private set
    var restoreCurrent by mutableStateOf("")
        private set
    var restoreResult by mutableStateOf<RestoreResult?>(null)
        private set
    private var restoreJob: Job? = null

    /** 还原源 = 格式兼容性检查里授权过的目录 */
    val restoreSourceLabel: String?
        get() = compatTreeLabel

    /** 算法与密钥都内置 / 从文件头自读取，只要有源目录且不在运行中即可开始 */
    val restoreCanStart: Boolean
        get() = compatTreeUri != null && !restoring

    fun setRestoreOutputTree(uri: android.net.Uri, label: String) {
        prefs.restoreOutputTreeUri = uri.toString()
        prefs.restoreOutputTreeLabel = label
        restoreOutputUri = uri.toString()
        restoreOutputLabel = label
    }

    fun setRestoreDryRun(value: Boolean) {
        restoreDryRunState = value
    }

    fun startRestore() {
        val sourceStr = compatTreeUri
        if (sourceStr == null) {
            showToast("请先选择源文件夹")
            return
        }
        val dryRun = restoreDryRun
        val outputStr = restoreOutputUri
        if (!dryRun && outputStr == null) {
            showToast("请先选择输出文件夹")
            return
        }
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            restoring = true
            restoreDone = 0
            restoreTotal = 0
            restoreCurrent = ""
            try {
                val engine = RestoreEngine(getApplication())
                val result = engine.restore(
                    sourceTree = android.net.Uri.parse(sourceStr),
                    outputTree = if (dryRun) null else android.net.Uri.parse(outputStr ?: ""),
                    dryRun = dryRun,
                    onProgress = { done, total, current ->
                        restoreDone = done
                        restoreTotal = total
                        restoreCurrent = current
                    }
                )
                restoreResult = result
                showToast(
                    when {
                        dryRun -> "预演完成：可还原 ${result.converted} 个，跳过 ${result.skipped} 个，失败 ${result.failed} 个"
                        result.converted > 0 -> "还原完成：成功 ${result.converted} 个，跳过 ${result.skipped} 个，失败 ${result.failed} 个"
                        result.failed > 0 -> "还原失败：${result.failed} 个文件处理出错"
                        else -> "没有找到可还原的文件"
                    }
                )
                // 写盘成功后刷新媒体库，让新文件尽快出现在列表里
                if (!dryRun && result.converted > 0) scan()
            } finally {
                restoring = false
            }
        }
    }

    fun cancelRestore() {
        restoreJob?.cancel()
        restoreJob = null
        restoring = false
        showToast("已取消还原")
    }

    fun clearRestoreResult() {
        restoreResult = null
    }

    // ===== 行为 =====

    /**
     * @param clearArtwork 是否清掉封面缓存。
     *
     * 正常扫描要清（歌曲可能被删了、封面可能变了）；**下载之后的重扫不要清** ——
     * 下载只是新增文件，已有封面依然有效，清了会让正在播放的封面闪一下
     * （用户边听边下载时特别明显）。
     */
    private suspend fun runScan(clearArtwork: Boolean = true): ScanOutcome {
        if (clearArtwork) ArtworkLoader.clear()
        val outcome = repo.scan(filterShortAudio, currentScanFilters())
        applyScanOutcome(outcome)
        // 把结果落到 Prefs，下次冷启动直接用、不再扫 MediaStore
        prefs.songsCacheJson = encodeSongsToJson(outcome.songs)
        prefs.lastScanAt = System.currentTimeMillis()
        return outcome
    }

    private fun applyScanOutcome(outcome: ScanOutcome) {
        songs = filterHidden(outcome.songs)
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        lastScanOutcome = outcome
    }

    /** 曲库里被用户"清理"掉（只从列表隐藏）的曲目 */
    private fun filterHidden(all: List<Song>): List<Song> {
        val hidden = prefs.hiddenIdSet()
        return if (hidden.isEmpty()) all else all.filter { it.id !in hidden }
    }

    // ===== 清理重复 / 同名歌曲（只隐藏，不动文件）=====

    var duplicateReport by mutableStateOf<DuplicateReport<Song>?>(null)
        private set

    var hiddenCount by mutableIntStateOf(prefs.hiddenIdSet().size)
        private set

    fun scanDuplicates() {
        viewModelScope.launch {
            val all = lastScanOutcome?.songs ?: songs
            duplicateReport = withContext(Dispatchers.Default) { findDuplicates(all) }
            val r = duplicateReport
            showToast(
                when {
                    r == null || r.isEmpty -> "没有发现重复或同名歌曲"
                    else -> "发现 ${r.exactCount} 首重复 · ${r.sameNameCount} 首同名"
                }
            )
        }
    }

    /**
     * 把重复曲目从列表隐藏。
     * @param includeSameName 是否连"同名但时长不同"的一起处理
     */
    fun cleanDuplicates(includeSameName: Boolean) {
        val report = duplicateReport ?: return
        val victims = HashSet<Long>()
        report.exactGroups.forEach { g -> g.duplicates.forEach { victims.add(it.id) } }
        if (includeSameName) {
            report.sameNameGroups.forEach { g -> g.duplicates.forEach { victims.add(it.id) } }
        }
        if (victims.isEmpty()) {
            showToast("没有需要清理的曲目")
            return
        }
        val merged = prefs.hiddenIdSet() + victims
        prefs.hiddenSongIds = merged.joinToString(",")
        hiddenCount = merged.size

        // 只改内存里的曲库列表，文件一个都没动
        songs = songs.filter { it.id !in victims }
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(songs)

        duplicateReport = null
        showToast("已从列表隐藏 ${victims.size} 首（文件未删除）")
    }

    /** 恢复所有被隐藏的曲目 */
    fun restoreHiddenSongs() {
        if (prefs.hiddenIdSet().isEmpty()) {
            showToast("没有被隐藏的曲目")
            return
        }
        prefs.hiddenSongIds = ""
        hiddenCount = 0
        // 重新扫描一次把完整曲库拿回来（缓存里已经是过滤后的了）
        scan()
        showToast("已恢复隐藏的曲目")
    }

    fun scan() {
        viewModelScope.launch {
            scanState = ScanState.SCANNING
            runScan()
            scanState = ScanState.READY
            // 没有歌曲缓存时（首次启动）init 里那次恢复会跳过，扫完再试一次
            restorePlaybackState()
        }
    }

    /**
     * 下载成功之后把新文件并进曲库。**必须做** ——
     *
     * 应用的曲库是缓存的，不重扫的话「文件夹」页永远不会出现下载目录那个分类，
     * 而那正是用户要的（"下载之后的歌曲在文件夹里面有一个独立的分类"）。
     *
     * 和 [scan] 的两点区别：
     * - **不清封面缓存**（见 [runScan] 的参数说明）—— 正在播的封面不该闪
     * - **不做播放现场恢复** —— 那会把正在播的队列重新排一遍
     */
    fun rescanAfterDownload() {
        viewModelScope.launch {
            scanState = ScanState.SCANNING
            runScan(clearArtwork = false)
            scanState = ScanState.READY
        }
    }

    /** 一键清理：用当前规则重扫曲库，并把清理结果告诉用户 */
    fun cleanLibrary() {
        viewModelScope.launch {
            scanState = ScanState.SCANNING
            val outcome = runScan()
            scanState = ScanState.READY
            showToast(
                when {
                    outcome.excludedTotal == 0 -> "曲库已经很干净，没有发现需要清理的条目"
                    else -> "已清理 ${outcome.excludedTotal} 个：加密 ${outcome.excludedLocked} · " +
                        "不支持格式 ${outcome.excludedUnsupported} · 非歌曲 ${outcome.excludedNonSong}"
                }
            )
        }
    }

    fun changeIgnoreShort(value: Boolean) {
        filterShortAudio = value
        prefs.scanFilterShortAudio = value
        scan()
    }

    fun selectTab(value: LibraryTab) {
        val changed = tab != value
        tab = value
        // 不把「皮肤与设置」记成上次的标签页：它在导航结构里是附属页（齿轮进入、
        // 返回回到主页面），记住了会导致下次冷启动直接停在设置页。
        if (value != LibraryTab.SETTINGS) prefs.lastTab = value.id
        // 切回「全部歌曲」时把正在播放的那首滚进可视区
        if (value == LibraryTab.SONGS && changed) requestScrollToCurrent()
    }

    // ===== 在线歌词 =====

    var onlineLyricsEnabled by mutableStateOf(prefs.onlineLyricsEnabled)
        private set

    var lyricsApiBase by mutableStateOf(prefs.lyricsApiBase)
        private set

    fun changeOnlineLyrics(enabled: Boolean) {
        onlineLyricsEnabled = enabled
        prefs.onlineLyricsEnabled = enabled
        // 缓存里可能存着"本地没歌词"的结果，开关一变就清掉重新查
        LyricsRepository.clear()
        showToast(if (enabled) "已开启在线歌词（会联网）" else "已关闭在线歌词（完全离线）")
    }

    fun changeLyricsApi(value: String) {
        lyricsApiBase = value
        prefs.lyricsApiBase = value
        LyricsRepository.clear()
    }

    /** 在线歌词里的繁体自动转简体（默认开）。只影响展示，缓存里存的仍是原文 */
    var lyricsConvertT2S by mutableStateOf(prefs.lyricsConvertToSimplified)
        private set

    /** 歌词字号（sp） */
    var lyricsFontSizeSp by mutableIntStateOf(prefs.lyricsFontSizeSp)
        private set

    fun changeLyricsT2S(value: Boolean) {
        lyricsConvertT2S = value
        prefs.lyricsConvertToSimplified = value
        LyricsRepository.clear()
        showToast(if (value) "歌词繁体将自动转简体" else "歌词保持原文")
    }

    fun changeLyricsFontSize(value: Int) {
        lyricsFontSizeSp = value.coerceIn(Prefs.MIN_LYRICS_FONT, Prefs.MAX_LYRICS_FONT)
        prefs.lyricsFontSizeSp = lyricsFontSizeSp
    }

    // ===== 歌词页背景玻璃 =====

    var glassStyle by mutableStateOf(GlassStyle.of(prefs.glassStyle))
        private set
    var glassBlur by mutableIntStateOf(prefs.glassBlurDp)
        private set
    var glassVisibility by mutableIntStateOf(prefs.glassVisibility)
        private set

    /** 换样式时把模糊 / 可见度一起带到该样式的预设值 */
    fun changeGlassStyle(style: GlassStyle) {
        glassStyle = style
        glassBlur = style.defaultBlurDp
        glassVisibility = style.defaultVisibility
        prefs.glassStyle = style.ordinal
        prefs.glassBlurDp = style.defaultBlurDp
        prefs.glassVisibility = style.defaultVisibility
    }

    fun changeGlassBlur(value: Int) {
        glassBlur = value.coerceIn(0, 80)
        prefs.glassBlurDp = glassBlur
    }

    fun changeGlassVisibility(value: Int) {
        glassVisibility = value.coerceIn(0, 100)
        prefs.glassVisibility = glassVisibility
    }

    // ===== 在线封面 =====

    var onlineCoverEnabled by mutableStateOf(prefs.onlineCoverEnabled)
        private set

    /** 本次会话已尝试补过的 songId，避免反复请求 */
    private val coverTried = HashSet<Long>()

    var coverSearching by mutableStateOf(false)
        private set
    var coverResults by mutableStateOf<List<CoverCandidate>>(emptyList())
        private set
    var coverNotice by mutableStateOf<String?>(null)
        private set

    /**
     * 正在手动选封面的歌曲；**0 表示没有**。
     *
     * ⚠️ 哨兵必须是 0，不能沿用 `-1L`：本地歌 id 为正、在线歌 id 为负
     * （`remoteSongId` 的值域是 `[-2147483648, -1]`），而哈希恰好为 0 时
     * 在线歌的 id **就是 -1** —— 用 -1 当哨兵会和真实歌曲撞上，
     * 表现是"关掉选封面对话框之后它自己又弹出来了"。
     * 0 在这个项目里一直是「没有歌曲」的保留值，用它才安全。
     */
    var coverTargetSongId by mutableLongStateOf(0L)
        private set

    var coverCacheCount by mutableIntStateOf(0)
        private set

    // ===== 播放页封面晕染 =====

    var glowMode by mutableStateOf(GlowMode.fromId(prefs.glowMode))
        private set

    var glowStrength by mutableStateOf(prefs.glowStrength)
        private set

    /** 换歌时颜色的过渡方式：跟手 / 渐变 */
    var glowTransition by mutableStateOf(GlowTransition.fromId(prefs.glowTransition))
        private set

    /** 「渐变」模式的补间时长（毫秒） */
    var glowFadeMs by mutableIntStateOf(prefs.glowFadeMs)
        private set

    fun changeGlowMode(mode: GlowMode) {
        glowMode = mode
        prefs.glowMode = mode.id
    }

    fun changeGlowStrength(v: Float) {
        glowStrength = v
        prefs.glowStrength = v
    }

    fun changeGlowTransition(t: GlowTransition) {
        glowTransition = t
        prefs.glowTransition = t.id
    }

    fun changeGlowFadeMs(ms: Int) {
        glowFadeMs = ms
        prefs.glowFadeMs = ms
    }

    fun changeOnlineCover(enabled: Boolean) {
        onlineCoverEnabled = enabled
        prefs.onlineCoverEnabled = enabled
        showToast(if (enabled) "已开启在线封面（会联网）" else "已关闭在线封面（完全离线）")
    }

    /**
     * 播放到某首歌时调用：本地没有任何封面才去联网补一张。
     *
     * 三个前置短路，保证"不会为封面乱发请求"：
     * 1. 开关没开 → 直接返回
     * 2. 本地（含文件内嵌）已有封面 → 直接返回
     * 3. 这次会话已经试过这首 → 直接返回
     */
    fun ensureCover(song: Song) {
        // 在线歌走另一条路：它**自带**封面地址，不需要"本地探测 + 联网猜"那一套，
        // 也不该受「在线封面」开关约束 —— 这首歌本来就在联网播。
        if (!song.isLocal) {
            ensureOnlineSongCover(song)
            return
        }
        if (!onlineCoverEnabled) return
        // 走到这里一定是本地歌（上面已经分流出在线歌了），id 恒为正。
        // 保留这个判断只是防脏数据 —— **别**把它挪到上面那个分流之前，
        // 那样在线歌会被整段挡掉、连自带封面的下载都不会发生。
        if (song.id <= 0) return
        if (ArtworkLoader.onlineCovers.containsKey(song.id)) return
        if (!coverTried.add(song.id)) return

        val key = OnlineCover.keyOf(song.title, song.artist)

        viewModelScope.launch {
            val app = getApplication<Application>()

            // 本地 / 内嵌有没有？顺手也算预热了缓存。
            // 传 key 之后，**之前下载落盘的封面也算"本地已有"** —— 于是不会再打接口。
            val local = withContext(Dispatchers.IO) {
                ArtworkLoader.load(app, song.albumId, 320, song.uri, key)
            }
            if (local != null) return@launch

            val bytes = withContext(Dispatchers.IO) {
                when (val hit = CoverCache.lookup(app, key)) {
                    is CoverCache.Lookup.Hit -> return@withContext hit.bytes
                    // 之前确认过没有：这次会话都不再请求
                    CoverCache.Lookup.KnownMiss -> return@withContext null
                    CoverCache.Lookup.Unknown -> Unit
                }

                val url = findCoverUrl(song)
                if (url.isNullOrBlank()) {
                    // 记一个"确认没有"，下次同一首不再打接口
                    CoverCache.store(app, key, null)
                    return@withContext null
                }
                val raw = OnlineCover.downloadBytes(url)
                if (raw != null) CoverCache.store(app, key, raw)
                raw
            }

            val bmp = bytes?.let { OnlineCover.decode(it, OnlineCover.FULL_PX) }
            // 刚写盘 → 让「磁盘里也没有封面」那条短路失效。
            // 必须做：列表要 200px、播放页要 900px，是两个缓存键，
            // 不失效的话另一个档位本次会话里会一直以为没封面。
            if (bmp != null) ArtworkLoader.forgetCoverMiss(key)
            ArtworkLoader.putOnline(song.id, bmp?.asImageBitmap())
            if (bmp == null) {
                android.util.Log.i("SonanceCover", "没补到封面：${song.title} - ${song.artist}")
            } else {
                // 通知栏 / 锁屏的 artworkUri 也要跟着换成刚下载的这张
                player.refreshCurrentArtwork(song)
            }
        }
    }

    /**
     * 给一首**本地歌**找封面地址。
     *
     * 两个源互补，都试一遍、谁先给结果用谁：
     * - **iTunes**（[OnlineCover]）：西洋曲库覆盖好、免 key
     * - **网易云**（[NeteaseSource]）：东亚曲库覆盖好，而且匹配**带时长校验**
     *   （iTunes 那边只按歌名歌手搜，容易返回同名翻唱的封面）
     *
     * 顺序按语种定：标题或歌手里有中日韩字符就先问网易云。
     * 实测华语歌问 iTunes 经常返回**同名翻唱**的封面 —— 那比"没有封面"更糟，
     * 因为用户很难发现是错的。
     *
     * 用户手动钉过封面就直接用，不再联网。
     */
    private suspend fun findCoverUrl(song: Song): String? {
        prefs.pinnedCoverUrl(song.id)?.let { return it }

        val neteaseFirst = neteaseFillEnabled && hasCjkText(song.title, song.artist)
        val first = if (neteaseFirst) neteaseCoverUrl(song) else itunesCoverUrl(song)
        if (!first.isNullOrBlank()) return first
        // 没开这个开关就只问 iTunes（保持原来的行为）
        if (!neteaseFillEnabled) return null
        return if (neteaseFirst) itunesCoverUrl(song) else neteaseCoverUrl(song)
    }

    private suspend fun itunesCoverUrl(song: Song): String? =
        OnlineCover.search(song.title, song.artist, song.album, 1)?.firstOrNull()?.fullUrl

    private suspend fun neteaseCoverUrl(song: Song): String? =
        NeteaseSource.findTrackFor(song.title, song.artist, song.durationMs)?.coverUrl

    /**
     * 在线歌的封面：把音源给的地址下载下来，按 [OnlineCover.keyOf] 落盘。
     *
     * 落盘而不是只放内存，是为了让**所有**已有的封面消费方自动生效 ——
     * 列表行、迷你条、播放页、桌面小组件、通知栏读的都是
     * `ArtworkLoader.load(..., coverKey = OnlineCover.keyOf(title, artist))`，
     * 只要这个 key 在磁盘缓存里有货，它们全都不用改。
     *
     * 下载的是**原图**（不带 `?param=`）：这里是要拿去铺满播放页的大图，
     * 而缩略图那条路是搜索列表专用的，两者不要互相污染。
     */
    private fun ensureOnlineSongCover(song: Song) {
        val url = song.coverUrl
        if (url.isNullOrBlank()) return
        if (!coverTried.add(song.id)) return
        val key = OnlineCover.keyOf(song.title, song.artist) ?: return

        viewModelScope.launch {
            val app = getApplication<Application>()
            val raw = withContext(Dispatchers.IO) {
                when (CoverCache.lookup(app, key)) {
                    // 之前下载过：什么都不用做，CoverArt 会从磁盘缓存读到它
                    is CoverCache.Lookup.Hit -> return@withContext null
                    // 之前确认过没有：这次会话不再试（避免反复打同一个死链）
                    CoverCache.Lookup.KnownMiss -> return@withContext null
                    CoverCache.Lookup.Unknown -> Unit
                }
                OnlineCover.downloadBytes(url)?.also { CoverCache.store(app, key, it) }
            }
            if (raw == null) return@launch

            // 刚写盘 → 让「磁盘里也没有」那条短路失效（列表 200px 和播放页 900px
            // 是两个缓存键，不失效的话另一档本次会话里会一直以为没封面）
            ArtworkLoader.forgetCoverMiss(key)
            ArtworkLoader.putOnline(song.id, OnlineCover.decode(raw, OnlineCover.FULL_PX)?.asImageBitmap())
            player.refreshCurrentArtwork(song)
        }
    }

    /** 手动搜索封面（歌名/歌手可改） */
    fun searchCovers(title: String, artist: String, album: String = "") {
        viewModelScope.launch {
            coverSearching = true
            coverNotice = null
            val list = withContext(Dispatchers.IO) { OnlineCover.search(title, artist, album, 12) }
            coverResults = list.orEmpty()
            coverSearching = false
            coverNotice = when {
                list == null -> "搜索失败，检查一下网络，或稍后再试"
                list.isEmpty() -> "没搜到「$title${if (artist.isNotBlank()) " - $artist" else ""}」的封面，换个写法试试"
                else -> null
            }
        }
    }

    /** 选定某张候选封面：落到这首歌上并写盘 */
    fun applyCover(songId: Long, candidate: CoverCandidate) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val song = songById(songId)
            coverSearching = true
            val raw = withContext(Dispatchers.IO) { OnlineCover.downloadBytes(candidate.fullUrl) }
            if (raw == null) {
                coverSearching = false
                coverNotice = "这张封面下载失败，换一张试试"
                return@launch
            }
            withContext(Dispatchers.IO) {
                song?.let { CoverCache.store(app, OnlineCover.keyOf(it.title, it.artist), raw) }
            }
            // 只有本地歌才记「用户钉过这张封面」。
            //
            // 在线歌的补图走 ensureOnlineSongCover，它只认音源给的地址、**不读这个 pin** ——
            // 写了也永远不会被读，只是往 SharedPreferences 里堆垃圾（每首一个 key）。
            // 手选的封面本身已经落进 CoverCache，在线歌下次播放照样会用上。
            if (!isRemoteSongId(songId)) prefs.pinCoverUrl(songId, candidate.fullUrl)
            val bmp = OnlineCover.decode(raw, OnlineCover.FULL_PX)
            ArtworkLoader.putOnline(songId, bmp?.asImageBitmap())
            coverTried.add(songId)
            // 刚写盘：让缓存短路失效，并把通知栏 / 锁屏的封面一起换掉
            song?.let {
                ArtworkLoader.forgetCoverMiss(OnlineCover.keyOf(it.title, it.artist))
                player.refreshCurrentArtwork(it)
            }
            coverSearching = false
            coverResults = emptyList()
            coverTargetSongId = 0L
            refreshCoverCacheStats()
            showToast("封面已更新")
        }
    }

    /** 删掉这首歌的封面（回到占位图；联网状态下下次播放会重新找） */
    fun clearCover(songId: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val song = songById(songId)
            withContext(Dispatchers.IO) {
                song?.let { CoverCache.remove(app, OnlineCover.keyOf(it.title, it.artist)) }
            }
            prefs.pinCoverUrl(songId, null)
            ArtworkLoader.putOnline(songId, null)
            coverTried.remove(songId)
            ArtworkLoader.clear()
            // 封面文件已删 → 通知栏也要跟着退回 MediaStore 的封面地址
            song?.let { player.refreshCurrentArtwork(it) }
            refreshCoverCacheStats()
            showToast("已清除这首歌的封面")
        }
    }

    fun openCoverPicker(songId: Long) {
        coverTargetSongId = songId
        coverResults = emptyList()
        coverNotice = null
    }

    fun closeCoverPicker() {
        coverTargetSongId = 0L
        coverResults = emptyList()
        coverNotice = null
    }

    fun refreshCoverCacheStats() {
        viewModelScope.launch {
            coverCacheCount = withContext(Dispatchers.IO) { CoverCache.count(getApplication()) }
        }
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CoverCache.clear(getApplication()) }
            ArtworkLoader.clear()
            coverTried.clear()
            coverCacheCount = 0
            showToast("已清除封面缓存")
        }
    }

    /** 歌词缓存条数（含"确认没有"的哨兵），设置页展示用 */
    var lyricsCacheCount by mutableIntStateOf(0)
        private set

    fun refreshLyricsCacheStats() {
        viewModelScope.launch {
            lyricsCacheCount = withContext(Dispatchers.IO) { LyricsCache.count(getApplication()) }
        }
    }

    fun clearLyricsCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LyricsCache.clear(getApplication()) }
            LyricsRepository.clear()
            lyricsCacheCount = 0
            showToast("已清除歌词缓存")
        }
    }

    // ===== 列表定位：回到歌曲列表时滚到正在播放的歌 =====

    /** 每次自增代表一次"滚到当前歌曲"的请求；SongList 监听它 */
    var scrollToCurrentSignal by mutableIntStateOf(0)
        private set

    fun requestScrollToCurrent() {
        scrollToCurrentSignal++
    }

    fun changeSkin(value: Skin) {
        skin = value
        prefs.skinId = value.id
        showToast("已切换皮肤 · ${value.name}")
    }

    /** 当前选中的应用图标（id 对应 manifest 里的 activity-alias 后缀） */
    var appIconId by mutableStateOf(prefs.appIconId)
        private set

    fun changeAppIcon(id: String) {
        if (id == appIconId) return
        AppIconSwitcher.apply(getApplication(), id)
        appIconId = id
        prefs.appIconId = id
        showToast("已更换图标 · 桌面稍后刷新")
    }

    /**
     * 排序需要的用户行为数据。
     *
     * 只在排序时算一次，不做成常驻状态 —— `sp.all` 是同步读，放进 Composition 会拖慢每一帧。
     */
    private fun songStats() = SongStats(
        playCounts = prefs.allPlayCounts(),
        recentOrder = prefs.recentOrderMap()
    )

    fun changeSort(value: SortField) {
        if (sortField == value) {
            sortAscending = !sortAscending
        } else {
            sortField = value
            // 「播放最多 / 最近播放」的自然读法是"最多的 / 最近的在前"，所以切过去默认降序。
            // 其他字段保持原来的默认升序。
            sortAscending = value != SortField.PLAY_COUNT && value != SortField.RECENT_PLAYED
        }
        prefs.sortField = sortField.name
        prefs.sortAscending = sortAscending
    }

    fun toggleSortDirection() {
        sortAscending = !sortAscending
        prefs.sortAscending = sortAscending
    }

    fun changeViewMode(value: ViewMode) {
        viewMode = value
        prefs.viewMode = value.name
    }

    fun changeQuery(value: String) {
        searchQuery = value
    }

    fun toggleFavorite(id: Long): Boolean {
        // 在线歌不能收藏。
        //
        // 「收藏」只存一个 id，而在线歌的 id 是**由 (音源, 远端 id) 哈希出来的**：
        // 收藏页要靠 id 反查 [songMap] 才能显示，而冷启动后 onlineSongMap 是空的 ——
        // 于是收藏页会永久少掉这一首，计数却还多着，用户找不到也删不掉。
        //
        // 要真正支持得把歌名/歌手/音源/远端 id 一起持久化（等于做一套"在线歌单"），
        // 那是另一个功能。这里先明确拒绝，不制造查不到的幽灵数据。
        if (isRemoteSongId(id)) {
            showToast("在线歌不能收藏")
            return false
        }
        val liked = prefs.toggleFavorite(id)
        favorites = prefs.favorites
        showToast(if (liked) "已添加到收藏" else "已取消收藏")
        return liked
    }

    fun clearRecent() {
        prefs.clearRecent()
        recentIds = emptyList()
        recentEntries = emptyList()
        showToast("已清空最近播放")
    }

    fun showToast(msg: String) {
        toastMessage = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            // 长文本多停留一会儿，避免没读完就消失
            delay((1200L + msg.length * 70L).coerceIn(1800L, 6000L))
            toastMessage = null
        }
    }

    // ===== 在线音乐 =====
    //
    // 和在线歌词 / 在线封面同一个原则：**默认关闭，联网要用户显式打开**。
    // 关掉时这一页只有一个开启入口，一个网络请求都不发。

    var onlineMusicEnabled by mutableStateOf(prefs.onlineMusicEnabled)
        private set

    var onlineSourceId by mutableStateOf(prefs.onlineSourceId)
        private set

    /**
     * 用网易云补全**本地歌**的封面与歌词。默认关闭。
     *
     * 和「在线封面」「在线歌词」并列的第三个联网开关，同样是默认关。
     */
    var neteaseFillEnabled by mutableStateOf(prefs.neteaseFillEnabled)
        private set

    fun changeNeteaseFill(enabled: Boolean) {
        neteaseFillEnabled = enabled
        prefs.neteaseFillEnabled = enabled
        showToast(
            if (enabled) "已开启网易云补全（会联网补封面与歌词）"
            else "已关闭网易云补全"
        )
    }

    // ===== 播放并下载 =====

    var onlineDownloadEnabled by mutableStateOf(prefs.onlineDownloadEnabled)
        private set

    var onlineDownloadWifiOnly by mutableStateOf(prefs.onlineDownloadWifiOnly)
        private set

    /** 已下载的条数（设置页显示用）。文件本身在系统音乐目录里，由用户自己管理 */
    var downloadedCount by mutableIntStateOf(prefs.downloadedKeys().size)
        private set

    /** 正在跑的下载。同时只允许一个 —— 并发下载会和播放抢带宽 */
    private var downloadJob: Job? = null

    fun changeOnlineDownload(enabled: Boolean) {
        onlineDownloadEnabled = enabled
        prefs.onlineDownloadEnabled = enabled
        showToast(
            if (enabled) {
                "已开启播放并下载 · 存到「${OnlineDownloader.FOLDER_NAME}」文件夹"
            } else {
                "已关闭播放并下载"
            }
        )
    }

    fun changeDownloadWifiOnly(wifiOnly: Boolean) {
        onlineDownloadWifiOnly = wifiOnly
        prefs.onlineDownloadWifiOnly = wifiOnly
    }

    /**
     * 清掉下载记录（**不删文件**）。
     *
     * 刻意只清记录：文件在公开音乐目录里，是用户自己的东西，
     * 一个播放器不该去批量删用户的文件。清掉记录之后重播会再存一份。
     */
    fun clearDownloadRecords() {
        prefs.clearDownloadedKeys()
        downloadedCount = 0
        showToast("已清除下载记录（文件未删除）")
    }

    /**
     * 播放到一首在线歌时调用：确认用户真的在听，然后存一份到本地。
     *
     * 存的是**正在播的那条流**（标准音质），不是官方下载接口的高音质版本 ——
     * 那些端点要登录 + 会员，这里刻意不碰。
     */
    private fun maybeDownload(song: Song) {
        if (!onlineDownloadEnabled) return
        if (song.isLocal) return
        val src = OnlineSources.byId(song.source) ?: return
        val remoteId = song.remoteId ?: return
        val key = OnlineDownloader.downloadKeyOf(song.source, remoteId) ?: return
        if (key in prefs.downloadedKeys()) return
        // 上一首还没下完就跳过这一首。用队列会更"完整"，但并发下载会和播放抢带宽，
        // 而这首歌下次再播还会触发 —— 不值得为它引入一个队列
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            // ★ 先等一会儿再下。
            //
            // 用户连点十几首跳着听的时候，一开播就下会把每一首都存下来
            // （实测一首 4.3 MB，十几首就是 50 MB+）。等够 [DOWNLOAD_ARM_DELAY_MS]
            // 说明他确实在听这一首。
            delay(DOWNLOAD_ARM_DELAY_MS)

            // 等待期间可能已经切歌 / 关掉开关了
            if (player.state.value.mediaId != song.id) return@launch
            if (!onlineDownloadEnabled) return@launch

            val app = getApplication<Application>()
            // 只在 Wi-Fi 下下载：判断不出来时**跳过**而不是赌一把
            if (onlineDownloadWifiOnly && !OnlineDownloader.isOnUnmeteredNetwork(app)) {
                android.util.Log.i("SonanceDownload", "非 Wi-Fi，跳过「${song.title}」")
                return@launch
            }

            // 歌词：顺手取一份写进文件。
            //
            // 只在用户开了「在线歌词」时才取 —— 那是"允许联网取词"的总开关，
            // 下载路径不该绕开它。取到了既写进 ID3（USLT 帧），也顺带落进歌词缓存，
            // 于是这首歌在应用里第一次打开歌词页就是现成的，不用再联网。
            val lyricsText = if (onlineLyricsEnabled) {
                runCatching {
                    LyricsRepository.textForTag(
                        context = app,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        apiBase = lyricsApiBase,
                        neteaseEnabled = neteaseFillEnabled
                    )
                }.getOrNull()
            } else {
                null
            }

            val outcome = OnlineDownloader.download(
                context = app,
                source = song.source,
                remoteId = remoteId,
                title = song.title,
                artist = song.artist,
                album = song.album,
                streamUrl = src.streamUrl(remoteId),
                coverBytes = coverBytesForTag(song),
                lyrics = lyricsText
            )
            when (outcome) {
                is OnlineDownloader.Outcome.Saved -> {
                    prefs.markDownloaded(key)
                    downloadedCount = prefs.downloadedKeys().size
                    showToast("已存到「${OnlineDownloader.FOLDER_NAME}」")
                    // 立刻并进曲库，否则「文件夹」页看不到这个分类
                    rescanAfterDownload()
                }
                // 会员歌 / 版权下架：静默跳过。它本来也播不了，
                // 弹一句"下载失败"只会让用户以为哪里坏了
                OnlineDownloader.Outcome.Unavailable -> Unit
                OnlineDownloader.Outcome.Failed -> showToast("「${song.title}」保存失败")
                OnlineDownloader.Outcome.Skipped -> Unit
            }
        }
    }

    /**
     * 给 ID3 标签准备封面字节。
     *
     * 优先用**已经落盘的那张** —— `ensureOnlineSongCover` 通常已经在下载了，
     * 没必要为写标签再拉一次。真没有再按在线歌自带的地址取，
     * 取到顺手写进 [CoverCache]，这样封面补全那边也不会重复下载。
     */
    private suspend fun coverBytesForTag(song: Song): ByteArray? {
        val key = OnlineCover.keyOf(song.title, song.artist) ?: return null
        val app = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            when (val hit = CoverCache.lookup(app, key)) {
                is CoverCache.Lookup.Hit -> hit.bytes
                else -> {
                    val url = song.coverUrl ?: return@withContext null
                    OnlineCover.downloadBytes(url)?.also { CoverCache.store(app, key, it) }
                }
            }
        }
    }

    /** 当前音源。清单里没有（比如配置被改坏）时返回 null，UI 按"不可用"处理 */
    val onlineSource: OnlineSource? get() = OnlineSources.byId(onlineSourceId)

    /** 搜索框里的词（受控，输入时就在更新） */
    var onlineQuery by mutableStateOf("")

    /**
     * 当前搜索结果（**已累积多页**）。**包含不可播的** —— 标出来比静默过滤诚实
     */
    var onlineResults by mutableStateOf<List<RemoteTrack>>(emptyList())
        private set

    /**
     * 列表里把「播不了的」过滤掉（VIP / 需购买）。默认开。
     *
     * 只影响显示，[onlineResults] 本身不变。
     */
    var onlineHideUnplayable by mutableStateOf(prefs.onlineHideUnplayable)
        private set

    fun changeOnlineHideUnplayable(v: Boolean) {
        onlineHideUnplayable = v
        prefs.onlineHideUnplayable = v
    }

    /**
     * 列表要显示的曲目，带**在 [onlineResults] 里的原始下标**。
     *
     * 必须带下标：过滤之后下标就错位了，直接用过滤后列表的下标点播放会播成另一首。
     * 播放队列也是按 [onlineResults] 建的，两边要对得上。
     */
    val visibleOnlineResults: List<Pair<Int, RemoteTrack>>
        get() = if (onlineHideUnplayable) {
            onlineResults.withIndex().filter { it.value.playable }.map { it.index to it.value }
        } else {
            onlineResults.mapIndexed { i, t -> i to t }
        }

    var onlineSearching by mutableStateOf(false)
        private set

    /** 后面还有没有下一页。列表滚到底时据此决定要不要继续请求 */
    var onlineHasMore by mutableStateOf(false)
        private set

    /** 正在加载下一页（和 [onlineSearching] 分开：前者是"换搜索词"，后者是"往下翻"） */
    var onlineLoadingMore by mutableStateOf(false)
        private set

    /** 加载下一页失败了。UI 显示可点击重试的页脚 —— 网络抖一下不该让用户重新搜一遍 */
    var onlineLoadMoreFailed by mutableStateOf(false)
        private set

    /**
     * 已经取回多少条。
     *
     * 直接当下一页的 `offset` 用。**不能用 `onlineResults.size`** ——
     * 追加时会按 `remoteId` 去重，去掉重复之后 size 会小于服务端已返回的条数，
     * 于是 offset 偏小、下一页会重复请求同一段（然后又被去重掉，永远翻不到底）。
     */
    private var onlineLoadedCount = 0

    /** 搜索失败 / 无结果的提示语，null 表示正常 */
    var onlineNotice by mutableStateOf<String?>(null)
        private set

    /** 这一页结果是用哪个词搜出来的（空态文案要用，不能读 onlineQuery —— 用户可能已经改了） */
    var onlineSearchedFor by mutableStateOf("")
        private set

    /**
     * 上一次搜索是**失败**（超时 / 断网）还是**确实没搜到**。
     *
     * 两者都是"结果为空"，但对用户的含义完全不同：断网时显示「没有结果」，
     * 用户会以为这首歌真的不存在。空态标题必须分开。
     */
    var onlineSearchFailed by mutableStateOf(false)
        private set

    /**
     * 在线歌的 id → [Song]。
     *
     * 为什么必须留着：播放队列、队列页、播放页、收藏判断全都按 id 查 [songMap]，
     * 而在线歌不在本地曲库里。**只增不减** —— 用户搜了新的词之后，
     * 上一轮搜索留在队列里的歌还得能查到，不然队列页会凭空少几首。
     *
     * 用 SnapshotStateMap 是为了让组合能观察到它（配合 [onlineRevision]）。
     */
    private val onlineSongMap = mutableStateMapOf<Long, Song>()

    /** 在线歌集合的版本号。`remember` 需要一个不可变的 key，而 map 本身是可变的 */
    var onlineRevision by mutableIntStateOf(0)
        private set

    fun changeOnlineMusic(enabled: Boolean) {
        onlineMusicEnabled = enabled
        prefs.onlineMusicEnabled = enabled
        if (!enabled) clearOnlineSearch()
        showToast(
            if (enabled) "已开启在线音乐（会联网搜索与播放）"
            else "已关闭在线音乐（不再联网）"
        )
    }

    fun changeOnlineSource(id: String) {
        onlineSourceId = id
        prefs.onlineSourceId = id
        // 换音源之后旧结果没意义了（id 是各音源自己的命名空间）
        clearOnlineSearch()
    }

    /** 正在跑的那次搜索。新的一次会取消它，理由见 [searchOnline] */
    private var onlineSearchJob: Job? = null

    /** 正在跑的"加载下一页"。和搜索分开存，取消时两个都要取消 */
    private var onlineLoadMoreJob: Job? = null

    /**
     * 搜索的代号。
     *
     * 取消一个协程时，它的 `finally` **仍然会执行** —— 而且是在新任务已经跑起来
     * 之后才执行。所以只靠 cancel 挡不住"旧任务把新任务的转圈状态抹掉"，
     * 需要一个"我还是当前那次吗"的判据。
     */
    private var onlineSearchToken = 0

    /**
     * 搜索（第一页）。
     *
     * ## 为什么要能取消
     *
     * 用户敲字是有节奏的：输入「周杰」停一下、又补个「伦」。两次请求都会发出去，
     * 而**先发的那个不保证先回来**（服务端快慢、网络抖动）。慢的那个晚到就会
     * 覆盖掉新结果 —— 用户看到的是「周杰」的结果，输入框里却写着「周杰伦」。
     *
     * 取消之后协程不会 resume 到赋值那几行，旧结果自然进不来。
     *
     * ## 分页
     *
     * 一次取 [ONLINE_PAGE_SIZE] 条，滚到底再取下一页（[loadMoreOnline]）。
     * 这里每次都**从头开始**：换搜索词之后旧结果作废，累积条数也要归零。
     */
    fun searchOnline(query: String = onlineQuery) {
        val src = onlineSource ?: run {
            onlineNotice = "音源不可用"
            return
        }
        val kw = query.trim()
        if (kw.isEmpty()) {
            clearOnlineSearch()
            return
        }
        onlineQuery = query

        cancelOnlineRequests()
        val token = ++onlineSearchToken
        onlineSearchJob = viewModelScope.launch {
            onlineSearching = true
            onlineNotice = null
            try {
                val app = getApplication<Application>()
                val page = withContext(Dispatchers.IO) { src.search(kw, ONLINE_PAGE_SIZE, 0) }
                val tracks = page?.tracks.orEmpty()

                // 把结果转成 Song 存进映射，队列 / 播放页 / 收藏判断才查得到
                commitOnlinePage(src, tracks)

                onlineResults = tracks
                onlineLoadedCount = tracks.size
                onlineSearchedFor = kw
                onlineSearchFailed = page == null
                // 用音源报的**总数**判断，不能用"这页满没满"（见 RemoteSearchPage 的说明）
                onlineHasMore = page != null &&
                    page.hasMore(offset = 0, limit = ONLINE_PAGE_SIZE) &&
                    tracks.size < MAX_ONLINE_RESULTS
                onlineLoadMoreFailed = false
                // 失败和"没搜到"要分开说：断网时显示「没搜到「周杰伦」」
                // 会让用户以为搜索坏了（音源用 null 表示失败）
                onlineNotice = when {
                    page == null -> "搜索失败，检查一下网络或稍后再试"
                    tracks.isEmpty() -> "没搜到「$kw」，换个写法或换首歌试试"
                    else -> null
                }
            } finally {
                // 只有"我还是当前那次"才允许收掉转圈
                if (token == onlineSearchToken) onlineSearching = false
            }
        }
    }

    /**
     * 加载下一页。
     *
     * 由列表滚到底触发（见 `OnlineScreen`）。四种情况下直接返回，不重复请求：
     * 没开、正在搜、正在翻、已经到底。
     *
     * **失败不清空已有结果** —— 翻页失败只该让页脚显示"点击重试"，
     * 把用户已经翻到的内容清掉是最糟的处理。
     */
    fun loadMoreOnline() {
        val src = onlineSource ?: return
        if (!onlineMusicEnabled) return
        if (onlineSearching || onlineLoadingMore || !onlineHasMore) return
        val kw = onlineSearchedFor
        if (kw.isEmpty()) return

        val token = onlineSearchToken
        val offset = onlineLoadedCount
        onlineLoadMoreJob = viewModelScope.launch {
            onlineLoadingMore = true
            onlineLoadMoreFailed = false
            try {
                val app = getApplication<Application>()
                val page = withContext(Dispatchers.IO) {
                    src.search(kw, ONLINE_PAGE_SIZE, offset)
                }
                // 期间换了搜索词 / 清空了：这一页已经不属于当前列表，丢掉
                if (token != onlineSearchToken) return@launch

                if (page == null) {
                    onlineLoadMoreFailed = true
                    return@launch
                }
                val tracks = page.tracks
                if (tracks.isEmpty()) {
                    onlineHasMore = false
                    return@launch
                }

                // 按 remoteId 去重：服务端的 offset 在结果变动时可能给出重叠的一段。
                // 网易云在 offset 超过总数时还会**绕回第一页**，那时整页都会被去重掉 ——
                // 下面的 hasMore 用总数算，所以不会因此反复请求。
                val seen = onlineResults.mapTo(HashSet(onlineResults.size * 2)) { it.remoteId }
                val fresh = tracks.filter { it.remoteId !in seen }

                commitOnlinePage(src, fresh)
                onlineResults = onlineResults + fresh
                // offset 按**服务端返回的条数**推进，不是按去重后的
                onlineLoadedCount = offset + tracks.size
                // 总数说了算；另外卡一个上限兜底 —— 万一音源报的总数虚高，
                // 也不会一直翻下去（MAX_ONLINE_RESULTS 说明见常量处）
                onlineHasMore = page.hasMore(offset, ONLINE_PAGE_SIZE) &&
                    onlineLoadedCount < MAX_ONLINE_RESULTS
            } finally {
                if (token == onlineSearchToken) onlineLoadingMore = false
            }
        }
    }

    /** 重试加载下一页（页脚点一下） */
    fun retryLoadMoreOnline() {
        onlineLoadMoreFailed = false
        loadMoreOnline()
    }

    /**
     * 把一页结果并进 [onlineSongMap]。
     *
     * 新增和淘汰都要推进 [onlineRevision] —— 它是 `SonanceRoot` 里 `songMap` 的
     * remember key，漏了"新增"这一半的话，搜出来的歌在队列页里会查不到。
     */
    private fun commitOnlinePage(src: OnlineSource, page: List<RemoteTrack>) {
        if (page.isEmpty()) return
        val songs = page.map { src.toSong(it) }
        var changed = false
        songs.forEach { if (onlineSongMap.put(it.id, it) == null) changed = true }
        // 淘汰时要连**累积的全部结果**一起算保留集，不能只看这一页
        val allIds = onlineResults.map { remoteSongId(src.id, it.remoteId) } + songs.map { it.id }
        changed = pruneOnlineSongs(allIds) || changed
        if (changed) onlineRevision++
    }

    private fun cancelOnlineRequests() {
        onlineSearchJob?.cancel()
        onlineSearchJob = null
        onlineLoadMoreJob?.cancel()
        onlineLoadMoreJob = null
    }

    /**
     * 把不再需要的在线歌从 [onlineSongMap] 里清掉。
     *
     * 只留「这次搜索的结果」和「还在播放队列里的」：
     * 前者是列表要显示的，后者是队列页 / 播放页 / 收藏判断要反查的。
     *
     * 不清理的话，用户搜 N 次就攒下 N×30 首、永不释放。单首不大，
     * 但没有理由让它无限长 —— 和 `onlineCovers` 加 120 上限是同一个考虑。
     *
     * @return 有没有真的删掉东西（调用方据此决定要不要推进版本号）
     */
    private fun pruneOnlineSongs(keepFromResults: List<Long>): Boolean {
        val st = player.state.value
        val keep = onlineSongIdsToKeep(keepFromResults, st.queueIds, st.mediaId)

        var removed = false
        val it = onlineSongMap.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in keep) {
                it.remove()
                removed = true
            }
        }
        return removed
    }

    /**
     * 清空搜索页。
     *
     * 必须**取消在途的请求**：用户清空输入框之后，先前那次请求还会回来，
     * 把结果重新填进一个空搜索框 —— 看起来就是"清不掉"。
     * 递增 token 是为了让那次任务在 `finally` 里认不出自己（它不该再动状态）。
     */
    fun clearOnlineSearch() {
        cancelOnlineRequests()
        onlineSearchToken++
        onlineResults = emptyList()
        onlineLoadedCount = 0
        onlineHasMore = false
        onlineLoadingMore = false
        onlineLoadMoreFailed = false
        onlineSearchedFor = ""
        onlineSearchFailed = false
        onlineNotice = null
        onlineSearching = false
        onlineQuery = ""
    }

    /**
     * 播放搜索结果里的第 [index] 首。
     *
     * 队列只放**能播的**：把 VIP 歌也塞进队列的话，自动续播到它就会卡住报错，
     * 用户还以为播放器坏了。所以不可播的当场给提示，不进队列。
     */
    fun playOnline(index: Int) {
        val src = onlineSource ?: return
        val track = onlineResults.getOrNull(index) ?: return
        if (!track.playable) {
            showToast("「${track.title}」需要会员，拿不到播放地址")
            return
        }
        val playable = onlineResults.filter { it.playable }
        // 复用映射里已经建好的 Song，避免同一首歌算出两个实例。
        // 下标**在过滤之后**再算：mapNotNull 理论上可能丢条目（比如音源被换掉、
        // 映射里还没这一首），先取下标再过滤的话队列和下标就会错位 —— 表现是
        // "点了 A 却播了 B"，而且只在边界情况下出现，很难复现。
        val songs = playable.mapNotNull { onlineSongMap[remoteSongId(src.id, it.remoteId)] }
        val at = songs.indexOfFirst { it.remoteId == track.remoteId }
        if (at < 0) return
        player.play(songs, at)
    }

    /** 在线歌的缩略图地址（列表用）。没有音源或没有封面时返回 null */
    fun onlineThumbUrl(track: RemoteTrack, px: Int): String? {
        val url = track.coverUrl ?: return null
        return onlineSource?.thumbUrl(url, px)
    }

    // ===== 派生数据 =====

    fun visibleSongs(): List<Song> {
        val searched = MediaRepository.search(songs, searchQuery)
        return MediaRepository.sortSongs(searched, sortField, sortAscending, songStats())
    }

    /**
     * 完整曲库（已排序、已排除隐藏项），**不受搜索词影响**。
     *
     * 给「点某首歌播放」用。搜索是**找入口**，不是"我要只听这几首" ——
     * 拿筛选后的列表当队列的话，用户搜一首歌点播放，队列里就只剩搜索结果，
     * 播完就没了（而且搜索命中一首时队列里就只有那一首）。
     *
     * `songs` 本身已经是"扫描时 + 读缓存时"过滤过隐藏项的列表，这里不用再过滤。
     */
    fun librarySongs(): List<Song> =
        MediaRepository.sortSongs(songs, sortField, sortAscending, songStats())

    fun recentSongs(): List<Song> {
        // 本地歌按 id 反查曲库；在线歌用存档重建（播放地址当场重新取）。
        //
        // [recentEntries] 自己是"最新在前"的顺序，不用再排一遍。
        // 以前这里只看 [recentIds]（本地 id），在线歌就永远进不来。
        return recentEntries.mapNotNull { entry ->
            if (entry.isOnline) entry.online?.toSong()
            else songs.firstOrNull { it.id == entry.localId }
        }
    }

    fun favoriteSongs(): List<Song> {
        val searched = MediaRepository.search(songs.filter { favorites.contains(it.id) }, searchQuery)
        return MediaRepository.sortSongs(searched, sortField, sortAscending, songStats())
    }

    fun songById(id: Long): Song? = songs.firstOrNull { it.id == id } ?: onlineSongMap[id]

    fun albumById(albumId: Long): Album? = albums.firstOrNull { it.albumId == albumId }

    fun artistByName(name: String): Artist? = artists.firstOrNull { it.name == name }

    fun folderByPath(path: String): MusicFolder? = folders.firstOrNull { it.path == path }

    // ===== 均衡器 =====
    //
    // 走**软件实现**（EqProcessor 挂在音频链路上），不是系统 AudioEffect ——
    // 这台 ColorOS 的 AudioFlinger 拒绝给应用会话创建音效，系统均衡器在它上面
    // 根本建不起来。软件实现全机型可用，段数也固定 10 段，换机不变形。

    /** 各段增益（dB），UI 滑杆直接绑它 */
    var eqBands by mutableStateOf(FloatArray(EQ_BAND_COUNT))
        private set

    var eqPreset by mutableStateOf(EqPreset.OFF)
        private set

    /** 总开关。"关闭"预设等价于 false，此时整条滤波链旁路 */
    var eqEnabled by mutableStateOf(false)
        private set

    /** 用户手动拖过滑杆：UI 据此取消预设高亮、显示"自定义" */
    var eqCustom by mutableStateOf(false)
        private set

    val eqCenterFreqs: IntArray get() = EQ_CENTER_FREQS
    val eqMinDb: Float get() = EQ_MIN_DB
    val eqMaxDb: Float get() = EQ_MAX_DB

    // 单独一个 init 块：必须放在上面几个属性**之后**。
    // Kotlin 的初始化按源码顺序执行，写进前面那个 init 块会被这里的
    // `by mutableStateOf(...)` 初值覆盖掉。
    init {
        val preset = EqPreset.fromName(prefs.eqPreset)
        val custom = parseEqBands(prefs.eqCustomBands)
        eqPreset = preset
        eqCustom = custom != null
        eqBands = custom ?: EqPreset.curveOf(preset)
        // 自定义值一旦存在就以它为准（用户拖过的成果不能被预设抹掉）
        eqEnabled = if (custom != null) prefs.eqEnabled else prefs.eqEnabled && preset != EqPreset.OFF
        pushEq()
    }

    /**
     * 打开均衡器面板时调用。
     *
     * 软件均衡器没有"挂载失败"这回事，这里只把状态再推一遍 ——
     * 防的是进程被回收后重建 ViewModel 时总线与界面不同步。
     */
    fun openEqualizer() {
        pushEq()
    }

    fun toggleEq(on: Boolean) {
        eqEnabled = on
        prefs.eqEnabled = on
        pushEq()
        showToast(if (on) "均衡器已开启" else "均衡器已关闭")
    }

    fun chooseEqPreset(preset: EqPreset) {
        eqPreset = preset
        eqCustom = false
        eqBands = EqPreset.curveOf(preset)
        eqEnabled = preset != EqPreset.OFF
        prefs.eqPreset = preset.name
        prefs.eqCustomBands = ""
        prefs.eqEnabled = eqEnabled
        pushEq()
        showToast("音效 · ${preset.label}")
    }

    /**
     * 拖某一段。
     *
     * 会打开总开关（用户拖滑杆的意思显然是想听到效果），并转入"自定义"。
     * 预设名保留不清 —— 于是"在流行基础上微调"这种用法，回头点一下"流行"
     * 就能还原。
     */
    fun setEqBand(index: Int, db: Float) {
        if (index !in eqBands.indices) return
        val next = eqBands.copyOf()
        next[index] = db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
        eqBands = next
        eqCustom = true
        eqEnabled = true
        prefs.eqEnabled = true
        prefs.eqCustomBands = next.joinToString(",")
        pushEq()
    }

    /** 复位成平直（自定义状态下的"归零"） */
    fun resetEqBands() {
        eqBands = FloatArray(EQ_BAND_COUNT)
        eqCustom = false
        prefs.eqCustomBands = ""
        pushEq()
        showToast("已复位为平直")
    }

    private fun pushEq() {
        EqBus.update(eqEnabled, eqBands)
    }

    /** 解析持久化的自定义曲线；长度不对或含脏数据都返回 null（回落到预设） */
    private fun parseEqBands(raw: String): FloatArray? {
        if (raw.isBlank()) return null
        val parts = raw.split(',')
        if (parts.size != EQ_BAND_COUNT) return null
        val out = FloatArray(EQ_BAND_COUNT)
        var i = 0
        while (i < EQ_BAND_COUNT) {
            val v = parts[i].trim().toFloatOrNull() ?: return null
            out[i] = v.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
            i++
        }
        return out
    }

    // ===== 睡眠定时器 =====

    /** 睡眠定时器模式 */
    enum class SleepTimerMode { OFF, COUNTDOWN, AFTER_CURRENT }

    var sleepTimerMode by mutableStateOf(SleepTimerMode.OFF)
        private set

    /** 剩余毫秒（倒计时模式）；UI 每秒刷新 */
    var sleepTimerRemainingMs by mutableLongStateOf(0L)
        private set

    /** 倒计时选的是多少分钟（面板高亮用）；非倒计时模式为 0 */
    var sleepTimerTotalMinutes by mutableIntStateOf(0)
        private set

    /** 「播完当前曲」模式下，记住开启时是哪一首 */
    private var sleepAnchorSongId = -1L
    private var sleepTimerJob: Job? = null

    /** 定时器状态文案，null 表示未开启 */
    val sleepTimerLabel: String?
        get() = when (sleepTimerMode) {
            SleepTimerMode.OFF -> null
            SleepTimerMode.AFTER_CURRENT -> "播完当前曲"
            SleepTimerMode.COUNTDOWN -> {
                val total = (sleepTimerRemainingMs / 1000).coerceAtLeast(0)
                "%d:%02d".format(total / 60, total % 60)
            }
        }

    /** 倒计时 N 分钟后暂停 */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerMode = SleepTimerMode.COUNTDOWN
        sleepTimerTotalMinutes = minutes
        val totalMs = minutes * 60_000L
        sleepTimerRemainingMs = totalMs
        val startedAt = android.os.SystemClock.elapsedRealtime()

        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                val left = totalMs - (android.os.SystemClock.elapsedRealtime() - startedAt)
                if (left <= 0L) break
                sleepTimerRemainingMs = left
                delay(1_000L)
            }
            // 到点：暂停播放（已经在暂停状态就什么都不做）
            if (player.state.value.isPlaying) player.togglePlay()
            clearSleepTimer()
            showToast("睡眠定时器到点，已暂停播放")
        }
        showToast("将在 $minutes 分钟后暂停播放")
    }

    /**
     * 播完当前这首就暂停。
     *
     * 不用 `onSongStarted` 来做 —— 那要等下一首**已经开始播**才触发，会多放一小段。
     * 这里轮询进度，在离结尾还有 700ms 时就暂停。
     */
    fun setSleepAfterCurrent() {
        sleepTimerJob?.cancel()
        sleepTimerMode = SleepTimerMode.AFTER_CURRENT
        sleepTimerTotalMinutes = 0
        sleepTimerRemainingMs = 0L
        sleepAnchorSongId = player.state.value.mediaId

        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                val st = player.state.value
                val ended = st.mediaId != sleepAnchorSongId ||
                    (st.durationMs > 0 && st.positionMs >= st.durationMs - 700L)
                if (ended) {
                    if (player.state.value.isPlaying) player.togglePlay()
                    clearSleepTimer()
                    showToast("睡眠定时器：已暂停播放")
                    return@launch
                }
                delay(400L)
            }
        }
        showToast("播完当前这首就暂停")
    }

    fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerMode = SleepTimerMode.OFF
        sleepTimerTotalMinutes = 0
        sleepTimerRemainingMs = 0L
        sleepAnchorSongId = -1L
    }

    /**
     * id → Song 的全量索引，**含在线歌**。
     *
     * 队列、队列页、播放页都靠它按 id 反查。在线歌不在 [songs] 里，
     * 不并进来的话：播在线歌时队列页会是空的、播放页拿不到歌名歌手、
     * 封面也换不了。
     *
     * ⚠️ 三个来源缺一不可，尤其是第三项 [recentEntries] 里的**在线存档**：
     * [onlineSongMap] 是纯内存的（本次会话搜到过才在），进程重启就空了，
     * 但队列和最近播放里还留着那些负数 id。少了存档这一路，那些 id 会解析成
     * null —— 而下游是按**列表下标**取歌的（`queue[index]`），
     * 一个 null 被丢掉，后面的下标就全体前移一位：
     * 表现就是「播放条明明是这首、进播放页却是另一首」（用户报的 bug）。
     */
    fun songMap(): Map<Long, Song> {
        val base = songs.associateBy { it.id }
        if (onlineSongMap.isEmpty() && recentEntries.isEmpty()) return base
        val online = HashMap<Long, Song>(onlineSongMap.size + recentEntries.size)
        online.putAll(onlineSongMap)
        // 存档只补缺：本次搜到的结果信息更新（地址是刚取的）
        recentEntries.forEach { e ->
            val s = e.online?.toSong() ?: return@forEach
            online.putIfAbsent(s.id, s)
        }
        if (online.isEmpty()) return base
        return base + online
    }

    /**
     * 播放器队列 → 界面用的 [Song] 列表，**并给出当前曲在结果里的真实下标**。
     *
     * ## 为什么不直接用 `queueIds.mapNotNull { songMap[it] }`
     *
     * 那样写等于允许"悄悄少掉几条"。而队列的消费方（播放页分页器、队列面板、
     * 上一首/下一首）拿到的都是**下标**，少一条就全体错位：显示的、点的、删的
     * 会一起偏 —— 这类错误不报异常、不打日志，表面只是"串了一首"，极难查。
     *
     * 下标对齐的规则本体在 [PlaybackRestore.alignIndices]（纯函数、有单测），
     * 这里只负责把原始下标换成 [Song]。
     *
     * @param current 当前正在播的那首（调用方已按 mediaId 查好）。它解析不出来时
     *   （例如文件已被删除但播放器还停在它上面），单独补在最前面，保证播放页
     *   显示的仍然是"正在播的这首"，而不是队列里恰好排第一的另一首。
     */
    fun resolveQueue(ids: List<Long>, index: Int, current: Song?): ResolvedQueue {
        val map = songMap()
        val (kept, mapped) = PlaybackRestore.alignIndices(ids, index) { map[it] != null }
        val songs = ArrayList<Song>(kept.size + 1)
        val src = ArrayList<Int>(kept.size + 1)
        kept.forEach { i ->
            songs += map.getValue(ids[i])
            src += i
        }
        var at = mapped
        if (at < 0 && current != null) {
            songs.add(0, current)
            src.add(0, index)
            at = 0
        }
        return ResolvedQueue(songs, at.coerceAtLeast(0), src)
    }

    // ===== 随机播放 =====

    /**
     * 开启随机前的原始队列顺序，关闭随机时用来还原。
     * 只保存一份 —— 每次「开」的时候覆盖，「关」的时候消费掉。
     */
    private var preShuffleOrder: List<Song>? = null

    /**
     * 当前播放队列（按播放器里的顺序还原成 Song 列表）。
     *
     * 用 [resolveQueue] 而不是 `mapNotNull`：**下标和列表必须成套拿**。
     * 单独拿列表、再用播放器的原始下标去取，队列里有解析不出来的条目时
     * 取到的就是另一首 —— 下面 [toggleShuffle] 要把"当前这首"挪到队首，
     * 取错了就是把别的歌当成了当前曲。
     */
    private fun currentQueue(): ResolvedQueue {
        val st = player.state.value
        return resolveQueue(st.queueIds, st.queueIndex, songById(st.mediaId))
    }

    /**
     * 开关随机播放。
     *
     * 为什么是**重排队列**而不是只切播放器的 shuffle 标志：
     * 播放页的上下滑动是 `VerticalPager`，页序 = 队列顺序。只切
     * `shuffleModeEnabled` 的话，`seekToNext()` 确实会随机，但**滑动仍然是时间轴顺序** ——
     * 这就是"开了随机，上下滑还是按原顺序"的原因。
     *
     * 这里直接把队列本身打乱（当前歌放队首，其余随机），于是：
     * 上下滑动、上一首/下一首、自动续播、队列页显示，全都走同一份随机序。
     * 关闭时用 [preShuffleOrder] 还原原顺序，并把播放位置停在当前这首歌上。
     */
    /**
     * @param quiet 不弹提示。三态切换（[cyclePlayMode]）内部调它时要传 true ——
     *   否则会先闪一下"随机播放已开启"再被"列表循环"顶掉，看着像抖了一下。
     */
    fun toggleShuffle(quiet: Boolean = false) {
        val st = player.state.value
        if (!st.shuffle) {
            val resolved = currentQueue()
            val q = resolved.songs
            if (q.size > 1) {
                preShuffleOrder = q
                // 用**解析后**的下标取当前曲（见 currentQueue 的说明）
                val cur = q.getOrNull(resolved.index)
                val rest = q.filter { it.id != cur?.id }.shuffled()
                val shuffled = if (cur != null) listOf(cur) + rest else rest
                player.toggleShuffle()
                // 保住进度：重排队列不等于重头播
                player.play(shuffled, 0, autoplay = st.isPlaying, startPositionMs = st.positionMs)
                if (!quiet) showToast("随机播放已开启")
                return
            }
        } else {
            val orig = preShuffleOrder
            if (orig != null) {
                val idx = orig.indexOfFirst { it.id == st.mediaId }.coerceAtLeast(0)
                player.toggleShuffle()
                player.play(orig, idx, autoplay = st.isPlaying, startPositionMs = st.positionMs)
                preShuffleOrder = null
                if (!quiet) showToast("随机播放已关闭")
                return
            }
        }
        // 队列只有一首 / 没有记录过原顺序：只切标志
        player.toggleShuffle()
    }

    /**
     * 三态播放模式：**随机 → 列表循环 → 单曲循环 → 随机**。
     *
     * 播放页那个按钮点一下就调它。
     *
     * 底层要同时动两个标志（`shuffleModeEnabled` + `repeatMode`），
     * 这里按目标状态一次性设好，而不是"先关一个再开另一个" ——
     * 中间态会短暂出现"随机 + 单曲循环"这种矛盾组合，播放器可能刚好在那一瞬
     * 决定下一首放什么。
     */
    fun cyclePlayMode() {
        val st = player.state.value
        val next = PlayMode.of(st.shuffle, st.repeat).next()
        when (next) {
            PlayMode.SHUFFLE -> {
                // 随机也要能一直放下去，所以 repeat 设成 ALL 而不是 OFF
                if (!st.shuffle) toggleShuffle(quiet = true)
                player.setRepeat(RepeatMode.ALL)
            }

            PlayMode.REPEAT_ALL -> {
                if (st.shuffle) toggleShuffle(quiet = true)
                player.setRepeat(RepeatMode.ALL)
            }

            PlayMode.REPEAT_ONE -> {
                if (st.shuffle) toggleShuffle(quiet = true)
                player.setRepeat(RepeatMode.ONE)
            }
        }
        showToast(next.label)
    }

    // ===== 播放入口 =====

    fun playFrom(list: List<Song>, song: Song) {
        val idx = list.indexOfFirst { it.id == song.id }
        player.play(list, if (idx < 0) 0 else idx)
        // 换了播放列表，之前记的原顺序就失效了
        preShuffleOrder = null
        showToast("正在播放 · ${song.title}")
    }

    fun playAll(list: List<Song>, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        val ordered = if (shuffled) list.shuffled() else list
        player.play(ordered, 0)
        preShuffleOrder = null
        showToast(if (shuffled) "随机播放 ${list.size} 首" else "播放全部 ${list.size} 首")
    }

    fun playNext(song: Song) {
        player.playNext(song)
        showToast("已添加到下一首播放")
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    // 供 UI 计算总时长
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
    val totalSizeBytes: Long get() = songs.sumOf { it.sizeBytes }

    companion object {
        /**
         * 一次搜索要多少条。
         *
         * 30 条是权衡：一屏能滚出十来条、够翻两页，而封面缩略图按每张 ~100KB 算
         * 也就 3MB —— 再多就要等明显的一下了。
         */
        private const val ONLINE_PAGE_SIZE = 30

        /**
         * 一次搜索**最多翻到多少条**，10 页。
         *
         * 兜底用。正常情况下 `RemoteSearchPage.hasMore` 靠音源报的总数就能准确停下；
         * 但那是第三方未公开接口，万一哪天总数报虚了（报 5000 实际只给得到 200），
         * 没有上限就会一直往下翻、一直打接口。300 条对一个"搜首歌来听"的场景绰绰有余。
         */
        private const val MAX_ONLINE_RESULTS = 300

        /**
         * 开始播放后**多久**才真正触发下载。
         *
         * 用来把"跳着听"和"真在听"区分开：用户翻歌单时每首都只播两三秒，
         * 等够 8 秒说明他确实在听这一首。太短会把翻过的歌全存下来，太长则要等半天。
         */
        private const val DOWNLOAD_ARM_DELAY_MS = 8_000L
    }
}
