package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenLyricsTest {

    @Test
    fun toLrcText_formatsSortedLrcTimestampsAndSkipsPlainLyrics() {
        val synced = Lyrics.Synced(
            raw = "",
            lines = listOf(
                LyricLine(65_320L, "第二句"),
                LyricLine(5_040L, "第一句"),
                LyricLine(80_000L, "   ")
            )
        )
        val lrc = LockScreenLyrics.toLrcText(synced)
        assertEquals("[00:05.040]第一句\n[01:05.320]第二句\n", lrc)
        assertNull(LockScreenLyrics.toLrcText(Lyrics.Plain(raw = "无时间轴", lines = listOf("无时间轴"))))
    }

    @Test
    fun buildJsonFromLrc_embedsLrcOrMarksNoLyric() {
        val lrc = LockScreenLyrics.toLrcText(listOf(LyricLine(1_000L, "故事的小黄花")))
        val json = LockScreenLyrics.buildJsonFromLrc(
            songId = 42L,
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L,
            lrc = lrc,
            generation = 3
        )
        assertTrue(json.contains("\"noLyric\":false"))
        assertTrue(json.contains("[00:01.000]故事的小黄花"))

        val noLyricJson = LockScreenLyrics.buildJsonFromLrc(
            songId = 42L,
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L,
            lrc = null,
            generation = 4
        )
        assertTrue(noLyricJson.contains("\"noLyric\":true"))
    }
}
