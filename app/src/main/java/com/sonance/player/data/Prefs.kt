package com.sonance.player.data

import android.content.Context
import android.content.SharedPreferences
import com.sonance.player.playback.EqPreset
import com.sonance.player.ui.theme.GlassStyle
import org.json.JSONArray
import org.json.JSONObject

/** 轻量持久化：收藏、最近播放、皮肤、排序、视图模式 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sonance_prefs", Context.MODE_PRIVATE)

    /** 播放页封面颜色往背景上晕开的包裹方式（见 [GlowMode]） */
    var glowMode: String
        get() = sp.getString(KEY_GLOW_MODE, null) ?: GlowMode.AROUND.id
        set(v) = sp.edit().putString(KEY_GLOW_MODE, v).apply()

    /**
     * 晕染浓度。
     *
     * 默认 0.60 是「能一眼看出来、但不抢封面」的档位 ——
     * 早先默认 0.10 那版太含蓄，用户以为功能根本没做出来。
     */
    var glowStrength: Float
        get() = sp.getFloat(KEY_GLOW_STRENGTH, DEFAULT_GLOW_STRENGTH)
            .coerceIn(MIN_GLOW_STRENGTH, MAX_GLOW_STRENGTH)
        set(v) = sp.edit()
            .putFloat(KEY_GLOW_STRENGTH, v.coerceIn(MIN_GLOW_STRENGTH, MAX_GLOW_STRENGTH))
            .apply()

    /** 换歌时颜色的过渡方式（见 [GlowTransition]）。默认「跟手」—— 和页面位移同步，观感最实 */
    var glowTransition: String
        get() = sp.getString(KEY_GLOW_TRANSITION, null) ?: GlowTransition.SWIPE.id
        set(v) = sp.edit().putString(KEY_GLOW_TRANSITION, v).apply()

    /**
     * 「渐变」模式下的补间时长（毫秒）。只在 [glowTransition] 为 FADE 时有意义。
     *
     * 默认 420：再短（<250）看着还像硬切，再长（>900）会明显滞后于已经切过去的封面。
     */
    var glowFadeMs: Int
        get() = sp.getInt(KEY_GLOW_FADE_MS, DEFAULT_GLOW_FADE_MS)
            .coerceIn(MIN_GLOW_FADE_MS, MAX_GLOW_FADE_MS)
        set(v) = sp.edit()
            .putInt(KEY_GLOW_FADE_MS, v.coerceIn(MIN_GLOW_FADE_MS, MAX_GLOW_FADE_MS))
            .apply()

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

    // ===== 上次的播放现场（冷启动恢复用）=====

    /**
     * 上次的播放队列（歌曲 id，逗号分隔）。
     *
     * 存整个队列而不是只存一首：恢复之后「上一首 / 下一首 / 队列」都还能用 ——
     * 只恢复一首的话队列里就孤零零一条，点下一首直接没反应。
     */
    var playQueueIds: String
        get() = sp.getString(KEY_PLAY_QUEUE, null) ?: ""
        set(v) = sp.edit().putString(KEY_PLAY_QUEUE, v).apply()

    /** 上次播到队列里的第几首 */
    var playQueueIndex: Int
        get() = sp.getInt(KEY_PLAY_INDEX, 0)
        set(v) = sp.edit().putInt(KEY_PLAY_INDEX, v).apply()

    /** 上次离开时的播放进度（毫秒），下次接着听 */
    var playPositionMs: Long
        get() = sp.getLong(KEY_PLAY_POS, 0L)
        set(v) = sp.edit().putLong(KEY_PLAY_POS, v).apply()

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

    // ===== 律动柱物理手感（见 playback/VizPhysics）=====

    /**
     * 上升系数：**每 1/60 秒**逼近目标的比例。
     *
     * 语义定在 60fps 基准上是为了和面板上的数字直觉一致；执行时按 dt 换算，
     * 所以 120Hz 下的实际手感是同一个。
     */
    var vizAttack: Float
        get() = sp.getFloat(KEY_VIZ_ATTACK, 0.62f).coerceIn(0.15f, 0.95f)
        set(v) = sp.edit().putFloat(KEY_VIZ_ATTACK, v.coerceIn(0.15f, 0.95f)).apply()

    /** 空气阻尼系数（1/秒）。越大下落越"黏"，自由落体感越弱 */
    var vizDecayDamping: Float
        get() = sp.getFloat(KEY_VIZ_DAMPING, 3.4f).coerceIn(0.5f, 8f)
        set(v) = sp.edit().putFloat(KEY_VIZ_DAMPING, v.coerceIn(0.5f, 8f)).apply()

    /** 重力加速度（单位/秒²）。越大落得越快 */
    var vizGravity: Float
        get() = sp.getFloat(KEY_VIZ_GRAVITY, 6.5f).coerceIn(1f, 15f)
        set(v) = sp.edit().putFloat(KEY_VIZ_GRAVITY, v.coerceIn(1f, 15f)).apply()

    /** 峰值悬停时长，单位是 **60fps 基准的帧数**（内部换算成秒，保证帧率无关） */
    var vizPeakHoldTicks: Int
        get() = sp.getInt(KEY_VIZ_PEAK_HOLD, 22).coerceIn(0, 90)
        set(v) = sp.edit().putInt(KEY_VIZ_PEAK_HOLD, v.coerceIn(0, 90)).apply()

    /** 是否画峰值条 */
    var vizShowPeak: Boolean
        get() = sp.getBoolean(KEY_VIZ_PEAK, true)
        set(v) = sp.edit().putBoolean(KEY_VIZ_PEAK, v).apply()

    /** EMA 平滑系数 0..1。越小越平滑但越迟钝，低于 0.3 会明显削弱打击感 */
    var vizEmaAlpha: Float
        get() = sp.getFloat(KEY_VIZ_EMA, 0.55f).coerceIn(0.1f, 1f)
        set(v) = sp.edit().putFloat(KEY_VIZ_EMA, v.coerceIn(0.1f, 1f)).apply()

    /** 在律动柱左下角显示实时帧率（排查性能用） */
    var vizShowFps: Boolean
        get() = sp.getBoolean(KEY_VIZ_FPS, false)
        set(v) = sp.edit().putBoolean(KEY_VIZ_FPS, v).apply()

    /**
     * 律动柱页面在歌名上方显示 3 行歌词。
     *
     * 默认**开**：那块本来就是空白，歌词填进去既有信息量又不挡可视化的主体
     * （只占 3 行，落在那条底部压暗的带子里）。没有歌词时不显示，不会留一行占位文字。
     */
    var vizShowLyrics: Boolean
        get() = sp.getBoolean(KEY_VIZ_LYRICS, true)
        set(v) = sp.edit().putBoolean(KEY_VIZ_LYRICS, v).apply()

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

    // ===== 在线音乐（默认关闭，与在线歌词 / 在线封面同一原则）=====

    /**
     * 是否启用在线音源（搜索 + 播放）。**默认关闭**。
     *
     * 关掉时「在线音乐」页只显示一个开启入口，**不发任何网络请求**。
     * 和在线歌词、在线封面保持一致的原则：联网必须由用户显式打开。
     */
    var onlineMusicEnabled: Boolean
        get() = sp.getBoolean(KEY_ONLINE_MUSIC, false)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_MUSIC, v).apply()

    /**
     * 选中的音源 id（见 [OnlineSources]）。
     *
     * 存字符串而不是下标：以后音源清单增删时，下标会串位，字符串不会。
     */
    var onlineSourceId: String
        get() = sp.getString(KEY_ONLINE_SOURCE, null)
            ?: OnlineSources.all.firstOrNull()?.id.orEmpty()
        set(v) = sp.edit().putString(KEY_ONLINE_SOURCE, v).apply()

    /**
     * 在线搜索结果里，把**播不了的**过滤掉。**默认打开**。
     *
     * 默认开，是因为会员歌点了只会失败：它的外链 302 到一张 HTML 下载页，
     * ExoPlayer 解析不了、报的是"容器不受支持"，用户会误以为是自己文件格式的问题。
     *
     * 想看完整列表（比如只是确认这首歌存不存在）可以关掉 —— 那时不可播的
     * 仍然会打上徽标，不会让人误点。
     *
     * 只影响**显示**，不影响结果集本身、也不影响分页条数。
     */
    var onlineHideUnplayable: Boolean
        get() = sp.getBoolean(KEY_ONLINE_HIDE_UNPLAYABLE, true)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_HIDE_UNPLAYABLE, v).apply()

    /**
     * 用网易云补全**本地歌**的封面与歌词。**默认关闭**。
     *
     * 为什么需要它：本地曲库的封面和歌词经常取不到 —— 内嵌标签缺失、
     * iTunes 对华语曲库覆盖差（还经常返回同名翻唱的封面）、LrcAPI 偶发没有。
     * 网易云那边命中率高得多，而且歌词**免登录、带翻译**。
     *
     * 和「在线封面」「在线歌词」是并列的第三个联网开关，同样是默认关。
     */
    var neteaseFillEnabled: Boolean
        get() = sp.getBoolean(KEY_NETEASE_FILL, false)
        set(v) = sp.edit().putBoolean(KEY_NETEASE_FILL, v).apply()

    // ===== 播放并下载 =====

    /**
     * 播放在线歌时自动存一份到本地。**默认关闭**。
     *
     * 存的是**正在播的那条流**（标准音质），不是官方下载接口给的高音质版本 ——
     * 这是刻意的：不去碰那些需要登录 / 会员的下载端点。
     */
    var onlineDownloadEnabled: Boolean
        get() = sp.getBoolean(KEY_DL_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_DL_ENABLED, v).apply()

    /**
     * 只在 Wi-Fi 下下载。**默认开** ——
     * 不然在移动网络下随便听几张专辑就悄悄吃掉几百 MB。
     */
    var onlineDownloadWifiOnly: Boolean
        get() = sp.getBoolean(KEY_DL_WIFI_ONLY, true)
        set(v) = sp.edit().putBoolean(KEY_DL_WIFI_ONLY, v).apply()

    /**
     * 已下载过的歌，键是 `音源:远端id`（见 `downloadKeyOf`）。
     *
     * 用这个而不是"文件在不在"来判断重复：文件可能被用户删掉、也可能被移走，
     * 而这两个字段就在 `Song` 里，判断成本几乎为零。
     */
    fun downloadedKeys(): MutableSet<String> =
        (sp.getStringSet(KEY_DL_KEYS, emptySet()) ?: emptySet()).toMutableSet()

    fun markDownloaded(key: String) {
        val set = downloadedKeys()
        if (set.add(key)) sp.edit().putStringSet(KEY_DL_KEYS, set).apply()
    }

    fun forgetDownloaded(key: String) {
        val set = downloadedKeys()
        if (set.remove(key)) sp.edit().putStringSet(KEY_DL_KEYS, set).apply()
    }

    fun clearDownloadedKeys() = sp.edit().remove(KEY_DL_KEYS).apply()


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

    /**
     * 「最近播放」里的一条。
     *
     * 在线歌**必须带完整信息**（[StoredOnlineSong]），不能只存 id ——
     * 在线歌的 id 是从 (音源, 远端 id) 哈希出来的负数，冷启动后本地曲库里
     * 查不到任何歌；只存 id 会得到一条"看得见、点不开、还删不掉"的幽灵记录。
     */
    data class RecentEntry(
        /** 本地歌的 id；在线歌为 0 */
        val localId: Long = 0L,
        /** 在线歌的完整信息；本地歌为 null */
        val online: StoredOnlineSong? = null,
        /** 记进来的时间。列表顺序本身就是时间顺序，这个字段供将来按时间筛选用 */
        val at: Long = 0L
    ) {
        val isOnline: Boolean get() = online != null

        fun toJson(): JSONObject = JSONObject().apply {
            if (online != null) {
                put("online", storedOnlineSongToJson(online))
            } else {
                put("localId", localId)
            }
            put("at", at)
        }
    }

    private fun recentEntryFromJson(o: JSONObject): RecentEntry? {
        val onlineJson = o.optJSONObject("online")
        if (onlineJson != null) {
            val song = storedOnlineSongFromJson(onlineJson) ?: return null
            return RecentEntry(online = song, at = o.optLong("at"))
        }
        val id = o.optLong("localId", 0L)
        if (id <= 0L) return null
        return RecentEntry(localId = id, at = o.optLong("at"))
    }

    /** 两条记录指的是不是同一首歌（用于去重） */
    private fun isSameEntry(a: RecentEntry, b: RecentEntry): Boolean = when {
        a.isOnline && b.isOnline -> a.online?.songId() == b.online?.songId()
        !a.isOnline && !b.isOnline -> a.localId == b.localId
        else -> false
    }

    /**
     * 最近播放的完整条目（**含在线歌**），最新在前。
     *
     * 存储升级过一次：以前这个键存的是「逗号分隔的本地歌曲 id」。
     * 所以读到旧格式要能接着用（解析不出来就退回旧格式），
     * 否则用户一升级「最近播放」就整个空了。
     */
    fun recentEntries(): List<RecentEntry> {
        val raw = sp.getString(KEY_RECENT, null)
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.trimStart().startsWith("[")) {
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        recentEntryFromJson(o)?.let { add(it) }
                    }
                }
            }.getOrDefault(emptyList())
        }
        // 旧格式：逗号分隔的本地 id
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .map { RecentEntry(localId = it) }
    }

    /** 本地歌曲 id 列表（不含在线歌）。给「按最近播放排序」用 —— 在线歌不参与曲库排序 */
    fun recentIds(limit: Int = 200): List<Long> =
        recentEntries().mapNotNull { if (it.isOnline) null else it.localId.takeIf { id -> id > 0L } }
            .take(limit)

    fun pushRecentLocal(id: Long) =
        pushRecent(RecentEntry(localId = id, at = System.currentTimeMillis()))

    /** 在线歌进最近播放。带完整信息，冷启动才查得到 */
    fun pushRecentOnline(song: StoredOnlineSong) =
        pushRecent(RecentEntry(online = song, at = System.currentTimeMillis()))

    private fun pushRecent(entry: RecentEntry) {
        val list = recentEntries().toMutableList()
        list.removeAll { isSameEntry(it, entry) }
        list.add(0, entry)
        val arr = JSONArray()
        list.take(RECENT_LIMIT).forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun clearRecent() = sp.edit().remove(KEY_RECENT).apply()

    // ===== 在线播放现场（冷启动恢复用）=====

    /**
     * 上次播的在线歌（[StoredOnlineSong] 的 JSON）。null = 上次播的是本地歌 / 没播过。
     *
     * 为什么在线歌也得存：它们的 id 是**负数**，而恢复逻辑按"在不在本地曲库里"
     * 过滤，负数 id 会被整条滤掉 —— 于是回到软件，播放页摆的是**上一次的本地歌**，
     * 在线歌看着就像"消失了一样"。
     */
    var lastOnlineSongJson: String?
        get() = sp.getString(KEY_ONLINE_LAST, null)
        set(v) = sp.edit().putString(KEY_ONLINE_LAST, v).apply()

    /** 上次播的在线队列（[StoredOnlineSong] 的 JSON 数组） */
    var onlineQueueJson: String?
        get() = sp.getString(KEY_ONLINE_QUEUE, null)
        set(v) = sp.edit().putString(KEY_ONLINE_QUEUE, v).apply()

    var onlineQueueIndex: Int
        get() = sp.getInt(KEY_ONLINE_QUEUE_INDEX, 0)
        set(v) = sp.edit().putInt(KEY_ONLINE_QUEUE_INDEX, v).apply()

    /**
     * 最后一次播的是**在线歌**还是本地歌。
     *
     * 恢复时两条路都要走，得有个字段说清楚该走哪条 ——
     * 只看"有没有 lastSongId"不行：那个字段永远指向上次播的**本地**歌。
     */
    var lastPlayedOnline: Boolean
        get() = sp.getBoolean(KEY_LAST_PLAYED_ONLINE, false)
        set(v) = sp.edit().putBoolean(KEY_LAST_PLAYED_ONLINE, v).apply()

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
        /** 晕染浓度的可调范围（界面上的滑块直接用这两个值） */
        const val MIN_GLOW_STRENGTH = 0.02f
        const val MAX_GLOW_STRENGTH = 0.95f
        const val DEFAULT_GLOW_STRENGTH = 0.60f

        /** 「渐变」模式的补间时长范围（毫秒） */
        const val MIN_GLOW_FADE_MS = 120
        const val MAX_GLOW_FADE_MS = 1500
        const val DEFAULT_GLOW_FADE_MS = 420

        private const val KEY_GLOW_MODE = "glow_mode"
        private const val KEY_GLOW_STRENGTH = "glow_strength"
        private const val KEY_GLOW_TRANSITION = "glow_transition"
        private const val KEY_GLOW_FADE_MS = "glow_fade_ms"
        private const val KEY_SKIN = "skin_id"
        private const val KEY_SORT = "sort_field"
        private const val KEY_SORT_ASC = "sort_asc"
        private const val KEY_VIEW = "view_mode"
        private const val KEY_TAB = "last_tab"
        private const val KEY_FAV = "favorites"
        private const val KEY_RECENT = "recent"
        private const val KEY_ONLINE_LAST = "online_last_song"
        private const val KEY_ONLINE_QUEUE = "online_queue"
        private const val KEY_ONLINE_QUEUE_INDEX = "online_queue_index"
        private const val KEY_LAST_PLAYED_ONLINE = "last_played_online"

        /** 最近播放保留多少条。再往上翻既没人看，也会把 SharedPreferences 撑大 */
        private const val RECENT_LIMIT = 200
        private const val KEY_LAST_SONG = "last_song"
        private const val KEY_PLAY_QUEUE = "play_queue_ids"
        private const val KEY_PLAY_INDEX = "play_queue_index"
        private const val KEY_PLAY_POS = "play_position_ms"
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
        private const val KEY_VIZ_LYRICS = "viz_show_lyrics"
        private const val KEY_VIZ_ATTACK = "viz_attack"
        private const val KEY_VIZ_DAMPING = "viz_decay_damping"
        private const val KEY_VIZ_GRAVITY = "viz_gravity"
        private const val KEY_VIZ_PEAK_HOLD = "viz_peak_hold_ticks"
        private const val KEY_VIZ_PEAK = "viz_show_peak"
        private const val KEY_VIZ_EMA = "viz_ema_alpha"
        private const val KEY_ONLINE_LYRICS = "online_lyrics_enabled"
        private const val KEY_LYRICS_API = "lyrics_api_base"
        private const val KEY_ONLINE_MUSIC = "online_music_enabled"
        private const val KEY_ONLINE_SOURCE = "online_music_source"
        private const val KEY_ONLINE_HIDE_UNPLAYABLE = "online_hide_unplayable"
        private const val KEY_NETEASE_FILL = "netease_fill_enabled"
        private const val KEY_DL_ENABLED = "online_download_enabled"
        private const val KEY_DL_WIFI_ONLY = "online_download_wifi_only"
        private const val KEY_DL_KEYS = "online_download_keys"
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
