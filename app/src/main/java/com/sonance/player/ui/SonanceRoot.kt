package com.sonance.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonance.player.ScanState
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.LibraryTab
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.Song
import com.sonance.player.data.SongMetaEditor
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.playback.BiliVideoSession
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.AppTopBar
import com.sonance.player.ui.components.ArtTransition
import com.sonance.player.ui.components.BiliFloatWindow
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.FullPlayer
import com.sonance.player.ui.components.GlassBackdrop
import com.sonance.player.ui.components.artBandColors
import com.sonance.player.ui.components.buildBgProfile
import com.sonance.player.ui.components.ensureReadable
import com.sonance.player.ui.components.equalizeScrimAlphas
import com.sonance.player.ui.components.IconAction
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.MiniPlayer
import com.sonance.player.ui.components.MenuRow
import com.sonance.player.ui.components.NowPlayingPanel
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.PlayerBar
import com.sonance.player.ui.components.PlayerLayer
import com.sonance.player.ui.components.SideNavigation
import com.sonance.player.ui.components.ToastHost
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.screens.AlbumsScreen
import com.sonance.player.ui.screens.ArtistsScreen
import com.sonance.player.ui.screens.BiliCacheScreen
import com.sonance.player.ui.screens.FavoritesScreen
import com.sonance.player.ui.screens.FoldersScreen
import com.sonance.player.ui.screens.LibraryPagerScreen
import com.sonance.player.ui.screens.OnlinePlaylistScreen
import com.sonance.player.ui.screens.OnlineScreen
import com.sonance.player.ui.screens.RecentScreen
import com.sonance.player.ui.screens.SettingsScreen
import com.sonance.player.ui.screens.SongCollectionScreen
import com.sonance.player.ui.screens.SongsScreen
import com.sonance.player.ui.screens.inPager
import com.sonance.player.ui.sheets.EqualizerSheet
import com.sonance.player.ui.sheets.QueueSheet
import com.sonance.player.ui.sheets.SleepTimerSheet
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.SonanceTheme
import com.sonance.player.ui.vinyl.VinylWebScreen
import com.sonance.player.ui.wall.NativeCoverWallScreen
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

/**
 * 按条件隐藏/恢复状态栏。
 *
 * 隐藏之后系统不再派发 `statusBars` insets，Compose 的 `statusBarsPadding()`
 * 会自动归零 —— 于是播放页顶栏、歌词页、律动柱那几处 padding 一起失效，
 * 内容自己顶到屏幕最上沿，**不需要改任何一处布局代码**。
 *
 * 只隐藏 statusBars，不动 navigationBars：横屏手势条还在，返回操作不会丢。
 * 用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，用户下拉仍能临时看到时间/通知。
 */
