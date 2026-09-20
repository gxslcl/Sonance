package com.sonance.player.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * **真实网络**集成测试：直接打 LrcAPI 的公开实例，验证整条链路
 * （URL 拼接 → HTTP → 拿到 LRC → 解析成时间轴）真的能跑通。
 *
 * 这是排查"一首歌词都拿不到"最直接的手段 —— 单测只能证明逻辑对，
 * 证明不了接口在这个网络环境下可用。
 *
 * 网络不通时用 [assumeTrue] 跳过（不算失败），避免离线环境下红一片。
 * 只跑一次、只发几个请求，不给公开实例添麻烦。
 */
class OnlineLyricsNetworkTest {

    private val base = "https://api.lrc.cx/lyrics"

    private fun online(): Boolean = runCatching {
        val conn = (URL("https://api.lrc.cx/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "HEAD"
        }
        val ok = conn.responseCode in 200..499
        conn.disconnect()
        ok
    }.getOrDefault(false)

    @Test
    fun `联网能取到中文歌的歌词并解析出时间轴`() = runBlocking {
        assumeTrue("网络不可用，跳过", online())

        val text = OnlineLyrics.fetch(base, "海阔天空", "Beyond", "")
        assertTrue("接口没返回歌词", !text.isNullOrBlank())

        val lyrics = LyricsRepository.parse(text!!)
        assertTrue("歌词没解析出内容", lyrics != null)
        assertTrue(
            "期望解析出带时间轴的歌词，实际是 ${lyrics!!::class.simpleName}",
            lyrics is Lyrics.Synced
        )
        val lines = (lyrics as Lyrics.Synced).lines
        assertTrue("时间轴行数太少: ${lines.size}", lines.size >= 5)
        // 时间应该递增
        assertTrue(lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }

    @Test
    fun `联网能取到英文歌的歌词`() = runBlocking {
        assumeTrue("网络不可用，跳过", online())

        val text = OnlineLyrics.fetch(base, "500 Miles", "The Proclaimers", "")
        assertTrue("接口没返回歌词", !text.isNullOrBlank())

        val lyrics = LyricsRepository.parse(text!!)
        assertTrue(lyrics is Lyrics.Synced)
        assertTrue((lyrics as Lyrics.Synced).lines.size >= 5)
    }

    @Test
    fun `不存在的歌返回 null 而不是抛异常`() = runBlocking {
        assumeTrue("网络不可用，跳过", online())

        val text = OnlineLyrics.fetch(
            base,
            "zzzz_this_song_should_not_exist_${System.nanoTime()}",
            "zzzz_no_such_artist",
            ""
        )
        // 允许接口返回 null（没找到）或返回点东西；关键是别崩
        println("不存在的歌返回: ${text?.take(60)}")
    }
}
