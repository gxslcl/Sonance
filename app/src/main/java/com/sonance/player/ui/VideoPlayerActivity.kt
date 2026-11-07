package com.sonance.player.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sonance.player.data.Prefs
import com.sonance.player.data.bili.BiliEntry
import com.sonance.player.data.bili.BiliPlaybackState
import com.sonance.player.data.bili.BiliRepository
import com.sonance.player.playback.BiliVideoPlayer
import com.sonance.player.playback.BiliVideoSession
import com.sonance.player.playback.GainLevel
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.probeVideo
import com.sonance.player.ui.icon.SonanceIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 前后各预载几页（pager 的 beyondViewportPageCount，同时决定实例释放半径） */
private const val PRELOAD_PAGES = 2

/** 同时合成 MP4 的数量上限：2 条已经能把 IO 打满，再多会互相拖慢 */
private const val PREPARE_PARALLEL = 2

/** 纵向拖动时「下一个 / 上一个」提示出现的最小位移（dp 的一半，防抖） */
private const val SWIPE_HINT_DP = 56f

/** logcat 过滤用：`adb logcat -s SonanceVideo` */
private const val TAG = "SonanceVideo"

/**
 * 上下切页的起手区间：只认屏幕高度 20%~80% 之间按下的拖动。
 * 顶部 20%（拉通知栏/状态栏）和底部 20%（系统手势）都不参与切页。
 */
private const val PAGE_SWIPE_START_TOP_RATIO = 0.20f
private const val PAGE_SWIPE_START_BOTTOM_RATIO = 0.80f

/** 切页最小位移：屏高的 20%，并且不低于 80dp（小屏上防止一滑就切） */
private const val PAGE_SWIPE_MIN_DISTANCE_RATIO = 0.20f
private const val PAGE_SWIPE_MIN_DISTANCE_DP = 80f

/** 甩动速度阈值（dp/s）：位移不够但甩得够快也允许切页 */
private const val PAGE_SWIPE_MIN_VELOCITY_DP_PER_SEC = 1000f

/**
 * 速度估计只看最近这么长的一段位移（毫秒）。
 * 拖到一半停住再松手，最后一段位移接近 0，就不会被算成「甩」。
 */
private const val PAGE_SWIPE_VELOCITY_WINDOW_MS = 120L

/** 主轴判定：纵向位移至少是横向的 1.5 倍才算切页，斜着拖不切 */
private const val PAGE_SWIPE_AXIS_RATIO = 1.5f

/**
 * B 站缓存的全屏播放页。
 *
 * ## 为什么用系统 MediaPlayer 而不是 ExoPlayer
 *
 * B 站缓存的音视频轨先由 [com.sonance.player.data.bili.BiliMp4Writer] 合成一个
 * 标准 MP4。这个文件系统 `MediaExtractor` 能读出两条轨（写完后有自检日志），
 * ffmpeg 也认，但 Media3 的 `Mp4Extractor` 解析出来是 **0 条轨**，导致
 * `index (0) must be less than size (0)`。
 *
 * 既然系统解封装器认可这个文件，就继续用系统 `MediaPlayer` 播；渲染层从
 * `VideoView`（内部是 SurfaceView）换成 `TextureView`，并把原本 `VideoView`
 * 包办的「surface 就绪 → 绑定 → 尺寸变化 → 销毁」交给 [BiliVideoPlayer] 的
 * 状态机自己走一遍 —— 提亮必须在送显前插一次 shader，SurfaceView 做不到。
 *
 * ## 交互模型（本轮重做）
 *
 * - 控件 = 顶部标题栏 + 底部进度条 + 操作菜单，**同一套显隐状态**；
 *   单击显示，再单击同时隐藏，隐藏时不留任何浮层。
 * - 菜单只在控件可见时存在；纵向切页 / 横向 seek 拖动期间菜单强制隐藏，
 *   进度条保留并继续跟随；拖动落定后控件短暂恢复。
 * - 菜单位置只在「隐藏 → 显示」那一刻决定；拖动菜单会记位置（屏幕比例），
 *   跨视频、跨会话沿用，不再重置到屏幕正中。
 * - 上下切换由 [VerticalPager] 承担整页滚动，落定后才切换正在播放的实例。
 * - 前后各预载 [PRELOAD_PAGES] 页，但预载的只是**合成好的 MP4 + 首帧图**，
 *   不提前创建播放器实例：相邻页的播放器会各自占一个硬件解码器，
 *   多条同时 prepare 时部分机型直接回 `-38`（解码器初始化失败），
 *   滑过去就停在 0:00 黑屏；退出重进只创建一条实例，所以又能播。
 * - 只有落定页持有 [BiliVideoPlayer]，任何时刻最多一个解码器；页失去当前身份即释放。
 */
class VideoPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoPath = intent.getStringExtra(EXTRA_VIDEO)
        if (videoPath.isNullOrBlank()) {
            finish()
            return
        }

        // 横屏 + 常亮 + 沉浸：视频页的默认姿态，用户按住系统手势可以临时唤出状态栏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme {
                VideoStage(
                    startPath = videoPath,
                    keys = intent.getStringArrayListExtra(EXTRA_KEYS) ?: ArrayList(),
                    startKey = intent.getStringExtra(EXTRA_KEY),
                    // 转屏等重建沿用会话里的当前条目；全新启动则以 Intent 里点的这条为准
                    restored = savedInstanceState != null,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO = "video_path"
        const val EXTRA_KEY = "entry_key"
        const val EXTRA_KEYS = "entry_keys"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_OWNER = "video_owner"
        const val EXTRA_AUDIO = "audio_path"
    }
}

/** 一页视频。直接唤起、拿不到列表时 [entry] 为 null，只有 [fallbackPath] */
private data class VideoPageItem(
    val key: String,
    val entry: BiliEntry?,
    val fallbackPath: String?
)

@Composable
private fun VideoStage(
    startPath: String,
    keys: List<String>,
    startKey: String?,
    restored: Boolean,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BiliRepository(context) }
    val prefs = remember { Prefs(context) }
    val density = LocalDensity.current

    /**
     * 播放实例的唯一持有者。全屏页只是它的宿主视图：退出 Activity / 转浮窗都不销毁解码器，
     * 所以浮窗能跨页面继续播、后台/锁屏也能续播。
     */
    val session = remember { BiliVideoSession.of(context) }

    // 只有能播的才进播放列表，否则上下滑会跳到点不开的条目上
    val playlist = remember(keys) {
        if (startKey.isNullOrBlank()) emptyList()
        else keys.mapNotNull { repo.entryByKey(it) }.filter { it.playable }
    }
    val pages = remember(playlist, startKey, startPath) {
        if (playlist.isEmpty()) {
            listOf(VideoPageItem(key = "__direct__", entry = null, fallbackPath = startPath))
        } else {
            playlist.map { VideoPageItem(key = it.key, entry = it, fallbackPath = null) }
        }
    }
    val hasList = pages.size > 1
    /**
     * 只有转屏/进程内重建时才沿用会话里正在播的那条。
     * 全新启动（含浮窗点回全屏，它会把当前 key 带进 Intent）必须以 [startKey] 为准：
     * 否则在浮窗里点列表另一条时，这里会拿旧的 current 顶掉点击目标，
     * 全屏页就停在上一修视频上。
     */
    val inheritedKey = if (restored) {
        session.current?.key?.takeIf { k -> pages.any { it.key == k } }
    } else {
        null
    }
    val resumeKey = inheritedKey ?: startKey
    val startIndex = remember(pages, resumeKey) {
        pages.indexOfFirst { it.key == resumeKey }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val settledPage = pagerState.settledPage

    /** 合成 MP4 的并发闸门 */
    val prepareGate = remember { Semaphore(PREPARE_PARALLEL) }

    val fallbackTitle = intentTitle(context)
    /** 会话播放列表与 pager 页一一对应 */
    val sessionItems = remember(pages, fallbackTitle) {
        pages.map { page ->
            BiliVideoSession.Item(
                key = page.key,
                title = page.entry?.displayTitle ?: fallbackTitle,
                owner = page.entry?.owner.orEmpty(),
                entry = page.entry,
                fallbackPath = page.fallbackPath
            )
        }
    }
    LaunchedEffect(sessionItems) {
        session.load(sessionItems, resumeKey)
    }

    // 播放状态一律读会话（全屏页与浮窗同一份，切宿主不会不同步）
    val isPlaying = session.isPlaying
    val position = session.position
    val duration = session.duration

    var controlsVisible by remember { mutableStateOf(true) }
    var sliding by remember { mutableStateOf<Float?>(null) }
    /** 横向 seek 手势进行中（此时菜单必须隐藏，进度条保留） */
    var seeking by remember { mutableStateOf(false) }
    /** 纵向切页手势进行中（单页时 pager 不会滚动，也要靠它隐藏菜单） */
    var verticalDragging by remember { mutableStateOf(false) }
    /** 直接拖进度条（Slider）进行中，同样隐藏菜单 */
    var sliderActive by remember { mutableStateOf(false) }

    /** 纵向拖动过程中的方向提示（下一个 / 上一个 / 边界） */
    var dragHint by remember { mutableStateOf<String?>(null) }
    /** 边界与「没有下一条」的短提示，1.6 秒后自动消失 */
    var gestureMessage by remember { mutableStateOf<String?>(null) }

    /**
     * 操作菜单的位置（屏幕像素坐标里的**中心点**）。
     *
     * 初始值来自持久化：拖过 → 用拖动位置；没拖过 → 用上次点击锚点；
     * 都没有 → 屏幕正中。之后只有「隐藏 → 显示」时才可能更新。
     */
    val screenWpx = context.resources.displayMetrics.widthPixels.toFloat()
    val screenHpx = context.resources.displayMetrics.heightPixels.toFloat()
    var menuCenter by remember {
        val dragged = prefs.biliMenuDragged
        val rx = if (dragged) prefs.biliMenuX else prefs.biliMenuAnchorX
        val ry = if (dragged) prefs.biliMenuY else prefs.biliMenuAnchorY
        mutableStateOf(
            if (rx >= 0f && ry >= 0f) Offset(rx * screenWpx, ry * screenHpx)
            else Offset(screenWpx / 2f, screenHpx / 2f)
        )
    }

    /** 三态播放模式，存的是 ordinal，取的时候夹范围防止读到脏值 */
    var modeOrdinal by remember {
        mutableIntStateOf(prefs.biliPlayMode.coerceIn(0, PlayMode.entries.size - 1))
    }

    fun mode(): PlayMode = PlayMode.entries.getOrNull(modeOrdinal) ?: PlayMode.REPEAT_ALL

    fun cycleMode() {
        modeOrdinal = (modeOrdinal + 1) % PlayMode.entries.size
        prefs.biliPlayMode = modeOrdinal
    }

    /**
     * 画面提亮档位。存在 Prefs 里，跨视频、跨会话沿用 ——
     * 切页或换下一集都不重置，用户调一次就该一直是那个档。
     */
    var gainOrdinal by remember {
        mutableIntStateOf(prefs.biliBrightnessGain)
    }
    val gainLevel = GainLevel.fromOrdinal(gainOrdinal)

    fun changeGainLevel(level: GainLevel) {
        if (level.ordinal == gainOrdinal) return
        gainOrdinal = level.ordinal
        // 提亮作用在会话里的唯一实例上；浮窗占用同一实例，因此浮窗里同样生效
        session.setGain(level)
        Log.i(TAG, "提亮档位切换: ${level.label} gain=${level.gain}")
    }

    /** 底部工具栏里的「提亮」档位选择器是否展开 */
    var brightnessPanelVisible by remember { mutableStateOf(false) }

    /**
     * 锁屏 / 后台继续播放开关。
     *
     * 初值读 [Prefs.biliBackgroundPlay]（用户上次的选择，默认关），改动立即写盘；
     * 打开后由 [BiliVideoService] 把进程钉在前台，切后台 / 锁屏 / 熄屏都继续出声。
     * 放在底栏最右侧、和提亮太阳并排，只占一个图标位，不新增常驻大按钮。
     */
    var backgroundPlay by remember { mutableStateOf(prefs.biliBackgroundPlay) }

    fun toggleBackgroundPlay() {
        backgroundPlay = !backgroundPlay
        session.setBackgroundPlay(backgroundPlay)
        gestureMessage = if (backgroundPlay) {
            "锁屏 / 后台继续播放：已开启"
        } else {
            "锁屏 / 后台继续播放：已关闭"
        }
    }

    val currentItem = pages.getOrNull(settledPage)

    /** 跳到指定页；越界时给一句可读提示，而不是无响应 */
    fun goToPage(target: Int) {
        if (target !in pages.indices) {
            gestureMessage = if (target < 0) "已经是第一个" else "已经是最后一个"
            return
        }
        scope.launch { pagerState.animateScrollToPage(target) }
    }

    // 落定换页 → 交给会话换条目，并短暂显示控件（用户刚滑完，给一眼确认）
    LaunchedEffect(settledPage) {
        sliding = null
        seeking = false
        dragHint = null
        gestureMessage = null
        controlsVisible = true
        session.selectPage(settledPage)
    }

    // 会话自动连播跨页时把 pager 带过去；用户手动滑动时 settledPage 先变，这里不会抢滚动
    LaunchedEffect(session.index) {
        val target = session.index
        if (target in pages.indices && target != pagerState.settledPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 3 秒自动隐藏。拖动（切页 / seek / 拖 Slider）或正在选提亮档位时不计时。
    LaunchedEffect(
        controlsVisible, sliding, seeking, sliderActive, brightnessPanelVisible,
        pagerState.isScrollInProgress
    ) {
        if (controlsVisible && sliding == null && !seeking && !sliderActive &&
            !brightnessPanelVisible && !pagerState.isScrollInProgress
        ) {
            delay(3_000)
            controlsVisible = false
        }
    }

    // 控件整体隐藏时收起档位选择器，避免下次唤出时莫名展开
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) brightnessPanelVisible = false
    }

    // 边界提示自动消失
    LaunchedEffect(gestureMessage) {
        val message = gestureMessage ?: return@LaunchedEffect
        delay(1_600)
        if (gestureMessage == message) gestureMessage = null
    }

    // 离开页面 / 进入后台：转浮窗时不动；后台续播开关打开时也不动；否则暂停，回来原地续播
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeAfterPause by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfterPause = session.onHostPause(enteringFloating = session.floating)
                }

                Lifecycle.Event.ON_RESUME -> {
                    session.onHostResume(resumeAfterPause)
                    resumeAfterPause = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 返回键：不是转浮窗就结束播放；转浮窗时播放器要留在会话里继续播
    BackHandler {
        if (!session.floating) session.stop()
        onBack()
    }

    /**
     * 底部系统手势区 = 48dp + navigation bar inset。
     * 这里不参与切页拖动（纵向拖动由 [Modifier.gateVerticalPageSwipe] 拦住），
     * 但单击照常显隐控件 —— 点击不会被本页 consume，系统「上滑回桌面」仍归系统。
     */
    val bottomSafePx = with(density) { 48.dp.toPx() } +
        WindowInsets.navigationBars.getBottom(density)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(pages.size, bottomSafePx) {
                val edge = 24.dp.toPx()

                awaitEachGesture {
                    // requireUnconsumed：从进度条 / 按钮 / 操作菜单上按下的手势交给它们
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val startX = down.position.x
                    val startY = down.position.y

                    // 左右边缘是系统返回手势区：整段手势放行给系统。
                    // 底部系统手势带不在这里整段放行 —— 那里只是「不参与切页」，
                    // 单击仍要能显隐控件；纵向拖动由 gateVerticalPageSwipe 拦住，不进 pager。
                    if (startX < edge || startX > size.width - edge) {
                        return@awaitEachGesture
                    }

                    // 切页只认屏幕纵向 20%~80% 的起手点（再扣掉底部系统手势带）
                    val startTopPx = size.height * PAGE_SWIPE_START_TOP_RATIO
                    val startBottomPx = min(
                        size.height * PAGE_SWIPE_START_BOTTOM_RATIO,
                        size.height - bottomSafePx
                    )
                    // 最小切页位移：屏高 20%，且不低于 80dp
                    val minSwitchPx = max(
                        size.height * PAGE_SWIPE_MIN_DISTANCE_RATIO,
                        PAGE_SWIPE_MIN_DISTANCE_DP.dp.toPx()
                    )
                    /** 这次手势的起手点是否落在允许切页的区间里 */
                    val startAllowed = startY in startTopPx..startBottomPx

                    // 速度估计只用最近一小段位移（(时间, 位移) 采样），见 PAGE_SWIPE_VELOCITY_WINDOW_MS
                    val samples = ArrayList<Pair<Long, Float>>(8)

                    var axis = 0 // 0 未判定，1 横向 seek，2 纵向切页
                    val seekBase = position.toFloat()
                    var lastDx = 0f
                    var lastDy = 0f
                    var released = false

                    while (!released) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        lastDx = change.position.x - startX
                        lastDy = change.position.y - startY

                        val slop = viewConfiguration.touchSlop
                        if (axis == 0 && lastDx * lastDx + lastDy * lastDy > slop * slop) {
                            // 先判主轴：横向位移更大才算 seek，否则交给 pager 纵向滚动
                            axis = if (abs(lastDx) > abs(lastDy)) 1 else 2
                            if (axis == 1) seeking = controlsVisible && duration > 0L
                            if (axis == 2) verticalDragging = true
                        }

                        if (axis == 1) {
                            if (seeking) {
                                val target =
                                    (seekBase + lastDx / size.width.toFloat() * duration.toFloat())
                                        .coerceIn(0f, duration.toFloat())
                                sliding = target
                                change.consume()
                            }
                        } else if (axis == 2) {
                            // 纵向交给 VerticalPager；这里只读位移做方向提示，不消费事件。
                            // 起手区不对、斜向拖动、或位移没到切页门槛，都不给提示（静默回弹）
                            val verticalDominant =
                                abs(lastDy) >= abs(lastDx) * PAGE_SWIPE_AXIS_RATIO
                            dragHint = if (startAllowed && verticalDominant &&
                                abs(lastDy) >= minSwitchPx
                            ) {
                                val index = pagerState.settledPage
                                when {
                                    lastDy < 0f -> when {
                                        !hasList -> "没有下一个视频"
                                        index >= pages.lastIndex -> "已经是最后一个"
                                        else -> "下一个视频"
                                    }

                                    else -> when {
                                        !hasList -> "没有上一个视频"
                                        index <= 0 -> "已经是第一个"
                                        else -> "上一个视频"
                                    }
                                }
                            } else {
                                null
                            }
                        }

                        // 只保留最近 PAGE_SWIPE_VELOCITY_WINDOW_MS 的采样用于估算甩动速度
                        samples.add(change.uptimeMillis to lastDy)
                        while (samples.size > 1 && change.uptimeMillis - samples.first().first >
                            PAGE_SWIPE_VELOCITY_WINDOW_MS
                        ) {
                            samples.removeAt(0)
                        }

                        if (!change.pressed) {
                            released = true
                            when (axis) {
                                0 -> {
                                    // 单击：位移没超过 touchSlop 就算点击（容忍手指抖动）；
                                    // 没达到切页 / seek 阈值就松手，也按点击处理。
                                    // 这些判断只看位移，不看起手区，所以顶部/底部受限区点击照常生效
                                    if (abs(lastDx) < slop && abs(lastDy) < slop) {
                                        if (!controlsVisible) {
                                            // 只在「隐藏 → 显示」这一刻定位菜单；
                                            // 隐藏时不重新定位，保持上次的位置
                                            menuCenter = change.position
                                            if (!prefs.biliMenuDragged) {
                                                prefs.biliMenuAnchorX =
                                                    (change.position.x / size.width).coerceIn(0f, 1f)
                                                prefs.biliMenuAnchorY =
                                                    (change.position.y / size.height).coerceIn(0f, 1f)
                                            }
                                        }
                                        controlsVisible = !controlsVisible
                                    }
                                }

                                1 -> {
                                    if (seeking) {
                                        sliding?.let { target ->
                                            runCatching { session.seekTo(target.toInt()) }
                                        }
                                        sliding = null
                                        seeking = false
                                    }
                                }

                                2 -> {
                                    val index = pagerState.settledPage
                                    val verticalDominant =
                                        abs(lastDy) >= abs(lastDx) * PAGE_SWIPE_AXIS_RATIO

                                    when {
                                        // 起手在顶部 / 底部：gate 已经拦住 pager，这里什么都不做
                                        !startAllowed -> Unit

                                        // 斜向拖动：不算切页，静默回弹，不弹提示
                                        !verticalDominant ->
                                            scope.launch { pagerState.animateScrollToPage(index) }

                                        else -> {
                                            // 甩动速度只取最近的采样窗口：拖到一半停住再松手不算「甩」
                                            val windowStart = samples.firstOrNull()
                                            val elapsedMs = windowStart
                                                ?.let {
                                                    (change.uptimeMillis - it.first)
                                                        .coerceAtLeast(1L)
                                                }
                                                ?: 1L
                                            val speed = windowStart
                                                ?.let {
                                                    abs(lastDy - it.second) / density.density /
                                                        (elapsedMs / 1000f)
                                                }
                                                ?: 0f
                                            val qualified = abs(lastDy) >= minSwitchPx ||
                                                speed >= PAGE_SWIPE_MIN_VELOCITY_DP_PER_SEC

                                            if (qualified) {
                                                val delta = if (lastDy < 0f) 1 else -1
                                                val target = index + delta
                                                if (target in pages.indices) {
                                                    scope.launch {
                                                        pagerState.animateScrollToPage(target)
                                                    }
                                                } else {
                                                    // 首尾：回弹并说明，不循环
                                                    scope.launch {
                                                        pagerState.animateScrollToPage(index)
                                                    }
                                                    gestureMessage = when {
                                                        !hasList && delta > 0 -> "没有下一个视频"
                                                        !hasList -> "没有上一个视频"
                                                        delta > 0 -> "已经是最后一个"
                                                        else -> "已经是第一个"
                                                    }
                                                }
                                            } else {
                                                // 位移和速度都不够：静默回弹
                                                scope.launch {
                                                    pagerState.animateScrollToPage(index)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    dragHint = null
                    seeking = false
                    verticalDragging = false
                }
            }
    ) {
        val maxWpx = with(density) { maxWidth.toPx() }
        val maxHpx = with(density) { maxHeight.toPx() }
        val panelWidth = if (hasList) 292.dp else 148.dp
        val panelHeight = 64.dp
        val panelWpx = with(density) { panelWidth.toPx() }
        val panelHpx = with(density) { panelHeight.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val topPx = with(density) { 56.dp.toPx() }
        val bottomPx = with(density) { 96.dp.toPx() }

        fun persistMenuPosition() {
            val clamped = clampMenuCenter(
                menuCenter, panelWpx, panelHpx, maxWpx, maxHpx, marginPx, topPx, bottomPx
            )
            prefs.biliMenuDragged = true
            prefs.biliMenuX = (clamped.x / maxWpx).coerceIn(0f, 1f)
            prefs.biliMenuY = (clamped.y / maxHpx).coerceIn(0f, 1f)
        }

        val clampedCenter = clampMenuCenter(
            menuCenter, panelWpx, panelHpx, maxWpx, maxHpx, marginPx, topPx, bottomPx
        )
        val panelX = (clampedCenter.x - panelWpx / 2f).roundToInt()
        val panelY = (clampedCenter.y - panelHpx / 2f).roundToInt()

        /** 菜单只在控件可见且没有拖动时出现 */
        val menuVisible = controlsVisible &&
            !pagerState.isScrollInProgress &&
            !seeking &&
            !sliderActive &&
            !verticalDragging

        // 整页纵向 pager：每一页都是全屏视频页，滚动过程中页面（连同画面）跟手移动
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                // 起手点不在允许区间（顶部 / 底部 / 系统手势带）时，纵向拖动不进 pager，
                // 避免轻微上下一滑就整页切换；单击、横向拖动不受影响
                .gateVerticalPageSwipe(
                    PAGE_SWIPE_START_TOP_RATIO,
                    PAGE_SWIPE_START_BOTTOM_RATIO,
                    bottomSafePx
                ),
            beyondViewportPageCount = PRELOAD_PAGES,
            key = { pages[it].key }
        ) { page ->
            VideoPage(
                index = page,
                item = pages[page],
                isCurrent = page == settledPage,
                distance = abs(page - settledPage),
                repo = repo,
                gate = prepareGate,
                session = session
            )
        }

        // 告诉 B 站缓存列表「这条正在播放」，删除时会被拒绝并提示；
        // 切到别的页时同步更新，退出播放页清空
        LaunchedEffect(settledPage) {
            BiliPlaybackState.activeKey = pages.getOrNull(settledPage)?.key
        }
        DisposableEffect(Unit) {
            onDispose { BiliPlaybackState.activeKey = null }
        }

        // 顶部标题栏 + 底部进度条：控件整体的显隐
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160))
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = SonanceIcons.ArrowLeft,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { onBack() }
                                .padding(2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = currentItem?.entry?.displayTitle
                                    ?: intentTitle(context),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val owner = currentItem?.entry?.owner?.takeIf { it.isNotBlank() }
                            if (owner != null) {
                                Text(
                                    text = owner,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1
                                )
                            }
                        }
                        // 缩到应用内浮窗：播放器留在会话里，Activity 退出不影响出声音
                        Icon(
                            imageVector = SonanceIcons.Minimize,
                            contentDescription = "浮窗播放",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(24.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    session.enterFloating()
                                    (context as? android.app.Activity)?.finish()
                                }
                                .padding(2.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // 提亮档位选择器：贴在进度条上方，点右下角太阳图标展开
                    if (brightnessPanelVisible) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "画面提亮",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.weight(1f))
                            GainLevel.entries.forEach { level ->
                                val selected = level == gainLevel
                                Box(
                                    Modifier
                                        .padding(start = 6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected) Color.White.copy(alpha = 0.92f)
                                            else Color.White.copy(alpha = 0.16f)
                                        )
                                        .clickable { changeGainLevel(level) }
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = level.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPlaying) SonanceIcons.Pause else SonanceIcons.Play,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier
                                .size(30.dp)
                                .clickable { session.toggle() }
                                .padding(4.dp)
                        )

                        Text(
                            text = formatClock(sliding?.toLong() ?: position),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Slider(
                            value = sliding ?: position.toFloat().coerceAtLeast(0f),
                            onValueChange = {
                                sliderActive = true
                                sliding = it
                            },
                            onValueChangeFinished = {
                                sliding?.let { target ->
                                    runCatching { session.seekTo(target.toInt()) }
                                }
                                sliding = null
                                sliderActive = false
                            },
                            valueRange = 0f..(duration.takeIf { it > 0 }?.toFloat() ?: 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f).height(24.dp)
                        )

                        Text(
                            text = formatClock(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 8.dp)
                        )

                        Icon(
                            imageVector = SonanceIcons.Sun,
                            contentDescription = "画面提亮",
                            tint = if (gainLevel.isActive) Color.White
                            else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { brightnessPanelVisible = !brightnessPanelVisible }
                                .padding(3.dp)
                        )

                        // 锁屏 / 后台继续播放开关：图标呈「月牙」，亮起 = 开。
                        // 和太阳同一尺寸、同一行，不占屏幕主体。
                        Icon(
                            imageVector = SonanceIcons.Moon,
                            contentDescription = if (backgroundPlay) {
                                "锁屏/后台继续播放：已开启"
                            } else {
                                "锁屏/后台继续播放：已关闭"
                            },
                            tint = if (backgroundPlay) Color.White
                            else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { toggleBackgroundPlay() }
                                .padding(3.dp)
                        )
                    }
                }
            }
        }

        // 操作菜单：独立一层，挂在 pager 之上；拖动时淡出，落定后再回来
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160))
        ) {
            ControlPanel(
                modifier = Modifier
                    .offset { IntOffset(panelX, panelY) }
                    // 拖动菜单本身 → 更新并持久化位置（按屏幕比例）
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { persistMenuPosition() },
                            onDragCancel = { persistMenuPosition() }
                        ) { change, drag ->
                            change.consume()
                            menuCenter = clampMenuCenter(
                                menuCenter + drag,
                                panelWpx, panelHpx, maxWpx, maxHpx, marginPx, topPx, bottomPx
                            )
                        }
                    },
                hasList = hasList,
                isPlaying = isPlaying,
                mode = mode(),
                onPrev = { goToPage(settledPage - 1) },
                onNext = {
                    val target = if (mode() == PlayMode.SHUFFLE && pages.size > 1) {
                        pages.indices.filter { it != settledPage }.random()
                    } else {
                        settledPage + 1
                    }
                    goToPage(target)
                },
                onPlayPause = { session.toggle() },
                onCycleMode = { cycleMode() }
            )
        }

        // 纵向拖动时的方向提示
        dragHint?.let { hint ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }

        // 到边界 / 没有下一条的提示
        gestureMessage?.let { message ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 单页视频。
 *
 * - 预载（所有页）：先合成好的 MP4，再用 [MediaMetadataRetriever] 解一张首帧图垫底；
 * - 播放器（只有落定页）：[TextureView] 挂好、surface 真就绪之后才 `prepareAsync`，
 *   避免在 surface 还没就绪时 prepare（那正是 `-38` 的常见来源）；
 * - 页失去当前身份 → `onRelease` 里释放，所以任何时刻最多一个解码器实例。
 *
 * 提亮：`VideoView` 底下的 `SurfaceView` 在独立图层，窗口级颜色处理作用不到视频
 * 像素，所以这里换成 `TextureView` + [com.sonance.player.playback.VideoGlRenderer]。
 * 档位是 OFF 时不创建 GL 上下文，视频 Surface 直接交给 TextureView 自己显示。
 */
@Composable
private fun VideoPage(
    index: Int,
    item: VideoPageItem,
    isCurrent: Boolean,
    distance: Int,
    repo: BiliRepository,
    gate: Semaphore,
    session: BiliVideoSession
) {
    var path by remember(item.key) { mutableStateOf(item.fallbackPath) }
    var prepareProgress by remember(item.key) {
        mutableFloatStateOf(if (item.fallbackPath != null) 1f else 0f)
    }
    var prepareError by remember(item.key) { mutableStateOf<String?>(null) }
    var frame by remember(item.key) { mutableStateOf<Bitmap?>(null) }
    /** 这条缓存是不是 HDR：是的话整体旁路提亮，不套 SDR 曲线 */
    var hdr by remember(item.key) { mutableStateOf(false) }
    /** 容器头里的像素宽高比，创建播放器时带给渲染层 */
    var sarNum by remember(item.key) { mutableIntStateOf(1) }
    var sarDen by remember(item.key) { mutableIntStateOf(1) }

    val latestIsCurrent by rememberUpdatedState(isCurrent)

    // 预载：直接唤起时用 Intent 里的路径；列表条目则先合成 MP4。
    // 当前页跳过闸门，保证「马上要看的那条」优先；相邻页最多 2 条并发。
    // 这里只产出一个可播文件和首帧图，不创建 MediaPlayer，所以不会占解码器。
    LaunchedEffect(item.key) {
        val entry = item.entry ?: return@LaunchedEffect
        val outcome = if (latestIsCurrent) {
            repo.prepare(entry) { prepareProgress = it }
        } else {
            gate.withPermit { repo.prepare(entry) { prepareProgress = it } }
        }
        outcome.onSuccess { media ->
            // 首帧在 IO 线程解，别卡住主线程
            val bitmap = withContext(Dispatchers.IO) {
                decodeFirstFrame(media.file.absolutePath)
            }
            if (bitmap == null) {
                Log.w(TAG, "首帧解码失败（只剩黑底）: key=${item.key} path=${media.file.absolutePath}")
            }
            frame = bitmap
            // 容器头探测要在 path 之前落地：两者在同一次 composition 里生效，
            // 播放器创建时就能拿到正确的 HDR 结论与 SAR
            withContext(Dispatchers.IO) {
                probeVideo(media.file.absolutePath)
            }.let { probe ->
                hdr = probe.hdr
                sarNum = probe.sarNum
                sarDen = probe.sarDen
            }
            path = media.file.absolutePath
            if (hdr) Log.i(TAG, "HDR 内容，旁路提亮: key=${item.key}")
            Log.i(TAG, "MP4 就绪: key=${item.key} bytes=${media.file.length()}")
        }.onFailure { e ->
            if (e is CancellationException) throw e
            prepareError = e.message ?: "准备失败"
            Log.w(TAG, "合成失败: key=${item.key} err=${e.message}")
        }
    }

    // 当前页 + 文件已就绪 → 交给会话。路径由本页预载产出，会话不会再合成一遍；
    // 若会话里已经有同一个 key 的实例（浮窗 → 全屏），这里只换宿主，不重启。
    LaunchedEffect(isCurrent, path) {
        val p = path ?: return@LaunchedEffect
        if (isCurrent) session.openPage(index, item.key, p, hdr, sarNum, sarDen)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 播放器视图只给落定页：距离 > 0 的页只显示首帧图。
        // 实例本身由会话持有，任何时刻最多一个 MediaPlayer。
        val mediaPath = path
        if (mediaPath != null && distance == 0) {
            // 按视频宽高比收缩并居中：等价于 VideoView 自己 measure 的结果，
            // 等比完整显示、不裁切、不变形，多余区域露出外层黑底
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = if (isCurrent && session.videoAspect > 0f) {
                        Modifier.aspectRatio(session.videoAspect)
                    } else {
                        Modifier.fillMaxSize()
                    },
                    factory = { ctx ->
                        // 用 TextureView 而不是 VideoView：VideoView 底下是
                        // SurfaceView，图层独立，窗口级的颜色/效果都作用不到视频
                        // 像素；提亮必须在送显前插一次自己的 shader。
                        val tv = TextureView(ctx)
                        session.attachHost(tv)
                        tv
                    },
                    onRelease = { tv ->
                        session.detachHost(tv)
                    }
                )
            }
        }

        // 首帧图盖在播放器上，只有真正出画（prepared）之后才让位；
        // 这样滑过去不会先闪一块黑，等解码的那一下也有画面。
        // 用 Fit 而不是 Crop：否则首帧会被裁掉两边，和真实画面比例对不上
        val ready = isCurrent && session.prepared && session.playingKey == item.key
        if (!ready) {
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // 合成进度：允许短暂等待，但要有进度感而不是空白
        if (path == null && prepareError == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    progress = { prepareProgress },
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "准备视频… ${(prepareProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        val videoError = if (isCurrent && session.playingKey == item.key) session.error else null
        if (path != null && !ready && frame == null && videoError == null) {
            Box(Modifier.align(Alignment.Center)) {
                Text("正在起播…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        // 失败提示：给人话 + 原始错误码，并留一个「重试」（重建实例）的出口，
        // 不再只是 0:00 的静止画面
        (prepareError ?: videoError)?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                if (videoError != null && prepareError == null && distance == 0) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable {
                                Log.i(TAG, "retry player: key=${item.key}")
                                session.retry()
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "重试",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** 把菜单中心点夹在安全边距内，保证整个面板不出屏、不压住标题栏 / 进度条 */
private fun clampMenuCenter(
    center: Offset,
    panelWidthPx: Float,
    panelHeightPx: Float,
    maxW: Float,
    maxH: Float,
    marginPx: Float,
    topPx: Float,
    bottomPx: Float
): Offset {
    val halfW = panelWidthPx / 2f
    val halfH = panelHeightPx / 2f
    val minX = marginPx + halfW
    val maxX = (maxW - marginPx - halfW).coerceAtLeast(minX)
    val minY = topPx + halfH
    val maxY = (maxH - bottomPx - halfH).coerceAtLeast(minY)
    return Offset(
        center.x.coerceIn(minX, maxX),
        center.y.coerceIn(minY, maxY)
    )
}

/**
 * 上下切页的门槛：起手点不在允许区间时，把纵向拖动从 pager 手里拦下来。
 *
 * - 起手点在 `[startTopRatio, startBottomRatio]` 高度区间内、且不在底部 [bandPx]
 *   系统手势带里 → 放行，交给 [VerticalPager] 做跟手滚动；
 * - 起手点在顶部 / 底部 → 累积位移，一旦纵向位移超过 touch slop 且纵向占优就 consume，
 *   pager 放弃这次拖拽（页不动，也就不会误切）。
 *
 * 只用 [PointerEventPass.Initial]（父先于 pager 的内部 scrollable 收到事件），
 * 且只在判定为纵向拖动后才 consume：单击与横向 seek 完全不受影响。
 */
private fun Modifier.gateVerticalPageSwipe(
    startTopRatio: Float,
    startBottomRatio: Float,
    bandPx: Float
): Modifier = this.pointerInput(startTopRatio, startBottomRatio, bandPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startY = down.position.y
        val startTopPx = size.height * startTopRatio
        val startBottomPx = min(size.height * startBottomRatio, size.height - bandPx)
        if (startY in startTopPx..startBottomPx) return@awaitEachGesture

        var totalDy = 0f
        var totalDx = 0f
        var blocked = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            totalDy += change.positionChange().y
            totalDx += change.positionChange().x
            if (!blocked && abs(totalDy) > viewConfiguration.touchSlop &&
                abs(totalDy) > abs(totalDx)
            ) {
                blocked = true
            }
            if (blocked) change.consume()
        }
    }
}

/**
 * 解一帧缩略图，顺手缩到 960 宽：预载窗口里最多 5 张，全分辨率会白占几十 MB。
 * 失败返回 null，调用方退回纯黑底，不影响播放。
 */
private fun decodeFirstFrame(path: String): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val frame = retriever.getFrameAtTime(0)
        if (frame != null && frame.width > THUMB_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(
                frame,
                THUMB_WIDTH,
                (frame.height * THUMB_WIDTH.toFloat() / frame.width).roundToInt(),
                true
            )
            if (scaled !== frame) frame.recycle()
            scaled
        } else {
            frame
        }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/** 首帧缩略图的目标宽度 */
private const val THUMB_WIDTH = 960

/**
 * 浮在画面上的操作面板。
 *
 * 位置由外层算好后通过 [modifier] 传入；拖动面板本身会把新位置写回 Prefs。
 * 播放 / 暂停、上下条、播放模式都收在这里，控件可见且未拖动时一直可用。
 */
@Composable
private fun ControlPanel(
    modifier: Modifier = Modifier,
    hasList: Boolean,
    isPlaying: Boolean,
    mode: PlayMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onCycleMode: () -> Unit
) {
    val modeIcon = when (mode) {
        PlayMode.SHUFFLE -> SonanceIcons.Shuffle
        PlayMode.REPEAT_ONE -> SonanceIcons.RepeatOne
        PlayMode.REPEAT_ALL -> SonanceIcons.Repeat
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.62f))
            // 吃掉面板空白处的点击，避免点面板反而把控件藏起来
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        if (hasList) {
            PanelButton(
                icon = SonanceIcons.Prev,
                contentDescription = "上一曲",
                onClick = onPrev
            )
        }
        PanelButton(
            icon = if (isPlaying) SonanceIcons.Pause else SonanceIcons.Play,
            contentDescription = if (isPlaying) "暂停" else "播放",
            size = 34.dp,
            onClick = onPlayPause
        )
        if (hasList) {
            PanelButton(
                icon = SonanceIcons.Next,
                contentDescription = "下一曲",
                onClick = onNext
            )
            // 竖线分隔：传输控件和模式切换是两类动作，混在一起会按错
            Box(
                Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(Color.White.copy(alpha = 0.22f))
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCycleMode)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = modeIcon,
                    contentDescription = mode.label,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun PanelButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    onClick: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    )
}

/** 拿不到列表（直接被唤起）时退回 Intent 里带的标题 */
@Composable
private fun intentTitle(context: android.content.Context): String {
    return remember {
        (context as? android.app.Activity)
            ?.intent
            ?.getStringExtra(VideoPlayerActivity.EXTRA_TITLE)
            .orEmpty()
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