@Composable
private fun PlayerStatusBarImmersive(enabled: Boolean) {
    val view = LocalView.current

    fun apply(hide: Boolean) {
        val window = (view.context as? Activity)?.window ?: return
        val controller = WindowCompat.getInsetsController(window, view)
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    DisposableEffect(enabled) {
        apply(enabled)
        onDispose {
            // 组合销毁（退出页面 / Activity 结束）一定把状态栏还回去，
            // 否则整个应用此后都没有状态栏
            apply(false)
        }
    }

    // 切后台再回来时，系统可能已经把状态栏恢复了；重新对齐一次。
    // ON_STOP 主动恢复，避免把「隐藏」状态带到最近任务列表/其他界面。
    LifecycleEventEffect(Lifecycle.Event.ON_START) { apply(enabled) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { apply(false) }
}

sealed interface Detail {
    data class AlbumDetail(val albumId: Long) : Detail
    data class ArtistDetail(val name: String) : Detail
    data class FolderDetail(val path: String) : Detail

    /**
     * B 站离线缓存（平板布局专有）。
     *
     * 不用 LibraryTab 是有意的：它只存在于宽屏侧栏，如果做成 tab，`lastTab`
     * 就会把一个手机布局下不存在的值持久化下来，下次在手机上冷启动会落到空页面。
     */
    data object BiliCache : Detail

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
    // 进程级前后台：视频浮动/后台续播行为按「整个应用是否退到后台」判断，
    // 而不是单看某个 Activity（视频全屏页盖住主界面时应用仍是前台）
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(Unit) {
        val session = BiliVideoSession.of(appContext)
        val owner = androidx.lifecycle.ProcessLifecycleOwner.get()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> session.onAppBackground()
                androidx.lifecycle.Lifecycle.Event.ON_START -> session.onAppForeground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

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
        if (!granted) return@LaunchedEffect
        if (vm.scanState == ScanState.IDLE) {
            // 首次启动：没有缓存，扫一遍（走加载态）
            vm.scan()
        } else {
            // 有缓存：后台静默刷新一次。这就是"每次打开应用自动刷新"，
            // 取代了已经被去掉的下拉刷新
            vm.refreshOnStart()
        }
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

/**
 * 编辑歌曲信息写盘被系统拦下时，把确认框拉起来 —— 结构照着 [CoverWritePermissionGate] 来，
 * 只是回调换成 [SonanceViewModel.onMetaEditPermissionResult]。
 */
@Composable
private fun MetaEditPermissionGate(vm: SonanceViewModel) {
    val pending = vm.pendingMetaEdit

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onMetaEditPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onMetaEditPermissionResult(granted) }

    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        val sender = request.intentSender
        if (sender != null) {
            val launched = runCatching {
                writeLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }.isSuccess
            // 拉不起来也要收尾，否则 pendingMetaEdit 会一直挂着
            if (!launched) vm.onMetaEditPermissionResult(false)
        } else {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

/* ============================================================
 * 听歌识曲的进度与结果
 * ============================================================ */

/**
 * 识别进行中：阶段文案 + 转圈（整条链路只有几秒，不做取消）。
 * 出结果：确认卡片，用户点「应用」才写盘 —— 与手动编辑共用同一条
 * 写盘链路（含系统「允许修改此文件？」授权）。
 */
@Composable
private fun RecognizeSheet(vm: SonanceViewModel) {
    val state = vm.recognizeState
    when (state) {
        is SonanceViewModel.RecognizeState.Running -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("听歌识曲") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            state.phase,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                },
                confirmButton = { }
            )
        }
        is SonanceViewModel.RecognizeState.Done -> {
            val r = state.result
            AlertDialog(
                onDismissRequest = { vm.dismissRecognition() },
                title = { Text("识别到歌曲") },
                text = {
                    Column {
                        Text("「${r.title}」- ${r.artist}")
                        if (r.album.isNotBlank()) {
                            Text(
                                "专辑：${r.album}",
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Text(
                            "应用后将写入文件标签，并把文件改名为" +
                                "「${SongMetaEditor.outputStem(r.title, r.artist)}」。",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.applyRecognition() }) { Text("应用") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissRecognition() }) { Text("放弃") }
                }
            )
        }
        else -> Unit
    }
}

/* ============================================================
 * 删除音频文件的授权
 * ============================================================ */
/**
 * 删除歌曲文件被系统拦下时，把确认框拉起来。结构照着 [CoverWritePermissionGate] 来：
 * - `intentSender` 非空：`StartIntentSenderForResult` 拉起「允许删除这些文件？」，
 *   用户同意后**系统已把文件删掉**；
 * - 为空（API 28 及以下）：先要 `WRITE_EXTERNAL_STORAGE`，拿到后 ViewModel 会重新执行删除。
 */
@Composable
private fun SongDeletePermissionGate(vm: SonanceViewModel) {
    val pending = vm.pendingSongDelete

    val senderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onSongDeletePermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onSongDeletePermissionResult(granted) }

    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        val sender = request.intentSender
        if (sender != null) {
            val launched = runCatching {
                senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }.isSuccess
            // 拉不起来也要收尾，否则 pendingSongDelete 会一直挂着
            if (!launched) vm.onSongDeletePermissionResult(false)
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

    // 状态栏只由**当前播放层**决定：歌词页 / 律动柱页各自的外观开关，
    // 封面主界面两个开关都不认（只保留手机横屏那条自动沉浸，在 FullPlayer 里算）。
    // FullPlayer 把结果报上来，这里一处落地；收起播放页 / 切层 / 切后台都会自动恢复。
    var immersiveByLayer by remember { mutableStateOf(false) }
    val hideStatusBar = fullPlayerOpen && immersiveByLayer
    PlayerStatusBarImmersive(enabled = hideStatusBar)

    /** 沉浸式黑胶模式（铺在一切之上；关闭后音频不受影响） */
    var vinylOpen by remember { mutableStateOf(false) }
    // 等黑胶页淡入完成再停止下层绘制；退出时立即恢复，保留原页面的组合状态。
    var vinylCovered by remember { mutableStateOf(false) }
    LaunchedEffect(vinylOpen) {
        if (vinylOpen) {
            delay(320)
            vinylCovered = true
        } else {
            vinylCovered = false
        }
    }
    /** 沉浸式封面墙（原生 Compose）：与黑胶同一套「盖稳即停绘」范式 */
    var nativeWallOpen by remember { mutableStateOf(false) }
    var nativeWallCovered by remember { mutableStateOf(false) }
    LaunchedEffect(nativeWallOpen) {
        if (nativeWallOpen) {
            delay(320)
            nativeWallCovered = true
        } else {
            nativeWallCovered = false
        }
    }
    // 黑胶 / 封面墙任一盖稳后，下层（主界面 + 播放页）统一停绘、冻结动画
    val suppressVinylUnderlay = (vinylOpen && vinylCovered) || (nativeWallOpen && nativeWallCovered)
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
    // 设置页 / 在线音乐页 / 文件夹页按返回 → 回主页面（「全部歌曲」），而不是直接退出应用。
    //
    // 必须注册在上面两个之后：BackHandler 是「后注册且 enabled 的优先」，
    // 放在前面会被全屏播放页 / 详情页抢走。另外这里刻意要求 detail == null ——
    // 详情页的返回优先级要高于这些顶层页面。
    BackHandler(
        enabled = !fullPlayerOpen && detail == null &&
            (vm.tab == LibraryTab.SETTINGS || vm.tab == LibraryTab.ONLINE ||
                vm.tab == LibraryTab.FOLDERS)
    ) {
        vm.selectTab(LibraryTab.SONGS)
    }

    // 曲库搜索框有内容时，返回键先清空搜索，而不是直接退出应用。
    //
    // 注册在上面那条之后：同一层级里「后注册且 enabled 的优先」，所以
    // 「清空搜索」优先于「非歌曲 tab 退回全部歌曲」。清空之后：
    //   - 仍在别的 tab → 下一次返回走上面那条，回到「全部歌曲」；
    //   - 在「全部歌曲」→ 搜索已空，下一次返回交给系统退出应用。
    // 设置页 / 在线音乐页各有自己的搜索状态（settings / onlineQuery），不在这里处理。
    BackHandler(
        enabled = !fullPlayerOpen && detail == null &&
            vm.tab != LibraryTab.SETTINGS && vm.tab != LibraryTab.ONLINE &&
            vm.searchQuery.isNotEmpty()
    ) {
        vm.changeQuery("")
        dismissInput()
    }

    val dense = configuration.screenHeightDp < 520

    // onlineRevision 是必须的 key：在线歌不在 vm.songs 里，搜一次之后
    // 队列页 / 播放页要按 id 反查它们，映射必须重建。
    val songMap = remember(vm.songs, vm.onlineRevision) { vm.songMap() }

    // 分页器的数据源必须和播放器时间轴严格 1:1：
    // `queueIds` 里可能有被隐藏/清理、或扫描后已不在 songs 里的曲目，
    // 之前用 mapNotNull 会把这些 id 静默丢掉，导致页号与播放器索引错位 ——
    // 用户滑到第 N 页时声音切到播放器第 N 首，页面却显示压缩列表里的另一首
    // （表现为「切歌后封面/歌名还停在上一首」）。这里改成占位补位，绝不丢项。
    val queue = remember(playerState.queueIds, songMap) {
        playerState.queueIds.map { id ->
            songMap[id] ?: Song(
                id = id,
                title = "未知歌曲",
                artist = "",
                album = "",
                albumId = -1L,
                durationMs = 0L,
                uri = android.net.Uri.EMPTY,
                folderPath = "",
                folderName = "",
                displayName = "",
                sizeBytes = 0L,
                dateAddedSec = 0L,
                track = 0
            )
        }
    }
    val currentSong = songMap[playerState.mediaId]

    // ===== 沉浸皮肤：背景 = 当前播放封面的液态玻璃，字色随封面明暗自适应 =====
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 封面与歌词页同一来源、同一张图：背景画它，字色也从它算，两边不能各拿各的。
    // 状态**不按歌曲 id 重置** —— 换歌时旧图保留到新图加载完成，GlassBackdrop 才能做
    // 一次连贯的滑动过渡；若在这里清空，背景会「先消失、再淡入」两段闪变
    var shellArt by remember { mutableStateOf<ImageBitmap?>(null) }
    // 封面的分段平均色：**与封面同一帧换上**（先算色带、再换图）——
    // 均衡遮罩由色带算出，两者错帧的话新封面会顶着旧遮罩闪一帧错误的亮度
    var shellBands by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(skin.immersive, currentSong?.albumId, currentSong?.id, currentSong?.title, currentSong?.artist) {
        val s = currentSong
        if (!skin.immersive || s == null) {
            shellArt = null
            shellBands = null
            return@LaunchedEffect
        }
        val bmp = ArtworkLoader.load(
            context = context,
            albumId = s.albumId,
            sizePx = 320,
            sourceUri = s.uri,
            coverKey = OnlineCover.keyOf(s.title, s.artist)
        ) ?: ArtworkLoader.online(s.id)
        // 像素读取放后台线程（320px 位图几千次采样，毫秒级）；色带先就位、
        // 封面后换，两个赋值在同一次重组里一起生效
        shellBands = if (bmp == null) null else withContext(Dispatchers.Default) { artBandColors(bmp) }
        shellArt = bmp
    }

    // 切歌方向：下一首新图从右滑入（+1）、上一首从左滑入（-1），对齐播放页轮播
    var artSlideDir by remember { mutableStateOf(1) }
    var lastQueueIndex by remember { mutableStateOf(Int.MIN_VALUE) }
    LaunchedEffect(playerState.queueIndex) {
        val idx = playerState.queueIndex
        if (lastQueueIndex != Int.MIN_VALUE && idx >= 0 && idx != lastQueueIndex) {
            artSlideDir = if (idx > lastQueueIndex) 1 else -1
        }
        lastQueueIndex = idx
    }

    // 方案 C「统一压暗」：先用色带算出每个停靠点的均衡遮罩 —— 把「底色 × 封面 ×
    // 遮罩」的合成亮度钉在恒定深色区间（封面越亮遮罩越浓、越暗越薄）。
    // 这份值同时交给 GlassBackdrop 画背景和 buildBgProfile 折算字色，画和算同参。
    // 玻璃「关闭」档不铺封面背景，遮罩和字色 profile 一起退到皮肤兜底
    val shellGlassOn = vm.shellGlassStyle != GlassStyle.OFF
    val equalScrims = remember(shellBands, shellArt, screenW, screenH, skin, shellGlassOn, vm.shellGlassVisibility) {
        if (!shellGlassOn) return@remember null
        val bands = shellBands ?: return@remember null
        equalizeScrimAlphas(
            bands = bands,
            artW = shellArt?.width ?: 0,
            artH = shellArt?.height ?: 0,
            boxW = screenW,
            boxH = screenH,
            skin = skin,
            visibility = vm.shellGlassVisibility,
            canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )
    }

    // 按 GlassBackdrop 的真实画法（Crop → 放大 1.35 → 遮罩）折算等效背景色带。
    // 画和算必须是同一套参数（同一样式 / 同一可见度 / 同一份均衡遮罩），字色才对得准
    val bgProfile = remember(shellBands, shellArt, screenW, screenH, skin, equalScrims, shellGlassOn, vm.shellGlassVisibility) {
        if (!shellGlassOn) return@remember null
        val bands = shellBands ?: return@remember null
        buildBgProfile(
            bands = bands,
            artW = shellArt?.width ?: 0,
            artH = shellArt?.height ?: 0,
            boxW = screenW,
            boxH = screenH,
            skin = skin,
            visibility = vm.shellGlassVisibility,
            canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            liquid = true,
            // 与上面 GlassBackdrop 的画法严格同参，字色才对得准
            scrimSpread = 0.10f,
            topHighlight = false,
            scrimAlphas = equalScrims
        )
    }
    // 方案 C：背景亮度恒定深色 → 组件 / 文字固定一套深色样式，**不再随封面明暗翻转**
    //（旧的 pickLighten / 白霜分支已删）。ensureReadable 只作兜底：个别停靠点
    // 没压到位（无封面 / 极暗封面遮罩趋零）或强调色与封面色相撞时，把字色朝亮推
    // 到刚好达标 —— 背景亮度钉死，推出来的颜色逐歌几乎不变，观感上就是"一套固定的色"。
    val adaptedSkin = if (skin.immersive && bgProfile != null) {
        // 亮文字最怕最亮的那段：取三个采样点里最亮的做基准
        val rep = listOf(bgProfile.at(0.10f), bgProfile.at(0.5f), bgProfile.at(0.85f))
            .maxBy { it.luminance() }
        val accent = ensureReadable(skin.accent, rep, 3.2f, true)
        skin.copy(
            text = ensureReadable(skin.text, rep, 4.5f, true),
            textDim = ensureReadable(skin.textDim, rep, 3.8f, true),
            textMuted = ensureReadable(skin.textMuted, rep, 3.4f, true),
            accent = accent,
            accentDim = ensureReadable(skin.accentDim, rep, 2.8f, true),
            // onAccent 是「accent 实底上的字/图标」——开关圆点、实心按钮、播放键。
            // 与 accent 相反方向适配（accent 亮它就暗），保证实底上永远看得清
            onAccent = ensureReadable(skin.onAccent, accent, 4.5f, false),
            danger = ensureReadable(skin.danger, rep, 3.0f, true)
        )
    } else skin

    fun openAlbum(albumId: Long) {
        detail = Detail.AlbumDetail(albumId)
        fullPlayerOpen = false
    }

    // 播放页底栏、设置页的"调节"按钮都汇到这里，避免同时渲染两个面板
    val openEqualizer: () -> Unit = {
        vm.openEqualizer()
        eqOpen = true
    }

    // 封面墙入口（顶栏图标 / 视图弹层共用）：先收输入法，避免光标手柄飘在墙上
    val openCoverWall: () -> Unit = {
        dismissInput()
        nativeWallOpen = true
    }

    CompositionLocalProvider(LocalDenseLayout provides dense) {
        Box(Modifier.fillMaxSize()) {

        // 沉浸皮肤：整块主界面铺「当前播放封面的玻璃背景」。样式 / 模糊 / 透出强度
        // 由设置页「主界面背景」区块控制（默认 = 原写死的液态玻璃 40 / 100）。
        // 画在根上 —— 列表 / 详情页 / 设置页都叠在它上面；下面那层 skin.bg 实底
        // 在沉浸模式下跳过，否则会把封面盖死。没有封面时只剩遮罩，仍然成立
        if (skin.immersive) {
            GlassBackdrop(
                art = shellArt,
                style = vm.shellGlassStyle,
                blurDp = vm.shellGlassBlur,
                visibility = vm.shellGlassVisibility,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = if (suppressVinylUnderlay) 0f else 1f }
                    // 1.35 倍放大的封面会溢出边界，裁掉（歌词页同款）
                    .clipToBounds(),
                scrimColor = skin.bg,
                // 整页铺底不描边：顶部那道折射亮线在列表页太抢戏
                edgeHighlight = false,
                // 遮罩压平 + 关顶部高光：让背景亮度上下均匀，字色按一处算全程有效。
                // 之前浅色封面上，顶部被高光提得比中部亮一大截，标题和搜索框全糊掉
                scrimSpread = 0.10f,
                topHighlight = false,
                // 换歌时的背景过渡：滑动（随切歌方向）/ 渐变 + 时长，设置页可选
                transition = if (vm.artTransitionMode == "fade") ArtTransition.FADE
                else ArtTransition.SLIDE,
                transitionMs = if (vm.artTransitionMode == "fade") vm.artFadeMs else 380,
                slideDir = artSlideDir,
                // 方案 C：均衡遮罩 —— 背景合成亮度钉在恒定深色区间，
                // 与下面 buildBgProfile 用的是同一份值（画和算同参）
                scrimAlphas = equalScrims
            )
        }
        // 沉浸模式下封面玻璃负责整页背景；玻璃「关闭」档没有这层，
        // 退回 skin.bg 实底（深色，和不铺背景时的观感一致）
        Box(Modifier.fillMaxSize().then(if (!skin.immersive || vm.shellGlassStyle == GlassStyle.OFF) Modifier.background(skin.bg) else Modifier)) {

        // 沉浸皮肤的字色随封面明暗逐歌重算 —— 只包住主界面子树；
        // 全屏播放页有自己的晕染背景与调色逻辑，维持原皮肤
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (suppressVinylUnderlay) 0f else 1f }) {
        CompositionLocalProvider(LocalSkin provides adaptedSkin) {
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
                onOpenEqualizer = openEqualizer,
                onOpenCoverWall = openCoverWall
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
                onOpenEqualizer = openEqualizer,
                onOpenCoverWall = openCoverWall
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
                onOpenEqualizer = openEqualizer,
                onOpenCoverWall = openCoverWall
            )
        }
        }
        }

        // ===== 全屏播放页 =====
        AnimatedVisibility(
            visible = fullPlayerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
                .graphicsLayer { alpha = if (suppressVinylUnderlay) 0f else 1f }
                // 兜底：个别 ROM 在横屏 hide() 之后仍会派发非零的 statusBars
                // insets，那几处 statusBarsPadding() 就还会把内容往下推。
                // 这里在播放页根节点把这段 inset 消费掉，子层统一拿到 0。
                // 只对「手机横屏播放页」生效，其它形态的 padding 原样保留。
                .then(
                    if (hideStatusBar) Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    else Modifier
                )
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
                onPrev = { vm.previousOrRandom() },
                onPlayPause = { vm.player.togglePlay() },
                onNext = { vm.nextOrRandom() },
                onSeek = { vm.player.seekTo(it) },
                onVolume = { vm.player.setVolume(it) },
                onOpenQueue = { queueOpen = true },
                onOpenCover = { vm.openCoverPicker(playerState.mediaId) },
                onOpenEqualizer = openEqualizer,
                onOpenSleepTimer = { sleepOpen = true },
                onOpenVinyl = { vinylOpen = true },
                coveredByVinyl = suppressVinylUnderlay,
                sleepTimerActive = vm.sleepTimerLabel != null,
                eqEnabled = vm.eqEnabled,
                playbackSpeed = vm.playbackSpeed,
                onSetSpeed = { vm.changePlaybackSpeed(it) },
                lyricsHideStatusBar = vm.lyricsHideStatusBar,
                onLyricsHideStatusBarChange = { vm.changeLyricsHideStatusBar(it) },
                visualizerHideStatusBar = vm.visualizerHideStatusBar,
                onVisualizerHideStatusBarChange = { vm.changeVisualizerHideStatusBar(it) },
                onImmersiveChange = { immersiveByLayer = it },
                glowMode = vm.glowMode,
                glowStrength = vm.glowStrength,
                glowTransition = vm.glowTransition,
                glowFadeMs = vm.glowFadeMs,
                onGlowModeChange = { vm.changeGlowMode(it) },
                onGlowStrengthChange = { vm.changeGlowStrength(it) },
                onGlowTransitionChange = { vm.changeGlowTransition(it) },
                onGlowFadeMsChange = { vm.changeGlowFadeMs(it) },
                vinylMode = vm.vinylMode,
                onVinylModeChange = { vm.changeVinylMode(it) }
            )
        }

        // ===== 沉浸式黑胶模式 =====
        AnimatedVisibility(
            visible = vinylOpen,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(35f)
        ) {
            VinylWebScreen(
                state = playerState,
                queue = queue,
                onClose = { vinylOpen = false },
                // 唱臂抬起时 150ms 极短淡出，音频渐弱与「啪嗒」机械声衔接更温润
                onPlayAt = { vm.player.playAt(it, fadeMs = 150) },
                onTogglePlay = { vm.player.togglePlay() },
                onSeek = { vm.player.seekTo(it) },
                onVolume = { vm.player.setVolume(it) },
                // 黑胶页模式按钮走主界面同一套三态逻辑；页面自己弹风格化 toast，抑制原生 toast
                onPlayMode = { vm.cyclePlayMode(quiet = true) }
            )
        }

        // ===== 沉浸式封面墙（原生 Compose） =====
        AnimatedVisibility(
            visible = nativeWallOpen,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(37f)
        ) {
            NativeCoverWallScreen(
                state = playerState,
                albums = vm.albums,
                songs = vm.songs,
                favorites = vm.favorites,
                onClose = { nativeWallOpen = false },
                onPlayAlbum = { wa, idx ->
                    val song = wa.tracks.getOrNull(idx) ?: wa.tracks.firstOrNull()
                    if (song != null) vm.playOrdered(wa.tracks, song)
                },
                onQueueAlbum = { wa -> wa.tracks.asReversed().forEach { vm.player.playNext(it) } },
                onTogglePlay = { vm.player.togglePlay() },
                onPrev = { vm.player.previous() },
                onNext = { vm.player.next() },
                onSeek = { vm.player.seekTo(it) }
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

        // ===== 应用内视频浮窗 =====
        // 放在根布局最上层：切到主界面 / 列表 / 设置都还看得见；
        // 它只是视频会话的宿主视图，播放器本身不随页面销毁
        BiliFloatWindow(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(40f)
        )
    }
    }
    }

    // 手动选封面（设置页和播放页的入口都汇到这里，避免同时渲染两个对话框）
    CoverPickerDialog(vm = vm)

    // 把封面写进原文件时，系统可能要用户点一下「允许修改此文件？」
    CoverWritePermissionGate(vm)

    // 删除歌曲文件被系统拦下时，拉起「允许删除这些文件？」
    SongDeletePermissionGate(vm)

    // 编辑歌曲信息写盘被系统拦下时，拉起「允许修改此文件？」
    MetaEditPermissionGate(vm)

    // 听歌识曲的进度框 / 结果确认卡片
    RecognizeSheet(vm)

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

