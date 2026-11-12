package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 只测纯 Kotlin 的 DSP：不含 Android / Media3 类，JVM 单测里可以直接喂正弦波。
 * 沿用 EqCascade 那套思路 —— 把音频算法抽出来，真的量输出幅度，而不是只看参数有没有写进去。
 */
class SoundFxDspTest {

    @Test
    fun `all built-in presets have valid curves and parameters`() {
        SoundPresets.ALL.forEach { p ->
            assertEquals("${p.id} 的 EQ 段数", EQ_BAND_COUNT, p.eqDb.size)
            p.eqDb.forEach { db ->
                assertTrue("${p.id} 的增益越界: $db", db >= EQ_MIN_DB && db <= EQ_MAX_DB)
            }
            assertEquals("${p.id} 的效果参数不该被 clamp 改写", p.fx, p.fx.clamp())
            assertTrue(p.id.isNotBlank())
            assertTrue(p.description.isNotBlank())
        }
        // 双扬外放档必须真的开了虚拟低音和限幅，否则"专门处理"就是空话
        val speaker = SoundPresets.DUAL_SPEAKER
        assertTrue(speaker.fx.virtualBass > 0f && speaker.fx.virtualBass <= 0.7f)
        assertTrue(speaker.fx.limiter)
        // 外放档不再靠前置增益"变小声"，而是靠切掉放不出的超低频腾余量
        assertTrue(speaker.eqDb[0] < -3f)
    }

    @Test
    fun `store round trip and dirty data clamp`() {
        val p = SoundFxParams(
            virtualBass = 0.7f, reverbWet = 0.2f, reverbDecay = 0.4f,
            stereoWidth = 1.2f, preampDb = -4f, limiter = true
        )
        val back = SoundFxParams.fromStore(SoundFxParams.toStore(p))
        assertEquals(p.virtualBass, back.virtualBass, 0.001f)
        assertEquals(p.reverbWet, back.reverbWet, 0.001f)
        assertEquals(p.stereoWidth, back.stereoWidth, 0.001f)
        assertEquals(p.preampDb, back.preampDb, 0.001f)
        assertEquals(p.limiter, back.limiter)

        assertEquals(SoundFxParams.NEUTRAL, SoundFxParams.fromStore(null))
        assertEquals(SoundFxParams.NEUTRAL, SoundFxParams.fromStore("1,2"))
        val dirty = SoundFxParams.fromStore("99,99,99,99,99,1,99")
        assertEquals(1f, dirty.virtualBass, 0.001f)
        assertEquals(0.6f, dirty.reverbWet, 0.001f)
        assertEquals(0f, dirty.preampDb, 0.001f)
    }

    @Test
    fun `virtual bass adds harmonic energy for a 60Hz sine`() {
        val sr = 48_000
        val frames = sr
        val buf = FloatArray(frames) { (0.5f * sin(2.0 * PI * 60.0 * it / sr)).toFloat() }
        VirtualBass(sr).process(buf, frames, 1, 0.7f)
        val fundamental = toneAmplitude(buf, sr, 60.0, sr / 4)
        val harmonic = toneAmplitude(buf, sr, 120.0, sr / 4)
        assertEquals("不应破坏原低频", 0.5, fundamental, 0.01)
        assertTrue("应补充少量二次谐波: $harmonic", harmonic in 0.002..0.1)
        assertTrue("不能出现 NaN", buf.all { it.isFinite() })
    }

    @Test
    fun `virtual bass fills in harmonics a phone speaker can reproduce`() {
        val sr = 48_000
        val frames = sr
        val buf = FloatArray(frames) { (0.5f * sin(2.0 * PI * 60.0 * it / sr)).toFloat() }
        VirtualBass(sr).process(buf, frames, 1, 0.8f)
        val second = toneAmplitude(buf, sr, 120.0, sr / 4)
        val third = toneAmplitude(buf, sr, 180.0, sr / 4)
        val fourth = toneAmplitude(buf, sr, 240.0, sr / 4)
        assertTrue("应补出二次谐波: $second", second > 0.004)
        assertTrue("手机喇叭要靠 3 次谐波才听得见低音: $third", third > 0.0015)
        assertTrue("谐波族应该越往高频越弱，不能变成嘶声: $fourth", fourth < second)
    }

