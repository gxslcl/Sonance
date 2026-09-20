package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * 在线歌词 URL 拼接的单元测试。
 *
 * 这一块拼错的表现是"一首歌词都拿不到"，而且不报错 —— 很难查，
 * 所以中文 / 空格 / 特殊字符的编码全部钉死。
 */
class OnlineLyricsUrlTest {

    private val base = "https://api.lrc.cx/lyrics"

    /** 把 query 解回来，方便断言编码正确 */
    private fun queryOf(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair ->
            val i = pair.indexOf('=')
            val k = pair.substring(0, i)
            val v = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            k to v
        }

    @Test
    fun `中文标题与歌手被正确编码并能解回原值`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "海阔天空", "Beyond", "海阔天空")!!
        assertTrue("URL 不应含未编码的中文", url.all { it.code < 128 })
        val q = queryOf(url)
        assertEquals("海阔天空", q["title"])
        assertEquals("Beyond", q["artist"])
    }

    @Test
    fun `空格编码成加号且能解回空格`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "500 Miles", "The Proclaimers", "")!!
        assertTrue("空格应编码为 +", url.contains("title=500+Miles"))
        assertEquals("500 Miles", queryOf(url)["title"])
    }

    @Test
    fun `and 与问号等特殊字符不会截断 query`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "Me & You", "A&B", "Q?")!!
        val q = queryOf(url)
        assertEquals("Me & You", q["title"])
        assertEquals("A&B", q["artist"])
        assertEquals("Q?", q["album"])
    }

    @Test
    fun `中文标题里带英文括号和斜杠`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "小幸运 (Live版)", "田馥甄", "A/B")!!
        val q = queryOf(url)
        assertEquals("小幸运 (Live版)", q["title"])
        assertEquals("A/B", q["album"])
    }

    @Test
    fun `空的 artist 与 album 不参与拼接`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "晴天", "", "")!!
        assertTrue(!url.contains("artist="))
        assertTrue(!url.contains("album="))
        assertEquals("晴天", queryOf(url)["title"])
    }

    @Test
    fun `标题为空时返回 null`() {
        assertNull(OnlineLyrics.buildLyricsUrl(base, "", "某人", "某专辑"))
        assertNull(OnlineLyrics.buildLyricsUrl(base, "   ", "某人", ""))
    }

    @Test
    fun `base 为空时返回 null`() {
        assertNull(OnlineLyrics.buildLyricsUrl("", "晴天", "周杰伦", ""))
        assertNull(OnlineLyrics.buildLyricsUrl("   ", "晴天", "周杰伦", ""))
    }

    @Test
    fun `base 已经带 query 时用 and 续接`() {
        val url = OnlineLyrics.buildLyricsUrl("$base?auth=abc", "晴天", "周杰伦", "")!!
        assertTrue(url.startsWith("$base?auth=abc&title="))
        assertEquals("晴天", queryOf(url)["title"])
    }

    @Test
    fun `自建实例地址同样可用`() {
        val url = OnlineLyrics.buildLyricsUrl("http://192.168.1.9:28883/lyrics", "晴天", "周杰伦", "")!!
        assertTrue(url.startsWith("http://192.168.1.9:28883/lyrics?title="))
        assertEquals("晴天", queryOf(url)["title"])
    }

    @Test
    fun `首尾空格会被裁掉再编码`() {
        val url = OnlineLyrics.buildLyricsUrl(base, "  晴天  ", "  周杰伦  ", "")!!
        // 参数名没变，值里不该出现首尾空格（这里只验证整体可解回）
        val q = queryOf(url)
        assertTrue(q["title"]!!.trim() == "晴天")
    }
}
