package com.sonance.player.ui.icon

import org.junit.Assert.*
import org.junit.Test

class PlaybackIconFamilyTest {
    @Test
    fun `all player chrome icons follow the same selected family as transport icons`() {
        val previous = IconSetState.style
        try {
            for (style in 0..2) {
                IconSetState.set(style)
                val icons = listOf(SonanceIcons.ChevronDown, SonanceIcons.Maximize, SonanceIcons.Timer,
                    SonanceIcons.Search, SonanceIcons.Layers, SonanceIcons.Prev, SonanceIcons.Next,
                    SonanceIcons.Equalizer, SonanceIcons.Queue)
                icons.forEach {
                    assertTrue("${it.name} did not follow style $style", it.name.endsWith(style.toString()))
                    assertEquals(24f, it.viewportWidth, 0f)
                    assertEquals(24f, it.viewportHeight, 0f)
                }
            }
        } finally {
            IconSetState.set(previous)
        }
    }
}
