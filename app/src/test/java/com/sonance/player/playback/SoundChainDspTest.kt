package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用实际信号验证整条链，包含 PCM 边界、切换和跨进程恢复需要的配方兼容性。 */
class SoundChainDspTest {
    private val sr = 48_000

    @Test
    fun `stock presets change tone without turning the music down`() {
        assertTrue(SoundPresets.OFF.fx.isNeutral)
        assertTrue(SoundPresets.OFF.eqDb.all { it == 0f })
        SoundPresets.ALL.filter { it.id != SoundPresets.OFF.id }.forEach { preset ->
            val response = EqResponse(preset.eqDb, sr)
            val auto = eqAutoPreampDb(response.maxBoostDb() + effectPreampBudgetDb(preset.fx, 2))
            // 旧版每个预设都被前置增益整体压低 2~3 dB，用户点下去只听到"变小"。
            fun net(hz: Double) = response.dbAt(hz) + auto
            assertTrue(
                "${preset.id} 中频比原声小了 ${-net(1000.0)} dB，预设不该靠变小声来表达音色",
                net(1000.0) > -2f
            )
            val maxSwing = listOf(62.0, 250.0, 1000.0, 3000.0, 8000.0).maxOf { net(it) } -
                listOf(62.0, 250.0, 1000.0, 3000.0, 8000.0).minOf { net(it) }
            if (preset.category == SoundPresetCategory.SPACE) {
                // 空间档的性格来自混响与宽度，EQ 本来就该近乎平直
                assertTrue("${preset.id} 空间档必须真的加混响", preset.fx.reverbWet > 0.05f)
            } else {
                assertTrue("${preset.id} 的音色变化只有 $maxSwing dB，等于没调", maxSwing >= 2.5f)
            }
            assertTrue("${preset.id} 增益越界", preset.eqDb.all { it in -6f..6f })
            assertTrue("${preset.id} 自动余量异常: $auto", auto <= 0f && auto >= -12f)
        }
    }

    @Test
    fun `each preset is voiced in the direction its name promises`() {
        fun net(preset: SoundPreset, hz: Double): Float {
            val response = EqResponse(preset.eqDb, sr)
            return response.dbAt(hz) + eqAutoPreampDb(response.maxBoostDb() + effectPreampBudgetDb(preset.fx, 2))
        }
        // 通透：高频抬起来、低中频让路
        assertTrue(net(SoundPresets.CLEAR, 4000.0) - net(SoundPresets.CLEAR, 250.0) > 3f)
        // 人声：2 kHz 的咬字明显高于 250 Hz 的闷
        assertTrue(net(SoundPresets.VOCAL, 2000.0) - net(SoundPresets.VOCAL, 250.0) > 3f)
        // 低频：62 Hz 要有重量，且不能靠整体变小声换来
        assertTrue(net(SoundPresets.BASS, 62.0) - net(SoundPresets.BASS, 500.0) > 3f)
        assertTrue(net(SoundPresets.BASS, 62.0) > 1.5f)
        // 深夜：高频收钝
        assertTrue(net(SoundPresets.NIGHT, 4000.0) < -1f)
        // 手机外放沿用旧版温和饱满调音：切 31 Hz 防破音、补 500 Hz 厚度、柔化 2 kHz；平板保留 48 Hz 高通与宽声场
        assertTrue("手机外放 31 Hz 应被切低: ${net(SoundPresets.PHONE_SPEAKER, 31.0)}", net(SoundPresets.PHONE_SPEAKER, 31.0) < -3f)
        assertTrue("手机外放 500 Hz 保持厚度: ${net(SoundPresets.PHONE_SPEAKER, 500.0)}", net(SoundPresets.PHONE_SPEAKER, 500.0) > 2f)
        assertTrue("手机外放柔化 2 kHz 毛刺: ${net(SoundPresets.PHONE_SPEAKER, 2000.0)}", net(SoundPresets.PHONE_SPEAKER, 2000.0) < -1f)
        assertEquals(0f, SoundPresets.PHONE_SPEAKER.fx.speakerHpfHz, 0f)
        assertTrue(SoundPresets.TABLET_SPEAKER.fx.speakerHpfHz > 40f)
        // 空间档的混响与宽度递增，日常档保持纯净无混响
        assertTrue(SoundPresets.CINEMA.fx.reverbWet > SoundPresets.LIVE.fx.reverbWet)
        listOf(SoundPresets.EXTREME_HIFI, SoundPresets.CLEAR, SoundPresets.VOCAL, SoundPresets.BASS, SoundPresets.NIGHT)
            .forEach { assertEquals("${it.id} 不应默认增加混响", 0f, it.fx.reverbWet, 0f) }
        // 低频档与外放档都应启用虚拟低音谐波合成
        assertTrue(SoundPresets.BASS.fx.virtualBass > 0.3f)
        assertTrue(SoundPresets.PHONE_SPEAKER.fx.virtualBass >= SoundPresets.BASS.fx.virtualBass)
    }

