package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 律动柱「逐频段自动增益 + 顶部余量」。
 *
 * 用户反馈：随便一首歌，很多柱子一起冲到顶部，整页显满。
 *
 * 根因有两层：
 * - 映射增益过大（2.4 时约 -21 dBFS 就饱和），饱和后又被硬截断成同一个 1.0；
 * - 每根柱子都拿**自己的**近期峰值归一化，于是每根柱子都会顶到自己的 100%。
 *
 * 现在改成「0.65 本柱 + 0.35 全频段」混合参考、压中段、82% 软顶。
 * 新的验收标准因此不是"低频高频一样高"，而是：
 * **顶端重新变成事件 —— 少数柱子到顶，其余按相对能量分层，且任何柱子都不超过 0.82。**
 */
class AudioVisualizerAutoGainTest {

    private fun viz(bars: Int = 8) = AudioVisualizer(bars)

    @Test
    fun `不再出现整片冲顶，峰值最多到 82`() {
        val v = viz()
        val n = 8
        val raw = FloatArray(n) { 5f }
        val peaks = FloatArray(n) { 5f }
        val out = FloatArray(n)

        v.applyAutoGain(raw, peaks, out)

        for (x in out) {
            assertTrue("输出 $x 超过了软顶 0.82", x <= 0.8201f)
            assertTrue("顶部余量被吃掉，输出只剩 $x", x >= 0.80f)
        }
    }

    @Test
    fun `顶端变得稀缺，而不是所有频段一起满格`() {
        val v = viz()
        val n = 8
        val raw = FloatArray(n) { if (it < n / 2) 0.9f else 0.15f }
        val peaks = raw.copyOf()
        val out = FloatArray(n)

        v.applyAutoGain(raw, peaks, out)

        val low = out[0]
        val high = out[n - 1]
        // 完全不平权（未归一化）时差距是 0.75；纯本柱归一化时差距是 0（一片平墙）。
        // 现在要的是"看得出层次，但不回到左重右轻"。
        val gap = kotlin.math.abs(low - high)
        assertTrue("层次被压平了（$low vs $high），又变成一片满", gap > 0.25f)
        assertTrue("差距过大，回到左重右轻（$low vs $high，差 $gap）", gap < 0.55f)
        assertTrue("低频应该接近顶部：$low", low > 0.75f)
        assertTrue("高频应该明显低于顶部：$high", high < 0.55f)
    }

    @Test
    fun `律动幅度在映射之后等比缩放`() {
        val v = viz()
        val raw = floatArrayOf(0.9f, 0.6f, 0.4f, 0.2f)
        val peaks = raw.copyOf()
        val full = FloatArray(raw.size)
        val half = FloatArray(raw.size)

        v.applyAutoGain(raw, peaks.copyOf(), full)
        v.applyAutoGain(raw, peaks.copyOf(), half, level = 0.5f)

        for (i in raw.indices) {
            assertEquals("第 $i 根柱子的幅度缩放不是线性", full[i] * 0.5f, half[i], 0.0001f)
        }
    }

    @Test
    fun `输出不会超过 1 且不会是负数`() {
        val v = viz()
        val raw = FloatArray(8) { 0.5f }
        val peaks = FloatArray(8) { 0.05f } // 峰值极小，触发"不归一化"分支
        val out = FloatArray(8)
        v.applyAutoGain(raw, peaks, out)
        for (x in out) assertTrue("输出 $x 越界", x in 0f..0.8201f)
    }

    @Test
    fun `峰值太小时不放大噪声`() {
        val v = viz()
        val raw = FloatArray(4) { 0.02f }
        val peaks = FloatArray(4) { 0.01f } // 低于 MIN_PEAK
        val out = FloatArray(4)
        v.applyAutoGain(raw, peaks, out)
        for (x in out) assertTrue("安静频段被放大了：$x > 0.02", x <= 0.021f)
    }

    @Test
    fun `峰值快起慢落`() {
        val v = viz()
        val peaks = FloatArray(1)
        val out = FloatArray(1)

        // 先冲到一个高值，峰值应立刻跟上
        v.applyAutoGain(floatArrayOf(0.8f), peaks, out)
        assertTrue("峰值应立刻跟到 0.8，实际 ${peaks[0]}", peaks[0] > 0.79f)

        // 随后信号消失，峰值应缓慢衰减而不是立刻归零
        v.applyAutoGain(floatArrayOf(0f), peaks, out)
        assertTrue("峰值衰减过快：${peaks[0]}", peaks[0] > 0.75f)
        assertTrue("峰值不该上升：${peaks[0]}", peaks[0] < 0.8f)
    }

    @Test
    fun `长时间静音后峰值会降下来`() {
        val v = viz()
        val peaks = FloatArray(1) { 0.9f }
        val out = FloatArray(1)
        // 连续 1000 帧静音（约 30 秒）
        repeat(1000) { v.applyAutoGain(floatArrayOf(0f), peaks, out) }
        assertTrue("峰值没有衰减，该频段会一直趴着：${peaks[0]}", peaks[0] < 0.01f)
    }

    @Test
    fun `静音输出为 0 且没有 NaN`() {
        val v = viz()
        val out = FloatArray(6)
        v.applyAutoGain(FloatArray(6), FloatArray(6) { 0.7f }, out)
        for (x in out) {
            assertEquals(0f, x, 0f)
            assertTrue(x.isFinite())
        }
    }

    @Test
    fun `空数组不会崩`() {
        val v = viz()
        v.applyAutoGain(FloatArray(0), FloatArray(0), FloatArray(0))
    }

    @Test
    fun `输出长度与输入一致`() {
        val v = viz()
        val raw = FloatArray(12) { 0.5f }
        val peaks = FloatArray(12)
        val out = FloatArray(12) { -1f }
        v.applyAutoGain(raw, peaks, out)
        assertEquals(12, out.size)
        for (x in out) assertTrue("有元素没被写入", x >= 0f)
    }
}
