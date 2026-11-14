package com.sonance.player.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverTint
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PcmBus
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.PlayMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.isCompactLandscapePhone
import com.sonance.player.ui.theme.HiResBadgeSize
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/* ============================================================
 * 播放页「呼吸感」
 * ============================================================ */

/** 晕染强度的呼吸上限：现有浓度的 1.10 倍。只动绘制 alpha，不重烘位图 */
private const val BREATH_GLOW_MAX = 1.10f

/** 音频能量叠加的晕染峰值 +4%；封面微呼吸由 PlayerCoverMotion 管理。 */
private const val BREATH_AUDIO_GLOW = 0.04f

/** 晕染周期 5.2s、滞后 0.6s，不与封面微呼吸同时到顶。 */
private const val BREATH_GLOW_PERIOD_MS = 5200f
private const val BREATH_GLOW_OFFSET_MS = 600f

/** 统一包络：起播 450ms 渐入、暂停 600ms 归零 */
private const val BREATH_IN_MS = 450f
private const val BREATH_OUT_MS = 600f

/** 音频能量平滑：起音 60ms、释放 350ms（暂停后比包络先落，600ms 内一定收干净） */
private const val BREATH_AUDIO_ATTACK_MS = 60f
private const val BREATH_AUDIO_RELEASE_MS = 350f

/**
 * RMS → 0..1 的分贝映射区间。
 * 音乐正常播放的 RMS 大约在 -30 ~ -12dB，映射后落在 0.5~0.95；
 * 静音/间歇段低于 -50dB 直接归零，免得把底噪放大成「一直在喘」。
 */
private const val BREATH_ENERGY_FLOOR_DB = -50f
private const val BREATH_ENERGY_CEIL_DB = -10f

/**
 * 呼吸引擎的内部状态。
 *
 * 刻意**不用** snapshot state：包络 / 相位 / 能量只在帧循环里读写，
 * 每帧变化的是 [FullPlayer] 里那两个最终输出值。这样它们不会被组合期读取，
 * 也就不会逐帧触发重组。
 */
private class BreathEngine {
    var envelope = 0f
    var energy = 0f
    var phaseMs = 0f
}

/** 原始 RMS → 0..1 能量（分贝映射，接近人耳对响度的感受） */
private fun rmsToEnergy(rms: Float): Float {
    if (rms <= 0f) return 0f
    val db = 20f * log10(rms)
    return ((db - BREATH_ENERGY_FLOOR_DB) /
        (BREATH_ENERGY_CEIL_DB - BREATH_ENERGY_FLOOR_DB)).coerceIn(0f, 1f)
}

/**
 * 全屏播放页。竖向翻页（TikTok / 汽水音乐式）：整页上下滑动切歌，
 * 相邻页提前渲染（[androidx.compose.foundation.pager.PagerState] 的
 * `beyondViewportPageCount = 1`），120Hz 下流畅切换。
 */
