package com.sonance.player.data.lx

import com.sonance.player.data.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 无感多音源兜底的「同一首歌」匹配底线：宁可借不到，也不能串歌。
 */
class LxFallbackMatchTest {

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long = 200_000L,
        playable: Boolean = true,
        unavailableReason: String? = null
    ) = RemoteTrack(
        remoteId = id,
        title = title,
        artist = artist,
        album = "",
        durationMs = durationMs,
        coverUrl = null,
        playable = playable,
        vip = false,
        unavailableReason = unavailableReason
    )

    @Test
    fun `exact title and artist wins over partial`() {
        val tracks = listOf(
            track("a", "晴天", "周杰伦"),
            track("b", "晴天（Live）", "周杰伦")
        )
        val match = pickLxFallbackMatch(tracks, "晴天", "周杰伦", 200_000L)
        assertEquals("a", match?.remoteId)
    }

    @Test
    fun `partial title without artist match is rejected`() {
        // 搜「爱」会命中一大片：标题只是包含关系、歌手又对不上时不能借
        val tracks = listOf(
            track("a", "爱情转移", "陈奕迅"),
            track("b", "因为爱情", "王菲")
        )
        assertNull(pickLxFallbackMatch(tracks, "爱", "周杰伦", 200_000L))
    }

    @Test
    fun `partial title with matching artist is accepted`() {
        val tracks = listOf(track("a", "晴天 (Live)", "周杰伦"))
        val match = pickLxFallbackMatch(tracks, "晴天", "周杰伦", 200_000L)
        assertEquals("a", match?.remoteId)
    }

    @Test
    fun `unavailable track is skipped`() {
        val tracks = listOf(
            track("a", "晴天", "周杰伦", unavailableReason = "无版权"),
            track("b", "晴天", "周杰伦")
        )
        val match = pickLxFallbackMatch(tracks, "晴天", "周杰伦", 200_000L)
        assertEquals("b", match?.remoteId)
    }

    @Test
    fun `closer duration breaks the tie`() {
        val tracks = listOf(
            track("a", "晴天", "周杰伦", durationMs = 260_000L),
            track("b", "晴天", "周杰伦", durationMs = 201_000L)
        )
        val match = pickLxFallbackMatch(tracks, "晴天", "周杰伦", 200_000L)
        assertEquals("b", match?.remoteId)
    }

    @Test
    fun `blank title yields no match`() {
        val tracks = listOf(track("a", "晴天", "周杰伦"))
        assertNull(pickLxFallbackMatch(tracks, "  ", "周杰伦", 200_000L))
    }

    @Test
    fun `normalization ignores case space and punctuation`() {
        assertEquals(normLxMatch("晴 天"), normLxMatch("晴天"))
        assertEquals(normLxMatch("Love Story"), normLxMatch("love-story"))
        assertEquals(normLxMatch("周杰伦·Jay"), normLxMatch("周杰伦jay"))
    }

    @Test
    fun `no candidates yields null`() {
        assertNull(pickLxFallbackMatch(emptyList(), "晴天", "周杰伦", 200_000L))
    }
}