package com.sonance.player.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CustomBackdropStore
import com.sonance.player.data.LaunchCoverSnapshot
import com.sonance.player.data.CoverRequest
import com.sonance.player.data.CoverTint
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.HiResQuality
import com.sonance.player.data.MotionFeel
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.PlayerLayoutStyle
import com.sonance.player.data.PlayerSwitchMotion
import com.sonance.player.desktoplyrics.DesktopLyricsManager
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PcmBus
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.PlayMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.isCompactLandscapePhone
import com.sonance.player.ui.theme.HiResBadgeSize
import com.sonance.player.ui.theme.LocalPrefs
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.scaleMotionDuration
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

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
    isSongFavorite: ((Song) -> Boolean)? = null,
    isDownloadBusy: (Song) -> Boolean = { false },
    isDownloaded: (Song) -> Boolean = { false },
    onDownloadSong: (Song) -> Unit = {},
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
    dlnaActive: Boolean = false,
    onOpenDlna: () -> Unit = {},
    /** 打开睡眠定时器面板 */
    onOpenSleepTimer: () -> Unit = {},
    /** 把正在播放的歌加入自建歌单（右上角「更多」面板）。null 时不显示这一项 */
    onAddToPlaylist: ((Song) -> Unit)? = null,
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
    /** 切换 A-B 循环（未开启 → 设 A 点 → 设 B 点开启循环 → 关闭） */
    onToggleAbLoop: () -> Unit = {},
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
    /** 点击上一曲 / 下一曲按钮的切歌动效 */
    switchMotion: PlayerSwitchMotion = PlayerSwitchMotion.POWERAMP_SLIDE,
    /** 普通播放主界面样式（经典样式 / 封面墙样式） */
    layoutStyle: PlayerLayoutStyle = PlayerLayoutStyle.CLASSIC,
    /** 封面墙样式是否用歌曲真实封面替换程序艺术替代封面 */
    coverWallUseRealCover: Boolean = false,
    /** 双击封面那个面板改了包裹方式 */
    onGlowModeChange: (GlowMode) -> Unit = {},
    /** 双击封面那个面板改了浓度 */
    onGlowStrengthChange: (Float) -> Unit = {},
    /** 双击封面那个面板改了过渡方式 */
    onGlowTransitionChange: (GlowTransition) -> Unit = {},
    /** 双击封面那个面板改了补间时长 */
    onGlowFadeMsChange: (Int) -> Unit = {},
    /** 右上角菜单切换「切歌动效」 */
    onSwitchMotionChange: (PlayerSwitchMotion) -> Unit = {},
    /** 右上角菜单切换「播放页样式」 */
    onLayoutStyleChange: (PlayerLayoutStyle) -> Unit = {},
    /** 切换「封面墙样式」下的真实/替代封面 */
    onCoverWallUseRealCoverChange: (Boolean) -> Unit = {},
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
    /** 播放队列弹窗覆盖在上方时暂停呼吸动画、延后分页器跳页，防止随机切歌掉帧。 */
    coveredByQueue: Boolean = false,
    /** 队列面板主动点歌的递增序号，用于区分「队列点歌」与「播完自动切歌」。 */
    queuePickSeq: Long = 0L,
    /** 全局流体动效手感 */
    motionFeel: MotionFeel = MotionFeel.FLUID,
    /** 封面圆角大小 */
    coverCornerDp: Dp = 20.dp,
    /** 顶栏下拉跟手收起回调 */
    onSheetDragStart: () -> Unit = {},
    onSheetDragDelta: (Float) -> Unit = {},
    onSheetDragEnd: (Float) -> Unit = {},
    customPlayerBgEnabled: Boolean = false,
    customPlayerBgRev: Long = 0L,
    customPlayerBgBlurDp: Int = 8,
    customPlayerBgDimPct: Int = 42,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val prefs = LocalPrefs.current ?: remember { com.sonance.player.data.Prefs(context) }
    val scope = rememberCoroutineScope()

    /**
     * 「歌词」那一行是否展开成了单行滚动歌词。
     *
     * 注意与左右滑动进入歌词层是两条路：歌词层是**全屏歌词页**（那里能看完整歌词、
     * 能点行跳转、能调字号/玻璃），这里的展开只在原地显示一行。
     */
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }

    /**
     * 胶囊「词」：封面下方显示两行歌词（当前 + 下一句），不再把封面藏掉。
     * 状态写入 [Prefs.coverLyricsInline]，冷启动/重建后依然保持用户的选择。
     */
    var coverLyricsInline by remember { mutableStateOf(prefs.coverLyricsInline) }

    /** 双击封面打开的「晕染」面板 */
    var showGlowSheet by rememberSaveable { mutableStateOf(false) }

    /** 右上角胶囊更多按钮打开的「更多功能」面板 */
    var showTopMoreSheet by rememberSaveable { mutableStateOf(false) }

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

    // 队列被重新洗牌/还原，或者短暂存在未映射曲目导致 queueIndex 与 mediaId 短时错位时，
    // 始终以「实际正在播的 mediaId 在当前展示队列中的下标」作为视觉对位基准，彻底杜绝封面/歌名闪成另一首歌。
    val activeQueueIndex = remember(queue, state.queueIndex, state.mediaId) {
        if (state.mediaId != 0L) {
            val atIdx = queue.getOrNull(state.queueIndex)
            if (atIdx != null && atIdx.id == state.mediaId) state.queueIndex
            else queue.indexOfFirst { it.id == state.mediaId }.takeIf { it >= 0 } ?: state.queueIndex
        } else {
            state.queueIndex
        }
    }
    val latestActiveIdx by rememberUpdatedState(activeQueueIndex)

    // 歌曲分页器。**必须声明在晕染素材之前** —— 跟手模式要按它的滚动位置混合颜色。
    val pagerState = rememberPagerState(
        initialPage = activeQueueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
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

    var pendingSwipeTarget by remember { mutableIntStateOf(-1) }
    var pendingSwipeUntilMs by remember { mutableLongStateOf(0L) }
    var lastSyncedMediaId by remember { mutableLongStateOf(state.mediaId) }
    var lastSyncedQueueIds by remember { mutableStateOf(state.queueIds) }
    var lastAnimatedSong by remember { mutableStateOf(queue.getOrNull(activeQueueIndex)) }
    var lastAnimatedIndex by remember { mutableIntStateOf(activeQueueIndex) }

    var switchSeq by remember { mutableLongStateOf(0L) }
    var pendingButtonSwitch by remember { mutableStateOf<ButtonSwitchState?>(null) }
    var activeSwitch by remember { mutableStateOf<ButtonSwitchState?>(null) }
    var optimisticTargetIdx by remember { mutableIntStateOf(-1) }
    var optimisticUntilMs by remember { mutableLongStateOf(0L) }
    var skipNextFadeOnSync by remember { mutableStateOf(false) }
    var audioDispatchJob by remember { mutableStateOf<Job?>(null) }
    val prevKick = remember { Animatable(0f) }
    val nextKick = remember { Animatable(0f) }
    var lastSwitchTriggeredMs by remember { mutableLongStateOf(0L) }
    var lastSeenQueuePickSeq by remember { mutableLongStateOf(queuePickSeq) }
    var lastHandledQueuePickSeq by remember { mutableLongStateOf(queuePickSeq) }
    var lastQueuePickAtMs by remember { mutableLongStateOf(0L) }
    var glowBakeTick by remember { mutableIntStateOf(0) }
    if (queuePickSeq != lastSeenQueuePickSeq) {
        lastSeenQueuePickSeq = queuePickSeq
        lastQueuePickAtMs = android.os.SystemClock.uptimeMillis()
    }

    val displayedQueueIndex = if (optimisticTargetIdx in queue.indices) {
        optimisticTargetIdx
    } else {
        activeQueueIndex
    }
    val latestDisplayedIdx by rememberUpdatedState(displayedQueueIndex)

    val curGlowColors = rememberGlowColors(queue.getOrNull(displayedQueueIndex), skin)
    LaunchedEffect(curGlowColors, displayedQueueIndex) {
        val activeSong = queue.getOrNull(displayedQueueIndex)
        if (activeSong != null && curGlowColors.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                LaunchCoverSnapshot.saveBands(context, activeSong.id, curGlowColors)
            }
        }
    }
    val smoothGlow = remember(skin.id) {
        val initial = curGlowColors.ifEmpty {
            resolveGlowColors(queue.getOrNull(pagerState.currentPage), skin, context)
        }
        SmoothGlowController(initial, skin.bg)
    }
    val drawGlowFade = remember(skin.id, glowMode) { DrawGlowCrossfade() }
    val useSmoothGlow = !isSwipeGlow || smoothGlow.fadeActive

    // 零重组驱动晕染颜色过渡：只在离散曲目变化时启动 0->1 进度补间，
    // 绘制阶段再跨两张缓存位图做 alpha 叠加，绝不在 120Hz 期间重烘位图。
    LaunchedEffect(curGlowColors, useSmoothGlow, displayedQueueIndex, glowFadeMs, skin.id) {
        if (!useSmoothGlow || curGlowColors.isEmpty()) return@LaunchedEffect
        if (activeSwitch == null && optimisticTargetIdx !in queue.indices) {
            snapshotFlow {
                !pagerState.isScrollInProgress && !userDraggedPager
            }.first { it }
        }
        val normalized = SmoothGlowController.normalizeColors(curGlowColors, skin.bg)
        if (!smoothGlow.hasColors) {
            smoothGlow.snapTo(normalized, skin.bg)
        } else if (smoothGlow.toColors != normalized || smoothGlow.progress.value < 0.999f) {
            val startColors = smoothGlow.currentColors().ifEmpty { normalized }
            withContext(Dispatchers.Default) {
                cachedGlowFrame(startColors, glowMode, glowBakeStrength)
                cachedGlowFrame(normalized, glowMode, glowBakeStrength)
            }
            glowBakeTick++
            smoothGlow.fromColors = startColors
            smoothGlow.toColors = normalized
            smoothGlow.progress.snapTo(0f)
            val effectiveFadeMs = if (isSwipeGlow) {
                glowFadeMs.coerceIn(520, 900)
            } else {
                glowFadeMs.coerceIn(GLOW_FADE_RANGE)
            }
            smoothGlow.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = effectiveFadeMs,
                    easing = FastOutSlowInEasing
                )
            )
        }
        smoothGlow.fadeActive = false
    }

    // 跟手模式下的零重组背景渐变补全：仅当随机/异曲的异步封面主色在动画结束之后才就绪时，
    // 在绘制阶段将 lastDrawnFrame -> nextFrame 平滑交叉溶解，不触发 FullPlayer 任何重组。
    LaunchedEffect(displayedQueueIndex, curGlowColors, glowMode, glowBakeStrength, skin.id, isSwipeGlow, layoutStyle) {
        if (!isSwipeGlow || layoutStyle != PlayerLayoutStyle.CLASSIC || curGlowColors.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            activeSwitch == null && optimisticTargetIdx !in queue.indices &&
                !pagerState.isScrollInProgress && !userDraggedPager
        }.first { it }
        val normalized = SmoothGlowController.normalizeColors(curGlowColors, skin.bg)
        val prevFrame = drawGlowFade.lastDrawnFrame
        if (prevFrame != null && prevFrame.colors.isNotEmpty() && prevFrame.colors != normalized) {
            val nextFrame = withContext(Dispatchers.Default) {
                cachedGlowFrame(normalized, glowMode, glowBakeStrength)
            }
            drawGlowFade.fromFrame = prevFrame
            drawGlowFade.toFrame = nextFrame
            drawGlowFade.active = true
            drawGlowFade.progress.snapTo(0f)
            try {
                drawGlowFade.progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing)
                )
                drawGlowFade.lastDrawnFrame = nextFrame
            } finally {
                drawGlowFade.active = false
            }
        } else {
            peekCachedGlowFrame(normalized, glowMode, glowBakeStrength)?.let {
                drawGlowFade.lastDrawnFrame = it
            }
        }
    }

    val buttonPaletteProvider = rememberPlayerControlPaletteProvider(state, queue.getOrNull(displayedQueueIndex))
    // 顶栏律动指示器的混色放在叶子组件 [HeaderPlayingIndicator] 的 Canvas 绘制阶段里算：
    // 滑动中每帧只触发该小 Canvas 重绘，不触发任何组合与测量，FullPlayer 零重组。

    // 预取前后两首（支持首尾环绕）的高清封面、色卡并提前烘入 LRU 位图缓存；
    // 避开切歌动画活跃期（650ms）与队列弹窗操作期再预热后续曲目，防止多路 900px 解码与 GC 抢占动画帧。
    LaunchedEffect(
        displayedQueueIndex, queue.size, coveredByVinyl, coveredByQueue,
        glowMode, glowBakeStrength, skin.id, layoutStyle, coverWallUseRealCover
    ) {
        if (queue.isEmpty() || coveredByVinyl || coveredByQueue) return@LaunchedEffect
        delay(650L)
        val n = queue.size
        val idx = displayedQueueIndex.coerceIn(0, n - 1)
        val offsets = if (n >= 5) listOf(0, 1, -1, 2, -2) else listOf(0, 1, -1, 2)
        val seenIdx = LinkedHashSet<Int>(5)
        offsets.forEach { off ->
            seenIdx.add(((idx + off) % n + n) % n)
        }
        val candidates = seenIdx.mapNotNull { queue.getOrNull(it) }
        if (layoutStyle == PlayerLayoutStyle.COVER_WALL && !coverWallUseRealCover) {
            coroutineScope {
                candidates.forEach { s ->
                    launch(Dispatchers.Default) { warmProceduralCoverBitmap(s) }
                }
            }
            return@LaunchedEffect
        }
        ArtworkLoader.prefetch(
            context = context,
            requests = candidates.map { s ->
                CoverRequest(s.albumId, s.uri, OnlineCover.keyOf(s.title, s.artist))
            },
            sizePx = 900
        )
        coroutineScope {
            candidates.forEach { s ->
                val key = OnlineCover.keyOf(s.title, s.artist)
                if (ArtworkLoader.tintOf(s.albumId, s.uri, key) == null) {
                    launch { ArtworkLoader.prefetchTint(context, s.albumId, s.uri, key) }
                }
            }
        }
        if (layoutStyle == PlayerLayoutStyle.COVER_WALL) return@LaunchedEffect
        snapshotFlow {
            candidates.map { s -> resolveGlowColors(s, skin) }
        }.first { colorLists ->
            val ready = colorLists.filter { it.isNotEmpty() }
            if (ready.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    ready.forEach { colors ->
                        cachedGlowFrame(colors, glowMode, glowBakeStrength)
                    }
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

    // 离开封面层（例如左右滑去全屏歌词 / 律动柱）时自动收起封面内歌词，避免下次滑回或后台残留
    LaunchedEffect(layer) {
        if (layer != PlayerLayer.COVER && lyricsExpanded) {
            lyricsExpanded = false
        }
    }

    val closePlayer: () -> Unit = {
        lyricsExpanded = false
        onClose()
    }

    // 歌词 / 律动柱上按返回键 → 滑回封面层，而不是直接收起播放页。
    // 拖动中不接管，免得和手指的滑动打架。
    BackHandler(enabled = layer != PlayerLayer.COVER && !layerPager.isScrollInProgress) {
        goToLayer(PlayerLayer.COVER)
    }

    // 仅在封面层且封面歌词已展开时接管返回键，先退回封面，不与其它层的 BackHandler 抢事件
    BackHandler(enabled = layer == PlayerLayer.COVER && lyricsExpanded) {
        lyricsExpanded = false
    }

    // 每个队列条目的稳定 key：同一首歌即使通过「下一首播放」在队列里出现多次，
    // 也能用 occurrence 区分，同时重排队列（随机 ↔ 列表/单曲/顺序）时当前播放页不会被销毁重建而闪屏。
    val pageKeys = remember(queue) {
        val seen = HashMap<Long, Int>(queue.size)
        queue.map { item ->
            val nth = seen.getOrDefault(item.id, 0)
            seen[item.id] = nth + 1
            "${item.id}#$nth"
        }
    }

    val switchDensity = LocalDensity.current
    fun peekCoverBitmapSync(s: Song): ImageBitmap? {
        val coverKey = OnlineCover.keyOf(s.title, s.artist)
        return ArtworkLoader.peekSelected(s.albumId, 900, s.uri, s.id)
            ?: ArtworkLoader.peekSelected(s.albumId, 256, s.uri, s.id)
            ?: ArtworkLoader.online(s.id)
            ?: ArtworkLoader.peekAny(s.albumId, 900, s.uri, coverKey)
            // 冷启动内存缓存为空：回落到上次播放封面落盘快照，首帧不空白
            ?: LaunchCoverSnapshot.peekCover(context, s.id)
    }

    fun triggerButtonSwitch(dir: Int, fallbackSwitch: () -> Unit) {
        val curIdx = displayedQueueIndex
        val from = queue.getOrNull(curIdx)
        val hasMulti = from != null && layer == PlayerLayer.COVER && !coveredByVinyl && queue.size > 1 && curIdx in queue.indices
        if (from != null && layer == PlayerLayer.COVER && !coveredByVinyl) {
            val targetIdx = if (hasMulti) ((curIdx + dir) % queue.size + queue.size) % queue.size else -1
            val targetSong = if (targetIdx >= 0) queue.getOrNull(targetIdx) else null
            val fromBmp = if (!vinylMode) peekCoverBitmapSync(from) else null
            val toBmp = if (!vinylMode && targetSong != null) peekCoverBitmapSync(targetSong) else null
            val prevSw = activeSwitch
            val carry = if (prevSw != null) {
                val rem = ((1f + prevSw.carryFrac) * (1f - prevSw.progress.value.coerceIn(0f, 1f)))
                    .coerceIn(0f, 0.92f)
                if (prevSw.dir == dir) rem else -rem
            } else {
                0f
            }
            val sw = ButtonSwitchState(
                seq = ++switchSeq,
                dir = dir,
                fromSong = from,
                fromIndex = curIdx,
                fromBitmap = fromBmp,
                toBitmap = toBmp,
                carryFrac = carry,
                trigger = SwitchTrigger.MANUAL_STEP
            )
            if (hasMulti) {
                // 手动切歌在四种播放模式下始终沿当前队列环绕推进，直接在第 0ms 乐观切过去，
                // 并把 ExoPlayer/MediaSession 切歌延后到动画落定时刻，避免音频解码与通知构建阻塞动画帧。
                optimisticTargetIdx = targetIdx
                optimisticUntilMs = android.os.SystemClock.uptimeMillis() + 1100L
                lastAnimatedSong = targetSong
                lastAnimatedIndex = targetIdx
                skipNextFadeOnSync = true
                if (isSwipeGlow && smoothGlow.fadeActive) {
                    smoothGlow.fadeActive = false
                }
                drawGlowFade.active = false
                pendingButtonSwitch = null
                activeSwitch = sw
                audioDispatchJob?.cancel()
                val baseMs = if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
                    420L
                } else {
                    when (switchMotion) {
                        PlayerSwitchMotion.POWERAMP_SLIDE -> 400L
                        PlayerSwitchMotion.DEPTH_SLIDE -> 420L
                        PlayerSwitchMotion.TILT_3D -> 440L
                        PlayerSwitchMotion.OPTICAL_DISSOLVE -> 450L
                    }
                }
                val scaledMs = scaleMotionDuration(baseMs.toInt(), motionFeel).toLong()
                audioDispatchJob = scope.launch {
                    delay(scaledMs)
                    onPlayAt(targetIdx)
                }
            } else {
                pendingButtonSwitch = sw
                fallbackSwitch()
            }
        } else {
            fallbackSwitch()
        }
        scope.launch {
            val targetKick = with(switchDensity) {
                (when (switchMotion) {
                    PlayerSwitchMotion.POWERAMP_SLIDE -> 6.0f
                    PlayerSwitchMotion.OPTICAL_DISSOLVE -> 2.5f
                    else -> 5.0f
                }).dp.toPx() * dir
            }
            val anim = if (dir < 0) prevKick else nextKick
            anim.snapTo(0f)
            anim.animateTo(targetKick, tween(110, easing = FastOutSlowInEasing))
            anim.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 460f))
        }
    }

    // 仅 queue.size <= 1 等无法乐观预测下标时的兜底锁存
    val pendingBtnNow = pendingButtonSwitch
    if (pendingBtnNow != null &&
        layer == PlayerLayer.COVER &&
        !coveredByVinyl &&
        (state.mediaId != pendingBtnNow.fromSong.id || activeQueueIndex != pendingBtnNow.fromIndex)
    ) {
        pendingButtonSwitch = null
        activeSwitch = pendingBtnNow
    }

    val queueIdsChanged = state.queueIds != lastSyncedQueueIds
    val hasPendingSwipe = pendingSwipeTarget >= 0 && !queueIdsChanged
    val optimisticLockActive = optimisticTargetIdx in queue.indices || pendingButtonSwitch != null
    val curSongForSwitch = queue.getOrNull(activeQueueIndex)
    val prevSongForSwitch = lastAnimatedSong
    val externalSongChanged = !coveredByVinyl &&
        layer == PlayerLayer.COVER &&
        !pagerState.isScrollInProgress &&
        !userDraggedPager &&
        !hasPendingSwipe &&
        !optimisticLockActive &&
        prevSongForSwitch != null &&
        curSongForSwitch != null &&
        (prevSongForSwitch.id != curSongForSwitch.id || (!queueIdsChanged && lastAnimatedIndex != activeQueueIndex))

    if (externalSongChanged) {
        val prev = prevSongForSwitch!!
        val nextSong = curSongForSwitch!!
        val dir = if (!queueIdsChanged && queue.size > 1 && lastAnimatedIndex in queue.indices && activeQueueIndex in queue.indices) {
            when {
                lastAnimatedIndex == queue.lastIndex && activeQueueIndex == 0 -> 1
                lastAnimatedIndex == 0 && activeQueueIndex == queue.lastIndex -> -1
                activeQueueIndex < lastAnimatedIndex -> -1
                else -> 1
            }
        } else {
            1
        }
        val nowMs = android.os.SystemClock.uptimeMillis()
        val trigger = classifyExternalSwitchTrigger(
            coveredByQueue = coveredByQueue,
            hasUnconsumedQueuePick = queuePickSeq != lastHandledQueuePickSeq,
            recentQueuePick = lastQueuePickAtMs > 0L && (nowMs - lastQueuePickAtMs) <= 1_500L
        )
        val fromBmp = if (!vinylMode) peekCoverBitmapSync(prev) else null
        val toBmp = if (!vinylMode) peekCoverBitmapSync(nextSong) else null
        activeSwitch = ButtonSwitchState(
            seq = ++switchSeq,
            dir = dir,
            fromSong = prev,
            fromIndex = lastAnimatedIndex,
            fromBitmap = fromBmp,
            toBitmap = toBmp,
            carryFrac = 0f,
            trigger = trigger
        )
        skipNextFadeOnSync = true
        if (isSwipeGlow && smoothGlow.fadeActive) {
            smoothGlow.fadeActive = false
        }
        drawGlowFade.active = false
        lastAnimatedSong = nextSong
        lastAnimatedIndex = activeQueueIndex
        if (trigger == SwitchTrigger.QUEUE_PICK) {
            lastHandledQueuePickSeq = queuePickSeq
        }
    } else if (!optimisticLockActive && curSongForSwitch != null) {
        lastAnimatedSong = curSongForSwitch
        lastAnimatedIndex = activeQueueIndex
    }

    val pagerLockActive = activeSwitch != null || optimisticTargetIdx in queue.indices || coveredByQueue
    val latestPagerLock by rememberUpdatedState(pagerLockActive)
    val latestActiveSwitch by rememberUpdatedState(activeSwitch)

    LaunchedEffect(pendingButtonSwitch?.seq) {
        val pending = pendingButtonSwitch ?: return@LaunchedEffect
        delay(450L)
        if (pendingButtonSwitch?.seq == pending.seq) {
            pendingButtonSwitch = null
        }
    }

    val hostView = LocalView.current
    androidx.compose.runtime.DisposableEffect(hostView, coveredByVinyl) {
        if (Build.VERSION.SDK_INT >= 35 && !coveredByVinyl) {
            hostView.requestedFrameRate = 120f
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 35) {
                hostView.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }
    val activeSwitchRef = activeSwitch
    LaunchedEffect(activeSwitchRef?.seq) {
        val sw = activeSwitchRef ?: return@LaunchedEffect
        val now = android.os.SystemClock.uptimeMillis()
        val isRapid = (now - lastSwitchTriggeredMs) < 380L
        lastSwitchTriggeredMs = now
        if (Build.VERSION.SDK_INT >= 35) {
            hostView.frameContentVelocity = when (sw.trigger) {
                SwitchTrigger.AUTO_ADVANCE -> 1200f
                SwitchTrigger.QUEUE_PICK -> 1800f
                SwitchTrigger.MANUAL_STEP -> 4000f
            }
        }
        val animMs = if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
            val wallBase = when (sw.trigger) {
                SwitchTrigger.AUTO_ADVANCE -> 560
                SwitchTrigger.QUEUE_PICK -> 380
                SwitchTrigger.MANUAL_STEP -> 480
            }
            val scaledWall = scaleMotionDuration(wallBase, motionFeel)
            if (isRapid) (scaledWall * 0.64f).toInt().coerceAtLeast(160) else scaledWall
        } else {
            resolveSwitchDurationMs(sw.trigger, switchMotion, motionFeel, isRapid)
        }
        val easing = if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
            CubicBezierEasing(0.22f, 0.72f, 0.24f, 1f)
        } else {
            when (sw.trigger) {
                SwitchTrigger.AUTO_ADVANCE -> CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
                SwitchTrigger.QUEUE_PICK -> CubicBezierEasing(0.20f, 0.88f, 0.26f, 1f)
                SwitchTrigger.MANUAL_STEP -> CubicBezierEasing(0.24f, 0.84f, 0.28f, 1f)
            }
        }
        sw.progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = animMs,
                easing = easing
            )
        )
        if (activeSwitch?.seq == sw.seq) {
            activeSwitch = null
        }
    }

    LaunchedEffect(activeSwitchRef?.seq, curGlowColors, glowMode, glowBakeStrength, skin.id, layoutStyle) {
        if (layoutStyle == PlayerLayoutStyle.COVER_WALL) return@LaunchedEffect
        val sw = activeSwitchRef ?: return@LaunchedEffect
        val fromColors = resolveGlowColors(sw.fromSong, skin)
        val toColors = curGlowColors
        val needFrom = fromColors.isNotEmpty() && peekCachedGlowFrame(fromColors, glowMode, glowBakeStrength) == null
        val needTo = toColors.isNotEmpty() && peekCachedGlowFrame(toColors, glowMode, glowBakeStrength) == null
        if (needFrom || needTo) {
            withContext(Dispatchers.Default) {
                if (needFrom) cachedGlowFrame(fromColors, glowMode, glowBakeStrength)
                if (needTo) cachedGlowFrame(toColors, glowMode, glowBakeStrength)
            }
            glowBakeTick++
        }
    }

    LaunchedEffect(optimisticTargetIdx, activeQueueIndex, activeSwitch) {
        if (optimisticTargetIdx >= 0 && activeSwitch == null && optimisticTargetIdx == activeQueueIndex) {
            optimisticTargetIdx = -1
        }
    }

    LaunchedEffect(optimisticTargetIdx, optimisticUntilMs) {
        if (optimisticTargetIdx < 0) return@LaunchedEffect
        val remaining = optimisticUntilMs - android.os.SystemClock.uptimeMillis()
        if (remaining > 0L) {
            delay(remaining)
        }
        if (activeSwitch == null) {
            optimisticTargetIdx = -1
        }
    }

    // 预探测前后曲目的 Hi-Res 标签，切歌第 0 帧即命中缓存，避免异步探测回落引起小金标延迟闪现
    LaunchedEffect(displayedQueueIndex, queue, coveredByQueue) {
        if (queue.isEmpty() || coveredByQueue) return@LaunchedEffect
        val n = queue.size
        val neighbors = (-2..2).mapNotNull { delta ->
            val idx = ((displayedQueueIndex + delta) % n + n) % n
            queue.getOrNull(idx)
        }.distinctBy { it.id }
        withContext(Dispatchers.IO) {
            neighbors.forEach { neighbor ->
                if (neighbor.isLocal && HiResQuality.peekLocalBadge(neighbor) == null) {
                    HiResQuality.probeLocalBadge(context, neighbor)
                }
            }
        }
    }

    val stalePagerPage = pagerState.currentPage
    val externalDesync = !coveredByVinyl &&
        !pagerState.isScrollInProgress &&
        !userDraggedPager &&
        !hasPendingSwipe &&
        !pagerLockActive &&
        activeQueueIndex in queue.indices &&
        stalePagerPage != activeQueueIndex

    SideEffect {
        if (queueIdsChanged) {
            lastSyncedQueueIds = state.queueIds
            if (pendingSwipeTarget >= 0) pendingSwipeTarget = -1
            if (optimisticTargetIdx >= 0) optimisticTargetIdx = -1
            pendingButtonSwitch = null
            if (!externalSongChanged) {
                activeSwitch = null
            }
            skipNextFadeOnSync = false
        }
        if (externalDesync) {
            pagerState.requestScrollToPage(activeQueueIndex)
        }
    }

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
                drawGlowFade.active = false
                pendingButtonSwitch = null
                activeSwitch = null
                optimisticTargetIdx = -1
                skipNextFadeOnSync = false
                audioDispatchJob?.cancel()
            }
            if (!scrolling) {
                if (queue.isNotEmpty() &&
                    userDraggedPager &&
                    !latestCoveredByVinyl &&
                    settledPage != latestActiveIdx
                ) {
                    val pageSong = latestQueue.getOrNull(settledPage)
                    if (pageSong != null) {
                        pendingSwipeTarget = settledPage
                        pendingSwipeUntilMs = android.os.SystemClock.uptimeMillis() + 600L
                        lastAnimatedSong = pageSong
                        lastAnimatedIndex = settledPage
                        onPlayAt(settledPage)
                    }
                }
                userDraggedPager = false
            }
        }
    }

    // 播放器 → 分页器：按钮/队列/自动切歌动效与乐观锁定期内保持分页器原地钉住，结束后再静默对齐页码。
    LaunchedEffect(activeQueueIndex, state.mediaId, queue, coveredByVinyl, pendingSwipeTarget, pagerLockActive) {
        if (queue.isEmpty() || coveredByVinyl || pagerLockActive) return@LaunchedEffect
        val target = activeQueueIndex.coerceIn(0, queue.lastIndex)
        if (pendingSwipeTarget >= 0) {
            if (target == pendingSwipeTarget) {
                pendingSwipeTarget = -1
                lastSyncedMediaId = state.mediaId
                return@LaunchedEffect
            }
            val remaining = pendingSwipeUntilMs - android.os.SystemClock.uptimeMillis()
            if (remaining > 0L) {
                delay(remaining)
            }
            pendingSwipeTarget = -1
        }
        val latestIdx = latestActiveIdx
        val currentMediaId = latestState.mediaId
        if (latestQueue.isEmpty() || latestIdx !in latestQueue.indices) return@LaunchedEffect
        val mediaChanged = currentMediaId != 0L && currentMediaId != lastSyncedMediaId
        val suppressFade = skipNextFadeOnSync
        if (suppressFade) skipNextFadeOnSync = false
        if (pagerState.settledPage != latestIdx && (!pagerState.isScrollInProgress || !userDraggedPager)) {
            userDraggedPager = false
            // 按钮切歌已经在动效期间完成晕染渐变，仅自动续播/外部点队列时触发淡入过渡。
            if (mediaChanged && !suppressFade) {
                if (isSwipeGlow && !smoothGlow.fadeActive) {
                    val fromColors = resolveGlowColors(latestQueue.getOrNull(pagerState.currentPage), skin)
                        .ifEmpty { resolveGlowColors(latestQueue.getOrNull(latestIdx), skin) }
                    if (fromColors.isNotEmpty()) {
                        smoothGlow.snapTo(fromColors, skin.bg)
                    }
                }
                smoothGlow.fadeActive = true
            }
            programmaticScroll = true
            try {
                pagerState.scrollToPage(latestIdx)
            } finally {
                programmaticScroll = false
            }
        }
        lastSyncedMediaId = currentMediaId
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
    val breathActive = state.isPlaybackActive && layer == PlayerLayer.COVER && !coveredByVinyl && !coveredByQueue
    val needCoverEnergy = layer == PlayerLayer.COVER && !coveredByVinyl
    val coverLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(needCoverEnergy, coverLifecycleOwner) {
        var acquired = false
        fun sync(st: Lifecycle.State) {
            val want = needCoverEnergy && st.isAtLeast(Lifecycle.State.STARTED)
            if (want && !acquired) {
                PcmBus.acquireConsumer()
                acquired = true
            } else if (!want && acquired) {
                PcmBus.releaseConsumer()
                acquired = false
            }
        }
        sync(coverLifecycleOwner.lifecycle.currentState)
        val observer = LifecycleEventObserver { _, _ -> sync(coverLifecycleOwner.lifecycle.currentState) }
        coverLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            coverLifecycleOwner.lifecycle.removeObserver(observer)
            if (acquired) PcmBus.releaseConsumer()
        }
    }

    LaunchedEffect(breathActive, layer, state.isPlaybackActive) {
        snapshotFlow {
            coveredByVinyl || coverTouched || activeSwitch != null || optimisticTargetIdx >= 0 ||
                pagerState.isScrollInProgress || layerPager.isScrollInProgress ||
                latestState.isBuffering || (latestQueue.isNotEmpty() && pagerState.settledPage != latestActiveIdx)
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

    val customPlayerRaw by produceState<Bitmap?>(
        initialValue = null,
        customPlayerBgEnabled,
        customPlayerBgRev
    ) {
        value = if (customPlayerBgEnabled) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CustomBackdropStore.loadBitmap(context, CustomBackdropStore.Slot.PLAYER)
            }
        } else null
    }
    val customPlayerImage by produceState<ImageBitmap?>(
        initialValue = null,
        customPlayerRaw,
        customPlayerBgBlurDp
    ) {
        val raw = customPlayerRaw
        value = if (raw == null) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || customPlayerBgBlurDp <= 0) {
            raw.asImageBitmap()
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                CustomBackdropStore.softBlur(raw, customPlayerBgBlurDp).asImageBitmap()
            }
        }
    }
    val resolvedWallColor = if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
        rememberCoverWallAccentColor(queue.getOrNull(displayedQueueIndex), coverWallUseRealCover)
    } else Color.Unspecified
    var stableWallColor by remember {
        mutableStateOf(
            if (resolvedWallColor != Color.Unspecified) resolvedWallColor else Color(0xFF282E42)
        )
    }
    if (resolvedWallColor != Color.Unspecified) {
        stableWallColor = resolvedWallColor
    }
    val animatedWallColorState = animateColorAsState(
        targetValue = stableWallColor,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "coverWallBgColor"
    )
    val isCoverWallDarkBg = layoutStyle == PlayerLayoutStyle.COVER_WALL && customPlayerImage == null
    val hasCustomBg = customPlayerImage != null
    val customBgDimPct = customPlayerBgDimPct
    val playerUiSkin = remember(skin, hasCustomBg, customBgDimPct, isCoverWallDarkBg, stableWallColor) {
        if (hasCustomBg) {
            val dimAlpha = customBgDimPct / 100f
            val rep = Color(
                red = 0.45f * (1f - dimAlpha),
                green = 0.45f * (1f - dimAlpha),
                blue = 0.45f * (1f - dimAlpha)
            )
            val accent = ensureReadable(skin.accent, rep, 3.2f, true)
            skin.copy(
                text = ensureReadable(skin.text, rep, 4.5f, true),
                textDim = ensureReadable(skin.textDim, rep, 3.8f, true),
                textMuted = ensureReadable(skin.textMuted, rep, 3.4f, true),
                accent = accent,
                accentDim = ensureReadable(skin.accentDim, rep, 2.8f, true),
                onAccent = ensureReadable(skin.onAccent, accent, 4.5f, false)
            )
        } else if (isCoverWallDarkBg) {
            val coverWallBackdrop = Color(0xFF0A0A0F)
            val accent = ensureReadable(stableWallColor, coverWallBackdrop, 3.4f, true)
            skin.copy(
                isLight = false,
                bg = Color(0xFF050507),
                surface = Color(0xFF14141A),
                surface2 = Color(0xFF1E1E26),
                surface3 = Color(0xFF2A2A34),
                text = Color(0xFFF3F3F7),
                textDim = Color(0xB3FFFFFF),
                textMuted = Color(0x88FFFFFF),
                accent = accent,
                accentDim = ensureReadable(accent.copy(alpha = 0.72f), coverWallBackdrop, 2.8f, true),
                onAccent = ensureReadable(skin.onAccent, accent, 4.5f, false)
            )
        } else skin
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSkin provides playerUiSkin) {
    Box(
        modifier
            .fillMaxSize()
            .then(
                if (customPlayerImage == null) {
                    Modifier.background(if (isCoverWallDarkBg) Color(0xFF050507) else skin.bg)
                } else Modifier
            )
            // 记下自己在 root 里的位置，用来把封面的 root 坐标换算成本页坐标
            .onGloballyPositioned { rootTopLeft = it.positionInRoot() }
            // 晕染画在**整页背景层**：这一层天然包含顶栏和状态栏，颜色能一路覆盖上去。
            // （之前画在封面那一层，被分页器裁掉了顶栏部分，所以顶部一直缺一块。）
            .drawBehind {
                if (customPlayerImage != null) return@drawBehind
                if (isCoverWallDarkBg) {
                    val glowColor = animatedWallColorState.value
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                glowColor.copy(alpha = 0.62f),
                                Color.Black.copy(alpha = 0.72f),
                                Color(0xFF050507)
                            )
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.24f),
                            radius = maxOf(size.width, size.height) * 0.62f
                        )
                    )
                    return@drawBehind
                }
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
                        @Suppress("UNUSED_VARIABLE")
                        val bakeTick = glowBakeTick
                        val p = smoothGlow.progress.value.coerceIn(0f, 1f)
                        val fromFrame = peekCachedGlowFrame(smoothGlow.fromColors, glowMode, glowBakeStrength)
                        val toFrame = peekCachedGlowFrame(smoothGlow.toColors, glowMode, glowBakeStrength)
                        if (p >= 0.999f || smoothGlow.fromColors == smoothGlow.toColors) {
                            val settled = toFrame ?: cachedGlowFrame(smoothGlow.toColors, glowMode, glowBakeStrength)
                            drawGlow(settled, cover, glowMode, strengthArg, alpha = alphaScale)
                        } else {
                            val fromReady = fromFrame != null && fromFrame.colors.isNotEmpty()
                            val toReady = toFrame != null && toFrame.colors.isNotEmpty()
                            when {
                                fromReady && toReady -> {
                                    drawGlow(fromFrame!!, cover, glowMode, strengthArg, alpha = (1f - p) * alphaScale)
                                    if (p > 0f) {
                                        drawGlow(toFrame!!, cover, glowMode, strengthArg, alpha = p * alphaScale)
                                    }
                                }
                                fromReady -> drawGlow(fromFrame!!, cover, glowMode, strengthArg, alpha = alphaScale)
                                toReady -> drawGlow(toFrame!!, cover, glowMode, strengthArg, alpha = alphaScale)
                                else -> {
                                    val fallbackTo = cachedGlowFrame(smoothGlow.toColors, glowMode, glowBakeStrength)
                                    drawGlow(fallbackTo, cover, glowMode, strengthArg, alpha = alphaScale)
                                }
                            }
                        }
                    } else if (activeSwitch != null) {
                        val sw = activeSwitch!!
                        val p = sw.progress.value.coerceIn(0f, 1f)
                        @Suppress("UNUSED_VARIABLE")
                        val bakeTick = glowBakeTick
                        val curQueue = latestQueue
                        val targetSong = curQueue.getOrNull(latestDisplayedIdx)
                            ?: curQueue.getOrNull(latestActiveIdx)
                        val fromFrame = peekCachedGlowFrame(
                            resolveGlowColors(sw.fromSong, skin),
                            glowMode,
                            glowBakeStrength
                        )
                        val toFrame = peekCachedGlowFrame(
                            resolveGlowColors(targetSong, skin),
                            glowMode,
                            glowBakeStrength
                        )
                        val fromReady = fromFrame != null && fromFrame.colors.isNotEmpty()
                        val toReady = toFrame != null && toFrame.colors.isNotEmpty()
                        when {
                            fromReady && toReady -> {
                                drawGlow(fromFrame!!, cover, glowMode, strengthArg, alpha = (1f - p) * alphaScale)
                                if (p > 0f) {
                                    drawGlow(toFrame!!, cover, glowMode, strengthArg, alpha = p * alphaScale)
                                }
                                drawGlowFade.lastDrawnFrame = if (p >= 0.99f) toFrame else fromFrame
                            }
                            fromReady -> {
                                drawGlow(fromFrame!!, cover, glowMode, strengthArg, alpha = alphaScale)
                                drawGlowFade.lastDrawnFrame = fromFrame
                            }
                            toReady -> {
                                drawGlow(toFrame!!, cover, glowMode, strengthArg, alpha = alphaScale)
                                drawGlowFade.lastDrawnFrame = toFrame
                            }
                        }
                    } else if (drawGlowFade.active &&
                        drawGlowFade.fromFrame != null &&
                        drawGlowFade.toFrame != null &&
                        !pagerState.isScrollInProgress &&
                        !userDraggedPager
                    ) {
                        val fp = drawGlowFade.progress.value.coerceIn(0f, 1f)
                        drawGlow(drawGlowFade.fromFrame!!, cover, glowMode, strengthArg, alpha = (1f - fp) * alphaScale)
                        if (fp > 0f) {
                            drawGlow(drawGlowFade.toFrame!!, cover, glowMode, strengthArg, alpha = fp * alphaScale)
                        }
                    } else {
                        // 跟手模式滑动中：按分页器的滚动位置在相邻两页之间混合。
                        //
                        // pos 是连续的「第几页」（例如 3.4）。基准页必须用 floor 取，
                        // **不能**用 offsetFraction 的符号判方向 —— 那个值在 currentPage
                        // 翻页的一刻会变号，用符号判会在中点处跳一下
                        // （3.4 和 3.6 会算成相反的混合比）。
                        val curQueue = latestQueue
                        val activeIdx = latestActiveIdx
                        val targetIdx = latestDisplayedIdx
                        val extAnchor = latestPagerLock || (
                            !pagerState.isScrollInProgress &&
                                !userDraggedPager &&
                                pendingSwipeTarget < 0 &&
                                activeIdx in curQueue.indices &&
                                pagerState.currentPage != activeIdx
                            )
                        val pos = if (extAnchor) {
                            targetIdx.coerceIn(0, (curQueue.size - 1).coerceAtLeast(0)).toFloat()
                        } else {
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        }
                        val base = floor(pos).toInt()
                        val t = (pos - base).coerceIn(0f, 1f)
                        val first = cachedGlowFrame(
                            resolveGlowColors(curQueue.getOrNull(base), skin, context),
                            glowMode,
                            glowBakeStrength
                        )
                        val second = if (t > 0f) {
                            cachedGlowFrame(
                                resolveGlowColors(curQueue.getOrNull(base + 1), skin, context),
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
                            firstReady && !secondReady -> {
                                drawGlow(first, cover, glowMode, strengthArg, alpha = alphaScale)
                                if (t <= 0.001f) drawGlowFade.lastDrawnFrame = first
                            }
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
        val currentSong = queue.getOrNull(activeQueueIndex)
        customPlayerImage?.let { bgImg ->
            Box(Modifier.matchParentSize().clipToBounds()) {
                val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && customPlayerBgBlurDp > 0) {
                    Modifier.blur(customPlayerBgBlurDp.dp, BlurredEdgeTreatment.Rectangle)
                } else Modifier
                Image(
                    bitmap = bgImg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().then(blurMod)
                )
                if (customPlayerBgDimPct > 0) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = customPlayerBgDimPct / 100f))
                    )
                }
            }
        }

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
            val topBackAction: () -> Unit = {
                when {
                    layer != PlayerLayer.COVER -> goToLayer(PlayerLayer.COVER)
                    lyricsExpanded -> lyricsExpanded = false
                    else -> closePlayer()
                }
            }
            val backIconRotation by animateFloatAsState(
                targetValue = if (layer == PlayerLayer.COVER && lyricsExpanded) 90f else 0f,
                animationSpec = tween(200),
                label = "topBackRotation"
            )
            val sheetStartNow = rememberUpdatedState(onSheetDragStart)
            val sheetDeltaNow = rememberUpdatedState(onSheetDragDelta)
            val sheetEndNow = rememberUpdatedState(onSheetDragEnd)
            val canPullDownSheet = layer == PlayerLayer.COVER && !lyricsExpanded
            val canPullNow = rememberUpdatedState(canPullDownSheet)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 26.dp)
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!canPullNow.value) return@awaitEachGesture
                            var dx = 0f
                            var dy = 0f
                            var locked = false
                            val tracker = VelocityTracker()
                            tracker.addPointerInputChange(down)
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) {
                                    if (locked) {
                                        sheetEndNow.value(tracker.calculateVelocity().y)
                                    }
                                    break
                                }
                                tracker.addPointerInputChange(c)
                                val delta = c.positionChange()
                                if (!locked) {
                                    dx += delta.x
                                    dy += delta.y
                                    if (abs(dx) > slop && abs(dx) >= abs(dy)) break
                                    if (dy > slop && abs(dy) > abs(dx)) {
                                        locked = true
                                        sheetStartNow.value()
                                        sheetDeltaNow.value(dy)
                                        c.consume()
                                    }
                                } else {
                                    sheetDeltaNow.value(delta.y)
                                    c.consume()
                                }
                            }
                        }
                    }
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
                            .clickable(onClick = topBackAction),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.ChevronDown,
                            contentDescription = if (layer == PlayerLayer.COVER && !lyricsExpanded) "收起" else "返回封面",
                            tint = iconTint,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(backIconRotation)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 右侧双联微磨砂胶囊：黑胶模式 | 更多面板（睡眠定时器 + DLNA 无线投屏）
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
                                .clickable {
                                    hapticTick(context)
                                    showTopMoreSheet = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = SonanceIcons.MoreVertical,
                                contentDescription = "更多",
                                tint = if (sleepTimerActive || dlnaActive) skin.accent else iconTint,
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
                            activeIndexProvider = { latestDisplayedIdx },
                            anchorActiveProvider = {
                                latestPagerLock || (!pagerState.isScrollInProgress && !userDraggedPager && pendingSwipeTarget < 0)
                            },
                            activeSwitchProvider = { latestActiveSwitch },
                            smoothGlowColorsProvider = { smoothGlow.currentColors() },
                            skin = skin,
                            glowMode = glowMode,
                            glowStrength = glowStrength
                        )
                    }
                }

                // 收起热区：顶栏左侧 ~55% 整片都能触发「收起播放页」，
                // 覆盖左上角箭头与中间的律动指示器；右侧三联胶囊所在的右侧区域不铺热区，保留各自点击。
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.55f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = topBackAction
                        )
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
                    // 同曲在队列出现多次时用 occurrence 编号区分 key，同时避免重排队列后页码变动重建整页
                    key = { page -> pageKeys.getOrNull(page) ?: "empty-$page" },
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
                    val displayIdx = when {
                        pagerLockActive && page == stalePagerPage -> displayedQueueIndex
                        externalDesync && page == stalePagerPage -> activeQueueIndex
                        else -> page
                    }
                    val song = queue.getOrNull(displayIdx) ?: return@VerticalPager
                    val isCurrent = displayIdx == displayedQueueIndex
                    val songMatchesState = activeSwitch == null && song.id == state.mediaId && displayIdx == activeQueueIndex
                    // 每一页都裁到自己的范围 —— **包括当前页**。
                    //
                    // 早先这里给当前页留了豁免（不裁），理由是「晕染画在封面层、
                    // 需要向上溢出才能覆盖顶栏」。但现在晕染已经搬到整页背景层了，
                    // 那个理由不再成立，豁免只剩下副作用：
                    // 滑动过程中当前页整体上移，它的**封面会溢出到分页器之外**，
                    // 也就是顶栏 / 状态栏那一条 —— 表现是屏幕上半截突然出现
                    // 不属于当前页的画面（用户反馈的「遮罩异常」）。
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                    val downloadBusy = isDownloadBusy(song)
                    val downloaded = isDownloaded(song)
                    if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
                        CoverWallPlayerPage(
                            song = song,
                            isFavorite = isSongFavorite?.invoke(song) ?: (song.id in favorites),
                            showDownloadButton = !song.isLocal && !song.isDlna && (downloadBusy || !downloaded),
                            isDownloading = downloadBusy,
                            isDownloaded = downloaded,
                            onDownload = { onDownloadSong(song) },
                            positionMs = if (songMatchesState) state.positionMs else 0L,
                            durationMs = if (songMatchesState && state.durationMs > 0) state.durationMs else song.durationMs,
                            isPlaying = isCurrent && (if (activeSwitch != null) true else state.isPlaying) && !coveredByVinyl,
                            controlsPlaying = if (isCurrent && activeSwitch != null) true else pagerPlayButtonActive(isCurrent, state),
                            coverScale = coverScaleState,
                            useRealCover = coverWallUseRealCover,
                            stableWallColor = stableWallColor,
                            activeWallColor = { animatedWallColorState.value },
                            playMode = if (isCurrent) PlayMode.of(state.shuffle, state.repeat) else PlayMode.REPEAT_ALL,
                            onToggleFavorite = { onToggleFavorite(song.id) },
                            onCyclePlayMode = onCyclePlayMode,
                            onPrev = {
                                pendingSwipeTarget = -1
                                triggerButtonSwitch(-1, onPrev)
                            },
                            onPlayPause = onPlayPause,
                            onNext = {
                                pendingSwipeTarget = -1
                                triggerButtonSwitch(1, onNext)
                            },
                            onSeek = onSeek,
                            onOpenQueue = onOpenQueue,
                            onOpenCover = onOpenCover,
                            playbackSpeed = playbackSpeed,
                            onSetSpeed = onSetSpeed,
                            abLoopStartMs = if (songMatchesState) state.abLoopStartMs else -1L,
                            abLoopEndMs = if (songMatchesState) state.abLoopEndMs else -1L,
                            onToggleAbLoop = onToggleAbLoop,
                            prefs = prefs,
                            swipePager = pagerState,
                            swipePage = page,
                            streamQuality = if (songMatchesState) streamQuality else null,
                            hiResBadgeEnabled = hiResBadgeEnabled,
                            hiResBadgeSize = hiResBadgeSize,
                            switchMotion = switchMotion,
                            activeSwitch = if (isCurrent) activeSwitch else null,
                            switchProgress = { activeSwitch?.progress?.value ?: 1f },
                            prevKickPx = { prevKick.value },
                            nextKickPx = { nextKick.value },
                            coveredByQueue = coveredByQueue,
                            coveredByVinyl = coveredByVinyl,
                            onCoverBounds = { rect ->
                                if (isCurrent &&
                                    !externalDesync &&
                                    !pagerLockActive &&
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
                    } else {
                    PlayerPage(
                        song = song,
                        isFavorite = isSongFavorite?.invoke(song) ?: (song.id in favorites),
                        showDownloadButton = !song.isLocal && !song.isDlna && (downloadBusy || !downloaded),
                        isDownloading = downloadBusy,
                        isDownloaded = downloaded,
                        onDownload = { onDownloadSong(song) },
                        // 动画进行中冻结进度条与音质徽标更新，避免中途 Binder 状态到达触发额外重组。
                        positionMs = if (songMatchesState) state.positionMs else 0L,
                        durationMs = if (songMatchesState && state.durationMs > 0) state.durationMs else song.durationMs,
                        isPlaying = isCurrent && (if (activeSwitch != null) true else state.isPlaying) && !coveredByVinyl,
                        // A preview is about to autoplay. Never render it as paused during the swipe.
                        controlsPlaying = if (isCurrent && activeSwitch != null) true else pagerPlayButtonActive(isCurrent, state),
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
                            triggerButtonSwitch(-1, onPrev)
                        },
                        onPlayPause = onPlayPause,
                        onNext = {
                            pendingSwipeTarget = -1
                            triggerButtonSwitch(1, onNext)
                        },
                        switchMotion = switchMotion,
                        activeSwitch = if (isCurrent) activeSwitch else null,
                        switchProgress = { activeSwitch?.progress?.value ?: 1f },
                        prevKickPx = { prevKick.value },
                        nextKickPx = { nextKick.value },
                        coverCornerDp = coverCornerDp,
                        onSeek = onSeek,
                        onVolume = onVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenCover = onOpenCover,
                        onOpenEqualizer = onOpenEqualizer,
                        eqEnabled = eqEnabled,
                        playbackSpeed = playbackSpeed,
                        onSetSpeed = onSetSpeed,
                        abLoopStartMs = if (songMatchesState) state.abLoopStartMs else -1L,
                        abLoopEndMs = if (songMatchesState) state.abLoopEndMs else -1L,
                        onToggleAbLoop = onToggleAbLoop,
                        prefs = prefs,
                        swipePager = pagerState,
                        swipePage = page,
                        streamQuality = if (songMatchesState) streamQuality else null,
                        // 内嵌歌词自动调色的素材：仅歌词展开或封面下方双行歌词开启时从 LRU 缓存读取，避免任何重组依赖。
                        glowImage = if (!lyricsExpanded && !coverLyricsInline) {
                            null
                        } else {
                            cachedGlowFrame(resolveGlowColors(song, skin), glowMode, glowBakeStrength).image
                        },
                        vinylMode = vinylMode,
                        hiResBadgeEnabled = hiResBadgeEnabled,
                        hiResBadgeSize = hiResBadgeSize,
                        lyricsExpanded = lyricsExpanded,
                        coverLyricsInline = coverLyricsInline,
                        onToggleLyrics = {
                            if (lyricsExpanded) {
                                lyricsExpanded = false
                            } else {
                                val nextInline = !coverLyricsInline
                                coverLyricsInline = nextInline
                                prefs.coverLyricsInline = nextInline
                            }
                        },
                        onCoverTap = { lyricsExpanded = !lyricsExpanded },
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
                                !externalDesync &&
                                !pagerLockActive &&
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

    if (showTopMoreSheet) {
        PlayerMoreActionsSheet(
            sleepTimerActive = sleepTimerActive,
            dlnaActive = dlnaActive,
            switchMotion = switchMotion,
            onSwitchMotionChange = onSwitchMotionChange,
            layoutStyle = layoutStyle,
            onLayoutStyleChange = onLayoutStyleChange,
            coverWallUseRealCover = coverWallUseRealCover,
            onCoverWallUseRealCoverChange = onCoverWallUseRealCoverChange,
            showEqualizerEntry = prefs.playerHideVolumeBar || layoutStyle == PlayerLayoutStyle.COVER_WALL,
            onOpenEqualizer = {
                showTopMoreSheet = false
                onOpenEqualizer()
            },
            onOpenSleepTimer = {
                showTopMoreSheet = false
                onOpenSleepTimer()
            },
            onOpenDlna = {
                showTopMoreSheet = false
                onOpenDlna()
            },
            onOpenGlow = {
                showTopMoreSheet = false
                showGlowSheet = true
            },
            onAddToPlaylist = onAddToPlaylist?.let { cb ->
                {
                    showTopMoreSheet = false
                    queue.getOrNull(state.queueIndex)?.let(cb)
                }
            },
            onDismiss = { showTopMoreSheet = false }
        )
    }
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
    val barWidth = when (com.sonance.player.ui.icon.IconSetState.style) {
        com.sonance.player.ui.icon.IconSetState.MINIMAL -> 1.5.dp
        com.sonance.player.ui.icon.IconSetState.SOLID -> 3.dp
        else -> 2.6.dp
    }
    // 46.8s 长周期内各柱采用互质倍频 + 轻副谐波叠加（全部为整数周期，回环处完全连续无跳变），
    // 对齐原型里 1.80s / 1.30s(反相) / 1.61s 完整往返周期的舒缓律动，兼顾自然随机感与暂停后 0fps。
    val phaseState = rememberThrottledPhase(
        active = isPlaying || level.value > 0.005f,
        periodMs = 46_800,
        reverse = false,
        stepMs = 33L
    )
    val primaryCycles = remember { floatArrayOf(26f, 36f, 29f) }
    val harmonicCycles = remember { floatArrayOf(41f, 53f, 47f) }
    val phaseOffsets = remember { floatArrayOf(0.0f, 0.5f, 0.23f) }

    Canvas(Modifier.size(width = 20.dp, height = 13.dp)) {
        val barW = barWidth.toPx()
        val gap = (size.width - barW * 3f) / 2f
        val maxH = size.height
        val minH = size.height * 0.22f
        val lvl = level.value
        val color = colorProvider(lvl)
        val basePhase = phaseState.value
        for (i in 0 until 3) {
            val p1 = 0.5f - 0.5f * cos((2.0 * PI * (basePhase * primaryCycles[i] + phaseOffsets[i]))).toFloat()
            val p2 = 0.5f - 0.5f * cos((2.0 * PI * (basePhase * harmonicCycles[i] + phaseOffsets[i] * 1.7f))).toFloat()
            val wave = (p1 * 0.82f + p2 * 0.18f).coerceIn(0.08f, 1f)
            val p = if (lvl > 0f) wave * lvl else 0f
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
private fun resolveGlowColors(
    song: Song?,
    skin: Skin,
    context: android.content.Context? = null
): List<Color> {
    if (song == null) return emptyList()
    val coverKey = OnlineCover.keyOf(song.title, song.artist)
    val tint = ArtworkLoader.tintOf(song.albumId, song.uri, coverKey)
    ArtworkLoader.artworkGoneVersion
    return when {
        tint != null -> listOf(Color(tint.left), Color(tint.center), Color(tint.right))
        else -> context?.let { LaunchCoverSnapshot.peekBands(it, song.id) }
            ?: if (ArtworkLoader.isArtworkGone(song.albumId, song.uri, coverKey)) {
                val c = skin.gradientFor(song.title + song.artist)
                listOf(c.first(), c[c.size / 2], c.last())
            } else {
                emptyList()
            }
    }
}

private fun peekCachedGlowFrame(colors: List<Color>, mode: GlowMode, strength: Float): GlowFrame? {
    if (colors.isEmpty()) return null
    if (mode == GlowMode.AROUND) return GlowFrame(colors, null)
    val key = GlowFrameKey(
        c0 = colors.getOrElse(0) { Color.Transparent }.toArgb(),
        c1 = colors.getOrElse(1) { Color.Transparent }.toArgb(),
        c2 = colors.getOrElse(2) { Color.Transparent }.toArgb(),
        count = colors.size,
        mode = mode,
        strengthBits = strength.toBits()
    )
    return glowFrameCache.get(key)
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
    val context = LocalContext.current
    val tint = song?.let { ArtworkLoader.tintOf(it.albumId, it.uri, coverKey) }
    val goneVersion = ArtworkLoader.artworkGoneVersion

    return remember(song?.id, song?.title, tint, skin.id, goneVersion) {
        when {
            song == null -> emptyList()

            tint != null -> listOf(Color(tint.left), Color(tint.center), Color(tint.right))

            // 冷启动内存色卡尚未算出时，同步回落到落盘色带快照，保证首帧就有晕染背景
            else -> LaunchCoverSnapshot.peekBands(context, song.id)
                ?: if (ArtworkLoader.isArtworkGone(song.albumId, song.uri, coverKey)) {
                    val c = skin.gradientFor(song.title + song.artist)
                    listOf(c.first(), c[c.size / 2], c.last())
                } else {
                    emptyList()
                }
        }
    }
}

/**
 * 非跟手滑动时的统一平滑晕染色卡：
 * - 色卡尚在异步取色（目标为空）时保持当前正在显示的颜色，绝不退回黑底；
 * - 目标色卡就绪后用 [Animatable] 从当前正在渲染的颜色平滑补间过去，不发生跳变；
 * - 手势滑动落定后直接对齐到已落定页的当前色，保证滑动切歌回交时零接缝。
 */
private class DrawGlowCrossfade {
    var lastDrawnFrame: GlowFrame? = null
    var fromFrame: GlowFrame? = null
    var toFrame: GlowFrame? = null
    var active: Boolean = false
    val progress = Animatable(1f)
}

private class SmoothGlowController(
    initial: List<Color>,
    fallback: Color
) {
    var fromColors by mutableStateOf(initial.ifEmpty { emptyList() })
    var toColors by mutableStateOf(initial.ifEmpty { emptyList() })
    val progress = Animatable(1f)
    var hasColors by mutableStateOf(initial.isNotEmpty())
    var fadeActive by mutableStateOf(false)
    val fallbackColor = fallback

    fun primeFromIfInactive(colors: List<Color>, fallback: Color = fallbackColor) {
        if (fadeActive && hasColors) return
        if (colors.isNotEmpty()) {
            val normalized = normalizeColors(colors, fallback)
            hasColors = true
            fromColors = normalized
            toColors = normalized
        }
        fadeActive = true
    }

    suspend fun snapTo(colors: List<Color>, fallback: Color = fallbackColor) {
        if (colors.isEmpty()) return
        val normalized = normalizeColors(colors, fallback)
        hasColors = true
        fromColors = normalized
        toColors = normalized
        progress.snapTo(1f)
    }

    fun currentColors(): List<Color> {
        if (!hasColors) return emptyList()
        val from = fromColors
        val to = toColors
        if (from.isEmpty()) return to
        if (to.isEmpty()) return from
        val p = progress.value.coerceIn(0f, 1f)
        if (p >= 0.999f) return to
        if (p <= 0.001f) return from
        return listOf(
            lerp(from[0], to[0], p),
            lerp(from[1], to[1], p),
            lerp(from[2], to[2], p)
        )
    }

    companion object {
        fun normalizeColors(colors: List<Color>, fallback: Color): List<Color> {
            val c0 = colors.getOrElse(0) { fallback }
            val c1 = colors.getOrElse(1) { c0 }
            val c2 = colors.getOrElse(2) { c1 }
            return listOf(c0, c1, c2)
        }
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
    activeIndexProvider: () -> Int,
    anchorActiveProvider: () -> Boolean,
    activeSwitchProvider: () -> ButtonSwitchState?,
    smoothGlowColorsProvider: () -> List<Color>,
    skin: Skin,
    glowMode: GlowMode,
    glowStrength: Float
) {
    PlayingStateIndicator(
        isPlaying = isPlaying,
        colorProvider = { playingFraction ->
            val sw = activeSwitchProvider()
            val coverColor = if (isSwipeGlow) {
                val q = queueProvider()
                val activeIdx = activeIndexProvider()
                if (sw != null) {
                    val p = sw.progress.value.coerceIn(0f, 1f)
                    val fromColors = resolveGlowColors(sw.fromSong, skin)
                    val toColors = resolveGlowColors(q.getOrNull(activeIdx), skin)
                    val c0 = if (fromColors.isEmpty()) skin.bg else glowTintAt(fromColors, 0.5f)
                    val c1 = if (toColors.isEmpty()) c0 else glowTintAt(toColors, 0.5f)
                    lerp(c0, c1, p)
                } else {
                    val extAnchor = anchorActiveProvider() &&
                        activeIdx in q.indices &&
                        pagerState.currentPage != activeIdx
                    val pos = if (extAnchor) {
                        activeIdx.toFloat()
                    } else {
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    }
                    val base = floor(pos).toInt()
                    val t = (pos - base).coerceIn(0f, 1f)
                    val first = resolveGlowColors(q.getOrNull(base), skin)
                    val second = if (t > 0f) resolveGlowColors(q.getOrNull(base + 1), skin) else emptyList()
                    val c0 = if (first.isEmpty()) skin.bg else glowTintAt(first, 0.5f)
                    val c1 = if (second.isEmpty()) c0 else glowTintAt(second, 0.5f)
                    lerp(c0, c1, t)
                }
            } else {
                smoothGlowColorsProvider().let { if (it.isEmpty()) skin.bg else glowTintAt(it, 0.5f) }
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
 * 封面的手势：**单击在封面位置看歌词、长按换封面、双击开晕染**。
 *
 * 单击现在是「把封面换成整块歌词」的入口（胶囊「词」只负责封面下方的两行歌词）。
 * 换封面仍保留在长按，避免误触。
 *
 * 这里不用 `detectTapGestures`：它的双击窗口只有 300ms，而且早先实测
 * `pointerInput` 那条路上 up 事件会被判成"已消费"（见 git 历史里的注释）。
 * 用 `combinedClickable` 最稳：长按/双击/单击都是系统标准判定。
 */
/**
 * 封面手势：
 * - 单击立刻响应（不绑双击回调，避免 Compose 等待 300ms 双击超时导致切歌词慢半拍）；
 * - 长按打开换封面面板。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.onCoverGesture(
    onLongPress: () -> Unit,
    onTap: () -> Unit = {}
): Modifier = this.combinedClickable(
    onClick = onTap,
    onDoubleClick = null,
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
    // 自己拿 withFrameNanos 累加角度并写进 State 在 graphicsLayer 绘制期读取：
    // 避免 120Hz 触发 CoverArt 重组、暂停时立即进入 0fps、退后台自动停帧，旋转节流到 ~60fps。
    val angleState = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isPlaying, lifecycleOwner) {
        if (!isPlaying) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withFrameNanos { it }
            var accNs = 0L
            while (true) {
                val now = withFrameNanos { it }
                val dtNs = (now - last).coerceIn(0L, 100_000_000L)
                last = now
                accNs += dtNs
                if (accNs >= 16_000_000L) {
                    angleState.floatValue =
                        (angleState.floatValue + accNs / 1_000_000_000f * VINYL_DEG_PER_SEC) % 360f
                    accNs = 0L
                }
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
                .graphicsLayer {
                    rotationZ = angleState.floatValue
                    compositingStrategy = CompositingStrategy.Offscreen
                },
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

/**
 * 右上角「更多功能」半屏面板（睡眠定时 + DLNA 无线投屏）。
 * 遵循系统内一致的 ModalBottomSheet 底板规范。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMoreActionsSheet(
    sleepTimerActive: Boolean,
    dlnaActive: Boolean,
    switchMotion: PlayerSwitchMotion,
    onSwitchMotionChange: (PlayerSwitchMotion) -> Unit,
    layoutStyle: PlayerLayoutStyle,
    onLayoutStyleChange: (PlayerLayoutStyle) -> Unit,
    coverWallUseRealCover: Boolean,
    onCoverWallUseRealCoverChange: (Boolean) -> Unit,
    showEqualizerEntry: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    onOpenSleepTimer: () -> Unit,
    onOpenDlna: () -> Unit,
    onOpenGlow: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(skin.textMuted.copy(alpha = 0.45f))
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "更多功能",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Spacer(Modifier.height(14.dp))

            // 睡眠定时卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sleepTimerActive) skin.accentGlow else skin.surface2)
                    .border(
                        1.dp,
                        if (sleepTimerActive) skin.accent else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        hapticTick(context)
                        onOpenSleepTimer()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (sleepTimerActive) skin.accentDim else skin.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Timer,
                        contentDescription = null,
                        tint = if (sleepTimerActive) skin.accent else skin.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "睡眠定时",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = skin.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (sleepTimerActive) "计时中 · 到点暂停并关闭应用" else "未开启 · 到点暂停并关闭应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sleepTimerActive) skin.accent else skin.textMuted
                    )
                }
                if (sleepTimerActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(skin.accentDim)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 音效工作室卡片：底部音量条被设置隐藏时，音效入口收进这里
            if (showEqualizerEntry) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.surface2)
                        .clickable {
                            hapticTick(context)
                            onOpenEqualizer()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(skin.surface3),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.Equalizer,
                            contentDescription = null,
                            tint = skin.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "音效工作室",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "均衡器 · 预设音色 · DSP 音效",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 加入歌单卡片（调用方没提供回调时不显示）
            if (onAddToPlaylist != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.surface2)
                        .clickable {
                            hapticTick(context)
                            onAddToPlaylist()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(skin.surface3),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.Queue,
                            contentDescription = null,
                            tint = skin.text,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "添加到歌单",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "把正在播放的歌收进自建歌单",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 无线投屏卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (dlnaActive) skin.accentGlow else skin.surface2)
                    .border(
                        1.dp,
                        if (dlnaActive) skin.accent else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        hapticTick(context)
                        onOpenDlna()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (dlnaActive) skin.accentDim else skin.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Cast,
                        contentDescription = null,
                        tint = if (dlnaActive) skin.accent else skin.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "无线投屏",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = skin.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (dlnaActive) "已连接 · DLNA 投屏中" else "未连接 · 投屏到音箱或电视",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dlnaActive) skin.accent else skin.textMuted
                    )
                }
                if (dlnaActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(skin.accentDim)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 桌面歌词卡片
            val prefs = remember { com.sonance.player.data.Prefs(context) }
            var desktopLyricsOn by remember { mutableStateOf(prefs.desktopLyricsEnabled) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (desktopLyricsOn) skin.accentGlow else skin.surface2)
                    .border(
                        1.dp,
                        if (desktopLyricsOn) skin.accent else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        hapticTick(context)
                        if (!DesktopLyricsManager.hasOverlayPermission(context)) {
                            Toast.makeText(context, "请授予山水有清音「悬浮窗」权限以开启桌面歌词", Toast.LENGTH_LONG).show()
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        } else {
                            val next = !desktopLyricsOn
                            desktopLyricsOn = next
                            DesktopLyricsManager.setEnabled(context, next)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (desktopLyricsOn) skin.accentDim else skin.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Lyrics,
                        contentDescription = null,
                        tint = if (desktopLyricsOn) skin.accent else skin.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "桌面歌词",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = skin.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (desktopLyricsOn) "已开启 · 悬浮于多应用之上" else "未开启 · 悬浮逐行显示歌词",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (desktopLyricsOn) skin.accent else skin.textMuted
                    )
                }
                if (desktopLyricsOn) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(skin.accentDim)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "已开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 封面晕染与黑胶效果入口（封面单击已改为零延迟切歌词）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(skin.surface2)
                    .clickable {
                        hapticTick(context)
                        onOpenGlow()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(skin.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Layers,
                        contentDescription = null,
                        tint = skin.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "封面晕染与黑胶",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = skin.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "调节背景晕染浓度、过渡方式与黑胶模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                }
            }

            // 切歌动效切换卡片：仅经典样式使用（封面墙样式自带实体黑胶封套切歌动效）
            if (layoutStyle == PlayerLayoutStyle.CLASSIC) {
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.surface2)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(skin.surface3),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.Waveform,
                            contentDescription = null,
                            tint = skin.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "切歌动效",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = switchMotion.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlayerSwitchMotion.entries.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { option ->
                                val selected = option == switchMotion
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) skin.accentGlow else skin.surface3)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) skin.accent else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            hapticTick(context)
                                            onSwitchMotionChange(option)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) skin.accent else skin.textDim,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            Spacer(Modifier.height(10.dp))

            // 播放主界面样式切换卡片：经典样式 vs 封面墙样式（含真实封面切换）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(skin.surface2)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(skin.surface3),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = SonanceIcons.Album,
                            contentDescription = null,
                            tint = skin.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "播放页样式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = layoutStyle.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlayerLayoutStyle.entries.forEach { option ->
                        val selected = option == layoutStyle
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) skin.accentGlow else skin.surface3)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) skin.accent else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    hapticTick(context)
                                    onLayoutStyleChange(option)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) skin.accent else skin.textDim,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (layoutStyle == PlayerLayoutStyle.COVER_WALL) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coverOptions = listOf(
                            false to "替代艺术封面",
                            true to "歌曲真实封面"
                        )
                        coverOptions.forEach { (useReal, label) ->
                            val selected = coverWallUseRealCover == useReal
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) skin.accentGlow else skin.surface3)
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) skin.accent else Color.Transparent,
                                        shape = RoundedCornerShape(9.dp)
                                    )
                                    .clickable {
                                        hapticTick(context)
                                        onCoverWallUseRealCoverChange(useReal)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) skin.accent else skin.textDim,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
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
 * 封面下方两行歌词悬浮层：独立提取成无 RowScope/ColumnScope 的 Composable，
 * 避免外层弹性列作用域抢占 AnimatedVisibility 隐式接收者，并统一横竖屏进出场阻尼动效。
 */
@Composable
private fun CoverInlineLyricsOverlay(
    visible: Boolean,
    song: Song,
    positionMs: Long,
    prefs: Prefs,
    onClick: () -> Unit,
    backdropColor: Color? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260, easing = LinearOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = tween(280, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
            ),
        exit = fadeOut(tween(180)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(180)),
        modifier = modifier
    ) {
        CoverInlineLyrics(
            song = song,
            positionMs = positionMs,
            prefs = prefs,
            onClick = onClick,
            backdropColor = backdropColor
        )
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
internal fun Modifier.swipeExitFade(pager: PagerState, page: Int): Modifier =
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

internal enum class SwitchTrigger {
    MANUAL_STEP,
    AUTO_ADVANCE,
    QUEUE_PICK
}

internal data class CoverLayerTransform(
    val alpha: Float,
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    val rotationY: Float = 0f
)

internal fun classifyExternalSwitchTrigger(
    coveredByQueue: Boolean,
    hasUnconsumedQueuePick: Boolean,
    recentQueuePick: Boolean
): SwitchTrigger = if (coveredByQueue || (hasUnconsumedQueuePick && recentQueuePick)) {
    SwitchTrigger.QUEUE_PICK
} else {
    SwitchTrigger.AUTO_ADVANCE
}

internal fun resolveSwitchDurationMs(
    trigger: SwitchTrigger,
    switchMotion: PlayerSwitchMotion,
    motionFeel: MotionFeel,
    isRapid: Boolean
): Int {
    val baseMs = when (trigger) {
        SwitchTrigger.MANUAL_STEP -> when (switchMotion) {
            PlayerSwitchMotion.POWERAMP_SLIDE -> 400
            PlayerSwitchMotion.DEPTH_SLIDE -> 420
            PlayerSwitchMotion.TILT_3D -> 440
            PlayerSwitchMotion.OPTICAL_DISSOLVE -> 450
        }
        SwitchTrigger.AUTO_ADVANCE -> 540
        SwitchTrigger.QUEUE_PICK -> 310
    }
    val scaledBase = scaleMotionDuration(baseMs, motionFeel)
    if (!isRapid) return scaledBase
    return when (trigger) {
        SwitchTrigger.MANUAL_STEP -> (scaledBase * 0.72f).toInt().coerceAtLeast(80)
        SwitchTrigger.AUTO_ADVANCE -> (scaledBase * 0.75f).toInt().coerceAtLeast(180)
        SwitchTrigger.QUEUE_PICK -> (scaledBase * 0.68f).toInt().coerceAtLeast(140)
    }
}

internal fun evalAutoAdvanceOut(
    progress: Float,
    baseScale: Float,
    baseTranslationY: Float
): CoverLayerTransform {
    val p = progress.coerceIn(0f, 1f)
    val t = (p / 0.80f).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return CoverLayerTransform(
        alpha = 1f - smooth,
        scale = baseScale * (1f - 0.045f * p),
        translationX = 0f,
        translationY = baseTranslationY
    )
}

internal fun evalAutoAdvanceIn(
    progress: Float,
    baseScale: Float,
    baseTranslationY: Float
): CoverLayerTransform {
    val p = progress.coerceIn(0f, 1f)
    val t = ((p - 0.06f) / 0.82f).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return CoverLayerTransform(
        alpha = smooth,
        scale = baseScale * (0.968f + 0.032f * p),
        translationX = 0f,
        translationY = baseTranslationY
    )
}

internal fun evalQueuePickOut(
    progress: Float,
    baseScale: Float,
    baseTranslationY: Float,
    density: Float
): CoverLayerTransform {
    val p = progress.coerceIn(0f, 1f)
    return CoverLayerTransform(
        alpha = 1f - (p / 0.64f).coerceIn(0f, 1f),
        scale = baseScale * (1f - 0.055f * p),
        translationX = 0f,
        translationY = baseTranslationY - 6f * density * p
    )
}

internal fun evalQueuePickIn(
    progress: Float,
    baseScale: Float,
    baseTranslationY: Float,
    density: Float
): CoverLayerTransform {
    val p = progress.coerceIn(0f, 1f)
    return CoverLayerTransform(
        alpha = (p / 0.58f).coerceIn(0f, 1f),
        scale = baseScale * (0.955f + 0.045f * p),
        translationX = 0f,
        translationY = baseTranslationY + 14f * density * (1f - p)
    )
}

internal data class ButtonSwitchState(
    val seq: Long,
    val dir: Int,
    val fromSong: Song,
    val fromIndex: Int,
    val fromBitmap: ImageBitmap? = null,
    val toBitmap: ImageBitmap? = null,
    val carryFrac: Float = 0f,
    val trigger: SwitchTrigger = SwitchTrigger.MANUAL_STEP,
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)
)

@Composable
private fun SwitchCoverSlot(
    song: Song,
    side: Dp,
    vinylMode: Boolean,
    isPlaying: Boolean,
    coverScale: State<Float>,
    switchMotion: PlayerSwitchMotion,
    activeSwitch: ButtonSwitchState?,
    switchProgress: () -> Float,
    baseTranslationY: Float = 0f,
    coverCornerDp: Dp = CoverCornerRadius,
    onOpenCover: () -> Unit,
    onCoverTap: () -> Unit
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current.density
    val hostView = LocalView.current
    val triggerNow = activeSwitch?.trigger ?: SwitchTrigger.MANUAL_STEP
    val showHalo = activeSwitch != null && (
        triggerNow == SwitchTrigger.AUTO_ADVANCE ||
            (triggerNow == SwitchTrigger.MANUAL_STEP && switchMotion == PlayerSwitchMotion.OPTICAL_DISSOLVE)
        )
    if (Build.VERSION.SDK_INT >= 35 && activeSwitch != null) {
        SideEffect {
            hostView.frameContentVelocity = when (activeSwitch.trigger) {
                SwitchTrigger.AUTO_ADVANCE -> 1200f
                SwitchTrigger.QUEUE_PICK -> 1800f
                SwitchTrigger.MANUAL_STEP -> 4000f
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = if (showHalo) {
            Modifier.drawBehind {
                val p = switchProgress().coerceIn(0f, 1f)
                if (p < 1f) {
                    val peak = if (triggerNow == SwitchTrigger.AUTO_ADVANCE) 0.26f else 0.42f
                    val pulse = sin(p * PI).toFloat() * peak
                    val radius = size.maxDimension * (0.62f + 0.08f * pulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(skin.accent.copy(alpha = pulse), Color.Transparent),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            }
        } else Modifier
    ) {
        if (activeSwitch != null) {
            val outMod = Modifier
                .size(side)
                .graphicsLayer {
                    compositingStrategy = if (vinylMode) CompositingStrategy.Offscreen else CompositingStrategy.ModulateAlpha
                    val rawP = switchProgress()
                    val p = rawP.coerceIn(0f, 1f)
                    val d = activeSwitch.dir.toFloat()
                    val carry = activeSwitch.carryFrac
                    val s = coverScale.value
                    transformOrigin = TransformOrigin.Center
                    when (activeSwitch.trigger) {
                        SwitchTrigger.AUTO_ADVANCE -> {
                            val tf = evalAutoAdvanceOut(p, s, baseTranslationY)
                            alpha = tf.alpha
                            scaleX = tf.scale
                            scaleY = tf.scale
                            translationX = tf.translationX
                            translationY = tf.translationY
                        }
                        SwitchTrigger.QUEUE_PICK -> {
                            val tf = evalQueuePickOut(p, s, baseTranslationY, density)
                            alpha = tf.alpha
                            scaleX = tf.scale
                            scaleY = tf.scale
                            translationX = tf.translationX
                            translationY = tf.translationY
                        }
                        SwitchTrigger.MANUAL_STEP -> when (switchMotion) {
                            PlayerSwitchMotion.POWERAMP_SLIDE -> {
                                val travel = size.width + 28.dp.toPx()
                                alpha = 1f - 0.65f * p
                                val sc = s * (1f - 0.08f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * travel * (carry - (1f + carry) * p)
                                translationY = baseTranslationY
                            }
                            PlayerSwitchMotion.DEPTH_SLIDE -> {
                                val dist = 150.dp.toPx()
                                alpha = (1f - (p / 0.68f).coerceIn(0f, 1f))
                                val sc = s * (1f - 0.16f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * dist * (carry - (1f + carry) * p)
                                translationY = baseTranslationY
                            }
                            PlayerSwitchMotion.TILT_3D -> {
                                val dist = 155.dp.toPx()
                                alpha = (1f - (p / 0.70f).coerceIn(0f, 1f))
                                val sc = s * (1f - 0.18f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * dist * (carry - (1f + carry) * p)
                                translationY = baseTranslationY
                                rotationY = -d * 22f * p
                                cameraDistance = 12f * density
                            }
                            PlayerSwitchMotion.OPTICAL_DISSOLVE -> {
                                alpha = (1f - (p / 0.76f).coerceIn(0f, 1f))
                                val sc = s * (1f - 0.12f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = -d * 44.dp.toPx() * p
                                translationY = baseTranslationY
                            }
                        }
                    }
                }
            if (vinylMode) {
                VinylCover(
                    song = activeSwitch.fromSong,
                    isPlaying = false,
                    modifier = outMod
                )
            } else {
                // 退场旧封面直接画内存快照位图/静态占位，避免 Frame 0 触发 CoverArt 状态与异步解码
                val ghostBmp = activeSwitch.fromBitmap
                Box(outMod.clip(RoundedCornerShape(coverCornerDp))) {
                    if (ghostBmp != null) {
                        Image(
                            bitmap = ghostBmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    } else {
                        PlaceholderCover(
                            seed = activeSwitch.fromSong.title + activeSwitch.fromSong.artist,
                            showMark = true,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
        }

        val sheenMod = if (activeSwitch != null &&
            activeSwitch.trigger == SwitchTrigger.MANUAL_STEP &&
            switchMotion == PlayerSwitchMotion.TILT_3D
        ) {
            Modifier
                .clip(if (vinylMode) CircleShape else RoundedCornerShape(coverCornerDp))
                .drawWithContent {
                    drawContent()
                    val p = switchProgress().coerceIn(0f, 1f)
                    if (p in 0.08f..0.95f) {
                        val d = activeSwitch.dir.toFloat()
                        val t = ((p - 0.08f) / 0.87f).coerceIn(0f, 1f)
                        val sweepAlpha = sin(t * PI).toFloat() * 0.24f
                        val centerX = size.width * (0.5f + d * (t - 0.5f) * 1.8f)
                        val band = size.width * 0.42f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = sweepAlpha),
                                    Color.Transparent
                                ),
                                start = Offset(centerX - band, 0f),
                                end = Offset(centerX + band, size.height)
                            )
                        )
                    }
                }
        } else Modifier

        val inMod = Modifier
            .size(side)
            .graphicsLayer {
                val s = coverScale.value
                transformOrigin = TransformOrigin.Center
                if (activeSwitch == null) {
                    scaleX = s
                    scaleY = s
                    translationY = baseTranslationY
                } else {
                    if (vinylMode) compositingStrategy = CompositingStrategy.Offscreen
                    val rawP = switchProgress()
                    val p = rawP.coerceIn(0f, 1f)
                    val d = activeSwitch.dir.toFloat()
                    val carry = activeSwitch.carryFrac
                    when (activeSwitch.trigger) {
                        SwitchTrigger.AUTO_ADVANCE -> {
                            val tf = evalAutoAdvanceIn(p, s, baseTranslationY)
                            alpha = tf.alpha
                            scaleX = tf.scale
                            scaleY = tf.scale
                            translationX = tf.translationX
                            translationY = tf.translationY
                        }
                        SwitchTrigger.QUEUE_PICK -> {
                            val tf = evalQueuePickIn(p, s, baseTranslationY, density)
                            alpha = tf.alpha
                            scaleX = tf.scale
                            scaleY = tf.scale
                            translationX = tf.translationX
                            translationY = tf.translationY
                        }
                        SwitchTrigger.MANUAL_STEP -> when (switchMotion) {
                            PlayerSwitchMotion.POWERAMP_SLIDE -> {
                                val travel = size.width + 28.dp.toPx()
                                alpha = 0.35f + 0.65f * p
                                val sc = s * (0.92f + 0.08f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * travel * (1f + carry) * (1f - p)
                                translationY = baseTranslationY
                            }
                            PlayerSwitchMotion.DEPTH_SLIDE -> {
                                val dist = 150.dp.toPx()
                                alpha = (p / 0.52f).coerceIn(0f, 1f)
                                val sc = s * (0.88f + 0.12f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * dist * (1f + carry) * (1f - p)
                                translationY = baseTranslationY
                            }
                            PlayerSwitchMotion.TILT_3D -> {
                                val dist = 155.dp.toPx()
                                alpha = (p / 0.54f).coerceIn(0f, 1f)
                                val sc = s * (0.86f + 0.14f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * dist * (1f + carry) * (1f - p)
                                translationY = baseTranslationY
                                rotationY = d * 22f * (1f - p)
                                cameraDistance = 12f * density
                            }
                            PlayerSwitchMotion.OPTICAL_DISSOLVE -> {
                                alpha = (p / 0.64f).coerceIn(0f, 1f)
                                val sc = s * (1.10f - 0.10f * p)
                                scaleX = sc
                                scaleY = sc
                                translationX = d * 48.dp.toPx() * (1f - p)
                                translationY = baseTranslationY
                            }
                        }
                    }
                }
            }
            .then(sheenMod)
            .onCoverGesture(onLongPress = onOpenCover, onTap = onCoverTap)

        if (vinylMode) {
            VinylCover(
                song = song,
                isPlaying = isPlaying,
                modifier = inMod
            )
        } else {
            // 始终保持 CoverArt 常驻，避免切歌动效结束切换不同节点造成单帧错位/闪烁
            CoverArt(
                albumId = song.albumId,
                seed = song.title + song.artist,
                modifier = inMod.clip(RoundedCornerShape(coverCornerDp)),
                cornerRadius = coverCornerDp,
                sizePx = 900,
                sourceUri = song.uri,
                songId = song.id,
                coverKey = OnlineCover.keyOf(song.title, song.artist)
            )
        }
    }
}

@Composable
private fun PlayerPage(
    song: Song,
    isFavorite: Boolean,
    showDownloadButton: Boolean = false,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onDownload: () -> Unit = {},
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
    /** A-B 循环起点/终点（< 0 表示未设置） */
    abLoopStartMs: Long = -1L,
    abLoopEndMs: Long = -1L,
    onToggleAbLoop: () -> Unit = {},
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
    /** 「封面换成歌词」是否已展开（单击封面切换） */
    lyricsExpanded: Boolean,
    /** 封面下方两行歌词（点胶囊「词」切换） */
    coverLyricsInline: Boolean,
    /** 展开 / 收起封面下方两行歌词 */
    onToggleLyrics: () -> Unit,
    /** 单击封面：把封面位置换成整块歌词（原逻辑） */
    onCoverTap: () -> Unit,
    /** 把封面的位置量出来上报 —— 晕染画在整页背景层，需要知道封面画在哪 */
    onCoverBounds: (Rect) -> Unit,
    switchMotion: PlayerSwitchMotion = PlayerSwitchMotion.DEPTH_SLIDE,
    activeSwitch: ButtonSwitchState? = null,
    switchProgress: () -> Float = { 1f },
    prevKickPx: () -> Float = { 0f },
    nextKickPx: () -> Float = { 0f },
    coverCornerDp: Dp = CoverCornerRadius
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
    val hiResBadgeAlpha by animateFloatAsState(
        targetValue = if (!lyricsExpanded && showHiResBadge) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "hiResBadgeAlpha"
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val boxH = maxHeight
        val isWide = boxW >= 620.dp && boxW > boxH
        val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        val isLandscapeWindow = boxW > boxH
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
                    if (compactPhone) 56.dp else 48.dp
                )
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
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
                            (fadeIn(tween(140)) togetherWith fadeOut(tween(90)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            // 方案 B：封面内歌词只作沉浸预览，单击任意位置立刻退回封面；
                            // 内部竖向滚动消耗尽后拦截余量，避免滑歌词时误触发上下滑切歌。
                            val lyricsScrollGuard = remember {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(
                                        consumed: Offset,
                                        available: Offset,
                                        source: NestedScrollSource
                                    ): Offset = Offset(0f, available.y)

                                    override suspend fun onPostFling(
                                        consumed: Velocity,
                                        available: Velocity
                                    ): Velocity = Velocity(0f, available.y)
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(lyricsScrollGuard)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCoverTap
                                    )
                            ) {
                                InlineLyrics(
                                    song = song,
                                    positionMs = positionMs,
                                    prefs = prefs,
                                    onSeek = onSeek,
                                    isPlaying = isPlaying,
                                    glowImage = glowImage,
                                    seekEnabled = false,
                                    binaryContrast = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            SwitchCoverSlot(
                                song = song,
                                side = landscapeSide,
                                vinylMode = vinylMode,
                                isPlaying = isPlaying,
                                coverScale = coverScale,
                                switchMotion = switchMotion,
                                activeSwitch = activeSwitch,
                                switchProgress = switchProgress,
                                baseTranslationY = -COVER_LIFT_PX,
                                coverCornerDp = coverCornerDp,
                                onOpenCover = onOpenCover,
                                onCoverTap = onCoverTap
                            )
                        }
                    }
                    // 横屏 / 宽屏模式：静止放在封面外右边靠底部，脱离 coverScale 缩放层以消除呼吸抖动
                    if (hiResBadgeAlpha > 0.001f) {
                        val density = LocalDensity.current
                        val badgeGapPx = with(density) { 8.dp.toPx() }
                        val badgeTotalPx = with(density) { hiResBadgeTotalDp(hiResBadgeSize).toPx() }
                        val badgeOffsetX = badgeTotalPx + badgeGapPx
                        val badgeOffsetY = -COVER_LIFT_PX
                        Box(Modifier.size(landscapeSide)) {
                            HiResGoldBadge(
                                badgeSize = hiResBadgeSize,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .graphicsLayer {
                                        alpha = hiResBadgeAlpha
                                        translationX = badgeOffsetX
                                        translationY = badgeOffsetY
                                    }
                            )
                        }
                    }
                }
                var wideRightTopInRootPx by remember { mutableFloatStateOf(0f) }
                var wideMetaTopInRootPx by remember { mutableFloatStateOf(0f) }
                var wideLyricsHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { wideRightTopInRootPx = it.boundsInRoot().top }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 信息行：收藏 + 歌名/歌手·专辑 + 下载|词胶囊
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { wideMetaTopInRootPx = it.boundsInRoot().top }
                        ) {
                            TrackMetaBar(
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                quality = streamQuality,
                                isFavorite = isFavorite,
                                lyricsExpanded = lyricsExpanded || coverLyricsInline,
                                showDownloadButton = showDownloadButton,
                                isDownloading = isDownloading,
                                isDownloaded = isDownloaded,
                                onDownload = onDownload,
                                onToggleFavorite = onToggleFavorite,
                                onToggleLyrics = onToggleLyrics,
                                switchMotion = switchMotion,
                                activeSwitch = activeSwitch,
                                switchProgress = switchProgress
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        // 上下切歌时这一组「进度 / 控制 / 音量」随页面离开而退场，
                        // 避免旧页的音量条一路升到屏幕顶端再被硬裁掉（见 [swipeExitFade]）。
                        Column(Modifier.fillMaxWidth().swipeExitFade(swipePager, swipePage)) {
                        ProgressBlock(
                            frac = frac,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onSeek = onSeek,
                            playbackSpeed = playbackSpeed,
                            onSetSpeed = onSetSpeed,
                            abLoopStartMs = abLoopStartMs,
                            abLoopEndMs = abLoopEndMs,
                            onToggleAbLoop = onToggleAbLoop
                        )
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
                            prevTranslationX = prevKickPx,
                            nextTranslationX = nextKickPx,
                            gap = 12.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        if (!prefs.playerHideVolumeBar) {
                            BottomRow(
                                volume = volume,
                                onVolume = onVolume,
                                onOpenEqualizer = onOpenEqualizer
                            )
                        }
                        }
                    }
                    // 胶囊「词」：脱离右栏文档流，在歌名上方留白足够时垂直居中悬浮显示，不挤压任何控件
                    val wideGapAboveMetaPx = (wideMetaTopInRootPx - wideRightTopInRootPx).coerceAtLeast(0f)
                    CoverInlineLyricsOverlay(
                        visible = coverLyricsInline && !lyricsExpanded,
                        song = song,
                        positionMs = positionMs,
                        prefs = prefs,
                        onClick = onCoverTap,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onSizeChanged { wideLyricsHeightPx = it.height }
                            .graphicsLayer {
                                val lh = wideLyricsHeightPx.toFloat()
                                val margin = 6.dp.toPx()
                                if (wideLyricsHeightPx <= 0 || wideGapAboveMetaPx < lh + margin) {
                                    alpha = 0f
                                } else {
                                    translationY = ((wideGapAboveMetaPx - lh) * 0.5f)
                                        .coerceIn(0f, wideGapAboveMetaPx - lh - margin)
                                }
                            }
                    )
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
                var portraitAreaWidthPx by remember { mutableIntStateOf(0) }
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
                            portraitAreaWidthPx = it.size.width
                            portraitAreaHeightPx = it.size.height
                            onCoverBounds(it.boundsInRoot().translate(Offset(0f, -COVER_LIFT_PX)))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 同上：歌词就在封面这一块里换出来
                    AnimatedContent(
                        targetState = lyricsExpanded,
                        transitionSpec = {
                            (fadeIn(tween(140)) togetherWith fadeOut(tween(90)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            // 方案 B：封面内歌词只作沉浸预览，单击任意位置立刻退回封面；
                            // 内部竖向滚动消耗尽后拦截余量，避免滑歌词时误触发上下滑切歌。
                            val lyricsScrollGuard = remember {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(
                                        consumed: Offset,
                                        available: Offset,
                                        source: NestedScrollSource
                                    ): Offset = Offset(0f, available.y)

                                    override suspend fun onPostFling(
                                        consumed: Velocity,
                                        available: Velocity
                                    ): Velocity = Velocity(0f, available.y)
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(lyricsScrollGuard)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCoverTap
                                    )
                            ) {
                                InlineLyrics(
                                    song = song,
                                    positionMs = positionMs,
                                    prefs = prefs,
                                    onSeek = onSeek,
                                    isPlaying = isPlaying,
                                    glowImage = glowImage,
                                    seekEnabled = false,
                                    binaryContrast = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            CoverLift {
                                SwitchCoverSlot(
                                    song = song,
                                    side = portraitSide,
                                    vinylMode = vinylMode,
                                    isPlaying = isPlaying,
                                    coverScale = coverScale,
                                    switchMotion = switchMotion,
                                    activeSwitch = activeSwitch,
                                    switchProgress = switchProgress,
                                    coverCornerDp = coverCornerDp,
                                    onOpenCover = onOpenCover,
                                    onCoverTap = onCoverTap
                                )
                            }
                        }
                    }
                    val portraitDensity = LocalDensity.current
                    val coverSidePx = with(portraitDensity) { portraitSide.toPx() }
                    var inlineLyricsHeightPx by remember { mutableIntStateOf(0) }
                    // 手机竖屏：放在封面外右下角正下方；平板 / 横向容器且右侧余量充足时：移动到封面右边靠底部
                    if (hiResBadgeAlpha > 0.001f) {
                        val gapPx = with(portraitDensity) { 10.dp.toPx() }
                        val badgeTotalPx = with(portraitDensity) { hiResBadgeTotalDp(hiResBadgeSize).toPx() }
                        val bottomMarginPx = with(portraitDensity) { 2.dp.toPx() }
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
                                            alpha = hiResBadgeAlpha
                                            val h = portraitAreaHeightPx.toFloat()
                                            val w = portraitAreaWidthPx.toFloat()
                                            val side = minOf(coverSidePx, h)
                                            val coverBottomY = (h - side) * 0.41f - COVER_LIFT_PX + side
                                            val maxY = (h - badgeTotalPx - bottomMarginPx).coerceAtLeast(0f)
                                            val sideMarginPx = ((w - coverSidePx) / 2f).coerceAtLeast(0f)
                                            val placeAtRightBottom = (isTablet || isLandscapeWindow) &&
                                                sideMarginPx >= badgeTotalPx + gapPx
                                            if (placeAtRightBottom) {
                                                translationX = badgeTotalPx + gapPx
                                                translationY = (coverBottomY - badgeTotalPx).coerceIn(0f, maxY)
                                            } else {
                                                translationX = 0f
                                                translationY = (coverBottomY + gapPx).coerceIn(0f, maxY)
                                            }
                                        }
                                    }
                            )
                        }
                    }
                    val pageSkin = LocalSkin.current
                    val coverLyricsBackdrop = remember(glowImage, pageSkin) {
                        sampleInlineBackdropColor(glowImage, pageSkin, yFraction = 0.65f)
                    }
                    // 胶囊「词」：脱离主文档流，垂直居中悬浮于「封面底边」与「下方歌名顶边」之间，开关时不顶动封面与控制区
                    CoverInlineLyricsOverlay(
                        visible = coverLyricsInline && !lyricsExpanded,
                        song = song,
                        positionMs = positionMs,
                        prefs = prefs,
                        onClick = onCoverTap,
                        backdropColor = coverLyricsBackdrop,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onSizeChanged { inlineLyricsHeightPx = it.height }
                            .graphicsLayer {
                                if (portraitAreaHeightPx <= 0 || inlineLyricsHeightPx <= 0) {
                                    alpha = 0f
                                } else {
                                    val h = portraitAreaHeightPx.toFloat()
                                    val side = minOf(coverSidePx, h)
                                    val coverBottomY = (h - side) * 0.41f - COVER_LIFT_PX + side
                                    val lyricsH = inlineLyricsHeightPx.toFloat()
                                    val gapCenterY = (coverBottomY + h) * 0.5f
                                    val minBottomMarginPx = 2.dp.toPx()
                                    val maxTopY = (h - lyricsH - minBottomMarginPx).coerceAtLeast(coverBottomY)
                                    val minTopY = (coverBottomY + 4.dp.toPx()).coerceAtMost(maxTopY)
                                    translationY = (gapCenterY - lyricsH * 0.5f).coerceIn(minTopY, maxTopY)
                                }
                            }
                    )
                }

                Spacer(Modifier.height(3.dp))
                // 信息行：收藏 + 标题/歌手/专辑（三行）+ 下载|词胶囊
                TrackMetaBar(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    quality = streamQuality,
                    isFavorite = isFavorite,
                    lyricsExpanded = lyricsExpanded || coverLyricsInline,
                    showDownloadButton = showDownloadButton,
                    isDownloading = isDownloading,
                    isDownloaded = isDownloaded,
                    onDownload = onDownload,
                    onToggleFavorite = onToggleFavorite,
                    onToggleLyrics = onToggleLyrics,
                    switchMotion = switchMotion,
                    activeSwitch = activeSwitch,
                    switchProgress = switchProgress
                )
                Spacer(Modifier.height(12.dp))
                // 同横屏：这一组随页面离开而退场（见 [swipeExitFade]）
                Column(Modifier.fillMaxWidth().swipeExitFade(swipePager, swipePage)) {
                ProgressBlock(
                    frac = frac,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    playbackSpeed = playbackSpeed,
                    onSetSpeed = onSetSpeed,
                    abLoopStartMs = abLoopStartMs,
                    abLoopEndMs = abLoopEndMs,
                    onToggleAbLoop = onToggleAbLoop
                )
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
                    prevTranslationX = prevKickPx,
                    nextTranslationX = nextKickPx,
                    gap = 14.dp,
                    // 内层 Column 默认左对齐：不撑满宽度的话整行控制键会贴左，
                    // 撑满后 spacedBy 的 CenterHorizontally 才生效（与横屏一致）
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (!prefs.playerHideVolumeBar) {
                    BottomRow(
                        volume = volume,
                        onVolume = onVolume,
                        onOpenEqualizer = onOpenEqualizer
                    )
                }
                }
                // 距底部 20dp（再叠加系统导航栏安全区）
                Spacer(Modifier.height(BottomGap))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/**
 * 信息行：`收藏 | 标题 / 歌手 / 专辑（三行） | [下载 | 词]胶囊`。
 *
 * 用户要求标题、歌手、专辑三行独立显示（不能合并成 `歌手 · 专辑` 一行）。
 * 三行都单行省略；即使歌手/专辑为空也保留行高（空格占位），保证每一页信息区
 * 高度完全相同 —— 否则底部贴底的信息区会随歌变高/变矮，把上方封面顶得上下挪，
 * 滑动切歌时就表现为「封面跳一下」。通过自定义 [Layout] 在不触碰左右按钮的前提下
 * 优先让文本块居中于整条信息行中轴，收藏和右侧胶囊垂直居中于三行块。
 */
@Composable
private fun TrackMetaBar(
    title: String,
    artist: String,
    album: String,
    quality: String? = null,
    isFavorite: Boolean,
    lyricsExpanded: Boolean,
    showDownloadButton: Boolean = false,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onDownload: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onToggleLyrics: () -> Unit,
    switchMotion: PlayerSwitchMotion = PlayerSwitchMotion.DEPTH_SLIDE,
    activeSwitch: ButtonSwitchState? = null,
    switchProgress: () -> Float = { 1f }
) {
    val skin = LocalSkin.current

    @Composable
    fun MetaLines(t: String, a: String, al: String, q: String?, mod: Modifier = Modifier) {
        Column(modifier = mod, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = t,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            // 歌手 / 专辑两行即使为空也保留行高（用空格占位）：固定三行高度保证翻页不跳。
            Spacer(Modifier.height(1.dp))
            Text(
                text = a.ifBlank { " " },
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
                    if (al.isNotBlank()) append(al)
                    if (!q.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(q)
                    }
                }.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            // 0: 收藏（42dp 热区、22dp 图标、无圆底）
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
            // 1: 居中三行曲目信息（按钮切歌时在裁剪区内部做错峰/微滚过渡，绝不牵动外层布局）
            Box(
                modifier = Modifier.clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                if (activeSwitch != null) {
                    MetaLines(
                        t = activeSwitch.fromSong.title,
                        a = activeSwitch.fromSong.artist,
                        al = activeSwitch.fromSong.album,
                        q = null,
                        mod = Modifier.graphicsLayer {
                            val p = switchProgress().coerceIn(0f, 1f)
                            val d = activeSwitch.dir.toFloat()
                            val outP = (p / 0.55f).coerceIn(0f, 1f)
                            alpha = if (switchMotion == PlayerSwitchMotion.POWERAMP_SLIDE) {
                                (1f - (p / 0.78f).coerceIn(0f, 1f))
                            } else {
                                1f - outP
                            }
                            when (switchMotion) {
                                PlayerSwitchMotion.POWERAMP_SLIDE -> translationX = -d * 220.dp.toPx() * p
                                PlayerSwitchMotion.DEPTH_SLIDE -> translationX = -d * 42.dp.toPx() * outP
                                PlayerSwitchMotion.TILT_3D -> translationY = -d * 22.dp.toPx() * outP
                                PlayerSwitchMotion.OPTICAL_DISSOLVE -> translationX = -d * 22.dp.toPx() * outP
                            }
                        }
                    )
                }
                MetaLines(
                    t = title,
                    a = artist,
                    al = album,
                    q = quality,
                    mod = if (activeSwitch != null) {
                        Modifier.graphicsLayer {
                            val p = switchProgress().coerceIn(0f, 1f)
                            val d = activeSwitch.dir.toFloat()
                            val stagger = ((p - 0.10f) / 0.90f).coerceIn(0f, 1f)
                            alpha = if (switchMotion == PlayerSwitchMotion.POWERAMP_SLIDE) {
                                ((p - 0.08f) / 0.72f).coerceIn(0f, 1f)
                            } else {
                                stagger
                            }
                            when (switchMotion) {
                                PlayerSwitchMotion.POWERAMP_SLIDE -> translationX = d * 220.dp.toPx() * (1f - p)
                                PlayerSwitchMotion.DEPTH_SLIDE -> translationX = d * 48.dp.toPx() * (1f - stagger)
                                PlayerSwitchMotion.TILT_3D -> translationY = d * 26.dp.toPx() * (1f - stagger)
                                PlayerSwitchMotion.OPTICAL_DISSOLVE -> translationX = d * 24.dp.toPx() * (1f - stagger)
                            }
                        }
                    } else Modifier
                )
            }
            // 2: 右侧「下载 | 词」或单「词」微磨砂胶囊
            LyricsPillButton(
                expanded = lyricsExpanded,
                showDownloadButton = showDownloadButton,
                isDownloading = isDownloading,
                isDownloaded = isDownloaded,
                onDownload = onDownload,
                onToggleLyrics = onToggleLyrics
            )
        }
    ) { measurables, constraints ->
        val minGap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = measurables[0].measure(loose)
        val right = measurables[2].measure(loose)
        val maxCenterW = (constraints.maxWidth - left.width - right.width - minGap * 2)
            .coerceAtLeast(0)
        val center = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = maxCenterW, minHeight = 0)
        )
        val height = maxOf(left.height, center.height, right.height)
        layout(constraints.maxWidth, height) {
            left.placeRelative(0, (height - left.height) / 2)
            right.placeRelative(constraints.maxWidth - right.width, (height - right.height) / 2)
            val idealX = (constraints.maxWidth - center.width) / 2
            val minX = left.width + minGap
            val maxX = (constraints.maxWidth - right.width - minGap - center.width)
                .coerceAtLeast(minX)
            center.placeRelative(idealX.coerceIn(minX, maxX), (height - center.height) / 2)
        }
    }
}

/**
 * 信息行右侧微磨砂胶囊：`[ ↓ | ≡ 词 ]`（播放在线歌时合入下载，本地歌为单「≡ 词」）。
 * 已下载后直接禁用下载按钮并柔和置灰，不再替换图标或绘制高亮圆底。
 */
@Composable
private fun LyricsPillButton(
    expanded: Boolean,
    showDownloadButton: Boolean,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onToggleLyrics: () -> Unit
) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val lyricsInteraction = remember { MutableInteractionSource() }
    val lyricsPressed by lyricsInteraction.collectIsPressedAsState()
    val lyricsScale by animateFloatAsState(
        targetValue = if (lyricsPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "lyricsPill"
    )
    val downloadEnabled = !isDownloaded && !isDownloading
    val downloadInteraction = remember { MutableInteractionSource() }
    val downloadPressed by downloadInteraction.collectIsPressedAsState()
    val downloadScale by animateFloatAsState(
        targetValue = if (downloadEnabled && downloadPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "downloadPill"
    )

    val shape = RoundedCornerShape(16.dp)
    val containerBg = if (!showDownloadButton && expanded) {
        skin.accentGlow
    } else {
        skin.surface2.copy(alpha = 0.55f)
    }
    val containerBorder = if (!showDownloadButton && expanded) {
        skin.accent
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(containerBg)
            .border(1.dp, containerBorder, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = showDownloadButton,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
            exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180))
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = downloadScale
                            scaleY = downloadScale
                        }
                        .clickable(
                            interactionSource = downloadInteraction,
                            indication = null,
                            enabled = downloadEnabled
                        ) {
                            hapticTick(ctx)
                            onDownload()
                        }
                        .padding(start = 10.dp, end = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDownloading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = skin.accent,
                                strokeWidth = 1.6.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                imageVector = SonanceIcons.Download,
                                contentDescription = if (isDownloaded) "已下载到本地曲库" else "下载此在线歌曲",
                                tint = if (isDownloaded) skin.text.copy(alpha = 0.28f) else skin.text.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(13.dp)
                        .background(Color.White.copy(alpha = 0.14f))
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (showDownloadButton && expanded) skin.accentGlow else Color.Transparent)
                .clickable(
                    interactionSource = lyricsInteraction,
                    indication = null,
                    onClick = onToggleLyrics
                )
                .padding(start = if (showDownloadButton) 8.dp else 11.dp, end = 11.dp)
                .graphicsLayer {
                    scaleX = lyricsScale
                    scaleY = lyricsScale
                },
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
}

@Composable
private fun ProgressBlock(
    frac: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    playbackSpeed: Float,
    onSetSpeed: (Float) -> Unit,
    abLoopStartMs: Long = -1L,
    abLoopEndMs: Long = -1L,
    onToggleAbLoop: () -> Unit = {}
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val abHasA = abLoopStartMs >= 0L
    val abLooping = abHasA && abLoopEndMs > abLoopStartMs
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
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
            if (durationMs > 0L && abHasA) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val padPx = 6.5.dp.toPx()
                    val usableW = (size.width - padPx * 2f).coerceAtLeast(1f)
                    val cy = size.height / 2f
                    val tickH = 8.dp.toPx()
                    val aFrac = (abLoopStartMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val ax = padPx + usableW * aFrac
                    drawLine(
                        color = skin.accent,
                        start = Offset(ax, cy - tickH / 2f),
                        end = Offset(ax, cy + tickH / 2f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    if (abLooping) {
                        val bFrac = (abLoopEndMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        val bx = padPx + usableW * bFrac
                        drawLine(
                            color = skin.accent,
                            start = Offset(bx, cy - tickH / 2f),
                            end = Offset(bx, cy + tickH / 2f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(positionMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = skin.textMuted,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            // 播放速度 + A-B 循环胶囊并排居于进度条下方中轴
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatSpeed(playbackSpeed),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = FontWeight.Medium,
                    color = if (playbackSpeed != 1f) skin.accent else skin.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            hapticTick(context)
                            onSetSpeed(nextSpeed(playbackSpeed))
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Text(
                    text = when {
                        abLooping -> "A-B"
                        abHasA -> "A→"
                        else -> "A-B"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = if (abHasA) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (abHasA) skin.accent else skin.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (abHasA) {
                                Modifier.background(skin.accent.copy(alpha = if (abLooping) 0.18f else 0.10f))
                            } else Modifier
                        )
                        .clickable {
                            hapticTick(context)
                            onToggleAbLoop()
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = skin.textMuted,
                modifier = Modifier.align(Alignment.CenterEnd)
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
internal fun nextSpeed(current: Float): Float {
    val i = SPEED_STEPS.indexOfFirst { abs(it - current) < 0.005f }
    return if (i < 0) 1f else SPEED_STEPS[(i + 1) % SPEED_STEPS.size]
}

/** "1.0×" / "0.75×" 这种统一两位小数位的写法，菜单与胶囊都用它 */
internal fun formatSpeed(v: Float): String =
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
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VolumeSlider(
            volume = volume,
            onVolumeChange = onVolume,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable {
                    hapticTick(ctx)
                    onOpenEqualizer()
                },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = SonanceIcons.Equalizer,
                contentDescription = "均衡器",
                tint = skin.accent,
                modifier = Modifier.size(20.dp)
            )
        }
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
