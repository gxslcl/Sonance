package com.sonance.player.data

import android.content.Context
import android.content.SharedPreferences
import com.sonance.player.playback.EqPreset
import com.sonance.player.ui.theme.AppIcons
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.ListCoverScale
import com.sonance.player.ui.theme.ListRowStyle
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.MiniProgressStyle
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
     * 播放页封面的「黑碟播放器」样式：封面变圆形原色、外圈包一层黑色黑胶，
     * 播放时缓慢旋转。双击封面打开的面板和设置页里都能切。
     */
    var vinylMode: Boolean
        get() = sp.getBoolean(KEY_VINYL_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_VINYL_MODE, v).apply()

    /**
     * 沉浸黑胶页（全屏 WebView 唱机）的机械音效开关与音量。
     * 默认关闭（音效与歌曲声音相冲突，触感反馈保留），设置页可开；
     * 进入黑胶页时经 setSfx 推给页面。
     */
    var vinylSfxEnabled: Boolean
        get() = sp.getBoolean(KEY_VINYL_SFX_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_VINYL_SFX_ENABLED, v).apply()

    /** 机械音效轨音量 0~1（页面内 masterGain），与媒体音量互相独立 */
    var vinylSfxVolume: Float
        get() = sp.getFloat(KEY_VINYL_SFX_VOLUME, 0.9f)
        set(v) = sp.edit().putFloat(KEY_VINYL_SFX_VOLUME, v.coerceIn(0f, 1f)).apply()

    /**
     * 播放/暂停按钮的触感震动反馈。默认开启 ——
     * 用户要求「点击暂停和播放的时候要有震动反馈」，设置页可关。
     */
    var playBtnHaptics: Boolean
        get() = sp.getBoolean(KEY_PLAY_BTN_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_PLAY_BTN_HAPTICS, v).apply()

    /**
     * 播放/暂停按钮的外圈光晕。默认开启（参考样机默认亮着光晕），
     * 播放态光晕会随呼吸律动；设置页可关，关掉后只保留按钮本体的干净渐变。
     */
    var playBtnGlow: Boolean
        get() = sp.getBoolean(KEY_PLAY_BTN_GLOW, true)
        set(v) = sp.edit().putBoolean(KEY_PLAY_BTN_GLOW, v).apply()

    /**
     * 播放/暂停按钮内部的图标样式。0=经典（实心播放/暂停图标）、
     * 1=液态（参考样机的三杆弹簧变形）、2=极简（细线三角/双杠）。
     * 默认 1 —— 用户指定参考那个液态按钮。
     */
    var playBtnIconStyle: Int
        get() = sp.getInt(KEY_PLAY_BTN_ICON_STYLE, 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt(KEY_PLAY_BTN_ICON_STYLE, v.coerceIn(0, 2)).apply()

    /** 播放/暂停按钮内部图标的大小档（百分比 60~170，100 为原设计值） */
    var playBtnIconScale: Int
        get() = sp.getInt(KEY_PLAY_BTN_ICON_SCALE, 100).coerceIn(60, 170)
        set(v) = sp.edit().putInt(KEY_PLAY_BTN_ICON_SCALE, v.coerceIn(60, 170)).apply()

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

    /**
     * 主界面玻璃背景的封面切换过渡："slide" = 左右滑动（播放页轮播同款），
     * "fade" = 直接渐变过去（不左右滑）。设置页「背景切换」里选。
     */
    var artTransitionMode: String
        get() = sp.getString(KEY_ART_TRANSITION, "slide") ?: "slide"
        set(v) = sp.edit()
            .putString(KEY_ART_TRANSITION, if (v == "fade") "fade" else "slide")
            .apply()

    /**
     * 「渐变」模式的过渡时长（毫秒）。只在新封面淡入旧封面淡出这条路径上生效。
     */
    var artFadeMs: Int
        get() = sp.getInt(KEY_ART_FADE_MS, DEFAULT_ART_FADE_MS)
            .coerceIn(MIN_ART_FADE_MS, MAX_ART_FADE_MS)
        set(v) = sp.edit()
            .putInt(KEY_ART_FADE_MS, v.coerceIn(MIN_ART_FADE_MS, MAX_ART_FADE_MS))
            .apply()

    var skinId: String
        get() = sp.getString(KEY_SKIN, null) ?: "immersive"
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

    /**
     * 播放模式（[PlayMode] 的 name：SHUFFLE / REPEAT_ALL / REPEAT_ONE）。
     *
     * 用字符串而不是 ordinal：以后模式顺序变了，旧值也不会错位成另一种模式。
     * 默认「列表循环」—— 与 UI 的首个图标一致。
     */
    var playMode: String
        get() = sp.getString(KEY_PLAY_MODE, "REPEAT_ALL") ?: "REPEAT_ALL"
        set(v) = sp.edit().putString(KEY_PLAY_MODE, v).apply()

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

    /**
     * 上次「扫描」面板里勾选的目录（换行分隔）。
     *
     * 空 = 还没选过，下次打开面板不预选任何目录；老用户没有这个键时读到的也是空，
     * 不会因为升级而误扫某些目录。
     */
    var scanScopePaths: String
        get() = sp.getString(KEY_SCAN_SCOPE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SCAN_SCOPE, v).apply()

    /** 当前选中的应用图标（对应 manifest 里的 activity-alias 后缀）；没选过时用全局默认（s2_4） */
    var appIconId: String
        get() = sp.getString(KEY_APP_ICON, null) ?: AppIcons.DEFAULT_ID
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
     * 是否允许联网取歌词。默认打开。
     */
    var onlineLyricsEnabled: Boolean
        get() = sp.getBoolean(KEY_ONLINE_LYRICS, true)
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

    /**
     * 歌词要不要跟着背景自动调色（见 [LyricAdaptMode]）。
     *
     * 默认 [LyricAdaptMode.ALL] —— 这正是当初做这个功能的原因：封面透出来和歌词撞色时，
     * 撞的通常只是屏幕某一段的那几行。但它是**动用户选的那套配色**，所以能关。
     */
    var lyricsAdaptMode: LyricAdaptMode
        get() = LyricAdaptMode.fromId(sp.getString(KEY_LYRICS_ADAPT, null))
        set(v) = sp.edit().putString(KEY_LYRICS_ADAPT, v.id).apply()

    /** 歌词页的展现模式（经典 / 倾斜），全局设置 */
    var lyricsStyle: LyricStyle
        get() = LyricStyle.fromId(sp.getString(KEY_LYRICS_STYLE, null))
        set(v) = sp.edit().putString(KEY_LYRICS_STYLE, v.id).apply()

    /**
     * 歌词页底部的三个播放按钮（上一曲 / 播放暂停 / 下一曲）显不显示。
     * 独立于展现模式 —— 经典 / 倾斜两种模式都遵循这一个开关。
     */
    var lyricsPlaybar: Boolean
        get() = sp.getBoolean(KEY_LYRICS_PLAYBAR, false)
        set(v) = sp.edit().putBoolean(KEY_LYRICS_PLAYBAR, v).apply()

    /**
     * 圆盘歌词的圆盘半径（屏宽的倍数）。越大弧面越平、行越不倾斜；
     * 全屏歌词页外观面板的「圆盘大小」滑杆可调。
     */
    var lyricsDiscRadius: Float
        get() = sp.getFloat(KEY_LYRICS_DISC_RADIUS, DEFAULT_LYRICS_DISC_RADIUS)
            .coerceIn(MIN_LYRICS_DISC_RADIUS, MAX_LYRICS_DISC_RADIUS)
        set(v) = sp.edit()
            .putFloat(KEY_LYRICS_DISC_RADIUS, v.coerceIn(MIN_LYRICS_DISC_RADIUS, MAX_LYRICS_DISC_RADIUS))
            .apply()

    /**
     * 歌词进度偏移，**按歌记忆**：不同歌的歌词源不同、偏差方向也不同，
     * 全局一个值会互相打架。正数 = 歌词比音频慢（提前显示），负数 = 歌词快了（推迟显示）。
     *
     * 渲染侧用 `positionMs - offset`，歌词整体提前/延后，不碰真实播放进度。
     * 存储是一张「歌名|歌手 → 毫秒」的表，落在一个 SP key 里；
     * 内存缓存一份：播放页的内嵌歌词每 500ms 随进度重组时都要读一次，不能每次都解析文本。
     */
    fun lyricsOffsetMs(title: String, artist: String): Long {
        loadOffsetsOnce()
        return offsetsCache[offsetKey(title, artist)] ?: 0L
    }

    /** 写入某首歌的歌词偏移；0 表示清除该歌的条目（防止表无限膨胀） */
    fun setLyricsOffsetMs(title: String, artist: String, ms: Long) {
        loadOffsetsOnce()
        val key = offsetKey(title, artist)
        val v = ms.coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        if (v == 0L) offsetsCache.remove(key) else offsetsCache[key] = v
        val text = offsetsCache.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        sp.edit().putString(KEY_LYRICS_OFFSETS, text).apply()
    }

    private fun offsetKey(title: String, artist: String) =
        "${title.trim().replace('\t', ' ').replace('\n', ' ')}|${artist.trim().replace('\t', ' ').replace('\n', ' ')}"

    @Volatile
    private var offsetsLoaded = false
    private val offsetsCache = HashMap<String, Long>()

    private fun loadOffsetsOnce() {
        if (offsetsLoaded) return
        synchronized(offsetsCache) {
            if (offsetsLoaded) return
            sp.getString(KEY_LYRICS_OFFSETS, null)?.let { text ->
                for (line in text.split('\n')) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val ms = line.substring(tab + 1).trim().toLongOrNull() ?: continue
                    offsetsCache[line.substring(0, tab)] = ms
                }
            }
            offsetsLoaded = true
        }
    }

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

    // ===== 沉浸皮肤主界面背景（与歌词页那套玻璃设置相互独立）=====

    /** 沉浸主界面玻璃样式：[GlassStyle] 的 ordinal（OFF = 不铺封面背景，退回纯色深底） */
    var shellGlassStyle: Int
        get() = sp.getInt(KEY_SHELL_GLASS_STYLE, GlassStyle.LIQUID.ordinal)
        set(v) = sp.edit().putInt(KEY_SHELL_GLASS_STYLE, v).apply()

    /** 沉浸主界面模糊半径 0~80dp（默认 40 = 原写死值） */
    var shellGlassBlurDp: Int
        get() = sp.getInt(KEY_SHELL_GLASS_BLUR, 40).coerceIn(0, 80)
        set(v) = sp.edit().putInt(KEY_SHELL_GLASS_BLUR, v.coerceIn(0, 80)).apply()

    /** 沉浸主界面背景可见度 0~100（默认 100 = 原写死值） */
    var shellGlassVisibility: Int
        get() = sp.getInt(KEY_SHELL_GLASS_VIS, 100).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_SHELL_GLASS_VIS, v.coerceIn(0, 100)).apply()

    // ===== 律动柱页背景（与歌词页同一套玻璃参数，但各自独立可调）=====

    /**
     * 律动柱页的背景原来只有一条「底色 → 表面色 → 底色」的渐变，看久了很单调。
     * 这里给它和歌词页一样的玻璃背景（糊开的封面 + 遮罩），曲线和柱体压在上面。
     *
     * 之所以**不和歌词页共用一份设置**：律动柱页要的是"背景有东西但别抢戏"，
     * 通常比歌词页压得更暗，共用一份会顾此失彼。
     */
    var vizGlassStyle: Int
        get() = sp.getInt(KEY_VIZ_GLASS_STYLE, GlassStyle.NORMAL.ordinal)
        set(v) = sp.edit().putInt(KEY_VIZ_GLASS_STYLE, v).apply()

    var vizGlassBlurDp: Int
        get() = sp.getInt(KEY_VIZ_GLASS_BLUR, GlassStyle.NORMAL.defaultBlurDp).coerceIn(0, 80)
        set(v) = sp.edit().putInt(KEY_VIZ_GLASS_BLUR, v.coerceIn(0, 80)).apply()

    var vizGlassVisibility: Int
        get() = sp.getInt(KEY_VIZ_GLASS_VIS, GlassStyle.NORMAL.defaultVisibility).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_VIZ_GLASS_VIS, v.coerceIn(0, 100)).apply()

    // ===== 迷你播放条（列表页底部那个「正在播放」）=====

    /** 形态：[MiniPlayerStyle] 的 ordinal。默认悬浮卡片。 */
    var miniPlayerStyle: Int
        get() = sp.getInt(KEY_MINI_STYLE, MiniPlayerStyle.FLOAT.ordinal)
        set(v) = sp.edit().putInt(KEY_MINI_STYLE, v).apply()

    /** 悬浮卡片的玻璃样式：[GlassStyle] 的 ordinal */
    var miniGlassStyle: Int
        get() = sp.getInt(KEY_MINI_GLASS_STYLE, GlassStyle.NORMAL.ordinal)
        set(v) = sp.edit().putInt(KEY_MINI_GLASS_STYLE, v).apply()

    var miniGlassBlurDp: Int
        get() = sp.getInt(KEY_MINI_GLASS_BLUR, GlassStyle.NORMAL.defaultBlurDp).coerceIn(0, 80)
        set(v) = sp.edit().putInt(KEY_MINI_GLASS_BLUR, v.coerceIn(0, 80)).apply()

    var miniGlassVisibility: Int
        get() = sp.getInt(KEY_MINI_GLASS_VIS, GlassStyle.NORMAL.defaultVisibility).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_MINI_GLASS_VIS, v.coerceIn(0, 100)).apply()

    /**
     * 卡片自身底色的不透明度 0~100。
     *
     * 和「透出」不是一回事：透出调的是**封面糊开那层**露多少，
     * 这里调的是卡片底色盖多实 —— 调到 0 就只剩玻璃和投影，卡片几乎"消失"。
     */
    var miniCardOpacity: Int
        get() = sp.getInt(KEY_MINI_OPACITY, 82).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_MINI_OPACITY, v.coerceIn(0, 100)).apply()

    /**
     * 是否把歌词发布到系统的 `MediaMetadata["lyricInfo"]`。
     *
     * 打开后 ColorOS / OPlus 的原生锁屏歌词页就能显示逐行歌词（小米/华为等
     * 不支持这个字段的机型会直接忽略，没有副作用）。
     * 只发**逐行**时间轴，且只用手上已有的歌词（内嵌 / 本地缓存），不会为它联网。
     */
    var lockScreenLyrics: Boolean
        get() = sp.getBoolean(KEY_LOCK_LYRICS, true)
        set(v) = sp.edit().putBoolean(KEY_LOCK_LYRICS, v).apply()

    /**
     * 悬浮卡片上的进度怎么显示：[MiniProgressStyle] 的 ordinal。
     *
     * 环和线同时开着会重复（一个进度说两遍），默认只留环 —— 玻璃卡片上环更自然。
     */
    var miniProgressStyle: Int
        get() = sp.getInt(KEY_MINI_PROGRESS, MiniProgressStyle.RING.ordinal)
        set(v) = sp.edit().putInt(KEY_MINI_PROGRESS, v).apply()

    /**
     * 左右滑动切歌的触发阈值，单位是**卡宽的百分比**。
     *
     * 两个方向分开存，因为手感本来就不不对称：大多数人右手持机，
     * "从右往左"（左滑）顺手，拇指一摆就到；反过来"从左往右"要往回够，
     * 同样的距离划不满 —— 所以右滑的默认阈值比左滑低。
     */
    var miniSwipeLeftPct: Int
        get() = sp.getInt(KEY_MINI_SWIPE_LEFT, 20).coerceIn(5, 60)
        set(v) = sp.edit().putInt(KEY_MINI_SWIPE_LEFT, v.coerceIn(5, 60)).apply()

    var miniSwipeRightPct: Int
        get() = sp.getInt(KEY_MINI_SWIPE_RIGHT, 12).coerceIn(5, 60)
        set(v) = sp.edit().putInt(KEY_MINI_SWIPE_RIGHT, v.coerceIn(5, 60)).apply()

    /** 悬浮条高度倍数（×100，100~200）。只加高卡片，不改里面的元素 */
    var miniHeightScale: Int
        get() = sp.getInt(KEY_MINI_HEIGHT, 100).coerceIn(100, 200)
        set(v) = sp.edit().putInt(KEY_MINI_HEIGHT, v.coerceIn(100, 200)).apply()

    /** 悬浮条里封面的大小倍数（×100，100~200） */
    var miniCoverScale: Int
        get() = sp.getInt(KEY_MINI_COVER, 100).coerceIn(100, 200)
        set(v) = sp.edit().putInt(KEY_MINI_COVER, v.coerceIn(100, 200)).apply()

    // ===== 列表外观 =====

    /** 列表行的分隔方式：[ListRowStyle] 的 ordinal。默认细分隔线 */
    var listRowStyle: Int
        get() = sp.getInt(KEY_LIST_ROW_STYLE, ListRowStyle.DIVIDER.ordinal)
        set(v) = sp.edit().putInt(KEY_LIST_ROW_STYLE, v).apply()

    /** 列表封面的缩放倍数：[ListCoverScale] 的 ordinal。默认 1.2 倍 */
    var listCoverScale: Int
        get() = sp.getInt(KEY_LIST_COVER_SCALE, ListCoverScale.X12.ordinal)
        set(v) = sp.edit().putInt(KEY_LIST_COVER_SCALE, v).apply()

    // ===== 顶栏品牌 =====

    /**
     * 顶栏标题左边那个小方块的图案（[AppIcons] 的 id）。
     *
     * ⚠️ 和 [appIconId] 不是一回事：那个换的是**桌面启动器**图标，
     * 这个只换应用内部的顶栏 logo，不影响桌面。
     */
    var topBarLogoId: String
        get() = sp.getString(KEY_TOP_BAR_LOGO, null) ?: ""
        set(v) = sp.edit().putString(KEY_TOP_BAR_LOGO, v).apply()

    /**
     * 拖动右侧快速滚动条时是否给震动反馈。
     *
     * 默认开启：长列表里拖滚动条是"盲操作"，没有触觉反馈很难判断拖了多远。
     * 关掉它的人通常是嫌震动烦，或者手机马达是转子式的（手感差）。
     */
    var scrollHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_SCROLL_HAPTIC, true)
        set(v) = sp.edit().putBoolean(KEY_SCROLL_HAPTIC, v).apply()

    // ===== B 站缓存 =====

    /**
     * B 站缓存视频的播放模式。默认**列表循环**（复试 1：与音乐播放器一致的初始选择）。
     *
     * 存的是 ordinal，取的时候夹一层范围检查 —— 哪天枚举顺序变了、或者
     * 读到越界值，直接退回默认而不是崩。
     */
    var biliPlayMode: Int
        get() = sp.getInt(KEY_BILI_PLAY_MODE, 1)
        set(v) = sp.edit().putInt(KEY_BILI_PLAY_MODE, v).apply()

    /**
     * 画面提亮档位：0=关 1=弱 2=中 3=强（对应 gain 1.0/1.15/1.30/1.45）。
     *
     * 和播放模式一样存 ordinal 并夹范围：跨视频、跨会话沿用，
     * 用户调一次就该一直是那个档，切视频不重置。
     */
    var biliBrightnessGain: Int
        get() = sp.getInt(KEY_BILI_BRIGHTNESS_GAIN, 0).coerceIn(0, 3)
        set(v) = sp.edit().putInt(KEY_BILI_BRIGHTNESS_GAIN, v.coerceIn(0, 3)).apply()

    /**
     * B 站播放页操作菜单**被用户拖动后的位置**，存的是菜单中心点的屏幕比例（0~1）。
     *
     * -1 = 还没拖过。按比例存是为了横竖屏、不同分辨率下位置不跑偏；
     * 跨视频、跨会话都沿用，切换视频或重进页面不会把它重置回屏幕正中。
     */
    var biliMenuX: Float
        get() = sp.getFloat(KEY_BILI_MENU_X, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_MENU_X, v.coerceIn(0f, 1f)).apply()

    var biliMenuY: Float
        get() = sp.getFloat(KEY_BILI_MENU_Y, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_MENU_Y, v.coerceIn(0f, 1f)).apply()

    /** 用户是否手动拖过菜单。没拖过时按「上次点击锚点」定位，拖动后固定用拖动位置 */
    var biliMenuDragged: Boolean
        get() = sp.getBoolean(KEY_BILI_MENU_DRAGGED, false)
        set(v) = sp.edit().putBoolean(KEY_BILI_MENU_DRAGGED, v).apply()

    /**
     * 菜单**上次出现时的点击锚点**（屏幕比例）。用户没拖过菜单时，下次显示就落在
     * 这个锚点上 —— 这样"上次点哪儿、菜单就在哪儿"，而不是每次回到屏幕中央。
     */
    var biliMenuAnchorX: Float
        get() = sp.getFloat(KEY_BILI_MENU_ANCHOR_X, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_MENU_ANCHOR_X, v.coerceIn(0f, 1f)).apply()

    var biliMenuAnchorY: Float
        get() = sp.getFloat(KEY_BILI_MENU_ANCHOR_Y, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_MENU_ANCHOR_Y, v.coerceIn(0f, 1f)).apply()

    /**
     * 视频浮窗中心点的屏幕比例（0~1），-1 = 还没拖过。
     *
     * 和 [biliMenuX] 一样按比例存：换分辨率、横竖屏后位置不会跑到屏幕外。
     */
    var biliFloatX: Float
        get() = sp.getFloat(KEY_BILI_FLOAT_X, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_FLOAT_X, v.coerceIn(0f, 1f)).apply()

    var biliFloatY: Float
        get() = sp.getFloat(KEY_BILI_FLOAT_Y, -1f)
        set(v) = sp.edit().putFloat(KEY_BILI_FLOAT_Y, v.coerceIn(0f, 1f)).apply()

    /**
     * 锁屏 / 切后台时是否继续播放视频音频。默认关闭（与音乐不同，视频默认不该在后台偷偷出声）。
     *
     * 状态持久化：用户改一次，下次冷启动保持上次选择。
     */
    var biliBackgroundPlay: Boolean
        get() = sp.getBoolean(KEY_BILI_BACKGROUND_PLAY, false)
        set(v) = sp.edit().putBoolean(KEY_BILI_BACKGROUND_PLAY, v).apply()

    // ===== 在线封面（默认打开，与在线歌词同一步调）=====

    var onlineCoverEnabled: Boolean
        get() = sp.getBoolean(KEY_COVER_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_COVER_ENABLED, v).apply()

    /** 手动指定过的封面：songId → 图片 URL（覆盖自动匹配的结果） */
    fun pinnedCoverUrl(songId: Long): String? = sp.getString("$KEY_COVER_PIN$songId", null)

    fun pinCoverUrl(songId: Long, url: String?) {
        if (url == null) sp.edit().remove("$KEY_COVER_PIN$songId").apply()
        else sp.edit().putString("$KEY_COVER_PIN$songId", url).apply()
    }

    // ===== 在线音乐（默认打开，与在线歌词 / 在线封面同一步调）=====

    /**
     * 是否启用在线音源（搜索 + 播放）。默认打开。
     *
     * 关掉时「在线音乐」页只显示一个开启入口，**不发任何网络请求**。
     */
    var onlineMusicEnabled: Boolean
        get() = sp.getBoolean(KEY_ONLINE_MUSIC, true)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_MUSIC, v).apply()

    /**
     * 是否已经给用户看过「左右滑动切换」的提示。
     *
     * 只提示一次：滑动是隐藏功能，新用户第一次进来不给提示就永远发现不了；
     * 但每次进来都提示又很烦，所以只记一个"已看过"。
     */
    var pagerHintShown: Boolean
        get() = sp.getBoolean(KEY_PAGER_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_PAGER_HINT, v).apply()

    /**
     * 三页列表（全部 / 最近 / 收藏）各自的滚动位置。
     *
     * 形如 `"12:340|0:0|3:18"` = 每页的「首个可见项下标 : 像素偏移」。
     * 跨进程重启也要留住 —— 否则杀掉应用再进来，列表又从头开始，
     * 而用户明明是听着歌翻到中间才退出的。
     *
     * 存首项而不是"当前歌曲"：用户自己滚到哪就是哪，这是他上一次真正的位置。
     */
    var libraryScroll: String
        get() = sp.getString(KEY_LIBRARY_SCROLL, "") ?: ""
        set(v) = sp.edit().putString(KEY_LIBRARY_SCROLL, v).apply()

    /**
     * 顶栏标题显示的应用名。
     *
     * 顶栏原来跟着页签走（"全部歌曲 · 共 N 首 · 21 小时"），每次切页文字都在变；
     * 固定成应用名之后它才是"这是哪个应用"的锚点。允许用户改 —— 想显示
     * 自己起的名字（比如"我的音乐"）也随他。空值回退到 "Sonance"。
     */
    var appDisplayName: String
        get() = sp.getString(KEY_APP_NAME, null)?.takeIf { it.isNotBlank() } ?: "Sonance"
        set(v) = sp.edit().putString(KEY_APP_NAME, v.trim()).apply()

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

    // ===== 在线搜索历史 =====

    /**
     * 最近搜过的词，**最新的在前**，最多 [MAX_ONLINE_HISTORY] 个。
     *
     * 词与词之间用换行分隔 —— 搜索词来自单行输入框，正常不会有换行；
     * 万一粘贴进来的词带换行，存之前会先剥掉。
     */
    fun onlineHistory(): List<String> =
        (sp.getString(KEY_ONLINE_HISTORY, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 记一次搜索。
     *
     * **去重并把这条提到最前** —— 同一个词搜第二次，它应该还在原来的位置上吗？
     * 不该，用户现在关心的就是它。
     *
     * 只存 [MAX_ONLINE_HISTORY] 个：再多这一行就翻不动了，而且没人会翻回去用。
     */
    fun pushOnlineHistory(term: String) {
        val next = nextOnlineHistory(onlineHistory(), term, MAX_ONLINE_HISTORY)
        sp.edit().putString(KEY_ONLINE_HISTORY, next.joinToString("\n")).apply()
    }

    /** 删掉某一条（历史胶囊上那个 × ） */
    fun removeOnlineHistory(term: String) {
        if (!onlineHistory().contains(term)) return
        sp.edit().putString(KEY_ONLINE_HISTORY, onlineHistory().filter { it != term }.joinToString("\n"))
            .apply()
    }

    fun clearOnlineHistory() = sp.edit().remove(KEY_ONLINE_HISTORY).apply()

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

    // ===== 嗅探目标 =====

    /**
     * 嗅探音乐缓存的目标 App（[com.sonance.player.data.netease.SniffApp.name] 集合）。
     * 默认网易云 + 酷狗 + QQ 音乐；汽水不进默认（其 MDL 加密无公开解密方案）。
     */
    var sniffTargets: Set<String>
        get() = (sp.getStringSet(KEY_SNIFF_TARGETS, DEFAULT_SNIFF_TARGETS) ?: DEFAULT_SNIFF_TARGETS)
            .filter { it in VALID_SNIFF_TARGETS }.toSet()
        set(v) = sp.edit().putStringSet(KEY_SNIFF_TARGETS, v).apply()

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

    /**
     * 列表长按提示是否已经看过。
     *
     * 行尾去掉 ⋮ 之后长按是唯一入口，这条灰字只提示一次；用户第一次长按后写 true，
     * 之后不再打扰。
     */
    var longPressHintShown: Boolean
        get() = sp.getBoolean(KEY_LONG_PRESS_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_LONG_PRESS_HINT, v).apply()

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

    /**
     * 「私有格式还原」入口的解锁截止时间（epoch 毫秒）。
     *
     * 这个区块默认不出现在设置里：在「关于」里连点署名 10 次才解锁，
     * 解锁后只保持一小时，之后自动回到隐藏状态。
     * 存的是**截止时间点**而不是布尔值 —— 这样即使进程被杀、App 重启，
     * 也不会把一小时的窗口重置成「永远解锁」。
     */
    var conversionUnlockUntil: Long
        get() = sp.getLong(KEY_CONVERSION_UNLOCK_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_CONVERSION_UNLOCK_UNTIL, v).apply()

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
     * 最近播放条目（**只含本地歌**），最新在前。
     *
     * 读取时**过滤掉在线条目**（用户要求在线歌不进最近播放）：
     * 这样不止是"以后不记"，历史遗留的在线条目也会自然从列表里消失。
     * 在线歌的**播放现场**（lastOnlineSongJson 等）是独立的键，不受影响。
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
            }.getOrDefault(emptyList()).filterNot { it.isOnline }
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

    // 在线歌不进最近播放（用户要求）：没有 pushRecentOnline，
    // 读取侧（recentEntries）也会把历史遗留的在线条目过滤掉。

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

    /**
     * 多设备同步：本机在组里显示的名字（默认取机型，可被用户改）。
     * 只做本地展示，不参与配对鉴权。
     */
    var groupDeviceName: String
        get() = sp.getString(KEY_GROUP_NAME, null)?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL.orEmpty().ifBlank { "Android" }
        set(v) = sp.edit().putString(KEY_GROUP_NAME, v.trim().take(24)).apply()

    /** 最近一次手动连接的主机地址（调试入口用，正式流程靠 NSD） */
    var groupLastHost: String?
        get() = sp.getString(KEY_GROUP_LAST_HOST, null)
        set(v) = sp.edit().putString(KEY_GROUP_LAST_HOST, v).apply()

    /**
     * 输出延迟校准结果，按「机型 + 输出路由」分开存。
     * 同一台手机插拔耳机/连蓝牙都要用不同的值，混用会把同步搞坏。
     */
    fun groupOutputLatency(model: String, route: String): Long =
        sp.getInt(KEY_GROUP_LATENCY + model + "_" + route, -1).toLong()

    fun setGroupOutputLatency(model: String, route: String, ms: Long) {
        sp.edit().putInt(KEY_GROUP_LATENCY + model + "_" + route, ms.toInt()).apply()
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
        const val MIN_ART_FADE_MS = 200
        const val MAX_ART_FADE_MS = 1500
        const val DEFAULT_ART_FADE_MS = 500

        private const val KEY_GLOW_MODE = "glow_mode"
        private const val KEY_GLOW_STRENGTH = "glow_strength"
        private const val KEY_GROUP_NAME = "group_device_name"
        private const val KEY_GROUP_LAST_HOST = "group_last_host"
        private const val KEY_GROUP_LATENCY = "group_latency_"
        private const val KEY_GLOW_TRANSITION = "glow_transition"
        private const val KEY_GLOW_FADE_MS = "glow_fade_ms"
        private const val KEY_ART_TRANSITION = "art_transition_mode"
        private const val KEY_ART_FADE_MS = "art_fade_ms"
        private const val KEY_VINYL_MODE = "player_vinyl_mode"
        private const val KEY_VINYL_SFX_ENABLED = "player_vinyl_sfx_enabled"
        private const val KEY_VINYL_SFX_VOLUME = "player_vinyl_sfx_volume"
        private const val KEY_PLAY_BTN_HAPTICS = "play_btn_haptics"
        private const val KEY_PLAY_BTN_GLOW = "play_btn_glow"
        private const val KEY_PLAY_BTN_ICON_STYLE = "play_btn_icon_style"
        private const val KEY_PLAY_BTN_ICON_SCALE = "play_btn_icon_scale"
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
        private const val KEY_PLAY_MODE = "play_mode"
        private const val KEY_COUNT = "play_count_"
        private const val KEY_FILTER_SHORT = "filter_short"
        private const val KEY_EXCLUDE_LOCKED = "scan_exclude_locked"
        private const val KEY_EXCLUDE_UNSUPPORTED = "scan_exclude_unsupported"
        private const val KEY_EXCLUDE_NON_SONG = "scan_exclude_nonsong"
        private const val KEY_SCAN_SCOPE = "scan_scope_paths"
        private const val KEY_LONG_PRESS_HINT = "long_press_hint_shown"
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
        private const val KEY_PAGER_HINT = "pager_hint_shown"
        private const val KEY_LIBRARY_SCROLL = "library_scroll"
        private const val KEY_APP_NAME = "app_display_name"
        private const val KEY_ONLINE_SOURCE = "online_music_source"
        private const val KEY_ONLINE_HIDE_UNPLAYABLE = "online_hide_unplayable"
        private const val KEY_ONLINE_HISTORY = "online_search_history"

        /** 搜索历史最多存几条。再多这一行胶囊就翻不动了，而且没人会翻回去用 */
        const val MAX_ONLINE_HISTORY = 12
        private const val KEY_NETEASE_FILL = "netease_fill_enabled"
        private const val KEY_SNIFF_TARGETS = "sniff_targets"
        private val DEFAULT_SNIFF_TARGETS = setOf("NETEASE", "KUGOU", "TENCENT")
        private val VALID_SNIFF_TARGETS =
            com.sonance.player.data.netease.SniffApp.entries.map { it.name }.toSet()
        private const val KEY_DL_ENABLED = "online_download_enabled"
        private const val KEY_DL_WIFI_ONLY = "online_download_wifi_only"
        private const val KEY_DL_KEYS = "online_download_keys"
        private const val KEY_LYRICS_T2S = "lyrics_t2s"
        private const val KEY_LYRICS_FONT = "lyrics_font_sp"
        private const val KEY_LYRICS_ADAPT = "lyrics_adapt_mode"
        private const val KEY_LYRICS_FONT_BUMPED = "lyrics_font_bumped"
        private const val KEY_LYRICS_STYLE = "lyrics_style"
        private const val KEY_LYRICS_PLAYBAR = "lyrics_playbar"
        private const val KEY_LYRICS_OFFSETS = "lyrics_offsets"
        private const val KEY_LYRICS_DISC_RADIUS = "lyrics_disc_radius"

        /** 歌词偏移的步进与上限。0.1 秒的细步长（0.5 太粗，副歌前奏常常就差两三拍）；±10 秒之外基本是歌词匹配错了，调也没用 */
        const val LYRICS_OFFSET_STEP_MS = 100L
        const val MAX_LYRICS_OFFSET_MS = 10_000L

        /** 圆盘半径的默认值与可调范围（屏宽的倍数） */
        const val DEFAULT_LYRICS_DISC_RADIUS = 5.5f
        const val MIN_LYRICS_DISC_RADIUS = 3.0f
        const val MAX_LYRICS_DISC_RADIUS = 10.0f

        private const val KEY_GLASS_CLEARED = "glass_cleared"
        private const val KEY_GLASS_STYLE = "glass_style"
        private const val KEY_GLASS_BLUR = "glass_blur_dp"
        private const val KEY_GLASS_VIS = "glass_visibility"
        private const val KEY_SHELL_GLASS_STYLE = "shell_glass_style"
        private const val KEY_SHELL_GLASS_BLUR = "shell_glass_blur_dp"
        private const val KEY_SHELL_GLASS_VIS = "shell_glass_visibility"
        private const val KEY_VIZ_GLASS_STYLE = "viz_glass_style"
        private const val KEY_VIZ_GLASS_BLUR = "viz_glass_blur_dp"
        private const val KEY_VIZ_GLASS_VIS = "viz_glass_visibility"
        private const val KEY_MINI_STYLE = "mini_player_style"
        private const val KEY_MINI_GLASS_STYLE = "mini_glass_style"
        private const val KEY_MINI_GLASS_BLUR = "mini_glass_blur_dp"
        private const val KEY_MINI_GLASS_VIS = "mini_glass_visibility"
        private const val KEY_MINI_OPACITY = "mini_card_opacity"
        private const val KEY_MINI_PROGRESS = "mini_progress_style"
        private const val KEY_LOCK_LYRICS = "lock_screen_lyrics"
        private const val KEY_MINI_SWIPE_LEFT = "mini_swipe_left_pct"
        private const val KEY_MINI_SWIPE_RIGHT = "mini_swipe_right_pct"
        private const val KEY_MINI_HEIGHT = "mini_height_scale"
        private const val KEY_MINI_COVER = "mini_cover_scale"
        private const val KEY_LIST_ROW_STYLE = "list_row_style"
        private const val KEY_LIST_COVER_SCALE = "list_cover_scale"
        private const val KEY_TOP_BAR_LOGO = "top_bar_logo_id"
        private const val KEY_SCROLL_HAPTIC = "scroll_haptic_enabled"

        /** B 站缓存视频的播放模式，存 [com.sonance.player.playback.PlayMode.ordinal] */
        private const val KEY_BILI_PLAY_MODE = "bili_play_mode"

        /** 播放页画面提亮档位（0=关 1=弱 2=中 3=强） */
        private const val KEY_BILI_BRIGHTNESS_GAIN = "bili_brightness_gain"

        /** B 站播放页操作菜单的位置（屏幕比例，-1 = 未设置）与拖动标记 */
        private const val KEY_BILI_MENU_X = "bili_menu_x"
        private const val KEY_BILI_MENU_Y = "bili_menu_y"
        private const val KEY_BILI_MENU_DRAGGED = "bili_menu_dragged"
        private const val KEY_BILI_MENU_ANCHOR_X = "bili_menu_anchor_x"
        private const val KEY_BILI_MENU_ANCHOR_Y = "bili_menu_anchor_y"
        private const val KEY_BILI_FLOAT_X = "bili_float_x"
        private const val KEY_BILI_FLOAT_Y = "bili_float_y"
        private const val KEY_BILI_BACKGROUND_PLAY = "bili_background_play"
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
        private const val KEY_CONVERSION_UNLOCK_UNTIL = "conversion_unlock_until"
    }
}

/**
 * 算出存完这个词之后，搜索历史应该长什么样。
 *
 * ## 三条规则
 *
 * 1. **去重并提到最前** —— 同一个词搜第二次，它不该留在原地：用户现在关心的就是它
 * 2. **截断到 [max] 条** —— 再多这排胶囊就翻不动了，而且没人会翻回去用
 * 3. **空词不动** —— 空着输入框触发的搜索（清空那次）不该往历史里塞一个空条
 *
 * 放在**文件级**而不是 `Prefs` 类里：纯函数，JVM 单测不用构造 SharedPreferences。
 *
 * @param existing 现在的历史（最新在前）
 * @param term     这次搜到的词
 * @param max      最多留几条
 */
internal fun nextOnlineHistory(existing: List<String>, term: String, max: Int): List<String> {
    val t = term.replace('\n', ' ').trim()
    if (t.isEmpty()) return existing
    return (listOf(t) + existing.filter { it != t }).take(max.coerceAtLeast(1))
}
