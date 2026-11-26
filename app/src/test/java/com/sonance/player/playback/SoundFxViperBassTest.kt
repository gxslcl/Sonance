package com.sonance.player.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFxViperBassTest {

    @Test
    fun `SoundFxParams serializes 15 fields and keeps 11 and 14 field backward compatibility`() {
        val params = SoundFxParams(
            virtualBass = 0.25f,
            stereoWidth = 1.2f,
            bassBoostDb = 2.5f,
            viperBass = 0.78f,
            viperBassFreqHz = 48f,
            viperBassMode = 2,
            clarity = 0.64f
        )
        val stored = SoundFxParams.toStore(params)
        val restored = SoundFxParams.fromStore(stored)
        assertEquals(0.78f, restored.viperBass, 0.001f)
        assertEquals(48f, restored.viperBassFreqHz, 0.001f)
        assertEquals(2, restored.viperBassMode)
        assertEquals(0.64f, restored.clarity, 0.001f)

        val legacy11 = "0.5,0.1,0.5,1.1,-1.0,1,-0.5,0.2,0.0,1.5,1.0"
        val fromLegacy11 = SoundFxParams.fromStore(legacy11)
        assertEquals(0.5f, fromLegacy11.virtualBass, 0.001f)
        assertEquals(0f, fromLegacy11.viperBass, 0.001f)
        assertEquals(52f, fromLegacy11.viperBassFreqHz, 0.001f)
        assertEquals(0, fromLegacy11.viperBassMode)
        assertEquals(0f, fromLegacy11.clarity, 0.001f)

        val legacy14 = "0.2,0.0,0.5,1.1,0.0,0,-1.0,0.1,0.0,1.0,1.0,0.55,48.0,1"
        val fromLegacy14 = SoundFxParams.fromStore(legacy14)
        assertEquals(0.55f, fromLegacy14.viperBass, 0.001f)
        assertEquals(48f, fromLegacy14.viperBassFreqHz, 0.001f)
        assertEquals(1, fromLegacy14.viperBassMode)
        assertEquals(0f, fromLegacy14.clarity, 0.001f)
    }

    @Test
    fun `UltraBass modes amplify bass energy cleanly without clipping or NaN`() {
        val sr = 48_000
        val frames = sr / 2
        val dry = FloatArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / sr
            (0.28 * sin(2.0 * PI * 55.0 * t) + 0.14 * sin(2.0 * PI * 1200.0 * t)).toFloat()
        }
        val dryRms = rms(dry)

        for (mode in 0..2) {
            val out = dry.copyOf()
            val dsp = SoundChainDsp(sr, 2)
            dsp.setTarget(
                eqEnabled = false,
                gainsDb = FloatArray(EQ_BAND_COUNT),
                fx = SoundFxParams(viperBass = 0.85f, viperBassFreqHz = 52f, viperBassMode = mode)
            )
            dsp.process(out, frames)
            assertTrue("mode=$mode must stay finite and non-clipping", out.all { it.isFinite() && abs(it) <= 1.0f })
            val wetRms = rms(out.copyOfRange(frames, out.size))
            assertTrue("mode=$mode must boost signal energy ($wetRms <= $dryRms)", wetRms > dryRms * 1.1)
        }
    }

    @Test
    fun `IEM_MASTER preset enforces IEM voicing invariants and processes cleanly`() {
        val preset = SoundPresets.IEM_MASTER
        assertEquals(SoundPresetCategory.HEADPHONE, preset.category)
        assertEquals(0f, preset.fx.reverbWet, 0f)
        assertTrue(!preset.fx.limiter)
        assertTrue(preset.fx.crossfeed in 0.10f..0.22f)
        assertTrue(preset.fx.stereoWidth > 1.2f)
        assertTrue(preset.eqDb[3] <= -2.5f) // 250 Hz 去浊
        assertTrue(preset.eqDb[8] < 0f)     // 8 kHz 去齿音刺峰
        assertTrue(preset.eqDb[9] >= 2.0f)  // 16 kHz 空气延展

        val sr = 48_000
        val frames = sr / 2
        val base = FloatArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / sr
            val mid = 0.22 * sin(2.0 * PI * 55.0 * t) +
                0.22 * sin(2.0 * PI * 1000.0 * t) +
                0.15 * sin(2.0 * PI * 4000.0 * t)
            val side = 0.10 * sin(2.0 * PI * 3200.0 * t)
            (mid + if (i % 2 == 0) side else -side).toFloat()
        }
        for (intensity in listOf(PresetIntensity.MEDIUM, PresetIntensity.HIGH)) {
            val (eq, fx) = preset.scaled(intensity)
            val buf = base.copyOf()
            SoundChainDsp(sr, 2).apply {
                setTarget(true, eq, fx)
                process(buf, frames)
            }
            assertTrue(
                "IEM_MASTER $intensity must stay finite and <= 1.0f",
                buf.all { it.isFinite() && abs(it) <= 1.0f }
            )
        }
    }

    @Test
    fun `ClarityExciter enhances high detail while leaving mid intact and avoiding clipping`() {
        val sr = 48_000
        val frames = sr / 2
        val detailInput = FloatArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / sr
            (0.24 * sin(2.0 * PI * 4_500.0 * t) + 0.14 * sin(2.0 * PI * 11_000.0 * t)).toFloat()
        }
        val midInput = FloatArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / sr
            (0.26 * sin(2.0 * PI * 420.0 * t)).toFloat()
        }
        val detailDryRms = rms(detailInput.copyOfRange(frames, detailInput.size))
        val midDryRms = rms(midInput.copyOfRange(frames, midInput.size))

        val dspDetail = SoundChainDsp(sr, 2).apply {
            setTarget(false, FloatArray(EQ_BAND_COUNT), SoundFxParams(clarity = 0.75f))
            process(detailInput, frames)
        }
        assertTrue(dspDetail.needsProcessing)
        assertTrue(detailInput.all { it.isFinite() && abs(it) <= 1.0f })
        val detailWetRms = rms(detailInput.copyOfRange(frames, detailInput.size))
        assertTrue("Clarity must enhance HF detail ($detailWetRms <= $detailDryRms)", detailWetRms > detailDryRms * 1.1)

        SoundChainDsp(sr, 2).apply {
            setTarget(false, FloatArray(EQ_BAND_COUNT), SoundFxParams(clarity = 0.75f))
            process(midInput, frames)
        }
        val midWetRms = rms(midInput.copyOfRange(frames, midInput.size))
        assertTrue("Clarity must preserve 420 Hz midrange ($midWetRms vs $midDryRms)", abs(midWetRms - midDryRms) < 0.02)
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
