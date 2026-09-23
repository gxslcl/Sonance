package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 「把缓存里的封面 / 歌词写回音乐文件」里能上 JVM 的部分。
 *
 * 候选判定和容器判断是**安全边界**：判宽了会往不该动的文件里写东西，
 * 判错了会把 WAV 当 MP3 改坏 —— 所以单独钉住。
 */
class TagWriteBackTest {

    // ===== decide：文件里已有的不动，缓存里没有的不写 =====

    @Test
    fun `文件里都没有而缓存里都有_两样都补`() {
        val (cover, lyrics) = TagWriteBack.decide(
            fileHasCover = false, fileHasLyrics = false,
            cacheCover = byteArrayOf(1, 2), cacheLyrics = "[00:01.00]词"
        )
        assertArrayEquals(byteArrayOf(1, 2), cover)
        assertEquals("[00:01.00]词", lyrics)
    }

    @Test
    fun `文件里已经有了就不动`() {
        val (cover, lyrics) = TagWriteBack.decide(
            fileHasCover = true, fileHasLyrics = true,
            cacheCover = byteArrayOf(1, 2), cacheLyrics = "[00:01.00]词"
        )
        assertNull(cover)
        assertNull(lyrics)
    }

    @Test
    fun `缓存里没有的不会硬写`() {
        val (cover, lyrics) = TagWriteBack.decide(
            fileHasCover = false, fileHasLyrics = false, cacheCover = null, cacheLyrics = null
        )
        assertNull(cover)
        assertNull(lyrics)
    }

    @Test
    fun `缓存里只有歌词时只写歌词`() {
        val (cover, lyrics) = TagWriteBack.decide(
            fileHasCover = true, fileHasLyrics = false,
            cacheCover = byteArrayOf(1, 2), cacheLyrics = "纯文本歌词"
        )
        assertNull(cover)
        assertEquals("纯文本歌词", lyrics)
    }

    @Test
    fun `空白歌词等于没有`() {
        val (_, lyrics) = TagWriteBack.decide(
            fileHasCover = true, fileHasLyrics = false,
            cacheCover = null, cacheLyrics = "   "
        )
        assertNull(lyrics)
    }

    // ===== isMp3：容器判断是安全边界 =====

    @Test
    fun `ID3 开头是 MP3`() {
        assertTrue(TagWriteBack.isMp3(byteArrayOf(0x49, 0x44, 0x33, 0x03)))
    }

    @Test
    fun `裸 MPEG 帧同步是 MP3`() {
        assertTrue(TagWriteBack.isMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        // 层位任意，只要 11 位同步 + 版本/层不是保留值
        assertTrue(TagWriteBack.isMp3(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x00)))
    }

    @Test
    fun `WAV 和 FLAC 一律不算`() {
        assertFalse(TagWriteBack.isMp3("RIFF____WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(TagWriteBack.isMp3("fLaC".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `太短的头部不算`() {
        assertFalse(TagWriteBack.isMp3("ID".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(TagWriteBack.isMp3(ByteArray(0)))
    }
}