/**
 * 悬浮迷你播放条占掉的那一条高度（内容要给它让出来）。
 * 卡片 62dp 内容 + 10dp 下边距，留 76dp 刚好不压住列表最后一行。
 */
private val MINI_FLOAT_RESERVE = 76.dp

/* ============================================================
 * 手机布局：顶栏 + 内容 + 迷你播放条 + 底部导航
 * ============================================================ */

@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenEqualizer: () -> Unit,
    onOpenCoverWall: () -> Unit
) {
    val skin = LocalSkin.current
    // 「视图」弹层：专辑 / 艺术家 / 文件夹 —— 从底栏下沉到这里
    var showViewSheet by remember { mutableStateOf(false) }
    // 双击顶栏标题 / logo 弹出的「应用名 + 桌面图标」编辑面板
    var showBrandEditor by remember { mutableStateOf(false) }

    // 悬浮卡片要叠在内容上：内容按卡片高度让出一条底边距，
    // 免得列表最后一行被卡片压住（贴底通栏本来就在内容下面，不需要让）
    val floating = vm.miniPlayerStyle == MiniPlayerStyle.FLOAT

    // 悬浮卡的**实测高度**。设置里能把卡片调到 2 倍高，原来那个写死的
    // MINI_FLOAT_RESERVE(76dp) 就兜不住了 —— 卡片会压住内容底部，
    // 连右下角「回到顶部」都被盖在里面。改成量一次高度，内容让位和那个按钮
    // 都跟着走，用户怎么调都不会再互相挡。
    var miniBarHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val miniBarHeight = with(density) { miniBarHeightPx.toDp() }
    // 还没测到之前先按老值垫着，避免第一帧内容跳动
    val miniReserve = if (miniBarHeightPx > 0) miniBarHeight + 8.dp else MINI_FLOAT_RESERVE

    Column(Modifier.fillMaxSize()) {

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 沉浸模式下不画实底：让根部的封面玻璃背景透出来
                .then(if (!skin.immersive) Modifier.background(skin.bg) else Modifier)
                .navigationBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
            AppTopBar(
                // 主页面标题固定成**应用名**：原来跟着页签换（"全部歌曲 · 共 N 首 ·
                // 21 小时"），每切一次页文字都在变，顶栏永远没有稳定锚点。
                // 详情页仍是专辑/艺术家名 —— 那是"我在看什么"的位置指示。
                title = detail?.let { detailTitle(vm, it) } ?: vm.appDisplayName,
                subtitle = detail?.let { detailSubtitle(vm, it) } ?: tabSubtitle(vm),
                // 列表页把统计并进标题行（省一整行）；详情页标题可能是长专辑名，
                // 挤一行会把标题截没，所以保持两行
                subtitleInline = detail == null,
                modifier = Modifier.statusBarsPadding(),
                // 双击标题/logo → 改应用名、换**顶栏图标**（不是桌面图标）
                onTitleDoubleTap = if (detail == null) {
                    { showBrandEditor = true }
                } else null,
                logoId = vm.topBarLogoId,
                // 底栏去掉之后，专辑/艺术家/文件夹/在线 变成"深一层"的页面 ——
                // 不给返回键，用户只能再点一次 ▤ 才能回曲库，等于走进死胡同
                onBack = when {
                    detail != null -> { { onCloseDetail() } }
                    vm.tab == LibraryTab.ALBUMS || vm.tab == LibraryTab.ARTISTS ||
                        vm.tab == LibraryTab.FOLDERS || vm.tab == LibraryTab.ONLINE ->
                        { { vm.selectTab(LibraryTab.SONGS) } }
                    else -> null
                },
                actions = {
                    if (detail == null) {
                        // ① 视图：专辑 / 艺术家 / 文件夹 收进弹层（原来占底栏三格）
                        IconAction(
                            icon = SonanceIcons.Layers,
                            contentDescription = "切换视图",
                            onClick = { showViewSheet = true },
                            iconSize = 19.dp,
                            tint = if (vm.tab == LibraryTab.ALBUMS ||
                                vm.tab == LibraryTab.ARTISTS ||
                                vm.tab == LibraryTab.FOLDERS
                            ) {
                                skin.accent
                            } else {
                                skin.textDim
                            }
                        )
                        // ② 封面墙入口已移到曲库筛选行的「随便听听」胶囊
                        //（LibraryPagerScreen 表头），顶栏不再放小图标。
                        // ③ 在线音乐：云图标进（不跟本地搜索框抢放大镜）；
                        // 已经停在在线音乐页时变成 ✕ —— 和设置页同一条规则：
                        // 这类"全屏接管"的页面，原样图标点上去毫无反应，看着就像坏了。
                        val inOnline = vm.tab == LibraryTab.ONLINE
                        // 在线音乐关闭时，这一格不再有「进在线页」的意义，改成刷新本地曲库。
                        // 刷新走 vm.scan()，内部使用 currentScanFilters()，已排除文件夹不会被扫回来。
                        val refreshOnly = !inOnline && !vm.onlineMusicEnabled
                        IconAction(
                            icon = when {
                                inOnline -> SonanceIcons.Close
                                refreshOnly -> SonanceIcons.Refresh
                                else -> SonanceIcons.Cloud
                            },
                            contentDescription = when {
                                inOnline -> "关闭在线音乐"
                                refreshOnly -> "刷新本地曲库"
                                else -> "在线音乐"
                            },
                            onClick = {
                                if (inOnline) {
                                    vm.selectTab(LibraryTab.SONGS)
                                } else if (refreshOnly) {
                                    vm.scan()
                                } else {
                                    vm.selectTab(LibraryTab.ONLINE)
                                    onCloseDetail()
                                }
                            },
                            iconSize = 19.dp,
                            tint = if (inOnline) skin.accent else skin.textDim
                        )
                    }
                    // ③ 设置：固定在最右。
                    //
                    // 已经停在设置页时它变成 ✕ —— 设置页是"全屏接管"的一页，
                    // 右上角那个齿轮点上去没有任何变化，看着就像坏了；
                    // 变成 ✕ 才说明「再点一下就退出去」。
                    val inSettings = detail == null && vm.tab == LibraryTab.SETTINGS
                    IconAction(
                        icon = if (inSettings) SonanceIcons.Close else SonanceIcons.Settings,
                        contentDescription = if (inSettings) "关闭设置" else "皮肤与设置",
                        onClick = {
                            if (inSettings) {
                                vm.selectTab(LibraryTab.SONGS)
                            } else {
                                vm.selectTab(LibraryTab.SETTINGS)
                                onCloseDetail()
                            }
                        },
                        iconSize = 19.dp,
                        tint = if (inSettings) skin.accent else skin.textDim
                    )
                }
            )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = if (floating && playerState.hasItem) miniReserve else 0.dp)
                ) {
                    if (detail == null && vm.tab.inPager) {
                        // 三页滑动：全部 / 最近 / 收藏（下拉刷新已移除，改由启动时自动刷新）
                        LibraryPagerScreen(
                            vm = vm,
                            playerState = playerState,
                            onOpenAlbum = { album -> onOpenDetail(Detail.AlbumDetail(album.albumId)) },
                            onOpenCoverWall = onOpenCoverWall
                        )
                    } else {
                        ScreenBody(
                            vm = vm,
                            playerState = playerState,
                            detail = detail,
                            onOpenDetail = onOpenDetail,
                            onOpenEqualizer = onOpenEqualizer,
                            reportCurrentVisibility = detail == null
                        )
                    }

                    // 「定位到正在播放」：只在曲库三页的列表视图显示。
                    // 内容区已为悬浮卡让位 8dp，这里再补 2dp —— 正好「距悬浮播放窗
                    // 上方 10dp、距屏幕右缘 15dp」；经典贴底时内容区不让位，改贴 10dp。
                    // 点击走 requestScrollToCurrent，滚动逻辑复用 SongList 里的定位 effect。
                    // 44dp 的 Box 保留完整点击面积；圆形裁剪让点击水波纹也按圆扩散，不露方框。
                    // 当前播放歌已在可见区内时隐藏；不在可见区才出现。
                    // 一层极浅的圆形底（用 skin.text 派生，深浅皮肤都看得见）。
                    if (detail == null && vm.tab.inPager && playerState.hasItem && !vm.currentSongVisible) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 15.dp)
                                .padding(bottom = if (floating) 2.dp else 10.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(skin.text.copy(alpha = 0.06f), CircleShape)
                                .clickable { vm.requestScrollToCurrent() },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = SonanceIcons.Locate,
                                contentDescription = "定位到正在播放的歌曲",
                                tint = skin.text.copy(alpha = 0.86f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 迷你播放条：悬浮时叠在内容上（Align.BottomCenter），
            // 经典时由下方那条 navigationBarsPadding 的位置关系自然贴底
            if (playerState.hasItem) {
                MiniPlayer(
                    state = playerState,
                    song = null,
                    onOpen = onOpenFullPlayer,
                    onPlayPause = { vm.player.togglePlay() },
                    onNext = { vm.player.next() },
                    onPrev = { vm.player.previous() },
                    style = vm.miniPlayerStyle,
                    glass = vm.miniGlassStyle,
                    glassBlur = vm.miniGlassBlur,
                    glassVisibility = vm.miniGlassVisibility,
                    cardOpacity = vm.miniCardOpacity,
                    progressStyle = vm.miniProgressStyle,
                    swipeLeftPct = vm.miniSwipeLeftPct,
                    swipeRightPct = vm.miniSwipeRightPct,
                    heightScale = vm.miniHeightScale / 100f,
                    coverScale = vm.miniCoverScale / 100f,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 量出真实高度：内容让位、回到顶部按钮的避让都靠它
                        .onSizeChanged { miniBarHeightPx = it.height }
                )
            }
        }
        // 底栏已移除：五个入口重新分层（三页滑动 / 视图弹层 / 右上角在线）
    }

    if (showViewSheet) {
        ModalBottomSheet(onDismissRequest = { showViewSheet = false }) {
            ViewPickerSheet(
                current = vm.tab,
                onPick = { tab ->
                    vm.selectTab(tab)
                    onCloseDetail()
                    showViewSheet = false
                },
                onOpenWall = {
                    showViewSheet = false
                    onOpenCoverWall()
                }
            )
        }
    }

    if (showBrandEditor) {
        BrandEditorDialog(vm = vm, onDismiss = { showBrandEditor = false })
    }
}

