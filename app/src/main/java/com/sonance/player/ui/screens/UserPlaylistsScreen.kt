package com.sonance.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.ImportMatchResult
import com.sonance.player.data.PlaylistEntry
import com.sonance.player.data.Song
import com.sonance.player.data.UrlImportPreview
import com.sonance.player.data.UserPlaylist
import com.sonance.player.data.formatDuration
import com.sonance.player.data.toStoredOrNull
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.CoverArt
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.IconAction
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.RowActionSheet
import com.sonance.player.ui.components.RowSheetAction
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的歌单」页：曲库 Pager 的第四页（手机端）/ 侧栏入口（平板）。
 * 歌单列表 ↔ 歌单详情两级都在这一页内完成，不占用 Detail 导航栈。
 * 表头分段控件和底部播放条由外层 Pager 统一提供，这里只画中间内容。
 */
@Composable
fun UserPlaylistsScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    gridState: LazyGridState? = null
) {
    val active = vm.activeUserPlaylistId?.let { vm.userPlaylistById(it) }
    // 歌单里的在线歌要先登记进 onlineSongMap，播放页/队列页按 id 反查才不会是「未知歌曲」
    LaunchedEffect(vm.userPlaylists) { vm.registerUserPlaylistOnlineSongs() }
    if (active != null) {
        UserPlaylistDetailScreen(vm = vm, playlist = active, playerState = playerState, modifier = modifier)
    } else {
        PlaylistListPage(vm = vm, modifier = modifier, gridState = gridState)
    }
}

/** 表头搜索框在歌单页的口径：按名称/描述过滤歌单卡片 */
internal fun List<UserPlaylist>.filterPlaylistsByQuery(query: String): List<UserPlaylist> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return this
    return filter {
        it.name.lowercase().contains(q) || it.desc.lowercase().contains(q)
    }
}

/* ============================================================
 * 歌单列表
 * ============================================================ */

