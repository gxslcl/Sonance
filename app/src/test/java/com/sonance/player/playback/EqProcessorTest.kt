package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 均衡器的 DSP 验证。
 *
 * 这些用例不是"跑一遍不崩"，而是**量响应**：
 * 拿 [designPeakingCoeffs] 算出系数，喂一段正弦波进 [EqCascade]，
 * 量输出 RMS 并换算成 dB，看它是不是真的等于设定值。
 *
 * 之所以要这样测：均衡器最容易出的错不是崩溃，而是"滑杆动了、声音没变"
 * 或者"增益反了 / 频点错位"—— 那种 bug 靠肉眼审查代码基本抓不到。
 */
class EqProcessorTest {

    private val fs = 44100

    /** 处理一段正弦波，返回稳定段的 RMS（跳过前 1/5，避开滤波器启动暂态） */
    private fun rms(cas: EqCascade, freqHz: Double, frames: Int = 8192): Float {
        val buf = FloatArray(frames)
        for (i in 0 until frames) {
            buf[i] = (0.5 * sin(2.0 * PI * freqHz * i / fs)).toFloat()
        }
        cas.process(buf, frames)
        val from = frames / 5
        var sum = 0.0
        for (i in from until frames) sum += buf[i].toDouble() * buf[i].toDouble()
        return sqrt(sum / (frames - from)).toFloat()
    }

    private fun db(ratio: Float): Double = 20.0 * ln(ratio.toDouble()) / ln(10.0)

    /** 单段（可指定频点/增益）的滤波器，用来单独量某一段的响应 */
    private fun single(freqHz: Int, gainDb: Float): EqCascade =
        EqCascade(bandCount = 1, channels = 1).also {
            it.design(intArrayOf(freqHz), floatArrayOf(gainDb), fs)
        }

    /** 直通参考：同样是单段滤波器，但增益为 0，用来消掉"输入本身有多少能量" */
    private fun flat(bandCount: Int = 1): EqCascade =
        EqCascade(bandCount, 1).also {
            it.design(EQ_CENTER_FREQS.copyOf(bandCount), FloatArray(bandCount), fs)
        }

    // ===== 系数设计 =====

    @Test
    fun `零增益返回单位冲激`() {
        val c = designPeakingCoeffs(1000f, 0f, fs)
        assertEquals(1f, c[0], 1e-6f)
        for (i in 1..4) assertEquals(0f, c[i], 1e-6f)
    }

    @Test
    fun `超过奈奎斯特频率返回单位冲激`() {
        // 22.05kHz 采样时 16kHz 段已经越过奈奎斯特，必须直通而不是乱滤波
        val c = designPeakingCoeffs(16000f, 6f, 22050)
        assertEquals(1f, c[0], 1e-6f)
        assertEquals(0f, c[1], 1e-6f)
    }

    @Test
    fun `采样率为零不崩且直通`() {
        val c = designPeakingCoeffs(1000f, 6f, 0)
        assertEquals(1f, c[0], 1e-6f)
    }

    // ===== 频响（核心）=====

    @Test
    fun `中心频率处增益等于设定值`() {
        for (gain in listOf(-9f, -6f, -3f, 3f, 6f, 9f)) {
            val measured = db(rms(single(1000, gain), 1000.0) / rms(flat(), 1000.0))
            assertEquals("1kHz 设定 ${gain}dB，实测 $measured dB", gain.toDouble(), measured, 0.6)
        }
    }

    @Test
    fun `负增益是真的衰减`() {
        val measured = db(rms(single(1000, -9f), 1000.0) / rms(flat(), 1000.0))
        assertTrue("设定 -9dB，实测 $measured dB", measured < -8.0)
    }

    @Test
    fun `远离中心频率处基本不受影响`() {
        // 1000Hz 抬 9dB，钟形滤波器的尾巴对 60Hz 的影响应该很小
        val measured = db(rms(single(1000, 9f), 60.0) / rms(flat(), 60.0))
        assertTrue("60Hz 被抬了 $measured dB，应该接近 0", abs(measured) < 2.0)
    }

    @Test
    fun `多段级联在各自频点上互不干扰`() {
        // 完整 10 段曲线：只在 62Hz 抬 8dB，其余为 0
        val gains = FloatArray(EQ_BAND_COUNT)
        gains[1] = 8f
        val cas = EqCascade(EQ_BAND_COUNT, 1)
        cas.design(EQ_CENTER_FREQS, gains, fs)
        val reference = flat(EQ_BAND_COUNT)

        val at62 = db(rms(cas, 62.0) / rms(reference, 62.0))
        val at16k = db(rms(cas, 16000.0) / rms(reference, 16000.0))

        assertEquals("62Hz 应抬 8dB，实测 $at62", 8.0, at62, 1.0)
        assertTrue("16kHz 不该被 62Hz 段影响，实测 $at16k", abs(at16k) < 1.5)
    }

