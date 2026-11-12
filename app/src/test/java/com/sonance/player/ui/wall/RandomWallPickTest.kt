package com.sonance.player.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「随便听听」随机挑选规则：封面墙与主播放界面单曲队列切歌共用。 */
class RandomWallPickTest {
    private fun album(key: String) =
        WallAlbum(key, key, "", false, emptyList(), emptyList())

    @Test
    fun emptyLibraryReturnsNull() {
        assertNull(pickRandomWallAlbum(emptyList(), null, emptySet()))
    }

    @Test
    fun excludesCurrentAndRecent() {
        val all = listOf(album("a"), album("b"), album("c"))
        repeat(50) {
            assertEquals("c", pickRandomWallAlbum(all, "a", setOf("b"))?.key)
        }
    }

    @Test
    fun fallsBackToAllWhenEverythingExcluded() {
        val all = listOf(album("a"), album("b"))
        repeat(50) {
            val pick = pickRandomWallAlbum(all, "a", setOf("b"))
            assertTrue(pick?.key in setOf("a", "b"))
        }
    }

    @Test
    fun singleAlbumStillPickable() {
        val all = listOf(album("only"))
        assertEquals("only", pickRandomWallAlbum(all, "only", emptySet())?.key)
    }

    @Test
    fun nullCurrentKeyOnlyExcludesRecent() {
        val all = listOf(album("a"), album("b"))
        repeat(50) {
            assertEquals("b", pickRandomWallAlbum(all, null, setOf("a"))?.key)
        }
    }
}