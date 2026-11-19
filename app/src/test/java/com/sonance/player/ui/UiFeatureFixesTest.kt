package com.sonance.player.ui

import com.sonance.player.data.PlayerSwitchMotion
import com.sonance.player.data.SettingsBaseline
import com.sonance.player.data.readDurableTextCache
import com.sonance.player.data.writeDurableTextCache
import com.sonance.player.ui.sheets.StudioConsoleSkin
import com.sonance.player.ui.sheets.sleepCountdownChipLabel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiFeatureFixesTest {

    @get:Rule
    val tempDir = TemporaryFolder()

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
        assertEquals(SettingsBaseline.Type.BOOLEAN, keys["auto_scan_on_launch"])
    }

    @Test
    fun `durable text cache persists migrates legacy value and deletes on null`() {
        val cacheFile = File(tempDir.root, "library_songs_cache.json")
        var legacy: String? = """[{"id":1,"title":"Test Song","uri":"content://media/1"}]"""
        var clearCalls = 0

        // 1. Migrate legacy SharedPreferences payload into durable file and clear legacy key.
        val migrated = readDurableTextCache(
            file = cacheFile,
            legacyFallback = { legacy },
            clearLegacy = {
                clearCalls++
                legacy = null
            }
        )
        assertEquals("""[{"id":1,"title":"Test Song","uri":"content://media/1"}]""", migrated)
        assertEquals(1, clearCalls)
        assertNull(legacy)
        assertTrue(cacheFile.isFile)

        // 2. Subsequent reads hit the durable file directly without consulting legacy fallback.
        var fallbackConsulted = false
        val fromFile = readDurableTextCache(
            file = cacheFile,
            legacyFallback = {
                fallbackConsulted = true
                null
            },
            clearLegacy = {}
        )
        assertFalse(fallbackConsulted)
        assertEquals(migrated, fromFile)

        // 3. Explicit write updates the durable file synchronously and clears legacy if any.
        val updatedJson = """[{"id":2,"title":"Updated","uri":"content://media/2"}]"""
        legacy = "stale"
        writeDurableTextCache(cacheFile, updatedJson) { legacy = null }
        assertNull(legacy)
        assertEquals(updatedJson, cacheFile.readText(Charsets.UTF_8))

        // 4. Writing null deletes the cache file cleanly.
        writeDurableTextCache(cacheFile, null) { legacy = null }
        assertFalse(cacheFile.exists())
        assertNull(readDurableTextCache(cacheFile, { null }, {}))
    }

    @Test
    fun `studio console skin uses fixed chinese red theme independent of app theme`() {
        assertFalse(StudioConsoleSkin.isLight)
        assertFalse(StudioConsoleSkin.immersive)
        assertTrue(StudioConsoleSkin.bg.red > StudioConsoleSkin.bg.blue)
        assertTrue(StudioConsoleSkin.accent.red > 0.80f && StudioConsoleSkin.accent.red > StudioConsoleSkin.accent.blue * 2f)
    }

    @Test
    fun `sonance view model declares pinyin and sort keys before init and restores song cache after properties`() {
        val candidates = listOf(
            File("src/main/java/com/sonance/player/SonanceViewModel.kt"),
            File("app/src/main/java/com/sonance/player/SonanceViewModel.kt")
        )
        val vmFile = candidates.firstOrNull { it.isFile }
        assertTrue("SonanceViewModel.kt not found", vmFile != null)
        val src = vmFile!!.readText()
        val pinyinIdx = src.indexOf("private var pinyinIndex by mutableStateOf")
        val sortKeysIdx = src.indexOf("var sortKeys by mutableStateOf")
        val firstInitIdx = Regex("(?m)^\\s*init\\s*\\{").find(src)?.range?.first ?: -1
        val cacheRestoreIdx = src.indexOf("val cachedJson = prefs.songsCacheJson")
        val lastDelegateIdx = Regex(
            "(?m)^\\s*(?:private\\s+|internal\\s+)?va[lr]\\s+\\w+\\s+by\\s+mutableStateOf"
        ).findAll(src).lastOrNull()?.range?.first ?: -1
        assertTrue(pinyinIdx in 1 until firstInitIdx)
        assertTrue(sortKeysIdx in 1 until firstInitIdx)
        assertTrue(lastDelegateIdx in 1 until cacheRestoreIdx)
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

    @Test
    fun `player switch motion exposes all four modes with poweramp slide default`() {
        val entries = PlayerSwitchMotion.entries
        assertEquals(4, entries.size)
        assertTrue(entries.contains(PlayerSwitchMotion.POWERAMP_SLIDE))
        assertTrue(entries.contains(PlayerSwitchMotion.DEPTH_SLIDE))
        assertTrue(entries.contains(PlayerSwitchMotion.TILT_3D))
        assertTrue(entries.contains(PlayerSwitchMotion.OPTICAL_DISSOLVE))
        assertEquals(PlayerSwitchMotion.POWERAMP_SLIDE, PlayerSwitchMotion.fromId("unknown"))
        entries.forEach { mode ->
            assertEquals(mode, PlayerSwitchMotion.fromId(mode.id))
        }
    }
}
