package com.sonance.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import com.sonance.player.data.Prefs
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.PlaybackCommands
import com.sonance.player.playback.PlaybackService

/**
 * 小组件按钮的落点。
 *
 * 不再经过 `androidx.media3.session.MediaButtonReceiver`：media3 1.5.0 在
 * API 26+ 会丢掉所有非播放类媒体按键（详见 [PlaybackCommands] 的说明），
 * 而且默认回调也不会走本应用自定义的切歌规则。这里改成**显式命令直达自己的服务**。
 *
 * `onReceive` 跑在应用主线程，正好符合 media3 对 `Player` 方法的线程要求。
 */
class WidgetCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val app = context.applicationContext

        when (command) {
            PlaybackCommands.TOGGLE_LYRICS -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                // 每个小组件各自记一个开关，默认全关（见 WidgetState）。
                WidgetState.setLyricOn(app, widgetId, !WidgetState.isLyricOn(app, widgetId))
                val pending = goAsync()
                Thread {
                    try {
                        runCatching { SonanceWidgets.refreshAll(app) }
                    } finally {
                        runCatching { pending.finish() }
                    }
                }.start()
                return
            }

            PlaybackCommands.TOGGLE_FAVORITE -> {
                val snap = WidgetState.read(app)
                if (snap.mediaId > 0L) {
                    val handler = onToggleFavorite
                    if (handler != null) {
                        handler.invoke(snap.mediaId)
                    } else {
                        val prefs = Prefs(app)
                        val liked = prefs.toggleFavorite(snap.mediaId)
                        WidgetState.write(app, snap.copy(isFavorite = liked))
                        val pending = goAsync()
                        Thread {
                            try {
                                runCatching { SonanceWidgets.refreshAll(app) }
                            } finally {
                                runCatching { pending.finish() }
                            }
                        }.start()
                    }
                }
                return
            }

            PlaybackCommands.CYCLE_PLAY_MODE -> {
                val handler = onCyclePlayMode
                if (handler != null) {
                    handler.invoke()
                } else {
                    val prefs = Prefs(app)
                    val cur = PlayMode.entries.firstOrNull { it.name == prefs.playMode } ?: PlayMode.REPEAT_ALL
                    val next = cur.next()
                    prefs.playMode = next.name
                    PlaybackService.playerOrNull()?.repeatMode =
                        if (next == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
                    val snap = WidgetState.read(app)
                    WidgetState.write(app, snap.copy(playMode = next.name))
                    val pending = goAsync()
                    Thread {
                        try {
                            runCatching { SonanceWidgets.refreshAll(app) }
                        } finally {
                            runCatching { pending.finish() }
                        }
                    }.start()
                }
                return
            }
        }

        // 服务还活着（听得到歌时必然活着）→ 在同一个进程里直接操作 Player：
        // 同步、零延迟，也完全绕开「后台启动前台服务」的系统限制。
        //
        // 服务不在时如实忽略。此刻播放队列已经没了，冷启动一个空媒体服务既放不出
        // 声音，又可能撞上 Android 12+ 的前台服务启动限制、甚至因没及时
        // startForeground 而被系统判异常。用户点卡片空白处打开应用即可重建现场。
        PlaybackService.executeWidgetCommand(command)
    }

    companion object {
        const val EXTRA_COMMAND = "sonance.widget.command"
        const val EXTRA_WIDGET_ID = "sonance.widget.id"

        private const val RC_BASE = 0x6000

        /** ViewModel 存活时注入的收藏与播放模式桥，确保小组件操作与 App 内队列/状态完全同步 */
        @Volatile
        var onToggleFavorite: ((Long) -> Unit)? = null

        @Volatile
        var onCyclePlayMode: (() -> Unit)? = null

        /**
         * `PendingIntent` 的相等判定用 `Intent.filterEquals`，**不看 extras**。
         * 所以命令差异必须体现在 action 上、widgetId 差异体现在 requestCode 上，
         * 否则「上一首」会覆盖「下一首」，或者多个小组件的歌词键全部改同一个 id。
         */
        fun pendingIntent(context: Context, command: String, widgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetCommandReceiver::class.java).apply {
                action = command
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                RC_BASE + widgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
