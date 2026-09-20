package com.sonance.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.AudioVisualizer
import com.sonance.player.playback.PcmBus
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** 可视化样式 */
enum class VizStyle(val label: String) { BARS("直立柱"), MIRROR("镜像柱"), WAVE("波形") }

/**
 * 律动柱配色。
 *
 * [SKIN] 是"跟随皮肤"，其余是固定色板 —— 每档给一个主色 + 一个暗色，
 * 暗色用于柱底和反射，这样一根柱子从下到上有明暗过渡，不是一块死色。
 */
enum class VizColor(val label: String, val main: Long, val dim: Long) {
    SKIN("跟随皮肤", 0L, 0L),
    AMBER("琥珀金", 0xFFE8A838, 0xFF7A5218),
    AQUA("青蓝", 0xFF4ECDC4, 0xFF1F5B57),
    ROSE("品红", 0xFFE0556A, 0xFF6E2233),
    JADE("翠绿", 0xFF5CC98A, 0xFF1F5B3A),
    VIOLET("紫罗兰", 0xFF9B72D9, 0xFF3E2A63),
    ICE("冰白", 0xFFE8EEF5, 0xFF5A6675),
    EMBER("霓虹橙", 0xFFFF7A45, 0xFF7A2E14),
    SKY("晴空蓝", 0xFF7EB8E8, 0xFF264A6B);

    val isSkin: Boolean get() = this == SKIN
}

/** 没有真实数据时的程序化动画周期（3 秒） */
private const val FALLBACK_PERIOD_NS = 3_000_000_000L

private const val TWO_PI = (2 * PI).toFloat()

/* ============================================================
 * 全屏律动柱（真实音频可视化）
 * ============================================================ */

@Composable
fun VisualizerOverlay(
    song: Song,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    prefs: Prefs,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current

    var style by remember { mutableStateOf(VizStyle.entries.getOrNull(prefs.vizStyle) ?: VizStyle.BARS) }
    var barCount by remember { mutableStateOf(prefs.vizBarCount) }
    var sensitivity by remember { mutableStateOf(prefs.vizSensitivity) }
    var mirror by remember { mutableStateOf(prefs.vizMirror) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showFps by remember { mutableStateOf(prefs.vizShowFps) }
    var colorIndex by remember { mutableStateOf(prefs.vizColorIndex) }
    var barWidth by remember { mutableStateOf(prefs.vizBarWidth) }

    // 选定配色；「跟随皮肤」时用皮肤强调色
    val vizColor = VizColor.entries.getOrNull(colorIndex) ?: VizColor.SKIN
    val barColor = if (vizColor.isSkin) skin.accent else Color(vizColor.main)
    val barColorDim = if (vizColor.isSkin) skin.accentDim else Color(vizColor.dim)

    // 每个柱数档位一个 Visualizer 实例，换档重建
    val viz = remember(barCount) { AudioVisualizer(barCount) }
    var live by remember { mutableStateOf(false) }

    DisposableEffect(viz) {
        // 这里只是"初始值"。真实状态由 VisualizerCanvas 的工作线程实时回写 ——
        // 之前只在这里算一次，若打开律动柱时 PCM 还没流动，live 会永远停在 false，
        // 于是走程序化回退动画（看着"流畅"但显示的是假数据）。
        live = viz.start(0)
        onDispose { viz.stop() }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(skin.bg, skin.surface, skin.bg)))
    ) {
        // ===== 可视化主体 =====
        VisualizerCanvas(
            viz = viz,
            live = live,
            onLiveChange = { live = it },
            isPlaying = isPlaying,
            style = style,
            mirror = mirror,
            sensitivity = sensitivity,
            showFps = showFps,
            barWidthRatio = barWidth,
            color = barColor,
            colorDim = barColorDim,
            modifier = Modifier.fillMaxSize()
        )

        // ===== 底部压暗 =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, skin.bg.copy(alpha = 0.55f), skin.bg)
                    )
                )
        )
        // ===== 顶部压暗 =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(skin.bg.copy(alpha = 0.88f), Color.Transparent)
                    )
                )
        )

        // ===== 右上角：设置齿轮 + 关闭 =====
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconAction(
                icon = SonanceIcons.Settings,
                contentDescription = "律动柱设置",
                onClick = { settingsOpen = true },
                iconSize = 20.dp,
                tint = if (settingsOpen) skin.accent else skin.textDim
            )
            Spacer(Modifier.width(2.dp))
            IconAction(
                icon = SonanceIcons.Close,
                contentDescription = "关闭律动柱",
                onClick = onClose,
                iconSize = 22.dp,
                tint = skin.text
            )
        }

        // 拿不到真实频谱时如实告知，不假装
        if (!live) {
            Text(
                "未取到实时频谱（设备/权限受限）· 已回退动画",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 54.dp)
            )
        }

        // ===== 歌名 / 歌手 / 专辑 + 进度 =====
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = skin.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(4.dp).background(skin.accent, CircleShape))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.titleSmall,
                    color = skin.textDim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (song.album.isNotBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatDuration(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(skin.surface3, CircleShape)
                ) {
                    val frac = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(2.dp)
                            .background(skin.accent, CircleShape)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }

        // ===== 设置面板 =====
        if (settingsOpen) {
            VizSettingsPanel(
                style = style,
                barCount = barCount,
                sensitivity = sensitivity,
                mirror = mirror,
                showFps = showFps,
                colorIndex = colorIndex,
                barWidth = barWidth,
                accentPreview = barColor,
                onStyle = {
                    style = it
                    prefs.vizStyle = it.ordinal
                },
                onBarCount = {
                    barCount = it
                    prefs.vizBarCount = it
                },
                onSensitivity = {
                    sensitivity = it
                    prefs.vizSensitivity = it
                },
                onMirror = {
                    mirror = it
                    prefs.vizMirror = it
                },
                onShowFps = {
                    showFps = it
                    prefs.vizShowFps = it
                },
                onColor = {
                    colorIndex = it
                    prefs.vizColorIndex = it
                },
                onBarWidth = {
                    barWidth = it
                    prefs.vizBarWidth = it
                },
                onDismiss = { settingsOpen = false }
            )
        }
    }
}