@Composable
fun FullPlayer(
    state: PlayerUiState,
    queue: List<Song>,
    favorites: Set<Long>,
    onClose: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    /**
     * 三态播放模式切换（随机 → 列表循环 → 单曲循环）。
     * 原来是 onShuffle + onRepeat 两个按钮，用户要求合并成一个。
     */
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit = {},
    /** 打开均衡器面板 */
    onOpenEqualizer: () -> Unit = {},
    /** 打开睡眠定时器面板 */
    onOpenSleepTimer: () -> Unit = {},
    /** 打开沉浸式黑胶播放模式 */
    onOpenVinyl: () -> Unit = {},
    /** 睡眠定时器是否在计时中（顶栏图标高亮用） */
    sleepTimerActive: Boolean = false,
    /** 均衡器是否开着（图标高亮用） */
    eqEnabled: Boolean = false,
    /** 当前播放速度（0.5–2.5 倍） */
    playbackSpeed: Float = 1f,
    /** 设置播放速度 */
    onSetSpeed: (Float) -> Unit = {},
    /** 全屏歌词页是否隐藏系统状态栏（**只作用于歌词层**） */
    lyricsHideStatusBar: Boolean = false,
    /** 全屏歌词页：改「隐藏状态栏」开关 */
    onLyricsHideStatusBarChange: (Boolean) -> Unit = {},
    /** 律动柱页是否隐藏系统状态栏（**只作用于律动柱层**） */
    visualizerHideStatusBar: Boolean = false,
    /** 律动柱页：改「隐藏状态栏」开关 */
    onVisualizerHideStatusBarChange: (Boolean) -> Unit = {},
    /** 把「当前层要不要隐藏状态栏」上报给 Shell 统一落地 */
    onImmersiveChange: (Boolean) -> Unit = {},
    /** 播放页封面的颜色往哪边晕开 */
    glowMode: GlowMode = GlowMode.AROUND,
    /** 晕染浓度 */
    glowStrength: Float = 0.6f,
    /** 换歌时颜色的过渡方式：跟手 / 渐变 */
    glowTransition: GlowTransition = GlowTransition.SWIPE,
    /** 「渐变」模式的补间时长（毫秒） */
    glowFadeMs: Int = Prefs.DEFAULT_GLOW_FADE_MS,
    /** 双击封面那个面板改了包裹方式 */
    onGlowModeChange: (GlowMode) -> Unit = {},
    /** 双击封面那个面板改了浓度 */
    onGlowStrengthChange: (Float) -> Unit = {},
    /** 双击封面那个面板改了过渡方式 */
    onGlowTransitionChange: (GlowTransition) -> Unit = {},
    /** 双击封面那个面板改了补间时长 */
    onGlowFadeMsChange: (Int) -> Unit = {},
    /** 封面是否用「黑碟播放器」样式（黑胶圆盘，播放时缓慢旋转） */
    vinylMode: Boolean = false,
    /** 双击封面那个面板切黑碟模式 */
    onVinylModeChange: (Boolean) -> Unit = {},
    /** 是否在播放主界面封面外右下角显示 Hi-Res AUDIO 小金标 */
    hiResBadgeEnabled: Boolean = true,
    /** 小金标尺寸档位（小 / 中 / 大） */
    hiResBadgeSize: HiResBadgeSize = HiResBadgeSize.MEDIUM,
    /**
     * 当前这首在线歌**实际**拿到的音质（如 `320k` / `无损`）。本地歌为 null。
     *
     * 显示在歌手那一行的专辑后面，不占新的行 —— 信息区是固定三行高的，
     * 多一行会让相邻页高度不一致，滑动时封面会"跳一下"。
     */
    streamQuality: String? = null,
    /** 被沉浸式黑胶完全遮住时暂停仅供本页面使用的动画与预取。 */
    coveredByVinyl: Boolean = false,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val prefs = remember { com.sonance.player.data.Prefs(context) }
    val scope = rememberCoroutineScope()

    /**
     * 「歌词」那一行是否展开成了单行滚动歌词。
     *
     * 注意与左右滑动进入歌词层是两条路：歌词层是**全屏歌词页**（那里能看完整歌词、
     * 能点行跳转、能调字号/玻璃），这里的展开只在原地显示一行。
     */
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }

    /** 双击封面打开的「晕染」面板 */
    var showGlowSheet by rememberSaveable { mutableStateOf(false) }

    /**
     * 本页（FullPlayer 根 Box）在 root 坐标系里的左上角。
     *
     * `boundsInRoot()` 量出来的是相对 **Composition 根**的坐标，
     * 而晕染画在根 Box 的 drawBehind 里、用的是**根 Box 的局部坐标**。
     * 两者差一个偏移，不换算的话：区域小的模式（下方 / 左右 / 四周）会整体错位、
     * 看上去像「完全没画」；只有全屏因为范围极大，偏出去也能盖住屏幕，才蒙混过关。
     */
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }

    /** 封面在**本页坐标系**里的矩形 —— 由 PlayerPage 量出来上报，晕染靠它定位 */
    var coverBounds by remember { mutableStateOf<Rect?>(null) }

    // 歌曲分页器。**必须声明在晕染素材之前** —— 跟手模式要按它的滚动位置混合颜色。
    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // 基础缩放与呼吸合并成一个最终值；所有预览页都读取同一份尺寸快照。
    val coverMotion = remember { PlayerCoverMotion(state.isPlaybackActive) }
    val coverScaleState = remember { mutableFloatStateOf(coverMotion.scale) }
    var coverTouched by remember { mutableStateOf(false) }

    // ===== 晕染素材 =====
    //
    // 两种过渡方式共用同一批「已经烘好的位图」，区别只在**什么时候换**：
    // - SWIPE（跟手）：按分页器的滚动位置在相邻两页之间混合，颜色和页面位移同步；
    // - FADE（渐变）：页面落定之后，用 animateColorAsState 在设定时长内补间。
    val isSwipeGlow = glowTransition == GlowTransition.SWIPE

    /**
     * 位图烘焙用的「带余量」浓度。
     *
     * 呼吸要往上加 10%，但位图的浓度已经烘进像素里，每帧重烘是绝对不允许的。
     * 做法是把峰值先烘进去、平时用 draw alpha 压低：
     * `bake = strength × 1.10`，绘制时 `alpha = strength × breath / bake`（≤1）。
     * 这样 1.00 倍时视觉上正好是原浓度，1.10 倍时刚好用满位图的余量。
     * AROUND 走径向 Brush、不烘位图，直接按强度乘即可，所以保持原值。
     */
    val glowBakeStrength = remember(glowStrength, glowMode) {
        if (glowMode == GlowMode.AROUND) {
            glowStrength
        } else {
            (glowStrength * BREATH_GLOW_MAX).coerceIn(0f, 1f)
        }
    }

    /**
     * 这一帧要画的一到两层素材。
     *
     * SWIPE 下是 `[上一页, 当前页, 下一页]` 三份（按滚动位置取其中相邻的两份混合）；
     * FADE 下只有当前这一首。
     */
    var userDraggedPager by remember { mutableStateOf(false) }
    var programmaticScroll by remember { mutableStateOf(false) }

    val latestState by rememberUpdatedState(state)
    val latestQueue by rememberUpdatedState(queue)
    val latestCoveredByVinyl by rememberUpdatedState(coveredByVinyl)

    val curGlowColors = rememberGlowColors(queue.getOrNull(state.queueIndex), skin)
    val smoothGlow = remember(skin.id) {
        val initial = curGlowColors.ifEmpty {
            resolveGlowColors(queue.getOrNull(pagerState.currentPage), skin)
        }
        SmoothGlowController(initial, skin.bg)
    }
    val useSmoothGlow = !isSwipeGlow || smoothGlow.fadeActive

    LaunchedEffect(curGlowColors, useSmoothGlow, state.queueIndex, glowFadeMs, skin.id) {
        if (!useSmoothGlow || curGlowColors.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage == latestState.queueIndex }.first { it }
        if (!smoothGlow.hasColors) {
            smoothGlow.snapTo(curGlowColors, skin.bg)
        } else {
            val c0 = curGlowColors.getOrElse(0) { skin.bg }
            val c1 = curGlowColors.getOrElse(1) { c0 }
            val c2 = curGlowColors.getOrElse(2) { c1 }
            val spec: AnimationSpec<Color> = tween(glowFadeMs.coerceIn(GLOW_FADE_RANGE))
            coroutineScope {
                launch { smoothGlow.anim0.animateTo(c0, spec) }
                launch { smoothGlow.anim1.animateTo(c1, spec) }
                launch { smoothGlow.anim2.animateTo(c2, spec) }
            }
        }
        smoothGlow.fadeActive = false
    }

    val smoothGlowColors = if (useSmoothGlow && smoothGlow.hasColors) {
        remember(smoothGlow.anim0.value, smoothGlow.anim1.value, smoothGlow.anim2.value) {
            listOf(smoothGlow.anim0.value, smoothGlow.anim1.value, smoothGlow.anim2.value)
        }
    } else {
        emptyList()
    }
    val smoothGlowFrame: GlowFrame = rememberGlowFrame(
        colors = smoothGlowColors,
        mode = glowMode,
        strength = glowBakeStrength
    )

    val buttonPaletteProvider = rememberPlayerControlPaletteProvider(state, queue.getOrNull(state.queueIndex))
    // 顶栏律动指示器的混色放在叶子组件 [HeaderPlayingIndicator] 的 Canvas 绘制阶段里算：
    // 滑动中每帧只触发该小 Canvas 重绘，不触发任何组合与测量，FullPlayer 零重组。

    // 预取相邻两首的色卡并提前烘入 LRU 位图缓存，让滑动跨过中点时零位图分配、零卡顿。
    // 只取前后各一首 —— 队列可能有几千首，不能遍历。
    LaunchedEffect(state.queueIndex, queue.size, coveredByVinyl, glowMode, glowBakeStrength, skin.id) {
        if (queue.isEmpty() || coveredByVinyl) return@LaunchedEffect
        val idx = state.queueIndex
        val candidates = listOfNotNull(
            queue.getOrNull(idx + 1),
            queue.getOrNull(idx - 1),
            queue.getOrNull(idx)
        )
        candidates.forEach { s ->
            ArtworkLoader.prefetchTint(
                context = context,
                albumId = s.albumId,
                sourceUri = s.uri,
                coverKey = OnlineCover.keyOf(s.title, s.artist)
            )
        }
        snapshotFlow {
            candidates.map { s -> resolveGlowColors(s, skin) }
        }.first { colorLists ->
            colorLists.forEach { colors ->
                if (colors.isNotEmpty()) {
                    cachedGlowFrame(colors, glowMode, glowBakeStrength)
                }
            }
            colorLists.all { it.isNotEmpty() }
        }
    }

    // 播放页的三层（封面 / 歌词 / 律动柱）做成**环形轮播**：
    // 页数取极大值、用「页号 % 3」映射到层，于是左右两个方向都能一直滑下去，
    // 到边自然绕回另一端。起始页取 3 的倍数，保证初始停在封面层。
    val layerPager = rememberPagerState(initialPage = LAYER_RING_START) { Int.MAX_VALUE }
    val layer = PlayerLayer.entries[layerPager.currentPage % PlayerLayer.entries.size]

    // 状态栏隐藏**按层各自生效**：歌词层只认歌词开关、律动柱层只认律动柱开关，
    // 封面主界面两个开关都不认（只保留手机横屏那条自动沉浸）。
    //
    // WindowInsetsController 是 Activity 级的，三层里各 hide/show 一次会互相打架
    // （相邻层还会被预组合），所以这里只算结果、上报给 Shell 一处落地。
    val immersiveConfig = LocalConfiguration.current
    // 手机横屏那条自动沉浸是独立的适配（不是设置开关），三层都保留，
    // 免得横屏切层时状态栏一闪一闪。竖屏下封面主界面不认任何开关。
    val compactLandscape = isCompactLandscapePhone(immersiveConfig)
    val immersive = compactLandscape || when (layer) {
        PlayerLayer.COVER -> false
        PlayerLayer.LYRICS -> lyricsHideStatusBar
        PlayerLayer.VISUALIZER -> visualizerHideStatusBar
    }
    LaunchedEffect(immersive) { onImmersiveChange(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChange(false) } }

    /**
     * 跳到指定层。
     *
     * 沿环取**更近**的那一侧：从封面直接点「歌词」应该右移一格，
     * 而不是往左绕两格、滑过律动柱。
     */
    fun goToLayer(target: PlayerLayer) {
        val n = PlayerLayer.entries.size
        val current = layerPager.currentPage
        val delta = ((target.ordinal - current % n) + n) % n
        val step = if (delta == n - 1) -1 else delta
        if (step == 0) return
        scope.launch { layerPager.animateScrollToPage(current + step) }
    }

    // 歌词 / 律动柱上按返回键 → 滑回封面层，而不是直接收起播放页。
    // 拖动中不接管，免得和手指的滑动打架。
    BackHandler(enabled = layer != PlayerLayer.COVER && !layerPager.isScrollInProgress) {
        goToLayer(PlayerLayer.COVER)
    }

    var pendingSwipeTarget by remember { mutableIntStateOf(-1) }
    var pendingSwipeUntilMs by remember { mutableLongStateOf(0L) }

    // 分页器 → 播放器：用户主动划动并在目标页静止后切歌。
    // 1) userDraggedPager 只能在 `!scrolling` 时清零：若在 `scrolling == true` 同一轮提前清掉，
    //    连续快速甩屏时 snapshotFlow 状态对未变化就会丢失拖拽标记，导致页停了却不切歌；
    // 2) 把 programmaticScroll 放进 snapshotFlow 依赖，避免刚完成程序跳页立刻拖走时漏判；
    // 3) 若仍在同队列内切页且确实由用户拖过，则允许按索引对齐（包括队列中存在同曲目副本）。
    LaunchedEffect(pagerState, queue.size) {
        snapshotFlow {
            Triple(pagerState.isScrollInProgress, pagerState.settledPage, programmaticScroll)
        }.collect { (scrolling, settledPage, progScroll) ->
            if (scrolling && !progScroll) {
                userDraggedPager = true
                smoothGlow.fadeActive = false
            }
            if (!scrolling) {
                if (queue.isNotEmpty() &&
                    userDraggedPager &&
                    !latestCoveredByVinyl &&
                    settledPage != latestState.queueIndex
                ) {
                    val pageSong = latestQueue.getOrNull(settledPage)
                    if (pageSong != null) {
                        pendingSwipeTarget = settledPage
                        pendingSwipeUntilMs = android.os.SystemClock.uptimeMillis() + 600L
                        onPlayAt(settledPage)
                    }
                }
                userDraggedPager = false
            }
        }
    }

    // 播放器 → 分页器：按钮切歌 / 自动续播 / 点队列 / 随机重排队列时把页面跟过去。
    // 若刚刚由滑屏触发了 pendingSwipeTarget，则 600ms 窗口内忽略尚未追上的中间态 queueIndex，
    // 防止快速连滑时旧 IPC 结果把分页器倒拽回上一首；窗口期结束后若仍不一致则自愈对齐真实曲目。
    LaunchedEffect(state.queueIndex, state.mediaId, queue, coveredByVinyl, pendingSwipeTarget) {
        if (queue.isEmpty() || coveredByVinyl) return@LaunchedEffect
        val target = state.queueIndex.coerceIn(0, queue.lastIndex)
        if (pendingSwipeTarget >= 0) {
            if (target == pendingSwipeTarget) {
                pendingSwipeTarget = -1
                return@LaunchedEffect
            }
            val remaining = pendingSwipeUntilMs - android.os.SystemClock.uptimeMillis()
            if (remaining > 0L) {
                delay(remaining)
            }
            pendingSwipeTarget = -1
        }
        val latestIdx = latestState.queueIndex
        if (latestQueue.isEmpty() || latestIdx !in latestQueue.indices) return@LaunchedEffect
        if (pagerState.settledPage != latestIdx && (!pagerState.isScrollInProgress || !userDraggedPager)) {
            userDraggedPager = false
            // 跟手模式下发生直接切页（无滑动动画）时，先锁定当前正在显示的颜色作为起点，
            // 再切到目标页并平滑渐变到新歌颜色，避免瞬切色卡闪烁。
            if (isSwipeGlow && !smoothGlow.fadeActive) {
                val fromColors = resolveGlowColors(latestQueue.getOrNull(pagerState.currentPage), skin)
                    .ifEmpty { resolveGlowColors(latestQueue.getOrNull(latestIdx), skin) }
                if (fromColors.isNotEmpty()) {
                    smoothGlow.snapTo(fromColors, skin.bg)
                }
            }
            smoothGlow.fadeActive = true
            programmaticScroll = true
            try {
                pagerState.scrollToPage(latestIdx)
            } finally {
                programmaticScroll = false
            }
        }
    }

    // ===== 呼吸动效：引擎 + 帧循环 =====
    //
    // 两个输出值只在这里被**写入**，读取全部发生在 drawBehind / graphicsLayer 里，
    // 所以每帧只是让绘制层失效，不会触发任何重组。
    // 包络 / 相位 / 能量放在普通对象里（不参与快照），暂停时从当前值继续收敛，不会跳。
    val breathEngine = remember { BreathEngine() }
    val glowBreath = remember { mutableFloatStateOf(1f) }

    // 「页面可见」= 停在封面层。切到歌词 / 律动柱层后不再推进；
    // 暂停后仍然继续跑 600ms 把包络收回去，收干净立即 break，不留空转。
    val breathActive = state.isPlaybackActive && layer == PlayerLayer.COVER && !coveredByVinyl

    LaunchedEffect(breathActive, layer, state.mediaId, state.isPlaybackActive) {
        snapshotFlow {
            coveredByVinyl || coverTouched || pagerState.isScrollInProgress || layerPager.isScrollInProgress ||
                latestState.isBuffering || (latestQueue.isNotEmpty() && pagerState.settledPage != latestState.queueIndex)
        }.collectLatest { frozen ->
            if (frozen || layer != PlayerLayer.COVER) {
                coverMotion.advance(0f, latestState.isPlaybackActive, frozen = true, trackId = latestState.mediaId)
                return@collectLatest
            }
            var last = withFrameNanos { it }
            // 绘制发布节流：高刷屏（120Hz）下每两帧发布一次，稳定在 ~60Hz，
            // 呼吸是秒级的慢动作，没必要追 120Hz 的功耗
            var sincePublish = 0f
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000f).coerceIn(0f, 50f)
                last = now
                coverScaleState.floatValue = coverMotion.advance(
                    dt, latestState.isPlaybackActive, frozen = false, trackId = latestState.mediaId,
                    audioEnergy = if (latestState.isPlaying && PcmBus.ready) rmsToEnergy(PcmBus.rms) else 0f
                )
                val e = breathEngine

                // 统一包络：450ms 渐入 / 600ms 归零（线性，乘以周期项后已经足够柔和）
                if (breathActive) {
                    e.envelope = (e.envelope + dt / BREATH_IN_MS).coerceAtMost(1f)
                    e.phaseMs += dt
                } else {
                    e.envelope = (e.envelope - dt / BREATH_OUT_MS).coerceAtLeast(0f)
                    if (e.envelope > 0f) e.phaseMs += dt
                }

                // 音频能量：复用 PcmTap 在同一轮采样里算出的 RMS（无 FFT、无二次遍历）
                val target = if (breathActive && PcmBus.ready) rmsToEnergy(PcmBus.rms) else 0f
                val k = (if (target > e.energy) {
                    dt / BREATH_AUDIO_ATTACK_MS
                } else {
                    dt / BREATH_AUDIO_RELEASE_MS
                }).coerceIn(0f, 1f)
                e.energy += (target - e.energy) * k

                if (e.envelope <= 0f) {
                    // 归零：缩放精确 1.000、晕染精确 1.00，不留任何残余
                    e.envelope = 0f
                    e.energy = 0f
                    glowBreath.floatValue = 1f
                    if (!breathActive && coverMotion.settled) break   // 暂停缩放和光晕都收干净后停帧
                } else {
                    val env = e.envelope
                    sincePublish += dt
                    if (sincePublish < 15f) continue
                    sincePublish = 0f
                    // 0 → 1 → 0 的余弦呼吸：起点就在基线，不会有「先弹一下再开始」的观感
                    val glowPeriodic = 0.5f - 0.5f * cos(
                        2f * PI.toFloat() * (e.phaseMs - BREATH_GLOW_OFFSET_MS) / BREATH_GLOW_PERIOD_MS
                    )
                    glowBreath.floatValue =
                        1f + (BREATH_GLOW_MAX - 1f) * env * glowPeriodic +
                            BREATH_AUDIO_GLOW * env * e.energy
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(skin.bg)
            // 记下自己在 root 里的位置，用来把封面的 root 坐标换算成本页坐标
            .onGloballyPositioned { rootTopLeft = it.positionInRoot() }
            // 晕染画在**整页背景层**：这一层天然包含顶栏和状态栏，颜色能一路覆盖上去。
            // （之前画在封面那一层，被分页器裁掉了顶栏部分，所以顶部一直缺一块。）
            .drawBehind {
                val cover = coverBounds ?: return@drawBehind
                if (cover.width <= 0f || cover.height <= 0f) return@drawBehind
                // 必须裁到本 Box 的范围内。
                //
                // 晕染是**故意**溢出布局边界的：FULL 模式向上超出封面区 1.2 倍高度、
                // 向下超出 1.7 倍（见 drawGlow 里的 top / height）。静态时这个 Box 就是
                // 整屏，溢出的部分落在屏幕外，所以一直没暴露。
                //
                // 但退出动画是整块往下滑（`slideOutVertically { it }`）：面板滑下去之后，
                // 那块「本来在屏幕外」的上溢就滑进了屏幕内，落在下层的页面上 ——
                // 表现就是返回时「一道全宽横带从设置页上扫过」。
                //
                // 裁掉即可，而且**零视觉损失**：静态时 Box 就是整屏，本来要画的东西
                // 一个都没少。注意必须裁在这里（Box 自己身上、在 slide 变换之内），
                // 裁在 AnimatedVisibility 的 modifier 上会因为顺序问题裁在变换之外，
                // 滑出时照样能看见。
                clipRect {
                    // 呼吸只在这里读一次：位图已按「峰值浓度」烘好，
                    // 绘制阶段只按 alpha 缩放，绝不重烘（见 glowBakeStrength 的说明）。
                    val breath = glowBreath.floatValue
                    val effective = (glowStrength * breath).coerceIn(0f, 1f)
                    // AROUND 走径向 Brush，可以直接把强度乘进去；
                    // 其它模式靠 draw alpha 从「带余量」的烘焙浓度里往回收。
                    val strengthArg = if (glowMode == GlowMode.AROUND) effective else glowStrength
                    val alphaScale = if (glowMode == GlowMode.AROUND) {
                        1f
                    } else if (glowBakeStrength > 0f) {
                        (effective / glowBakeStrength).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    if (useSmoothGlow) {
                        drawGlow(smoothGlowFrame, cover, glowMode, strengthArg, alpha = alphaScale)
                    } else {
                        // 跟手模式滑动中：按分页器的滚动位置在相邻两页之间混合。
                        //
                        // pos 是连续的「第几页」（例如 3.4）。基准页必须用 floor 取，
                        // **不能**用 offsetFraction 的符号判方向 —— 那个值在 currentPage
                        // 翻页的一刻会变号，用符号判会在中点处跳一下
                        // （3.4 和 3.6 会算成相反的混合比）。
                        val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val base = floor(pos).toInt()
                        val t = (pos - base).coerceIn(0f, 1f)
                        val curQueue = latestQueue
                        val first = cachedGlowFrame(
                            resolveGlowColors(curQueue.getOrNull(base), skin),
                            glowMode,
                            glowBakeStrength
                        )
                        val second = if (t > 0f) {
                            cachedGlowFrame(
                                resolveGlowColors(curQueue.getOrNull(base + 1), skin),
                                glowMode,
                                glowBakeStrength
                            )
                        } else {
                            GlowFrame(emptyList(), null)
                        }
                        val firstReady = first.colors.isNotEmpty()
                        val secondReady = second.colors.isNotEmpty()
                        when {
                            !firstReady && !secondReady -> Unit
                            firstReady && !secondReady -> drawGlow(first, cover, glowMode, strengthArg, alpha = alphaScale)
                            !firstReady && secondReady -> drawGlow(second, cover, glowMode, strengthArg, alpha = alphaScale)
                            else -> {
                                drawGlow(first, cover, glowMode, strengthArg, alpha = (1f - t) * alphaScale)
                                if (t > 0f) {
                                    drawGlow(second, cover, glowMode, strengthArg, alpha = t * alphaScale)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val currentSong = queue.getOrNull(state.queueIndex)

        // 三层横向轮播。这里用 HorizontalPager 而不是 AnimatedContent：
        // 内容会**跟着手指走**、松手回弹到最近一层，这才是轮播该有的手感；
        // AnimatedContent 只是「触发后播一段动画」，滑起来是跳的、过渡生硬。
        //
        // beyondViewportPageCount = 0：落定后只组合当前一层。早先那条
        // 「律动柱打开时把下层从组合里摘掉」的性能优化，交给 pager 自然完成。
        //
        // 注意：下面三个分支内部的缩进沿用了改造前的层级（整体没有再缩进四格），
        // 免得这次改动变成几千行噪声 diff。
        HorizontalPager(
            state = layerPager,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
        // 每层裁到自己的页面矩形。这层裁剪不是保险，是必须的：
        // 歌词层的背景是 `scale(1.35f)` 的模糊封面，而 Modifier.scale 默认**不裁剪**内容，
        // 于是它向左右各溢出 17.5% 的页面宽度（1080 宽的手机恰好 189px）。
        // 滑动过程中相邻两层会同时参与组合（beyondViewportPageCount = 0 只保证落定后
        // 不多组合，滑动中该组合的照样组合），溢出部分就盖到邻层上 ——
        // 表现即「只在中途出现、切换完就没了」的竖条。
        // pager 自己的视口裁剪挡不住它：竖条落在屏幕内，而不是视口外。
        //
        // 实测（1080x2400 / android-36）：去掉这两处裁剪后，封面页背景区亮度从 18.0
        // 变成 85.4 —— 就是那条带；加回裁剪即恢复 18.0，且歌词页自身亮度完全不变。
        Box(Modifier.fillMaxSize().clipToBounds()) {
        when (page % PlayerLayer.entries.size) {

        // ===== 第 0 层：封面 + 顶栏 =====
        0 -> Column(Modifier.fillMaxSize()) {

            // ===== 顶栏（方案 A：与下方封面 26dp 齐平 + 对称微磨砂胶囊 + 统一亮度）=====
            //
            // 用 Box 叠一层，而不是把标题塞进 Row 的中间格：
            // 左侧只有 1 个图标、右侧胶囊有 2 个，塞进 Row 的话指示器会在**剩余空间**里居中，
            // 结果偏左。这里让律动指示器独立居中于整条栏。
            val pillBg = skin.text.copy(alpha = 0.08f)
            val pillBorder = skin.text.copy(alpha = 0.11f)
            val iconTint = skin.text.copy(alpha = 0.88f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 26.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(pillBg)
                            .border(1.dp, pillBorder, CircleShape)
                            .clickable {
                                if (layer == PlayerLayer.COVER) onClose() else goToLayer(PlayerLayer.COVER)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.ChevronDown,
                            contentDescription = if (layer == PlayerLayer.COVER) "收起" else "返回封面",
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 右侧合并微磨砂胶囊：黑胶模式（唱片图标）+ 睡眠定时器，统一亮度与字重
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(pillBg)
                            .border(1.dp, pillBorder, RoundedCornerShape(50))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 30.dp, height = 28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onOpenVinyl),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = SonanceIcons.VinylDisc,
                                contentDescription = "黑胶模式",
                                tint = iconTint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(13.dp)
                                .background(skin.text.copy(alpha = 0.14f))
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 30.dp, height = 28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onOpenSleepTimer),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = SonanceIcons.Timer,
                                contentDescription = if (sleepTimerActive) "睡眠定时器（计时中）" else "睡眠定时器",
                                tint = if (sleepTimerActive) skin.accent else iconTint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // 标题独立居中于整条栏，不受左右图标数量影响
                // 顶部状态：只留律动小图标（用户要求去掉文字），播放中三柱跳动、暂停时压平
                Box(Modifier.align(Alignment.Center)) {
                    if (!coveredByVinyl) {
                        HeaderPlayingIndicator(
                            isPlaying = state.isPlaybackActive,
                            isSwipeGlow = !useSmoothGlow,
                            pagerState = pagerState,
                            queueProvider = { latestQueue },
                            smoothGlowColors = smoothGlowColors,
                            skin = skin,
                            glowMode = glowMode,
                            glowStrength = glowStrength
                        )
                    }
                }

                // 收起热区：顶栏左侧 ~60% 整片都能触发「收起播放页」，
                // 覆盖左上角箭头与中间的律动指示器；右侧胶囊所在的右侧区域不铺热区，保留各自点击。
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.60f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (layer == PlayerLayer.COVER) onClose() else goToLayer(PlayerLayer.COVER)
                        }
                )
            }

            if (queue.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未在播放",
                        style = MaterialTheme.typography.titleMedium,
                        color = skin.textMuted
                    )
                }
            } else {
                // 分页器外面**再套一层裁剪**。
                //
                // 只给 `VerticalPager` 挂 `clipToBounds()` 是不够的：它内部还要处理
                // 「相邻页提前组合」（beyondViewportPageCount），那套 beyond-bounds 机制
                // 会让视口外的页照样参与绘制，挂在它自己身上的裁剪被绕过去了。
                // 实测现象：往回滑（看上一曲）时，上一页停在视口上方的那一截
                // —— 控制按钮、音量条 —— 会画在顶栏和状态栏那一条上。
                // 裁在这一层就绕不过去了。
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                // 底部安全区 = 分页器高度的 10% 与系统导航栏高度取较大值。
                // 只手挡「上下滑切歌」，点击 / 横向拖动（音量条）不受影响；
                // 布局完全不动，只改触控命中范围。
                val density = LocalDensity.current
                val heightPx = with(density) { maxHeight.toPx() }
                val navPx = WindowInsets.navigationBars.getBottom(density)
                val bottomSafePx = maxOf(heightPx * 0.1f, navPx.toFloat())
                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    // key 必须带上页码：`playNext` 不查重，队列里可能出现同一首歌两次
                    // （用户对已在队列里的歌点"下一首播放"就会），只拿 song.id 当 key
                    // 会撞成重复 key，分页器直接抛 "Key ... was already used"。
                    key = { page -> "$page-${queue.getOrNull(page)?.id ?: -1L}" },
                    modifier = Modifier
                        .fillMaxSize()
                        // 观察按下而不消费，触摸起点就冻结尺寸，不等分页器越过拖动阈值。
                        .pointerInput(pagerState) {
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        coverTouched = event.changes.any { it.pressed }
                                    }
                                }
                            } finally {
                                coverTouched = false
                            }
                        }
                        .blockSongSwipeInBottomBand(bottomSafePx)
                ) { page ->
                    val song = queue.getOrNull(page) ?: return@VerticalPager
                    val isCurrent = page == state.queueIndex
                    // 每一页都裁到自己的范围 —— **包括当前页**。
                    //
                    // 早先这里给当前页留了豁免（不裁），理由是「晕染画在封面层、
                    // 需要向上溢出才能覆盖顶栏」。但现在晕染已经搬到整页背景层了，
                    // 那个理由不再成立，豁免只剩下副作用：
                    // 滑动过程中当前页整体上移，它的**封面会溢出到分页器之外**，
                    // 也就是顶栏 / 状态栏那一条 —— 表现是屏幕上半截突然出现
                    // 不属于当前页的画面（用户反馈的「遮罩异常」）。
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                    PlayerPage(
                        song = song,
                        isFavorite = song.id in favorites,
                        // 只有当前页读实时播放状态；其它页给中性值，避免进度每 500ms
                        // 更新时把相邻页也拖进重组。
                        positionMs = if (isCurrent) state.positionMs else 0L,
                        durationMs = if (isCurrent && state.durationMs > 0) state.durationMs else song.durationMs,
                        isPlaying = isCurrent && state.isPlaying && !coveredByVinyl,
                        // A preview is about to autoplay. Never render it as paused during the swipe.
                        controlsPlaying = pagerPlayButtonActive(isCurrent, state),
                        // 暂停/播放缩放与呼吸已合并为三页共享的最终尺寸。
                        // 相邻页也读同一个值 —— 尺寸永远一致，滑动时不会一大一小。
                        coverScale = coverScaleState,
                        playButtonPaletteProvider = buttonPaletteProvider,
                        playMode = if (isCurrent) {
                            PlayMode.of(state.shuffle, state.repeat)
                        } else {
                            // 非当前页给中性档，免得翻页时看到"随机"亮着
                            PlayMode.REPEAT_ALL
                        },
                        volume = if (isCurrent) state.userVolume else 1f,
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onCyclePlayMode = onCyclePlayMode,
                        onPrev = {
                            pendingSwipeTarget = -1
                            onPrev()
                        },
                        onPlayPause = onPlayPause,
                        onNext = {
                            pendingSwipeTarget = -1
                            onNext()
                        },
                        onSeek = onSeek,
                        onVolume = onVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenCover = onOpenCover,
                        onOpenEqualizer = onOpenEqualizer,
                        eqEnabled = eqEnabled,
                        playbackSpeed = playbackSpeed,
                        onSetSpeed = onSetSpeed,
                        prefs = prefs,
                        swipePager = pagerState,
                        swipePage = page,
                        streamQuality = if (isCurrent) streamQuality else null,
                        // 内嵌歌词自动调色的素材：仅歌词展开时才读取，避免封面滑动期间触发重组与位图读取。
                        glowImage = if (!lyricsExpanded) {
                            null
                        } else if (!useSmoothGlow) {
                            cachedGlowFrame(resolveGlowColors(song, skin), glowMode, glowBakeStrength).image
                        } else if (isCurrent) {
                            smoothGlowFrame.image
                        } else {
                            null
                        },
                        vinylMode = vinylMode,
                        hiResBadgeEnabled = hiResBadgeEnabled,
                        hiResBadgeSize = hiResBadgeSize,
                        lyricsExpanded = lyricsExpanded,
                        onToggleLyrics = { lyricsExpanded = !lyricsExpanded },
                        onOpenGlow = { showGlowSheet = true },
                        // 量出来的是 root 坐标，换算成本页（根 Box）坐标再交给晕染。
                        //
                        // 只有**当前页**的上报算数：分页器会同时组合相邻页
                        // （beyondViewportPageCount = 1），那些页停在屏幕外，
                        // 它们的封面位置一旦参与上报，晕染就会被拽到屏幕外去 ——
                        // 表现是颜色突然消失、或者闪到别的位置。
                        onCoverBounds = { rect ->
                            // 四个条件缺一不可：
                            // - isCurrent：只有当前页的上报算数（相邻页停在屏幕外，
                            //   它们的位置一旦参与上报，晕染会被拽到屏幕外去）；
                            // - page == currentPage && settledPage == page && !isScrollInProgress：
                            //   按钮切歌时 state.queueIndex 会先于 scrollToPage 变化一帧，
                            //   此时新页还停在视口外（被 clipToBounds 裁成 Rect.Zero），
                            //   绝不能把 0×0 或视口外坐标写进 coverBounds，否则晕染会整屏黑掉几十毫秒；
                            // - rect.width > 1f && rect.height > 1f：拒绝退化空矩形。
                            if (isCurrent &&
                                page == pagerState.currentPage &&
                                pagerState.settledPage == page &&
                                !pagerState.isScrollInProgress &&
                                rect.width > 1f &&
                                rect.height > 1f
                            ) {
                                coverBounds = rect.translate(-rootTopLeft.x, -rootTopLeft.y)
                            }
                        }
                    )
                    }
                }
                }   // Box(weight(1f).clipToBounds()) —— 分页器的视口裁剪
            }
        }

        // ===== 第 1 层：歌词 =====
        // 不收 onClose 了：歌词页的关闭按钮已去掉（用户要求），
        // 退出靠左右滑动切层或系统返回键（上面有 BackHandler）。
        1 -> if (currentSong != null) {
            LyricsOverlay(
                song = currentSong,
                state = state,
                prefs = prefs,
                onSeek = onSeek,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onOpenPlayer = { goToLayer(PlayerLayer.COVER) },
                hideStatusBar = lyricsHideStatusBar,
                onHideStatusBarChange = onLyricsHideStatusBarChange,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== 第 2 层：律动柱 =====
        else -> if (currentSong != null) {
            VisualizerOverlay(
                song = currentSong,
                isPlaying = state.isPlaying && !coveredByVinyl,
                positionMs = state.positionMs,
                durationMs = if (state.durationMs > 0) {
                    state.durationMs
                } else {
                    currentSong.durationMs
                },
                prefs = prefs,
                modifier = Modifier.fillMaxSize(),
                hideStatusBar = visualizerHideStatusBar,
                onHideStatusBarChange = onVisualizerHideStatusBarChange
            )
        }
        }
        }   // Box(fillMaxSize().clipToBounds())
        }
    }

    if (showGlowSheet) {
        GlowSheet(
            mode = glowMode,
            strength = glowStrength,
            transition = glowTransition,
            fadeMs = glowFadeMs,
            onModeChange = onGlowModeChange,
            onStrengthChange = onGlowStrengthChange,
            onTransitionChange = onGlowTransitionChange,
            onFadeMsChange = onGlowFadeMsChange,
            vinylMode = vinylMode,
            onVinylModeChange = onVinylModeChange,
            onDismiss = { showGlowSheet = false }
        )
    }
}

