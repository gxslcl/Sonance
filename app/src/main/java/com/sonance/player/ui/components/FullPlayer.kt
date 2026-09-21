package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverTint
import com.sonance.player.data.GlowMode
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit = {},
    /** 打开均衡器面板 */
    onOpenEqualizer: () -> Unit = {},
    /** 打开睡眠定时器面板 */
    onOpenSleepTimer: () -> Unit = {},
    /** 睡眠定时器是否在计时中（顶栏图标高亮用） */
    sleepTimerActive: Boolean = false,
    /** 均衡器是否开着（图标高亮用） */
    eqEnabled: Boolean = false,
    /** 播放页封面的颜色往哪边晕开 */
    glowMode: GlowMode = GlowMode.AROUND,
    /** 晕染浓度 */
    glowStrength: Float = 0.6f,
    /** 双击封面那个面板改了包裹方式 */
    onGlowModeChange: (GlowMode) -> Unit = {},
    /** 双击封面那个面板改了浓度 */
    onGlowStrengthChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val prefs = remember { com.sonance.player.data.Prefs(context) }
    val scope = rememberCoroutineScope()

    /**
     * 「歌词」那一行是否展开成了单行滚动歌词。
     *
     * 注意与顶栏那个歌词图标是两条路：顶栏仍然进**全屏歌词页**（那里能看完整歌词、
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

    // 当前这首的晕染色卡。取色是异步算的，所以把 tint 也放进 remember 的 key：
    // 只按 songId 缓存的话，第一帧拿到的「还没取到色」的兜底色会一直被记住。
    val glowSong = queue.getOrNull(state.queueIndex)
    val glowTint = glowSong?.let {
        ArtworkLoader.tintOf(it.albumId, it.uri, OnlineCover.keyOf(it.title, it.artist))
    }
    // artworkGoneVersion 也要进 key：它变化代表「又确认了一首歌没有封面」，
    // 只有那时才该从「等真色」切到「用占位色兜底」。
    val glowColors = remember(
        glowSong?.id,
        glowSong?.title,
        glowTint,
        skin.id,
        ArtworkLoader.artworkGoneVersion
    ) {
        val song = glowSong
        when {
            song == null -> emptyList()

            glowTint != null ->
                listOf(Color(glowTint.left), Color(glowTint.center), Color(glowTint.right))

            // 封面**确实没有**（本地探测过、磁盘上也没有下载封面）→ 用占位图配色兜底。
            // 这类歌永远不会产生色卡，不给兜底的话它们就一直没有晕染。
            ArtworkLoader.isArtworkGone(
                song.albumId,
                song.uri,
                OnlineCover.keyOf(song.title, song.artist)
            ) -> {
                val c = skin.gradientFor(song.title + song.artist)
                listOf(c.first(), c[c.size / 2], c.last())
            }

            // 封面**还在找**（探测中 / 正在解码 / 在线封面正在下载）→ 先不画。
            //
            // 这里以前是直接回落到占位色的，于是进入播放页 / 切歌时会出现
            // 「先上一版跟封面无关的假颜色，真色到了再突变一次」——
            // 看上去就是渐变闪了一下。宁可晚一两百毫秒出现
            // （正好和封面图本身同时就位），也不要先给错的。
            else -> emptyList()
        }
    }
    val glowSpec = coverBounds
        ?.takeIf { glowColors.isNotEmpty() && it.width > 0f && it.height > 0f }
        ?.let { GlowSpec(colors = glowColors, mode = glowMode, strength = glowStrength, cover = it) }

    // 晕染的二维渐变图（横向取封面色、纵向做淡出）。
    //
    // 以前这里是「横向切 32 段、每段画一条纵向渐变」。段与段之间会留下接缝：
    // 每段都是独立的 drawRect，交界处的像素会被两个矩形各画一次，
    // 于是屏幕上出现一排等距的竖线（32 段 → 1080 宽的手机上每 34px 一条）。
    // 一张小位图拉伸就能彻底避免 —— 横向是连续的，没有段就没有缝。
    val glowImage = remember(glowColors, glowMode, glowStrength) {
        buildGlowImage(glowColors, glowMode, glowStrength)
    }

    // 播放页的三层（封面 / 歌词 / 律动柱）做成**环形轮播**：
    // 页数取极大值、用「页号 % 3」映射到层，于是左右两个方向都能一直滑下去，
    // 到边自然绕回另一端。起始页取 3 的倍数，保证初始停在封面层。
    val layerPager = rememberPagerState(initialPage = LAYER_RING_START) { Int.MAX_VALUE }
    val layer = PlayerLayer.entries[layerPager.currentPage % PlayerLayer.entries.size]

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

    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // 歌词 / 律动柱上按返回键 → 滑回封面层，而不是直接收起播放页。
    // 拖动中不接管，免得和手指的滑动打架。
    BackHandler(enabled = layer != PlayerLayer.COVER && !layerPager.isScrollInProgress) {
        goToLayer(PlayerLayer.COVER)
    }

    // 分页器 → 播放器：只在页面「落定」后切歌，避免滑动过程中反复触发。
    // 用 rememberUpdatedState 读取最新 queueIndex，否则快照流里会捕获到旧值。
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(pagerState, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != latestState.queueIndex) onPlayAt(page)
        }
    }

    // 播放器 → 分页器：按钮切歌 / 自动续播 / 点队列时把页面跟过去。
    LaunchedEffect(state.queueIndex, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        val target = state.queueIndex.coerceIn(0, queue.lastIndex)
        if (pagerState.settledPage != target && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(target)
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
                val spec = glowSpec ?: return@drawBehind
                drawGlow(spec, glowImage)
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

            // ===== 顶栏（固定，不随页滚动）=====
            //
            // 用 Box 叠一层，而不是把标题塞进 Row 的中间格：
            // 左侧只有 1 个图标、右侧有 3 个，塞进 Row 的话标题是在**剩余空间**里居中，
            // 结果明显偏左。这里让标题独立居中于整条栏。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(
                        icon = SonanceIcons.ChevronDown,
                        contentDescription = "收起",
                        onClick = onClose,
                        iconSize = 22.dp
                    )
                    // 睡眠定时器：放在左侧第二格。
                    // 不放右边 —— 右侧已经有歌词/律动柱/队列三个图标，再挤一个会把
                    // 居中的「正在播放」顶到，左侧只有"收起"一个，还有富余。
                    IconAction(
                        icon = SonanceIcons.Timer,
                        contentDescription = if (sleepTimerActive) "睡眠定时器（计时中）" else "睡眠定时器",
                        onClick = onOpenSleepTimer,
                        iconSize = 20.dp,
                        tint = if (sleepTimerActive) skin.accent else skin.textMuted
                    )
                    Spacer(Modifier.weight(1f))
                    // 歌词 / 律动柱开关：当前就在那一层时再点一次，回到封面层
                    IconAction(
                        icon = SonanceIcons.Lyrics,
                        contentDescription = if (layer == PlayerLayer.LYRICS) "关闭歌词" else "显示歌词",
                        onClick = {
                            goToLayer(
                                if (layer == PlayerLayer.LYRICS) PlayerLayer.COVER else PlayerLayer.LYRICS
                            )
                        },
                        iconSize = 20.dp,
                        tint = if (layer == PlayerLayer.LYRICS) skin.accent else skin.textMuted
                    )
                    IconAction(
                        icon = SonanceIcons.Equalizer,
                    contentDescription = if (layer == PlayerLayer.VISUALIZER) "关闭律动柱" else "显示律动柱",
                    onClick = {
                        goToLayer(
                            if (layer == PlayerLayer.VISUALIZER) {
                                PlayerLayer.COVER
                            } else {
                                PlayerLayer.VISUALIZER
                            }
                        )
                    },
                    iconSize = 20.dp,
                    tint = if (layer == PlayerLayer.VISUALIZER) skin.accent else skin.textMuted
                )
                    IconAction(
                        icon = SonanceIcons.Queue,
                        contentDescription = "播放队列",
                        onClick = onOpenQueue,
                        iconSize = 20.dp
                    )
                }

                // 标题独立居中于整条栏，不受左右图标数量影响
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted,
                    modifier = Modifier.align(Alignment.Center)
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
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    // key 必须带上页码：`playNext` 不查重，队列里可能出现同一首歌两次
                    // （用户对已在队列里的歌点"下一首播放"就会），只拿 song.id 当 key
                    // 会撞成重复 key，分页器直接抛 "Key ... was already used"。
                    key = { page -> "$page-${queue.getOrNull(page)?.id ?: -1L}" },
                    modifier = Modifier.fillMaxSize()
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
                        isPlaying = if (isCurrent) state.isPlaying else false,
                        shuffle = if (isCurrent) state.shuffle else false,
                        repeat = if (isCurrent) state.repeat else RepeatMode.OFF,
                        volume = if (isCurrent) state.volume else 1f,
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onShuffle = onShuffle,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onRepeat = onRepeat,
                        onSeek = onSeek,
                        onVolume = onVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenCover = onOpenCover,
                        onOpenEqualizer = onOpenEqualizer,
                        eqEnabled = eqEnabled,
                        prefs = prefs,
                        lyricsExpanded = lyricsExpanded,
                        onToggleLyrics = { lyricsExpanded = !lyricsExpanded },
                        glowMode = glowMode,
                        glowStrength = glowStrength,
                        onOpenGlow = { showGlowSheet = true },
                        // 量出来的是 root 坐标，换算成本页（根 Box）坐标再交给晕染。
                        //
                        // 只有**当前页**的上报算数：分页器会同时组合相邻页
                        // （beyondViewportPageCount = 1），那些页停在屏幕外，
                        // 它们的封面位置一旦参与上报，晕染就会被拽到屏幕外去 ——
                        // 表现是颜色突然消失、或者闪到别的位置。
                        onCoverBounds = {
                            if (isCurrent) {
                                coverBounds = it.translate(-rootTopLeft.x, -rootTopLeft.y)
                            }
                        }
                    )
                    }
                }
                }   // Box(weight(1f).clipToBounds()) —— 分页器的视口裁剪
            }
        }

        // ===== 第 1 层：歌词 =====
        1 -> if (currentSong != null) {
            LyricsOverlay(
                song = currentSong,
                positionMs = state.positionMs,
                prefs = prefs,
                onSeek = onSeek,
                onClose = { goToLayer(PlayerLayer.COVER) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== 第 2 层：律动柱 =====
        else -> if (currentSong != null) {
            VisualizerOverlay(
                song = currentSong,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = if (state.durationMs > 0) {
                    state.durationMs
                } else {
                    currentSong.durationMs
                },
                prefs = prefs,
                onClose = { goToLayer(PlayerLayer.COVER) },
                modifier = Modifier.fillMaxSize()
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
            onModeChange = onGlowModeChange,
            onStrengthChange = onGlowStrengthChange,
            onDismiss = { showGlowSheet = false }
        )
    }
}

/**
 * 播放页的三层。三者首尾相连成一个环，左右滑动可以一直转下去
 * （封面 → 歌词 → 律动柱 → 封面 …，反方向同理）。
 */
