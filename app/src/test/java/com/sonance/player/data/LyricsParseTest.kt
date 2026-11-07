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

    // ===== 增强型 LRC（行内逐字时间标签）=====

    @Test
    fun `增强型 LRC 按行内标签切出逐字片段`() {
        val text = """
            [00:27.72]<00:27.720>而<00:27.990>人<00:28.330>生
            [00:29.00]<00:29.000>就<00:29.400>是
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(2, lyrics.lines.size)

        val first = lyrics.lines[0]
        // 行级时间取该行首个标签
        assertEquals(27720L, first.timeMs)
        // 用户看到的正文里绝不能残留 <..> 标签
        assertEquals("而人生", first.text)
        val segments = first.segments
        assertNotNull(segments)
        assertEquals(3, segments!!.size)
        assertEquals("而", segments[0].text)
        assertEquals(27720L, segments[0].timeMs)
        assertEquals("人", segments[1].text)
        assertEquals(27990L, segments[1].timeMs)
        assertEquals("生", segments[2].text)
        assertEquals(28330L, segments[2].timeMs)
    }

    @Test
    fun `混合文件里没有逐字标签的行退化为整行`() {
        val text = """
            [00:10.00]<00:10.000>逐<00:10.300>字
            [00:12.00]普通的一行
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(2, lyrics.lines.size)

        assertNotNull(lyrics.lines[0].segments)
        assertEquals("逐字", lyrics.lines[0].text)
        // 普通行没有片段，渲染层照旧整行高亮
        assertEquals(null, lyrics.lines[1].segments)
        assertEquals("普通的一行", lyrics.lines[1].text)
    }

    @Test
    fun `普通 LRC 不会凭空产生片段`() {
        val lyrics = LyricsRepository.parse("[00:10.00]第一行\n[00:12.00]第二行") as Lyrics.Synced
        assertEquals(null, lyrics.lines[0].segments)
        assertEquals(null, lyrics.lines[1].segments)
        assertEquals("第一行", lyrics.lines[0].text)
    }

    @Test
    fun `增强型 LRC 的 offset 同时作用于行级与逐字时间`() {
        val text = """
            [offset:-500]
            [00:10.00]<00:10.000>甲<00:10.500>乙
            [00:12.00]下一行
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals(9500L, lyrics.lines[0].timeMs)
        val segs = lyrics.lines[0].segments!!
        assertEquals(9500L, segs[0].timeMs)
        assertEquals(10000L, segs[1].timeMs)
    }

    @Test
    fun `行首有多余文字时归到行级时间`() {
        val text = """
            [00:10.00]开<00:10.600>始<00:11.000>唱
            [00:12.00]下一行
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals("开始唱", lyrics.lines[0].text)
        val segs = lyrics.lines[0].segments!!
        assertEquals(3, segs.size)
        assertEquals(10000L, segs[0].timeMs)
        assertEquals("开", segs[0].text)
        assertEquals(10600L, segs[1].timeMs)
    }

    @Test
    fun `写坏的时间标签也不会显示给用户`() {
        val text = """
            [00:10.00]<00:10.000>甲<00:10.500>乙
            [00:12.00]残留<00:12>标签
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals("甲乙", lyrics.lines[0].text)
        // 第二位是写坏的时间标签：剥掉，不能当正文
        assertTrue(lyrics.lines[1].text.contains("残留"))
        assertTrue(lyrics.lines[1].text.contains("标签"))
        assertTrue(!lyrics.lines[1].text.contains("<"))
    }

    @Test
    fun `BOM 开头的增强型 LRC 不把行级时间漏进正文`() {
        // 实测事故：缓存文件带 UTF-8 BOM，行首 [00:00.00] 认不出来，
        // 结果渲染成「逐字歌词测[00:00.00]试」。
        val text = "\uFEFF[00:00.00]<00:00.000>逐<00:00.600>字<00:01.200>歌" +
            "<00:01.800>词<00:02.400>测<00:03.000>试\n[00:04.00]第二行"
        val lyrics = LyricsRepository.parse(text) as Lyrics.Synced
        assertEquals("逐字歌词测试", lyrics.lines[0].text)
        assertEquals(0L, lyrics.lines[0].timeMs)
        assertTrue(!lyrics.lines[0].text.contains("["))
        assertEquals(6, lyrics.lines[0].segments!!.size)
    }

    @Test
    fun `行中间的残留行级时间戳也会被剥掉`() {
        val lyrics = LyricsRepository.parse(
            "[00:10.00]<00:10.000>甲<00:10.500>乙[00:11.00]\n[00:12.00]下一行"
        ) as Lyrics.Synced
        assertEquals("甲乙", lyrics.lines[0].text)
        assertTrue(!lyrics.lines[0].text.contains("["))
    }

    @Test
    fun `纯文本歌词的 !text 标记不显示`() {
        val text = """
            [!text]Let my touch
            Be indistinct
        """.trimIndent()
        val lyrics = LyricsRepository.parse(text) as Lyrics.Plain
        assertEquals("Let my touch", lyrics.lines[0])
        assertEquals("Be indistinct", lyrics.lines[1])
    }
}
