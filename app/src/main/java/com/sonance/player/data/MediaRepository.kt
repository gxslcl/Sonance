package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 扫描曲库时的过滤规则 */
data class ScanFilters(
    val excludeLocked: Boolean = true,
    val excludeUnsupported: Boolean = true,
    val excludeNonSongFolders: Boolean = true
)

/** 一次扫描的结果：可用曲库 + 各类被排除的数量 */
data class ScanOutcome(
    val songs: List<Song>,
    val excludedLocked: Int = 0,
    val excludedUnsupported: Int = 0,
    val excludedNonSong: Int = 0,
    val excludedShort: Int = 0
) {
    val excludedTotal: Int get() = excludedLocked + excludedUnsupported + excludedNonSong
}

/**
 * 通过 MediaStore 扫描设备上的本地音频。
 *
 * 扫描时顺手把「播不了的」和「不是歌的」挡在曲库外，规则见 [ScanFilters]：
 * - **加密容器**：名字可疑（含 .kgm/.ncm 等标记）**或**时长解析不出来 → 读文件头确认魔数。
 *   最终只认魔数不认名字，所以已转换的 `X.kgm.mp3` 不会被误杀。
 * - **系统不支持的格式**：ape / wma / dsf … 按后缀排除。
 * - **录音 / 系统提示音**：按 MediaStore 标志与目录名排除。
 */
class MediaRepository(private val context: Context) {

    suspend fun scan(
        filterShortAudio: Boolean = true,
        filters: ScanFilters = ScanFilters()
    ): ScanOutcome = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_ALARM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val out = ArrayList<Song>(512)
        // 可疑项：名字带加密标记、或时长解析不出来。留到第二轮读文件头确认。
        val suspicious = ArrayList<Pair<Song, Long>>()

        var excludedLocked = 0
        var excludedUnsupported = 0
        var excludedNonSong = 0
        var excludedShort = 0

        try {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val iId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iDate = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iTrack = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iRing = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val iNoti = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                val iAlarm = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                val iPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(iDur)
                    val id = cursor.getLong(iId)
                    val rawTitle = cursor.getString(iTitle)?.trim().orEmpty()
                    val displayName = cursor.getString(iName)?.trim().orEmpty()
                    val path = if (iPath >= 0) cursor.getString(iPath).orEmpty() else ""
                    val title = rawTitle.ifBlank { displayName.substringBeforeLast('.') }
                    val artist = sanitize(cursor.getString(iArtist))
                    val album = sanitize(cursor.getString(iAlbum))

                    val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        path.trimEnd('/')
                    } else {
                        path.substringBeforeLast('/', "")
                    }
                    val folderName = folder.substringAfterLast('/').ifBlank { "根目录" }

                    val song = Song(
                        id = id,
                        title = title.ifBlank { "未知曲目" },
                        artist = artist,
                        album = album,
                        albumId = cursor.getLong(iAlbumId),
                        durationMs = duration,
                        uri = ContentUris.withAppendedId(collection, id),
                        folderPath = folder,
                        folderName = folderName,
                        displayName = displayName,
                        sizeBytes = cursor.getLong(iSize),
                        dateAddedSec = cursor.getLong(iDate),
                        track = cursor.getInt(iTrack)
                    )

                    // ---- 过滤 1：系统提示音 / 录音目录 ----
                    val systemSound = (iRing >= 0 && cursor.getInt(iRing) == 1) ||
                        (iNoti >= 0 && cursor.getInt(iNoti) == 1) ||
                        (iAlarm >= 0 && cursor.getInt(iAlarm) == 1)
                    if (filters.excludeNonSongFolders &&
                        (systemSound || LockedFormat.isNonSongFolder(folder))
                    ) {
                        excludedNonSong++
                        continue
                    }

                    // ---- 过滤 2：系统解码器不支持的格式 ----
                    if (filters.excludeUnsupported && LockedFormat.isPlainUnsupported(displayName)) {
                        excludedUnsupported++
                        continue
                    }

                    // ---- 过滤 3：短音频 ----
                    if (filterShortAudio && duration in 1..14_000) {
                        excludedShort++
                        continue
                    }

