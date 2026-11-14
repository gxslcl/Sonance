package com.sonance.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.formatDuration
import com.sonance.player.data.remoteSongId
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.PlaceholderCover
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 在线歌单详情（独立页面）。
 *
 * ## 为什么曲目要现拉
 *
 * 歌单搜索一次回来几十个歌单，每个都顺手拉曲目就是几十次请求。列表页只用得上
 * 「名字 / 封面 / 曲目数」，所以曲目推迟到这里、按需拉一次（见 [OnlineSource.playlistTracks]）。
 *
 * ## 生命周期
 *
 * 进页面 [SonanceViewModel.openPlaylist] 拉曲目，离开 [SonanceViewModel.closePlaylist]
 * 清状态并取消在途请求。用 `DisposableEffect(playlist.remoteId)` 而不是 `LaunchedEffect`：
 * 离开时（含返回键、切 tab）必须收尾，`LaunchedEffect` 的取消回调不适合做这件事。
 *
 * ## 一键下载
 *
 * 不在这一页写下载循环 —— 那属于 ViewModel（要跨页面存活、要遵守 Wi-Fi 限制、
 * 要能取消）。这里只负责「确认弹窗 → 调一下」和「把进度画出来」。
 */
@Composable
fun OnlinePlaylistScreen(
    playlist: RemotePlaylist,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val src = vm.onlineSource
    if (src == null) {
        EmptyState(
            icon = SonanceIcons.Info,
            title = "音源不可用",
            subtitle = "在线音乐可能已关闭，回到在线页重新开启。",
            modifier = modifier
        )
        return
    }

    DisposableEffect(playlist.remoteId) {
        vm.openPlaylist(playlist)
        onDispose { vm.closePlaylist() }
    }

    // VM 还没切到这个歌单时也算「加载中」—— 否则第一帧会闪一下「这个歌单是空的」。
    // openPlaylist 是同步置位的，正常情况下这一帧只出现在极短的过渡里。
    val ready = vm.playlistDetail?.remoteId == playlist.remoteId
    val loading = !ready || vm.playlistLoading
    val tracks = if (ready) vm.playlistTracks else emptyList()

    var selectionMode by remember(playlist.remoteId) { mutableStateOf(false) }
    var selectedIds by remember(playlist.remoteId) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDownloadTracks by remember(playlist.remoteId) { mutableStateOf<List<RemoteTrack>?>(null) }

    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }
    BackHandler(enabled = selectionMode) { exitSelection() }

    val selectedTracks = remember(tracks, selectedIds) {
        if (selectedIds.isEmpty()) emptyList()
        else tracks.filter { it.remoteId in selectedIds }
    }

    Column(modifier.fillMaxSize()) {
        PlaylistHeader(
            playlist = playlist,
            vm = vm,
            loadedCount = if (ready) tracks.size else null,
            totalMs = if (ready) tracks.sumOf { it.durationMs } else 0L,
            onPlayAll = { vm.playPlaylist() },
            onDownload = { pendingDownloadTracks = tracks }
        )

        // 只有"这轮下载属于本歌单"才显示进度条：下载跨页面存活，用户可能已经翻到
        // 别的歌单，把上一个歌单的进度摆在这里会让人以为在下这一个
        if (vm.playlistDownloadActive && vm.playlistDownloadOwner == playlist.remoteId) {
            DownloadProgress(
                done = vm.playlistDownloadDone,
                total = vm.playlistDownloadTotal,
                saved = vm.playlistDownloadSaved,
                skipped = vm.playlistDownloadSkipped,
                failed = vm.playlistDownloadFailed,
                onCancel = { vm.cancelPlaylistDownload() }
            )
        }

        // 歌曲列表顶部同一行操作栏：常规态左上角摆「多选」，进入多选后同一行右侧出现「下载」与「加入播放队列」（未选时禁用）
        if (ready && tracks.isNotEmpty() && !loading) {
            val allSelected = selectedIds.size >= tracks.size
            val selectedDownloadable = selectedTracks.count { it.playable && !vm.isDownloaded(it) }
            TrackListToolbar(
                selectionMode = selectionMode,
                selectedCount = selectedTracks.size,
                totalCount = tracks.size,
                downloadableCount = selectedDownloadable,
                allSelected = allSelected,
                downloading = vm.playlistDownloadActive,
                onEnterSelect = {
                    selectedIds = emptySet()
                    selectionMode = true
                },
                onToggleSelectAll = {
                    selectedIds = if (allSelected) emptySet() else tracks.mapTo(HashSet()) { it.remoteId }
                },
                onDownloadSelected = {
                    pendingDownloadTracks = selectedTracks
                },
                onAddToQueue = {
                    vm.enqueueOnlineTracks(selectedTracks)
                    exitSelection()
                },
                onCancel = exitSelection
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> LoadingState(
                    title = "正在读取歌单…",
                    subtitle = "大歌单要等几秒，音源要分批取歌曲信息"
                )

                vm.playlistFailed -> EmptyState(
                    icon = SonanceIcons.Refresh,
                    title = "歌单读取失败",
                    subtitle = "检查一下网络，或者稍后再试。",
                    actionLabel = "重试",
                    onAction = { vm.retryPlaylistTracks() }
                )

                tracks.isEmpty() -> EmptyState(
                    icon = SonanceIcons.Music,
                    title = "这个歌单是空的",
                    subtitle = "音源没有返回任何曲目。"
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = tracks,
                        key = { _, t -> t.remoteId }
                    ) { index, track ->
                        val checked = track.remoteId in selectedIds
                        OnlineTrackRow(
                            track = track,
                            thumbUrl = vm.onlineThumbUrl(track, ROW_COVER_PX),
                            sourceLabel = src.label,
                            downloaded = vm.isDownloaded(track),
                            isCurrent = playerState.mediaId == remoteSongId(src.id, track.remoteId),
                            isPlaying = playerState.isPlaying,
                            selectionMode = selectionMode,
                            selected = checked,
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(track.remoteId)
                                }
                            },
                            // 多选模式下点击切换选中态，常规模式按 playlistTracks 原始下标点播
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (checked) {
                                        selectedIds - track.remoteId
                                    } else {
                                        selectedIds + track.remoteId
                                    }
                                } else {
                                    vm.playPlaylistTrack(index)
                                }
                            }
                        )
                    }

                    item {
                        Text(
                            text = "标 VIP 的歌需要会员，取流接口拿不到播放地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }

    pendingDownloadTracks?.let { targetTracks ->
        DownloadConfirmDialog(
            playlist = playlist,
            tracks = targetTracks,
            isPartial = selectionMode && targetTracks.size < tracks.size,
            vm = vm,
            onDismiss = { pendingDownloadTracks = null },
            onConfirm = {
                pendingDownloadTracks = null
                if (selectionMode) exitSelection()
                vm.downloadPlaylistTracks(targetTracks)
            }
        )
    }
}

@Composable
private fun TrackListToolbar(
    selectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    downloadableCount: Int,
    allSelected: Boolean,
    downloading: Boolean,
    onEnterSelect: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDownloadSelected: () -> Unit,
    onAddToQueue: () -> Unit,
    onCancel: () -> Unit
) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (!selectionMode) {
                SmallToolbarButton(
                    label = "多选",
                    icon = SonanceIcons.Check,
                    onClick = onEnterSelect
                )
                Text(
                    text = "共 $totalCount 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmallToolbarButton(
                        label = if (allSelected) "全不选" else "全选",
                        highlighted = allSelected,
                        onClick = onToggleSelectAll
                    )
                    SmallToolbarButton(
                        label = "取消",
                        onClick = onCancel
                    )
                    if (selectedCount > 0) {
                        Text(
                            text = "已选 $selectedCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.accent,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val canDownload = selectedCount > 0 && downloadableCount > 0 && !downloading
                    SmallToolbarButton(
                        label = if (downloading) "下载中" else if (canDownload) "下载 $downloadableCount" else "下载",
                        enabled = canDownload,
                        onClick = onDownloadSelected
                    )
                    SmallToolbarButton(
                        label = "加入队列",
                        enabled = selectedCount > 0,
                        highlighted = selectedCount > 0,
                        onClick = onAddToQueue
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = skin.border
        )
    }
}

@Composable
private fun SmallToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        highlighted && enabled -> skin.accent
        else -> skin.surface2.copy(alpha = 0.72f)
    }
    val fg = when {
        highlighted && enabled -> skin.onAccent
        else -> skin.text
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(0.8.dp, if (highlighted && enabled) skin.accent else skin.border, shape)
            .alpha(if (enabled) 1f else 0.36f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * 头部：封面 + 标题 + 创建者/曲目数 + 「播放全部 / 下载全部」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistHeader(
    playlist: RemotePlaylist,
    vm: SonanceViewModel,
    loadedCount: Int?,
    totalMs: Long,
    onPlayAll: () -> Unit,
    onDownload: () -> Unit
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current
    val cover = rememberRemoteCover(vm.playlistThumbUrl(playlist, HEADER_COVER_PX), HEADER_COVER_PX)
    val count = loadedCount ?: playlist.trackCount

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (dense) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(if (dense) 88.dp else 108.dp)) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                PlaceholderCover(
                    seed = playlist.name,
                    showMark = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (playlist.creatorName.isNotBlank()) append("${playlist.creatorName} · ")
                    append("$count 首")
                    if (totalMs > 0L) append(" · ${formatDuration(totalMs)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = skin.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(if (dense) 8.dp else 10.dp))
            val isLikedPlaylist = playlist.remoteId == vm.onlineLikedPlaylist?.remoteId &&
                vm.neteaseAccount != null
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlaylistActionButton(label = "播放全部", onClick = onPlayAll, filled = true)

                if (isLikedPlaylist) {
                    PlaylistActionButton(
                        label = if (vm.intelligenceLoading) "开启中" else "心动模式",
                        onClick = { if (!vm.intelligenceLoading) vm.playIntelligenceMode() },
                        filled = false
                    )
                }

                // 曲目还没到时先不摆下载按钮：目标数都不知道，摆出来会误导
                if (loadedCount != null) {
                    val left = vm.playlistDownloadableCount
                    when {
                        vm.playlistDownloadActive && vm.playlistDownloadOwner == playlist.remoteId -> Unit

                        vm.playlistDownloadActive -> Text(
                            text = "有歌单正在下载…",
                            style = MaterialTheme.typography.labelMedium,
                            color = skin.textMuted,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )

                        left > 0 -> PlaylistActionButton(
                            label = "下载全部 $left",
                            onClick = onDownload,
                            filled = false
                        )

                        else -> Text(
                            text = "已全部存到本地",
                            style = MaterialTheme.typography.labelMedium,
                            color = skin.textMuted,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistActionButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (filled) skin.accent else skin.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) skin.onAccent else skin.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * 下载进度：文案 + 进度条 + 取消。
 *
 * 进度条自己画（一个圆角轨道 + 一个按比例填充的块）而不是用 `LinearProgressIndicator`：
 * 后者的 `progress` 参数在不同 material3 版本里签名不一样（`Float` / `() -> Float`），
 * 自绘能避开这个版本坑，样式也和皮肤一致。
 */
@Composable
private fun DownloadProgress(
    done: Int,
    total: Int,
    saved: Int,
    skipped: Int,
    failed: Int,
    onCancel: () -> Unit
) {
    val skin = LocalSkin.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在下载 $done / $total",
                style = MaterialTheme.typography.bodySmall,
                color = skin.text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelLarge,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(skin.surface3)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(skin.accent)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = buildString {
                append("新存 $saved")
                if (skipped > 0) append(" · 跳过 $skipped")
                if (failed > 0) append(" · 失败 $failed")
            },
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
    }
}

/**
 * 「下载整个歌单」确认弹窗。
 *
 * 先确认再全下：一个 500 首的歌单动辄几百 MB、几十次请求，点错一次的代价太大。
 * 弹窗把「下几首、跳过几首、为什么跳过」讲清楚，用户才好决定。
 */
@Composable
private fun DownloadConfirmDialog(
    playlist: RemotePlaylist,
    tracks: List<RemoteTrack>,
    isPartial: Boolean,
    vm: SonanceViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val skin = LocalSkin.current
    val total = tracks.size
    val playable = tracks.count { it.playable }
    val notPlayable = total - playable
    val downloaded = tracks.count { vm.isDownloaded(it) }
    val downloadable = tracks.count { it.playable && !vm.isDownloaded(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2,
        title = {
            Text(
                text = if (isPartial) "下载所选曲目" else "下载整个歌单",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "「${playlist.name}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = skin.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "共 $total 首，本次会下载 $downloadable 首。",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim
                )
                if (downloaded > 0) {
                    HintLine("已存到本地的 $downloaded 首会自动跳过")
                }
                if (notPlayable > 0) {
                    HintLine("$notPlayable 首需要会员或已下架，会跳过")
                }
                if (vm.onlineDownloadWifiOnly) {
                    HintLine("当前设置「只在 Wi-Fi 下载」，非 Wi-Fi 会直接跳过")
                }
                HintLine("逐首下载，中途可以取消。")
            }
        },
        confirmButton = {
            PillButton(label = "开始下载", onClick = onConfirm)
        },
        dismissButton = {
            PillButton(label = "取消", onClick = onDismiss, filled = false)
        }
    )
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.labelSmall,
        color = LocalSkin.current.textMuted
    )
}

/** 头部大封面的请求尺寸。铺满 104dp 需要 2~3 倍图，取 500 够清晰又不至于太大 */
private const val HEADER_COVER_PX = 500

/** 曲目行缩略图。和搜索列表保持一致 */
private const val ROW_COVER_PX = 200
