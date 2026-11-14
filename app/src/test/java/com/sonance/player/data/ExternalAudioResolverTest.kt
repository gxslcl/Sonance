package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioResolverTest {

    private val sampleLibrary = listOf(
        LibraryMatchEntry(id = 101L, folderPath = "Music/周杰伦", displayName = "晴天.flac", sizeBytes = 28_000_000L),
        LibraryMatchEntry(id = 102L, folderPath = "Download", displayName = "live.mp3", sizeBytes = 4_200_000L),
        LibraryMatchEntry(id = 103L, folderPath = "Music/Live Recordings", displayName = "live.mp3", sizeBytes = 9_500_000L),
        LibraryMatchEntry(id = 104L, folderPath = "Download/Music", displayName = "unique_track.opus", sizeBytes = 3_100_000L)
    )

    @Test
    fun `合成 id 分区与在线负数 id 及哨兵严格隔离`() {
        val unindexedId = unindexedSongId("/storage/emulated/0/Download/test.flac")
        val externalId = externalSongId("content://com.mi.filemanager/external_files/test.flac")

        assertTrue(unindexedId > 0L)
        assertTrue(isUnindexedLocalSongId(unindexedId))
        assertFalse(isExternalSongId(unindexedId))
        assertFalse(isRemoteSongId(unindexedId))

        assertTrue(externalId > 0L)
        assertTrue(isExternalSongId(externalId))
        assertFalse(isUnindexedLocalSongId(externalId))
        assertFalse(isRemoteSongId(externalId))

        // 真实 MediaStore 正数 ID 不落入任何合成区间
        assertFalse(isExternalSongId(101L))
        assertFalse(isUnindexedLocalSongId(101L))
    }

    @Test
    fun `按 MediaStore 文档 id 优先命中曲库条目`() {
        val docId = ExternalAudioResolver.extractDocumentNumericId(
            authority = "com.android.providers.media.documents",
            documentId = "audio:102"
        )
        assertEquals(102L, docId)

        val idx = ExternalAudioResolver.matchLibrarySongIndex(
            entries = sampleLibrary,
            entryOf = { it },
            mediaId = docId,
            pathHint = null,
            displayName = null,
            sizeBytes = -1L
        )
        assertEquals(1, idx)
    }

    @Test
    fun `按 FileProvider 路径前缀剥离并精确匹配同名不同目录的歌曲`() {
        val pair = ExternalAudioResolver.normalizeHintFolderAndName(
            "/external_files/Music/Live Recordings/live.mp3"
        )
        assertEquals("music/live recordings" to "live.mp3", pair)

        // 曲库里有两首 live.mp3，通过目录精准选中下标 2 而不是 Download 里的下标 1
        val idx = ExternalAudioResolver.matchLibrarySongIndex(
            entries = sampleLibrary,
            entryOf = { it },
            mediaId = null,
            pathHint = "/external_files/Music/Live Recordings/live.mp3",
            displayName = "live.mp3",
            sizeBytes = -1L
        )
        assertEquals(2, idx)
    }

    @Test
    fun `文件名同名且无目录线索时靠字节数区分，缺失大小则拒绝猜错`() {
        val bySize = ExternalAudioResolver.matchLibrarySongIndex(
            entries = sampleLibrary,
            entryOf = { it },
            mediaId = null,
            pathHint = null,
            displayName = "live.mp3",
            sizeBytes = 4_200_000L
        )
        assertEquals(1, bySize)

        val ambiguous = ExternalAudioResolver.matchLibrarySongIndex(
            entries = sampleLibrary,
            entryOf = { it },
            mediaId = null,
            pathHint = null,
            displayName = "live.mp3",
            sizeBytes = -1L
        )
        assertEquals(-1, ambiguous)

        val uniqueFallback = ExternalAudioResolver.matchLibrarySongIndex(
            entries = sampleLibrary,
            entryOf = { it },
            mediaId = null,
            pathHint = null,
            displayName = "unique_track.opus",
            sizeBytes = -1L
        )
        assertEquals(3, uniqueFallback)
    }

    @Test
    fun `MediaStore Files 候选过滤只收音频，绝不把 mp4 视频混进曲库`() {
        val audioType = 2 // MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        val noneType = 0
        // 即使被系统归类为 MEDIA_TYPE_NONE，常见纯音频后缀也应收入
        assertTrue(ExternalAudioResolver.isScannableMediaFileCandidate("song.opus", null, noneType, audioType))
        assertTrue(ExternalAudioResolver.isScannableMediaFileCandidate("track.flac", "application/octet-stream", noneType, audioType))
        assertTrue(ExternalAudioResolver.isScannableMediaFileCandidate("podcast.m4b", null, noneType, audioType))
        // 纯视频文件或被系统标为 video/* 的文件必须拒掉
        assertFalse(ExternalAudioResolver.isScannableMediaFileCandidate("VID_2026.mp4", "video/mp4", 3, audioType))
        assertFalse(ExternalAudioResolver.isScannableMediaFileCandidate("clip.mp4", null, noneType, audioType))
        // 明确带 MEDIA_TYPE_AUDIO 时则放行
        assertTrue(ExternalAudioResolver.isScannableMediaFileCandidate("music_only.m4a", "audio/mp4", audioType, audioType))
        // 隐藏 / 临时文件名不扫
        assertFalse(ExternalAudioResolver.isScannableMediaFileCandidate(".pending-123.mp3", "audio/mpeg", audioType, audioType))
    }

    @Test
    fun `externalstorage 文档相对路径解析正常`() {
        assertEquals(
            "Download/Music/song.flac",
            ExternalAudioResolver.extractExternalStorageRelativePath("primary:Download/Music/song.flac")
        )
        assertNull(ExternalAudioResolver.extractExternalStorageRelativePath("invalid"))
    }
}
