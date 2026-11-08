package com.sonance.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.sonance.player.data.PlayButtonColorMode
import org.junit.Assert.*
import org.junit.Test

class PlayerPaletteTest {
    @Test
    fun `three modes round trip and original is the migration default`() {
        assertEquals(3, PlayButtonColorMode.entries.size)
        PlayButtonColorMode.entries.forEach { assertEquals(it, PlayButtonColorMode.fromId(it.id)) }
        listOf(null, "", "unknown").forEach {
            assertEquals(PlayButtonColorMode.ORIGINAL, PlayButtonColorMode.fromId(it))
        }
    }

    @Test
    fun `original palette preserves exact cyan indigo rose colors`() {
        val original = originalPlayButtonPalette(Color.White)
        assertEquals(Color(0xFF38BDF8), original.light)
        assertEquals(Color(0xFF6366F1), original.main)
        assertEquals(Color(0xFFEC4899), original.deep)
        assertEquals(Color.White, original.icon)
    }

    @Test
    fun `custom palettes remain gradients and keep the play glyph readable`() {
        val seeds = listOf(Color.Black, Color.White, Color.Gray, Color.Red, Color.Green, Color.Blue,
            Color.Yellow, Color.Cyan, Color.Magenta, Color(0xFF5271FF), Color(0xFFF59E0B))
        for (seed in seeds) {
            val p = playButtonPalette(seed)
            assertNotEquals(p.light, p.deep)
            for ((a, b) in listOf(p.light to p.main, p.main to p.deep)) {
                for (step in 0..20) {
                    assertTrue("$seed gradient must contrast with its glyph",
                        contrastRatio(lerp(a, b, step / 20f), p.icon) >= 3.4f)
                }
            }
        }
    }

    @Test
    fun `neutral covers remain neutral rather than borrowing the theme hue`() {
        val seed = coverPaletteSeed(listOf(Color.White, Color.Black, Color.Gray), Color.Blue)
        val p = playButtonPalette(seed)
        for (c in listOf(p.light, p.main, p.deep)) {
            assertEquals(c.red, c.green, 0.001f)
            assertEquals(c.green, c.blue, 0.001f)
        }
        assertEquals(Color.Blue, coverPaletteSeed(emptyList(), Color.Blue))
    }

    @Test
    fun `header coordination keeps a readable gap on dark and bright backgrounds`() {
        val backgrounds = listOf(Color.Black, Color.White, Color.Gray, Color.Red, Color.Green,
            Color.Blue, Color.Yellow, Color.Cyan, Color(0xFF241F1A), Color(0xFF232942))
        backgrounds.forEach { bg ->
            val c = harmonizedIndicatorColor(bg, Color(0xFF5271FF))
            assertTrue("indicator disappeared into $bg", contrastRatio(c, bg) >= 3.35f)
            assertTrue(c.red.isFinite() && c.green.isFinite() && c.blue.isFinite())
        }
    }
}