                    // ---- 可疑项：留到第二轮读头确认 ----
                    val suspiciousName = LockedFormat.looksLikeLockedContainer(displayName)
                    if (filters.excludeLocked && (suspiciousName || duration <= 0)) {
                        suspicious += song to duration
                    } else {
                        out += song
                    }
                }
            }
        } catch (_: Exception) {
            // 权限缺失或媒体库异常时返回空表
        }

        // 第二轮：只对可疑项读 44 字节文件头，按魔数定生死。
        // 名字带加密标记但能正常解出时长（= 已转换的成品）会被放行。
        for ((song, duration) in suspicious) {
            val byMagic = readIsLockedContainer(song.uri)
            val byHeuristic = LockedFormat.looksLikeLockedContainer(song.displayName) && duration <= 0
            if (byMagic || byHeuristic) excludedLocked++ else out += song
        }

        ScanOutcome(
            songs = out,
            excludedLocked = excludedLocked,
            excludedUnsupported = excludedUnsupported,
            excludedNonSong = excludedNonSong,
            excludedShort = excludedShort
        )
    }

    /** 读文件头 44 字节判断是否为受支持的加密容器（KGM/KGMA/VPR）。读不到一律按「不是」处理。 */
    private fun readIsLockedContainer(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(PrivateAudioRestorer.HEADER_MIN)
            var off = 0
            while (off < header.size) {
                val n = input.read(header, off, header.size - off)
                if (n < 0) break
                off += n
            }
            off == header.size && PrivateAudioRestorer.parseHeader(header) != null
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun sanitize(v: String?): String {
        val s = v?.trim().orEmpty()
        return if (s.isEmpty() || s == "<unknown>") UNKNOWN_ARTIST else s
    }

    companion object {
        fun groupAlbums(songs: List<Song>): List<Album> =
            songs.groupBy { it.albumId to it.album }
                .map { (key, list) ->
                    Album(
                        albumId = key.first,
                        name = key.second,
                        artist = list.groupingBy { it.artist }.eachCount()
                            .maxByOrNull { it.value }?.key ?: UNKNOWN_ARTIST,
                        songs = list.sortedBy { it.track }
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        fun groupArtists(songs: List<Song>): List<Artist> =
            songs.groupBy { it.artist }
                .map { (name, list) ->
                    Artist(name = name, songs = list, albumCount = list.map { it.albumId }.distinct().size)
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        fun groupFolders(songs: List<Song>): List<MusicFolder> =
            songs.groupBy { it.folderPath }
                .map { (path, list) -> MusicFolder(path = path, name = list.first().folderName, songs = list) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        /**
         * 行为类排序字段（播放次数 / 最近播放）的排序键。
         *
         * 抽成纯函数是为了能单测 —— [Song] 带 `android.net.Uri` 字段，
         * 纯 JVM 测试构造不出来（项目没引 Robolectric）。
         *
         * 两个关键约定：
         * - 没有播放记录的当 **0** 次（降序时自然排最后）
         * - 没播过的用 **Int.MAX_VALUE** 当"最近播放序号"，降序时排在末尾
         */
        internal fun statsSortKey(songId: Long, field: SortField, stats: SongStats): Long =
            when (field) {
                SortField.PLAY_COUNT -> (stats.playCounts[songId] ?: 0).toLong()
                SortField.RECENT_PLAYED ->
                    (stats.recentOrder[songId] ?: Int.MAX_VALUE).toLong()
                else -> 0L
            }

        fun sortSongs(
            songs: List<Song>,
            field: SortField,
            ascending: Boolean,
            stats: SongStats = SongStats()
        ): List<Song> {
            val cmp: Comparator<Song> = when (field) {
                SortField.TITLE -> Comparator { a, b ->
                    a.title.compareTo(b.title, ignoreCase = true)
                }

                SortField.ARTIST -> Comparator { a, b ->
                    val r = a.artist.compareTo(b.artist, ignoreCase = true)
                    if (r != 0) r else a.album.compareTo(b.album, ignoreCase = true)
                }

                SortField.ALBUM -> Comparator { a, b ->
                    val r = a.album.compareTo(b.album, ignoreCase = true)
                    if (r != 0) r else a.track.compareTo(b.track)
                }

                SortField.DURATION -> Comparator { a, b -> a.durationMs.compareTo(b.durationMs) }
                SortField.DATE_ADDED -> Comparator { a, b -> a.dateAddedSec.compareTo(b.dateAddedSec) }
                SortField.FILE_NAME -> Comparator { a, b ->
                    a.displayName.compareTo(b.displayName, ignoreCase = true)
                }

                SortField.PLAY_COUNT -> Comparator { a, b ->
                    val ca = statsSortKey(a.id, field, stats)
                    val cb = statsSortKey(b.id, field, stats)
                    // 键相同的按标题，避免顺序每次刷新都乱跳
                    if (ca != cb) ca.compareTo(cb) else a.title.compareTo(b.title, ignoreCase = true)
                }

                SortField.RECENT_PLAYED -> Comparator { a, b ->
                    val ia = statsSortKey(a.id, field, stats)
                    val ib = statsSortKey(b.id, field, stats)
                    if (ia != ib) ia.compareTo(ib) else a.title.compareTo(b.title, ignoreCase = true)
                }
            }
            return if (ascending) songs.sortedWith(cmp) else songs.sortedWith(cmp.reversed())
        }

        fun search(songs: List<Song>, query: String): List<Song> {
            if (query.isBlank()) return songs
            val q = query.trim().lowercase()
            return songs.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q) ||
                    it.folderName.lowercase().contains(q)
            }
        }

        /**
         * 「点某首歌播放」时**进队列**的列表。
         *
         * 搜索是**找入口**，不是"我要只听这几首" —— 拿筛选后的列表当队列的话，
         * 用户搜一首歌点播放，队列里就只剩搜索结果（搜索只命中一首时就只剩那一首），
         * 播完就没了。用户报的就是这个（"点击播放就默认只把这首歌放入播放列表"）。
         *
         * 不搜索时列表本身就是完整曲库，直接用它，行为不变。
         *
         * 抽成**泛型**纯函数是为了可测：[SongsScreen] 要 `SonanceViewModel`，
         * JVM 单测里起不来；而 `Song` 又带 `android.net.Uri`、在 JVM 上构造不出来
         * （`Uri.parse` 是返回 null 的桩）。这个函数只做"用哪个列表"的选择、
         * 跟元素类型无关，所以泛型化没有任何代价 —— 和 [findDuplicatesIn] 同一个做法。
         *
         * @param display  列表当前显示的歌（可能被搜索筛过）
         * @param library  完整曲库（已排序、已排除隐藏项）
         * @param searching 当前是否在搜索
         */
        fun <T> playQueue(display: List<T>, library: List<T>, searching: Boolean): List<T> =
            if (searching) library else display
    }
}

/**
 * 排序需要的用户行为数据。
 *
 * 单独作为参数传进来，而不是让 `MediaRepository` 去依赖 `Prefs` ——
 * 这样这个对象仍然是无状态纯函数，也方便单测。
 */
data class SongStats(
    /** songId -> 播放次数 */
    val playCounts: Map<Long, Int> = emptyMap(),
    /** songId -> 最近播放序号（越小越近） */
    val recentOrder: Map<Long, Int> = emptyMap()
)

/**
 * 一组重复条目：保留 [keep]，其余 [duplicates] 建议从列表里隐藏。
 */
data class DuplicateGroup<T>(
    val key: String,
    val keep: T,
    val duplicates: List<T>
)

/**
 * 重复检测结果。
 *
 * - [exactGroups]：**同一首歌的多份文件** —— 标题、歌手、时长完全一致。
 *   基本可以确定是重复（同一文件拷了两份 / 换过格式又留了原件）。
 * - [sameNameGroups]：**同名但时长不同** —— 可能是同一首的不同版本（现场 / 重录 / 不同码率），
 *   也可能是真的重复。所以清理时单独一个按钮、单独确认，不跟"确定重复"混在一起。
 */
data class DuplicateReport<T>(
    val exactGroups: List<DuplicateGroup<T>>,
    val sameNameGroups: List<DuplicateGroup<T>>
) {
    val exactCount: Int get() = exactGroups.sumOf { it.duplicates.size }
    val sameNameCount: Int get() = sameNameGroups.sumOf { it.duplicates.size }
    val isEmpty: Boolean get() = exactCount == 0 && sameNameCount == 0
}

/**
 * 在内存里的曲库中找重复。**不碰文件系统**，纯列表比对。
 */
fun findDuplicates(songs: List<Song>): DuplicateReport<Song> = findDuplicatesIn(
    items = songs,
    id = { it.id },
    title = { it.title },
    artist = { it.artist },
    durationMs = { it.durationMs },
    sizeBytes = { it.sizeBytes }
)

/**
 * 去重的核心逻辑。做成泛型是为了能在**纯 JVM 单测**里覆盖 ——
 * [Song] 带 `android.net.Uri` 字段，普通单元测试构造不出来（项目没引 Robolectric）。
 */
internal fun <T> findDuplicatesIn(
    items: List<T>,
    id: (T) -> Long,
    title: (T) -> String,
    artist: (T) -> String,
    durationMs: (T) -> Long,
    sizeBytes: (T) -> Long
): DuplicateReport<T> {
    if (items.size < 2) return DuplicateReport(emptyList(), emptyList())

    fun norm(s: String) = s.trim().lowercase()

    // ---- 精确重复：标题 + 歌手 + 时长 ----
    val exactBuckets = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        val t = title(item)
        if (t.isBlank()) continue
        val k = "${norm(t)}|${norm(artist(item))}|${durationMs(item)}"
        exactBuckets.getOrPut(k) { ArrayList(2) }.add(item)
    }
    val exactGroups = ArrayList<DuplicateGroup<T>>()
    val exactMemberIds = HashSet<Long>()
    for ((k, list) in exactBuckets) {
        if (list.size < 2) continue
        // 保留体积最大的那份（同样时长下体积大 = 码率更高）
        val sorted = list.sortedByDescending { sizeBytes(it) }
        val dups = sorted.drop(1)
        exactGroups.add(DuplicateGroup(k, sorted.first(), dups))
        dups.forEach { exactMemberIds.add(id(it)) }
    }

    // ---- 同名：标题 + 歌手，但时长不同 ----
    val nameBuckets = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        val t = title(item)
        if (t.isBlank()) continue
        // 已经判为精确重复的成员不再进同名分组，避免同一个文件被算两次
        if (id(item) in exactMemberIds) continue
        val k = "${norm(t)}|${norm(artist(item))}"
        nameBuckets.getOrPut(k) { ArrayList(2) }.add(item)
    }
    val sameNameGroups = ArrayList<DuplicateGroup<T>>()
    for ((k, list) in nameBuckets) {
        if (list.size < 2) continue
        // 保留时长最长的那份（通常是完整版）
        val sorted = list.sortedByDescending { durationMs(it) }
        sameNameGroups.add(DuplicateGroup(k, sorted.first(), sorted.drop(1)))
    }

    return DuplicateReport(exactGroups, sameNameGroups)
}

