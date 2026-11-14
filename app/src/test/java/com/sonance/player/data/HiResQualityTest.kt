package com.sonance.player.data

import com.sonance.player.ui.theme.HiResBadgeSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiResQualityTest {

    @Test
    fun `lossless extensions always show badge`() {
        listOf("test.flac", "master.WAV", "live.aiff", "track.alac", "hi.dsf", "sacd.dff", "old.ape")
            .forEach { name ->
                assertTrue("Expected true for $name", HiResQuality.isLosslessExtension(name))
                assertTrue(
                    "Expected badge for $name",
                    HiResQuality.shouldHighlightLocalAudio(
                        displayName = name,
                        mimeType = null,
                        sampleRateHz = 44_100,
                        bitrateBps = 0
                    )
                )
            }
    }

    @Test
    fun `high bitrate lossy shows badge while standard lossy does not`() {
        assertTrue(
            HiResQuality.shouldHighlightLocalAudio(
                displayName = "hq.mp3",
                mimeType = "audio/mpeg",
                sampleRateHz = 44_100,
                bitrateBps = 320_000
            )
        )
        assertTrue(
            HiResQuality.shouldHighlightLocalAudio(
                displayName = "itunes.m4a",
                mimeType = "audio/mp4a-latm",
                sampleRateHz = 44_100,
                bitrateBps = 256_000
            )
        )
        assertFalse(
            HiResQuality.shouldHighlightLocalAudio(
                displayName = "std.mp3",
                mimeType = "audio/mpeg",
                sampleRateHz = 44_100,
                bitrateBps = 128_000
            )
        )
        assertFalse(
            HiResQuality.shouldHighlightLocalAudio(
                displayName = "mid.mp3",
                mimeType = "audio/mpeg",
                sampleRateHz = 44_100,
                bitrateBps = 192_000
            )
        )
    }

    @Test
    fun `online quality labels match high quality lossless and hires`() {
        assertTrue(HiResQuality.shouldHighlightOnlineQuality("Hi-Res"))
        assertTrue(HiResQuality.shouldHighlightOnlineQuality("无损"))
        assertTrue(HiResQuality.shouldHighlightOnlineQuality("320k"))
        assertFalse(HiResQuality.shouldHighlightOnlineQuality("192k"))
        assertFalse(HiResQuality.shouldHighlightOnlineQuality("128k"))
        assertFalse(HiResQuality.shouldHighlightOnlineQuality("试听"))
        assertFalse(HiResQuality.shouldHighlightOnlineQuality(null))
    }

    @Test
    fun `audio tech spec formats sample rate bit depth and bitrate cleanly`() {
        val flac16 = AudioTechSpec(
            formatLabel = "FLAC",
            mimeType = "audio/flac",
            sampleRateHz = 44_100,
            bitDepth = 16,
            bitrateBps = 921_000,
            bitrateEstimated = false,
            channelCount = 2,
            sizeBytes = 28_000_000L,
            isLossless = true
        )
        assertEquals("16bit / 44.1kHz FLAC", flac16.summarySpecText())
        assertEquals("921 kbps", flac16.bitrateText())

        val flac24_48 = flac16.copy(sampleRateHz = 48_000, bitDepth = 24, bitrateEstimated = true, bitrateBps = 1_536_000)
        assertEquals("24bit / 48kHz FLAC", flac24_48.summarySpecText())
        assertEquals("约 1536 kbps", flac24_48.bitrateText())

        val mp3 = AudioTechSpec(
            formatLabel = "MP3",
            mimeType = "audio/mpeg",
            sampleRateHz = 44_100,
            bitDepth = 0,
            bitrateBps = 320_000,
            bitrateEstimated = false,
            channelCount = 2,
            sizeBytes = 9_600_000L,
            isLossless = false
        )
        assertEquals("44.1kHz MP3", mp3.summarySpecText())
        assertEquals("320 kbps", mp3.bitrateText())
    }

    @Test
    fun `parses flac streaminfo and wav fmt headers accurately`() {
        // 44100 Hz (0x0AC44), 2 ch (code=1), 16 bit (code=15)
        val flacHeader = ByteArray(42)
        "fLaC".encodeToByteArray().copyInto(flacHeader, 0)
        flacHeader[4] = 0x00 // STREAMINFO
        flacHeader[18] = 0x0A.toByte()
        flacHeader[19] = 0xC4.toByte()
        flacHeader[20] = 0x42.toByte() // sr low=4, ch-1=1 -> bits 3..1 = 001 (0x02), bps-1 MSB=0
        flacHeader[21] = 0xF0.toByte() // bps-1 low=15 (0x0F) in upper nibble

        val parsedFlac = HiResQuality.parseLosslessHeader(flacHeader)
        assertNotNull(parsedFlac)
        assertEquals(44_100, parsedFlac!!.sampleRateHz)
        assertEquals(16, parsedFlac.bitDepth)
        assertEquals(2, parsedFlac.channelCount)

        // WAV: 48000 Hz, 2 ch, 24 bit
        val wavHeader = ByteArray(44)
        "RIFF".encodeToByteArray().copyInto(wavHeader, 0)
        "WAVE".encodeToByteArray().copyInto(wavHeader, 8)
        "fmt ".encodeToByteArray().copyInto(wavHeader, 12)
        wavHeader[16] = 16 // fmt chunk size LE
        wavHeader[22] = 2  // channels
        // 48000 = 0x0000BB80 LE -> 80 BB 00 00
        wavHeader[24] = 0x80.toByte()
        wavHeader[25] = 0xBB.toByte()
        wavHeader[34] = 24 // bits per sample
        val parsedWav = HiResQuality.parseLosslessHeader(wavHeader)
        assertNotNull(parsedWav)
        assertEquals(48_000, parsedWav!!.sampleRateHz)
        assertEquals(24, parsedWav.bitDepth)
        assertEquals(2, parsedWav.channelCount)
    }

    @Test
    fun `hires badge size enum resolves small medium large and safe default`() {
        assertEquals(24, HiResBadgeSize.SMALL.badgeDp)
        assertEquals(30, HiResBadgeSize.MEDIUM.badgeDp)
        assertEquals(36, HiResBadgeSize.LARGE.badgeDp)
        assertEquals(HiResBadgeSize.SMALL, HiResBadgeSize.of(0))
        assertEquals(HiResBadgeSize.MEDIUM, HiResBadgeSize.of(1))
        assertEquals(HiResBadgeSize.LARGE, HiResBadgeSize.of(2))
        assertEquals(HiResBadgeSize.MEDIUM, HiResBadgeSize.of(99))
    }
}
