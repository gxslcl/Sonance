package com.sonance.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.ScanState
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.Album
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.Song
import com.sonance.player.data.ViewMode
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.AlbumCard
import com.sonance.player.ui.components.ArtistRow
import com.sonance.player.ui.components.ChipsRow
import com.sonance.player.ui.components.CoverArt
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.FolderRow
import com.sonance.player.ui.components.ListHeader
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SongRow
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/* ============================================================
 * 歌曲列表骨架
 * ============================================================ */

@Composable
fun SongList(
    songs: List<Song>,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showIndex: Boolean = true,
    contentPadding: PaddingValues? = null,
    scrollToCurrentSignal: Int = 0
) {
    val dense = LocalDenseLayout.current
    val padding = contentPadding ?: PaddingValues(
        horizontal = 16.dp,
        vertical = if (dense) 2.dp else 4.dp
    )
    // 把 item 里真正要用的原始值提到 lambda 外：否则每一项都闭包住整个 playerState，
    // 播放进度每次跳动都会让可见的每一行跟着重组
    val currentId = playerState.mediaId
    val isPlaying = playerState.isPlaying
    val favorites = vm.favorites

    // 滚动期间暂停封面解码，避免主线程被 bitmap 状态写入轰炸。
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
    }

    // 「回到列表时滚到正在播放的歌」。
    // 已经在可视区里就不动 —— 用户自己滚到哪就是哪，别硬拽。
    LaunchedEffect(scrollToCurrentSignal) {
        if (scrollToCurrentSignal <= 0 || currentId <= 0L) return@LaunchedEffect
        val index = songs.indexOfFirst { it.id == currentId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.any { it.index == index }) return@LaunchedEffect
        listState.animateScrollToItem(index)
    }

    // 把捕获了 unstable 值（vm / List<Song>）的回调包到 remember 里，让回调实例稳定；
    // items 块里再传出去的 lambda 只捕获稳定值，编译器就能 memoize，
    // SongRow 的 onClick / onToggleFavorite / onPlayNext 跨帧保持同一实例 → 真正能 skip。
    val playFromSongs = remember(vm, songs) { { song: Song -> vm.playFrom(songs, song) } }
    val toggleFav = remember(vm) { { id: Long -> vm.toggleFavorite(id) } }
    val playNextSong = remember(vm) { { song: Song -> vm.playNext(song) } }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        itemsIndexed(items = songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                index = index,
                isCurrent = currentId == song.id,
                isPlaying = isPlaying,
                isFavorite = favorites.contains(song.id),
                onClick = { playFromSongs(song) },
                onToggleFavorite = { toggleFav(song.id) },
                onPlayNext = { playNextSong(song) },
                compact = compact || dense
            )
        }
    }
}

@Composable
private fun QuickActions(
    songs: List<Song>,
    vm: SonanceViewModel,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PillButton(label = "播放全部", onClick = { vm.playAll(songs) })
        PillButton(label = "随机播放", onClick = { vm.playAll(songs, shuffled = true) }, filled = false)
    }
}

/* ============================================================
 * 全部歌曲
 * ============================================================ */

@Composable
fun SongsScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    onOpenFolder: (String) -> Unit = {},
    onOpenAlbum: (Album) -> Unit = {}
) {
    var mode by rememberSaveable { mutableStateOf(0) }
    val dense = LocalDenseLayout.current

    val list = remember(vm.songs, vm.sortField, vm.sortAscending, vm.searchQuery, mode) {
        if (mode == 1) vm.recentSongs() else vm.visibleSongs()
    }
    val folders = vm.folders

    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            viewMode = if (mode == 0) vm.viewMode else null,
            onViewMode = { vm.changeViewMode(it) },
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = if (dense) 4.dp else 10.dp
            ),
            chips = { m ->
                ChipsRow(
                    chips = listOf(
                        "全部歌曲" to (mode == 0),
                        "最近播放" to (mode == 1),
                        "文件夹" to (mode == 2)
                    ),
                    onSelect = { mode = it },
                    modifier = m
                )
            },
            leadingText = if (mode == 2) "${folders.size} 个文件夹"
            else "${list.size} 首 · ${formatDuration(list.sumOf { it.durationMs })}",
            actions = if (mode != 2 && list.isNotEmpty()) {
                { m -> QuickActions(songs = list, vm = vm, modifier = m) }
            } else null
        )

        when {
            vm.scanState == ScanState.SCANNING && vm.songs.isEmpty() -> LoadingState(
                title = "正在扫描本地音乐…",
                subtitle = "首次扫描可能需要几秒钟"
            )

            mode == 2 -> {
                if (folders.isEmpty()) {
                    EmptyState(
                        icon = SonanceIcons.Folder,
                        title = "还没有文件夹",
                        subtitle = "把音乐文件放进设备存储后重新扫描"
                    )
                } else {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(gridState) {
                        snapshotFlow { gridState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(280.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(folders, key = { it.path }) { folder ->
                            FolderRow(folder = folder, onClick = { onOpenFolder(folder.path) })
                        }
                    }
                }
            }

            list.isEmpty() -> EmptyState(
                icon = SonanceIcons.Music,
                title = if (vm.searchQuery.isNotBlank()) "没有找到匹配的歌曲" else "本地曲库还是空的",
                subtitle = if (vm.searchQuery.isNotBlank()) "换个关键词试试" else "把音乐文件放进设备后点击重新扫描",
                actionLabel = if (vm.searchQuery.isBlank()) "重新扫描" else null,
                onAction = if (vm.searchQuery.isBlank()) ({ vm.scan() }) else null
            )

            mode == 0 && vm.viewMode == ViewMode.GRID -> AlbumGrid(
                albums = vm.albums,
                vm = vm,
                playerState = playerState,
                onOpenAlbum = onOpenAlbum
            )

            else -> SongList(
                songs = list,
                vm = vm,
                playerState = playerState,
                scrollToCurrentSignal = vm.scrollToCurrentSignal
            )
        }
    }
}

