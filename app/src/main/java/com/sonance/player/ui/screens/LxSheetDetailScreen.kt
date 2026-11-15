package com.sonance.player.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.lx.LxPlatform
import com.sonance.player.data.lx.LxSheetDetailTarget
import com.sonance.player.data.remoteSongId
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.PlaceholderCover
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 洛雪模式歌单 / 排行榜详情页（独立页面）。
 */
@Composable
fun LxSheetDetailScreen(
    target: LxSheetDetailTarget,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val platform = remember(target.platform) { LxPlatform.byId(target.platform) }
    val sourceId = remember(target.platform) { LxPlatform.sourceIdFor(target.platform) }
    val sourceLabel = platform?.shortLabel ?: target.platform

    DisposableEffect(target.id, target.platform, target.isBoard) {
        vm.openLxSheetDetail(target)
        onDispose { vm.closeLxSheetDetail() }
    }

    var actionTrack by remember { mutableStateOf<RemoteTrack?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部封面信息 + 播放全部 / 全部入队操作栏
        LxSheetDetailHeader(
            target = target,
            platformLabel = platform?.displayName ?: target.platform,
            trackCount = vm.lxDetailTracks.size,
            canAct = vm.lxDetailTracks.any { it.playable } && !vm.lxDetailLoading,
            onPlayAll = { vm.playLxAll(vm.lxDetailTracks, target.platform) },
            onEnqueueAll = { vm.enqueueLxTracks(vm.lxDetailTracks, target.platform) }
        )

        HorizontalDivider(color = skin.border)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.lxDetailLoading -> LoadingState(
                    title = if (target.isBoard) "正在加载榜单曲目…" else "正在加载歌单曲目…"
                )
                vm.lxDetailError != null -> EmptyState(
                    icon = SonanceIcons.Album,
                    title = vm.lxDetailError ?: "曲目加载失败",
                    subtitle = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { vm.openLxSheetDetail(target) }
                )
                vm.lxDetailTracks.isEmpty() -> EmptyState(
                    icon = SonanceIcons.Music,
                    title = "暂无曲目",
                    subtitle = "该歌单或榜单暂时没有返回歌曲"
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = vm.lxDetailTracks,
                        key = { index, t -> "${index}_${t.remoteId}" }
                    ) { index, track ->
                        val songId = remoteSongId(sourceId, track.remoteId)
                        OnlineTrackRow(
                            track = track,
                            thumbUrl = track.coverUrl,
                            sourceLabel = sourceLabel,
                            downloaded = vm.isLxTrackDownloaded(target.platform, track.remoteId),
                            isCurrent = playerState.mediaId == songId,
                            isPlaying = playerState.isPlaying,
                            onLongClick = {
                                hapticTick(context)
                                actionTrack = track
                            },
                            onClick = { vm.playLxTrackAt(vm.lxDetailTracks, index, target.platform) }
                        )
                    }
                }
            }
        }
    }

    val pendingTrack = actionTrack
    if (pendingTrack != null) {
        val index = remember(pendingTrack, vm.lxDetailTracks) {
            vm.lxDetailTracks.indexOfFirst { it.remoteId == pendingTrack.remoteId }.coerceAtLeast(0)
        }
        LxTrackActionDialog(
            track = pendingTrack,
            downloaded = vm.isLxTrackDownloaded(target.platform, pendingTrack.remoteId),
            onPlayNow = {
                actionTrack = null
                vm.playLxTrackAt(vm.lxDetailTracks, index, target.platform)
            },
            onEnqueue = {
                actionTrack = null
                vm.enqueueLxTracks(listOf(pendingTrack), target.platform)
            },
            onDownload = {
                actionTrack = null
                vm.downloadSingleLxTrack(pendingTrack, target.platform)
            },
            onDismiss = { actionTrack = null }
        )
    }
}

@Composable
private fun LxSheetDetailHeader(
    target: LxSheetDetailTarget,
    platformLabel: String,
    trackCount: Int,
    canAct: Boolean,
    onPlayAll: () -> Unit,
    onEnqueueAll: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(target.coverUrl, 400)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(skin.surface3)
                    .border(1.dp, skin.border, RoundedCornerShape(10.dp))
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = target.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlaceholderCover(
                        seed = "lx_${target.platform}_${target.id}",
                        showMark = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(skin.accentGlow)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "$platformLabel · ${if (target.isBoard) "排行榜" else "歌单"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.accent
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    target.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (target.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        target.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trackCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "共 $trackCount 首 · 长按单曲可入队或下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                label = "播放全部",
                enabled = canAct,
                compact = true,
                onClick = onPlayAll
            )
            PillButton(
                label = "全部入队",
                enabled = canAct,
                filled = false,
                compact = true,
                onClick = onEnqueueAll
            )
        }
    }
}
