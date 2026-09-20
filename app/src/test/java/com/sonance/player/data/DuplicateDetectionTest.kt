package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复 / 同名检测的单元测试。
 *
 * 走的是泛型入口 [findDuplicatesIn]，所以不需要构造 [Song]
 * （它带 `android.net.Uri` 字段，纯 JVM 测试里造不出来）。
 */
class DuplicateDetectionTest {

    /** 测试用的最小条目 */
    private data class T(
        val id: Long,
        val title: String,
        val artist: String = "某人",
        val durationMs: Long = 200_000L,
        val sizeBytes: Long = 5_000_000L
    )

    private var nextId = 1L

    private fun t(
        title: String,
        artist: String = "某人",
        durationMs: Long = 200_000L,
        sizeBytes: Long = 5_000_000L
    ) = T(nextId++, title, artist, durationMs, sizeBytes)

    private fun detect(items: List<T>) = findDuplicatesIn(
        items = items,
        id = { it.id },
        title = { it.title },
        artist = { it.artist },
        durationMs = { it.durationMs },
        sizeBytes = { it.sizeBytes }
    )

    @Test
    fun `标题歌手时长都一样判为重复`() {
        val a = t("海阔天空", sizeBytes = 8_000_000L)
        val b = t("海阔天空", sizeBytes = 4_000_000L)
        val report = detect(listOf(a, b))

        assertEquals(1, report.exactCount)
        assertEquals(0, report.sameNameCount)
        // 保留体积更大的那份
        assertEquals(a.id, report.exactGroups[0].keep.id)
        assertEquals(listOf(b.id), report.exactGroups[0].duplicates.map { it.id })
    }

    @Test
    fun `大小写与首尾空格差异不算不同歌`() {
        val a = t("Hello")
        val b = t("  hello  ")
        assertEquals(1, detect(listOf(a, b)).exactCount)
    }

    @Test
    fun `同名但时长不同归到同名分组`() {
        val studio = t("海阔天空", durationMs = 200_000L)
        val live = t("海阔天空", durationMs = 320_000L)
        val report = detect(listOf(studio, live))

        assertEquals(0, report.exactCount)
        assertEquals(1, report.sameNameCount)
        // 保留时长更长的（通常是完整版）
        assertEquals(live.id, report.sameNameGroups[0].keep.id)
        assertEquals(listOf(studio.id), report.sameNameGroups[0].duplicates.map { it.id })
    }

    @Test
    fun `已判为重复的文件不会被同名分组重复计数`() {
        val a = t("海阔天空", durationMs = 200_000L)
        val b = t("海阔天空", durationMs = 200_000L)   // a 的重复
        val live = t("海阔天空", durationMs = 320_000L)
        val report = detect(listOf(a, b, live))

        assertEquals(1, report.exactCount)      // b
        assertEquals(1, report.sameNameCount)   // 留 live，隐藏 a
        val hidden = report.exactGroups.flatMap { it.duplicates }.map { it.id } +
            report.sameNameGroups.flatMap { it.duplicates }.map { it.id }
        assertEquals(hidden.size, hidden.toSet().size)
    }

    @Test
    fun `三份完全相同的文件只保留一份`() {
        val a = t("X", sizeBytes = 9_000_000L)
        val b = t("X", sizeBytes = 3_000_000L)
        val c = t("X", sizeBytes = 6_000_000L)
        val report = detect(listOf(a, b, c))

        assertEquals(2, report.exactCount)
        assertEquals(a.id, report.exactGroups[0].keep.id)
        assertEquals(setOf(b.id, c.id), report.exactGroups[0].duplicates.map { it.id }.toSet())
    }

    @Test
    fun `不同歌手的同名歌不算重复`() {
        val a = t("Hello", artist = "Adele")
        val b = t("Hello", artist = "Lionel Richie")
        assertTrue(detect(listOf(a, b)).isEmpty)
    }

    @Test
    fun `时长只差一秒就算同名而非重复`() {
        val a = t("Y", durationMs = 200_000L)
        val b = t("Y", durationMs = 201_000L)
        val report = detect(listOf(a, b))
        assertEquals(0, report.exactCount)
        assertEquals(1, report.sameNameCount)
    }

    @Test
    fun `标题为空的条目不参与判定`() {
        assertTrue(detect(listOf(t(""), t(""))).isEmpty)
    }

    @Test
    fun `没有重复时报告为空`() {
        assertTrue(detect(listOf(t("A"), t("B"), t("C"))).isEmpty)
    }

    @Test
    fun `少于两首直接返回空`() {
        assertTrue(detect(emptyList()).isEmpty)
        assertTrue(detect(listOf(t("A"))).isEmpty)
    }
}
