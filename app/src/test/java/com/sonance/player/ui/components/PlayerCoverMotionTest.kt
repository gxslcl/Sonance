package com.sonance.player.ui.components

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
}
