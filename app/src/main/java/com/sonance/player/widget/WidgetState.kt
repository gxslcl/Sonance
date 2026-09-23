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
 */
object WidgetState {

    private const val SP = "sonance_widget_state"
    private const val K_HAS = "has_song"
    private const val K_TITLE = "title"
    private const val K_ARTIST = "artist"
    private const val K_PLAYING = "is_playing"
    private const val K_ALBUM = "album_id"
    private const val K_URI = "source_uri"
    private const val K_COVER = "cover_key"
    private const val K_BG = "skin_bg"
    private const val K_TEXT = "skin_text"
    private const val K_TEXT_DIM = "skin_text_dim"
    private const val K_ACCENT = "skin_accent"
    private const val K_ON_ACCENT = "skin_on_accent"

    /**
     * 皮肤色的默认值 = `res/values/colors.xml` 里那套 widget 配色。
     *
     * 用户没换过皮肤（或换肤后还没触发过一次写盘）时用它，
     * 保证小组件第一次放上去就有颜色，而不是一片透明。
     */
    private const val DEF_BG = 0xFF1A1A1A.toInt()
    private const val DEF_TEXT = 0xFFEDE8E0.toInt()
    private const val DEF_TEXT_DIM = 0xFF9A958E.toInt()
    private const val DEF_ACCENT = 0xFFE8A838.toInt()
    private const val DEF_ON_ACCENT = 0xFF121212.toInt()

    data class Snapshot(
        val hasSong: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val isPlaying: Boolean = false,
        val albumId: Long = -1L,
        val sourceUri: String? = null,
        /** 已下载封面的缓存 key（[com.sonance.player.data.OnlineCover.keyOf]） */
        val coverKey: String? = null,
        /**
         * 皮肤色（ARGB）。
         *
         * 由应用侧在换肤时写进来，小组件只管读 —— 和 title/artist 同一个道理：
         * 小组件的 `onUpdate` 跑在 `BroadcastReceiver` 里，让它自己去翻 Prefs
         * 再解析 14 套皮肤是不现实的，而且 `RemoteViews` 只能同步构造。
         */
        val colorBg: Int = DEF_BG,
        val colorText: Int = DEF_TEXT,
        val colorTextDim: Int = DEF_TEXT_DIM,
        val colorAccent: Int = DEF_ACCENT,
        val colorOnAccent: Int = DEF_ON_ACCENT
    ) {
        /**
         * 封面相关字段是否变了。
         *
         * 用来决定要不要重新解码封面 —— 每 500ms 的进度更新不该触发一次位图解码。
         */
        fun sameArtwork(other: Snapshot): Boolean =
            albumId == other.albumId && sourceUri == other.sourceUri && coverKey == other.coverKey
    }

    fun read(context: Context): Snapshot {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return Snapshot(
            hasSong = sp.getBoolean(K_HAS, false),
            title = sp.getString(K_TITLE, null).orEmpty(),
            artist = sp.getString(K_ARTIST, null).orEmpty(),
            isPlaying = sp.getBoolean(K_PLAYING, false),
            albumId = sp.getLong(K_ALBUM, -1L),
            sourceUri = sp.getString(K_URI, null),
            coverKey = sp.getString(K_COVER, null),
            colorBg = sp.getInt(K_BG, DEF_BG),
            colorText = sp.getInt(K_TEXT, DEF_TEXT),
            colorTextDim = sp.getInt(K_TEXT_DIM, DEF_TEXT_DIM),
            colorAccent = sp.getInt(K_ACCENT, DEF_ACCENT),
            colorOnAccent = sp.getInt(K_ON_ACCENT, DEF_ON_ACCENT)
        )
    }

    /**
     * 换肤时单独更新颜色。
     *
     * 走独立的方法而不是塞进 [write]：换肤不该碰播放内容，
     * 而播放状态每 500ms 就会推一次，两者的更新频率差着一个量级。
     *
     * @return 颜色是否真的变了
     */
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
            .putString(K_TITLE, snap.title)
            .putString(K_ARTIST, snap.artist)
            .putBoolean(K_PLAYING, snap.isPlaying)
            .putLong(K_ALBUM, snap.albumId)
            .putString(K_URI, snap.sourceUri)
            .putString(K_COVER, snap.coverKey)
            .apply()
        return true
    }
}
