package com.sonance.player.data.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 洛雪多平台曲库引擎（酷我 / QQ / 酷狗）纯 JSON / JSONP 解析单测。
 */
class LxCatalogParserTest {

    @Test
    fun `kuwo song search, sheet search, recommend sheets, tags, toplists and lyric parsers`() {
        val searchJson = """
            {
              "TOTAL": "120",
              "abslist": [
                {
                  "MUSICRID": "MUSIC_187654",
                  "SONGNAME": "七里香 &amp; 测试",
                  "ARTIST": "周杰伦&amp;嘉宾",
                  "ALBUM": "七里香",
                  "DURATION": 299,
                  "web_albumpic_short": "120/76/54/187654.jpg"
                }
              ]
            }
        """.trimIndent()
        val page = LxCatalogProvider.parseKuwoSongSearch(searchJson)
        assertNotNull(page)
        assertEquals(120, page!!.total)
        assertEquals(1, page.tracks.size)
        assertEquals("187654", page.tracks[0].remoteId)
        assertEquals("七里香 & 测试", page.tracks[0].title)
        assertEquals("周杰伦 / 嘉宾", page.tracks[0].artist)
        assertEquals(299_000L, page.tracks[0].durationMs)
        assertEquals("https://img4.kuwo.cn/star/albumcover/500/76/54/187654.jpg", page.tracks[0].coverUrl)

        val sheetSearchJson = """
            {
              "TOTAL": "2",
              "abslist": [
                {
                  "playlistid": "345678",
                  "name": "华语精选 &amp; 热歌",
                  "pic": "http://img4.kuwo.cn/star/albumcover/240/a.jpg",
                  "nickname": "阿乐",
                  "songnum": "45"
                }
              ]
            }
        """.trimIndent()
        val sheetPage = LxCatalogProvider.parseKuwoSheetSearch(sheetSearchJson)
        assertNotNull(sheetPage)
        assertEquals(2, sheetPage!!.total)
        assertEquals("345678", sheetPage.sheets[0].id)
        assertEquals("华语精选 & 热歌", sheetPage.sheets[0].name)
        assertEquals("https://img4.kuwo.cn/star/albumcover/500/a.jpg", sheetPage.sheets[0].coverUrl)
        assertEquals(45, sheetPage.sheets[0].trackCount)

        val toplistJson = """
            {
              "child": [
                {
                  "name": "官方榜",
                  "child": [
                    {"sourceid": "93", "name": "飙升榜", "pic5": "http://img4.kuwo.cn/star/albumcover/120/top93.jpg", "intro": "每日更新"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val groups = LxCatalogProvider.parseKuwoToplists(toplistJson)
        assertNotNull(groups)
        assertEquals(1, groups!!.size)
        assertEquals("官方榜", groups[0].title)
        assertEquals("93", groups[0].boards[0].id)
        assertEquals("飙升榜", groups[0].boards[0].name)

        val boardTracksJson = """
            {
              "musiclist": [
                {"id": "1001", "name": "稻香", "artist": "周杰伦", "album": "魔杰座", "duration": "223", "albumpic": "https://img4.kuwo.cn/star/albumcover/240/dx.jpg"}
              ]
            }
        """.trimIndent()
        val boardTracks = LxCatalogProvider.parseKuwoBoardTracks(boardTracksJson)!!
        assertEquals(1, boardTracks.size)
        assertEquals("1001", boardTracks[0].remoteId)
        assertEquals(223_000L, boardTracks[0].durationMs)

        val tagsJson = """
            {
              "data": [
                {"name": "语种", "data": [{"id": "37", "name": "华语", "digest": "10000"}]}
              ]
            }
        """.trimIndent()
        val tags = LxCatalogProvider.parseKuwoSheetTags(tagsJson)
        assertEquals(1, tags.size)
        assertEquals("37", tags[0].id)
        assertEquals("语种", tags[0].group)

        val rcmJson = """
            {
              "data": {
                "data": [
                  {"id": "888", "name": "夜晚民谣", "img": "http://img4.kuwo.cn/star/albumcover/120/folk.jpg", "uname": "清音", "total": "30", "desc": "静心"}
                ]
              }
            }
        """.trimIndent()
        val rcm = LxCatalogProvider.parseKuwoRcmSheets(rcmJson)!!
        assertEquals(1, rcm.size)
        assertEquals("888", rcm[0].id)
        assertEquals(30, rcm[0].trackCount)

        val sheetTracks = LxCatalogProvider.parseKuwoSheetTracks(boardTracksJson)!!
        assertEquals(1, sheetTracks.size)

        val lyricJson = """
            {
              "data": {
                "lrclist": [
                  {"time": "1.25", "lineLyric": "窗外的麻雀"},
                  {"time": "65.5", "lineLyric": "在电线杆上多嘴 &amp; 轻轻唱"}
                ],
                "songinfo": {"pic": "http://img4.kuwo.cn/star/albumcover/120/qilixiang.jpg"}
              }
            }
        """.trimIndent()
        val (lrc, pic) = LxCatalogProvider.parseKuwoLyricAndCover(lyricJson)
        assertEquals("[00:01.25]窗外的麻雀\n[01:05.50]在电线杆上多嘴 & 轻轻唱", lrc)
        assertEquals("https://img4.kuwo.cn/star/albumcover/500/qilixiang.jpg", pic)
    }

    @Test
    fun `qq music song, sheet, toplist, tags and base64 utf8 lyric parsers`() {
        val searchJson = """
            {
              "req": {
                "data": {
                  "meta": {"sum": 50},
                  "body": {
                    "song": {
                      "list": [
                        {
                          "mid": "001txMid",
                          "id": "5100",
                          "title": "夜曲",
                          "interval": 226,
                          "file": {"media_mid": "001mediaMid"},
                          "album": {"mid": "001albMid", "name": "十一月的萧邦"},
                          "singer": [{"name": "周杰伦"}, {"name": "合唱"}]
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val page = LxCatalogProvider.parseTxSongSearch(searchJson)
        assertNotNull(page)
        assertEquals(50, page!!.total)
        assertEquals(1, page.tracks.size)
        val t = page.tracks[0]
        assertEquals("夜曲", t.title)
        assertEquals("周杰伦 / 合唱", t.artist)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000001albMid.jpg", t.coverUrl)
        val parsedIds = LxRemoteId.parseTx(t.remoteId)
        assertEquals("001txMid", parsedIds.songmid)
        assertEquals("001mediaMid", parsedIds.mediaMid)
        assertEquals("001albMid", parsedIds.albumMid)
        assertEquals("5100", parsedIds.songId)

        val sheetSearchJson = """
            {
              "req": {
                "data": {
                  "meta": {"sum": 10},
                  "body": {
                    "songlist": {
                      "list": [
                        {"dissid": "9012", "dissname": "QQ 精选", "imgurl": "https://y.gtimg.cn/a.jpg", "creator": {"name": "官方"}, "song_count": 20, "introduction": "简介"}
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val txSheets = LxCatalogProvider.parseTxSheetSearch(sheetSearchJson)!!
        assertEquals(10, txSheets.total)
        assertEquals("9012", txSheets.sheets[0].id)

        val toplistJson = """
            {
              "req": {
                "data": {
                  "group": [
                    {
                      "groupName": "巅峰榜",
                      "toplist": [
                        {"topId": 26, "title": "热歌榜", "headPicUrl": "https://y.gtimg.cn/top26.jpg", "period": "2026_10"}
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val txGroups = LxCatalogProvider.parseTxToplists(toplistJson)!!
        assertEquals("26", txGroups[0].boards[0].id)
        assertEquals("2026_10", txGroups[0].boards[0].period)

        val boardTracksJson = """
            {
              "req": {
                "data": {
                  "data": {
                    "songInfoList": [
                      {"mid": "009board", "name": "晴天", "interval": 269, "album": {"mid": "009alb", "title": "叶惠美"}, "singer": [{"name": "周杰伦"}]}
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        assertEquals(1, LxCatalogProvider.parseTxBoardTracks(boardTracksJson)!!.size)

        val tagsJsonp = """MusicJsonCallback({"data":{"categories":[{"categoryGroupName":"热门","items":[{"categoryId":6,"categoryName":"华语","usable":1},{"categoryId":99,"categoryName":"停用","usable":0}]}]}})"""
        val txTags = LxCatalogProvider.parseTxSheetTags(tagsJsonp)
        assertEquals(1, txTags.size)
        assertEquals("6", txTags[0].id)

        val tagSheetsJsonp = """getPlaylistTags({"data":{"list":[{"dissid":"777","dissname":"标签歌单","imgurl":"https://y.gtimg.cn/777.jpg","creator":{"name":"小王"}}]}})"""
        val txTagSheets = LxCatalogProvider.parseTxTagSheets(tagSheetsJsonp)!!
        assertEquals("777", txTagSheets[0].id)

        val sheetTracksJsonp = """playlistinfoCallback({"cdlist":[{"songlist":[{"songmid":"002abc","songname":"简单爱","interval":270,"albummid":"002alb","albumname":"范特西","singer":[{"name":"周杰伦"}]}]}]})"""
        val txSheetTracks = LxCatalogProvider.parseTxSheetTracks(sheetTracksJsonp)!!
        assertEquals("简单爱", txSheetTracks[0].title)

        val lrcRaw = "[00:10.00]Hello &amp; 世界"
        val transRaw = "[00:10.00]你好 &apos;翻译&apos;"
        val lrcB64 = Base64.getEncoder().encodeToString(lrcRaw.toByteArray(Charsets.UTF_8))
        val transB64 = Base64.getEncoder().encodeToString(transRaw.toByteArray(Charsets.UTF_8))
        val lyricJsonp = """MusicJsonCallback({"lyric":"$lrcB64","trans":"$transB64"})"""
        val txLyric = LxCatalogProvider.parseTxLyric(lyricJsonp)
        assertNotNull(txLyric)
        assertEquals("[00:10.00]Hello & 世界", txLyric!!.lrc)
        assertEquals("[00:10.00]你好 '翻译'", txLyric.tlyric)
    }

    @Test
    fun `kugou song, sheet, toplist, track info and lyric parsers handle tags and filenames`() {
        val searchJson = """
            {
              "data": {
                "total": 33,
                "lists": [
                  {
                    "FileHash": "HASH123",
                    "AlbumID": "456",
                    "SongName": "<em>告白</em>气球",
                    "SingerName": "周杰伦",
                    "AlbumName": "周杰伦的床边故事",
                    "Duration": 215,
                    "Image": "https://imge.kugou.com/stdmusic/{size}/cover.jpg"
                  },
                  {
                    "FileHash": "HASH999",
                    "AlbumID": "0",
                    "SongName": "",
                    "SingerName": "",
                    "FileName": "陈奕迅 - 十年",
                    "AlbumName": "黑白灰",
                    "Duration": 205
                  }
                ]
              }
            }
        """.trimIndent()
        val kgPage = LxCatalogProvider.parseKgSongSearch(searchJson)!!
        assertEquals(33, kgPage.total)
        assertEquals(2, kgPage.tracks.size)
        assertEquals("HASH123~456", kgPage.tracks[0].remoteId)
        assertEquals("告白气球", kgPage.tracks[0].title)
        assertEquals("https://imge.kugou.com/stdmusic/480/cover.jpg", kgPage.tracks[0].coverUrl)
        assertEquals("HASH999", kgPage.tracks[1].remoteId)
        assertEquals("陈奕迅", kgPage.tracks[1].artist)
        assertEquals("十年", kgPage.tracks[1].title)

        val sheetJson = """
            {
              "data": {
                "total": 5,
                "info": [
                  {"specialid": "1029", "specialname": "<em>怀旧</em>金曲", "imgurl": "https://p.kugou.com/{size}/s.jpg", "nickname": "酷狗号", "songcount": 60, "intro": "经典"}
                ]
              }
            }
        """.trimIndent()
        val kgSheets = LxCatalogProvider.parseKgSheetSearch(sheetJson)!!
        assertEquals(5, kgSheets.total)
        assertEquals("怀旧金曲", kgSheets.sheets[0].name)

        val toplistJson = """
            {
              "data": {
                "info": [
                  {"rankid": "6666", "rankname": "酷狗飙升榜", "imgurl": "https://p.kugou.com/{size}/rank.jpg", "intro": "每天更新"}
                ]
              }
            }
        """.trimIndent()
        val kgGroups = LxCatalogProvider.parseKgToplists(toplistJson)!!
        assertEquals("酷狗音乐榜", kgGroups[0].title)
        assertEquals("6666", kgGroups[0].boards[0].id)
        assertTrue(kgGroups[0].boards[0].coverUrl!!.contains("/480/"))

        val trackListJson = """
            {
              "data": {
                "info": [
                  {
                    "hash": "H111",
                    "album_id": "A222",
                    "filename": "林俊杰 - 江南",
                    "remark": "第二天堂",
                    "duration": 267,
                    "album_sizable_cover": "https://p.kugou.com/{size}/jj.jpg"
                  }
                ]
              }
            }
        """.trimIndent()
        val boardTracks = LxCatalogProvider.parseKgBoardTracks(trackListJson)!!
        assertEquals(1, boardTracks.size)
        assertEquals("江南", boardTracks[0].title)
        assertEquals("林俊杰", boardTracks[0].artist)
        assertEquals("H111~A222", boardTracks[0].remoteId)
        assertEquals(1, LxCatalogProvider.parseKgSheetTracks(trackListJson)!!.size)

        val cand = LxCatalogProvider.parseKgLyricCandidate("""{"candidates":[{"id":"9988","accesskey":"KEY_ABC"}]}""")
        assertEquals("9988" to "KEY_ABC", cand)
        assertNull(LxCatalogProvider.parseKgLyricCandidate("""{"candidates":[]}"""))

        val kgLrc = "[00:05.00]到这里就好"
        val kgLrcB64 = Base64.getEncoder().encodeToString(kgLrc.toByteArray(Charsets.UTF_8))
        assertEquals(kgLrc, LxCatalogProvider.parseKgLyricDownload("""{"content":"$kgLrcB64"}"""))

        assertEquals("五月天" to "倔强", LxCatalogProvider.splitKugouFilename(" 五月天 - 倔强 "))
        assertEquals("" to "纯音乐", LxCatalogProvider.splitKugouFilename("纯音乐"))
    }

    @Test
    fun `track playability status parsing across kw tx kg and wy`() {
        // 酷我：不可播拦截、纯数字专辑、VIP 歌曲（洛雪可播）与榜单合法 copyright="0"
        val kwSearch = """
            {
              "TOTAL": "4",
              "abslist": [
                {"MUSICRID": "MUSIC_1", "SONGNAME": "无版权歌", "ARTIST": "A", "DURATION": "200", "ONLINE": "0", "payInfo": {"cannotOnlinePlay": "1", "play": "0000"}},
                {"MUSICRID": "MUSIC_2", "SONGNAME": "数字专辑", "ARTIST": "A", "DURATION": "210", "ONLINE": "1", "pay": "0", "payInfo": {"cannotOnlinePlay": "0", "play": "1111", "feeType": {"album": "1", "song": "0", "vip": "0"}}},
                {"MUSICRID": "MUSIC_3", "SONGNAME": "VIP可解", "ARTIST": "A", "DURATION": "220", "ONLINE": "1", "pay": "16515324", "tpay": "1", "payInfo": {"cannotOnlinePlay": "0", "play": "1111", "feeType": {"song": "1", "vip": "1"}}},
                {"MUSICRID": "MUSIC_4", "SONGNAME": "合法免费", "ARTIST": "A", "DURATION": "230", "ONLINE": "1", "copyright": "0", "payInfo": {"cannotOnlinePlay": "0", "play": "1111"}}
              ]
            }
        """.trimIndent()
        val kw = LxCatalogProvider.parseKuwoSongSearch(kwSearch)!!.tracks
        assertFalse(kw[0].playable)
        assertEquals("无版权", kw[0].unavailableReason)
        assertFalse(kw[1].playable)
        assertEquals("数专", kw[1].unavailableReason)
        assertTrue(kw[2].playable)
        assertTrue(kw[2].vip)
        assertTrue(kw[3].playable)
        assertFalse(kw[3].vip)

        // QQ：status != 0 / switch bit0=0 不可播，仅数专不包月不可播，VIP 包月可播
        val txSearch = """
            {
              "req": {
                "data": {
                  "meta": {"sum": 4},
                  "body": {
                    "song": {
                      "list": [
                        {"mid": "M1", "name": "下架", "status": 31, "singer": [{"name": "S"}], "interval": 180, "pay": {"pay_play": 0, "pay_month": 0}},
                        {"mid": "M2", "name": "开关禁用", "status": 0, "action": {"switch": 636}, "singer": [{"name": "S"}], "interval": 180},
                        {"mid": "M3", "name": "纯数专", "status": 0, "singer": [{"name": "S"}], "interval": 180, "pay": {"pay_play": 1, "pay_month": 0, "price_album": 2000}},
                        {"mid": "M4", "name": "Cruel Summer", "status": 0, "singer": [{"name": "Taylor Swift"}], "interval": 178, "pay": {"pay_play": 1, "pay_month": 1, "price_album": 2000}}
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val tx = LxCatalogProvider.parseTxSongSearch(txSearch)!!.tracks
        assertFalse(tx[0].playable)
        assertEquals("无版权", tx[0].unavailableReason)
        assertFalse(tx[1].playable)
        assertEquals("无版权", tx[1].unavailableReason)
        assertFalse(tx[2].playable)
        assertEquals("数专", tx[2].unavailableReason)
        assertTrue(tx[3].playable)
        assertTrue(tx[3].vip)

        // 酷狗：Privilege=5 无版权，PayType=2 数专，Privilege=10+PkgPrice>0 正常 VIP
        val kgSearch = """
            {
              "data": {
                "total": 4,
                "lists": [
                  {"FileHash": "H1", "AlbumID": "1", "SongName": "下架", "SingerName": "S", "Duration": 200, "Privilege": 5, "PayType": 0},
                  {"FileHash": "H2", "AlbumID": "2", "SongName": "不为谁而作的歌", "SingerName": "林俊杰", "Duration": 225, "Privilege": 10, "PayType": 2, "Price": 200, "PkgPrice": 0},
                  {"FileHash": "H3", "AlbumID": "3", "SongName": "VIP歌", "SingerName": "S", "Duration": 210, "Privilege": 10, "PayType": 3, "Price": 200, "PkgPrice": 1},
                  {"FileHash": "H4", "AlbumID": "4", "SongName": "免费歌", "SingerName": "S", "Duration": 190, "Privilege": 0, "PayType": 0}
                ]
              }
            }
        """.trimIndent()
        val kg = LxCatalogProvider.parseKgSongSearch(kgSearch)!!.tracks
        assertFalse(kg[0].playable)
        assertEquals("无版权", kg[0].unavailableReason)
        assertFalse(kg[1].playable)
        assertEquals("数专", kg[1].unavailableReason)
        assertTrue(kg[2].playable)
        assertTrue(kg[2].vip)
        assertTrue(kg[3].playable)
        assertFalse(kg[3].vip)

        // 网易云 v3/song/detail：privileges.st=-200 / noCopyrightRcmd -> 无版权，fee=4 -> 数专，fee=1 -> VIP可播
        val wyDetail = """
            {
              "songs": [
                {"id": 186016, "fee": 0, "noCopyrightRcmd": null},
                {"id": 1002, "fee": 0, "noCopyrightRcmd": {"type": 2}},
                {"id": 1003, "fee": 4, "noCopyrightRcmd": null},
                {"id": 1004, "fee": 1, "noCopyrightRcmd": null},
                {"id": 1005, "fee": 0, "noCopyrightRcmd": null}
              ],
              "privileges": [
                {"id": 186016, "st": -200, "fee": 0},
                {"id": 1002, "st": 0, "fee": 0},
                {"id": 1003, "st": 0, "fee": 4},
                {"id": 1004, "st": 0, "fee": 1},
                {"id": 1005, "st": 0, "fee": 0}
              ]
            }
        """.trimIndent()
        val wy = LxCatalogProvider.parseWySongDetailStatuses(wyDetail)
        assertFalse(wy["186016"]!!.playable)
        assertEquals("无版权", wy["186016"]!!.unavailableReason)
        assertFalse(wy["1002"]!!.playable)
        assertEquals("无版权", wy["1002"]!!.unavailableReason)
        assertFalse(wy["1003"]!!.playable)
        assertEquals("数专", wy["1003"]!!.unavailableReason)
        assertTrue(wy["1004"]!!.playable)
        assertTrue(wy["1004"]!!.vip)
        assertTrue(wy["1005"]!!.playable)
        assertFalse(wy["1005"]!!.vip)
    }
}
