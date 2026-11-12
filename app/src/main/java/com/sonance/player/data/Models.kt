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
    val track: Int,
    /**
     * 音源标识。[LOCAL_SOURCE] 是本地文件；其余是在线音源（见 [OnlineSource.id]）。
     *
     * 给默认值是为了**零回归**：本地歌的所有构造点、JSON 编解码、排序分组
     * 全都不用改，行为和以前一模一样。
     */
    val source: String = LOCAL_SOURCE,
    /** 音源侧的 id（在线歌才有）。本地歌为 null */
    val remoteId: String? = null,
    /**
     * 音源直接给的封面地址（在线歌才有）。
     *
     * 本地歌为 null —— 它们的封面走 MediaStore / 内嵌 / 在线补全那条链路。
     * 在线歌**自带**封面地址，不该再去 iTunes 猜一次（猜中的概率还很低，
     * 因为在线歌的歌名经常带「(Live)」「feat. xxx」这类后缀）。
     */
    val coverUrl: String? = null
) {
    val durationText: String get() = formatDuration(durationMs)

    /** 是不是本地文件。用它来决定要不要走「最近播放 / 播放次数 / 封面补全」这些本地逻辑 */
    val isLocal: Boolean get() = source == LOCAL_SOURCE

    companion object {
        const val LOCAL_SOURCE = "local"
    }
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
    FILE_NAME("文件名"),

    /**
     * 按播放次数排序。
     *
     * 播放次数其实一直在记（每次切歌写一次），但此前**只写不读** —— 属于无效 IO。
     * 这里把它用起来，让"我最常听的是哪些"有地方体现。
     */
    PLAY_COUNT("播放最多"),

    /** 按最近播放时间排序（没播过的排在最后） */
    RECENT_PLAYED("最近播放")
}

enum class ViewMode { LIST, GRID }

/**
 * 歌词要不要跟着背景自动调色。
 *
 * 起因：歌词页的背景是封面模糊后透出来的，有些封面透出来以后正好和歌词撞色，
 * 屏幕上某一段的歌词就糊进背景里了。做法是按每行**在屏幕上的位置**取那一段的
 * 背景色，把歌词的明度挪到刚好看得清（色相不动，所以还是原来那套颜色）。
 *
 * 默认 [ALL] —— 撞色往往只发生在屏幕某一段的那几行，只调当前句解决不了它。
 * 但这件事终究是**动了用户选的那套配色**，所以必须留一个能关的开关。
 */
enum class LyricAdaptMode(val id: String, val label: String, val hint: String) {
    OFF("off", "关闭", "歌词保持皮肤原本的颜色，不跟着背景调"),
    CURRENT_ONLY("current", "只调当前句", "只有唱到的那一句跟着背景调，其余行保持原色（纯文本歌词没有高亮句，此档等同关闭）"),
    ALL("all", "全部", "当前句和其它行都按所在区域调：压在偏亮背景上的那几行会被压暗，偏暗的会被提亮");

    companion object {
        fun fromId(id: String?): LyricAdaptMode = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * 歌词页的**展现模式**。
 *
 * 仅用于全屏歌词；其他入口继续使用经典列表。
 * 旧圆盘的 tilted id 已移除，读取旧设置时回退到经典模式。
 */
enum class LyricStyle(val id: String, val label: String) {
    CLASSIC("classic", "经典"),
    VERTICAL("vertical", "竖屏歌词"),
    FLUID("fluid", "流体声波景深"),
    VINYL("vinyl", "黑胶环形声轨"),
    SPATIAL("spatial", "3D 双向深空星廊");

    companion object {
        fun fromId(id: String?): LyricStyle = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** 全屏歌词底部控制样式，独立于列表页迷你播放器的形态。 */
enum class LyricPlaybarStyle(val id: String, val label: String) {
    BUTTONS("buttons", "三个播放按钮"),
    FLOAT("float", "悬浮播放器");

    companion object {
        fun fromId(id: String?): LyricPlaybarStyle = entries.firstOrNull { it.id == id } ?: BUTTONS
    }
}

/** 播放页、列表播放条与歌词悬浮播放器共用的播放/暂停按钮配色。 */
enum class PlayButtonColorMode(val id: String, val label: String) {
    ORIGINAL("original", "当前风格"),
    THEME("theme", "跟随主题"),
    COVER("cover", "跟随封面");

    companion object {
        fun fromId(id: String?): PlayButtonColorMode = entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/**
 * 播放页封面颜色的「包裹方式」—— 决定颜色从封面的哪一侧向外晕开。
 *
 * 默认 [AROUND]：这是最初的需求本身（「图片四周的颜色渐变到背景上」），
 * 只在下方那一版是当时选项受限的产物。
 */
enum class GlowMode(val id: String, val label: String, val hint: String) {
    BOTTOM("bottom", "下方", "只从封面下边缘向外晕开"),
    SIDES("sides", "左右", "从封面左右两侧向外晕开"),
    AROUND("around", "四周", "封面四条边都向外晕开，上方也带一点"),
    FULL("full", "全屏", "扩散到屏幕边缘才淡出，范围最大"),
    IMMERSIVE("immersive", "沉浸", "整屏铺满封面主色，浓度更深，底部按钮区自动减淡");

    companion object {
        fun fromId(id: String?): GlowMode = entries.firstOrNull { it.id == id } ?: AROUND
    }
}

/**
 * 换歌时晕染颜色的**过渡方式**。
 *
 * 两种都能消除「颜色直接跳变」，区别在于**什么时候换**：
 */
enum class GlowTransition(val id: String, val label: String, val hint: String) {

    /**
     * 跟手：颜色由上下滑动的**滚动位置**驱动。
     *
     * 手指滑到一半，颜色就混到一半；页面滑到位，颜色也正好到位。
     * 按钮切歌走的是同一个分页动画，所以两种切歌方式自动一致。
     * 因为颜色和页面位移是同一件事的两个投影，看起来最「实」。
     */
    SWIPE("swipe", "跟手", "颜色跟着上下滑动的位置走，和页面完全同步"),

    /**
     * 渐变：页面落定之后，颜色在设定时长内补间过去。
     *
     * 颜色变化发生在页面切换**之后**，和位移没有绑定关系；
     * 好处是时长可调，慢一点更柔和。
     */
    FADE("fade", "渐变", "页面切完之后，颜色在设定时长内平滑过渡");

    companion object {
        fun fromId(id: String?): GlowTransition = entries.firstOrNull { it.id == id } ?: SWIPE
    }
}

enum class LibraryTab(val id: String, val label: String) {
    SONGS("songs", "全部歌曲"),
    ALBUMS("albums", "专辑"),
    ARTISTS("artists", "艺术家"),
    FOLDERS("folders", "文件夹"),
    RECENT("recent", "最近播放"),
    ONLINE("online", "在线音乐"),
    FAVORITES("favorites", "我的收藏"),
    SETTINGS("settings", "皮肤与设置");

    companion object {
        /** 侧边导航（完整） */
        val rail = listOf(SONGS, ALBUMS, ARTISTS, FOLDERS, RECENT, ONLINE, FAVORITES, SETTINGS)

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
