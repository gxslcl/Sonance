package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidecarAssetResolverTest {

    @Test
    fun `three separate files in same folder match even when cover and lyric names differ`() {
        val candidates = listOf("01. Track One.flac", "artwork_hires.png", "song_words.lrc")
        assertEquals(
            "artwork_hires.png",
            SidecarAssetResolver.selectBestSidecarCover(
                audioBaseName = "01. Track One",
                songTitle = "Track One",
                candidates = candidates,
                audioCountInFolder = 1
            )
        )
        assertEquals(
            "song_words.lrc",
            SidecarAssetResolver.selectBestSidecarLyric(
                audioBaseName = "01. Track One",
                songTitle = "Track One",
                candidates = candidates,
                audioCountInFolder = 1
            )
        )
    }

    @Test
    fun `multi-song folder prefers exact stem or standard cover name and does not mismatch random files`() {
        val candidates = listOf(
            "SongA.flac", "SongB.flac",
            "SongA.lrc", "cover.jpg", "random.jpg", "other.lrc"
        )
        assertEquals(
            "cover.jpg",
            SidecarAssetResolver.selectBestSidecarCover("SongA", "Song A", candidates, audioCountInFolder = 2)
        )
        assertEquals(
            "SongA.lrc",
            SidecarAssetResolver.selectBestSidecarLyric("SongA", "Song A", candidates, audioCountInFolder = 2)
        )
        assertNull(
            SidecarAssetResolver.selectBestSidecarLyric("SongB", "Song B", candidates, audioCountInFolder = 2)
        )
    }
}
