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
        SonanceWidgets.invalidateWidgetPresence(true)

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
                if (snap.mediaId != 0L) {
                    val handler = onToggleFavorite
                    if (handler != null) {
                        handler.invoke(snap.mediaId)
                    } else if (snap.mediaId > 0L) {
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
                    } else {
                        val prefs = Prefs(app)
                        val curOnline = prefs.onlineFavorites
                        val exists = curOnline.any { it.song.songId() == snap.mediaId }
                        val liked = if (exists) {
                            prefs.onlineFavorites = curOnline.filterNot { it.song.songId() == snap.mediaId }
                            false
                        } else {
                            val stored = prefs.lastOnlineSongJson?.let { raw ->
                                runCatching {
                                    com.sonance.player.data.storedOnlineSongFromJson(org.json.JSONObject(raw))
                                }.getOrNull()
                            }?.takeIf { it.songId() == snap.mediaId }
                            if (stored != null) {
                                prefs.onlineFavorites = curOnline + com.sonance.player.data.OnlineFavoriteEntry(
                                    song = stored,
                                    likedAt = System.currentTimeMillis()
                                )
                                true
                            } else false
                        }
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
                    PlaybackService.playerOrNull()?.repeatMode = when (next) {
                        PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                        PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
                        PlayMode.SHUFFLE, PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                    }
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

        // 服务还活着且队列非空 → 在同一个进程里同步操作 Player，零延迟也绕开前台服务启动开销；
        // 服务未运行或队列为空（如用户关闭应用后点桌面小组件）→ 借桌面小组件官方白名单用
        // startForegroundService 拉起 PlaybackService，服务端从磁盘缓存恢复上次歌单/进度并直接开播。
        if (PlaybackService.executeWidgetCommand(command)) return
        if (command == PlaybackCommands.PLAY_PAUSE ||
            command == PlaybackCommands.PREVIOUS ||
            command == PlaybackCommands.NEXT
        ) {
            if (!PlaybackService.hasRestorableSession(app)) return
            val serviceIntent = Intent(app, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_WIDGET_COMMAND
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(app, serviceIntent)
            }
        }
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
