package com.sonance.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.flow.collect

/**
 * 全屏播放页。竖向翻页（TikTok / 汽水音乐式）：整页上下滑动切歌，
 * 相邻页提前渲染（[androidx.compose.foundation.pager.PagerState] 的
 * `beyondViewportPageCount = 1`），120Hz 下流畅切换。
 */
@Composable
fun FullPlayer(
    state: PlayerUiState,
    queue: List<Song>,
    favorites: Set<Long>,
    onClose: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val prefs = remember { com.sonance.player.data.Prefs(context) }
    var showVisualizer by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // 律动柱 / 歌词打开时用返回键先关它，而不是直接收起播放页
    BackHandler(enabled = showVisualizer) { showVisualizer = false }
    BackHandler(enabled = showLyrics) { showLyrics = false }

    // 分页器 → 播放器：只在页面「落定」后切歌，避免滑动过程中反复触发。
    // 用 rememberUpdatedState 读取最新 queueIndex，否则快照流里会捕获到旧值。
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(pagerState, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != latestState.queueIndex) onPlayAt(page)
        }
    }

    // 播放器 → 分页器：按钮切歌 / 自动续播 / 点队列时把页面跟过去。
    LaunchedEffect(state.queueIndex, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        val target = state.queueIndex.coerceIn(0, queue.lastIndex)
        if (pagerState.settledPage != target && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(target)
        }
    }

    Box(modifier.fillMaxSize().background(skin.bg)) {
        // 律动柱 / 歌词是整屏不透明覆盖层。打开时**把下层从组合里摘掉**，
        // 而不是盖在上面 —— 否则 Compose 每帧仍要重录整棵子树的绘制指令
        // （VerticalPager + 封面图 + 进度条 + 一排控制按钮），这是律动柱
        // 帧率上不去的主要原因之一。
        val overlayOpen = showVisualizer || showLyrics
        if (!overlayOpen) {
        Column(Modifier.fillMaxSize()) {

            // ===== 顶栏（固定，不随页滚动）=====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    icon = SonanceIcons.ChevronDown,
                    contentDescription = "收起",
                    onClick = onClose,
                    iconSize = 22.dp
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "正在播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.textMuted
                    )
                }
                // 歌词开关
                IconAction(
                    icon = SonanceIcons.Lyrics,
                    contentDescription = if (showLyrics) "关闭歌词" else "显示歌词",
                    onClick = {
                        showLyrics = !showLyrics
                        if (showLyrics) showVisualizer = false
                    },
                    iconSize = 20.dp,
                    tint = if (showLyrics) skin.accent else skin.textMuted
                )
                // 律动柱开关
                IconAction(
                    icon = SonanceIcons.Equalizer,
                    contentDescription = if (showVisualizer) "关闭律动柱" else "显示律动柱",
                    onClick = {
                        showVisualizer = !showVisualizer
                        if (showVisualizer) showLyrics = false
                    },
                    iconSize = 20.dp,
                    tint = if (showVisualizer) skin.accent else skin.textMuted
                )
                IconAction(
                    icon = SonanceIcons.Queue,
                    contentDescription = "播放队列",
                    onClick = onOpenQueue,
                    iconSize = 20.dp
                )
            }

            if (queue.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未在播放",
                        style = MaterialTheme.typography.titleMedium,
                        color = skin.textMuted
                    )
                }
            } else {
                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    key = { page -> queue.getOrNull(page)?.id ?: page },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val song = queue.getOrNull(page) ?: return@VerticalPager
                    val isCurrent = page == state.queueIndex
                    PlayerPage(
                        song = song,
                        isFavorite = song.id in favorites,
                        // 只有当前页读实时播放状态；其它页给中性值，避免进度每 500ms
                        // 更新时把相邻页也拖进重组。
                        positionMs = if (isCurrent) state.positionMs else 0L,
                        durationMs = if (isCurrent && state.durationMs > 0) state.durationMs else song.durationMs,
                        isPlaying = if (isCurrent) state.isPlaying else false,
                        shuffle = if (isCurrent) state.shuffle else false,
                        repeat = if (isCurrent) state.repeat else RepeatMode.OFF,
                        volume = if (isCurrent) state.volume else 1f,
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onShuffle = onShuffle,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onRepeat = onRepeat,
                        onSeek = onSeek,
                        onVolume = onVolume,
                        onOpenQueue = onOpenQueue,
                        // 左滑封面进歌词页（和顶栏的歌词按钮等价）
                        onOpenCover = onOpenCover,
                        onSwipeToLyrics = {
                            showLyrics = true
                            showVisualizer = false
                        }
                    )
                }
            }
        }
        }   // if (!overlayOpen)

        // ===== 全屏歌词 =====
        if (showLyrics) {
            val current = queue.getOrNull(state.queueIndex)
            if (current != null) {
                LyricsOverlay(
                    song = current,
                    positionMs = state.positionMs,
                    prefs = prefs,
                    onClose = { showLyrics = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(11f)
                )
            } else {
                showLyrics = false
            }
        }

        // ===== 全屏律动柱 =====
        if (showVisualizer) {
            val current = queue.getOrNull(state.queueIndex)
            if (current != null) {
                VisualizerOverlay(
                    song = current,
                    isPlaying = state.isPlaying,
                    positionMs = state.positionMs,
                    durationMs = if (state.durationMs > 0) state.durationMs else current.durationMs,
                    prefs = prefs,
                    onClose = { showVisualizer = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                )
            } else {
                showVisualizer = false
            }
        }
    }
}

