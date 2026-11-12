package com.sonance.player.ui.components

import com.sonance.player.data.LyricStyle
import org.junit.Assert.*
import org.junit.Test

class LyricStageGeometryTest {
    @Test
    fun `five styles round trip and removed disc falls back to classic`() {
        assertEquals(5, LyricStyle.entries.size)
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
                if (kotlin.math.abs(diff) >= 4f && style != LyricStyle.CLASSIC && style != LyricStyle.VERTICAL) {
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
    fun `center lookup is the exact inverse of the anchor`() {
        // 行高不等（长句折行 + 带翻译）才最能暴露"跟手忽快忽慢"的问题
        val centers = lyricStageCenters(listOf(40f, 150f, 280f, 60f, 96f), 76f, 18f)
        var step = 0
        while (step <= 400) {
            val index = step / 100f
            val y = lyricStageAnchor(centers, index)
            if (index in 0f..centers.lastIndex.toFloat()) {
                assertEquals("行号 $index 往返不一致", index, lyricIndexAtCenter(centers, y), 0.001f)
            }
            step++
        }
    }

    @Test
    fun `center lookup clamps to the first and last line`() {
        val centers = lyricStageCenters(listOf(40f, 80f, 60f), 76f, 18f)
        assertEquals(0f, lyricIndexAtCenter(centers, -1000f), 0f)
        assertEquals(0f, lyricIndexAtCenter(centers, centers.first()), 0f)
        assertEquals(2f, lyricIndexAtCenter(centers, centers.last() + 1000f), 0f)
        assertEquals(2f, lyricIndexAtCenter(centers, centers.last()), 0f)
    }

    @Test
    fun `center lookup is monotonic for uneven rows`() {
        val centers = lyricStageCenters(listOf(40f, 150f, 280f, 60f), 76f, 18f)
        var previous = -1f
        var y = -50f
        while (y <= centers.last() + 50f) {
            val index = lyricIndexAtCenter(centers, y)
            assertTrue("位置 $y 处行号回退了（$previous → $index）", index >= previous)
            assertTrue(index in 0f..centers.lastIndex.toFloat())
            previous = index
            y += 7f
        }
    }

    @Test
    fun `center lookup handles empty and single row tables`() {
        assertEquals(0f, lyricIndexAtCenter(emptyList(), 0f), 0f)
        assertEquals(0f, lyricIndexAtCenter(emptyList(), 500f), 0f)
        assertEquals(0f, lyricIndexAtCenter(listOf(120f), 500f), 0f)
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
