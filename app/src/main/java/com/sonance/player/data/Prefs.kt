package com.sonance.player.data

import android.content.Context
import android.content.SharedPreferences
import com.sonance.player.playback.EqPreset
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

    /** 柱子配色档位，0 = 跟随皮肤 */
    var vizColorIndex: Int
        get() = sp.getInt(KEY_VIZ_COLOR, 0)
        set(v) = sp.edit().putInt(KEY_VIZ_COLOR, v).apply()

    /** 柱子宽度：占单个槽位的比例，0.25 ~ 1.0 */
    var vizBarWidth: Float
        get() = sp.getFloat(KEY_VIZ_BARW, 0.62f)
        set(v) = sp.edit().putFloat(KEY_VIZ_BARW, v.coerceIn(0.25f, 1f)).apply()

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

    // ===== 一次性迁移 =====

    /**
     * 把「默认值改了」这件事对**已经装过旧版本的用户**也生效。
     *
     * 只改 `getX()` 的默认值只对新装机有用：老用户的 SharedPreferences 里已经存了旧值，
     * 默认值根本不会被读到。所以这里显式搬一次，用标志位保证只做一次 ——
     * 之后用户在设置里怎么调，都不会被覆盖回来。
     *
     * 在 [com.sonance.player.SonanceApplication.onCreate] 里调用。
     */
    fun migrateOnce() {
        val ed = sp.edit()

        // 歌词字号：反馈偏小，整体抬 2sp。
        // 没写过字号的吃新默认 20sp（旧默认 18）；写过的一律 +2（夹在 14~30）。
        // 于是无论之前有没有手动调过，看到的都会比现在大 2。
        if (!sp.getBoolean(KEY_LYRICS_FONT_BUMPED, false)) {
            if (sp.contains(KEY_LYRICS_FONT)) {
                val v = sp.getInt(KEY_LYRICS_FONT, DEFAULT_LYRICS_FONT)
                ed.putInt(KEY_LYRICS_FONT, (v + 2).coerceIn(MIN_LYRICS_FONT, MAX_LYRICS_FONT))
            }
            ed.putBoolean(KEY_LYRICS_FONT_BUMPED, true)
        }

        // 歌词页背景：默认不再铺背景（模糊封面 / 渐变都不要）。
        // 之前选过样式的用户也一并清成「关闭」。
        if (!sp.getBoolean(KEY_GLASS_CLEARED, false)) {
            if (sp.contains(KEY_GLASS_STYLE)) {
                ed.putInt(KEY_GLASS_STYLE, GlassStyle.OFF.ordinal)
            }
            ed.putBoolean(KEY_GLASS_CLEARED, true)
        }

        ed.apply()
    }

    // ===== 歌词页背景玻璃 =====

    /**
     * 玻璃样式：[GlassStyle] 的 ordinal。
     *
     * 默认是**关闭**：歌词页不铺背景。想要的话在设置里打开（见 [migrateOnce]）。
     */
    var glassStyle: Int
        get() = sp.getInt(KEY_GLASS_STYLE, GlassStyle.OFF.ordinal)
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


    // ===== 均衡器 =====

    /** 均衡器总开关。默认关闭 —— 不动用户的原始音色 */
    var eqEnabled: Boolean
        get() = sp.getBoolean(KEY_EQ_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_EQ_ENABLED, v).apply()

    /** 当前预设名（[EqPreset] 的 `name`），脏数据回落到 `OFF` */
    var eqPreset: String
        get() = sp.getString(KEY_EQ_PRESET, null) ?: EqPreset.OFF.name
        set(v) = sp.edit().putString(KEY_EQ_PRESET, v).apply()

    /**
     * 手动拖动后的各段增益（dB，逗号分隔）。
     *
     * 只有用户真的拖过滑杆才会写这里；点预设时不写，让它继续跟预设走 ——
     * 否则以后想微调预设曲线，老用户的旧数值会一直盖着。
     */
    var eqCustomBands: String
        get() = sp.getString(KEY_EQ_BANDS, null) ?: ""
        set(v) = sp.edit().putString(KEY_EQ_BANDS, v).apply()

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
    /**
     * 最近播放的顺序表：songId -> 序号（越小越近）。
     *
     * 抽出来给排序用；顺带说明为什么删掉了原来的 `orderByRecent(songs)` ——
     * 那个函数全项目零调用，是死代码，而它唯一有价值的中间结果就是这个 map。
     */
    fun recentOrderMap(): Map<Long, Int> =
        recentIds().withIndex().associate { (i, id) -> id to i }

    /**
     * 一次读出所有播放次数。
     *
     * 不要逐首调 [playCount] —— 那是 300+ 次 SharedPreferences 查询（每次都要加锁 + 解析 XML 缓存），
     * 在排序热路径上会明显卡。这里只遍历一遍 `sp.all`。
     */
    fun allPlayCounts(): Map<Long, Int> = buildMap {
        for ((k, v) in sp.all) {
            if (!k.startsWith(KEY_COUNT)) continue
            val id = k.removePrefix(KEY_COUNT).toLongOrNull() ?: continue
            (v as? Int)?.let { put(id, it) }
        }
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
        private const val KEY_APP_ICON = "app_icon_id"
        private const val KEY_VIZ_STYLE = "viz_style"
        private const val KEY_VIZ_BARS = "viz_bars"
        private const val KEY_VIZ_SENS = "viz_sensitivity"
        private const val KEY_VIZ_MIRROR = "viz_mirror"
        private const val KEY_VIZ_COLOR = "viz_color_index"
        private const val KEY_VIZ_BARW = "viz_bar_width"
        private const val KEY_VIZ_FPS = "viz_show_fps"
        private const val KEY_ONLINE_LYRICS = "online_lyrics_enabled"
        private const val KEY_LYRICS_API = "lyrics_api_base"
        private const val KEY_LYRICS_T2S = "lyrics_t2s"
        private const val KEY_LYRICS_FONT = "lyrics_font_sp"
        private const val KEY_LYRICS_FONT_BUMPED = "lyrics_font_bumped"
        private const val KEY_GLASS_CLEARED = "glass_cleared"
        private const val KEY_GLASS_STYLE = "glass_style"
        private const val KEY_GLASS_BLUR = "glass_blur_dp"
        private const val KEY_GLASS_VIS = "glass_visibility"
        private const val KEY_COVER_ENABLED = "online_cover_enabled"
        private const val KEY_COVER_PIN = "cover_pin_"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_BANDS = "eq_custom_bands"

        /** 歌词字号默认值 20sp（旧版是 18sp，反馈偏小，见 [migrateOnce]） */
        const val DEFAULT_LYRICS_FONT = 20
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
