package com.sonance.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    // 播放页的三层（封面 / 歌词 / 律动柱）做成**环形轮播**：
    // 页数取极大值、用「页号 % 3」映射到层，于是左右两个方向都能一直滑下去，
    // 到边自然绕回另一端。起始页取 3 的倍数，保证初始停在封面层。
    val layerPager = rememberPagerState(initialPage = LAYER_RING_START) { Int.MAX_VALUE }
    val layer = PlayerLayer.entries[layerPager.currentPage % PlayerLayer.entries.size]

    /**
     * 跳到指定层。
     *
     * 沿环取**更近**的那一侧：从封面直接点「歌词」应该右移一格，
     * 而不是往左绕两格、滑过律动柱。
     */
    fun goToLayer(target: PlayerLayer) {
        val n = PlayerLayer.entries.size
        val current = layerPager.currentPage
        val delta = ((target.ordinal - current % n) + n) % n
        val step = if (delta == n - 1) -1 else delta
        if (step == 0) return
        scope.launch { layerPager.animateScrollToPage(current + step) }
    }

    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    ) { queue.size }

    // 歌词 / 律动柱上按返回键 → 滑回封面层，而不是直接收起播放页。
    // 拖动中不接管，免得和手指的滑动打架。
    BackHandler(enabled = layer != PlayerLayer.COVER && !layerPager.isScrollInProgress) {
        goToLayer(PlayerLayer.COVER)
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
        val currentSong = queue.getOrNull(state.queueIndex)

        // 三层横向轮播。这里用 HorizontalPager 而不是 AnimatedContent：
        // 内容会**跟着手指走**、松手回弹到最近一层，这才是轮播该有的手感；
        // AnimatedContent 只是「触发后播一段动画」，滑起来是跳的、过渡生硬。
        //
        // beyondViewportPageCount = 0：落定后只组合当前一层。早先那条
        // 「律动柱打开时把下层从组合里摘掉」的性能优化，交给 pager 自然完成。
        //
        // 注意：下面三个分支内部的缩进沿用了改造前的层级（整体没有再缩进四格），
        // 免得这次改动变成几千行噪声 diff。
        HorizontalPager(
            state = layerPager,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
        // 每层裁到自己的页面矩形。这层裁剪不是保险，是必须的：
        // 歌词层的背景是 `scale(1.35f)` 的模糊封面，而 Modifier.scale 默认**不裁剪**内容，
        // 于是它向左右各溢出 17.5% 的页面宽度（1080 宽的手机恰好 189px）。
        // 滑动过程中相邻两层会同时参与组合（beyondViewportPageCount = 0 只保证落定后
        // 不多组合，滑动中该组合的照样组合），溢出部分就盖到邻层上 ——
        // 表现即「只在中途出现、切换完就没了」的竖条。
        // pager 自己的视口裁剪挡不住它：竖条落在屏幕内，而不是视口外。
        //
        // 实测（1080x2400 / android-36）：去掉这两处裁剪后，封面页背景区亮度从 18.0
        // 变成 85.4 —— 就是那条带；加回裁剪即恢复 18.0，且歌词页自身亮度完全不变。
        Box(Modifier.fillMaxSize().clipToBounds()) {
        when (page % PlayerLayer.entries.size) {

        // ===== 第 0 层：封面 + 顶栏 =====
        0 -> Column(Modifier.fillMaxSize()) {

            // ===== 顶栏（固定，不随页滚动）=====
            //
            // 用 Box 叠一层，而不是把标题塞进 Row 的中间格：
            // 左侧只有 1 个图标、右侧有 3 个，塞进 Row 的话标题是在**剩余空间**里居中，
            // 结果明显偏左。这里让标题独立居中于整条栏。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(
                        icon = SonanceIcons.ChevronDown,
                        contentDescription = "收起",
                        onClick = onClose,
                        iconSize = 22.dp
                    )
                    Spacer(Modifier.weight(1f))
                    // 歌词 / 律动柱开关：当前就在那一层时再点一次，回到封面层
                    IconAction(
                        icon = SonanceIcons.Lyrics,
                        contentDescription = if (layer == PlayerLayer.LYRICS) "关闭歌词" else "显示歌词",
                        onClick = {
                            goToLayer(
                                if (layer == PlayerLayer.LYRICS) PlayerLayer.COVER else PlayerLayer.LYRICS
                            )
                        },
                        iconSize = 20.dp,
                        tint = if (layer == PlayerLayer.LYRICS) skin.accent else skin.textMuted
                    )
                    IconAction(
                        icon = SonanceIcons.Equalizer,
                    contentDescription = if (layer == PlayerLayer.VISUALIZER) "关闭律动柱" else "显示律动柱",
                    onClick = {
                        goToLayer(
                            if (layer == PlayerLayer.VISUALIZER) {
                                PlayerLayer.COVER
                            } else {
                                PlayerLayer.VISUALIZER
                            }
                        )
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

                // 标题独立居中于整条栏，不受左右图标数量影响
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted,
                    modifier = Modifier.align(Alignment.Center)
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
                    // key 必须带上页码：`playNext` 不查重，队列里可能出现同一首歌两次
                    // （用户对已在队列里的歌点"下一首播放"就会），只拿 song.id 当 key
                    // 会撞成重复 key，分页器直接抛 "Key ... was already used"。
                    key = { page -> "$page-${queue.getOrNull(page)?.id ?: -1L}" },
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
                        onOpenLyrics = { goToLayer(PlayerLayer.LYRICS) }
                    )
                }
            }
        }

        // ===== 第 1 层：歌词 =====
        1 -> if (currentSong != null) {
            LyricsOverlay(
                song = currentSong,
                positionMs = state.positionMs,
                prefs = prefs,
                onSeek = onSeek,
                onClose = { goToLayer(PlayerLayer.COVER) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== 第 2 层：律动柱 =====
        else -> if (currentSong != null) {
            VisualizerOverlay(
                song = currentSong,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = if (state.durationMs > 0) {
                    state.durationMs
                } else {
                    currentSong.durationMs
                },
                prefs = prefs,
                onClose = { goToLayer(PlayerLayer.COVER) },
                modifier = Modifier.fillMaxSize()
            )
        }
        }
        }   // Box(fillMaxSize().clipToBounds())
        }
    }
}

