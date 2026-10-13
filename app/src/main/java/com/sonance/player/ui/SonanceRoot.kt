package com.sonance.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonance.player.ScanState
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.LibraryTab
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.AppBottomBar
import com.sonance.player.ui.components.AppTopBar
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.FullPlayer
import com.sonance.player.ui.components.IconAction
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.MiniPlayer
import com.sonance.player.ui.components.NowPlayingPanel
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.PlayerBar
import com.sonance.player.ui.components.SideNavigation
import com.sonance.player.ui.components.ToastHost
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.screens.AlbumsScreen
import com.sonance.player.ui.screens.ArtistsScreen
import com.sonance.player.ui.screens.FavoritesScreen
import com.sonance.player.ui.screens.FoldersScreen
import com.sonance.player.ui.screens.OnlinePlaylistScreen
import com.sonance.player.ui.screens.OnlineScreen
import com.sonance.player.ui.screens.RecentScreen
import com.sonance.player.ui.screens.SettingsScreen
import com.sonance.player.ui.screens.SongCollectionScreen
import com.sonance.player.ui.screens.SongsScreen
import com.sonance.player.ui.sheets.EqualizerSheet
import com.sonance.player.ui.sheets.QueueSheet
import com.sonance.player.ui.sheets.SleepTimerSheet
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.SonanceTheme
import com.sonance.player.ui.components.CoverPickerDialog

/* ============================================================
 * 自适应断点
 * ============================================================ */

enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

private fun resolveLayout(widthDp: Int, heightDp: Int): LayoutMode = when {
    widthDp < 600 -> LayoutMode.COMPACT
    widthDp < 960 -> LayoutMode.MEDIUM
    else -> LayoutMode.EXPANDED
}

sealed interface Detail {
    data class AlbumDetail(val albumId: Long) : Detail
    data class ArtistDetail(val name: String) : Detail
    data class FolderDetail(val path: String) : Detail

    /**
     * 在线歌单详情。
     *
     * 直接带上 [RemotePlaylist] 本身，而不是一个 id：详情页的标题 / 封面 / 曲目数
     * 都要用它，而歌单**不在任何本地查找表里**（不像专辑 / 艺术家能按 id 反查）。
     */
    data class PlaylistDetail(val playlist: RemotePlaylist) : Detail
}

/* ============================================================
 * 根
 * ============================================================ */

@Composable
fun SonanceRoot(vm: SonanceViewModel) {
    SonanceTheme(vm.skin) {
        val playerState by vm.player.state.collectAsStateWithLifecycle()
        PermissionGate(vm) {
            Shell(vm, playerState)
        }
    }
}

/* ============================================================
 * 权限门
 * ============================================================ */

