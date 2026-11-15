package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLyricsSyncTest {

    @Test
    fun `canonicalLyricKey stays unified and invalidates on revision change`() {
        val rev1 = PlaybackService.canonicalLyricKey(42L, " カタオモイ ", " Aimer ", revision = 1L)
        val rev1Trimmed = PlaybackService.canonicalLyricKey(42L, "カタオモイ", "Aimer", revision = 1L)
        val rev2 = PlaybackService.canonicalLyricKey(42L, "カタオモイ", "Aimer", revision = 2L)

        assertEquals(rev1Trimmed, rev1)
        assertNotEquals(rev1, rev2)
    }

    @Test
    fun `matchesSameTrack distinguishes different songs and matches revision bumps`() {
        val k1 = PlaybackService.canonicalLyricKey(101L, "晴天", "周杰伦", revision = 3L)

        assertTrue(PlaybackService.matchesSameTrack(k1, 101L, "晴天", "周杰伦"))
        assertFalse(PlaybackService.matchesSameTrack(k1, 102L, "晴天", "周杰伦"))
        assertFalse(PlaybackService.matchesSameTrack(k1, 101L, "七里香", "周杰伦"))
        assertFalse(PlaybackService.matchesSameTrack(null, 101L, "晴天", "周杰伦"))
    }
}
