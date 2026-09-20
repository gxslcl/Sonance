package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 律动柱「频段映射」的单元测试。
 *
 * 钉死一个真实问题：频段边界原先直接 `toInt()` 量化，低频端好几根柱子会落在
 * **同一个 FFT bin** 上 → 高度完全相同 → 视觉上糊成一块，
 * 用户看到的就是"左边的柱子比右边明显少"。
 *
 * 实测（修复前）：
 * - 48 柱：前 6 根都映射到 bin 1，20% 的柱子与邻居重复
 * - 96 柱：前 12 根都是 bin 1，31% 重复，且全在左侧
 */
class AudioVisualizerBandsTest {

    /** 设置页里允许的全部柱数档位 */
    private val supportedBarCounts = listOf(24, 32, 48, 64, 96)

    @Test
    fun `每个档位的频段边界都严格递增`() {
        for (bars in supportedBarCounts) {
            val edges = AudioVisualizer(bars).bandEdgesSnapshot()
            assertEquals("边界数应为 柱数+1", bars + 1, edges.size)

            var i = 1
            while (i < edges.size) {
                assertTrue(
                    "$bars 柱：第 ${i - 1} 与第 $i 个边界相同（${edges[i]}），" +
                        "这两根柱子高度会完全一样，视觉上会糊成一块",
                    edges[i] > edges[i - 1]
                )
                i++
            }
        }
    }

    @Test
    fun `不会出现区间为空的柱子`() {
        for (bars in supportedBarCounts) {
            val edges = AudioVisualizer(bars).bandEdgesSnapshot()
            var b = 0
            while (b < bars) {
                assertTrue(
                    "$bars 柱：第 $b 根柱子的区间为空（${edges[b]}..${edges[b + 1]}），高度会恒为 0",
                    edges[b + 1] > edges[b]
                )
                b++
            }
        }
    }

    @Test
    fun `边界起点为 1 且终点覆盖到可用频段末端`() {
        for (bars in supportedBarCounts) {
            val edges = AudioVisualizer(bars).bandEdgesSnapshot()
            assertEquals("$bars 柱：起点应为 1（跳过 DC）", 1, edges[0])
            // usable = (fftN/2 * 3)/4 = (512 * 3)/4 = 384
            assertEquals("$bars 柱：终点应覆盖到 usable", 384, edges[bars])
        }
    }

    @Test
    fun `柱数越少单根柱子覆盖的频段越宽`() {
        val few = AudioVisualizer(24).bandEdgesSnapshot()
        val many = AudioVisualizer(96).bandEdgesSnapshot()
        assertTrue(
            "24 柱的首段（${few[1] - few[0]}）应不窄于 96 柱的首段（${many[1] - many[0]}）",
            (few[1] - few[0]) >= (many[1] - many[0])
        )
    }

    @Test
    fun `柱数超出上限时被夹到 128 且仍然严格递增`() {
        val edges = AudioVisualizer(500).bandEdgesSnapshot()
        assertEquals(129, edges.size)
        var i = 1
        while (i < edges.size) {
            assertTrue("边界必须严格递增", edges[i] > edges[i - 1])
            i++
        }
    }
}
