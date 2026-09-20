package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应体 → 歌词文本 的提取逻辑。
 *
 * 这里钉死一个**真实踩过的坑**：LRC 每行都以 `[mm:ss]` 开头，
 * 如果用"首字符是不是 `[`"来判断 JSON，所有正常歌词都会被误判成 JSON、
 * 解析失败后被丢掉 —— 现象就是"一首歌词都拿不到"。
 */
class OnlineLyricsBodyTest {

    private val lrc = """
        [00:10.274]狂风吹过城市吹乱我心情
        [00:18.041]天际闪耀星光彷佛在指引
        [00:25.808]梦想未曾停息只在路途行
    """.trimIndent()

    @Test
    fun `以方括号开头的 LRC 不会被误判成 JSON`() {
        val out = OnlineLyrics.extractText(lrc)
        assertEquals(lrc, out)
    }

    @Test
    fun `带前导空白的 LRC 也能原样返回`() {
        val padded = "\n\n  $lrc"
        assertEquals(padded, OnlineLyrics.extractText(padded))
    }

    @Test
    fun `带 Verse 标记的 LRC 原样返回`() {
        val withMarker = "[Verse]\n[00:07.167]When I wake up\n[00:08.993]Well, I know"
        assertEquals(withMarker, OnlineLyrics.extractText(withMarker))
    }

    @Test
    fun `带 !text 标记的纯文本歌词原样返回`() {
        val plain = "[!text]第一次牵你的手\n夜裏大步走\n不知你的心"
        assertEquals(plain, OnlineLyrics.extractText(plain))
    }

    @Test
    fun `JSON 数组形态仍然能取出 lyrics 字段`() {
        val json = """[{"id":"1","title":"晴天","artist":"周杰伦","lyrics":"[00:01.00]故事的小黄花"}"""
            .trim() + "}]"
        val out = OnlineLyrics.extractText(json)
        assertTrue("应从 JSON 里取出歌词，实际: $out", out.contains("故事的小黄花"))
    }

    @Test
    fun `JSON 对象形态能取出 lyrics 字段`() {
        val json = """{"title":"晴天","lyrics":"[00:01.00]故事的小黄花"}"""
        val out = OnlineLyrics.extractText(json)
        assertTrue(out.contains("故事的小黄花"))
    }

    @Test
    fun `看着像 JSON 但解析失败时回退成原文`() {
        // 畸形 JSON：不能因为解析失败就把整段丢掉
        val broken = "[{not valid json at all"
        assertEquals(broken, OnlineLyrics.extractText(broken))
    }

    @Test
    fun `普通文本原样返回`() {
        val plain = "这是一段没有任何标记的歌词\n第二行"
        assertEquals(plain, OnlineLyrics.extractText(plain))
    }
}
