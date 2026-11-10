package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 扫描曲库时的过滤规则 */
data class ScanFilters(
    val excludeLocked: Boolean = true,
    val excludeUnsupported: Boolean = true,
    val excludeNonSongFolders: Boolean = true,
    /** 用户排除的文件夹路径（含子文件夹）；空 = 不做此过滤 */
    val excludedDirs: Set<String> = emptySet()
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

/** 「扫描」面板里的一个可选目录：路径 + 展示名 + 曲目数 */
data class ScanFolderInfo(
    val path: String,
    val name: String,
    val songCount: Int
)

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
        filters: ScanFilters = ScanFilters(),
        /** 只扫描这些目录（路径与 [ScanFolderInfo.path] 一致）；null = 全量扫描 */
        folderPaths: Set<String>? = null,
        /** 已检查的 MediaStore 行数，用于面板进度显示 */
        onProgress: ((Int) -> Unit)? = null
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
        var scanned = 0

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
                    coroutineContext.ensureActive()
                    scanned++
                    if (scanned % 64 == 0) onProgress?.invoke(scanned)
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

                    // 限定扫描范围：不在所选目录里的条目直接跳过（不参与任何排除计数）
                    if (folderPaths != null && folder !in folderPaths) continue

                    // 用户排除的文件夹（含子文件夹）：整支剪掉，不参与排除计数
                    if (filters.excludedDirs.isNotEmpty() &&
                        filters.excludedDirs.any { ex -> folder == ex || folder.startsWith("$ex/") }
                    ) continue

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
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // 权限缺失或媒体库异常时返回空表
        }

        // 第二轮：只对可疑项读 64 字节文件头，按魔数定生死。
        // 名字带加密标记但能正常解出时长（= 已转换的成品）会被放行。
        for ((song, duration) in suspicious) {
            coroutineContext.ensureActive()
            val byMagic = readIsLockedContainer(song.uri)
            val byHeuristic = LockedFormat.looksLikeLockedContainer(song.displayName) && duration <= 0
            if (byMagic || byHeuristic) excludedLocked++ else out += song
        }

        onProgress?.invoke(scanned)
        ScanOutcome(
            songs = out,
            excludedLocked = excludedLocked,
            excludedUnsupported = excludedUnsupported,
            excludedNonSong = excludedNonSong,
            excludedShort = excludedShort
        )
    }

    /**
     * 列出 MediaStore 里已有的音频目录，按目录聚合并统计曲目数。
     *
     * 只作为「扫描范围」的候选列表，不读取文件、不写库；权限缺失时返回空表，
     * 由面板提示用户检查读取音乐权限。
     */
    suspend fun listFolders(): List<ScanFolderInfo> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val counts = LinkedHashMap<String, Int>()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_MUSIC, pathColumn),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null
            )?.use { cursor ->
                val iPath = cursor.getColumnIndex(pathColumn)
                if (iPath >= 0) {
                    while (cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        val folder = folderOf(cursor.getString(iPath).orEmpty())
                        counts[folder] = (counts[folder] ?: 0) + 1
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // 权限缺失或媒体库异常时返回空表
        }
        counts.map { (path, count) ->
            ScanFolderInfo(
                path = path,
                name = path.substringAfterLast('/').ifBlank { "根目录" },
                songCount = count
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
    }

    /** 与 [scan] 中曲目目录计算保持完全一致的归一化（扫描范围用它做匹配） */
    private fun folderOf(path: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            path.trimEnd('/')
        } else {
            path.substringBeforeLast('/', "")
        }

    /**
     * 读文件头 64 字节，按魔数判断是否为已知加密容器（KGM/KGMA/KGG/VPR、NCM、KWM）。
     *
     * 这里只做 `startsWith` 比对，不做完整解析 —— 扫描要遍历整个曲库，
     * 每首都跑一遍 NCM 的两轮 AES 是不可接受的。真正的完整探测留给
     * [ContainerRestorer.probe]（还原流程才用）。
     *
     * 读不到一律按「不是」处理：宁可让可疑文件留在库里，也不要误删用户的歌。
     */
    private fun readIsLockedContainer(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(AudioDecoder.HEADER_MIN)
            var off = 0
            while (off < header.size) {
                val n = input.read(header, off, header.size - off)
                if (n < 0) break
                off += n
            }
            off == header.size && ContainerRestorer.looksLocked(header)
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

        fun search(
            songs: List<Song>,
            query: String,
            pinyin: Map<Long, String> = emptyMap()
        ): List<Song> {
            if (query.isBlank()) return songs
            val q = query.trim().lowercase()
            // 拼音查询同样去掉空格，让 "zhou jie lun" 也能命中
            val pinyinQ = q.replace(" ", "")
            return songs.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q) ||
                    it.folderName.lowercase().contains(q) ||
                    (pinyinQ.isNotEmpty() && pinyin[it.id]?.contains(pinyinQ) == true)
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
 * - [exactGroups]：**同一首歌的多份文件** —— 标题、歌手归一化后一致，且时长在
 *   [DUPLICATE_DURATION_TOLERANCE_MS] 容差内。基本可以确定是重复
 *   （同一文件拷了两份 / 换过格式又留了原件）。
 * - [sameNameGroups]：**同名但时长不同** —— 可能是同一首的不同版本（现场 / 重录 / 不同码率），
 *   也可能是真的重复。所以清理时单独一个按钮、单独确认，不跟"确定重复"混在一起。
 *
 * 标题 / 歌手的「一致」是归一化后的一致：全角半角、大小写、括号修饰、音轨号前缀、
 * 复制体尾巴、中英标点、多歌手顺序都不算差异（见 [canonicalTitle] / [canonicalArtist]）。
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
 *
 * 比对前先把标题 / 歌手过一遍**繁 → 简**（[ChineseConverter]）：同一首歌在曲库里
 * 常常一份是简体标签、一份是繁体标签（台版 / 港版抓轨、老 CD 抓轨），不做这一步
 * 会把它俩当成两首不同的歌，正是"明明重复却检测不出来"的主因。
 *
 * 转换只作用于**判定键**，[Song] 里显示的值一个都不改。
 */
fun findDuplicates(songs: List<Song>, context: Context): DuplicateReport<Song> = findDuplicatesIn(
    items = songs,
    id = { it.id },
    title = { ChineseConverter.toSimplified(context, it.title) },
    artist = { ChineseConverter.toSimplified(context, it.artist) },
    durationMs = { it.durationMs },
    sizeBytes = { it.sizeBytes }
)

/**
 * 判定「同一首歌」时允许的时长偏差（毫秒）。
 *
 * 同一首歌重新编码（换码率、mp3 ↔ flac）后时长常差几百毫秒，硬比相等会漏判。
 * 取 **1 秒以下**：真·不同版本（现场 / 重录）通常差几十秒，不会被误并。
 */
internal const val DUPLICATE_DURATION_TOLERANCE_MS = 1000L

/** 全角转半角，顺带做 Unicode 兼容分解（`Ｈｅｌｌｏ` → `Hello`、`①` → `1`） */
private fun normalizeWidth(s: String): String =
    runCatching { java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC) }
        .getOrDefault(s)

/** 括号及其内容：`(Live)` / `【伴奏】` / `（1）` */
private val TITLE_BRACKET = Regex("""[(\[【（][^)\]】）]*[)\]】）]""")

/** 开头的音轨号：`01 - ` / `03.` / `12、` */
private val TITLE_TRACK_PREFIX = Regex("""^\s*\d{1,3}\s*[-–—.、_]\s*""")

/** 复制体尾巴：`- 副本` / ` copy 2` */
private val TITLE_COPY_TAIL = Regex("""(?i)[-_ ]*(copy|副本|拷贝)\s*\d*\s*$""")

/** 标点 / 空白一律折叠成空格 */
private val TITLE_NON_WORD = Regex("""[^\p{L}\p{N}]+""")
private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * 标题归一化 —— 把「同一首歌的不同写法」折叠成同一个键。
 *
 * 覆盖曲库里真实存在的差异：全角半角、大小写、括号修饰（`(Live)` / `【伴奏】`）、
 * 开头音轨号（`01 - `）、复制体尾巴（`X (1)` / `X - 副本`）、中英标点。
 *
 * 只在去重判定里用，**不写回 [Song]** —— 列表里显示的仍是原始标题。
 */
internal fun canonicalTitle(raw: String): String {
    if (raw.isBlank()) return ""
    var s = normalizeWidth(raw).lowercase()
    s = TITLE_COPY_TAIL.replace(s, "")
    s = TITLE_TRACK_PREFIX.replace(s, "")
    s = TITLE_BRACKET.replace(s, " ")
    s = TITLE_NON_WORD.replace(s, " ")
    return s.trim().replace(WHITESPACE_RUN, " ")
}

/** 歌手分隔符：`A/B`、`A、B`、`A & B`、`A feat. B` 都视为同一组人 */
private val ARTIST_SEPARATOR =
    Regex("""(?i)\s*(?:[/\\、,，;；&＆+]|feat\.?|ft\.?|with)\s*""")

/** 视为「没有歌手信息」的写法 —— 这类条目在聚簇时按通配处理 */
private val UNKNOWN_ARTIST_TOKENS = setOf(
    UNKNOWN_ARTIST.lowercase(), "unknown", "<unknown>", "未知", "未知歌手"
)

/**
 * 歌手归一化。返回空串表示「歌手未知」，调用方按**通配**处理 ——
 * 同一首歌常常一份打了标签、一份没打，按空串硬分桶就永远聚不到一起。
 */
internal fun canonicalArtist(raw: String): String {
    val s = normalizeWidth(raw).lowercase().trim()
    if (s.isEmpty() || s in UNKNOWN_ARTIST_TOKENS) return ""
    return s.split(ARTIST_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it !in UNKNOWN_ARTIST_TOKENS }
        .distinct()
        .sorted()
        .joinToString("/")
}

/**
 * 同一标题下的条目按歌手聚簇。
 *
 * 歌手未知（归一化后为空）的条目**与任何簇兼容**：先并进已有簇，没有簇时自己起一个
 * 待定簇（后续有具名歌手进来会补上代表歌手）。
 */
private fun <T> clusterByArtist(items: List<T>, artist: (T) -> String): List<MutableList<T>> {
    val clusters = ArrayList<MutableList<T>>()
    val reps = ArrayList<String>()
    for (item in items) {
        val a = canonicalArtist(artist(item))
        if (a.isEmpty()) {
            if (clusters.isEmpty()) {
                clusters.add(ArrayList<T>(2).also { it.add(item) })
                reps.add("")
            } else {
                clusters[0].add(item)
            }
            continue
        }
        val idx = reps.indexOfFirst { it.isEmpty() || it == a }
        if (idx >= 0) {
            clusters[idx].add(item)
            if (reps[idx].isEmpty()) reps[idx] = a
        } else {
            clusters.add(ArrayList<T>(2).also { it.add(item) })
            reps.add(a)
        }
    }
    return clusters
}

/**
 * 把一簇条目按时长（带 [DUPLICATE_DURATION_TOLERANCE_MS] 容差）切成若干子簇。
 *
 * 先按时长排序，再让每个子簇的**跨度**不超过容差 —— 避免 200.0 / 200.9 / 201.8
 * 这种链式相邻被串成一串（跨度 1.8s，其实已经是两首不同的东西）。
 */
private fun <T> splitByDuration(items: List<T>, durationMs: (T) -> Long): List<MutableList<T>> {
    if (items.size < 2) return listOf(ArrayList(items))
    val sorted = items.sortedBy { durationMs(it) }
    val out = ArrayList<MutableList<T>>()
    var current = ArrayList<T>()
    var firstMs = 0L
    for (item in sorted) {
        val d = durationMs(item)
        if (current.isEmpty()) {
            firstMs = d
            current.add(item)
        } else if (d - firstMs < DUPLICATE_DURATION_TOLERANCE_MS) {
            current.add(item)
        } else {
            out.add(current)
            current = ArrayList()
            firstMs = d
            current.add(item)
        }
    }
    if (current.isNotEmpty()) out.add(current)
    return out
}

/**
 * 去重的核心逻辑。做成泛型是为了能在**纯 JVM 单测**里覆盖 ——
 * [Song] 带 `android.net.Uri` 字段，普通单元测试构造不出来（项目没引 Robolectric）。
 *
 * 分三层判定：
 * 1. **标题**（归一化后）分桶 —— 「是不是同一首歌」的主键；
 * 2. 同标题下按**歌手**聚簇，歌手未知者当通配；
 * 3. 簇内按**时长**（带容差）切分：时长一致 → 确定重复；否则 → 同名待确认。
 *
 * 归一化见 [canonicalTitle] / [canonicalArtist]，容差见 [DUPLICATE_DURATION_TOLERANCE_MS]。
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

    // 1) 按归一化标题分桶
    val byTitle = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        val t = canonicalTitle(title(item))
        if (t.isEmpty()) continue
        byTitle.getOrPut(t) { ArrayList(2) }.add(item)
    }

    val exactGroups = ArrayList<DuplicateGroup<T>>()
    val sameNameGroups = ArrayList<DuplicateGroup<T>>()

    for ((titleKey, titleGroup) in byTitle) {
        if (titleGroup.size < 2) continue

        // 2) 同标题下按歌手聚簇（歌手未知 = 通配）
        for (cluster in clusterByArtist(titleGroup, artist)) {
            if (cluster.size < 2) continue

            // 3) 时长一致（含容差）的算「确定重复」，保留体积最大的那份
            //    （同样时长下体积大 = 码率更高）
            val hiddenHere = HashSet<Long>()
            for (sub in splitByDuration(cluster, durationMs)) {
                if (sub.size < 2) continue
                val sorted = sub.sortedByDescending { sizeBytes(it) }
                val dups = sorted.drop(1)
                exactGroups.add(DuplicateGroup(titleKey, sorted.first(), dups))
                dups.forEach { hiddenHere.add(id(it)) }
            }

            // 4) 剩下的（时长不同 / 没被算进重复）只要还有 ≥2 个，就算同名。
            //    保留时长最长的那份（通常是完整版）。
            val rest = cluster.filter { id(it) !in hiddenHere }
            if (rest.size >= 2) {
                val sorted = rest.sortedByDescending { durationMs(it) }
                sameNameGroups.add(DuplicateGroup(titleKey, sorted.first(), sorted.drop(1)))
            }
        }
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
