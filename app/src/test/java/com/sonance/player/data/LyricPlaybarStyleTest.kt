package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricPlaybarStyleTest {
    @Test
    fun `both lyric control styles have unique persistent ids and round trip`() {
        assertEquals(2, LyricPlaybarStyle.entries.size)
        assertEquals(2, LyricPlaybarStyle.entries.map { it.id }.toSet().size)
        LyricPlaybarStyle.entries.forEach { style ->
            assertEquals(style, LyricPlaybarStyle.fromId(style.id))
        }
    }

    @Test
    fun `missing or invalid setting preserves original three buttons`() {
        listOf(null, "", "unknown", "classic", "tilted").forEach { id ->
            assertEquals(LyricPlaybarStyle.BUTTONS, LyricPlaybarStyle.fromId(id))
        }
    }
}
