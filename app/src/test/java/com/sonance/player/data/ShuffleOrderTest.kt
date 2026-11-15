package com.sonance.player.data

import com.sonance.player.data.ShuffleOrder.ShuffleMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleOrderTest {

    @Test
    fun `circular fill places fresh tracks immediately after keepIndex even near queue end`() {
        val n = 40
        val items = (0 until n).map { i ->
            ShuffleMeta(id = (i + 1).toLong(), artist = "Artist$i", album = "Album$i", albumId = i.toLong())
        }
        // 最近听过前 15 首（但 keep 在末尾 35），旧实现会在 36..39 先把 heard 放出来
        val recent = (1L..15L).toSet()
        val keep = 35
        val plan = ShuffleOrder.planIndices(
            items = items,
            keepIndex = keep,
            recent = recent,
            recentOrder = (15L downTo 1L).toList(),
            rng = Random(42)
        )

        assertEquals(n, plan.size)
        assertEquals(n, plan.toSet().size)
        assertEquals(keep, plan[keep])

        val afterKeep = (1 until n).map { offset -> items[plan[(keep + offset) % n]].id }
        val freshCount = afterKeep.count { it !in recent }
        // 所有未听/不重复的歌必须严格排在所有最近听过的歌前面
        assertTrue(afterKeep.take(freshCount).all { it !in recent })
        assertTrue(afterKeep.drop(freshCount).all { it in recent })
    }

    @Test
    fun `recent heard tracks wrap with oldest first and newest last`() {
        val items = (1..20).map { i ->
            ShuffleMeta(id = i.toLong(), artist = "Artist$i", album = "Album$i")
        }
        // id 2 刚刚听过（rank 0），id 19 很久前听过（rank 17）
        val recentOrder = (2L..19L).toList()
        val plan = ShuffleOrder.planIndices(
            items = items,
            keepIndex = 0,
            recent = recentOrder.toSet(),
            recentOrder = recentOrder,
            rng = Random(7)
        )
        assertEquals(0, plan[0])
        val heardPositions = (1 until items.size).map { items[plan[it]].id }
        // 第一个 heard 段应偏早出现高 rank（id 15..19），最末尾应出现刚听过的 low rank（id 2..5）
        val firstQuarterAvg = heardPositions.take(4).average()
        val lastQuarterAvg = heardPositions.takeLast(4).average()
        assertTrue("Expected oldest-heard before newest-heard, got $heardPositions", firstQuarterAvg > lastQuarterAvg)
    }

    @Test
    fun `artist and album spreading works when albumId is negative`() {
        // 30 首歌，分属 3 位歌手，albumId 全部为 -1（在线曲目/未知专辑常见情况）
        val items = (0 until 30).map { i ->
            val group = i / 10
            ShuffleMeta(
                id = (i + 1).toLong(),
                artist = "歌手$group",
                album = "专辑$group",
                albumId = -1L
            )
        }
        val plan = ShuffleOrder.planIndices(
            items = items,
            keepIndex = 5,
            recent = emptySet(),
            rng = Random(123)
        )
        var consecutiveSameArtist = 0
        for (i in 1 until plan.size) {
            if (items[plan[i]].artist == items[plan[i - 1]].artist) {
                consecutiveSameArtist++
            }
        }
        assertTrue("Expected low adjacent same-artist runs, got $consecutiveSameArtist", consecutiveSameArtist <= 2)
    }

    @Test
    fun `inverse play count weighting favors unplayed songs early in queue`() {
        val items = (1..40).map { i ->
            ShuffleMeta(id = i.toLong(), artist = "A$i", album = "B$i")
        }
        val playCounts = (1L..20L).associateWith { 15 } + (21L..40L).associateWith { 0 }
        var unplayedInFirstHalf = 0
        repeat(20) { seed ->
            val plan = ShuffleOrder.planIndices(
                items = items,
                keepIndex = 0,
                recent = emptySet(),
                playCounts = playCounts,
                rng = Random(seed)
            )
            unplayedInFirstHalf += (1..20).count { items[plan[it]].id in 21L..40L }
        }
        // 20 轮 × 前 20 个槽位 = 400 次，若均等应为 ~200；加权应明显 > 250
        assertTrue("Expected > 250 unplayed slots in first half across 20 runs, got $unplayedInFirstHalf", unplayedInFirstHalf > 250)
    }
}
