package com.sonance.player.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAuthAndExploreTest {

    @Test
    fun `md5Hex matches standard lowercase digest`() {
        assertEquals("e10adc3949ba59abbe56e057f20f883e", NeteaseAuth.md5Hex("123456"))
    }

    @Test
    fun `parseSmsSendResponse distinguishes 200 vs rate limit vs risk control`() {
        assertTrue(NeteaseAuth.parseSmsSendResponse("""{"code":200,"data":true}""") is NeteaseAuth.SmsSendResult.Sent)
        val limited = NeteaseAuth.parseSmsSendResponse("""{"code":405,"message":"too fast"}""")
        assertTrue(limited is NeteaseAuth.SmsSendResult.Failed)
        assertTrue((limited as NeteaseAuth.SmsSendResult.Failed).message.contains("频繁"))

        val risk = NeteaseAuth.parseSmsSendResponse("""{"code":462}""")
        assertTrue((risk as NeteaseAuth.SmsSendResult.Failed).message.contains("扫码"))
    }

    @Test
    fun `parseCellphoneLoginPayload extracts MUSIC_U from Set-Cookie or body cookie`() {
        val fromHeader = NeteaseAuth.parseCellphoneLoginPayload(
            raw = """{"code":200,"account":{"id":12345},"profile":{"nickname":"清音","avatarUrl":"https://p1.music.126.net/a.jpg"}}""",
            setCookies = listOf("MUSIC_U=abc123456789; Path=/", "__csrf=tok99; Path=/")
        )
        assertNull(fromHeader.errorMessage)
        assertEquals("abc123456789", fromHeader.musicU)
        assertEquals("tok99", fromHeader.csrf)
        assertEquals(12345L, fromHeader.userId)
        assertEquals("清音", fromHeader.nickname)

        val fromBody = NeteaseAuth.parseCellphoneLoginPayload(
            raw = """{"code":200,"cookie":"os=pc; MUSIC_U=bodycookie999; __csrf=cs123","account":{"id":88}}""",
            setCookies = emptyList()
        )
        assertNull(fromBody.errorMessage)
        assertEquals("bodycookie999", fromBody.musicU)
        assertEquals("cs123", fromBody.csrf)
    }

    @Test
    fun `parseCellphoneLoginPayload maps error codes cleanly`() {
        val wrongPwd = NeteaseAuth.parseCellphoneLoginPayload("""{"code":502}""", emptyList())
        assertTrue(wrongPwd.errorMessage!!.contains("密码"))

        val badCaptcha = NeteaseAuth.parseCellphoneLoginPayload("""{"code":503}""", emptyList())
        assertTrue(badCaptcha.errorMessage!!.contains("验证码"))

        val risk = NeteaseAuth.parseCellphoneLoginPayload("""{"code":-460}""", emptyList())
        assertTrue(risk.errorMessage!!.contains("扫码"))
    }

    @Test
    fun `parseUserPlaylists separates liked playlist from other playlists`() {
        val json = """
            {
              "code": 200,
              "playlist": [
                {"id": 1001, "name": "清音喜欢的音乐", "specialType": 5, "trackCount": 148, "coverImgUrl": "https://img/liked.jpg", "creator": {"userId": 42, "nickname": "清音"}},
                {"id": 1002, "name": "夜听轻音", "specialType": 0, "trackCount": 32, "coverImgUrl": "https://img/pl2.jpg", "creator": {"userId": 42, "nickname": "清音"}},
                {"id": 1003, "name": "日语精选", "specialType": 0, "trackCount": 60, "coverImgUrl": "", "creator": {"userId": 99, "nickname": "他人"}}
              ]
            }
        """.trimIndent()
        val parsed = NeteaseExplore.parseUserPlaylists(json, uid = 42L)
        assertNotNull(parsed)
        assertEquals("1001", parsed!!.likedPlaylist?.remoteId)
        assertEquals(148, parsed.likedPlaylist?.trackCount)
        assertEquals(listOf("1002", "1003"), parsed.otherPlaylists.map { it.remoteId })
        assertNull(parsed.otherPlaylists[1].coverUrl)
    }

    @Test
    fun `parseDailyRecommendTracks handles v3 dailySongs with fee vip status`() {
        val json = """
            {
              "code": 200,
              "data": {
                "dailySongs": [
                  {
                    "id": 2001,
                    "name": "晴天",
                    "fee": 1,
                    "dt": 269000,
                    "ar": [{"name": "周杰伦"}],
                    "al": {"name": "叶惠美", "picUrl": "https://img/q.jpg"}
                  }
                ]
              }
            }
        """.trimIndent()
        val nonVipTracks = NeteaseExplore.parseDailyRecommendTracks(json, isVip = false)!!
        assertEquals(1, nonVipTracks.size)
        assertEquals(false, nonVipTracks[0].playable)
        assertEquals(true, nonVipTracks[0].vip)

        val vipTracks = NeteaseExplore.parseDailyRecommendTracks(json, isVip = true)!!
        assertEquals(true, vipTracks[0].playable)
        assertEquals("https://img/q.jpg", vipTracks[0].coverUrl)
    }

    @Test
    fun `parsePersonalFmTracks and parseToplists parse valid payloads`() {
        val fmJson = """
            {
              "code": 200,
              "data": [
                {"id": 3001, "name": "稻香", "fee": 0, "duration": 223000, "artists": [{"name": "周杰伦"}], "album": {"name": "魔杰座", "picUrl": "https://img/fm.jpg"}}
              ]
            }
        """.trimIndent()
        val fm = NeteaseExplore.parsePersonalFmTracks(fmJson, isVip = false)!!
        assertEquals(1, fm.size)
        assertEquals("3001", fm[0].remoteId)
        assertEquals(223000L, fm[0].durationMs)

        val topJson = """
            {
              "code": 200,
              "list": [
                {"id": 19723756, "name": "飙升榜", "trackCount": 100, "coverImgUrl": "https://img/top1.jpg", "updateFrequency": "每天更新"},
                {"id": 3778678, "name": "热歌榜", "trackCount": 200, "coverImgUrl": "https://img/top2.jpg", "updateFrequency": "每周四更新"}
              ]
            }
        """.trimIndent()
        val tops = NeteaseExplore.parseToplists(topJson, limit = 1)!!
        assertEquals(1, tops.size)
        assertEquals("19723756", tops[0].remoteId)
        assertEquals("每天更新", tops[0].creatorName)
    }

    @Test
    fun `parseExplorePlaylists handles pagination total and more flag`() {
        val pageJson = """
            {
              "code": 200,
              "total": 689,
              "more": true,
              "playlists": [
                {"id": 5001, "name": "精选夜听", "trackCount": 42, "coverImgUrl": "https://img/e1.jpg", "creator": {"nickname": "主编"}}
              ]
            }
        """.trimIndent()
        val page = NeteaseExplore.parseExplorePlaylists(pageJson, offset = 0)!!
        assertEquals(1, page.playlists.size)
        assertEquals(689, page.total)
        assertTrue(page.hasMore(offset = 0, limit = 20))

        val endJson = """
            {
              "code": 200,
              "total": 689,
              "more": false,
              "playlists": [
                {"id": 5002, "name": "最后一页", "trackCount": 12, "coverImgUrl": "", "creator": {"nickname": "清音"}}
              ]
            }
        """.trimIndent()
        val endPage = NeteaseExplore.parseExplorePlaylists(endJson, offset = 40)!!
        assertEquals(41, endPage.total)
        assertEquals(false, endPage.hasMore(offset = 40, limit = 20))
    }

    @Test
    fun `parseIntelligenceTracks unwraps songInfo and parses track metadata`() {
        val json = """
            {
              "code": 200,
              "data": [
                {
                  "id": 9001,
                  "recommended": true,
                  "songInfo": {
                    "id": 2714278532,
                    "name": "泡沫 (G.E.M.重生版)",
                    "fee": 1,
                    "dt": 258320,
                    "ar": [{"name": "G.E.M.邓紫棋"}],
                    "al": {"name": "I AM GLORIA", "picUrl": "https://img/gloria.jpg"}
                  }
                },
                {
                  "id": 186016,
                  "name": "晴天",
                  "fee": 8,
                  "duration": 269000,
                  "artists": [{"name": "周杰伦"}],
                  "album": {"name": "叶惠美", "picUrl": "https://img/qt.jpg"}
                }
              ]
            }
        """.trimIndent()
        val tracks = NeteaseExplore.parseIntelligenceTracks(json, isVip = true)!!
        assertEquals(2, tracks.size)
        assertEquals("2714278532", tracks[0].remoteId)
        assertEquals(258320L, tracks[0].durationMs)
        assertTrue(tracks[0].playable)
        assertTrue(tracks[0].vip)
        assertEquals("186016", tracks[1].remoteId)
        assertEquals(269000L, tracks[1].durationMs)
    }
}
