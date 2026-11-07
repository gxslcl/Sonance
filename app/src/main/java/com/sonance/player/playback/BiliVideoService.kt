package com.sonance.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.sonance.player.MainActivity
import com.sonance.player.R
import com.sonance.player.data.Prefs

/**
 * 视频「锁屏 / 后台继续播放」的前台服务 + 媒体通知。
 *
 * 为什么需要它：`BiliVideoSession` 已经把播放器抬到了应用级单例，但进程在后台
 * 没有任何前台组件时，Android 会把它很快回收 —— 声音断掉而 UI 还记着「正在播」，
 * 正是用户最讨厌的那种「状态撒谎」。前台服务把这个进程钉住，媒体通知则给锁屏 /
 * 通知栏一个真实的播放入口。
 *
 * 设计上的克制：
 * - 只有开关打开、且真的有播放实例时才启动；`stop()` / 退出应用 / 关开关立刻停；
 * - `START_NOT_STICKY`：进程被系统杀掉后**不**自动复活，避免留下一条
 *   「显示在播、实际没声」的幽灵通知；
 * - 进度每秒刷新一次，通知 `setOnlyAlertOnce`，不会反复打扰；
 * - 唤醒锁不在这里拿：`BiliVideoPlayer` 用 `MediaPlayer.setWakeMode`，
 *   只在真正播放期间持有，暂停即释放。
 */
class BiliVideoService : Service() {

    companion object {
        private const val TAG = "SonanceVideoSvc"
        private const val CHANNEL_ID = "bili_video_playback"
        private const val NOTIFICATION_ID = 1201

        const val ACTION_TOGGLE = "com.sonance.player.bili.TOGGLE"
        const val ACTION_PREV = "com.sonance.player.bili.PREV"
        const val ACTION_NEXT = "com.sonance.player.bili.NEXT"
        const val ACTION_CLOSE = "com.sonance.player.bili.CLOSE"

        /**
         * 服务是否已经在跑。
         *
         * 用来避免「后台自动连播下一条」时再调一次 [start]：在后台对已经存在的
         * 前台服务重复 `startForegroundService` 在 Android 12+ 会被判成
         * `ForegroundServiceStartNotAllowedException`。已经在跑就直接复用。
         */
        @Volatile
        private var alive = false

        /** 开关打开且存在播放实例时调用；必须在应用仍处前台时调用（系统限制） */
        fun start(context: Context) {
            if (alive) return
            val intent = Intent(context, BiliVideoService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动后台播放服务失败: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BiliVideoService::class.java)) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var mediaSession: MediaSession

    @Volatile
    private var running = false

    private val session get() = BiliVideoSession.of(this)

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val s = session
            // 开关被关掉 / 播放现场已清空：主动收摊，通知不留在那里假装还能控制
            if (!prefs.biliBackgroundPlay || (s.items.isEmpty() && s.playingKey == null)) {
                shutdown()
                return
            }
            syncNotification()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        mediaSession = MediaSession(this, "SonanceVideo").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    session.play()
                    syncNotification()
                }

                override fun onPause() {
                    session.pause()
                    syncNotification()
                }

                override fun onSkipToNext() {
                    session.next()
                    syncNotification()
                }

                override fun onSkipToPrevious() {
                    session.previous()
                    syncNotification()
                }

                override fun onSeekTo(pos: Long) {
                    session.seekTo(pos.toInt())
                    syncNotification()
                }

                override fun onStop() {
                    session.stop()
                    shutdown()
                }
            })
            isActive = true
        }
        running = true
        alive = true
        // startForegroundService 有 5 秒窗口，先把通知挂上去再谈别的
        syncNotification()
        main.postDelayed(ticker, 1_000L)
        Log.i(TAG, "服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> session.toggle()
            ACTION_PREV -> session.previous()
            ACTION_NEXT -> session.next()
            ACTION_CLOSE -> session.stop()
            else -> Unit
        }
        if (running) syncNotification()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户把任务从最近任务里划掉：不留孤儿播放器与通知
        runCatching { session.stop() }
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        alive = false
        main.removeCallbacks(ticker)
        runCatching {
            mediaSession.isActive = false
            mediaSession.release()
        }
        Log.i(TAG, "服务销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== 通知 =====

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频后台播放",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "锁屏 / 切到后台时继续播放视频音频"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun syncNotification() {
        val s = session
        val item = s.current
        val playing = s.isPlaying
        val position = s.position
        val duration = s.duration

        // 播放状态：锁屏 / 系统媒体面板读这里，不依赖通知文本
        val state = when {
            s.playingKey == null -> PlaybackState.STATE_STOPPED
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        runCatching {
            mediaSession.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO or
                            PlaybackState.ACTION_STOP
                    )
                    .setState(state, position, if (playing) 1f else 0f)
                    .build()
            )
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, item?.title ?: "视频")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.owner.orEmpty())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                    .build()
            )
        }

        val notification = buildNotification(playing, position, duration, item?.title, item?.owner)
        // 先保证前台身份，再更新内容；重复调用是安全的
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun buildNotification(
        playing: Boolean,
        position: Long,
        duration: Long,
        title: String?,
        owner: String?
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title ?: "视频播放")
            .setContentText(
                buildString {
                    if (!owner.isNullOrBlank()) append(owner).append(" · ")
                    append(formatClock(position)).append(" / ").append(formatClock(duration))
                }
            )
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)

        if (duration > 0) {
            val percent = (position * 100L / duration).coerceIn(0L, 100L).toInt()
            builder.setProgress(100, percent, false)
        }

        val prev = actionIntent(2, ACTION_PREV)
        val toggle = actionIntent(3, ACTION_TOGGLE)
        val next = actionIntent(4, ACTION_NEXT)
        val close = actionIntent(5, ACTION_CLOSE)

        builder
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "上一首", prev
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (playing) "暂停" else "播放",
                    toggle
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "下一首", next
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "关闭", close
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    private fun actionIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BiliVideoService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun shutdown() {
        if (!running) return
        running = false
        main.removeCallbacks(ticker)
        runCatching {
            mediaSession.isActive = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val sec = total % 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, sec)
        else "%d:%02d".format(m, sec)
    }
}