/* ============================================================
 * 画布
 * ============================================================ */

@Composable
private fun VisualizerCanvas(
    viz: AudioVisualizer,
    live: Boolean,
    onLiveChange: (Boolean) -> Unit,
    isPlaying: Boolean,
    style: VizStyle,
    mirror: Boolean,
    sensitivity: Float,
    showFps: Boolean,
    barWidthRatio: Float,
    color: Color,
    colorDim: Color,
    modifier: Modifier = Modifier
) {
    /**
     * 每帧自增的帧时钟。
     *
     * 关键：它必须在绘制阶段被**无条件**读取，否则写它不会让画布失效。
     * 之前 `frameNanos.longValue` 只在 `if (live)` 的 else 分支里读，
     * 导致 live=true 时整块画布完全不重绘 —— 这就是"只有 1~5 帧"的直接原因。
     */
    val frameTick = remember { mutableLongStateOf(0L) }

    // 显示值：绘制阶段往 viz.levels（FFT 目标值）插值，把 20~40Hz 的数据画成连续运动
    val display = remember(viz) { FloatArray(viz.bars) }

    // 帧率统计
    val framesInWindow = remember { intArrayOf(0) }
    var fps by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                frameTick.longValue = nanos
                framesInWindow[0]++
            }
        }
    }

    if (showFps) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                fps = framesInWindow[0]
                framesInWindow[0] = 0
            }
        }
    }

    // FFT 工作线程：整条流水线（环形缓冲读取 + 加窗 + FFT + 频段映射）
    // 全部挪到 Dispatchers.Default，主线程只负责绘制。
    // poll() 内部还有"新数据门控"，没有新 PCM 时直接返回，不会空转 FFT。
    LaunchedEffect(viz, sensitivity, isPlaying) {
        var lastReady = PcmBus.ready
        withContext(Dispatchers.Default) {
            while (isActive) {
                val ready = PcmBus.ready
                if (ready != lastReady) {
                    lastReady = ready
                    withContext(Dispatchers.Main) { onLiveChange(ready) }
                }
                if (ready) {
                    if (!viz.poll(sensitivity, isPlaying)) viz.decayToZero()
                } else {
                    viz.decayToZero()
                }
                // 4ms ≈ 250Hz 轮询：有新数据才算 FFT，没新数据只是两次 volatile 读
                delay(4)
            }
        }
    }

    val drawModifier = modifier
        // 独立 RenderNode：这样每帧失效只重录这一层，不会把覆盖层里的
        // 背景渐变 / 歌名文字 / 进度条 / 图标全部重录一遍
        .graphicsLayer()
        .drawWithCache {
            val w = size.width
            val h = size.height
            val n = viz.bars

            // ---- 几何：只在尺寸/柱数变化时算一次 ----
            val slot = w / n
            // 宽度由设置决定；上限放宽到 20dp，否则柱数少的时候"拉满"也还是细的
            val barW = (slot * barWidthRatio).coerceIn(1f, 20.dp.toPx())
            val gap = slot - barW

            val base = h * 0.78f
            val maxH = h * 0.70f
            val mirrorCy = h * 0.45f
            val mirrorHalf = h * 0.32f
            val mirrorPad = 2.dp.toPx()
            val reflectPad = 1.dp.toPx()

            // ---- 画笔：缓存，不每帧新建 ----
            val upBrush = Brush.verticalGradient(
                colors = listOf(colorDim, color),
                startY = base - maxH,
                endY = base
            )
            val downBrush = Brush.verticalGradient(
                colors = listOf(color, colorDim.copy(alpha = 0.25f)),
                startY = base + mirrorPad,
                endY = base + mirrorPad + maxH * 0.6f
            )
            val reflectBrush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.18f), Color.Transparent),
                startY = base + reflectPad,
                endY = base + maxH * 0.55f
            )

            // ---- 合批用的 Path：所有柱子拼成 2~3 个 Path，每帧只 2~3 次 drawPath ----
            val barPath = Path()
            val mirrorDownPath = Path()
            val reflectPath = Path()

            // ---- 波形用的 Path / Stroke：复用 ----
            val waveCy = h * 0.5f
            val waveMax = h * 0.25f
            val waveStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val waveLine = Path()
            val waveFill = Path()

            onDrawBehind {
                // ★ 无条件读帧时钟：这是画布每帧失效的唯一保证，不要挪进任何分支
                val tick = frameTick.longValue

                val levels = viz.levels
                val count = levels.size

                // 按帧插值：快起慢落。FFT 只更新 20~40Hz，靠这一步补出连续运动。
                var j = 0
                while (j < n) {
                    val target = if (j < count) levels[j] else 0f
                    val cur = display[j]
                    display[j] = if (target > cur) {
                        cur + (target - cur) * 0.62f      // 起：跟得紧
                    } else {
                        cur + (target - cur) * 0.13f      // 落：慢慢回
                    }
                    j++
                }

                val phase = if (live) {
                    0f
                } else {
                    (tick % FALLBACK_PERIOD_NS) / FALLBACK_PERIOD_NS.toFloat() * TWO_PI
                }

                when (style) {
                    VizStyle.WAVE -> drawWave(
                        display, phase, live, isPlaying, w, waveCy, waveMax,
                        waveLine, waveFill, waveStroke, color, colorDim
                    )

                    else -> {
                        val isMirror = style == VizStyle.MIRROR
                        barPath.reset()
                        mirrorDownPath.reset()
                        reflectPath.reset()

                        var i = 0
                        while (i < n) {
                            val nx = (i + 0.5f) / n
                            val amp = when {
                                live -> display[i]
                                isPlaying -> {
                                    val t = phase * (1f + nx * 1.7f) + nx * 9.5f
                                    val a = 0.5f + 0.5f * sin(t)
                                    val b = 0.5f + 0.5f * sin(t * 0.43f + 1.3f)
                                    val env = 0.28f + 0.72f * sin(PI.toFloat() * nx)
                                    (a * 0.62f + b * 0.38f) * env
                                }

                                else -> 0.06f
                            }

                            val bh = (maxH * amp).coerceAtLeast(1.5f)
                            val x = i * slot + gap / 2f
                            val xr = x + barW

                            if (isMirror) {
                                barPath.addRect(Rect(x, base - bh, xr, base))
                                val dh = bh * 0.58f
                                mirrorDownPath.addRect(Rect(x, base + mirrorPad, xr, base + mirrorPad + dh))
                            } else {
                                barPath.addRect(Rect(x, base - bh, xr, base))
                                if (mirror && bh > 4f) {
                                    val rh = (bh * 0.55f).coerceAtMost(maxH * 0.45f)
                                    reflectPath.addRect(Rect(x, base + reflectPad, xr, base + reflectPad + rh))
                                }
                            }
                            i++
                        }

                        // 3 次 drawPath 取代原来的 96 次 drawRoundRect
                        drawPath(barPath, upBrush)
                        if (isMirror) drawPath(mirrorDownPath, downBrush)
                        else if (mirror) drawPath(reflectPath, reflectBrush)
                    }
                }
            }
        }

    Box(drawModifier) {
        if (showFps) {
            Text(
                text = "$fps fps · ${viz.bars} 柱",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

private fun DrawScope.drawWave(
    levels: FloatArray,
    phase: Float,
    live: Boolean,
    isPlaying: Boolean,
    w: Float,
    cy: Float,
    maxH: Float,
    line: Path,
    fill: Path,
    stroke: Stroke,
    color: Color,
    colorDim: Color
) {
    val n = levels.size
    val denom = (n - 1).coerceAtLeast(1)
    val step = w / denom

    // Path 复用：不再每帧 new 两个 Path
    line.reset()
    var i = 0
    while (i < n) {
        val nx = i.toFloat() / denom
        val amp = when {
            live -> levels[i]
            isPlaying -> 0.5f + 0.5f * sin(phase * 2f + nx * 12f)
            else -> 0.05f
        }
        val y = cy - (amp - 0.5f) * maxH * 2f
        if (i == 0) line.moveTo(0f, y) else line.lineTo(i * step, y)
        i++
    }

    // 填充：波形下方到基线之间的一层淡色
    fill.reset()
    fill.addPath(line)
    fill.lineTo(w, cy + maxH)
    fill.lineTo(0f, cy + maxH)
    fill.close()

    drawPath(fill, colorDim.copy(alpha = 0.20f))
    drawPath(line, color, style = stroke)
}

/* ============================================================
 * 设置面板（右上角齿轮点开）
 * ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VizSettingsPanel(
    style: VizStyle,
    barCount: Int,
    sensitivity: Float,
    mirror: Boolean,
    showFps: Boolean,
    colorIndex: Int,
    barWidth: Float,
    accentPreview: Color,
    onStyle: (VizStyle) -> Unit,
    onBarCount: (Int) -> Unit,
    onSensitivity: (Float) -> Unit,
    onMirror: (Boolean) -> Unit,
    onShowFps: (Boolean) -> Unit,
    onColor: (Int) -> Unit,
    onBarWidth: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.TopEnd
    ) {
Column(
                Modifier
                    .padding(top = 92.dp, end = 12.dp, bottom = 12.dp)
                    .width(280.dp)
                    .heightIn(max = 520.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(skin.surface2)
                    .border(1.dp, skin.border2, RoundedCornerShape(16.dp))
                    .clickable { /* 吃掉点击，避免穿透关闭 */ }
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Text(
                "律动柱设置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("样式", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VizStyle.entries.forEach { s ->
                        SmallChip(s.label, s == style) { onStyle(s) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("柱子数量", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 32, 48, 64, 96).forEach { c ->
                        SmallChip("$c", barCount == c) { onBarCount(c) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("灵敏度", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Text(
                        "%.1fx".format(sensitivity),
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.accent
                    )
                }
                SonanceSlider(
                    value = ((sensitivity - 0.4f) / 2.6f).coerceIn(0f, 1f),
                    onValueChange = { onSensitivity(0.4f + it * 2.6f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackHeight = 3.dp,
                    thumbSize = 11.dp,
                    alwaysShowThumb = true
                )
            }

            // ---- 柱子颜色 ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("柱子颜色", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Text(
                        VizColor.entries.getOrNull(colorIndex)?.label ?: VizColor.SKIN.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentPreview
                    )
                }
                // 色板用 FlowRow 自动换行；每个色块是圆形，选中的加一圈描边
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VizColor.entries.forEachIndexed { i, c ->
                        val selected = i == colorIndex
                        val swatch = if (c.isSkin) skin.accent else Color(c.main)
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) skin.text else skin.border,
                                    shape = CircleShape
                                )
                                .clickable { onColor(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                // 选中标记：小圆点，深浅色都能看清
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (c.isSkin) skin.onAccent else Color.Black.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    }
                }
            }

            // ---- 柱子宽度 ----
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("柱子宽度", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Text(
                        when {
                            barWidth < 0.4f -> "细"
                            barWidth < 0.62f -> "偏细"
                            barWidth < 0.8f -> "标准"
                            barWidth < 0.95f -> "偏粗"
                            else -> "拉满"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = accentPreview
                    )
                }
                SonanceSlider(
                    value = ((barWidth - 0.25f) / 0.75f).coerceIn(0f, 1f),
                    onValueChange = { onBarWidth(0.25f + it * 0.75f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackHeight = 3.dp,
                    thumbSize = 11.dp,
                    alwaysShowThumb = true
                )
            }

            // 仅直立柱样式下显示底部反射开关
            if (style == VizStyle.BARS) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "底部反射",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.textMuted
                    )
                    Box(
                        Modifier
                            .size(width = 42.dp, height = 24.dp)
                            .clip(CircleShape)
                            .background(if (mirror) skin.accent else skin.surface3)
                            .clickable { onMirror(!mirror) },
                        contentAlignment = if (mirror) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (mirror) skin.onAccent else skin.textDim)
                        )
                    }
                }
            }

            // 显示帧率：排查性能用，左下角一个角标
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "显示帧率",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.textMuted
                    )
                    Text(
                        "左下角显示实际 fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textDim
                    )
                }
                Box(
                    Modifier
                        .size(width = 42.dp, height = 24.dp)
                        .clip(CircleShape)
                        .background(if (showFps) skin.accent else skin.surface3)
                        .clickable { onShowFps(!showFps) },
                    contentAlignment = if (showFps) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (showFps) skin.onAccent else skin.textDim)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) skin.accent else skin.surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) skin.onAccent else skin.textDim
        )
    }
}
