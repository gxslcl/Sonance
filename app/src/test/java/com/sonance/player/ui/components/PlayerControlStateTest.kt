package com.sonance.player.ui.components

import com.sonance.player.playback.PlayerUiState
import org.junit.Assert.*
import org.junit.Test

class PlayerControlStateTest {
    @Test
    fun `neighboring preview already shows the pause action for autoplay`() {
        assertTrue(pagerPlayButtonActive(false, PlayerUiState(isPlaying = false)))
    }

    @Test
    fun `current page honors explicit pause and buffering intent`() {
        assertFalse(pagerPlayButtonActive(true, PlayerUiState(isPlaying = false)))
        assertTrue(pagerPlayButtonActive(true, PlayerUiState(isBuffering = true, playWhenReady = true)))
        assertTrue(pagerPlayButtonActive(true, PlayerUiState(isPlaying = true)))
    }
}
