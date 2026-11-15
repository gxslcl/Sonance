package com.sonance.player.desktoplyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 桌面歌词广播接收器：处理通知栏快捷解锁与关闭操作。
 */
class DesktopLyricsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_UNLOCK -> {
                DesktopLyricsManager.setLocked(app, false)
            }
            ACTION_CLOSE -> {
                DesktopLyricsManager.setEnabled(app, false)
            }
        }
    }

    companion object {
        const val ACTION_UNLOCK = "com.sonance.player.desktoplyrics.ACTION_UNLOCK"
        const val ACTION_CLOSE = "com.sonance.player.desktoplyrics.ACTION_CLOSE"
    }
}