/**
 * 播放页的三层。三者首尾相连成一个环，左右滑动可以一直转下去
 * （封面 → 歌词 → 律动柱 → 封面 …，反方向同理）。
 */
private enum class PlayerLayer { COVER, LYRICS, VISUALIZER }

/**
 * 环形轮播的起始页号。
 *
 * 取环中间的 3 的倍数：既保证启动时停在封面层（`page % 3 == 0`），
 * 又让左右两个方向都有足够余量一直滑下去。
 */
private const val LAYER_RING_START = 3000

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
    /** 点「歌词」入口：切到全屏歌词页 */
    onOpenLyrics: () -> Unit
) {
    val skin = LocalSkin.current
    val seed = song.title + song.artist
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
                    Spacer(Modifier.height(12.dp))
                    LyricsEntryButton(onClick = onOpenLyrics)
                    Spacer(Modifier.height(18.dp))
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
                Spacer(Modifier.height(12.dp))
                LyricsEntryButton(onClick = onOpenLyrics)
                Spacer(Modifier.height(18.dp))
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

/**
 * 「歌词」入口胶囊。
 *
 * 顶栏虽然有一个歌词图标按钮，但纯图标辨识度低、用户反馈找不到，
 * 所以在封面页的控制区给一个**带文字**的入口，点一下切到全屏歌词页。
 */
@Composable
private fun LyricsEntryButton(onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(skin.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = SonanceIcons.Lyrics,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = "歌词",
            style = MaterialTheme.typography.labelMedium,
            color = skin.text
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
