package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.blendGlowColors
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.PlayMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 全屏播放页。竖向翻页（TikTok / 汽水音乐式）：整页上下滑动切歌，
 * 相邻页提前渲染（[androidx.compose.foundation.pager.PagerState] 的
 * `beyondViewportPageCount = 1`），120Hz 下流畅切换。
 */
@Composable
fun FullPlayer(
    state: PlayerUiState,
    /**
     * 播放队列。**必须和 [queueIndex] 配套使用** ——
     * 这份列表是"解析后"的（解析不出来的条目会被跳过），
     * 所以它的下标和播放器里的下标不是一回事，别拿 [PlayerUiState.queueIndex] 去索引它。
     */
    queue: List<Song>,
    /** 当前曲在 [queue] 里的下标（见 [ResolvedQueue.index]） */
    queueIndex: Int,
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
    /** 睡眠定时器是否在计时中（顶栏图标高亮用） */
    sleepTimerActive: Boolean = false,
    /** 均衡器是否开着（图标高亮用） */
    eqEnabled: Boolean = false,
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

    // 歌曲分页器。**必须声明在晕染素材之前** —— 跟手模式要按它的滚动位置混合颜色。
    val pagerState = rememberPagerState(
        initialPage = queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // ===== 晕染素材 =====
    //
    // 两种过渡方式都只用**一层**烘好的素材，区别只在"颜色什么时候换"：
    // - SWIPE（跟手）：颜色由分页器的滚动位置驱动，和页面位移同步；
    // - FADE（渐变）：页面落定之后，用 animateColorAsState 在设定时长内补间。
    //
    // ⚠️ 都必须只有一层。早先跟手模式是"相邻两页各画一次，按 (1-t) / t 交叉淡化"，
    // 而两层都半透明地叠起来之后，中点的总覆盖度**低于**两端（底色透了出来），
    // 观感就是每次切歌到一半背景亮一下 —— 用户报的「切歌时背景会闪一下」。
    // 实测（1080×2400，给测试曲注入高饱和封面）：背景左右边缘的 G 通道
    // 两端是 56 / 93，中间那几帧只有 64~75，比端点连线低 8~10 个单位。
    // 颜色插值必须落在**颜色本身**上，不能靠两次 src-over 去拼。
    val isSwipeGlow = glowTransition == GlowTransition.SWIPE

    /** 这一帧要画的那一层素材。 */
    val glowFrame: GlowFrame = if (isSwipeGlow) {
        // 滚动位置**只在这里读一次**，位置和素材因此来自同一份快照。
        // （原来是在绘制期读 currentPage、素材却在组合期算；两者错开一帧时，
        //   翻页那一刻会拿"新下标配旧素材"，背景闪一帧别的颜色。）
        val pos = rememberGlowPage(pagerState)
        val base = floor(pos).toInt()
        // 该画哪两页由**滚动位置**决定，和 currentPage 在哪翻页无关 ——
        // 于是原来那套 `i = (base - (currentPage - 1))` 的下标运算整个不需要了，
        // 要取色的页也从三份变成两份。
        val blended = blendGlowColors(
            cur = rememberGlowColors(queue.getOrNull(base), skin),
            next = rememberGlowColors(queue.getOrNull(base + 1), skin),
            t = (pos - base).coerceIn(0f, 1f)
        )
        rememberGlowFrame(rememberHeldGlowColors(blended), glowMode, glowStrength)
    } else {
        rememberGlowFrame(
            colors = rememberFadeGlowColors(queue, queueIndex, skin, glowFadeMs),
            mode = glowMode,
            strength = glowStrength
        )
    }

    // 预取前后两首的封面：色卡和位图都要，换歌时颜色和封面都得「一上来就在」。
    //
    // 两个细节都是"上一曲会闪、下一曲不闪"的直接原因（用户报的 bug）：
    //
    // 1. **尺寸要用播放页自己的封面尺寸**。缓存键里带尺寸档（[ArtworkLoader] 的 bucketOf），
    //    早先这里固定 192（档 256）而播放页封面是 900（档 1024）—— 键对不上，
    //    等于完全没预热，切过去时封面还得现解一次，那几十到几百毫秒里是占位图。
    // 2. **两个方向必须并行、而且不能只赌前进方向**。分页器只往"前进方向"预组合下一页，
    //    往回切的那一页是临时才组合的 —— 它的封面唯一的预热来源就是这里。
    //    原来是 `forEach` 顺序取，`idx + 1` 排在前、`idx - 1` 排在后，
    //    上一曲被系统性地推到最后（快速连切时根本等不到）。
    LaunchedEffect(queueIndex, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        val idx = queueIndex
        // 只取前后各一首 —— 队列可能有几千首，不能遍历
        val neighbors = listOfNotNull(queue.getOrNull(idx + 1), queue.getOrNull(idx - 1))
        coroutineScope {
            neighbors.forEach { s ->
                launch {
                    ArtworkLoader.prefetchTint(
                        context = context,
                        albumId = s.albumId,
                        sourceUri = s.uri,
                        coverKey = OnlineCover.keyOf(s.title, s.artist),
                        sizePx = PLAYER_COVER_PX
                    )
                }
            }
        }
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

    // 歌词 / 律动柱上按返回键 → 滑回封面层，而不是直接收起播放页。
    // 拖动中不接管，免得和手指的滑动打架。
    BackHandler(enabled = layer != PlayerLayer.COVER && !layerPager.isScrollInProgress) {
        goToLayer(PlayerLayer.COVER)
    }

    // 分页器 → 播放器：只在页面「落定」后切歌，避免滑动过程中反复触发。
    // 用 rememberUpdatedState 读取最新下标，否则快照流里会捕获到旧值。
    val latestIndex by rememberUpdatedState(queueIndex)
    LaunchedEffect(pagerState, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != latestIndex) onPlayAt(page)
        }
    }

    // 播放器 → 分页器：按钮切歌 / 自动续播 / 点队列时把页面跟过去。
    LaunchedEffect(queueIndex, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        val target = queueIndex.coerceIn(0, queue.lastIndex)
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
                // 只有一层，颜色（含过渡插值）已经在组合期算好了 ——
                // 绘制阶段在这里只做"把这张图铺到封面周围"，不做任何混合。
                clipRect { drawGlow(glowFrame, cover, glowMode, glowStrength) }
            }
    ) {
        val currentSong = queue.getOrNull(queueIndex)

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
                        // 在歌词 / 律动柱层上先"退一层"回封面，而不是直接收起整个播放页。
                        //
                        // 这两个页面自己的关闭按钮已经去掉了（用户要求精简顶栏），
                        // 只剩系统返回键的话可发现性太差 —— 用户会以为"进得去出不来"。
                        // 向下箭头本来就表示"退一层"，语义正好。
                        contentDescription = if (layer == PlayerLayer.COVER) "收起" else "返回封面",
                        onClick = {
                            if (layer == PlayerLayer.COVER) onClose() else goToLayer(PlayerLayer.COVER)
                        },
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
                        contentDescription = if (layer == PlayerLayer.VISUALIZER) {
                            "关闭律动柱"
                        } else {
                            "显示律动柱"
                        },
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
                    // 顶栏这里原来还有一个「播放队列」图标，已去掉（用户要求）——
                    // 队列入口在下面的控制行上（原来"循环模式"按钮那个位置），
                    // 同一屏出现两个一模一样的按钮没有意义。
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
                    val isCurrent = page == queueIndex
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
                        playMode = if (isCurrent) {
                            PlayMode.of(state.shuffle, state.repeat)
                        } else {
                            // 非当前页给中性档，免得翻页时看到"随机"亮着
                            PlayMode.REPEAT_ALL
                        },
                        volume = if (isCurrent) state.volume else 1f,
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onCyclePlayMode = onCyclePlayMode,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onSeek = onSeek,
                        onVolume = onVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenCover = onOpenCover,
                        onOpenEqualizer = onOpenEqualizer,
                        eqEnabled = eqEnabled,
                        prefs = prefs,
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
                            // 三个条件缺一不可：
                            // - isCurrent：只有当前页的上报算数（相邻页停在屏幕外，
                            //   它们的位置一旦参与上报，晕染会被拽到屏幕外去）；
                            // - !isScrollInProgress：**滚动过程中不要更新**。
                            //   那时上报的是"旧页正在滑走的位置"，晕染会跟着往上滑一整屏
                            //   （≈2780px）再在落定时跳回来 —— 那个回跳比颜色跳变更显眼。
                            //   而每一页的布局是同一套，静止时的矩形几乎相同
                            //   （差别只来自歌名 1 行/2 行、有没有专辑名，几十像素量级），
                            //   保持静止值反而没有跳变。晕染是跨度超过两个屏高的柔和渐变，
                            //   这点偏差看不出来。
                            if (isCurrent && !pagerState.isScrollInProgress) {
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
                positionMs = state.positionMs,
                prefs = prefs,
                onSeek = onSeek,
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
            transition = glowTransition,
            fadeMs = glowFadeMs,
            onModeChange = onGlowModeChange,
            onStrengthChange = onGlowStrengthChange,
            onTransitionChange = onGlowTransitionChange,
            onFadeMsChange = onGlowFadeMsChange,
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

/**
 * 播放页封面的解码尺寸。
 *
 * ⚠️ 它同时被 [ArtworkLoader.prefetchTint] 用来预热相邻两首 ——
 * 两边必须**用同一个值**，否则缓存键的尺寸档对不上，预热等于没做，
 * 切歌时封面会先显示占位图（「切歌时闪一下」就是这么来的）。
 * 改这里就够了，别在两处各写一个 900。
 */
private const val PLAYER_COVER_PX = 900

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

/**
 * 「跟手」过渡里滚动位置量化的档数（一页分成多少档）。
 * 取 64 的取舍见 [rememberGlowPage]。
 */
private const val GLOW_POS_STEPS = 64f

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

/* ============================================================
 * 晕染素材的取色与烘焙
 * ============================================================ */

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
 *
 * key 里放的是一份 List —— `remember` 用 `equals` 比，所以颜色没变时不会重烘；
 * 跟手过渡期间颜色每量化一档变一次，一次切歌最多重烘 [GLOW_POS_STEPS] 次。
 */
@Composable
private fun rememberGlowFrame(colors: List<Color>, mode: GlowMode, strength: Float): GlowFrame =
    remember(colors, mode, strength) {
        GlowFrame(colors, buildGlowImage(colors, mode, strength))
    }

/**
 * 跟手过渡里**唯一**读滚动位置的地方：量化到 1/[GLOW_POS_STEPS] 页。
 *
 * 为什么要量化：
 * - 一档颜色就要重烘一张位图（见 [rememberGlowFrame]），量化之后一次切歌最多
 *   重烘 64 次，而不是每帧一次；
 * - 外面套一层 [derivedStateOf]，值没变时**不会**通知读它的组合 ——
 *   直接读 `currentPageOffsetFraction` 那个浮点值是每帧都变的，会让整页每帧重组。
 *
 * 量化精度够不够：一页 2400px、64 档，每档 37px 的手指位移；
 * 相邻两档的颜色差不到一个色阶，看不出台阶。
 */
@Composable
private fun rememberGlowPage(pagerState: PagerState): Float {
    val page by remember(pagerState) {
        derivedStateOf {
            val raw = pagerState.currentPage + pagerState.currentPageOffsetFraction
            floor(raw * GLOW_POS_STEPS) / GLOW_POS_STEPS
        }
    }
    return page
}

/**
 * 插值结果为空（两侧色卡都还没算出来）时，沿用**上一次真正画出来的颜色**。
 *
 * 和 [rememberFadeGlowColors] 里那条"色卡没算出来就沿用上一份"是同一个道理。
 * 少了它，切歌切到一半会有几百毫秒背景完全没有颜色、等色卡到了再突变回来 ——
 * 那也是一次「闪」。
 */
@Composable
private fun rememberHeldGlowColors(colors: List<Color>): List<Color> {
    var lastColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(colors) {
        if (colors.isNotEmpty()) lastColors = colors
    }
    return colors.ifEmpty { lastColors }
}

/**
 * 「渐变」模式要显示的色卡：在当前这一首的色卡上做补间。
 *
 * 色卡没算出来时**沿用上一份**（只在非空时更新，所以它永远是一份真实颜色）。
 * 这一条是「换歌时颜色不会先消失再突然出现」的关键 ——
 * 早先的实现要么直接跳变，要么整层消失，两种都闪。
 */
@Composable
private fun rememberFadeGlowColors(
    queue: List<Song>,
    index: Int,
    skin: Skin,
    fadeMs: Int
): List<Color> {
    val target = rememberGlowColors(queue.getOrNull(index), skin)
    var lastColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(target) {
        if (target.isNotEmpty()) lastColors = target
    }
    val shown = target.ifEmpty { lastColors }

    // 用 animateColorAsState 而不是自己拿 Animatable：它内部从**当前值**开始补间，
    // 换歌那一帧不会先按新色满不透明度画一次（snapTo(0f) 要等当前帧提交后才生效）。
    //
    // 色卡还没算出来时 shown 是空的，落到 skin.bg —— 等于「画了但看不见」，
    // 不会先给一版和封面无关的假色。
    val fallback = skin.bg
    val spec: AnimationSpec<Color> = tween(fadeMs.coerceIn(GLOW_FADE_RANGE), easing = LinearEasing)
    val c0 by animateColorAsState(shown.getOrElse(0) { fallback }, spec, label = "glow0")
    val c1 by animateColorAsState(shown.getOrElse(1) { fallback }, spec, label = "glow1")
    val c2 by animateColorAsState(shown.getOrElse(2) { fallback }, spec, label = "glow2")
    return remember(c0, c1, c2) { listOf(c0, c1, c2) }
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
 * 一份晕染素材：三个取样色 + **已经烘好**的渐变位图。
 *
 * 位图提前烘好是刻意的：绘制阶段只做一次 `drawImage`，不必重新填 8192 个像素、
 * 也不必每帧新建 Bitmap。过渡靠**组合期**把颜色插值好再烘 ——
 * 所以整页永远只有这一层素材（原因见 [FullPlayer] 里那段说明）。
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
    strength: Float
) {
    val colors = frame.colors
    val image = frame.image
    if (colors.isEmpty() || strength <= 0f) return
    val w = cover.width
    val h = cover.height
    val cx = cover.center.x
    val cy = cover.center.y

    when (mode) {
                    // ── 只从下边缘向下晕 ──
                    GlowMode.BOTTOM -> drawGlowImage(
                        image = image,
                        top = cy - h / 2f + h * GLOW_TOP - GLOW_BOTTOM_LIFT.toPx(),
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

/**
 * 把位图按目标矩形拉伸绘制 —— 颜色和浓度都已经烘在位图里，一次就画完了。
 *
 * 没有不透明度参数：跨页过渡不在这里做，而是在组合期就把相邻两页的颜色插值好
 * （见 [blendGlowColors]）。两层各按 `1-t` / `t` 叠加**不是**等价的交叉淡化 ——
 * 两次 src-over 之后中点会比两端淡一成半，底色透出来就是「背景闪一下」。
 */
private fun DrawScope.drawGlowImage(
    image: ImageBitmap?,
    top: Float,
    height: Float
) {
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
    prefs: Prefs,
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
                                sizePx = PLAYER_COVER_PX,
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
                        gap = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    BottomRow(
                        isFavorite, volume, onToggleFavorite, onVolume,
                        onOpenCover, onOpenEqualizer, eqEnabled
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
                                sizePx = PLAYER_COVER_PX,
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
                    gap = 14.dp
                )
                Spacer(Modifier.height(14.dp))
                BottomRow(
                    isFavorite, volume, onToggleFavorite, onVolume,
                    onOpenCover, onOpenEqualizer, eqEnabled
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
