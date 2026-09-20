package com.sonance.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.sonance.player.data.OnlineCover
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
import com.sonance.player.data.SortField
import com.sonance.player.data.UnplayableFileScanner
import com.sonance.player.data.UnplayableScanResult
import com.sonance.player.data.ViewMode
import com.sonance.player.playback.PlayerController
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import kotlinx.coroutines.Job
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

    // ===== 提示 =====
    var toastMessage by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null

    /** 歌曲 id → 最近播放顺序 */
    private val recentOrder: Map<Long, Int> by lazy {
        recentIds.withIndex().associate { (i, id) -> id to i }
    }

    init {
        player.onSongStarted = { id ->
            prefs.pushRecent(id)
            prefs.bumpPlayCount(id)
            recentIds = prefs.recentIds()
            prefs.lastSongId = id
            // 本地没有封面就顺手联网补一张（开关没开时内部直接返回）
            // 刷新通知栏封面不受开关影响：下载过的封面是本地数据，随时该显示
            songById(id)?.let {
                player.refreshCurrentArtwork(it)
                ensureCover(it)
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

    private suspend fun runScan(): ScanOutcome {
        ArtworkLoader.clear()
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

    /** 正在手动选封面的歌曲 */
    var coverTargetSongId by mutableLongStateOf(-1L)
        private set

    var coverCacheCount by mutableIntStateOf(0)
        private set

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
        if (!onlineCoverEnabled) return
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

                val url = prefs.pinnedCoverUrl(song.id)
                    ?: OnlineCover.search(song.title, song.artist, song.album, 1)
                        ?.firstOrNull()?.fullUrl
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
            prefs.pinCoverUrl(songId, candidate.fullUrl)
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
            coverTargetSongId = -1L
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
        coverTargetSongId = -1L
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

    fun changeSort(value: SortField) {
        if (sortField == value) {
            sortAscending = !sortAscending
        } else {
            sortField = value
            sortAscending = true
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
        val liked = prefs.toggleFavorite(id)
        favorites = prefs.favorites
        showToast(if (liked) "已添加到收藏" else "已取消收藏")
        return liked
    }

    fun clearRecent() {
        prefs.clearRecent()
        recentIds = emptyList()
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

    // ===== 派生数据 =====

    fun visibleSongs(): List<Song> {
        val searched = MediaRepository.search(songs, searchQuery)
        return MediaRepository.sortSongs(searched, sortField, sortAscending)
    }

    fun recentSongs(): List<Song> {
        val order = recentIds.withIndex().associate { (i, id) -> id to i }
        return songs.filter { order.containsKey(it.id) }.sortedBy { order[it.id] }
    }

    fun favoriteSongs(): List<Song> {
        val searched = MediaRepository.search(songs.filter { favorites.contains(it.id) }, searchQuery)
        return MediaRepository.sortSongs(searched, sortField, sortAscending)
    }

    fun songById(id: Long): Song? = songs.firstOrNull { it.id == id }

    fun albumById(albumId: Long): Album? = albums.firstOrNull { it.albumId == albumId }

    fun artistByName(name: String): Artist? = artists.firstOrNull { it.name == name }

    fun folderByPath(path: String): MusicFolder? = folders.firstOrNull { it.path == path }

    fun songMap(): Map<Long, Song> = songs.associateBy { it.id }

    // ===== 播放入口 =====

    fun playFrom(list: List<Song>, song: Song) {
        val idx = list.indexOfFirst { it.id == song.id }
        player.play(list, if (idx < 0) 0 else idx)
        showToast("正在播放 · ${song.title}")
    }

    fun playAll(list: List<Song>, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        val ordered = if (shuffled) list.shuffled() else list
        player.play(ordered, 0)
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
}
