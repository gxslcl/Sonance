package com.sonance.player.playback

import com.sonance.player.data.OnlineDownloader
import com.sonance.player.data.PlayerSwitchMotion
import com.sonance.player.data.Song
import com.sonance.player.filterDownloadableUserPlaylistSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureBatchOptimizationTest {

    @Test
    fun switchMotionRenamedToSmoothSlide() {
        assertEquals("丝滑横推", PlayerSwitchMotion.POWERAMP_SLIDE.label)
    }

    @Test
    fun shakeDetectorRequiresReverseReboundAndRespectsCooldownAndPocketGuard() {
        val detector = ShakeDetector(sensitivity = 1)

        // 单向冲击不应触发
        assertFalse(detector.onLinearAcceleration(13.5f, 0f, 0f, 1000L))
        // 同向继续晃动不应触发
        assertFalse(detector.onLinearAcceleration(12.0f, 0f, 0f, 1100L))
        // 450ms 窗口内的反向回弹应触发切歌
        assertTrue(detector.onLinearAcceleration(-11.0f, 0f, 0f, 1180L))

        // 1000ms 冷却期内再次大幅反向摇晃不应重复触发
        assertFalse(detector.onLinearAcceleration(15.0f, 0f, 0f, 1500L))
        assertFalse(detector.onLinearAcceleration(-15.0f, 0f, 0f, 1600L))

        // 口袋遮挡开启且距离传感器靠近时应屏蔽触发
        detector.pocketGuardEnabled = true
        detector.isProximityNear = true
        assertFalse(detector.onLinearAcceleration(16.0f, 0f, 0f, 3000L))
        assertFalse(detector.onLinearAcceleration(-16.0f, 0f, 0f, 3100L))

        // 离开口袋后可恢复正常触发
        detector.isProximityNear = false
        assertFalse(detector.onLinearAcceleration(14.0f, 0f, 0f, 3500L))
        assertTrue(detector.onLinearAcceleration(-12.0f, 0f, 0f, 3600L))
    }

    @Test
    fun shakeDetectorSensitivityAdjustsThreshold() {
        val lowSens = ShakeDetector(sensitivity = 0)
        val highSens = ShakeDetector(sensitivity = 2)

        assertFalse(lowSens.onLinearAcceleration(10.0f, 0f, 0f, 1000L))
        assertFalse(lowSens.onLinearAcceleration(-10.0f, 0f, 0f, 1100L))

        assertFalse(highSens.onLinearAcceleration(10.0f, 0f, 0f, 1000L))
        assertTrue(highSens.onLinearAcceleration(-8.5f, 0f, 0f, 1100L))
    }

    @Test
    fun headsetButtonClickCounterHandlesSingleDoubleTripleAndTimeoutReset() {
        val counter = HeadsetButtonClickCounter(clickWindowMs = 380L)

        // 单击 -> PLAY_PAUSE
        assertNull(counter.onClick(1000L))
        assertEquals(HeadsetButtonClickCounter.Action.PLAY_PAUSE, counter.consumePending())

        // 双击 -> NEXT
        assertNull(counter.onClick(2000L))
        assertNull(counter.onClick(2150L))
        assertEquals(HeadsetButtonClickCounter.Action.NEXT, counter.consumePending())

        // 三击 -> 立即返回 PREVIOUS，且清空 pending
        assertNull(counter.onClick(3000L))
        assertNull(counter.onClick(3120L))
        assertEquals(HeadsetButtonClickCounter.Action.PREVIOUS, counter.onClick(3240L))
        assertNull(counter.consumePending())

        // 超时重置：隔了 500ms 算新的一次单击
        assertNull(counter.onClick(4000L))
        assertNull(counter.onClick(4500L))
        assertEquals(HeadsetButtonClickCounter.Action.PLAY_PAUSE, counter.consumePending())
    }

    @Test
    fun userPlaylistDownloadFilterOnlyKeepsUndownloadedRemoteSongs() {
        val localSong = Song(
            id = 1L,
            title = "本地歌",
            artist = "歌手",
            album = "专辑",
            albumId = 1L,
            durationMs = 180_000L,
            uri = android.net.TestUri("content://media/external/audio/media/1"),
            folderPath = "/music",
            folderName = "music",
            displayName = "local.flac",
            sizeBytes = 1000L,
            dateAddedSec = 1L,
            track = 1,
            source = Song.LOCAL_SOURCE
        )
        val downloadedRemote = Song(
            id = 2L,
            title = "已下载网歌",
            artist = "歌手",
            album = "专辑",
            albumId = 2L,
            durationMs = 180_000L,
            uri = android.net.TestUri("sonance://online/netease/1001"),
            folderPath = "",
            folderName = "",
            displayName = "1001.mp3",
            sizeBytes = 0L,
            dateAddedSec = 2L,
            track = 2,
            source = "netease",
            remoteId = "1001"
        )
        val pendingRemote = downloadedRemote.copy(
            id = 3L,
            title = "待下载网歌",
            remoteId = "1002"
        )
        val dlnaSong = downloadedRemote.copy(
            id = 4L,
            title = "DLNA",
            source = Song.DLNA_SOURCE,
            remoteId = "dlna-1"
        )

        val downloadedKey = OnlineDownloader.downloadKeyOf("netease", "1001")!!
        val result = filterDownloadableUserPlaylistSongs(
            songs = listOf(localSong, downloadedRemote, pendingRemote, dlnaSong),
            downloadedKeys = setOf(downloadedKey)
        )

        assertEquals(listOf(pendingRemote), result)
    }
}
