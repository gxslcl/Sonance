package com.sonance.player.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.LaunchCoverSnapshot
import com.sonance.player.data.PlayButtonShape
import com.sonance.player.data.Prefs
import com.sonance.player.ui.theme.LocalMotionConfig
import com.sonance.player.ui.theme.LocalPrefs
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.pressSpring
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 矮屏（手机横屏等）紧凑布局开关 */
val LocalDenseLayout = androidx.compose.runtime.staticCompositionLocalOf { false }

/* ============================================================
 * 封面
 * ============================================================ */

@Composable
fun CoverArt(
    albumId: Long,
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    sizePx: Int = 400,
    showPlaceholderMark: Boolean = true,
    sourceUri: android.net.Uri? = null,
    /** 传入后即可享受「本地没封面时用在线补的封面」 */
    songId: Long = -1L,
    /**
     * 这首歌的在线封面缓存 key（`OnlineCover.keyOf(title, artist)`）。
     *
     * 传了它，本地（MediaStore / 内嵌）没有封面时会继续去读**已经下载落盘**的封面 ——
     * 重启后不播放也能直接显示，而不是先给占位图。传 null 就退回原来的行为。
     */
    coverKey: String? = null
) {
    val context = LocalContext.current
    val prefs = LocalPrefs.current ?: remember(context) { Prefs(context.applicationContext) }
    val coverRevision = ArtworkLoader.coverRevision(coverKey)
    val selectedRevision = ArtworkLoader.selectedRevision(songId)
    val resetVersion = ArtworkLoader.cacheResetVersion
    // 首帧就直接读内存缓存 —— 已加载过的封面必须立刻出现，不能先给占位图。
    // （load() 是 suspend 的，即使缓存命中也要走协程调度，等好几帧才赋值，
    //   表现就是"滚回来还要等半秒"。）
    // sizePx 也进 key：尺寸档变了要重新 peek，否则播放页会拿到列表留下的缩略图。
    // peekAny（不是 peek）：本尺寸档没现成的就先用缩略图档那份顶上，
    // 免得播放页在解码 900px 的那几百毫秒里先闪一张占位图。
    var bitmap by remember(albumId, sizePx, sourceUri, songId, coverKey, resetVersion) {
        // 内存缓存没有时回落到「上次播放封面」落盘快照：放进 bitmap（而非 selected），
        // 避免无自定义封面时 LaunchedEffect 里的 selected = null 把首帧快照清空闪回占位图
        mutableStateOf(
            ArtworkLoader.peekAny(albumId, sizePx, sourceUri, coverKey)
                ?: LaunchCoverSnapshot.peekCover(context, songId)
        )
    }
    var selected by remember(albumId, sizePx, sourceUri, songId, selectedRevision, resetVersion) {
        mutableStateOf(ArtworkLoader.peekSelected(albumId, sizePx, sourceUri, songId))
    }

    // 这里**故意不把 ArtworkLoader.paused 放进 key**：放进去的话，滚动开始和结束
    // 各会重启一次本 effect，可见的几十行同时重新发起加载 —— 那正是
    // "停下来还要等 0.5~1 秒"的成因。滚动中要不要降并发由 ArtworkLoader 内部决定
    // （见 withDecodeGate），界面这边只管发起。
    LaunchedEffect(albumId, sizePx, sourceUri, songId, coverKey, coverRevision, selectedRevision, resetVersion) {
        val legacyPinned = songId > 0L && !prefs.pinnedCoverUrl(songId).isNullOrBlank()
        val chosen = ArtworkLoader.loadSelected(context, albumId, sizePx, sourceUri, songId, coverKey, legacyPinned)
        if (chosen != null) {
            selected = chosen
            if (sizePx >= 512) {
                withContext(Dispatchers.IO) { LaunchCoverSnapshot.saveCover(context, songId, chosen) }
            }
            return@LaunchedEffect
        }
        selected = null
        // 缓存命中：同步取，不走协程；大图命中时若尚未落盘快照则补写一次（避免预取命中后跳过保存）
        ArtworkLoader.peek(albumId, sizePx, sourceUri, coverKey)?.let {
            bitmap = it
            if (sizePx >= 512 && LaunchCoverSnapshot.anySongId(context) != songId) {
                withContext(Dispatchers.IO) { LaunchCoverSnapshot.saveCover(context, songId, it) }
            }
            return@LaunchedEffect
        }
        if (albumId <= 0 && sourceUri == null && coverKey == null) return@LaunchedEffect
        // 只在拿到图时赋值：load() 返回 null 只代表"这次没取到"（可能因为这张
        // 确实没有封面），**不能**拿它去覆盖已有的图 —— 早先这么写会让
        // 滚动时封面闪回占位图。
        val loaded = ArtworkLoader.load(context, albumId, sizePx, sourceUri, coverKey)
        if (loaded != null) {
            bitmap = loaded
            if (sizePx >= 512) {
                withContext(Dispatchers.IO) { LaunchCoverSnapshot.saveCover(context, songId, loaded) }
            }
        } else {
            // 临时媒体服务/文件读失败不视为永久无图；可见行稍后重试一次。
            delay(2_800)
            ArtworkLoader.load(context, albumId, sizePx, sourceUri, coverKey)?.let { bitmap = it }
        }
    }

    // 读 SnapshotStateMap：在线封面补进来时这里会自动重组
    val online = ArtworkLoader.online(songId)

    Box(modifier.clip(RoundedCornerShape(cornerRadius))) {
        // ⚠️ 顺序是 `online ?: bitmap`，**不能反过来**。
        //
        // 本地/内嵌封面优先的话，用户「手动搜索封面」选中的那张永远不会生效 ——
        // 因为能用到这个功能的歌，基本都已经有本地封面了，`bitmap` 一直非空，
        // 于是提示"封面已更新"但画面纹丝不动。
        //
        // 反过来是安全的：`onlineCovers` 只在两种情况下有非空值 ——
        // ① `ensureCover` 自动补的，而它只在本地确实没有封面时才会去补；
        // ② `applyCover` 用户手选的，那就该盖过本地封面。
        // 值为 null（"确认没有"的哨兵）时 `online` 返回 null，自然回落到本地封面。
        val bmp = selected ?: online ?: bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            PlaceholderCover(seed, showPlaceholderMark, Modifier.matchParentSize())
        }
    }
}