private enum class PlayerLayer { COVER, LYRICS, VISUALIZER }

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

/**
 * 纵向浓度曲线：`v = 0` 是晕染区**靠封面的那一端**，`v = 1` 是远端。
 * 端点都收在 0，所以无论哪种模式，边界都是渐变淡出、不会留下硬边。
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
    else -> (1f - v) * 0.92f
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
    val w = GLOW_BITMAP_W
    val h = GLOW_BITMAP_H
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val v = y / (h - 1f)
        val a = (glowVerticalCurve(mode, v) * strength).coerceIn(0f, 1f)
        val ai = (a * 255f + 0.5f).toInt()
        if (ai == 0) continue   // 全透明，留 0 就行
        for (x in 0 until w) {
            val argb = glowTintAt(colors, x / (w - 1f)).toArgb()
            px[y * w + x] = (ai shl 24) or (argb and 0x00FFFFFF)
        }
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/** 在封面的几个取样色之间按 [t]（0~1）插值 */
private fun glowTintAt(colors: List<Color>, t: Float): Color {
    if (colors.size == 1) return colors[0]
    val scaled = t.coerceIn(0f, 1f) * (colors.size - 1)
    val i = scaled.toInt().coerceIn(0, colors.size - 2)
    return lerp(colors[i], colors[i + 1], scaled - i)
}

