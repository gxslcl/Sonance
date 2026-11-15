package com.sonance.player.playback

import android.media.audiofx.AudioEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAudioEffectsTest {

    @Test
    fun `tracker emits open session once when enabled and skips duplicate reconciles`() {
        val tracker = SystemEffectSessionTracker()

        val first = tracker.reconcile(enabled = true, playerSessionId = 42, packageName = "com.sonance.player")
        assertEquals(1, first.size)
        assertEquals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, first[0].action)
        assertEquals(42, first[0].audioSessionId)
        assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, first[0].contentType)
        assertEquals(42, tracker.activeSessionId)

        val repeat = tracker.reconcile(enabled = true, playerSessionId = 42, packageName = "com.sonance.player")
        assertTrue(repeat.isEmpty())
    }

    @Test
    fun `tracker closes old session before opening new session on session id change`() {
        val tracker = SystemEffectSessionTracker()
        tracker.reconcile(enabled = true, playerSessionId = 42, packageName = "com.sonance.player")

        val swap = tracker.reconcile(enabled = true, playerSessionId = 99, packageName = "com.sonance.player")
        assertEquals(2, swap.size)
        assertEquals(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, swap[0].action)
        assertEquals(42, swap[0].audioSessionId)
        assertEquals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, swap[1].action)
        assertEquals(99, swap[1].audioSessionId)
        assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, swap[1].contentType)
        assertEquals(99, tracker.activeSessionId)
    }

    @Test
    fun `tracker closes active session when disabled or released`() {
        val tracker = SystemEffectSessionTracker()
        tracker.reconcile(enabled = true, playerSessionId = 77, packageName = "com.sonance.player")

        val closed = tracker.reconcile(enabled = false, playerSessionId = 77, packageName = "com.sonance.player")
        assertEquals(1, closed.size)
        assertEquals(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, closed[0].action)
        assertEquals(77, closed[0].audioSessionId)
        assertEquals(0, tracker.activeSessionId)

        tracker.reconcile(enabled = true, playerSessionId = 88, packageName = "com.sonance.player")
        val released = tracker.closeAll("com.sonance.player")
        assertEquals(1, released.size)
        assertEquals(88, released[0].audioSessionId)
        assertEquals(0, tracker.activeSessionId)
    }

    @Test
    fun `tracker ignores invalid non-positive audio session ids`() {
        val tracker = SystemEffectSessionTracker()
        assertTrue(tracker.reconcile(enabled = true, playerSessionId = 0, packageName = "com.sonance.player").isEmpty())
        assertTrue(tracker.reconcile(enabled = true, playerSessionId = -1, packageName = "com.sonance.player").isEmpty())
        assertEquals(0, tracker.activeSessionId)
    }
}