/**
 * 没有内嵌封面时生成的占位图。
 *
 * 不是"一张灰色方块"：底色由 [Skin.gradientFor] 按种子做色相偏移，
 * 前景再按种子从 6 种几何母题里挑一种画出来。同一套皮肤下也能得到
 * 几百种可区分的组合，列表里不会看起来"全都一样"。
 */
@Composable
fun PlaceholderCover(seed: String, showMark: Boolean = true, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val colors = remember(seed, skin.id) { skin.gradientFor(seed) }
    val motif = remember(seed) { Skin.seedHash(seed) % 6 }
    val accent = remember(skin.id) { skin.accent }

    Box(modifier.background(Brush.linearGradient(colors))) {
        Canvas(Modifier.fillMaxSize()) {
            when (motif) {
                0 -> drawConcentric(accent)
                1 -> drawBarStack(accent)
                2 -> drawDiagonalBands(accent)
                3 -> drawRipple(accent)
                4 -> drawDotGrid(accent)
                else -> drawSunburst(accent)
            }
        }
        if (showMark) {
            // 中心的小圆点，保持和品牌图标一致的识别度
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.055f
                drawCircle(
                    color = accent.copy(alpha = 0.55f),
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawConcentric(accent: Color) {
    val c = Offset(size.width * 0.5f, size.height * 0.52f)
    val max = size.minDimension * 0.62f
    var i = 1
    while (i <= 4) {
        drawCircle(
            color = accent.copy(alpha = 0.06f + 0.05f * i),
            radius = max * i / 4f,
            center = c,
            style = Stroke(width = size.minDimension * 0.018f)
        )
        i++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarStack(accent: Color) {
    val n = 7
    val gap = size.width * 0.045f
    val w = (size.width - gap * (n + 1)) / n
    val base = size.height * 0.80f
    var i = 0
    while (i < n) {
        val t = (i + 1).toFloat() / n
        val h = size.height * (0.18f + 0.44f * kotlin.math.sin(t * 2.1f))
        val x = gap + i * (w + gap)
        drawRoundRect(
            color = accent.copy(alpha = 0.16f + 0.10f * t),
            topLeft = Offset(x, base - h),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.35f, w * 0.35f)
        )
        i++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDiagonalBands(accent: Color) {
    val step = size.width * 0.26f
    val sw = size.width * 0.085f
    var x = -size.height
    var k = 0
    while (x < size.width) {
        drawLine(
            color = accent.copy(alpha = if (k % 2 == 0) 0.14f else 0.07f),
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = sw
        )
        x += step
        k++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRipple(accent: Color) {
    val cy = size.height * 0.62f
    val path = Path()
    val steps = 48
    var i = 0
    while (i <= steps) {
        val t = i.toFloat() / steps
        val y = cy + kotlin.math.sin(t * kotlin.math.PI.toFloat() * 3f) * size.height * 0.10f
        if (i == 0) path.moveTo(0f, y) else path.lineTo(t * size.width, y)
        i++
    }
    drawPath(
        path,
        color = accent.copy(alpha = 0.22f),
        style = Stroke(width = size.minDimension * 0.030f, cap = StrokeCap.Round)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDotGrid(accent: Color) {
    val n = 5
    val cell = size.width / n
    var r = 0
    while (r < n) {
        var c = 0
        while (c < n) {
            val d = ((r * 3 + c * 7) % 5) / 5f
            drawCircle(
                color = accent.copy(alpha = 0.08f + 0.14f * d),
                radius = cell * (0.06f + 0.10f * d),
                center = Offset(cell * (c + 0.5f), cell * (r + 0.5f))
            )
            c++
        }
        r++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSunburst(accent: Color) {
    val c = Offset(size.width * 0.5f, size.height * 0.86f)
    val r = size.width * 0.78f
    var i = 0
    val n = 9
    while (i < n) {
        val a0 = 180f + i * (180f / n)
        val a1 = 180f + (i + 1) * (180f / n) - 3f
        val path = Path().apply {
            moveTo(c.x, c.y)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r),
                startAngleDegrees = a0,
                sweepAngleDegrees = a1 - a0,
                forceMoveTo = false
            )
            close()
        }
        drawPath(path, accent.copy(alpha = if (i % 2 == 0) 0.15f else 0.07f))
        i++
    }
}

/* ============================================================
 * 按钮
 * ============================================================ */

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tint: Color = LocalSkin.current.textDim,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(iconSize + 16.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 圆形控制按钮，可高亮表示激活。
 *
 * [highlight] 为 false 时不画圆形底色 —— 参考样机里的模式 / 跳过按钮都是
 * 「纯图标、无外框」，只有激活色（tint）变化，没有背景块。
 */
@Composable
fun CtrlButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    active: Boolean = false,
    tint: Color = LocalSkin.current.textDim,
    highlight: Boolean = true
) {
    val skin = LocalSkin.current
    val motion = LocalMotionConfig.current
    Box(
        modifier = modifier
            .pressSpring(enabled = motion.touchSpringFeedback, pressedScale = 0.90f)
            .size(size)
            .clip(CircleShape)
            .background(if (highlight && active) skin.accentGlow else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) skin.accent else tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 液态水滴播放/暂停键（参考样机样式）。
 *
 * 结构（从外到内）：
 * - 光晕：径向渐变软边光斑（不用 blur，兼容低版本），播放时呼吸律动，
 *   暂停时保留一圈微弱底光；设置里可整体关掉；
 * - 水滴本体：cyan→indigo→rose 的 135° 线性渐变圆，播放时渐变缓慢旋转 +
 *   整体轻微呼吸，按下果冻缩放（0.88）；
 * - 玻璃高光：左上角一道固定白色高光；
 * - 图标：三种样式可切（经典 / 液态三杆弹簧变形 / 极细细线），
 *   大小可在设置里缩放（60%~170%）；
 * - 触感：点击触发系统震动反馈（可在设置关闭）；
 *   点击瞬间还有一圈向外扩散的水波纹。
 *
 * 外占位尺寸沿用 [size]，布局不受影响。
 */
private val DefaultPlayGradient = listOf(Color(0xFF38BDF8), Color(0xFF6366F1), Color(0xFFEC4899))

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 22.dp,
    /** Shared gradient palette supplied by the main/mini/floating players. */
    palette: PlayButtonPalette? = null,
    /** 绘制阶段读取颜色的回调版本：供全屏播放页等滑动敏感场景使用，700ms 渐变零重组 */
    paletteProvider: (() -> PlayButtonPalette)? = null
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val prefs = LocalPrefs.current ?: remember(context) { Prefs(context.applicationContext) }
    var prefsTick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (Prefs.isPlayButtonPrefKey(key)) prefsTick++
        }
        prefs.registerChangeListener(listener)
        onDispose { prefs.unregisterChangeListener(listener) }
    }
    val glowEnabled = remember(prefsTick, prefs) { prefs.playBtnGlow }
    val btnShape = remember(prefsTick, prefs) { prefs.playBtnShape }
    val iconStyle = remember(prefsTick, prefs) { prefs.playBtnIconStyle }
    val iconScale = remember(prefsTick, prefs) { prefs.playBtnIconScale / 100f }
    val view = LocalView.current

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScaleState = animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "pressScale"
    )

    // 点击水波纹：0 → 1 的一次性扩散（只在绘制阶段读 ripple.value，避免 120fps 重组）
    val ripple = remember { Animatable(1f) }

    val hasCustomPalette = palette != null || paletteProvider != null
    val dropletActive = isPlaying && btnShape == PlayButtonShape.DROPLET
    val morphBlendState = animateFloatAsState(
        targetValue = if (dropletActive) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "morphBlend"
    )
    // Shared colored buttons pause without abruptly dropping glow/scale.
    val playingBlendState = if (hasCustomPalette) {
        animateFloatAsState(if (isPlaying) 1f else 0f, tween(350), label = "mainButtonGlow")
    } else null

    val animRunning = isPlaying || (playingBlendState?.value ?: 0f) > 0.01f
    val morphRunning = dropletActive || morphBlendState.value > 0.01f

    // 装饰性慢速动效统一按 ~30fps 发布且仅在播放 + 前台可见时运行；暂停后即刻进入 0fps 静止态。
    val breathState = rememberThrottledPhase(
        active = animRunning,
        periodMs = 2200,
        reverse = true,
        stepMs = 33L
    )
    val orbitState = if (hasCustomPalette) rememberButtonOrbit(isPlaying) else null
    val orbitPhaseState = if (!hasCustomPalette) {
        rememberThrottledPhase(
            active = animRunning,
            periodMs = 11000,
            reverse = false,
            stepMs = 33L,
            initial = 135f / 360f
        )
    } else null
    val morphState = rememberThrottledPhase(
        active = morphRunning,
        periodMs = 5500,
        reverse = true,
        stepMs = 33L
    )
    val lastMorphRef = remember { floatArrayOf(0f) }

    val paletteState = rememberUpdatedState(palette)
    val blobPath = remember { Path() }
    val highlightPath = remember { Path() }
    val ripplePath = remember { Path() }
    val glowCache = remember { GlowDrawCache() }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ---- 光晕（glow）：预烘焙双层高斯柔光纹理 + GPU 层旋转/缩放，告别每帧 120Hz 实时模糊重栅格化 ----
        if (glowEnabled) {
            Canvas(
                Modifier
                    .requiredSize(size * 2.4f)
                    .graphicsLayer {
                        val blend = playingBlendState?.value ?: if (isPlaying) 1f else 0f
                        val b = breathState.value * blend
                        val s = 1f + b * 0.05f
                        scaleX = s
                        scaleY = s
                        alpha = 0.42f + 0.58f * blend
                        val orbit = orbitState?.floatValue ?: (orbitPhaseState!!.value * 360f)
                        rotationZ = orbit - 45f
                    }
            ) {
                val p = paletteProvider?.invoke() ?: paletteState.value
                val cLight = p?.light ?: Color(0xFFA78BFA)
                val cMain = p?.main ?: Color(0xFF6366F1)
                val cDeep = p?.deep ?: Color(0xFFEC4899)
                val bmp = glowCache.ensureBitmap(cLight.toArgb(), cMain.toArgb(), cDeep.toArgb())
                drawImage(
                    image = bmp,
                    dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                )
            }
        }

        // ---- 可点击的按钮本体（水滴/正圆 + 贴合高光 + 波纹 + 图标）----
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = pressScaleState.value
                    scaleX = s
                    scaleY = s
                }
                .clickable(interactionSource = interaction, indication = null) {
                    if (prefs.playBtnHaptics) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    onClick()
                }
        ) {
            // 按钮本体（支持有机水滴形变 ↔ 经典正圆平滑过渡）
            Canvas(
                Modifier
                    .matchParentSize()
                    .padding(2.dp)
                    .graphicsLayer {
                        val blend = playingBlendState?.value ?: if (isPlaying) 1f else 0f
                        val b = breathState.value * blend
                        val s = 1f + b * 0.035f
                        scaleX = s
                        scaleY = s
                    }
            ) {
                val p = paletteProvider?.invoke() ?: paletteState.value
                val gradientColors = if (p != null) {
                    listOf(p.light, p.main, p.deep)
                } else {
                    DefaultPlayGradient
                }
                val orbit = orbitState?.floatValue ?: (orbitPhaseState!!.value * 360f)
                val morphProg = morphState.value.also { lastMorphRef[0] = it }
                val morphAmp = morphBlendState.value
                val w = this.size.width
                val h = this.size.height
                val angleRad = Math.toRadians(orbit.toDouble() - 45.0)
                val dir = Offset(cos(angleRad).toFloat(), sin(angleRad).toFloat())
                val brush = Brush.linearGradient(
                    colors = gradientColors,
                    start = center - dir * this.size.minDimension / 2f,
                    end = center + dir * this.size.minDimension / 2f
                )
                if (morphAmp <= 0.002f) {
                    drawCircle(brush = brush)
                } else {
                    val radii = computeBlobCornerRadii(morphProg, morphAmp)
                    drawPath(organicBlobPath(blobPath, 0f, 0f, w, h, radii), brush)
                }
            }

            // 玻璃高光（严格贴合当前水滴或正圆轮廓）
            Canvas(Modifier.matchParentSize().padding(2.dp)) {
                val morphProg = morphState.value
                val morphAmp = morphBlendState.value
                val hlBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    start = Offset(this.size.width * 0.15f, this.size.height * 0.1f),
                    end = Offset(this.size.width * 0.65f, this.size.height * 0.6f)
                )
                if (morphAmp <= 0.002f) {
                    drawCircle(brush = hlBrush)
                } else {
                    val radii = computeBlobCornerRadii(morphProg, morphAmp)
                    drawPath(organicBlobPath(highlightPath, 0f, 0f, this.size.width, this.size.height, radii), hlBrush)
                }
            }

            // 点击水波纹（在 Canvas 绘制期读取 Animatable.value，静止时几乎零开销、触发时不重组）
            Canvas(Modifier.matchParentSize().padding(2.dp)) {
                val t = ripple.value
                if (t < 1f) {
                    val morphProg = morphState.value
                    val morphAmp = morphBlendState.value
                    val strokeColor = Color.White.copy(alpha = (1f - t) * 0.55f)
                    val stroke = Stroke(width = 1.5.dp.toPx())
                    if (morphAmp <= 0.002f) {
                        val r = this.size.minDimension / 2f
                        drawCircle(
                            color = strokeColor,
                            radius = r * (0.55f + 0.48f * t),
                            style = stroke
                        )
                    } else {
                        val scale = 0.55f + 0.48f * t
                        val rw = this.size.width * scale
                        val rh = this.size.height * scale
                        val rx = (this.size.width - rw) / 2f
                        val ry = (this.size.height - rh) / 2f
                        val radii = computeBlobCornerRadii(morphProg, morphAmp)
                        drawPath(organicBlobPath(ripplePath, rx, ry, rw, rh, radii), strokeColor, style = stroke)
                    }
                }
            }

            // 中心图标
            val iconTint = rememberUpdatedState(skin.onAccent)
            val tintProvider = remember(paletteProvider) {
                { (paletteProvider?.invoke() ?: paletteState.value)?.icon ?: iconTint.value }
            }
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                FluidPlayIcon(
                    isPlaying = isPlaying,
                    style = iconStyle,
                    scale = iconScale,
                    stage = iconSize,
                    tintProvider = tintProvider
                )
            }
        }
    }

    // 波纹驱动：点击释放时启动一次 0 → 1 的扩散
    androidx.compose.runtime.LaunchedEffect(interaction) {
        interaction.interactions.collect { i ->
            if (i is PressInteraction.Release) {
                ripple.snapTo(0f)
                ripple.animateTo(1f, tween(650, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
            }
        }
    }
}

/**
 * 播放/暂停键的中心图标，三种样式：
 * - 0 经典：实心播放/暂停矢量图标（原版）；
 * - 1 液态：三根圆角短杆在「三角(播放) ↔ 双杠(暂停)」之间弹簧变形（参考样机）；
 * - 2 极简：细线三角 / 细双杠。
 *
 * [scale] 是设置里的图标大小档（1.0 为原设计），[stage] 是基准图标框。
 */
@Composable
private fun FluidPlayIcon(
    isPlaying: Boolean,
    style: Int,
    scale: Float,
    stage: Dp,
    tintProvider: () -> Color
) {
    when (style) {
        0 -> {
            val painter = rememberVectorPainter(
                if (isPlaying) com.sonance.player.ui.icon.SonanceIcons.Pause
                else com.sonance.player.ui.icon.SonanceIcons.Play
            )
            val desc = if (isPlaying) "暂停" else "播放"
            Canvas(
                Modifier
                    .size(stage * scale)
                    .semantics { contentDescription = desc }
            ) {
                with(painter) {
                    draw(size, colorFilter = ColorFilter.tint(tintProvider()))
                }
            }
        }
        2 -> Canvas(Modifier.size(stage * scale)) {
            val tint = tintProvider()
            val w = this.size.width
            val h = this.size.height
            if (isPlaying) {
                val barW = w * 0.13f
                val gap = w * 0.14f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w / 2f - gap - barW, h * 0.18f),
                    size = Size(barW, h * 0.64f),
                    cornerRadius = CornerRadius(barW / 2f)
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w / 2f + gap, h * 0.18f),
                    size = Size(barW, h * 0.64f),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            } else {
                val p = Path().apply {
                    moveTo(w * 0.26f, h * 0.16f)
                    lineTo(w * 0.82f, h * 0.5f)
                    lineTo(w * 0.26f, h * 0.84f)
                    close()
                }
                drawPath(p, color = tint)
            }
        }
        else -> {
            // 液态三杆：杆宽/高/位移都按 stage 取比例，弹簧动画跟随播放态（仅在绘制阶段读 State）
            val springSpec = androidx.compose.animation.core.spring<Float>(
                dampingRatio = 0.55f, stiffness = 380f
            )
            val s = stage * scale
            val barW = s * 0.20f
            val maxBarH = s * 0.76f

            // 各杆的播放态目标：|← 杆 →|（双杠）；暂停态：三角（两根斜杆 + 左脊）
            val topTxState = animateFloatAsState(if (isPlaying) -0.24f else 0.12f, springSpec, label = "topTx")
            val topTyState = animateFloatAsState(if (isPlaying) 0f else -0.16f, springSpec, label = "topTy")
            val topRotState = animateFloatAsState(if (isPlaying) 0f else -55f, springSpec, label = "topRot")
            val botTxState = animateFloatAsState(if (isPlaying) 0.24f else 0.12f, springSpec, label = "botTx")
            val botTyState = animateFloatAsState(if (isPlaying) 0f else 0.16f, springSpec, label = "botTy")
            val botRotState = animateFloatAsState(if (isPlaying) 0f else 55f, springSpec, label = "botRot")
            val spineTxState = animateFloatAsState(if (isPlaying) -0.24f else -0.19f, springSpec, label = "spineTx")
            val spineAlphaState = animateFloatAsState(
                if (isPlaying) 0f else 1f,
                androidx.compose.animation.core.tween(280), label = "spineAlpha"
            )
            val barScaleYState = animateFloatAsState(
                if (isPlaying) 1f else 0.72f / 0.76f,
                springSpec,
                label = "barH"
            )

            FluidBar(barW, maxBarH, s, tintProvider, topTxState, topTyState, topRotState, barScaleYState, null)
            FluidBar(barW, maxBarH, s, tintProvider, botTxState, botTyState, botRotState, barScaleYState, null)
            FluidBar(barW, maxBarH, s, tintProvider, spineTxState, null, null, barScaleYState, spineAlphaState)
        }
    }
}

/**
 * 有机 blob 形变路径（对应参考样机 organicDropletMorph 的 border-radius 动画）。
 *
 * [progress] ∈ [0,1]，在四组关键帧之间插值；每组 8 个百分比 = 四角水平半径
 * （TL TR BR BL）+ 四角垂直半径（TL TR BR BL），按 CSS border-radius 的
 * 归一化规则处理相邻半径之和超边长的情况。progress=0 时也会是圆润的
 * 不规则形状（不是圆），暂停态不走这条路径、直接画正圆。
 */
private val BLOB_MORPH_KEYS = arrayOf(
    floatArrayOf(24f, 76f, 60f, 40f, 26f, 58f, 42f, 74f),
    floatArrayOf(68f, 32f, 34f, 66f, 56f, 24f, 76f, 44f),
    floatArrayOf(36f, 64f, 26f, 74f, 64f, 40f, 60f, 36f),
    floatArrayOf(54f, 46f, 76f, 24f, 32f, 68f, 32f, 68f)
)

/**
 * 节流且感知生命周期的 0..1 相位源（替代无限 Running 的 `rememberInfiniteTransition`）：
 * - [active] == false 或界面退居后台（`< STARTED`）时立刻退出 `withFrameNanos` 帧循环（0fps 休眠），
 *   同时保持当前相位不回跳；
 * - 仅当累计经过 [stepMs]（默认 33ms ≈ 30fps）才发布状态，兼顾丝滑视觉与高刷屏低功耗；
 * - 返回只读 [State<Float>] 供 `graphicsLayer` / `Canvas` 在绘制阶段读取，避免逐帧触发重组。
 */
@Composable
internal fun rememberThrottledPhase(
    active: Boolean,
    periodMs: Int,
    reverse: Boolean = false,
    stepMs: Long = 33L,
    initial: Float = 0f
): State<Float> {
    val safePeriod = periodMs.coerceAtLeast(1)
    val state = remember { mutableFloatStateOf(initial.coerceIn(0f, 1f)) }
    val progressRef = remember { floatArrayOf(initial.coerceIn(0f, 1f), 1f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(active, safePeriod, reverse, stepMs, lifecycleOwner) {
        if (!active) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withFrameNanos { it }
            var accNs = 0L
            val stepNs = stepMs.coerceAtLeast(1L) * 1_000_000L
            while (true) {
                val now = withFrameNanos { it }
                val dtNs = (now - last).coerceIn(0L, 100_000_000L)
                last = now
                accNs += dtNs
                if (accNs >= stepNs) {
                    val delta = (accNs / 1_000_000f) / safePeriod.toFloat()
                    accNs = 0L
                    var p = progressRef[0] + progressRef[1] * delta
                    if (reverse) {
                        while (p > 1f || p < 0f) {
                            if (p > 1f) {
                                p = 2f - p
                                progressRef[1] = -1f
                            }
                            if (p < 0f) {
                                p = -p
                                progressRef[1] = 1f
                            }
                        }
                    } else {
                        p %= 1f
                        if (p < 0f) p += 1f
                    }
                    progressRef[0] = p
                    state.floatValue = p
                }
            }
        }
    }
    return state
}

private const val GLOW_BMP_PX = 128

private class GlowDrawCache {
    private val outerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val innerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    var lastLight = 0
    var lastMain = 0
    var lastDeep = 0
    var glowBitmap: ImageBitmap? = null

    fun ensureBitmap(argbLight: Int, argbMain: Int, argbDeep: Int): ImageBitmap {
        glowBitmap?.let { cached ->
            if (lastLight == argbLight && lastMain == argbMain && lastDeep == argbDeep) {
                return cached
            }
        }
        val sizePx = GLOW_BMP_PX
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val btnSpan = sizePx / 2.4f
        val half = btnSpan * 0.65f
        val shader = LinearGradient(
            cx - half, cy, cx + half, cy,
            intArrayOf(argbLight, argbMain, argbDeep),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        outerPaint.shader = shader
        innerPaint.shader = shader
        outerPaint.maskFilter = BlurMaskFilter(btnSpan * 0.24f, BlurMaskFilter.Blur.NORMAL)
        innerPaint.maskFilter = BlurMaskFilter(btnSpan * 0.105f, BlurMaskFilter.Blur.NORMAL)
        outerPaint.alpha = 158
        c.drawCircle(cx, cy, btnSpan * 1.20f / 2f, outerPaint)
        innerPaint.alpha = 216
        c.drawCircle(cx, cy, btnSpan * 1.05f / 2f, innerPaint)

        val img = bmp.asImageBitmap()
        lastLight = argbLight
        lastMain = argbMain
        lastDeep = argbDeep
        glowBitmap = img
        return img
    }
}

/**
 * 计算有机水滴在 [progress] ∈ [0,1] 与形变幅度 [amplitude] ∈ [0,1] 下的 8 个归一化圆角半径：
 * `[tlH, trH, brH, blH, tlV, trV, brV, blV]`。
 * `amplitude = 0` 时严格返回全 0.5（正圆），`amplitude = 1` 时为完整水滴曲线。
 */
internal fun computeBlobCornerRadii(progress: Float, amplitude: Float = 1f): FloatArray {
    val amp = amplitude.coerceIn(0f, 1f)
    if (amp <= 0f) return floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    val keys = BLOB_MORPH_KEYS
    val seg = (progress.coerceIn(0f, 1f)) * 3f
    val i = minOf(2, seg.toInt())
    val t = seg - i
    val a = keys[i]
    val b = keys[i + 1]
    var tlH = (50f + ((a[0] + (b[0] - a[0]) * t) - 50f) * amp) / 100f
    var trH = (50f + ((a[1] + (b[1] - a[1]) * t) - 50f) * amp) / 100f
    var brH = (50f + ((a[2] + (b[2] - a[2]) * t) - 50f) * amp) / 100f
    var blH = (50f + ((a[3] + (b[3] - a[3]) * t) - 50f) * amp) / 100f
    var tlV = (50f + ((a[4] + (b[4] - a[4]) * t) - 50f) * amp) / 100f
    var trV = (50f + ((a[5] + (b[5] - a[5]) * t) - 50f) * amp) / 100f
    var brV = (50f + ((a[6] + (b[6] - a[6]) * t) - 50f) * amp) / 100f
    var blV = (50f + ((a[7] + (b[7] - a[7]) * t) - 50f) * amp) / 100f

    // CSS 归一化：每条边上相邻两个半径之和超过边长时等比缩小
    val fTop = minOf(1f, 1f / (tlH + trH).coerceAtLeast(1e-4f))
    val fBottom = minOf(1f, 1f / (brH + blH).coerceAtLeast(1e-4f))
    val fLeft = minOf(1f, 1f / (tlV + blV).coerceAtLeast(1e-4f))
    val fRight = minOf(1f, 1f / (trV + brV).coerceAtLeast(1e-4f))
    tlH *= fTop; trH *= fTop; brH *= fBottom; blH *= fBottom
    tlV *= fLeft; blV *= fLeft; trV *= fRight; brV *= fRight
    return floatArrayOf(tlH, trH, brH, blH, tlV, trV, brV, blV)
}

private fun organicBlobPath(
    path: Path,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: FloatArray
): Path {
    val rect = androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
    path.rewind()
    path.addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            rect,
            topLeft = CornerRadius(w * r[0], h * r[4]),
            topRight = CornerRadius(w * r[1], h * r[5]),
            bottomRight = CornerRadius(w * r[2], h * r[6]),
            bottomLeft = CornerRadius(w * r[3], h * r[7])
        )
    )
    return path
}

/**
 * 液态图标的单根圆角短杆（位移动画与色彩读取全部延后到绘制阶段，零重组）
 */
@Composable
private fun FluidBar(
    width: Dp,
    height: Dp,
    stage: Dp,
    tintProvider: () -> Color,
    txFrac: State<Float>,
    tyFrac: State<Float>?,
    rotation: State<Float>?,
    scaleYState: State<Float>,
    alphaState: State<Float>?
) {
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer {
                translationX = (stage * txFrac.value).toPx()
                translationY = if (tyFrac != null) (stage * tyFrac.value).toPx() else 0f
                rotationZ = rotation?.value ?: 0f
                scaleY = scaleYState.value
                alpha = alphaState?.value ?: 1f
            }
            .drawBehind {
                val r = this.size.width / 2f
                drawRoundRect(
                    color = tintProvider(),
                    cornerRadius = CornerRadius(r, r)
                )
            }
    )
}

/* ============================================================
 * 滑块（进度 / 音量）
 * ============================================================ */

/**
 * 自绘滑块。
 *
 * **手势判定**（这一点很重要，改之前是"按下就跳"）：
 * - 按下**不立即跳进度**。底座贴着屏幕底部时，系统的"上滑回桌面"会先给我们一个
 *   DOWN，原来那一瞬间就 seek 了 —— 这就是平板上滑误改进度的根因。
 * - 只有**横向意图明确**（横移超过 touch slop 且大于纵向位移）才进入拖动并跳转。
 * - 纵向意图明确 → 直接放弃这次手势，不消耗事件，交还给系统 / 外层滚动容器。
 * - 整个手势都没有超过 slop → 认定为**精确点击**，按落点跳一次。
 */
@Composable
fun SonanceSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumbSize: Dp = 12.dp,
    activeColor: Color = LocalSkin.current.accent,
    inactiveColor: Color = LocalSkin.current.surface3,
    alwaysShowThumb: Boolean = false,
    enabled: Boolean = true,
    /**
     * 填充色的终点色：传了就用 [activeColor] → 该色的横向渐变
     * （参考样机的进度条 accent-secondary → accent-primary）。
     * 不传则沿用原来的单色填充。
     */
    gradientEnd: Color? = null,
    /** 拇指外圈加一层柔光（参考样机 0 0 0 3px accent-soft），进度条用 */
    thumbHalo: Boolean = false,
    /** 双向滑杆的中性点；不传时沿用从左端填充的普通进度条。 */
    neutralPoint: Float? = null,
    /**
     * 手势结束（松手 / 精确点击落地）后回调一次。
     *
     * 给"拖动中实时预览、松手才落盘"的场景用 —— 比如均衡器滑杆：
     * 拖动时立刻改音频链路上的增益（听得到），但不必每帧都写 SharedPreferences。
     */
    onValueChangeFinished: (() -> Unit)? = null
) {
    var trackWidth by remember { mutableStateOf(1f) }
    var pressed by remember { mutableStateOf(false) }
    val safe = value.coerceIn(0f, 1f)

    // pointerInput(enabled) 的 key 不含回调，持续读取最新 lambda。
    val latestValueChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier
            .height(thumbSize.coerceAtLeast(26.dp))
            .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    var dy = 0f
                    var scrubbing = false
                    var abandoned = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx += change.position.x - change.previousPosition.x
                        dy += change.position.y - change.previousPosition.y

                        if (!scrubbing && !abandoned) {
                            val hx = kotlin.math.abs(dx)
                            val hy = kotlin.math.abs(dy)
                            if (hx > slop && hx >= hy) {
                                scrubbing = true
                                pressed = true
                                latestValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                                change.consume()
                            } else if (hy > slop) {
                                abandoned = true
                            }
                        } else if (scrubbing) {
                            latestValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }

                    if (scrubbing) {
                        pressed = false
                        latestFinished?.invoke()
                    } else if (!abandoned) {
                        latestValueChange((down.position.x / size.width).coerceIn(0f, 1f))
                        latestFinished?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxWidth()) {
            val cy = size.height / 2f
            val h = trackHeight.toPx()
            val radius = CornerRadius(h / 2f)
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width, h),
                cornerRadius = radius
            )
            val thumbX = size.width * safe
            val origin = size.width * (neutralPoint?.coerceIn(0f, 1f) ?: 0f)
            val fillStart = minOf(origin, thumbX)
            val fillWidth = kotlin.math.abs(thumbX - origin)
            if (fillWidth > 0.5f) {
                val end = gradientEnd
                if (end != null) {
                    // 横向渐变：起点暗、终点亮，和样机一致
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(activeColor, end),
                            start = Offset(0f, cy),
                            end = Offset(size.width, cy)
                        ),
                            topLeft = Offset(fillStart, cy - h / 2f),
                            size = Size(fillWidth, h),
                        cornerRadius = radius
                    )
                } else {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(fillStart, cy - h / 2f),
                        size = Size(fillWidth, h),
                        cornerRadius = radius
                    )
                }
            }
            if (neutralPoint != null) {
                drawLine(
                    color = activeColor.copy(alpha = 0.55f),
                    start = Offset(origin, cy - 4.dp.toPx()),
                    end = Offset(origin, cy + 4.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            if (alwaysShowThumb || pressed) {
                // 样机：按住时拇指放大到 1.3 倍
                val r = thumbSize.toPx() / 2f * (if (pressed) 1.3f else 1f)
                val thumbColor = gradientEnd ?: activeColor
                val cx = thumbX.coerceIn(r, size.width - r)
                if (thumbHalo) {
                    drawCircle(
                        color = thumbColor.copy(alpha = 0.25f),
                        radius = r + 3.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
                drawCircle(
                    color = thumbColor,
                    radius = r,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

/** 音量滑块（带图标） */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    trackHeight: Dp = 3.dp,
    thumbSize: Dp = 11.dp
) {
    val skin = LocalSkin.current
    val icon = when {
        volume <= 0.001f -> com.sonance.player.ui.icon.SonanceIcons.VolumeMute
        volume < 0.5f -> com.sonance.player.ui.icon.SonanceIcons.VolumeLow
        else -> com.sonance.player.ui.icon.SonanceIcons.Volume
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = "音量",
            tint = skin.textMuted,
            modifier = Modifier
                .size(iconSize)
                .clickable {
                    onVolumeChange(if (volume > 0.001f) 0f else 0.7f)
                }
        )
        SonanceSlider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier.weight(1f),
            trackHeight = trackHeight,
            thumbSize = thumbSize,
            activeColor = skin.textDim,
            inactiveColor = skin.surface3,
            alwaysShowThumb = true
        )
    }
}

/* ============================================================
 * 文本 / 容器
 * ============================================================ */

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(skin.surface2),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = skin.textMuted,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = skin.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = skin.textMuted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun LoadingState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = skin.accent,
            trackColor = skin.surface3,
            strokeWidth = 3.dp,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = skin.text, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val skin = LocalSkin.current
    val motion = LocalMotionConfig.current
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
    Box(
        modifier = modifier
            .pressSpring(enabled = enabled && motion.touchSpringFeedback, pressedScale = 0.96f)
            .clip(shape)
            .background(if (filled) skin.accent else skin.surface2)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 20.dp,
                vertical = if (compact) 6.dp else 11.dp
            )
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = if (filled) skin.onAccent else skin.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * 与主页「随便听听」同款的轻玻璃操作胶囊，供专辑、文件夹等次级页操作按钮统一风格。
 */
@Composable
fun GlassActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        accent -> skin.accent.copy(alpha = if (skin.isLight) 0.12f else 0.17f)
        skin.immersive && skin.isLight -> Color.White.copy(alpha = 0.66f)
        skin.immersive -> Color.White.copy(alpha = 0.09f)
        else -> skin.surface2
    }
    val stroke = when {
        accent -> skin.accent.copy(alpha = 0.38f)
        skin.immersive && skin.isLight -> Color.Black.copy(alpha = 0.10f)
        skin.immersive -> Color.White.copy(alpha = 0.12f)
        else -> skin.border.copy(alpha = 0.35f)
    }
    val fg = if (accent) skin.accent else skin.textDim
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.42f)
            .clip(shape)
            .background(bg)
            .border(0.8.dp, stroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1
        )
    }
}

