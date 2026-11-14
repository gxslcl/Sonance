package com.sonance.player.widget

import android.content.Context

/**
 * 桌面小组件要显示的内容快照。
 *
 * 为什么不直接让小组件去查 `MediaController`：小组件的 `onUpdate` 跑在
 * `BroadcastReceiver` 里，进程可能刚被拉起、服务还没连上，`MediaController`
 * 是**异步连接**的，等不起。而 `RemoteViews` 只能同步构造。
 *
 * 所以改由应用侧在播放状态变化时把内容写进 SharedPreferences，
 * 小组件只管读 —— 简单、同步、进程重启后也还有内容可显示。
 *
 * 三块数据按更新频率分文件，互不干扰：
 * - [SP] **元信息**：歌名、歌手、播放状态、封面 key、皮肤色。只在真正变化时写。
 * - [SP_LIVE] **实时数据**：进度（秒）与当前歌词三行。每秒最多写一次。
 * - [SP_FLAGS] **每个小组件自己的开关**：歌词显示与否，默认关闭。
 */
object WidgetState {

    private const val SP = "sonance_widget_state"
    private const val SP_LIVE = "sonance_widget_live"
    private const val SP_FLAGS = "sonance_widget_flags"

    private const val K_HAS = "has_song"
    private const val K_MEDIA_ID = "media_id"
    private const val K_TITLE = "title"
    private const val K_ARTIST = "artist"
    private const val K_PLAYING = "is_playing"
    private const val K_ALBUM = "album_id"
    private const val K_URI = "source_uri"
    private const val K_COVER = "cover_key"
    private const val K_FAV = "is_favorite"
    private const val K_PLAY_MODE = "play_mode"
    private const val K_BG = "skin_bg"
    private const val K_TEXT = "skin_text"
    private const val K_TEXT_DIM = "skin_text_dim"
    private const val K_ACCENT = "skin_accent"
    private const val K_ON_ACCENT = "skin_on_accent"

    private const val K_POS = "position_sec"
    private const val K_DURATION = "duration_sec"
    private const val K_LYRIC_PREV = "lyric_prev"
    private const val K_LYRIC_CUR = "lyric_cur"
    private const val K_LYRIC_NEXT = "lyric_next"
    private const val K_HAS_LYRICS = "has_lyrics"

    private const val K_LYRIC_ON = "lyric_on_"
    private const val K_APPEARANCE = "widget_appearance"
    private const val K_EXTRACTED_RGB = "extracted_rgb"
    private const val K_EXTRACTED_HAS = "extracted_has"
    private const val K_EXTRACTED_SIG = "extracted_sig"

    /**
     * 皮肤色的默认值 = `res/values/colors.xml` 里那套 widget 配色。
     * 现在渐变底色改为取封面主色，这几个值只作为「完全取不到色」时的兜底。
     */
    private const val DEF_BG = 0xFF1A1A1A.toInt()
    private const val DEF_TEXT = 0xFFEDE8E0.toInt()
    private const val DEF_TEXT_DIM = 0xFF9A958E.toInt()
    private const val DEF_ACCENT = 0xFF4E7FCB.toInt()
    private const val DEF_ON_ACCENT = 0xFF121212.toInt()

    /** 当前歌词行 + 前后各一行。没有时间轴歌词时 [hasLyrics] 为 false。 */
    data class LyricTriplet(
        val prev: String = "",
        val cur: String = "",
        val next: String = "",
        val hasLyrics: Boolean = false
    ) {
        companion object {
            val EMPTY = LyricTriplet()
        }
    }

    /** 进度与歌词。位置用**秒**存储，避免每 500ms 的播放器回调触发一次刷新。 */
    data class Live(
        val positionSec: Int = 0,
        val durationSec: Int = 0,
        val lyric: LyricTriplet = LyricTriplet.EMPTY
    )

