package com.sonance.player.data.lx

import com.sonance.player.data.OnlineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洛雪模式数据模型与纯工具函数的 JVM 单测。
 */
class LxModelsTest {

    @Test
    fun `parseLxScriptMeta parses standard block comment with BOM`() {
        val script = "\uFEFF/*!\n" +
            " * @name 测试洛雪聚合源\n" +
            " * @description 支持酷我与 QQ\n" +
            " * @version 1.2.3\n" +
            " * @author DemoAuthor\n" +
            " * @homepage https://example.com/lx\n" +
            " */\n" +
            "const { EVENT_NAMES, on, send } = globalThis.lx;\n"

        val meta = parseLxScriptMeta(script, fallbackName = "demo_source.js")
        assertEquals("测试洛雪聚合源", meta.name)
        assertEquals("支持酷我与 QQ", meta.description)
        assertEquals("1.2.3", meta.version)
        assertEquals("DemoAuthor", meta.author)
        assertEquals("https://example.com/lx", meta.homepage)
    }

    @Test
    fun `parseLxScriptMeta falls back to clean filename when header absent`() {
        val meta = parseLxScriptMeta("console.log('hi');", fallbackName = "my_custom_source.JS")
        assertEquals("my_custom_source", meta.name)
        assertEquals("", meta.version)
        assertEquals("", meta.author)
    }

    @Test
    fun `LxQuality resolveCandidates caps at requested tier and falls back downward`() {
        assertEquals(
            listOf("flac", "320k", "128k"),
            LxQuality.resolveCandidates("flac24bit", listOf("128k", "320k", "flac"))
        )
        assertEquals(
            listOf("320k", "128k"),
            LxQuality.resolveCandidates("320k", listOf("128k", "320k", "flac", "flac24bit"))
        )
        assertEquals(
            listOf("flac", "flac24bit"),
            LxQuality.resolveCandidates("128k", listOf("flac", "flac24bit"))
        )
        assertEquals(
            listOf("320k", "128k"),
            LxQuality.resolveCandidates("320k", emptyList())
        )
    }

    @Test
    fun `LxQuality formatFor respects resolved url extension first`() {
        assertEquals("flac", LxQuality.formatFor("320k", "https://cdn.example.com/track.flac?token=abc"))
        assertEquals("mp3", LxQuality.formatFor("flac", "https://cdn.example.com/track.mp3?token=abc"))
        assertEquals("mp3", LxQuality.formatFor("flac", "https://cdn.example.com/track.m4a"))
        assertEquals("flac", LxQuality.formatFor("flac24bit", "https://cdn.example.com/stream?id=1"))
        assertEquals("mp3", LxQuality.formatFor("128k", null))
    }

    @Test
    fun `LxRemoteId encodes and parses tx and kg ids safely without path chars`() {
        val txCompact = LxRemoteId.encodeTx(songmid = "001abc", mediaMid = "001abc")
        assertEquals("001abc", txCompact)

        val txFull = LxRemoteId.encodeTx(
            songmid = "001abc",
            mediaMid = "002media",
            albumMid = "003alb",
            songId = "98765"
        )
        assertFalse('/' in txFull || ':' in txFull || '?' in txFull)
        val parsedTx = LxRemoteId.parseTx(txFull)
        assertEquals("001abc", parsedTx.songmid)
        assertEquals("002media", parsedTx.mediaMid)
        assertEquals("003alb", parsedTx.albumMid)
        assertEquals("98765", parsedTx.songId)

        val kgCompact = LxRemoteId.encodeKg("AABBCC", albumId = "0")
        assertEquals("AABBCC", kgCompact)
        val kgFull = LxRemoteId.encodeKg("AABBCC", albumId = "554433")
        val parsedKg = LxRemoteId.parseKg(kgFull)
        assertEquals("AABBCC", parsedKg.hash)
        assertEquals("554433", parsedKg.albumId)
    }

