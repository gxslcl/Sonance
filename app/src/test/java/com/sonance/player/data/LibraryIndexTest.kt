package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测纯 Kotlin 部分：ICU 转写要跑在 Android 上（android.icu），
 * JVM 单测里拿不到，那部分由真机验证。
 */
class LibraryIndexTest {

    @Test
    fun `letter of normalized key`() {
        assertEquals('Z', IndexKey.letterOf("zhoujielun"))
        assertEquals('B', IndexKey.letterOf("beatles"))
        // 不做 "The " 这类前导冠词剥离：排序键与分组键都取字面首字母，规则简单可预期
        assertEquals('T', IndexKey.letterOf("thebeatles"))
        assertEquals('#', IndexKey.letterOf("1st"))
        assertEquals('#', IndexKey.letterOf(""))
        assertEquals('#', IndexKey.letterOf("-live"))
    }

    @Test
    fun `sections keep list order and count each letter`() {
        val s = LetterSections.build(listOf("an", "apple", "beatles", "1st", "zhou", "zhu"))
        assertEquals(0, s.firstIndex['A'] ?: -1)
        assertEquals(2, s.firstIndex['B'] ?: -1)
        assertEquals(3, s.firstIndex['#'] ?: -1)
        assertEquals(4, s.firstIndex['Z'] ?: -1)
        assertEquals(2, s.counts['A'] ?: -1)
        assertEquals(2, s.counts['Z'] ?: -1)
        assertEquals(listOf('A', 'A', 'B', '#', 'Z', 'Z'), s.letterOfItem)
    }

    @Test
    fun `empty letter snaps to nearest letter that has songs`() {
        val s = LetterSections.build(listOf("an", "zhou", "zoo"))
        // C 离 A 更近，M 离 A / Z 距离相等时优先前面的 A
        assertEquals(0, s.nearest('C') ?: -1)
        assertEquals(0, s.nearest('M') ?: -1)
        assertEquals(1, s.nearest('Y') ?: -1)
        assertEquals(1, s.nearest('Z') ?: -1)
    }

    @Test
    fun `empty library has nothing to seek`() {
        assertEquals(LetterSections.EMPTY, LetterSections.build(emptyList()))
        assertNull(LetterSections.EMPTY.nearest('A'))
    }

    @Test
    fun `index keys follow the sort field`() {
        val k = IndexKeys("title", "artist", "album", "file")
        assertEquals("title", k.forField(SortField.TITLE))
        assertEquals("artist", k.forField(SortField.ARTIST))
        assertEquals("album", k.forField(SortField.ALBUM))
        assertEquals("file", k.forField(SortField.FILE_NAME))
        assertEquals("", k.forField(SortField.DURATION))
        assertEquals(
            setOf(SortField.TITLE, SortField.ARTIST, SortField.ALBUM, SortField.FILE_NAME),
            INDEXABLE_SORT_FIELDS
        )
    }
}
