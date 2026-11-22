package com.sonance.player.data.lx

import com.sonance.player.data.NeteaseSource
import com.sonance.player.data.RemoteSearchPage
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.mergeTranslation
import com.sonance.player.data.netease.NeteaseExplore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 洛雪模式内置多平台曲库引擎（`kw` / `tx` / `kg` / `wy`）。
 *
 * 只负责**搜索、榜单、歌单、封面与歌词**这些「曲库展示元数据」；
 * 音频播放流严格经由用户导入的洛雪 `.js` 脚本通过 [LxRuntime] 解析，绝不走内置直连取流。
 */
object LxCatalogProvider {

    private const val TIMEOUT_MS = 10_000
    private val HTML_TAG_REGEX = Regex("</?em>", RegexOption.IGNORE_CASE)

    internal data class LxTrackStatus(
        val playable: Boolean,
        val vip: Boolean,
        val unavailableReason: String? = null
    )

    // ==================== 公开调度入口 ====================

    suspend fun searchSongs(
        platform: String,
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): RemoteSearchPage? = withContext(Dispatchers.IO) {
        val kw = query.trim()
        if (kw.isEmpty()) return@withContext RemoteSearchPage(emptyList(), 0)
        when (platform) {
            "kw" -> searchKuwoSongs(kw, limit, offset)
            "tx" -> searchTxSongs(kw, limit, offset)
            "kg" -> searchKgSongs(kw, limit, offset)
            "wy" -> NeteaseSource.search(kw, limit, offset)?.let { page ->
                page.copy(tracks = enrichWyTracks(page.tracks))
            }
            else -> null
        }
    }

    suspend fun searchSheets(
        platform: String,
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): LxSheetPage? = withContext(Dispatchers.IO) {
        val kw = query.trim()
        if (kw.isEmpty()) return@withContext LxSheetPage(emptyList(), 0)
        when (platform) {
            "kw" -> searchKuwoSheets(kw, limit, offset)
            "tx" -> searchTxSheets(kw, limit, offset)
            "kg" -> searchKgSheets(kw, limit, offset)
            "wy" -> NeteaseSource.searchPlaylists(kw, limit, offset)?.let { page ->
                LxSheetPage(
                    sheets = page.playlists.map { p ->
                        LxSheetSummary(
                            id = p.remoteId,
                            platform = "wy",
                            name = p.name,
                            coverUrl = p.coverUrl,
                            creatorName = p.creatorName,
                            trackCount = p.trackCount
                        )
                    },
                    total = page.total
                )
            }
            else -> null
        }
    }

    suspend fun fetchToplists(platform: String): List<LxTopListGroup>? = withContext(Dispatchers.IO) {
        when (platform) {
            "kw" -> httpGet("http://wapi.kuwo.cn/api/pc/bang/list")?.let(::parseKuwoToplists)
            "tx" -> httpPostJson(
                url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
                body = JSONObject().apply {
                    put("comm", JSONObject().apply { put("ct", 24); put("cv", 0) })
                    put("req", JSONObject().apply {
                        put("module", "musicToplist.ToplistInfoServer")
                        put("method", "GetAll")
                        put("param", JSONObject())
                    })
                }.toString(),
                headers = TX_HEADERS
            )?.let(::parseTxToplists)
            "kg" -> httpGet(
                "http://mobilecdnbj.kugou.com/api/v3/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=0&with_res_tag=0"
            )?.let(::parseKgToplists)
            "wy" -> NeteaseExplore.fetchToplists(limit = 24)?.let { list ->
                listOf(
                    LxTopListGroup(
                        title = "云音乐官方榜",
                        boards = list.map { p ->
                            LxBoard(
                                id = p.remoteId,
                                name = p.name,
                                platform = "wy",
                                coverUrl = p.coverUrl
                            )
                        }
                    )
                )
            }
            else -> null
        }
    }