    @Test
    fun `phone speaker and featured presets audibly upgrade multi-band music over raw without thinning`() {
        val frames = sr // 1 秒信号，前 0.4s 让斜坡与滤波器稳态；后 0.6s 是 5 Hz 公倍周期，零频谱泄漏
        val start = (sr * 0.4).toInt()
        val dry = FloatArray(frames * 2)
        val phoneDry = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val mid = 0.22 * sin(2.0 * PI * 55.0 * t) +
                0.20 * sin(2.0 * PI * 95.0 * t) +
                0.22 * sin(2.0 * PI * 1850.0 * t)
            val side = 0.12 * sin(2.0 * PI * 3500.0 * t) +
                0.08 * sin(2.0 * PI * 7500.0 * t)
            dry[2 * i] = (mid + side).toFloat()
            dry[2 * i + 1] = (mid - side).toFloat()

            // 单独构造含 400 Hz 中低频的多音信号验证旧版手机外放，保持 55/95 Hz 幅度与总峰值在 SoftLimiter 阈值之下
            val phoneMid = 0.22 * sin(2.0 * PI * 55.0 * t) +
                0.20 * sin(2.0 * PI * 95.0 * t) +
                0.15 * sin(2.0 * PI * 400.0 * t) +
                0.12 * sin(2.0 * PI * 1850.0 * t)
            val phoneSide = 0.09 * sin(2.0 * PI * 3500.0 * t) +
                0.06 * sin(2.0 * PI * 7500.0 * t)
            phoneDry[2 * i] = (phoneMid + phoneSide).toFloat()
            phoneDry[2 * i + 1] = (phoneMid - phoneSide).toFloat()
        }

        fun render(preset: SoundPreset, source: FloatArray = dry): FloatArray {
            val out = source.copyOf()
            SoundChainDsp(sr, 2).also {
                it.setTarget(true, preset.eqDb, preset.fx)
                it.process(out, frames)
            }
            return out
        }

        fun bandMid(buf: FloatArray, hz: Double): Double {
            val mono = FloatArray(frames - start) { idx ->
                val p = (idx + start) * 2
                (buf[p] + buf[p + 1]) * 0.5f
            }
            return toneAmplitude(mono, hz)
        }

        fun bandSide(buf: FloatArray, hz: Double): Double {
            val side = FloatArray(frames - start) { idx ->
                val p = (idx + start) * 2
                (buf[p] - buf[p + 1]) * 0.5f
            }
            return toneAmplitude(side, hz)
        }

        val phoneDryRms = rms(phoneDry.copyOfRange(start * 2, phoneDry.size))
        val phone = render(SoundPresets.PHONE_SPEAKER, phoneDry)
        val phoneRms = rms(phone.copyOfRange(start * 2, phone.size))
        val p110 = bandMid(phone, 110.0)
        val d110 = bandMid(phoneDry, 110.0)
        val p150 = bandMid(phone, 150.0)
        val d150 = bandMid(phoneDry, 150.0)
        assertTrue("手机外放保持合理响度不塌陷: phone=$phoneRms dry=$phoneDryRms", phoneRms >= phoneDryRms * 0.90)
        assertTrue("手机外放应明显增强低音二次/和频谐波(110Hz=$p110, 150Hz=$p150, dry=$d110/$d150)", (p110 + p150) > (d110 + d150) + 0.015)
        assertTrue("手机外放 400 Hz 中低频胸腔厚度应明显增强", bandMid(phone, 400.0) > bandMid(phoneDry, 400.0) * 1.15)
        assertTrue("手机外放极高频空气声场宽度应明显大于原声", bandSide(phone, 7500.0) > bandSide(phoneDry, 7500.0) * 1.15)