@Composable
private fun PlaylistListPage(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier,
    gridState: LazyGridState? = null
) {
    val skin = LocalSkin.current
    val playlists = vm.userPlaylists.filterPlaylistsByQuery(vm.searchQuery)
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PillButton(label = "＋ 新建歌单", onClick = { showCreate = true }, compact = true)
            PillButton(label = "导入歌单", onClick = { showImport = true }, filled = false, compact = true)
        }
        if (playlists.isEmpty()) {
            if (vm.userPlaylists.isEmpty()) {
                EmptyState(
                    icon = SonanceIcons.Queue,
                    title = "还没有歌单",
                    subtitle = "新建或导入一个歌单，把喜欢的歌收进来"
                )
            } else {
                EmptyState(
                    icon = SonanceIcons.Queue,
                    title = "没有找到相关歌单",
                    subtitle = "换个关键词试试"
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState ?: rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(playlists, key = { it.id }) { pl ->
                    PlaylistCard(
                        playlist = pl,
                        vm = vm,
                        onClick = { vm.openUserPlaylist(pl.id) },
                        onLongClick = { menuTarget = pl }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, desc ->
                val pl = vm.createUserPlaylist(name, desc)
                showCreate = false
                if (pl != null) vm.openUserPlaylist(pl.id)
            }
        )
    }

    if (showImport) {
        ImportPlaylistDialog(
            vm = vm,
            onDismiss = { showImport = false },
            onImported = { pl ->
                showImport = false
                vm.openUserPlaylist(pl.id)
            }
        )
    }

    menuTarget?.let { pl ->
        RowActionSheet(
            title = pl.name,
            subtitle = "${pl.entries.size} 首",
            actions = listOf(
                RowSheetAction("重命名…") { renameTarget = pl },
                RowSheetAction("删除歌单", destructive = true) { deleteTarget = pl }
            ),
            onDismiss = { menuTarget = null }
        )
    }

    renameTarget?.let { pl ->
        CreatePlaylistDialog(
            title = "重命名歌单",
            initialName = pl.name,
            initialDesc = pl.desc,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name, desc ->
                vm.renameUserPlaylist(pl.id, name, desc)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除歌单") },
            text = { Text("删除「${pl.name}」？歌单里的歌曲文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteUserPlaylist(pl.id)
                    deleteTarget = null
                }) { Text("删除", color = skin.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/** 歌单卡片：四宫格拼贴封面 + 名称 + 曲目数/时长。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: UserPlaylist,
    vm: SonanceViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val skin = LocalSkin.current
    val songs = remember(playlist, vm.songs) { vm.userPlaylistSongs(playlist) }
    val totalMs = songs.sumOf { it.durationMs }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        PlaylistCollage(songs = songs, seed = playlist.name, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(7.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (playlist.desc.isNotBlank()) playlist.desc
            else "${playlist.entries.size} 首 · ${formatDuration(totalMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 2×2 封面拼贴；不足四张用占位图补齐，空歌单整块占位。 */
@Composable
private fun PlaylistCollage(songs: List<Song>, seed: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val four = songs.take(4)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
    ) {
        if (four.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = SonanceIcons.Queue,
                    contentDescription = null,
                    tint = skin.textMuted,
                    modifier = Modifier.size(34.dp)
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    CollageCell(four.getOrNull(0), seed + "0", Modifier.weight(1f).fillMaxSize())
                    CollageCell(four.getOrNull(1), seed + "1", Modifier.weight(1f).fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    CollageCell(four.getOrNull(2), seed + "2", Modifier.weight(1f).fillMaxSize())
                    CollageCell(four.getOrNull(3), seed + "3", Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CollageCell(song: Song?, seed: String, modifier: Modifier = Modifier) {
    if (song == null) {
        val skin = LocalSkin.current
        Box(modifier.background(skin.surface2.copy(alpha = 0.5f)))
    } else {
        CoverArt(
            albumId = song.albumId,
            seed = seed,
            sourceUri = song.uri,
            songId = song.id,
            coverKey = com.sonance.player.data.OnlineCover.keyOf(song.title, song.artist),
            sizePx = 200,
            cornerRadius = 0.dp,
            showPlaceholderMark = false,
            modifier = modifier
        )
    }
}

/* ============================================================
 * 歌单详情
 * ============================================================ */

@Composable
private fun UserPlaylistDetailScreen(
    vm: SonanceViewModel,
    playlist: UserPlaylist,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs = remember(playlist, vm.songs, vm.userPlaylists) { vm.userPlaylistSongs(playlist) }

    // 详情页内的返回：先回歌单列表，而不是退出整个 tab
    BackHandler { vm.closeUserPlaylist() }

    var showAddSongs by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var downloadSelectMode by remember(playlist.id) { mutableStateOf(false) }
    var selectedDownloadIds by remember(playlist.id) { mutableStateOf<Set<Long>>(emptySet()) }
    val exitDownloadSelect = {
        downloadSelectMode = false
        selectedDownloadIds = emptySet()
    }
    BackHandler(enabled = downloadSelectMode) { exitDownloadSelect() }

    val hasRemoteSongs = remember(songs) {
        songs.any { !it.isLocal && !it.isDlna && !it.remoteId.isNullOrBlank() }
    }
    val downloadableRemoteSongs = remember(songs, vm.playlistDownloadDone) {
        songs.filter { !it.isLocal && !it.isDlna && !it.remoteId.isNullOrBlank() && !vm.isSongDownloaded(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val text = exportText
        if (uri != null && text != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }.isSuccess
                }
                vm.showToast(if (ok) "已导出歌单文件" else "导出失败")
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 顶部显式返回「全部歌单」+ 多歌单快捷切换胶囊
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(skin.surface2)
                    .clickable { vm.closeUserPlaylist() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = SonanceIcons.ArrowLeft,
                    contentDescription = "返回全部歌单",
                    tint = skin.text,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "全部歌单",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.text
                )
            }
            if (vm.userPlaylists.size > 1) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.userPlaylists, key = { it.id }) { item ->
                        val active = item.id == playlist.id
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) skin.accentGlow else skin.surface2)
                                .border(
                                    1.dp,
                                    if (active) skin.accent else Color.Transparent,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable(enabled = !active) {
                                    exitDownloadSelect()
                                    vm.openUserPlaylist(item.id)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) skin.accent else skin.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PlaylistCollage(
                songs = songs,
                seed = playlist.name,
                modifier = Modifier.size(86.dp).clip(RoundedCornerShape(10.dp))
            )
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
                    text = "${songs.size} 首 · ${formatDuration(songs.sumOf { it.durationMs })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted
                )
                if (playlist.desc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = playlist.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconAction(
                icon = SonanceIcons.MoreVertical,
                contentDescription = "歌单操作",
                onClick = { showMenu = true },
                iconSize = 20.dp,
                tint = skin.textDim
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PillButton(
                label = "▶ 全部播放",
                onClick = { vm.playAll(songs) },
                compact = true,
                enabled = songs.isNotEmpty()
            )
            PillButton(label = "＋ 加歌", onClick = { showAddSongs = true }, filled = false, compact = true)
            if (hasRemoteSongs) {
                PillButton(
                    label = if (downloadSelectMode) "退出下载" else "↓ 下载",
                    onClick = {
                        if (downloadSelectMode) {
                            exitDownloadSelect()
                        } else {
                            selectedDownloadIds = downloadableRemoteSongs.mapTo(HashSet()) { it.id }
                            downloadSelectMode = true
                        }
                    },
                    filled = false,
                    compact = true
                )
            }
            PillButton(
                label = "导出",
                onClick = { exportText = vm.exportUserPlaylistText(playlist) },
                filled = false,
                compact = true,
                enabled = songs.isNotEmpty()
            )
        }
        if (vm.playlistDownloadActive && vm.playlistDownloadOwner == playlist.id) {
            OnlineDownloadProgressBanner(
                done = vm.playlistDownloadDone,
                total = vm.playlistDownloadTotal,
                saved = vm.playlistDownloadSaved,
                skipped = vm.playlistDownloadSkipped,
                failed = vm.playlistDownloadFailed,
                onCancel = { vm.cancelPlaylistDownload() }
            )
        }
        if (downloadSelectMode && songs.isNotEmpty()) {
            val selectedSongs = songs.filter { it.id in selectedDownloadIds && !it.isLocal && !it.isDlna && !vm.isSongDownloaded(it) }
            val allDownloadableSelected = downloadableRemoteSongs.isNotEmpty() &&
                downloadableRemoteSongs.all { it.id in selectedDownloadIds }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedDownloadIds = if (allDownloadableSelected) {
                            emptySet()
                        } else {
                            downloadableRemoteSongs.mapTo(HashSet()) { it.id }
                        }
                    }) {
                        Text(if (allDownloadableSelected) "清空选择" else "全选未下载")
                    }
                    Text(
                        text = "已选 ${selectedSongs.size} 首网络歌曲",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        label = "下载 (${selectedSongs.size})",
                        onClick = {
                            vm.downloadUserPlaylistSongs(selectedSongs, playlist.id)
                            exitDownloadSelect()
                        },
                        compact = true,
                        enabled = selectedSongs.isNotEmpty() && !vm.playlistDownloadActive
                    )
                    TextButton(onClick = exitDownloadSelect) { Text("取消") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (songs.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Music,
                title = "歌单还是空的",
                subtitle = "点「＋ 加歌」从曲库里挑，或在歌曲列表长按「加入歌单」",
                actionLabel = "＋ 加歌",
                onAction = { showAddSongs = true }
            )
        } else if (downloadSelectMode) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    val alreadyLocalOrDownloaded = song.isLocal || vm.isSongDownloaded(song)
                    val selectable = !alreadyLocalOrDownloaded && !song.isDlna && !song.remoteId.isNullOrBlank()
                    val checked = alreadyLocalOrDownloaded || (song.id in selectedDownloadIds)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = selectable) {
                                selectedDownloadIds = if (song.id in selectedDownloadIds) {
                                    selectedDownloadIds - song.id
                                } else {
                                    selectedDownloadIds + song.id
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = if (selectable) {
                                {
                                    selectedDownloadIds = if (song.id in selectedDownloadIds) {
                                        selectedDownloadIds - song.id
                                    } else {
                                        selectedDownloadIds + song.id
                                    }
                                }
                            } else null,
                            enabled = selectable,
                            colors = CheckboxDefaults.colors(
                                checkedColor = skin.accent,
                                disabledCheckedColor = skin.textMuted.copy(alpha = 0.45f),
                                uncheckedColor = skin.textDim
                            ),
                            modifier = Modifier.alpha(if (selectable) 1f else 0.5f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectable) skin.text else skin.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val statusTag = when {
                            song.isLocal -> "本地已匹配"
                            vm.isSongDownloaded(song) -> "已下载"
                            else -> "在线"
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selectable) skin.accentGlow else skin.surface2)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = statusTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectable) skin.accent else skin.textMuted
                            )
                        }
                    }
                }
            }
        } else {
            SongList(
                songs = songs,
                vm = vm,
                playerState = playerState,
                showIndex = true,
                onRemoveFromLibrary = { song -> vm.removeUserPlaylistSongs(playlist.id, listOf(song.id)) }
            )
        }
    }

    if (showAddSongs) {
        AddSongsPickerSheet(
            vm = vm,
            playlist = playlist,
            onDismiss = { showAddSongs = false }
        )
    }

    if (showMenu) {
        RowActionSheet(
            title = playlist.name,
            subtitle = null,
            actions = listOf(
                RowSheetAction("重命名…") { showRename = true },
                RowSheetAction("删除歌单", destructive = true) { showDelete = true }
            ),
            onDismiss = { showMenu = false }
        )
    }

    if (showRename) {
        CreatePlaylistDialog(
            title = "重命名歌单",
            initialName = playlist.name,
            initialDesc = playlist.desc,
            confirmLabel = "保存",
            onDismiss = { showRename = false },
            onConfirm = { name, desc ->
                vm.renameUserPlaylist(playlist.id, name, desc)
                showRename = false
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除歌单") },
            text = { Text("删除「${playlist.name}」？歌单里的歌曲文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.deleteUserPlaylist(playlist.id)
                }) { Text("删除", color = skin.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }

    exportText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("导出为 .m3u8") },
            text = {
                Column {
                    Text(
                        text = "本地歌曲写文件路径，在线歌曲写网易云链接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(skin.surface2)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textDim
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveLauncher.launch(playlist.name + ".m3u8")
                }) { Text("保存到文件") }
            },
            dismissButton = {
                TextButton(onClick = { exportText = null }) { Text("关闭") }
            }
        )
    }
}

/* ============================================================
 * 新建 / 重命名对话框
 * ============================================================ */

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, desc: String) -> Unit,
    title: String = "新建歌单",
    initialName: String = "",
    initialDesc: String = "",
    confirmLabel: String = "创建"
) {
    val skin = LocalSkin.current
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, desc) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/* ============================================================
 * 加入歌单（长按单曲 / 多选批量 / 播放页共用）
 * ============================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    vm: SonanceViewModel,
    songs: List<Song>,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreate by remember { mutableStateOf(false) }
    val songIds = remember(songs) { songs.map { it.id } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = if (songs.size == 1) "加入歌单 · ${songs.first().title}" else "加入歌单 · 已选 ${songs.size} 首",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (vm.userPlaylists.isEmpty()) {
                Text(
                    text = "还没有歌单，先新建一个吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(vm.userPlaylists, key = { it.id }) { pl ->
                    val inAll = pl.songIds.containsAll(songIds)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (inAll) vm.removeUserPlaylistSongs(pl.id, songIds)
                                else vm.addUserPlaylistSongs(pl.id, songs)
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = SonanceIcons.Queue,
                            contentDescription = null,
                            tint = if (inAll) skin.accent else skin.textDim,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${pl.entries.size} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textMuted
                            )
                        }
                        if (inAll) {
                            Text(
                                text = "✓ 已在歌单",
                                style = MaterialTheme.typography.labelMedium,
                                color = skin.accent
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = skin.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showCreate = true }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = SonanceIcons.Plus,
                    contentDescription = null,
                    tint = skin.accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "新建歌单并加入",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.accent
                )
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, desc ->
                val entries = songs.mapNotNull { s ->
                    if (s.isLocal) PlaylistEntry(localId = s.id)
                    else s.toStoredOrNull()?.let { PlaylistEntry(online = it) }
                }
                val pl = vm.createUserPlaylist(name, desc, entries)
                showCreate = false
                if (pl != null) onDismiss()
            }
        )
    }
}

/* ============================================================
 * 从曲库加歌（详情页「＋ 加歌」）
 * ============================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongsPickerSheet(
    vm: SonanceViewModel,
    playlist: UserPlaylist,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // 直接读 vm.userPlaylists 里的最新条目：加/减之后勾选态即时刷新
    val current = vm.userPlaylistById(playlist.id) ?: playlist
    val memberIds = remember(current) { current.songIds.toHashSet() }
    val filtered = remember(vm.songs, query) {
        val q = query.trim()
        if (q.isEmpty()) vm.songs.take(300)
        else vm.songs.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }.take(300)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = "加歌到「${playlist.name}」",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = skin.text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索曲库…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it.id }) { song ->
                    val inPlaylist = song.id in memberIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (inPlaylist) vm.removeUserPlaylistSongs(playlist.id, listOf(song.id))
                                else vm.addUserPlaylistSongs(playlist.id, listOf(song))
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CoverArt(
                            albumId = song.albumId,
                            seed = song.title,
                            sourceUri = song.uri,
                            songId = song.id,
                            sizePx = 96,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = if (inPlaylist) SonanceIcons.Check else SonanceIcons.Plus,
                            contentDescription = if (inPlaylist) "移出歌单" else "加入歌单",
                            tint = if (inPlaylist) skin.accent else skin.textDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================
 * 导入歌单（本地文件 / 网易云链接）
 * ============================================================ */

