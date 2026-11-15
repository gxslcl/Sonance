package com.sonance.player.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 单条在线收藏存档。
 *
 * 在线歌下载成系统曲库本地文件之前，靠 [song] 留在「收藏」清单并支持点播；
 * 一旦下载进曲库且通过扫描匹配上，会把 [localSongId] 关联过去并将对应本地 ID
 * 写入本地收藏集合，保证收藏总数、小心心状态与本地/在线无缝一致。
 */
data class OnlineFavoriteEntry(
    val song: StoredOnlineSong,
    val likedAt: Long = 0L,
    val localSongId: Long = 0L
)

/** 在线收藏的序列化、匹配与曲库联动纯函数。 */
object OnlineFavorites {

    internal data class IndexedCandidate(
        val durationMs: Long,
        val artistTokens: Set<String>
    )

    /**
     * 本地曲库归一化哈希索引：曲库扫描或变动时在后台一次性构建，
     * 供在线列表与播放器以 O(1) 复杂度判断曲目是否已存在或已下载。
     */
    class LocalSongIndex internal constructor(
        internal val isLibraryEmpty: Boolean,
        internal val byTitle: Map<String, List<IndexedCandidate>>,
        internal val byStem: Map<String, List<Long>>
    ) {
        companion object {
            val EMPTY = LocalSongIndex(
                isLibraryEmpty = true,
                byTitle = emptyMap(),
                byStem = emptyMap()
            )
        }
    }

    data class ReconcileResult(
        val localFavorites: Set<Long>,
        val onlineFavorites: List<OnlineFavoriteEntry>,
        val changed: Boolean
    )

