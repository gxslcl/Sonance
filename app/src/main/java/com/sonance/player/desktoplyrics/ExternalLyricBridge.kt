package com.sonance.player.desktoplyrics

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 外部状态栏歌词扩展桥接器（兼容 HyperLyric / LyricGetter / StatusBarLyric 标准广播协议）。
 *
 * 协议说明：
 * - 广播 Action：`"Lyric_Server"`
 * - 发送 `"type" = "app"`、`"package" = context.packageName`、`"lyric" = currentLine`
 *   以及 LyricGetter 2.x / HyperLyric 兼容的 `"Lyric_Data"` Bundle；
 * - 暂停 / 停止 / 关闭开关时发送空歌词清理状态栏残留；
 * - 相同歌词文本做严格去重，避免高频刷广播。
 */
object ExternalLyricBridge {

    private const val ACTION_LYRIC_SERVER = "Lyric_Server"

    @Volatile
    private var lastSentLyric: String? = null

    @Volatile
    private var lastSentActive: Boolean = false

    fun onTick(
        context: Context,
        enabled: Boolean,
        isPlaying: Boolean,
        lyricText: String?
    ) {
        if (!enabled || !isPlaying) {
            if (lastSentActive) {
                clear(context)
            }
            return
        }

        val line = lyricText?.trim().orEmpty()
        if (lastSentActive && line == lastSentLyric) return

        sendLyricBroadcast(context, line)
        lastSentLyric = line
        lastSentActive = true
    }

    fun clear(context: Context) {
        if (!lastSentActive && lastSentLyric.isNullOrEmpty()) return
        sendLyricBroadcast(context, "")
        lastSentLyric = ""
        lastSentActive = false
    }

    private fun sendLyricBroadcast(context: Context, lyric: String) {
        runCatching {
            val appContext = context.applicationContext
            val pkg = appContext.packageName
            val lyricData = Bundle().apply {
                putString("lyric", lyric)
                putString("packageName", pkg)
                putBoolean("customIcon", false)
                putBoolean("useOwnMusicController", false)
                putInt("type", 0)
            }
            val intent = Intent(ACTION_LYRIC_SERVER).apply {
                putExtra("type", "app")
                putExtra("package", pkg)
                putExtra("lyric", lyric)
                putExtra("customIcon", false)
                putExtra("useOwnMusicController", false)
                putExtra("Lyric_Data", lyricData)
            }
            appContext.sendBroadcast(intent)
        }
    }
}