/**
 * 播放页的三层。三者首尾相连成一个环，左右滑动可以一直转下去
 * （封面 → 歌词 → 律动柱 → 封面 …，反方向同理）。
 *
 * 非 private：SonanceRoot 要读它决定「歌词页是否隐藏状态栏」。
 */
enum class PlayerLayer { COVER, LYRICS, VISUALIZER }

/**
 * 顶部状态条左侧的「播放中 / 已暂停」律动小图标（参考样机的 mini-eq）。
 *
 * 播放中：三根小柱错拍跳动；已暂停：齐平压低。配色随背景协调、平滑过渡。
 * 替换顶栏原来的纯文字状态，让状态一眼可辨。
 */
@Composable
private fun PlayingStateIndicator(
    isPlaying: Boolean,
    colorProvider: (playingFraction: Float) -> Color
) {
    val level = animateFloatAsState(if (isPlaying) 1f else 0f, tween(350), label = "stateEqLevel")
    val transition = rememberInfiniteTransition(label = "stateEq")
    val barWidth = when (com.sonance.player.ui.icon.IconSetState.style) {
        com.sonance.player.ui.icon.IconSetState.MINIMAL -> 1.5.dp
        com.sonance.player.ui.icon.IconSetState.SOLID -> 3.dp
        else -> 2.6.dp
    }
    // 三根柱错拍：StartOffset 把相位错开（对应样机 0 / 0.2s / 0.35s 的 delay）
    val bars = listOf(0, 150, 260).map { offset ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(offset)
            ),
            label = "eqBar$offset"
        )
    }

    Canvas(Modifier.size(width = 20.dp, height = 13.dp)) {
        val barW = barWidth.toPx()
        val gap = (size.width - barW * 3f) / 2f
        val maxH = size.height
        val minH = size.height * 0.22f
        val color = colorProvider(level.value)
        bars.forEachIndexed { i, wave ->
            val p = if (level.value > 0f) wave.value * level.value else 0f
            val h = minH + (maxH - minH) * p
            drawRoundRect(
                color = color,
                topLeft = Offset(x = i * (barW + gap), y = maxH - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

/**
 * 环形轮播的起始页号。
 *
 * 取环中间的 3 的倍数：既保证启动时停在封面层（`page % 3 == 0`），
 * 又让左右两个方向都有足够余量一直滑下去。
 */
private const val LAYER_RING_START = 3000

/* ============================================================
 * 封面颜色渗到背景
 * ============================================================ */

/**
 * 「下方」模式里，颜色从封面高度的这个比例处开始。
 *
 * ⚠️ 必须贴着封面底边（不能小太多）。第一版取 0.72，叠上背景色遮罩之后，
 * 颜色最浓的位置落在了封面高度的 91% 处 —— 也就是**封面自己背后**，
 * 被封面挡得干干净净，看起来就像「没有效果」。
 */
private const val GLOW_TOP = 0.86f

/** 晕染向外扩散的距离，按封面高度的比例算（「下方」「四周」模式用） */
private const val GLOW_REACH = 0.62f

/** 「左右」模式里，颜色上下各超出封面高度的比例 */
private const val GLOW_SIDE_PAD = 0.10f

/**
 * 「下方」模式里把整块渐变**再往上抬一点**，让它压住封面底边。
 *
 * 抬起来之后颜色像是「从封面底下渗出来」的，而不是在封面下方另外起一条；
 * 峰值也会略微往封面背后挪，可见部分更贴合封面。
 *
 * 8dp（约 24px @3x）是实测的中间值：再往下（0dp）颜色和封面之间会显出脱节感，
 * 再往上（>12dp）峰值基本躲进封面背后，可见的那一段就明显变淡了。
 */
private val GLOW_BOTTOM_LIFT = 8.dp

/**
 * 颜色边界的过渡长度，按区域总长的**比例**算。
 *
 * 早先用的是固定的 14dp。抹掉硬边是够的，但放到「左右」这种整条色带上，
 * 上下两端只剩十几像素的过渡，看着就是一刀切的硬边 —— 用户反馈「生硬、不自然」。
 * 改成按比例之后，长度会随区域自适应：短的地方依旧是小过渡，长的地方用 18% 自然淡出。
 */
private const val GLOW_FADE = 0.18f

/**
 * 晕染位图的分辨率。
 *
 * 每个模式都要「横向取封面的颜色 + 纵向淡出」这种二维效果，而 Compose 的 [Brush]
 * 只支持单一方向，所以是把渐变**烘成一张小位图**再拉伸：
 * - 横向 64 是颜色插值的方向（色卡只有左 / 中 / 右三色，双线性拉伸出来就是精确的连续渐变）；
 * - 纵向 128 是浓度曲线的方向，几个折点（起点、GLOW_FADE、终点）都能被采样到。
 *
 * 位图很小（64×128 = 8192 像素），而且只在 颜色 / 模式 / 浓度 变化时重建，
 * 拖动滑块时每帧重建一次也在 0.2ms 量级，不值得再做缓存。
 *
 * 为什么不继续用「横向切 N 段、每段一条纵向渐变」：每个分段都是独立的 `drawRect`，
 * 交界像素被两个矩形各画一次，屏幕上会出现一排等距竖线；而且分段本身是常数色，
 * 渐变区还会带出竖条状色阶。一张连续位图两头都占。
 */
private const val GLOW_BITMAP_W = 64
private const val GLOW_BITMAP_H = 128

/* ---- 「沉浸」模式 ---- */

/**
 * 沉浸模式里色块保持**满色**的部分（按屏幕高度的比例）。
 * 从这里往下开始为底部按钮区减淡 —— 上面铺满、下面让位。
 *
 * 起点要对在**歌曲名称上方一点**：歌名/歌手文字大约落在屏幕 50%~55% 处，
 * 满色区太靠下的话文字正好泡在浓色里，浅色封面时和字色撞在一起看不清
 * （用户反馈）—— 所以从 0.55 提前到 0.45，让文字区域已经进入过渡段。
 */
private const val IMMERSIVE_HOLD = 0.45f

/**
 * 沉浸模式底部减淡的**保底浓度**：不降到零，按钮区还留一层薄底色 ——
 * 全降为零的话底部会像被挖掉一块，和上面浓色形成断层。
 */
private const val IMMERSIVE_BOTTOM_FLOOR = 0.18f

/**
 * 沉浸模式的浓度增益：同样的滑杆值下比其它模式更深（这是"沉浸"的本意）。
 * 0.60 的默认浓度在这里表现为 0.81 的铺色，滑到上限时封顶在 1.0。
 */
private const val IMMERSIVE_BOOST = 1.35f

/**
 * 换歌时晕染颜色的补间时长。
 *
 * 420ms 是「看得出是渐变、又不觉得颜色跟不上歌」的折中：
 * 再短（<250ms）看着还是像硬切，再长（>700ms）会明显滞后于已经切过去的封面。
 * 用线性缓动 —— 颜色插值上加速/减速反而显得别扭。
 */
/**
 * 「渐变」模式的补间时长**范围**。默认值与当前值都在 [Prefs] 里。
 *
 * 120ms 是下限：再短看着还是硬切；1500ms 是上限：再长会明显滞后于已经切过去的封面。
 */
private val GLOW_FADE_RANGE = Prefs.MIN_GLOW_FADE_MS..Prefs.MAX_GLOW_FADE_MS


/**
 * 纵向浓度曲线：`v = 0` 是晕染区**靠封面的那一端**，`v = 1` 是远端。
 * 端点都收在 0，所以无论哪种模式，边界都是渐变淡出、不会留下硬边。
 *
 * 四个模式**全部显式列出、不留 `else`**：
 * 一是以后新增 [GlowMode] 时编译器会强制你在这里做决定；
 * 二是把「[GlowMode.AROUND] 不走位图」这条不变量写在它该在的地方 ——
 * 那条规则原先只存在于 [buildGlowImage] 的提前 return 里，没有任何保护。
 */
private fun glowVerticalCurve(mode: GlowMode, v: Float): Float = when (mode) {
    // 只从下边缘向下晕：贴着封面的地方最浓，向下逐渐淡掉
    GlowMode.BOTTOM -> if (v < GLOW_FADE) v / GLOW_FADE else (1f - v) / (1f - GLOW_FADE)
    // 左右两侧：上下两端各收一段，中间保持满色
    GlowMode.SIDES -> when {
        v < GLOW_FADE -> v / GLOW_FADE
        v > 1f - GLOW_FADE -> (1f - v) / GLOW_FADE
        else -> 1f
    }
    // 全屏：顶部最浓，向下线性淡到没有（底部按钮区应该几乎看不见颜色）
    GlowMode.FULL -> (1f - v) * 0.92f
    // 沉浸：单色铺满、不做四周渐变 —— 主体恒定满色，只有底部按钮区那一段
    // 从满色线性降到保底值（位图按屏幕高度整张铺，v 就是屏幕高度的占比）
    GlowMode.IMMERSIVE -> when {
        v < IMMERSIVE_HOLD -> 1f
        else -> 1f - (v - IMMERSIVE_HOLD) / (1f - IMMERSIVE_HOLD) *
            (1f - IMMERSIVE_BOTTOM_FLOOR)
    }
    // 四周模式用径向渐变（见 buildGlowImage 对它提前 return null），压根不会走到这里。
    // 真被问到也只返回 0（全透明）—— 万一哪天位图那条路改了，最坏是画不出颜色，
    // 而不是按 FULL 的曲线糊出一层莫名其妙的东西。
    GlowMode.AROUND -> 0f
}

/**
 * 把「横向色卡 + 纵向浓度」烘成一张 [ImageBitmap]。
 *
 * 径向那个模式（[GlowMode.AROUND]）直接用 `Brush.radialGradient`，不需要位图 ——
 * 径向渐变本身就是二维的，没有分段，也就没有竖线问题。
 */
private fun buildGlowImage(
    colors: List<Color>,
    mode: GlowMode,
    strength: Float
): ImageBitmap? {
    if (colors.isEmpty() || strength <= 0f) return null
    if (mode == GlowMode.AROUND) return null
    // 沉浸模式：只取封面正中**一个**主色铺满（不做横向取色），并且浓度整体加深
    val immersive = mode == GlowMode.IMMERSIVE
    val single = if (immersive) glowTintAt(colors, 0.5f) else null
    val s = if (immersive) (strength * IMMERSIVE_BOOST).coerceIn(0f, 1f) else strength
    val w = GLOW_BITMAP_W
    val h = GLOW_BITMAP_H
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val v = y / (h - 1f)
        val a = (glowVerticalCurve(mode, v) * s).coerceIn(0f, 1f)
        val ai = (a * 255f + 0.5f).toInt()
        if (ai == 0) continue   // 全透明，留 0 就行
        for (x in 0 until w) {
            val argb = (single ?: glowTintAt(colors, x / (w - 1f))).toArgb()
            px[y * w + x] = (ai shl 24) or (argb and 0x00FFFFFF)
        }
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/* ============================================================
 * 晕染素材的取色与烘焙
 * ============================================================ */

private data class GlowFrameKey(
    val c0: Int,
    val c1: Int,
    val c2: Int,
    val count: Int,
    val mode: GlowMode,
    val strengthBits: Int
)

private val glowFrameCache = LruCache<GlowFrameKey, GlowFrame>(24)

/**
 * 非组合期直接读取色卡（用于绘制阶段与 LRU 预烘焙），读取 [ArtworkLoader.tintOf] 的快照状态
 * 以便色卡就绪时只触发绘制层失效，不拖拽 FullPlayer 重组。
 */
private fun resolveGlowColors(song: Song?, skin: Skin): List<Color> {
    if (song == null) return emptyList()
    val coverKey = OnlineCover.keyOf(song.title, song.artist)
    val tint = ArtworkLoader.tintOf(song.albumId, song.uri, coverKey)
    ArtworkLoader.artworkGoneVersion
    return when {
        tint != null -> listOf(Color(tint.left), Color(tint.center), Color(tint.right))
        ArtworkLoader.isArtworkGone(song.albumId, song.uri, coverKey) -> {
            val c = skin.gradientFor(song.title + song.artist)
            listOf(c.first(), c[c.size / 2], c.last())
        }
        else -> emptyList()
    }
}

private fun cachedGlowFrame(colors: List<Color>, mode: GlowMode, strength: Float): GlowFrame {
    if (colors.isEmpty()) return GlowFrame(emptyList(), null)
    val key = GlowFrameKey(
        c0 = colors.getOrElse(0) { Color.Transparent }.toArgb(),
        c1 = colors.getOrElse(1) { Color.Transparent }.toArgb(),
        c2 = colors.getOrElse(2) { Color.Transparent }.toArgb(),
        count = colors.size,
        mode = mode,
        strengthBits = strength.toBits()
    )
    glowFrameCache.get(key)?.let { return it }
    val frame = GlowFrame(colors, buildGlowImage(colors, mode, strength))
    glowFrameCache.put(key, frame)
    return frame
}

/**
 * 取某首歌的晕染色卡。
 *
 * **空列表表示「色卡还没算出来」**（探测中 / 解码中 / 在线封面下载中），
 * 而**不是**「这首歌没有封面」—— 后者会走 [ArtworkLoader.isArtworkGone] 分支
 * 拿到皮肤占位色。两者要分开，因为前者要由调用方决定是「沿用上一份」还是「先不画」。
 */
@Composable
private fun rememberGlowColors(song: Song?, skin: Skin): List<Color> {
    val coverKey = remember(song?.id, song?.title, song?.artist) {
        song?.let { OnlineCover.keyOf(it.title, it.artist) }
    }
    // ⚠️ `tintOf` 必须在 remember **外面**读。
    // 它读的是 SnapshotStateMap（色卡是异步算出来的）；放进 remember 块里的话，
    // 色卡算好之后下面那几个 key 都没变、块不会重跑，拿到的就永远是"还没算好"。
    val tint = song?.let { ArtworkLoader.tintOf(it.albumId, it.uri, coverKey) }
    val goneVersion = ArtworkLoader.artworkGoneVersion

    return remember(song?.id, song?.title, tint, skin.id, goneVersion) {
        when {
            song == null -> emptyList()

            tint != null -> listOf(Color(tint.left), Color(tint.center), Color(tint.right))

            // 封面**确实没有** → 用占位图配色兜底。
            // 这类歌永远不会产生色卡，不给兜底的话它们就一直没有晕染。
            ArtworkLoader.isArtworkGone(song.albumId, song.uri, coverKey) -> {
                val c = skin.gradientFor(song.title + song.artist)
                listOf(c.first(), c[c.size / 2], c.last())
            }

            else -> emptyList()
        }
    }
}

/**
 * 把色卡烘成位图。只在 颜色 / 模式 / 浓度 变化时重建。
 *
 * 这里**不写提前 return**：`remember` 必须无条件调用，否则条件翻转时
 * 组合的 group 结构会跟着变。空色卡交给 [buildGlowImage]（它对空列表直接返回 null），
 * 画出来就是全透明。
 */
@Composable
private fun rememberGlowFrame(colors: List<Color>, mode: GlowMode, strength: Float): GlowFrame =
    remember(colors, mode, strength) {
        GlowFrame(colors, buildGlowImage(colors, mode, strength))
    }

/**
 * 非跟手滑动时的统一平滑晕染色卡：
 * - 色卡尚在异步取色（目标为空）时保持当前正在显示的颜色，绝不退回黑底；
 * - 目标色卡就绪后用 [Animatable] 从当前正在渲染的颜色平滑补间过去，不发生跳变；
 * - 手势滑动落定后直接对齐到已落定页的当前色，保证滑动切歌回交时零接缝。
 */
private class SmoothGlowController(
    initial: List<Color>,
    fallback: Color
) {
    val anim0 = Animatable(initial.getOrElse(0) { fallback })
    val anim1 = Animatable(initial.getOrElse(1) { initial.getOrElse(0) { fallback } })
    val anim2 = Animatable(initial.getOrElse(2) { initial.getOrElse(1) { initial.getOrElse(0) { fallback } } })
    var hasColors by mutableStateOf(initial.isNotEmpty())
    var fadeActive by mutableStateOf(false)

    suspend fun snapTo(colors: List<Color>, fallback: Color) {
        if (colors.isEmpty()) return
        val c0 = colors.getOrElse(0) { fallback }
        val c1 = colors.getOrElse(1) { c0 }
        val c2 = colors.getOrElse(2) { c1 }
        hasColors = true
        anim0.snapTo(c0)
        anim1.snapTo(c1)
        anim2.snapTo(c2)
    }
}

/** 在封面的几个取样色之间按 [t]（0~1）插值 */
private fun glowTintAt(colors: List<Color>, t: Float): Color {
    if (colors.size == 1) return colors[0]
    val scaled = t.coerceIn(0f, 1f) * (colors.size - 1)
    val i = scaled.toInt().coerceIn(0, colors.size - 2)
    return lerp(colors[i], colors[i + 1], scaled - i)
}

/**
 * 顶栏律动指示器 + 它的「按页位置混色」。
 *
 * 混色完全延后到 [PlayingStateIndicator] 的 Canvas 绘制阶段读取 [PagerState.currentPageOffsetFraction]
 * 与 [PagerState.currentPage]，滑动过程中零重组、零颜色动画重启，只重绘 22x12dp 的小画布。
 */
@Composable
private fun HeaderPlayingIndicator(
    isPlaying: Boolean,
    isSwipeGlow: Boolean,
    pagerState: PagerState,
    queueProvider: () -> List<Song>,
    smoothGlowColors: List<Color>,
    skin: Skin,
    glowMode: GlowMode,
    glowStrength: Float
) {
    PlayingStateIndicator(
        isPlaying = isPlaying,
        colorProvider = { playingFraction ->
            val coverColor = if (isSwipeGlow) {
                val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val base = floor(pos).toInt()
                val t = (pos - base).coerceIn(0f, 1f)
                val q = queueProvider()
                val first = resolveGlowColors(q.getOrNull(base), skin)
                val second = if (t > 0f) resolveGlowColors(q.getOrNull(base + 1), skin) else emptyList()
                val c0 = if (first.isEmpty()) skin.bg else glowTintAt(first, 0.5f)
                val c1 = if (second.isEmpty()) c0 else glowTintAt(second, 0.5f)
                lerp(c0, c1, t)
            } else {
                smoothGlowColors.let { if (it.isEmpty()) skin.bg else glowTintAt(it, 0.5f) }
            }
            val tintStrength = when (glowMode) {
                GlowMode.BOTTOM -> 0f
                GlowMode.IMMERSIVE -> (glowStrength * IMMERSIVE_BOOST).coerceIn(0f, 1f)
                GlowMode.FULL -> glowStrength * 0.92f
                GlowMode.AROUND, GlowMode.SIDES -> glowStrength * 0.25f
            }
            val backdrop = lerp(skin.bg, coverColor, tintStrength)
            val accent = harmonizedIndicatorColor(backdrop, skin.accent)
            val idle = ensureReadable(
                lerp(accent, backdrop, 0.18f),
                backdrop,
                3f,
                contrastRatio(Color.White, backdrop) >= contrastRatio(Color.Black, backdrop)
            )
            lerp(idle, accent, playingFraction)
        }
    )
}

/**
 * 封面的手势：**长按换封面、双击开晕染**。
 *
 * 上一版是"单击换封面"，太容易误触 —— 播放页上点封面本就是最随手的动作。
 * 换成长按之后单击完全空着，误触就没有了。
 *
 * 这里不用 `detectTapGestures`：它的双击窗口只有 300ms，而且早先实测
 * `pointerInput` 那条路上 up 事件会被判成"已消费"（见 git 历史里的注释）。
 * 用 `combinedClickable` 最稳：长按/双击/单击都是系统标准判定。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.onCoverGesture(
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit
): Modifier = this.combinedClickable(
    // 单击什么都不做 —— 这是刻意的，播放页最容易误碰的就是封面
    onClick = {},
    onDoubleClick = onDoubleTap,
    onLongClick = onLongPress,
    // 不画涟漪：封面是圆的、手势挂在方形容器上，默认 ripple 会闪出一个半透明方框
    interactionSource = remember { MutableInteractionSource() },
    indication = null
)

/* ============================================================
 * 黑碟播放器（黑胶唱片样式，参考网易云）
 * ============================================================ */

/** 黑碟转速：每秒转过的角度。24 秒一整圈，和网易云的黑胶速度一个量级 */
private const val VINYL_DEG_PER_SEC = 360f / 24f

/** 封面圆相对整个唱片直径的比例：外圈留出一环黑色黑胶 */
private const val VINYL_COVER_FRACTION = 0.74f

/**
 * 黑胶盘面：深底 + 两圈细高光模拟唱片的沟槽。
 * 中心比外缘略亮一点；沟槽高光跟着盘面一起转，唱片"在转"的感觉主要来自这里。
 */
private val VINYL_BRUSH = Brush.radialGradient(
    0.00f to Color(0xFF2E2E2E),
    0.11f to Color(0xFF191919),
    0.56f to Color(0xFF141414),
    0.58f to Color(0xFF343434),   // 沟槽高光 1：贴着封面外缘
    0.62f to Color(0xFF121212),
    0.82f to Color(0xFF101010),
    0.84f to Color(0xFF2B2B2B),   // 沟槽高光 2：接近盘边
    0.88f to Color(0xFF0D0D0D),
    1.00f to Color(0xFF060606)
)

/**
 * 「黑碟播放器」封面：封面变回**圆形原色**，外面裹一圈黑色黑胶，
 * 播放时整张唱片（盘面 + 封面）缓慢旋转，暂停就停在原地 —— 不回弹、不重置。
 *
 * 长按换封面、双击开晕染面板的手势由调用方挂在外层（[onCoverGesture]），照旧可用。
 */
@Composable
private fun VinylCover(
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 自己拿 withFrameNanos 累加角度，而不是 infiniteRepeatable：
    // 后者在 isPlaying 翻转时会把动画整个换掉、角度归零，唱片会"咣当"跳回 12 点。
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle = (angle + (now - last) / 1_000_000_000f * VINYL_DEG_PER_SEC) % 360f
                last = now
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // 外层容器给的宽高可能不相等：`size(portraitSide)` 会被父约束逐维压缩，
        // 宽度常被内边距压小、高度却没动，CircleShape 按非正方形边界裁出来就是椭圆。
        // 这里强制取短边成正方形、居中放，唱片在任何布局下都是正圆。
        Box(
            Modifier
                .aspectRatio(1f)
                .rotate(angle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(VINYL_BRUSH)
            )
            CoverArt(
                albumId = song.albumId,
                seed = song.title + song.artist,
                modifier = Modifier
                    .fillMaxSize(VINYL_COVER_FRACTION)
                    .clip(CircleShape),
                cornerRadius = 0.dp,
                sizePx = 900,
                sourceUri = song.uri,
                songId = song.id,
                coverKey = OnlineCover.keyOf(song.title, song.artist)
            )
        }
    }
}

/**
 * 一页的晕染素材：三个取样色 + **已经烘好**的渐变位图。
 *
 * 位图提前烘好是刻意的：换歌过渡时每帧只需要按不透明度把两张图叠起来，
 * 不必在绘制阶段重新填 8192 个像素、也不必每帧新建 Bitmap。
 */
private data class GlowFrame(val colors: List<Color>, val image: ImageBitmap?)

/**
 * 把封面的颜色往背景上晕开。
 *
 * 非径向的三种模式都是「横向取封面色 + 纵向淡出」，统一由 [buildGlowImage] 烘出来的
 * 那张小位图拉伸绘制 —— 只算位置就够了，没有分段也就没有竖线。
 * 径向模式本身是二维渐变，直接用 [Brush.radialGradient]。
 *
 * 画在 [drawBehind] 里 = 它在封面之下、也在歌名 / 进度条 / 按钮之下，
 * 所以只有背景会被染色，任何文字和控件都不会被盖住。
 */
private fun DrawScope.drawGlow(
    frame: GlowFrame,
    cover: Rect,
    mode: GlowMode,
    strength: Float,
    alpha: Float = 1f
) {
    val colors = frame.colors
    val image = frame.image
    if (colors.isEmpty() || strength <= 0f || alpha <= 0f) return
    val w = cover.width
    val h = cover.height
    val cx = cover.center.x
    val cy = cover.center.y

    when (mode) {
                    // ── 只从下边缘向下晕 ──
                    GlowMode.BOTTOM -> drawGlowImage(
                        image = image,
                        top = cy - h / 2f + h * GLOW_TOP - GLOW_BOTTOM_LIFT.toPx(),
                        height = h * GLOW_REACH,
                        alpha = alpha
                    )

                    // ── 只从左右两侧向外晕 ──
                    //
                    // 横向**不做两端淡出**：颜色一路铺到屏幕左右边缘之外。
                    // 早先这里给两端各留了一段渐变（屏幕边缘 0 → 封面边缘满色），
                    // 结果颜色还没到屏幕边就淡没了，看着只是封面旁边「各自一条窄带」，
                    // 完全不是「从封面往两侧延伸出去」的感觉。
                    GlowMode.SIDES -> {
                        val pad = h * GLOW_SIDE_PAD
                        drawGlowImage(
                            image = image,
                            top = cy - h / 2f - pad,
                            height = h + pad * 2f,
                            alpha = alpha
                        )
                    }

                    // ── 四周：从封面四条边向外晕开 ──
                    GlowMode.AROUND -> {
                        // 半径要够大，否则颜色到屏幕顶部就淡没了（顶栏那条看着像「没覆盖」）；
                        // 但也不能一路糊到控制按钮，所以取 0.72 倍 ——
                        // 顶部铺满、按钮附近正好淡到看不见。
                        //
                        // `coerceAtLeast` 是**保命的**：封面还没量到尺寸时（0×0）
                        // 半径会算成 0，而 `RadialGradient` 对 0 半径直接抛
                        // 「ending radius must be > 0」，异常发生在绘制阶段、一路冒到
                        // 主线程 —— 实测崩过一次。这里夹一下，最坏也只是画不出颜色。
                        val r = (minOf(w, h) * 0.5f + h * GLOW_REACH * 0.72f).coerceAtLeast(1f)
                        // 内切圆之内保持满色 —— 紧贴封面四周的地方最浓
                        val inner = (minOf(w, h) * 0.5f / r).coerceIn(0f, 0.95f)
                        // 圆形对称的渐变只有一个「径向」，所以这里取中间那个取样色
                        val c = glowTintAt(colors, 0.5f)
                        translate(left = 0f, top = cy - r) {
                            drawRect(
                                brush = Brush.radialGradient(
                                    0.00f to c.copy(alpha = strength * alpha),
                                    inner to c.copy(alpha = strength * alpha),
                                    1.00f to c.copy(alpha = 0f),
                                    center = Offset(cx, r),
                                    radius = r
                                ),
                                size = Size(size.width, r * 2f)
                            )
                        }
                    }

                    // ── 全屏：整页铺一层，从上到下越来越淡 ──
                    //
                    // 覆盖范围要盖过整块屏幕：上方留一屏多、下方留两屏，
                    // 屏幕顶到屏幕底就都落在范围内了（超出屏幕的部分画了也看不见）。
                    GlowMode.FULL -> drawGlowImage(
                        image = image,
                        top = cy - h / 2f - h * 1.2f,
                        height = h * 3.4f,
                        alpha = alpha
                    )

                    // ── 沉浸：单一主色铺满整屏，不做四周渐变 ──
                    //
                    // 位图正好按屏幕高度铺（top=0、height=整页），纵向曲线里
                    // "v 即屏幕高度占比"，所以底部的按钮区减淡就是按屏幕位置精确生效的。
                    GlowMode.IMMERSIVE -> drawGlowImage(
                        image = image,
                        top = 0f,
                        height = size.height,
                        alpha = alpha
                    )
                }
}

/**
 * 把位图按目标矩形拉伸绘制 —— 颜色和浓度都已经烘在位图里，一次就画完了。
 *
 * [alpha] 是给「跟手」模式做跨页交叉淡化用的整体不透明度。
 *
 * ⚠️ 两层各按 `t` / `1-t` 叠加，严格来说不是数学上等价的交叉淡化：
 * src-over 两次之后，过渡中点会比两端淡大约 14%（因为底色透出来了一部分）。
 * 但端点（t=0 / t=1）是精确的，中间只是一个平滑的轻微变淡，看不出台阶，
 * 而换来的是「零每帧分配」—— 不值得为这点差异去每帧重烘位图。
 */
private fun DrawScope.drawGlowImage(
    image: ImageBitmap?,
    top: Float,
    height: Float,
    alpha: Float = 1f
) {
    val width = size.width
    if (image == null || width <= 0f || height <= 0f || alpha <= 0f) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, top.roundToInt()),
        dstSize = IntSize(width.roundToInt(), height.roundToInt()),
        alpha = alpha
    )
}

/**
 * 「封面晕染」设置面板 —— 双击封面打开。
 *
 * 和设置页里那一份是同一组选项（包裹方式 / 浓度 / 过渡方式 / 补间时长）。
 * 两处都保留是有意的：在播放页看到颜色不对可以就地调，不用退出去翻设置再回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlowSheet(
    mode: GlowMode,
    strength: Float,
    transition: GlowTransition,
    fadeMs: Int,
    onModeChange: (GlowMode) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onTransitionChange: (GlowTransition) -> Unit,
    onFadeMsChange: (Int) -> Unit,
    vinylMode: Boolean,
    onVinylModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = skin.surface.solidSurface()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "封面显示",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "晕染与黑碟。以后想调，双击封面就行。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )

            Spacer(Modifier.height(18.dp))
            Text("包裹方式", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) skin.accentGlow else skin.surface2)
                            .clickable { onModeChange(m) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = m.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) skin.accent else skin.textDim
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(mode.hint, style = MaterialTheme.typography.labelSmall, color = skin.textDim)

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("浓度", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                Text(
                    text = "%.2f".format(strength),
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.accent
                )
            }
            Slider(
                value = strength,
                onValueChange = onStrengthChange,
                valueRange = Prefs.MIN_GLOW_STRENGTH..Prefs.MAX_GLOW_STRENGTH,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))
            Text("换歌过渡", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowTransition.entries.forEach { t ->
                    val selected = t == transition
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) skin.accentGlow else skin.surface2)
                            .clickable { onTransitionChange(t) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = t.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) skin.accent else skin.textDim
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(transition.hint, style = MaterialTheme.typography.labelSmall, color = skin.textDim)

            // 时长只在「渐变」下有意义：跟手模式的时间由手指决定，没有可调的时长
            if (transition == GlowTransition.FADE) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("渐变时长", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Text(
                        text = "${fadeMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.accent
                    )
                }
                Slider(
                    value = fadeMs.toFloat(),
                    onValueChange = { onFadeMsChange(it.roundToInt()) },
                    valueRange = Prefs.MIN_GLOW_FADE_MS.toFloat()..Prefs.MAX_GLOW_FADE_MS.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "黑碟播放器",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.text
                    )
                    Text(
                        "封面变圆形黑胶唱片，播放时缓慢旋转",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                Switch(checked = vinylMode, onCheckedChange = onVinylModeChange)
            }
        }
    }
}

/** 封面圆角唯一来源：投影、裁剪、内容绘制全部引用它，防止切换路径上形状漂移 */
private val CoverCornerRadius = 20.dp
private const val COVER_LIFT_PX = 40f

/**
 * 保留弹性区域的 0.41 : 0.59 排布，再将封面整体上移 [COVER_LIFT_PX] 个物理像素。
 * 不修改布局尺寸，歌词态不套这一层，仍占满整块。
 */
@Composable
private fun CoverLift(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -COVER_LIFT_PX },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.41f))
        content()
        Spacer(Modifier.weight(0.59f))
    }
}

