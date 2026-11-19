package com.sonance.player.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自建歌单里的一首歌。
 *
 * 本地歌只存 id；在线歌**必须带完整信息**（[StoredOnlineSong]）——
 * 在线歌 id 是哈希出来的负数，冷启动后曲库里查不到，只存 id 会变成
 * 「看得见、点不开」的幽灵条目（与最近播放同一规则）。
 */
data class PlaylistEntry(
    val localId: Long = 0L,
    val online: StoredOnlineSong? = null
) {
    val isOnline: Boolean get() = online != null
    val songId: Long get() = online?.songId() ?: localId

    fun toJson(): JSONObject = JSONObject().apply {
        if (online != null) put("online", storedOnlineSongToJson(online)) else put("localId", localId)
    }

    companion object {
        fun fromJson(o: JSONObject): PlaylistEntry? {
            val onlineJson = o.optJSONObject("online")
            if (onlineJson != null) {
                val song = storedOnlineSongFromJson(onlineJson) ?: return null
                return PlaylistEntry(online = song)
            }
            val id = o.optLong("localId", 0L)
            return if (id > 0L) PlaylistEntry(localId = id) else null
        }
    }
}

/** 一个用户自建歌单。entries 顺序即播放顺序。 */
data class UserPlaylist(
    val id: String,
    val name: String,
    val desc: String,
    val createdAt: Long,
    val entries: List<PlaylistEntry>
) {
    val songIds: List<Long> get() = entries.map { it.songId }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("desc", desc)
        put("createdAt", createdAt)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        put("entries", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): UserPlaylist? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { PlaylistEntry.fromJson(it) }?.let { add(it) }
                }
            }
            return UserPlaylist(
                id = id,
                name = name,
                desc = o.optString("desc"),
                createdAt = o.optLong("createdAt"),
                entries = entries
            )
        }
    }
}

/** JSON 编解码抽成纯函数，JVM 单测直接钉住「存了什么、读回什么」。 */
object UserPlaylistsCodec {
    fun encode(list: List<UserPlaylist>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decode(raw: String?): List<UserPlaylist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { UserPlaylist.fromJson(it) }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** 自建歌单持久化（JSON-in-SP，与收藏/最近播放同一惯例）。 */
class UserPlaylistStore(private val sp: SharedPreferences) {

    var playlists: List<UserPlaylist>
        get() = UserPlaylistsCodec.decode(sp.getString(KEY, null))
        set(v) {
            sp.edit().putString(KEY, UserPlaylistsCodec.encode(v)).apply()
        }

    fun byId(id: String): UserPlaylist? = playlists.firstOrNull { it.id == id }

    fun create(name: String, desc: String, entries: List<PlaylistEntry> = emptyList()): UserPlaylist {
        val pl = UserPlaylist(
            id = "upl-" + java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            desc = desc.trim(),
            createdAt = System.currentTimeMillis(),
            entries = dedupe(entries)
        )
        playlists = listOf(pl) + playlists
        return pl
    }

    fun delete(id: String) {
        playlists = playlists.filterNot { it.id == id }
    }

    fun rename(id: String, name: String, desc: String) {
        update(id) { it.copy(name = name.trim(), desc = desc.trim()) }
    }

    /** 有新增才返回 true；全部已在歌单里返回 false（UI 据此提示「已加入」或「已在歌单」）。 */
    fun addSongs(id: String, entries: List<PlaylistEntry>): Boolean {
        val pl = byId(id) ?: return false
        val existing = pl.songIds.toHashSet()
        val fresh = dedupe(entries).filterNot { it.songId in existing }
        if (fresh.isEmpty()) return false
        update(id) { it.copy(entries = it.entries + fresh) }
        return true
    }

    fun removeSongs(id: String, songIds: Collection<Long>) {
        val set = songIds.toHashSet()
        update(id) { it.copy(entries = it.entries.filterNot { e -> e.songId in set }) }
    }

    fun containsAll(id: String, songIds: Collection<Long>): Boolean {
        val have = byId(id)?.songIds?.toHashSet() ?: return false
        return songIds.isNotEmpty() && songIds.all { it in have }
    }

    private fun update(id: String, transform: (UserPlaylist) -> UserPlaylist) {
        playlists = playlists.map { if (it.id == id) transform(it) else it }
    }

    private fun dedupe(entries: List<PlaylistEntry>): List<PlaylistEntry> {
        val seen = HashSet<Long>()
        return entries.filter { seen.add(it.songId) }
    }

    companion object {
        private const val KEY = "user_playlists"
    }
}

/**
 * 导出 .m3u8 文本。本地歌写文件路径（文件夹 + 文件名），
 * 在线歌没有本地文件，写网易云歌曲链接占位（注释里说明）。
 */
fun playlistToM3u(name: String, songs: List<Song>): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#PLAYLIST:$name")
    for (s in songs) {
        val seconds = (s.durationMs / 1000).coerceAtLeast(0)
        appendLine("#EXTINF:$seconds,${s.artist} - ${s.title}")
        if (s.isLocal) {
            appendLine(s.folderPath.trimEnd('/', '\\') + "/" + s.displayName)
        } else if (s.source == "netease" && s.remoteId != null) {
            appendLine("https://music.163.com/song?id=${s.remoteId}")
        } else {
            appendLine("# 在线歌曲（${s.source}）：${s.artist} - ${s.title}")
        }
    }
}