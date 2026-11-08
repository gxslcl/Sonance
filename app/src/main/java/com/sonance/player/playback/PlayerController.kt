package com.sonance.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverCache
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.LockScreenLyrics
import com.sonance.player.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ALL, ONE }

/**
 * 播放模式：**三态互斥**，用一个按钮循环切换。
 *
 * ## 为什么不直接用 `shuffle: Boolean` + [RepeatMode]
 *
 * 那两个是独立开关，能组合出 6 种状态（包括"随机 + 单曲循环"这种自相矛盾的：
 * 队列已经打乱了，"单曲"到底单曲哪一首？）。而用户心智里只有三种：
 * 随机放 / 顺序循环放 / 单曲反复放。合并成一个按钮之后语义明确，
 * 也不会出现"两个都亮着，不知道哪个生效"。
 */
enum class PlayMode(val label: String) {
    SHUFFLE("随机播放"),
    REPEAT_ALL("列表循环"),
    REPEAT_ONE("单曲循环");

    /** 下一个模式。按钮点一下就取它 */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 从底层两个标志推出当前模式。
         *
         * **`shuffle` 优先**：它开着的时候 repeat 是 ALL 还是 OFF 对用户没区别
         * （队列已经被打乱了），都算"随机播放"。
         *
         * ⚠️ `shuffle=false + repeat=OFF`（App 的初始状态）**归到列表循环** ——
         * 三态里没有"放完就停"这一档，把它单列出来就变成四态了。
         * 代价是首次点击之前，按钮显示"列表循环"而实际放完会停；
         * 点一下（进入下一态再回来）就真的变成列表循环。
         */
        fun of(shuffle: Boolean, repeat: RepeatMode): PlayMode = when {
            shuffle -> SHUFFLE
            repeat == RepeatMode.ONE -> REPEAT_ONE
            else -> REPEAT_ALL
        }
    }
}

/**
 * UI 侧播放状态快照。
 *
 * 标 [Immutable]：`queueIds` 是 `List`（接口类型，Compose 默认推断为 unstable），
 * 不标的话整个类会被判成 unstable，所有读它的界面都失去跳过能力 ——
 * 而它每 500ms 更新一次播放进度，代价会被放大到全屏。
 */
@Immutable
data class PlayerUiState(
    val connected: Boolean = false,
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Playback intent survives seek/buffering; isPlaying still means audio is actually advancing. */
    val playWhenReady: Boolean = false,
    val mediaId: Long = -1L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1L,
    /** 当前曲目的 content URI 字符串；用于在没有 MediaStore 封面时读内嵌封面 */
    val sourceUri: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 1f,
    val queueIds: List<Long> = emptyList(),
    val queueIndex: Int = -1,
    /** 最近一次播放失败的原因（已翻译成中文），由 UI 取走后清空 */
    val errorMessage: String? = null
) {
    /** Pause-button state, not a clock source for lyrics/progress extrapolation. */
    val isPlaybackActive: Boolean get() = isPlaying || playWhenReady
}

/** Buffering/seek is not pause; ended and failed playback must still show the play action. */
internal fun activePlaybackIntent(playWhenReady: Boolean, playbackState: Int, hasError: Boolean): Boolean =
    playWhenReady && playbackState != Player.STATE_ENDED && !hasError

/**
 * UI 侧的播放控制器：桥接 MediaController ↔ Compose 状态。
 */
