package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 冷启动恢复播放现场的纯逻辑。
 *
 * 重点覆盖「重新扫描之后歌曲 id 变了」这条真实路径 ——
 * 存档里的 id 可能整条失效、也可能只剩一半，这两种情况下恢复出来的队列
 * 和「播到第几首」都必须是对的，否则要么播放器停在不存在的歌上，要么直接空队列。
 */
class PlaybackRestoreTest {

    // ===== parseIdList =====

    @Test
    fun `解析空串得到空列表`() {
        assertEquals(emptyList<Long>(), PlaybackRestore.parseIdList(""))
        assertEquals(emptyList<Long>(), PlaybackRestore.parseIdList("   "))
    }

    @Test
    fun `解析正常列表`() {
        assertEquals(listOf(3L, 1L, 2L), PlaybackRestore.parseIdList("3,1,2"))
    }

    @Test
    fun `脏数据被跳过而不是抛异常`() {
        // 前后空格、空项、非数字 —— 都该安静地跳过
        assertEquals(listOf(5L, 7L), PlaybackRestore.parseIdList(" 5 , ,abc,7 ,"))
    }

    @Test
    fun `末尾多余的逗号不影响结果`() {
        assertEquals(listOf(1L, 2L), PlaybackRestore.parseIdList("1,2,"))
    }

    // ===== resolveIds =====

    @Test
    fun `id 全都在曲库里时原样还原`() {
        val (queue, index) = PlaybackRestore.resolveIds(listOf(30L, 10L, 20L), 1, setOf(10L, 20L, 30L))!!
        assertEquals(listOf(30L, 10L, 20L), queue)
        assertEquals(1, index)
    }

    @Test
    fun `部分 id 失效时只保留还在的`() {
        // 存档是 [30, 20, 10]，其中 20 已经不在曲库里
        val (queue, _) = PlaybackRestore.resolveIds(listOf(30L, 20L, 10L), 0, setOf(10L, 30L))!!
        assertEquals(listOf(30L, 10L), queue)
    }

    @Test
    fun `丢歌之后下标会重新夹紧`() {
        // 存档下标指向第 3 首（id=10），但中间那首没了，现在只剩 2 首
        val (queue, index) = PlaybackRestore.resolveIds(listOf(30L, 20L, 10L), 2, setOf(10L, 30L))!!
        assertEquals(listOf(30L, 10L), queue)
        assertEquals(1, index)   // 夹到 lastIndex，不能越界
    }

    @Test
    fun `下标为负也夹回 0`() {
        val (_, index) = PlaybackRestore.resolveIds(listOf(10L), -5, setOf(10L))!!
        assertEquals(0, index)
    }

    @Test
    fun `一首都不在曲库里时返回 null`() {
        // 典型场景：重新扫描之后 id 全变了
        assertNull(PlaybackRestore.resolveIds(listOf(1L, 2L, 3L), 1, setOf(9L, 8L, 7L)))
    }

    @Test
    fun `空存档返回 null`() {
        assertNull(PlaybackRestore.resolveIds(emptyList(), 0, setOf(1L, 2L)))
    }

    @Test
    fun `空曲库返回 null`() {
        assertNull(PlaybackRestore.resolveIds(listOf(1L), 0, emptySet()))
    }

    @Test
    fun `队列顺序按存档而不是按曲库`() {
        // 曲库里是 1,2,3，存档是倒过来的 —— 恢复出来必须也是倒过来的，
        // 不然用户上次排的播放顺序就白存了
        val (queue, _) = PlaybackRestore.resolveIds(listOf(3L, 2L, 1L), 0, setOf(1L, 2L, 3L))!!
        assertEquals(listOf(3L, 2L, 1L), queue)
    }

    @Test
    fun `同一首在队列里出现两次时两处都保留`() {
        // playNext 不查重，队列里真的可能有重复（对已在队列里的歌点"下一首播放"）
        val (queue, _) = PlaybackRestore.resolveIds(listOf(7L, 7L, 8L), 0, setOf(7L, 8L))!!
        assertEquals(listOf(7L, 7L, 8L), queue)
    }
}
