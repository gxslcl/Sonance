package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三态播放模式。
 *
 * 这个枚举的全部价值在于**把两个独立开关压成三种用户能理解的状态**，
 * 所以测试重点就是映射和循环两条：
 *
 * - 映射错了 → 按钮显示的图标和播放器实际行为对不上（最糟：显示"单曲循环"
 *   实际在随机放，用户完全无从察觉）
 * - 循环漏了一档 / 多了一档 → 点几下之后卡在某个状态出不来
 */
class PlayModeTest {

    // ===== 从底层两个标志推出模式 =====

    @Test
    fun `关随机加单曲循环是单曲循环`() {
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.of(shuffle = false, repeat = RepeatMode.ONE))
    }

    @Test
    fun `关随机加列表循环是列表循环`() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.of(shuffle = false, repeat = RepeatMode.ALL))
    }

    @Test
    fun `App 初始状态归到顺序播放`() {
        // shuffle=false + repeat=OFF 对应"顺序播放"（按当前列表从头播到尾后停止）。
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.of(shuffle = false, repeat = RepeatMode.OFF))
    }

    @Test
    fun `随机优先于循环`() {
        // 随机开着的时候 repeat 是 ALL 还是 OFF 对用户没区别（队列已经打乱了），
        // 都该显示"随机播放"。漏了这条会出现"随机 + 单曲循环"这种
        // 自相矛盾的状态 —— 队列都乱了，"单曲"到底单曲哪一首？
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(shuffle = true, repeat = RepeatMode.ALL))
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(shuffle = true, repeat = RepeatMode.ONE))
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(shuffle = true, repeat = RepeatMode.OFF))
    }

    // ===== 循环 =====

    @Test
    fun `四态按固定顺序循环`() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.SHUFFLE.next())
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL.next())
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.REPEAT_ONE.next())
        assertEquals(PlayMode.SHUFFLE, PlayMode.SEQUENTIAL.next())
    }

    @Test
    fun `点四下回到原点`() {
        for (start in PlayMode.entries) {
            assertEquals(start, start.next().next().next().next())
        }
    }

    @Test
    fun `循环只覆盖这四个状态`() {
        // 遍历一整圈，必须恰好回到起点且不重复
        val seen = LinkedHashSet<PlayMode>()
        var cur = PlayMode.SHUFFLE
        repeat(PlayMode.entries.size) {
            assertTrue("循环里出现了重复状态: $cur", seen.add(cur))
            cur = cur.next()
        }
        assertEquals("循环一圈应该回到起点", PlayMode.SHUFFLE, cur)
        assertEquals(PlayMode.entries.size, seen.size)
    }

    // ===== 文案 =====

    @Test
    fun `四种模式的文案互不相同且非空`() {
        // 文案是点一下之后弹出的提示语，重复了用户就分不清切到了哪一档
        for (m in PlayMode.entries) {
            assertTrue("${m.name} 的文案为空", m.label.isNotBlank())
        }
        val labels = PlayMode.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `文案就是用户要求的那四种说法`() {
        assertEquals("随机播放", PlayMode.SHUFFLE.label)
        assertEquals("列表循环", PlayMode.REPEAT_ALL.label)
        assertEquals("单曲循环", PlayMode.REPEAT_ONE.label)
        assertEquals("顺序播放", PlayMode.SEQUENTIAL.label)
    }

    @Test
    fun `正好四种模式`() {
        assertEquals(4, PlayMode.entries.size)
        assertNotEquals(PlayMode.SHUFFLE, PlayMode.REPEAT_ALL)
        assertNotEquals(PlayMode.REPEAT_ONE, PlayMode.SEQUENTIAL)
    }
}
