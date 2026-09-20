package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线封面搜索的解析逻辑。
 *
 * 重点钉死一个**真实崩溃**：iTunes 搜一首歌经常返回同一张专辑的多个曲目，
 * 它们的 `artworkUrl100` 一模一样。早先候选列表拿 `fullUrl` 当 LazyGrid 的 key，
 * 重复 key 直接抛 `IllegalArgumentException: Key ... was already used` ——
 * 现象就是"搜封面一有结果就闪退"。现在解析阶段就按封面 URL 去重。
 */
class OnlineCoverParseTest {

    private fun body(vararg items: String) =
        """{"resultCount":${items.size},"results":[${items.joinToString(",")}]}"""

    private fun item(track: String, artist: String, art: String, album: String = "某专辑") =
        """{"trackName":"$track","artistName":"$artist","collectionName":"$album","artworkUrl100":"$art"}"""

    @Test
    fun `正常解析出候选并按尺寸改写封面地址`() {
        val json = body(
            item("晴天", "周杰伦", "https://is1.mzstatic.com/image/thumb/x/100x100bb.jpg")
        )
        val list = OnlineCover.parseResults(json)!!
        assertEquals(1, list.size)
        assertEquals("晴天", list[0].trackName)
        assertEquals("周杰伦", list[0].artistName)
        // 缩略图与大图分别被改写到 THUMB_PX / FULL_PX
        assertTrue(list[0].thumbUrl.contains("${OnlineCover.THUMB_PX}x${OnlineCover.THUMB_PX}bb.jpg"))
        assertTrue(list[0].fullUrl.contains("${OnlineCover.FULL_PX}x${OnlineCover.FULL_PX}bb.jpg"))
    }

    @Test
    fun `同一张专辑的多个曲目只保留一条（这就是闪退的根因）`() {
        // 三首歌来自同一张专辑 → artworkUrl100 完全相同
        val art = "https://is1.mzstatic.com/image/thumb/x/100x100bb.jpg"
        val json = body(
            item("曲目一", "某人", art),
            item("曲目二", "某人", art),
            item("曲目三", "某人", art)
        )
        val list = OnlineCover.parseResults(json)!!
        assertEquals("重复封面必须被去重，否则候选列表全是同一张图", 1, list.size)
    }

    @Test
    fun `去重后仍然保留不同封面`() {
        val json = body(
            item("甲", "A", "https://x/1/100x100bb.jpg"),
            item("乙", "B", "https://x/2/100x100bb.jpg"),
            item("甲重复", "A", "https://x/1/100x100bb.jpg")
        )
        val list = OnlineCover.parseResults(json)!!
        assertEquals(2, list.size)
        assertEquals(list.size, list.map { it.fullUrl }.toSet().size)
    }

    @Test
    fun `没有 artworkUrl100 的条目被跳过`() {
        val json = body(
            """{"trackName":"无封面","artistName":"某人"}""",
            item("有封面", "某人", "https://x/9/100x100bb.jpg")
        )
        val list = OnlineCover.parseResults(json)!!
        assertEquals(1, list.size)
        assertEquals("有封面", list[0].trackName)
    }

    @Test
    fun `空结果返回空列表而不是 null`() {
        assertEquals(0, OnlineCover.parseResults("""{"resultCount":0,"results":[]}""")!!.size)
    }

    @Test
    fun `畸形 JSON 返回 null 而不是抛异常`() {
        assertNull(OnlineCover.parseResults("not json at all"))
        assertNull(OnlineCover.parseResults(""))
    }

    @Test
    fun `resizeUrl 只替换末尾的尺寸片段`() {
        val src = "https://is1.mzstatic.com/image/thumb/Purple/x/100x100bb.jpg"
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/Purple/x/800x800bb.jpg",
            OnlineCover.resizeUrl(src, 800)
        )
        // png 也要支持
        assertEquals(
            "https://x/100x100bb.png".replace("100x100", "600x600"),
            OnlineCover.resizeUrl("https://x/100x100bb.png", 600)
        )
    }

    @Test
    fun `搜索词按精确到宽松排序且去掉占位歌手`() {
        val q = OnlineCover.buildQueries("晴天", "未知艺术家", "叶惠美")
        // 占位歌手要被丢掉：不能出现 "晴天 未知艺术家"
        assertTrue("不应包含占位歌手: $q", q.none { it.contains("未知艺术家") })
        assertTrue("应包含纯标题查询: $q", q.contains("晴天"))
    }

    @Test
    fun `标题里的音轨序号会被清掉`() {
        assertEquals("Midnight Horizon", OnlineCover.cleanTitle("01 Midnight Horizon"))
        assertEquals("Midnight Horizon", OnlineCover.cleanTitle("01. Midnight Horizon"))
        assertEquals("Midnight Horizon", OnlineCover.cleanTitle("[01] Midnight Horizon"))
        // 正常标题不动
        assertEquals("晴天", OnlineCover.cleanTitle("晴天"))
    }

    @Test
    fun `搜索 URL 对中文正确编码`() {
        val url = OnlineCover.buildSearchUrl("晴天 周杰伦", 12, "CN")!!
        assertTrue("URL 不应含未编码中文", url.all { it.code < 128 })
        assertTrue(url.startsWith("https://itunes.apple.com/search?term="))
        assertTrue(url.contains("entity=song"))
        assertTrue(url.contains("limit=12"))
        assertTrue(url.contains("country=CN"))
    }
}
