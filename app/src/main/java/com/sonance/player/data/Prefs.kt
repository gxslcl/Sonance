package com.sonance.player.data

import android.content.Context
import android.content.SharedPreferences
import com.sonance.player.ui.theme.GlassStyle

/** 轻量持久化：收藏、最近播放、皮肤、排序、视图模式 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sonance_prefs", Context.MODE_PRIVATE)

    var skinId: String
        get() = sp.getString(KEY_SKIN, null) ?: "midnight_amber"
        set(v) = sp.edit().putString(KEY_SKIN, v).apply()

    var sortField: String
        get() = sp.getString(KEY_SORT, SortField.TITLE.name) ?: SortField.TITLE.name
        set(v) = sp.edit().putString(KEY_SORT, v).apply()

    var sortAscending: Boolean
        get() = sp.getBoolean(KEY_SORT_ASC, true)
        set(v) = sp.edit().putBoolean(KEY_SORT_ASC, v).apply()

    var viewMode: String
        get() = sp.getString(KEY_VIEW, ViewMode.LIST.name) ?: ViewMode.LIST.name
        set(v) = sp.edit().putString(KEY_VIEW, v).apply()

    var lastTab: String
        get() = sp.getString(KEY_TAB, LibraryTab.SONGS.id) ?: LibraryTab.SONGS.id
        set(v) = sp.edit().putString(KEY_TAB, v).apply()

    var lastSongId: Long
        get() = sp.getLong(KEY_LAST_SONG, -1L)
        set(v) = sp.edit().putLong(KEY_LAST_SONG, v).apply()

    var scanFilterShortAudio: Boolean
        get() = sp.getBoolean(KEY_FILTER_SHORT, true)
        set(v) = sp.edit().putBoolean(KEY_FILTER_SHORT, v).apply()

    /** 扫描过滤：排除加密容器（按文件头确认，避免误杀已转换的成品） */
    var scanExcludeLocked: Boolean
        get() = sp.getBoolean(KEY_EXCLUDE_LOCKED, true)
        set(v) = sp.edit().putBoolean(KEY_EXCLUDE_LOCKED, v).apply()

    /** 扫描过滤：排除系统解码器不支持的格式（ape / wma / dsf …） */
    var scanExcludeUnsupported: Boolean
        get() = sp.getBoolean(KEY_EXCLUDE_UNSUPPORTED, true)
        set(v) = sp.edit().putBoolean(KEY_EXCLUDE_UNSUPPORTED, v).apply()

    /** 扫描过滤：排除录音 / 系统提示音等非歌曲目录 */
    var scanExcludeNonSongFolders: Boolean
        get() = sp.getBoolean(KEY_EXCLUDE_NON_SONG, true)
        set(v) = sp.edit().putBoolean(KEY_EXCLUDE_NON_SONG, v).apply()

    var crossfadeEnabled: Boolean
        get() = sp.getBoolean(KEY_CROSSFADE, false)
        set(v) = sp.edit().putBoolean(KEY_CROSSFADE, v).apply()

    /** 当前选中的应用图标（对应 manifest 里的 activity-alias 后缀） */
    var appIconId: String
        get() = sp.getString(KEY_APP_ICON, null) ?: "default"
        set(v) = sp.edit().putString(KEY_APP_ICON, v).apply()

    // ===== 律动柱（音频可视化）=====

    /** 0=镜像柱 1=柱状 2=波形 */
    var vizStyle: Int
        get() = sp.getInt(KEY_VIZ_STYLE, 0)
        set(v) = sp.edit().putInt(KEY_VIZ_STYLE, v).apply()

    var vizBarCount: Int
        get() = sp.getInt(KEY_VIZ_BARS, 48)
        set(v) = sp.edit().putInt(KEY_VIZ_BARS, v).apply()

    var vizSensitivity: Float
        get() = sp.getFloat(KEY_VIZ_SENS, 1.0f)
        set(v) = sp.edit().putFloat(KEY_VIZ_SENS, v).apply()

    var vizMirror: Boolean
        get() = sp.getBoolean(KEY_VIZ_MIRROR, true)
        set(v) = sp.edit().putBoolean(KEY_VIZ_MIRROR, v).apply()

    /** 在律动柱左下角显示实时帧率（排查性能用） */
    var vizShowFps: Boolean
        get() = sp.getBoolean(KEY_VIZ_FPS, false)
        set(v) = sp.edit().putBoolean(KEY_VIZ_FPS, v).apply()

    // ===== 在线歌词 =====

    /**
     * 是否允许联网取歌词。**默认关闭** —— 这个播放器的定位是纯本地、不联网，
     * 联网取词必须由用户显式打开。
     */
    var onlineLyricsEnabled: Boolean
        get() = sp.getBoolean(KEY_ONLINE_LYRICS, false)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_LYRICS, v).apply()

    /** 歌词服务地址，默认 LrcAPI 的公开实例；可改成自建地址 */
    var lyricsApiBase: String
        get() = sp.getString(KEY_LYRICS_API, null) ?: "https://api.lrc.cx/lyrics"
        set(v) = sp.edit().putString(KEY_LYRICS_API, v).apply()

    /**
     * 歌词里的繁体自动转简体。默认开启 ——
     * 在线歌词源返回的不少是繁体，直接显示对简体用户不友好。
     * 只影响展示，缓存里存的仍是原始文本，关掉开关无需重新联网。
     */
    var lyricsConvertToSimplified: Boolean
        get() = sp.getBoolean(KEY_LYRICS_T2S, true)
        set(v) = sp.edit().putBoolean(KEY_LYRICS_T2S, v).apply()

    /** 歌词字号（sp），范围 14~30 */
    var lyricsFontSizeSp: Int
        get() = sp.getInt(KEY_LYRICS_FONT, DEFAULT_LYRICS_FONT).coerceIn(14, 30)
        set(v) = sp.edit().putInt(KEY_LYRICS_FONT, v.coerceIn(14, 30)).apply()

    // ===== 歌词页背景玻璃 =====

    /** 玻璃样式：[GlassStyle] 的 ordinal */
    var glassStyle: Int
        get() = sp.getInt(KEY_GLASS_STYLE, GlassStyle.NORMAL.ordinal)
        set(v) = sp.edit().putInt(KEY_GLASS_STYLE, v).apply()

    /** 模糊半径 dp，0~80 */
    var glassBlurDp: Int
        get() = sp.getInt(KEY_GLASS_BLUR, GlassStyle.NORMAL.defaultBlurDp).coerceIn(0, 80)
        set(v) = sp.edit().putInt(KEY_GLASS_BLUR, v.coerceIn(0, 80)).apply()

    /** 背景可见度 0~100 */
    var glassVisibility: Int
        get() = sp.getInt(KEY_GLASS_VIS, GlassStyle.NORMAL.defaultVisibility).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_GLASS_VIS, v.coerceIn(0, 100)).apply()

    // ===== 在线封面（默认关闭，与在线歌词同一原则）=====

    var onlineCoverEnabled: Boolean
        get() = sp.getBoolean(KEY_COVER_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_COVER_ENABLED, v).apply()

    /** 手动指定过的封面：songId → 图片 URL（覆盖自动匹配的结果） */
    fun pinnedCoverUrl(songId: Long): String? = sp.getString("$KEY_COVER_PIN$songId", null)

    fun pinCoverUrl(songId: Long, url: String?) {
        if (url == null) sp.edit().remove("$KEY_COVER_PIN$songId").apply()
        else sp.edit().putString("$KEY_COVER_PIN$songId", url).apply()
    }


    // ===== 曲库隐藏（清理重复 / 同名歌曲只从列表隐藏，不动文件）=====

    /** 被隐藏的曲目 id，逗号分隔 */
    var hiddenSongIds: String
        get() = sp.getString(KEY_HIDDEN_IDS, null) ?: ""
        set(v) = sp.edit().putString(KEY_HIDDEN_IDS, v).apply()

    fun hiddenIdSet(): Set<Long> {
        val raw = hiddenSongIds
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    // ===== 歌曲库缓存：上次扫描结果 JSON + 时间戳 =====
    // 避免每次启动都重新扫 MediaStore（耗时 + 影响体验）
    var songsCacheJson: String?
        get() = sp.getString(KEY_SONGS_CACHE, null)
        set(v) = sp.edit().putString(KEY_SONGS_CACHE, v).apply()

    var lastScanAt: Long
        get() = sp.getLong(KEY_SCAN_AT, 0L)
        set(v) = sp.edit().putLong(KEY_SCAN_AT, v).apply()

    /** 用户授权用于检查不可播放文件的目录（SAF tree uri） */
    var compatTreeUri: String?
        get() = sp.getString(KEY_COMPAT_TREE, null)
        set(v) = sp.edit().putString(KEY_COMPAT_TREE, v).apply()

    var compatTreeLabel: String?
        get() = sp.getString(KEY_COMPAT_TREE_LABEL, null)
        set(v) = sp.edit().putString(KEY_COMPAT_TREE_LABEL, v).apply()

    /** 还原输出目录（SAF tree uri） */
    var restoreOutputTreeUri: String?
        get() = sp.getString(KEY_RESTORE_OUT_URI, null)
        set(v) = sp.edit().putString(KEY_RESTORE_OUT_URI, v).apply()

    var restoreOutputTreeLabel: String?
        get() = sp.getString(KEY_RESTORE_OUT_LABEL, null)
        set(v) = sp.edit().putString(KEY_RESTORE_OUT_LABEL, v).apply()

    // ===== 收藏 =====
    private fun favSet(): MutableSet<String> =
        (sp.getStringSet(KEY_FAV, emptySet()) ?: emptySet()).toMutableSet()

    var favorites: Set<Long>
        get() = favSet().mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putStringSet(KEY_FAV, v.map { it.toString() }.toSet()).apply()

    fun toggleFavorite(id: Long): Boolean {
        val set = favSet()
        val liked = if (set.contains(id.toString())) {
            set.remove(id.toString()); false
        } else {
            set.add(id.toString()); true
        }
        sp.edit().putStringSet(KEY_FAV, set).apply()
        return liked
    }

    // ===== 最近播放（有序，最新在前）=====
    fun recentIds(limit: Int = 200): List<Long> =
        (sp.getString(KEY_RECENT, "") ?: "").split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .take(limit)

    fun pushRecent(id: Long) {
        val list = recentIds().toMutableList()
        list.remove(id)
        list.add(0, id)
        sp.edit().putString(KEY_RECENT, list.take(200).joinToString(",")).apply()
    }

    fun clearRecent() = sp.edit().remove(KEY_RECENT).apply()

    // ===== 播放统计 =====
    fun playCount(id: Long): Int = sp.getInt("$KEY_COUNT$id", 0)

    fun bumpPlayCount(id: Long) {
        sp.edit().putInt("$KEY_COUNT$id", playCount(id) + 1).apply()
    }

    /** 在“最近播放”里按最近使用顺序重排歌曲列表 */
    fun orderByRecent(songs: List<Song>): List<Song> {
        val order = recentIds().withIndex().associate { (i, id) -> id to i }
        return songs.filter { order.containsKey(it.id) }
            .sortedBy { order[it.id] }
    }

    companion object {
        private const val KEY_SKIN = "skin_id"
        private const val KEY_SORT = "sort_field"
        private const val KEY_SORT_ASC = "sort_asc"
        private const val KEY_VIEW = "view_mode"
        private const val KEY_TAB = "last_tab"
        private const val KEY_FAV = "favorites"
        private const val KEY_RECENT = "recent"
        private const val KEY_LAST_SONG = "last_song"
        private const val KEY_COUNT = "play_count_"
        private const val KEY_FILTER_SHORT = "filter_short"
        private const val KEY_EXCLUDE_LOCKED = "scan_exclude_locked"
        private const val KEY_EXCLUDE_UNSUPPORTED = "scan_exclude_unsupported"
        private const val KEY_EXCLUDE_NON_SONG = "scan_exclude_nonsong"
        private const val KEY_CROSSFADE = "crossfade"
        private const val KEY_APP_ICON = "app_icon_id"
        private const val KEY_VIZ_STYLE = "viz_style"
        private const val KEY_VIZ_BARS = "viz_bars"
        private const val KEY_VIZ_SENS = "viz_sensitivity"
        private const val KEY_VIZ_MIRROR = "viz_mirror"
        private const val KEY_VIZ_FPS = "viz_show_fps"
        private const val KEY_ONLINE_LYRICS = "online_lyrics_enabled"
        private const val KEY_LYRICS_API = "lyrics_api_base"
        private const val KEY_LYRICS_T2S = "lyrics_t2s"
        private const val KEY_LYRICS_FONT = "lyrics_font_sp"
        private const val KEY_GLASS_STYLE = "glass_style"
        private const val KEY_GLASS_BLUR = "glass_blur_dp"
        private const val KEY_GLASS_VIS = "glass_visibility"
        private const val KEY_COVER_ENABLED = "online_cover_enabled"
        private const val KEY_COVER_PIN = "cover_pin_"

        const val DEFAULT_LYRICS_FONT = 18
        const val MIN_LYRICS_FONT = 14
        const val MAX_LYRICS_FONT = 30
        private const val KEY_HIDDEN_IDS = "hidden_song_ids"
        private const val KEY_SONGS_CACHE = "songs_cache_json"
        private const val KEY_SCAN_AT = "last_scan_at"
        private const val KEY_COMPAT_TREE = "compat_tree_uri"
        private const val KEY_COMPAT_TREE_LABEL = "compat_tree_label"
        private const val KEY_RESTORE_OUT_URI = "restore_out_uri"
        private const val KEY_RESTORE_OUT_LABEL = "restore_out_label"
    }
}
