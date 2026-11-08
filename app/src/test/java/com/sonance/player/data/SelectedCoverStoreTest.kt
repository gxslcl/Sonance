package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SelectedCoverStoreTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun `selected image survives a new store instance and ordinary cache deletion`() {
        val selectedDir = File(dir.root, "selected")
        val normal = dir.newFolder("cache")
        val old = File(normal, "download.jpg").apply { writeBytes(byteArrayOf(9)) }
        val source = "content://media/audio/42"
        val image = byteArrayOf(1, 3, 5, 7)
        assertTrue(SelectedCoverStore(selectedDir).store(42, source, image))
        assertTrue(old.delete())
        assertArrayEquals(image, SelectedCoverStore(selectedDir).lookup(42, source))
    }

    @Test
    fun `different local files do not share hand selected covers`() {
        val store = SelectedCoverStore(File(dir.root, "selected"))
        assertTrue(store.store(1, "content://media/audio/1", byteArrayOf(1)))
        assertTrue(store.store(2, "content://media/audio/2", byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), store.lookup(1, "content://media/audio/1"))
        assertArrayEquals(byteArrayOf(2), store.lookup(2, "content://media/audio/2"))
        store.remove(1, "content://media/audio/1")
        assertNull(store.lookup(1, "content://media/audio/1"))
        assertArrayEquals(byteArrayOf(2), store.lookup(2, "content://media/audio/2"))
    }

    @Test
    fun `legacy migration never replaces an existing selection`() {
        val store = SelectedCoverStore(File(dir.root, "selected"))
        val source = "content://media/audio/7"
        assertTrue(store.store(7, source, byteArrayOf(7)))
        assertTrue(store.migrateIfAbsent(7, source, byteArrayOf(8)))
        assertArrayEquals(byteArrayOf(7), store.lookup(7, source))
        assertFalse(store.store(7, source, byteArrayOf()))
        assertArrayEquals(byteArrayOf(7), store.lookup(7, source))
    }
}