class PlayerController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 歌曲真正开始播放时回调（用于记录最近播放） */
    var onSongStarted: ((Long) -> Unit)? = null

    /**
     * App 侧的「随机」开关。[PlayerUiState.shuffle] 读的是它，不是播放器的标志 ——
     * 播放器那个标志一直关着，见 [setShuffle]。
     */
    private var shuffleState: Boolean = false

    /**
     * 最近一次设定的循环模式。
     *
     * 连接 MediaController 是异步的（[connect]），冷启动恢复播放现场时
     * [setRepeat] **可能还没连上**。只写 `controller.repeatMode` 的话这次设定会丢，
     * 恢复出来的歌就退回播放器默认的 OFF。这里先记住，连上后补一次。
     */
    private var repeatState: RepeatMode = RepeatMode.OFF

    private var pendingRequest: PlayRequest? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private data class PlayRequest(
        val songs: List<Song>,
        val index: Int,
        val autoplay: Boolean,
        /** 从第几毫秒开始播。重排队列（开关随机）时要保住当前进度，不能从头开始 */
        val startPositionMs: Long = 0L,
        /**
         * 是否触发 [onSongStarted]。
         *
         * 冷启动「恢复上次播放现场」时传 false —— 那只是把上次的歌摆回来，
         * 不是用户又听了一遍。不拦的话每开一次 App 就给那首歌的播放次数 +1，
         * 「播放最多」排序会被开机次数污染。
         */
        val notifyStart: Boolean = true
    )

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            // 冷启动恢复时 [setRepeat] 可能早于连接完成：这里补上记住的模式
            c.repeatMode = repeatState.toPlayerRepeat()
            // 随机始终由 App 自己的队列实现，播放器标志固定关掉
            if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    sync(player, events)
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 报错时 `_state.value.title` 可能还停在上一条（元数据是异步回调的），
                    // 直接读播放器当前的媒体项才不会把「鸟之诗」报成上一首。
                    val failedTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?.takeIf { it.isNotBlank() } ?: _state.value.title
                    val failedUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
                        ?: _state.value.sourceUri
                    val message = describeError(error, failedTitle)
                    android.util.Log.e(
                        "Sonance",
                        "播放失败 code=${error.errorCode} name=${error.errorCodeName} " +
                            "item=$failedTitle uri=$failedUri msg=$message",
                        error
                    )
                    _state.update { it.copy(errorMessage = message, isPlaying = false, playWhenReady = false) }
                }
            })
            _state.update { it.copy(connected = true) }
            pendingRequest?.let { req ->
                pendingRequest = null
                applyPlay(req)
            }
            sync(c, null)
            startTicker()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            durationMs = if (c.duration > 0) c.duration else it.durationMs
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun sync(player: Player, events: Player.Events?) {
        val meta = player.currentMediaItem?.mediaMetadata
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val duration = if (player.duration > 0) player.duration else (meta?.durationMs ?: 0L)

        val queueChanged = events == null ||
            events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION
            )

        _state.update { old ->
            old.copy(
                connected = true,
                hasItem = player.mediaItemCount > 0,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                playWhenReady = activePlaybackIntent(player.playWhenReady, player.playbackState, player.playerError != null),
                mediaId = id,
                title = meta?.title?.toString().orEmpty(),
                artist = meta?.artist?.toString().orEmpty(),
                album = meta?.albumTitle?.toString().orEmpty(),
                albumId = meta?.extras?.getLong(EXTRA_ALBUM_ID, -1L) ?: -1L,
                sourceUri = meta?.extras?.getString(EXTRA_URI),
                durationMs = duration,
                positionMs = if (id != old.mediaId) 0L else player.currentPosition.coerceAtLeast(0L),
                shuffle = shuffleState,
                repeat = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
                volume = player.volume,
                queueIndex = player.currentMediaItemIndex
            )
        }

        if (queueChanged) refreshQueue(player)

        // ⚠️ 判据是 `id != 0L`，**不能写 `id > 0`** ——
        // 在线歌的 mediaId 是负数（见 `data/remoteSongId`），写成 `> 0` 的话
        // [onSongStarted] 对在线歌**永远不会触发**。它的下游挂着：
        //   · `ensureCover` → 在线歌的封面下载 → 表现就是"在线歌没有封面"
        //   · 队列 / 进度的持久化
        // 而且失败是静默的：播放、切歌、进度全都正常，只有封面和统计不见。
        //
        // 0 是"没有歌曲"的哨兵，只有它该短路。
        if (id != 0L && id != lastNotifiedId) {
            lastNotifiedId = id
            if (suppressNextStart) suppressNextStart = false else onSongStarted?.invoke(id)
        }
    }

    private var lastNotifiedId: Long = -1L

    /**
     * 下一次 [sync] 见到新歌时**不要**回调 [onSongStarted]，然后自动复位。
     *
     * 记成一次性标志而不是一直挂着：用户紧接着真的点了播放，
     * 那次就该正常计数（[applyPlay] 会用新请求里的 notifyStart 覆盖它）。
     */
    private var suppressNextStart = false

    private fun refreshQueue(player: Player) {
        val count = runCatching { player.mediaItemCount }.getOrDefault(0)
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) {
            ids += runCatching { player.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L }.getOrDefault(-1L)
        }
        _state.update { it.copy(queueIds = ids, queueIndex = player.currentMediaItemIndex) }
    }

    fun clearError() {
        if (_state.value.errorMessage != null) {
            _state.update { it.copy(errorMessage = null) }
        }
    }

    /**
     * 刷新当前曲目的封面 metadata。
     *
     * 两个调用时机：切歌之后、封面刚下载完成之后。以前只有 `setMediaItems` 那一刻
     * 会写一次 artworkUri，所以播放中补到的封面永远进不了通知栏。
     *
     * [MediaController.replaceMediaItem] 在**媒体源没变**时只更新 metadata、
     * 不会重新准备播放（ExoPlayer 会比较 `MediaItem.localConfiguration`），
     * 所以播放不中断、进度不丢。开头先比一次 URI，避免每次切歌都白替换一遍。
     */
    fun refreshCurrentArtwork(song: Song) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx !in 0 until c.mediaItemCount) return
        val current = runCatching { c.getMediaItemAt(idx) }.getOrNull() ?: return
        if (current.mediaId != song.id.toString()) return
        val wanted = song.artworkUri(context)
        if (current.mediaMetadata.artworkUri == wanted) return
        runCatching { c.replaceMediaItem(idx, song.toMediaItem(context)) }
            .onFailure { android.util.Log.w("SonanceCover", "刷新通知栏封面失败: ${it.message}") }
    }

    /**
     * 把**锁屏歌词**写进当前 MediaItem 的 metadata。
     *
     * ColorOS / OPlus 的锁屏歌词读的是 `MediaMetadata["lyricInfo"]`（一段 JSON），
     * 所以这里只改 extras，其余字段（标题、歌手、封面、时长）全部原样带回 ——
     * 换掉整个 metadata 会把封面和身份一起丢了。
     *
     * 用 `replaceMediaItem` 而不是重建 MediaItem：这条路径本来就在给通知栏刷封面
     * （见 [refreshCurrentArtwork]），实测不会打断播放。
     *
     * @param json null = 清除（换到没有歌词的歌时要把上一首的歌词收掉，
     *   否则锁屏会一直挂着上一首的句子）
     */
    fun publishLyricsInfo(json: String?) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx !in 0 until c.mediaItemCount) return
        val current = runCatching { c.getMediaItemAt(idx) }.getOrNull() ?: return

        val extras = android.os.Bundle(current.mediaMetadata.extras ?: android.os.Bundle())
        if (json == null) extras.remove(LockScreenLyrics.EXTRA_KEY)
        else extras.putString(LockScreenLyrics.EXTRA_KEY, json)
        if (current.mediaMetadata.extras?.getString(LockScreenLyrics.EXTRA_KEY) == json) return

        val metadata = current.mediaMetadata.buildUpon().setExtras(extras).build()
        runCatching { c.replaceMediaItem(idx, current.buildUpon().setMediaMetadata(metadata).build()) }
            .onFailure { android.util.Log.w("SonanceLyrics", "发布锁屏歌词失败: ${it.message}") }
    }

    /**
     * 当前这首是不是**在线流**（而不是本地文件）。
     *
     * 判据是 MediaItem 的 URI scheme，不是 `Song.source` —— 这里只有
     * `MediaController`，拿不到 [Song]。scheme 判据反而更可靠：它描述的是
     * "这一份数据实际从哪来"，而 `source` 是"这首歌属于哪个音源"，
     * 两者在将来支持"在线歌缓存到本地后播放"时就会分叉。
     */
    private fun isCurrentStream(): Boolean {
        val scheme = controller?.currentMediaItem
            ?.localConfiguration?.uri?.scheme
            ?: return false
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    /** 把 ExoPlayer 的错误码翻译成用户能看懂的一句话 */
    private fun describeError(error: PlaybackException, title: String): String {
        val prefix = if (title.isNotBlank()) "「$title」" else "这首歌"

        // 在线流要单独解释。
        //
        // 会员歌的取流接口不是返回 403，而是 302 到一张**下载页 HTML** ——
        // ExoPlayer 解析不出来，报的是「容器不受支持」。照搬那句话用户会以为
        // 是文件格式问题，跑去设置里翻「格式与兼容性」，其实只是没会员。
        if (isCurrentStream()) {
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "${prefix}连不上音源，检查一下网络"

                else -> "${prefix}拿不到播放地址，可能是会员歌或版权已下架"
            }
        }

        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到文件，可能已被移动或删除"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取该文件的权限"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "读取文件失败"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "文件格式不受支持（容器无法解析）"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "文件已损坏或不完整"

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "设备的解码器不支持这种音频编码"

            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "音频参数超出设备解码能力"

            // 24bit / 192kHz 这类高采样率 PCM 最常踩的就是这里：文件能解析、
            // 时长也读得出来，但 AudioTrack 建不起来。必须给出明确原因，
            // 不能让用户以为"进度条都有了，是不是卡了"。
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ->
                "设备的音频输出不支持这个音频参数（如 192kHz / 24bit PCM）"

            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED ->
                "音频输出中断，可能是设备音频服务异常"

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"

            // 1004：底层运行时检查失败。实测 384kHz 的 WAV 会在这里报
            // `getMinBufferSize(): 384000 Hz is not a supported sample rate`。
            // 192kHz 的 PCM 在部分设备上同样会走到这条路径 —— 文件能解析、
            // 时长也读得出来，只是 AudioTrack 建不起来。必须说清是采样率，
            // 否则用户只看到「无法播放（错误码 1004）」。
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> {
                // 这条路径抛的是「没有 message 的 IllegalStateException」，
                // 只能靠栈里是不是 DefaultAudioSink 来判断是不是音频参数问题。
                // 实测 384kHz：Assertions.checkState 在 getAudioTrackMinBufferSize 里失败。
                val fromAudioSink = generateSequence(error as Throwable?) { it.cause }
                    .flatMap { t -> t.stackTrace.asSequence() }
                    .any {
                        it.className.contains("DefaultAudioSink") ||
                            it.className.contains("AudioTrack")
                    }
                if (fromAudioSink) {
                    "设备的音频输出不支持这种音频参数（高采样率 PCM 常见）"
                } else {
                    "播放器运行时检查失败"
                }
            }

            PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            PlaybackException.ERROR_CODE_BAD_VALUE -> "设备不支持这种音频参数"

            else -> "无法播放（错误码 ${error.errorCode}）"
        }

        val hint = if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        ) {
            "。可以在「皮肤与设置 → 格式与兼容性」里查看本机支持的格式"
        } else if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
        ) {
            "。可以试试转换成 48kHz / 16bit 的版本再播放"
        } else {
            ""
        }

        return "$prefix$reason$hint"
    }

    // ===== 对外操作 =====

    fun play(
        songs: List<Song>,
        index: Int,
        autoplay: Boolean = true,
        startPositionMs: Long = 0L,
        notifyStart: Boolean = true
    ) {
        val req = PlayRequest(songs, index, autoplay, startPositionMs, notifyStart)
        val c = controller
        if (c == null) {
            pendingRequest = req
        } else {
            applyPlay(req)
        }
    }

    private fun applyPlay(req: PlayRequest) {
        val c = controller ?: return
        val items = req.songs.map { it.toMediaItem(context) }
        if (items.isEmpty()) return
        val start = req.index.coerceIn(0, items.lastIndex)
        lastNotifiedId = -1L
        suppressNextStart = !req.notifyStart
        c.setMediaItems(items, start, req.startPositionMs.coerceAtLeast(0L))
        c.prepare()
        if (req.autoplay) c.play() else c.pause()
        refreshQueue(c)
        sync(c, null)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (activePlaybackIntent(c.playWhenReady, c.playbackState, c.playerError != null)) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    /** 切歌音量淡出协程：新调用先取消旧的，避免被打断的旧协程停在半途音量上 */
    private var fadeJob: Job? = null

    /**
     * 播放指定曲目。
     *
     * [fadeMs] > 0 且正在播放时，先把当前曲目音量在 fadeMs 内渐降到 0 再切换
     * （黑胶页唱臂抬起时传 150ms 极短淡出，消除音频硬截断叠上机械音效的突兀感），
     * 完成后恢复用户音量并起播新曲。恢复值取 [_state.value.volume]——它是
     * [setVolume] 即时写入的用户设定，不受淡出操作污染。
     */
    fun playAt(index: Int, fadeMs: Long = 0L) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        fadeJob?.cancel()
        // 上一个淡出协程若被打断会停在半途音量上，先复原
        c.volume = _state.value.volume
        if (index == c.currentMediaItemIndex) {
            togglePlay()
        } else if (fadeMs > 0L && c.isPlaying) {
            lastNotifiedId = -1L
            fadeJob = scope.launch {
                val target = _state.value.volume
                val steps = 5
                repeat(steps) { step ->
                    if (!c.isPlaying) {
                        c.volume = target // 淡出中被打断（暂停/退出）：复原后放弃切歌
                        return@launch
                    }
                    c.volume = target * (steps - step - 1f) / steps
                    delay(fadeMs / steps)
                }
                c.volume = target
                c.seekToDefaultPosition(index)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        } else {
            lastNotifiedId = -1L
            c.seekToDefaultPosition(index)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /**
     * 下一首。
     *
     * **手动切歌按队列下标走，到末首绕回第一首**，不交给 `seekToNext()` 判断。
     *
     * 之前用 `hasNextMediaItem()` 兜底，有两个坑：
     * - 单曲循环（REPEAT_MODE_ONE）下它返回 false（"下一项"就是当前项），
     *   于是手动点下一首被兜底成"跳回第 0 首"；
     * - 随机是 App 自己重排队列实现的（播放器 `shuffleModeEnabled` 始终关着），
     *   `seekToNext()` 只看时间轴，本来也不会乱序。
     *
     * 自己算下标之后，顺序 / 列表循环 / 单曲循环 / 随机四种模式下手动切歌
     * 行为一致：永远往后走一首，最后一首回到第一首，不存在"切不动"的尽头。
     */
    fun next() {
        val c = controller ?: return
        lastNotifiedId = -1L
        val count = c.mediaItemCount
        if (count <= 0) return
        val nextIndex = if (c.currentMediaItemIndex >= count - 1) 0 else c.currentMediaItemIndex + 1
        c.seekToDefaultPosition(nextIndex)
        if (!c.isPlaying) c.play()
    }

    fun previous() {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count <= 0) return
        // 队首再往前 → 绕到队尾。这一条优先于"回到本首开头"，
        // 否则第一首放过 4 秒之后，用户按上一首永远到不了最后一首。
        if (c.currentMediaItemIndex <= 0) {
            lastNotifiedId = -1L
            c.seekToDefaultPosition(count - 1)
            if (!c.isPlaying) c.play()
            return
        }
        if (c.currentPosition > 4000L) {
            // 唱了几秒再按上一首：按主流播放器的惯例先回到本首开头
            c.seekTo(0)
            return
        }
        lastNotifiedId = -1L
        // 其余位置：往上一首走
        val prevIndex = c.currentMediaItemIndex - 1
        c.seekToDefaultPosition(prevIndex)
        if (!c.isPlaying) c.play()
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        c.seekTo(ms.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0L)) }
    }

    fun setVolume(value: Float) {
        val c = controller ?: return
        c.volume = value.coerceIn(0f, 1f)
        _state.update { it.copy(volume = value.coerceIn(0f, 1f)) }
    }

    /**
     * 直接设定「随机」开关。
     *
     * ⚠️ 播放器的 `shuffleModeEnabled` **始终保持关闭** —— 随机由 App 自己重排队列
     * 实现（见 `SonanceViewModel.toggleShuffle` / `ShuffleOrder`）。
     *
     * 为什么不能交给播放器：播放顺序和界面上那份队列会变成**两套**。
     * 播放页的上下滑走的是队列下标（分页器），自动续播走的是播放器的 shuffle order。
     * 于是手动滑到某一首之后，自动续播会按它自己的序跳到别处 ——
     * 用户来回几次就会撞上刚听过的歌，表现就是"老是那几首、重复概率高"。
     * 让两者共用一条队列，随机才真的是"一轮之内不重复"。
     */
    fun setShuffle(on: Boolean) {
        shuffleState = on
        val c = controller
        if (c == null) {
            _state.update { it.copy(shuffle = on) }
            return
        }
        // 兜底：万一别处把它打开了（比如系统媒体通知），这里按回来
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        sync(c, null)
    }

    /**
     * 直接指定循环模式。
     *
     * 三态播放模式（[PlayMode]）切换时用它。原来这里还有一个 `cycleRepeat()`
     * 做 OFF→ALL→ONE 转一圈，三态合并之后没有调用方了，已删除 ——
     * 它那种"转一圈"的语义和 [PlayMode] 的三态不是一回事，留着只会让人误用。
     */
    fun setRepeat(mode: RepeatMode) {
        repeatState = mode
        val c = controller
        if (c == null) {
            _state.update { it.copy(repeat = mode) }
            return
        }
        c.repeatMode = mode.toPlayerRepeat()
        sync(c, null)
    }

    private fun RepeatMode.toPlayerRepeat(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    /** 下一首播放 */
    fun playNext(song: Song) {
        val c = controller ?: return
        val idx = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(idx, song.toMediaItem(context))
        refreshQueue(c)
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount && c.mediaItemCount > 1) {
            c.removeMediaItem(index)
            refreshQueue(c)
        }
    }

    fun clearQueue() {
        val c = controller ?: return
        c.clearMediaItems()
        lastNotifiedId = -1L
        _state.update { PlayerUiState(connected = true, volume = it.volume) }
    }

    fun release() {
        ticker?.cancel()
        controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        controllerFuture = null
        controller = null
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val EXTRA_ALBUM_ID = "sonance.album_id"
        const val EXTRA_URI = "sonance.source_uri"

        /**
         * 通知栏 / 锁屏取封面的地址。
         *
         * 优先用**本应用下载并落盘**的那张。能走到"需要下载封面"这一步的歌，
         * 本地（MediaStore 专辑封面 / 文件内嵌）本来就是空的 —— 也就是说原来的
         * `legacyAlbumArtUri(albumId)` 对它们必定取不到图，这就是
         * 「系统下拉只显示播放信息、没有封面」的原因。
         *
         * 下载的封面躺在应用私有目录里，系统进程读不到 `File`，所以经 FileProvider
         * 转成 `content://` URI（见 [CoverCache.contentUri]）再交出去。
         */
        fun Song.artworkUri(context: Context): Uri =
            CoverCache.contentUri(context, OnlineCover.keyOf(title, artist))
                ?: ArtworkLoader.legacyAlbumArtUri(albumId)

        fun Song.toMediaItem(context: Context): MediaItem {
            val extras = android.os.Bundle().apply {
                putLong(EXTRA_ALBUM_ID, albumId)
                putString(EXTRA_URI, uri.toString())
            }
            return MediaItem.Builder()
                .setMediaId(id.toString())
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(artworkUri(context))
                        .setExtras(extras)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
}