    @Test
    fun `limiter keeps the shape of a sustained loud bass note`() {
        val sr = 48_000
        val frames = sr / 2
        val buf = FloatArray(frames) { (1.6f * sin(2.0 * PI * 60.0 * it / sr)).toFloat() }
        SoftLimiter(sr).process(buf, frames, 1, -1f)
        val tail = buf.copyOfRange(frames / 2, frames)
        val fundamental = toneAmplitude(tail, sr, 60.0, 0)
        val third = toneAmplitude(tail, sr, 180.0, 0)
        assertTrue("峰值应被压到阈值附近: $fundamental", fundamental < 0.9)
        assertTrue("低频限幅不该把正弦波削成方波: $third / $fundamental", third < fundamental * 0.06)
    }

    @Test
    fun `reverb separates direct sound from the first reflection`() {
        val sr = 48_000
        val reverb = Freeverb(sr, 1)
        val impulse = FloatArray(sr)
        impulse[0] = 1f
        reverb.process(impulse, impulse.size, 1, 0.4f, 0.6f)
        // 20 ms 预延迟内只该有直达声，否则人声会贴在耳朵上糊成一团
        assertTrue(
            "预延迟窗口内不该出现湿声",
            (1 until sr * 2 / 100).all { abs(impulse[it]) < 1e-6f }
        )
    }

    @Test
    fun `soft limiter caps peaks below threshold`() {
        val buf = FloatArray(2048) { 2f }
        val threshold = 10f.pow(-1f / 20f)
        SoftLimiter(48_000).process(buf, 2048, 1, -1f)
        buf.forEach { v ->
            assertTrue("限幅后仍超阈值: $v", abs(v) <= threshold + 0.02f)
        }
    }

    @Test
    fun `reverb reset clears the tail`() {
        val reverb = Freeverb(44_100, 1)
        val loud = FloatArray(1024) { 1f }
        reverb.process(loud, 1024, 1, 0.5f, 0.8f)
        reverb.reset()
        val silent = FloatArray(1024)
        reverb.process(silent, 1024, 1, 0.5f, 0.8f)
        assertTrue("切歌后不该残留上一首的混响尾音", silent.all { it == 0f })
    }

    @Test
    fun `stereo width zero collapses out-of-phase stereo to silence`() {
        val dsp = SoundFxDsp(48_000, 2)
        dsp.setTarget(SoundFxParams(stereoWidth = 0f))
        val buf = FloatArray(2048 * 2) { if (it % 2 == 0) 1f else -1f }
        // 第一块还在 30ms ramp 里，多跑几块等参数落定
        repeat(4) { dsp.process(buf, 2048) }
        assertTrue("宽度 0 应把左右反相信号合成静音", buf.all { abs(it) < 0.01f })
    }

    @Test
    fun `full chain never produces NaN or unbounded samples`() {
        val dsp = SoundFxDsp(48_000, 2)
        dsp.setTarget(SoundFxParams(
            virtualBass = 0.7f, reverbWet = 0.3f, reverbDecay = 0.8f,
            stereoWidth = 1.3f, preampDb = -4f, limiter = true
        ))
        val buf = FloatArray(4096 * 2) { (0.9f * sin(2.0 * PI * 220.0 * (it / 2) / 48_000)).toFloat() }
        repeat(4) { dsp.process(buf, 4096) }
        assertTrue(buf.all { it.isFinite() && abs(it) <= 1.01f })
        dsp.reset()
        assertTrue(buf.all { it.isFinite() })
    }

    private fun rms(a: FloatArray): Double =
        sqrt(a.sumOf { (it * it).toDouble() } / a.size)

    private fun toneAmplitude(a: FloatArray, sampleRate: Int, hz: Double, start: Int): Double {
        var s = 0.0
        var c = 0.0
        for (i in start until a.size) {
            val phase = 2.0 * PI * hz * i / sampleRate
            s += a[i] * sin(phase)
            c += a[i] * cos(phase)
        }
        return 2.0 * sqrt(s * s + c * c) / (a.size - start)
    }
}
