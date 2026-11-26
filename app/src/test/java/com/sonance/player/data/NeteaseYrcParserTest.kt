package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseYrcParserTest {

    @Test
    fun `convertYrcToEnhancedLrc parses word timings merges near translations and strips credits`() {
        val yrc = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"测试作者"}]}
            [850,400](850,150,0)作词 : 测试作者
            [10250,2400](10250,400,0)风(10650,350,0)吹(11000,500,0)过
            [15400,1800](15400,600,0)Hello (16000,700,0)world
        """.trimIndent()
        val tr = """
            [00:10.270]风轻轻吹过
            [00:15.400]你好世界
        """.trimIndent()

        val converted = convertYrcToEnhancedLrc(yrc)
        assertNotNull(converted)
        val merged = mergeTranslation(converted!!, tr)
        val parsed = LyricsRepository.parse(merged) as Lyrics.Synced

        assertEquals(2, parsed.lines.size)
        val line0 = parsed.lines[0]
        assertEquals(10_250L, line0.timeMs)
        assertEquals("风吹过", line0.text)
        assertEquals("风轻轻吹过", line0.translation)
        assertEquals(3, line0.segments?.size)
        assertEquals(10_250L, line0.segments!![0].timeMs)
        assertEquals("风", line0.segments!![0].text)
        assertEquals(11_000L, line0.segments!![2].timeMs)
        assertEquals("过", line0.segments!![2].text)

        val line1 = parsed.lines[1]
        assertEquals("Hello world", line1.text)
        assertEquals("你好世界", line1.translation)
        assertTrue((line1.segments?.size ?: 0) == 2)
    }

    @Test
    fun `raw YRC input parses directly into word-timed Lyrics`() {
        val rawYrc = """
            [1000,1200](1000,300,0)第(1300,400,0)一(1700,500,0)句
            [3000,1200](3000,400,0)第(3400,400,0)二(3800,400,0)句
        """.trimIndent()
        val parsed = LyricsRepository.parse(rawYrc) as Lyrics.Synced
        assertEquals(2, parsed.lines.size)
        assertEquals("第一句", parsed.lines[0].text)
        assertEquals(3, parsed.lines[0].segments?.size)
        assertEquals(1300L, parsed.lines[0].segments!![1].timeMs)
    }
}
