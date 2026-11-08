package com.sonance.player.ui.components

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.data.LyricLine
import com.sonance.player.data.LyricStyle
import com.sonance.player.data.indexAt
import com.sonance.player.ui.theme.Skin
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** Three native lyric stages; the full-screen host still owns background and controls. */
@Composable
internal fun FullscreenThemedLyrics(
    style: LyricStyle,
    lines: List<LyricLine>,
    positionMs: Long,
    durationMs: Long,
    timelineOffsetMs: Long,
    isPlaying: Boolean,
    fontSizeSp: Int,
    onSeek: (Long) -> Unit,
    bgProfile: BgProfile?,
    adaptMode: LyricAdaptMode,
    skin: Skin
) {
    if (lines.isEmpty()) return
    // Switching tracks/styles resets the stage and frame clock, not the saved settings.
    key(lines, style) {
        val smoothPos = rememberSmoothPosition(positionMs, isPlaying, alignmentKey = timelineOffsetMs)
        val nextTimes = remember(lines) { lyricNextTimes(lines.map { it.timeMs }) }
        val current by remember(smoothPos) { derivedStateOf { lines.indexAt(smoothPos.longValue) } }
        val anchor = remember { Animatable(current.coerceAtLeast(0).toFloat()) }
        LaunchedEffect(current) {
            val target = current.coerceAtLeast(0).toFloat()
            if (abs(target - anchor.value) > 3f) {
                anchor.snapTo(target) // Seeking must not fly through hundreds of old lines.
            } else if (style == LyricStyle.SPATIAL) {
                anchor.animateTo(target, tween(900, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
            } else {
                anchor.animateTo(target, spring(dampingRatio = 0.85f, stiffness = 140f))
            }
        }
        val motion = rememberStageMotion(isPlaying && style != LyricStyle.FLUID)
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.1f to Color.Black,
                            0.9f to Color.Black, 1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            // Scale prototype geometry to the current viewport; font size remains a user setting.
            val unit = w / 336f
            val left = when (style) {
                LyricStyle.VINYL -> w * 0.35f
                LyricStyle.FLUID -> with(density) { 24.dp.toPx() }
                else -> w * 0.04f
            }
            val textWidth = (w - left - if (style == LyricStyle.SPATIAL) w * 0.04f else 16f * unit)
                .coerceAtLeast(1f)
            val textWidthDp = with(density) { textWidth.toDp() }
            val font = fontSizeSp * when (style) {
                LyricStyle.FLUID -> 1.3f
                LyricStyle.SPATIAL -> 1.15f
                else -> 1f
            }
            val mainStyle = TextStyle(fontSize = font.sp, lineHeight = (font * 1.38f).sp, fontWeight = FontWeight.ExtraBold)
            val translationStyle = TextStyle(fontSize = (fontSizeSp * 0.72f).sp, lineHeight = (fontSizeSp * 1.01f).sp)
            val reflectionStyle = TextStyle(fontSize = (font * 0.92f).sp, lineHeight = (font * 1.27f).sp,
                fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            val reflectionHeights = remember(lines, textWidth, fontSizeSp, density, style) {
                if (style == LyricStyle.SPATIAL) lines.map {
                    measurer.measure(AnnotatedString(it.text), reflectionStyle,
                        constraints = Constraints(maxWidth = textWidth.toInt().coerceAtLeast(1))).size.height.toFloat()
                } else List(lines.size) { 0f }
            }
            val heights = remember(lines, textWidth, fontSizeSp, density, style) {
                lines.mapIndexed { index, line ->
                    val mainWidth = if (style == LyricStyle.VINYL) {
                        textWidth - with(density) { 46.dp.toPx() }
                    } else textWidth
                    val main = measurer.measure(AnnotatedString(line.text), mainStyle,
                        constraints = Constraints(maxWidth = mainWidth.toInt().coerceAtLeast(1))).size.height
                    val translation = line.translation?.let {
                        measurer.measure(AnnotatedString(it), translationStyle,
                            constraints = Constraints(maxWidth = textWidth.toInt().coerceAtLeast(1))).size.height
                    } ?: 0
                    with(density) {
                        main + translation + when (style) {
                            LyricStyle.FLUID -> 34.dp.toPx()
                            LyricStyle.SPATIAL -> 32.dp.toPx() + reflectionHeights[index] * 0.55f
                            else -> 18.dp.toPx()
                        }
                    }
                }
            }
            val centers = remember(heights, unit, style) {
                lyricStageCenters(heights, (if (style == LyricStyle.FLUID) 76f else 56f) * unit, 18f * unit)
            }
            val a = anchor.value
            val anchorCenter = lyricStageAnchor(centers, a)
            val from = maxOf(0, floor(a).toInt() - 4)
            val to = minOf(lines.lastIndex, ceil(a).toInt() + 4)

            when (style) {
                LyricStyle.VINYL -> VinylStageDecoration(skin, motion, unit)
                LyricStyle.SPATIAL -> SpatialStageDecoration(skin, motion, unit)
                else -> Unit
            }
            for (index in from..to) {
                key(index) {
                    val line = lines[index]
                    val pose = lyricStagePose(style, index - a, centers[index] - anchorCenter, unit, index)
                    val isCurrent = index == current
                    val emphasis = if (current >= 0) pose.emphasis else 0f
                    val base = lerp(skin.textDim, skin.accent, emphasis)
                    val y = h * (if (style == LyricStyle.FLUID) 0.44f else 0.5f) + pose.y
                    val color = if (bgProfile != null && shouldAdapt(isCurrent, adaptMode)) {
                        lyricColor(base, y / h, isCurrent, bgProfile)
                    } else base
                    // Quantize/cap blur and only render the seven nearby rows (API 24-30 uses fading).
                    val blur = round(pose.blur / (unit * 0.5f)) * unit * 0.5f
                    val effect = remember(blur) {
                        if (Build.VERSION.SDK_INT >= 31 && blur > 0f) {
                            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL).asComposeRenderEffect()
                        } else null
                    }
                    val centered = style == LyricStyle.SPATIAL
                    Column(
                        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                        modifier = Modifier.align(Alignment.CenterStart).width(textWidthDp)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
                                translationX = left + pose.x
                                translationY = y - h / 2f
                                scaleX = pose.scale
                                scaleY = pose.scale
                                rotationZ = pose.rotationZ
                                rotationX = pose.rotationX
                                cameraDistance = 700f * unit
                                alpha = pose.opacity
                                renderEffect = effect
                            }
                            .zIndex(emphasis + 2f)
                            .then(if (pose.opacity > 0.05f) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null
                            ) { onSeek(line.timeMs) } else Modifier)
                            .padding(vertical = 4.dp)
                    ) {
                        if (style != LyricStyle.VINYL) {
                            Text(
                                "${stageTime(line.timeMs)} · ${if (centered) "SPATIAL STAGE" else "VOCAL FOCUS"}",
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = skin.accent.copy(alpha = emphasis),
                                modifier = Modifier.padding(bottom = 4.dp).clearAndSetSemantics { }
                            )
                        }
                        if (style == LyricStyle.VINYL) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text(stageTime(line.timeMs), fontSize = 10.sp,
                                    color = skin.accent, modifier = Modifier.padding(top = 5.dp))
                                StageWords(line, isCurrent, smoothPos, color, font, emphasis, false,
                                    Modifier.weight(1f))
                            }
                        } else {
                            StageWords(line, isCurrent, smoothPos, color, font, emphasis, centered)
                        }
                        line.translation?.let {
                            Text(it, style = translationStyle, color = color.copy(alpha = 0.55f + 0.3f * emphasis),
                                textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                        }
                        when (style) {
                            LyricStyle.FLUID -> {
                                Spacer(Modifier.height(8.dp))
                                LineProgressBeam(line, nextTimes[index],
                                    durationMs, smoothPos, emphasis, skin)
                            }
                            LyricStyle.SPATIAL -> {
                                Spacer(Modifier.height(8.dp))
                                val reflectionHeight = with(density) { reflectionHeights[index].toDp() }
                                Box(Modifier.fillMaxWidth().height(reflectionHeight * 0.55f).clearAndSetSemantics { }) {
                                    Text(line.text, style = reflectionStyle, color = skin.accent,
                                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                                            .requiredHeight(reflectionHeight)
                                            .graphicsLayer {
                                                transformOrigin = TransformOrigin(0.5f, 0f)
                                                scaleY = -0.55f
                                                translationY = size.height * 0.55f
                                                alpha = emphasis * 0.3f
                                                compositingStrategy = CompositingStrategy.Offscreen
                                            }
                                            .drawWithContent {
                                                drawContent()
                                                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                                                    blendMode = BlendMode.DstIn)
                                            })
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageWords(
    line: LyricLine, isCurrent: Boolean, smoothPos: LongState, color: Color,
    font: Float, emphasis: Float, centered: Boolean, modifier: Modifier = Modifier
) {
    Column(modifier) {
        val weight = if (emphasis > 0.5f) FontWeight.ExtraBold else FontWeight.Medium
        if (isCurrent && line.segments != null) {
            KaraokeLineText(line, smoothPos, color.copy(alpha = 0.38f), color,
                font.sp, (font * 1.38f).sp, weight,
                if (centered) TextAlign.Center else TextAlign.Start)
        } else {
            Text(line.text, color = color,
                style = TextStyle(fontSize = font.sp, lineHeight = (font * 1.38f).sp,
                    fontWeight = weight,
                    shadow = Shadow(color.copy(alpha = emphasis * 0.5f), Offset.Zero, 18f * emphasis)),
                textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        }
    }
}

@Composable
private fun LineProgressBeam(
    line: LyricLine, nextMs: Long?, durationMs: Long, smoothPos: LongState, emphasis: Float, skin: Skin
) {
    Canvas(Modifier.fillMaxWidth(0.72f).height(3.dp).clearAndSetSemantics { }) {
        if (emphasis <= 0f) return@Canvas
        val progress = lyricLineProgress(line.timeMs, nextMs, durationMs, smoothPos.longValue)
        val end = size.width * progress
        val brush = Brush.horizontalGradient(listOf(skin.accent, lerp(skin.accent, skin.text, 0.45f)))
        drawRoundRect(brush, size = Size(end, size.height), alpha = emphasis,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
    }
}

private fun stageTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000
    return "${(sec / 60).toString().padStart(2, '0')}:${(sec % 60).toString().padStart(2, '0')}"
}

/** Decorative movement pauses without resetting its phase; values are read only by Canvas. */
@Composable
private fun rememberStageMotion(isPlaying: Boolean): FloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time.floatValue = (time.floatValue + ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.08f)) % 420f
            previous = now
        }
    }
    return time
}

@Composable
private fun VinylStageDecoration(skin: Skin, motion: FloatState, unit: Float) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val center = Offset(-45f * unit, size.height / 2f)
        val radius = 140f * unit
        drawCircle(Color.White.copy(alpha = 0.02f), radius + 72f * unit, center)
        drawCircle(Color.White.copy(alpha = 0.03f), radius + 32f * unit, center)
        drawCircle(Color(0xFF121318), radius, center)
        for (r in 64..138 step 6) {
            drawCircle(Color(0xFF30323C), r * unit, center, style = Stroke(unit))
        }
        drawCircle(Color.White.copy(alpha = 0.14f), radius, center, style = Stroke(unit))
        rotate(motion.floatValue * (360f / 14f), center) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF4EFE1), Color(0xFFDCD0B8)), center, 59f * unit),
                59f * unit, center)
            drawLine(skin.accent.copy(alpha = 0.5f), center + Offset(20f * unit, -20f * unit),
                center + Offset(42f * unit, -42f * unit), 12f * unit)
            drawCircle(Color(0xFF141418), 9f * unit, center)
        }
        val needle = Offset(82f * unit, center.y)
        drawLine(skin.accent.copy(alpha = 0.18f), needle, needle + Offset(24f * unit, 0f), 10f * unit)
        drawLine(skin.accent, needle, needle + Offset(24f * unit, 0f), 2f * unit)
        drawCircle(skin.accent.copy(alpha = 0.18f), 10f * unit, needle + Offset(24f * unit, 0f))
        drawCircle(lerp(skin.accent, skin.text, 0.7f), 4f * unit, needle + Offset(24f * unit, 0f))
    }
}

@Composable
private fun SpatialStageDecoration(skin: Skin, motion: FloatState, unit: Float) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val horizon = Offset(size.width / 2f, size.height * 0.63f)
        val gridColor = skin.text.copy(alpha = 0.055f)
        for (i in -7..7) {
            drawLine(gridColor, horizon + Offset(i * 8f * unit, 0f),
                Offset(size.width / 2f + i * 65f * unit, size.height), unit)
        }
        val phase = (motion.floatValue / 6f) % 1f
        for (i in 0..9) {
            val depth = (i + phase) / 10f
            val y = horizon.y + depth * depth * (size.height - horizon.y)
            drawLine(gridColor.copy(alpha = depth * 0.055f), Offset(0f, y), Offset(size.width, y), unit)
        }
        val radius = 130f * unit
        drawOval(Brush.radialGradient(listOf(skin.accent.copy(alpha = 0.18f), Color.Transparent),
            Offset(size.width / 2f, size.height / 2f + 30f * unit), radius),
            topLeft = Offset(size.width / 2f - radius, size.height / 2f + 5f * unit),
            size = Size(radius * 2f, 50f * unit))
    }
}