@Composable
fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) skin.accentGlow else skin.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) skin.accent else skin.textDim
        )
    }
}

@Composable
fun CountLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalSkin.current.textMuted,
        modifier = modifier
    )
}

/** 应用内轻提示 */
@Composable
fun ToastHost(message: String?, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(skin.surface3)
                .padding(horizontal = 20.dp, vertical = 11.dp)
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = skin.text
            )
        }
    }
}

/** 正在播放的三段律动条 */
@Composable
fun EqualizerBars(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 2.5.dp,
    height: Dp = 12.dp
) {
    val boxModifier = modifier.size(barWidth * 5, height)

    // 暂停时不起动画：无限动画会让界面永远进不了空闲帧，白白耗电
    if (!active) {
        Canvas(boxModifier) { drawEqBars(barWidth, color, 0.3f, 0.3f, 0.3f) }
        return
    }

    val phaseState = rememberThrottledPhase(
        active = true,
        periodMs = 1100,
        reverse = false,
        stepMs = 33L
    )

    Canvas(boxModifier) {
        val p = phaseState.value * (2f * Math.PI.toFloat())
        drawEqBars(
            barWidth = barWidth,
            color = color,
            f0 = (kotlin.math.sin(p.toDouble()) * 0.5 + 0.5).toFloat(),
            f1 = (kotlin.math.sin((p + 1.1f).toDouble()) * 0.5 + 0.5).toFloat(),
            f2 = (kotlin.math.sin((p + 2.2f).toDouble()) * 0.5 + 0.5).toFloat()
        )
    }
}

/** 画三段律动条；f0..f2 是 0..1 的强度 */
private fun DrawScope.drawEqBars(barWidth: Dp, color: Color, f0: Float, f1: Float, f2: Float) {
    val slot = size.width / 3f
    val w = barWidth.toPx()
    val factors = floatArrayOf(f0, f1, f2)
    for (i in 0 until 3) {
        val h = size.height * (0.28f + 0.72f * factors[i])
        val cx = slot * i + slot / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - w / 2f, size.height - h),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2f)
        )
    }
}
