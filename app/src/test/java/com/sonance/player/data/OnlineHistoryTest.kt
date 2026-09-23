package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线搜索历史的存取规则。
 *
 * 这块错了不会报错，只会让历史**越用越没用**：
 * 顺序不动 → 用户每次都找不到刚搜的那个词；
 * 不去重 → 同一个词重复好几个；
 * 记了搜不到的词 → 点一下什么都没有。
 * 抽成纯函数就能把这些都钉住。
 */
class OnlineHistoryTest {

    private val max = Prefs.MAX_ONLINE_HISTORY

    @Test
    fun `新词插到最前面`() {
        assertEquals(
            listOf("周杰伦", "张学友", "陈奕迅"),
            nextOnlineHistory(listOf("张学友", "陈奕迅"), "周杰伦", max)
        )
    }

    @Test
    fun `重复的词提到最前而不是原地不动`() {
        // 用户第二次搜它，说明现在关心的就是它 —— 它就该在最前面
        assertEquals(
            listOf("张学友", "周杰伦", "陈奕迅"),
            nextOnlineHistory(listOf("周杰伦", "陈奕迅"), "张学友", max)
        )
    }

    @Test
    fun `只出现一次不重复`() {
        val out = nextOnlineHistory(listOf("周杰伦", "张学友"), "周杰伦", max)
        assertEquals(listOf("周杰伦", "张学友"), out)
        assertEquals(2, out.size)
    }

    @Test
    fun `首尾空白剥掉`() {
        assertEquals(
            listOf("周杰伦"),
            nextOnlineHistory(emptyList(), "  周杰伦  ", max)
        )
    }

    @Test
    fun `换行替换成空格而不是截断`() {
        // 从剪贴板粘贴进来的词可能带换行。剥成空格比截断安全 ——
        // 词还是完整的（输入框是单行的，正常不会有换行）
        assertEquals(
            listOf("周杰伦 稻香"),
            nextOnlineHistory(emptyList(), "周杰伦\n稻香", max)
        )
    }

    @Test
    fun `剥完空白之后和已有的重复也要去重`() {
        // "周杰伦" 和 " 周杰伦 " 是同一个词，不该出现两条
        assertEquals(
            listOf("周杰伦", "张学友"),
            nextOnlineHistory(listOf("张学友", "周杰伦"), "  周杰伦 ", max)
        )
    }

    @Test
    fun `空词和纯空白不动历史`() {
        // 空着输入框触发的搜索（清空那次）不该往历史里塞一个空条
        assertEquals(listOf("张学友"), nextOnlineHistory(listOf("张学友"), "", max))
        assertEquals(listOf("张学友"), nextOnlineHistory(listOf("张学友"), "   ", max))
        assertEquals(listOf("张学友"), nextOnlineHistory(listOf("张学友"), "\n", max))
    }

    @Test
    fun `超过上限丢掉最旧的`() {
        val existing = (1..max).map { "词$it" }        // 词1 最新 … 词12 最旧
        val out = nextOnlineHistory(existing, "新词", max)
        assertEquals(max, out.size)
        assertEquals("新词", out.first())
        // 最旧的（词12）被挤掉，其余按原顺序跟在后面
        assertEquals(existing.dropLast(1), out.drop(1))
        assertFalse("不该再有最旧的「词$max」", out.contains("词$max"))
    }

    @Test
    fun `max 至少要留一条`() {
        // 防御：万一有人把上限配成 0，take(0) 会把历史清空成永远只有 0 条
        assertEquals(listOf("周杰伦"), nextOnlineHistory(emptyList(), "周杰伦", 0))
        assertEquals(listOf("周杰伦"), nextOnlineHistory(emptyList(), "周杰伦", -5))
    }

    @Test
    fun `空历史不会崩`() {
        assertEquals(listOf("周杰伦"), nextOnlineHistory(emptyList(), "周杰伦", max))
    }
}
