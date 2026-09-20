package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为类排序字段（播放次数 / 最近播放）的排序键。
 *
 * 播放次数此前是「只写不读」的死数据，这里把它接进排序后必须钉住两个约定，
 * 否则很容易出现"最常听的排到了最后"这种反直觉结果。
 */
class SongStatsSortTest {

    private val stats = SongStats(
        playCounts = mapOf(100L to 42, 200L to 7),
        recentOrder = mapOf(100L to 0, 300L to 1)
    )

    @Test
    fun `播放次数取实际记录`() {
        assertEquals(42L, MediaRepository.statsSortKey(100L, SortField.PLAY_COUNT, stats))
        assertEquals(7L, MediaRepository.statsSortKey(200L, SortField.PLAY_COUNT, stats))
    }

    @Test
    fun `没有播放记录的算 0 次`() {
        assertEquals(0L, MediaRepository.statsSortKey(999L, SortField.PLAY_COUNT, stats))
    }

    @Test
    fun `降序时最常听的排最前`() {
        val a = MediaRepository.statsSortKey(100L, SortField.PLAY_COUNT, stats) // 42
        val b = MediaRepository.statsSortKey(200L, SortField.PLAY_COUNT, stats) // 7
        val c = MediaRepository.statsSortKey(999L, SortField.PLAY_COUNT, stats) // 0
        assertTrue("次数多的键应更大，降序才能排前面", a > b && b > c)
    }

    @Test
    fun `最近播放序号越小越近`() {
        assertEquals(0L, MediaRepository.statsSortKey(100L, SortField.RECENT_PLAYED, stats))
        assertEquals(1L, MediaRepository.statsSortKey(300L, SortField.RECENT_PLAYED, stats))
    }

    @Test
    fun `没播过的最近播放排最后`() {
        val never = MediaRepository.statsSortKey(999L, SortField.RECENT_PLAYED, stats)
        val recent = MediaRepository.statsSortKey(100L, SortField.RECENT_PLAYED, stats)
        assertEquals(Int.MAX_VALUE.toLong(), never)
        assertTrue("没播过的键必须是最大值，降序时才落到末尾", never > recent)
    }

    @Test
    fun `空统计不会崩且键都取默认值`() {
        val empty = SongStats()
        assertEquals(0L, MediaRepository.statsSortKey(1L, SortField.PLAY_COUNT, empty))
        assertEquals(
            Int.MAX_VALUE.toLong(),
            MediaRepository.statsSortKey(1L, SortField.RECENT_PLAYED, empty)
        )
    }

    @Test
    fun `非行为类字段返回中性值 0`() {
        for (f in listOf(SortField.TITLE, SortField.ARTIST, SortField.ALBUM, SortField.DURATION)) {
            assertEquals("$f 不该用行为数据", 0L, MediaRepository.statsSortKey(100L, f, stats))
        }
    }
}
