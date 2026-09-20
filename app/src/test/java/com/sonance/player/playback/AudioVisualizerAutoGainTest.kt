package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 律动柱「逐频段自动增益」。
 *
 * 用户反馈：音乐里低频能量天然占优，所以左边几根柱子总是跳得最凶、右边几乎不动，
 * 看起来"重心偏左、不平衡"。这里的归一化就是为了把它拉平。
 *
 * 关键性质：**在各自峰值附近，不同频段应该输出接近的高度** —— 这正是"平衡"的定义。
 */
class AudioVisualizerAutoGainTest {

    private fun viz(bars: Int = 8) = AudioVisualizer(bars)

    @Test
    fun `低频高能量与高频低能量归一化后输出接近`() {
        val v = viz()
        val n = 8
        val raw = FloatArray(n)
        val peaks = FloatArray(n)
        val out = FloatArray(n)

        // 模拟真实音乐：低频 0.9，高频 0.15，各频段都"顶到自己的满格"
        for (i in 0 until n) {
            val full = if (i < n / 2) 0.9f else 0.15f
            raw[i] = full
            peaks[i] = full // 预置峰值 = 当前值，代表"该频段正在自己的最大值"
        }
        v.applyAutoGain(raw, peaks, out)

        val low = out[0]
        val high = out[n - 1]
        // 没做归一化时这两个值就是原始输入：0.90 与 0.15，差 0.75 —— 那正是"左重右轻"的来源。
        assertTrue(
            "低频 $low 与高频 $high 差距过大（未归一化时是 0.90/0.15，差 0.75）",
            kotlin.math.abs(low - high) < 0.12f
        )
    }

    @Test
    fun `输出不会超过 1`() {
        val v = viz()
        val raw = FloatArray(8) { 0.5f }
        val peaks = FloatArray(8) { 0.05f } // 峰值极小，触发"不归一化"分支
        val out = FloatArray(8)
        v.applyAutoGain(raw, peaks, out)
        for (x in out) assertTrue("输出 $x 越界", x in 0f..1f)
    }

    @Test
    fun `峰值太小时不放大噪声`() {
        val v = viz()
        val raw = FloatArray(4) { 0.02f }
        val peaks = FloatArray(4) { 0.01f } // 低于 MIN_PEAK
        val out = FloatArray(4)
        v.applyAutoGain(raw, peaks, out)
        // 不做归一化 → 输出应贴近原始值（留了 28% 原始动态，所以不会超过原值）
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