@Composable
private fun PermissionGate(vm: SonanceViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) {
            vm.scan()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    LaunchedEffect(granted) {
        if (granted && vm.scanState == ScanState.IDLE) vm.scan()
    }

    if (granted) {
        content()
    } else {
        PermissionScreen(
            onGrant = { mediaLauncher.launch(permission) },
            onRescan = {
                granted = ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        )
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onRescan: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .fillMaxSize()
            .background(skin.bg)
            .systemBarsPadding()
    ) {
        EmptyState(
            icon = SonanceIcons.Music,
            title = "Sonance 需要读取本地音频",
            subtitle = "授权后即可扫描设备上的音乐文件。\n所有数据都留在本机，不会联网上传。",
            actionLabel = "授予权限",
            onAction = onGrant
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PillButton(label = "已授权？点此重新检测", onClick = onRescan, filled = false)
        }
    }
}

/* ============================================================
 * 封面写回文件的授权
 * ============================================================ */

/**
 * 把封面写进歌曲文件时，如果系统要求用户确认，就把那个确认框拉起来。
 *
 * 两个 launcher 对应两代权限模型：
 * - API 29+：系统给一个 `IntentSender`，走「允许修改此文件？」（`StartIntentSenderForResult`）
 * - API 28 及以下：没有 `IntentSender`，只能先要 `WRITE_EXTERNAL_STORAGE`
 *
 * ViewModel 拿不到 Activity，所以这件事只能由界面来做 ——
 * ViewModel 那边只负责把「待授权的请求」放进 [SonanceViewModel.pendingCoverWrite]。
 */
@Composable
private fun CoverWritePermissionGate(vm: SonanceViewModel) {
    val pending = vm.pendingCoverWrite

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onCoverWritePermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onCoverWritePermissionResult(granted) }

    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        val sender = request.intentSender
        if (sender != null) {
            val launched = runCatching {
                writeLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }.isSuccess
            // 拉不起来（IntentSender 已失效等）也要收尾，否则 pendingCoverWrite
            // 会一直挂着，用户下次换封面时又弹一次
            if (!launched) vm.onCoverWritePermissionResult(false)
        } else {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

/* ============================================================
 * 自适应外壳
 * ============================================================ */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shell(vm: SonanceViewModel, playerState: PlayerUiState) {
    val skin = LocalSkin.current
    val configuration = LocalConfiguration.current
    val layout = resolveLayout(configuration.screenWidthDp, configuration.screenHeightDp)

    var fullPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // 均衡器 / 睡眠定时器面板。不用 rememberSaveable：这两个是"当场调完就走"的
    // 临时面板，旋转屏幕后自动收起比留着一个半开的 sheet 更自然。
    var eqOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(null) }

    /**
     * 收掉输入法焦点。
     *
     * ## 为什么必须显式清，而不是靠层级盖住
     *
     * 输入法的**光标手柄**（那个蓝色水滴）是系统级 `PopupWindow`，
     * 挂在 Window 上、**不在 Compose 的层级里** —— 播放页用 `zIndex(20f)` 盖上来
     * 也压不住它，于是搜索框的光标会飘在播放页封面上面（用户截图报的就是这个）。
     *
     * Compose 里没有任何"层级"手段能治它，唯一的办法是让输入框**失去焦点**，
     * 手柄自己就会收起来。
     *
     * 顺带也把键盘收掉 —— 切页面时键盘还杵在那里同样是这类问题。
     */
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissInput: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    // 切 tab / 进详情页时也收一下：这两种操作都会把当前页面换掉，
    // 留着输入焦点就会出现"键盘浮在另一个页面上"
    LaunchedEffect(vm.tab) { dismissInput() }
    LaunchedEffect(detail) { dismissInput() }

    BackHandler(enabled = fullPlayerOpen) { fullPlayerOpen = false }
    BackHandler(enabled = !fullPlayerOpen && detail != null) { detail = null }
    // 设置页按返回 → 回主页面（「全部歌曲」），而不是直接退出应用。
    //
    // 必须注册在上面两个之后：BackHandler 是「后注册且 enabled 的优先」，
    // 放在前面会被全屏播放页 / 详情页抢走。另外这里刻意要求 detail == null ——
    // 详情页的返回优先级要高于设置页。
    BackHandler(
        enabled = !fullPlayerOpen && detail == null && vm.tab == LibraryTab.SETTINGS
    ) {
        vm.selectTab(LibraryTab.SONGS)
    }

    val dense = configuration.screenHeightDp < 520

    // onlineRevision 是必须的 key：在线歌不在 vm.songs 里，搜一次之后
    // 队列页 / 播放页要按 id 反查它们，映射必须重建。
    val songMap = remember(vm.songs, vm.onlineRevision) { vm.songMap() }
    val queue = remember(playerState.queueIds, songMap) {
        playerState.queueIds.mapNotNull { songMap[it] }
    }
    val currentSong = songMap[playerState.mediaId]

    fun openAlbum(albumId: Long) {
        detail = Detail.AlbumDetail(albumId)
        fullPlayerOpen = false
    }

    // 播放页底栏、设置页的"调节"按钮都汇到这里，避免同时渲染两个面板
    val openEqualizer: () -> Unit = {
        vm.openEqualizer()
        eqOpen = true
    }

    CompositionLocalProvider(LocalDenseLayout provides dense) {
        Box(Modifier.fillMaxSize().background(skin.bg)) {

        when (layout) {
            LayoutMode.COMPACT -> CompactLayout(
                vm = vm,
                playerState = playerState,
                detail = detail,
                currentSongTitle = currentSong?.title,
                onOpenDetail = { detail = it },
                onCloseDetail = { detail = null },
                // 打开播放页前先收掉输入法焦点 —— 否则搜索框的光标手柄会飘在播放页上
                onOpenFullPlayer = { dismissInput(); fullPlayerOpen = true },
                onOpenQueue = { queueOpen = true },
                onOpenEqualizer = openEqualizer
            )

            LayoutMode.MEDIUM -> MediumLayout(
                vm = vm,
                playerState = playerState,
                detail = detail,
                onOpenDetail = { detail = it },
                onCloseDetail = { detail = null },
                // 打开播放页前先收掉输入法焦点 —— 否则搜索框的光标手柄会飘在播放页上
                onOpenFullPlayer = { dismissInput(); fullPlayerOpen = true },
                onOpenQueue = { queueOpen = true },
                onOpenEqualizer = openEqualizer
            )

            LayoutMode.EXPANDED -> ExpandedLayout(
                vm = vm,
                playerState = playerState,
                detail = detail,
                onOpenDetail = { detail = it },
                onCloseDetail = { detail = null },
                // 打开播放页前先收掉输入法焦点 —— 否则搜索框的光标手柄会飘在播放页上
                onOpenFullPlayer = { dismissInput(); fullPlayerOpen = true },
                onOpenQueue = { queueOpen = true },
                onOpenEqualizer = openEqualizer
            )
        }

        // ===== 全屏播放页 =====
        AnimatedVisibility(
            visible = fullPlayerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
        ) {
            FullPlayer(
                state = playerState,
                queue = queue,
                favorites = vm.favorites,
                onClose = {
                    fullPlayerOpen = false
                    // 收起播放页时把正在播放的那首滚进可视区
                    vm.requestScrollToCurrent()
                },
                onPlayAt = { vm.player.playAt(it) },
                onToggleFavorite = { vm.toggleFavorite(it) },
                onCyclePlayMode = { vm.cyclePlayMode() },
                onPrev = { vm.player.previous() },
                onPlayPause = { vm.player.togglePlay() },
                onNext = { vm.player.next() },
                onSeek = { vm.player.seekTo(it) },
                onVolume = { vm.player.setVolume(it) },
                onOpenQueue = { queueOpen = true },
                onOpenCover = { vm.openCoverPicker(playerState.mediaId) },
                onOpenEqualizer = openEqualizer,
                onOpenSleepTimer = { sleepOpen = true },
                sleepTimerActive = vm.sleepTimerLabel != null,
                eqEnabled = vm.eqEnabled,
                glowMode = vm.glowMode,
                glowStrength = vm.glowStrength,
                glowTransition = vm.glowTransition,
                glowFadeMs = vm.glowFadeMs,
                onGlowModeChange = { vm.changeGlowMode(it) },
                onGlowStrengthChange = { vm.changeGlowStrength(it) },
                onGlowTransitionChange = { vm.changeGlowTransition(it) },
                onGlowFadeMsChange = { vm.changeGlowFadeMs(it) }
            )
        }

        // ===== 轻提示 =====
        ToastHost(
            message = vm.toastMessage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp)
                .padding(bottom = if (layout == LayoutMode.EXPANDED) 104.dp else 150.dp)
                .zIndex(30f)
        )
    }
    }

    // 手动选封面（设置页和播放页的入口都汇到这里，避免同时渲染两个对话框）
    CoverPickerDialog(vm = vm)

    // 把封面写进原文件时，系统可能要用户点一下「允许修改此文件？」
    CoverWritePermissionGate(vm)

    if (queueOpen) {
        QueueSheet(
            queue = queue,
            state = playerState,
            onDismiss = { queueOpen = false },
            onPlayAt = { vm.player.playAt(it) },
            onRemoveAt = { vm.player.removeAt(it) },
            onShuffle = { vm.toggleShuffle() },
            onClear = {
                vm.player.clearQueue()
                queueOpen = false
            }
        )
    }

    if (eqOpen) {
        EqualizerSheet(vm = vm, onDismiss = { eqOpen = false })
    }

    if (sleepOpen) {
        SleepTimerSheet(vm = vm, onDismiss = { sleepOpen = false })
    }

    // 打开专辑详情时收起全屏播放页
    LaunchedEffect(detail) {
        if (detail != null) fullPlayerOpen = false
    }

    // 播放失败时明确告知原因，而不是静默跳过
    LaunchedEffect(playerState.errorMessage) {
        val message = playerState.errorMessage
        if (message != null) {
            vm.showToast(message)
            vm.player.clearError()
        }
    }
}