/**
 * 「左滑封面进歌词」的手势。
 *
 * 用 [detectHorizontalDragGestures] 而不是自己处理原始事件：它只在**水平**方向
 * 超过 touch slop 后才接管，所以不会和 VerticalPager 的上下滑动抢事件；
 * 又因为只挂在封面区域（不覆盖进度条 / 音量条），也不会把横向拖动的滑杆吞掉。
 *
 * 阈值取 72dp —— 太短会误触，太长滑不动。
 */
@Composable
private fun rememberSwipeLeftToLyrics(onTrigger: () -> Unit): Modifier {
    val cb by rememberUpdatedState(onTrigger)
    return Modifier.pointerInput(Unit) {
        val threshold = 72.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                if (total <= -threshold) cb()
                total = 0f
            }
        ) { _, dragAmount ->
            total += dragAmount
        }
    }
}


/**
 * 单页播放器（自包含）：背景晕染、封面、文本、进度与控制。
 * 只接收派生值，不直接读 [PlayerUiState]，因此非当前页可被跳过重组。
 */
@Composable
private fun PlayerPage(
    song: Song,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    volume: Float,
    onToggleFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit,
    onSwipeToLyrics: () -> Unit = {}
) {
    val skin = LocalSkin.current
    val seed = song.title + song.artist
    // 左滑封面进歌词页
    val swipeToLyrics = rememberSwipeLeftToLyrics(onSwipeToLyrics)
    val frac = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val boxH = maxHeight
        val isWide = boxW >= 620.dp && boxW > boxH
        // 单次 BoxWithConstraints：原实现里再嵌了一层只为算封面边长，把这一层去掉，
        // 封面尺寸在外层一次算好，少一轮 measure。
        val portraitSide = if (!isWide) (if (boxW < boxH) boxW else boxH) else 0.dp
        val landscapeSide = if (isWide) landscapeCoverSize(boxW, boxH) else 0.dp

        // 背景只用一层渐变（drawBehind），不再叠整屏 alpha-cover + 整屏渐变两个 layer；
        // 旧的 `alpha(<1)` 会强制创建一张全屏大小的离屏缓冲，3 页一起就是 3 张。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            skin.bg.copy(alpha = 0.94f),
                            skin.bg
                        )
                    )
                )
        )

        if (isWide) {
            // ===== 横屏 / 宽屏：左右双栏 =====
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                Box(Modifier.weight(1f).then(swipeToLyrics), contentAlignment = Alignment.Center) {
                    CoverArt(
                        albumId = song.albumId,
                        seed = seed,
                        modifier = Modifier
                            .size(landscapeSide)
                            .clip(RoundedCornerShape(16.dp)),
                        cornerRadius = 16.dp,
                        sizePx = 900,
                        sourceUri = song.uri,
                        songId = song.id,
                        coverKey = OnlineCover.keyOf(song.title, song.artist)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    TrackText(song.title, song.artist, song.album)
                    Spacer(Modifier.height(20.dp))
                    ProgressBlock(frac, positionMs, durationMs, onSeek)
                    Spacer(Modifier.height(14.dp))
                    TransportControls(
                        isPlaying = isPlaying,
                        shuffle = shuffle,
                        repeat = repeat,
                        onShuffle = onShuffle,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onRepeat = onRepeat,
                        ctrlSize = 40.dp,
                        ctrlIcon = 20.dp,
                        playSize = 60.dp,
                        playIcon = 26.dp,
                        gap = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    BottomRow(isFavorite, volume, onToggleFavorite, onVolume, onOpenQueue, onOpenCover)
                }
            }
        } else {
            // ===== 竖屏：封面在上方弹性居中，信息与控制整体贴底 =====
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(swipeToLyrics),
                    contentAlignment = Alignment.Center
                ) {
                    CoverArt(
                        albumId = song.albumId,
                        seed = seed,
                        modifier = Modifier
                            .size(portraitSide)
                            .clip(RoundedCornerShape(16.dp)),
                        cornerRadius = 16.dp,
                        sizePx = 900,
                        sourceUri = song.uri,
                        songId = song.id,
                        coverKey = OnlineCover.keyOf(song.title, song.artist)
                    )
                }

                Spacer(Modifier.height(10.dp))
                TrackText(song.title, song.artist, song.album, centered = true)
                Spacer(Modifier.height(20.dp))
                ProgressBlock(frac, positionMs, durationMs, onSeek)
                Spacer(Modifier.height(16.dp))
                TransportControls(
                    isPlaying = isPlaying,
                    shuffle = shuffle,
                    repeat = repeat,
                    onShuffle = onShuffle,
                    onPrev = onPrev,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onRepeat = onRepeat,
                    ctrlSize = 44.dp,
                    ctrlIcon = 22.dp,
                    playSize = 64.dp,
                    playIcon = 28.dp,
                    gap = 14.dp
                )
                Spacer(Modifier.height(14.dp))
                BottomRow(isFavorite, volume, onToggleFavorite, onVolume, onOpenQueue, onOpenCover)
                // 距底部 20dp（再叠加系统导航栏安全区）
                Spacer(Modifier.height(BottomGap))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun TrackText(title: String, artist: String, album: String, centered: Boolean = false) {
    val skin = LocalSkin.current
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = skin.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
        if (album.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = album,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align
            )
        }
    }
}

