package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放并下载」里**不依赖 Android** 的那部分。
 *
 * 重点是 [OnlineDownloader.fileNameFor]：歌名里带文件名非法字符的非常多
 * （`AC/DC`、`你在吗?`、`1/2 的魔法`），直接用会让创建文件失败 ——
 * 更糟的是带 `/` 时会被当成**目录分隔符**，文件落到别的路径下去。
 * 系统又不报错，只是"这首歌怎么下载不了"，很难查。
 */
class OnlineDownloaderTest {

    // ===== 下载键 =====

    @Test
    fun `下载键是 音源-远端id`() {
        assertEquals("netease:186016", OnlineDownloader.downloadKeyOf("netease", "186016"))
    }

    @Test
    fun `没有远端 id 就没有下载键`() {
        // 本地歌 / 数据不完整的在线歌：不该进下载流程
        assertNull(OnlineDownloader.downloadKeyOf("netease", null))
        assertNull(OnlineDownloader.downloadKeyOf("netease", ""))
        assertNull(OnlineDownloader.downloadKeyOf("netease", "   "))
    }

    @Test
    fun `同一个音源里不同 id 键不同`() {
        val a = OnlineDownloader.downloadKeyOf("netease", "1")
        val b = OnlineDownloader.downloadKeyOf("netease", "2")
        assertTrue(a != b)
    }

    @Test
    fun `不同音源的同一个 id 键不同`() {
        // 远端 id 只在各自音源内唯一，主键必须是 (音源, 远端 id)
        val a = OnlineDownloader.downloadKeyOf("netease", "1")
        val b = OnlineDownloader.downloadKeyOf("qqmusic", "1")
        assertTrue(a != b)
    }

    // ===== 文件名 =====

    @Test
    fun `文件名是 歌手 - 歌名`() {
        assertEquals(
            "周杰伦 - 晴天.mp3",
            OnlineDownloader.fileNameFor("晴天", "周杰伦")
        )
    }

    @Test
    fun `没有歌手时只留歌名`() {
        // 本地文件/搜索结果里歌手经常是空的
        assertEquals("晴天.mp3", OnlineDownloader.fileNameFor("晴天", ""))
        assertEquals("晴天.mp3", OnlineDownloader.fileNameFor("晴天", "   "))
    }

    @Test
    fun `歌名里的斜杠必须被换掉`() {
        // ★ 最关键的一条：`/` 会被当成目录分隔符，文件会落到别的路径下，
        // 而且不报错 —— 表现只是"这首下载了但找不到"
        val name = OnlineDownloader.fileNameFor("AC/DC", "")
        assertFalse("文件名里不能有 /：$name", name.contains("/"))
        assertEquals("AC_DC.mp3", name)
    }

    @Test
    fun `其它非法字符也会被换掉`() {
        val name = OnlineDownloader.fileNameFor("你在吗?*:<>|\"\\", "")
        for (c in listOf('?', '*', ':', '<', '>', '|', '"', '\\', '/')) {
            assertFalse("不该出现 $c：$name", name.contains(c))
        }
        assertTrue(name.endsWith(".mp3"))
    }

    @Test
    fun `连字符和空格是安全的保留下来`() {
        // 只换非法字符，别把正常的歌名改得面目全非
        assertEquals(
            "张学友 - 味道 (live).mp3",
            OnlineDownloader.fileNameFor("味道 (live)", "张学友")
        )
    }

    @Test
    fun `中文不被误伤`() {
        assertEquals("活出生命Live演唱会.mp3", OnlineDownloader.fileNameFor("活出生命Live演唱会", ""))
        // 全角标点不在 FAT 的非法集里，保留 —— 它是歌名的一部分
        assertEquals("你在，我怕谁？.mp3", OnlineDownloader.fileNameFor("你在，我怕谁？", ""))
    }

    @Test
    fun `歌名为空时有兜底`() {
        assertEquals("未知歌曲.mp3", OnlineDownloader.fileNameFor("", ""))
        assertEquals("未知歌曲.mp3", OnlineDownloader.fileNameFor("   ", "  "))
    }

    @Test
    fun `结尾的点和空格会被去掉`() {
        // Windows 上以点/空格结尾的文件名是非法的 —— 外接卡插电脑上就暴露了
        assertEquals("晴天.mp3", OnlineDownloader.fileNameFor("晴天...", ""))
        assertEquals("晴天.mp3", OnlineDownloader.fileNameFor("晴天   ", ""))
    }

    @Test
    fun `超长歌名会被截断`() {
        // 单段文件名上限通常 255 字节，中文 UTF-8 一个字 3 字节 —— 保守取 60 字符
        val name = OnlineDownloader.fileNameFor("あ".repeat(300), "")
        val stem = name.removeSuffix(".mp3")
        assertTrue("实际长度 ${stem.length}", stem.length <= 60)
        assertTrue(name.endsWith(".mp3"))
    }

    @Test
    fun `同一个歌名歌手算出同一个文件名`() {
        // 用来判断是否重复下载，必须稳定
        assertEquals(
            OnlineDownloader.fileNameFor("晴天", "周杰伦"),
            OnlineDownloader.fileNameFor("晴天", "周杰伦")
        )
    }
}
