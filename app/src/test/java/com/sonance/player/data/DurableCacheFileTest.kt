package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DurableCacheFileTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun `empty miss never replaces an existing image`() {
        val file = File(dir.root, "cover.jpg")
        val image = byteArrayOf(1, 2, 3, 4)
        assertTrue(DurableCacheFile.write(file, image))
        assertTrue(DurableCacheFile.write(file, byteArrayOf(), preserveNonEmpty = true))
        assertArrayEquals(image, DurableCacheFile.read(file))
    }

    @Test
    fun `a valid replacement is fully written`() {
        val file = File(dir.root, "replace.jpg")
        assertTrue(DurableCacheFile.write(file, byteArrayOf(1, 2)))
        assertTrue(DurableCacheFile.write(file, byteArrayOf(3, 4, 5)))
        assertArrayEquals(byteArrayOf(3, 4, 5), DurableCacheFile.read(file))
    }

    @Test
    fun `failed replacement leaves existing target intact`() {
        val target = dir.newFolder("existing")
        File(target, "keep.txt").writeText("keep")
        assertFalse(DurableCacheFile.write(target, byteArrayOf(1, 2, 3)))
        assertTrue(File(target, "keep.txt").isFile)
    }

    @Test
    fun `cleanup does not delete a replacement written after a failed read`() {
        val file = File(dir.root, "replaced.jpg")
        val current = byteArrayOf(4, 5, 6)
        assertTrue(DurableCacheFile.write(file, current))
        assertFalse(DurableCacheFile.deleteIfUnchanged(file, byteArrayOf(9)))
        assertArrayEquals(current, DurableCacheFile.read(file))
    }

    @Test
    fun `legacy migration cannot replace a newer selected cover`() {
        val file = File(dir.root, "selected.jpg")
        val selected = byteArrayOf(7, 8, 9)
        assertTrue(DurableCacheFile.write(file, selected))
        assertTrue(DurableCacheFile.writeIfAbsent(file, byteArrayOf(1, 2)))
        assertArrayEquals(selected, DurableCacheFile.read(file))
    }

    @Test
    fun `concurrent writes never produce a partial file`() {
        val file = File(dir.root, "concurrent.jpg")
        val payloads = List(4) { n -> ByteArray(4096) { (n + 1).toByte() } }
        val pool = Executors.newFixedThreadPool(payloads.size)
        val start = CountDownLatch(1)
        val futures = payloads.map { data ->
            pool.submit<Boolean> {
                start.await()
                (0 until 15).all { DurableCacheFile.write(file, data) }
            }
        }
        start.countDown()
        futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
        pool.shutdown()
        val result = DurableCacheFile.read(file)
        assertTrue(payloads.any { it.contentEquals(result) })
        assertTrue(dir.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
