package com.sonance.player.data

import com.sonance.player.data.MediaRepository.Companion.SongSortDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：覆盖曾导致闪退的 `TimSort: Comparison method violates its general contract!` 问题。
 */
class SongSortContractTest {

    @Test
    fun `partial index keys across more than 32 items never violate TimSort contract`() {
        val items = ArrayList<SongSortDescriptor>(120)
        val partialKeys = HashMap<Long, IndexKeys>()

        // 模拟真实的混合数据：旧扫描曲目已有拼音 IndexKeys（中文"阿"->"a..."、"周"->"z..."），
        // 新扫描到的曲目尚未进入 sortKeys，且包含 ASCII / 中文 / 大小写 / 特殊 Unicode 字符。
        val rawTitles = listOf(
            "阿刁", "爱的可能", "爱如潮水", "安河桥",
            "Bravo", "blue", "City Of Stars", "Dream",
            "周杰伦-晴天", "张学友-听海", "王菲-暗涌", "陈绮贞-鱼",
            "Zeta", "zebra", "1973", "#Special",
            "İstanbul", "i", "I", "ſmall", "microµ"
        )
        val mappedPinyin = mapOf(
            "阿刁" to "adiao",
            "爱的可能" to "aidekeneng",
            "爱如潮水" to "airuchaoshui",
            "安河桥" to "anheqiao",
            "周杰伦-晴天" to "zhoujielunqingtian",
            "张学友-听海" to "zhangxueyoutinghai",
            "王菲-暗涌" to "wangfeianyong",
            "陈绮贞-鱼" to "chenqizhenyu"
        )

        for (i in 0 until 120) {
            val id = (i + 1).toLong()
            val title = "${rawTitles[i % rawTitles.size]} #$i"
            val artist = rawTitles[(i * 3) % rawTitles.size]
            val album = rawTitles[(i * 5) % rawTitles.size]
            items += SongSortDescriptor(
                id = id,
                title = title,
                artist = artist,
                album = album,
                displayName = "$title.flac",
                track = i % 12,
                durationMs = (180_000L + (i % 17) * 5_000L),
                dateAddedSec = 1_700_000_000L + (i % 23) * 100L
            )
            // 只为其中约 2/3 条目填充 IndexKeys，刻意留出未建索引的新歌
            if (i % 3 != 0) {
                val baseTitle = mappedPinyin[rawTitles[i % rawTitles.size]] ?: rawTitles[i % rawTitles.size].lowercase()
                partialKeys[id] = IndexKeys(
                    title = "$baseTitle$i",
                    artist = mappedPinyin[artist] ?: artist.lowercase(),
                    album = mappedPinyin[album] ?: album.lowercase(),
                    fileName = "${baseTitle}${i}flac"
                )
            }
        }

        val stats = SongStats(
            playCounts = mapOf(1L to 12, 2L to 12, 5L to 3),
            recentOrder = mapOf(2L to 0, 1L to 1, 7L to 2)
        )

        for (field in SortField.entries) {
            for (asc in listOf(true, false)) {
                val sorted = MediaRepository.sortDescriptors(
                    items = items,
                    field = field,
                    ascending = asc,
                    stats = stats,
                    keys = partialKeys
                )
                assertEquals("排序不应增删元素 ($field, asc=$asc)", items.size, sorted.size)

                // 空索引 + 含特殊 Unicode 字符同样不应违反 TimSort 契约
                val fallbackSorted = MediaRepository.sortDescriptors(
                    items = items,
                    field = field,
                    ascending = asc,
                    stats = stats,
                    keys = emptyMap()
                )
                assertEquals("空索引排序不应增删元素 ($field, asc=$asc)", items.size, fallbackSorted.size)
            }
        }
    }

    @Test
    fun `play count ties fall back deterministically to lowercase title`() {
        val a = SongSortDescriptor(1L, "Beta", "A", "Alb", "b.mp3", 1, 1000L, 10L)
        val b = SongSortDescriptor(2L, "alpha", "A", "Alb", "a.mp3", 2, 1000L, 20L)
        val c = SongSortDescriptor(3L, "Top", "A", "Alb", "t.mp3", 3, 1000L, 30L)
        val stats = SongStats(playCounts = mapOf(1L to 5, 2L to 5, 3L to 20))

        val desc = MediaRepository.sortDescriptors(
            items = listOf(a, b, c),
            field = SortField.PLAY_COUNT,
            ascending = false,
            stats = stats
        )
        assertEquals(listOf(3L, 1L, 2L), desc.map { it.id })
    }
}
