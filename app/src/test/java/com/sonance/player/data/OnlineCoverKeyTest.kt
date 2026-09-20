package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `OnlineCover.keyOf` 的契约。
 *
 * 为什么值得单独钉住：界面（歌曲列表每一行、播放条、全屏播放页、封面选择器）
 * 现在都直接把这个 key 传给 `CoverArt`，`ArtworkLoader` 也拿它当缓存键的一部分。
 * 也就是说它只要出现「同一首歌算出两个值」，就会表现为
 * **封面时有时无**（一个 key 有图、另一个 key 没图）—— 这种 bug 靠点界面很难定位，
 * 所以在单测里把等价性钉死。
 *
 * 另外 `keyOf` 现在带记忆化（LruCache），第 2 条用来确认记忆化不改变结果。
 */
class OnlineCoverKeyTest {

    @Test
    fun `同一首歌大小写与首尾空格不同也得到同一个 key`() {
        val a = OnlineCover.keyOf("Hello", "World")
        assertEquals(a, OnlineCover.keyOf("hello", "world"))
        assertEquals(a, OnlineCover.keyOf("  Hello  ", "World"))
        assertEquals(a, OnlineCover.keyOf("HELLO", " world "))
    }

    @Test
    fun `反复调用结果稳定`() {
        val first = OnlineCover.keyOf("晴天", "周杰伦")
        assertNotNull(first)
        repeat(5) { assertEquals(first, OnlineCover.keyOf("晴天", "周杰伦")) }
    }

    @Test
    fun `标题与歌手都为空返回 null`() {
        assertNull(OnlineCover.keyOf("", ""))
        // 只有空白字符等同于空
        assertNull(OnlineCover.keyOf("   ", "\t"))
    }

    @Test
    fun `只有歌手或只有标题也能算出 key`() {
        assertNotNull(OnlineCover.keyOf("晴天", ""))
        assertNotNull(OnlineCover.keyOf("", "周杰伦"))
    }

    @Test
    fun `不同的歌得到不同的 key`() {
        val a = OnlineCover.keyOf("晴天", "周杰伦")
        val b = OnlineCover.keyOf("晴天", "五月天")
        val c = OnlineCover.keyOf("七里香", "周杰伦")
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `歌手为占位文案时仍然按原文算 key`() {
        // 占位歌手只在**拼查询词**时被过滤，缓存 key 不该跟着变 ——
        // 否则同一首歌在过滤规则调整后又会认不出旧缓存。
        assertNotNull(OnlineCover.keyOf("01 Midnight Horizon", "未知艺术家"))
    }
}
