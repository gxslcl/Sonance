package com.sonance.player.ui.components

import com.sonance.player.data.MotionFeel
import com.sonance.player.data.PlayerSwitchMotion
import org.junit.Assert.*
import org.junit.Test

class PlayerCoverMotionTest {
    @Test
    fun `drag and buffering freeze the exact final displayed size even if playing changes`() {
        val motion = PlayerCoverMotion(true)
        repeat(150) { motion.advance(16f, true, false, 1L, 0.7f) }
        val frozen = motion.scale
        repeat(300) { i ->
            assertEquals(frozen, motion.advance(16f, i % 2 == 0, true, 1L + i / 30, 1f), 0f)
        }
    }

    @Test
    fun `track change and settled handoff preserve phase and do not restart scaling`() {
        val motion = PlayerCoverMotion(true)
        repeat(120) { motion.advance(16f, true, false, 1L) }
        val before = motion.scale
        assertEquals(before, motion.advance(16f, false, false, 2L), 0f)
        repeat(11) { assertEquals(before, motion.advance(16f, false, false, 2L), 0f) }
        assertEquals(before, motion.advance(0f, true, false, 2L), 0f)
        repeat(20) {
            val old = motion.scale
            val next = motion.advance(16f, true, false, 2L)
            assertTrue(kotlin.math.abs(next - old) < 0.001f)
        }
    }

    @Test
    fun `explicit pause converges gently to 096 and play restores gentle breathing`() {
        val motion = PlayerCoverMotion(true)
        repeat(180) { motion.advance(16f, true, false, 1L) }
        repeat(100) {
            val old = motion.scale
            val next = motion.advance(16f, false, false, 1L)
            assertTrue(kotlin.math.abs(next - old) < 0.004f)
        }
        assertEquals(0.96f, motion.scale, 0.00001f)
        assertTrue(motion.settled)
        val samples = mutableListOf<Float>()
        repeat(400) { samples.add(motion.advance(16f, true, false, 1L, 0.5f)) }
        assertTrue(samples.last() >= 1f)
        assertTrue(samples.max() <= 1.0141f)
        assertTrue(samples.takeLast(200).max() - samples.takeLast(200).min() > 0.005f)
    }

    @Test
    fun `initial pause is stable and every page can reuse the same scale without reset`() {
        val motion = PlayerCoverMotion(false)
        assertEquals(0.96f, motion.scale, 0f)
        assertEquals(0.96f, motion.advance(16f, false, false, 1L), 0f)
        assertTrue(motion.settled)
        repeat(10) { i ->
            assertEquals(0.96f, motion.advance(16f, false, true, i.toLong()), 0f)
        }
    }

    @Test
    fun `external switch trigger separates queue picks from auto advance`() {
        assertEquals(
            SwitchTrigger.QUEUE_PICK,
            classifyExternalSwitchTrigger(coveredByQueue = true, hasUnconsumedQueuePick = false, recentQueuePick = false)
        )
        assertEquals(
            SwitchTrigger.QUEUE_PICK,
            classifyExternalSwitchTrigger(coveredByQueue = false, hasUnconsumedQueuePick = true, recentQueuePick = true)
        )
        assertEquals(
            SwitchTrigger.AUTO_ADVANCE,
            classifyExternalSwitchTrigger(coveredByQueue = false, hasUnconsumedQueuePick = true, recentQueuePick = false)
        )
        assertEquals(
            SwitchTrigger.AUTO_ADVANCE,
            classifyExternalSwitchTrigger(coveredByQueue = false, hasUnconsumedQueuePick = false, recentQueuePick = true)
        )
    }

    @Test
    fun `switch durations are ordered cleanly and shorten on rapid triggers`() {
        val autoMs = resolveSwitchDurationMs(SwitchTrigger.AUTO_ADVANCE, PlayerSwitchMotion.POWERAMP_SLIDE, MotionFeel.FLUID, isRapid = false)
        val manualMs = resolveSwitchDurationMs(SwitchTrigger.MANUAL_STEP, PlayerSwitchMotion.POWERAMP_SLIDE, MotionFeel.FLUID, isRapid = false)
        val queueMs = resolveSwitchDurationMs(SwitchTrigger.QUEUE_PICK, PlayerSwitchMotion.POWERAMP_SLIDE, MotionFeel.FLUID, isRapid = false)
        assertTrue(autoMs > manualMs)
        assertTrue(manualMs > queueMs)
        assertTrue(resolveSwitchDurationMs(SwitchTrigger.QUEUE_PICK, PlayerSwitchMotion.POWERAMP_SLIDE, MotionFeel.FLUID, isRapid = true) < queueMs)
    }

    @Test
    fun `auto advance ambient cross-dissolve stays centered with smooth non-black midpoints`() {
        val outStart = evalAutoAdvanceOut(0f, baseScale = 1f, baseTranslationY = -12f)
        val inStart = evalAutoAdvanceIn(0f, baseScale = 1f, baseTranslationY = -12f)
        assertEquals(1f, outStart.alpha, 0.0001f)
        assertEquals(0f, inStart.alpha, 0.0001f)
        assertEquals(0f, outStart.translationX, 0f)
        assertEquals(-12f, outStart.translationY, 0f)

        val outMid = evalAutoAdvanceOut(0.45f, baseScale = 1f, baseTranslationY = -12f)
        val inMid = evalAutoAdvanceIn(0.45f, baseScale = 1f, baseTranslationY = -12f)
        assertEquals(0f, outMid.translationX, 0f)
        assertEquals(0f, inMid.translationX, 0f)
        assertTrue("Expected non-dim cross-dissolve midpoint, got ${outMid.alpha + inMid.alpha}", outMid.alpha + inMid.alpha >= 0.75f)

        val outEnd = evalAutoAdvanceOut(1f, baseScale = 1f, baseTranslationY = -12f)
        val inEnd = evalAutoAdvanceIn(1f, baseScale = 1f, baseTranslationY = -12f)
        assertEquals(0f, outEnd.alpha, 0.0001f)
        assertEquals(1f, inEnd.alpha, 0.0001f)
        assertEquals(1f, inEnd.scale, 0.0001f)
    }

    @Test
    fun `queue pick focus rise glides vertically without horizontal shift`() {
        val density = 3f
        val inStart = evalQueuePickIn(0f, baseScale = 1f, baseTranslationY = 10f, density = density)
        val inEnd = evalQueuePickIn(1f, baseScale = 1f, baseTranslationY = 10f, density = density)
        val outEnd = evalQueuePickOut(1f, baseScale = 1f, baseTranslationY = 10f, density = density)

        assertEquals(0f, inStart.translationX, 0f)
        assertTrue(inStart.translationY > 10f)
        assertTrue(inStart.scale < 1f)
        assertEquals(1f, inEnd.alpha, 0.0001f)
        assertEquals(1f, inEnd.scale, 0.0001f)
        assertEquals(10f, inEnd.translationY, 0.0001f)
        assertEquals(0f, outEnd.alpha, 0.0001f)
        assertEquals(0f, outEnd.translationX, 0f)
    }
}
