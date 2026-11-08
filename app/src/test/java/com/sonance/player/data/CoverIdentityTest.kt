package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverIdentityTest {
    @Test
    fun `local selection follows source uri rather than song title`() {
        val first = selectedCoverIdentity(12, "content://media/audio/12")
        assertEquals(first, selectedCoverIdentity(99, "content://media/audio/12"))
        assertNotEquals(first, selectedCoverIdentity(13, "content://media/audio/13"))
    }

    @Test
    fun `online selection ignores expiring playback url`() {
        assertEquals(selectedCoverIdentity(-42, "https://example.invalid/a?token=1"),
            selectedCoverIdentity(-42, "https://example.invalid/a?token=2"))
        assertNotEquals(selectedCoverIdentity(-42, null), selectedCoverIdentity(-43, null))
    }

    @Test
    fun `missing song sentinels do not get a persistent identity`() {
        assertNull(selectedCoverIdentity(0, null))
        assertNull(selectedCoverIdentity(-1, null))
    }

    @Test
    fun `one song without embedded art does not hide another in same album`() {
        val first = localCoverSources(10, "content://media/audio/1")
        val second = localCoverSources(10, "content://media/audio/2")
        val missing = setOf(first.album, first.file)
        assertTrue(allLocalSourcesMissing(first) { it in missing })
        assertFalse(allLocalSourcesMissing(second) { it in missing })
    }

    @Test
    fun `remote playback urls are never treated as local cover files`() {
        val remote = localCoverSources(0, "https://example.invalid/audio")
        assertNull(remote.file)
        assertNull(remote.album)
    }
}