    suspend fun fetchBoardTracks(board: LxBoard): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        when (board.platform) {
            "kw" -> httpGet(
                "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=100&type=bang&data=content&id=${enc(board.id)}&show_copyright_off=0&pcmp4=1&isbang=1&userid=0&httpStatus=1"
            )?.let(::parseKuwoBoardTracks)
            "tx" -> {
                val topId = board.id.toIntOrNull() ?: return@withContext emptyList()
                val param = JSONObject().apply {
                    put("topId", topId)
                    put("offset", 0)
                    put("num", 100)
                    if (board.period.isNotBlank()) put("period", board.period)
                }
                httpPostJson(
                    url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
                    body = JSONObject().apply {
                        put("comm", JSONObject().apply { put("ct", 24); put("cv", 0) })
                        put("req", JSONObject().apply {
                            put("module", "musicToplist.ToplistInfoServer")
                            put("method", "GetDetail")
                            put("param", param)
                        })
                    }.toString(),
                    headers = TX_HEADERS
                )?.let(::parseTxBoardTracks)
            }
            "kg" -> httpGet(
                "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=0&plat=0&pagesize=100&area_code=1&page=1&volid=35050&rankid=${enc(board.id)}&with_res_tag=0"
            )?.let(::parseKgBoardTracks)
            "wy" -> NeteaseSource.playlistTracks(board.id)?.let { enrichWyTracks(it) }
            else -> null
        }
    }

    suspend fun fetchSheetTags(platform: String): List<LxSheetTag> = withContext(Dispatchers.IO) {
        when (platform) {
            "kw" -> {
                val hot = LxSheetTag(id = "hot", name = "热门", digest = "hot")
                val body = httpGet(
                    "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576"
                )
                val parsed = body?.let(::parseKuwoSheetTags).orEmpty()
                listOf(hot) + parsed.filterNot { it.name == "热门" }.take(20)
            }
            "tx" -> {
                val all = LxSheetTag(id = "10000000", name = "全部", digest = "tx")
                val body = httpGet(
                    "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg?format=json&inCharset=utf8&outCharset=utf-8",
                    TX_HEADERS
                )
                val parsed = body?.let(::parseTxSheetTags).orEmpty()
                listOf(all) + parsed.filterNot { it.id == "10000000" }.take(20)
            }
            "kg" -> KG_PRESET_TAGS
            "wy" -> WY_PRESET_TAGS
            else -> emptyList()
        }
    }

    suspend fun fetchRecommendedSheets(
        platform: String,
        tag: LxSheetTag? = null,
        limit: Int = 24,
        page: Int = 1
    ): List<LxSheetSummary>? = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        when (platform) {
            "kw" -> {
                val useRcm = tag == null || tag.digest == "hot" || tag.id == "hot"
                if (useRcm) {
                    httpGet(
                        "https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$safePage&rn=$limit&order=hot"
                    )?.let(::parseKuwoRcmSheets)
                } else {
                    val activeTag = tag!!
                    if (activeTag.digest == "43") {
                        val qz = httpGet(
                            "http://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=${enc(activeTag.id)}&prod=pc"
                        )?.let(::parseKuwoQzSheets)?.takeIf { it.isNotEmpty() }
                        qz ?: searchKuwoSheets(activeTag.name, limit, (safePage - 1) * limit)?.sheets
                    } else {
                        val tagList = httpGet(
                            "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$safePage&id=${enc(activeTag.id)}&rn=$limit"
                        )?.let(::parseKuwoRcmSheets)?.takeIf { it.isNotEmpty() }
                        tagList ?: httpGet(
                            "http://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=${enc(activeTag.id)}&prod=pc"
                        )?.let(::parseKuwoQzSheets)?.takeIf { it.isNotEmpty() }
                            ?: searchKuwoSheets(activeTag.name, limit, (safePage - 1) * limit)?.sheets
                    }
                }
            }
            "tx" -> {
                val catId = tag?.id?.ifBlank { "10000000" } ?: "10000000"
                val sin = (safePage - 1) * limit
                val ein = sin + limit - 1
                httpGet(
                    "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.1&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&categoryId=${enc(catId)}&sortId=5&sin=$sin&ein=$ein",
                    TX_HEADERS
                )?.let(::parseTxTagSheets)
            }
            "kg" -> {
                val keyword = tag?.name?.ifBlank { "热门" } ?: "热门"
                searchKgSheets(keyword, limit, (safePage - 1) * limit)?.sheets
            }
            "wy" -> {
                val cat = tag?.id?.ifBlank { "全部" } ?: "全部"
                NeteaseExplore.fetchExplorePlaylists(
                    limit = limit,
                    offset = (safePage - 1) * limit,
                    cat = cat
                )?.playlists?.map { p ->
                    LxSheetSummary(
                        id = p.remoteId,
                        platform = "wy",
                        name = p.name,
                        coverUrl = p.coverUrl,
                        creatorName = p.creatorName,
                        trackCount = p.trackCount
                    )
                }
            }
            else -> null
        }
    }

    suspend fun fetchRecommendedSheets(platform: String, limit: Int): List<LxSheetSummary>? =
        fetchRecommendedSheets(platform = platform, tag = null, limit = limit, page = 1)

    suspend fun fetchSheetTracks(platform: String, sheetId: String): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        when (platform) {
            "kw" -> httpGet(
                "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=${enc(sheetId)}&pn=0&rn=100&encode=utf8&keyset=pl2012&vipver=MUSIC_9.1.1.2_BCS2&newver=1"
            )?.let(::parseKuwoSheetTracks)
            "tx" -> fetchTxSheetTracks(sheetId)
            "kg" -> com.sonance.player.data.OnlinePlaylistImporter.fetchKugouTracksByTarget(sheetId)
                ?: httpGet(
                    "http://mobilecdn.kugou.com/api/v3/special/song?specialid=${enc(sheetId)}&plat=0&page=1&pagesize=100&version=9108"
                )?.let(::parseKgSheetTracks)
            "wy" -> NeteaseSource.playlistTracks(sheetId)?.let { enrichWyTracks(it) }
            else -> null
        }
    }

    /**
     * QQ 歌单：旧 Qzone 接口对部分歌单返回 subcode=4000「check privacy error!」（无 cdlist），
     * 与网络无关、重试也没用。此时兜底 lx-music-desktop 同款 musicu.fcg uniform_get_Dissinfo，
     * 匿名即可取回完整 songlist（字段结构与旧接口一致，复用同一套解析）。
     */
    private fun fetchTxSheetTracks(sheetId: String): List<RemoteTrack>? {
        val oldBody = httpGet(
            "https://i.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=${enc(sheetId)}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
            TX_PLAYLIST_HEADERS
        )
        oldBody?.let(::parseTxSheetTracks)?.let { return it }
        val disstid = sheetId.toLongOrNull() ?: return null
        val body = httpPostJson(
            url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("cv", 4747474)
                    put("ct", 24)
                    put("format", "json")
                    put("inCharset", "utf-8")
                    put("outCharset", "utf-8")
                    put("platform", "yqq.json")
                    put("needNewCode", 1)
                    put("uin", 0)
                })
                put("req_1", JSONObject().apply {
                    put("module", "music.srfDissInfo.aiDissInfo")
                    put("method", "uniform_get_Dissinfo")
                    put("param", JSONObject().apply {
                        put("disstid", disstid)
                        put("userinfo", 1)
                        put("tag", 1)
                        put("orderlist", 1)
                        put("song_begin", 0)
                        put("song_num", 100000)
                        put("onlysonglist", 0)
                        put("enc_host_uin", "")
                    })
                })
            }.toString(),
            headers = mapOf(
                "Origin" to "https://y.qq.com",
                "Referer" to "https://y.qq.com/n/yqq/playsquare/$sheetId.html"
            )
        ) ?: return null
        return parseTxSheetTracksUniform(body)
    }

    suspend fun fetchLyric(
        platform: String,
        remoteId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L
    ): String? = withContext(Dispatchers.IO) {
        when (platform) {
            "kw" -> {
                val body = httpGet("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${enc(remoteId)}&httpStatus=1")
                    ?: return@withContext null
                parseKuwoLyricAndCover(body).first.takeIf { it.isNotBlank() }
            }
            "tx" -> {
                val mid = LxRemoteId.parseTx(remoteId).songmid
                if (mid.isBlank()) return@withContext null
                val body = httpGet(
                    "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(mid)}&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0",
                    TX_HEADERS
                ) ?: return@withContext null
                val data = parseTxLyric(body) ?: return@withContext null
                val lrc = data.lrc.trim()
                if (lrc.isEmpty()) return@withContext null
                val trans = data.tlyric.trim()
                if (trans.isNotEmpty()) mergeTranslation(lrc, trans) else lrc
            }
            "kg" -> {
                val hash = LxRemoteId.parseKg(remoteId).hash
                val keyword = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
                val searchUrl =
                    "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(keyword)}&hash=${enc(hash)}&timelength=${durationMs.coerceAtLeast(0L)}"
                val searchBody = httpGet(searchUrl, KG_LYRIC_HEADERS) ?: return@withContext null
                val candidate = parseKgLyricCandidate(searchBody) ?: return@withContext null
                val dlUrl =
                    "http://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(candidate.first)}&accesskey=${enc(candidate.second)}&fmt=lrc&charset=utf8"
                val dlBody = httpGet(dlUrl, KG_LYRIC_HEADERS) ?: return@withContext null
                parseKgLyricDownload(dlBody)?.takeIf { it.isNotBlank() }
            }
            "wy" -> NeteaseSource.lyrics(remoteId, withTranslation = true)
            else -> null
        }
    }

    suspend fun fetchCoverUrl(platform: String, remoteId: String): String? = withContext(Dispatchers.IO) {
        when (platform) {
            "kw" -> {
                val body = httpGet("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${enc(remoteId)}&httpStatus=1")
                    ?: return@withContext null
                parseKuwoLyricAndCover(body).second
            }
            "tx" -> normalizeQqAlbumCoverUrl(LxRemoteId.parseTx(remoteId).albumMid)
            "wy" -> NeteaseSource.songDetail(remoteId)?.coverUrl
            "kg" -> null
            else -> null
        }
    }

    // ==================== 内部各平台网络封装 ====================

    private fun searchKuwoSongs(query: String, limit: Int, offset: Int): RemoteSearchPage? {
        val pn = (offset / limit.coerceAtLeast(1)).coerceAtLeast(0)
        val url =
            "http://search.kuwo.cn/r.s?client=kt&all=${enc(query)}&pn=$pn&rn=$limit&uid=2574109560&ver=kwplayer_ar_8.5.4.2&vipver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1"
        val body = httpGet(url) ?: return null
        return parseKuwoSongSearch(body)
    }

    private fun searchKuwoSheets(query: String, limit: Int, offset: Int): LxSheetPage? {
        val pn = (offset / limit.coerceAtLeast(1)).coerceAtLeast(0)
        val url =
            "http://search.kuwo.cn/r.s?all=${enc(query)}&ft=playlist&itemset=web_2013&client=kt&pn=$pn&rn=$limit&rformat=json&encoding=utf8&pcjson=1"
        val body = httpGet(url) ?: return null
        return parseKuwoSheetSearch(body)
    }

    private fun searchTxSongs(query: String, limit: Int, offset: Int): RemoteSearchPage? {
        val page = (offset / limit.coerceAtLeast(1)) + 1
        val body = httpPostJson(
            url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body = buildTxSearchPayload(query, searchType = 0, limit = limit, page = page),
            headers = TX_HEADERS
        ) ?: return null
        return parseTxSongSearch(body)
    }

    private fun searchTxSheets(query: String, limit: Int, offset: Int): LxSheetPage? {
        val page = (offset / limit.coerceAtLeast(1)) + 1
        val body = httpPostJson(
            url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body = buildTxSearchPayload(query, searchType = 3, limit = limit, page = page),
            headers = TX_HEADERS
        ) ?: return null
        return parseTxSheetSearch(body)
    }

    private fun searchKgSongs(query: String, limit: Int, offset: Int): RemoteSearchPage? {
        val page = (offset / limit.coerceAtLeast(1)) + 1
        val url =
            "https://songsearch.kugou.com/song_search_v2?keyword=${enc(query)}&page=$page&pagesize=$limit&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        val body = httpGet(url) ?: return null
        return parseKgSongSearch(body)
    }

    private fun searchKgSheets(query: String, limit: Int, offset: Int): LxSheetPage? {
        val page = (offset / limit.coerceAtLeast(1)) + 1
        val url =
            "http://mobilecdn.kugou.com/api/v3/search/special?format=json&keyword=${enc(query)}&page=$page&pagesize=$limit&showtype=1"
        val body = httpGet(url) ?: return null
        return parseKgSheetSearch(body)
    }

    private fun buildTxSearchPayload(query: String, searchType: Int, limit: Int, page: Int): String =
        JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", 19)
                put("cv", 1859)
                put("uin", "0")
            })
            put("req", JSONObject().apply {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                put("param", JSONObject().apply {
                    put("grp", 1)
                    put("num_per_page", limit)
                    put("page_num", page)
                    put("query", query)
                    put("search_type", searchType)
                })
            })
        }.toString()

    // ==================== 版权 / 收费状态解析 ====================

    internal fun parseKuwoTrackStatus(o: JSONObject): LxTrackStatus {
        val online = o.optString("ONLINE").ifBlank { o.optString("online") }.trim()
        val disable = o.optString("disable").trim()
        val payInfo = o.optJSONObject("payInfo")
        val cannotOnline = payInfo?.optString("cannotOnlinePlay")?.trim().orEmpty()
        val tipsIntercept = payInfo?.optString("tips_intercept")?.trim().orEmpty()
        val playMask = payInfo?.optString("play")?.trim().orEmpty()
        // 注意：酷我榜单行里合法可播曲目也可能返回 copyright="0"，绝不能用它判无版权。
        if (
            online == "0" ||
            disable == "1" ||
            cannotOnline == "1" ||
            tipsIntercept == "1" ||
            (playMask.isNotEmpty() && playMask.all { it == '0' })
        ) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "无版权")
        }
        val feeType = payInfo?.optJSONObject("feeType") ?: o.optJSONObject("feeType")
        val songFee = feeType?.optString("song")?.trim() == "1"
        val vipFee = feeType?.optString("vip")?.trim() == "1"
        val albumFee = feeType?.optString("album")?.trim() == "1"
        val payStr = o.optString("pay").ifBlank { o.optString("paytype") }.trim()
        val payNum = payStr.toIntOrNull() ?: 0
        val tpay = o.optString("tpay").trim() == "1"
        val fpay = o.optString("fpay").trim() == "1"
        if (albumFee && !songFee && (!vipFee || payNum < 0)) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "数专")
        }
        if (songFee || payNum > 0 || tpay || fpay) {
            return LxTrackStatus(playable = true, vip = true)
        }
        return LxTrackStatus(playable = true, vip = false)
    }

    internal fun parseTxTrackStatus(o: JSONObject): LxTrackStatus {
        val status = o.optInt("status", 0)
        val switchBits = o.optJSONObject("action")?.optLong("switch", -1L) ?: -1L
        val actionDisabled = switchBits >= 0L && (switchBits and 1L) == 0L
        if (status != 0 || actionDisabled) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "无版权")
        }
        val pay = o.optJSONObject("pay")
        val payPlay = (pay?.optInt("pay_play", 0) ?: 0) == 1 || (pay?.optInt("payplay", 0) ?: 0) == 1
        val payMonth = (pay?.optInt("pay_month", 0) ?: 0) == 1 || (pay?.optInt("paymonth", 0) ?: 0) == 1
        val priceAlbum = pay?.optInt("price_album", 0) ?: pay?.optInt("pricealbum", 0) ?: 0
        if (payPlay && !payMonth && priceAlbum > 0) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "数专")
        }
        if (payPlay || payMonth) {
            return LxTrackStatus(playable = true, vip = true)
        }
        return LxTrackStatus(playable = true, vip = false)
    }

    internal fun parseKgTrackStatus(o: JSONObject): LxTrackStatus {
        val privilege = optIntAny(o, "Privilege", "privilege", 0)
        val payType = optIntAny(o, "PayType", "pay_type", 0)
        val price = optIntAny(o, "Price", "price", 0)
        val pkgPrice = optIntAny(o, "PkgPrice", "pkg_price", 0)
        if (privilege == 5) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "无版权")
        }
        if (payType == 2 || (payType == 3 && price > 0 && pkgPrice == 0 && privilege >= 8)) {
            return LxTrackStatus(playable = false, vip = false, unavailableReason = "数专")
        }
        if (privilege >= 8 || payType == 3 || pkgPrice > 0) {
            return LxTrackStatus(playable = true, vip = true)
        }
        return LxTrackStatus(playable = true, vip = false)
    }

    internal fun parseWySongDetailStatuses(raw: String): Map<String, LxTrackStatus> = runCatching {
        val root = JSONObject(raw)
        val songsById = HashMap<String, JSONObject>()
        val privById = HashMap<String, JSONObject>()
        val songsArr = root.optJSONArray("songs")
        if (songsArr != null) {
            for (i in 0 until songsArr.length()) {
                val s = songsArr.optJSONObject(i) ?: continue
                val id = s.optLong("id")
                if (id > 0L) songsById[id.toString()] = s
            }
        }
        val privArr = root.optJSONArray("privileges")
        if (privArr != null) {
            for (i in 0 until privArr.length()) {
                val p = privArr.optJSONObject(i) ?: continue
                val id = p.optLong("id")
                if (id > 0L) privById[id.toString()] = p
            }
        }
        val allIds = LinkedHashSet<String>(songsById.keys).apply { addAll(privById.keys) }
        buildMap(allIds.size) {
            for (id in allIds) {
                val songObj = songsById[id]
                val privObj = privById[id]
                val noCopyright = songObj != null && !songObj.isNull("noCopyrightRcmd")
                val st = privObj?.optInt("st", 0) ?: 0
                val fee = privObj?.optInt("fee", -1)?.takeIf { it >= 0 }
                    ?: (songObj?.optInt("fee", 0) ?: 0)
                val status = when {
                    noCopyright || st < 0 -> LxTrackStatus(playable = false, vip = false, unavailableReason = "无版权")
                    fee == 4 -> LxTrackStatus(playable = false, vip = false, unavailableReason = "数专")
                    fee == 1 -> LxTrackStatus(playable = true, vip = true)
                    else -> LxTrackStatus(playable = true, vip = false)
                }
                put(id, status)
            }
        }
    }.getOrDefault(emptyMap())

    private fun optIntAny(o: JSONObject, primary: String, fallback: String, default: Int = 0): Int = when {
        o.has(primary) -> o.optInt(primary, default)
        o.has(fallback) -> o.optInt(fallback, default)
        else -> default
    }

    /**
     * 网易云 `/api/search/get` 不返 `privileges.st`，会把已下架老歌（如部分停权曲目）
     * 当成 `fee=0` 放行；这里批量打 `/api/v3/song/detail` 一次补齐真实权益（st / fee / noCopyrightRcmd）。
     */
    private fun enrichWyTracks(tracks: List<RemoteTrack>): List<RemoteTrack> {
        if (tracks.isEmpty()) return tracks
        val statuses = HashMap<String, LxTrackStatus>(tracks.size)
        val numericIds = tracks.mapNotNull { t -> t.remoteId.toLongOrNull()?.takeIf { it > 0L } }.distinct()
        for (chunk in numericIds.chunked(100)) {
            val cArr = JSONArray()
            chunk.forEach { id -> cArr.put(JSONObject().put("id", id)) }
            val body = httpGet("https://music.163.com/api/v3/song/detail?c=${enc(cArr.toString())}", WY_HEADERS)
            if (body != null) {
                statuses.putAll(parseWySongDetailStatuses(body))
            }
        }
        return tracks.map { t ->
            val st = statuses[t.remoteId]
            if (st != null) {
                t.copy(
                    playable = st.playable,
                    vip = st.vip,
                    unavailableReason = st.unavailableReason
                )
            } else if (t.vip) {
                // 详情请求偶发超时时的兜底：洛雪脚本可尝试解 VIP，但无版权/数专仍不放行
                t.copy(playable = true, vip = true, unavailableReason = null)
            } else if (!t.playable) {
                t.copy(unavailableReason = t.unavailableReason ?: "无版权")
            } else {
                t
            }
        }
    }

    // ==================== 纯解析函数（internal 供 JVM 单测覆盖）====================

    internal fun parseKuwoSongSearch(raw: String): RemoteSearchPage? = runCatching {
        val obj = JSONObject(raw)
        val total = obj.optString("TOTAL").toIntOrNull() ?: obj.optInt("TOTAL", -1)
        val arr = obj.optJSONArray("abslist") ?: JSONArray()
        val list = ArrayList<RemoteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rawId = o.optString("MUSICRID").ifBlank { o.optString("DC_TARGETID") }
            val rid = rawId.removePrefix("MUSIC_").trim()
            if (rid.isEmpty()) continue
            val pic = normalizeKuwoCoverUrl(
                o.optString("web_albumpic_short").ifBlank { o.optString("web_artistpic_short") }
            )
            val status = parseKuwoTrackStatus(o)
            list += RemoteTrack(
                remoteId = LxRemoteId.encodeKw(rid),
                title = decodeHtmlEntities(o.optString("SONGNAME").ifBlank { o.optString("NAME") }),
                artist = cleanKuwoArtists(o.optString("ARTIST")),
                album = decodeHtmlEntities(o.optString("ALBUM")),
                durationMs = o.optLong("DURATION", 0L).coerceAtLeast(0L) * 1000L,
                coverUrl = pic,
                playable = status.playable,
                vip = status.vip,
                unavailableReason = status.unavailableReason
            )
        }
        RemoteSearchPage(list, total)
    }.getOrNull()

    internal fun parseKuwoSheetSearch(raw: String): LxSheetPage? = runCatching {
        val obj = JSONObject(raw)
        val total = obj.optString("TOTAL").toIntOrNull() ?: obj.optInt("TOTAL", -1)
        val arr = obj.optJSONArray("abslist") ?: JSONArray()
        val list = ArrayList<LxSheetSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("playlistid").ifBlank { o.optString("id") }.trim()
            if (id.isEmpty()) continue
            list += LxSheetSummary(
                id = id,
                platform = "kw",
                name = decodeHtmlEntities(o.optString("name")),
                coverUrl = normalizeKuwoCoverUrl(o.optString("pic")),
                creatorName = decodeHtmlEntities(o.optString("nickname")),
                trackCount = o.optString("songnum").toIntOrNull() ?: o.optInt("songnum", 0)
            )
        }
        LxSheetPage(list, total)
    }.getOrNull()

    internal fun parseKuwoToplists(raw: String): List<LxTopListGroup>? = runCatching {
        val arr = JSONObject(raw).optJSONArray("child") ?: return@runCatching null
        val groups = ArrayList<LxTopListGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val groupName = decodeHtmlEntities(g.optString("name")).ifBlank { "排行榜" }
            val items = g.optJSONArray("child") ?: continue
            val boards = ArrayList<LxBoard>(items.length())
            for (j in 0 until items.length()) {
                val b = items.optJSONObject(j) ?: continue
                val id = b.optString("sourceid").ifBlank { b.optString("id") }.trim()
                if (id.isEmpty()) continue
                val pic = normalizeKuwoCoverUrl(
                    b.optString("pic5")
                        .ifBlank { b.optString("pic2") }
                        .ifBlank { b.optString("pic") }
                )
                boards += LxBoard(
                    id = id,
                    name = decodeHtmlEntities(b.optString("name")),
                    platform = "kw",
                    coverUrl = pic,
                    intro = decodeHtmlEntities(b.optString("intro"))
                )
            }
            if (boards.isNotEmpty()) groups += LxTopListGroup(groupName, boards)
        }
        groups
    }.getOrNull()

    internal fun parseKuwoBoardTracks(raw: String): List<RemoteTrack>? = runCatching {
        val arr = JSONObject(raw).optJSONArray("musiclist") ?: return@runCatching null
        parseKuwoMusicListArray(arr)
    }.getOrNull()

    internal fun parseKuwoSheetTags(raw: String): List<LxSheetTag> = runCatching {
        val arr = JSONObject(raw).optJSONArray("data") ?: return@runCatching emptyList()
        val tags = ArrayList<LxSheetTag>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val groupName = decodeHtmlEntities(g.optString("name"))
            val inner = g.optJSONArray("data") ?: continue
            for (j in 0 until inner.length()) {
                val t = inner.optJSONObject(j) ?: continue
                val id = t.optString("id").trim()
                val name = decodeHtmlEntities(t.optString("name"))
                val digest = t.optString("digest", "10000")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    tags += LxSheetTag(id = id, name = name, digest = digest, group = groupName)
                }
            }
        }
        tags
    }.getOrDefault(emptyList())

    internal fun parseKuwoRcmSheets(raw: String): List<LxSheetSummary>? = runCatching {
        val root = JSONObject(raw)
        val dataObj = root.optJSONObject("data") ?: return@runCatching null
        val arr = dataObj.optJSONArray("data") ?: return@runCatching null
        val list = ArrayList<LxSheetSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            list += LxSheetSummary(
                id = id,
                platform = "kw",
                name = decodeHtmlEntities(o.optString("name")),
                coverUrl = normalizeKuwoCoverUrl(o.optString("img").ifBlank { o.optString("pic") }),
                creatorName = decodeHtmlEntities(o.optString("uname")),
                trackCount = o.optString("total").toIntOrNull() ?: o.optInt("total", 0),
                desc = decodeHtmlEntities(o.optString("desc"))
            )
        }
        list
    }.getOrNull()

    internal fun parseKuwoQzSheets(raw: String): List<LxSheetSummary>? = runCatching {
        val trimmed = raw.trim()
        val sections: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")
                    ?: root.optJSONArray("list")?.let { JSONArray().put(root) }
                    ?: return@runCatching null
            }
            else -> return@runCatching null
        }
        val list = ArrayList<LxSheetSummary>()
        val seenIds = HashSet<String>()
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val items = sec.optJSONArray("list") ?: continue
            for (j in 0 until items.length()) {
                val o = items.optJSONObject(j) ?: continue
                val id = o.optString("id")
                    .ifBlank { o.optString("pid") }
                    .ifBlank { o.optString("playlistid") }
                    .trim()
                if (id.isEmpty() || !seenIds.add(id)) continue
                val name = decodeHtmlEntities(o.optString("name").ifBlank { o.optString("title") })
                if (name.isBlank()) continue
                list += LxSheetSummary(
                    id = id,
                    platform = "kw",
                    name = name,
                    coverUrl = normalizeKuwoCoverUrl(o.optString("img").ifBlank { o.optString("pic") }),
                    creatorName = decodeHtmlEntities(o.optString("uname").ifBlank { o.optString("info") }),
                    trackCount = o.optString("total").toIntOrNull()
                        ?: o.optInt("total", 0).takeIf { it > 0 }
                        ?: (o.optString("listencnt").toIntOrNull() ?: 0),
                    desc = decodeHtmlEntities(o.optString("desc").ifBlank { o.optString("info") })
                )
            }
        }
        list
    }.getOrNull()

    internal fun parseKuwoSheetTracks(raw: String): List<RemoteTrack>? = runCatching {
        val arr = JSONObject(raw).optJSONArray("musiclist") ?: return@runCatching null
        parseKuwoMusicListArray(arr)
    }.getOrNull()

    internal fun parseKuwoLyricAndCover(raw: String): Pair<String, String?> = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching "" to null
        val lrcArr = data.optJSONArray("lrclist")
        val pairs = ArrayList<Pair<String, String>>(lrcArr?.length() ?: 0)
        if (lrcArr != null) {
            for (i in 0 until lrcArr.length()) {
                val item = lrcArr.optJSONObject(i) ?: continue
                pairs += item.optString("time") to item.optString("lineLyric")
            }
        }
        val pic = normalizeKuwoCoverUrl(data.optJSONObject("songinfo")?.optString("pic"))
        formatKuwoLrcList(pairs) to pic
    }.getOrDefault("" to null)

    private fun parseKuwoMusicListArray(arr: JSONArray): List<RemoteTrack> {
        val list = ArrayList<RemoteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optString("id").ifBlank { o.optString("musicrid") }.removePrefix("MUSIC_").trim()
            if (rid.isEmpty()) continue
            val pic = normalizeKuwoCoverUrl(
                o.optString("albumpic").ifBlank { o.optString("pic") }
            )
            val status = parseKuwoTrackStatus(o)
            list += RemoteTrack(
                remoteId = LxRemoteId.encodeKw(rid),
                title = decodeHtmlEntities(o.optString("name").ifBlank { o.optString("songName") }),
                artist = cleanKuwoArtists(o.optString("artist")),
                album = decodeHtmlEntities(o.optString("album")),
                durationMs = o.optLong("duration", 0L).coerceAtLeast(0L) * 1000L,
                coverUrl = pic,
                playable = status.playable,
                vip = status.vip,
                unavailableReason = status.unavailableReason
            )
        }
        return list
    }

    internal fun parseTxSongSearch(raw: String): RemoteSearchPage? = runCatching {
        val data = JSONObject(raw).optJSONObject("req")?.optJSONObject("data") ?: return@runCatching null
        val total = data.optJSONObject("meta")?.optInt("sum", -1) ?: -1
        val arr = data.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
            ?: return@runCatching null
        RemoteSearchPage(parseTxSongListArray(arr), total)
    }.getOrNull()

    internal fun parseTxSheetSearch(raw: String): LxSheetPage? = runCatching {
        val data = JSONObject(raw).optJSONObject("req")?.optJSONObject("data") ?: return@runCatching null
        val total = data.optJSONObject("meta")?.optInt("sum", -1) ?: -1
        val arr = data.optJSONObject("body")?.optJSONObject("songlist")?.optJSONArray("list")
            ?: return@runCatching null
        val list = ArrayList<LxSheetSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("dissid").trim()
            if (id.isEmpty()) continue
            val creator = o.optJSONObject("creator")
            val creatorName = creator?.optString("name").orEmpty()
                .ifBlank { creator?.optString("nick").orEmpty() }
            list += LxSheetSummary(
                id = id,
                platform = "tx",
                name = decodeHtmlEntities(o.optString("dissname")),
                coverUrl = o.optString("imgurl").takeIf { it.startsWith("http") },
                creatorName = decodeHtmlEntities(creatorName),
                trackCount = o.optInt("song_count", 0),
                desc = decodeHtmlEntities(o.optString("introduction"))
            )
        }
        LxSheetPage(list, total)
    }.getOrNull()

    internal fun parseTxToplists(raw: String): List<LxTopListGroup>? = runCatching {
        val groupsArr = JSONObject(raw)
            .optJSONObject("req")?.optJSONObject("data")?.optJSONArray("group")
            ?: return@runCatching null
        val groups = ArrayList<LxTopListGroup>(groupsArr.length())
        for (i in 0 until groupsArr.length()) {
            val g = groupsArr.optJSONObject(i) ?: continue
            val gName = decodeHtmlEntities(g.optString("groupName")).ifBlank { "排行榜" }
            val arr = g.optJSONArray("toplist") ?: continue
            val boards = ArrayList<LxBoard>(arr.length())
            for (j in 0 until arr.length()) {
                val b = arr.optJSONObject(j) ?: continue
                val topId = b.optInt("topId", 0)
                if (topId <= 0) continue
                val pic = b.optString("headPicUrl")
                    .ifBlank { b.optString("frontPicUrl") }
                    .ifBlank { b.optString("mbFrontPicUrl") }
                    .takeIf { it.startsWith("http") }
                boards += LxBoard(
                    id = topId.toString(),
                    name = decodeHtmlEntities(b.optString("title").ifBlank { b.optString("label") }),
                    platform = "tx",
                    coverUrl = pic,
                    intro = decodeHtmlEntities(b.optString("intro")),
                    period = b.optString("period")
                )
            }
            if (boards.isNotEmpty()) groups += LxTopListGroup(gName, boards)
        }
        groups
    }.getOrNull()

    internal fun parseTxBoardTracks(raw: String): List<RemoteTrack>? = runCatching {
        val reqData = JSONObject(raw).optJSONObject("req")?.optJSONObject("data") ?: return@runCatching null
        val songList = reqData.optJSONObject("data")?.optJSONArray("songInfoList")
            ?: reqData.optJSONArray("songInfoList")
            ?: return@runCatching null
        parseTxSongListArray(songList)
    }.getOrNull()

    internal fun parseTxSheetTags(raw: String): List<LxSheetTag> = runCatching {
        val cats = JSONObject(stripJsonpCallback(raw))
            .optJSONObject("data")?.optJSONArray("categories")
            ?: return@runCatching emptyList()
        val tags = ArrayList<LxSheetTag>()
        for (i in 0 until cats.length()) {
            val g = cats.optJSONObject(i) ?: continue
            val gName = decodeHtmlEntities(g.optString("categoryGroupName"))
            val items = g.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (item.optInt("usable", 1) == 0) continue
                val id = item.optInt("categoryId", -1)
                val name = decodeHtmlEntities(item.optString("categoryName"))
                if (id > 0 && name.isNotEmpty()) {
                    tags += LxSheetTag(id = id.toString(), name = name, digest = "tx", group = gName)
                }
            }
        }
        tags
    }.getOrDefault(emptyList())

    internal fun parseTxTagSheets(raw: String): List<LxSheetSummary>? = runCatching {
        val arr = JSONObject(stripJsonpCallback(raw))
            .optJSONObject("data")?.optJSONArray("list")
            ?: return@runCatching null
        val list = ArrayList<LxSheetSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("dissid").trim()
            if (id.isEmpty()) continue
            list += LxSheetSummary(
                id = id,
                platform = "tx",
                name = decodeHtmlEntities(o.optString("dissname")),
                coverUrl = o.optString("imgurl").takeIf { it.startsWith("http") },
                creatorName = decodeHtmlEntities(o.optJSONObject("creator")?.optString("name").orEmpty()),
                trackCount = 0
            )
        }
        list
    }.getOrNull()

    internal fun parseTxSheetTracks(raw: String): List<RemoteTrack>? = runCatching {
        val cdList = JSONObject(stripJsonpCallback(raw)).optJSONArray("cdlist") ?: return@runCatching null
        val first = cdList.optJSONObject(0) ?: return@runCatching null
        val songList = first.optJSONArray("songlist") ?: return@runCatching null
        parseTxSongListArray(songList)
    }.getOrNull()

    /** musicu.fcg uniform_get_Dissinfo 兜底响应：req_1.data.songlist 与旧接口同构 */
    internal fun parseTxSheetTracksUniform(raw: String): List<RemoteTrack>? = runCatching {
        val req = JSONObject(stripJsonpCallback(raw)).optJSONObject("req_1") ?: return@runCatching null
        if (req.optInt("code", -1) != 0) return@runCatching null
        val songList = req.optJSONObject("data")?.optJSONArray("songlist") ?: return@runCatching null
        parseTxSongListArray(songList)
    }.getOrNull()

    internal fun parseTxLyric(raw: String): LxLyricData? = runCatching {
        val obj = JSONObject(stripJsonpCallback(raw))
        val lrcB64 = obj.optString("lyric")
        if (lrcB64.isBlank()) return@runCatching null
        val lrc = decodeBase64Utf8(lrcB64)
        val trans = obj.optString("trans").takeIf { it.isNotBlank() }?.let(::decodeBase64Utf8).orEmpty()
        LxLyricData(lrc = decodeHtmlEntities(lrc), tlyric = decodeHtmlEntities(trans))
    }.getOrNull()

    internal fun parseTxSongListArray(arr: JSONArray): List<RemoteTrack> {
        val list = ArrayList<RemoteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("mid").ifBlank { o.optString("songmid") }.trim()
            if (mid.isEmpty()) continue
            val songId = o.optString("id").ifBlank { o.optString("songid") }.trim()
            val mediaMid = o.optJSONObject("file")?.optString("media_mid").orEmpty()
                .ifBlank { o.optString("strMediaMid") }
            val albumObj = o.optJSONObject("album")
            val albumMid = albumObj?.optString("mid").orEmpty()
                .ifBlank { o.optString("albummid") }
            val albumName = albumObj?.optString("name").orEmpty()
                .ifBlank { albumObj?.optString("title").orEmpty() }
                .ifBlank { o.optString("albumname") }
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { o.optString("songname") }
            val singers = joinTxSingers(o.optJSONArray("singer"))
            val interval = o.optLong("interval", 0L).coerceAtLeast(0L)
            val status = parseTxTrackStatus(o)
            list += RemoteTrack(
                remoteId = LxRemoteId.encodeTx(mid, mediaMid, albumMid, songId),
                title = decodeHtmlEntities(title),
                artist = decodeHtmlEntities(singers),
                album = decodeHtmlEntities(albumName),
                durationMs = interval * 1000L,
                coverUrl = normalizeQqAlbumCoverUrl(albumMid),
                playable = status.playable,
                vip = status.vip,
                unavailableReason = status.unavailableReason
            )
        }
        return list
    }

    internal fun parseKgSongSearch(raw: String): RemoteSearchPage? = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching null
        val total = data.optInt("total", -1)
        val arr = data.optJSONArray("lists") ?: return@runCatching null
        val list = ArrayList<RemoteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hash = o.optString("FileHash").trim()
            if (hash.isEmpty()) continue
            val albumId = o.optString("AlbumID").trim()
            val songName = cleanKgField(o.optString("SongName"))
            val singerName = cleanKgField(o.optString("SingerName"))
            val fallbackSplit = if (songName.isBlank() || singerName.isBlank()) {
                splitKugouFilename(cleanKgField(o.optString("FileName")))
            } else null
            val status = parseKgTrackStatus(o)
            list += RemoteTrack(
                remoteId = LxRemoteId.encodeKg(hash, albumId),
                title = songName.ifBlank { fallbackSplit?.second.orEmpty() },
                artist = singerName.ifBlank { fallbackSplit?.first.orEmpty() },
                album = cleanKgField(o.optString("AlbumName")),
                durationMs = o.optLong("Duration", 0L).coerceAtLeast(0L) * 1000L,
                coverUrl = normalizeKugouCoverUrl(o.optString("Image")),
                playable = status.playable,
                vip = status.vip,
                unavailableReason = status.unavailableReason
            )
        }
        RemoteSearchPage(list, total)
    }.getOrNull()

    internal fun parseKgSheetSearch(raw: String): LxSheetPage? = runCatching {
        val data = JSONObject(raw).optJSONObject("data") ?: return@runCatching null
        val total = data.optInt("total", -1)
        val arr = data.optJSONArray("info") ?: return@runCatching null
        val list = ArrayList<LxSheetSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val gid = o.optString("gid").trim()
            val specialId = o.optString("specialid").trim()
            val positiveSpecial = specialId.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }.orEmpty()
            val id = gid.ifBlank { positiveSpecial }
            if (id.isEmpty()) continue
            list += LxSheetSummary(
                id = id,
                platform = "kg",
                name = cleanKgField(o.optString("specialname")),
                coverUrl = normalizeKugouCoverUrl(o.optString("imgurl")),
                creatorName = cleanKgField(o.optString("nickname")),
                trackCount = o.optInt("songcount", 0),
                desc = cleanKgField(o.optString("intro"))
            )
        }
        LxSheetPage(list, total)
    }.getOrNull()

    internal fun parseKgToplists(raw: String): List<LxTopListGroup>? = runCatching {
        val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return@runCatching null
        val boards = ArrayList<LxBoard>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("rankid").trim()
            if (id.isEmpty()) continue
            val pic = normalizeKugouCoverUrl(
                o.optString("imgurl").ifBlank { o.optString("banner7url") }
            )
            boards += LxBoard(
                id = id,
                name = o.optString("rankname"),
                platform = "kg",
                coverUrl = pic,
                intro = o.optString("intro")
            )
        }
        if (boards.isEmpty()) emptyList() else listOf(LxTopListGroup("酷狗音乐榜", boards))
    }.getOrNull()

    internal fun parseKgBoardTracks(raw: String): List<RemoteTrack>? = runCatching {
        val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return@runCatching null
        parseKgTrackInfoArray(arr)
    }.getOrNull()

    internal fun parseKgSheetTracks(raw: String): List<RemoteTrack>? = runCatching {
        val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("info") ?: return@runCatching null
        parseKgTrackInfoArray(arr)
    }.getOrNull()

    internal fun parseKgLyricCandidate(raw: String): Pair<String, String>? = runCatching {
        val arr = JSONObject(raw).optJSONArray("candidates") ?: return@runCatching null
        val first = arr.optJSONObject(0) ?: return@runCatching null
        val id = first.optString("id").trim()
        val key = first.optString("accesskey").trim()
        if (id.isEmpty() || key.isEmpty()) null else id to key
    }.getOrNull()

    internal fun parseKgLyricDownload(raw: String): String? = runCatching {
        val b64 = JSONObject(raw).optString("content")
        if (b64.isBlank()) null else decodeBase64Utf8(b64)
    }.getOrNull()

    internal fun parseKgTrackInfoArray(arr: JSONArray): List<RemoteTrack> {
        val list = ArrayList<RemoteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hash = o.optString("hash").trim()
            if (hash.isEmpty()) continue
            val albumInfo = o.optJSONObject("albuminfo")
            val albumId = o.optString("album_id").trim()
                .ifBlank { albumInfo?.optString("id")?.trim().orEmpty() }
            val rawName = o.optString("filename").ifBlank { o.optString("name") }
            val (splitArtist, title) = splitKugouFilename(rawName)
            val artist = splitArtist.ifBlank { joinTxSingers(o.optJSONArray("singerinfo")) }
            val coverRaw = o.optString("album_sizable_cover")
                .ifBlank { o.optString("cover") }
                .ifBlank { o.optJSONObject("trans_param")?.optString("union_cover").orEmpty() }
            val albumName = albumInfo?.optString("name").orEmpty()
                .ifBlank { o.optString("album_name") }
                .ifBlank { o.optString("remark") }
            val durationMs = if (o.optLong("timelen", 0L) > 0L) {
                o.optLong("timelen")
            } else {
                o.optLong("duration", 0L).coerceAtLeast(0L) * 1000L
            }
            val status = parseKgTrackStatus(o)
            list += RemoteTrack(
                remoteId = LxRemoteId.encodeKg(hash, albumId),
                title = title,
                artist = artist,
                album = cleanKgField(albumName),
                durationMs = durationMs,
                coverUrl = normalizeKugouCoverUrl(coverRaw),
                playable = status.playable,
                vip = status.vip,
                unavailableReason = status.unavailableReason
            )
        }
        return list
    }

    internal fun splitKugouFilename(filename: String): Pair<String, String> {
        val clean = filename.trim()
        val sep = clean.indexOf(" - ")
        return if (sep > 0 && sep + 3 < clean.length) {
            clean.substring(0, sep).trim() to clean.substring(sep + 3).trim()
        } else {
            "" to clean
        }
    }

    private fun cleanKuwoArtists(raw: String): String {
        val decoded = decodeHtmlEntities(raw).trim()
        return if ('&' in decoded) decoded.replace("&", " / ") else decoded
    }

    internal fun cleanKgField(raw: String): String =
        decodeHtmlEntities(HTML_TAG_REGEX.replace(raw, "")).trim()

    private fun joinTxSingers(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        return buildString {
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (n.isEmpty()) continue
                if (isNotEmpty()) append(" / ")
                append(n)
            }
        }
    }

    private fun decodeBase64Utf8(b64: String): String {
        val clean = b64.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ""
        val out = ByteArrayOutputStream((clean.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+' -> 62
                '/' -> 63
                else -> continue
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? =
        executeHttp(url = url, method = "GET", body = null, headers = headers)

    private fun httpPostJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? =
        executeHttp(url = url, method = "POST", body = body, headers = headers + ("Content-Type" to "application/json; charset=utf-8"))

    private fun executeHttp(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DEFAULT_BROWSER_UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val TX_HEADERS = mapOf(
        "Referer" to "https://y.qq.com/",
        "Cookie" to "uin=0"
    )

    private val TX_PLAYLIST_HEADERS = mapOf(
        "Referer" to "https://y.qq.com/n/yqq/playlist",
        "Cookie" to "uin=0"
    )

    private val WY_HEADERS = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "os=pc"
    )

    private val KG_LYRIC_HEADERS = mapOf(
        "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        "KG-RC" to "1"
    )

    private val KG_PRESET_TAGS = listOf("热门", "华语", "怀旧", "流行", "欧美", "轻音乐", "摇滚", "古风")
        .map { LxSheetTag(id = it, name = it, digest = "kg") }

    private val WY_PRESET_TAGS = listOf("全部", "华语", "流行", "摇滚", "民谣", "电子", "轻音乐", "古风")
        .map { LxSheetTag(id = it, name = it, digest = "wy") }
}
