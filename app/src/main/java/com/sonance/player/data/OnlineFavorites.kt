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

    /** 按歌名/歌手对账在线收藏与已下载的本地歌曲；两者时长都在且相差过大时排除错配。 */
    fun matchesSong(fav: StoredOnlineSong, candidate: Song): Boolean {
        if (!candidate.isLocal) return false
        val favTitle = normalizeField(fav.title)
        val candTitle = normalizeField(candidate.title)
        if (favTitle.isEmpty() || favTitle != candTitle) return false

        val favArtist = normalizeField(fav.artist)
        val candArtist = normalizeField(candidate.artist)
        if (favArtist.isNotEmpty() && candArtist.isNotEmpty() && favArtist != candArtist) {
            return false
        }
        if (fav.durationMs > 0L && candidate.durationMs > 0L &&
            abs(fav.durationMs - candidate.durationMs) > 10_000L
        ) {
            return false
        }
        return true
    }

    fun matchesSong(a: Song, b: Song): Boolean {
        val t1 = normalizeField(a.title)
        val t2 = normalizeField(b.title)
        if (t1.isEmpty() || t1 != t2) return false
        val a1 = normalizeField(a.artist)
        val a2 = normalizeField(b.artist)
        if (a1.isNotEmpty() && a2.isNotEmpty() && a1 != a2) return false
        return true
    }

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

    private fun normalizeField(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")
}
