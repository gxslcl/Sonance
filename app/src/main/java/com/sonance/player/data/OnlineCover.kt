package com.sonance.player.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 一次搜索返回的一个候选封面 */
data class CoverCandidate(
    val trackName: String,
    val artistName: String,
    val collectionName: String,
    /** 列表展示用（小图） */
    val thumbUrl: String,
    /** 实际写入 / 显示用（大图） */
    val fullUrl: String,
    /** 来自哪个源（"iTunes" / "网易云"），只用于界面上说明一句 */
    val source: String = ""
)

/**
 * 在线封面（默认关闭）。
 *
 * 用的是 **iTunes Search API**：
 *   GET https://itunes.apple.com/search?term=...&entity=song&limit=N
 * 免鉴权、免 key，一次返回多条带 `artworkUrl100` 的结果 —— 正好满足
 * 「搜到好几个让用户自己挑」。图片尺寸可以靠改 URL 里的 `100x100bb` 拿到 600 / 1200。
 *
 * 调研过的其它选择（为什么没用）：
 * - **Deezer** `api.deezer.com` —— 本机连不通
 * - **Last.fm** —— 必须申请 api_key，未申请直接 403
 * - **MusicBrainz + Cover Art Archive** —— 免 key 可用，但要「先查 MBID 再取图」两步，
 *   且 MB 有 1 req/sec 的限流和 User-Agent 要求，对一个"补个封面"的需求太重
 *
 * 和在线歌词一样：**只在需要时才请求**（播放到某首且本地没封面），拿到就落盘。
 */
object OnlineCover {

    /** 候选列表的缩略图缓存（只活在内存里，列表关掉即可回收） */
    val thumbCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(60)

    private const val TAG = "SonanceCover"
    private const val CONNECT_MS = 6000
    private const val READ_MS = 10000
    /** 封面一般 100KB 上下，给 8MB 上限足够宽松又挡得住异常响应 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 列表缩略图尺寸 */
    const val THUMB_PX = 200
    /** 实际使用尺寸 */
    const val FULL_PX = 800

    @androidx.annotation.VisibleForTesting
    internal fun buildSearchUrl(term: String, limit: Int, country: String?, entity: String = "song"): String? {
        val q = term.trim()
        if (q.isEmpty()) return null
        return buildString {
            append("https://itunes.apple.com/search?term=").append(enc(q))
            append("&entity=").append(entity).append("&media=music&limit=").append(limit.coerceIn(1, 50))
            if (!country.isNullOrBlank()) append("&country=").append(enc(country))
        }
    }

    /**
     * 「不是真歌手名」的占位值。
     *
     * 本地文件的 artist 标签经常是这些，拼进查询词会把结果带偏 ——
     * 实测 `"01 Midnight Horizon 未知艺术家"` 在 iTunes 上一条都搜不到，
     * 而去掉占位歌手、去掉文件名序号后就能命中。
     */
    private val placeholderArtists = setOf(
        "", "未知艺术家", "未知", "未知歌手", "<unknown>", "unknown", "unknown artist",
        "various artists", "various", "va", "群星", "无", "n/a", "null"
    )

    private fun isRealArtist(s: String): Boolean =
        s.trim().lowercase().let { it.isNotEmpty() && it !in placeholderArtists }

    /**
     * 去掉标题里的文件名痕迹：
     * - 开头的音轨序号：`01 Midnight Horizon` / `01. x` / `01 - x` / `[01] x`
     * - 结尾的括号补充说明保留（可能是重要的版本信息，比如 (Live)）
     */
    internal fun cleanTitle(title: String): String {
        var t = title.trim()
        t = t.replace(Regex("^\\[?\\d{1,3}\\]?\\s*[-._、)）]?\\s*"), "")
        return t.trim().ifBlank { title.trim() }
    }

