package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面缓存的**尺寸档**规则。
 *
 * 两条规则的动机都是"切歌时背景闪一下"：播放页要 1024 档，
 * 而列表早就解过同一张图的 512 档。拿不到准确档就退一步用小图先显示，
 * 就不会先闪一帧占位图。
 */
class ArtworkBucketTest {

    // ===== 尺寸归一 =====

    @Test
    fun `请求尺寸落到不小于它的那一档`() {
        assertEquals(128, artworkBucketOf(64))
        assertEquals(128, artworkBucketOf(128))
        assertEquals(256, artworkBucketOf(129))
        assertEquals(256, artworkBucketOf(200))
        assertEquals(512, artworkBucketOf(400))
        // 播放页封面 900 → 1024（这就是"列表的 400 和播放页的 900 是两档"的由来）
        assertEquals(1024, artworkBucketOf(900))
        assertEquals(1024, artworkBucketOf(1024))
    }

    @Test
    fun `超过最大档也夹在最大档`() {
        assertEquals(1024, artworkBucketOf(4096))
        assertEquals(1024, artworkBucketOf(Int.MAX_VALUE))
    }

    // ===== 回退顺序 =====

    @Test
    fun `播放页先试 1024 再往下退`() {
        // 播放页要 900（档 1024）。列表留下的 512 必须能被试到 ——
        // 它就是"不闪一帧占位图"的关键
        assertEquals(intArrayOf(1024, 512, 256, 128).toList(), artworkPeekOrder(900).toList())
    }

    @Test
    fun `列表行先试 512`() {
        assertEquals(intArrayOf(512, 256, 128).toList(), artworkPeekOrder(400).toList())
    }

    @Test
    fun `迷你播放器先试 256`() {
        assertEquals(intArrayOf(256, 128).toList(), artworkPeekOrder(200).toList())
    }

    @Test
    fun `最小档没有更小的可退`() {
        assertEquals(intArrayOf(128).toList(), artworkPeekOrder(64).toList())
    }

    @Test
    fun `第一档一定落在请求尺寸的那一档`() {
        for (req in listOf(1, 64, 128, 129, 200, 400, 900, 1024, 4096)) {
            val order = artworkPeekOrder(req)
            assertEquals("请求 $req 的第一档", artworkBucketOf(req), order[0])
        }
        // 请求不超过最大档时，第一档必须"够大"—— 否则显示出来会糊
        for (req in listOf(1, 64, 128, 129, 200, 400, 900, 1024)) {
            assertTrue("请求 $req 的第一档要够大", artworkPeekOrder(req)[0] >= req)
        }
        // ⚠️ 超过最大档（比如 4096）会被**夹到最大档**，这时第一档 < 请求尺寸。
        // 这是刻意的：要大图而缓存里只有 1024 档时，拿它顶上比显示占位图好
        assertEquals(1024, artworkPeekOrder(4096)[0])
    }

    @Test
    fun `只退到更小的档绝不往上取`() {
        // 往上取等于拿大图顶小图，一张 1024 就是 4 MB，白白占内存
        for (req in listOf(64, 128, 200, 400, 900)) {
            val want = artworkBucketOf(req)
            for (b in artworkPeekOrder(req)) {
                assertTrue("请求 $req 时出现了更大的档 $b", b <= want)
            }
        }
    }

    @Test
    fun `顺序是从大到小没有重复`() {
        for (req in listOf(64, 128, 200, 400, 900, 1024)) {
            val order = artworkPeekOrder(req).toList()
            assertEquals("请求 $req 有重复档", order.toSet().size, order.size)
            assertEquals("请求 $req 顺序不是递减", order.sortedDescending(), order)
        }
    }
}