/**
 * 双击手势（用来打开晕染面板）。
 *
 * 自己比两次点击的时间戳，**不用** `detectTapGestures(onDoubleTap=)` ——
 * 后者的判定窗口是系统写死的 300ms，且实测用 `adb shell input tap` 注入的
 * 两次点击根本挤不进这个窗口，模拟器和真机上都很难触发。
 * 800ms 对真人双击（通常 100~250ms）绰绰有余，也不会把两次无关点击算成双击。
 *
 * 用 `clickable` 而不是自己写 `pointerInput`：后者实测 up 事件会被判成
 * 「已被消费」直接返回 null，一次都不触发。
 */
@Composable
private fun Modifier.onDoubleTapGlow(action: () -> Unit): Modifier {
    val lastTap = remember { longArrayOf(0L) }
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null) {
        val now = System.currentTimeMillis()
        if (now - lastTap[0] < 800L) {
            lastTap[0] = 0L
            action()
        } else {
            lastTap[0] = now
        }
    }
}

/**
 * 晕染需要的全部参数。
 *
 * 它画在**整页背景层**（[FullPlayer] 最外层那个 Box 上），而不是封面那一层：
 * 封面层在分页器内部，向上溢出到顶栏的那部分会被分页器裁掉，顶栏就永远缺一块颜色。
 * 代价是这一层不知道封面画在哪（竖屏居中、横屏在左栏），
 * 所以封面矩形由 [PlayerPage] 用 `onGloballyPositioned` 量出来、再传上来。
 */