    /**
     * 去掉标题里的括号补充说明：`晴天 (Live)` → `晴天`。
     *
     * 本地文件名里这类后缀特别多（`(Live)`、`（电影主题曲）`、`【无损】`、
     * `- 抖音热播版`），拼进查询词几乎必然搜不到。
     * 但它是**额外的**一条查询词，不是替换 —— `(Live)` 有时正是要找的版本。
     */
    internal fun stripExtras(title: String): String {
        var t = title.trim()
        // 括号及其内容（中英文、方头括号）
        t = t.replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), " ")
        // 横线 / 空格后面的版本尾巴，只在**结尾**出现时才去掉
        t = t.replace(Regex("[-—–_]\\s*[^\\-—–_]{0,20}$"), "")
        return t.replace(Regex("\\s+"), " ").trim().ifBlank { title.trim() }
    }

    /**
     * 依次用「越来越宽松」的查询词搜，命中即返回。
     *
     * 顺序是刻意的：先精确（标题+歌手+专辑），不行再逐级放宽。
     * 直接上最宽的话，一首叫「Summer」的歌会被一堆同名结果淹没。
     *
     * 变体有两组（原样 / 去括号），每组都按「全带 → 标题+歌手 → 只要标题」放宽。
     * 加"去括号"这一组是因为本地文件名几乎都带尾巴，
     * 而原来只有一条查询词能过，稍微对不上就整首搜不到。
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildQueries(title: String, artist: String, album: String): List<String> {
        val t = title.trim()
        val a = artist.trim().takeIf { isRealArtist(it) }.orEmpty()
        val al = album.trim().takeIf { isRealArtist(it) }.orEmpty()
        val ct = cleanTitle(t)
        val st = stripExtras(t)

        return buildList {
            addAll(variants(t, a, al))
            if (ct != t) addAll(variants(ct, a, al))
            if (st != t && st != ct) addAll(variants(st, a, al))
        }.distinct()
    }

    private fun variants(t: String, a: String, al: String): List<String> = buildList {
        if (t.isNotBlank() && a.isNotBlank() && al.isNotBlank()) add("$t $a $al")
        if (t.isNotBlank() && a.isNotBlank()) add("$t $a")
        if (t.isNotBlank()) add(t)
    }

    /**
     * 搜封面。
     *
     * 两个源都试，谁先给结果用谁：
     * - **iTunes**（[searchItunes]）：西洋曲库覆盖好、免 key
     * - **网易云**（[searchNetease]）：东亚曲库覆盖好，App 里本来就在用它的歌词和音源
     *
     * 顺序按语种定：标题或歌手里有中日韩字符就先问网易云 ——
     * 实测华语歌问 iTunes 经常返回**同名翻唱**的封面，那比"没有封面"更糟。
     *
     * @return 候选列表（可能为空，表示没搜到）；参数不合法返回 null
     */
    suspend fun search(
        title: String,
        artist: String = "",
        album: String = "",
        limit: Int = 12
    ): List<CoverCandidate>? = withContext(Dispatchers.IO) {
        val queries = buildQueries(title, artist, album)
        if (queries.isEmpty()) {
            Log.w(TAG, "封面搜索词为空: title='$title' artist='$artist'")
            return@withContext null
        }

        val cjk = hasCjkText(title, artist)
        val first = if (cjk) searchNetease(queries, limit) else searchItunes(queries, limit)
        if (!first.isNullOrEmpty()) return@withContext first

        val second = if (cjk) searchItunes(queries, limit) else searchNetease(queries, limit)
        if (!second.isNullOrEmpty()) return@withContext second

        Log.i(TAG, "封面搜索用尽两个源仍无结果: $queries")
        emptyList()
    }

    /**
     * iTunes：每个查询词先全球区、再中国区，还不行再按**专辑**搜一遍。
     *
     * ⚠️ 中国区**必须排在全球区后面**：实测 `country=CN` 对华语曲目
     * 返回的 `resultCount` 常年是 0（Apple Music 中国区不进这个老接口）。
     * 原来把它排在最前面，于是华语歌第一条查询词就死在这里。
     */
    private suspend fun searchItunes(queries: List<String>, limit: Int): List<CoverCandidate>? {
        for (entity in listOf("song", "album")) {
            for (q in queries) {
                for (country in listOf(null, "CN")) {
                    val url = buildSearchUrl(q, limit, country, entity) ?: continue
                    val body = httpGetText(url) ?: continue
                    val list = parseResults(body, "iTunes")
                    if (!list.isNullOrEmpty()) {
                        Log.i(TAG, "封面搜索命中 ${list.size} 条（iTunes entity=$entity query='$q' country=$country）")
                        return list
                    }
                }
            }
        }
        return null
    }

    /** 网易云：搜索接口一次就带歌名+歌手，够用了 */
    private suspend fun searchNetease(queries: List<String>, limit: Int): List<CoverCandidate>? {
        for (q in queries) {
            val page = runCatching { NeteaseSource.search(q, limit.coerceIn(1, 20), 0) }
                .onFailure { Log.w(TAG, "网易封面搜索失败: ${it.message}") }
                .getOrNull() ?: continue
            val list = page.tracks.mapNotNull { t ->
                val url = t.coverUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CoverCandidate(
                    trackName = t.title,
                    artistName = t.artist,
                    collectionName = t.album,
                    // 图床认 `?param=宽y高`，会真的缩放再返回；不指定的话一张图 1MB+
                    thumbUrl = NeteaseSource.thumbUrl(url, THUMB_PX),
                    fullUrl = NeteaseSource.thumbUrl(url, FULL_PX),
                    source = "网易云"
                )
            }.distinctBy { it.fullUrl }
            if (list.isNotEmpty()) {
                Log.i(TAG, "封面搜索命中 ${list.size} 条（网易云 query='$q'）")
                return list
            }
        }
        return null
    }

    /**
     * 下载原始图片字节。
     *
     * 缓存里存的就是这个原始字节 —— 不存重新编码后的位图，
     * 否则每过一轮"解码→压 JPEG→再解码"都会掉一次画质。
     */
    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Sonance/1.0 (local music player)")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "封面下载 HTTP ${conn.responseCode}")
                return@withContext null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                Log.w(TAG, "封面大小异常：${bytes.size}")
                return@withContext null
            }
            bytes
        } catch (t: Throwable) {
            Log.w(TAG, "封面下载失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 下载并解码；失败返回 null */
    suspend fun download(url: String, maxPx: Int = FULL_PX): Bitmap? =
        downloadBytes(url)?.let { decode(it, maxPx) }

    /** 解码并按需下采样 */
    fun decode(bytes: ByteArray, maxPx: Int = FULL_PX): Bitmap? =
        runCatching { decodeSampled(bytes, maxPx) }.getOrNull()

    /** 把 iTunes 的 artworkUrl 换成指定尺寸 */
    fun resizeUrl(url: String, px: Int): String =
        url.replace(Regex("/\\d+x\\d+bb\\.(jpg|png)$"), "/${px}x${px}bb.$1")

    /* ============================================================ */

    internal fun parseResults(body: String, source: String = "iTunes"): List<CoverCandidate>? = runCatching {
        val json = JSONObject(body)
        val arr = json.optJSONArray("results") ?: return@runCatching emptyList()
        val out = ArrayList<CoverCandidate>(arr.length())
        // 按封面 URL 去重：搜一首歌经常返回同一张专辑的好几个曲目，
        // 它们的 artworkUrl100 完全相同 —— 不去重的话候选列表里会是一排一样的图，
        // 而且（早先版本）还会因为拿 fullUrl 当 LazyGrid 的 key 而直接崩掉。
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val art = o.optString("artworkUrl100")
            if (art.isNullOrBlank()) continue
            val full = resizeUrl(art, FULL_PX)
            if (!seen.add(full)) continue
            // entity=album 时没有 trackName，用专辑名补上，界面那两行文字不至于空着
            val track = o.optString("trackName").orEmpty()
                .ifBlank { o.optString("collectionName").orEmpty() }
            out += CoverCandidate(
                trackName = track,
                artistName = o.optString("artistName").orEmpty(),
                collectionName = o.optString("collectionName").orEmpty(),
                thumbUrl = resizeUrl(art, THUMB_PX),
                fullUrl = full,
                source = source
            )
        }
        out
    }.getOrNull()

    private fun httpGetText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Sonance/1.0 (local music player)")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "封面搜索 HTTP ${conn.responseCode} url=$url")
                return null
            }
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (t: Throwable) {
            Log.w(TAG, "封面搜索失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        val max = maxOf(opts.outWidth, opts.outHeight)
        while (max / sample > target * 2) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * `keyOf` 的记忆化。
     *
     * 界面（列表每一行、播放条、全屏页）都会在 Composition 里直接调用 `keyOf`，
     * 一次列表重组就是几十次调用 —— SHA-1 只该算一次。
     * 命中时返回的是**同一个字符串实例**，值不变，所以可以安全地当作
     * `remember` / `LaunchedEffect` 的 key 用。
     */
    private val keyMemo = android.util.LruCache<String, String>(512)

    /**
     * 缓存 key：和歌词缓存同样的思路，用「歌名+歌手」而不是 MediaStore id（重扫后 id 会变）。
     *
     * 前缀带 `v2` —— 搜索策略改过（过滤占位歌手 / 清理文件名序号）之后，
     * 旧版本记下的"查不到"哨兵必须作废，否则修好了也还是读回旧的空结果。
     */
    fun keyOf(title: String, artist: String): String? {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()
        if (t.isEmpty() && a.isEmpty()) return null
        val raw = "$t|$a"
        keyMemo.get(raw)?.let { return it }
        return sha1("cover|v2|$raw").also { keyMemo.put(raw, it) }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