/**
 * 把扫描结果序列化成 JSON 写进 Prefs，避免下次冷启动再跑一遍 MediaStore。
 *
 * 字段与 [Song] 的 data class 字段一一对应——id / uri 这两个最关键：
 * 文件被外部删了之后，[id] 还在但访问可能拿不到内容；保留它在 cache 里也没坏处，
 * 真用到了再 lazy-load 一次 MediaStore 行，发现不存在就忽略。
 */
fun encodeSongsToJson(songs: List<Song>): String {
    val arr = JSONArray()
    for (s in songs) {
        arr.put(JSONObject().apply {
            put("id", s.id)
            put("title", s.title)
            put("artist", s.artist)
            put("album", s.album)
            put("albumId", s.albumId)
            put("durationMs", s.durationMs)
            put("uri", s.uri.toString())
            put("folderPath", s.folderPath)
            put("folderName", s.folderName)
            put("displayName", s.displayName)
            put("sizeBytes", s.sizeBytes)
            put("dateAddedSec", s.dateAddedSec)
            put("track", s.track)
        })
    }
    return arr.toString()
}

fun decodeSongsFromJson(json: String): List<Song> {
    val arr = JSONArray(json)
    val out = ArrayList<Song>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        out += Song(
            id = o.getLong("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            albumId = o.optLong("albumId"),
            durationMs = o.optLong("durationMs"),
            uri = Uri.parse(o.optString("uri")),
            folderPath = o.optString("folderPath"),
            folderName = o.optString("folderName"),
            displayName = o.optString("displayName"),
            sizeBytes = o.optLong("sizeBytes"),
            dateAddedSec = o.optLong("dateAddedSec"),
            track = o.optInt("track")
        )
    }
    return out
}