/**
 * 单页播放器（自包含）：背景晕染、封面、文本、进度与控制。
 * 只接收派生值，不直接读 [PlayerUiState]，因此非当前页可被跳过重组。
 */
/* ============================================================
 * 上下切歌的退场过渡
 * ============================================================ */

/** 离开多少屏高后，底部功能区完全退场 */
private const val SWIPE_EXIT_SPAN = 0.40f

/** 退场后保留的透明度：不归零，避免与入页之间出现"真空帧" */
private const val SWIPE_EXIT_MIN_ALPHA = 0.05f

/** 退场时向下沉的量：控件像被页面"往下压走"，而不是凭空消失 */
private val SWIPE_EXIT_SINK = 28.dp

/**
 * 上下切歌时，让「进度行 + 控制行 + 音量行」这一组随页面离开而退场。
 *
 * 为什么需要：分页器是**整页刚性平移**，页内所有元素都跟着页走。旧页向上滑出时，
 * 它最底下的音量条会一路升到屏幕顶端再被硬裁掉 —— 一条高对比白线横穿全屏、
 * 停在顶栏下沿、然后瞬间消失，读起来就是"卡了一下"。
 *
 * 为什么只处理 `offset < 0`（向上离开）：
 * - 向上离开的页，底部控件会经过屏幕顶部 → 必须提前退场；
 * - 新页从下方进入时，底部控件本来就在屏幕外 → 不用管；
 * - 向下滑回上一首时，旧页向下移动，底部控件从屏幕底部退出 → 也不会横穿。
 * 非对称处理就够，**入页完全不受影响，跟手感不变**。
 *
 * 性能：偏移量在 `graphicsLayer` 的 block 里读 —— 那是**绘制阶段**，
 * 只让这一层的属性过期，不触发重组、不重新测量、不重绘内容。
 * 静止时 offset 为 0 走不进分支；alpha 保持 1 时 Compose 不做离屏合成，
 * 等于零开销。
 */
