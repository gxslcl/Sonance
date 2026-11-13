package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.MiniProgressStyle
import com.sonance.player.ui.theme.MiniSwipeStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/* ============================================================
 * 传输控制（上一首 / 播放 / 下一首 / 随机 / 循环）
 * ============================================================ */

@Composable
fun TransportControls(
    isPlaying: Boolean,
    /**
     * 三态播放模式。**不再是两个独立开关**（随机 / 循环）——
     * 它们能组合出"随机 + 单曲循环"这种自相矛盾的状态，见 [PlayMode] 的说明。
     */
    playMode: PlayMode,
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 传了就在最后多一个「播放队列」按钮。
     *
     * 播放页要它（用户要求把队列入口放在控制行上）；底栏和正在播放面板里
     * 本来就有队列入口，传 null 免得同一屏出现两个一样的按钮。
     */
    onOpenQueue: (() -> Unit)? = null,
    ctrlSize: Dp = 36.dp,
    ctrlIcon: Dp = 18.dp,
    playSize: Dp = 52.dp,
    playIcon: Dp = 22.dp,
    gap: Dp = 6.dp,
    playButtonPalette: PlayButtonPalette? = null,
    playButtonPaletteProvider: (() -> PlayButtonPalette)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
        // 模式按钮：参考样机是「纯图标 + 主题色」，没有圆形背景块；
        // 切换时图标缩到 0.65 并淡出，再淡入 —— 对应样机的 switching 类
        PlayModeCtrlButton(
            playMode = playMode,
            onClick = onCyclePlayMode,
            size = ctrlSize,
            iconSize = ctrlIcon
        )
        CtrlButton(
            icon = SonanceIcons.Prev,
            contentDescription = "上一首",
            onClick = onPrev,
            size = ctrlSize,
            iconSize = ctrlIcon
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onPlayPause,
            size = playSize,
            iconSize = playIcon,
            palette = playButtonPalette,
            paletteProvider = playButtonPaletteProvider
        )
        CtrlButton(
            icon = SonanceIcons.Next,
            contentDescription = "下一首",
            onClick = onNext,
            size = ctrlSize,
            iconSize = ctrlIcon
        )
        if (onOpenQueue != null) {
            CtrlButton(
                icon = SonanceIcons.Queue,
                contentDescription = "播放队列",
                onClick = onOpenQueue,
                size = ctrlSize,
                iconSize = ctrlIcon
            )
        }
    }
}

/**
 * 播放模式按钮：纯图标、主题色、无外框。
 *
 * 切换模式时图标先缩小到 0.65 并淡出，再淡入新图标 —— 参考样机的
 * `switching` 过渡（140ms）。没有背景块是为了和样机一致：整排控制键
 * 只有中心的水滴按钮带「面」，两侧都是线条 / 图形。
 */
@Composable
private fun PlayModeCtrlButton(
    playMode: PlayMode,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp
) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = playMode,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.65f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.65f))
            },
            label = "playModeIcon"
        ) { mode ->
            androidx.compose.material3.Icon(
                imageVector = when (mode) {
                    PlayMode.SHUFFLE -> SonanceIcons.Shuffle
                    PlayMode.REPEAT_ALL -> SonanceIcons.Repeat
                    PlayMode.REPEAT_ONE -> SonanceIcons.RepeatOne
                },
                contentDescription = mode.label,
                tint = skin.accent,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

private fun progressFraction(state: PlayerUiState): Float {
    if (state.durationMs <= 0L) return 0f
    return (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * 仅在卡片外缘绘制柔和外投影：内区用 [ClipOp.Difference] 挖空，
 * 避免半透明玻璃卡片在正常显示或滑出淡化时透出底层硬件阴影的矩形暗区。
 */
private fun Modifier.outerCardShadow(cornerRadius: Dp): Modifier = drawBehind {
    val r = cornerRadius.toPx()
    val cardPath = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r))
    }
    clipPath(cardPath, ClipOp.Difference) {
        for (i in 1..4) {
            val spread = (i * 2.5f).dp.toPx()
            val yOffset = (i * 0.9f).dp.toPx()
            val alpha = 0.085f * (5 - i) / 4f
            drawRoundRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(-spread, -spread + yOffset),
                size = Size(size.width + spread * 2f, size.height + spread * 2f),
                cornerRadius = CornerRadius(r + spread, r + spread)
            )
        }
    }
}

