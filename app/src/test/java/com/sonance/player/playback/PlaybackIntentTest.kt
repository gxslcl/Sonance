package com.sonance.player.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackIntentTest {
    @Test
    fun `buffering remains active but does not advance the audio clock`() {
        val state = PlayerUiState(hasItem = true, isPlaying = false, isBuffering = true, playWhenReady = true)
        assertTrue(state.isPlaybackActive)
        assertFalse(state.isPlaying)
        assertTrue(activePlaybackIntent(true, Player.STATE_BUFFERING, false))
    }

    @Test
    fun `explicit pause while buffering shows play rather than ignoring the pause`() {
        assertFalse(PlayerUiState(isBuffering = true, playWhenReady = false).isPlaybackActive)
        assertFalse(activePlaybackIntent(false, Player.STATE_BUFFERING, false))
    }

    @Test
    fun `ended and error override retained autoplay intent`() {
        assertFalse(activePlaybackIntent(true, Player.STATE_ENDED, false))
        assertFalse(activePlaybackIntent(true, Player.STATE_IDLE, true))
        assertTrue(activePlaybackIntent(true, Player.STATE_IDLE, false))
        assertTrue(activePlaybackIntent(true, Player.STATE_READY, false))
    }

    @Test
    fun `playing buffering playing handoff never flips the control icon`() {
        val states = listOf(
            PlayerUiState(isPlaying = true, playWhenReady = true, mediaId = 1),
            PlayerUiState(isPlaying = false, playWhenReady = true, isBuffering = true, mediaId = 2),
            PlayerUiState(isPlaying = true, playWhenReady = true, mediaId = 2)
        )
        assertEquals(listOf(true, true, true), states.map { it.isPlaybackActive })
        assertEquals(listOf(true, false, true), states.map { it.isPlaying })
    }
}
