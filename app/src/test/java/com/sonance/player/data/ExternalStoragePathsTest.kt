package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStoragePathsTest {

    @Test
    fun `formatVolumeRelativeFolder keeps internal relative path and prefixes external volumes`() {
        assertEquals(
            "Music/Pop",
            ExternalStoragePaths.formatVolumeRelativeFolder("/Music/Pop/", volumeLabel = "USB0", isExternalVolume = false)
        )
        assertEquals(
            "USB0/Music/Pop",
            ExternalStoragePaths.formatVolumeRelativeFolder("/Music/Pop/", volumeLabel = "USB0", isExternalVolume = true)
        )
        assertEquals(
            "USB0",
            ExternalStoragePaths.formatVolumeRelativeFolder("", volumeLabel = "USB0", isExternalVolume = true)
        )
        assertEquals(
            "外置存储/FLAC",
            ExternalStoragePaths.formatVolumeRelativeFolder("FLAC", volumeLabel = null, isExternalVolume = true)
        )
    }

    @Test
    fun `saf tree serialization round-trips deduplicates and removes cleanly`() {
        val raw1 = ExternalStoragePaths.addSafTree("", "content://com.android.externalstorage.documents/tree/1234-5678%3A", "车载U盘")
        val raw2 = ExternalStoragePaths.addSafTree(raw1, "content://com.android.externalstorage.documents/tree/ABCD-EF01%3ACar", "无损目录\t1")
        // Updating an existing URI replaces label cleanly without duplicating
        val raw3 = ExternalStoragePaths.addSafTree(raw2, "content://com.android.externalstorage.documents/tree/1234-5678%3A", "金士顿U盘")

        val parsed = ExternalStoragePaths.parseSafScanTrees(raw3)
        assertEquals(2, parsed.size)
        assertEquals("无损目录 1", parsed[0].label)
        assertEquals("金士顿U盘", parsed[1].label)

        val afterRemove = ExternalStoragePaths.removeSafTree(raw3, "content://com.android.externalstorage.documents/tree/ABCD-EF01%3ACar")
        val remaining = ExternalStoragePaths.parseSafScanTrees(afterRemove)
        assertEquals(listOf(SafScanTree("content://com.android.externalstorage.documents/tree/1234-5678%3A", "金士顿U盘")), remaining)
    }

    @Test
    fun `safFolderPath and matchesScopedPath match root subfolders and direct folders`() {
        val tree = SafScanTree("content://tree/usb1", "车载U盘")
        val scopeKey = ExternalStoragePaths.safScopePathOf(tree.uriString)
        val rootFolder = ExternalStoragePaths.safFolderPath(tree.label, "")
        val subFolder = ExternalStoragePaths.safFolderPath(tree.label, "/周杰伦/Live/")

        assertEquals("外置 · 车载U盘", rootFolder)
        assertEquals("外置 · 车载U盘/周杰伦/Live", subFolder)

        val selectedSafScope = setOf(scopeKey)
        assertTrue(ExternalStoragePaths.matchesScopedPath(rootFolder, selectedSafScope, listOf(tree)))
        assertTrue(ExternalStoragePaths.matchesScopedPath(subFolder, selectedSafScope, listOf(tree)))
        assertFalse(ExternalStoragePaths.matchesScopedPath("Music/Pop", selectedSafScope, listOf(tree)))
        assertFalse(ExternalStoragePaths.matchesScopedPath("外置 · 车载U盘Pro", selectedSafScope, listOf(tree)))

        assertTrue(ExternalStoragePaths.matchesScopedPath("Music/Pop", setOf("Music/Pop"), listOf(tree)))
    }
}