/* ============================================================
 * 手机布局：顶栏 + 内容 + 迷你播放条 + 底部导航
 * ============================================================ */

@Composable
private fun CompactLayout(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    detail: Detail?,
    currentSongTitle: String?,
    onOpenDetail: (Detail) -> Unit,
    onCloseDetail: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxSize()) {

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(skin.bg)
        ) {
            AppTopBar(
                title = detail?.let { detailTitle(vm, it) } ?: vm.tab.label,
                subtitle = detail?.let { detailSubtitle(vm, it) } ?: tabSubtitle(vm),
                // 列表页把统计并进标题行（省一整行）；详情页标题可能是长专辑名，
                // 挤一行会把标题截没，所以保持两行
                subtitleInline = detail == null,
                modifier = Modifier.statusBarsPadding(),
                onBack = detail?.let { { onCloseDetail() } },
                actions = {
                    // 「重新扫描」只对本地曲库有意义。在线页上摆一个会让人以为
                    // 它是"重新搜索" —— 而那是搜索框自己的事。
                    if (detail == null &&
                        vm.tab != LibraryTab.SETTINGS &&
                        vm.tab != LibraryTab.ONLINE
                    ) {
                        IconAction(
                            icon = SonanceIcons.Refresh,
                            contentDescription = "重新扫描",
                            onClick = { vm.scan() },
                            iconSize = 18.dp
                        )
                    }
                    // 设置入口：从底部栏搬到右上角齿轮
                    IconAction(
                        icon = SonanceIcons.Settings,
                        contentDescription = "皮肤与设置",
                        onClick = {
                            vm.selectTab(LibraryTab.SETTINGS)
                            onCloseDetail()
                        },
                        iconSize = 19.dp,
                        tint = if (detail == null && vm.tab == LibraryTab.SETTINGS) {
                            skin.accent
                        } else {
                            skin.textDim
                        }
                    )
                }
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ScreenBody(
                    vm = vm,
                    playerState = playerState,
                    detail = detail,
                    onOpenDetail = onOpenDetail,
                    onOpenEqualizer = onOpenEqualizer
                )
            }
        }

        if (playerState.hasItem) {
            MiniPlayer(
                state = playerState,
                song = null,
                onOpen = onOpenFullPlayer,
                onPlayPause = { vm.player.togglePlay() },
                onNext = { vm.player.next() },
                onPrev = { vm.player.previous() }
            )
        }

        AppBottomBar(
            selected = vm.tab,
            onSelect = {
                vm.selectTab(it)
                onCloseDetail()
            },
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

/* ============================================================
 * 中等宽度（小平板 / 手机横屏）：侧栏 + 内容 + 迷你播放条
 * ============================================================ */

@Composable
private fun MediumLayout(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    detail: Detail?,
    onOpenDetail: (Detail) -> Unit,
    onCloseDetail: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxSize()) {
        SideNavigation(
            selected = vm.tab,
            onSelect = {
                vm.selectTab(it)
                onCloseDetail()
            },
            expanded = false,
            songCount = vm.songs.size,
            favoriteCount = vm.favorites.size,
            storageText = "",
            storageFraction = 0f,
            modifier = Modifier.systemBarsPadding()
        )
        Row(Modifier.width(1.dp).fillMaxHeight().background(skin.border)) {}

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                AppTopBar(
                    title = detail?.let { detailTitle(vm, it) } ?: vm.tab.label,
                    subtitle = detail?.let { detailSubtitle(vm, it) } ?: tabSubtitle(vm),
                    // 列表页把统计并进标题行（省一整行）；详情页标题可能是长专辑名，
                    // 挤一行会把标题截没，所以保持两行
                    subtitleInline = detail == null,
                    modifier = Modifier.statusBarsPadding(),
                    onBack = detail?.let { { onCloseDetail() } },
                    actions = {
                        IconAction(
                            icon = SonanceIcons.Refresh,
                            contentDescription = "重新扫描",
                            onClick = { vm.scan() },
                            iconSize = 18.dp
                        )
                    }
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ScreenBody(
                        vm = vm,
                        playerState = playerState,
                        detail = detail,
                        onOpenDetail = onOpenDetail,
                        onOpenEqualizer = onOpenEqualizer
                    )
                }
            }

            if (playerState.hasItem) {
                MiniPlayer(
                    state = playerState,
                    song = null,
                    onOpen = onOpenFullPlayer,
                    onPlayPause = { vm.player.togglePlay() },
                    onNext = { vm.player.next() },
                    onPrev = { vm.player.previous() },
                    modifier = Modifier.navigationBarsPadding()
                )
            } else {
                Box(Modifier.navigationBarsPadding())
            }
        }
    }
}

