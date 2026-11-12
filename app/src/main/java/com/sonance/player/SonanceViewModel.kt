package com.sonance.player

import android.Manifest
import android.app.Application
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.sonance.player.data.Album
import com.sonance.player.data.AppIconSwitcher
import com.sonance.player.data.Artist
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverCache
import com.sonance.player.data.CoverCandidate
import com.sonance.player.data.CoverRequest
import com.sonance.player.data.CoverTagWriter
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.MediaFileDeleter
import com.sonance.player.data.plugin.PluginOnlineSource
import com.sonance.player.data.plugin.PluginRecord
import com.sonance.player.data.plugin.PluginRepository
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.OnlineDownloader
import com.sonance.player.data.NeteaseSource
import com.sonance.player.data.OnlineSource
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.ShuffleOrder
import com.sonance.player.data.SongMetaEditor
import com.sonance.player.data.recognition.AudioFingerprinter
import com.sonance.player.data.recognition.NeteaseRecognizer
import com.sonance.player.data.recognition.PcmExtractor
import com.sonance.player.data.RemotePlaylist
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
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.data.LyricPlaybarStyle
import com.sonance.player.data.PlayButtonColorMode
import com.sonance.player.data.LockScreenLyrics
import com.sonance.player.data.LyricsCache
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.MediaRepository
import com.sonance.player.data.MusicFolder
import com.sonance.player.data.Prefs
import com.sonance.player.data.IndexKey
import com.sonance.player.data.IndexKeys
import com.sonance.player.data.PinyinIndex
import com.sonance.player.data.ScanFilters
import com.sonance.player.data.decodeSongsFromJson
import com.sonance.player.data.findDuplicates
import com.sonance.player.data.encodeSongsToJson
import com.sonance.player.data.ScanOutcome
import com.sonance.player.data.ScanFolderInfo
import com.sonance.player.data.canonicalArtist
import com.sonance.player.data.canonicalTitle
import com.sonance.player.data.DUPLICATE_DURATION_TOLERANCE_MS
import com.sonance.player.data.RestoreEngine
import com.sonance.player.data.RestoreResult
import com.sonance.player.data.netease.NcConvertSummary
import com.sonance.player.data.netease.NeteaseCacheRepository
import com.sonance.player.data.netease.NeteaseScanResult
import com.sonance.player.data.netease.SniffApp
import com.sonance.player.data.Song
import com.sonance.player.data.SongStats
import com.sonance.player.data.SortField
import com.sonance.player.ui.screens.LIST_COVER_PX
import com.sonance.player.ui.screens.PAGE_COUNT
import com.sonance.player.data.UnplayableFileScanner
import com.sonance.player.data.UnplayableScanResult
import com.sonance.player.data.ViewMode
import com.sonance.player.playback.EQ_BAND_COUNT
import com.sonance.player.playback.EQ_CENTER_FREQS
import com.sonance.player.playback.EQ_MAX_DB
import com.sonance.player.playback.EQ_MIN_DB
import com.sonance.player.playback.EqBus
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.SoundFxBus
import com.sonance.player.playback.SoundFxParams
import com.sonance.player.playback.SoundPreset
import com.sonance.player.playback.SoundPresets
import com.sonance.player.playback.KaraokeBus
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.PlayerController
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.icon.IconSetState
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.ListCoverScale
import com.sonance.player.ui.theme.ListRowStyle
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.MiniProgressStyle
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import kotlinx.coroutines.CancellationException
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

    /**
     * 启动时把皮肤色同步给桌面小组件一次。
     *
     * 换肤走 [changeSkin]，但**没换过皮肤**的用户（绝大多数）永远不会走到那里 ——
     * 不同步的话，小组件会一直用 `WidgetState` 里那套默认色，
     * 和 App 里的皮肤对不上。
     *
     * 放在 `skin` 声明之后：Kotlin 按源码顺序初始化属性。
     */
    private fun pushSkinToWidget() {
        val s = skin
        com.sonance.player.widget.SonanceWidgets.onSkinChanged(
            context = getApplication(),
            bg = s.bg.toArgb(),
            text = s.text.toArgb(),
            textDim = s.textDim.toArgb(),
            accent = s.accent.toArgb(),
            onAccent = s.onAccent.toArgb()
        )
    }
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

    // ===== 长按提示（一次性）=====

    /**
     * 行尾去掉 ⋮ 之后，长按是低频操作的唯一入口。
     * 列表顶部那条灰字只出现到用户第一次长按为止，之后不再出现，也不弹 toast。
     */
    var longPressHintShown by mutableStateOf(prefs.longPressHintShown)
        private set

    fun noteLongPressUsed() {
        if (longPressHintShown) return
        longPressHintShown = true
        prefs.longPressHintShown = true
    }

    // ===== 列表多选删除 =====

    /** 是否处于多选态。放 ViewModel 里，保证多个复用 SongList 的页面/页签共享同一份状态 */
    var selectionMode by mutableStateOf(false)
        private set

    /** 多选态下勾选的歌曲 id */
    var selectedSongIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun enterSelection(initialId: Long? = null) {
        selectionMode = true
        selectedSongIds = if (initialId == null) emptySet() else setOf(initialId)
    }

    fun toggleSelection(id: Long) {
        selectedSongIds = if (id in selectedSongIds) selectedSongIds - id else selectedSongIds + id
    }

    fun selectAllSongIds(ids: Collection<Long>) {
        selectedSongIds = ids.toSet()
    }

    fun deselectAllSongIds() {
        selectedSongIds = emptySet()
    }

    fun clearSelection() {
        selectionMode = false
        selectedSongIds = emptySet()
    }

    /** 多选删除：走和单曲「删除文件」同一套删除/授权链路 */
    fun deleteSelectedSongs(allSongs: List<Song>) {
        val targets = allSongs.filter { it.id in selectedSongIds && it.isLocal }
        if (targets.isEmpty()) {
            showToast("没有可删除的本地文件")
            return
        }
        // 先退出多选，结果由删除链路异步提示
        clearSelection()
        deleteSongFiles(targets, detailed = true)
    }

    // ===== 扫描范围（MediaStore 已索引目录多选）=====

    /** 面板里的可选目录（MediaStore 聚合结果 + 已记忆但暂时查不到的目录） */
    var scanFolders by mutableStateOf<List<ScanFolderInfo>>(emptyList())
        private set
    var scanFoldersLoading by mutableStateOf(false)
        private set

    /** 上次勾选的目录；从 Prefs 读，默认空集合 */
    var scanScopeSelection by mutableStateOf(
        prefs.scanScopePaths.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    )
        private set

    var scopedScanRunning by mutableStateOf(false)
        private set
    var scopedScanScanned by mutableIntStateOf(0)
        private set
    var scopedScanResult by mutableStateOf<ScopedScanResult?>(null)
        private set

    private var scopedScanJob: Job? = null

    /** 扫描结果短暂占用顶栏副标题位，几秒后自动退回常规副标题（不新增控件、不弹 toast） */
    var scanHint by mutableStateOf<String?>(null)
        private set
    private var scanHintJob: Job? = null

    private fun flashScanHint(text: String) {
        scanHint = text
        scanHintJob?.cancel()
        scanHintJob = viewModelScope.launch {
            delay(6_000L)
            scanHint = null
        }
    }

    data class ScopedScanResult(val scanned: Int, val added: Int, val removed: Int)

    /** 打开面板时拉取目录列表；已记忆但 MediaStore 暂时查不到的目录保留为 0 首条目 */
    fun loadScanFolders() {
        if (scanFoldersLoading) return
        scanFoldersLoading = true
        viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) { repo.listFolders() }
            val known = listed.mapTo(HashSet()) { it.path }
            val missing = scanScopeSelection.filter { it !in known }.map {
                ScanFolderInfo(
                    path = it,
                    name = it.substringAfterLast('/').ifBlank { "根目录" },
                    songCount = 0
                )
            }
            scanFolders = listed + missing
            scanFoldersLoading = false
        }
    }

    fun toggleScanScope(path: String) {
        val next = if (path in scanScopeSelection) scanScopeSelection - path else scanScopeSelection + path
        updateScanScopeSelection(next)
    }

    fun updateScanScopeSelection(paths: Set<String>) {
        scanScopeSelection = paths
        prefs.scanScopePaths = paths.joinToString("\n")
    }

    fun clearScopedScanResult() {
        scopedScanResult = null
    }

    fun cancelScopedScan() {
        scopedScanJob?.cancel()
        scopedScanJob = null
        scopedScanRunning = false
        showToast("已取消扫描")
    }

    /** 只重扫所选目录，并与现有曲库合并（其他目录保持不动） */
    fun startScopedScan(paths: Set<String>) {
        if (paths.isEmpty()) {
            showToast("请先选择要扫描的文件夹")
            return
        }
        if (!hasAudioPermission()) {
            showToast("没有读取音乐文件的权限，无法扫描")
            return
        }
        scopedScanJob?.cancel()
        updateScanScopeSelection(paths)
        scopedScanScanned = 0
        scopedScanResult = null
        scopedScanRunning = true
        scopedScanJob = viewModelScope.launch {
            try {
                val outcome = repo.scan(
                    filterShortAudio = filterShortAudio,
                    filters = currentScanFilters(),
                    folderPaths = paths,
                    onProgress = { n -> scopedScanScanned = n }
                )
                mergeScopedScan(outcome, paths)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                showToast("扫描失败：${t.message ?: "目录不可读"}")
            } finally {
                scopedScanRunning = false
            }
        }
    }

    private suspend fun mergeScopedScan(outcome: ScanOutcome, paths: Set<String>) {
        val existing = songs
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val kept = existing.filter { it.folderPath !in paths }
        // 原来就在库里的歌（同一 MediaStore id）直接保留，避免重扫把老条目当成"新增"
        val reappearing = outcome.songs.filter { it.id in existingIds }
        val fresh = outcome.songs.filter { it.id !in existingIds }

        val (merged, added) = withContext(Dispatchers.Default) {
            val keys = HashSet<String>()
            (kept + reappearing).forEach { keys += songDedupeKey(it) }
            val unique = ArrayList<Song>(fresh.size)
            for (song in fresh) {
                if (keys.add(songDedupeKey(song))) unique += song
            }
            val all = (kept + reappearing + unique)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            all to unique.size
        }

        val removed = (existing.size - kept.size - reappearing.size).coerceAtLeast(0)
        songs = filterHidden(merged)
        warmUpCovers()
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(merged)
        prefs.lastScanAt = System.currentTimeMillis()
        lastScanOutcome = outcome.copy(songs = merged)
        duplicateReport = null
        scopedScanResult = ScopedScanResult(scanned = scopedScanScanned, added = added, removed = removed)
        flashScanHint(
            if (removed > 0) "已扫描 · 新增 $added 首 · 移除 $removed 首"
            else "已扫描 · 新增 $added 首"
        )
        showToast(
            if (removed > 0) {
                "扫描完成：新增 $added 首，移除 $removed 首"
            } else {
                "扫描完成：新增 $added 首"
            }
        )
    }

    /** 与现有去重规则一致的 key：归一化标题 + 归一化歌手 + 时长桶 */
    private fun songDedupeKey(song: Song): String {
        val bucket = song.durationMs / DUPLICATE_DURATION_TOLERANCE_MS
        return canonicalTitle(song.title) + "\u0000" + canonicalArtist(song.artist) + "\u0000" + bucket
    }

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
        excludeNonSongFolders = scanExcludeNonSongFolders,
        excludedDirs = prefs.excludedDirs
    )
    /**
     * 「左右滑动切换」提示是否已经展示过。
     *
     * 只展示一次：滑动是隐藏功能，第一次不给提示用户永远发现不了；
     * 每次都给又很烦。状态放在内存里即可，落盘只是为了让"已看过"跨启动保留。
     */
    var pagerHintShown by mutableStateOf(prefs.pagerHintShown)
        private set

    fun markPagerHintShown() {
        if (pagerHintShown) return
        pagerHintShown = true
        prefs.pagerHintShown = true
    }

    /* ============================================================
     * 三页列表的滚动位置（**只存数值，不存 LazyListState**）
     * ============================================================ */

    /**
     * 读上一次存下的三页滚动位置：`[第0页(下标, 偏移), 第1页, 第2页]`。
     *
     * ⚠️ 这里刻意**不缓存 `LazyListState` 对象**。
     *
     * 曾经为了"进出设置页也保留位置"，把三页的 `LazyListState` 提升到 ViewModel 里常驻。
     * 结果是偶发的**列表纵向彻底滑不动**（横向翻页、点浮窗都正常）——
     * 一个 `LazyListState` 被反复从组合里摘下来、过一会再挂到新的 LazyColumn 上，
     * 内部 measure / scrollPosition 的那套状态会错乱，滚动就此哑掉。
     *
     * 现在滚动状态留在组合内（`remember`），位置只以"下标 + 像素偏移"的形式存取，
     * 进组合时当初始值用 —— 效果一样，但不会再出现跨生命周期的状态错乱。
     */
    fun readLibraryScroll(): List<Pair<Int, Int>> {
        val saved = prefs.libraryScroll.split('|')
        return List(PAGE_COUNT) { i ->
            saved.getOrNull(i)?.split(':')?.let {
                (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
            } ?: (0 to 0)
        }
    }

    /** 离开曲库页时把三页的位置写进 Prefs（下标 + 偏移，不含任何状态对象） */
    fun saveLibraryScroll(positions: List<Pair<Int, Int>>) {
        prefs.libraryScroll = positions.joinToString("|") { (idx, off) -> "$idx:$off" }
    }

    /**
     * 冷启动封面预热。
     *
     * 进程重启后内存缓存是空的，只靠"滚到哪解到哪"会有那么一两秒满屏占位图 ——
     * 用户看到的就是"重启后封面没了，得播一会才回来"。曲库一就位就把开头
     * 这些歌的封面提前解出来（命中磁盘缓存时几乎不花钱）。
     */
    private var selectedCoverMigrationJob: Job? = null

    /** 旧版手选图先复制到持久目录；旧缓存保留，不因迁移失败丢图。 */
    private suspend fun preserveLegacySelectedCovers(
        app: Application,
        library: List<Song>,
        verifyExisting: Boolean = false
    ): Boolean =
        withContext(Dispatchers.IO) {
            for (song in library) {
                if (!isActive) return@withContext false
                if (song.id <= 0L || prefs.pinnedCoverUrl(song.id).isNullOrBlank()) continue
                val version = ArtworkLoader.selectedGeneration(song.id)
                if (prefs.pinnedCoverUrl(song.id).isNullOrBlank()) continue
                if (CoverCache.hasSelected(app, song.id, song.uri)) {
                    if (verifyExisting) {
                        val bytes = CoverCache.lookupSelected(app, song.id, song.uri) ?: return@withContext false
                        val bitmap = OnlineCover.decode(bytes, 128) ?: return@withContext false
                        bitmap.recycle()
                    }
                    continue
                }
                val key = OnlineCover.keyOf(song.title, song.artist) ?: continue
                val old = CoverCache.lookup(app, key)
                if (old is CoverCache.Lookup.Hit) {
                    val bitmap = OnlineCover.decode(old.bytes, 128) ?: return@withContext false
                    bitmap.recycle()
                    val migrated = ArtworkLoader.ifSelectedGeneration(song.id, version) {
                        CoverCache.migrateSelectedIfAbsent(app, song.id, song.uri, old.bytes)
                    }
                    if (migrated == false) {
                        return@withContext false
                    }
                }
            }
            true
        }

    private fun warmUpCovers() {
        val app = getApplication<Application>()
        if (selectedCoverMigrationJob?.isActive != true) {
            val library = songs
            selectedCoverMigrationJob = viewModelScope.launch {
                preserveLegacySelectedCovers(app, library)
            }
        }
        // 恢复列表位置附近少量预热，避免开头 80 个后台任务占住可见行的解码队列。
        val start = (readLibraryScroll().firstOrNull()?.first ?: 0).coerceIn(0, songs.size)
        val list = songs.drop(start).take(24)
        if (list.isEmpty()) return
        ArtworkLoader.warmUp(
            context = app,
            requests = list.map { CoverRequest(it.albumId, it.uri, OnlineCover.keyOf(it.title, it.artist)) },
            sizePx = LIST_COVER_PX
        )
    }


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

    /**
     * 恢复完成、等播放器报出 mediaId 后要请求一次列表定位。
     *
     * **只有上次没留下滚动位置时才做**：存的那个位置是用户上一次真正翻到的地方，
     * 比"正在播放那首"更贴近他的预期；两边都要的话，存档就白存了。
     */
    private var pendingStartupScroll = false

    private fun armStartupScroll() {
        pendingStartupScroll = prefs.libraryScroll.isBlank()
    }

    init {
        pushSkinToWidget()
        com.sonance.player.widget.WidgetCommandReceiver.onToggleFavorite = { id ->
            toggleFavorite(id)
        }
        com.sonance.player.widget.WidgetCommandReceiver.onCyclePlayMode = {
            cyclePlayMode(quiet = false)
        }
        // 播放模式（随机 / 列表循环 / 单曲循环）持久化恢复。
        // 必须在恢复播放现场之前设好：队列是按上次的模式排的（随机时就是洗牌序），
        // 播放器的 repeatMode 也要跟着摆回去，UI 图标才不会和实际行为对不上。
        applyPersistedPlayMode()
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
                // 在线歌**不进**最近播放（用户要求），但**播放现场存档必须保留**。
                //
                // lastOnlineSongJson / lastPlayedOnline 是「恢复上次播放现场」用的，
                // 和「最近播放」是两回事 —— 删了的话，切几次后台进程被回收后，
                // 正在播的在线歌就找不回来了（那才是真正的"数据丢失"）。
                //Prefs.recentEntries() 那边也会把历史遗留的在线条目过滤掉。
                songById(id)?.toStoredOrNull()?.let { stored ->
                    prefs.lastOnlineSongJson = storedOnlineSongToJson(stored).toString()
                    prefs.lastPlayedOnline = true
                    // 切歌瞬间把进度归零：playPositionMs 是本地/在线**共用**的一个键，
                    // 不归零的话「切歌后还没走到 onStop 就被杀」会把上一首的
                    // 进度摆到这一首上，恢复出来直接跳到歌中间。
                    prefs.playPositionMs = 0L
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
                // 插件源：入队时可能还没取到流地址（点播只预取当前这首），轮到它时补一次
                resolveStreamIfMissing(it)
                // 锁屏歌词：ColorOS / OPlus 的锁屏歌词页读的是 mediaMetadata["lyricInfo"]
                publishLockScreenLyrics(it)
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
                    rebuildPinyinIndex(visible)
                    albums = MediaRepository.groupAlbums(visible)
                    artists = MediaRepository.groupArtists(visible)
                    folders = MediaRepository.groupFolders(visible)
                    scanState = ScanState.READY
                    // 曲库就位 → 提前把开头这些歌的封面解出来，
                    // 免得重启后第一屏全是占位图（见 warmUpCovers 的说明）
                    warmUpCovers()
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
        armStartupScroll()
        // autoplay=false：冷启动就出声很吓人，摆成"暂停在这首歌"等用户按播放。
        // notifyStart=false：这只是把上次的歌摆回来，不该给播放次数 +1。
        player.play(
            songs = list,
            index = prefs.onlineQueueIndex.coerceIn(0, list.lastIndex),
            autoplay = false,
            // 上次停在哪就摆回哪（配合 persistPlayback 对在线歌存进度）。
            // 地址是当场重新取的（带签名会过期）；流不支持 Range 时最坏退化为从头播。
            startPositionMs = prefs.playPositionMs.coerceAtLeast(0L),
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
        armStartupScroll()
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
            // 进度也存下来。以前这里抹成 0，进程被杀后恢复的歌会**从头开始** ——
            // 听到一半切几次后台回来就回到开头，用户感知就是「播放在中断」。
            // 网易云的取流地址支持 Range 请求，恢复时可以直接摆回这个位置。
            prefs.playPositionMs = st.positionMs.coerceAtLeast(0L)
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

    /**
     * 「私有格式还原」区块是否可见。
     *
     * 默认隐藏：在设置页「关于」里连点署名 [AUTHOR_TAP_TARGET] 次才出现，
     * 出现后只保持 [CONVERSION_UNLOCK_MS]，到点自动收起，要再点一次才会回来。
     * 截止时间落盘（[Prefs.conversionUnlockUntil]），所以重启 App 不会把它变成永久解锁。
     */
    var conversionUnlocked by mutableStateOf(false)
        private set

    /** 连续点击署名的计数；解锁一次后归零，避免第二次点一下就又触发 */
    private var authorTaps = 0

    init {
        refreshConversionUnlock()
    }

    /** 按落盘的截止时间重新判定可见性；界面每隔几秒调一次，到点就自动隐藏 */
    fun refreshConversionUnlock() {
        val unlocked = prefs.conversionUnlockUntil > System.currentTimeMillis()
        if (unlocked != conversionUnlocked) conversionUnlocked = unlocked
    }

    /**
     * 点一下「陈陆」。
     *
     * 前 9 次完全静默 —— 这是彩蛋，给提示就等于把彩蛋提前剧透了。
     * 第 10 次解锁并弹一条 toast，告诉用户东西出来了、以及它一小时后会消失。
     */
    fun tapAuthor() {
        refreshConversionUnlock()
        if (conversionUnlocked) return // 已经解锁，重复点击不再延长
        authorTaps++
        if (authorTaps < AUTHOR_TAP_TARGET) return
        authorTaps = 0
        prefs.conversionUnlockUntil = System.currentTimeMillis() + CONVERSION_UNLOCK_MS
        conversionUnlocked = true
        showToast("已解锁私有格式还原，1 小时后自动隐藏")
    }

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

    // ===== 网易云缓存嗅探与转换 =====

    // 与「私有格式还原」共用 conversionUnlocked 彩蛋开关与输出文件夹，
    // 不再单独做一套授权 / 目录选择
    private val neteaseRepo by lazy { NeteaseCacheRepository(getApplication()) }

    var neteaseScanState by mutableStateOf<NeteaseScanResult?>(null)
        private set
    var neteaseScanning by mutableStateOf(false)
        private set
    var neteaseConverting by mutableStateOf(false)
        private set
    var neteaseDone by mutableStateOf(0)
        private set
    var neteaseTotal by mutableStateOf(0)
        private set
    var neteaseCurrent by mutableStateOf("")
        private set
    var neteaseResult by mutableStateOf<NcConvertSummary?>(null)
        private set

    /** 已转换成功的条目 key（持久化在仓库的记录文件里），列表用来打「已转换」标记 */
    var neteaseConvertedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 嗅探目标 App（网易云 / 酷狗 / QQ 音乐 / 汽水），持久化在 Prefs 里 */
    var sniffTargets by mutableStateOf(prefs.sniffTargets)
        private set

    fun toggleSniffTarget(name: String) {
        val next = if (name in sniffTargets) sniffTargets - name else sniffTargets + name
        sniffTargets = next
        prefs.sniffTargets = next
    }

    private var neteaseJob: Job? = null

    /** 首次进区块时用上次扫描结果铺列表（可能为 null，表示还没扫过） */
    fun neteaseLoadCached() {
        neteaseConvertedKeys = neteaseRepo.loadDoneMap().keys
        if (neteaseScanState != null) return
        val cached = neteaseRepo.loadCached() ?: return
        neteaseScanState = NeteaseScanResult.Ok(cached)
    }

    fun neteaseScan() {
        val apps = sniffTargets.mapNotNull { SniffApp.safeOf(it) }.toSet()
        if (apps.isEmpty()) {
            showToast("请先勾选至少一个嗅探目标")
            return
        }
        neteaseJob?.cancel()
        neteaseJob = viewModelScope.launch {
            neteaseScanning = true
            val result = neteaseRepo.scan(apps)
            neteaseScanState = result
            neteaseScanning = false
            when (result) {
                is NeteaseScanResult.Ok ->
                    showToast(if (result.entries.isEmpty()) "没有发现可识别的音乐缓存" else "发现 ${result.entries.size} 个缓存音频")
                is NeteaseScanResult.Failed -> showToast(result.message)
                else -> {}
            }
        }
    }

    /**
     * 转换指定条目（全部或单条，由调用方传 key 列表决定）。
     * 成品写进「私有格式还原」的输出文件夹，没有选过就直接拦下。
     */
    fun neteaseConvert(keys: List<String>) {
        if (keys.isEmpty()) return
        val entries = (neteaseScanState as? NeteaseScanResult.Ok)
            ?.entries
            ?.filter { it.key in keys }
            .orEmpty()
        if (entries.isEmpty()) return
        val outputStr = restoreOutputUri
        if (outputStr == null) {
            showToast("请先选择输出文件夹")
            return
        }
        neteaseJob?.cancel()
        neteaseJob = viewModelScope.launch {
            neteaseConverting = true
            neteaseDone = 0
            neteaseTotal = entries.size
            neteaseCurrent = ""
            try {
                val summary = neteaseRepo.convertAll(
                    entries,
                    android.net.Uri.parse(outputStr)
                ) { done, total, current ->
                    neteaseDone = done
                    neteaseTotal = total
                    neteaseCurrent = current
                }
                neteaseResult = summary
                neteaseConvertedKeys = neteaseConvertedKeys + summary.doneKeys
                showToast(
                    when {
                        summary.converted > 0 -> "转换完成：成功 ${summary.converted} 个，跳过 ${summary.skipped} 个，失败 ${summary.failed} 个"
                        summary.failed > 0 -> "转换失败：${summary.failed} 个文件处理出错"
                        else -> "没有可转换的内容"
                    }
                )
                if (summary.converted > 0) scan()
            } finally {
                neteaseConverting = false
            }
        }
    }

    fun cancelNetease() {
        neteaseJob?.cancel()
        neteaseJob = null
        neteaseConverting = false
        showToast("已取消转换")
    }

    fun clearNeteaseResult() {
        neteaseResult = null
    }

    /** 清掉嗅探列表：扫描结果与本地缓存副本一并丢弃（不动已转换记录与输出成品） */
    fun neteaseClearList() {
        neteaseJob?.cancel()
        neteaseScanState = null
        neteaseResult = null
        neteaseRepo.clearCached()
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
        if (clearArtwork) ArtworkLoader.clear(getApplication())
        val outcome = repo.scan(filterShortAudio, currentScanFilters())
        applyScanOutcome(outcome)
        // 把结果落到 Prefs，下次冷启动直接用、不再扫 MediaStore
        prefs.songsCacheJson = encodeSongsToJson(outcome.songs)
        prefs.lastScanAt = System.currentTimeMillis()
        return outcome
    }

    private fun applyScanOutcome(outcome: ScanOutcome) {
        songs = filterHidden(outcome.songs)
        rebuildPinyinIndex(songs)
        // 扫描会清掉封面缓存，这里重新预热一遍（见 warmUpCovers 的说明）
        warmUpCovers()
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        lastScanOutcome = outcome
    }

    /** 曲库里被用户"清理"掉（只从列表隐藏）或所在文件夹被排除的曲目 */
    private fun filterHidden(all: List<Song>): List<Song> {
        val hidden = prefs.hiddenIdSet()
        val dirsEmpty = prefs.excludedDirs.isEmpty()
        if (hidden.isEmpty() && dirsEmpty) return all
        return all.filter { it.id !in hidden && !prefs.isFolderExcluded(it.folderPath) }
    }

    // ===== 清理重复 / 同名歌曲（只隐藏，不动文件）=====

    var duplicateReport by mutableStateOf<DuplicateReport<Song>?>(null)
        private set

    var hiddenCount by mutableIntStateOf(prefs.hiddenIdSet().size)
        private set

    fun scanDuplicates() {
        viewModelScope.launch {
            val all = lastScanOutcome?.songs ?: songs
            duplicateReport = withContext(Dispatchers.Default) { findDuplicates(all, getApplication()) }
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
     * 清理重复曲目。
     * @param includeSameName 是否连"同名但时长不同"的一起处理
     * @param deleteFiles **true 时把对应的音频文件从设备上删掉**（走 [deleteSongFiles]，
     *   系统还会再要一次确认）；false 保持原行为——只从列表隐藏，文件不动。
     */
    fun cleanDuplicates(includeSameName: Boolean, deleteFiles: Boolean = false) {
        val report = duplicateReport ?: return
        val victims = LinkedHashSet<Song>()
        report.exactGroups.forEach { g -> g.duplicates.forEach { victims.add(it) } }
        if (includeSameName) {
            report.sameNameGroups.forEach { g -> g.duplicates.forEach { victims.add(it) } }
        }
        if (victims.isEmpty()) {
            showToast("没有需要清理的曲目")
            return
        }

        if (deleteFiles) {
            // 文件删除是另一条链路（含系统授权确认），落地时自己会清 duplicateReport
            deleteSongFiles(victims.toList())
            return
        }

        val ids = victims.mapTo(HashSet()) { it.id }
        val merged = prefs.hiddenIdSet() + ids
        prefs.hiddenSongIds = merged.joinToString(",")
        hiddenCount = merged.size

        // 只改内存里的曲库列表，文件一个都没动
        songs = songs.filter { it.id !in ids }
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(songs)

        duplicateReport = null
        showToast("已从列表隐藏 ${victims.size} 首（文件未删除）")
    }

    /**
     * 把某一首歌从列表移除（**只隐藏，不动文件**）。
     *
     * 和 [cleanDuplicates] 走同一套机制（[Prefs.hiddenIdSet]），所以设置页那个
     * 「恢复隐藏曲目」的入口能一并把它找回来 —— 误删不用重装 App。
     *
     * 只对本地歌有意义：在线歌的 id 是哈希出来的负数，而且它们本来就不在
     * 曲库列表里（搜索结果列表每次都是重新拉的）。
     */
    fun hideSong(song: Song) {
        if (!song.isLocal) return
        if (song.id in prefs.hiddenIdSet()) return
        val merged = prefs.hiddenIdSet() + song.id
        prefs.hiddenSongIds = merged.joinToString(",")
        hiddenCount = merged.size

        // 只改内存里的曲库列表，文件一个都没动
        songs = songs.filter { it.id != song.id }
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(songs)
        showToast("已从列表移除「${song.title}」（文件未删除）")
    }

    // ===== 删除音频文件（列表菜单 / 去重清理共用）=====

    /**
     * 一次「等用户点头」的文件删除。
     */
    class PendingSongDelete(
        /** 计划删除的歌（都已过滤成本地歌） */
        val songs: List<Song>,
        /**
         * 非空 → API 29+ 的系统确认框；**用户同意后文件已被系统删掉**，直接落地即可。
         * 为空 → API 28 及以下，先授予 `WRITE_EXTERNAL_STORAGE`，同意后要**重新执行删除**。
         */
        val intentSender: IntentSender?,
        /** 多选删除：结果提示用「成功 N 首 / 失败 M 首」 */
        val detailed: Boolean = false
    ) {
        val songIds: Set<Long> = songs.mapTo(HashSet()) { it.id }
    }

    /** 待用户确认的删除请求；null 表示没有。界面据此拉起系统弹窗 */
    var pendingSongDelete by mutableStateOf<PendingSongDelete?>(null)
        private set

    /**
     * 把一批歌曲的**音频文件**从设备上删掉。
     *
     * 应用内二次确认由调用方的对话框负责；这里负责真正的删除动作，
     * 以及系统（MediaStore）自己的那一次确认：被拦下时把请求挂进
     * [pendingSongDelete]，由界面拉起系统弹窗，结果回到 [onSongDeletePermissionResult]。
     */
    fun deleteSongFiles(songsToDelete: List<Song>, detailed: Boolean = false) {
        val targets = songsToDelete.filter { it.isLocal }
        if (targets.isEmpty()) {
            showToast("没有可删除的本地文件")
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            when (val r = withContext(Dispatchers.IO) { MediaFileDeleter.request(app, targets) }) {
                is MediaFileDeleter.Result.Deleted -> {
                    val deleted = targets.filter { it.uri in r.deletedUris }
                    finalizeSongFileDeletion(
                        deleted.mapTo(HashSet()) { it.id },
                        deleted.size,
                        r.failed,
                        detailed
                    )
                }
                is MediaFileDeleter.Result.NeedsPermission ->
                    pendingSongDelete = PendingSongDelete(targets, r.intentSender, detailed)
                MediaFileDeleter.Result.Failed -> {
                    clearSelection()
                    showToast("删除失败：文件可能正被其它应用占用")
                }
            }
        }
    }

    /** 系统弹窗 / 权限申请的结果回到这里 */
    fun onSongDeletePermissionResult(approved: Boolean) {
        val pending = pendingSongDelete ?: return
        pendingSongDelete = null
        if (!approved) {
            showToast("已取消删除")
            return
        }
        if (pending.intentSender == null) {
            // 老权限模型：刚拿到 WRITE 权限，现在才真正执行删除
            deleteSongFiles(pending.songs, pending.detailed)
            return
        }
        // createDeleteRequest 路径：用户同意时系统已经把文件删掉了。
        // 系统只回「同意/取消」，没有逐个结果，所以再查一遍哪些还在，给用户准确的成败数。
        viewModelScope.launch {
            val app = getApplication<Application>()
            val remaining = withContext(Dispatchers.IO) { MediaFileDeleter.remaining(app, pending.songs) }
            val deleted = pending.songs.filterNot { it in remaining }
            finalizeSongFileDeletion(
                deleted.mapTo(HashSet()) { it.id },
                deleted.size,
                remaining.size,
                pending.detailed
            )
        }
    }

    /**
     * 文件删掉之后把曲库收干净。**不走 hiddenSongIds** —— 文件已经不存在，
     * 把 id 塞进隐藏表只会让「恢复隐藏曲目」的计数说谎。
     */
    private fun finalizeSongFileDeletion(
        ids: Set<Long>,
        success: Int,
        failed: Int = 0,
        detailed: Boolean = false
    ) {
        songs = songs.filter { it.id !in ids }
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(songs)
        duplicateReport = null
        clearSelection()
        showToast(
            when {
                detailed -> "成功 $success 首 / 失败 $failed 首"
                failed > 0 -> "删除完成：成功 $success 个 / 失败 $failed 个"
                else -> "已删除 $success 个文件"
            }
        )
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

    // ===== 排除文件夹（歌曲不再出现在列表中，文件不动）=====

    /** 被排除的文件夹路径集合（与 [Prefs.isFolderExcluded] 同一条规则） */
    var excludedDirs by mutableStateOf(prefs.excludedDirs)
        private set

    /**
     * 排除一个文件夹：立即从内存曲库剔除并重新分组，文件不动。
     * 之后每次扫描都会按同一规则把它挡在外面（[currentScanFilters]）。
     */
    fun excludeFolder(path: String) = excludeFolders(listOf(path))

    /** 批量排除文件夹（文件夹页多选）。规则与单删一致，一次重建列表、只弹一次提示。 */
    fun excludeFolders(paths: Collection<String>) {
        val fresh = paths.filter { it.isNotBlank() && it !in prefs.excludedDirs }.distinct()
        if (fresh.isEmpty()) return
        prefs.excludedDirs = prefs.excludedDirs + fresh
        excludedDirs = prefs.excludedDirs

        val before = songs.size
        val visible = songs.filter { !prefs.isFolderExcluded(it.folderPath) }
        val removed = before - visible.size
        songs = visible
        albums = MediaRepository.groupAlbums(visible)
        artists = MediaRepository.groupArtists(visible)
        folders = MediaRepository.groupFolders(visible)
        prefs.songsCacheJson = encodeSongsToJson(visible)
        showToast(
            when {
                fresh.size == 1 && removed > 0 -> "已排除该文件夹（$removed 首）· 文件未删除"
                fresh.size == 1 -> "已排除该文件夹 · 文件未删除"
                removed > 0 -> "已排除 ${fresh.size} 个文件夹（$removed 首）· 文件未删除"
                else -> "已排除 ${fresh.size} 个文件夹 · 文件未删除"
            }
        )
    }

    /** 恢复某个被排除的文件夹：移出黑名单后重扫入库 */
    fun restoreExcludedFolder(path: String) {
        prefs.excludedDirs = prefs.excludedDirs - path
        excludedDirs = prefs.excludedDirs
        showToast("已恢复，正在重新扫描")
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            val beforeIds = songs.mapTo(HashSet()) { it.id }
            scanState = ScanState.SCANNING
            runScan()
            scanState = ScanState.READY
            // 没有歌曲缓存时（首次启动）init 里那次恢复会跳过，扫完再试一次
            restorePlaybackState()
            val added = songs.count { it.id !in beforeIds }
            flashScanHint(if (added > 0) "已扫描 · 新增 $added 首" else "已扫描 · 暂无新增")
        }
    }

    /** 冷启动的自动刷新只做一次（PermissionGate 每次重组都可能再叫它） */
    private var startupRefreshStarted = false

    /**
     * 冷启动自动刷新曲库 —— 取代原来的下拉刷新。
     *
     * 几个刻意的取舍：
     * - **不把 scanState 置成 SCANNING**（已经有缓存时）：那样列表会闪一下加载态；
     * - **不清封面缓存**（`clearArtwork = false`）：清了每次启动封面都要重新解一遍，
     *   正是之前"重启后封面要等一会才出来"的老毛病；
     * - **没权限就不扫**：拿不到 READ_MEDIA_AUDIO 时 scan 会返回空结果，
     *   把空结果写进缓存等于把用户的曲库抹了。
     */
    fun refreshOnStart() {
        if (startupRefreshStarted) return
        startupRefreshStarted = true
        if (!hasAudioPermission()) return
        viewModelScope.launch {
            val hadSongs = songs.isNotEmpty()
            if (!hadSongs) scanState = ScanState.SCANNING
            runCatching { runScan(clearArtwork = false) }
                .onFailure { android.util.Log.w("SonanceScan", "启动刷新失败: ${it.message}") }
            scanState = ScanState.READY
            if (!hadSongs) restorePlaybackState()
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(
            getApplication(), permission
        ) == PackageManager.PERMISSION_GRANTED
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

    /**
     * 切换标签页。
     *
     * @param scrollToCurrent 切回「全部歌曲」时是否把正在播放那首滚进可视区。
     *   左右滑动切页时必须传 false —— 滑动只是"看看另一页"，不该把用户在
     *   全部歌曲里的滚动位置重置到正在播放处。
     */
    /**
     * 切换主标签页。
     *
     * ⚠️ 这里**不再**自动"滚到正在播放的歌"。
     *
     * 原来只要切回「全部歌曲」就会滚一次，于是从设置页返回时，
     * 用户翻到一半的列表被硬拽到正在播放那首 —— 他自己的位置就这么丢了。
     * 定位改成**只有显式操作才做**（顶栏/迷你条上的"回到正在播放"），
     * 见 [requestScrollToCurrent]。
     */
    fun selectTab(value: LibraryTab) {
        val changed = tab != value
        tab = value
        // 不把「皮肤与设置」记成上次的标签页：它在导航结构里是附属页（齿轮进入、
        // 返回回到主页面），记住了会导致下次冷启动直接停在设置页。
        if (value != LibraryTab.SETTINGS) prefs.lastTab = value.id
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

    /**
     * 歌词要不要跟着背景自动调色（见 [LyricAdaptMode]）。
     *
     * 这个值**只影响展示**：色带是每次进歌词页现算的，改完不用清缓存、不用重新解析歌词。
     */
    var lyricsAdaptMode by mutableStateOf(prefs.lyricsAdaptMode)
        private set

    fun changeLyricsAdaptMode(value: LyricAdaptMode) {
        lyricsAdaptMode = value
        prefs.lyricsAdaptMode = value
        showToast(
            when (value) {
                LyricAdaptMode.OFF -> "歌词将保持皮肤原本的颜色"
                LyricAdaptMode.CURRENT_ONLY -> "只有当前句会跟着背景调色"
                LyricAdaptMode.ALL -> "歌词会按所在区域自动调色"
            }
        )
    }

    /** 歌词页底部播放控制显不显示，四种全屏模式共用 */
    var lyricsPlaybarEnabled by mutableStateOf(prefs.lyricsPlaybar)
        private set

    fun changeLyricsPlaybar(value: Boolean) {
        lyricsPlaybarEnabled = value
        prefs.lyricsPlaybar = value
    }

    var lyricsPlaybarStyle by mutableStateOf(prefs.lyricsPlaybarStyle)
        private set

    fun changeLyricsPlaybarStyle(value: LyricPlaybarStyle) {
        lyricsPlaybarStyle = value
        prefs.lyricsPlaybarStyle = value
    }

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

    // ===== 沉浸皮肤主界面背景（与歌词页 / 律动柱 / 迷你条的玻璃设置相互独立）=====

    var shellGlassStyle by mutableStateOf(GlassStyle.of(prefs.shellGlassStyle))
        private set
    var shellGlassBlur by mutableIntStateOf(prefs.shellGlassBlurDp)
        private set
    var shellGlassVisibility by mutableIntStateOf(prefs.shellGlassVisibility)
        private set

    /** 换样式时把模糊 / 可见度一起带到该样式的预设值（与歌词页同款手感） */
    fun changeShellGlassStyle(style: GlassStyle) {
        shellGlassStyle = style
        shellGlassBlur = style.defaultBlurDp
        shellGlassVisibility = style.defaultVisibility
        prefs.shellGlassStyle = style.ordinal
        prefs.shellGlassBlurDp = style.defaultBlurDp
        prefs.shellGlassVisibility = style.defaultVisibility
    }

    fun changeShellGlassBlur(value: Int) {
        shellGlassBlur = value.coerceIn(0, 80)
        prefs.shellGlassBlurDp = shellGlassBlur
    }

    fun changeShellGlassVisibility(value: Int) {
        shellGlassVisibility = value.coerceIn(0, 100)
        prefs.shellGlassVisibility = shellGlassVisibility
    }

    // ===== 迷你播放条（列表页底部那个「正在播放」）=====

    var miniPlayerStyle by mutableStateOf(MiniPlayerStyle.of(prefs.miniPlayerStyle))
        private set
    var miniGlassStyle by mutableStateOf(GlassStyle.of(prefs.miniGlassStyle))
        private set
    var miniGlassBlur by mutableIntStateOf(prefs.miniGlassBlurDp)
        private set
    var miniGlassVisibility by mutableIntStateOf(prefs.miniGlassVisibility)
        private set
    var miniCardOpacity by mutableIntStateOf(prefs.miniCardOpacity)
        private set

    fun changeMiniPlayerStyle(value: MiniPlayerStyle) {
        miniPlayerStyle = value
        prefs.miniPlayerStyle = value.ordinal
    }

    /** 换样式时把模糊 / 可见度一起带到该样式的预设值（和歌词页一个路子） */
    fun applyMiniGlassStyle(style: GlassStyle) {
        miniGlassStyle = style
        miniGlassBlur = style.defaultBlurDp
        miniGlassVisibility = style.defaultVisibility
        prefs.miniGlassStyle = style.ordinal
        prefs.miniGlassBlurDp = style.defaultBlurDp
        prefs.miniGlassVisibility = style.defaultVisibility
    }

    fun changeMiniGlassBlur(value: Int) {
        miniGlassBlur = value.coerceIn(0, 80)
        prefs.miniGlassBlurDp = miniGlassBlur
    }

    fun changeMiniGlassVisibility(value: Int) {
        miniGlassVisibility = value.coerceIn(0, 100)
        prefs.miniGlassVisibility = miniGlassVisibility
    }

    fun changeMiniCardOpacity(value: Int) {
        miniCardOpacity = value.coerceIn(0, 100)
        prefs.miniCardOpacity = miniCardOpacity
    }

    // ===== 播放/暂停按钮：触感与外观（设置页「播放控制」区） =====

    var playBtnHaptics by mutableStateOf(prefs.playBtnHaptics)
        private set

    fun changePlayBtnHaptics(value: Boolean) {
        playBtnHaptics = value
        prefs.playBtnHaptics = value
    }

    var playBtnGlow by mutableStateOf(prefs.playBtnGlow)
        private set

    fun changePlayBtnGlow(value: Boolean) {
        playBtnGlow = value
        prefs.playBtnGlow = value
    }

    var playBtnColorMode by mutableStateOf(prefs.playBtnColorMode)
        private set

    fun changePlayBtnColorMode(value: PlayButtonColorMode) {
        playBtnColorMode = value
        prefs.playBtnColorMode = value
    }

    /** 图标样式：0=经典 1=液态 2=极简（与 Prefs.playBtnIconStyle 对应） */
    var playBtnIconStyle by mutableIntStateOf(prefs.playBtnIconStyle)
        private set

    /** 全局图标套系：0=原型原版 1=极简线条 2=实心圆润（Prefs.iconSet + IconSetState 双写） */
    var iconSet by mutableIntStateOf(prefs.iconSet)
        private set

    fun changeIconSet(value: Int) {
        iconSet = value.coerceIn(0, 2)
        prefs.iconSet = iconSet
        IconSetState.set(iconSet)
    }

    fun changePlayBtnIconStyle(value: Int) {
        playBtnIconStyle = value.coerceIn(0, 2)
        prefs.playBtnIconStyle = playBtnIconStyle
    }

    /** 图标大小档（百分比 60~170） */
    var playBtnIconScale by mutableIntStateOf(prefs.playBtnIconScale)
        private set

    fun changePlayBtnIconScale(value: Int) {
        playBtnIconScale = value.coerceIn(60, 170)
        prefs.playBtnIconScale = playBtnIconScale
    }

    /** 悬浮卡片上进度用环还是用线（两者都开就重复，所以做成三选一） */
    var miniProgressStyle by mutableStateOf(MiniProgressStyle.of(prefs.miniProgressStyle))
        private set

    fun changeMiniProgressStyle(value: MiniProgressStyle) {
        miniProgressStyle = value
        prefs.miniProgressStyle = value.ordinal
    }

    /** 左滑（下一首）/ 右滑（上一首）的触发阈值，百分比卡宽 */
    var miniSwipeLeftPct by mutableIntStateOf(prefs.miniSwipeLeftPct)
        private set
    var miniSwipeRightPct by mutableIntStateOf(prefs.miniSwipeRightPct)
        private set

    fun changeMiniSwipeLeftPct(value: Int) {
        miniSwipeLeftPct = value.coerceIn(5, 60)
        prefs.miniSwipeLeftPct = miniSwipeLeftPct
    }

    fun changeMiniSwipeRightPct(value: Int) {
        miniSwipeRightPct = value.coerceIn(5, 60)
        prefs.miniSwipeRightPct = miniSwipeRightPct
    }

    /** 悬浮条高度 / 封面大小，百分比（100 = 现在的样子） */
    var miniHeightScale by mutableIntStateOf(prefs.miniHeightScale)
        private set
    var miniCoverScale by mutableIntStateOf(prefs.miniCoverScale)
        private set

    fun changeMiniHeightScale(value: Int) {
        miniHeightScale = value.coerceIn(100, 200)
        prefs.miniHeightScale = miniHeightScale
    }

    fun changeMiniCoverScale(value: Int) {
        miniCoverScale = value.coerceIn(100, 200)
        prefs.miniCoverScale = miniCoverScale
    }

    /**
     * 锁屏歌词（ColorOS / OPlus 原生锁屏歌词页）。
     *
     * 系统读的是我们 MediaSession 上 `MediaMetadata["lyricInfo"]` 里的时间轴，
     * 所以只要在换歌时把它写进去就行。**只为它做本地读取**（内嵌标签 / 本地缓存），
     * 不联网 —— 锁屏歌词是"顺手显示"，不该因为它在后台发请求。
     */
    var lockScreenLyrics by mutableStateOf(prefs.lockScreenLyrics)
        private set

    /** 换歌代次：迟到的旧歌词结果靠它丢弃 */
    private var lockLyricsGeneration = 0

    fun changeLockScreenLyrics(value: Boolean) {
        lockScreenLyrics = value
        prefs.lockScreenLyrics = value
        if (!value) {
            player.publishLyricsInfo(null)
        } else {
            val id = player.state.value.mediaId
            songById(id)?.let { publishLockScreenLyrics(it) }
        }
    }

    /**
     * 给当前这首发布 / 清除锁屏歌词。
     *
     * 三种情况都要处理：
     * - 在线歌：没有本地时间轴 → **清除**，免得锁屏挂着上一首的句子；
     * - 本地歌有歌词 → 写 `lyricInfo`；
     * - 本地歌没歌词 → 写一份 `noLyric = true` 的 payload（不带 `lyric` 字段），
     *   让系统知道"这首确实没有"，而不是继续显示上一首。
     */
    private fun publishLockScreenLyrics(song: Song) {
        if (!prefs.lockScreenLyrics) return
        if (isRemoteSongId(song.id)) {
            lockLyricsGeneration++
            player.publishLyricsInfo(null)
            return
        }
        val gen = ++lockLyricsGeneration
        viewModelScope.launch {
            val result = runCatching {
                LyricsRepository.load(
                    context = getApplication(),
                    songId = song.id,
                    uri = song.uri,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    // 锁屏歌词**不联网**：只用内嵌标签和本地缓存
                    onlineEnabled = false,
                    convertToSimplified = false,
                    durationMs = song.durationMs,
                    neteaseEnabled = false
                )
            }.onFailure {
                android.util.Log.w("SonanceLyrics", "读取锁屏歌词失败: ${it.message}")
            }.getOrNull()
            // 回来时可能已经换歌了：代次或当前歌对不上就丢弃
            if (gen != lockLyricsGeneration) return@launch
            if (player.state.value.mediaId != song.id) return@launch
            player.publishLyricsInfo(LockScreenLyrics.buildJson(song, result?.lyrics, gen))
        }
    }

    // ===== 列表外观 =====

    var listRowStyle by mutableStateOf(ListRowStyle.of(prefs.listRowStyle))
        private set

    fun changeListRowStyle(value: ListRowStyle) {
        listRowStyle = value
        prefs.listRowStyle = value.ordinal
    }

    /** 列表封面倍数（1 / 1.2 / 1.5 / 2 倍）。只放大封面，文字布局不变 */
    var listCoverScale by mutableStateOf(ListCoverScale.of(prefs.listCoverScale))
        private set

    fun changeListCoverScale(value: ListCoverScale) {
        listCoverScale = value
        prefs.listCoverScale = value.ordinal
    }

    // ===== 顶栏品牌 =====

    /**
     * 顶栏标题左边那个小方块的图案（`AppIcons` 的 id，空串 = 内置 Logo 矢量图）。
     *
     * 和 [appIconId] 分开：那个换桌面启动器图标，这个只换应用内的顶栏 logo。
     */
    /** 拖动快速滚动条时的震动反馈开关 */
    var scrollHaptic by mutableStateOf(prefs.scrollHapticEnabled)
        private set

    fun changeScrollHaptic(enabled: Boolean) {
        if (scrollHaptic == enabled) return
        scrollHaptic = enabled
        prefs.scrollHapticEnabled = enabled
    }

    /** 「全部歌曲」右侧轨道：true = A–Z 字母索引，false = 原来的比例滑块 */
    var rightRailLetters by mutableStateOf(prefs.rightRailLetters)
        private set

    fun changeRightRailLetters(letters: Boolean) {
        if (rightRailLetters == letters) return
        rightRailLetters = letters
        prefs.rightRailLetters = letters
    }

    var topBarLogoId by mutableStateOf(prefs.topBarLogoId)
        private set

    fun changeTopBarLogo(id: String) {
        if (id == topBarLogoId) return
        topBarLogoId = id
        prefs.topBarLogoId = id
        showToast("已更换顶栏图标")
    }

    /**
     * 把一套玻璃样式**同时**铺到歌词页 / 律动柱页 / 悬浮迷你播放条。
     *
     * 玻璃主题（[Skin.glass] 非 null）选中时走这里：主题是一整套观感，
     * 只换颜色令牌而让三处玻璃保持原样，看上去就是"换了个配色，别的没变"。
     *
     * 律动柱页没有对应的 VM 状态（它在页面里直接读 prefs），这里只写盘。
     */
    private fun applyGlassEverywhere(style: GlassStyle) {
        changeGlassStyle(style)
        prefs.vizGlassStyle = style.ordinal
        prefs.vizGlassBlurDp = style.defaultBlurDp
        prefs.vizGlassVisibility = style.defaultVisibility
        applyMiniGlassStyle(style)
    }

    // ===== 在线封面 =====

    var onlineCoverEnabled by mutableStateOf(prefs.onlineCoverEnabled)
        private set

    /** 本次会话已尝试补过的 songId，避免反复请求 */
    private val coverTried = HashSet<Long>()

    /** 本次会话已尝试「把下载到的封面写进文件」的 songId，避免反复改文件 */
    private val coverEmbedTried = HashSet<Long>()

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

    /**
     * 一次「等用户点头」的封面写回。
     *
     * 之所以要把封面字节一起存下来：系统的授权框弹出来之后，用户可能过一会儿才点，
     * 而那时选封面对话框已经关了 —— 没有别的地方还留着这张图，重试时就拿不到了。
     */
    class PendingCoverWrite(
        val songId: Long,
        val cover: ByteArray,
        /**
         * 非空 → 用 `StartIntentSenderForResult` 拉起「允许修改此文件？」（API 29+）；
         * 为空 → API 28 及以下，得先授予 `WRITE_EXTERNAL_STORAGE`
         */
        val intentSender: IntentSender?
    )

    /** 待用户授权的写回请求；null 表示没有。界面据此拉起系统弹窗 */
    var pendingCoverWrite by mutableStateOf<PendingCoverWrite?>(null)
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

    // ===== 主界面背景：封面切换的过渡方式（设置页「背景切换」）=====

    /** "slide" = 左右滑动（播放页轮播同款），"fade" = 直接渐变过去 */
    var artTransitionMode by mutableStateOf(prefs.artTransitionMode)
        private set

    /** 「渐变」模式的过渡时长（毫秒） */
    var artFadeMs by mutableIntStateOf(prefs.artFadeMs)
        private set

    fun changeArtTransitionMode(v: String) {
        artTransitionMode = v
        prefs.artTransitionMode = v
    }

    fun changeArtFadeMs(v: Int) {
        artFadeMs = v
        prefs.artFadeMs = v
    }

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

    /** 播放页封面是否用「黑碟播放器」样式（黑胶圆盘 + 播放时缓慢旋转） */
    var vinylMode by mutableStateOf(prefs.vinylMode)
        private set

    fun changeVinylMode(enabled: Boolean) {
        vinylMode = enabled
        prefs.vinylMode = enabled
    }

    /** 黑胶页机械音效开关（页面内小喇叭按钮与设置页读写同一份 Prefs） */
    var vinylSfxEnabled by mutableStateOf(prefs.vinylSfxEnabled)
        private set

    /** 黑胶页机械音效音量 0~1 */
    var vinylSfxVolume by mutableStateOf(prefs.vinylSfxVolume)
        private set

    fun changeVinylSfxEnabled(enabled: Boolean) {
        vinylSfxEnabled = enabled
        prefs.vinylSfxEnabled = enabled
    }

    fun changeVinylSfxVolume(v: Float) {
        vinylSfxVolume = v
        prefs.vinylSfxVolume = v
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
    // ===== 一键补齐所有缺失的封面 =====

    /** 批量补封面的进度。`null` = 当前没在跑 */
    var coverBatch by mutableStateOf<CoverBatchProgress?>(null)
        private set

    private var coverBatchJob: Job? = null

    data class CoverBatchProgress(
        /** 已经处理完的歌数 */
        val done: Int,
        /** 一共要处理多少首 */
        val total: Int,
        /** 成功补到的张数 */
        val found: Int,
        /** 正在处理的歌名 —— 让用户看得见"它在动" */
        val current: String
    )

    /**
     * 一键给**所有还缺封面**的歌补上封面。点一下就开始，不挑网络。
     *
     * ## 为什么串行
     *
     * 一次请求约 1 秒，几百首歌并发下去既会打爆音源（iTunes 和网易云都是
     * 免费公开接口，没有配额可言），也会让进度条瞬间跳到底 ——
     * 用户看不到过程，反而以为卡住了。串行还能让「停止」立刻生效。
     *
     * ## 为什么会重复点没反应
     *
     * 已经在跑的时候直接返回：[coverBatchJob] 只有一个，重新启动会把
     * 上一轮的进度冲掉，而上一轮的协程还在发请求。
     */
    fun fetchAllMissingCovers() {
        if (coverBatchJob?.isActive == true) return
        // 快照一份：跑的过程中用户可能从列表删歌，那不该影响这一轮
        val targets = songs.filter { it.isLocal }
        if (targets.isEmpty()) {
            showToast("曲库里还没有歌")
            return
        }
        coverBatchJob = viewModelScope.launch {
            val app = getApplication<Application>()
            var found = 0
            var done = 0
            for (song in targets) {
                // 每首之间检查一次：点了「停止」就真的停在这里，不再发下一个请求
                if (!isActive) break
                coverBatch = CoverBatchProgress(done, targets.size, found, song.title)
                if (withContext(Dispatchers.IO) { fetchOneCover(app, song) }) found++
                done++
                coverBatch = CoverBatchProgress(done, targets.size, found, song.title)
            }
            coverBatch = null
            showToast(
                if (found == 0) "一首都没补到（可能本来就有封面）"
                else "已补齐 $found 张封面"
            )
        }
    }

    fun cancelCoverBatch() {
        coverBatchJob?.cancel()
        coverBatchJob = null
        coverBatch = null
        showToast("已停止")
    }

    /**
     * 给一首歌补封面（只补"确实没有"的）。@return 是否补到了新封面。
     *
     * 三道短路，避免把已经有的封面再下一遍：
     * 1. 磁盘缓存里已经有（之前下载过）；
     * 2. 之前确认过"确实没有"（[CoverCache.Lookup.KnownMiss]）；
     * 3. 本地 / 内嵌有封面。
     *
     * 第 3 步用 **128 的尺寸档**只为判断"有没有"，不为显示 ——
     * 批量跑的时候不该为每张封面都解一张 320px 的位图出来。
     */
    private suspend fun fetchOneCover(app: Application, song: Song): Boolean {
        val key = OnlineCover.keyOf(song.title, song.artist) ?: return false
        if (ArtworkLoader.loadSelected(app, song.albumId, 128, song.uri, song.id, key,
                !prefs.pinnedCoverUrl(song.id).isNullOrBlank()) != null) return false
        when (CoverCache.lookup(app, key)) {
            is CoverCache.Lookup.Hit -> return false
            CoverCache.Lookup.KnownMiss -> return false
            CoverCache.Lookup.Unknown -> Unit
        }
        if (ArtworkLoader.load(app, song.albumId, 128, song.uri, key) != null) return false

        val url = findCoverUrl(song)
        if (url.isNullOrBlank()) {
            // 网络失败和真正无结果都可能返回 null；不能永久写成“无封面”。
            return false
        }
        val raw = OnlineCover.downloadBytes(url) ?: return false
        if (!CoverCache.store(app, key, raw)) return false
        embedCoverToFile(app, song, raw)
        // 让「磁盘里也没有封面」那条短路失效：列表 200px 和播放页 900px 是两个缓存键，
        // 不失效的话另一个档位会一直以为没封面。
        //
        // 这里**不**调 `ArtworkLoader.putOnline`：批量下载的图若全部驻留内存，
        // 几百首就是几百张位图。写盘已经足够 —— 列表下次组合（滚动 / 重进）就会读到。
        return true
    }

    /**
     * 下载到的封面顺手写进歌曲文件本身；失败就只留在应用私有目录，**不弹窗**。
     *
     * - 只做 MP3 / FLAC。M4A、WAV、OGG 等在 [CoverTagWriter] 里直接返回
     *   UnsupportedFormat —— 那些容器的覆盖写风险不值得在后台自动做。
     * - 分区存储下没有写权限时返回 NeedsPermission，这里刻意**不**拉起系统确认框：
     *   自动补封面动辄几十上百首，每首弹一次是骚扰；用户手动「选封面」那条路仍会弹。
     * - 每首每个会话只尝试一次，避免反复改文件（改动会改 mtime）。
     */
    private suspend fun embedCoverToFile(app: Application, song: Song, raw: ByteArray) {
        if (!coverEmbedTried.add(song.id)) return
        when (CoverTagWriter.write(app, song, raw, skipIfPresent = true)) {
            CoverTagWriter.Result.Written ->
                Log.i("SonanceCover", "封面已写入文件：${song.displayName}")
            is CoverTagWriter.Result.NeedsPermission ->
                Log.i("SonanceCover", "无写文件权限，封面仅存应用私有目录：${song.displayName}")
            CoverTagWriter.Result.Failed ->
                Log.w("SonanceCover", "写封面到文件失败：${song.displayName}")
            else -> Unit
        }
    }

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
                ArtworkLoader.loadSelected(app, song.albumId, 320, song.uri, song.id, key,
                    !prefs.pinnedCoverUrl(song.id).isNullOrBlank())
                    ?: ArtworkLoader.load(app, song.albumId, 320, song.uri, key)
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
                    // 网络超时不能持久化成“确定没有”，下次仍可重试。
                    return@withContext null
                }
                val raw = OnlineCover.downloadBytes(url)
                if (raw != null) {
                    if (!CoverCache.store(app, key, raw)) return@withContext null
                    embedCoverToFile(app, song, raw)
                }
                raw
            }

            val bmp = bytes?.let { OnlineCover.decode(it, OnlineCover.FULL_PX) }
            // 刚写盘 → 让「磁盘里也没有封面」那条短路失效。
            // 必须做：列表要 200px、播放页要 900px，是两个缓存键，
            // 不失效的话另一个档位本次会话里会一直以为没封面。
            if (bmp == null) {
                coverTried.remove(song.id)
                ArtworkLoader.onlineCovers.remove(song.id)
                android.util.Log.i("SonanceCover", "没补到封面：${song.title} - ${song.artist}")
            } else {
                ArtworkLoader.putOnline(song.id, bmp.asImageBitmap())
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
                val bytes = OnlineCover.downloadBytes(url) ?: return@withContext null
                if (CoverCache.store(app, key, bytes)) bytes else null
            }
            if (raw == null) return@launch

            // 刚写盘 → 让「磁盘里也没有」那条短路失效（列表 200px 和播放页 900px
            // 是两个缓存键，不失效的话另一档本次会话里会一直以为没封面）
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
                list == null -> "搜索词是空的，至少填个歌名"
                list.isEmpty() -> "iTunes 和网易云都没搜到「$title${if (artist.isNotBlank()) " - $artist" else ""}」。" +
                    "试试只留歌名，或去掉 (Live) / 抖音版 这类后缀"
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
            if (song == null) {
                coverSearching = false
                coverNotice = "歌曲已不存在，无法保存封面"
                return@launch
            }
            val raw = withContext(Dispatchers.IO) { OnlineCover.downloadBytes(candidate.fullUrl) }
            if (raw == null) {
                coverSearching = false
                coverNotice = "这张封面下载失败，换一张试试"
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) { OnlineCover.decode(raw, OnlineCover.FULL_PX) }
            if (bmp == null) {
                coverSearching = false
                coverNotice = "这张封面无法解码，请换一张"
                return@launch
            }
            ArtworkLoader.invalidateSelected(songId)
            val version = ArtworkLoader.selectedGeneration(songId)
            val saved = withContext(Dispatchers.IO) {
                ArtworkLoader.ifSelectedGeneration(songId, version) {
                    CoverCache.storeSelected(app, song.id, song.uri, raw)
                }
            }
            if (saved == null) {
                coverSearching = false
                return@launch
            }
            if (!saved) {
                coverSearching = false
                coverNotice = "封面保存失败，请检查设备存储空间后重试"
                return@launch
            }
            ArtworkLoader.invalidateSelected(songId)
            withContext(Dispatchers.IO) {
                CoverCache.store(app, OnlineCover.keyOf(song.title, song.artist), raw)
            }

            // 本地歌：顺手把封面写进**文件本身**，这样换设备、拷给别人、
            // 用别的播放器打开时封面都还在。在线歌没有本地文件，跳过。
            //
            // 只在这个入口写：自动补封面（ensureOnlineSongCover）是后台行为，
            // 不该在用户不知情的时候改他的文件。
            val writeOutcome = if (song.isLocal) {
                withContext(Dispatchers.IO) { CoverTagWriter.write(app, song, raw) }
            } else {
                null
            }
            if (writeOutcome is CoverTagWriter.Result.NeedsPermission) {
                // 弹窗交给界面（ViewModel 拿不到 Activity），授权后走
                // onCoverWritePermissionResult 重试
                pendingCoverWrite = PendingCoverWrite(songId, raw, writeOutcome.intentSender)
            }

            // 只有本地歌才记「用户钉过这张封面」。
            //
            // 在线歌的补图走 ensureOnlineSongCover，它只认音源给的地址、**不读这个 pin** ——
            // 写了也永远不会被读，只是往 SharedPreferences 里堆垃圾（每首一个 key）。
            // 手选的封面本身已经落进 CoverCache，在线歌下次播放照样会用上。
            if (!isRemoteSongId(songId)) prefs.pinCoverUrl(songId, candidate.fullUrl)
            ArtworkLoader.putOnline(songId, bmp.asImageBitmap())
            coverTried.add(songId)
            // 刚写盘：让缓存短路失效，并把通知栏 / 锁屏的封面一起换掉
            player.refreshCurrentArtwork(song)
            coverSearching = false
            coverResults = emptyList()
            coverTargetSongId = 0L
            refreshCoverCacheStats()
            showToast(coverAppliedMessage(writeOutcome))
        }
    }

    /** 封面应用后给用户的一句话 —— 写文件的结果不同，说法也该不同 */
    private fun coverAppliedMessage(outcome: CoverTagWriter.Result?): String = when (outcome) {
        null -> "封面已更新"
        CoverTagWriter.Result.Written -> "封面已更新，并写入文件"
        CoverTagWriter.Result.UnsupportedFormat -> "封面已更新（该格式暂不支持写入文件）"
        is CoverTagWriter.Result.NeedsPermission -> "封面已更新，确认后可写入文件"
        else -> "封面已更新（写入文件失败）"
    }

    /**
     * 系统写文件确认框的结果（见 [pendingCoverWrite]）。
     *
     * @param granted 用户在「允许修改此文件？」里点了允许（或已授予存储权限）
     */
    fun onCoverWritePermissionResult(granted: Boolean) {
        val pending = pendingCoverWrite ?: return
        pendingCoverWrite = null
        if (!granted) {
            showToast("未授权改写文件，封面只存在应用里")
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val song = songById(pending.songId) ?: return@launch
            val result = withContext(Dispatchers.IO) {
                CoverTagWriter.write(app, song, pending.cover)
            }
            showToast(
                when (result) {
                    CoverTagWriter.Result.Written -> "封面已写入文件"
                    is CoverTagWriter.Result.NeedsPermission -> "还是没拿到写入权限"
                    else -> "写入文件失败"
                }
            )
        }
    }

    // ===== 歌曲信息编辑 / 听歌识曲 =====

    /**
     * 一次「等用户点头」的歌曲信息写盘（结构照 [PendingCoverWrite] 来：
     * 系统授权框弹出来时编辑对话框可能已经关了，字段必须存在 pending 里）。
     */
    class PendingMetaEdit(
        val songId: Long,
        val title: String,
        val artist: String,
        val album: String,
        /**
         * 非空 → API 29+ 的「允许修改此文件？」；为空 → API 28 及以下先要
         * `WRITE_EXTERNAL_STORAGE`
         */
        val intentSender: IntentSender?
    )

    /** 待用户授权的信息写回；null 表示没有。界面据此拉起系统弹窗 */
    var pendingMetaEdit by mutableStateOf<PendingMetaEdit?>(null)
        private set

    /** 识别状态机：空闲 → 运行（带阶段文案）→ 出结果等确认 / 失败回空闲 */
    sealed interface RecognizeState {
        data object Idle : RecognizeState
        data class Running(val songId: Long, val phase: String) : RecognizeState
        data class Done(val songId: Long, val result: NeteaseRecognizer.Recognized) : RecognizeState
    }

    var recognizeState by mutableStateOf<RecognizeState>(RecognizeState.Idle)
        private set

    /** 手动编辑歌曲信息：写进文件真实标签 + 文件改名「歌名 - 歌手」 */
    fun editSongInfo(songId: Long, title: String, artist: String, album: String) {
        viewModelScope.launch { writeSongMeta(songId, title, artist, album) }
    }

    private suspend fun writeSongMeta(songId: Long, title: String, artist: String, album: String) {
        val app = getApplication<Application>()
        val song = songById(songId) ?: return
        val result = SongMetaEditor.edit(app, song, title, artist, album)
        when (result) {
            is SongMetaEditor.Result.Written -> {
                applySongMetaInMemory(song, title, artist, album)
                showToast(if (result.renamed) "已写入文件，文件已改名" else "已写入文件")
            }
            is SongMetaEditor.Result.NeedsPermission ->
                pendingMetaEdit = PendingMetaEdit(songId, title, artist, album, result.intentSender)
            SongMetaEditor.Result.NotLocal -> showToast("在线歌曲没有本地文件可写")
            SongMetaEditor.Result.UnsupportedFormat -> showToast("该格式暂不支持写入（仅 mp3 / m4a）")
            SongMetaEditor.Result.Failed -> showToast("写入文件失败")
        }
    }

    /** 系统写文件确认框的结果（见 [pendingMetaEdit]） */
    fun onMetaEditPermissionResult(granted: Boolean) {
        val pending = pendingMetaEdit ?: return
        pendingMetaEdit = null
        if (!granted) {
            showToast("未授权改写文件，信息未保存")
            return
        }
        viewModelScope.launch {
            writeSongMeta(pending.songId, pending.title, pending.artist, pending.album)
        }
    }

    /**
     * 标签写盘成功后把内存列表同步改掉：MediaStore 的回读是异步的（rescan
     * 不保证立即可见），等下次扫描再刷新会让用户以为没改成功。
     */
    private fun applySongMetaInMemory(song: Song, title: String, artist: String, album: String) {
        val patched = song.copy(title = title, artist = artist, album = album)
        songs = songs.map { if (it.id == song.id) patched else it }
        albums = MediaRepository.groupAlbums(songs)
        artists = MediaRepository.groupArtists(songs)
        folders = MediaRepository.groupFolders(songs)
        prefs.songsCacheJson = encodeSongsToJson(songs)
        // 封面缓存按「标题+歌手」做 key，改名后按新信息重新补（pinCoverUrl 按
        // songId 记，不受改名影响）；正在播的话通知栏/锁屏跟着换
        player.refreshCurrentArtwork(patched)
    }

    /**
     * 听歌识曲：解码开头几秒 → 算指纹 → 网易云匹配。
     * 出结果不停在 Done 状态等确认卡片，用户点「应用」才写盘。
     */
    fun startRecognize(songId: Long) {
        if (recognizeState is RecognizeState.Running) {
            showToast("已有识别任务进行中")
            return
        }
        val song = songById(songId) ?: return
        if (!song.isLocal) {
            showToast("仅支持识别本地文件")
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            recognizeState = RecognizeState.Running(songId, "正在解码音频")
            val decoded = withContext(Dispatchers.IO) {
                PcmExtractor.decodePrefix(app, song.uri, song.durationMs)
            }
            if (decoded == null) {
                recognizeState = RecognizeState.Idle
                showToast("音频解码失败，无法识别")
                return@launch
            }
            recognizeState = RecognizeState.Running(songId, "正在计算指纹")
            val fp = AudioFingerprinter.fingerprint(app, PcmExtractor.toFingerprintSamples(decoded))
            if (fp.fp == null) {
                recognizeState = RecognizeState.Idle
                showToast(fp.error ?: "指纹计算失败")
                return@launch
            }
            recognizeState = RecognizeState.Running(songId, "正在识别歌曲")
            val match = NeteaseRecognizer.match(fp.fp, song.durationMs)
            if (match.recognized == null) {
                recognizeState = RecognizeState.Idle
                showToast(match.error ?: "未识别出歌曲")
                return@launch
            }
            recognizeState = RecognizeState.Done(songId, match.recognized)
        }
    }

    /** 用户在识别确认卡片上点了「应用」：按识别结果写盘 */
    fun applyRecognition() {
        val state = recognizeState
        if (state !is RecognizeState.Done) return
        recognizeState = RecognizeState.Idle
        viewModelScope.launch {
            writeSongMeta(state.songId, state.result.title, state.result.artist, state.result.album)
        }
    }

    /** 用户放弃了识别结果 */
    fun dismissRecognition() {
        if (recognizeState is RecognizeState.Done) recognizeState = RecognizeState.Idle
    }

    /** 删掉这首歌的封面（回到占位图；联网状态下下次播放会重新找） */
    fun clearCover(songId: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val song = songById(songId)
            // 先阻止后台旧缓存迁移，再删除文件；完成后再次通知可见行。
            prefs.pinCoverUrl(songId, null)
            ArtworkLoader.invalidateSelected(songId)
            val version = ArtworkLoader.selectedGeneration(songId)
            withContext(Dispatchers.IO) {
                ArtworkLoader.ifSelectedGeneration(songId, version) {
                    song?.let {
                        CoverCache.removeSelected(app, it.id, it.uri)
                        CoverCache.remove(app, OnlineCover.keyOf(it.title, it.artist))
                    }
                    Unit
                }
            }
            ArtworkLoader.invalidateSelected(songId)
            ArtworkLoader.putOnline(songId, null)
            coverTried.remove(songId)
            ArtworkLoader.clear(app)
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
            val app = getApplication<Application>()
            if (!preserveLegacySelectedCovers(app, songs, verifyExisting = true)) {
                showToast("手选封面保护失败，未清除缓存")
                return@launch
            }
            withContext(Dispatchers.IO) { CoverCache.clear(app) }
            ArtworkLoader.clear(app)
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

    /**
     * 当前播放歌曲是否已经在列表可见区内。由**当前显示的列表页**根据
     * `LazyListState.layoutInfo.visibleItemsInfo` 实时上报；悬浮的
     * 「回到当前播放」按钮据此隐藏/显示。
     */
    var currentSongVisible by mutableStateOf(false)

    /**
     * 设置「关于」长按版本号：把当前设置导出成出厂基线 JSON（只读，不改本地值）。
     * @return 导出的文件；失败返回 null
     */
    fun exportSettingsBaseline(): java.io.File? = prefs.exportSettingsBaseline(getApplication())

    fun changeSkin(value: Skin) {
        skin = value
        prefs.skinId = value.id
        // 玻璃主题：颜色之外还要把三处玻璃铺开，并顺手把迷你播放条换成悬浮卡片
        // （贴底通栏没有"一块玻璃"的观感，配玻璃主题说不通）
        val bundledGlass = value.glass
        if (bundledGlass != null) {
            applyGlassEverywhere(bundledGlass)
            miniPlayerStyle = MiniPlayerStyle.FLOAT
            prefs.miniPlayerStyle = MiniPlayerStyle.FLOAT.ordinal
        }
        // 桌面小组件也跟着换色。它在**另一个进程上下文**里渲染，
        // 不会自己感知到皮肤变了，必须应用侧把颜色写过去并请求重画。
        val app = getApplication<Application>()
        com.sonance.player.widget.SonanceWidgets.onSkinChanged(
            context = app,
            bg = value.bg.toArgb(),
            text = value.text.toArgb(),
            textDim = value.textDim.toArgb(),
            accent = value.accent.toArgb(),
            onAccent = value.onAccent.toArgb()
        )
        showToast(
            if (value.glass != null) "已切换皮肤 · ${value.name}（含玻璃背景）"
            else "已切换皮肤 · ${value.name}"
        )
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

    /** 顶栏显示的应用名（双击顶栏标题可改） */
    var appDisplayName by mutableStateOf(prefs.appDisplayName)
        private set

    fun changeDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == appDisplayName) return
        appDisplayName = trimmed
        prefs.appDisplayName = trimmed
        showToast("已改名为「$trimmed」")
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
        com.sonance.player.widget.SonanceWidgets.onFavoriteOrModeChanged(getApplication())
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
     * 可选音源的可观察快照。
     *
     * `OnlineSources.all` 是普通 getter，插件增删后虽然变了，但 Compose 观察不到，
     * 结果就是「插件列表里已经出现，上方 chips 却还是只有网易云」。
     * 所以每次 refreshSources 之后都要把快照重新赋给这个 state。
     */
    var onlineSources by mutableStateOf(OnlineSources.all)
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

            val outcome = OnlineDownloader.download(
                context = app,
                source = song.source,
                remoteId = remoteId,
                title = song.title,
                artist = song.artist,
                album = song.album,
                streamUrl = src.streamUrl(remoteId),
                coverBytes = coverBytesForTag(song)
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
    private suspend fun coverBytesForTag(song: Song): ByteArray? =
        coverBytesForTag(song.title, song.artist, song.coverUrl)

    /**
     * [coverBytesForTag] 的原始字段版本 —— 歌单批量下载拿到的只有 [RemoteTrack]，
     * 不必为了写个封面标签去拼一个 [Song] 出来。
     */
    private suspend fun coverBytesForTag(
        title: String,
        artist: String,
        coverUrl: String?
    ): ByteArray? {
        val key = OnlineCover.keyOf(title, artist) ?: return null
        val app = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            when (val hit = CoverCache.lookup(app, key)) {
                is CoverCache.Lookup.Hit -> hit.bytes
                else -> {
                    val url = coverUrl ?: return@withContext null
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

    // ===== 在线搜索历史 =====

    /** 最近搜过的词，最新在前。给搜索框下面那排胶囊用 */
    var onlineHistory by mutableStateOf(prefs.onlineHistory())
        private set

    /**
     * 点历史词直接搜。
     *
     * 先把输入框填上这个词再搜 —— **必须两步都做**：只搜不填的话，
     * 结果回来之后输入框还是空的，看起来像搜了个空气；
     * 只填不搜的话用户还得再点一次放大镜。
     */
    fun searchFromHistory(term: String) {
        if (term.isBlank()) return
        onlineQuery = term
        searchOnline(term)
    }

    fun removeOnlineHistory(term: String) {
        prefs.removeOnlineHistory(term)
        onlineHistory = prefs.onlineHistory()
    }

    fun clearOnlineHistory() {
        prefs.clearOnlineHistory()
        onlineHistory = emptyList()
        showToast("已清空搜索历史")
    }

    /** 有结果时把这个词记进历史。状态持久化在 [Prefs]，这里只负责让组合能观察到 */
    private fun pushOnlineHistory(term: String) {
        prefs.pushOnlineHistory(term)
        onlineHistory = prefs.onlineHistory()
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
        // 推荐也是按音源拉的，一并清掉让界面重新拉一次
        recommendedJob?.cancel()
        recommendedJob = null
        recommendedPlaylists = emptyList()
        recommendedFailed = false
        recommendedLoading = false
    }

    // ===== 内容源插件 =====

    private val pluginRepo = PluginRepository.get(app)

    /** 已安装插件，界面跟着它刷新 */
    var pluginRecords by mutableStateOf(pluginRepo.list())
        private set

    /** 导入/启停的结果提示，只在设置页显示，不弹 toast 打断用户 */
    var pluginHint by mutableStateOf<String?>(null)
        private set

    fun importPluginScript(script: String) {
        viewModelScope.launch {
            pluginHint = "正在校验插件…"
            pluginRepo.install(script)
                .onSuccess { record ->
                    pluginRepo.refreshSources()
                    onlineSources = OnlineSources.all
                    pluginRecords = pluginRepo.list()
                    pluginHint = "已导入「${record.meta.platform}」，可在上方切换"
                }
                .onFailure { pluginHint = "导入失败：${it.message}" }
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        pluginRepo.setEnabled(id, enabled)
        pluginRepo.refreshSources()
        onlineSources = OnlineSources.all
        pluginRecords = pluginRepo.list()
        // 关掉的正好是当前源时，退回内置源，避免选中一个已不存在的 id
        if (!enabled && onlineSourceId == id) changeOnlineSource(OnlineSources.all.first().id)
        pluginHint = if (enabled) "已启用" else "已停用"
    }

    fun removePlugin(id: String) {
        if (onlineSourceId == id) changeOnlineSource(OnlineSources.all.first().id)
        pluginRepo.remove(id)
        pluginRepo.refreshSources()
        onlineSources = OnlineSources.all
        pluginRecords = pluginRepo.list()
        pluginHint = "已删除"
    }

    fun clearPluginHint() { pluginHint = null }

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
        // 搜单曲还是搜歌单，由 [onlineSearchMode] 决定。两类结果的下标语义完全不同
        // （一个进播放队列、一个进歌单详情页），所以共用一套"在搜 / 有下一页 / 搜索词"
        // 状态，但结果列表各存各的 —— 切换模式时会清空，同一时刻只有一类在生效。
        when (onlineSearchMode) {
            OnlineSearchMode.SINGLE -> searchOnlineSingles(query)
            OnlineSearchMode.PLAYLIST -> searchOnlinePlaylists(query)
        }
    }

    private fun searchOnlineSingles(query: String) {
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
                // **搜到了东西**才记进历史。
                // 打了一半的词、搜不到结果的词都不该留 —— 历史的价值在于
                // "点一下就能回到上次搜到东西的那次搜索"。
                // 失败（page == null）也不记：断网时误触的那一下不该占一个位置。
                if (page != null && tracks.isNotEmpty()) pushOnlineHistory(kw)
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
        when (onlineSearchMode) {
            OnlineSearchMode.SINGLE -> loadMoreOnlineSingles()
            OnlineSearchMode.PLAYLIST -> loadMoreOnlinePlaylists()
        }
    }

    private fun loadMoreOnlineSingles() {
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
        onlinePlaylistResults = emptyList()
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
        //
        // 下标必须在**换成曲库副本之前**算：换过去的本地 Song 没有 remoteId，
        // 换完再按 remoteId 找会一首都找不到（表现是点了没反应）。
        val onlineSongs = playable.mapNotNull { onlineSongMap[remoteSongId(src.id, it.remoteId)] }
        val at = onlineSongs.indexOfFirst { it.remoteId == track.remoteId }
        if (at < 0) return

        // 内置音源的地址是同步拼出来的，保持原来的零延迟路径
        if (src !is PluginOnlineSource) {
            player.play(onlineSongs.map { localCopyOf(it) ?: it }, at)
            return
        }
        // 插件源的 getMediaSource 是异步 Promise。这里**只解析点中的这一首**：
        // 以前是先把整页 20 首串行预热完再播，点击到出声要好几秒；
        // 更糟的是任何一首取流失败都会留下空 uri，被播放器报成「找不到文件」。
        viewModelScope.launch { playPluginQueue(src, onlineSongs, at, track.title) }
    }

    /**
     * 插件源的「点了就播」：解析 [at] 这一首 → 立刻入队播放 → 后台补其余曲目地址。
     *
     * @return false 表示当前这首确实拿不到地址（已提示用户），调用方不要再调 [player.play]。
     */
    private suspend fun playPluginQueue(
        src: PluginOnlineSource,
        onlineSongs: List<Song>,
        at: Int,
        title: String,
    ): Boolean {
        val target = onlineSongs.getOrNull(at) ?: return false

        // 已经存到本地的歌走本地文件，不联网
        if (localCopyOf(target) == null) {
            val url = src.resolveStream(target.remoteId.orEmpty())
            if (url == null) {
                showToast("「$title」获取播放地址失败，换一首或稍后再试")
                return false
            }
        }

        val songs = onlineSongs.map { s ->
            val url = src.streamUrl(s.remoteId.orEmpty())
            if (url.isNotBlank() && localCopyOf(s) == null) {
                s.copy(uri = android.net.Uri.parse(url))
            } else s
        }.map { localCopyOf(it) ?: it }
        player.play(songs, at)

        // 队列里剩下的歌此刻还没有地址。后台按顺序补齐并**原地替换**队列项，
        // 正常听到下一首（几分钟后）时地址早就就位了，点播却不用等。
        prefetchQueueStreams(src, onlineSongs, skipRemoteId = target.remoteId)
        return true
    }

    /**
     * 后台逐首取流并把地址补进播放队列。
     *
     * 只处理还没地址、也没存到本地的曲目；解析失败的静默跳过（真正轮到它时
     * [resolveStreamIfMissing] 还会再试一次）。同一时刻只保留一轮 —— 用户连续点歌时
     * 旧队列的预取已经没有意义，留着只会和新的播放抢带宽。
     */
    private var prefetchStreamsJob: Job? = null

    private fun prefetchQueueStreams(
        src: PluginOnlineSource,
        queue: List<Song>,
        skipRemoteId: String?,
    ) {
        prefetchStreamsJob?.cancel()
        prefetchStreamsJob = viewModelScope.launch {
            for (s in queue) {
                if (!isActive) break
                val rid = s.remoteId ?: continue
                if (rid == skipRemoteId) continue
                if (src.streamUrl(rid).isNotBlank()) continue
                if (localCopyOf(s) != null) continue
                val url = src.resolveStream(rid)
                if (url != null) player.patchQueueUri(s.id, android.net.Uri.parse(url))
            }
        }
    }

    /**
     * 真正轮到某一首在线歌时，如果它入队时还没解析到地址，这里补一次。
     *
     * 典型场景：用户点了第一首后立刻连按「下一首」，后台预取还没来得及跑到这一首。
     * 取到地址后替换当前 MediaItem 会让 ExoPlayer 用新地址重新准备，
     * 所以「多等一下」就能出声，而不是弹「找不到文件」。
     */
    private fun resolveStreamIfMissing(song: Song) {
        if (song.isLocal) return
        val src = OnlineSources.byId(song.source) as? PluginOnlineSource ?: return
        val rid = song.remoteId ?: return
        if (src.streamUrl(rid).isNotBlank()) return
        if (localCopyOf(song) != null) return
        viewModelScope.launch {
            val url = src.resolveStream(rid) ?: return@launch
            player.patchQueueUri(song.id, android.net.Uri.parse(url))
        }
    }

    /**
     * 这首歌是不是**已经存到本地**了。
     *
     * 判据是 [Prefs.downloadedKeys]（`音源:远端id`），和 [maybeDownload] 用同一个键 ——
     * 判断和写入必须是同一套，否则会出现"存过却还是每次都下"。
     */
    fun isDownloaded(track: RemoteTrack): Boolean {
        val key = OnlineDownloader.downloadKeyOf(onlineSourceId, track.remoteId) ?: return false
        return key in prefs.downloadedKeys()
    }

    /**
     * 已经存到本地的在线歌 → 曲库里对应的那一首；没存过 / 曲库里找不到则返回 null。
     *
     * 匹配用**标题 + 艺术家**：下载时这些信息都写进了 ID3 标签，扫描进曲库后
     * 读出来的就是它们。用 id 匹配是不行的 —— 在线 id 是哈希出来的负数，
     * 和 MediaStore 给的本地 id 没有任何关系。
     *
     * 找不到时返回 null 而不是硬凑：那说明文件被用户挪走或删了，
     * 退回在线播放（重新取一次流）比播一个找不到文件的本地条目强。
     */
    private fun localCopyOf(song: Song): Song? {
        val key = OnlineDownloader.downloadKeyOf(song.source, song.remoteId) ?: return null
        if (key !in prefs.downloadedKeys()) return null
        return songs.firstOrNull { it.isLocal && it.title == song.title && it.artist == song.artist }
    }

    /** 在线歌的缩略图地址（列表用）。没有音源或没有封面时返回 null */
    fun onlineThumbUrl(track: RemoteTrack, px: Int): String? {
        val url = track.coverUrl ?: return null
        return onlineSource?.thumbUrl(url, px)
    }

    /** 歌单封面的缩略图地址。和 [onlineThumbUrl] 同理，只是数据源是 [RemotePlaylist] */
    fun playlistThumbUrl(playlist: RemotePlaylist, px: Int): String? {
        val url = playlist.coverUrl ?: return null
        return onlineSource?.thumbUrl(url, px)
    }

    // ===== 在线歌单：搜索 / 推荐 / 详情 / 批量下载 =====

    /**
     * 在线搜索的对象：单曲还是歌单。
     *
     * 两类结果**共用**「在搜 / 有下一页 / 搜索词 / 翻页失败」这套状态（同一时刻只有一类
     * 在生效），但结果列表各存各的 —— 一个进播放队列，一个进歌单详情页，下标语义
     * 完全不同，混在一起点播放必然错位。切换模式会走 [clearOnlineSearch]。
     */
    enum class OnlineSearchMode { SINGLE, PLAYLIST }

    var onlineSearchMode by mutableStateOf(OnlineSearchMode.SINGLE)
        private set

    fun changeOnlineSearchMode(mode: OnlineSearchMode) {
        if (mode == onlineSearchMode) return
        val keepQuery = onlineQuery
        onlineSearchMode = mode
        // 换对象 = 换结果，旧结果的下标不能留
        clearOnlineSearch()
        // 但输入框里的词要放回去：切模式通常是想"换个对象搜同一个词"，
        // 清掉的话用户得重新打一遍。放回去之后防抖逻辑会自然触发一次新搜索。
        onlineQuery = keepQuery
    }

    /** 歌单搜索结果（**已累积多页**）。和 [onlineResults] 同一套分页约定 */
    var onlinePlaylistResults by mutableStateOf<List<RemotePlaylist>>(emptyList())
        private set

    /**
     * 搜歌单（第一页）。
     *
     * 和 [searchOnlineSingles] 是同一套骨架（取消在途请求 → 拿总数判断有没有下一页 →
     * 失败和"没搜到"分开说），只是换了音源方法、结果类型和空态文案。
     */
    private fun searchOnlinePlaylists(query: String) {
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
                val page = withContext(Dispatchers.IO) {
                    src.searchPlaylists(kw, ONLINE_PAGE_SIZE, 0)
                }
                val lists = page?.playlists.orEmpty()

                onlinePlaylistResults = lists
                onlineLoadedCount = lists.size
                onlineSearchedFor = kw
                onlineSearchFailed = page == null
                onlineHasMore = page != null &&
                    page.hasMore(offset = 0, limit = ONLINE_PAGE_SIZE) &&
                    lists.size < MAX_ONLINE_RESULTS
                onlineLoadMoreFailed = false
                if (page != null && lists.isNotEmpty()) pushOnlineHistory(kw)
                onlineNotice = when {
                    page == null -> "搜索失败，检查一下网络或稍后再试"
                    lists.isEmpty() -> "没搜到「$kw」相关的歌单"
                    else -> null
                }
            } finally {
                if (token == onlineSearchToken) onlineSearching = false
            }
        }
    }

    /** 歌单搜索的下一页。语义和 [loadMoreOnlineSingles] 一致 */
    private fun loadMoreOnlinePlaylists() {
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
                val page = withContext(Dispatchers.IO) {
                    src.searchPlaylists(kw, ONLINE_PAGE_SIZE, offset)
                }
                if (token != onlineSearchToken) return@launch
                if (page == null) {
                    onlineLoadMoreFailed = true
                    return@launch
                }
                val lists = page.playlists
                if (lists.isEmpty()) {
                    onlineHasMore = false
                    return@launch
                }
                val seen = onlinePlaylistResults.mapTo(HashSet(onlinePlaylistResults.size * 2)) {
                    it.remoteId
                }
                onlinePlaylistResults = onlinePlaylistResults + lists.filter { it.remoteId !in seen }
                onlineLoadedCount = offset + lists.size
                onlineHasMore = page.hasMore(offset, ONLINE_PAGE_SIZE) &&
                    onlineLoadedCount < MAX_ONLINE_RESULTS
            } finally {
                if (token == onlineSearchToken) onlineLoadingMore = false
            }
        }
    }

    // ---- 首页推荐歌单 ----

    /** 首页推荐（输入框为空时展示）。空列表 + [recommendedFailed] 才代表"拉失败" */
    var recommendedPlaylists by mutableStateOf<List<RemotePlaylist>>(emptyList())
        private set

    var recommendedLoading by mutableStateOf(false)
        private set

    var recommendedFailed by mutableStateOf(false)
        private set

    private var recommendedJob: Job? = null

    /**
     * 拉一次推荐歌单。
     *
     * 已经有结果就直接返回 —— 它由界面在进入在线页 / 换音源时调一次，
     * 不跟着输入框的每次变化重拉。失败时可以走 [retryRecommendedPlaylists]。
     */
    fun loadRecommendedPlaylists() {
        val src = onlineSource ?: return
        if (!onlineMusicEnabled) return
        if (recommendedLoading || recommendedPlaylists.isNotEmpty()) return
        recommendedJob = viewModelScope.launch {
            recommendedLoading = true
            recommendedFailed = false
            try {
                val list = withContext(Dispatchers.IO) { src.recommendedPlaylists(RECOMMEND_LIMIT) }
                if (list == null) {
                    recommendedFailed = true
                } else {
                    recommendedPlaylists = list
                }
            } finally {
                recommendedLoading = false
            }
        }
    }

    fun retryRecommendedPlaylists() {
        recommendedJob?.cancel()
        recommendedJob = null
        recommendedLoading = false
        recommendedFailed = false
        loadRecommendedPlaylists()
    }

    // ---- 歌单详情 ----

    /** 当前打开的歌单；null 表示没在详情页。界面据此渲染标题 / 封面 */
    var playlistDetail by mutableStateOf<RemotePlaylist?>(null)
        private set

    /** 详情页的曲目（已补全元数据）。空 + [playlistFailed] 才是"拉失败" */
    var playlistTracks by mutableStateOf<List<RemoteTrack>>(emptyList())
        private set

    var playlistLoading by mutableStateOf(false)
        private set

    var playlistFailed by mutableStateOf(false)
        private set

    private var playlistJob: Job? = null

    /**
     * 打开一个歌单：记住它，然后去拉全部曲目。
     *
     * 拉曲目是**两次请求起步**（trackIds + 批量详情，见 [OnlineSource.playlistTracks]），
     * 大歌单要好几秒，所以这里只负责发起，界面用 [playlistLoading] 显示进度。
     */
    fun openPlaylist(playlist: RemotePlaylist) {
        val src = onlineSource ?: return
        playlistJob?.cancel()
        playlistDetail = playlist
        playlistTracks = emptyList()
        playlistFailed = false
        // 同步置位，别等协程调度 —— 否则详情页会先闪一下"这个歌单是空的"
        playlistLoading = true
        playlistJob = viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { src.playlistTracks(playlist.remoteId) }
                // 期间关掉了 / 又点了别的歌单：这一批已经不属于当前页面
                if (playlistDetail?.remoteId != playlist.remoteId) return@launch
                if (tracks == null) {
                    playlistFailed = true
                } else {
                    playlistTracks = tracks
                    // 并进 onlineSongMap：播放、队列页、播放页、收藏判断都要按 id 反查
                    commitOnlinePage(src, tracks)
                }
            } finally {
                if (playlistDetail?.remoteId == playlist.remoteId) playlistLoading = false
            }
        }
    }

    /** 详情页加载失败后的"点击重试" */
    fun retryPlaylistTracks() {
        playlistDetail?.let { openPlaylist(it) }
    }

    /** 离开详情页时调用，顺带取消在途的曲目请求 */
    fun closePlaylist() {
        playlistJob?.cancel()
        playlistJob = null
        playlistDetail = null
        playlistTracks = emptyList()
        playlistLoading = false
        playlistFailed = false
    }

    /**
     * 播放歌单里的第 [index] 首。
     *
     * 队列只放**能播的**，理由和 [playOnline] 完全一样：VIP 歌塞进队列，
     * 自动续播到它就会卡住报错，用户还以为播放器坏了。
     */
    fun playPlaylistTrack(index: Int) {
        val src = onlineSource ?: return
        val track = playlistTracks.getOrNull(index) ?: return
        if (!track.playable) {
            showToast("「${track.title}」需要会员，拿不到播放地址")
            return
        }
        val playable = playlistTracks.filter { it.playable }
        val onlineSongs = playable.mapNotNull { onlineSongMap[remoteSongId(src.id, it.remoteId)] }
        val at = onlineSongs.indexOfFirst { it.remoteId == track.remoteId }
        if (at < 0) return
        // 插件源的流地址是异步取的，走和搜索结果同一条「点了就播」路径
        if (src is PluginOnlineSource) {
            viewModelScope.launch { playPluginQueue(src, onlineSongs, at, track.title) }
            return
        }
        val songs = onlineSongs.map { localCopyOf(it) ?: it }
        player.play(songs, at)
    }

    /** 从歌单里第一首能播的开始放整个歌单（详情页的「播放全部」） */
    fun playPlaylist() {
        val at = playlistTracks.indexOfFirst { it.playable }
        if (at < 0) {
            showToast("这个歌单里没有能播的歌")
            return
        }
        playPlaylistTrack(at)
    }

    /** 当前歌单里已经存到本地的曲目数（详情页头部显示进度用） */
    val playlistDownloadedCount: Int
        get() = playlistTracks.count { isDownloaded(it) }

    /** 当前歌单里"还能下"的曲目数（可播且没存过）。确认弹窗用它报预期 */
    val playlistDownloadableCount: Int
        get() = playlistTracks.count { it.playable && !isDownloaded(it) }

    // ---- 一键下载整个歌单 ----

    var playlistDownloadActive by mutableStateOf(false)
        private set

    /**
     * 这一轮下载属于哪个歌单（[RemotePlaylist.remoteId]）；null = 没在跑。
     *
     * 下载是**跨页面存活**的：用户可以在下载途中退出去开另一个歌单。详情页靠它
     * 判断"这进度是不是我这一页的" —— 否则 A 歌单的进度条会出现在 B 歌单页上，
     * 让人以为在下 B。
     */
    var playlistDownloadOwner by mutableStateOf<String?>(null)
        private set

    /** 这一轮要下多少首（= 可播的曲目数，含已存过要跳过的） */
    var playlistDownloadTotal by mutableIntStateOf(0)
        private set

    /** 已处理多少首（含跳过和失败） */
    var playlistDownloadDone by mutableIntStateOf(0)
        private set

    var playlistDownloadSaved by mutableIntStateOf(0)
        private set

    var playlistDownloadSkipped by mutableIntStateOf(0)
        private set

    var playlistDownloadFailed by mutableIntStateOf(0)
        private set

    private var playlistDownloadJob: Job? = null

    /**
     * 一键下载当前歌单的**全部**曲目。
     *
     * ## 和 [maybeDownload] 的区别
     *
     * 那个是"播放时顺手存一份"，受 [onlineDownloadEnabled] 开关和 8 秒冷静期约束；
     * 这个是用户**明确点出来**的动作，所以不看那个开关 —— 但
     * [onlineDownloadWifiOnly] 仍然生效（用户设的流量红线，手动下载也得守）。
     *
     * ## 串行 + 可取消
     *
     * 一首接一首地下，不并发：并发会和播放抢带宽，网易云对外链请求也不友好。
     * 可播的、没存过的才下；VIP / 下架的跳过并计数，不报错（它们本来也播不了）。
     */
    fun downloadPlaylistTracks() {
        val src = onlineSource ?: return
        if (playlistDownloadActive) return
        val tracks = playlistTracks
        if (tracks.isEmpty()) return

        playlistDownloadJob = viewModelScope.launch {
            val app = getApplication<Application>()
            // 只在 Wi-Fi 下下载：判断不出来时跳过，和 [maybeDownload] 同一套保守策略
            if (onlineDownloadWifiOnly && !OnlineDownloader.isOnUnmeteredNetwork(app)) {
                showToast("当前不是 Wi-Fi，已按「只在 Wi-Fi 下载」跳过")
                return@launch
            }
            val targets = tracks.filter { it.playable }
            playlistDownloadTotal = targets.size
            playlistDownloadDone = 0
            playlistDownloadSaved = 0
            playlistDownloadSkipped = tracks.size - targets.size
            playlistDownloadFailed = 0
            playlistDownloadActive = true
            // 记下"这轮属于哪个歌单"。取的是发起时的那个，之后用户翻到别的歌单也不受影响
            playlistDownloadOwner = playlistDetail?.remoteId
            try {
                for (track in targets) {
                    if (!isActive) break
                    val key = OnlineDownloader.downloadKeyOf(src.id, track.remoteId)
                    if (key != null && key in prefs.downloadedKeys()) {
                        playlistDownloadSkipped++
                        playlistDownloadDone++
                        continue
                    }
                    val outcome = OnlineDownloader.download(
                        context = app,
                        source = src.id,
                        remoteId = track.remoteId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        streamUrl = src.streamUrl(track.remoteId),
                        coverBytes = coverBytesForTag(track.title, track.artist, track.coverUrl)
                    )
                    when (outcome) {
                        is OnlineDownloader.Outcome.Saved -> {
                            key?.let { prefs.markDownloaded(it) }
                            downloadedCount = prefs.downloadedKeys().size
                            playlistDownloadSaved++
                        }
                        OnlineDownloader.Outcome.Unavailable -> playlistDownloadSkipped++
                        OnlineDownloader.Outcome.Failed -> playlistDownloadFailed++
                        OnlineDownloader.Outcome.Skipped -> playlistDownloadSkipped++
                    }
                    playlistDownloadDone++
                }
            } finally {
                playlistDownloadActive = false
                playlistDownloadOwner = null
                // 有东西落盘才重扫 —— 全跳过时扫一次纯属浪费
                if (playlistDownloadSaved > 0) rescanAfterDownload()
                val tail = buildString {
                    if (playlistDownloadSkipped > 0) append(" · 跳过 ${playlistDownloadSkipped}")
                    if (playlistDownloadFailed > 0) append(" · 失败 ${playlistDownloadFailed}")
                }
                showToast(
                    if (!isActive) "已取消 · 新存 ${playlistDownloadSaved} 首$tail"
                    else "歌单下载完成 · 新存 ${playlistDownloadSaved} 首$tail"
                )
            }
        }
    }

    /** 取消正在跑的歌单下载。进度状态由协程的 `finally` 收尾 */
    fun cancelPlaylistDownload() {
        playlistDownloadJob?.cancel()
        playlistDownloadJob = null
    }

    // ===== 派生数据 =====

    /**
     * 拼音索引：songId → "全拼 首字母"。
     *
     * 扫描/缓存加载完成后在后台线程构建，搜索时只做字符串 contains，不阻塞输入。
     * 索引没就绪时为空 Map，搜索自然退化为「原名匹配」。
     */
    private var pinyinIndex by mutableStateOf<Map<Long, String>>(emptyMap())

    /**
     * 四个文本字段的归一化排序键：songId → [IndexKeys]。
     *
     * 列表排序和右侧字母分组**都读它**，两边必然同源。索引没就绪时为空：
     * 排序退回原来的码点序，右侧退回滑块，不会出现「字母表说在 Z、列表却不是」。
     */
    var sortKeys by mutableStateOf<Map<Long, IndexKeys>>(emptyMap())
        private set

    private fun rebuildPinyinIndex(list: List<Song>) {
        viewModelScope.launch {
            // 搜索用的拼音索引和排序 / 分组用的归一化键一次扫描同时产出，
            // 都跑在后台线程：搜索输入时只是查 Map，不再做 ICU 转写。
            val (pinyin, keys) = withContext(Dispatchers.Default) {
                val p = list.associate {
                    it.id to PinyinIndex.build("${it.title} ${it.artist} ${it.album}")
                }
                val k = list.associate { it.id to IndexKey.keysOf(it) }
                p to k
            }
            pinyinIndex = pinyin
            sortKeys = keys
        }
    }

    fun visibleSongs(): List<Song> {
        val searched = MediaRepository.search(songs, searchQuery, pinyinIndex)
        return MediaRepository.sortSongs(searched, sortField, sortAscending, songStats(), sortKeys)
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
        MediaRepository.sortSongs(songs, sortField, sortAscending, songStats(), sortKeys)

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
        val searched = MediaRepository.search(
            songs.filter { favorites.contains(it.id) },
            searchQuery,
            pinyinIndex
        )
        return MediaRepository.sortSongs(searched, sortField, sortAscending, songStats(), sortKeys)
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

    /** 当前 EQ 曲线是否来自音效预设，而不是下方的独立曲风预设。 */
    var eqFromSoundPreset by mutableStateOf(false)
        private set

    val eqCenterFreqs: IntArray get() = EQ_CENTER_FREQS
    val eqMinDb: Float get() = EQ_MIN_DB
    val eqMaxDb: Float get() = EQ_MAX_DB

    // 单独一个 init 块：必须放在上面几个属性**之后**。
    // Kotlin 的初始化按源码顺序执行，写进前面那个 init 块会被这里的
    // `by mutableStateOf(...)` 初值覆盖掉。
    init {
        val storedEq = prefs.eqPreset
        val preset = EqPreset.fromName(storedEq)
        val custom = parseEqBands(prefs.eqCustomBands)
        var soundEq = SoundPresets.eqPresetFromStore(storedEq)
        // 旧版本选音效时没有保存 EQ 的来源。只迁移没有独立曲风和手调曲线的
        // 明确场景；有歧义时保留用户原有的 EQ，不覆盖已有成果。
        if (soundEq == null && storedEq == EqPreset.OFF.name && custom == null &&
            prefs.eqEnabled && prefs.soundFxEnabled
        ) {
            val oldSound = SoundPresets.fromId(prefs.soundPresetId)
            if (oldSound.id != SoundPresets.OFF.id) {
                soundEq = oldSound
                prefs.eqPreset = SoundPresets.eqStoreName(oldSound)
            }
        }
        eqPreset = preset
        eqCustom = custom != null
        eqFromSoundPreset = soundEq != null
        eqBands = custom ?: soundEq?.eqDb?.copyOf() ?: EqPreset.curveOf(preset)
        // 自定义值优先；独立的 EQ 开关也要按用户上次选择恢复。
        eqEnabled = prefs.eqEnabled
        pushEq()
        // 卡拉OK 与播放速度：进程重建后同样要和播放器/音频链路重新对齐
        KaraokeBus.enabled = prefs.karaokeEnabled
        player.setSpeed(prefs.playSpeed)
    }

    /**
     * 打开均衡器面板时调用。
     *
     * 软件均衡器没有"挂载失败"这回事，这里只把状态再推一遍 ——
     * 防的是进程被回收后重建 ViewModel 时总线与界面不同步。
     */
    fun openEqualizer() {
        pushEq()
        pushSoundFx()
    }

    fun toggleEq(on: Boolean) {
        resumeSoundAfterComparison()
        eqEnabled = on
        prefs.eqEnabled = on
        pushEq()
        showToast(if (on) "均衡器已开启" else "均衡器已关闭")
    }

    fun chooseEqPreset(preset: EqPreset) {
        resumeSoundAfterComparison()
        eqPreset = preset
        eqCustom = false
        eqFromSoundPreset = false
        eqBands = EqPreset.curveOf(preset)
        eqEnabled = preset != EqPreset.OFF
        prefs.eqPreset = preset.name
        prefs.eqCustomBands = ""
        prefs.eqEnabled = eqEnabled
        pushEq()
        showToast("均衡器 · ${preset.label}")
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
        resumeSoundAfterComparison()
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
        resumeSoundAfterComparison()
        eqBands = FloatArray(EQ_BAND_COUNT)
        eqPreset = EqPreset.OFF
        eqCustom = false
        eqFromSoundPreset = false
        prefs.eqPreset = EqPreset.OFF.name
        prefs.eqCustomBands = ""
        pushEq()
        showToast("已复位为平直")
    }

    private fun pushEq() {
        EqBus.update(eqEnabled, eqBands)
    }

    // ===== 音效预设 =====
    //
    // 一套音效 = 一条 EQ 曲线 + 一组效果参数。选预设时两者一起写；
    // 预设曲线就是下方的 10 段 EQ，用户可继续微调或替换成独立曲风；
    // 额外效果与当前 EQ 一起生效，不会重复叠加两条 EQ 曲线。

    var soundPreset by mutableStateOf(SoundPresets.OFF)
        private set

    var soundFxParams by mutableStateOf(SoundFxParams.NEUTRAL)
        private set

    var soundFxEnabled by mutableStateOf(false)
        private set

    /** 用户手动改过效果强度：UI 显示「已微调」 */
    var soundFxCustom by mutableStateOf(false)
        private set

    /** 临时 A/B 比较，不落盘，关闭面板时恢复。 */
    var soundFxBypassed by mutableStateOf(false)
        private set

    // 又一个独立 init：必须放在上面几个属性之后，否则会被初值覆盖（和 EQ 那段同理）。
    init {
        soundPreset = SoundPresets.fromId(prefs.soundPresetId)
        val restored = SoundPresets.restoreFx(soundPreset, prefs.soundFxParams)
        soundFxParams = restored.params
        soundFxCustom = restored.custom
        soundFxEnabled = prefs.soundFxEnabled
        if (!restored.custom) prefs.soundFxParams = SoundFxParams.toStore(restored.params)
        pushSoundFx()
    }

    /** 选中一套音效：同时写入 EQ 曲线与效果参数 */
    fun chooseSoundPreset(preset: SoundPreset) {
        resumeSoundAfterComparison()
        soundPreset = preset
        soundFxParams = preset.fx.clamp()
        soundFxEnabled = preset.id != SoundPresets.OFF.id
        soundFxCustom = false

        eqBands = preset.eqDb.copyOf()
        eqPreset = EqPreset.OFF
        eqCustom = false
        eqFromSoundPreset = true
        eqEnabled = soundFxEnabled

        prefs.eqEnabled = eqEnabled
        prefs.eqPreset = SoundPresets.eqStoreName(preset)
        prefs.eqCustomBands = ""
        prefs.soundPresetId = preset.id
        prefs.soundFxParams = SoundFxParams.toStore(soundFxParams)
        prefs.soundFxEnabled = soundFxEnabled
        pushEq()
        pushSoundFx()
        showToast(if (soundFxEnabled) "音效 · ${preset.name}" else "已切回原声")
    }

    fun toggleSoundFx(on: Boolean) {
        resumeSoundAfterComparison()
        if (on && soundPreset.id == SoundPresets.OFF.id && soundFxParams.isNeutral) {
            chooseSoundPreset(SoundPresets.CLEAR)
            return
        }
        soundFxEnabled = on
        prefs.soundFxEnabled = on
        // 只联动预设自己的 EQ；用户另选的曲风或自定义 EQ 不受此开关影响。
        if (eqFromSoundPreset && soundPreset.id != SoundPresets.OFF.id) {
            eqEnabled = on
            prefs.eqEnabled = on
            pushEq()
        }
        pushSoundFx()
    }

    /** 微调某个效果强度；改过之后自动打开音效并标记「已微调」 */
    fun updateSoundFx(next: SoundFxParams) {
        resumeSoundAfterComparison()
        val wasEnabled = soundFxEnabled
        soundFxParams = next.clamp()
        soundFxEnabled = true
        soundFxCustom = soundFxParams != soundPreset.fx
        prefs.soundFxParams = SoundFxParams.toStore(soundFxParams)
        prefs.soundFxEnabled = true
        if (!wasEnabled && eqFromSoundPreset && soundPreset.id != SoundPresets.OFF.id) {
            eqEnabled = true
            prefs.eqEnabled = true
            pushEq()
        }
        pushSoundFx()
    }

    /** A/B 试听同时旁通 EQ 与音效；伴奏模式不变。 */
    fun setSoundFxBypass(value: Boolean) {
        soundFxBypassed = value
        SoundFxBus.setBypass(value)
    }

    private fun resumeSoundAfterComparison() {
        if (soundFxBypassed) setSoundFxBypass(false)
    }

    private fun pushSoundFx() {
        SoundFxBus.update(soundFxEnabled, soundFxParams)
    }

    // ===== 人声/伴奏（卡拉OK）=====

    /** true = 伴奏模式（中置消除），false = 原唱直通 */
    var karaokeEnabled by mutableStateOf(prefs.karaokeEnabled)
        private set

    fun toggleKaraoke() = setKaraoke(!karaokeEnabled)

    fun setKaraoke(value: Boolean) {
        karaokeEnabled = value
        prefs.karaokeEnabled = value
        KaraokeBus.enabled = value
        showToast(if (value) "伴奏模式（人声消除，效果因歌而异）" else "已切回原唱")
    }

    // ===== 播放速度 =====

    /** 播放速度（0.5–2.5 倍），跨歌、跨进程保持 */
    var playbackSpeed by mutableFloatStateOf(prefs.playSpeed)
        private set

    fun changePlaybackSpeed(value: Float) {
        playbackSpeed = value.coerceIn(0.5f, 2.5f)
        prefs.playSpeed = playbackSpeed
        player.setSpeed(playbackSpeed)
    }

    // ===== 全屏歌词页 =====

    /** 全屏歌词页是否隐藏系统状态栏（只作用于歌词页） */
    var lyricsHideStatusBar by mutableStateOf(prefs.lyricsHideStatusBar)
        private set

    fun changeLyricsHideStatusBar(value: Boolean) {
        lyricsHideStatusBar = value
        prefs.lyricsHideStatusBar = value
    }

    /** 律动柱页是否隐藏系统状态栏（只作用于律动柱页，与歌词页互不影响） */
    var visualizerHideStatusBar by mutableStateOf(prefs.visualizerHideStatusBar)
        private set

    fun changeVisualizerHideStatusBar(value: Boolean) {
        visualizerHideStatusBar = value
        prefs.visualizerHideStatusBar = value
    }

    /** 解析持久化的自定义曲线；长度不对或含脏数据都返回 null（回落到预设） */
    private fun parseEqBands(raw: String): FloatArray? {
        if (raw.isBlank()) return null
        val parts = raw.split(',')
        if (parts.size != EQ_BAND_COUNT) return null
        val out = FloatArray(EQ_BAND_COUNT)
        var i = 0
        while (i < EQ_BAND_COUNT) {
            val v = parts[i].trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
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
     */
    fun songMap(): Map<Long, Song> {
        val base = songs.associateBy { it.id }
        // 队列里可能还有被「清理重复」隐藏、或扫描后暂时不在 songs 里的曲目。
        // 这里把上一次扫描的全量结果并进来，保证 queueIds 都能解析到对象，
        // 否则分页器页号会和播放器索引错位（切歌后信息停在上一首的根因之一）。
        val all = lastScanOutcome?.songs?.associateBy { it.id }.orEmpty()
        val merged = if (all.isEmpty()) base else all + base
        if (onlineSongMap.isEmpty()) return merged
        return merged + onlineSongMap
    }

    // ===== 随机播放 =====

    /**
     * 开启随机前的原始队列顺序，关闭随机时用来还原。
     * 只保存一份 —— 每次「开」的时候覆盖，「关」的时候消费掉。
     */
    private var preShuffleOrder: List<Song>? = null

    /** 当前播放队列（按播放器里的顺序还原成 Song 列表） */
    private fun currentQueue(): List<Song> {
        val map = songMap()
        return player.state.value.queueIds.mapNotNull { map[it] }
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
            val q = currentQueue()
            if (q.size > 1) {
                preShuffleOrder = q
                val idx = st.queueIndex.coerceIn(0, q.lastIndex)
                val shuffled = ShuffleOrder.build(q, idx, recentWindowFor(q.size))
                player.setShuffle(true)
                // 随机必须能无限往下切：repeat 设成 ALL，避免从「单曲循环」切过来时
                // 随机开着却一直重复当前首。
                player.setRepeat(RepeatMode.ALL)
                persistPlayMode(PlayMode.SHUFFLE)
                // 无缝重排队列：不重建当前 MediaPeriod，不触发音频停顿或重缓冲。
                player.reorderQueueKeepingCurrent(shuffled, idx)
                if (!quiet) showToast("随机播放已开启")
                return
            }
        } else {
            val orig = preShuffleOrder
            if (orig != null) {
                val idx = orig.indexOfFirst { it.id == st.mediaId }.coerceAtLeast(0)
                player.setShuffle(false)
                persistPlayMode(PlayMode.of(shuffle = false, repeat = st.repeat))
                player.reorderQueueKeepingCurrent(orig, idx)
                preShuffleOrder = null
                if (!quiet) showToast("随机播放已关闭")
                return
            }
        }
        // 队列只有一首 / 没有记录过原顺序：只切标志
        val on = !st.shuffle
        player.setShuffle(on)
        if (on) player.setRepeat(RepeatMode.ALL)
        persistPlayMode(PlayMode.of(on, if (on) RepeatMode.ALL else st.repeat))
    }

    /** 读取上次保存的播放模式；值损坏时退回列表循环（也是 UI 的首个图标） */
    private fun persistedPlayMode(): PlayMode =
        PlayMode.entries.firstOrNull { it.name == prefs.playMode } ?: PlayMode.REPEAT_ALL

    private fun persistPlayMode(mode: PlayMode) {
        prefs.playMode = mode.name
        com.sonance.player.widget.SonanceWidgets.onFavoriteOrModeChanged(getApplication())
    }

    /**
     * 把持久化的播放模式应用到播放器。
     *
     * 随机模式的队列已经是上次保存的洗牌序（`playQueueIds` 存的就是队列本身），
     * 所以这里只需要打开 App 侧的随机开关；播放器的 `shuffleModeEnabled` 始终关闭
     * （理由见 [PlayerController.setShuffle]）。
     */
    private fun applyPersistedPlayMode() {
        when (persistedPlayMode()) {
            PlayMode.SHUFFLE -> {
                player.setShuffle(true)
                player.setRepeat(RepeatMode.ALL)
            }
            PlayMode.REPEAT_ALL -> {
                player.setShuffle(false)
                player.setRepeat(RepeatMode.ALL)
            }
            PlayMode.REPEAT_ONE -> {
                player.setShuffle(false)
                player.setRepeat(RepeatMode.ONE)
            }
        }
    }

    /**
     * 洗牌时要避开的「最近听过」。
     *
     * 窗口取队列长度的一半（6~60 之间）：太小挡不住刚放过的那几首，
     * 太大则一轮里「没听过」的歌太少，反而听不出新鲜感。
     */
    private fun recentWindowFor(queueSize: Int): Set<Long> {
        if (recentIds.isEmpty()) return emptySet()
        val window = (queueSize / 2).coerceIn(6, 60)
        return recentIds.take(window).toHashSet()
    }

    /**
     * 三态播放模式：**随机 → 列表循环 → 单曲循环 → 随机**。
     *
     * 播放页那个按钮点一下就调它。
     *
     * @param quiet 不弹提示。沉浸黑胶页的模式按钮也走这里（onPlayModeRequested 桥），
     *   页面自己有风格化 toast，Android 原生 toast 再弹一条就重复了，传 true 抑制。
     * @param list 还没在播时用这一页的歌定模式开播（黑胶页只切模式不传）。
     *
     * 底层要同时动两个标志（`shuffleModeEnabled` + `repeatMode`），
     * 这里按目标状态一次性设好，而不是"先关一个再开另一个" ——
     * 中间态会短暂出现"随机 + 单曲循环"这种矛盾组合，播放器可能刚好在那一瞬
     * 决定下一首放什么。
     */
    fun cyclePlayMode(list: List<Song> = emptyList(), quiet: Boolean = false) {
        val st = player.state.value

        // 还没在播 → 先定模式，再用这一页的歌开播。
        //
        // 原来的行为是"点一下就重排整条队列"，哪怕什么都没在播；
        // 而列表表头那个位置，用户的预期是"从这里放起来"。
        // 所以没在播时按目标模式把队列排好再从第一首开始。
        if (!st.hasItem && list.isNotEmpty()) {
            val next = PlayMode.of(st.shuffle, st.repeat).next()
            val shuffled = next == PlayMode.SHUFFLE
            // 走 [ShuffleOrder] 而不是 [List.shuffled]：后者一轮之内会重复，
            // 和播放页那个模式按钮必须是同一套排序
            val ordered = if (shuffled) {
                ShuffleOrder.build(list, 0, recentWindowFor(list.size))
            } else {
                list
            }
            preShuffleOrder = if (shuffled) list else null
            player.play(ordered, 0)
            player.setShuffle(shuffled)
            player.setRepeat(if (next == PlayMode.REPEAT_ONE) RepeatMode.ONE else RepeatMode.ALL)
            persistPlayMode(next)
            if (!quiet) showToast(next.label)
            return
        }

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
        persistPlayMode(next)
        if (!quiet) showToast(next.label)
    }

    // ===== 播放入口 =====

    fun playFrom(list: List<Song>, song: Song) {
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        // 随机开着的时候，点一首歌也要按"洗牌袋"来排 ——
        // 否则队列回到曲库顺序，而模式按钮还亮着"随机"，两边说的不是一回事。
        val on = player.state.value.shuffle
        val ordered = if (on) ShuffleOrder.build(list, idx, recentWindowFor(list.size)) else list
        // 换了播放列表，之前记的原顺序就换成这一份（随机开着时它才是"还原"的基准）
        preShuffleOrder = if (on) list else null
        player.play(ordered, idx)
        showToast("正在播放 · ${song.title}")
    }

    fun playAll(list: List<Song>, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        // 同上：只要最终是随机序，就用洗牌袋而不是 `shuffled()`（后者会重复）
        val on = shuffled || player.state.value.shuffle
        val ordered = if (on) ShuffleOrder.build(list, 0, recentWindowFor(list.size)) else list
        preShuffleOrder = if (on) list else null
        player.play(ordered, 0)
        showToast(if (on) "随机播放 ${list.size} 首" else "播放全部 ${list.size} 首")
    }

    fun playNext(song: Song) {
        player.playNext(song)
        showToast("已添加到下一首播放")
    }

    override fun onCleared() {
        com.sonance.player.widget.WidgetCommandReceiver.onToggleFavorite = null
        com.sonance.player.widget.WidgetCommandReceiver.onCyclePlayMode = null
        player.release()
        super.onCleared()
    }

    // 供 UI 计算总时长
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
    val totalSizeBytes: Long get() = songs.sumOf { it.sizeBytes }

    companion object {
        /** 「关于」里点多少下署名才解锁私有格式还原 */
        private const val AUTHOR_TAP_TARGET = 10

        /** 解锁后保持多久（一小时），到点自动回到隐藏状态 */
        private const val CONVERSION_UNLOCK_MS = 60L * 60L * 1000L

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
         * 首页推荐歌单拉多少个。
         *
         * 12 个够横向滑两屏，也够凑满一屏的网格。每个都要下缩略图，
         * 再多会让首页首屏等太久。
         */
        private const val RECOMMEND_LIMIT = 12

        /**
         * 开始播放后**多久**才真正触发下载。
         *
         * 用来把"跳着听"和"真在听"区分开：用户翻歌单时每首都只播两三秒，
         * 等够 8 秒说明他确实在听这一首。太短会把翻过的歌全存下来，太长则要等半天。
         */
        private const val DOWNLOAD_ARM_DELAY_MS = 8_000L
    }
}
