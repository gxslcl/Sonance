package com.sonance.player.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * 一首本地歌曲。
 *
 * 标 [Immutable] 是性能关键：`uri` 是外部类型（Compose 推断为 unstable），
 * 不标的话 `Song` 会被判成 unstable，`SongRow` 等列表项的参数比较退化为引用相等，
 * 列表滚动 / 重组时开销明显上升。这个类所有字段都是 val，标 Immutable 是成立的。
 */
@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    val folderPath: String,
    val folderName: String,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val track: Int
) {
    val durationText: String get() = formatDuration(durationMs)
}

/** 专辑聚合 */
@Immutable
data class Album(
    val albumId: Long,
    val name: String,
    val artist: String,
    val songs: List<Song>
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val year: Int get() = 0
}

/** 艺术家聚合 */
@Immutable
data class Artist(
    val name: String,
    val songs: List<Song>,
    val albumCount: Int
)

/** 文件夹聚合 */
@Immutable
data class MusicFolder(
    val path: String,
    val name: String,
    val songs: List<Song>
)

enum class SortField(val label: String) {
    TITLE("标题"),
    ARTIST("艺术家"),
    ALBUM("专辑"),
    DURATION("时长"),
    DATE_ADDED("添加时间"),
    FILE_NAME("文件名")
}

enum class ViewMode { LIST, GRID }

enum class LibraryTab(val id: String, val label: String) {
    SONGS("songs", "全部歌曲"),
    ALBUMS("albums", "专辑"),
    ARTISTS("artists", "艺术家"),
    FOLDERS("folders", "文件夹"),
    RECENT("recent", "最近播放"),
    FAVORITES("favorites", "我的收藏"),
    SETTINGS("settings", "皮肤与设置");

    companion object {
        /** 手机底部导航（4 项）。设置不放这里 —— 走右上角齿轮。 */
        val bottomBar = listOf(SONGS, ALBUMS, ARTISTS, FAVORITES)

        /** 侧边导航（完整） */
        val rail = listOf(SONGS, ALBUMS, ARTISTS, FOLDERS, RECENT, FAVORITES, SETTINGS)

        fun fromId(id: String?): LibraryTab = entries.firstOrNull { it.id == id } ?: SONGS
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** 无 ID3 信息时的兜底 */
const val UNKNOWN_ARTIST = "未知艺术家"
const val UNKNOWN_ALBUM = "未知专辑"
