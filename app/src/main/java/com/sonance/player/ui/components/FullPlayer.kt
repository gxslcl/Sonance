package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.IntOffset
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
    // 播放页的三层：封面 / 歌词 / 律动柱。横向滑动在它们之间**循环**切换。
    var layer by rememberSaveable { mutableStateOf(PlayerLayer.COVER) }
    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // 歌词 / 律动柱打开时用返回键先回到封面层，而不是直接收起播放页
    BackHandler(enabled = layer != PlayerLayer.COVER) { layer = PlayerLayer.COVER }

    // 横向滑动切层：左滑下一层、右滑上一层，到边了绕回另一端
    val layerSwipe = rememberLayerSwipe { left ->
        layer = layer.step(forward = left)
    }

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
        val overlayOpen = layer != PlayerLayer.COVER
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
                // 歌词 / 律动柱开关：当前就在那一层时再点一次，回到封面层
                IconAction(
                    icon = SonanceIcons.Lyrics,
                    contentDescription = if (layer == PlayerLayer.LYRICS) "关闭歌词" else "显示歌词",
                    onClick = {
                        layer = if (layer == PlayerLayer.LYRICS) {
                            PlayerLayer.COVER
                        } else {
                            PlayerLayer.LYRICS
                        }
                    },
                    iconSize = 20.dp,
                    tint = if (layer == PlayerLayer.LYRICS) skin.accent else skin.textMuted
                )
                IconAction(
                    icon = SonanceIcons.Equalizer,
                    contentDescription = if (layer == PlayerLayer.VISUALIZER) "关闭律动柱" else "显示律动柱",
                    onClick = {
                        layer = if (layer == PlayerLayer.VISUALIZER) {
                            PlayerLayer.COVER
                        } else {
                            PlayerLayer.VISUALIZER
                        }
                    },
                    iconSize = 20.dp,
                    tint = if (layer == PlayerLayer.VISUALIZER) skin.accent else skin.textMuted
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
                        onOpenCover = onOpenCover,
                        // 滑动热区覆盖整页（见 rememberLayerSwipe）
                        swipeModifier = layerSwipe
                    )
                }
            }
        }
        }   // if (!overlayOpen)

        // ===== 歌词 / 律动柱覆盖层 =====
        // 两层之间用 AnimatedContent 滑动过渡，做出「轮播」的感觉。
        // 方向直接由环上的相对位置推出来，不需要额外记一个方向状态：
        // 目标层是「当前层的下一层」就是左滑（新页从右进、旧页往左出）。
        val currentSong = queue.getOrNull(state.queueIndex)
        if (overlayOpen && currentSong != null) {
            AnimatedContent(
                targetState = layer,
                transitionSpec = {
                    val forward = targetState == initialState.step(forward = true)
                    val dir = if (forward) 1 else -1
                    val spec = tween<IntOffset>(LAYER_SLIDE_MS)
                    (
                        slideInHorizontally(spec) { w -> dir * w } +
                            fadeIn(tween(LAYER_SLIDE_MS))
                        ).togetherWith(
                        slideOutHorizontally(spec) { w -> -dir * w } +
                            fadeOut(tween(LAYER_SLIDE_MS))
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
                label = "playerLayer"
            ) { target ->
                // 覆盖层自己也要能横滑，才能「歌词 ←→ 律动柱」互相切过去
                Box(Modifier.fillMaxSize().then(layerSwipe)) {
                    when (target) {
                        PlayerLayer.LYRICS -> LyricsOverlay(
                            song = currentSong,
                            positionMs = state.positionMs,
                            prefs = prefs,
                            onClose = { layer = PlayerLayer.COVER },
                            modifier = Modifier.fillMaxSize()
                        )

                        PlayerLayer.VISUALIZER -> VisualizerOverlay(
                            song = currentSong,
                            isPlaying = state.isPlaying,
                            positionMs = state.positionMs,
                            durationMs = if (state.durationMs > 0) {
                                state.durationMs
                            } else {
                                currentSong.durationMs
                            },
                            prefs = prefs,
                            onClose = { layer = PlayerLayer.COVER },
                            modifier = Modifier.fillMaxSize()
                        )

                        PlayerLayer.COVER -> Unit
                    }
                }
            }
        }
    }
}

/** 播放页的三层。横向滑动在它们之间**循环**切换（封面 → 歌词 → 律动柱 → 封面）。 */
private enum class PlayerLayer { COVER, LYRICS, VISUALIZER }

/**
 * 沿着环走一步。
 *
 * 用取模而不是 when 枚举：三层首尾相连，`step` 在两个方向上都是对称的一句，
 * 以后加层也不用改这里。
 */
private fun PlayerLayer.step(forward: Boolean): PlayerLayer {
    val n = PlayerLayer.entries.size
    val i = (ordinal + if (forward) 1 else n - 1) % n
    return PlayerLayer.entries[i]
}

/** 层间滑动过渡时长。短一点：这是整屏内容，拖久了会显得拖沓 */
private const val LAYER_SLIDE_MS = 220

/**
 * 播放页的横向滑动切层手势。
 *
 * **热区挂在整页上**，不再只挂封面那一块 —— 之前手指落在歌词文字、进度条上方或者
 * 大片留白处都没反应，只有正好按在封面上才有效。
 *
 * 用 [detectHorizontalDragGestures] 而不是自己处理原始事件：它只在**水平**方向超过
 * touch slop 之后才接管，于是
 * - 不会和 VerticalPager 的上下滑动抢事件（竖向它根本不参与）
 * - 铺满整页也不会吞掉进度条 / 音量条 —— 子级 Slider 在 Main 阶段先消费横向拖动，
 *   而 `awaitHorizontalTouchSlopOrCancellation` 一旦发现手势被消费就会放弃
 *
 * 阈值 64dp：太短会误触，太长滑不动。
 */
@Composable
private fun rememberLayerSwipe(onSwipe: (left: Boolean) -> Unit): Modifier {
    val cb by rememberUpdatedState(onSwipe)
    return Modifier.pointerInput(Unit) {
        val threshold = 64.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                if (total <= -threshold) cb(true) else if (total >= threshold) cb(false)
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
    /** 横向滑动切层的热区，挂在整页最外层（见 [rememberLayerSwipe]） */
    swipeModifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val seed = song.title + song.artist
    val frac = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    BoxWithConstraints(Modifier.fillMaxSize().then(swipeModifier)) {
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
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
                        .fillMaxWidth(),
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
