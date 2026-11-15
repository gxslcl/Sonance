package com.sonance.player.ui

import com.sonance.player.data.SettingsBaseline
import com.sonance.player.ui.sheets.sleepCountdownChipLabel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFeatureFixesTest {

    @Test
    fun `sleep timer countdown chips label 60 and 90 minutes explicitly`() {
        assertEquals("5 分钟", sleepCountdownChipLabel(5))
        assertEquals("15 分钟", sleepCountdownChipLabel(15))
        assertEquals("45 分钟", sleepCountdownChipLabel(45))
        assertEquals("60 分钟", sleepCountdownChipLabel(60))
        assertEquals("90 分钟", sleepCountdownChipLabel(90))
    }

    @Test
    fun `settings baseline includes new mini show time and system audio effect keys`() {
        val keys = SettingsBaseline.specs.map { it.key to it.type }.toMap()
        assertEquals(SettingsBaseline.Type.BOOLEAN, keys["mini_show_time"])
        assertEquals(SettingsBaseline.Type.BOOLEAN, keys["system_audio_effect_enabled"])
    }

    @Test
    fun `vinyl asset hides artist along with song title when hide-song-title class is active`() {
        val candidates = listOf(
            File("src/main/assets/vinyl/vinyl.html"),
            File("app/src/main/assets/vinyl/vinyl.html")
        )
        val vinylFile = candidates.firstOrNull { it.isFile }
        assertTrue("vinyl.html not found from working dir ${File(".").absolutePath}", vinylFile != null)
        val html = vinylFile!!.readText()
        assertTrue(
            html.contains("#app-viewport.hide-song-title #song-title") &&
                html.contains("#app-viewport.hide-song-title #song-artist")
        )
    }
}
