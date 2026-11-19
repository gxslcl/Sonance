package com.sonance.player.data

import android.net.TestUri
import com.sonance.player.data.lx.LxCatalogProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {

    private fun song(id: Long, title: String, artist: String, file: String) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "专辑",
        albumId = 1L,
        durationMs = 240_000L,
        uri = TestUri("content://test/$id"),
        folderPath = "/Music",
        folderName = "Music",
        displayName = file,
        sizeBytes = 1L,
        dateAddedSec = 0L,
        track = 1
    )

    private val library = listOf(
        song(1L, "这世界那么多人", "莫文蔚", "莫文蔚 - 这世界那么多人.flac"),
        song(2L, "MY ALL", "滨崎步", "Ayumi Hamasaki - MY ALL.flac"),
        song(3L, "面会菜", "Lethe", "Lethe - 面会菜.flac"),
        song(4L, "静止", "杨乃文", "杨乃文 - 静止.flac")
    )

    @Test
    fun `parses m3u8 extinf and path pairs`() {
        val text = """
            #EXTM3U
            #EXTINF:285,莫文蔚 - 这世界那么多人
            /Music/莫文蔚 - 这世界那么多人.flac
            #EXTINF:320,滨崎步 - MY ALL
            /Music/Ayumi Hamasaki - MY ALL.flac
        """.trimIndent()
        val candidates = PlaylistImportParser.parse(text)
        assertEquals(2, candidates.size)
        assertEquals("莫文蔚", candidates[0].artist)
        assertEquals("这世界那么多人", candidates[0].title)
        assertEquals("/Music/莫文蔚 - 这世界那么多人.flac", candidates[0].path)
    }

    @Test
    fun `parses pls format`() {
        val text = """
            [playlist]
            File1=/Music/Lethe - 面会菜.flac
            Title1=Lethe - 面会菜
            Length1=245
            File2=/Music/杨乃文 - 静止.flac
            NumberOfEntries=2
        """.trimIndent()
        val candidates = PlaylistImportParser.parse(text)
        assertEquals(2, candidates.size)
        assertEquals("面会菜", candidates[0].title)
        assertEquals("静止", candidates[1].title)
    }

    @Test
    fun `parses plain artist dash title lines`() {
        val candidates = PlaylistImportParser.parse("赵鹏 - 绿岛小夜曲\n杨丞琳 - 雨爱")
        assertEquals(2, candidates.size)
        assertEquals("赵鹏", candidates[0].artist)
        assertEquals("绿岛小夜曲", candidates[0].title)
    }

    @Test
    fun `matches by file name then by title and artist`() {
        val candidates = PlaylistImportParser.parse(
            """
            #EXTM3U
            #EXTINF:285,莫文蔚 - 这世界那么多人
            /Music/莫文蔚 - 这世界那么多人.flac
            #EXTINF:245,Lethe - 面会菜
            /Music/Lethe - 面会菜.flac
            #EXTINF:240,陈奕迅 - 富士山下
            /Music/陈奕迅 - 富士山下.flac
            """.trimIndent()
        )
        val result = PlaylistImportMatcher.match(candidates, library)
        assertEquals(listOf(1L, 3L), result.matchedLocalIds)
        assertEquals(1, result.unmatched.size)
        assertTrue(result.unmatched[0].display.contains("富士山下"))
    }

    @Test
    fun `title only line matches without artist`() {
        val result = PlaylistImportMatcher.match(PlaylistImportParser.parse("静止"), library)
        assertEquals(listOf(4L), result.matchedLocalIds)
    }

    @Test
    fun `duplicate candidates dedupe in match result`() {
        val result = PlaylistImportMatcher.match(PlaylistImportParser.parse("静止\n杨乃文 - 静止"), library)
        assertEquals(listOf(4L), result.matchedLocalIds)
    }

    @Test
    fun `extracts netease playlist id from common link forms`() {
        assertEquals(
            "123456",
            PlaylistImportParser.neteasePlaylistId("https://music.163.com/#/playlist?id=123456")
        )
        assertEquals(
            "789",
            PlaylistImportParser.neteasePlaylistId("https://y.music.163.com/m/playlist?id=789&userid=1")
        )
        assertEquals(
            "42",
            PlaylistImportParser.neteasePlaylistId("https://music.163.com/playlist/42/whatever/")
        )
        assertEquals(null, PlaylistImportParser.neteasePlaylistId("https://example.com/playlist?id=1"))
        assertEquals(null, PlaylistImportParser.neteasePlaylistId("https://music.163.com/#/song?id=1"))
    }

    @Test
    fun `m3u export writes extinf and local file paths`() {
        val text = playlistToM3u("我的歌单", library.take(2))
        val lines = text.lines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#PLAYLIST:我的歌单", lines[1])
        assertEquals("#EXTINF:240,莫文蔚 - 这世界那么多人", lines[2])
        assertEquals("/Music/莫文蔚 - 这世界那么多人.flac", lines[3])
        assertEquals("#EXTINF:240,滨崎步 - MY ALL", lines[4])
    }

    @Test
    fun `m3u export writes netease link for online songs`() {
        val online = library[0].copy(
            id = -1L,
            source = "netease",
            remoteId = "999",
            folderPath = "",
            displayName = ""
        )
        val text = playlistToM3u("在线歌单", listOf(online))
        assertTrue(text.contains("https://music.163.com/song?id=999"))
    }

    @Test
    fun `extracts urls from share copy across netease kugou and qq`() {
        val kgCopy = "分享陈陆Cc创建的歌单「喜欢的音乐」https://t1.kugou.com/4kaF3dbG7V2（来自@酷狗音乐）"
        assertEquals("https://t1.kugou.com/4kaF3dbG7V2", OnlinePlaylistImporter.extractFirstHttpUrl(kgCopy))

        val qqCopy = "♪分享歌单，快来看看吧：https://i.y.qq.com/n2/m/share/details/taoge.html?id=7707261125&platform=11。"
        assertEquals(
            "https://i.y.qq.com/n2/m/share/details/taoge.html?id=7707261125&platform=11",
            OnlinePlaylistImporter.extractFirstHttpUrl(qqCopy)
        )

        val wyCopy = "分享歌单 https://music.163.com/#/playlist?id=123456"
        assertEquals("123456", PlaylistImportParser.neteasePlaylistId(wyCopy))
    }

    @Test
    fun `parses kugou targets including negative specialid sentinel and zlist urls`() {
        val redirected =
            "https://activity.kugou.com/share/v-a00a45b0/index.html?u=1322464044&h1=338&h2=-&specialid=-2147483648&global_specialid=collection_3_1322464044_2_0&cType=0"
        val t1 = OnlinePlaylistImporter.parseKugouTarget(redirected)
        assertNotNull(t1)
        assertNull(t1!!.specialId)
        assertEquals("collection_3_1322464044_2_0", t1.globalCollectionId)

        val zlist = "https://www.kugou.com/yy/html/zlist.html?listid=2&uid=1322464044"
        val t2 = OnlinePlaylistImporter.parseKugouTarget(zlist)
        assertNotNull(t2)
        assertEquals("collection_3_1322464044_2_0", t2!!.globalCollectionId)

        val single = "https://www.kugou.com/yy/special/single/6409645.html"
        val t3 = OnlinePlaylistImporter.parseKugouTarget(single)
        assertNotNull(t3)
        assertEquals("6409645", t3!!.specialId)
    }

    @Test
    fun `parses qq playlist ids and rejects song links`() {
        assertEquals(
            "7707261125",
            OnlinePlaylistImporter.parseQqPlaylistId("https://i.y.qq.com/n2/m/share/details/taoge.html?id=7707261125&platform=11")
        )
        assertEquals(
            "7707261125",
            OnlinePlaylistImporter.parseQqPlaylistId("https://y.qq.com/n/ryqq/playlist/7707261125")
        )
        assertEquals(
            "7707261125",
            OnlinePlaylistImporter.parseQqPlaylistId("https://y.qq.com/n/yqq/playsquare/7707261125.html")
        )
        assertNull(
            OnlinePlaylistImporter.parseQqPlaylistId("https://y.qq.com/n/ryqq/songDetail/002xTzGb2UBQRk")
        )
    }

    @Test
    fun `kugou h5 signature is deterministic and parses pubsongscdn tracks`() {
        val params = mapOf("appid" to "1058", "page" to "1", "specialid" to "0")
        val s1 = OnlinePlaylistImporter.signKugouH5Params(params)
        val s2 = OnlinePlaylistImporter.signKugouH5Params(mapOf("specialid" to "0", "page" to "1", "appid" to "1058"))
        assertEquals(32, s1.length)
        assertEquals(s1, s2)

        val arr = org.json.JSONArray(
            """
            [
              {
                "hash": "2305DC36B8CDE63BD1E22F371CE120B5",
                "name": "叶蒨文 - 秋去秋来",
                "album_id": "517053",
                "timelen": 273031,
                "remark": "《哭砂》粤语版",
                "albuminfo": {"id": 517053, "name": "叶倩文精选十七首"},
                "cover": "http://imge.kugou.com/stdmusic/{size}/20250101.jpg"
              }
            ]
            """.trimIndent()
        )
        val tracks = LxCatalogProvider.parseKgTrackInfoArray(arr)
        assertEquals(1, tracks.size)
        assertEquals("秋去秋来", tracks[0].title)
        assertEquals("叶蒨文", tracks[0].artist)
        assertEquals("叶倩文精选十七首", tracks[0].album)
        assertEquals(273_031L, tracks[0].durationMs)
    }
}