    data class Snapshot(
        val hasSong: Boolean = false,
        val mediaId: Long = -1L,
        val title: String = "",
        val artist: String = "",
        val isPlaying: Boolean = false,
        val albumId: Long = -1L,
        val sourceUri: String? = null,
        /** 已下载封面的缓存 key（[com.sonance.player.data.OnlineCover.keyOf]） */
        val coverKey: String? = null,
        val isFavorite: Boolean = false,
        val playMode: String = "REPEAT_ALL",
        val colorBg: Int = DEF_BG,
        val colorText: Int = DEF_TEXT,
        val colorTextDim: Int = DEF_TEXT_DIM,
        val colorAccent: Int = DEF_ACCENT,
        val colorOnAccent: Int = DEF_ON_ACCENT
    ) {
        /** 封面相关字段是否变了：每 500ms 的进度更新不该触发一次位图解码。 */
        fun sameArtwork(other: Snapshot): Boolean =
            albumId == other.albumId && sourceUri == other.sourceUri && coverKey == other.coverKey
    }

    fun read(context: Context): Snapshot {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return Snapshot(
            hasSong = sp.getBoolean(K_HAS, false),
            mediaId = sp.getLong(K_MEDIA_ID, -1L),
            title = sp.getString(K_TITLE, null).orEmpty(),
            artist = sp.getString(K_ARTIST, null).orEmpty(),
            isPlaying = sp.getBoolean(K_PLAYING, false),
            albumId = sp.getLong(K_ALBUM, -1L),
            sourceUri = sp.getString(K_URI, null),
            coverKey = sp.getString(K_COVER, null),
            isFavorite = sp.getBoolean(K_FAV, false),
            playMode = sp.getString(K_PLAY_MODE, "REPEAT_ALL").orEmpty().ifBlank { "REPEAT_ALL" },
            colorBg = sp.getInt(K_BG, DEF_BG),
            colorText = sp.getInt(K_TEXT, DEF_TEXT),
            colorTextDim = sp.getInt(K_TEXT_DIM, DEF_TEXT_DIM),
            colorAccent = sp.getInt(K_ACCENT, DEF_ACCENT),
            colorOnAccent = sp.getInt(K_ON_ACCENT, DEF_ON_ACCENT)
        )
    }

    /** 换肤时单独更新颜色；换肤不该碰播放内容。 */
    fun writeSkin(context: Context, bg: Int, text: Int, textDim: Int, accent: Int, onAccent: Int): Boolean {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (sp.getInt(K_BG, DEF_BG) == bg &&
            sp.getInt(K_TEXT, DEF_TEXT) == text &&
            sp.getInt(K_TEXT_DIM, DEF_TEXT_DIM) == textDim &&
            sp.getInt(K_ACCENT, DEF_ACCENT) == accent &&
            sp.getInt(K_ON_ACCENT, DEF_ON_ACCENT) == onAccent
        ) return false
        sp.edit()
            .putInt(K_BG, bg)
            .putInt(K_TEXT, text)
            .putInt(K_TEXT_DIM, textDim)
            .putInt(K_ACCENT, accent)
            .putInt(K_ON_ACCENT, onAccent)
            .apply()
        return true
    }

    /** @return 内容是否真的变了（没变就不用去更新小组件，省掉一次跨进程传输） */
    fun write(context: Context, snap: Snapshot): Boolean {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (read(context) == snap) return false
        sp.edit()
            .putBoolean(K_HAS, snap.hasSong)
            .putLong(K_MEDIA_ID, snap.mediaId)
            .putString(K_TITLE, snap.title)
            .putString(K_ARTIST, snap.artist)
            .putBoolean(K_PLAYING, snap.isPlaying)
            .putLong(K_ALBUM, snap.albumId)
            .putString(K_URI, snap.sourceUri)
            .putString(K_COVER, snap.coverKey)
            .putBoolean(K_FAV, snap.isFavorite)
            .putString(K_PLAY_MODE, snap.playMode)
            .apply()
        return true
    }

    // ===== 实时数据：进度与歌词 =====

