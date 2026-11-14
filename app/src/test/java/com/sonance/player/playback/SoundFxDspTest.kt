package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        // 手机外放恢复为原始温和饱满曲线与效果参数，其它档位保持不变
        val speaker = SoundPresets.PHONE_SPEAKER
        assertEquals(
            SoundFxParams(virtualBass = 0.65f, stereoWidth = 1.18f, limiter = true),
            speaker.fx
        )
        assertTrue(speaker.eqDb.contentEquals(floatArrayOf(-4.5f, -1.5f, 1.2f, 3.2f, 3.6f, 1.2f, -1.8f, -1.2f, 1.5f, 2.2f)))
        // 新增耳机、平板、车载与全景预设检查（适度 BS2B 避免挤压声场）
        assertTrue(SoundPresets.IN_EAR.fx.crossfeed in 0.12f..0.25f)
        assertTrue(SoundPresets.OVER_EAR.fx.crossfeed > SoundPresets.IN_EAR.fx.crossfeed)
        assertTrue(SoundPresets.TABLET_SPEAKER.fx.speakerHpfHz in 42f..60f)
        assertTrue(SoundPresets.CAR_AUDIO.fx.speakerHpfHz in 30f..60f)
        assertTrue(SoundPresets.PANORAMA.fx.stereoWidth > 1.4f)
        assertEquals(0f, SoundPresets.EXTREME_HIFI.fx.reverbWet, 0f)
    }

    @Test
    fun `store round trip and dirty data clamp`() {
        val p = SoundFxParams(
            virtualBass = 0.7f, reverbWet = 0.2f, reverbDecay = 0.4f,
            stereoWidth = 1.2f, preampDb = -4f, limiter = true,
            crossfeed = 0.42f, speakerHpfHz = 115f
        )
        val back = SoundFxParams.fromStore(SoundFxParams.toStore(p))
        assertEquals(p.virtualBass, back.virtualBass, 0.001f)
        assertEquals(p.reverbWet, back.reverbWet, 0.001f)
        assertEquals(p.stereoWidth, back.stereoWidth, 0.001f)
        assertEquals(p.preampDb, back.preampDb, 0.001f)
        assertEquals(p.limiter, back.limiter)
        assertEquals(p.crossfeed, back.crossfeed, 0.001f)
        assertEquals(p.speakerHpfHz, back.speakerHpfHz, 0.001f)

        assertEquals(SoundFxParams.NEUTRAL, SoundFxParams.fromStore(null))
        assertEquals(SoundFxParams.NEUTRAL, SoundFxParams.fromStore("1,2"))
        val dirty = SoundFxParams.fromStore("99,99,99,99,99,1,99,5,999")
        assertEquals(1f, dirty.virtualBass, 0.001f)
        assertEquals(0.6f, dirty.reverbWet, 0.001f)
        assertEquals(0f, dirty.preampDb, 0.001f)
        assertEquals(1f, dirty.crossfeed, 0.001f)
        assertEquals(160f, dirty.speakerHpfHz, 0.001f)
        assertEquals(0f, SoundFxParams(speakerHpfHz = 10f).clamp().speakerHpfHz, 0f)
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

    @Test
    fun `virtual bass decays cleanly without negative dc thump`() {
        val sr = 48_000
        val burstFrames = sr / 5
        val silenceFrames = sr / 5
        val total = burstFrames + silenceFrames
        val buf = FloatArray(total) { i ->
            if (i < burstFrames) (0.7f * sin(2.0 * PI * 60.0 * i / sr)).toFloat() else 0f
        }
        val orig = buf.copyOf()
        VirtualBass(sr).process(buf, total, 1, 0.9f)
        // 验证纯衰减末尾几乎无残余负偏置，无轰头直流台阶
        val tailDiff = FloatArray(silenceFrames / 2) { buf[burstFrames + silenceFrames / 2 + it] - orig[burstFrames + silenceFrames / 2 + it] }
        val maxAbsTail = tailDiff.maxOf { abs(it) }
        assertTrue("虚拟低音在鼓点衰减后不应残留负向直流偏移: $maxAbsTail", maxAbsTail < 0.006f)
    }

    @Test
    fun `binaural crossfeed bleeds opposite bass subtly without smearing centered voice`() {
        val sr = 48_000
        val frames = sr / 4
        val cf = BinauralCrossfeed(sr)
        val hardLeft = FloatArray(frames * 2) { i ->
            if (i % 2 == 0) (0.6f * sin(2.0 * PI * 250.0 * (i / 2) / sr)).toFloat() else 0f
        }
        for (f in 0 until frames) cf.processFrame(hardLeft, f * 2, 0.8f)
        val rightBleed = FloatArray(frames / 2) { hardLeft[(frames / 2 + it) * 2 + 1] }
        assertTrue("交叉馈送应把低频软串入对侧耳朵: ${rms(rightBleed)}", rms(rightBleed) > 0.04)

        cf.reset()
        val centered = FloatArray(frames * 2) { i ->
            (0.5f * sin(2.0 * PI * 1000.0 * (i / 2) / sr)).toFloat()
        }
        for (f in 0 until frames) cf.processFrame(centered, f * 2, 0.8f)
        val midOut = FloatArray(frames / 2) { centered[(frames / 2 + it) * 2] }
        assertEquals("正中人声能量不应被大幅改变", 0.5 / sqrt(2.0), rms(midOut), 0.03)
    }

    @Test
    fun `speaker protection HPF cuts sub-bass after extracting virtual bass harmonics`() {
        val sr = 48_000
        val frames = sr
        val dsp = SoundFxDsp(sr, 2)
        dsp.setTarget(SoundFxParams(virtualBass = 0.7f, speakerHpfHz = 115f))
        val buf = FloatArray(frames * 2) { i ->
            (0.6f * sin(2.0 * PI * 50.0 * (i / 2) / sr)).toFloat()
        }
        dsp.process(buf, frames)
        val monoTail = FloatArray(frames / 2) { buf[(frames / 2 + it) * 2] }
        val amp50 = toneAmplitude(monoTail, sr, 50.0, 0)
        val amp100 = toneAmplitude(monoTail, sr, 100.0, 0)
        val amp150 = toneAmplitude(monoTail, sr, 150.0, 0)
        assertTrue("115 Hz 高通应大幅衰减 50 Hz 打底超低频: $amp50", amp50 < 0.16)
        assertTrue("高通前提取的 2f/3f 谐波应被保留: ${amp100 + amp150}", amp100 + amp150 > 0.01)
    }

    @Test
    fun `speaker 65Hz HPF cuts 31Hz rumble while preserving 85Hz chest punch`() {
        val sr = 48_000
        val frames = sr
        val dsp = SoundFxDsp(sr, 2)
        val params = SoundFxParams(virtualBass = 0.70f, stereoWidth = 1.18f, limiter = true, speakerHpfHz = 65f)
        dsp.setTarget(params)
        val sub = FloatArray(frames * 2) { (0.5f * sin(2.0 * PI * 31.0 * (it / 2) / sr)).toFloat() }
        dsp.process(sub, frames)
        val subTail = FloatArray(frames / 2) { sub[(frames / 2 + it) * 2] }
        val amp31 = toneAmplitude(subTail, sr, 31.0, 0)
        assertTrue("65 Hz 高通应明显拦截 31 Hz 伤喇叭共振: $amp31", amp31 < 0.15)

        dsp.reset()
        dsp.setTarget(params)
        val punch = FloatArray(frames * 2) { (0.5f * sin(2.0 * PI * 85.0 * (it / 2) / sr)).toFloat() }
        dsp.process(punch, frames)
        val punchTail = FloatArray(frames / 2) { punch[(frames / 2 + it) * 2] }
        val amp85 = toneAmplitude(punchTail, sr, 85.0, 0)
        assertTrue("实际能发出的 85 Hz 鼓点肉感不应被误切: $amp85", amp85 > 0.35)
    }

    @Test
    fun `karaoke keeps bass kick and mono compatibility while attenuating centered vocals`() {
        val sr = 48_000
        val frames = sr / 2
        KaraokeBus.enabled = true
        try {
            val kp = KaraokeProcessor()
            kp.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
            kp.flush()
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (f in 0 until frames) {
                val bass = 0.35 * sin(2.0 * PI * 75.0 * f / sr)
                val vocal = 0.35 * sin(2.0 * PI * 900.0 * f / sr)
                val pannedGuitar = 0.25 * sin(2.0 * PI * 2200.0 * f / sr)
                val l = ((bass + vocal + pannedGuitar) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val r = ((bass + vocal) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                input.putShort(l)
                input.putShort(r)
            }
            input.flip()
            kp.queueInput(input)
            val out = kp.output.order(ByteOrder.nativeOrder())
            val lOut = FloatArray(frames)
            val monoOut = FloatArray(frames)
            for (f in 0 until frames) {
                val l = out.short / 32768f
                val r = out.short / 32768f
                lOut[f] = l
                monoOut[f] = (l + r) * 0.5f
            }
            val start = frames / 4
            val bassLeft = toneAmplitude(lOut, sr, 75.0, start)
            val vocalLeft = toneAmplitude(lOut, sr, 900.0, start)
            val guitarLeft = toneAmplitude(lOut, sr, 2200.0, start)
            val guitarMono = toneAmplitude(monoOut, sr, 2200.0, start)
            assertTrue("底鼓 75 Hz 不应被卡拉OK 模式砍空: $bassLeft", bassLeft > 0.28)
            assertTrue("居中 900 Hz 人声应显著削弱: $vocalLeft", vocalLeft < 0.08)
            assertTrue("左声道偏置吉他应清晰保留: $guitarLeft", guitarLeft > 0.12)
            assertTrue("单声道和声不能抵消成静音，吉他在单声道亦保留: $guitarMono", guitarMono > 0.05)
            kp.reset()
        } finally {
            KaraokeBus.enabled = false
        }
    }

    @Test
    fun `pcm codec supports 24bit and 32bit hi-res pcm round trip`() {
        val samples = 16
        val src = FloatArray(samples) { (it - 8) * 0.1f }
        // 24-bit packed
        val buf24 = ByteBuffer.allocateDirect(samples * 3).order(ByteOrder.LITTLE_ENDIAN)
        val out24 = ByteBuffer.allocateDirect(samples * 3).order(ByteOrder.LITTLE_ENDIAN)
        PcmCodec.writeFromFloat(src, buf24, samples * 3, samples, C.ENCODING_PCM_24BIT, ShortArray(0), IntArray(0), applyDither = false, ditherSeed = 1)
        val decoded24 = FloatArray(samples)
        PcmCodec.readToFloat(buf24, decoded24, samples, C.ENCODING_PCM_24BIT)
        for (i in 0 until samples) assertEquals(src[i], decoded24[i], 1e-5f)

        // 32-bit int
        val buf32 = ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
        PcmCodec.writeFromFloat(src, buf32, samples * 4, samples, C.ENCODING_PCM_32BIT, ShortArray(0), IntArray(samples), applyDither = false, ditherSeed = 1)
        val decoded32 = FloatArray(samples)
        PcmCodec.readToFloat(buf32, decoded32, samples, C.ENCODING_PCM_32BIT)
        for (i in 0 until samples) assertEquals(src[i], decoded32[i], 1e-6f)

        assertTrue(PcmCodec.isSupportedEncoding(C.ENCODING_PCM_24BIT))
        assertTrue(PcmCodec.isSupportedEncoding(C.ENCODING_PCM_32BIT))
        assertTrue(out24.capacity() == samples * 3)
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