/* ============================================================
 * 宽屏（平板横屏 / 桌面）：侧栏 + 内容 + 正在播放面板 + 底部播放条
 * ============================================================ */

@Composable
private fun ExpandedLayout(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    detail: Detail?,
    onOpenDetail: (Detail) -> Unit,
    onCloseDetail: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val skin = LocalSkin.current
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 1200

    Row(Modifier.fillMaxSize()) {
        SideNavigation(
            selected = vm.tab,
            onSelect = {
                vm.selectTab(it)
                onCloseDetail()
            },
            expanded = wide,
            songCount = vm.songs.size,
            favoriteCount = vm.favorites.size,
            storageText = "${formatSize(vm.totalSizeBytes)} · ${vm.songs.size} 首",
            storageFraction = runCatching {
                vm.totalSizeBytes.toFloat() / (24f * 1024f * 1024f * 1024f)
            }.getOrDefault(0f),
            modifier = Modifier.systemBarsPadding()
        )
        Row(Modifier.width(1.dp).fillMaxHeight().background(skin.border)) {}

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    AppTopBar(
                        title = detail?.let { detailTitle(vm, it) } ?: vm.tab.label,
                        subtitle = detail?.let { detailSubtitle(vm, it) } ?: tabSubtitle(vm),
                        // 列表页把统计并进标题行（省一整行）；详情页标题可能是长专辑名，
                        // 挤一行会把标题截没，所以保持两行
                        subtitleInline = detail == null,
                        onBack = detail?.let { { onCloseDetail() } },
                        actions = {
                            IconAction(
                                icon = SonanceIcons.Refresh,
                                contentDescription = "重新扫描",
                                onClick = { vm.scan() },
                                iconSize = 18.dp
                            )
                        }
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ScreenBody(
                            vm = vm,
                            playerState = playerState,
                            detail = detail,
                            onOpenDetail = onOpenDetail,
                            onOpenEqualizer = onOpenEqualizer
                        )
                    }
                }

                Row(Modifier.width(1.dp).fillMaxHeight().background(skin.border)) {}

                NowPlayingPanel(
                    state = playerState,
                    song = null,
                    onCyclePlayMode = { vm.cyclePlayMode() },
                    onPrev = { vm.player.previous() },
                    onPlayPause = { vm.player.togglePlay() },
                    onNext = { vm.player.next() },
                    onVolume = { vm.player.setVolume(it) },
                    onSeek = { vm.player.seekTo(it) },
                    onOpenQueue = onOpenQueue,
                    onFavorite = {
                        vm.songById(playerState.mediaId)?.let { vm.toggleFavorite(it.id) }
                    },
                    isFavorite = vm.favorites.contains(playerState.mediaId),
                    modifier = Modifier
                        .width(if (wide) 344.dp else 312.dp)
                        .fillMaxHeight()
                )
            }

            PlayerBar(
                state = playerState,
                song = null,
                onOpen = onOpenFullPlayer,
                onCyclePlayMode = { vm.cyclePlayMode() },
                onPrev = { vm.player.previous() },
                onPlayPause = { vm.player.togglePlay() },
                onNext = { vm.player.next() },
                onVolume = { vm.player.setVolume(it) },
                onSeek = { vm.player.seekTo(it) },
                onOpenQueue = onOpenQueue,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
}

