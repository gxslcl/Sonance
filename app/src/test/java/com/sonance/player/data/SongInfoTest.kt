package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongInfoTest {

    @Test
    fun `formatSize scales bytes KB and MB`() {
        assertEquals("未知", SongInfo.formatSize(0L))
        assertEquals("512 B", SongInfo.formatSize(512L))
        assertEquals("2.0 KB", SongInfo.formatSize(2048L))
        assertEquals("1.50 MB", SongInfo.formatSize(1572864L))
    }

    @Test
    fun `formatDuration and date handle missing values honestly`() {
        assertEquals("未知", SongInfo.formatDuration(0L))
        assertEquals("04:29", SongInfo.formatDuration(269_000L))
        assertEquals("未知", SongInfo.formatDate(0L))
    }

    @Test
    fun `extension handles dotless and trailing dot names`() {
        assertEquals("FLAC", SongInfo.extensionOf("晴天.flac"))
        assertEquals("M4A", SongInfo.extensionOf("a.b.m4a"))
        assertEquals("未知", SongInfo.extensionOf("noext"))
        assertEquals("未知", SongInfo.extensionOf("name."))
    }

    @Test
    fun `audio details render human readable fallbacks`() {
        val empty = SongInfo.AudioDetails()
        assertEquals("未知", empty.sampleRateText)
        assertEquals("未知", empty.bitDepthText)
        assertEquals("未知", empty.bitrateText)
        assertEquals("未知", empty.channelsText)

        val flac = SongInfo.AudioDetails(
            format = "audio/flac", sampleRateHz = 96000, bitDepth = 24,
            bitrateKbps = 1856, channels = 2
        )
        assertEquals("96000 Hz", flac.sampleRateText)
        assertEquals("24 bit", flac.bitDepthText)
        assertEquals("1856 kbps", flac.bitrateText)
        assertEquals("立体声", flac.channelsText)
    }
}
