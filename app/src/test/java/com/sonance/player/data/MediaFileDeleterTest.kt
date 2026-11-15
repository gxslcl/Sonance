package com.sonance.player.data

import com.sonance.player.data.MediaFileDeleter.DeleteUriKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFileDeleterTest {

    @Test
    fun `classifyUri distinguishes MediaStore audio, MediaStore files, file, and SAF URIs`() {
        assertEquals(
            DeleteUriKind.MEDIA_STORE_AUDIO,
            MediaFileDeleter.classifyUri("content", "media", "/external/audio/media/42")
        )
        assertEquals(
            DeleteUriKind.MEDIA_STORE_FILES,
            MediaFileDeleter.classifyUri("content", "media", "/external/file/88")
        )
        assertEquals(
            DeleteUriKind.FILE,
            MediaFileDeleter.classifyUri("file", "", "/storage/emulated/0/Moon/song.flac")
        )
        assertEquals(
            DeleteUriKind.DOCUMENT,
            MediaFileDeleter.classifyUri("content", "com.android.externalstorage.documents", "/tree/primary%3AMoon/document/12")
        )
    }

    @Test
    fun `extractMediaVolume extracts external volume name cleanly`() {
        assertEquals("external", MediaFileDeleter.extractMediaVolume(listOf("external", "file", "88")))
        assertEquals("external_primary", MediaFileDeleter.extractMediaVolume(listOf("external_primary", "file", "99")))
        assertNull(MediaFileDeleter.extractMediaVolume(listOf("file", "88")))
        assertNull(MediaFileDeleter.extractMediaVolume(emptyList()))
    }

    @Test
    fun `localFileForSong builds path from file URI or filesystem folder and filename`() {
        val fromUri = MediaFileDeleter.localFileForSong(
            uriScheme = "file",
            uriPath = "/storage/emulated/0/Music/track.mp3",
            folderPath = "",
            displayName = ""
        )
        assertNotNull(fromUri)
        assertEquals("/storage/emulated/0/Music/track.mp3".replace('/', java.io.File.separatorChar), fromUri!!.path)

        val fromFolder = MediaFileDeleter.localFileForSong(
            uriScheme = "content",
            uriPath = "/external/file/12",
            folderPath = "/storage/emulated/0/Moon",
            displayName = "moon.flac"
        )
        assertNotNull(fromFolder)
        assertEquals("moon.flac", fromFolder!!.name)

        assertNull(
            MediaFileDeleter.localFileForSong(
                uriScheme = "content",
                uriPath = "/external/audio/media/12",
                folderPath = "saf://primary",
                displayName = "moon.flac"
            )
        )
    }
}
