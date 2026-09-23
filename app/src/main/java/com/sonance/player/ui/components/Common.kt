package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin

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
    // 首帧就直接读内存缓存 —— 已加载过的封面必须立刻出现，不能先给占位图。
    // （load() 是 suspend 的，即使缓存命中也要走协程调度，等好几帧才赋值，
    //   表现就是"滚回来还要等半秒"。）
    //
    // 用 [ArtworkLoader.peekAny] 而不是 `peek`：拿不到**准确尺寸档**时退到更小的档。
    //
    // 播放页要 1024 档，而列表早就解过同一张图的 512 档。只认准确档的话，
    // 切到那一页的瞬间它会显示占位图、几十到几百毫秒后才换成真封面 ——
    // 那就是「切歌时背景闪一下」（往回切尤其明显：那一页是切过去才组合的）。
    // 退一步先显示小图，糊一瞬但**不会闪空白**，准确档解码好就立刻顶上来。
    var bitmap by remember(albumId, sourceUri, coverKey) {
        mutableStateOf(ArtworkLoader.peekAny(albumId, sizePx, sourceUri, coverKey))
    }

    LaunchedEffect(albumId, sizePx, sourceUri, coverKey) {
        // ⚠️ 这里**不读** ArtworkLoader.paused。
        //
        // 以前的写法是"滚动中直接 return"，结果封面只能等手停下来才开工 ——
        // 用户看到的就是「快速滑动时没有封面，停下 0.5~1 秒才出来」。
        // 现在滚动照常加载，只在 [ArtworkLoader] 内部把并发压低（见 scrollGate），
        // 于是封面往往在手指还没停就已经就位了。
        //
        // 也因此不需要再把 paused 放进 key（那是为了让"被跳过的行"在滚动结束后
        // 补一次加载）；这个 effect 现在一次跑到底。
        if (albumId <= 0 && sourceUri == null && coverKey == null) return@LaunchedEffect
        // 拿不到就保持现状（占位图），**不要**把它赋成 null ——
        // 那会把首帧 peek 到的、或已经加载好的封面抹掉。
        ArtworkLoader.load(context, albumId, sizePx, sourceUri, coverKey)?.let { bitmap = it }
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
        val bmp = online ?: bitmap
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

/** 圆形控制按钮，可高亮表示激活 */
@Composable
fun CtrlButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    active: Boolean = false,
    tint: Color = LocalSkin.current.textDim
) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) skin.accentGlow else Color.Transparent)
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

/** 实心强调色播放键 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 22.dp
) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(skin.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (isPlaying) com.sonance.player.ui.icon.SonanceIcons.Pause
            else com.sonance.player.ui.icon.SonanceIcons.Play,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = skin.onAccent,
            modifier = Modifier.size(iconSize)
        )
    }
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

    // pointerInput(enabled) 的 key 不含这个回调，直接捕获会拿到旧 lambda
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
                                onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                                change.consume()
                            } else if (hy > slop) {
                                abandoned = true
                            }
                        } else if (scrubbing) {
                            onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }

                    if (scrubbing) {
                        pressed = false
                        latestFinished?.invoke()
                    } else if (!abandoned) {
                        onValueChange((down.position.x / size.width).coerceIn(0f, 1f))
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
            val w = size.width * safe
            if (w > 0.5f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(w, h),
                    cornerRadius = radius
                )
            }
            if (alwaysShowThumb || pressed) {
                val r = thumbSize.toPx() / 2f
                drawCircle(
                    color = activeColor,
                    radius = r,
                    center = Offset(w.coerceIn(r, size.width - r), cy)
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
    filled: Boolean = true
) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) skin.accent else skin.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) skin.onAccent else skin.text,
            fontWeight = FontWeight.SemiBold
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

    val transition = rememberInfiniteTransition(label = "eq")
    // 刻意不用 by 委托：把动画值的读取推迟到 draw 阶段，
    // 这样每帧只触发重绘、不触发重组（律动条在列表行里，重组代价会被放大）
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(boxModifier) {
        val p = phase.value
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