/* ============================================================
 * 专辑
 * ============================================================ */

@Composable
fun AlbumGrid(
    albums: List<Album>,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    onOpenAlbum: (Album) -> Unit
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
    }
    val playAllOf = remember(vm) { { songs: List<Song> -> vm.playAll(songs) } }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(158.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(albums, key = { "${it.albumId}::${it.name}" }) { album ->
            AlbumCard(
                album = album,
                onClick = { onOpenAlbum(album) },
                onPlay = { playAllOf(album.songs) },
                isCurrent = playerState.albumId == album.albumId && playerState.hasItem
            )
        }
    }
}

@Composable
fun AlbumsScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    onOpenAlbum: (Album) -> Unit
) {
    val dense = LocalDenseLayout.current
    val albums = remember(vm.songs, vm.searchQuery) {
        val all = vm.albums
        if (vm.searchQuery.isBlank()) all
        else all.filter {
            it.name.contains(vm.searchQuery, ignoreCase = true) ||
                it.artist.contains(vm.searchQuery, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
            leadingText = "${albums.size} 张专辑 · ${vm.songs.size} 首",
            actions = if (vm.songs.isNotEmpty()) {
                { m ->
                    PillButton(
                        label = "随机播放全部",
                        onClick = { vm.playAll(vm.songs, shuffled = true) },
                        filled = false,
                        modifier = m
                    )
                }
            } else null
        )
        Spacer(Modifier.height(if (dense) 4.dp else 10.dp))
        if (vm.scanState == ScanState.SCANNING && vm.songs.isEmpty()) {
            LoadingState(title = "正在扫描本地音乐…", subtitle = "首次扫描可能需要几秒钟")
        } else if (albums.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Album,
                title = "还没有专辑",
                subtitle = "扫描本地音乐后，专辑会自动归类"
            )
        } else {
            AlbumGrid(albums, vm, playerState, onOpenAlbum = onOpenAlbum)
        }
    }
}

/* ============================================================
 * 艺术家
 * ============================================================ */

@Composable
fun ArtistsScreen(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier,
    onOpenArtist: (String) -> Unit
) {
    val dense = LocalDenseLayout.current
    val artists = remember(vm.songs, vm.searchQuery) {
        val all = vm.artists
        if (vm.searchQuery.isBlank()) all
        else all.filter { it.name.contains(vm.searchQuery, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
            leadingText = "${artists.size} 位艺术家"
        )
        Spacer(Modifier.height(if (dense) 4.dp else 10.dp))
        if (vm.scanState == ScanState.SCANNING && vm.songs.isEmpty()) {
            LoadingState(title = "正在扫描本地音乐…", subtitle = "首次扫描可能需要几秒钟")
        } else if (artists.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Users,
                title = "还没有艺术家",
                subtitle = "扫描本地音乐后，艺术家会自动归类"
            )
        } else {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
                snapshotFlow { gridState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(artists, key = { it.name }) { artist ->
                    ArtistRow(artist = artist, onClick = { onOpenArtist(artist.name) })
                }
            }
        }
    }
}

/* ============================================================
 * 文件夹
 * ============================================================ */

