package com.sonance.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sonance.player.MainActivity
import com.sonance.player.data.AudioTags
import com.sonance.player.data.LyricLine
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsCache
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.indexAt
import com.sonance.player.data.isRemoteUrl
import com.sonance.player.widget.SonanceWidgets
import com.sonance.player.widget.WidgetCommandReceiver
import com.sonance.player.widget.WidgetState
import java.util.concurrent.Executors

/**
 * 进程内播放服务句柄：小组件和播放服务同一个进程，命令可以直接、同步地送到 Player。
 * 放在文件顶层而不是 companion 里，避免私有成员可见性的坑。
 */
@Volatile
private var liveService: PlaybackService? = null

/**
 * 常驻媒体服务：负责后台播放、通知栏控制、耳机线控与蓝牙按键。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        // 必须先于 super.onCreate()：MediaSessionService 在 super 里就会建通知管理器，
        // 之后再换 provider 会直接抛异常。
        // 这里固定的是「标准媒体通知」——小米超级岛与妙享背屏都按这条通知识别播放状态，
        // 官方文档明确「接入媒体通知能力后将自动上岛」，不需要自己拼岛参数。
        PlaybackNotification.ensureChannel(this)
        setMediaNotificationProvider(PlaybackNotification.provider(this))

        super.onCreate()

        // 音频链路：卡拉OK（人声/伴奏）→ 浮点 EQ + 音效 → PCM 取样器。
        //
        // 顺序有讲究：
        // - 取样器放最后，可视化读到的是**用户实际听到的声音**（开了低音增强，
        //   柱子也真的往低频那边动）；反过来就会出现"调了音效但柱子没反应"。
        // - 卡拉OK 要在混响/声场之前，否则中置消除会把空间信息也一起消掉。
        // - EQ 与音效共用浮点缓冲：先留余量，再滤波、混合效果，最后一次量化，
        //   避免 EQ 在 16 位中间缓冲先削顶，后面的增益和限幅已经无法修复。
        //
        // ⚠️ 必须显式关掉 Audio Offload：硬件 offload 会把编码流直接送给 DSP，
        // 不做 PCM 解码，整条 AudioProcessor 链会被**完全绕过** —— 表现就是
        // "某些机型 / 某些格式下所有音效静默失效"。代价是省电档失效。
        // Media3 1.5.0 没有 setOffloadMode（那是更新版本才有的 API），
        // 这里通过 OffloadSupportProvider 恒定返回 DEFAULT_UNSUPPORTED 达到同样效果。
        val karaoke = KaraokeProcessor()
        val fx = SoundFxProcessor()
        val tap = PcmTapProcessor()
        val audioSink = DefaultAudioSink.Builder(this)
            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
            .setAudioProcessors(arrayOf<AudioProcessor>(karaoke, fx, tap))
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(onlineDataSource()))
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
        liveService = this
    }

    /**
     * 播放用的数据源。**本地和在线共用这一套**，所以每一项都得兼顾两边。
     *
     * ## 为什么必须自己建：跨协议重定向
     *
     * ExoPlayer 默认的 `DefaultHttpDataSource` 把
     * [DefaultHttpDataSource.Factory.setAllowCrossProtocolRedirects] 设成 **false** ——
     * 也就是**禁止 https → http 的跳转**（防降级攻击）。
     *
     * 而在线音源的取流接口恰好就是这种形态：
     * ```
     * https://music.163.com/song/media/outer/url?id=X.mp3
     *   └─ 302 ─→ http://m701.music.126.net/....mp3   ← 明文 http
     * ```
     * 用默认配置的话，这一步会被直接拒掉，表现为「所有在线歌都播不了」，
     * 而且错误码是重定向相关的，很难一眼看出是协议问题。
     *
     * ## 其它几项
     *
     * - **UA 必须像浏览器**：取流接口靠 UA 区分"网页播放器"和"脚本"，
     *   拿 ExoPlayer 的默认 UA 去要，偶尔会拿到一个 HTML 页面而不是 302。
     * - **超时放宽**：在线流要过 CDN，8 秒对某些节点不够；本地文件不受影响
     *   （本地走的是 `DefaultDataSource` 的 file/content 分支，根本不看这两个值）。
     */
    private fun onlineDataSource(): DefaultDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(ONLINE_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            // 只声明 `Accept`。刻意**不**设 `Accept-Encoding` ——
            // 自己写死编码的话，遇到 CDN 回一个我们解不开的格式就整首歌拉不到；
            // 交给 HttpURLConnection 默认协商 gzip 更稳。
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))

        return DefaultDataSource.Factory(this, http)
    }

    companion object {
        /** 服务收到显式播控命令时的 action（本应用私有，组件不导出）。 */
        const val ACTION_WIDGET_COMMAND = "sonance.widget.service_command"

        /** @return 命令是否真的送到了播放器 */
        fun executeWidgetCommand(command: String): Boolean {
            val player = liveService?.playerOrNull() ?: return false
            PlaybackCommands.execute(player, command)
            return true
        }

        internal fun playerOrNull(): Player? = liveService?.playerOrNull()

        /**
         * 带浏览器 UA 去请求在线音源。
         *
         * 不带的话取流接口偶尔会返回一个 HTML 页面而不是 302 —— 这是它区分
         * "网页播放器"和"脚本"的方式，不是必须登录。
         */
        const val ONLINE_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
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
            val mediaId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
            SonanceWidgets.onPlaybackChanged(
                context = applicationContext,
                mediaId = mediaId,
                title = title,
                artist = artist,
                isPlaying = player.isPlaying,
                albumId = meta?.extras?.getLong(PlayerController.EXTRA_ALBUM_ID, -1L) ?: -1L,
                sourceUri = meta?.extras?.getString(PlayerController.EXTRA_URI),
                coverKey = OnlineCover.keyOf(title, artist)
            )

            // 歌词只在换歌时读一次本地文件；后台绝不联网。
            val uriStr = meta?.extras?.getString(PlayerController.EXTRA_URI)
            val lyricKey = "$title|$artist|${player.currentMediaItem?.mediaId}"
            if (lyricKey != widgetLyricKey) {
                widgetLyricKey = lyricKey
                widgetLyricLines = emptyList()
                loadWidgetLyrics(lyricKey, title, artist, uriStr)
            }

            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            SonanceWidgets.onTick(applicationContext, position, duration, lyricTripletAt(position))
        }
    }

    // ===== 给桌面小组件的歌词 =====

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lyricLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sonance-widget-lyrics").apply { isDaemon = true }
    }

    @Volatile
    private var widgetLyricKey: String? = null

    @Volatile
    private var widgetLyricLines: List<LyricLine> = emptyList()

    /**
     * 读这首歌的时间轴歌词。
     *
     * 只用两个本地来源（都不联网）：
     * 1. 在线歌词在歌词页落盘的缓存（按「歌名+歌手+服务地址」查，也试一次空 salt）；
     * 2. 本地音频文件的内嵌歌词标签。
     *
     * 在线歌的 `sourceUri` 是取流地址，**绝不能**拿去给 MediaMetadataRetriever
     * 读内嵌标签 —— 那会把整首歌的音频流拉下来。纯文本歌词没有时间轴，
     * 小组件会显示「暂无同步歌词」而不是假装在滚动。
     */
    private fun loadWidgetLyrics(key: String, title: String, artist: String, uriStr: String?) {
        val app = applicationContext
        lyricLoader.execute {
            val lines = runCatching { readLocalLyrics(app, title, artist, uriStr) }
                .getOrDefault(emptyList())
            mainHandler.post {
                // 换歌比读文件快时，迟到的旧结果直接丢弃
                if (widgetLyricKey == key) widgetLyricLines = lines
            }
        }
    }

    private fun readLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        uriStr: String?
    ): List<LyricLine> {
        val apiBase = Prefs(context).lyricsApiBase
        val keys = listOfNotNull(
            LyricsCache.keyOf(title, artist, apiBase),
            LyricsCache.keyOf(title, artist, "")
        ).distinct()

        var raw: String? = null
        for (k in keys) {
            val hit = LyricsCache.lookup(context, k)
            if (hit is LyricsCache.Lookup.Hit) {
                raw = hit.text
                break
            }
        }
        if (raw == null && !uriStr.isNullOrBlank() && !isRemoteUrl(uriStr)) {
            raw = runCatching { AudioTags.readLyrics(context, Uri.parse(uriStr)) }.getOrNull()
        }
        val text = raw ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val synced = runCatching { LyricsRepository.parse(text) }.getOrNull() as? Lyrics.Synced
        return synced?.lines?.filter { it.text.isNotBlank() }.orEmpty()
    }

    private fun lyricTripletAt(positionMs: Long): WidgetState.LyricTriplet {
        val lines = widgetLyricLines
        if (lines.isEmpty()) return WidgetState.LyricTriplet.EMPTY
        val i = lines.indexAt(positionMs)
        return WidgetState.LyricTriplet(
            prev = lines.getOrNull(i - 1)?.text.orEmpty(),
            cur = if (i < 0) "" else lines[i].text,
            next = lines.getOrNull(i + 1)?.text.orEmpty(),
            hasLyrics = true
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_COMMAND) {
            intent.getStringExtra(WidgetCommandReceiver.EXTRA_COMMAND)?.let { command ->
                playerOrNull()?.let { PlaybackCommands.execute(it, command) }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 进程内命令通道取播放器，不把整个 session 暴露出去。 */
    internal fun playerOrNull(): Player? = mediaSession?.player

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
        if (liveService == this) liveService = null
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
