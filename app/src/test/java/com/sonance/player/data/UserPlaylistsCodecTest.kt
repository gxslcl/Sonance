package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPlaylistsCodecTest {

    private val online = StoredOnlineSong(
        source = "wy",
        remoteId = "r-1",
        title = "平凡之路",
        artist = "朴树",
        album = "猎户星座",
        durationMs = 302_000L,
        coverUrl = null,
        playable = true,
        vip = false
    )

    @Test
    fun `round trip keeps local and online entries in order`() {
        val pl = UserPlaylist(
            id = "upl-1",
            name = "深夜循环",
            desc = "测试",
            createdAt = 123L,
            entries = listOf(PlaylistEntry(localId = 7L), PlaylistEntry(online = online), PlaylistEntry(localId = 9L))
        )
        val decoded = UserPlaylistsCodec.decode(UserPlaylistsCodec.encode(listOf(pl)))
        assertEquals(1, decoded.size)
        val back = decoded[0]
        assertEquals("upl-1", back.id)
        assertEquals("深夜循环", back.name)
        assertEquals(listOf(7L, online.songId(), 9L), back.songIds)
        assertTrue(back.entries[1].isOnline)
        assertEquals("平凡之路", back.entries[1].online?.title)
    }

    @Test
    fun `decode tolerates garbage and drops broken entries`() {
        assertTrue(UserPlaylistsCodec.decode(null).isEmpty())
        assertTrue(UserPlaylistsCodec.decode("not json").isEmpty())
        val raw = """[{"id":"a","name":"ok","entries":[{"localId":1},{"localId":0},{}]}, {"name":"no id"}]"""
        val decoded = UserPlaylistsCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(listOf(1L), decoded[0].songIds)
    }

    @Test
    fun `online entry id matches remote song id hash`() {
        val entry = PlaylistEntry(online = online)
        assertEquals(remoteSongId("wy", "r-1"), entry.songId)
    }
}