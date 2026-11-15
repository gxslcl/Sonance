package com.sonance.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import com.sonance.player.desktoplyrics.DesktopLyricsManager
import com.sonance.player.widget.SonanceWidgets
import com.sonance.player.widget.WidgetCommandReceiver
import com.sonance.player.widget.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
    private var transitions: TransitionEffects? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var pendingBtRecovery: Runnable? = null

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
        // ⚠️ 必须保持 DefaultAudioSink 默认浮点关闭（setEnableFloatOutput(false)）：
        //    Media3 1.5.0 在 enableFloatOutput=true 且播放 24/32bit Hi-Res 音轨时会直接跳过整个
        //    audioProcessorChain（导致均衡器、音效、卡拉OK 和频谱全失效）。关闭后各 Processor 已
        //    通过 PcmCodec 原生解码 16/24/32/Float 并在内部全程 32/64 位处理。
        val audioSink = DefaultAudioSink.Builder(this)
            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
            .setEnableFloatOutput(false)
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

        // 曲目衔接（无缝开关 + 淡入淡出）与 USB 独占：都跟着服务生命周期走
        transitions = TransitionEffects(player, this).also {
            player.addListener(it)
            it.start()
        }
        UsbExclusive.syncWithPrefs(this)
        SystemAudioEffects.syncSession(this, Prefs(this).systemAudioEffectEnabled, player.audioSessionId)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                SystemAudioEffects.syncSession(
                    this@PlaybackService,
                    Prefs(this@PlaybackService).systemAudioEffectEnabled,
                    audioSessionId
                )
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                if (format.sampleRate > 0) {
                    UsbExclusive.syncTrackFormat(
                        context = this@PlaybackService,
                        sampleRateHz = format.sampleRate,
                        pcmEncoding = format.pcmEncoding,
                        channelCount = format.channelCount
                    )
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(widgetSync)
        liveService = this
        watchBluetoothRoute(player)

        if (player.isPlaying) {
            mainHandler.post(playbackTicker)
        }

        if (Prefs(this).desktopLyricsEnabled && DesktopLyricsManager.hasOverlayPermission(this)) {
            DesktopLyricsManager.show(this)
        }
    }

    /**
     * 监听蓝牙 / 有线 / 外放输出切换：
     * 部分机型（尤其同时开启经典蓝牙 A2DP 与 LE Audio 的系统）在蓝牙连接或切换时，
     * 已创建的 AudioTrack 会继续挂在旧输出通道上，出现「进度条在走但蓝牙耳机无声」。
     * 在输出切换到蓝牙时清掉过期首选设备并在 HAL 握手稳定后原地 seek 刷新 AudioSink，
     * 迫使 ExoPlayer 重建指向当前蓝牙通道的 AudioTrack。
     */
    private fun watchBluetoothRoute(player: ExoPlayer) {
        val am = getSystemService(AudioManager::class.java) ?: return
        var lastRoute = OutputAudioRouter.detectCurrentRoute(this)
        var lastBtIds = bluetoothMediaDeviceIds(am)

        val scheduleRebuild = {
            pendingBtRecovery?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingBtRecovery = null
                if (liveService != this) return@Runnable
                player.setPreferredAudioDevice(null)
                transitions?.ensureActiveVolume()
                if (OutputAudioRouter.detectCurrentRoute(this) == AudioOutputRoute.BLUETOOTH &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED &&
                    player.isCurrentMediaItemSeekable &&
                    transitions?.isSwitchFadingNow() != true
                ) {
                    player.seekTo(player.currentPosition.coerceAtLeast(0L))
                }
            }
            pendingBtRecovery = task
            mainHandler.postDelayed(task, 280L)
        }

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                val relevant = addedDevices.any { isBluetoothMediaOutput(it) || isExternalRouteOutput(it) }
                if (!relevant) return
                val newRoute = OutputAudioRouter.detectCurrentRoute(this@PlaybackService)
                val newBtIds = bluetoothMediaDeviceIds(am)
                val btAppeared = newBtIds.any { it !in lastBtIds }
                val enteredBt = newRoute == AudioOutputRoute.BLUETOOTH && (lastRoute != AudioOutputRoute.BLUETOOTH || btAppeared)
                lastRoute = newRoute
                lastBtIds = newBtIds
                if (enteredBt) {
                    scheduleRebuild()
                } else {
                    player.setPreferredAudioDevice(null)
                    transitions?.ensureActiveVolume()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                val relevant = removedDevices.any { isBluetoothMediaOutput(it) || isExternalRouteOutput(it) }
                if (!relevant) return
                val newRoute = OutputAudioRouter.detectCurrentRoute(this@PlaybackService)
                val newBtIds = bluetoothMediaDeviceIds(am)
                val hadBt = removedDevices.any { isBluetoothMediaOutput(it) }
                lastRoute = newRoute
                lastBtIds = newBtIds
                player.setPreferredAudioDevice(null)
                transitions?.ensureActiveVolume()
                if (hadBt && newRoute == AudioOutputRoute.BLUETOOTH && newBtIds.isNotEmpty()) {
                    scheduleRebuild()
                }
            }
        }
        runCatching {
            am.registerAudioDeviceCallback(cb, mainHandler)
            audioDeviceCallback = cb
        }
    }

    private fun bluetoothMediaDeviceIds(am: AudioManager): Set<Int> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isBluetoothMediaOutput)
            .map { it.id }
            .toSet()

    private fun isBluetoothMediaOutput(info: AudioDeviceInfo): Boolean =
        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 28 && info.type == AudioDeviceInfo.TYPE_HEARING_AID) ||
            (Build.VERSION.SDK_INT >= 31 && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= 33 && info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

    private fun isExternalRouteOutput(info: AudioDeviceInfo): Boolean =
        info.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            info.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            info.type == AudioDeviceInfo.TYPE_AUX_LINE ||
            info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

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
        fun executeWidgetCommand(command: String): Boolean =
            liveService?.executeWithTransitionReset(command) ?: false

        internal fun switchTrackWithFade(index: Int, fadeMs: Long): Boolean =
            liveService?.switchTrackWithFade(index, fadeMs) ?: false

        internal fun cancelSwitchFade(): Boolean =
            liveService?.cancelSwitchFade() ?: false

        internal fun syncUserVolume(volume: Float) {
            liveService?.syncUserVolume(volume)
        }

        internal fun playerOrNull(): Player? = liveService?.playerOrNull()

        internal fun currentAudioSessionId(): Int {
            val exo = liveService?.playerOrNull() as? ExoPlayer
            return exo?.audioSessionId?.takeIf { it > 0 } ?: SystemAudioEffects.latestPlayerSessionId
        }

        internal fun syncSystemAudioEffects() {
            val svc = liveService ?: return
            val exo = svc.playerOrNull() as? ExoPlayer ?: return
            SystemAudioEffects.syncSession(svc, Prefs(svc).systemAudioEffectEnabled, exo.audioSessionId)
        }

        internal fun canonicalLyricKey(
            mediaId: Long,
            title: String,
            artist: String,
            revision: Long = LyricsRepository.revision.value
        ): String = "$mediaId|${title.trim()}|${artist.trim()}|$revision"

        internal fun trackPartOfLyricKey(
            mediaId: Long,
            title: String,
            artist: String
        ): String = "$mediaId|${title.trim()}|${artist.trim()}"

        internal fun matchesSameTrack(key: String?, mediaId: Long, title: String, artist: String): Boolean {
            if (key.isNullOrEmpty()) return false
            val prefix = trackPartOfLyricKey(mediaId, title, artist)
            return key.startsWith("$prefix|")
        }

        internal fun syncLiveLyrics(title: String, artist: String, lines: List<LyricLine>) {
            syncLiveLyrics(0L, title, artist, lines)
        }

        internal fun syncLiveLyrics(
            songId: Long,
            title: String,
            artist: String,
            lines: List<LyricLine>
        ) {
            val svc = liveService ?: return
            val cleaned = lines.filter { it.text.isNotBlank() }
            svc.runOnMain {
                val player = svc.playerOrNull()
                val item = player?.currentMediaItem
                val curId = item?.mediaId?.toLongOrNull() ?: -1L
                val curMeta = item?.mediaMetadata
                val curTitle = curMeta?.title?.toString().orEmpty()
                val curArtist = curMeta?.artist?.toString().orEmpty()
                val hasActiveItem = item != null && (curId != -1L || curTitle.isNotBlank())
                val matchesCurrent = !hasActiveItem || when {
                    curId != 0L && curId != -1L && songId != 0L && songId != -1L -> curId == songId
                    else -> curTitle.trim() == title.trim() && curArtist.trim() == artist.trim()
                }
                if (!matchesCurrent) return@runOnMain

                val resolvedId = if (curId != -1L) curId else songId
                val resolvedTitle = curTitle.ifBlank { title }
                val resolvedArtist = curArtist.ifBlank { artist }
                val key = canonicalLyricKey(resolvedId, resolvedTitle, resolvedArtist)
                svc.activeTrackIdentity = trackPartOfLyricKey(resolvedId, resolvedTitle, resolvedArtist)
                svc.pendingOnlineLyricJob?.cancel()
                svc.pendingOnlineLyricJob = null
                synchronized(svc.lyricLoadLock) {
                    svc.widgetLyricInFlightKey = null
                    svc.widgetLyricKey = key
                    svc.widgetLyricLines = cleaned
                }
                svc.tickLive()
                svc.enqueueWidgetLivePush()
            }
        }

        internal fun refreshLyricsIfEmpty() {
            val svc = liveService ?: return
            svc.runOnMain {
                if (svc.widgetLyricLines.isEmpty()) {
                    synchronized(svc.lyricLoadLock) {
                        svc.widgetLyricKey = null
                        svc.widgetLyricInFlightKey = null
                    }
                    svc.tickLive()
                }
            }
        }

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
    private data class WidgetSyncSnapshot(
        val mediaId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val isPlaying: Boolean,
        val albumId: Long,
        val sourceUri: String?,
        val remoteSource: String,
        val remoteId: String?,
        val coverKey: String?,
        val positionMs: Long,
        val durationMs: Long
    )

    private data class TrackLyricQuery(
        val lyricKey: String,
        val mediaId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val sourceUri: String?,
        val remoteSource: String,
        val remoteId: String?
    )

    private data class WidgetTickPayload(
        val positionMs: Long,
        val durationMs: Long,
        val triplet: WidgetState.LyricTriplet
    )

    private sealed interface LocalLyricsLookup {
        data class Resolved(val lines: List<LyricLine>) : LocalLyricsLookup
        data object NeedOnline : LocalLyricsLookup
    }

    private val pendingWidgetSync = AtomicReference<WidgetSyncSnapshot?>(null)
    private val pendingWidgetTick = AtomicReference<WidgetTickPayload?>(null)

    private val widgetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sonance-widget-sync").apply { isDaemon = true }
    }

    private val lyricScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingOnlineLyricJob: Job? = null
    private val lyricLoadLock = Any()

    @Volatile
    private var activeTrackIdentity: String = ""

    @Volatile
    private var lastPushedWidgetTickSig: String? = null

    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = mediaSession?.player
            if (player != null && player.isPlaying) {
                tickLive()
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun queryFromPlayer(player: Player): TrackLyricQuery {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId?.toLongOrNull() ?: -1L
        val meta = item?.mediaMetadata
        val title = meta?.title?.toString().orEmpty()
        val artist = meta?.artist?.toString().orEmpty()
        val album = meta?.albumTitle?.toString().orEmpty()
        val extras = meta?.extras
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: meta?.durationMs?.takeIf { it > 0L }
            ?: extras?.getLong(PlayerController.EXTRA_DURATION_MS, 0L)?.takeIf { it > 0L }
            ?: 0L
        return TrackLyricQuery(
            lyricKey = canonicalLyricKey(mediaId, title, artist),
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            sourceUri = extras?.getString(PlayerController.EXTRA_URI),
            remoteSource = extras?.getString(PlayerController.EXTRA_SOURCE).orEmpty(),
            remoteId = extras?.getString(PlayerController.EXTRA_REMOTE_ID)
        )
    }

    private fun tickLive() {
        val player = mediaSession?.player ?: return
        val query = queryFromPlayer(player)
        val posMs = player.currentPosition.coerceAtLeast(0L)
        activeTrackIdentity = trackPartOfLyricKey(query.mediaId, query.title, query.artist)

        // 计算当前歌曲的歌词延迟/提前偏移 (lyricsOffsetMs)
        val offsetMs = Prefs(applicationContext).lyricsOffsetMs(query.title, query.artist)
        val effectivePosMs = (posMs + offsetMs).coerceAtLeast(0L)

        // 切到新歌时立即清空上一首的残留歌词，绝不在主线程做磁盘或网络 I/O
        val prevKey = widgetLyricKey
        if (!matchesSameTrack(prevKey, query.mediaId, query.title, query.artist) && widgetLyricLines.isNotEmpty()) {
            widgetLyricLines = emptyList()
        }
        if (query.title.isNotBlank() &&
            query.lyricKey != widgetLyricKey &&
            query.lyricKey != widgetLyricInFlightKey
        ) {
            requestServiceLyricsLoad(query)
        }

        val triplet = lyricTripletAt(effectivePosMs)

        DesktopLyricsManager.onTick(
            context = applicationContext,
            isPlaying = player.isPlaying,
            title = query.title,
            artist = query.artist,
            lyricTriplet = triplet
        )

        val posSec = (posMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val durSec = (query.durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val tickSig = "${query.mediaId}|$posSec|$durSec|${triplet.hasLyrics}|${triplet.prev}|${triplet.cur}|${triplet.next}"
        if (tickSig != lastPushedWidgetTickSig) {
            lastPushedWidgetTickSig = tickSig
            val payload = WidgetTickPayload(posMs, query.durationMs, triplet)
            if (pendingWidgetTick.getAndSet(payload) == null) {
                widgetExecutor.execute { drainWidgetTick() }
            }
        }
    }

    private fun enqueueWidgetLivePush() {
        val player = mediaSession?.player ?: return
        val query = queryFromPlayer(player)
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val offsetMs = Prefs(applicationContext).lyricsOffsetMs(query.title, query.artist)
        val triplet = lyricTripletAt((posMs + offsetMs).coerceAtLeast(0L))
        lastPushedWidgetTickSig = null
        val payload = WidgetTickPayload(posMs, query.durationMs, triplet)
        if (pendingWidgetTick.getAndSet(payload) == null) {
            widgetExecutor.execute { drainWidgetTick() }
        }
    }

    private fun drainWidgetTick() {
        while (true) {
            val payload = pendingWidgetTick.getAndSet(null) ?: break
            runCatching {
                if (SonanceWidgets.hasAnyWidgets(applicationContext)) {
                    SonanceWidgets.onTick(
                        applicationContext,
                        payload.positionMs,
                        payload.durationMs,
                        payload.triplet
                    )
                }
            }
        }
    }

    private val widgetSync = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // 主线程只读内存中的轻量字段，Pref 读写 / 歌词读取 / RemoteViews 构建全部转到后台串行执行，
            // 快速连滑切歌时原子合并待处理状态，避免主线程卡顿。
            val item = player.currentMediaItem
            val meta = item?.mediaMetadata
            val title = meta?.title?.toString().orEmpty()
            val artist = meta?.artist?.toString().orEmpty()
            val mediaId = item?.mediaId?.toLongOrNull() ?: -1L
            val extras = meta?.extras
            val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: meta?.durationMs?.takeIf { it > 0L }
                ?: extras?.getLong(PlayerController.EXTRA_DURATION_MS, 0L)?.takeIf { it > 0L }
                ?: 0L
            activeTrackIdentity = trackPartOfLyricKey(mediaId, title, artist)
            val snapshot = WidgetSyncSnapshot(
                mediaId = mediaId,
                title = title,
                artist = artist,
                album = meta?.albumTitle?.toString().orEmpty(),
                isPlaying = player.isPlaying,
                albumId = extras?.getLong(PlayerController.EXTRA_ALBUM_ID, -1L) ?: -1L,
                sourceUri = extras?.getString(PlayerController.EXTRA_URI),
                remoteSource = extras?.getString(PlayerController.EXTRA_SOURCE).orEmpty(),
                remoteId = extras?.getString(PlayerController.EXTRA_REMOTE_ID),
                coverKey = OnlineCover.keyOf(title, artist),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = durationMs
            )
            if (pendingWidgetSync.getAndSet(snapshot) == null) {
                widgetExecutor.execute { drainWidgetSync() }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mainHandler.removeCallbacks(playbackTicker)
            if (isPlaying) {
                mainHandler.post(playbackTicker)
            } else {
                tickLive()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            tickLive()
        }
    }

    private fun drainWidgetSync() {
        while (true) {
            val s = pendingWidgetSync.getAndSet(null) ?: break
            runCatching {
                SonanceWidgets.onPlaybackChanged(
                    context = applicationContext,
                    mediaId = s.mediaId,
                    title = s.title,
                    artist = s.artist,
                    isPlaying = s.isPlaying,
                    albumId = s.albumId,
                    sourceUri = s.sourceUri,
                    coverKey = s.coverKey
                )

                val query = TrackLyricQuery(
                    lyricKey = canonicalLyricKey(s.mediaId, s.title, s.artist),
                    mediaId = s.mediaId,
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    durationMs = s.durationMs,
                    sourceUri = s.sourceUri,
                    remoteSource = s.remoteSource,
                    remoteId = s.remoteId
                )
                if (s.title.isNotBlank() &&
                    query.lyricKey != widgetLyricKey &&
                    query.lyricKey != widgetLyricInFlightKey
                ) {
                    val claimed = synchronized(lyricLoadLock) {
                        if (query.lyricKey == widgetLyricKey || query.lyricKey == widgetLyricInFlightKey) {
                            false
                        } else {
                            widgetLyricInFlightKey = query.lyricKey
                            true
                        }
                    }
                    if (claimed) {
                        resolveAndApplyLyricsInBackground(query)
                    }
                }

                val offsetMs = Prefs(applicationContext).lyricsOffsetMs(s.title, s.artist)
                val effectivePosMs = (s.positionMs + offsetMs).coerceAtLeast(0L)
                val triplet = lyricTripletAt(effectivePosMs)

                SonanceWidgets.onTick(
                    applicationContext,
                    s.positionMs,
                    s.durationMs,
                    triplet
                )
                DesktopLyricsManager.onTick(
                    context = applicationContext,
                    isPlaying = s.isPlaying,
                    title = s.title,
                    artist = s.artist,
                    lyricTriplet = triplet
                )
            }
        }
    }

    // ===== 给桌面小组件的歌词 =====

    @Volatile
    private var widgetLyricKey: String? = null

    @Volatile
    private var widgetLyricLines: List<LyricLine> = emptyList()

    @Volatile
    private var widgetLyricInFlightKey: String? = null

    private fun requestServiceLyricsLoad(query: TrackLyricQuery) {
        synchronized(lyricLoadLock) {
            if (query.lyricKey == widgetLyricKey || query.lyricKey == widgetLyricInFlightKey) return
            widgetLyricInFlightKey = query.lyricKey
        }
        widgetExecutor.execute {
            resolveAndApplyLyricsInBackground(query)
        }
    }

    private fun resolveAndApplyLyricsInBackground(query: TrackLyricQuery) {
        val local = runCatching {
            readLocalLyrics(
                context = applicationContext,
                songId = query.mediaId,
                title = query.title,
                artist = query.artist,
                uriStr = query.sourceUri,
                remoteSource = query.remoteSource
            )
        }.getOrDefault(LocalLyricsLookup.Resolved(emptyList()))

        when (local) {
            is LocalLyricsLookup.Resolved -> {
                val applied = applyLoadedLyricsIfStillCurrent(query, local.lines)
                synchronized(lyricLoadLock) {
                    if (widgetLyricInFlightKey == query.lyricKey) widgetLyricInFlightKey = null
                }
                if (applied) {
                    runOnMain {
                        tickLive()
                        enqueueWidgetLivePush()
                    }
                }
            }
            LocalLyricsLookup.NeedOnline -> {
                val prefs = Prefs(applicationContext)
                val shouldFetchOnline = query.title.isNotBlank() &&
                    prefs.onlineLyricsEnabled &&
                    (DesktopLyricsManager.isShowing() || prefs.desktopLyricsEnabled || SonanceWidgets.hasAnyWidgets(applicationContext))

                applyLoadedLyricsIfStillCurrent(query, emptyList())
                if (shouldFetchOnline) {
                    scheduleOnlineLyricsFetch(query)
                } else {
                    synchronized(lyricLoadLock) {
                        if (widgetLyricInFlightKey == query.lyricKey) widgetLyricInFlightKey = null
                    }
                    runOnMain {
                        tickLive()
                        enqueueWidgetLivePush()
                    }
                }
            }
        }
    }

    private fun scheduleOnlineLyricsFetch(query: TrackLyricQuery) {
        pendingOnlineLyricJob?.cancel()
        pendingOnlineLyricJob = lyricScope.launch {
            delay(350L)
            val expectedTrack = trackPartOfLyricKey(query.mediaId, query.title, query.artist)
            if (activeTrackIdentity.isNotEmpty() && activeTrackIdentity != expectedTrack) {
                synchronized(lyricLoadLock) {
                    if (widgetLyricInFlightKey == query.lyricKey) widgetLyricInFlightKey = null
                }
                return@launch
            }
            val uri = query.sourceUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: Uri.EMPTY
            val prefs = Prefs(applicationContext)
            val result = runCatching {
                LyricsRepository.load(
                    context = applicationContext,
                    songId = query.mediaId,
                    uri = uri,
                    title = query.title,
                    artist = query.artist,
                    album = query.album,
                    onlineEnabled = prefs.onlineLyricsEnabled,
                    apiBase = prefs.lyricsApiBase,
                    convertToSimplified = prefs.lyricsConvertToSimplified,
                    durationMs = query.durationMs,
                    neteaseEnabled = prefs.neteaseFillEnabled,
                    remoteSource = query.remoteSource,
                    remoteId = query.remoteId
                )
            }.getOrNull()
            val lines = (result?.lyrics as? Lyrics.Synced)?.lines
                ?.filter { it.text.isNotBlank() }
                .orEmpty()
            synchronized(lyricLoadLock) {
                if (widgetLyricInFlightKey == query.lyricKey) widgetLyricInFlightKey = null
            }
            if (applyLoadedLyricsIfStillCurrent(query, lines)) {
                runOnMain {
                    tickLive()
                    enqueueWidgetLivePush()
                }
            }
        }
    }

    private fun applyLoadedLyricsIfStillCurrent(
        query: TrackLyricQuery,
        lines: List<LyricLine>
    ): Boolean {
        val currentRev = LyricsRepository.revision.value
        val expectedTrack = trackPartOfLyricKey(query.mediaId, query.title, query.artist)
        val active = activeTrackIdentity
        if (active.isNotEmpty() && active != expectedTrack) return false
        synchronized(lyricLoadLock) {
            if (query.lyricKey != canonicalLyricKey(query.mediaId, query.title, query.artist, currentRev)) {
                return false
            }
            widgetLyricKey = query.lyricKey
            widgetLyricLines = lines
        }
        return true
    }

    private fun readLocalLyrics(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        uriStr: String?,
        remoteSource: String = ""
    ): LocalLyricsLookup {
        val prefs = Prefs(context)
        val convertT2S = prefs.lyricsConvertToSimplified

        // 0) 用户手动导入 / 显式删除覆盖（优先级最高）
        when (val ov = LyricsCache.lookupOverride(context, songId)) {
            is LyricsCache.Override.Deleted -> return LocalLyricsLookup.Resolved(emptyList())
            is LyricsCache.Override.Imported -> {
                return LocalLyricsLookup.Resolved(parseSyncedLines(context, ov.text, convertT2S))
            }
            LyricsCache.Override.None -> Unit
        }

        // 1) 内嵌标签
        if (!uriStr.isNullOrBlank() && !isRemoteUrl(uriStr)) {
            val embedded = runCatching { AudioTags.readLyrics(context, Uri.parse(uriStr)) }.getOrNull()
            if (!embedded.isNullOrBlank()) {
                return LocalLyricsLookup.Resolved(parseSyncedLines(context, embedded, convertT2S))
            }
        }

        // 2) 本地在线缓存（含洛雪命名空间、当前服务地址、空盐值、以及 songId 别名）
        val apiBase = prefs.lyricsApiBase
        val lxPlatform = com.sonance.player.data.lx.LxPlatform.platformIdFromSource(remoteSource)
        val effectiveBase = if (lxPlatform != null) "$apiBase|$remoteSource" else apiBase
        val primaryKey = LyricsCache.keyOf(title, artist, effectiveBase)
        val candidateKeys = buildList {
            if (primaryKey != null) add(primaryKey)
            LyricsCache.keyOf(title, artist, apiBase)?.let { if (it !in this) add(it) }
            LyricsCache.keyOf(title, artist, "")?.let { if (it !in this) add(it) }
            if (songId != 0L && songId != -1L) add("id:$songId")
        }

        var rawHit: String? = null
        var primaryKnownMiss = false
        for ((index, key) in candidateKeys.withIndex()) {
            when (val lookup = LyricsCache.lookup(context, key)) {
                is LyricsCache.Lookup.Hit -> {
                    rawHit = lookup.text
                    break
                }
                is LyricsCache.Lookup.KnownMiss -> {
                    if (index == 0) primaryKnownMiss = true
                }
                LyricsCache.Lookup.Unknown -> Unit
            }
        }

        if (!rawHit.isNullOrBlank()) {
            return LocalLyricsLookup.Resolved(parseSyncedLines(context, rawHit, convertT2S))
        }
        if (primaryKnownMiss) {
            return LocalLyricsLookup.Resolved(emptyList())
        }
        return LocalLyricsLookup.NeedOnline
    }

    private fun parseSyncedLines(
        context: Context,
        rawText: String,
        convertToSimplified: Boolean
    ): List<LyricLine> {
        if (rawText.isBlank()) return emptyList()
        val shown = if (convertToSimplified) {
            com.sonance.player.data.ChineseConverter.toSimplified(context, rawText)
        } else {
            rawText
        }
        val synced = runCatching { LyricsRepository.parse(shown) }.getOrNull() as? Lyrics.Synced
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
                executeWithTransitionReset(command)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 进程内命令通道取播放器，不把整个 session 暴露出去。 */
    internal fun playerOrNull(): Player? = mediaSession?.player

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun executeWithTransitionReset(command: String): Boolean {
        val player = playerOrNull() ?: return false
        runOnMain {
            transitions?.cancelSwitchFade(restoreVolume = true)
            PlaybackCommands.execute(player, command)
        }
        return true
    }

    /** 由服务端 TransitionEffects 原子执行切歌淡出与切轨，防止跨线程/轮询污染 userVolume。 */
    internal fun switchTrackWithFade(index: Int, fadeMs: Long): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val player = playerOrNull() ?: return false
        val fx = transitions ?: return false
        if (index !in 0 until player.mediaItemCount) return false
        fx.beginSwitchFade(fadeMs) {
            if (index in 0 until player.mediaItemCount) {
                player.seekToDefaultPosition(index)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
        return true
    }

    internal fun cancelSwitchFade(): Boolean {
        val fx = transitions ?: return false
        runOnMain { fx.cancelSwitchFade(restoreVolume = true) }
        return true
    }

    internal fun syncUserVolume(volume: Float) {
        val fx = transitions ?: return
        runOnMain { fx.setUserVolume(volume) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        pendingOnlineLyricJob?.cancel()
        pendingOnlineLyricJob = null
        lyricScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        DesktopLyricsManager.hide(this)
        pendingBtRecovery?.let { mainHandler.removeCallbacks(it) }
        pendingBtRecovery = null
        audioDeviceCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        SystemAudioEffects.releaseSession(this)
        mediaSession?.run {
            transitions?.let {
                player.removeListener(it)
                it.release()
            }
            player.removeListener(widgetSync)
            player.release()
            release()
        }
        transitions = null
        mediaSession = null
        widgetExecutor.shutdown()
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