@Composable
fun FoldersScreen(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier,
    onOpenFolder: (String) -> Unit
) {
    val dense = LocalDenseLayout.current
    val folders = vm.folders
    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
            leadingText = "${folders.size} 个文件夹",
            actions = {
                PillButton(
                    label = if (vm.scanState == ScanState.SCANNING) "扫描中…" else "重新扫描",
                    onClick = { vm.scan() },
                    filled = false
                )
            }
        )
        Spacer(Modifier.height(if (dense) 4.dp else 10.dp))
        if (vm.scanState == ScanState.SCANNING && vm.songs.isEmpty()) {
            LoadingState(title = "正在扫描本地音乐…", subtitle = "首次扫描可能需要几秒钟")
        } else if (folders.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Folder,
                title = "还没有文件夹",
                subtitle = "把音乐文件放进设备存储后重新扫描"
            )
        } else {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
                snapshotFlow { gridState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(320.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(folders, key = { it.path }) { folder ->
                    FolderRow(folder = folder, onClick = { onOpenFolder(folder.path) })
                }
            }
        }
    }
}

/* ============================================================
 * 最近播放 / 我的收藏
 * ============================================================ */

@Composable
fun RecentScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val dense = LocalDenseLayout.current
    val list = remember(vm.songs, vm.recentIds) { vm.recentSongs() }
    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
            leadingText = "${list.size} 首",
            actions = if (list.isNotEmpty()) {
                { m ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        QuickActions(songs = list, vm = vm, modifier = m)
                        PillButton(label = "清空", onClick = { vm.clearRecent() }, filled = false)
                    }
                }
            } else null
        )
        Spacer(Modifier.height(if (dense) 4.dp else 8.dp))
        if (list.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Clock,
                title = "还没有播放记录",
                subtitle = "听过的歌会自动出现在这里"
            )
        } else {
            SongList(songs = list, vm = vm, playerState = playerState)
        }
    }
}

@Composable
fun FavoritesScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val dense = LocalDenseLayout.current
    val list = remember(vm.songs, vm.favorites, vm.sortField, vm.sortAscending, vm.searchQuery) {
        vm.favoriteSongs()
    }
    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
            leadingText = "${list.size} 首 · ${formatDuration(list.sumOf { it.durationMs })}",
            actions = if (list.isNotEmpty()) {
                { m -> QuickActions(songs = list, vm = vm, modifier = m) }
            } else null
        )
        Spacer(Modifier.height(if (dense) 4.dp else 8.dp))
        if (list.isEmpty()) {
            EmptyState(
                icon = SonanceIcons.Heart,
                title = "还没有收藏",
                subtitle = "点歌单里的小心心，把喜欢的歌收进来"
            )
        } else {
            SongList(songs = list, vm = vm, playerState = playerState)
        }
    }
}

/* ============================================================
 * 详情页（专辑 / 艺术家 / 文件夹 共用）
 * ============================================================ */

@Composable
fun SongCollectionScreen(
    title: String,
    subtitle: String,
    songs: List<Song>,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    coverAlbumId: Long = songs.firstOrNull()?.albumId ?: -1L,
    coverUri: android.net.Uri? = songs.firstOrNull()?.uri,
    showCover: Boolean = true
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current
    Column(modifier.fillMaxSize()) {
        if (showCover) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (dense) 6.dp else 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CoverArt(
                    albumId = coverAlbumId,
                    seed = title,
                    modifier = Modifier
                        .size(if (dense) 76.dp else 104.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    cornerRadius = 12.dp,
                    sizePx = 500,
                    sourceUri = coverUri,
                    songId = songs.firstOrNull()?.id ?: -1L
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim,
                        maxLines = if (dense) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(if (dense) 8.dp else 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PillButton(label = "播放", onClick = { vm.playAll(songs) })
                        PillButton(
                            label = "随机",
                            onClick = { vm.playAll(songs, shuffled = true) },
                            filled = false
                        )
                    }
                }
            }
        } else {
            ListHeader(
                query = vm.searchQuery,
                onQueryChange = vm::changeQuery,
                sortField = vm.sortField,
                sortAscending = vm.sortAscending,
                onSort = vm::changeSort,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
                leadingText = subtitle,
                actions = { m -> QuickActions(songs = songs, vm = vm, modifier = m) }
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(skin.bg)
                .padding(horizontal = 16.dp, vertical = if (dense) 2.dp else 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("曲目", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Text(
                "${songs.size} 首 · ${formatSize(songs.sumOf { it.sizeBytes })}",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
        }

        SongList(
            songs = songs,
            vm = vm,
            playerState = playerState,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.height(8.dp))
    }
}