@Composable
private fun ImportPlaylistDialog(
    vm: SonanceViewModel,
    onDismiss: () -> Unit,
    onImported: (UserPlaylist) -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    var tab by remember { mutableStateOf(0) } // 0 本地文件 / 1 歌单链接
    var name by remember { mutableStateOf("") }

    // ---- 本地 ----
    var localText by remember { mutableStateOf("") }
    var localPreview by remember { mutableStateOf<ImportMatchResult?>(null) }
    var showTextArea by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    vm.showToast("文件读不出来")
                } else {
                    localText = text
                    localPreview = vm.matchLocalPlaylistText(text)
                    if (name.isBlank()) name = "导入的歌单"
                }
            }
        }
    }

    // ---- 链接 ----
    var url by remember { mutableStateOf("") }
    var fillOnline by remember { mutableStateOf(false) }
    var urlBusy by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(skin.surface.solidSurface())
                    .border(1.dp, skin.border, RoundedCornerShape(22.dp))
            ) {
                // ---- 标题栏 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "导入歌单",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "选择本地文件或粘贴歌单链接，一步解析并导入",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted
                        )
                    }
                    IconAction(
                        icon = SonanceIcons.Close,
                        contentDescription = "关闭",
                        onClick = onDismiss,
                        iconSize = 18.dp,
                        tint = skin.textDim
                    )
                }

                // ---- 来源分段切换 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(skin.surface2)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ImportSegmentTab("本地文件", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ImportSegmentTab("歌单链接", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }

                // ---- 内容区（可滚动；上限随屏高收，矮屏不挤出确认按钮） ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = minOf(440.dp, (configuration.screenHeightDp - 260).dp.coerceAtLeast(160.dp)))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    if (tab == 0) {
                        Text(
                            text = "支持 .m3u / .m3u8 / .pls / 纯文本（每行「歌手 - 歌名」），匹配本地曲库。",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                label = "选择文件…",
                                onClick = { filePicker.launch(arrayOf("audio/x-mpegurl", "audio/x-scpls", "text/plain", "*/*")) },
                                compact = true
                            )
                            PillButton(
                                label = if (showTextArea) "收起文本框" else "粘贴文本",
                                onClick = { showTextArea = !showTextArea },
                                filled = false,
                                compact = true
                            )
                        }
                        if (showTextArea) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = localText,
                                onValueChange = { localText = it },
                                placeholder = { Text("#EXTM3U\n#EXTINF:245,陈奕迅 - 富士山下\n…") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                                maxLines = 8
                            )
                            Spacer(Modifier.height(8.dp))
                            PillButton(
                                label = "解析匹配",
                                onClick = {
                                    localPreview = vm.matchLocalPlaylistText(localText)
                                    if (name.isBlank()) name = "导入的歌单"
                                },
                                filled = false,
                                compact = true,
                                enabled = localText.isNotBlank()
                            )
                        }
                        localPreview?.let { preview ->
                            Spacer(Modifier.height(10.dp))
                            ImportPreviewBlock(
                                matchedCount = preview.matchedLocalIds.size,
                                onlineCount = 0,
                                unmatched = preview.unmatched.map { it.display }
                            )
                        }
                    } else {
                        Text(
                            text = "支持网易云音乐、酷狗音乐、QQ 音乐歌单链接（可直接粘贴分享文案）",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            placeholder = { Text("粘贴网易云 / 酷狗 / QQ音乐歌单链接…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        ImportModeRow(
                            title = "仅匹配本地曲库",
                            subtitle = "只收本地有的歌，其余忽略",
                            selected = !fillOnline,
                            onClick = { fillOnline = false }
                        )
                        Spacer(Modifier.height(8.dp))
                        ImportModeRow(
                            title = "本地优先＋在线补齐",
                            subtitle = "本地没有的歌以在线形态补进歌单（需联网）",
                            selected = fillOnline,
                            onClick = { fillOnline = true }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("新歌单名（选填，留空自动命名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // ---- 底部一步导入确认 ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 2.dp, bottom = 18.dp)
                ) {
                    val canImport = if (tab == 0) localText.isNotBlank() else url.isNotBlank() && !urlBusy
                    PillButton(
                        label = if (urlBusy) "正在解析并导入…" else if (tab == 0) "立即导入" else "解析并导入",
                        onClick = {
                            if (tab == 0) {
                                val p = localPreview ?: vm.matchLocalPlaylistText(localText)
                                val finalName = name.trim().ifBlank { "导入的歌单" }
                                val pl = vm.commitImportedPlaylist(finalName, p.matchedLocalIds, emptyList())
                                if (pl != null) {
                                    vm.showToast("已导入「${pl.name}」：本地匹配 ${p.matchedLocalIds.size} 首")
                                    onImported(pl)
                                }
                            } else {
                                urlBusy = true
                                scope.launch {
                                    val result = vm.previewOnlinePlaylistImport(url.trim(), fillOnline)
                                    urlBusy = false
                                    val preview = result.getOrNull()
                                    if (preview == null) {
                                        vm.showToast(
                                            result.exceptionOrNull()?.message
                                                ?: "导入失败：链接无效或取不到曲目"
                                        )
                                    } else {
                                        val finalName = name.trim().ifBlank { preview.playlistName }
                                        val pl = vm.commitImportedPlaylist(
                                            finalName,
                                            preview.matchedLocalIds,
                                            preview.onlineEntries
                                        )
                                        if (pl != null) {
                                            val unmatchedNote = if (preview.unmatchedDisplays.isNotEmpty()) {
                                                "，未匹配 ${preview.unmatchedDisplays.size} 首"
                                            } else ""
                                            vm.showToast(
                                                "已导入「${pl.name}」：本地 ${preview.matchedLocalIds.size} 首、在线 ${preview.onlineEntries.size} 首$unmatchedNote"
                                            )
                                            onImported(pl)
                                        }
                                    }
                                }
                            }
                        },
                        enabled = canImport,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 来源分段按钮：选中态用主色淡底 + 主色字，未选中透明底灰字。 */
@Composable
private fun ImportSegmentTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) skin.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) skin.accent else skin.textMuted
        )
    }
}

/** 链接导入模式选项行：标题 + 说明 + 选中勾。 */
@Composable
private fun ImportModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) skin.accent.copy(alpha = 0.10f) else skin.surface2)
            .border(
                width = 1.dp,
                color = if (selected) skin.accent.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) skin.text else skin.textDim
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        if (selected) {
            Icon(
                imageVector = SonanceIcons.Check,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ImportPreviewBlock(matchedCount: Int, onlineCount: Int, unmatched: List<String>) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(12.dp)
    ) {
        Text(
            text = buildString {
                append("命中本地 $matchedCount 首")
                if (onlineCount > 0) append(" · 在线补齐 $onlineCount 首")
                if (unmatched.isNotEmpty()) append(" · 未命中 ${unmatched.size} 首")
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (matchedCount + onlineCount > 0) skin.accent else skin.danger
        )
        if (unmatched.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            unmatched.take(6).forEach {
                Text(
                    text = "· $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (unmatched.size > 6) {
                Text(
                    text = "… 其余 ${unmatched.size - 6} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
    }
}