private fun Modifier.swipeExitFade(pager: PagerState, page: Int): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        // 视口中心对应的连续页位置；本页与它的差就是"离中心多远"（负 = 已向上离开）
        val offset = page - (pager.currentPage + pager.currentPageOffsetFraction)
        if (offset < 0f) {
            val t = (-offset / SWIPE_EXIT_SPAN).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)          // smoothstep：起步轻、收尾快
            alpha = 1f - eased * (1f - SWIPE_EXIT_MIN_ALPHA)
            translationY = eased * SWIPE_EXIT_SINK.toPx()
        }
    }

@Composable
private fun PlayerPage(
    song: Song,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    controlsPlaying: Boolean,
    /**
     * 三页共享的最终缩放（基础大小 + 呼吸），拖动、落定同步及缓冲期间完全冻结。
     */
    coverScale: State<Float>,
    playButtonPaletteProvider: () -> PlayButtonPalette,
    /** 三态播放模式。非当前页传列表循环（中性档），避免"翻到下一页看到随机亮着" */
    playMode: PlayMode,
    volume: Float,
    onToggleFavorite: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit,
    /** 打开均衡器面板 */
    onOpenEqualizer: () -> Unit,
    /** 均衡器是否开着（图标高亮用） */
    eqEnabled: Boolean,
    /** 当前播放速度（0.5–2.5 倍） */
    playbackSpeed: Float,
    /** 设置播放速度 */
    onSetSpeed: (Float) -> Unit,
    prefs: Prefs,
    /** 上下切歌的分页器 —— 只用来在绘制层读「本页离视口中心多远」，做退场过渡 */
    swipePager: PagerState,
    /** 本页在分页器里的页号（配合 [swipePager] 算偏移） */
    swipePage: Int,
    /** 当前这首在线歌实际拿到的音质，附在专辑后面显示 */
    streamQuality: String?,
    /** 当前页的晕染烘焙位图：内嵌歌词据此折算背景色带（跟随全屏歌词的调色设置）；非当前页传 null */
    glowImage: ImageBitmap?,
    /** 封面是否用「黑碟播放器」样式 */
    vinylMode: Boolean,
    /** 是否显示封面外右下角 Hi-Res 小金标 */
    hiResBadgeEnabled: Boolean,
    /** 小金标尺寸档位 */
    hiResBadgeSize: HiResBadgeSize,
    /** 「歌词」那一行是否已展开成单行滚动歌词 */
    lyricsExpanded: Boolean,
    /** 展开 / 收起那一行（不切页、不改布局） */
    onToggleLyrics: () -> Unit,
    /** 双击封面 → 打开晕染设置面板 */
    onOpenGlow: () -> Unit,
    /** 把封面的位置量出来上报 —— 晕染画在整页背景层，需要知道封面画在哪 */
    onCoverBounds: (Rect) -> Unit
) {
    val seed = song.title + song.artist
    val frac = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 晕染色卡由 [FullPlayer] 统一取（那里还管着交叉淡化），这一页只负责：
    // ① 把封面矩形量出来上报给晕染层；② 双击封面打开晕染面板。
    //
    // 这里原先留着一份"自己取色 + 皮肤占位色兜底"的旧逻辑，是晕染搬到整页背景层
    // 之后没删干净的死代码：算出来的 glowColors 一次都没被读过。代价不只是几行 ——
    // ArtworkLoader.tintOf 读的是 SnapshotStateMap，于是分页器里**同时组合的 3 页**
    // 都对 tints 建立了快照依赖，任何一首歌的色卡被算出来都会让它们全部失效重组。
    val coverKey = OnlineCover.keyOf(song.title, song.artist)
    val showHiResBadge = if (hiResBadgeEnabled) {
        rememberHiResBadgeVisible(song, streamQuality)
    } else {
        false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val boxH = maxHeight
        val isWide = boxW >= 620.dp && boxW > boxH
        /**
         * 手机横屏细长屏（20:9 转过来那种）。这个形态高度特别矮，
         * 沿用通用横屏参数会让封面几乎撑满整屏、内容重心偏下。
         * 判据与「隐藏状态栏」共用 [isCompactLandscapePhone]，两处必须同步。
         */
        val compactPhone = isCompactLandscapePhone(LocalConfiguration.current)
        // 单次 BoxWithConstraints：原实现里再嵌了一层只为算封面边长，把这一层去掉，
        // 封面尺寸在外层一次算好，少一轮 measure。
        val portraitSide = if (!isWide) (if (boxW < boxH) boxW else boxH) else 0.dp
        val landscapeSide = when {
            !isWide -> 0.dp
            compactPhone -> landscapeCoverSizeCompact(boxW, boxH)
            else -> landscapeCoverSize(boxW, boxH)
        }
        // onGloballyPositioned 报的是像素，横屏推封面矩形时要用来算边长
        val landscapeSidePx = with(LocalDensity.current) { landscapeSide.toPx() }

        // 这里原来有一层页面背景渐变。**删掉了**：它是不透明的，会把画在上一层
        // （[FullPlayer] 最外那个 Box）的封面晕染整个盖住 —— 这是「顶栏一直没有颜色」
        // 的另一半原因。页面底色现在由 [FullPlayer] 根 Box 的 `skin.bg` 提供。

        if (isWide) {
            // ===== 横屏 / 宽屏：左右双栏 =====
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        // 手机横屏：两侧多留一点，底部比顶部多留，把整体重心往上提
                        if (compactPhone) PaddingValues(
                            start = 48.dp, top = 4.dp, end = 48.dp, bottom = 26.dp
                        ) else PaddingValues(horizontal = 36.dp, vertical = 6.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (compactPhone) 56.dp else 40.dp
                )
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 晕染要的是「封面实际占的那块矩形」，这里从**左栏**推出来，
                        // 而不是去量封面本身 —— 封面在歌词态根本不在组合里，
                        // 量它的话一进播放页就是歌词态时永远拿不到值，切回封面才
                        // 第一次测到，晕染就「闪」出来（用户反馈的现象）。
                        //
                        // 横屏封面是定边长的正方形、在左栏里垂直居中（靠 Row 的
                        // verticalAlignment），所以「左栏中心 + landscapeSide」就是它。
                        // 不能直接报左栏矩形：歌词态的 InlineLyrics(fillMaxSize)
                        // 会把左栏撑到全高，矩形跟着变，切换时同样会跳。
                        .onGloballyPositioned { coords ->
                            val center = coords.boundsInRoot().center
                            onCoverBounds(
                                Rect(
                                    offset = Offset(
                                        center.x - landscapeSidePx / 2f,
                                        center.y - landscapeSidePx / 2f - COVER_LIFT_PX
                                    ),
                                    size = Size(landscapeSidePx, landscapeSidePx)
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 主区域在「封面」与「歌词」之间切换。
                    // 主流播放器就是这样：歌词占的就是封面那一块，而不是另开一整屏。
                    //
                    // SizeTransform(clip = false)：封面是正方形、歌词是整块区域，
                    // 默认的 SizeTransform 会按动画中的矩形边界裁剪旧内容，把封面的
                    // 20dp 圆角切掉 —— 表现就是「切歌词时圆角闪一下」。关掉裁剪后
                    // 圆角全程保持，溢出由外层 clipToBounds 兜住。
                    AnimatedContent(
                        targetState = lyricsExpanded,
                        transitionSpec = {
                            (fadeIn(tween(160)) togetherWith fadeOut(tween(120)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            InlineLyrics(
                                song = song,
                                positionMs = positionMs,
                                prefs = prefs,
                                onSeek = onSeek,
                                isPlaying = isPlaying,
                                glowImage = glowImage,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (vinylMode) {
                            VinylCover(
                                song = song,
                                isPlaying = isPlaying,
                                modifier = Modifier
                                    .size(landscapeSide)
                                    .graphicsLayer {
                                        scaleX = coverScale.value
                                        scaleY = coverScale.value
                                        translationY = -COVER_LIFT_PX
                                    }
                                    .onCoverGesture(onLongPress = onOpenCover, onDoubleTap = onOpenGlow)
                            )
                        } else {
                            CoverArt(
                                albumId = song.albumId,
                                seed = seed,
                                modifier = Modifier
                                    .size(landscapeSide)
                                    // 缩放只挂在绘制层：读取发生在 graphicsLayer 里，
                                    // 不触发重组/重新测量。数值三页共享（同一 State）。
                                    .graphicsLayer {
                                        val s = coverScale.value
                                        scaleX = s
                                        scaleY = s
                                        translationY = -COVER_LIFT_PX
                                        transformOrigin = TransformOrigin.Center
                                    }
                                    .shadow(18.dp, RoundedCornerShape(CoverCornerRadius), clip = false)
                                    .clip(RoundedCornerShape(CoverCornerRadius))
                                    // 位置由左栏那一层上报（见上面的注释）
                                    .onCoverGesture(onLongPress = onOpenCover, onDoubleTap = onOpenGlow),
                                cornerRadius = CoverCornerRadius,
                                sizePx = 900,
                                sourceUri = song.uri,
                                songId = song.id,
                                coverKey = coverKey
                            )
                        }
                    }
                    // 静止放在封面外右下角正下方，脱离 coverScale 缩放层以消除呼吸抖动
                    if (!lyricsExpanded && showHiResBadge) {
                        val density = LocalDensity.current
                        val badgeOffsetY = with(density) {
                            -COVER_LIFT_PX + hiResBadgeTotalDp(hiResBadgeSize).toPx() + 10.dp.toPx()
                        }
                        Box(Modifier.size(landscapeSide)) {
                            HiResGoldBadge(
                                badgeSize = hiResBadgeSize,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .graphicsLayer { translationY = badgeOffsetY }
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    // 信息行：收藏 + 歌名/歌手·专辑 + 歌词胶囊（参考样机 track-meta-bar）
                    TrackMetaBar(
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        quality = streamQuality,
                        isFavorite = isFavorite,
                        lyricsExpanded = lyricsExpanded,
                        onToggleFavorite = onToggleFavorite,
                        onToggleLyrics = onToggleLyrics
                    )
                    Spacer(Modifier.height(14.dp))
                    // 上下切歌时这一组「进度 / 控制 / 音量」随页面离开而退场，
                    // 避免旧页的音量条一路升到屏幕顶端再被硬裁掉（见 [swipeExitFade]）。
                    Column(Modifier.fillMaxWidth().swipeExitFade(swipePager, swipePage)) {
                    ProgressBlock(frac, positionMs, durationMs, onSeek, playbackSpeed, onSetSpeed)
                    Spacer(Modifier.height(16.dp))
                    TransportControls(
                        isPlaying = controlsPlaying,
                        playMode = playMode,
                        onCyclePlayMode = onCyclePlayMode,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        // 队列入口就放在这里 —— 原来这个位置是"循环模式"按钮，
                        // 循环和随机合并之后空出来的槽位正好给它
                        onOpenQueue = onOpenQueue,
                        ctrlSize = 40.dp,
                        ctrlIcon = 20.dp,
                        playSize = 60.dp,
                        playIcon = 26.dp,
                        playButtonPaletteProvider = playButtonPaletteProvider,
                        gap = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    BottomRow(
                        volume, onVolume,
                        onOpenEqualizer
                    )
                    }
                }
            }
        } else {
            // ===== 竖屏：封面在上方弹性居中，信息与控制整体贴底 =====
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var portraitAreaHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 晕染要的封面矩形从**主区域**量，而不是量封面本身 ——
                        // 封面在歌词态根本不在组合里，量它的话一进播放页就是歌词态时
                        // 永远拿不到值，切回封面才第一次测到，晕染就「闪」出来
                        // （用户反馈的现象）。
                        //
                        // 竖屏这里可以直接报主区域矩形：封面被分页内容的
                        // min constraints 撑满了这一块，两者是同一个矩形。
                        .onGloballyPositioned {
                            portraitAreaHeightPx = it.size.height
                            onCoverBounds(it.boundsInRoot().translate(Offset(0f, -COVER_LIFT_PX)))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 同上：歌词就在封面这一块里换出来
                    AnimatedContent(
                        targetState = lyricsExpanded,
                        transitionSpec = {
                            (fadeIn(tween(160)) togetherWith fadeOut(tween(120)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            InlineLyrics(
                                song = song,
                                positionMs = positionMs,
                                prefs = prefs,
                                onSeek = onSeek,
                                isPlaying = isPlaying,
                                glowImage = glowImage,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (vinylMode) {
                            CoverLift {
                                VinylCover(
                                    song = song,
                                    isPlaying = isPlaying,
                                    modifier = Modifier
                                        .size(portraitSide)
                                        .graphicsLayer {
                                            scaleX = coverScale.value
                                            scaleY = coverScale.value
                                        }
                                        .onCoverGesture(onLongPress = onOpenCover, onDoubleTap = onOpenGlow)
                                )
                            }
                        } else {
                            CoverLift {
                                CoverArt(
                                    albumId = song.albumId,
                                    seed = seed,
                                    modifier = Modifier
                                        .size(portraitSide)
                                        // 见横屏那处说明：缩放挂在 clip 外层，
                                        // 圆角/阴影随整体一起缩放，形状不会被裁掉一角。
                                        .graphicsLayer {
                                            val s = coverScale.value
                                            scaleX = s
                                            scaleY = s
                                            transformOrigin = TransformOrigin.Center
                                        }
                                        .shadow(18.dp, RoundedCornerShape(CoverCornerRadius), clip = false)
                                        .clip(RoundedCornerShape(CoverCornerRadius))
                                        // 位置由主区域那一层上报（见上面的注释）——
                                        // 挂在这里的话歌词态就断了
                                        .onCoverGesture(onLongPress = onOpenCover, onDoubleTap = onOpenGlow),
                                    cornerRadius = CoverCornerRadius,
                                    sizePx = 900,
                                    sourceUri = song.uri,
                                    songId = song.id,
                                    coverKey = coverKey
                                )
                            }
                        }
                    }
                    // 静止放在封面外右下角正下方（不进入 CoverLift / coverScale，杜绝呼吸抖动）
                    if (!lyricsExpanded && showHiResBadge) {
                        val density = LocalDensity.current
                        val coverSidePx = with(density) { portraitSide.toPx() }
                        val gapPx = with(density) { 10.dp.toPx() }
                        val badgeTotalPx = with(density) { hiResBadgeTotalDp(hiResBadgeSize).toPx() }
                        val bottomMarginPx = with(density) { 2.dp.toPx() }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(portraitSide)
                                .fillMaxHeight()
                        ) {
                            HiResGoldBadge(
                                badgeSize = hiResBadgeSize,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .graphicsLayer {
                                        if (portraitAreaHeightPx <= 0) {
                                            alpha = 0f
                                        } else {
                                            val h = portraitAreaHeightPx.toFloat()
                                            val side = minOf(coverSidePx, h)
                                            val desiredY = (h - side) * 0.41f - COVER_LIFT_PX + side + gapPx
                                            val maxY = (h - badgeTotalPx - bottomMarginPx).coerceAtLeast(0f)
                                            translationY = desiredY.coerceIn(0f, maxY)
                                        }
                                    }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))
                // 信息行：收藏 + 标题/歌手/专辑（三行）+ 歌词胶囊
                TrackMetaBar(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    quality = streamQuality,
                    isFavorite = isFavorite,
                    lyricsExpanded = lyricsExpanded,
                    onToggleFavorite = onToggleFavorite,
                    onToggleLyrics = onToggleLyrics
                )
                Spacer(Modifier.height(12.dp))
                // 同横屏：这一组随页面离开而退场（见 [swipeExitFade]）
                Column(Modifier.fillMaxWidth().swipeExitFade(swipePager, swipePage)) {
                ProgressBlock(frac, positionMs, durationMs, onSeek, playbackSpeed, onSetSpeed)
                Spacer(Modifier.height(14.dp))
                TransportControls(
                    isPlaying = controlsPlaying,
                    playMode = playMode,
                    onCyclePlayMode = onCyclePlayMode,
                    onPrev = onPrev,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    // 见竖屏那边的说明：这个槽位原来放"循环模式"，现在给队列
                    onOpenQueue = onOpenQueue,
                    ctrlSize = 44.dp,
                    ctrlIcon = 22.dp,
                    playSize = 64.dp,
                    playIcon = 28.dp,
                    playButtonPaletteProvider = playButtonPaletteProvider,
                    gap = 14.dp,
                    // 内层 Column 默认左对齐：不撑满宽度的话整行控制键会贴左，
                    // 撑满后 spacedBy 的 CenterHorizontally 才生效（与横屏一致）
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                BottomRow(
                    volume, onVolume,
                    onOpenEqualizer
                )
                }
                // 距底部 20dp（再叠加系统导航栏安全区）
                Spacer(Modifier.height(BottomGap))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/**
 * 信息行：`收藏 | 标题 / 歌手 / 专辑（三行） | 歌词胶囊`。
 *
 * 用户要求标题、歌手、专辑三行独立显示（不能合并成 `歌手 · 专辑` 一行）。
 * 三行都单行省略；即使歌手/专辑为空也保留行高（空格占位），保证每一页信息区
 * 高度完全相同 —— 否则底部贴底的信息区会随歌变高/变矮，把上方封面顶得上下挪，
 * 滑动切歌时就表现为「封面跳一下」。收藏和「词」胶囊垂直居中于三行块。
 */
@Composable
private fun TrackMetaBar(
    title: String,
    artist: String,
    album: String,
    quality: String? = null,
    isFavorite: Boolean,
    lyricsExpanded: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleLyrics: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 收藏：42dp 热区、22dp 图标、无圆底（样机 like-pure-btn）
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                contentDescription = "收藏",
                tint = if (isFavorite) Color(0xFFFF4D6D) else skin.text.copy(alpha = 0.72f),
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            // 歌手 / 专辑两行即使为空也保留行高（用空格占位）：
            // 信息区高度一旦随歌变化，底部贴底的这一块就会变高/变矮，
            // 上方弹性区里的封面跟着上下挪 —— 相邻页尺寸一样、位置却不同，
            // 滑动时看起来就是「封面跳一下」。固定三行高度才能让每页完全对齐。
            Spacer(Modifier.height(1.dp))
            Text(
                text = artist.ifBlank { " " },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = skin.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(1.dp))
            Text(
                // 音质附在专辑后面：占同一行，不破坏"固定三行"的高度约定
                text = buildString {
                    if (album.isNotBlank()) append(album)
                    if (!quality.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(quality)
                    }
                }.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        LyricsPillButton(expanded = lyricsExpanded, onClick = onToggleLyrics)
    }
}

/**
 * 「词」胶囊：参考样机的 lyrics-pill —— 半透明底、1px 细边、圆角 16，
 * 展开时描边与图标变主题色。样机用 `backdrop-filter: blur(10px)`，
 * Compose 里实时背景模糊代价高，这里换成了 55% 透明底 + 细描边（观感接近）。
 */
@Composable
private fun LyricsPillButton(expanded: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "lyricsPill"
    )
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(if (expanded) skin.accentGlow else skin.surface2.copy(alpha = 0.55f))
            .border(1.dp, if (expanded) skin.accent else Color.White.copy(alpha = 0.12f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = SonanceIcons.Lyrics,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "词",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = skin.text
        )
    }
}

@Composable
private fun ProgressBlock(
    frac: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    playbackSpeed: Float,
    onSetSpeed: (Float) -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        SonanceSlider(
            value = frac,
            onValueChange = { f -> onSeek((f * durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 4.dp,
            thumbSize = 13.dp,
            alwaysShowThumb = true,
            // 与下方音量滑块同一套配色：已播放/滑块 = textDim，未播放 = surface3。
            // 不再用 accent 渐变 —— 深色皮肤里 accent 近似黑色，和界面不协调。
            activeColor = skin.textDim,
            inactiveColor = skin.surface3,
            thumbHalo = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(positionMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = skin.textMuted
            )
            // 播放速度胶囊：**点一下切到下一档**（0.5× → 0.75× → 1× → … 循环），不弹菜单。
            // 非 1.0× 时亮主题色，一眼能看出"正在快进/慢放"。
            // 每次切换给一次短震动，闭着眼睛点也能确认"档位变了"。
            Text(
                text = formatSpeed(playbackSpeed),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Medium,
                color = if (playbackSpeed != 1f) skin.accent else skin.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        hapticTick(context)
                        onSetSpeed(nextSpeed(playbackSpeed))
                    }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = skin.textMuted
            )
        }
    }
}

/** 倍速档位：0.5×–2.0×。超过 2 倍对音乐播放意义不大，就不给更大的档了 */
private val SPEED_STEPS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 点一下的下一档，到 2× 后回到 0.5×。
 *
 * 当前值不在档位里（旧版本可能存过 1.1× 之类的任意值）时回到 1×，
 * 而不是从"最近的一档"接着往后跳，避免用户点一下就莫名变成 1.5×。
 */
private fun nextSpeed(current: Float): Float {
    val i = SPEED_STEPS.indexOfFirst { abs(it - current) < 0.005f }
    return if (i < 0) 1f else SPEED_STEPS[(i + 1) % SPEED_STEPS.size]
}

/** "1.0×" / "0.75×" 这种统一两位小数位的写法，菜单与胶囊都用它 */
private fun formatSpeed(v: Float): String =
    (if (v % 1f == 0f) "${v.toInt()}.0" else "$v") + "×"

/**
 * 播放键下面那一排：**音量 + 均衡器**（收藏已上移到信息行左侧）。
 *
 * 参考样机 bottom-utility：左边是静音图标（在 [VolumeSlider] 内部），
 * 中间音量条，右侧音效入口。人声/伴奏开关已收进均衡器面板。
 */
@Composable
private fun BottomRow(
    volume: Float,
    onVolume: (Float) -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VolumeSlider(
            volume = volume,
            onVolumeChange = onVolume,
            modifier = Modifier.weight(1f)
        )
        IconAction(
            icon = SonanceIcons.Equalizer,
            contentDescription = "均衡器",
            onClick = onOpenEqualizer,
            iconSize = 20.dp,
            tint = skin.accent
        )
    }
}

private fun landscapeCoverSize(width: Dp, height: Dp): Dp {
    val byHeight = height - 28.dp
    val byWidth = width * 0.44f
    return if (byHeight < byWidth) byHeight else byWidth
}

/**
 * 手机横屏细长屏专用的封面尺寸。
 *
 * 通用公式取 `height - 28.dp`，在这种矮窗口里等于让封面占满 ~81% 屏高，
 * 上下几乎不留白，视觉上又大又贴底。这里改成按高度取 68%、按宽度取 34%，
 * 两者取小 —— 实测在这个比例下封面约 600dp 见方，上下各留出明显呼吸空间。
 *
 * 只被 [isCompactLandscapePhone] 命中的形态调用，平板/其它横屏仍走原公式。
 */
private fun landscapeCoverSizeCompact(width: Dp, height: Dp): Dp {
    val byHeight = height * 0.68f
    val byWidth = width * 0.34f
    return if (byHeight < byWidth) byHeight else byWidth
}

/** 播放页内容距底部的留白 */
private val BottomGap = 14.dp

/**
 * 底部安全区内不响应「上下滑切歌」。
 *
 * 只挡竖向拖拽，不动布局、不挡别的交互：
 * - 起点不在安全区 → 一个事件都不消费，分页器照常翻页；
 * - 起点在安全区 → 等竖向位移超过 touch slop 再 consume，
 *   分页器的 scrollable 发现事件已被消费就放弃这次拖拽（不会切歌）；
 * - 没超过 slop 就抬手 = 点击，不消费 → 暂停/播放、收藏、队列等按钮正常；
 * - 横向位移大于竖向时不消费 → 音量条拖动正常；
 * - 系统「底部上滑回桌面」是系统级手势，App 侧的 consume 拦不住它，
 *   所以留白区上滑依然能正常返回桌面。
 */
private fun Modifier.blockSongSwipeInBottomBand(bandPx: Float): Modifier =
    this.pointerInput(bandPx) {
        if (bandPx <= 0f) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 起点在安全区之上：完全放行，不参与这次手势
            if (down.position.y < size.height - bandPx) return@awaitEachGesture
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
