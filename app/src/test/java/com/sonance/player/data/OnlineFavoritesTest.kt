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
        durationMs: Long = 269_000L,
        displayName: String = "$title - $artist.mp3"
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
        displayName = displayName,
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

    @Test
    fun `matchesSong handles multi-artist separators fullwidth brackets and untagged flac filename`() {
        val duet = stored(remoteId = "2001", title = "因为爱情（Live）", artist = "周杰伦 / 那英", durationMs = 202_000L)
        assertTrue(
            OnlineFavorites.matchesSong(
                duet,
                localSong(id = 201L, title = "因为爱情(live)", artist = "周杰伦、那英", durationMs = 201_000L)
            )
        )

        val flac = stored(remoteId = "2002", title = "麻雀", artist = "李荣浩", durationMs = 252_000L)
        assertTrue(
            OnlineFavorites.matchesSong(
                flac,
                localSong(
                    id = 202L,
                    title = "李荣浩 - 麻雀",
                    artist = "未知艺术家",
                    durationMs = 252_000L,
                    displayName = "李荣浩 - 麻雀.flac"
                )
            )
        )
    }

    @Test
    fun `isOnlineSongAvailableLocally detects pre-existing local songs and session downloads`() {
        val onlineNotDownloaded = onlineSong(stored(remoteId = "3001", title = "麻雀", artist = "李荣浩", durationMs = 252_000L))
        val unrelatedLocal = listOf(localSong(id = 1L, title = "晴天", artist = "周杰伦", durationMs = 269_000L))

        // 无本地副本、未记录本轮下载 -> 需要显示下载按钮
        assertFalse(
            OnlineFavorites.isOnlineSongAvailableLocally(
                song = onlineNotDownloaded,
                localSongs = unrelatedLocal,
                downloadedKeys = emptySet(),
                sessionDownloadedKeys = emptySet()
            )
        )

        // 本地已有同一首歌（例如手动导入/缓存转换，downloadedKeys 为空）-> 不再显示下载按钮
        val existingLocal = listOf(localSong(id = 2L, title = "麻雀", artist = "李荣浩", durationMs = 252_000L))
        assertTrue(
            OnlineFavorites.isOnlineSongAvailableLocally(
                song = onlineNotDownloaded,
                localSongs = existingLocal,
                downloadedKeys = emptySet(),
                sessionDownloadedKeys = emptySet()
            )
        )

        // 历史键存在但本地曲库已扫完且找不到文件（文件已被删除）-> 允许重新下载
        assertFalse(
            OnlineFavorites.isOnlineSongAvailableLocally(
                song = onlineNotDownloaded,
                localSongs = unrelatedLocal,
                downloadedKeys = setOf("netease:3001"),
                sessionDownloadedKeys = emptySet()
            )
        )

        // 本轮刚刚完成下载但尚在等媒体库重扫 -> 立即判定为已下载，隐藏按钮
        assertTrue(
            OnlineFavorites.isOnlineSongAvailableLocally(
                song = onlineNotDownloaded,
                localSongs = unrelatedLocal,
                downloadedKeys = setOf("netease:3001"),
                sessionDownloadedKeys = setOf("netease:3001")
            )
        )
    }

    @Test
    fun `LocalSongIndex matches linear scan semantics across tags filenames and fallback rules`() {
        val localLibrary = listOf(
            localSong(id = 1L, title = "晴天", artist = "周杰伦", durationMs = 269_000L, displayName = "晴天.mp3"),
            localSong(id = 2L, title = "因为爱情 (Live)", artist = "周杰伦、那英", durationMs = 201_000L, displayName = "duet.flac"),
            localSong(id = 3L, title = "李荣浩 - 麻雀", artist = "未知艺术家", durationMs = 252_000L, displayName = "李荣浩 - 麻雀.flac"),
            localSong(id = 4L, title = "Love Me Do?", artist = "The Beatles", durationMs = 142_000L, displayName = "The Beatles - Love Me Do_.flac"),
            localSong(id = 5L, title = "无歌手歌名", artist = "", durationMs = 180_000L, displayName = "无歌手歌名.mp3")
        )
        val index = OnlineFavorites.buildLocalIndex(localLibrary)

        val candidates = listOf(
            RemoteTrack("101", "晴天", "周杰伦", "叶惠美", 270_000L, null, playable = true, vip = false),
            RemoteTrack("102", "因为爱情（Live）", "周杰伦 / 那英", "", 202_000L, null, playable = true, vip = false),
            RemoteTrack("103", "麻雀", "李荣浩", "", 252_000L, null, playable = true, vip = false),
            RemoteTrack("104", "Love Me Do?", "The Beatles", "", 141_000L, null, playable = true, vip = false),
            RemoteTrack("105", "无歌手歌名", "某歌手", "", 181_000L, null, playable = true, vip = false),
            RemoteTrack("106", "晴天", "林俊杰", "", 269_000L, null, playable = true, vip = false),
            RemoteTrack("107", "晴天", "周杰伦", "", 100_000L, null, playable = true, vip = false),
            RemoteTrack("108", "不存在的歌", "未知", "", 210_000L, null, playable = true, vip = false)
        )
        val downloadedKeys = setOf("netease:108")
        val sessionKeys = setOf("netease:106")

        for (track in candidates) {
            val expected = OnlineFavorites.isRemoteTrackAvailableLocally(
                sourceId = "netease",
                track = track,
                localSongs = localLibrary,
                downloadedKeys = downloadedKeys,
                sessionDownloadedKeys = sessionKeys
            )
            val actual = OnlineFavorites.isRemoteTrackAvailableLocally(
                sourceId = "netease",
                track = track,
                localIndex = index,
                downloadedKeys = downloadedKeys,
                sessionDownloadedKeys = sessionKeys
            )
            assertEquals("mismatch for ${track.remoteId}:${track.title}", expected, actual)

            val asSong = onlineSong(stored(track.remoteId, track.title, track.artist, track.durationMs))
            assertEquals(
                expected,
                OnlineFavorites.isOnlineSongAvailableLocally(asSong, index, downloadedKeys, sessionKeys)
            )
        }

        // 未扫库时历史 downloadedKeys 兜底仍生效，与 localSongs = emptyList() 一致
        val emptyIndex = OnlineFavorites.buildLocalIndex(emptyList())
        assertTrue(
            OnlineFavorites.isRemoteTrackAvailableLocally(
                sourceId = "netease",
                track = candidates.last(),
                localIndex = emptyIndex,
                downloadedKeys = downloadedKeys,
                sessionDownloadedKeys = emptySet()
            )
        )
    }
}
