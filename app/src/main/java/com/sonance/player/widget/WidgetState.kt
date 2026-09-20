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

    data class Snapshot(
        val hasSong: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val isPlaying: Boolean = false,
        val albumId: Long = -1L,
        val sourceUri: String? = null,
        /** 已下载封面的缓存 key（[com.sonance.player.data.OnlineCover.keyOf]） */
        val coverKey: String? = null
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
            coverKey = sp.getString(K_COVER, null)
        )
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
