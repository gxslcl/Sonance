package com.sonance.player.ui.components

import com.sonance.player.playback.PlayerUiState

/** Selecting a neighboring page autoplays it; only the current page reflects actual play intent. */
internal fun pagerPlayButtonActive(isCurrent: Boolean, state: PlayerUiState): Boolean =
    if (isCurrent) state.isPlaybackActive else true
