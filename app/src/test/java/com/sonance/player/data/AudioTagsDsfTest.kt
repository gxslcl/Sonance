package com.sonance.player.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTagsDsfTest {

    private fun le64(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 32) and 0xFF).toByte(),
        ((v ushr 40) and 0xFF).toByte(),
        ((v ushr 48) and 0xFF).toByte(),
        ((v ushr 56) and 0xFF).toByte()
    )

    @Test
    fun `DSF reads trailing ID3v2 cover and lyrics via metadataOffset without scanning payload`() {
        val fakeCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val lyricsText = "[00:01.00]DSF 歌词测试\n[00:05.00]第二句"
        val id3 = Id3Writer.buildTag("标题", "歌手", "专辑", fakeCover, "image/jpeg", lyricsText)

        val audioBody = ByteArray(1024) { 0x55.toByte() }
        val metadataOffset = 28L + audioBody.size.toLong()
        val dsf = ByteArrayOutputStream().apply {
            write("DSD ".toByteArray(Charsets.US_ASCII))
            write(le64(28L))
            write(le64(metadataOffset + id3.size))
            write(le64(metadataOffset))
            write(audioBody)
            write(id3)
        }.toByteArray()

        assertArrayEquals(fakeCover, AudioTags.coverArtFrom(ByteArrayInputStream(dsf)))
        assertEquals(lyricsText, AudioTags.lyricsFrom(ByteArrayInputStream(dsf)))
    }

    @Test
    fun `DFF WAV and OGG return null cleanly without misinterpreting magic as giant MP4 atom`() {
        val dff = "FRM8\u0000\u0000\u0001\u0000\u0000\u0000\u0000\u0000DSD ".toByteArray(Charsets.ISO_8859_1)
        val wav = "RIFF\u0000\u0010\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)
        val ogg = "OggS\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(dff)))
        assertNull(AudioTags.lyricsFrom(ByteArrayInputStream(dff)))
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(wav)))
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(ogg)))
    }
}
