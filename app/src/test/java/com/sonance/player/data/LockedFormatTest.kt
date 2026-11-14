package com.sonance.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲库过滤用到的纯判定函数。
 *
 * 关键点：`looksLikeLockedContainer` 只用来**挑可疑项**，不能当最终判定 ——
 * 因为 `X.kgm.flac`（加密，要排除）和 `X.kgm.mp3`（已转换，要保留）名字一模一样，
 * 最终必须读文件头（见 [PrivateAudioRestorer.parseHeader] 与 `MediaRepository.readIsLockedContainer`）。
 */
class LockedFormatTest {

    @Test
    fun `looksLikeLockedContainer 能认出藏在中间段的加密标记`() {
        assertTrue(LockedFormat.looksLikeLockedContainer("Adele - Hello_HQ.kgm.flac"))
        assertTrue(LockedFormat.looksLikeLockedContainer("Adele - Hello_HQ.kgma.flac"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.kgm (1).flac"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.ncm.mp3"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.qmcflac"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.qmc0"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.mflac"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.mgg1"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.tkm"))
        assertTrue(LockedFormat.looksLikeLockedContainer("X.vpr"))
        // 已转换的成品也会命中 —— 这是有意的，它只是「可疑」，后面还要读头确认
        assertTrue(LockedFormat.looksLikeLockedContainer("Adele - Hello_HQ.kgm.mp3"))
    }

    @Test
    fun `looksLikeLockedContainer 不误伤普通命名`() {
        assertFalse(LockedFormat.looksLikeLockedContainer("Adele - Hello_HQ.flac"))
        assertFalse(LockedFormat.looksLikeLockedContainer("Adele - Hello_HQ.mp3"))
        assertFalse(LockedFormat.looksLikeLockedContainer("Mr. Polska - Move Up.mp3"))
        assertFalse(LockedFormat.looksLikeLockedContainer("G.E.M. 邓紫棋 - 来自天堂的魔鬼_HQ.flac"))
        assertFalse(LockedFormat.looksLikeLockedContainer("song.m4a"))
        assertFalse(LockedFormat.looksLikeLockedContainer(""))
    }

    @Test
    fun `isNonSongFolder 认出录音与系统提示音目录`() {
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Music/Recordings"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Recordings"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Ringtones"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Notifications"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Alarms"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/通话录音"))
        assertTrue(LockedFormat.isNonSongFolder("/storage/emulated/0/Music/语音备忘录"))
    }

    @Test
    fun `isNonSongFolder 不误伤正常音乐目录`() {
        assertFalse(LockedFormat.isNonSongFolder("/storage/emulated/0/Music"))
        assertFalse(LockedFormat.isNonSongFolder("/storage/emulated/0/Music/周杰伦"))
        assertFalse(LockedFormat.isNonSongFolder("/storage/emulated/0/Download/专辑"))
        // 音乐专辑目录常带 Live Recordings / Recording Studio / The Alarm 等词，不应误杀
        assertFalse(LockedFormat.isNonSongFolder("Music/Live Recordings/Coldplay"))
        assertFalse(LockedFormat.isNonSongFolder("Music/Sun Recording Studio"))
        assertFalse(LockedFormat.isNonSongFolder("Music/The Alarm"))
        assertFalse(LockedFormat.isNonSongFolder(""))
    }

    @Test
    fun `isPlainUnsupported 认出系统解码不了的格式`() {
        assertTrue(LockedFormat.isPlainUnsupported("x.ape"))
        assertTrue(LockedFormat.isPlainUnsupported("x.wma"))
        assertTrue(LockedFormat.isPlainUnsupported("x.dsf"))
        assertTrue(LockedFormat.isPlainUnsupported("x.TAK"))
        assertFalse(LockedFormat.isPlainUnsupported("x.mp3"))
        assertFalse(LockedFormat.isPlainUnsupported("x.flac"))
    }

    @Test
    fun `allExtensions 覆盖主要加密格式`() {
        val all = LockedFormat.allExtensions
        assertTrue(all.containsAll(listOf("kgm", "kgma", "kgg", "vpr", "ncm", "qmcflac", "mflac", "mgg", "x2m")))
    }
}