    fun encodeJson(entries: List<OnlineFavoriteEntry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("song", storedOnlineSongToJson(entry.song))
                    put("likedAt", entry.likedAt)
                    if (entry.localSongId > 0L) put("localSongId", entry.localSongId)
                }
            )
        }
        return arr.toString()
    }

    fun decodeJson(raw: String?): List<OnlineFavoriteEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                val seen = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val songObj = obj.optJSONObject("song") ?: continue
                    val stored = storedOnlineSongFromJson(songObj) ?: continue
                    val key = "${stored.source}:${stored.remoteId}"
                    if (!seen.add(key)) continue
                    add(
                        OnlineFavoriteEntry(
                            song = stored,
                            likedAt = obj.optLong("likedAt", 0L),
                            localSongId = obj.optLong("localSongId", 0L).coerceAtLeast(0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 按歌名/歌手/时长对账在线曲目与本地歌曲；兼容全角括号、多歌手分隔及无标签文件名。 */
    fun matchesSong(fav: StoredOnlineSong, candidate: Song): Boolean =
        candidate.isLocal && matchesTrackFields(
            titleA = fav.title,
            artistA = fav.artist,
            durationMsA = fav.durationMs,
            titleB = candidate.title,
            artistB = candidate.artist,
            durationMsB = candidate.durationMs,
            displayNameB = candidate.displayName
        )

    fun matchesSong(a: Song, b: Song): Boolean = when {
        b.isLocal && !a.isLocal -> matchesTrackFields(
            titleA = a.title,
            artistA = a.artist,
            durationMsA = a.durationMs,
            titleB = b.title,
            artistB = b.artist,
            durationMsB = b.durationMs,
            displayNameB = b.displayName
        )
        a.isLocal && !b.isLocal -> matchesTrackFields(
            titleA = b.title,
            artistA = b.artist,
            durationMsA = b.durationMs,
            titleB = a.title,
            artistB = a.artist,
            durationMsB = a.durationMs,
            displayNameB = a.displayName
        )
        else -> matchesTrackFields(
            titleA = a.title,
            artistA = a.artist,
            durationMsA = a.durationMs,
            titleB = b.title,
            artistB = b.artist,
            durationMsB = b.durationMs,
            displayNameB = b.displayName.ifBlank { a.displayName }
        )
    }

    fun matchesRemoteTrack(track: RemoteTrack, candidate: Song): Boolean =
        candidate.isLocal && matchesTrackFields(
            titleA = track.title,
            artistA = track.artist,
            durationMsA = track.durationMs,
            titleB = candidate.title,
            artistB = candidate.artist,
            durationMsB = candidate.durationMs,
            displayNameB = candidate.displayName
        )

    /** 统一比对曲目信息与本地音频（含无标签 FLAC 按文件名 `歌手 - 歌名` 兜底识别）。 */
    fun matchesTrackFields(
        titleA: String,
        artistA: String,
        durationMsA: Long,
        titleB: String,
        artistB: String,
        durationMsB: Long,
        displayNameB: String = ""
    ): Boolean {
        if (matchesDirect(titleA, artistA, durationMsA, titleB, artistB, durationMsB)) return true
        val stem = extractFileStem(displayNameB)
        if (stem.isBlank() || !matchesDuration(durationMsA, durationMsB)) return false

        val expectedStem = OnlineDownloader.fileNameFor(titleA, artistA, "")
        if (titleA.isNotBlank() && artistA.isNotBlank() &&
            normalizeTitle(stem) == normalizeTitle(expectedStem)
        ) {
            return true
        }
        val sepIdx = stem.indexOf(" - ")
        if (sepIdx <= 0) return false
        val left = stem.substring(0, sepIdx)
        val right = stem.substring(sepIdx + 3)
        return matchesDirect(titleA, artistA, durationMsA, right, left, durationMsB) ||
            matchesDirect(titleA, artistA, durationMsA, left, right, durationMsB)
    }

    /** 将本地曲库预建为标准化哈希索引，避免在线列表每次逐首扫描和重复规范化字符串。 */
    fun buildLocalIndex(localSongs: List<Song>): LocalSongIndex {
        if (localSongs.isEmpty()) return LocalSongIndex.EMPTY
        val byTitle = HashMap<String, MutableList<IndexedCandidate>>(localSongs.size)
        val byStem = HashMap<String, MutableList<Long>>(localSongs.size)
        for (song in localSongs) {
            if (!song.isLocal) continue
            val durationMs = song.durationMs
            val titleKey = normalizeTitle(song.title)
            if (titleKey.isNotEmpty()) {
                appendTitleCandidate(byTitle, titleKey, IndexedCandidate(durationMs, parseArtistTokens(song.artist)))
            }
            val stem = extractFileStem(song.displayName)
            if (stem.isBlank()) continue
            val normStem = normalizeTitle(stem)
            if (normStem.isNotEmpty()) {
                appendStemDuration(byStem, normStem, durationMs)
            }
            val sepIdx = stem.indexOf(" - ")
            if (sepIdx <= 0) continue
            val left = stem.substring(0, sepIdx)
            val right = stem.substring(sepIdx + 3)
            val rightKey = normalizeTitle(right)
            if (rightKey.isNotEmpty()) {
                appendTitleCandidate(byTitle, rightKey, IndexedCandidate(durationMs, parseArtistTokens(left)))
            }
            val leftKey = normalizeTitle(left)
            if (leftKey.isNotEmpty()) {
                appendTitleCandidate(byTitle, leftKey, IndexedCandidate(durationMs, parseArtistTokens(right)))
            }
        }
        return LocalSongIndex(
            isLibraryEmpty = false,
            byTitle = byTitle,
            byStem = byStem
        )
    }

    private fun appendTitleCandidate(
        map: MutableMap<String, MutableList<IndexedCandidate>>,
        key: String,
        candidate: IndexedCandidate
    ) {
        val bucket = map.getOrPut(key) { ArrayList(2) }
        if (candidate !in bucket) bucket += candidate
    }

    private fun appendStemDuration(
        map: MutableMap<String, MutableList<Long>>,
        key: String,
        durationMs: Long
    ) {
        val bucket = map.getOrPut(key) { ArrayList(1) }
        if (durationMs !in bucket) bucket += durationMs
    }

    /**
     * 判断在线歌曲是否「已下载或本地曲库已存在同一首歌」：
     * - 优先与当前已扫描的本地曲库（含自行导入、缓存转换、已下载文件）逐首比对；
     * - 本次会话刚下载完成尚未被媒体库扫入前，结合 [sessionDownloadedKeys] 立即隐藏按钮；
     * - 曲库尚未完成首轮扫描时兜底核对历史下载键，避免误闪。
     */
    fun isOnlineSongAvailableLocally(
        song: Song,
        localIndex: LocalSongIndex,
        downloadedKeys: Set<String>,
        sessionDownloadedKeys: Set<String>
    ): Boolean {
        if (song.isLocal || song.isDlna) return true
        return isTrackAvailableWithIndex(
            sourceId = song.source,
            remoteId = song.remoteId,
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            localIndex = localIndex,
            downloadedKeys = downloadedKeys,
            sessionDownloadedKeys = sessionDownloadedKeys
        )
    }

    /** 与 [isOnlineSongAvailableLocally] 共用一套规则，供在线歌单/搜索列表使用索引进行 O(1) 判定。 */
    fun isRemoteTrackAvailableLocally(
        sourceId: String,
        track: RemoteTrack,
        localIndex: LocalSongIndex,
        downloadedKeys: Set<String>,
        sessionDownloadedKeys: Set<String>
    ): Boolean = isTrackAvailableWithIndex(
        sourceId = sourceId,
        remoteId = track.remoteId,
        title = track.title,
        artist = track.artist,
        durationMs = track.durationMs,
        localIndex = localIndex,
        downloadedKeys = downloadedKeys,
        sessionDownloadedKeys = sessionDownloadedKeys
    )

    fun isOnlineSongAvailableLocally(
        song: Song,
        localSongs: List<Song>,
        downloadedKeys: Set<String>,
        sessionDownloadedKeys: Set<String>
    ): Boolean {
        if (song.isLocal || song.isDlna) return true
        if (localSongs.any { it.isLocal && matchesSong(song, it) }) return true
        val key = OnlineDownloader.downloadKeyOf(song.source, song.remoteId) ?: return false
        if (key in sessionDownloadedKeys) return true
        return localSongs.isEmpty() && key in downloadedKeys
    }

    /** 与 [isOnlineSongAvailableLocally] 共用一套规则，供在线歌单/搜索列表判定已下载状态。 */
    fun isRemoteTrackAvailableLocally(
        sourceId: String,
        track: RemoteTrack,
        localSongs: List<Song>,
        downloadedKeys: Set<String>,
        sessionDownloadedKeys: Set<String>
    ): Boolean {
        if (localSongs.any { it.isLocal && matchesRemoteTrack(track, it) }) return true
        val key = OnlineDownloader.downloadKeyOf(sourceId, track.remoteId) ?: return false
        if (key in sessionDownloadedKeys) return true
        return localSongs.isEmpty() && key in downloadedKeys
    }

    private fun isTrackAvailableWithIndex(
        sourceId: String,
        remoteId: String?,
        title: String,
        artist: String,
        durationMs: Long,
        localIndex: LocalSongIndex,
        downloadedKeys: Set<String>,
        sessionDownloadedKeys: Set<String>
    ): Boolean {
        if (!localIndex.isLibraryEmpty && matchesIndexedTrack(title, artist, durationMs, localIndex)) {
            return true
        }
        val key = OnlineDownloader.downloadKeyOf(sourceId, remoteId) ?: return false
        if (key in sessionDownloadedKeys) return true
        return localIndex.isLibraryEmpty && key in downloadedKeys
    }

    private fun matchesIndexedTrack(
        title: String,
        artist: String,
        durationMs: Long,
        localIndex: LocalSongIndex
    ): Boolean {
        val normTitle = normalizeTitle(title)
        if (normTitle.isNotEmpty()) {
            val candidates = localIndex.byTitle[normTitle]
            if (!candidates.isNullOrEmpty()) {
                val tokensA = parseArtistTokens(artist)
                if (candidates.any { matchesCandidate(durationMs, tokensA, it.durationMs, it.artistTokens) }) {
                    return true
                }
            }
        }
        if (title.isNotBlank() && artist.isNotBlank()) {
            val expectedStem = normalizeTitle(OnlineDownloader.fileNameFor(title, artist, ""))
            if (expectedStem.isNotEmpty()) {
                val durations = localIndex.byStem[expectedStem]
                if (!durations.isNullOrEmpty() && durations.any { matchesDuration(durationMs, it) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun matchesDirect(
        titleA: String,
        artistA: String,
        durationMsA: Long,
        titleB: String,
        artistB: String,
        durationMsB: Long
    ): Boolean {
        val tA = normalizeTitle(titleA)
        val tB = normalizeTitle(titleB)
        if (tA.isEmpty() || tA != tB) return false
        return matchesCandidate(
            durationMsA = durationMsA,
            tokensA = parseArtistTokens(artistA),
            durationMsB = durationMsB,
            tokensB = parseArtistTokens(artistB)
        )
    }

    private fun matchesCandidate(
        durationMsA: Long,
        tokensA: Set<String>,
        durationMsB: Long,
        tokensB: Set<String>
    ): Boolean {
        if (!matchesDuration(durationMsA, durationMsB)) return false
        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            return tokensA == tokensB
        }
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return true
        }
        // 单侧缺少艺术家元数据（常见于无标签本地文件）时，仅当双方时长都在且吻合才判定为同一首歌
        return durationMsA > 0L && durationMsB > 0L
    }

    private fun matchesDuration(durationMsA: Long, durationMsB: Long): Boolean =
        durationMsA <= 0L || durationMsB <= 0L || abs(durationMsA - durationMsB) <= 10_000L

    /**
     * 计算对外展示/统计用的收藏 ID 集合：每首在线歌只占 1 个名额（已关联本地 ID 时只计本地 ID，
     * 尚未落盘前计在线负 ID），确保「设置页收藏统计 / 红心高亮 / 收藏列表」永不重复计数。
     */
    fun effectiveFavoriteIds(
        localFavorites: Set<Long>,
        onlineFavorites: List<OnlineFavoriteEntry>
    ): Set<Long> {
        if (onlineFavorites.isEmpty()) return localFavorites
        val out = LinkedHashSet(localFavorites)
        onlineFavorites.forEach { entry ->
            val linkedLocal = entry.localSongId > 0L && entry.localSongId in localFavorites
            if (!linkedLocal) out += entry.song.songId()
        }
        return out
    }

    fun isFavoriteSong(
        song: Song,
        localFavorites: Set<Long>,
        onlineFavorites: List<OnlineFavoriteEntry>
    ): Boolean {
        if (song.id in localFavorites) return true
        if (onlineFavorites.any {
                it.song.songId() == song.id ||
                    (it.localSongId > 0L && it.localSongId == song.id) ||
                    (!song.isLocal && !song.isDlna &&
                        it.song.source == song.source && it.song.remoteId == song.remoteId)
            }
        ) {
            return true
        }
        return song.isLocal && onlineFavorites.any { matchesSong(it.song, song) }
    }

    fun isFavoriteId(
        id: Long,
        localFavorites: Set<Long>,
        onlineFavorites: List<OnlineFavoriteEntry>
    ): Boolean {
        if (id == 0L) return false
        if (id in localFavorites) return true
        return onlineFavorites.any { it.song.songId() == id || it.localSongId == id }
    }

    /**
     * 扫描本地曲库后同步在线收藏与本地 ID 的关联：
     * - 未关联或找不到文件时，若本地出现下载完成的同名曲目则自动迁移收藏到该本地 ID；
     * - 用户在收藏列表取消收藏本地副本时，一并清理对应的在线收藏记录；
     * - 若用户删掉了已下载的本地文件，解绑失效 localSongId 并保留在线收藏以防数据丢失。
     */
    fun reconcileWithLibrary(
        localSongs: List<Song>,
        localFavorites: Set<Long>,
        onlineFavorites: List<OnlineFavoriteEntry>
    ): ReconcileResult {
        if (localSongs.isEmpty() || onlineFavorites.isEmpty()) {
            return ReconcileResult(localFavorites, onlineFavorites, changed = false)
        }
        val localById = localSongs.filter { it.isLocal }.associateBy { it.id }
        if (localById.isEmpty()) {
            return ReconcileResult(localFavorites, onlineFavorites, changed = false)
        }

        val nextLocalFavs = LinkedHashSet(localFavorites)
        val nextOnlineFavs = ArrayList<OnlineFavoriteEntry>(onlineFavorites.size)
        var changed = false

        for (entry in onlineFavorites) {
            val linked = entry.localSongId
            if (linked > 0L) {
                val existingLocal = localById[linked]
                if (existingLocal != null) {
                    if (linked in nextLocalFavs) {
                        nextOnlineFavs += entry
                    } else {
                        // 本地已取消收藏 -> 同步移除在线收藏条目
                        changed = true
                    }
                    continue
                }
                // 原本地 ID 不在当前曲库（例如重扫/移动/重下载）
                val rematched = localSongs.firstOrNull { matchesSong(entry.song, it) }
                if (rematched != null) {
                    val wasLiked = nextLocalFavs.remove(linked)
                    if (wasLiked) nextLocalFavs += rematched.id
                    nextOnlineFavs += entry.copy(localSongId = rematched.id)
                    changed = true
                } else {
                    nextLocalFavs.remove(linked)
                    nextOnlineFavs += entry.copy(localSongId = 0L)
                    changed = true
                }
            } else {
                val matched = localSongs.firstOrNull { matchesSong(entry.song, it) }
                if (matched != null) {
                    nextLocalFavs += matched.id
                    nextOnlineFavs += entry.copy(localSongId = matched.id)
                    changed = true
                } else {
                    nextOnlineFavs += entry
                }
            }
        }

        return ReconcileResult(
            localFavorites = nextLocalFavs,
            onlineFavorites = nextOnlineFavs,
            changed = changed
        )
    }

    private fun normalizeTitle(raw: String): String = normalizePunctuation(raw)
        .lowercase()
        .replace(MULTI_SPACE_REGEX, " ")
        .replace(BRACKET_SPACE_REGEX, " $1")
        .trim()

    private fun parseArtistTokens(raw: String): Set<String> {
        val cleaned = normalizePunctuation(raw).lowercase().replace(MULTI_SPACE_REGEX, " ").trim()
        if (cleaned.isEmpty() || cleaned in UNKNOWN_ARTISTS) return emptySet()
        return cleaned
            .replace(FEAT_REGEX, ",")
            .split(ARTIST_SEP_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in UNKNOWN_ARTISTS }
            .toSet()
    }

    private fun extractFileStem(displayName: String): String {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return ""
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0 && trimmed.length - dot <= 6) trimmed.substring(0, dot) else trimmed
    }

    private fun normalizePunctuation(raw: String): String = raw
        .replace('\u3000', ' ')
        .replace('（', '(')
        .replace('）', ')')
        .replace('［', '[')
        .replace('］', ']')
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')

    private val UNKNOWN_ARTISTS = setOf("unknown", "<unknown>", "未知艺术家", "未知歌手")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val BRACKET_SPACE_REGEX = Regex("\\s*([(\\[])")
    private val ARTIST_SEP_REGEX = Regex("""[,，、/;；&|｜]""")
    private val FEAT_REGEX = Regex("""\s+(?:feat\.?|ft\.?|vs\.?|with)\s+""", RegexOption.IGNORE_CASE)
}
