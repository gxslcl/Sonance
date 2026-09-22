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
    fun `丢歌之后下标会跟着前移`() {
        // 存档下标指向第 3 首（id=10），但中间那首没了
        val (queue, index) = PlaybackRestore.resolveIds(listOf(30L, 20L, 10L), 2, setOf(10L, 30L))!!
        assertEquals(listOf(30L, 10L), queue)
        // ★ 必须是**对齐后**的位置，而不是把旧下标夹到 lastIndex。
        // id=10 现在排第 2 位，夹紧的写法恰好也是 1，两者在这个例子里撞上了 ——
        // 靠下面"当前曲不在中间"的那条才区分得开。
        assertEquals(1, index)
    }

    @Test
    fun `前面丢三首后面留一首时下标仍然指向同一首歌`() {
        // 存档 [10,11,12,13,14]，播到 12（下标 2）；10、11 没了但 13、14 还在。
        // ★ 这条是这套规则存在的理由：
        //   - 旧实现（把旧下标夹紧）= min(2, 2) = 2 → 恢复出来是 **14**，
        //     也就是"重启之后换了一首歌"（用户报过同类现象）；
        //   - 对齐后 = 0 → 仍然是 12。
        val (queue, index) = PlaybackRestore.resolveIds(
            listOf(10L, 11L, 12L, 13L, 14L), 2, setOf(12L, 13L, 14L)
        )!!
        assertEquals(listOf(12L, 13L, 14L), queue)
        assertEquals(0, index)
    }

    @Test
    fun `当前曲自己也失效时退回夹紧的旧行为`() {
        val (queue, index) = PlaybackRestore.resolveIds(listOf(1L, 2L, 3L), 1, setOf(1L, 3L))!!
        assertEquals(listOf(1L, 3L), queue)
        assertEquals(1, index)   // 1 在范围内，不乱跳
    }

    // ===== alignIndices =====

    @Test
    fun `alignIndices 返回保留项的原始下标`() {
        val (kept, mapped) = PlaybackRestore.alignIndices(listOf(10L, 11L, 12L, 13L), 2) { it % 2 == 0L }
        assertEquals(listOf(0, 2), kept)     // 只有 10 和 12
        assertEquals(1, mapped)              // 12 落在保留后的第 2 位
    }

    @Test
    fun `alignIndices 全部保留时下标不变`() {
        val (kept, mapped) = PlaybackRestore.alignIndices(listOf(5L, 6L, 7L), 1) { true }
        assertEquals(listOf(0, 1, 2), kept)
        assertEquals(1, mapped)
    }

    @Test
    fun `alignIndices 关心那项被过滤掉时返回 -1`() {
        val (kept, mapped) = PlaybackRestore.alignIndices(listOf(10L, 11L), 1) { it == 10L }
        assertEquals(listOf(0), kept)
        assertEquals(-1, mapped)
    }

    @Test
    fun `alignIndices 对空输入与越界下标都不炸`() {
        assertEquals(emptyList<Int>(), PlaybackRestore.alignIndices(emptyList(), 0) { true }.first)
        val (kept, mapped) = PlaybackRestore.alignIndices(listOf(1L), 99) { true }
        assertEquals(listOf(0), kept)
        assertEquals(-1, mapped)
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