@Composable
private fun ProgressBlock(
    frac: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth()) {
        SonanceSlider(
            value = frac,
            onValueChange = { f -> onSeek((f * durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 4.dp,
            thumbSize = 13.dp,
            alwaysShowThumb = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }
    }
}

@Composable
private fun BottomRow(
    isFavorite: Boolean,
    volume: Float,
    onFavorite: () -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconAction(
            icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
            contentDescription = "收藏",
            onClick = onFavorite,
            iconSize = 20.dp,
            tint = if (isFavorite) skin.accent else skin.textMuted
        )
        VolumeSlider(
            volume = volume,
            onVolumeChange = onVolume,
            modifier = Modifier.weight(1f)
        )
        IconAction(
            icon = SonanceIcons.Waveform,
            contentDescription = "音效",
            onClick = onOpenQueue,
            iconSize = 20.dp,
            tint = skin.textMuted
        )
        IconAction(
            icon = SonanceIcons.Image,
            contentDescription = "更换封面",
            onClick = onOpenCover,
            iconSize = 20.dp,
            tint = skin.textMuted
        )
    }
}

private fun landscapeCoverSize(width: Dp, height: Dp): Dp {
    val byHeight = height - 28.dp
    val byWidth = width * 0.44f
    return if (byHeight < byWidth) byHeight else byWidth
}

/** 播放页内容距底部的留白 */
private val BottomGap = 20.dp
