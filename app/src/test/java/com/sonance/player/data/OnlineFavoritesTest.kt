package com.sonance.player.data

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineFavoritesTest {

    private fun stored(
        remoteId: String = "1001",
        title: String = "晴天",
        artist: String = "周杰伦",
        durationMs: Long = 269_000L
    ) = StoredOnlineSong(
        source = "netease",
        remoteId = remoteId,
        title = title,
        artist = artist,
        album = "叶惠美",
        durationMs = durationMs,
        coverUrl = "https://example.com/c.jpg",
        playable = true,
        vip = false
    )

    private fun localSong(
        id: Long = 88L,
        title: String = "晴天",
        artist: String = "周杰伦",
        durationMs: Long = 269_000L
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "叶惠美",
        albumId = 9L,
        durationMs = durationMs,
        uri = TestUri("content://media/external/audio/media/$id"),
        folderPath = "/Music/山水有清音下载",
        folderName = "山水有清音下载",
        displayName = "$title - $artist.mp3",
        sizeBytes = 4_000_000L,
        dateAddedSec = 100L,
        track = 1
    )

    private fun onlineSong(stored: StoredOnlineSong) = Song(
        id = stored.songId(),
        title = stored.title,
        artist = stored.artist,
        album = stored.album,
        albumId = 0L,
        durationMs = stored.durationMs,
        uri = TestUri("https://example.com/${stored.remoteId}.mp3"),
        folderPath = "",
        folderName = "在线",
        displayName = stored.title,
        sizeBytes = 0L,
        dateAddedSec = 0L,
        track = 0,
        source = stored.source,
        remoteId = stored.remoteId,
        coverUrl = stored.coverUrl
    )

    @Test
    fun `effectiveFavoriteIds never double counts linked online favorite`() {
        val online = stored()
        val unlinked = OnlineFavoriteEntry(online, likedAt = 1L, localSongId = 0L)
        val unlinkedIds = OnlineFavorites.effectiveFavoriteIds(emptySet(), listOf(unlinked))
        assertEquals(setOf(online.songId()), unlinkedIds)

        val linked = OnlineFavoriteEntry(online, likedAt = 1L, localSongId = 88L)
        val linkedIds = OnlineFavorites.effectiveFavoriteIds(setOf(88L), listOf(linked))
        assertEquals(setOf(88L), linkedIds)
        assertEquals(1, linkedIds.size)
    }

    @Test
    fun `isFavoriteSong stays true on playing online song even after migrating to local id`() {
        val online = stored()
        val playingOnline = onlineSong(online)
        val downloadedLocal = localSong(id = 88L)
        val linked = OnlineFavoriteEntry(online, likedAt = 1L, localSongId = 88L)

        assertTrue(OnlineFavorites.isFavoriteSong(playingOnline, setOf(88L), listOf(linked)))
        assertTrue(OnlineFavorites.isFavoriteSong(downloadedLocal, setOf(88L), listOf(linked)))
        assertTrue(OnlineFavorites.isFavoriteId(online.songId(), setOf(88L), listOf(linked)))
        assertTrue(OnlineFavorites.isFavoriteId(88L, setOf(88L), listOf(linked)))
    }

    @Test
    fun `reconcileWithLibrary migrates pending online favorite to local song and cleans unliked entries`() {
        val online = stored()
        val downloadedLocal = localSong(id = 88L)

        // 1) 下载入库后：关联本地 ID 并迁入 localFavorites，总计依然为 1
        val r1 = OnlineFavorites.reconcileWithLibrary(
            localSongs = listOf(downloadedLocal),
            localFavorites = emptySet(),
            onlineFavorites = listOf(OnlineFavoriteEntry(online, likedAt = 1L, localSongId = 0L))
        )
        assertTrue(r1.changed)
        assertEquals(setOf(88L), r1.localFavorites)
        assertEquals(88L, r1.onlineFavorites.single().localSongId)

        // 2) 用户在本地收藏列表中取消收藏 88L：在线关联记录一并移除
        val r2 = OnlineFavorites.reconcileWithLibrary(
            localSongs = listOf(downloadedLocal),
            localFavorites = emptySet(),
            onlineFavorites = r1.onlineFavorites
        )
        assertTrue(r2.changed)
        assertTrue(r2.localFavorites.isEmpty())
        assertTrue(r2.onlineFavorites.isEmpty())
    }

    @Test
    fun `reconcileWithLibrary preserves online favorite when downloaded file disappears`() {
        val otherLocal = localSong(id = 12L, title = "七里香")
        val online = stored(remoteId = "1001", title = "晴天")
        val entry = OnlineFavoriteEntry(online, likedAt = 1L, localSongId = 88L)

        val res = OnlineFavorites.reconcileWithLibrary(
            localSongs = listOf(otherLocal),
            localFavorites = setOf(88L),
            onlineFavorites = listOf(entry)
        )
        assertTrue(res.changed)
        assertFalse(88L in res.localFavorites)
        assertEquals(1, res.onlineFavorites.size)
        assertEquals(0L, res.onlineFavorites.single().localSongId)
    }

    @Test
    fun `matchesSong checks normalized title artist and duration window`() {
        val s = stored(title = "  晴天 ", artist = "周杰伦", durationMs = 269_000L)
        assertTrue(OnlineFavorites.matchesSong(s, localSong(title = "晴天", artist = "周杰伦", durationMs = 270_000L)))
        assertFalse(OnlineFavorites.matchesSong(s, localSong(title = "晴天", artist = "林俊杰", durationMs = 269_000L)))
        assertFalse(OnlineFavorites.matchesSong(s, localSong(title = "晴天", artist = "周杰伦", durationMs = 35_000L)))
    }
}