/**
 * 「视图」弹层：把专辑 / 艺术家 / 文件夹 放在一步可达的位置。
 *
 * 为什么不塞进设置页：这三项是中频操作（按专辑听歌是常见路径），
 * 埋进设置会变成"每次都要找"。放在标题栏一个图标后面，既不占常驻位，也只需一步。
 */
@Composable
private fun ViewPickerSheet(
    current: LibraryTab,
    onPick: (LibraryTab) -> Unit,
    onOpenWall: () -> Unit
) {
    val skin = LocalSkin.current
    val options = listOf(
        Triple(LibraryTab.SONGS, SonanceIcons.Music, "歌曲"),
        Triple(LibraryTab.ALBUMS, SonanceIcons.Album, "专辑"),
        Triple(LibraryTab.ARTISTS, SonanceIcons.Users, "艺术家"),
        Triple(LibraryTab.FOLDERS, SonanceIcons.Folder, "文件夹")
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = "按什么方式浏览曲库",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = skin.textMuted,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp)
        )
        options.forEach { (tab, icon, label) ->
            val on = current == tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(tab) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (on) skin.accent else skin.textDim,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = label,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) skin.accent else skin.text
                )
                if (on) {
                    Text(
                        text = "当前",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        // 封面墙：不是曲库视图而是一种沉浸浏览方式，用分隔线与四个 tab 隔开，
        // 副标题说明它做什么（点进去是全屏 3D 墙，不是又一个列表页）
        androidx.compose.material3.HorizontalDivider(
            color = skin.border,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenWall() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = SonanceIcons.CoverWall,
                contentDescription = null,
                tint = skin.textDim,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "封面墙",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = skin.text
                )
                Text(
                    text = "沉浸式 3D 浏览全部专辑",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
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
    onOpenEqualizer: () -> Unit,
    onOpenCoverWall: () -> Unit
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
            // 沉浸皮肤下侧栏也半透明，与右侧面板同一层背景；系统栏内边距由侧栏内部处理
            translucent = skin.immersive,
            modifier = Modifier
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
                    // 同上：刷新不再占顶栏的位置；只留封面墙一个沉浸入口
                    actions = {
                        if (detail == null && vm.tab != LibraryTab.SETTINGS &&
                            vm.tab != LibraryTab.ONLINE
                        ) {
                            IconAction(
                                icon = SonanceIcons.CoverWall,
                                contentDescription = "封面墙",
                                onClick = onOpenCoverWall,
                                iconSize = 19.dp,
                                tint = skin.textDim
                            )
                        }
                    }
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ScreenBody(
                        vm = vm,
                        playerState = playerState,
                        detail = detail,
                        onOpenDetail = onOpenDetail,
                        onOpenEqualizer = onOpenEqualizer,
                        reportCurrentVisibility = detail == null
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
                    style = vm.miniPlayerStyle,
                    glass = vm.miniGlassStyle,
                    glassBlur = vm.miniGlassBlur,
                    glassVisibility = vm.miniGlassVisibility,
                    cardOpacity = vm.miniCardOpacity,
                    progressStyle = vm.miniProgressStyle,
                    swipeLeftPct = vm.miniSwipeLeftPct,
                    swipeRightPct = vm.miniSwipeRightPct,
                    heightScale = vm.miniHeightScale / 100f,
                    coverScale = vm.miniCoverScale / 100f,
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
    onOpenEqualizer: () -> Unit,
    onOpenCoverWall: () -> Unit
) {
    val skin = LocalSkin.current
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 1200

    // 双击左上角品牌块 = 手机「双击顶栏标题」的等价入口（改应用名 / 换顶栏图标）
    var showBrandEditor by remember { mutableStateOf(false) }
    // 悬浮迷你播放卡：设置里选「悬浮」档时，平板也用它，而不是底部通栏
    val floating = vm.miniPlayerStyle == MiniPlayerStyle.FLOAT
    var miniBarHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val miniBarHeight = with(density) { miniBarHeightPx.toDp() }
    // 还没量到高度前先用老值垫着，避免第一帧列表跳动
    val miniReserve = if (miniBarHeightPx > 0) miniBarHeight + 8.dp else MINI_FLOAT_RESERVE

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
            // 平板布局专有入口：读 B 站离线缓存（需要 Shizuku）
            biliVisible = true,
            biliSelected = detail is Detail.BiliCache,
            onOpenBili = { onOpenDetail(Detail.BiliCache) },
            appName = vm.appDisplayName,
            logoId = vm.topBarLogoId,
            onBrandDoubleTap = { showBrandEditor = true },
            // 沉浸皮肤下侧栏也半透明，与右侧面板同一层背景；系统栏内边距由侧栏内部处理
            translucent = skin.immersive,
            modifier = Modifier
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
                        // 和手机同一条规则：双击标题 = 改应用名 / 换顶栏图标
                        logoId = vm.topBarLogoId,
                        onTitleDoubleTap = if (detail == null) {
                            { showBrandEditor = true }
                        } else null,
                        // 刷新图标去掉了：列表页有下拉刷新，其它页面走「设置 → 重新扫描」。
                        // 顶栏只留真正一步到不了的入口，图标多了反而每个都不像入口。
                        actions = {
                            if (detail == null && vm.tab != LibraryTab.SETTINGS &&
                                vm.tab != LibraryTab.ONLINE
                            ) {
                                IconAction(
                                    icon = SonanceIcons.CoverWall,
                                    contentDescription = "封面墙",
                                    onClick = onOpenCoverWall,
                                    iconSize = 19.dp,
                                    tint = skin.textDim
                                )
                            }
                        }
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                // 悬浮卡叠在列表上：内容让出一条底边距，
                                // 免得最后一行被卡片压住
                                .padding(
                                    bottom = if (floating && playerState.hasItem) miniReserve else 0.dp
                                )
                        ) {
                            ScreenBody(
                                vm = vm,
                                playerState = playerState,
                                detail = detail,
                                onOpenDetail = onOpenDetail,
                                onOpenEqualizer = onOpenEqualizer,
                                reportCurrentVisibility = detail == null
                            )
                        }

                        // 「定位到正在播放」：与手机布局同一个按钮、同一套滚动逻辑。
                        // 平板列表原来没有这个入口，长曲库里找回正在播放的歌只能手滚
                        if (detail == null && vm.tab.inPager && playerState.hasItem && !vm.currentSongVisible) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 15.dp)
                                    // 悬浮卡是整宽贴底的，按钮要抬到卡片上方才不被压住
                                    .padding(bottom = if (floating) miniReserve + 8.dp else 10.dp)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(skin.text.copy(alpha = 0.06f), CircleShape)
                                    .clickable { vm.requestScrollToCurrent() },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = SonanceIcons.Locate,
                                    contentDescription = "定位到正在播放的歌曲",
                                    tint = skin.text.copy(alpha = 0.86f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 悬浮播放卡：设置里选了「悬浮」才出现（经典档仍是底部通栏）
                        if (floating && playerState.hasItem) {
                            MiniPlayer(
                                state = playerState,
                                song = null,
                                onOpen = onOpenFullPlayer,
                                onPlayPause = { vm.player.togglePlay() },
                                onNext = { vm.player.next() },
                                onPrev = { vm.player.previous() },
                                style = vm.miniPlayerStyle,
                                glass = vm.miniGlassStyle,
                                glassBlur = vm.miniGlassBlur,
                                glassVisibility = vm.miniGlassVisibility,
                                cardOpacity = vm.miniCardOpacity,
                                progressStyle = vm.miniProgressStyle,
                                swipeLeftPct = vm.miniSwipeLeftPct,
                                swipeRightPct = vm.miniSwipeRightPct,
                                heightScale = vm.miniHeightScale / 100f,
                                coverScale = vm.miniCoverScale / 100f,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    // 量出真实高度：内容让位靠它（设置里能把卡片调到 2 倍高）
                                    .onSizeChanged { miniBarHeightPx = it.height }
                            )
                        }
                    }
                }

                Row(Modifier.width(1.dp).fillMaxHeight().background(skin.border)) {}

                NowPlayingPanel(
                    state = playerState,
                    // 右侧面板的歌词/律动柱层需要完整 Song（uri、album、时长等），
                    // 原来这里传 null，导致只能展示封面
                    song = vm.songById(playerState.mediaId),
                    onCyclePlayMode = { vm.cyclePlayMode() },
                    onPrev = { vm.previousOrRandom() },
                    onPlayPause = { vm.player.togglePlay() },
                    onNext = { vm.nextOrRandom() },
                    onVolume = { vm.player.setVolume(it) },
                    onSeek = { vm.player.seekTo(it) },
                    onOpenQueue = onOpenQueue,
                    onFavorite = {
                        vm.songById(playerState.mediaId)?.let { vm.toggleFavorite(it.id) }
                    },
                    isFavorite = vm.favorites.contains(playerState.mediaId),
                    // 沉浸皮肤：右侧面板也半透明，让整页封面玻璃透上来，
                    // 与左侧列表是同一层背景（不再是一块实心色）
                    translucent = skin.immersive,
                    // 面板内上下滑切歌，方向和手机播放页一致：上滑下一首、下滑上一首
                    onSwipeUp = { vm.nextOrRandom() },
                    onSwipeDown = { vm.previousOrRandom() },
                    modifier = Modifier
                        .width(if (wide) 344.dp else 312.dp)
                        .fillMaxHeight()
                )
            }

            // 悬浮档不画底部通栏（卡片会叠在列表上）；经典档保持原样
            if (playerState.hasItem && !floating) {
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
            } else {
                // 没有通栏时也要给系统手势条留高度
                Box(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showBrandEditor) {
        BrandEditorDialog(vm = vm, onDismiss = { showBrandEditor = false })
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
    onOpenEqualizer: () -> Unit,
    /**
     * 是否上报「当前播放歌曲是否在列表可见区内」。只有宽屏（无 Pager）
     * 的歌曲/最近/收藏列表传 true；手机 Pager 由 LibraryPagerScreen 自己处理。
     */
    reportCurrentVisibility: Boolean = false
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
                    // 「排除此文件夹」：整支从曲库列表剔除（文件不动），
                    // 恢复入口在 设置 → 乐库清理与合并 → 已排除的文件夹。
                    var confirmExclude by remember { mutableStateOf(false) }
                    SongCollectionScreen(
                        title = folder.name,
                        subtitle = "${folder.songs.size} 首 · ${folder.path}",
                        songs = folder.songs,
                        vm = vm,
                        playerState = playerState,
                        showCover = false,
                        headerOverflow = {
                            MenuRow(
                                icon = SonanceIcons.FolderMinus,
                                label = "排除此文件夹",
                                selected = false,
                                onClick = { confirmExclude = true }
                            )
                        }
                    )
                    if (confirmExclude) {
                        AlertDialog(
                            onDismissRequest = { confirmExclude = false },
                            title = { Text("排除文件夹") },
                            text = {
                                Text(
                                    "把「${folder.name}」从曲库列表排除？\n\n" +
                                        "其中 ${folder.songs.size} 首歌曲将不再出现在任何列表中，" +
                                        "文件不会被删除。\n" +
                                        "可在 设置 → 乐库清理与合并 → 已排除的文件夹 中恢复。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmExclude = false
                                    vm.excludeFolder(folder.path)
                                }) { Text("排除") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmExclude = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }

            is Detail.PlaylistDetail -> OnlinePlaylistScreen(
                playlist = detail.playlist,
                vm = vm,
                playerState = playerState
            )

            Detail.BiliCache -> BiliCacheScreen()
        }
        return
    }

    when (vm.tab) {
        LibraryTab.SONGS -> SongsScreen(
            vm = vm,
            playerState = playerState,
            onOpenFolder = { onOpenDetail(Detail.FolderDetail(it)) },
            onOpenAlbum = { onOpenDetail(Detail.AlbumDetail(it.albumId)) },
            reportCurrentVisibility = reportCurrentVisibility
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

        LibraryTab.RECENT -> RecentScreen(
            vm = vm,
            playerState = playerState,
            reportCurrentVisibility = reportCurrentVisibility
        )

        LibraryTab.ONLINE -> OnlineScreen(
            vm = vm,
            playerState = playerState,
            onOpenPlaylist = { onOpenDetail(Detail.PlaylistDetail(it)) }
        )

        LibraryTab.FAVORITES -> FavoritesScreen(
            vm = vm,
            playerState = playerState,
            reportCurrentVisibility = reportCurrentVisibility
        )

        LibraryTab.SETTINGS -> SettingsScreen(
            vm = vm,
            onOpenEqualizer = onOpenEqualizer
        )
    }
}

/* ============================================================
 * 文案
 * ============================================================ */

private fun tabSubtitle(vm: SonanceViewModel): String {
    // 扫描刚完成：先用副标题位顶几秒结果，再自动退回常规文案
    vm.scanHint?.let { return it }
    return when (vm.tab) {
    // 时间汇总去掉了：找歌的人不关心曲库总共多少小时，
    // 而且它跟着扫描结果变，挂在那里只是噪音
    LibraryTab.SONGS -> "共 ${vm.songs.size} 首"
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
}

private fun detailTitle(vm: SonanceViewModel, detail: Detail): String = when (detail) {
    is Detail.AlbumDetail -> vm.albumById(detail.albumId)?.name ?: "专辑"
    is Detail.ArtistDetail -> detail.name
    is Detail.FolderDetail -> vm.folderByPath(detail.path)?.name ?: "文件夹"
    is Detail.PlaylistDetail -> detail.playlist.name
    Detail.BiliCache -> "B站缓存"
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

    Detail.BiliCache -> "本机离线缓存"
}
