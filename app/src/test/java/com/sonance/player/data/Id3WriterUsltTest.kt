package com.sonance.player.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class Id3WriterUsltTest {

    @Test
    fun `Id3Writer buildTag with USLT lyrics round-trips cleanly through AudioTags`() {
        val lrc = "[00:00.50]山川载不动太多悲哀\n[00:04.20]岁月经不起太长的等待"
        val tag = Id3Writer.buildTag(
            title = "测试歌曲",
            artist = "测试歌手",
            album = "测试专辑",
            cover = null,
            lyrics = lrc
        )
        val mp3Bytes = tag + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(mp3Bytes)))
    }
}
