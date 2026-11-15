package com.sonance.player.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCacheRulesTest {

    @Test
    fun `excluded paths skip standalone folder and downloader output`() {
        assertTrue(LocalCacheRules.isExcludedPath("/storage/emulated/0/Music/本机缓存转换/song.flac"))
        assertTrue(LocalCacheRules.isExcludedPath("/storage/emulated/0/Music/山水有清音下载/song.mp3"))
        assertTrue(LocalCacheRules.isExcludedPath("/data/user/0/com.sonance.player/cache/local_cache_work/a.bin"))
        assertFalse(LocalCacheRules.isExcludedPath("/storage/emulated/0/Download/netease/cloudmusic/Music/a.ncm"))
    }

    @Test
    fun `shouldIncludeScannedFile accepts encrypted containers and cache plain files but skips normal downloads`() {
        assertTrue(
            LocalCacheRules.shouldIncludeScannedFile(
                kind = NcKind.NCM,
                fileName = "song.ncm",
                folderPathHint = "Download/netease/cloudmusic/Music",
                sizeBytes = 3_200_000L
            )
        )
        assertTrue(
            LocalCacheRules.shouldIncludeScannedFile(
                kind = NcKind.NMS,
                fileName = "1901371647_320_abc.nmsf",
                folderPathHint = "netease/cloudmusic/Cache/Music3",
                sizeBytes = 4_100_000L
            )
        )
        assertTrue(
            LocalCacheRules.shouldIncludeScannedFile(
                kind = NcKind.PLAIN,
                fileName = "1901371647_320_hash.mp3",
                folderPathHint = "Download/netease",
                sizeBytes = 4_500_000L
            )
        )
        // 下载目录里普通已转换好的歌曲不该被重复扫进本机缓存待转换
        assertFalse(
            LocalCacheRules.shouldIncludeScannedFile(
                kind = NcKind.PLAIN,
                fileName = "稻香 - 周杰伦.mp3",
                folderPathHint = "Download/netease/cloudmusic/Music",
                sizeBytes = 4_500_000L
            )
        )
    }

    @Test
    fun `extractNeteaseSongId parses nms and numeric plain caches safely`() {
        assertEquals("1901371647", LocalCacheRules.extractNeteaseSongId("1901371647_320_abc", NcKind.NMS))
        assertEquals("186016", LocalCacheRules.extractNeteaseSongId("186016-128-xyz", NcKind.PLAIN))
        assertNull(LocalCacheRules.extractNeteaseSongId("稻香 - 周杰伦", NcKind.NCM))
        assertNull(LocalCacheRules.extractNeteaseSongId("2025Live", NcKind.PLAIN))
    }

    @Test
    fun `uniqueFileName avoids overwriting colliding names in standalone folder`() {
        val existing = setOf("晴天 - 周杰伦.flac", "晴天 - 周杰伦 (1).flac")
        assertEquals("晴天 - 周杰伦 (2).flac", LocalCacheRules.uniqueFileName("晴天 - 周杰伦.flac", existing))
        assertEquals("七里香 - 周杰伦.mp3", LocalCacheRules.uniqueFileName("七里香 - 周杰伦.mp3", existing))
    }
}
