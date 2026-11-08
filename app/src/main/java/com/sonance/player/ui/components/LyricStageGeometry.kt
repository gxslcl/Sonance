package com.sonance.player.ui.components

import com.sonance.player.data.LyricStyle
import kotlin.math.abs
import kotlin.math.pow

/** Pure stage geometry. Distances use pixels; unit is the prototype's viewport scale. */
internal data class LyricStagePose(
    val x: Float,
    val y: Float,
    val scale: Float,
    val opacity: Float,
    val blur: Float,
    val rotationZ: Float = 0f,
    val rotationX: Float = 0f,
    val emphasis: Float = 0f
)

internal fun lyricStagePose(
    style: LyricStyle,
    diff: Float,
    distance: Float,
    unit: Float,
    index: Int
): LyricStagePose {
    val a = abs(diff)
    val t = (1f - a).coerceIn(0f, 1f)
    val emphasis = t * t * (3f - 2f * t)
    // A fourth, invisible row is retained during transitions to avoid edge popping.
    val edge = (4f - a).coerceIn(0f, 1f)
    return when (style) {
        LyricStyle.FLUID -> LyricStagePose(
            x = 0f, y = distance,
            scale = (1f - a * 0.08f).coerceAtLeast(0.76f) * (0.77f + 0.23f * emphasis),
            opacity = (0.7f - a * 0.2f).coerceAtLeast(0.14f) * edge + 0.3f * emphasis,
            blur = (a * 1.5f).coerceAtMost(5.5f) * unit,
            emphasis = emphasis
        )
        LyricStyle.VINYL -> LyricStagePose(
            x = -a.pow(1.45f) * 9f * unit, y = distance,
            scale = 0.775f + 0.225f * emphasis,
            opacity = ((0.75f - a * 0.22f).coerceAtLeast(0.16f) + 0.25f * emphasis) * edge,
            blur = 0f, rotationZ = diff * 10.5f, emphasis = emphasis
        )
        LyricStyle.SPATIAL -> {
            val future = diff > 0f
            val z = -a * (if (future) 130f else 120f) + 35f * emphasis
            val perspective = 700f / (700f - z)
            val side = if (index % 2 == 0) 1f else -1f
            LyricStagePose(
                x = side * (if (future) 12f else -10f) * a * unit * perspective,
                y = -distance * perspective,
                scale = (1f - a * 0.07f).coerceAtLeast(0.7f) * perspective,
                opacity = ((if (future) 0.78f - a * 0.18f else 0.55f - a * 0.15f)
                    .coerceAtLeast(0.1f) * (1f - emphasis) + emphasis) * edge,
                blur = (a * 1.1f).coerceAtMost(4.5f) * unit,
                rotationX = if (future) -a * 4f else 12f * (1f - emphasis),
                emphasis = emphasis
            )
        }
        LyricStyle.CLASSIC -> LyricStagePose(0f, distance, 1f, 1f, 0f, emphasis = emphasis)
    }
}

/** Interpolating a cumulative height table keeps multiline originals/translations apart. */
internal fun lyricStageCenters(heights: List<Float>, minimumSlot: Float, gap: Float): List<Float> {
    if (heights.isEmpty()) return emptyList()
    val centers = ArrayList<Float>(heights.size)
    centers.add(0f)
    for (i in 1 until heights.size) {
        centers.add(centers.last() + maxOf(minimumSlot, (heights[i - 1] + heights[i]) / 2f + gap))
    }
    return centers
}

internal fun lyricStageAnchor(centers: List<Float>, anchor: Float): Float {
    if (centers.isEmpty()) return 0f
    val bounded = anchor.coerceIn(0f, centers.lastIndex.toFloat())
    val i = bounded.toInt()
    val fraction = bounded - i
    return centers[i] + ((centers.getOrNull(i + 1) ?: centers[i]) - centers[i]) * fraction
}

internal fun lyricLineProgress(startMs: Long, nextMs: Long?, durationMs: Long, positionMs: Long): Float {
    // The last line uses the real track end, not the prototype's fixed 3.2-second timer.
    val end = nextMs?.takeIf { it > startMs } ?: durationMs
    if (end <= startMs) return 0f // Unknown duration: do not invent a fake full progress bar.
    return ((positionMs - startMs).toDouble() / (end - startMs)).toFloat().coerceIn(0f, 1f)
}

/** Find the next distinct timestamp in a sorted timeline, including duet/same-time rows. */
internal fun lyricNextTimes(times: List<Long>): List<Long?> {
    val out = MutableList<Long?>(times.size) { null }
    var next: Long? = null
    for (i in times.lastIndex downTo 0) {
        if (i < times.lastIndex && times[i + 1] > times[i]) next = times[i + 1]
        out[i] = next
    }
    return out
}