private data class GlowSpec(
    val colors: List<Color>,
    val mode: GlowMode,
    val strength: Float,
    /** 封面在整页坐标系（root）里的矩形 */
    val cover: Rect,
)

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
private fun DrawScope.drawGlow(spec: GlowSpec, image: ImageBitmap?) {
    val colors = spec.colors
    val mode = spec.mode
    val strength = spec.strength
    if (colors.isEmpty() || strength <= 0f) return
    val w = spec.cover.width
    val h = spec.cover.height
    val cx = spec.cover.center.x
    val cy = spec.cover.center.y

    when (mode) {
                    // ── 只从下边缘向下晕 ──
                    GlowMode.BOTTOM -> drawGlowImage(
                        image = image,
                        top = cy - h / 2f + h * GLOW_TOP,
                        height = h * GLOW_REACH
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
                            height = h + pad * 2f
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
                                    0.00f to c.copy(alpha = strength),
                                    inner to c.copy(alpha = strength),
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
                        height = h * 3.4f
                    )
                }
}

/** 把位图按目标矩形拉伸绘制 —— 颜色和浓度都已经烘在位图里，一次就画完了 */
private fun DrawScope.drawGlowImage(image: ImageBitmap?, top: Float, height: Float) {
    val width = size.width
    if (image == null || width <= 0f || height <= 0f) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, top.roundToInt()),
        dstSize = IntSize(width.roundToInt(), height.roundToInt())
    )
}

/**
 * 「封面晕染」设置面板 —— 双击封面打开。
 *
 * 和设置页里那一份是同一组选项（[GlowMode] + 浓度）。两处都保留是有意的：
 * 在播放页看到颜色不对可以就地调，不用退出去翻设置再回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlowSheet(
    mode: GlowMode,
    strength: Float,
    onModeChange: (GlowMode) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = skin.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "封面颜色晕染",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "把封面的颜色往背景上渗一点。以后想调，双击封面就行。",
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
        }
    }
}

/**
 * 单页播放器（自包含）：背景晕染、封面、文本、进度与控制。
 * 只接收派生值，不直接读 [PlayerUiState]，因此非当前页可被跳过重组。
 */
