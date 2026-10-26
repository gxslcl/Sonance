package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词解析的单元测试。
 * 内嵌歌词里塞 LRC 是最常见的形态，时间轴格式五花八门，这里把边界钉住。
 */
class LyricsParseTest {

    @Test
    fun `标准 LRC 解析成时间轴`() {
        val text = """
            [ti:测试]
            [ar:某人]
            [00:12.34]第一行
            [00:15.00]第二行
            [01:02.50]第三行
        """.trimIndent()

        val lyrics = LyricsRepository.parse(text)
        assertNotNull(lyrics)
        assertTrue(lyrics is Lyrics.Synced)

        val lines = (lyrics as Lyrics.Synced).lines
        assertEquals(3, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(15000L, lines[1].timeMs)
        assertEquals(62500L, lines[2].timeMs)
    }

    @Test
    fun `一行多个时间标签会展开成多行`() {
        val text = "[00:10.00][01:20.00]副歌"
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(2, lyrics.lines.size)
        assertEquals(10000L, lyrics.lines[0].timeMs)
        assertEquals(80000L, lyrics.lines[1].timeMs)
        assertEquals("副歌", lyrics.lines[0].text)
        assertEquals("副歌", lyrics.lines[1].text)
    }

    @Test
    fun `offset 标签会整体平移时间`() {
        val text = """
            [offset:-500]
            [00:10.00]第一行
            [00:20.00]第二行
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(9500L, lyrics.lines[0].timeMs)
        assertEquals(19500L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `纯文本歌词退化成 Plain`() {
        val text = """
            第一行没有时间
            第二行也没有
            第三行
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text)
        assertNotNull(lyrics)
        assertTrue(lyrics is Lyrics.Plain)
        assertEquals(3, (lyrics as Lyrics.Plain).lines.size)
    }

    @Test
    fun `只有一行带时间时按纯文本处理`() {
        val text = """
            [00:10.00]只有这一行有时间
            另外一行没有
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text)
        assertTrue(lyrics is Lyrics.Plain)
    }

    @Test
    fun `毫秒三位与无小数都能解析`() {
        val a = LyricsRepository.parse("[00:10]甲\n[00:11]乙") as Lyrics.Synced
        assertEquals(10000L, a.lines[0].timeMs)

        val b = LyricsRepository.parse("[00:10.123]甲\n[00:11.456]乙") as Lyrics.Synced
        assertEquals(10123L, b.lines[0].timeMs)
        assertEquals(11456L, b.lines[1].timeMs)
    }

    @Test
    fun `小时级时间戳也能解析`() {
        val l = LyricsRepository.parse("[01:02:03]很长的一首\n[01:02:10]下一句") as Lyrics.Synced
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, l.lines[0].timeMs)
    }

    @Test
    fun `空内容返回 null`() {
        assertEquals(null, LyricsRepository.parse(""))
        assertEquals(null, LyricsRepository.parse("   \n  \n"))
        assertEquals(null, LyricsRepository.parse("[ti:只有元信息]\n[ar:某人]"))
    }

    @Test
    fun `indexAt 二分定位当前行`() {
        val lines = listOf(
            LyricLine(1000L, "a"),
            LyricLine(5000L, "b"),
            LyricLine(9000L, "c")
        )
        assertEquals(-1, lines.indexAt(0L))
        assertEquals(0, lines.indexAt(1000L))
        assertEquals(0, lines.indexAt(4999L))
        assertEquals(1, lines.indexAt(5000L))
        assertEquals(2, lines.indexAt(999_999L))
        assertEquals(-1, emptyList<LyricLine>().indexAt(1000L))
    }

    @Test
    fun `换行符 CRLF 与 CR 都能处理`() {
        val crlf = LyricsRepository.parse("[00:01.00]甲\r\n[00:02.00]乙") as Lyrics.Synced
        assertEquals(2, crlf.lines.size)

        val cr = LyricsRepository.parse("[00:01.00]甲\r[00:02.00]乙") as Lyrics.Synced
        assertEquals(2, cr.lines.size)
    }

    // ===== 翻译（网易云 tlyric 以 " / " 合并在同一行，解析时拆出）=====

    @Test
    fun `带翻译分隔符的行拆出 translation`() {
        val text = """
            [00:10.00]でももし生まれ変わったら / 如果还有下辈子
            [00:15.00]その時は探すからね / 那时我一定会去找你
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(2, lyrics.lines.size)
        assertEquals("でももし生まれ変わったら", lyrics.lines[0].text)
        assertEquals("如果还有下辈子", lyrics.lines[0].translation)
        assertEquals("その時は探すからね", lyrics.lines[1].text)
        assertEquals("那时我一定会去找你", lyrics.lines[1].translation)
    }

    @Test
    fun `没有分隔符的行 translation 为 null`() {
        val lyrics = LyricsRepository.parse("[00:10.00]普通的一行\n[00:12.00]第二行") as Lyrics.Synced
        assertEquals("普通的一行", lyrics.lines[0].text)
        assertEquals(null, lyrics.lines[0].translation)
    }

    @Test
    fun `译文里再含斜杠时整段归译文`() {
        val lyrics = LyricsRepository.parse("[00:10.00]原文 / 是 / 否\n[00:12.00]下一行") as Lyrics.Synced
        assertEquals("原文", lyrics.lines[0].text)
        assertEquals("是 / 否", lyrics.lines[0].translation)
    }

    @Test
    fun `分隔符两边有一边为空则不拆`() {
        val lyrics = LyricsRepository.parse("[00:10.00]开头就是分隔符 / \n[00:12.00]下一行") as Lyrics.Synced
        // 行尾的孤立斜杠凑不齐 " / " 两侧内容 → 整行保留原样、不当翻译
        assertEquals("开头就是分隔符 /", lyrics.lines[0].text)
        assertEquals(null, lyrics.lines[0].translation)
    }
}