    @Test
    fun `全零曲线输出等于输入`() {
        val cas = EqCascade(EQ_BAND_COUNT, 2)
        cas.design(EQ_CENTER_FREQS, FloatArray(EQ_BAND_COUNT), fs)
        val frames = 1024
        val buf = FloatArray(frames * 2) { (sin(2.0 * PI * 440.0 * it / fs) * 0.4).toFloat() }
        val copy = buf.copyOf()
        cas.process(buf, frames)
        // 10 段全是 0dB，级联之后必须原样透传（容差给浮点累计误差）
        for (i in copy.indices) {
            assertEquals("第 $i 个采样被改动了", copy[i], buf[i], 1e-4f)
        }
    }

    @Test
    fun `立体声两个声道状态互相独立`() {
        val cas = EqCascade(bandCount = 1, channels = 2).also {
            it.design(intArrayOf(1000), floatArrayOf(9f), fs)
        }
        val frames = 2048
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            buf[i * 2] = (0.5 * sin(2.0 * PI * 1000.0 * i / fs)).toFloat()
            buf[i * 2 + 1] = 0f
        }
        cas.process(buf, frames)
        var rightEnergy = 0.0
        for (i in 0 until frames) rightEnergy += buf[i * 2 + 1].toDouble() * buf[i * 2 + 1]
        // 右声道没有输入，就绝不能有输出（否则说明串扰了）
        assertEquals(0.0, rightEnergy, 1e-9)
    }

    @Test
    fun `重置状态后不再残留上一段信号`() {
        val cas = single(1000, 9f)
        rms(cas, 1000.0, frames = 2048)
        cas.reset()
        // 重置后喂全零，输出必须是全零（DF2T 的状态被清干净了）
        val buf = FloatArray(512)
        cas.process(buf, 512)
        assertTrue("重置后仍有残留能量", buf.all { abs(it) < 1e-9f })
    }

    // ===== 预设 =====

    @Test
    fun `所有预设的曲线长度都等于频段数`() {
        EqPreset.entries.forEach { p ->
            assertEquals("预设 ${p.name} 长度不对", EQ_BAND_COUNT, EqPreset.curveOf(p).size)
        }
    }

    @Test
    fun `所有预设增益都在允许范围内`() {
        EqPreset.entries.forEach { p ->
            EqPreset.curveOf(p).forEach { v ->
                assertTrue("预设 ${p.name} 有越界值 $v", v >= EQ_MIN_DB && v <= EQ_MAX_DB)
            }
        }
    }

    @Test
    fun `关闭预设全为零`() {
        assertTrue(EqPreset.curveOf(EqPreset.OFF).all { it == 0f })
    }

    @Test
    fun `预设名解析容错`() {
        assertEquals(EqPreset.ROCK, EqPreset.fromName("ROCK"))
        assertEquals(EqPreset.OFF, EqPreset.fromName("不存在的预设"))
        assertEquals(EqPreset.OFF, EqPreset.fromName(null))
    }

    @Test
    fun `低音增强确实抬低频、高音增强确实抬高频`() {
        val bass = EqPreset.curveOf(EqPreset.BASS)
        assertTrue("低音增强的 31Hz 段应明显为正", bass[0] > 4f)
        assertTrue("低音增强的 16kHz 段应不为正", bass[EQ_BAND_COUNT - 1] <= 0f)

        val treble = EqPreset.curveOf(EqPreset.TREBLE)
        assertTrue("高音增强的 16kHz 段应明显为正", treble[EQ_BAND_COUNT - 1] > 4f)
        assertTrue("高音增强的 31Hz 段应为负", treble[0] < 0f)
    }

    /** 每个预设都必须真的能"听见变化"—— 全零曲线等于这个预设是废的 */
    @Test
    fun `除关闭外每个预设都有非零增益`() {
        EqPreset.entries.filter { it != EqPreset.OFF }.forEach { p ->
            assertTrue("预设 ${p.name} 全为零，等于没效果", EqPreset.curveOf(p).any { abs(it) > 0.5f })
        }
    }

    // ===== 总线 =====

    @Test
    fun `总线更新会补齐长度并推进版本号`() {
        EqBus.reset()
        val v0 = EqBus.revision
        EqBus.update(true, floatArrayOf(3f))   // 故意只给 1 个值
        assertEquals(EQ_BAND_COUNT, EqBus.gainsDb.size)
        assertEquals(3f, EqBus.gainsDb[0], 1e-6f)
        assertEquals(0f, EqBus.gainsDb[5], 1e-6f)
        assertEquals(v0 + 1, EqBus.revision)
        assertTrue(EqBus.enabled)
        EqBus.reset()
    }

    @Test
    fun `总线存入的是副本而不是引用`() {
        EqBus.reset()
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        EqBus.update(true, src)
        src[0] = 99f
        // 外部改了原数组不该影响总线里的值
        assertEquals(1f, EqBus.gainsDb[0], 1e-6f)
        EqBus.reset()
    }
}