/* ============================================================
 * 内容路由
 * ============================================================ */

@Composable
private fun ScreenBody(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    detail: Detail?,
    onOpenDetail: (Detail) -> Unit,
    /** 设置页里的"调节均衡器"要打开的是 Shell 层的面板，所以把回调透传下去 */
    onOpenEqualizer: () -> Unit
) {
    val skin = LocalSkin.current

    if (detail != null) {
        when (detail) {
            is Detail.AlbumDetail -> {
                val album = vm.albumById(detail.albumId)
                if (album == null) {
                    EmptyState(SonanceIcons.Album, "专辑不存在", "可能已被移除或重新扫描")
                } else {
                    SongCollectionScreen(
                        title = album.name,
                        subtitle = "${album.artist} · ${album.songs.size} 首 · ${formatDuration(album.durationMs)}",
                        songs = album.songs,
                        vm = vm,
                        playerState = playerState,
                        coverAlbumId = album.albumId
                    )
                }
            }

            is Detail.ArtistDetail -> {
                val artist = vm.artistByName(detail.name)
                if (artist == null) {
                    EmptyState(SonanceIcons.Users, "艺术家不存在", "可能已被移除或重新扫描")
                } else {
                    SongCollectionScreen(
                        title = artist.name,
                        subtitle = "${artist.songs.size} 首 · ${artist.albumCount} 张专辑 · ${
                            formatDuration(artist.songs.sumOf { it.durationMs })
                        }",
                        songs = artist.songs,
                        vm = vm,
                        playerState = playerState,
                        coverAlbumId = artist.songs.firstOrNull()?.albumId ?: -1L
                    )
                }
            }

            is Detail.FolderDetail -> {
                val folder = vm.folderByPath(detail.path)
                if (folder == null) {
                    EmptyState(SonanceIcons.Folder, "文件夹不存在", "可能已被移除或重新扫描")
                } else {
                    SongCollectionScreen(
                        title = folder.name,
                        subtitle = "${folder.songs.size} 首 · ${folder.path}",
                        songs = folder.songs,
                        vm = vm,
                        playerState = playerState,
                        showCover = false
                    )
                }
            }

            is Detail.PlaylistDetail -> OnlinePlaylistScreen(
                playlist = detail.playlist,
                vm = vm,
                playerState = playerState
            )
        }
        return
    }

    when (vm.tab) {
        LibraryTab.SONGS -> SongsScreen(
            vm = vm,
            playerState = playerState,
            onOpenFolder = { onOpenDetail(Detail.FolderDetail(it)) },
            onOpenAlbum = { onOpenDetail(Detail.AlbumDetail(it.albumId)) }
        )

        LibraryTab.ALBUMS -> AlbumsScreen(
            vm = vm,
            playerState = playerState,
            onOpenAlbum = { onOpenDetail(Detail.AlbumDetail(it.albumId)) }
        )

        LibraryTab.ARTISTS -> ArtistsScreen(
            vm = vm,
            onOpenArtist = { onOpenDetail(Detail.ArtistDetail(it)) }
        )

        LibraryTab.FOLDERS -> FoldersScreen(
            vm = vm,
            onOpenFolder = { onOpenDetail(Detail.FolderDetail(it)) }
        )

        LibraryTab.RECENT -> RecentScreen(vm = vm, playerState = playerState)

        LibraryTab.ONLINE -> OnlineScreen(
            vm = vm,
            playerState = playerState,
            onOpenPlaylist = { onOpenDetail(Detail.PlaylistDetail(it)) }
        )

        LibraryTab.FAVORITES -> FavoritesScreen(vm = vm, playerState = playerState)

        LibraryTab.SETTINGS -> SettingsScreen(
            vm = vm,
            onOpenEqualizer = onOpenEqualizer
        )
    }
}