/* ============================================================
 * 迷你播放条（手机 / 中等宽度）
 * ============================================================ */

/**
 * 列表页底部那个「正在播放」。
 *
 * 两种形态（[MiniPlayerStyle]），设置里可切：
 *  - [MiniPlayerStyle.CLASSIC]：贴底通栏，顶部一条进度线 —— 原来那副样子；
 *  - [MiniPlayerStyle.FLOAT]：离屏边有间距的**大圆角悬浮卡片**，带投影，
 *    可以叠毛玻璃 / 液态玻璃（[glass]），底色实不实由 [cardOpacity] 决定。
 *
 * 悬浮时它**叠在内容上**（由调用方决定要不要给内容留出底边距），
 * 经典时是内容下面的一条，两种都不改内容自身的高度。
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    song: Song?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
    style: MiniPlayerStyle = MiniPlayerStyle.CLASSIC,
    glass: GlassStyle = GlassStyle.OFF,
    glassBlur: Int = 0,
    glassVisibility: Int = 0,
    cardOpacity: Int = 100,
    /** 悬浮卡片上进度用环还是用线（见 [MiniProgressStyle]） */
    progressStyle: MiniProgressStyle = MiniProgressStyle.RING,
    /** 悬浮卡片左右滑动切歌动效（整卡滑动 / 槽内滑动） */
    swipeStyle: MiniSwipeStyle = MiniSwipeStyle.WHOLE_CARD,
    /** 左滑（下一首）阈值，百分比卡宽 */
    swipeLeftPct: Int = 20,
    /** 右滑（上一首）阈值。默认比左滑低：右手持机时"往回够"划不满同样的距离 */
    swipeRightPct: Int = 12,
    /** 卡片高度倍数（1.0 = 现在） */
    heightScale: Float = 1f,
    /** 卡片里封面倍数（1.0 = 现在） */
    coverScale: Float = 1f
) {
    val skin = LocalSkin.current
    val frac = progressFraction(state)
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }
    // 内容要放在 Row 里（中间那列文字要吃掉剩余宽度），所以带上 RowScope
    val body: @Composable RowScope.() -> Unit = {
        MiniPlayerBody(
            state = state,
            song = song,
            coverUri = coverUri,
            frac = frac,
            coverScale = coverScale,
            // 环形进度只给悬浮卡片（经典贴底那条通栏本来就只有线），
            // 具体显示环还是线由用户在设置里定，见 MiniProgressStyle
            progressRing = style == MiniPlayerStyle.FLOAT && progressStyle.showsRing,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext
        )
    }

    when (style) {
        MiniPlayerStyle.CLASSIC -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(skin.surface2)
            ) {
                // 顶部进度线
                MiniProgressLine(frac = frac, thickness = 2.dp, rounded = false)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { body() }
            }
        }

        MiniPlayerStyle.FLOAT -> {
            val shape = RoundedCornerShape(20.dp)

            // ---- 轮播式左右滑动切歌 ----
            //
            // 手感三条铁律（上一版踩过的坑）：
            //  ① **1:1 跟手**，不加阻尼。阻尼会让手指走一半卡片只走 0.6 倍，
            //     这就是"慢半拍"的来源。
            //  ② **任何动画都可打断**。上一版用 `busy` 锁住手势，连划要等上一段动画
            //     跑完（390ms）才响应，所以连续滑动特别粘。
            //     现在新的一次按下会直接 cancel 掉正在跑的动画，从当前位置接着走。
            //  ③ **不用弹簧**。Spring 一定会过冲（那是它的天性），用户明确说不要回弹；
            //     全部换成 `tween` + ease-out 曲线，到位就停。
            //
            // 换歌用一个连续运动完成：滑出 → 换歌 → 从另一侧滑入。
            // 两段同向、中间那一帧卡片在屏外（看不见），所以没有跳变。
            var dragX by remember { mutableFloatStateOf(0f) }
            var cardW by remember { mutableFloatStateOf(1f) }
            val scope = rememberCoroutineScope()
            // 正在跑的位移动画。新手势按下时取消它 —— 这就是"可打断"
            val animJob = remember { arrayOfNulls<Job>(1) }
            // pointerInput(Unit) 的 lambda **只在第一次组合时创建**，里面读到的
            // 外部变量会被永久冻结 —— 阈值必须走 rememberUpdatedState 取最新一份，
            // 否则设置里怎么调都不生效（这就是"阈值设置无效"的根因）。
            val nextNow = rememberUpdatedState(onNext)
            val prevNow = rememberUpdatedState(onPrev)
            val leftPctNow = rememberUpdatedState(swipeLeftPct)
            val rightPctNow = rememberUpdatedState(swipeRightPct)
            val isSlot = swipeStyle == MiniSwipeStyle.INNER_SLOT

            // 外层容器永远稳住布局占位、触摸坐标与（槽内模式下的）微暗底座，避免拖拽时触摸区域跟着偏移；
            // 注意：
            // 1) 槽内底座是半透明表面，不能叠 Modifier.shadow —— Android 硬件阴影会在半透明底下面透出一块矩形暗斑；
            // 2) clickable 不能挂在未裁圆角的外层上，否则左半边无子按钮区起滑时会触发默认矩形按压涟漪闪框。
            Box(
                modifier = modifier
                    // 16dp 与列表高亮框、搜索框同一条边线 —— 12dp 的时候
                    // 三个"左右边界"是三套数，纵着看下来是歪的
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .onSizeChanged { cardW = it.width.toFloat().coerceAtLeast(1f) }
                    .let { m ->
                        if (isSlot) {
                            m.clip(shape)
                                .background(skin.surface2.copy(alpha = (cardOpacity / 100f) * 0.36f))
                                .border(1.dp, skin.text.copy(alpha = 0.09f), shape)
                        } else {
                            m
                        }
                    }
                    .pointerInput(Unit) {
                        // 位移动画：总是先停掉上一条，保证永远只有一条在跑
                        fun slideTo(target: Float, spec: AnimationSpec<Float>) {
                            animJob[0]?.cancel()
                            val from = dragX
                            animJob[0] = scope.launch {
                                animate(from, target, animationSpec = spec) { v, _ -> dragX = v }
                                animJob[0] = null
                            }
                        }

                        detectHorizontalDragGestures(
                            // 手指一落下就接管：正在跑的归位/入场动画立刻停住，
                            // 从卡片当前的位置继续跟手（**不清零** —— 清零就是"弹一下"）
                            onDragStart = {
                                animJob[0]?.cancel()
                                animJob[0] = null
                            },
                            onDragEnd = {
                                val w = cardW
                                // 左右阈值分开：右手持机时左滑顺手、右滑要往回够，
                                // 同一个百分比会让右滑经常划不满
                                val pct = if (dragX < 0) leftPctNow.value else rightPctNow.value
                                val threshold = w * pct / 100f
                                val dir = when {
                                    dragX <= -threshold -> -1
                                    dragX >= threshold -> 1
                                    else -> 0
                                }
                                if (dir == 0) {
                                    // 没过阈值：快速平滑归位（ease-out，无过冲）
                                    slideTo(0f, tween(140, easing = FastOutSlowInEasing))
                                } else {
                                    // ① 滑出：100ms，只走剩下这点距离
                                    animJob[0]?.cancel()
                                    val outFrom = dragX
                                    val out = scope.launch {
                                        animate(
                                            outFrom, dir * w * 1.2f,
                                            animationSpec = tween(100, easing = FastOutLinearInEasing)
                                        ) { v, _ -> dragX = v }
                                    }
                                    animJob[0] = out
                                    // ② 精确等①跑完（不用写死的 delay），再换歌接力滑入
                                    scope.launch {
                                        out.join()
                                        // 被新手势打断了就别换歌 —— 用户又抓住卡片了
                                        if (out.isCancelled) return@launch
                                        if (dir < 0) nextNow.value() else prevNow.value()
                                        // 此刻卡片在屏外：直接摆到另一侧屏外，看不见跳变
                                        dragX = -dir * w * 1.2f
                                        val inJob = scope.launch {
                                            animate(
                                                -dir * w * 1.2f, 0f,
                                                animationSpec = tween(170, easing = FastOutSlowInEasing)
                                            ) { v, _ -> dragX = v }
                                        }
                                        animJob[0] = inJob
                                    }
                                }
                            },
                            onDragCancel = { slideTo(0f, tween(140, easing = FastOutSlowInEasing)) }
                        ) { change, delta ->
                            change.consume()
                            // 1:1 跟手；只夹住"别拖出屏外太远"，不做阻尼
                            dragX = (dragX + delta).coerceIn(-cardW * 1.2f, cardW * 1.2f)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationX = dragX
                            val ratio = (kotlin.math.abs(dragX) / cardW).coerceIn(0f, 1.2f)
                            // 位移越大越淡：槽内模式下更快渐隐融入底座，整卡模式下适度淡出保持卡片完整实体感
                            alpha = if (isSlot) {
                                (1f - ratio * 1.05f).coerceIn(0f, 1f)
                            } else {
                                1f - (ratio * 0.72f).coerceIn(0f, 0.72f)
                            }
                        }
                        // 整卡模式仅在卡片外缘画柔和投影，杜绝半透明玻璃卡片透出系统阴影矩形底；槽内模式不叠加投影
                        .let { m -> if (!isSlot) m.outerCardShadow(20.dp) else m }
                        .clip(shape)
                        .background(skin.surface2.copy(alpha = cardOpacity / 100f))
                        // 内描边：玻璃板的"切割线"。**从主题文字色派生**而不是写死白色 ——
                        // 浅色主题下白描边会直接消失，深色主题下它就是那条立体高光
                        .border(1.dp, skin.text.copy(alpha = if (isSlot) 0.14f else 0.10f), shape)
                        // 点击挂在已裁圆角的内层卡片且关闭按压涟漪，避免起滑手势闪出矩形按压框
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpen
                        )
                ) {
                    // 玻璃：卡片自己的封面糊开铺底。压暗幅度刻意比整页小
                    // （scrimSpread 0.10），压太狠这块玻璃就看不出是玻璃了。
                    // edgeHighlight 打开：上边缘一条折射亮线，滑过深色列表项时
                    // 卡片不至于"融"进背景里。
                    GlassBackdrop(
                        art = miniGlassArt(state, coverUri),
                        style = glass,
                        blurDp = glassBlur,
                        visibility = glassVisibility,
                        modifier = Modifier.matchParentSize(),
                        scrimColor = skin.surface2,
                        edgeHighlight = true,
                        scrimSpread = 0.10f
                    )
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 12.dp,
                                    // 高度倍数只加"上下留白"，不动内部元素 ——
                                    // 这样调高度不会把字挤小、也不会把按钮撑变形
                                    vertical = 10.dp * heightScale
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) { body() }
                        // 进度线收进卡片下沿。设成"只用圆环"时这里不画 ——
                        // 同一个进度说两遍，看着就是重复
                        if (progressStyle.showsBar) {
                            MiniProgressLine(frac = frac, thickness = 2.5.dp, rounded = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniProgressLine(frac: Float, thickness: Dp, rounded: Boolean) {
    val skin = LocalSkin.current
    // 圆角只在悬浮卡片里用；经典模式那条线本来就是通栏的，
    // 圆角反而让它两头缩进去
    val shape = if (rounded) RoundedCornerShape(50) else RoundedCornerShape(0)
    Box(
        Modifier
            .fillMaxWidth()
            .height(thickness)
            .background(if (rounded) Color.Transparent else skin.surface3)
    ) {
        if (frac > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(thickness)
                    .clip(shape)
                    .background(skin.accent)
            )
        }
    }
}

/**
 * 悬浮卡片玻璃层用的封面。
 *
 * 和列表里那张缩略图分开加载：玻璃要的是一张够大、糊开后还认得出的图，
 * 复用 200px 的缩略图放大再糊会全是色块。只在卡片真的叠玻璃时才加载。
 */
@Composable
private fun miniGlassArt(
    state: PlayerUiState,
    coverUri: android.net.Uri?
): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    var art by remember(state.mediaId, state.albumId, coverUri) {
        mutableStateOf(ArtworkLoader.peek(state.albumId, 320, coverUri, OnlineCover.keyOf(state.title, state.artist)))
    }
    LaunchedEffect(state.albumId, coverUri, state.title, state.artist) {
        art = ArtworkLoader.load(
            context = context,
            albumId = state.albumId,
            sizePx = 320,
            sourceUri = coverUri,
            coverKey = OnlineCover.keyOf(state.title, state.artist)
        ) ?: ArtworkLoader.online(state.mediaId)
    }
    return art
}

@Composable
private fun RowScope.MiniPlayerBody(
    state: PlayerUiState,
    song: Song?,
    coverUri: android.net.Uri?,
    /** 播放进度 0~1，环形进度用。CLASSIC 不用它 */
    frac: Float = 0f,
    /** 封面大小倍数（设置里可调） */
    coverScale: Float = 1f,
    /** 播放键外圈套环形进度（悬浮玻璃卡片专用） */
    progressRing: Boolean = false,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val skin = LocalSkin.current
    val palette = rememberPlayerControlPalette(state, song)
    CoverArt(
        albumId = state.albumId,
        seed = state.title + state.artist,
        modifier = Modifier.size(42.dp * coverScale),
        cornerRadius = 7.dp * coverScale,
        // 解码尺寸跟着放大，不然 2 倍下是拉糊的 200px
        sizePx = (200 * coverScale).toInt(),
        sourceUri = coverUri,
        songId = state.mediaId,
        coverKey = OnlineCover.keyOf(state.title, state.artist)
    )
    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(
            text = state.title.ifBlank { song?.title ?: "未在播放" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = state.artist.ifBlank { song?.artist ?: "—" },
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    CtrlButton(
        icon = SonanceIcons.Prev,
        contentDescription = "上一首",
        onClick = onPrev,
        size = 36.dp,
        iconSize = 17.dp
    )
    if (progressRing) {
        PlayPauseRing(
            frac = frac,
            isPlaying = state.isPlaybackActive,
            onClick = onPlayPause,
            palette = palette
        )
    } else {
        PlayPauseButton(
            isPlaying = state.isPlaybackActive,
            onClick = onPlayPause,
            size = 40.dp,
            iconSize = 18.dp,
            palette = palette
        )
    }
    CtrlButton(
        icon = SonanceIcons.Next,
        contentDescription = "下一首",
        onClick = onNext,
        size = 36.dp,
        iconSize = 17.dp
    )
}

/**
 * 套着环形进度的播放键（悬浮玻璃卡片用）。
 *
 * 进度贴在卡片底边的那种线，在 20dp 大圆角 + 液态玻璃透出深色列表时
 * 几乎不可见 —— 环绕着播放键转，玻璃卡片上才"看得见"进度。
 *
 * 环的直径与原来 40dp 的按钮完全一致，键体缩到 32dp 让出环的厚度：
 * 行高不变，卡片不会因此变高。
 */
@Composable
private fun PlayPauseRing(
    frac: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    palette: PlayButtonPalette
) {
    val skin = LocalSkin.current
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val d = size.minDimension - stroke
            val tl = Offset(stroke / 2f, stroke / 2f)
            // 轨道：主题文字色的低透明版 —— 深浅主题下都可见，又不会抢过内容
            drawArc(
                color = skin.text.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = tl,
                size = Size(d, d),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (frac > 0f) {
                drawArc(
                    color = skin.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * frac.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = tl,
                    size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onClick,
            size = 32.dp,
            iconSize = 14.dp,
            palette = palette
        )
    }
}

/* ============================================================
 * 底部播放条（平板 / 横屏宽屏）
 * ============================================================ */

@Composable
fun PlayerBar(
    state: PlayerUiState,
    song: Song?,
    onOpen: () -> Unit,
    /** 三态播放模式切换（随机 / 列表循环 / 单曲循环）。原来是 onShuffle + onRepeat 两个 */
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 78.dp
) {
    val palette = rememberPlayerControlPalette(state, song)
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }

    val skin = LocalSkin.current
    val frac = progressFraction(state)
    val duration = if (state.durationMs > 0) state.durationMs else (song?.durationMs ?: 0L)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(skin.surface2)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .width(210.dp)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverArt(
                albumId = state.albumId,
                seed = state.title + state.artist,
                modifier = Modifier.size(46.dp),
                cornerRadius = 7.dp,
                sizePx = 220,
                sourceUri = coverUri,
                songId = state.mediaId,
                coverKey = OnlineCover.keyOf(state.title, state.artist)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "未在播放" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.artist.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TransportControls(
                isPlaying = state.isPlaybackActive,
                playMode = PlayMode.of(state.shuffle, state.repeat),
                onCyclePlayMode = onCyclePlayMode,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext,
                // 这一行右侧本来就有队列图标，这里不重复放
                ctrlSize = 30.dp,
                ctrlIcon = 15.dp,
                playSize = 36.dp,
                playIcon = 16.dp,
                gap = 4.dp,
                playButtonPalette = palette
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TimeLabel(formatDuration(state.positionMs))
                SonanceSlider(
                    value = frac,
                    onValueChange = { f -> onSeek((f * duration).toLong()) },
                    modifier = Modifier.weight(1f),
                    trackHeight = 4.dp,
                    thumbSize = 11.dp
                )
                TimeLabel(formatDuration(duration))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VolumeSlider(
                volume = state.userVolume,
                onVolumeChange = onVolume,
                modifier = Modifier.width(118.dp)
            )
            IconAction(
                icon = SonanceIcons.Queue,
                contentDescription = "播放队列",
                onClick = onOpenQueue,
                iconSize = 18.dp,
                tint = skin.textMuted
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalSkin.current.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(38.dp)
    )
}

/* ============================================================
 * 正在播放侧栏（平板 / 桌面宽屏）
 * ============================================================ */

/**
 * 右侧面板的三层：封面 / 歌词 / 律动柱。
 * 顺序与手机播放页 `PlayerLayer` 一致，横向滑动时环形轮转。
 */
private const val PANEL_LAYER_COVER = 0
private const val PANEL_LAYER_LYRICS = 1
private const val PANEL_LAYER_VISUALIZER = 2
private const val PANEL_LAYER_COUNT = 3

@Composable
fun NowPlayingPanel(
    state: PlayerUiState,
    song: Song?,
    /** 三态播放模式切换（随机 / 列表循环 / 单曲循环）。原来是 onShuffle + onRepeat 两个 */
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onFavorite: () -> Unit,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 半透明面板：沉浸皮肤下让整页的封面玻璃背景透出来 ——
     * 左侧列表已经是这个观感，右侧播放区原来是实心色，两边接不上。
     */
    translucent: Boolean = false,
    /** 面板内**上滑** = 下一首（和手机播放页同一方向语义） */
    onSwipeUp: (() -> Unit)? = null,
    /** 面板内**下滑** = 上一首 */
    onSwipeDown: (() -> Unit)? = null
) {
    val palette = rememberPlayerControlPalette(state, song)
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }

    val skin = LocalSkin.current
    val frac = progressFraction(state)
    val duration = if (state.durationMs > 0) state.durationMs else (song?.durationMs ?: 0L)
    // 刻意不用 by 委托：把动画值读取推迟到 layer 阶段，
    // 否则这个常驻面板会每帧重组一次
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val dotAlpha = pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "dot"
    )

    // ---- 面板内手势：上下切歌 / 左右切层 ----
    //
    // 手机上的三层是 HorizontalPager（跟手）。这里是只读预览面板，需求明确不要跟手位移，
    // 所以改成「主轴判定 + 松手过阈值触发 + AnimatedContent 过渡」：
    //   纵向：沿用原来的 56dp 阈值，上滑下一首、下滑上一首；
    //   横向：同样 56dp，左滑下一层、右滑上一层，三层环形循环。
    // 判定前**不消费**事件（子级 Slider 仍能正常拖动）；判定后只消费自己的主轴方向，
    // 纵向不会切层、横向不会切歌。手势只挂在本面板上，左侧列表完全不受影响。
    val swipeThreshold = rememberUpdatedState(with(LocalDensity.current) { 56.dp.toPx() })
    val swipeUpNow = rememberUpdatedState(onSwipeUp)
    val swipeDownNow = rememberUpdatedState(onSwipeDown)

    // 右侧面板自己的当前层：**不和手机 FullPlayer 的 layerPager 共享**。
    // 右侧是常驻预览，手机全屏播放页可能根本没打开；一旦共享，任一侧切层都会
    // 把另一侧的状态劫持走，而两边的宽度/排版又完全不同，恢复时还得追着改。
    var layer by remember { mutableIntStateOf(PANEL_LAYER_COVER) }
    // 过渡方向：+1 新内容从右侧进（手指左滑），-1 从左侧进
    var slideDir by remember { mutableIntStateOf(1) }

    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (translucent) skin.surface.copy(alpha = 0.46f) else skin.surface)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var axis = 0 // 0 = 未判定，1 = 横向，2 = 纵向
                    var dx = 0f
                    var dy = 0f
                    var aborted = false

                    // 主轴判定：过 touch slop 才锁定方向；期间交给子级优先处理
                    while (axis == 0) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed || c.isConsumed) {
                            aborted = true
                            break
                        }
                        val d = c.positionChange()
                        dx += d.x
                        dy += d.y
                        if (abs(dx) > slop || abs(dy) > slop) {
                            axis = if (abs(dx) > abs(dy)) 1 else 2
                            // 和原实现一致：阈值只算过 slop 之后的位移
                            if (axis == 1) dx = 0f else dy = 0f
                        }
                    }

                    if (!aborted && axis != 0) {
                        // 主轴已锁定：后续位移全部归自己，并消费掉，避免和任何子级/祖先抢
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed) break
                            val d = c.positionChange()
                            if (axis == 1) dx += d.x else dy += d.y
                            c.consume()
                        }
                        val t = swipeThreshold.value
                        if (axis == 1) {
                            // 左滑 → 下一层；右滑 → 上一层（和手机 pager 的方向语义一致）
                            if (dx <= -t) {
                                slideDir = 1
                                layer = (layer + 1) % PANEL_LAYER_COUNT
                            } else if (dx >= t) {
                                slideDir = -1
                                layer = (layer + PANEL_LAYER_COUNT - 1) % PANEL_LAYER_COUNT
                            }
                        } else {
                            if (dy <= -t) swipeUpNow.value?.invoke()
                            else if (dy >= t) swipeDownNow.value?.invoke()
                        }
                    }
                }
            }
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (state.isPlaying) skin.accent else skin.textMuted)
                    .graphicsLayer { alpha = if (state.isPlaying) dotAlpha.value else 1f }
            )
            Text(
                text = if (state.isPlaying) "正在播放" else "已暂停",
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent
            )
            Spacer(Modifier.weight(1f))
            // 三层指示：只用小圆点，**不可点击**（面板内不提供任何设置/切换入口）
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(PANEL_LAYER_COUNT) { i ->
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == layer) skin.accent
                                else skin.textMuted.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }

        // 三层（封面 / 歌词 / 律动柱）复用封面原来那块区域。
        // 过渡是淡入淡出 + 轻微位移，不做跟手位移预览（和右侧切歌同一手感）。
        AnimatedContent(
            targetState = layer,
            transitionSpec = {
                val dir = slideDir
                (
                    fadeIn(tween(220, delayMillis = 40)) +
                        slideInHorizontally(tween(260)) { w -> dir * w / 10 }
                    ).togetherWith(
                        fadeOut(tween(150)) +
                            slideOutHorizontally(tween(200)) { w -> -dir * w / 10 }
                    )
            },
            label = "panelLayer",
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clipToBounds()
        ) { l ->
            when (l) {
                PANEL_LAYER_LYRICS -> if (song != null) {
                    // 只读歌词层：同一套解析/渲染（含逐字高亮），没有任何设置入口
                    PanelLyrics(
                        song = song,
                        positionMs = state.positionMs,
                        prefs = prefs,
                        isPlaying = state.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无可展示的内容",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                    }
                }

                PANEL_LAYER_VISUALIZER -> PanelVisualizer(
                    isPlaying = state.isPlaying,
                    prefs = prefs,
                    modifier = Modifier.fillMaxSize()
                )

                else -> androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                    CoverArt(
                        albumId = state.albumId,
                        seed = state.title + state.artist,
                        modifier = Modifier
                            .size(side)
                            .clip(RoundedCornerShape(14.dp)),
                        cornerRadius = 14.dp,
                        sizePx = 800,
                        sourceUri = coverUri,
                        songId = state.mediaId,
                        coverKey = OnlineCover.keyOf(state.title, state.artist)
                    )
                }
            }
        }

        Text(
            text = state.title.ifBlank { "未在播放" },
            style = MaterialTheme.typography.headlineSmall,
            color = skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.artist.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        SonanceSlider(
            value = frac,
            onValueChange = { f -> onSeek((f * duration).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 4.dp,
            thumbSize = 12.dp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }

        Spacer(Modifier.height(16.dp))

        TransportControls(
            isPlaying = state.isPlaybackActive,
            playMode = PlayMode.of(state.shuffle, state.repeat),
            onCyclePlayMode = onCyclePlayMode,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            playButtonPalette = palette
            // 下面那行已经有队列图标了，这里不重复放
        )

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconAction(
                icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                contentDescription = "收藏",
                onClick = onFavorite,
                iconSize = 18.dp,
                tint = if (isFavorite) skin.accent else skin.textMuted
            )
            VolumeSlider(
                volume = state.userVolume,
                onVolumeChange = onVolume,
                modifier = Modifier.weight(1f)
            )
            IconAction(
                icon = SonanceIcons.Queue,
                contentDescription = "播放队列",
                onClick = onOpenQueue,
                iconSize = 18.dp,
                tint = skin.textMuted
            )
        }
    }
}

private fun Modifier.squareAspect(): Modifier = this.then(Modifier.aspectRatio(1f))