@Composable
private fun PlayerPage(
    song: Song,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    volume: Float,
    onToggleFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit,
    /** 打开均衡器面板 */
    onOpenEqualizer: () -> Unit,
    /** 均衡器是否开着（图标高亮用） */
    eqEnabled: Boolean,
    prefs: Prefs,
    /** 「歌词」那一行是否已展开成单行滚动歌词 */
    lyricsExpanded: Boolean,
    /** 展开 / 收起那一行（不切页、不改布局） */
    onToggleLyrics: () -> Unit,
    glowMode: GlowMode,
    glowStrength: Float,
    /** 双击封面 → 打开晕染设置面板 */
    onOpenGlow: () -> Unit,
    /** 把封面的位置量出来上报 —— 晕染画在整页背景层，需要知道封面画在哪 */
    onCoverBounds: (Rect) -> Unit
) {
    val skin = LocalSkin.current
    val seed = song.title + song.artist
    val frac = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 晕染用的三个色。tintOf 读的是 SnapshotStateMap，色卡算出来后这里会自动重组。
    // 取不到（还没解码 / 封面本身就是纯黑白）时退回占位图的配色，
    // 免得出现「有的歌有晕染、有的歌没有」的割裂感。
    val coverKey = OnlineCover.keyOf(song.title, song.artist)
    val tint = ArtworkLoader.tintOf(song.albumId, song.uri, coverKey)
    val placeholderColors = remember(seed, skin.id) { skin.gradientFor(seed) }
    val glowColors = remember(tint, placeholderColors) {
        if (tint != null) {
            listOf(Color(tint.left), Color(tint.center), Color(tint.right))
        } else {
            val c = placeholderColors
            listOf(c.first(), c[c.size / 2], c.last())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val boxH = maxHeight
        val isWide = boxW >= 620.dp && boxW > boxH
        // 单次 BoxWithConstraints：原实现里再嵌了一层只为算封面边长，把这一层去掉，
        // 封面尺寸在外层一次算好，少一轮 measure。
        val portraitSide = if (!isWide) (if (boxW < boxH) boxW else boxH) else 0.dp
        val landscapeSide = if (isWide) landscapeCoverSize(boxW, boxH) else 0.dp
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
                    .padding(horizontal = 36.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
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
                                        center.y - landscapeSidePx / 2f
                                    ),
                                    size = Size(landscapeSidePx, landscapeSidePx)
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 主区域在「封面」与「歌词」之间切换。
                    // 主流播放器就是这样：歌词占的就是封面那一块，而不是另开一整屏。
                    AnimatedContent(
                        targetState = lyricsExpanded,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            InlineLyrics(
                                song = song,
                                positionMs = positionMs,
                                prefs = prefs,
                                onSeek = onSeek,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CoverArt(
                                albumId = song.albumId,
                                seed = seed,
                                modifier = Modifier
                                    .size(landscapeSide)
                                    .clip(RoundedCornerShape(16.dp))
                                    // 位置由左栏那一层上报（见上面的注释）
                                    .onDoubleTapGlow(onOpenGlow),
                                cornerRadius = 16.dp,
                                sizePx = 900,
                                sourceUri = song.uri,
                                songId = song.id,
                                coverKey = coverKey
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
                    TrackText(song.title, song.artist, song.album)
                    Spacer(Modifier.height(12.dp))
                    LyricsEntryButton(expanded = lyricsExpanded, onClick = onToggleLyrics)
                    Spacer(Modifier.height(18.dp))
                    ProgressBlock(frac, positionMs, durationMs, onSeek)
                    Spacer(Modifier.height(14.dp))
                    TransportControls(
                        isPlaying = isPlaying,
                        shuffle = shuffle,
                        repeat = repeat,
                        onShuffle = onShuffle,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onRepeat = onRepeat,
                        ctrlSize = 40.dp,
                        ctrlIcon = 20.dp,
                        playSize = 60.dp,
                        playIcon = 26.dp,
                        gap = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    BottomRow(
                        isFavorite, volume, onToggleFavorite, onVolume,
                        onOpenQueue, onOpenCover, onOpenEqualizer, eqEnabled
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
                        .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) },
                    contentAlignment = Alignment.Center
                ) {
                    // 同上：歌词就在封面这一块里换出来
                    AnimatedContent(
                        targetState = lyricsExpanded,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "coverOrLyrics"
                    ) { expanded ->
                        if (expanded) {
                            InlineLyrics(
                                song = song,
                                positionMs = positionMs,
                                prefs = prefs,
                                onSeek = onSeek,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CoverArt(
                                albumId = song.albumId,
                                seed = seed,
                                modifier = Modifier
                                    .size(portraitSide)
                                    .clip(RoundedCornerShape(16.dp))
                                    // 位置由主区域那一层上报（见上面的注释）——
                                    // 挂在这里的话歌词态就断了
                                    .onDoubleTapGlow(onOpenGlow),
                                cornerRadius = 16.dp,
                                sizePx = 900,
                                sourceUri = song.uri,
                                songId = song.id,
                                coverKey = coverKey
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                TrackText(song.title, song.artist, song.album, centered = true)
                Spacer(Modifier.height(12.dp))
                LyricsEntryButton(expanded = lyricsExpanded, onClick = onToggleLyrics)
                Spacer(Modifier.height(18.dp))
                ProgressBlock(frac, positionMs, durationMs, onSeek)
                Spacer(Modifier.height(16.dp))
                TransportControls(
                    isPlaying = isPlaying,
                    shuffle = shuffle,
                    repeat = repeat,
                    onShuffle = onShuffle,
                    onPrev = onPrev,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onRepeat = onRepeat,
                    ctrlSize = 44.dp,
                    ctrlIcon = 22.dp,
                    playSize = 64.dp,
                    playIcon = 28.dp,
                    gap = 14.dp
                )
                Spacer(Modifier.height(14.dp))
                BottomRow(
                    isFavorite, volume, onToggleFavorite, onVolume,
                    onOpenQueue, onOpenCover, onOpenEqualizer, eqEnabled
                )
                // 距底部 20dp（再叠加系统导航栏安全区）
                Spacer(Modifier.height(BottomGap))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun TrackText(title: String, artist: String, album: String, centered: Boolean = false) {
    val skin = LocalSkin.current
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = skin.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
        if (album.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = album,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align
            )
        }
    }
}

@Composable
private fun ProgressBlock(
    frac: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth()) {
        SonanceSlider(
            value = frac,
            onValueChange = { f -> onSeek((f * durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 4.dp,
            thumbSize = 13.dp,
            alwaysShowThumb = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }
    }
}

@Composable
private fun BottomRow(
    isFavorite: Boolean,
    volume: Float,
    onFavorite: () -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit,
    onOpenEqualizer: () -> Unit,
    eqEnabled: Boolean
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconAction(
            icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
            contentDescription = "收藏",
            onClick = onFavorite,
            iconSize = 20.dp,
            tint = if (isFavorite) skin.accent else skin.textMuted
        )
        VolumeSlider(
            volume = volume,
            onVolumeChange = onVolume,
            modifier = Modifier.weight(1f)
        )
        IconAction(
            icon = SonanceIcons.Waveform,
            contentDescription = "均衡器",
            // 这里以前错接成了「播放队列」—— 图标是音效波形、名字也叫音效，
            // 点下去却弹队列，跟顶栏那个队列按钮完全重复。现在接均衡器。
            onClick = onOpenEqualizer,
            iconSize = 20.dp,
            tint = if (eqEnabled) skin.accent else skin.textMuted
        )
        IconAction(
            icon = SonanceIcons.Image,
            contentDescription = "更换封面",
            onClick = onOpenCover,
            iconSize = 20.dp,
            tint = skin.textMuted
        )
    }
}

/**
 * 主区域「封面 / 歌词」的切换胶囊。
 *
 * 顶栏虽然有一个歌词图标按钮，但纯图标辨识度低、用户反馈找不到，
 * 所以控制区留一个**带文字**的入口。展开歌词后文案变成「封面」，点一下换回封面 ——
 * 胶囊位置不变，所以来回切换时布局不跳。
 *
 * 要看完整的整页歌词（可调字号、点行跳转、手动搜索）仍然走全屏歌词页：
 * 顶栏歌词图标，或者左右滑动。
 */
@Composable
private fun LyricsEntryButton(expanded: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (expanded) skin.accentGlow else skin.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (expanded) SonanceIcons.Image else SonanceIcons.Lyrics,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = if (expanded) "封面" else "歌词",
            style = MaterialTheme.typography.labelMedium,
            color = skin.text
        )
    }
}

private fun landscapeCoverSize(width: Dp, height: Dp): Dp {
    val byHeight = height - 28.dp
    val byWidth = width * 0.44f
    return if (byHeight < byWidth) byHeight else byWidth
}

/** 播放页内容距底部的留白 */
private val BottomGap = 20.dp