        val dryRms = rms(dry.copyOfRange(start * 2, dry.size))
        val tablet = render(SoundPresets.TABLET_SPEAKER)
        val tabletRms = rms(tablet.copyOfRange(start * 2, tablet.size))
        assertTrue("平板外放总体响度保持饱满不虚弱: tablet=$tabletRms dry=$dryRms", tabletRms >= dryRms * 0.96)
        assertTrue("平板外放 95 Hz 真实鼓点肉感保持充沛", bandMid(tablet, 95.0) >= bandMid(dry, 95.0) * 0.95)
        assertTrue("平板外放人声 1.85 kHz 应明确亮出原声", bandMid(tablet, 1850.0) > bandMid(dry, 1850.0) * 1.05)
        assertTrue("平板外放中高频声场宽度应显著大于原声", bandSide(tablet, 3500.0) > bandSide(dry, 3500.0) * 1.25)

        val bass = render(SoundPresets.BASS)
        assertTrue("低频档 55 Hz 应实实在在比原声更澎湃", bandMid(bass, 55.0) > bandMid(dry, 55.0) * 1.35)
        assertTrue("低频档不应过度压低人声中频", bandMid(bass, 1850.0) >= bandMid(dry, 1850.0) * 0.85)

        val panorama = render(SoundPresets.PANORAMA)
        assertTrue("全景环绕侧向声场应显著拉开", bandSide(panorama, 3500.0) > bandSide(dry, 3500.0) * 1.40)
    }

    @Test
    fun `auto preamp keeps extreme user eq safe but leaves gentle curves alone`() {
        // 日常曲线：几乎不扣，提升才听得见
        assertTrue(eqAutoPreampDb(2f) > -1f)
        assertTrue(eqAutoPreampDb(4f) > -1.8f)
        // 手拖到极限：全额补偿，避免 16 位输出削顶
        assertEquals(-12f, eqAutoPreampDb(12f), 0.2f)
        assertEquals(0f, eqAutoPreampDb(0f), 0f)
        assertTrue(eqAutoPreampDb(8.5f) < -5.5f)
    }

    @Test
    fun `legacy stock settings migrate but custom effect settings survive`() {
        val legacy = SoundFxParams(
            virtualBass = 0.7f, reverbWet = 0.06f, reverbDecay = 0.2f,
            stereoWidth = 1.18f, preampDb = -4f, limiter = true
        )
        val migrated = SoundPresets.restoreFx(SoundPresets.PHONE_SPEAKER, SoundFxParams.toStore(legacy))
        assertFalse(migrated.custom)
        assertEquals(SoundPresets.PHONE_SPEAKER.fx, migrated.params)
        assertEquals(SoundPresets.PHONE_SPEAKER, SoundPresets.fromId("dual_speaker"))

        val custom = legacy.copy(stereoWidth = 1.23f)
        val restored = SoundPresets.restoreFx(SoundPresets.PHONE_SPEAKER, SoundFxParams.toStore(custom))
        assertTrue(restored.custom)
        assertEquals(custom, restored.params)
        // 第二代/上一代老配方同样要当成内置参数迁移掉，否则更新后仍听旧干瘪参数
        val secondGen = SoundFxParams(stereoWidth = 1.04f, preampDb = -1.5f, limiter = true)
        assertFalse(SoundPresets.restoreFx(SoundPresets.CLEAR, SoundFxParams.toStore(secondGen)).custom)
        val prevPhoneSpeaker = SoundFxParams(
            virtualBass = 0.68f, stereoWidth = 1.16f, limiter = true,
            limiterThresholdDb = -1.2f, speakerHpfHz = 115f
        )
        val migratedPhone = SoundPresets.restoreFx(SoundPresets.PHONE_SPEAKER, SoundFxParams.toStore(prevPhoneSpeaker))
        assertFalse(migratedPhone.custom)
        assertEquals(SoundPresets.PHONE_SPEAKER.fx, migratedPhone.params)
        val prevPhoneSpeaker65 = SoundFxParams(
            virtualBass = 0.70f, stereoWidth = 1.24f, limiter = true,
            limiterThresholdDb = -0.5f, speakerHpfHz = 65f
        )
        val migratedPhone65 = SoundPresets.restoreFx(SoundPresets.PHONE_SPEAKER, SoundFxParams.toStore(prevPhoneSpeaker65))
        assertFalse(migratedPhone65.custom)
        assertEquals(SoundPresets.PHONE_SPEAKER.fx, migratedPhone65.params)
        assertFalse(SoundPresets.restoreFx(SoundPresets.CLEAR, null).custom)
        assertEquals(SoundPresets.BASS, SoundPresets.eqPresetFromStore(SoundPresets.eqStoreName(SoundPresets.BASS)))
        assertEquals(null, SoundPresets.eqPresetFromStore("sound:unknown"))
        assertEquals(null, SoundPresets.eqPresetFromStore("ROCK"))
    }

    @Test
    fun `non finite persisted parameters cannot poison the audio thread`() {
        val p = SoundFxParams.fromStore("NaN,Infinity,NaN,NaN,-Infinity,1,NaN")
        assertEquals(0f, p.virtualBass, 0f)
        assertEquals(0f, p.reverbWet, 0f)
        assertEquals(0.5f, p.reverbDecay, 0f)
        assertEquals(1f, p.stereoWidth, 0f)
        assertEquals(0f, p.preampDb, 0f)
        assertEquals(-1f, p.limiterThresholdDb, 0f)
        EqBus.reset()
        EqBus.update(true, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 50f))
        assertArrayEquals(floatArrayOf(0f, 0f, 12f), EqBus.gainsDb.copyOf(3), 0f)
        EqBus.reset()
    }

    @Test
    fun `slider maps the entire bipolar dB range`() {
        assertEquals(-12f, eqDbFromSlider(0f), 0f)
        assertEquals(-6f, eqDbFromSlider(0.25f), 0f)
        assertEquals(0f, eqDbFromSlider(0.5f), 0f)
        assertEquals(6f, eqDbFromSlider(0.75f), 0f)
        assertEquals(12f, eqDbFromSlider(1f), 0f)
    }

    @Test
    fun `automatic headroom counts overlapping EQ bands`() {
        val gains = FloatArray(EQ_BAND_COUNT)
        gains[4] = 6f
        gains[5] = 6f
        gains[6] = 6f
        val response = EqResponse(gains, sr)
        assertTrue("相邻频段叠加不能只看最大滑杆", response.maxBoostDb() > 6.5f)
        assertTrue(response.dbAt(1000.0) > 6.5f)
    }

    @Test
    fun `boosted EQ stays clean before the final PCM conversion`() {
        val gains = FloatArray(EQ_BAND_COUNT).also { it[5] = 12f }
        val input = FloatArray(sr) { (0.85 * sin(2.0 * PI * 1000.0 * it / sr)).toFloat() }
        val dsp = SoundChainDsp(sr, 1)
        dsp.setTarget(true, gains, SoundFxParams.NEUTRAL)
        dsp.process(input, input.size)
        val stable = input.copyOfRange(sr / 2, sr)
        val fundamental = toneAmplitude(stable, 1000.0)
        val third = toneAmplitude(stable, 3000.0)
        assertTrue("EQ 后不应削顶: ${stable.maxOf { abs(it) }}", stable.maxOf { abs(it) } < 0.94f)
        assertTrue("不能用过多衰减代替正确处理: $fundamental", fundamental > 0.65)
        assertTrue("削顶会产生三次谐波: $third", third < fundamental * 0.005)
    }

    @Test
    fun `processing is independent of buffer boundaries`() {
        val frames = 24_000
        val source = stereoSignal(frames)
        val params = SoundFxParams(
            virtualBass = 0.35f, reverbWet = 0.16f, reverbDecay = 0.55f,
            stereoWidth = 1.16f, preampDb = -1.8f, limiter = true
        )
        val whole = source.copyOf()
        SoundChainDsp(sr, 2).also {
            it.setTarget(true, SoundPresets.VOCAL.eqDb, params)
            it.process(whole, frames)
        }
        val chunked = FloatArray(source.size)
        val dsp = SoundChainDsp(sr, 2)
        dsp.setTarget(true, SoundPresets.VOCAL.eqDb, params)
        val sizes = intArrayOf(1, 17, 257, 1024, 33, 511, 93)
        var frame = 0
        var n = 0
        while (frame < frames) {
            val count = minOf(sizes[n++ % sizes.size], frames - frame)
            val part = source.copyOfRange(frame * 2, (frame + count) * 2)
            dsp.process(part, count)
            part.copyInto(chunked, frame * 2)
            frame += count
        }
        assertArrayEquals("渐变、混响和限幅不应因缓冲大小改变", whole, chunked, 0.000002f)
    }

    @Test
    fun `rapid bypass during an EQ transition returns to exact dry audio`() {
        val dsp = SoundChainDsp(sr, 2)
        dsp.setTarget(true, SoundPresets.VOCAL.eqDb, SoundPresets.VOCAL.fx)
        dsp.process(stereoSignal(500), 500)
        dsp.setTarget(false, FloatArray(EQ_BAND_COUNT), SoundFxParams.NEUTRAL)
        dsp.process(stereoSignal(4000), 4000)
        assertFalse(dsp.needsProcessing)
        val input = stereoSignal(1024)
        val output = input.copyOf()
        dsp.process(output, 1024)
        assertArrayEquals(input, output, 0f)
    }

    @Test
    fun `stereo widening keeps mono sum and restrains low frequency side`() {
        fun measure(hz: Double): Double {
            val imager = StereoImager(sr)
            val frames = sr / 2
            val buf = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val mid = (0.1 * sin(2.0 * PI * 300.0 * i / sr)).toFloat()
                val side = (0.25 * sin(2.0 * PI * hz * i / sr)).toFloat()
                buf[2 * i] = mid + side
                buf[2 * i + 1] = mid - side
                imager.processFrame(buf, 2 * i, 1.6f)
                assertEquals("中置信号必须保持不变", mid, (buf[2 * i] + buf[2 * i + 1]) / 2f, 0.000001f)
            }
            val sideOut = FloatArray(frames - sr / 10) { i ->
                val p = (i + sr / 10) * 2
                (buf[p] - buf[p + 1]) * 0.5f
            }
            return toneAmplitude(sideOut, hz) / 0.25
        }
        val bassRatio = measure(50.0)
        val trebleRatio = measure(4000.0)
        assertTrue("低频不应被整段展宽: $bassRatio", bassRatio < 1.12)
        assertTrue("高频应有适度展宽: $trebleRatio", trebleRatio in 1.48..1.65)
    }

    @Test
    fun `virtual bass does not distort a voice band signal`() {
        val frames = sr
        val input = FloatArray(frames) { (0.7 * sin(2.0 * PI * 1000.0 * it / sr)).toFloat() }
        val original = input.copyOf()
        VirtualBass(sr).process(input, frames, 1, 1f)
        val difference = FloatArray(frames - sr / 4) { i -> input[i + sr / 4] - original[i + sr / 4] }
        assertTrue("中频不应产生明显谐波: ${rms(difference)}", rms(difference) < 0.001)
    }

    @Test
    fun `reverb keeps direct sound and tail disappears after reset`() {
        val reverb = Freeverb(sr, 1)
        val impulse = FloatArray(sr * 2)
        impulse[0] = 0.8f
        reverb.process(impulse, impulse.size, 1, 0.4f, 0.6f)
        assertEquals("湿度不能直接降低干声", 0.8f, impulse[0], 0f)
        val early = rms(impulse.copyOfRange(sr / 10, sr / 2))
        val late = rms(impulse.copyOfRange(sr * 3 / 2, sr * 2))
        assertTrue("应存在少量空间尾音", early > 0.000001)
        assertTrue("尾音应自然衰减: $early -> $late", late < early * 0.2)
        reverb.reset()
        val silent = FloatArray(sr / 2)
        reverb.process(silent, silent.size, 1, 0.4f, 0.6f)
        assertTrue(silent.all { it == 0f })
    }

    @Test
    fun `limiter links channels and leaves normal signals unchanged`() {
        val limiter = SoftLimiter(sr)
        val normal = floatArrayOf(0.2f, 0.1f, -0.6f, -0.3f)
        val original = normal.copyOf()
        limiter.process(normal, 2, 2, -1f)
        assertArrayEquals(original, normal, 0f)
        val loud = FloatArray(4096 * 2) { i ->
            val left = (1.7 * sin(2.0 * PI * 500.0 * (i / 2) / sr)).toFloat()
            if (i % 2 == 0) left else left * 0.5f
        }
        limiter.process(loud, 4096, 2, -1f)
        for (i in 0 until 4096) {
            val left = loud[i * 2]
            val right = loud[i * 2 + 1]
            assertTrue(abs(left) <= 0.892f)
            assertEquals(left * 0.5f, right, 0.000001f)
        }
    }

    @Test
    fun `processor re-applies settings after flush and bypasses both EQ and effects`() {
        EqBus.reset()
        SoundFxBus.reset()
        try {
            EqBus.update(true, SoundPresets.CLEAR.eqDb)
            SoundFxBus.update(true, SoundPresets.CLEAR.fx)
            val processor = SoundFxProcessor()
            processor.configure(AudioProcessor.AudioFormat(sr, 1, C.ENCODING_PCM_16BIT))
            processor.flush()
            val source = ShortArray(4096) { (24000 * sin(2.0 * PI * 1000.0 * it / sr)).toInt().toShort() }
            fun render(): ShortArray {
                val input = ByteBuffer.allocateDirect(source.size * 2).order(ByteOrder.nativeOrder())
                source.forEach { input.putShort(it) }
                input.flip()
                processor.queueInput(input)
                val output = processor.output.order(ByteOrder.nativeOrder())
                return ShortArray(output.remaining() / 2) { output.short }
            }
            val first = render()
            assertTrue("开启预设应真的处理 PCM", !first.contentEquals(source))
            processor.flush()
            assertArrayEquals("flush 后仍须重新应用相同预设", first, render())
            SoundFxBus.setBypass(true)
            render() // 等待平滑过渡
            assertArrayEquals("对比时 EQ 与音效都应旁通", source, render())
            SoundFxBus.setBypass(false)
            render()
            assertTrue("恢复后音效应重新生效", !render().contentEquals(source))
            processor.reset()
        } finally {
            EqBus.reset()
            SoundFxBus.reset()
        }
    }

    @Test
    fun `high sample rate 192kHz 31Hz peaking filter tracks target boost without precision drift`() {
        val hiSr = 192_000
        val gains = FloatArray(EQ_BAND_COUNT).also { it[0] = 6f }
        val cas = EqCascade(EQ_BAND_COUNT, 1)
        cas.design(EQ_CENTER_FREQS, gains, hiSr)
        val flat = EqCascade(EQ_BAND_COUNT, 1)
        flat.design(EQ_CENTER_FREQS, FloatArray(EQ_BAND_COUNT), hiSr)
        val frames = hiSr / 2
        val boosted = FloatArray(frames) { (0.3 * sin(2.0 * PI * 31.0 * it / hiSr)).toFloat() }
        val ref = boosted.copyOf()
        cas.process(boosted, frames)
        flat.process(ref, frames)
        val boostDb = 20.0 * kotlin.math.log10(
            rms(boosted.copyOfRange(frames / 2, frames)) / rms(ref.copyOfRange(frames / 2, frames))
        )
        assertEquals("192 kHz 下 31 Hz 极点不应出现双精度前的截断误差", 6.0, boostDb, 0.7)
    }

    private fun stereoSignal(frames: Int): FloatArray = FloatArray(frames * 2) { i ->
        val t = (i / 2).toDouble() / sr
        val mid = 0.30 * sin(2.0 * PI * 61.0 * t) + 0.24 * sin(2.0 * PI * 997.0 * t)
        val side = 0.11 * sin(2.0 * PI * 3100.0 * t)
        (mid + if (i % 2 == 0) side else -side).toFloat()
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun toneAmplitude(samples: FloatArray, hz: Double): Double {
        var s = 0.0
        var c = 0.0
        for (i in samples.indices) {
            val phase = 2.0 * PI * hz * i / sr
            s += samples[i] * sin(phase)
            c += samples[i] * cos(phase)
        }
        return 2.0 * sqrt(s * s + c * c) / samples.size
    }
}
