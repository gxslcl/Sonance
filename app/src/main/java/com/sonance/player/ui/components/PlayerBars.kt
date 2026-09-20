package com.sonance.player.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.playback.RepeatMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/* ============================================================
 * 传输控制（上一首 / 播放 / 下一首 / 随机 / 循环）
 * ============================================================ */

@Composable
fun TransportControls(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    ctrlSize: Dp = 36.dp,
    ctrlIcon: Dp = 18.dp,
    playSize: Dp = 52.dp,
    playIcon: Dp = 22.dp,
    gap: Dp = 6.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
        CtrlButton(
            icon = SonanceIcons.Shuffle,
            contentDescription = "随机播放",
            onClick = onShuffle,
            size = ctrlSize,
            iconSize = ctrlIcon,
            active = shuffle
        )
        CtrlButton(
            icon = SonanceIcons.Prev,
            contentDescription = "上一首",
            onClick = onPrev,
            size = ctrlSize,
            iconSize = ctrlIcon
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onPlayPause,
            size = playSize,
            iconSize = playIcon
        )
        CtrlButton(
            icon = SonanceIcons.Next,
            contentDescription = "下一首",
            onClick = onNext,
            size = ctrlSize,
            iconSize = ctrlIcon
        )
        CtrlButton(
            icon = if (repeat == RepeatMode.ONE) SonanceIcons.RepeatOne else SonanceIcons.Repeat,
            contentDescription = "循环模式",
            onClick = onRepeat,
            size = ctrlSize,
            iconSize = ctrlIcon,
            active = repeat != RepeatMode.OFF
        )
    }
}

private fun progressFraction(state: PlayerUiState): Float {
    if (state.durationMs <= 0L) return 0f
    return (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
}

/* ============================================================
 * 迷你播放条（手机 / 中等宽度）
 * ============================================================ */

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    song: Song?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }

    val skin = LocalSkin.current
    val frac = progressFraction(state)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(skin.surface2)
    ) {
        // 顶部进度线
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(skin.surface3)
        ) {
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(2.dp)
                        .background(skin.accent)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CoverArt(
                albumId = state.albumId,
                seed = state.title + state.artist,
                modifier = Modifier.size(42.dp),
                cornerRadius = 7.dp,
                sizePx = 200,
                sourceUri = coverUri,
                songId = state.mediaId
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { song?.title ?: "未在播放" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.artist.ifBlank { song?.artist ?: "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CtrlButton(
                icon = SonanceIcons.Prev,
                contentDescription = "上一首",
                onClick = onPrev,
                size = 36.dp,
                iconSize = 17.dp
            )
            PlayPauseButton(
                isPlaying = state.isPlaying,
                onClick = onPlayPause,
                size = 40.dp,
                iconSize = 18.dp
            )
            CtrlButton(
                icon = SonanceIcons.Next,
                contentDescription = "下一首",
                onClick = onNext,
                size = 36.dp,
                iconSize = 17.dp
            )
        }
    }
}

/* ============================================================
 * 底部播放条（平板 / 横屏宽屏）
 * ============================================================ */

@Composable
fun PlayerBar(
    state: PlayerUiState,
    song: Song?,
    onOpen: () -> Unit,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 78.dp
) {
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }

    val skin = LocalSkin.current
    val frac = progressFraction(state)
    val duration = if (state.durationMs > 0) state.durationMs else (song?.durationMs ?: 0L)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(skin.surface2)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .width(210.dp)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverArt(
                albumId = state.albumId,
                seed = state.title + state.artist,
                modifier = Modifier.size(46.dp),
                cornerRadius = 7.dp,
                sizePx = 220,
                sourceUri = coverUri,
                songId = state.mediaId
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "未在播放" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.artist.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TransportControls(
                isPlaying = state.isPlaying,
                shuffle = state.shuffle,
                repeat = state.repeat,
                onShuffle = onShuffle,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onRepeat = onRepeat,
                ctrlSize = 30.dp,
                ctrlIcon = 15.dp,
                playSize = 36.dp,
                playIcon = 16.dp,
                gap = 4.dp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TimeLabel(formatDuration(state.positionMs))
                SonanceSlider(
                    value = frac,
                    onValueChange = { f -> onSeek((f * duration).toLong()) },
                    modifier = Modifier.weight(1f),
                    trackHeight = 4.dp,
                    thumbSize = 11.dp
                )
                TimeLabel(formatDuration(duration))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VolumeSlider(
                volume = state.volume,
                onVolumeChange = onVolume,
                modifier = Modifier.width(118.dp)
            )
            IconAction(
                icon = SonanceIcons.Queue,
                contentDescription = "播放队列",
                onClick = onOpenQueue,
                iconSize = 18.dp,
                tint = skin.textMuted
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalSkin.current.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(38.dp)
    )
}

/* ============================================================
 * 正在播放侧栏（平板 / 桌面宽屏）
 * ============================================================ */

@Composable
fun NowPlayingPanel(
    state: PlayerUiState,
    song: Song?,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onFavorite: () -> Unit,
    isFavorite: Boolean,
    modifier: Modifier = Modifier
) {
    // 封面来源：state 里是 String，这里解析一次复用，避免每次重组都 new 一个 Uri
    val coverUri = remember(state.sourceUri) {
        state.sourceUri?.let { android.net.Uri.parse(it) }
    }

    val skin = LocalSkin.current
    val frac = progressFraction(state)
    val duration = if (state.durationMs > 0) state.durationMs else (song?.durationMs ?: 0L)
    // 刻意不用 by 委托：把动画值读取推迟到 layer 阶段，
    // 否则这个常驻面板会每帧重组一次
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val dotAlpha = pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "dot"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(skin.surface)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (state.isPlaying) skin.accent else skin.textMuted)
                    .graphicsLayer { alpha = if (state.isPlaying) dotAlpha.value else 1f }
            )
            Text(
                text = if (state.isPlaying) "正在播放" else "已暂停",
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent
            )
        }

        // 封面自适应剩余高度，避免小高度窗口被挤压
        Box(
            Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                CoverArt(
                    albumId = state.albumId,
                    seed = state.title + state.artist,
                    modifier = Modifier
                        .size(side)
                        .clip(RoundedCornerShape(14.dp)),
                    cornerRadius = 14.dp,
                    sizePx = 800,
                    sourceUri = coverUri,
                    songId = state.mediaId
                )
            }
        }

        Text(
            text = state.title.ifBlank { "未在播放" },
            style = MaterialTheme.typography.headlineSmall,
            color = skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.artist.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        SonanceSlider(
            value = frac,
            onValueChange = { f -> onSeek((f * duration).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 4.dp,
            thumbSize = 12.dp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }

        Spacer(Modifier.height(16.dp))

        TransportControls(
            isPlaying = state.isPlaying,
            shuffle = state.shuffle,
            repeat = state.repeat,
            onShuffle = onShuffle,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onRepeat = onRepeat
        )

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconAction(
                icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                contentDescription = "收藏",
                onClick = onFavorite,
                iconSize = 18.dp,
                tint = if (isFavorite) skin.accent else skin.textMuted
            )
            VolumeSlider(
                volume = state.volume,
                onVolumeChange = onVolume,
                modifier = Modifier.weight(1f)
            )
            IconAction(
                icon = SonanceIcons.Queue,
                contentDescription = "播放队列",
                onClick = onOpenQueue,
                iconSize = 18.dp,
                tint = skin.textMuted
            )
        }
    }
}

private fun Modifier.squareAspect(): Modifier = this.then(Modifier.aspectRatio(1f))