    @Test
    fun `LxRemoteId buildMusicInfoJson populates platform specific identifiers and meta`() {
        val kwInfo = LxRemoteId.buildMusicInfoJson(
            platform = "kw",
            remoteId = "228903",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L
        )
        assertEquals("kw", kwInfo.getString("source"))
        assertEquals("228903", kwInfo.getString("songmid"))
        assertEquals("228903", kwInfo.getString("rid"))
        assertEquals(269, kwInfo.getInt("interval"))
        assertEquals("228903", kwInfo.getJSONObject("meta").getString("songId"))

        val kgEncoded = LxRemoteId.encodeKg("DEADBEAF", "1024")
        val kgInfo = LxRemoteId.buildMusicInfoJson("kg", kgEncoded, "海阔天空", "Beyond", "乐与怒", 325_000L)
        assertEquals("DEADBEAF", kgInfo.getString("hash"))
        assertEquals("1024", kgInfo.getString("albumId"))
        assertEquals("DEADBEAF", kgInfo.getJSONObject("meta").getString("hash"))
        assertEquals("1024", kgInfo.getJSONObject("meta").getString("albumId"))

        val txEncoded = LxRemoteId.encodeTx("001mid", "002media", "003alb", "99")
        val txInfo = LxRemoteId.buildMusicInfoJson("tx", txEncoded, "晴天", "周杰伦", "叶惠美", 269_000L)
        assertEquals("002media", txInfo.getString("strMediaMid"))
        assertEquals("002media", txInfo.getJSONObject("meta").getString("strMediaMid"))
    }

    @Test
    fun `html entities, kuwo lrc timestamps, cover urls and jsonp helpers work`() {
        assertEquals("Tom & Jerry '99 \"Hi\" ♪", decodeHtmlEntities("Tom &amp; Jerry &#39;99 &quot;Hi&quot; &#x266A;"))
        assertEquals("[00:02.25]", formatKuwoLrcTimestamp("2.25"))
        assertEquals("[02:14.50]", formatKuwoLrcTimestamp("134.5"))
        assertNull(formatKuwoLrcTimestamp("-1.0"))
        assertNull(formatKuwoLrcTimestamp("abc"))

        val lrc = formatKuwoLrcList(listOf("1.5" to "第一句 &amp; 测试", "bad" to "ignored", "65.2" to "第二句"))
        assertEquals("[00:01.50]第一句 & 测试\n[01:05.20]第二句", lrc)

        assertEquals(
            "https://img4.kuwo.cn/star/albumcover/500/12/34/56.jpg",
            normalizeKuwoCoverUrl("http://img4.kuwo.cn/star/albumcover/120/12/34/56.jpg")
        )
        assertEquals(
            "https://img4.kuwo.cn/star/albumcover/500/89/12/34.jpg",
            normalizeKuwoCoverUrl("120/89/12/34.jpg")
        )
        assertNull(normalizeKuwoCoverUrl("null"))

        assertEquals(
            "https://imge.kugou.com/stdmusic/480/2020.jpg",
            normalizeKugouCoverUrl("https://imge.kugou.com/stdmusic/{size}/2020.jpg")
        )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000001abc.jpg",
            normalizeQqAlbumCoverUrl("001abc")
        )

        assertEquals("{\"code\":0}", stripJsonpCallback("playlistinfoCallback({\"code\":0})"))
        assertTrue(stripJsonpCallback("  {\"ok\":true} ").startsWith("{"))
    }

    @Test
    fun `OnlineMode fromStored defaults safely to BUILTIN`() {
        assertEquals(OnlineMode.LX, OnlineMode.fromStored("LX"))
        assertEquals(OnlineMode.BUILTIN, OnlineMode.fromStored("BUILTIN"))
        assertEquals(OnlineMode.BUILTIN, OnlineMode.fromStored("UNKNOWN"))
        assertEquals(OnlineMode.BUILTIN, OnlineMode.fromStored(null))
    }
}
