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
)

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

    private var pendingRequest: PlayRequest? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private data class PlayRequest(
        val songs: List<Song>,
        val index: Int,
        val autoplay: Boolean,
        /** 从第几毫秒开始播。重排队列（开关随机）时要保住当前进度，不能从头开始 */
        val startPositionMs: Long = 0L
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
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    sync(player, events)
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(errorMessage = describeError(error)) }
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
                mediaId = id,
                title = meta?.title?.toString().orEmpty(),
                artist = meta?.artist?.toString().orEmpty(),
                album = meta?.albumTitle?.toString().orEmpty(),
                albumId = meta?.extras?.getLong(EXTRA_ALBUM_ID, -1L) ?: -1L,
                sourceUri = meta?.extras?.getString(EXTRA_URI),
                durationMs = duration,
                positionMs = if (id != old.mediaId) 0L else player.currentPosition.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
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

        if (id > 0 && id != lastNotifiedId) {
            lastNotifiedId = id
            onSongStarted?.invoke(id)
        }
    }

    private var lastNotifiedId: Long = -1L

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

    /** 把 ExoPlayer 的错误码翻译成用户能看懂的一句话 */
    private fun describeError(error: PlaybackException): String {
        val title = _state.value.title
        val prefix = if (title.isNotBlank()) "「$title」" else "这首歌"

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

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"

            else -> "无法播放（错误码 ${error.errorCode}）"
        }

        val hint = if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ) {
            "。可以在「皮肤与设置 → 格式与兼容性」里查看本机支持的格式"
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
        startPositionMs: Long = 0L
    ) {
        val req = PlayRequest(songs, index, autoplay, startPositionMs)
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
        c.setMediaItems(items, start, req.startPositionMs.coerceAtLeast(0L))
        c.prepare()
        if (req.autoplay) c.play() else c.pause()
        refreshQueue(c)
        sync(c, null)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        if (index == c.currentMediaItemIndex) {
            togglePlay()
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
     * 必须用 [Player.seekToNext] 而不是 [Player.seekToNextMediaItem]：
     * 前者按**当前 repeat + shuffle 模式**挑下一首，后者是死板的"时间轴上下一项"。
     * 之前用错了，所以开了随机播放按下一首还是原顺序 —— 这就是"随机不生效"的原因。
     */
    fun next() {
        val c = controller ?: return
        lastNotifiedId = -1L
        if (c.hasNext()) c.seekToNext() else c.seekToDefaultPosition(0)
        if (!c.isPlaying) c.play()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 4000L) {
            c.seekTo(0)
            return
        }
        lastNotifiedId = -1L
        // 同样用 seekToPrevious（尊重 shuffle）。media3 1.5 的 Player 接口没有
        // hasPrevious()，而 hasPreviousMediaItem() 是时间轴语义、判断不了 shuffle 序，
        // 所以这里直接调用 —— 在队首时它是 no-op，正是想要的行为。
        c.seekToPrevious()
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
     * 切换随机播放。
     *
     * 只切 [Player.setShuffleModeEnabled] 就够了 —— ExoPlayer 在 setMediaItems 时
     * 会用一个**随机种子**生成 shuffle order（DefaultShuffleOrder），开启后
     * 自动续播和 [next]/[previous] 都会按那份随机序走。
     *
     * 注意 media3 1.5 的 `Player` 接口没有 `setShuffleOrder`（那是 `ExoPlayer` 才有的），
     * MediaController 用不了，所以没法在每次开启时强制换一份新随机序；
     * 但每次重新播放（setMediaItems）都会重新生成，实际体验没问题。
     */
    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        sync(c, null)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        sync(c, null)
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