/* ============================================================
 * 文案
 * ============================================================ */

private fun tabSubtitle(vm: SonanceViewModel): String = when (vm.tab) {
    LibraryTab.SONGS -> "共 ${vm.songs.size} 首 · ${formatDuration(vm.totalDurationMs)}"
    // 曲目数原来挂在 ListHeader 的统计行上，那行删掉之后并进这里，信息不丢
    LibraryTab.ALBUMS -> "共 ${vm.albums.size} 张专辑 · ${vm.songs.size} 首"
    LibraryTab.ARTISTS -> "共 ${vm.artists.size} 位艺术家"
    LibraryTab.FOLDERS -> "共 ${vm.folders.size} 个文件夹"
    // 用 recentEntries 而不是 recentIds —— 在线歌不在 recentIds 里，
    // 用它会让"最近听过 N 首"比列表里实际少几首
    LibraryTab.RECENT -> "最近听过 ${vm.recentEntries.size} 首"
    LibraryTab.ONLINE -> if (vm.onlineMusicEnabled) "搜索并播放网络歌曲" else "未开启 · 点击开启"
    LibraryTab.FAVORITES -> "已收藏 ${vm.favorites.size} 首"
    LibraryTab.SETTINGS -> vm.skin.name
}

private fun detailTitle(vm: SonanceViewModel, detail: Detail): String = when (detail) {
    is Detail.AlbumDetail -> vm.albumById(detail.albumId)?.name ?: "专辑"
    is Detail.ArtistDetail -> detail.name
    is Detail.FolderDetail -> vm.folderByPath(detail.path)?.name ?: "文件夹"
    is Detail.PlaylistDetail -> detail.playlist.name
}

private fun detailSubtitle(vm: SonanceViewModel, detail: Detail): String = when (detail) {
    is Detail.AlbumDetail -> vm.albumById(detail.albumId)?.let { "${it.artist} · ${it.songs.size} 首" }
        ?: ""

    is Detail.ArtistDetail -> vm.artistByName(detail.name)?.let { "${it.songs.size} 首" } ?: ""
    is Detail.FolderDetail -> vm.folderByPath(detail.path)?.path ?: ""

    is Detail.PlaylistDetail -> buildString {
        if (detail.playlist.creatorName.isNotBlank()) append("${detail.playlist.creatorName} · ")
        append("${detail.playlist.trackCount} 首")
    }
}
