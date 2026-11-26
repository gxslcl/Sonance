package com.sonance.player.playback

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.sonance.player.desktoplyrics.ExternalLyricBridge
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import androidx.compose.ui.graphics.asAndroidBitmap
import com.sonance.player.MainActivity
import com.sonance.player.R
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.AudioTags
import com.sonance.player.data.LockScreenLyrics
import com.sonance.player.data.LyricLine
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsCache
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.PlaybackRestore
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.decodeSongsFromJson
import com.sonance.player.data.indexAt
import com.sonance.player.data.isExternalSongId
import com.sonance.player.data.isRemoteSongId
import com.sonance.player.data.isRemoteUrl
import com.sonance.player.data.storedOnlineSongFromJson
import com.sonance.player.data.storedOnlineSongsFromJson
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程内播放服务句柄：小组件和播放服务同一个进程，命令可以直接、同步地送到 Player。
 * 放在文件顶层而不是 companion 里，避免私有成员可见性的坑。
 */
@Volatile
private var liveService: PlaybackService? = null

/**
 * 常驻媒体服务：继承 [MediaLibraryService]（兼容 Media3 MediaSessionService 与传统
 * `android.media.browse.MediaBrowserService`），统一服务控制中心、锁屏控制、
 * vivo 原子随身听 / 原子岛、小米超级岛与车载媒体协议。
 */
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var transitions: TransitionEffects? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var pendingBtRecovery: Runnable? = null
    private val persistSnapshotRunnable = Runnable {
        mediaSession?.player?.let { persistServicePlaybackSnapshot(it) }
    }

    @Volatile
    private var shuttingDownForSleep = false

    private var sleepAfterCurrentWatch: Runnable? = null

    @Volatile
    private var isScreenInteractive = true

    private var screenStateReceiver: BroadcastReceiver? = null
    private val coldRestoreInFlight = AtomicBoolean(false)
    private val pendingColdCommand = AtomicReference<String?>(null)

    private var sessionLyricsGeneration = 0
    private var lastPublishedLyricsSig: String? = null
    private var lastPublishedLyricsTrackKey: String? = null
    private var lastDesktopCoverSyncKey: String? = null

    private var shakeController: ShakeTrackController? = null
    private val headsetButtonCounter = HeadsetButtonClickCounter(380L)
    private val headsetButtonFlushRunnable = Runnable {
        when (headsetButtonCounter.consumePending()) {
            HeadsetButtonClickCounter.Action.PLAY_PAUSE ->
                executeWithTransitionReset(PlaybackCommands.PLAY_PAUSE)
            HeadsetButtonClickCounter.Action.NEXT ->
                executeWithTransitionReset(PlaybackCommands.NEXT)
            HeadsetButtonClickCounter.Action.PREVIOUS ->
                executeWithTransitionReset(PlaybackCommands.PREVIOUS)
            null -> Unit
        }
    }

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

        val defaultExtractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(DsdExtractor()) + defaultExtractors.createExtractors()
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ !Prefs(this).allowMixWithOthers
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setRenderersFactory(TapRenderersFactory(this, audioSink))
            .setMediaSourceFactory(DefaultMediaSourceFactory(onlineDataSource(), extractorsFactory))
            .build().apply {
                setSeekParameters(SeekParameters(100_000L, 100_000L))
            }

        syncDspAndPlayerModesIfCold(player)

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

        mediaSession = MediaLibrarySession.Builder(this, player, buildLibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(widgetSync)
        liveService = this
        shakeController = ShakeTrackController(this) {
            mainHandler.post { executeWithTransitionReset(PlaybackCommands.NEXT) }
        }
        watchBluetoothRoute(player)
        registerScreenStateObserver()
        syncShakeListener()

        scheduleNextPlaybackTick(player)

        if (Prefs(this).desktopLyricsEnabled && DesktopLyricsManager.hasOverlayPermission(this)) {
            DesktopLyricsManager.show(this)
        }

        restoreSleepTimerFromPrefs()
    }

    /** 进程重建后从落盘状态恢复睡眠定时：倒计时已过期立即收尾，否则重挂闹钟 / 轮询。 */
    private fun restoreSleepTimerFromPrefs() {
        val prefs = Prefs(this)
        val deadline = prefs.sleepTimerDeadlineElapsed
        val anchor = prefs.sleepTimerAfterCurrentAnchor
        when {
            deadline > 0L -> {
                if (deadline <= SystemClock.elapsedRealtime()) {
                    prefs.sleepTimerDeadlineElapsed = 0L
                    sleepShutdownAndStop()
                } else {
                    armSleepCountdown(this, deadline)
                }
            }
            anchor >= 0L -> startSleepAfterCurrentWatch(anchor)
        }
    }

    /** 「播完当前曲」的服务端轮询：离结尾 700ms 或曲目已切换就收尾，界面死了也照停。 */
    internal fun startSleepAfterCurrentWatch(anchorSongId: Long) {
        cancelSleepAfterCurrentWatch()
        val watch = object : Runnable {
            override fun run() {
                val player = playerOrNull()
                val curId = player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                val duration = player?.duration ?: 0L
                val position = player?.currentPosition ?: 0L
                val ended = player != null && (
                    (curId != -1L && curId != anchorSongId) ||
                        (duration > 0L && position >= duration - 700L)
                    )
                if (ended) {
                    Prefs(this@PlaybackService).sleepTimerAfterCurrentAnchor = -1L
                    sleepShutdownAndStop()
                } else {
                    mainHandler.postDelayed(this, 400L)
                }
            }
        }
        sleepAfterCurrentWatch = watch
        mainHandler.postDelayed(watch, 400L)
    }

    internal fun cancelSleepAfterCurrentWatch() {
        sleepAfterCurrentWatch?.let { mainHandler.removeCallbacks(it) }
        sleepAfterCurrentWatch = null
    }

    private fun buildLibraryCallback(): MediaLibrarySession.Callback =
        object : MediaLibrarySession.Callback {
            @androidx.annotation.OptIn(UnstableApi::class)
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                mediaButtonIntent: Intent
            ): Boolean {
                val keyEvent = IntentCompat.getParcelableExtra(
                    mediaButtonIntent,
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent::class.java
                ) ?: return super.onMediaButtonEvent(session, controllerInfo, mediaButtonIntent)

                val isSingleHook = keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                if (!isSingleHook) {
                    return super.onMediaButtonEvent(session, controllerInfo, mediaButtonIntent)
                }

                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    return true
                }
                if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) {
                    return true
                }

                val immediate = headsetButtonCounter.onClick(SystemClock.elapsedRealtime())
                mainHandler.removeCallbacks(headsetButtonFlushRunnable)
                if (immediate == HeadsetButtonClickCounter.Action.PREVIOUS) {
                    executeWithTransitionReset(PlaybackCommands.PREVIOUS)
                } else {
                    mainHandler.postDelayed(headsetButtonFlushRunnable, 380L)
                }
                return true
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    )
                    .setAvailablePlayerCommands(
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    )
                    .build()

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId(LIBRARY_ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("山水有清音")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != LIBRARY_ROOT_ID) {
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
                val player = session.player
                val count = player.mediaItemCount
                if (count <= 0) {
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
                val safePage = page.coerceAtLeast(0)
                val safeSize = pageSize.coerceIn(1, 200)
                val from = (safePage * safeSize).coerceAtMost(count)
                val to = ((safePage + 1) * safeSize).coerceAtMost(count)
                val builder = ImmutableList.builder<MediaItem>()
                for (i in from until to) {
                    builder.add(player.getMediaItemAt(i))
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(builder.build(), params))
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val player = mediaSession.player
                val count = player.mediaItemCount
                if (count > 0) {
                    val items = (0 until count).map { player.getMediaItemAt(it) }
                    val idx = player.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(items, idx, pos)
                    )
                }
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                lyricScope.launch {
                    val restored = runCatching { loadSavedQueueFromDisk() }.getOrNull()
                    if (restored == null || restored.songs.isEmpty()) {
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        return@launch
                    }
                    val preparedSongs = resolveTargetTrackIfNeeded(restored.songs, restored.index)
                    val items = with(PlayerController) {
                        preparedSongs.map { it.toMediaItem(applicationContext) }
                    }
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            restored.index.coerceIn(0, items.lastIndex),
                            restored.positionMs.coerceAtLeast(0L)
                        )
                    )
                }
                return future
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

        val scheduleRebuild = { autoPlayOnReady: Boolean ->
            pendingBtRecovery?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingBtRecovery = null
                if (liveService != this) return@Runnable
                player.setPreferredAudioDevice(null)
                transitions?.ensureActiveVolume()
                if (OutputAudioRouter.detectCurrentRoute(this) == AudioOutputRoute.BLUETOOTH) {
                    if (autoPlayOnReady &&
                        Prefs(this).bluetoothAutoPlay &&
                        player.mediaItemCount > 0 &&
                        !player.playWhenReady
                    ) {
                        if (player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        } else if (player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0L)
                        }
                        player.play()
                    } else if (player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.isCurrentMediaItemSeekable &&
                        transitions?.isSwitchFadingNow() != true
                    ) {
                        player.seekTo(player.currentPosition.coerceAtLeast(0L))
                    }
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
                    scheduleRebuild(Prefs(this@PlaybackService).bluetoothAutoPlay)
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
                val leftAllBt = hadBt && newRoute != AudioOutputRoute.BLUETOOTH && newBtIds.isEmpty()
                lastRoute = newRoute
                lastBtIds = newBtIds
                player.setPreferredAudioDevice(null)
                transitions?.ensureActiveVolume()
                if (leftAllBt) {
                    pendingBtRecovery?.let { mainHandler.removeCallbacks(it); pendingBtRecovery = null }
                    if (Prefs(this@PlaybackService).bluetoothAutoPlay && (player.isPlaying || player.playWhenReady)) {
                        player.pause()
                    }
                } else if (hadBt && newRoute == AudioOutputRoute.BLUETOOTH && newBtIds.isNotEmpty()) {
                    scheduleRebuild(false)
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

        /** @return 命令是否真的送到了非空队列的播放器；服务未启动或队列为空返回 false 以便拉起恢复 */
        fun executeWidgetCommand(command: String): Boolean =
            liveService?.executeWithTransitionReset(command) ?: false

        fun hasRestorableSession(context: Context): Boolean {
            val app = context.applicationContext
            if (WidgetState.read(app).hasSong) return true
            val p = Prefs(app)
            if (p.lastPlayedOnline && (!p.lastOnlineSongJson.isNullOrBlank() || !p.onlineQueueJson.isNullOrBlank())) {
                return true
            }
            return p.lastSongId > 0L || p.playQueueIds.isNotBlank() || !p.songsCacheJson.isNullOrBlank()
        }

        internal fun wakePlaybackTicker() {
            val svc = liveService ?: return
            svc.runOnMain {
                val p = svc.mediaSession?.player ?: return@runOnMain
                svc.tickLive()
                svc.scheduleNextPlaybackTick(p)
            }
        }

        internal fun syncTransitionConfig() {
            val svc = liveService ?: return
            svc.runOnMain { svc.transitions?.onConfigChanged() }
        }

        internal fun refreshExternalAndShakeState() {
            val svc = liveService ?: return
            svc.runOnMain {
                svc.syncShakeListener()
                svc.tickLive()
                svc.mediaSession?.player?.let { svc.scheduleNextPlaybackTick(it) }
            }
        }

        internal fun switchTrackWithFade(index: Int, fadeMs: Long): Boolean =
            liveService?.switchTrackWithFade(index, fadeMs) ?: false

        internal fun cancelSwitchFade(): Boolean =
            liveService?.cancelSwitchFade() ?: false

        internal fun syncUserVolume(volume: Float): Boolean =
            liveService?.syncUserVolume(volume) ?: false

        internal fun playerOrNull(): Player? = liveService?.playerOrNull()

        internal fun currentAudioSessionId(): Int {
            val exo = liveService?.playerOrNull() as? ExoPlayer
            return exo?.audioSessionId?.takeIf { it > 0 } ?: SystemAudioEffects.latestPlayerSessionId
        }

        internal fun syncAudioFocusPolicy(allowMixWithOthers: Boolean) {
            liveService?.syncAudioFocusPolicy(allowMixWithOthers)
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

        /** 车载/锁屏/原子岛歌词开关变化后立即重估：关闭时马上还原歌名、开启时立即启动定时推送。 */
        internal fun syncLyricOutputs() {
            val svc = liveService ?: return
            svc.runOnMain {
                svc.tickLive()
                svc.mediaSession?.player?.let { svc.scheduleNextPlaybackTick(it) }
            }
        }

        internal fun formatSessionLyricArtist(origTitle: String, origArtist: String): String {
            val a = origArtist.trim()
            val t = origTitle.trim()
            return when {
                a.isNotEmpty() && t.isNotEmpty() -> "$a - $t"
                a.isNotEmpty() -> a
                else -> t
            }
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
                val curTitle = curMeta?.extras?.getString(PlayerController.EXTRA_ORIG_TITLE)
                    ?: curMeta?.title?.toString().orEmpty()
                val curArtist = curMeta?.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
                    ?: curMeta?.artist?.toString().orEmpty()
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
                svc.publishSessionLyricsOnMain(resolvedId, resolvedTitle, resolvedArtist, cleaned)
                svc.tickLive()
                svc.enqueueWidgetLivePush()
            }
        }

        private const val LIBRARY_ROOT_ID = "sonance_root"

        /** 定时关闭触发：暂停并停播以释放音频焦点、关闭桌面悬浮词、移除前台通知并退出服务。 */
        internal fun stopAndReleaseForSleep() {
            liveService?.sleepShutdownAndStop()
        }

        private const val SLEEP_ALARM_REQUEST = 7021

        private fun sleepAlarmIntent(context: Context): PendingIntent {
            val intent = Intent(context, SleepTimerReceiver::class.java)
                .setAction("sonance.sleep.FIRE")
            return PendingIntent.getBroadcast(
                context,
                SLEEP_ALARM_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * 倒计时睡眠：截止时间落盘 + 系统闹钟兜底。
         *
         * 只靠 ViewModel 协程计时的话，用户把界面划掉（任务移除、VM 销毁）后
         * 前台服务还在播，倒计时却跟着死了 —— 这就是「定时到点不关」的根因。
         * 闹钟由系统持有，进程死了也能准时把服务停掉。
         */
        internal fun armSleepCountdown(context: Context, deadlineElapsedMs: Long) {
            val app = context.applicationContext
            val prefs = Prefs(app)
            prefs.sleepTimerDeadlineElapsed = deadlineElapsedMs
            prefs.sleepTimerAfterCurrentAnchor = -1L
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = sleepAlarmIntent(app)
            am.cancel(pi)
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedMs, pi)
            }
            liveService?.runOnMain { liveService?.cancelSleepAfterCurrentWatch() }
        }

        /** 「播完当前曲」：锚点落盘 + 服务端轮询，不再依赖界面协程活着。 */
        internal fun armSleepAfterCurrent(context: Context, anchorSongId: Long) {
            val app = context.applicationContext
            val prefs = Prefs(app)
            prefs.sleepTimerAfterCurrentAnchor = anchorSongId
            prefs.sleepTimerDeadlineElapsed = 0L
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(sleepAlarmIntent(app))
            liveService?.runOnMain { liveService?.startSleepAfterCurrentWatch(anchorSongId) }
        }

        internal fun disarmSleepTimer(context: Context) {
            val app = context.applicationContext
            val prefs = Prefs(app)
            prefs.sleepTimerDeadlineElapsed = 0L
            prefs.sleepTimerAfterCurrentAnchor = -1L
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(sleepAlarmIntent(app))
            liveService?.runOnMain { liveService?.cancelSleepAfterCurrentWatch() }
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

    @Volatile
    private var lastQueuedWidgetMediaId: Long = Long.MIN_VALUE

    private val flushWidgetSyncRunnable = Runnable {
        widgetExecutor.execute { drainWidgetSync() }
    }

    private fun needsOffscreenLyricTick(): Boolean {
        val prefs = Prefs(applicationContext)
        return prefs.carBluetoothLyrics ||
            (prefs.lockScreenLyrics && XiaomiLyricIsland.isSupported(applicationContext))
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            if (player.isPlaying && (isScreenInteractive || needsOffscreenLyricTick())) {
                tickLive()
                scheduleNextPlaybackTick(player)
            }
        }
    }

    /** 仅在亮屏且存在消费者，或熄屏下存在蓝牙/锁屏/原子岛等息屏歌词消费者时调度，零唤醒浪费。 */
    private fun scheduleNextPlaybackTick(player: Player) {
        mainHandler.removeCallbacks(playbackTicker)
        if (!player.isPlaying) return
        val prefs = Prefs(applicationContext)
        val hasSessionTitleLyrics = prefs.carBluetoothLyrics
        val hasXiaomiIsland = prefs.lockScreenLyrics && XiaomiLyricIsland.isSupported(applicationContext)
        if (!isScreenInteractive && !hasSessionTitleLyrics && !hasXiaomiIsland) return
        val hasDesktopLyrics = isScreenInteractive && DesktopLyricsManager.isShowing()
        val hasExternalLyrics = isScreenInteractive && prefs.externalLyricsEnabled
        val hasWidgets = isScreenInteractive && SonanceWidgets.hasAnyWidgets(applicationContext)
        if (!hasDesktopLyrics && !hasExternalLyrics && !hasWidgets && !hasSessionTitleLyrics && !hasXiaomiIsland) return
        val delayMs = when {
            hasDesktopLyrics || hasExternalLyrics -> 250L
            hasSessionTitleLyrics || hasXiaomiIsland -> 300L
            else -> {
                val posMs = player.currentPosition.coerceAtLeast(0L)
                (1_000L - (posMs % 1_000L)).coerceIn(250L, 1_000L)
            }
        }
        mainHandler.postDelayed(playbackTicker, delayMs)
    }

    private fun syncShakeListener() {
        val controller = shakeController ?: return
        val prefs = Prefs(applicationContext)
        val playing = mediaSession?.player?.isPlaying == true
        controller.updateState(
            enabled = prefs.shakeNextEnabled,
            isPlaying = playing,
            screenInteractive = isScreenInteractive,
            screenOffAllowed = prefs.shakeScreenOffEnabled,
            pocketGuard = prefs.shakePocketGuardEnabled,
            sensitivity = prefs.shakeSensitivity
        )
    }

    private fun registerScreenStateObserver() {
        isScreenInteractive = getSystemService(PowerManager::class.java)?.isInteractive != false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenInteractive = false
                        val p = mediaSession?.player
                        if (p != null && p.isPlaying && needsOffscreenLyricTick()) {
                            scheduleNextPlaybackTick(p)
                        } else {
                            mainHandler.removeCallbacks(playbackTicker)
                        }
                        syncShakeListener()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenInteractive = true
                        syncShakeListener()
                        val p = mediaSession?.player ?: return
                        if (SonanceWidgets.hasAnyWidgets(applicationContext)) {
                            widgetExecutor.execute {
                                runCatching { SonanceWidgets.refreshAll(applicationContext) }
                            }
                        }
                        tickLive()
                        scheduleNextPlaybackTick(p)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            screenStateReceiver = receiver
        }
    }

    private fun queryFromPlayer(player: Player): TrackLyricQuery {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId?.toLongOrNull() ?: -1L
        val meta = item?.mediaMetadata
        val title = meta?.extras?.getString(PlayerController.EXTRA_ORIG_TITLE)
            ?: meta?.title?.toString().orEmpty()
        val artist = meta?.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
            ?: meta?.artist?.toString().orEmpty()
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

        // 车载蓝牙歌词：息屏也要继续刷新（车机显示不依赖手机屏幕状态）
        maybeUpdateCarLyricTitle(player, triplet)

        // 小米超级岛歌词：岛 / AOD 同样不依赖亮屏
        XiaomiLyricIsland.onTick(applicationContext, query.title, query.artist, triplet)

        if (!isScreenInteractive) return

        DesktopLyricsManager.onTick(
            context = applicationContext,
            isPlaying = player.isPlaying,
            title = query.title,
            artist = query.artist,
            lyricTriplet = triplet
        )
        ExternalLyricBridge.onTick(
            context = applicationContext,
            enabled = Prefs(applicationContext).externalLyricsEnabled,
            isPlaying = player.isPlaying,
            lyricText = triplet.cur
        )

        if (!SonanceWidgets.hasAnyWidgets(applicationContext)) return

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
            val title = meta?.extras?.getString(PlayerController.EXTRA_ORIG_TITLE)
                ?: meta?.title?.toString().orEmpty()
            val artist = meta?.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
                ?: meta?.artist?.toString().orEmpty()
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
            val mediaChanged = mediaId != lastQueuedWidgetMediaId
            lastQueuedWidgetMediaId = mediaId
            pendingWidgetSync.set(snapshot)
            mainHandler.removeCallbacks(flushWidgetSyncRunnable)
            mainHandler.postDelayed(flushWidgetSyncRunnable, if (mediaChanged) 750L else 120L)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                shuttingDownForSleep = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mainHandler.removeCallbacks(playbackTicker)
            syncShakeListener()
            val p = mediaSession?.player ?: return
            if (isPlaying) {
                tickLive()
                scheduleNextPlaybackTick(p)
            } else {
                tickLive()
                persistServicePlaybackSnapshot(p)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = mediaSession?.player ?: return
            mainHandler.removeCallbacks(persistSnapshotRunnable)
            mainHandler.postDelayed(persistSnapshotRunnable, 350L)
            resolveCurrentOnlineStreamInServiceIfNeeded(p, mediaItem)
            if (p.isPlaying) {
                scheduleNextPlaybackTick(p)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                mediaSession?.player?.let { persistServicePlaybackSnapshot(it) }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            tickLive()
            val p = mediaSession?.player
            if (p != null && p.isPlaying) {
                scheduleNextPlaybackTick(p)
            }
        }
    }

    private fun drainWidgetSync() {
        while (true) {
            val s = pendingWidgetSync.getAndSet(null) ?: break
            runCatching {
                val interactive = isScreenInteractive
                SonanceWidgets.onPlaybackChanged(
                    context = applicationContext,
                    mediaId = s.mediaId,
                    title = s.title,
                    artist = s.artist,
                    isPlaying = s.isPlaying,
                    albumId = s.albumId,
                    sourceUri = s.sourceUri,
                    coverKey = s.coverKey,
                    refreshUi = interactive
                )

                val desktopKey = "${s.mediaId}|${s.coverKey.orEmpty()}"
                if (Prefs(applicationContext).desktopLyricsEnabled &&
                    Prefs(applicationContext).desktopLyricsTextColor == DesktopLyricsManager.COLOR_FOLLOW_COVER &&
                    desktopKey != lastDesktopCoverSyncKey
                ) {
                    lastDesktopCoverSyncKey = desktopKey
                    lyricScope.launch {
                        val parsedUri = s.sourceUri?.let(Uri::parse)
                        val img = ArtworkLoader.load(
                            context = applicationContext,
                            albumId = s.albumId,
                            sizePx = 128,
                            sourceUri = parsedUri,
                            coverKey = s.coverKey
                        ) ?: ArtworkLoader.online(s.mediaId)
                        if (img != null) {
                            val argb = DesktopLyricsManager.extractVibrantAccent(img.asAndroidBitmap())
                            if (argb != 0) {
                                DesktopLyricsManager.updateCoverColor(applicationContext, argb)
                                return@launch
                            }
                        }
                        ArtworkLoader.tintOf(s.albumId, parsedUri, s.coverKey)?.center?.let { c ->
                            if (c != 0) DesktopLyricsManager.updateCoverColor(applicationContext, c)
                        }
                    }
                }

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

                if (!isScreenInteractive) return@runCatching

                val offsetMs = Prefs(applicationContext).lyricsOffsetMs(s.title, s.artist)
                val effectivePosMs = (s.positionMs + offsetMs).coerceAtLeast(0L)
                val triplet = lyricTripletAt(effectivePosMs)

                if (SonanceWidgets.hasAnyWidgets(applicationContext)) {
                    SonanceWidgets.onTick(
                        applicationContext,
                        s.positionMs,
                        s.durationMs,
                        triplet
                    )
                }
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
                        publishSessionLyricsOnMain(query.mediaId, query.title, query.artist, local.lines)
                        tickLive()
                        enqueueWidgetLivePush()
                    }
                }
            }
            LocalLyricsLookup.NeedOnline -> {
                val prefs = Prefs(applicationContext)
                val shouldFetchOnline = query.title.isNotBlank() &&
                    prefs.onlineLyricsEnabled &&
                    (prefs.lockScreenLyrics ||
                        prefs.carBluetoothLyrics ||
                        DesktopLyricsManager.isShowing() || prefs.desktopLyricsEnabled || SonanceWidgets.hasAnyWidgets(applicationContext))

                if (applyLoadedLyricsIfStillCurrent(query, emptyList())) {
                    runOnMain {
                        publishSessionLyricsOnMain(query.mediaId, query.title, query.artist, emptyList())
                    }
                }
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
                    publishSessionLyricsOnMain(query.mediaId, query.title, query.artist, lines)
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

    /* ===== 蓝牙车载歌词 =====
     * 蓝牙 AVRCP 只传 歌名/歌手/专辑，车机没有歌词通道。QQ音乐等的「车载蓝牙歌词」
     * 做法是把会话标题持续改写成当前歌词行，车机把它当歌名显示。
     * 原歌名存在 EXTRA_ORIG_TITLE，内部所有标题读取都优先取它，
     * 因此歌词匹配、小组件、应用内显示、锁屏歌词协议都不受影响。 */

    private var carLyricMediaId: String? = null
    private var lastCarLyricLine: String? = null

    private fun maybeUpdateCarLyricTitle(
        player: Player,
        triplet: WidgetState.LyricTriplet
    ) {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId
        if (carLyricMediaId != null && carLyricMediaId != mediaId) {
            restoreCarLyricTitle(player, carLyricMediaId!!)
            carLyricMediaId = null
            lastCarLyricLine = null
        }
        val active = Prefs(applicationContext).carBluetoothLyrics && player.isPlaying
        if (!active || item == null) {
            if (carLyricMediaId != null) {
                restoreCarLyricTitle(player, carLyricMediaId!!)
                carLyricMediaId = null
                lastCarLyricLine = null
            }
            return
        }
        val line = triplet.cur.trim()
        if (line.isEmpty()) return
        val meta = item.mediaMetadata
        val origTitle = meta.extras?.getString(PlayerController.EXTRA_ORIG_TITLE)
            ?: meta.title?.toString().orEmpty()
        val origArtist = meta.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
            ?: meta.artist?.toString().orEmpty()
        if (origTitle.isBlank()) return
        if (carLyricMediaId == mediaId && line == lastCarLyricLine &&
            meta.title?.toString() == line && meta.displayTitle?.toString() == line
        ) return
        val artistLine = formatSessionLyricArtist(origTitle, origArtist)
        val extras = Bundle(meta.extras ?: Bundle()).apply {
            putString(PlayerController.EXTRA_ORIG_TITLE, origTitle)
            putString(PlayerController.EXTRA_ORIG_ARTIST, origArtist)
        }
        val updated = item.buildUpon()
            .setMediaMetadata(
                meta.buildUpon()
                    .setTitle(line)
                    .setDisplayTitle(line)
                    .setArtist(artistLine)
                    .setSubtitle(artistLine)
                    .setExtras(extras)
                    .build()
            )
            .build()
        runCatching {
            PlayerController.replaceQueueItemSafely(
                player = player,
                index = player.currentMediaItemIndex,
                updated = updated,
                isMediaController = false
            )
        }
        carLyricMediaId = mediaId
        lastCarLyricLine = line
    }

    private fun restoreCarLyricTitle(player: Player, mediaId: String) {
        val count = runCatching { player.mediaItemCount }.getOrDefault(0)
        for (i in 0 until count) {
            val item = runCatching { player.getMediaItemAt(i) }.getOrNull() ?: continue
            if (item.mediaId != mediaId) continue
            val origTitle = item.mediaMetadata.extras?.getString(PlayerController.EXTRA_ORIG_TITLE) ?: return
            val origArtist = item.mediaMetadata.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
                ?: item.mediaMetadata.artist?.toString().orEmpty()
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                remove(PlayerController.EXTRA_ORIG_TITLE)
                remove(PlayerController.EXTRA_ORIG_ARTIST)
            }
            val updated = item.buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setTitle(origTitle)
                        .setDisplayTitle(null)
                        .setArtist(origArtist)
                        .setSubtitle(null)
                        .setExtras(extras)
                        .build()
                )
                .build()
            runCatching {
                PlayerController.replaceQueueItemSafely(
                    player = player,
                    index = i,
                    updated = updated,
                    isMediaController = false
                )
            }
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_COMMAND) {
            shuttingDownForSleep = false
            val command = intent.getStringExtra(WidgetCommandReceiver.EXTRA_COMMAND)
            if (command != null) {
                val player = playerOrNull()
                if (player != null && player.mediaItemCount > 0) {
                    executeWithTransitionReset(command)
                } else {
                    ensureColdStartForeground()
                    restoreAndExecuteWidgetCommand(command)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private data class RestoredPlaybackQueue(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long
    )

    private fun syncDspAndPlayerModesIfCold(player: ExoPlayer) {
        val prefs = Prefs(this)
        if (EqBus.revision == 0L && SoundFxBus.revision == 0L) {
            val intensity = PresetIntensity.fromId(prefs.soundPresetIntensity)
            val eqPreset = EqPreset.fromName(prefs.eqPreset)
            val custom = parseEqBandsCsv(prefs.eqCustomBands)
            val soundEq = SoundPresets.eqPresetFromStore(prefs.eqPreset)
            val bands = custom ?: soundEq?.scaled(intensity)?.first ?: EqPreset.curveOf(eqPreset)
            EqBus.update(prefs.eqEnabled, bands)

            val sp = SoundPresets.fromId(prefs.soundPresetId)
            val fxRestored = SoundPresets.restoreFx(sp, prefs.soundFxParams, intensity)
            SoundFxBus.update(prefs.soundFxEnabled, fxRestored.params)

            KaraokeBus.enabled = prefs.karaokeEnabled
        }
        player.shuffleModeEnabled = false
        val mode = PlayMode.entries.firstOrNull { it.name == prefs.playMode } ?: PlayMode.REPEAT_ALL
        player.repeatMode = when (mode) {
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            PlayMode.SHUFFLE, PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        }
        val speed = prefs.playSpeed
        if (speed != 1f) {
            player.playbackParameters = player.playbackParameters.withSpeed(speed)
        }
    }

    private fun ensureColdStartForeground() {
        val snap = WidgetState.read(this)
        val notification = NotificationCompat.Builder(this, PlaybackNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(snap.title.ifBlank { getString(R.string.app_name) })
            .setContentText(snap.artist.ifBlank { "正在恢复播放…" })
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                notification,
                fgsType
            )
        }
    }

    private fun loadSavedQueueFromDisk(): RestoredPlaybackQueue? {
        val app = applicationContext
        val prefs = Prefs(app)
        if (prefs.lastPlayedOnline) {
            val queue = storedOnlineSongsFromJson(prefs.onlineQueueJson).mapNotNull { it.toSong() }
            val last = prefs.lastOnlineSongJson
                ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                ?.let { storedOnlineSongFromJson(it) }
                ?.toSong()
            val list = queue.ifEmpty { listOfNotNull(last) }
            if (list.isNotEmpty()) {
                val lxRepo = com.sonance.player.data.lx.LxRepository.get(app)
                list.forEach { lxRepo.rememberSongHint(it) }
                val idx = prefs.onlineQueueIndex.coerceIn(0, list.lastIndex)
                return RestoredPlaybackQueue(list, idx, prefs.playPositionMs.coerceAtLeast(0L))
            }
        }
        val cachedJson = prefs.songsCacheJson
        if (cachedJson.isNullOrBlank()) return null
        val all = runCatching { decodeSongsFromJson(cachedJson) }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val hidden = prefs.hiddenIdSet()
        val dirsEmpty = prefs.excludedDirs.isEmpty()
        val visible = if (hidden.isEmpty() && dirsEmpty) {
            all
        } else {
            all.filter { it.id !in hidden && !prefs.isFolderExcluded(it.folderPath) }
        }
        if (visible.isEmpty()) return null
        val map = visible.associateBy { it.id }
        val resolved = PlaybackRestore.resolveIds(
            savedIds = PlaybackRestore.parseIdList(prefs.playQueueIds),
            savedIndex = prefs.playQueueIndex,
            libraryIds = map.keys
        )
        val queue = resolved?.first?.mapNotNull(map::get)
            ?: map[prefs.lastSongId]?.let { listOf(it) }
            ?: listOf(visible.first())
        val idx = resolved?.second ?: 0
        val pos = if (resolved != null || prefs.lastSongId in map) {
            prefs.playPositionMs.coerceAtLeast(0L)
        } else {
            0L
        }
        return RestoredPlaybackQueue(queue, idx, pos)
    }

    private suspend fun resolveTargetTrackIfNeeded(
        songs: List<Song>,
        targetIndex: Int
    ): List<Song> {
        val target = songs.getOrNull(targetIndex) ?: return songs
        if (target.isLocal || target.isDlna) return songs
        val rid = target.remoteId ?: return songs
        if (!PlayerController.isUnresolvedStreamUri(target.uri.toString())) return songs
        val src = OnlineSources.byId(target.source) ?: return songs
        val url = if (src.hasFreshStream(rid)) {
            src.streamUrl(rid).takeIf { it.isNotBlank() }
        } else {
            runCatching { src.resolveStream(rid, target.durationMs) }.getOrNull()
        } ?: return songs
        val uri = Uri.parse(url)
        return songs.mapIndexed { idx, s -> if (idx == targetIndex) s.copy(uri = uri) else s }
    }

    private fun restoreAndExecuteWidgetCommand(command: String) {
        pendingColdCommand.set(command)
        if (!coldRestoreInFlight.compareAndSet(false, true)) return
        lyricScope.launch {
            var postedToMain = false
            try {
                val restored = runCatching { loadSavedQueueFromDisk() }.getOrNull()
                val effectiveCommand = pendingColdCommand.getAndSet(null) ?: command
                if (restored == null || restored.songs.isEmpty()) {
                    postedToMain = true
                    runOnMain {
                        coldRestoreInFlight.set(false)
                        if ((mediaSession?.player?.mediaItemCount ?: 0) == 0) {
                            ServiceCompat.stopForeground(
                                this@PlaybackService,
                                ServiceCompat.STOP_FOREGROUND_REMOVE
                            )
                            stopSelf()
                        }
                    }
                    return@launch
                }
                val (targetIdx, startPos) = PlaybackCommands.resolveColdStartTarget(
                    queueSize = restored.songs.size,
                    savedIndex = restored.index,
                    savedPosMs = restored.positionMs,
                    command = effectiveCommand
                )
                val preparedSongs = resolveTargetTrackIfNeeded(restored.songs, targetIdx)
                val items = with(PlayerController) {
                    preparedSongs.map { it.toMediaItem(applicationContext) }
                }
                postedToMain = true
                runOnMain {
                    coldRestoreInFlight.set(false)
                    val player = mediaSession?.player ?: return@runOnMain
                    if (player.mediaItemCount > 0) {
                        executeWithTransitionReset(effectiveCommand)
                    } else {
                        player.setMediaItems(items, targetIdx, startPos)
                        player.prepare()
                        player.play()
                    }
                }
            } finally {
                if (!postedToMain) coldRestoreInFlight.set(false)
            }
        }
    }

    private fun resolveCurrentOnlineStreamInServiceIfNeeded(player: Player, item: MediaItem?) {
        val current = item ?: return
        val curUri = current.localConfiguration?.uri?.toString()
        if (!PlayerController.isUnresolvedStreamUri(curUri)) return
        val extras = current.mediaMetadata.extras ?: return
        val source = extras.getString(PlayerController.EXTRA_SOURCE).orEmpty()
        val remoteId = extras.getString(PlayerController.EXTRA_REMOTE_ID).orEmpty()
        if (source.isBlank() || remoteId.isBlank()) return
        val durationMs = extras.getLong(PlayerController.EXTRA_DURATION_MS, 0L)
        val expectedMediaId = current.mediaId
        val src = OnlineSources.byId(source) ?: return

        lyricScope.launch {
            // 若 ViewModel 在前台活跃，稍等其优先解析并记录缓存，避免重复发起网络取流
            delay(120L)
            val resolvedUrl = if (src.hasFreshStream(remoteId)) {
                src.streamUrl(remoteId).takeIf { it.isNotBlank() }
            } else {
                runCatching { src.resolveStream(remoteId, durationMs) }.getOrNull()
            } ?: return@launch
            val parsedUri = Uri.parse(resolvedUrl)
            runOnMain {
                val p = mediaSession?.player ?: return@runOnMain
                val idx = p.currentMediaItemIndex
                if (idx !in 0 until p.mediaItemCount) return@runOnMain
                val liveItem = p.getMediaItemAt(idx)
                if (liveItem.mediaId != expectedMediaId) return@runOnMain
                val latestUri = liveItem.localConfiguration?.uri?.toString()
                if (!PlayerController.isUnresolvedStreamUri(latestUri)) return@runOnMain
                val newExtras = Bundle(liveItem.mediaMetadata.extras ?: Bundle()).apply {
                    putString(PlayerController.EXTRA_URI, parsedUri.toString())
                }
                val newMeta = liveItem.mediaMetadata.buildUpon().setExtras(newExtras).build()
                val updated = liveItem.buildUpon().setUri(parsedUri).setMediaMetadata(newMeta).build()
                runCatching {
                    PlayerController.replaceQueueItemSafely(
                        player = p,
                        index = idx,
                        updated = updated,
                        isMediaController = false
                    )
                }
            }
        }
    }

    private fun persistServicePlaybackSnapshot(player: Player) {
        if (player.mediaItemCount <= 0) return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId.toLongOrNull() ?: return
        if (mediaId == -1L || mediaId == 0L || isExternalSongId(mediaId)) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val idx = player.currentMediaItemIndex.coerceAtLeast(0)
        mainHandler.removeCallbacks(persistSnapshotRunnable)
        Prefs(applicationContext).saveServicePlaybackSnapshot(
            mediaId = mediaId,
            queueIndex = idx,
            positionMs = pos,
            isRemote = isRemoteSongId(mediaId)
        )
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (shuttingDownForSleep) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    /**
     * 将系统锁屏 / 原子随身听 / 车机歌词（ColorOS `lyricInfo`、vivo `ucar.media.metadata.LYRICS_WHOLE`、
     * 通用 `android.media.metadata.LYRIC`）直接同步到服务端当前 MediaItem。
     */
    private fun publishSessionLyricsOnMain(
        expectedSongId: Long,
        expectedTitle: String,
        expectedArtist: String,
        lines: List<LyricLine>
    ) {
        if (!Prefs(applicationContext).lockScreenLyrics) return
        val player = mediaSession?.player ?: return
        val idx = player.currentMediaItemIndex
        if (idx !in 0 until player.mediaItemCount) return
        val current = runCatching { player.getMediaItemAt(idx) }.getOrNull() ?: return
        val curId = current.mediaId.toLongOrNull() ?: -1L
        val curMeta = current.mediaMetadata
        val curTitle = curMeta.extras?.getString(PlayerController.EXTRA_ORIG_TITLE)
            ?: curMeta.title?.toString().orEmpty()
        val curArtist = curMeta.extras?.getString(PlayerController.EXTRA_ORIG_ARTIST)
            ?: curMeta.artist?.toString().orEmpty()
        val matches = if (curId != -1L && expectedSongId != 0L && expectedSongId != -1L) {
            curId == expectedSongId
        } else {
            curTitle.trim() == expectedTitle.trim() && curArtist.trim() == expectedArtist.trim()
        }
        if (!matches) return

        val cleaned = lines.filter { it.text.isNotBlank() }
        val lrcText = LockScreenLyrics.toLrcText(cleaned)
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: curMeta.durationMs
            ?: curMeta.extras?.getLong(PlayerController.EXTRA_DURATION_MS, 0L)
            ?: 0L
        val sig = "$curId|$curTitle|$curArtist|${lrcText.orEmpty()}"
        if (sig == lastPublishedLyricsSig) return
        lastPublishedLyricsSig = sig
        // 协议要求 generation 只在真实换曲时递增；同曲补全歌词沿用旧值，
        // 否则 SystemUI 可能把补全当成新歌、丢弃已展示的歌词状态。
        val trackKey = "$curId|$curTitle|$curArtist"
        val gen = if (trackKey == lastPublishedLyricsTrackKey && sessionLyricsGeneration > 0) {
            sessionLyricsGeneration
        } else {
            lastPublishedLyricsTrackKey = trackKey
            ++sessionLyricsGeneration
        }

        val json = LockScreenLyrics.buildJsonFromLrc(
            songId = curId,
            title = curTitle.ifBlank { expectedTitle },
            artist = curArtist.ifBlank { expectedArtist },
            album = curMeta.albumTitle?.toString().orEmpty(),
            durationMs = durationMs,
            lrc = lrcText,
            generation = gen
        )
        val extras = Bundle(curMeta.extras ?: Bundle()).apply {
            putString(LockScreenLyrics.EXTRA_KEY, json)
            if (!lrcText.isNullOrBlank()) {
                putString(LockScreenLyrics.EXTRA_KEY_VIVO_WHOLE, lrcText)
                putString(LockScreenLyrics.EXTRA_KEY_GENERIC_LYRIC, lrcText)
            } else {
                remove(LockScreenLyrics.EXTRA_KEY_VIVO_WHOLE)
                remove(LockScreenLyrics.EXTRA_KEY_GENERIC_LYRIC)
            }
        }
        val flip = if (curMeta.compilation?.toString() == "1") "0" else "1"
        val updatedMeta = curMeta.buildUpon()
            .setExtras(extras)
            .setCompilation(flip)
            .build()
        val updatedItem = current.buildUpon().setMediaMetadata(updatedMeta).build()
        runCatching {
            PlayerController.replaceQueueItemSafely(
                player = player,
                index = idx,
                updated = updatedItem,
                isMediaController = false
            )
        }
    }

    /** 定时结束收尾：暂停并停止播放器释放音频焦点、隐藏桌面歌词、移除前台媒体通知并停止服务。 */
    internal fun sleepShutdownAndStop() {
        runOnMain {
            shuttingDownForSleep = true
            cancelSleepAfterCurrentWatch()
            pendingOnlineLyricJob?.cancel()
            pendingOnlineLyricJob = null
            transitions?.cancelSwitchFade(restoreVolume = true)
            DesktopLyricsManager.hide(this)
            XiaomiLyricIsland.cancel(this)
            mediaSession?.player?.let { player ->
                player.playWhenReady = false
                player.pause()
                player.stop()
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            }
            stopSelf()
        }
    }

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
        if (Looper.myLooper() == Looper.getMainLooper() && player.mediaItemCount == 0) {
            return false
        }
        runOnMain {
            if (player.mediaItemCount > 0) {
                transitions?.cancelSwitchFade(restoreVolume = true)
                PlaybackCommands.execute(player, command)
            }
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

    internal fun syncAudioFocusPolicy(allowMixWithOthers: Boolean) {
        runOnMain {
            val exo = mediaSession?.player as? ExoPlayer ?: return@runOnMain
            val attrs = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            exo.setAudioAttributes(attrs, /* handleAudioFocus = */ !allowMixWithOthers)
        }
    }

    internal fun syncUserVolume(volume: Float): Boolean {
        val fx = transitions ?: return false
        runOnMain { fx.setUserVolume(volume) }
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            persistServicePlaybackSnapshot(player)
        }
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        pendingOnlineLyricJob?.cancel()
        pendingOnlineLyricJob = null
        lyricScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        shakeController?.release()
        shakeController = null
        ExternalLyricBridge.clear(this)
        DesktopLyricsManager.hide(this)
        XiaomiLyricIsland.cancel(this)
        screenStateReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        screenStateReceiver = null
        pendingBtRecovery?.let { mainHandler.removeCallbacks(it) }
        pendingBtRecovery = null
        audioDeviceCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        sleepAfterCurrentWatch = null
        SystemAudioEffects.releaseSession(this)
        mediaSession?.run {
            persistServicePlaybackSnapshot(player)
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

    init {
        // 优先系统硬解，遇到系统不支持的无损/多声道格式（ALAC/FLAC/TrueHD/MLP/DTS 等）自动回落内置 FFmpeg 软解
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = audioSink
}
