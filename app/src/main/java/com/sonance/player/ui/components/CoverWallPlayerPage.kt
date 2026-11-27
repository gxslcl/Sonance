package com.sonance.player.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.HiResQuality
import com.sonance.player.data.LaunchCoverSnapshot
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.PlayerSwitchMotion
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.HiResBadgeSize
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.wall.CoverSpec
import com.sonance.player.ui.wall.WallCardPainter
import com.sonance.player.ui.wall.WallPose
import com.sonance.player.ui.wall.decorateCover
import com.sonance.player.ui.wall.renderCoverBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WALL_VINYL_DEG_PER_SEC = 28f
private val WALL_TEXT_PRIMARY = Color(0xFFF3F3F7)

private val proceduralWallArtCache = object : LruCache<String, Bitmap>(32) {}
private val wallPaintersByDensity = HashMap<Int, WallCardPainter>()

private fun sharedWallCardPainter(density: Float): WallCardPainter {
    val key = density.toBits()
    return synchronized(wallPaintersByDensity) {
        wallPaintersByDensity.getOrPut(key) { WallCardPainter(density) }
    }
}

internal suspend fun warmProceduralCoverBitmap(song: Song) {
    loadProceduralBitmap(coverWallSpecFor(song))
}

internal fun coverWallSpecFor(song: Song): CoverSpec =
    decorateCover(song.album.ifBlank { song.title }, song.artist)

private fun proceduralCoverCacheKey(spec: CoverSpec): String =
    "${spec.title.length}:${spec.title}|${spec.artist.length}:${spec.artist}|${spec.style}|${spec.h1}|${spec.h2}|512"

private fun peekProceduralBitmap(spec: CoverSpec): Bitmap? =
    proceduralWallArtCache.get(proceduralCoverCacheKey(spec))

private suspend fun loadProceduralBitmap(spec: CoverSpec): Bitmap {
    val key = proceduralCoverCacheKey(spec)
    proceduralWallArtCache.get(key)?.let { return it }
    return withContext(Dispatchers.Default) {
        renderCoverBitmap(spec, 512, 0f)
    }.also { proceduralWallArtCache.put(key, it) }
}

/**
 * 计算封面墙播放样式当前曲目的主色调：
 * - 关闭真实封面：立即确定性生成程序艺术封面主色；
 * - 开启真实封面：优先读取真实封面异步提取出的主色卡，未就绪时返回 [Color.Unspecified] 保持上一首颜色平滑过渡。
 */
@Composable
internal fun rememberCoverWallAccentColor(
    song: Song?,
    useRealCover: Boolean
): Color {
    val context = LocalContext.current
    if (song == null) return Color(0xFF1E2230)
    if (!useRealCover) {
        return remember(song.id, song.title, song.artist, song.album) {
            Color(coverWallSpecFor(song).mainColor)
        }
    }
    val coverKey = remember(song.id, song.title, song.artist) {
        OnlineCover.keyOf(song.title, song.artist)
    }
    val tint = ArtworkLoader.tintOf(song.albumId, song.uri, coverKey)
    val goneVersion = ArtworkLoader.artworkGoneVersion
    val snapshot = remember(song.id, goneVersion) {
        LaunchCoverSnapshot.peekBands(context, song.id)
    }
    return when {
        tint != null -> Color(tint.center)
        snapshot != null && snapshot.isNotEmpty() -> snapshot.getOrElse(1) { snapshot[0] }
        ArtworkLoader.isArtworkGone(song.albumId, song.uri, coverKey) -> Color(coverWallSpecFor(song).mainColor)
        else -> Color.Unspecified
    }
}

/**
 * 复用封面墙聚焦态美学的普通全屏播放页布局：
 * - 比例与 `NativeWallFocusLayout` 一致；使用无裁切整块 Canvas 绘制实体封套 + 黑胶，消除四角杂影；
 * - 自带封面墙专属实体唱片切歌动画（旧盘缩回封套后退、新盘入场滑出黑胶并接续旋转），独立于经典切歌动效；
 * - 剥离封面墙「下一曲随机」、底部「曲目列表 · N 首」、向下拖动提示与音量条，均衡器收纳至三点菜单。
 */
