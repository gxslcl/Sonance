package com.sonance.player.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹输入链路的纯函数：重采样 + 指纹窗抽取。
 *
 * 这两条错了**不会报错**，只会让网易云识别不出歌 —— 指纹差一个采样点的
 * 抽取方式，识别率就完全两样。所以把它们和参考实现（toNCMBuffer）的
 * 数学逐项钉死。
 */
class PcmExtractorTest {

    // ===== linearResample =====

    @Test
    fun `同采样率原样返回`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(input, PcmExtractor.linearResample(input, 48_000, 48_000))
    }

    @Test
    fun `空数组安全返回`() {
        assertEquals(0, PcmExtractor.linearResample(FloatArray(0), 44_100, 48_000).size)
    }

    @Test
    fun `输出长度按采样率比例换算`() {
        // 1 秒 44100 → 48000
        val out = PcmExtractor.linearResample(FloatArray(44_100), 44_100, 48_000)
        assertEquals(48_000, out.size)
    }

    @Test
    fun `线性插值取中间值`() {
        // 2 倍上采样：[0, 1] → 每两点之间插一个中点
        val out = PcmExtractor.linearResample(floatArrayOf(0f, 1f), 1, 2)
        assertEquals(4, out.size)   // 2 * 2 / 1
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(1f, out[2], 1e-6f)
        // 末尾超界按最后一个采样补
        assertEquals(1f, out[3], 1e-6f)
    }

    // ===== fingerprintSamples =====

    @Test
    fun `输出固定 48000 个值`() {
        val mono = FloatArray(PcmExtractor.TARGET_RATE * 12)   // 12 秒
        assertEquals(48_000, PcmExtractor.fingerprintSamples(mono).size)
    }

    @Test
    fun `从第 4 秒起每 6 个采样取 1 个`() {
        // 12 秒 48kHz：起点 = 4 * 48000 = 192000
        val rate = PcmExtractor.TARGET_RATE
        val mono = FloatArray(rate * 12) { it.toFloat() }
        val fp = PcmExtractor.fingerprintSamples(mono)
        // fp[i] 应等于 mono[192000 + i*6]
        val from = 4 * rate
        assertEquals(mono[from], fp[0], 1e-6f)
        assertEquals(mono[from + 6], fp[1], 1e-6f)
        assertEquals(mono[from + 47_999 * 6], fp[47_999], 1e-6f)
    }

    @Test
    fun `短音频起点退到全长减 6 秒`() {
        // 5 秒：起点 = (5-6 取 0) → 从 0 开始取
        val rate = PcmExtractor.TARGET_RATE
        val mono = FloatArray(rate * 5) { (it % 1000).toFloat() }
        val fp = PcmExtractor.fingerprintSamples(mono)
        assertEquals(mono[0], fp[0], 1e-6f)
        assertEquals(mono[6], fp[1], 1e-6f)
    }

    @Test
    fun `尾部越界补零而不是越界`() {
        // 1 秒：全程都在窗口外，起点退到 0，但只有 48000 个采样，
        // 抽到后面必然越界 → 补 0
        val rate = PcmExtractor.TARGET_RATE
        val mono = FloatArray(rate) { 0.7f }
        val fp = PcmExtractor.fingerprintSamples(mono)
        assertEquals(0.7f, fp[0], 1e-6f)
        // 48000 / 6 = 8000 → 第 8000 个起越界补零
        assertEquals(0f, fp[8_000], 1e-6f)
        assertEquals(0f, fp[47_999], 1e-6f)
    }

    @Test
    fun `toFingerprintSamples 一条龙：重采样后取窗`() {
        // 12 秒 24kHz：重采样不改变时长，到 48k 仍是 12 秒 → 起点 min(4, 12-6) = 4s
        val rate = 24_000
        val decoded = PcmExtractor.Decoded(FloatArray(rate * 12) { it.toFloat() }, rate)
        val fp = PcmExtractor.toFingerprintSamples(decoded)
        assertEquals(48_000, fp.size)
        // mono48k[i] = 原数组[floor(i*0.5)]（24k→48k 线性插值）
        // fp[1] = mono48k[192000 + 6] = 原[floor(192006/2)] = 原[96003]
        assertEquals(96_003f, fp[1], 1.0f)
    }
}
