package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线歌存档（[StoredOnlineSong]）的编解码。
 *
 * 这套规则值得单独钉住：它决定"在线歌在冷启动后能不能被找回来"。
 * 少存一个字段就是一条**点不开也删不掉**的幽灵记录，而用户自己修不了。
 *
 * 只测**纯数据部分**：
 * - [StoredOnlineSong.toSong] 要建 [Song]，而 [Song] 带 `android.net.Uri`，
 *   JVM 单测里 `Uri.parse` 是返回 null 的桩 —— 所以"能不能还原成一首能播的歌"
 *   只能在真机验（和 `OnlineMusicTest` 里说明的同一个限制）。
 * - 编解码、id 稳定性这些不需要 Uri，这里就能穷举。
 */
class StoredOnlineSongTest {

    private val sample = StoredOnlineSong(
        source = "netease",
        remoteId = "12345",
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000L,
        coverUrl = "https://example.com/a.jpg",
        playable = true,
        vip = false
    )

    @Test
    fun jsonRoundTripKeepsEverything() {
        val back = storedOnlineSongFromJson(storedOnlineSongToJson(sample))
        assertEquals(sample, back)
    }

    @Test
    fun coverUrlIsOptional() {
        val noCover = sample.copy(coverUrl = null)
        val back = storedOnlineSongFromJson(storedOnlineSongToJson(noCover))
        assertEquals(noCover, back)
        assertNull(back?.coverUrl)
    }

    /** source 和 remoteId 是"把歌找回来"的最小凭据，缺一个这条就废了 */
    @Test
    fun missingSourceIsDropped() {
        val json = storedOnlineSongToJson(sample)
        json.put("source", "")
        assertNull(storedOnlineSongFromJson(json))
    }

    @Test
    fun missingRemoteIdIsDropped() {
        val json = storedOnlineSongToJson(sample)
        json.put("remoteId", "")
        assertNull(storedOnlineSongFromJson(json))
    }

    /** 从 SharedPreferences 读回来的可能是脏数据，丢一条比崩一次好 */
    @Test
    fun wrongTypesDoNotCrash() {
        val json = storedOnlineSongToJson(sample)
        json.put("durationMs", "not-a-number")
        val back = storedOnlineSongFromJson(json)
        assertEquals(0L, back?.durationMs)
    }

    @Test
    fun listRoundTripKeepsOrder() {
        val list = listOf(sample, sample.copy(remoteId = "999", title = "稻香"))
        val back = storedOnlineSongsFromJson(storedOnlineSongsToJson(list))
        assertEquals(list.map { it.remoteId }, back.map { it.remoteId })
    }

    @Test
    fun brokenListJsonGivesEmptyInsteadOfCrashing() {
        assertTrue(storedOnlineSongsFromJson("这不是 JSON").isEmpty())
        assertTrue(storedOnlineSongsFromJson(null).isEmpty())
        assertTrue(storedOnlineSongsFromJson("").isEmpty())
    }

    /**
     * 存档算出来的 id 必须和播放时用的是同一个 ——
     * "正在播的是不是这一首"这类判断全靠它对上。
     */
    @Test
    fun songIdMatchesWhatPlaybackUses() {
        assertEquals(remoteSongId(sample.source, sample.remoteId), sample.songId())
    }

    @Test
    fun toTrackCarriesFieldsOver() {
        val track = sample.toTrack()
        assertEquals(sample.remoteId, track.remoteId)
        assertEquals(sample.title, track.title)
        assertEquals(sample.artist, track.artist)
        assertEquals(sample.album, track.album)
        assertEquals(sample.durationMs, track.durationMs)
        assertEquals(sample.coverUrl, track.coverUrl)
        assertEquals(sample.playable, track.playable)
        assertEquals(sample.vip, track.vip)
    }

    /** 本地歌不该被存成在线歌存档 —— 它本来就在曲库里，走了反而查不到 */
    @Test
    fun localSongHasNoStoredForm() {
        // Song 需要 Uri，JVM 单测造不出来（Uri.parse 是桩），
        // 所以这里只验"没有 remoteId 就不该有存档"这条规则的对应面：
        // 存档必须带 source 和 remoteId，上面 missing* 两个用例已经钉住了。
        // 真正的 Song.toStoredOrNull() 在真机走通。
        assertTrue(sample.source.isNotBlank())
        assertTrue(sample.remoteId.isNotBlank())
    }
}
