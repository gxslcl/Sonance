package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ===== 索引缓存编解码：冷启动首帧即最终排序的关键（见 encodeLibraryIndexJson）=====

    @Test
    fun `index cache round trip keeps both maps`() {
        val pinyin = mapOf(1L to "qinghuaci qinghuaci", 2L to "beatles letitbe", -7L to "zaixian")
        val keys = mapOf(
            1L to IndexKeys("qinghuaci", "zhoujielun", "shiyiyuedexiaobang", "01qinghuaci.mp3"),
            2L to IndexKeys("letitbe", "beatles", "letitbe", "letitbe.flac"),
            -7L to IndexKeys("", "", "", "")
        )
        val decoded = decodeLibraryIndexJson(encodeLibraryIndexJson(pinyin, keys))
        assertEquals(pinyin to keys, decoded)
    }

    @Test
    fun `empty index round trips`() {
        val decoded = decodeLibraryIndexJson(encodeLibraryIndexJson(emptyMap(), emptyMap()))
        assertEquals(emptyMap<Long, String>() to emptyMap<Long, IndexKeys>(), decoded)
    }

    @Test
    fun `corrupt or foreign index cache decodes to null`() {
        assertNull(decodeLibraryIndexJson("not json"))
        assertNull(decodeLibraryIndexJson(""))
        assertNull(decodeLibraryIndexJson("{\"v\":1,\"p\":{}}")) // 缺 k
        assertNull(decodeLibraryIndexJson("{\"v\":999,\"p\":{},\"k\":{}}")) // 版本不符
        assertNull(decodeLibraryIndexJson("{\"v\":1,\"p\":{\"abc\":\"x\"},\"k\":{}}")) // id 不是数字
        assertNull(decodeLibraryIndexJson("{\"v\":1,\"p\":{},\"k\":{\"1\":[\"a\"]}}")) // 键数组缺字段
        // 截断的串（写盘被打断）也不能抛
        val full = encodeLibraryIndexJson(mapOf(1L to "x"), mapOf(1L to IndexKeys("a", "b", "c", "d")))
        assertNull(decodeLibraryIndexJson(full.substring(0, full.length / 2)))
    }

    @Test
    fun `coverage requires every id in both maps`() {
        val p = mapOf(1L to "a", 2L to "b")
        val k = mapOf(1L to IndexKeys("a", "", "", ""), 2L to IndexKeys("b", "", "", ""))
        assertTrue(libraryIndexCovers(listOf(1L, 2L), p, k))
        assertTrue(libraryIndexCovers(emptyList(), p, k))
        // 超集（索引里还留着已隐藏的歌）也算覆盖
        assertTrue(libraryIndexCovers(listOf(1L), p, k))
        // 新增歌缺键 → 不覆盖，触发重建
        assertEquals(false, libraryIndexCovers(listOf(1L, 3L), p, k))
        // 只缺排序键 / 只缺拼音索引都不行
        assertEquals(false, libraryIndexCovers(listOf(1L, 2L), p, mapOf(1L to IndexKeys("a", "", "", ""))))
        assertEquals(false, libraryIndexCovers(listOf(1L, 2L), mapOf(1L to "a"), k))
    }
}
