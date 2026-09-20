package com.sonance.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sonance.player.MainActivity
import com.sonance.player.data.OnlineCover
import com.sonance.player.widget.SonanceWidgets

/**
 * 常驻媒体服务：负责后台播放、通知栏控制、耳机线控与蓝牙按键。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 音频链路：均衡器 → PCM 取样器。
        //
        // 顺序有讲究：均衡器放在前面，取样器放在后面，于是可视化读到的是
        // **用户实际听到的声音** —— 开了低音增强，柱子也真的往低频那边动。
        // 反过来（取样在前）就会出现"调了音效但柱子没反应"的割裂感。
        val eq = EqProcessor()
        val tap = PcmTapProcessor()
        val audioSink = DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf<AudioProcessor>(eq, tap))
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setRenderersFactory(TapRenderersFactory(this, audioSink))
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(widgetSync)
    }

    /**
     * 播放状态 → 桌面小组件。
     *
     * 挂在**服务**上而不是 ViewModel 上：服务活多久、同步就活多久。
     * 用户播着歌把界面划掉之后，小组件照样能跟着切歌 —— 挂在 ViewModel 上的话
     * 界面一关就断流了。
     *
     * 进度每 500ms 会触发一次 `onEvents`，但 [SonanceWidgets.onPlaybackChanged]
     * 内部先比快照，内容没变直接返回，实际开销接近零。
     */
    private val widgetSync = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val meta = player.currentMediaItem?.mediaMetadata
            val title = meta?.title?.toString().orEmpty()
            val artist = meta?.artist?.toString().orEmpty()
            SonanceWidgets.onPlaybackChanged(
                context = applicationContext,
                title = title,
                artist = artist,
                isPlaying = player.isPlaying,
                albumId = meta?.extras?.getLong(PlayerController.EXTRA_ALBUM_ID, -1L) ?: -1L,
                sourceUri = meta?.extras?.getString(PlayerController.EXTRA_URI),
                coverKey = OnlineCover.keyOf(title, artist)
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(widgetSync)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * 唯一目的：把带 [PcmTapProcessor] 的 [AudioSink] 交给 ExoPlayer。
 *
 * `ExoPlayer.Builder` 没有 `setAudioSink`，注入音频链路的官方入口就是
 * [DefaultRenderersFactory.buildAudioSink]。
 */
private class TapRenderersFactory(
    context: Context,
    private val audioSink: AudioSink
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = audioSink
}