    fun readLive(context: Context): Live {
        val sp = context.applicationContext.getSharedPreferences(SP_LIVE, Context.MODE_PRIVATE)
        return Live(
            positionSec = sp.getInt(K_POS, 0),
            durationSec = sp.getInt(K_DURATION, 0),
            lyric = LyricTriplet(
                prev = sp.getString(K_LYRIC_PREV, null).orEmpty(),
                cur = sp.getString(K_LYRIC_CUR, null).orEmpty(),
                next = sp.getString(K_LYRIC_NEXT, null).orEmpty(),
                hasLyrics = sp.getBoolean(K_HAS_LYRICS, false)
            )
        )
    }

    /**
     * 写入进度与当前歌词行。
     *
     * 按**秒**和**歌词行内容**联合判重：进度没跨秒、歌词没换行就直接返回，
     * 播放器的 500ms 回调因此不会变成每秒两次 Binder 更新。
     */
    fun writeLive(
        context: Context,
        positionMs: Long,
        durationMs: Long,
        lyric: LyricTriplet
    ): Boolean {
        val posSec = (positionMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val durSec = (durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sp = context.applicationContext.getSharedPreferences(SP_LIVE, Context.MODE_PRIVATE)
        val previous = readLive(context)
        if (previous.positionSec == posSec &&
            previous.durationSec == durSec &&
            previous.lyric == lyric
        ) return false
        sp.edit()
            .putInt(K_POS, posSec)
            .putInt(K_DURATION, durSec)
            .putString(K_LYRIC_PREV, lyric.prev)
            .putString(K_LYRIC_CUR, lyric.cur)
            .putString(K_LYRIC_NEXT, lyric.next)
            .putBoolean(K_HAS_LYRICS, lyric.hasLyrics)
            .apply()
        return true
    }

    // ===== 每个小组件独立的歌词开关（默认关闭） =====

    private fun flags(context: Context) =
        context.applicationContext.getSharedPreferences(SP_FLAGS, Context.MODE_PRIVATE)

    fun isLyricOn(context: Context, widgetId: Int): Boolean =
        flags(context).getBoolean(K_LYRIC_ON + widgetId, false)

    fun setLyricOn(context: Context, widgetId: Int, on: Boolean) {
        flags(context).edit().putBoolean(K_LYRIC_ON + widgetId, on).apply()
    }

    /** 小组件被移除时清掉它的开关，别在磁盘上留垃圾。 */
    fun clearWidget(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val editor = flags(context).edit()
        widgetIds.forEach { editor.remove(K_LYRIC_ON + it) }
        editor.apply()
    }

    // ===== 明暗外观偏好（深色烟熏琉璃 / 浅色磨砂白 / 跟随应用皮肤） =====

    fun readAppearance(context: Context): WidgetAppearance =
        WidgetAppearance.fromPref(flags(context).getString(K_APPEARANCE, null))

    fun writeAppearance(context: Context, appearance: WidgetAppearance) {
        flags(context).edit().putString(K_APPEARANCE, appearance.prefValue).apply()
    }

    fun artworkSignature(snap: Snapshot): String =
        "${snap.coverKey.orEmpty()}|${snap.albumId}|${snap.sourceUri.orEmpty()}"

    fun readExtractedRgb(context: Context, sig: String): Pair<Boolean, Int?> {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val savedSig = sp.getString(K_EXTRACTED_SIG, null)
        if (savedSig != sig) return false to null
        val hasRgb = sp.getBoolean(K_EXTRACTED_HAS, false)
        return true to if (hasRgb) sp.getInt(K_EXTRACTED_RGB, 0) else null
    }

    fun writeExtractedRgb(context: Context, sig: String, rgb: Int?) {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val editor = sp.edit()
            .putString(K_EXTRACTED_SIG, sig)
            .putBoolean(K_EXTRACTED_HAS, rgb != null)
        if (rgb != null) editor.putInt(K_EXTRACTED_RGB, rgb) else editor.remove(K_EXTRACTED_RGB)
        editor.apply()
    }
}
