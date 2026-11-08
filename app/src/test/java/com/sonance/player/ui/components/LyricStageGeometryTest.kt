package com.sonance.player.ui.components

import com.sonance.player.data.LyricStyle
import org.junit.Assert.*
import org.junit.Test

class LyricStageGeometryTest {
    @Test
    fun `four styles round trip and removed disc falls back to classic`() {
        assertEquals(4, LyricStyle.entries.size)
        LyricStyle.entries.forEach { assertEquals(it, LyricStyle.fromId(it.id)) }
        listOf(null, "", "tilted", "unknown").forEach {
            assertEquals(LyricStyle.CLASSIC, LyricStyle.fromId(it))
        }
    }

    @Test
    fun `fluid and vinyl lyrics move upward as playback advances`() {
        listOf(LyricStyle.FLUID, LyricStyle.VINYL).forEach { style ->
            val past = lyricStagePose(style, -1f, -100f, 1f, 1)
            val future = lyricStagePose(style, 1f, 100f, 1f, 3)
            assertTrue(past.y < 0f)
            assertTrue(future.y > 0f)
            assertEquals(past.opacity, future.opacity, 0.001f)
        }
    }

    @Test
    fun `spatial future approaches from above and past sinks below`() {
        val past = lyricStagePose(LyricStyle.SPATIAL, -1f, -100f, 1f, 1)
        val focus = lyricStagePose(LyricStyle.SPATIAL, 0f, 0f, 1f, 2)
        val future = lyricStagePose(LyricStyle.SPATIAL, 1f, 100f, 1f, 3)
        assertTrue(past.y > 0f)
        assertTrue(future.y < 0f)
        assertEquals(0f, focus.y, 0f)
        assertEquals(0f, focus.x, 0f)
        assertEquals(1f, focus.opacity, 0f)
        assertTrue(past.scale < focus.scale)
        assertTrue(future.scale < focus.scale)
        assertTrue(past.opacity < future.opacity)
    }

    @Test
    fun `stage poses are bounded and finite throughout transitions`() {
        for (style in LyricStyle.entries) {
            for (step in -80..80) {
                val diff = step / 10f
                val p = lyricStagePose(style, diff, diff * 100f, 3f, step)
                assertTrue(p.opacity in 0f..1f)
                assertTrue(p.emphasis in 0f..1f)
                assertTrue(p.scale > 0f && p.scale.isFinite())
                assertTrue(p.x.isFinite() && p.y.isFinite())
                assertTrue(p.blur >= 0f && p.blur.isFinite())
                if (kotlin.math.abs(diff) >= 4f && style != LyricStyle.CLASSIC) {
                    assertEquals(0f, p.opacity, 0f)
                }
            }
        }
    }

    @Test
    fun `fluid has increasing optical depth and vinyl has opposite arc angles`() {
        val focus = lyricStagePose(LyricStyle.FLUID, 0f, 0f, 1f, 0)
        val near = lyricStagePose(LyricStyle.FLUID, 1f, 76f, 1f, 1)
        val far = lyricStagePose(LyricStyle.FLUID, 3f, 228f, 1f, 3)
        assertTrue(focus.scale > near.scale && near.scale > far.scale)
        assertTrue(focus.opacity > near.opacity && near.opacity > far.opacity)
        assertTrue(focus.blur < near.blur && near.blur < far.blur)
        val past = lyricStagePose(LyricStyle.VINYL, -1f, -56f, 1f, 1)
        val future = lyricStagePose(LyricStyle.VINYL, 1f, 56f, 1f, 3)
        assertEquals(-past.rotationZ, future.rotationZ, 0f)
        assertTrue(past.x < 0f && future.x < 0f)
    }

    @Test
    fun `multiline translations and large fonts reserve sufficient row spacing`() {
        val heights = listOf(40f, 150f, 280f, 60f)
        val centers = lyricStageCenters(heights, 76f, 18f)
        for (i in 1 until centers.size) {
            assertTrue(centers[i] - centers[i - 1] >= (heights[i] + heights[i - 1]) / 2f + 18f)
        }
        assertEquals((centers[1] + centers[2]) / 2f, lyricStageAnchor(centers, 1.5f), 0.001f)
        assertEquals(centers.first(), lyricStageAnchor(centers, -1f), 0f)
        assertEquals(centers.last(), lyricStageAnchor(centers, 100f), 0f)
        assertEquals(0f, lyricStageAnchor(emptyList(), 0f), 0f)
        assertTrue(lyricStageCenters(emptyList(), 76f, 18f).isEmpty())
    }

    @Test
    fun `progress follows the real line interval and real track end`() {
        assertEquals(0f, lyricLineProgress(10_000, 16_000, 30_000, 9_000), 0f)
        assertEquals(0.5f, lyricLineProgress(10_000, 16_000, 30_000, 13_000), 0f)
        assertEquals(1f, lyricLineProgress(10_000, 16_000, 30_000, 20_000), 0f)
        assertEquals(0.5f, lyricLineProgress(20_000, null, 30_000, 25_000), 0f)
        assertEquals(0f, lyricLineProgress(20_000, null, 0, 25_000), 0f)
    }

    @Test
    fun `duet timestamps use the next distinct start`() {
        assertEquals(listOf(2000L, 2000L, 4000L, null, null),
            lyricNextTimes(listOf(1000L, 1000L, 2000L, 4000L, 4000L)))
        assertEquals(listOf<Long?>(null), lyricNextTimes(listOf(1000L)))
        assertTrue(lyricNextTimes(emptyList()).isEmpty())
    }
}