@Composable
internal fun CoverWallPlayerPage(
    song: Song,
    isFavorite: Boolean,
    showDownloadButton: Boolean,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    controlsPlaying: Boolean,
    coverScale: State<Float>,
    useRealCover: Boolean,
    stableWallColor: Color,
    activeWallColor: () -> Color,
    playMode: PlayMode,
    onToggleFavorite: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCover: () -> Unit,
    playbackSpeed: Float,
    onSetSpeed: (Float) -> Unit,
    abLoopStartMs: Long,
    abLoopEndMs: Long,
    onToggleAbLoop: () -> Unit,
    prefs: Prefs,
    swipePager: PagerState,
    swipePage: Int,
    streamQuality: String?,
    hiResBadgeEnabled: Boolean,
    hiResBadgeSize: HiResBadgeSize,
    switchMotion: PlayerSwitchMotion,
    activeSwitch: ButtonSwitchState?,
    switchProgress: () -> Float,
    prevKickPx: () -> Float,
    nextKickPx: () -> Float,
    coveredByQueue: Boolean,
    coveredByVinyl: Boolean,
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit
) {
    val skin = LocalSkin.current
    val safeDuration = durationMs.coerceAtLeast(0L)
    val safePos = positionMs.coerceIn(0L, safeDuration)
    val frac = if (safeDuration > 0L) (safePos.toDouble() / safeDuration).toFloat().coerceIn(0f, 1f) else 0f

    val accentList = remember(stableWallColor, skin.accent) {
        if (stableWallColor != Color.Unspecified) listOf(stableWallColor) else listOf(skin.accent)
    }
    val playPalette = rememberPlayButtonPalette(
        mode = prefs.playBtnColorMode,
        coverColors = accentList,
        themeColor = skin.accent,
        originalIcon = skin.onAccent
    )
    val playButtonPaletteProvider: () -> PlayButtonPalette = { playPalette }

    val lyricsScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset(0f, available.y)

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity = androidx.compose.ui.unit.Velocity(0f, available.y)
        }
    }

    // 用 state 对象原地传递旋转角，仅在 Canvas 绘制阶段读取，彻底避免 120Hz 全页重组抢占滑页/切歌帧率
    val discAngleState = remember { mutableFloatStateOf(0f) }
    val spinActive = isPlaying && !coveredByQueue && !coveredByVinyl
    LaunchedEffect(spinActive) {
        if (!spinActive) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            discAngleState.floatValue = (discAngleState.floatValue + dt * WALL_VINYL_DEG_PER_SEC) % 360f
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxMaxW = maxWidth
        val boxMaxH = maxHeight
        val isWide = boxMaxW >= 560.dp && boxMaxW > boxMaxH
        if (isWide) {
            val isCompactLandscape = boxMaxH < 500.dp
            val hPad = if (isCompactLandscape) 20.dp else (boxMaxW * 0.045f).coerceIn(32.dp, 52.dp)
            val paneGap = if (isCompactLandscape) 20.dp else 28.dp
            val leftWeight = if (isCompactLandscape) 0.43f else 0.45f
            val leftPaneW = (boxMaxW - hPad * 2 - paneGap) * leftWeight
            val coverSide = if (isCompactLandscape) {
                minOf(leftPaneW / 1.39f, (boxMaxH - 16.dp) * 0.78f, 260.dp).coerceAtLeast(120.dp)
            } else {
                minOf(leftPaneW / 1.39f, (boxMaxH - 28.dp) * 0.70f, 360.dp).coerceAtLeast(160.dp)
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = hPad, vertical = if (isCompactLandscape) 6.dp else 14.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(leftWeight)
                        .fillMaxHeight()
                        .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) },
                    contentAlignment = Alignment.Center
                ) {
                    WallSwitchCoverSlot(
                        song = song,
                        side = coverSide,
                        useRealCover = useRealCover,
                        activeWallColor = activeWallColor,
                        discAngle = { discAngleState.floatValue },
                        coverScale = coverScale,
                        activeSwitch = activeSwitch,
                        switchProgress = switchProgress,
                        hiResBadgeEnabled = hiResBadgeEnabled,
                        hiResBadgeSize = hiResBadgeSize,
                        onOpenCover = onOpenCover,
                        prefs = prefs
                    )
                }
                Spacer(Modifier.width(paneGap))
                Column(
                    modifier = Modifier
                        .weight(1f - leftWeight)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(lyricsScrollGuard)
                    ) {
                        InlineLyrics(
                            song = song,
                            positionMs = safePos,
                            prefs = prefs,
                            onSeek = onSeek,
                            isPlaying = isPlaying,
                            compact = true,
                            seekEnabled = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(if (isCompactLandscape) 4.dp else 10.dp))
                    WallPlayerControlCluster(
                        song = song,
                        streamQuality = streamQuality,
                        isFavorite = isFavorite,
                        showDownloadButton = showDownloadButton,
                        isDownloading = isDownloading,
                        isDownloaded = isDownloaded,
                        onDownload = onDownload,
                        onToggleFavorite = onToggleFavorite,
                        frac = frac,
                        positionMs = safePos,
                        durationMs = safeDuration,
                        onSeek = onSeek,
                        playbackSpeed = playbackSpeed,
                        onSetSpeed = onSetSpeed,
                        abLoopStartMs = abLoopStartMs,
                        abLoopEndMs = abLoopEndMs,
                        onToggleAbLoop = onToggleAbLoop,
                        controlsPlaying = controlsPlaying,
                        playMode = playMode,
                        onCyclePlayMode = onCyclePlayMode,
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onOpenQueue = onOpenQueue,
                        playButtonPaletteProvider = playButtonPaletteProvider,
                        prevKickPx = prevKickPx,
                        nextKickPx = nextKickPx,
                        compact = isCompactLandscape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .swipeExitFade(swipePager, swipePage)
                    )
                }
            }
        } else {
            // 与 NativeWallFocusLayout 竖屏规格对齐：heroSize = min(W*0.60, H*0.35)，上方为唱片留足呼吸与投影余量
            val coverSide = minOf(boxMaxW * 0.60f, boxMaxH * 0.35f, 268.dp).coerceAtLeast(148.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(coverSide + 34.dp)
                        .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) },
                    contentAlignment = Alignment.Center
                ) {
                    WallSwitchCoverSlot(
                        song = song,
                        side = coverSide,
                        useRealCover = useRealCover,
                        activeWallColor = activeWallColor,
                        discAngle = { discAngleState.floatValue },
                        coverScale = coverScale,
                        activeSwitch = activeSwitch,
                        switchProgress = switchProgress,
                        hiResBadgeEnabled = hiResBadgeEnabled,
                        hiResBadgeSize = hiResBadgeSize,
                        onOpenCover = onOpenCover,
                        prefs = prefs
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(lyricsScrollGuard)
                ) {
                    InlineLyrics(
                        song = song,
                        positionMs = safePos,
                        prefs = prefs,
                        onSeek = onSeek,
                        isPlaying = isPlaying,
                        compact = true,
                        seekEnabled = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                WallPlayerControlCluster(
                    song = song,
                    streamQuality = streamQuality,
                    isFavorite = isFavorite,
                    showDownloadButton = showDownloadButton,
                    isDownloading = isDownloading,
                    isDownloaded = isDownloaded,
                    onDownload = onDownload,
                    onToggleFavorite = onToggleFavorite,
                    frac = frac,
                    positionMs = safePos,
                    durationMs = safeDuration,
                    onSeek = onSeek,
                    playbackSpeed = playbackSpeed,
                    onSetSpeed = onSetSpeed,
                    abLoopStartMs = abLoopStartMs,
                    abLoopEndMs = abLoopEndMs,
                    onToggleAbLoop = onToggleAbLoop,
                    controlsPlaying = controlsPlaying,
                    playMode = playMode,
                    onCyclePlayMode = onCyclePlayMode,
                    onPrev = onPrev,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onOpenQueue = onOpenQueue,
                    playButtonPaletteProvider = playButtonPaletteProvider,
                    prevKickPx = prevKickPx,
                    nextKickPx = nextKickPx,
                    compact = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .swipeExitFade(swipePager, swipePage)
                )
                Spacer(Modifier.height(14.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun WallSwitchCoverSlot(
    song: Song,
    side: Dp,
    useRealCover: Boolean,
    activeWallColor: () -> Color,
    discAngle: () -> Float,
    coverScale: State<Float>,
    activeSwitch: ButtonSwitchState?,
    switchProgress: () -> Float,
    hiResBadgeEnabled: Boolean,
    hiResBadgeSize: HiResBadgeSize,
    onOpenCover: () -> Unit,
    prefs: Prefs
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val spec = remember(song.id, song.title, song.artist, song.album) { coverWallSpecFor(song) }
    val coverKey = remember(song.id, song.title, song.artist) { OnlineCover.keyOf(song.title, song.artist) }
    val coverRevision = ArtworkLoader.coverRevision(coverKey)
    val selectedRevision = ArtworkLoader.selectedRevision(song.id)
    val resetVersion = ArtworkLoader.cacheResetVersion
    var displayBitmap by remember(song.id, useRealCover, resetVersion) {
        val initial = if (useRealCover) {
            (ArtworkLoader.peekSelected(song.albumId, 900, song.uri, song.id)
                ?: ArtworkLoader.peekSelected(song.albumId, 256, song.uri, song.id)
                ?: ArtworkLoader.online(song.id)
                ?: ArtworkLoader.peekAny(song.albumId, 900, song.uri, coverKey)
                ?: LaunchCoverSnapshot.peekCover(context, song.id))?.asAndroidBitmap()
                ?: peekProceduralBitmap(spec)
        } else {
            peekProceduralBitmap(spec)
        }
        mutableStateOf(initial)
    }
    val online = ArtworkLoader.online(song.id)
    LaunchedEffect(song.id, useRealCover, coverRevision, selectedRevision, resetVersion) {
        if (!useRealCover) {
            displayBitmap = loadProceduralBitmap(spec)
            return@LaunchedEffect
        }
        val legacyPinned = song.id > 0L && !prefs.pinnedCoverUrl(song.id).isNullOrBlank()
        val selected = ArtworkLoader.loadSelected(context, song.albumId, 900, song.uri, song.id, coverKey, legacyPinned)
        if (selected != null) {
            displayBitmap = selected.asAndroidBitmap()
            return@LaunchedEffect
        }
        val onlineNow = ArtworkLoader.online(song.id)
        if (onlineNow != null) {
            displayBitmap = onlineNow.asAndroidBitmap()
            return@LaunchedEffect
        }
        val real = ArtworkLoader.load(context, song.albumId, 900, song.uri, coverKey)
        displayBitmap = real?.asAndroidBitmap() ?: loadProceduralBitmap(spec)
    }
    val activeBitmap = if (useRealCover && online != null) {
        online.asAndroidBitmap()
    } else {
        displayBitmap
    }

    val fromSong = activeSwitch?.fromSong
    val fromSpec = remember(fromSong?.id, fromSong?.title, fromSong?.artist, fromSong?.album) {
        fromSong?.let(::coverWallSpecFor)
    }
    val fromBitmap = remember(activeSwitch?.seq, useRealCover, fromSpec) {
        if (fromSpec != null) {
            if (useRealCover) {
                activeSwitch?.fromBitmap?.asAndroidBitmap() ?: peekProceduralBitmap(fromSpec)
            } else {
                peekProceduralBitmap(fromSpec)
            }
        } else null
    }

    val painter = remember(density) { sharedWallCardPainter(density) }
    val pose = remember { WallPose() }
    val fromPose = remember { WallPose() }
    val cornerPx = 10f * density

    Box(Modifier.fillMaxSize()) {
        // 整块无裁切 Canvas，彻底避免投影与左右露边被容器边界切出四角黑边/多余阴影
        Canvas(
            Modifier
                .fillMaxSize()
                .onCoverGesture(onLongPress = onOpenCover, onTap = {})
        ) {
            val sidePx = side.toPx()
            val baseX = (size.width - sidePx) * 0.5f - sidePx * 0.035f
            val baseY = (size.height - sidePx) * 0.5f
            val s = coverScale.value
            val curDiscAngle = discAngle()
            val wallColor = activeWallColor()
            val resolvedLabelArgb = if (wallColor != Color.Unspecified) wallColor.toArgb() else null

            drawIntoCanvas { canvas ->
                if (activeSwitch == null || fromSpec == null) {
                    pose.x = baseX
                    pose.y = baseY
                    pose.size = sidePx
                    pose.rx = 0f
                    pose.ry = 0f
                    pose.z = 0f
                    pose.scale = s
                    pose.shade = 0f
                    pose.alpha = 1f
                    painter.project(pose)
                    painter.draw(
                        canvas = canvas.nativeCanvas,
                        bitmap = activeBitmap,
                        spec = spec,
                        pose = pose,
                        corner = cornerPx,
                        gx = 0f,
                        gy = 0f,
                        glow = 0f,
                        elevation = 1.25f,
                        vinyl = 1f,
                        discAngle = curDiscAngle,
                        labelColorOverride = resolvedLabelArgb
                    )
                } else {
                    val p = switchProgress().coerceIn(0f, 1f)
                    val d = activeSwitch.dir.toFloat()

                    // 1) 退场旧唱片：黑胶先行缩回封套，封套顺势后退淡出（参考封面墙 3D 景深体感）
                    val outVinyl = (1f - (p / 0.46f).coerceIn(0f, 1f))
                    val outFadeT = (p / 0.62f).coerceIn(0f, 1f)
                    val outAlpha = 1f - outFadeT * outFadeT * (3f - 2f * outFadeT)
                    if (outAlpha > 0.005f) {
                        fromPose.x = baseX + (if (activeSwitch.trigger == SwitchTrigger.MANUAL_STEP) -d * 34.dp.toPx() * p else 0f)
                        fromPose.y = baseY + (if (activeSwitch.trigger == SwitchTrigger.QUEUE_PICK) -8.dp.toPx() * p else 0f)
                        fromPose.size = sidePx
                        fromPose.scale = s * (1f - 0.12f * p)
                        fromPose.z = -90f * density * p
                        fromPose.rx = 0f
                        fromPose.ry = if (activeSwitch.trigger == SwitchTrigger.MANUAL_STEP) -d * 14f * p else 0f
                        fromPose.shade = 0.25f * p
                        fromPose.alpha = outAlpha
                        painter.project(fromPose)
                        painter.draw(
                            canvas = canvas.nativeCanvas,
                            bitmap = fromBitmap,
                            spec = fromSpec,
                            pose = fromPose,
                            corner = cornerPx,
                            gx = 0f,
                            gy = 0f,
                            glow = 0f,
                            elevation = 1.1f * outAlpha,
                            vinyl = outVinyl,
                            discAngle = curDiscAngle,
                            labelColorOverride = null
                        )
                    }

                    // 2) 入场新唱片：封套浮起聚焦，落定过程中右侧黑胶盘柔和滑出并接续旋转
                    val inEase = 1f - (1f - p).let { it * it * it }
                    val inFadeT = ((p - 0.12f) / 0.56f).coerceIn(0f, 1f)
                    val inAlpha = inFadeT * inFadeT * (3f - 2f * inFadeT)
                    val inVinylT = ((p - 0.24f) / 0.76f).coerceIn(0f, 1f)
                    val inVinyl = 1f - (1f - inVinylT).let { it * it * it }
                    if (inAlpha > 0.005f) {
                        pose.x = baseX + (if (activeSwitch.trigger == SwitchTrigger.MANUAL_STEP) d * 34.dp.toPx() * (1f - inEase) else 0f)
                        pose.y = baseY + (if (activeSwitch.trigger == SwitchTrigger.QUEUE_PICK) 14.dp.toPx() * (1f - inEase) else 0f)
                        pose.size = sidePx
                        pose.scale = s * (0.90f + 0.10f * inEase)
                        pose.z = -70f * density * (1f - inEase)
                        pose.rx = 0f
                        pose.ry = if (activeSwitch.trigger == SwitchTrigger.MANUAL_STEP) d * 12f * (1f - inEase) else 0f
                        pose.shade = 0f
                        pose.alpha = inAlpha
                        painter.project(pose)
                        painter.draw(
                            canvas = canvas.nativeCanvas,
                            bitmap = activeBitmap,
                            spec = spec,
                            pose = pose,
                            corner = cornerPx,
                            gx = 0f,
                            gy = 0f,
                            glow = 0f,
                            elevation = 1.25f * inEase,
                            vinyl = inVinyl,
                            discAngle = curDiscAngle,
                            labelColorOverride = resolvedLabelArgb
                        )
                    }
                }
            }
        }

        if (hiResBadgeEnabled) {
            val badgeVisible = HiResQuality.peekLocalBadge(song) == true
            if (badgeVisible) {
                HiResGoldBadge(
                    badgeSize = hiResBadgeSize,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 4.dp)
                )
            } else if (song.isLocal) {
                LaunchedEffect(song.id) {
                    withContext(Dispatchers.IO) {
                        HiResQuality.probeLocalBadge(context, song)
                    }
                }
            }
        }
    }
}

@Composable
private fun WallPlayerControlCluster(
    song: Song,
    streamQuality: String?,
    isFavorite: Boolean,
    showDownloadButton: Boolean,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    frac: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    playbackSpeed: Float,
    onSetSpeed: (Float) -> Unit,
    abLoopStartMs: Long,
    abLoopEndMs: Long,
    onToggleAbLoop: () -> Unit,
    controlsPlaying: Boolean,
    playMode: PlayMode,
    onCyclePlayMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
    playButtonPaletteProvider: () -> PlayButtonPalette,
    prevKickPx: () -> Float,
    nextKickPx: () -> Float,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val latestDuration by rememberUpdatedState(durationMs)
    val latestOnSeek by rememberUpdatedState(onSeek)
    var dragFraction by remember(song.id) { mutableStateOf<Float?>(null) }
    val displayedProgress = dragFraction ?: frac
    val displayedPosition = dragFraction?.let {
        (it.toDouble() * durationMs).toLong().coerceIn(0L, durationMs)
    } ?: positionMs

    Column(modifier = modifier) {
        // 歌名 + 歌手/专辑（左对齐，复刻封面墙聚焦排版） + 右侧轻量下载/收藏图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = Triple(song.title, song.artist, song.album),
                transitionSpec = {
                    fadeIn(tween(260, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(150))
                },
                label = "wallMetaTitle",
                modifier = Modifier.weight(1f)
            ) { (title, artist, album) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = title,
                        color = WALL_TEXT_PRIMARY,
                        fontSize = if (compact) 18.sp else 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    val sub = buildString {
                        if (artist.isNotBlank()) append(artist)
                        if (album.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(album)
                        }
                        if (!streamQuality.isNullOrBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(streamQuality)
                        }
                    }.ifBlank { "未知艺术家" }
                    Text(
                        text = sub,
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = if (compact) 12.sp else 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AnimatedVisibility(
                visible = showDownloadButton,
                enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180))
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isDownloading && !isDownloaded) {
                            hapticTick(context)
                            onDownload()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = skin.accent,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = SonanceIcons.Download,
                            contentDescription = if (isDownloaded) "已下载" else "下载",
                            tint = if (isDownloaded) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable {
                        hapticTick(context)
                        onToggleFavorite()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                    contentDescription = "收藏",
                    tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))

        // 封面墙极简白线进度条（支持拖拽与 A-B 区间点标记）
        val canSeek = durationMs > 0L
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 22.dp else 28.dp)
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(displayedProgress, 0f..1f)
                    if (!canSeek) disabled()
                    setProgress { fraction ->
                        if (canSeek && fraction.isFinite()) {
                            latestOnSeek(
                                (fraction.coerceIn(0f, 1f).toDouble() * latestDuration).toLong()
                                    .coerceIn(0L, latestDuration)
                            )
                            true
                        } else false
                    }
                }
                .pointerInput(song.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun fractionOf(x: Float) = (x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        val seeking = latestDuration > 0L
                        if (seeking) dragFraction = fractionOf(down.position.x)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if (seeking) dragFraction = fractionOf(change.position.x)
                                val released = change.changedToUpIgnoreConsumed()
                                change.consume()
                                if (released) {
                                    val dur = latestDuration
                                    if (seeking && dur > 0L) {
                                        latestOnSeek(
                                            ((dragFraction ?: 0f).toDouble() * dur).toLong().coerceIn(0L, dur)
                                        )
                                    }
                                    break
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            dragFraction = null
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val trackWidth = 4.dp.toPx()
                val y = size.height / 2f
                drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), trackWidth, StrokeCap.Round)
                if (durationMs > 0L && abLoopStartMs >= 0L) {
                    val aFrac = (abLoopStartMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val ax = size.width * aFrac
                    if (abLoopEndMs > abLoopStartMs) {
                        val bFrac = (abLoopEndMs.toFloat() / durationMs.toFloat()).coerceIn(aFrac, 1f)
                        drawLine(
                            skin.accent.copy(alpha = 0.72f),
                            Offset(ax, y),
                            Offset(size.width * bFrac, y),
                            trackWidth,
                            StrokeCap.Round
                        )
                    } else {
                        drawCircle(skin.accent, 3.5.dp.toPx(), Offset(ax, y))
                    }
                }
                if (displayedProgress > 0f) {
                    drawLine(Color.White, Offset(0f, y), Offset(size.width * displayedProgress, y), trackWidth, StrokeCap.Round)
                }
                if (dragFraction != null) {
                    val radius = 5.dp.toPx()
                    val x = (size.width * displayedProgress).coerceIn(
                        radius.coerceAtMost(size.width / 2f),
                        (size.width - radius).coerceAtLeast(size.width / 2f)
                    )
                    drawCircle(Color.White, radius, Offset(x, y))
                }
            }
        }

        // 时长 + 倍速 / A-B 轻胶囊
        val abHasA = abLoopStartMs >= 0L
        val abLooping = abHasA && abLoopEndMs > abLoopStartMs
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(displayedPosition),
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val speedChanged = kotlin.math.abs(playbackSpeed - 1f) > 0.01f
                Text(
                    text = formatSpeed(playbackSpeed),
                    fontSize = 11.sp,
                    fontWeight = if (speedChanged) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (speedChanged) skin.accent else Color.White.copy(alpha = 0.52f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (speedChanged) Modifier.background(skin.accent.copy(alpha = 0.16f)) else Modifier
                        )
                        .clickable {
                            hapticTick(context)
                            onSetSpeed(nextSpeed(playbackSpeed))
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Text(
                    text = when {
                        abLooping -> "A-B"
                        abHasA -> "A→"
                        else -> "A-B"
                    },
                    fontSize = 11.sp,
                    fontWeight = if (abHasA) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (abHasA) skin.accent else Color.White.copy(alpha = 0.52f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (abHasA) {
                                Modifier.background(skin.accent.copy(alpha = if (abLooping) 0.20f else 0.12f))
                            } else Modifier
                        )
                        .clickable {
                            hapticTick(context)
                            onToggleAbLoop()
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Text(
                text = formatDuration(durationMs),
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))

        // 5 键统一纯透播控：播放模式 · 上一首 · 水滴播放键 · 下一首 · 播放队列
        TransportControls(
            isPlaying = controlsPlaying,
            playMode = playMode,
            onCyclePlayMode = onCyclePlayMode,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onOpenQueue = onOpenQueue,
            ctrlSize = if (compact) 38.dp else 44.dp,
            ctrlIcon = if (compact) 19.dp else 22.dp,
            playSize = if (compact) 54.dp else 62.dp,
            playIcon = if (compact) 23.dp else 27.dp,
            gap = if (compact) 10.dp else 14.dp,
            playButtonPaletteProvider = playButtonPaletteProvider,
            prevTranslationX = prevKickPx,
            nextTranslationX = nextKickPx,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
