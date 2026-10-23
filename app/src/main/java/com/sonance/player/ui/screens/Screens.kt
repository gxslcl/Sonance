package com.sonance.player.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sonance.player.ScanState
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.Album
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.CoverRequest
import com.sonance.player.data.LibraryTab
import com.sonance.player.data.MediaRepository
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Song
import com.sonance.player.data.ViewMode
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.playback.PlayMode
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
import com.sonance.player.ui.theme.ListRowStyle
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import com.sonance.player.ui.components.IconAction
import com.sonance.player.ui.components.MenuRow

/* ============================================================
 * 歌曲列表骨架
 * ============================================================ */

/**
 * 列表行封面的请求尺寸。**必须和 `SongRow` 里传的一致**（也是 200），
 * 否则预取解出来的是另一档，真正的行还得再解一次，白做。
 */
internal const val LIST_COVER_PX = 200

/** 参与左右滑动的页签数量（全部 / 最近 / 收藏）。ViewModel 也要用它建滚动状态 */
internal const val PAGE_COUNT = 3

/** 预取在可见区外各外扩多少行。一屏约 12~14 行，上下各 10 行够盖住中速滑动 */
private const val COVER_PREFETCH_AHEAD = 10

@Composable
fun SongList(
    songs: List<Song>,
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showIndex: Boolean = true,
    contentPadding: PaddingValues? = null,
    scrollToCurrentSignal: Int = 0,
    /**
     * 列表的滚动状态。**调用方传进来才能从外部操控滚动**（回到顶部、
     * 判断"是不是在顶部"）—— 默认自己持有一份，老调用点不用改。
     */
    listState: LazyListState = rememberLazyListState(),
    /**
     * 点某首歌时**进队列**的列表，默认就是 [songs]（列表即上下文）。
     *
     * 「全部歌曲」页搜索时要传**未筛选的曲库**：搜索只是找入口，不是
     * "我要只听这几首"。用筛选后的列表当队列的话，搜一首歌点播放就只剩搜索结果，
     * 播完就没了。专辑 / 艺术家 / 文件夹 / 收藏这些页不传，保持"列表即上下文"。
     */
    queueSongs: List<Song> = songs,
    /**
     * 从列表移除某一首的回调。**传 null 就不在菜单里显示这一项** ——
     * 专辑 / 艺术家这些聚合页里，"移除"该作用于哪一层说不清，摆出来只会误导。
     */
    onRemoveFromLibrary: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
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

    // 滚动期间**降低**封面解码并发（不是停掉，见 ArtworkLoader.withDecodeGate）。
    // 这一句同时被下面几个列表复用，所以放在 SongList 里，不散到每个调用点。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
    }

    // ---- 封面预取 ----
    //
    // ⚠️ 这里曾经是「`LaunchedEffect` 的 key 用预取窗口」的写法：窗口每变一次
    // （也就是**滚动时每一帧**）就重启一次 effect，每次重新发起 20 个解码请求，
    // 而这些请求跑在 ArtworkLoader 自己的 workScope 上、effect 重启也不会取消。
    // 一次快速滑动就是几百上千个任务堆在 Semaphore 后面，每个还要读文件 / 写磁盘，
    // IO 直接打满 —— UI 线程跟着被饿住，表现就是**列表卡住滑不动，过一两秒又好了**。
    //
    // 现在改成一条流：窗口变化 → debounce 180ms → 只取最终窗口发一次。
    // 滑动过程中基本不发请求（手指不停，窗口一直在变），停下才补，真正"预取"的语义。
    val coverScale = vm.listCoverScale.factor
    LaunchedEffect(listState, songs, coverScale) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index
            val last = visible.lastOrNull()?.index
            if (first == null || last == null) null else first to last
        }
            .distinctUntilChanged()
            // 手指还在滚的时候窗口一直在变，debounce 会把中间那些全丢掉
            .debounce(180L)
            .collect { range ->
                if (range == null) return@collect
                val (first, last) = range
                val targets = ((first - COVER_PREFETCH_AHEAD)..(last + COVER_PREFETCH_AHEAD))
                    .filter { it in songs.indices && (it < first || it > last) }
                    // 一次最多补 12 张：再多也只是堆队列，对首屏没帮助
                    .take(12)
                if (targets.isEmpty()) return@collect
                ArtworkLoader.prefetch(
                    context = context,
                    requests = targets.map { i ->
                        val s = songs[i]
                        CoverRequest(s.albumId, s.uri, OnlineCover.keyOf(s.title, s.artist))
                    },
                    sizePx = (LIST_COVER_PX * coverScale).toInt()
                )
            }
    }

    // 「回到列表时滚到正在播放的歌」。
    // 已经在可视区里就不动 —— 用户自己滚到哪就是哪，别硬拽。
    LaunchedEffect(scrollToCurrentSignal) {
        if (scrollToCurrentSignal <= 0) return@LaunchedEffect
        // 播放器是**异步连上**的：冷启动恢复那次请求很可能赶在 mediaId 就位之前，
        // 直接拿 currentId 会得到 -1 然后白跑一趟（而且请求已经消费掉了，不会再来）。
        // 所以这里等一下，最多 3 秒；平时 currentId 已经有值，走的是上面那条快路径。
        val targetId = if (currentId > 0L) currentId else withTimeoutOrNull(3_000L) {
            vm.player.state.first { it.mediaId > 0L }.mediaId
        } ?: return@LaunchedEffect
        val index = songs.indexOfFirst { it.id == targetId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.any { it.index == index }) return@LaunchedEffect
        listState.animateScrollToItem(index)
    }

    // 把捕获了 unstable 值（vm / List<Song>）的回调包到 remember 里，让回调实例稳定；
    // items 块里再传出去的 lambda 只捕获稳定值，编译器就能 memoize，
    // SongRow 的 onClick / onToggleFavorite / onPlayNext 跨帧保持同一实例 → 真正能 skip。
    //
    // 注意捕获的是 [queueSongs] 而不是 [songs]：显示用筛选后的列表，
    // 进队列用完整列表，两者在搜索时不一样。
    val playFromSongs = remember(vm, queueSongs) { { song: Song -> vm.playFrom(queueSongs, song) } }
    val toggleFav = remember(vm) { { id: Long -> vm.toggleFavorite(id) } }
    val playNextSong = remember(vm) { { song: Song -> vm.playNext(song) } }

    // 行分隔方式由用户在设置里定（默认保持原来的"无分界线"）
    val rowStyle = vm.listRowStyle
    val skin = LocalSkin.current
    val rowGap = when (rowStyle) {
        ListRowStyle.SPACED -> 7.dp
        else -> 1.dp
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(rowGap)
    ) {
        itemsIndexed(items = songs, key = { _, s -> s.id }) { index, song ->
            Column {
                SongRow(
                    song = song,
                    index = index,
                    isCurrent = currentId == song.id,
                    isPlaying = isPlaying,
                    isFavorite = favorites.contains(song.id),
                    onClick = { playFromSongs(song) },
                    onToggleFavorite = { toggleFav(song.id) },
                    onPlayNext = { playNextSong(song) },
                    onRemoveFromLibrary = onRemoveFromLibrary?.let { remove ->
                        { remove(song) }
                    },
                    compact = compact || dense,
                    coverScale = coverScale
                )
                // 细分隔线：**左右各缩 12dp**，不顶到屏幕边 —— 顶满会变成
                // "表格线"，把列表切成一条条格子，反而更乱。
                // 最后一行不画，免得列表底部多一条悬空的线。
                if (rowStyle == ListRowStyle.DIVIDER && index < songs.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(0.6.dp)
                            .background(skin.border.copy(alpha = if (dense) 0.5f else 0.65f))
                    )
                }
            }
        }
    }
}

/**
 * 表头右侧那个按钮：**播放模式开关**，不是"播放全部"。
 *
 * 点一下切到下一档（随机 → 列表循环 → 单曲循环 → 随机），图标跟着换。
 * 关键是它**不霸占播放状态**：
 * - 现在没在播 → 用这一页的歌从第一首开播（随机档就按洗牌袋排好）；
 * - 已经在播 → 只改模式，不打断、不重排到别处。
 *
 * 早先它是"随机播放全部"，点一下就把正在听的那首顶掉、队列整条重排，
 * 于是这个位置既不敢点、又和播放页那个模式按钮说着两套话。
 */
@Composable
private fun QuickActions(
    songs: List<Song>,
    vm: SonanceViewModel,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    // 自己订阅播放状态，不从参数拿：这个按钮在好几处表头里复用，
    // 让每个调用点都多传一个 playerState 只会增加漏传的机会
    val st by vm.player.state.collectAsState()
    val mode = PlayMode.of(st.shuffle, st.repeat)
    IconAction(
        icon = when (mode) {
            PlayMode.SHUFFLE -> SonanceIcons.Shuffle
            PlayMode.REPEAT_ALL -> SonanceIcons.Repeat
            PlayMode.REPEAT_ONE -> SonanceIcons.RepeatOne
        },
        contentDescription = "播放模式：${mode.label}",
        onClick = { vm.cyclePlayMode(songs) },
        iconSize = 20.dp,
        // 用强调色：它显示的是**状态**而不是装饰，灰着放会被当成列表的一部分
        tint = skin.accent,
        modifier = modifier
    )
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
    val skin = LocalSkin.current

    val list = remember(vm.songs, vm.sortField, vm.sortAscending, vm.searchQuery, mode) {
        if (mode == 1) vm.recentSongs() else vm.visibleSongs()
    }

    // 点某首歌时**进队列**的列表。
    //
    // 搜索时传完整曲库而不是搜索结果：搜索是"找入口"，不是"我要只听这几首"。
    // 用筛选后的列表当队列的话，搜一首歌点播放，队列里就只剩搜索结果（命中一首
    // 时就只剩那一首），播完就没了 —— 用户要的是"从这首开始，接着放整个曲库"。
    //
    // 「最近播放」模式（mode == 1）不换：那里的上下文本来就是"最近听过的一批"。
    // 规则本体在 MediaRepository.playQueue，有单测覆盖。
    val playQueue = remember(list, vm.searchQuery, mode) {
        MediaRepository.playQueue(
            display = list,
            library = vm.librarySongs(),
            // 最近播放模式不套这条规则：它的列表不受搜索词影响，
            // 而且"最近听过的一批"本身就是有意义的播放上下文
            searching = vm.searchQuery.isNotBlank() && mode != 1
        )
    }
    // 「最近播放」模式里的歌可能是**在线存档**（在线歌不进曲库），
    // 那种条目没有本地 id，"从曲库移除"对它没有意义 —— vm.hideSong 自己会挡掉。
    val removeSong = remember(vm) { { song: Song -> vm.hideSong(song) } }
    val folders = vm.folders

    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = if (dense) 4.dp else 10.dp
            ),

            // 第二行左侧 = **上下文槽位**：
            // 平时是分类切换；搜索时换成"找到 N 首"。
            // 搜索的时候本来也不会去切分类，让位给结果统计不损失任何操作 ——
            // 而结果数量是**新信息**（曲库总数已经在顶栏了），该出现。
            leading = { m ->
                if (vm.searchQuery.isNotBlank()) {
                    Text(
                        text = "找到 ${list.size} 首 · ${formatDuration(list.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.textDim,
                        maxLines = 1,
                        modifier = m
                    )
                } else {
                    ChipsRow(
                        // 文件夹模式下把「文件夹」露出来当选中项 ——
                        // 它平时收在 ⁝ 菜单里，但当前就在这个模式时必须能看见、能点回去
                        chips = if (mode == 2) {
                            listOf(
                                "全部歌曲" to false,
                                "最近播放" to false,
                                "文件夹" to true
                            )
                        } else {
                            listOf("全部歌曲" to (mode == 0), "最近播放" to (mode == 1))
                        },
                        onSelect = { mode = it },
                        modifier = m
                    )
                }
            },

            // ⁝ 菜单：收纳被移出主行的两件事。文件夹模式下视图切换不适用（文件夹恒为网格），
            // 菜单里就没东西可放了，这时整个按钮一起收起。
            overflow = if (mode == 2) {
                null
            } else {
                {
                    MenuRow(
                        icon = SonanceIcons.ListView,
                        label = "列表视图",
                        selected = vm.viewMode == ViewMode.LIST
                    ) { vm.changeViewMode(ViewMode.LIST) }
                    MenuRow(
                        icon = SonanceIcons.GridView,
                        label = "网格视图",
                        selected = vm.viewMode == ViewMode.GRID
                    ) { vm.changeViewMode(ViewMode.GRID) }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    MenuRow(
                        icon = SonanceIcons.Folder,
                        label = "文件夹",
                        selected = false
                    ) { mode = 2 }
                }
            },

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
                scrollToCurrentSignal = vm.scrollToCurrentSignal,
                queueSongs = playQueue,
                // 「全部歌曲」是曲库本身，"从列表移除"在这一页才说得清
                onRemoveFromLibrary = removeSong
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
    /** 同 [SongList] 的 `listState`：外面传进来才能从外部滚到顶部 */
    gridState: LazyGridState = rememberLazyGridState(),
    onOpenAlbum: (Album) -> Unit
) {
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
    // 依赖 recentEntries 而不是 recentIds —— 最近播放现在也含在线歌
    val list = remember(vm.songs, vm.recentEntries) { vm.recentSongs() }
    Column(modifier.fillMaxSize()) {
        ListHeader(
            query = vm.searchQuery,
            onQueryChange = vm::changeQuery,
            sortField = vm.sortField,
            sortAscending = vm.sortAscending,
            onSort = vm::changeSort,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 10.dp),
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
                    songId = songs.firstOrNull()?.id ?: -1L,
                    coverKey = songs.firstOrNull()
                        ?.let { OnlineCover.keyOf(it.title, it.artist) }
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

/* ============================================================
 * 资料库三页（全部 / 最近 / 收藏）—— 紧凑屏专用
 *
 * 设计取舍：
 *  - 手机上去掉 5 项底栏，把「歌曲 / 最近 / 收藏」做成可左右滑的三页；
 *    专辑 / 艺术家 / 文件夹 收进右上角「视图」弹层 —— 它们本来就是
 *    「同一批歌的另一种视角」，不该和页签平铺在同一层。
 *  - **循环滑动**：三页首尾相接，一直往一个方向滑总能回到「全部」。
 *    到边回弹的做法（老版本）在收藏页往右滑会"撞墙"，而三页本来就没有
 *    "最后一页"的概念 —— 撞墙感是假的，用户只会以为这一页坏了。
 *  - 指示器**实时**跟随手指：它读的是 pager 的当前页，不是等页面停稳才回写的
 *    tab，否则手指都滑过去半页了，上面的「全部 / 最近 / 收藏」还停在原处。
 *  - 指示器可点：滑动是隐藏功能，光靠滑动新用户发现不了。
 * ============================================================ */

/** 参与左右滑动的三个页签。 */
val pagerTabs: List<LibraryTab> = listOf(
    LibraryTab.SONGS,
    LibraryTab.RECENT,
    LibraryTab.FAVORITES
)

/**
 * 循环用的"虚拟页数"：真实三页 × 1000 圈，从正中间那一圈起步。
 *
 * 这么做的代价是 Pager 会为 3000 页建状态 —— 但它只为**可见的那几页**组合内容，
 * 状态本身只是一个整数列表，开销可以忽略。换来的是滑到任何方向都不会撞墙。
 */
private const val PAGER_LOOP_PAGES = 3 * 1000
private const val PAGER_LOOP_START = 3 * 500

/** 虚拟页码 → 真实页下标（对负数也成立，往左滑出起点时会用到） */
private fun realIndex(page: Int): Int = Math.floorMod(page, pagerTabs.size)

/**
 * 滚动状态的"池子"大小 —— **故意比真实页数大**。
 *
 * 分页器是循环的，翻页时必然"销毁一个虚拟页 + 新建一个虚拟页"，而这俩的
 * [realIndex] 是同一个。如果按"三个页签三份状态"来存，被销毁的那个
 * `LazyColumn` 和新建的那个会有一小段时间**同时抓着同一个 `LazyListState`**
 * （Compose 的销毁是下一帧才生效的）。一份滚动状态被两个列表同时 attach，
 * 内部的测量回调与位置记录会互相覆盖，表现就是：这一页**纵向彻底滑不动**，
 * 而横向翻页、点浮窗全都正常 —— 正是那个偶发的卡死。
 *
 * 池化之后相邻虚拟页落在不同槽位，刚销毁的和新建的至少隔 [SCROLL_SLOTS]
 * 页才可能撞到同一个槽，而一次滑动跨 8 页不会发生。
 */
private const val SCROLL_SLOTS = 8

/** 虚拟页码 → 状态槽位 */
private fun slotFor(page: Int): Int = Math.floorMod(page, SCROLL_SLOTS)

/** 该 tab 是否属于三页滑动的范围（紧凑屏据此决定渲染哪套内容）。 */
val LibraryTab.inPager: Boolean get() = this in pagerTabs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPagerScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    modifier: Modifier = Modifier,
    onOpenFolder: (String) -> Unit = {},
    onOpenAlbum: (Album) -> Unit = {}
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current

    val pagerState = rememberPagerState(
        initialPage = PAGER_LOOP_START + pagerTabs.indexOf(vm.tab).coerceAtLeast(0)
    ) { PAGER_LOOP_PAGES }

    /**
     * 指示器用的「当前是第几页」——**必须读 pager 自己**。
     *
     * 早先读的是 vm.tab，而 vm.tab 要等页面停稳才回写（见下面 settledPage 那条），
     * 于是手指滑过半页、页面都换好了，上面那段「全部 / 最近 / 收藏」还停在原处，
     * 松手才"啪"地跳一下 —— 就是那个慢半拍。
     */
    val liveIndex by remember { derivedStateOf { realIndex(pagerState.currentPage) } }

    // 滚动状态 hoist 到分页器外面：表头的「回到顶部」、右侧快速滚动条、卡死自愈
    // 都要操作/观察**当前这一页**的列表，状态放在页面内部就拿不到。
    //
    // ⚠️ 状态**必须留在组合里**，不能挂到 ViewModel 上常驻（试过，见
    // `SonanceViewModel.readLibraryScroll` 的注释：会导致纵向滚动偶发彻底失效）。
    // 跨"进出设置页 / 冷启动"的位置靠 Prefs 里的"下标 + 偏移"恢复：
    // `remember` 只在**首次进入本屏**时读一次初值，之后一直由组合自己维护。
    val savedScroll = remember { vm.readLibraryScroll() }

    // 「列表卡死」的兜底自愈开关：见下面 healConnection 的说明。
    // 变化时滚动状态池会按**当前可见位置**重建，列表节点也跟着重挂一次。
    var healEpoch by remember { mutableIntStateOf(0) }

    // 每个页签（全部 / 最近 / 收藏）上次离开时的位置：**下标 + 偏移**。
    //
    // 状态对象是按虚拟页池化的（见 SCROLL_SLOTS 的注释），绕一圈回来是另一份状态，
    // 所以位置不能赖在状态对象里，得按页签单独记一份 —— 翻页进出、绕圈、自愈
    // 重建，都靠它把用户"停在第几首"还原回去。
    val scrollSave = remember {
        Array(pagerTabs.size) { i -> savedScroll.getOrElse(i) { 0 to 0 } }
    }

    // 滚动状态池：槽位 → 状态。为什么不是三页三份，见 SCROLL_SLOTS 的注释。
    val slotStates = remember(healEpoch) {
        List(SCROLL_SLOTS) { slot ->
            // 首屏就用上次的位置建，省掉"先显示第一首再跳走"的闪一下
            val (idx, off) = scrollSave[realIndex(slot)]
            LazyListState(
                firstVisibleItemIndex = idx.coerceAtLeast(0),
                firstVisibleItemScrollOffset = off
            )
        }
    }
    // 各个回调（回到顶部、滚动条跳转、自愈探针）都是**延迟执行**的，捕获
    // 状态列表本身会在自愈换掉那一批之后还指向旧的 —— 走 UpdatedState 拿最新。
    val slotStatesNow by rememberUpdatedState(slotStates)
    val gridState = remember(healEpoch) { LazyGridState() }
    val gridStateNow by rememberUpdatedState(gridState)
    val scope = rememberCoroutineScope()

    // 离开本屏（进设置、进详情页、退出）时把位置写进 Prefs，下次进来/冷启动接着用。
    // 正在看的这一页还没走 onDispose，所以要现取一次补进去。
    DisposableEffect(Unit) {
        onDispose {
            val out = scrollSave.toMutableList()
            val st = slotStatesNow[slotFor(pagerState.currentPage)]
            out[liveIndex] = st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset
            vm.saveLibraryScroll(out)
        }
    }

    // 读的是**调用那一刻**的页码 / 视图模式，所以这个 lambda 不用跟着重组换实例 ——
    // 表头的 pointerInput 也就不会因为换实例而反复重启手势检测。
    val pageNow by rememberUpdatedState(liveIndex)
    val gridMode by rememberUpdatedState(vm.viewMode == ViewMode.GRID)
    val jumpToTop: () -> Unit = remember(scope) {
        {
            scope.launch {
                if (gridMode && pageNow == 0) gridStateNow.animateScrollToItem(0)
                else slotStatesNow[slotFor(pagerState.currentPage)].animateScrollToItem(0)
            }
        }
    }

    /** 当前页是不是停在顶部（决定右下角那个「回到顶部」出不出来） */
    val atTop by remember {
        derivedStateOf {
            if (gridMode && liveIndex == 0) {
                gridStateNow.firstVisibleItemIndex == 0 && gridStateNow.firstVisibleItemScrollOffset == 0
            } else {
                val st = slotStatesNow[slotFor(pagerState.currentPage)]
                st.firstVisibleItemIndex == 0 && st.firstVisibleItemScrollOffset == 0
            }
        }
    }

    // 外部改 tab（视图弹层、侧栏、搜索跳转）→ 滚到**最近**的那一页。
    // 循环之后同一个页签对应 1000 个页码，随便挑一个会让页面绕一大圈。
    LaunchedEffect(vm.tab) {
        val idx = pagerTabs.indexOf(vm.tab)
        if (idx < 0) return@LaunchedEffect
        val cur = pagerState.currentPage
        val n = pagerTabs.size
        val base = cur - Math.floorMod(cur, n)
        val target = listOf(base + idx - n, base + idx, base + idx + n)
            .minByOrNull { kotlin.math.abs(it - cur) } ?: return@LaunchedEffect
        if (target != cur) pagerState.animateScrollToPage(target)
    }
    // 滑动落定 → 回写 tab。用 settledPage 而不是 currentPage：
    // 后者在拖拽过程中就变，会让顶栏标题在手指还没松时就来回跳。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
                pagerTabs.getOrNull(realIndex(page))?.let { tab ->
                    // 滑动切页不触发「滚到正在播放」——那会把用户在全部歌曲里的位置重置
                    if (vm.tab != tab) vm.selectTab(tab)
            }
        }
    }
    // 横滑期间**暂停封面解码**。
    //
    // 这是滑动卡顿的主因：切页瞬间两侧页面同时布局、同时发现自己"可见"，
    // 几十张封面的解码请求一起进队列，UI 线程等的就是这一批。
    // 列表自己的滚动也写了同一个开关（见 SongList），两者不会同时为真，
    // 谁先结束谁把它放回去 —— 暂停只是延后，不会丢请求。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { swiping -> ArtworkLoader.paused = swiping }
    }

    val all = remember(vm.songs, vm.sortField, vm.sortAscending, vm.searchQuery) { vm.visibleSongs() }
    val recent = remember(vm.songs, vm.recentEntries, vm.sortField, vm.sortAscending, vm.searchQuery) {
        vm.recentSongs()
    }
    val favorites = remember(vm.songs, vm.favorites, vm.sortField, vm.sortAscending, vm.searchQuery) {
        vm.favoriteSongs()
    }

    val currentList = when (vm.tab) {
        LibraryTab.RECENT -> recent
        LibraryTab.FAVORITES -> favorites
        else -> all
    }

    // 「全部歌曲」搜索时的队列要传完整曲库：搜索是找入口，不是只听这几首
    // （规则本体在 MediaRepository.playQueue，和 SongsScreen 保持一致）
    val playQueue = remember(all, vm.searchQuery) {
        MediaRepository.playQueue(
            display = all,
            library = vm.librarySongs(),
            searching = vm.searchQuery.isNotBlank()
        )
    }
    val removeSong = remember(vm) { { song: Song -> vm.hideSong(song) } }

    // 首次进入的滑动提示：3.2 秒后自动消失，点一下也能立刻关掉。
    // 只提示一次（prefs 里记了"已看过"）—— 滑动是隐藏功能，不给提示发现不了，
    // 但每次都给又很烦。
    var showSwipeHint by remember { mutableStateOf(!vm.pagerHintShown) }
    LaunchedEffect(Unit) {
        if (showSwipeHint) {
            kotlinx.coroutines.delay(3_200)
            showSwipeHint = false
            vm.markPagerHintShown()
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
            leading = { m ->
                if (vm.searchQuery.isNotBlank()) {
                    // 搜索时让位给结果统计（和 SongsScreen 同一套逻辑）
                    Text(
                        text = "找到 ${currentList.size} 首 · ${formatDuration(currentList.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.textDim,
                        maxLines = 1,
                        modifier = m
                    )
                } else {
                    LibrarySegmented(
                        labels = listOf("全部", "最近", "收藏"),
                        selected = liveIndex,
                        onSelect = { idx -> pagerTabs.getOrNull(idx)?.let(vm::selectTab) },
                        modifier = m
                    )
                }
            },
            // 这一行**永远占位**（空列表时只留高度）：
            // 收藏页没歌时它一消失，整个表头就矮一截，上面那段
            // 「全部 / 最近 / 收藏」会跟着上下跳一下 —— 滑动时最刺眼。
            actions = { m ->
                Row(
                    m.heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentList.isNotEmpty()) {
                        QuickActions(songs = currentList, vm = vm)
                    }
                }
            },
            // 双击指示器右边的空白 → 回到顶部（替代原来那个刷新图标的位置感）
            onDoubleTapToTop = jumpToTop
        )

        PageDots(count = pagerTabs.size, selected = liveIndex)

        // 下拉刷新**整个去掉了**。
        //
        // 它在这套结构里（HorizontalPager + 三页列表）一直都是隐患：
        // 是整条嵌套滚动链上唯一会跟列表抢纵向手势的东西，列表不在顶部时
        // 也会偶尔接管拖拽 —— "停在半中间上下滑不动 + 莫名出现下拉刷新"。
        // 改"按住 0.5 秒"之后依然是个没人用的功能（曲库刷新本来就该自动做），
        // 所以直接删掉：每次打开应用时后台自动刷新一次（见 `refreshOnStart`），
        // 加上「媒体库 → 重新扫描本地音乐」这个显式入口，够用了。
        //
        // 内容区单独套一层 Box：滑动提示要**浮在列表上面**，
        // 不能像原来那样占一行 —— 它 3.2 秒后消失，一消失整个页面就会往上跳一下。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // ★ 「列表卡死」自愈探针
            //
            // 偶发过的现象：某一页列表**纵向彻底滑不动**（横向翻页、点浮窗都正常），
            // 过一会或者重进页面才好。这说明 LazyColumn 的滚动链路本身哑了 ——
            // 手指的位移送到了，列表一个像素都没吃。
            //
            // 这个连接挂在列表**外面**只做观察，判定用两道关：
            //  ① 竖直方向有明显剩余位移，而列表自己报告"我本来还能往这边滚"；
            //  ② 连续几个事件下来，列表的**位置一个字都没变**。
            // 两道都过 → 把列表节点重挂一次（healEpoch++），并按记录的可见位置
            // 重建状态池，用户看到的就是"卡一下又好了"，位置不丢。
            // 比"卡死到重启应用"强得多。
            //
            // 只加一道关不够准：惯性滚动收尾那一两帧、或者在列表首尾继续用力拖，
            // 都会出现"有剩余位移但没滚"，光看 ① 会误判 —— 误判会让列表白跳一下，
            // 比卡住还烦。加上 ② 之后，只有真的"动了但没动"才触发。
            val gridOnPage0 = vm.viewMode == ViewMode.GRID && liveIndex == 0
            val probeState = rememberUpdatedState<(Float) -> Boolean> { availY ->
                if (availY > 0f) {
                    if (gridOnPage0) gridStateNow.canScrollBackward
                    else slotStatesNow[slotFor(pagerState.currentPage)].canScrollBackward
                } else {
                    if (gridOnPage0) gridStateNow.canScrollForward
                    else slotStatesNow[slotFor(pagerState.currentPage)].canScrollForward
                }
            }
            /** 当前列表位置的指纹：下标 × 大质数 + 偏移，两条一起变才算动过 */
            val posState = rememberUpdatedState {
                if (gridOnPage0) {
                    gridStateNow.firstVisibleItemIndex * 1_000_003 +
                        gridStateNow.firstVisibleItemScrollOffset
                } else {
                    val st = slotStatesNow[slotFor(pagerState.currentPage)]
                    st.firstVisibleItemIndex * 1_000_003 + st.firstVisibleItemScrollOffset
                }
            }
            val healAction = rememberUpdatedState {
                // 自愈前补记一下当前页的位置：其它页签在离开时已经存进 scrollSave 了，
                // 正在看的这一页还没走 onDispose，这里先落一次，重建后按它还原。
                val st = slotStatesNow[slotFor(pagerState.currentPage)]
                scrollSave[liveIndex] = st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset
                healEpoch++
            }
            val healConnection = remember {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    private var misses = 0
                    private var lastPos = Int.MIN_VALUE
                    private var lastHealAt = 0L

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                    ): Offset {
                        val vertical = kotlin.math.abs(available.y) >= kotlin.math.abs(available.x)
                        if (kotlin.math.abs(available.y) < 1.5f || !vertical) {
                            misses = 0
                            lastPos = Int.MIN_VALUE
                            return Offset.Zero
                        }
                        if (!probeState.value(available.y)) {
                            misses = 0
                            lastPos = Int.MIN_VALUE
                            return Offset.Zero
                        }
                        val pos = posState.value()
                        if (pos == lastPos) misses++ else {
                            misses = 0
                            lastPos = pos
                        }
                        // 自愈后 2 秒内不再触发：真要还没好，也让用户先滑两下看看，
                        // 免得连续重建把界面抖成一团。
                        val now = android.os.SystemClock.uptimeMillis()
                        if (misses >= 4 && now - lastHealAt > 2_000L) {
                            misses = 0
                            lastPos = Int.MIN_VALUE
                            lastHealAt = now
                            healAction.value()
                        }
                        return Offset.Zero
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(healConnection)
            ) {
            key(healEpoch) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                // 本虚拟页用的滚动状态（池化，见 SCROLL_SLOTS 的注释）
                val pageState = slotStatesNow[slotFor(page)]
                //
                // 位置**按页签**存取，不跟着状态对象走：状态是池化的，同一个页签
                // 在不同虚拟页上拿到的是不同对象，位置存在对象里就会串。
                //
                // 这里两个 effect 的 key 都是 page，所以只在**本页首次组合**时跑一次 ——
                // 翻页回来、卡死自愈重建，都靠它们把"停在第几首"接上。
                LaunchedEffect(page) {
                    val (idx, off) = scrollSave[realIndex(page)]
                    if (idx != 0 || off != 0) pageState.scrollToItem(idx, off)
                }
                DisposableEffect(page) {
                    onDispose {
                        scrollSave[realIndex(page)] =
                            pageState.firstVisibleItemIndex to pageState.firstVisibleItemScrollOffset
                    }
                }
                when (realIndex(page)) {
                    0 -> {
                        when {
                            vm.scanState == ScanState.SCANNING && vm.songs.isEmpty() -> LoadingState(
                                title = "正在扫描本地音乐…",
                                subtitle = "首次扫描可能需要几秒钟"
                            )
                            all.isEmpty() -> EmptyState(
                                icon = SonanceIcons.Music,
                                title = if (vm.searchQuery.isNotBlank()) "没有找到匹配的歌曲" else "本地曲库还是空的",
                                subtitle = if (vm.searchQuery.isNotBlank()) "换个关键词试试"
                                else "把音乐文件放进设备后下拉刷新",
                                actionLabel = if (vm.searchQuery.isBlank()) "重新扫描" else null,
                                onAction = if (vm.searchQuery.isBlank()) ({ vm.scan() }) else null
                            )
                            vm.viewMode == ViewMode.GRID -> AlbumGrid(
                                albums = vm.albums,
                                vm = vm,
                                playerState = playerState,
                                gridState = gridState,
                                onOpenAlbum = onOpenAlbum
                            )
                            else -> SongList(
                                songs = all,
                                vm = vm,
                                playerState = playerState,
                                scrollToCurrentSignal = vm.scrollToCurrentSignal,
                                listState = pageState,
                                queueSongs = playQueue,
                                onRemoveFromLibrary = removeSong
                            )
                        }
                    }

                    1 -> {
                        if (recent.isEmpty()) {
                            EmptyState(
                                icon = SonanceIcons.Clock,
                                title = "还没有播放记录",
                                subtitle = "播几首歌，这里会按最近听的顺序排出来"
                            )
                        } else {
                            SongList(
                                songs = recent,
                                vm = vm,
                                playerState = playerState,
                                scrollToCurrentSignal = vm.scrollToCurrentSignal,
                                listState = pageState
                            )
                        }
                    }

                    else -> {
                        if (favorites.isEmpty()) {
                            EmptyState(
                                icon = SonanceIcons.Heart,
                                title = "还没有收藏",
                                subtitle = "点歌单里的小心心，把喜欢的歌收进来"
                            )
                        } else {
                            SongList(
                                songs = favorites,
                                vm = vm,
                                playerState = playerState,
                                scrollToCurrentSignal = vm.scrollToCurrentSignal,
                                listState = pageState
                            )
                        }
                    }
                }
            }
            }

            // 首次进入的滑动提示（浮在列表上，3.2 秒后自己消失；点一下也能立刻关）
            if (showSwipeHint) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(skin.surface3.copy(alpha = 0.92f))
                        .clickable {
                            showSwipeHint = false
                            vm.markPagerHintShown()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "← 左右滑动切换「全部 / 最近 / 收藏」→",
                        style = MaterialTheme.typography.labelSmall,
                        // textMuted 在深色皮肤下几乎不可读，提示必须一眼看到
                        color = skin.text
                    )
                }
            }

            // 右侧快速滚动条（取代原来的「回到顶部」圆钮）。
            //
            // 滑块长度 = 可见比例，拖动即可快速跳转；按住时变粗变亮。
            // 同样是**独立组件**：它每帧读滚动位置，放在主函数体里会让整页每帧重组。
            val barOnGrid = vm.viewMode == ViewMode.GRID && liveIndex == 0
            val barState = slotStatesNow[slotFor(pagerState.currentPage)]
            FastScrollBar(
                itemCount = { if (barOnGrid) vm.albums.size else currentList.size },
                visibleCount = {
                    if (barOnGrid) gridStateNow.layoutInfo.visibleItemsInfo.size
                    else barState.layoutInfo.visibleItemsInfo.size
                },
                firstIndex = {
                    if (barOnGrid) gridStateNow.firstVisibleItemIndex
                    else barState.firstVisibleItemIndex
                },
                hapticEnabled = vm.scrollHaptic,
                onSeek = { index ->
                    scope.launch {
                        if (barOnGrid) gridStateNow.scrollToItem(index)
                        else barState.scrollToItem(index)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            )
        }
    }
}
}

/**
 * 右侧快速滚动条。
 *
 * 取代了原来右下角那个「回到顶部」圆钮：那个只能回顶部，而这个能拖到任意位置，
 * 长曲库里找歌更实用。手感按"原生滚动条"来做 ——
 *
 * - 常态：5dp 宽、低透明度，几乎不干扰内容；
 * - 按住：加宽到 11dp、变成强调色，明确表示"我在拖它"；
 * - 滑块长度 = 可见条目占全量的比例，所以列表越长滑块越短，一眼能看出还剩多少。
 *
 * 三个读状态的 lambda 都是**闭包**：声明式的参数会在每次滚动时触发本组件重组，
 * 但重组范围只有这条 20dp 宽的滚动条，不会波及列表本身。
 */
@Composable
private fun FastScrollBar(
    itemCount: () -> Int,
    visibleCount: () -> Int,
    firstIndex: () -> Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 拖动时是否给震动反馈（设置里可关） */
    hapticEnabled: Boolean = true
) {
    val skin = LocalSkin.current
    val total = itemCount()
    if (total <= 1) return
    val visible = visibleCount().coerceAtLeast(1)
    // 一屏就装得下 → 没有滚动条存在的意义
    if (visible >= total) return

    val ratio = (visible.toFloat() / total).coerceIn(0.08f, 1f)
    val progress = (firstIndex().toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)

    var trackPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }

    // 触觉反馈：按"跨过多少行"节流，而不是每帧都震 ——
    // 每帧震会连成一片嗡嗡声，反而分不清拖动距离。
    // 步长按列表长度自适应：长曲库 1262 首时约每 15 行一下，短列表则更密。
    val haptics = LocalHapticFeedback.current
    var lastTickIndex by remember(total) { mutableIntStateOf(-1) }
    val hapticStep = (total / 80).coerceAtLeast(3)
    val barW by animateDpAsState(
        targetValue = if (dragging) 11.dp else 5.dp,
        animationSpec = tween(120),
        label = "scrollBarW"
    )

    // 把手指的 y 换算成 0~1，再交给调用方跳转。
    //
    // ⚠️ 分母是 `total - visible`（能当首行的最后一个下标），不是 `total - 1`：
    // 上面算 progress 用的就是它，两边必须同一把尺子。用 total - 1 的话，
    // 把滑块拖到最底下会冲过头（想跳到第 total-1 行当首行，可下面已经没有
    // 一屏内容了），列表被 clamp 到底、滑块却还没到手指 —— 拖到底会觉得"发飘"。
    fun seekAt(y: Float) {
        val thumbPx = trackPx * ratio
        val span = (trackPx - thumbPx).coerceAtLeast(1f)
        val p = ((y - thumbPx / 2f) / span).coerceIn(0f, 1f)
        val target = (p * (total - visible)).roundToInt()
        onSeek(target)

        // 跨过 hapticStep 行才震一次；第一次按下（lastTickIndex < 0）也给一下，
        // 让"抓住滑块了"这个动作有确认感
        if (hapticEnabled) {
            if (lastTickIndex < 0 || kotlin.math.abs(target - lastTickIndex) >= hapticStep) {
                lastTickIndex = target
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            // 20dp 触摸区：比视觉上的 5dp 宽得多，但仍在屏幕最右侧，
            // 不会和列表的上下滑动抢手势（只有按在这条上才接管）
            .width(20.dp)
            .onSizeChanged { trackPx = it.height.coerceAtLeast(1) }
            .pointerInput(total, ratio) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        lastTickIndex = -1
                        seekAt(o.y)
                    },
                    onDragEnd = {
                        dragging = false
                        lastTickIndex = -1
                    },
                    onDragCancel = {
                        dragging = false
                        lastTickIndex = -1
                    }
                ) { change, _ -> seekAt(change.position.y) }
            }
            .pointerInput(total, ratio) {
                detectTapGestures { o -> seekAt(o.y) }
            },
        // ⚠️ 必须 TopEnd，不能 CenterEnd。
        //
        // 滑块是"先 fillMaxHeight(ratio) 定长，再用 offset 往下推 progress 的距离"——
        // 这个算法默认滑块**从轨道顶端**起算。外层一旦是 CenterEnd，滑块会先被
        // 垂直居中再下推，凭空多出 (轨道高 - 滑块高) / 2 的偏移：
        //   · 列表已经在顶部（progress = 0），滑块却停在轨道中间；
        //   · seekAt() 按"手指 = 滑块中心"反算进度，算出来的位置和画出来的位置
        //     刚好差这半个偏移 —— 拖动手感和滑块永远对不上。
        // 列表越长（滑块越短）偏得越明显。
        contentAlignment = Alignment.TopEnd
    ) {
        // 轨道
        Box(
            Modifier
                .fillMaxHeight()
                .width(barW)
                .clip(CircleShape)
                .background(skin.text.copy(alpha = if (dragging) 0.14f else 0.06f))
        )
        // 滑块：高度按可见比例，位置按进度
        Box(
            Modifier
                .width(barW)
                .fillMaxHeight(ratio)
                .offset {
                    val thumbPx = trackPx * ratio
                    IntOffset(0, ((trackPx - thumbPx) * progress).roundToInt())
                }
                .clip(CircleShape)
                .background(
                    if (dragging) skin.accent
                    else skin.text.copy(alpha = 0.42f)
                )
        )
    }
}

/** 三段式页签（可点，与滑动双向联动）。 */
@Composable
private fun LibrarySegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            // 用 surface2 而不是 surface：列表页底色是 bg，surface 和它只差一点点，
            // 深色皮肤下几乎看不出这个控件的边界
            .background(skin.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    // 深色底上 14% 的强调色几乎看不出来，按明暗分两档
                    .background(
                        if (on) skin.accent.copy(alpha = if (skin.isLight) 0.14f else 0.24f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) skin.accent else skin.textDim,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 页码点：当前页拉长成小横条。
 *
 * 固定显示 [count] 个点（不做无限轮播），用户永远知道"一共三页、我在第几页"。
 */
@Composable
private fun PageDots(count: Int, selected: Int) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (LocalDenseLayout.current) 2.dp else 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val on = i == selected
            Box(
                Modifier
                    .padding(horizontal = 2.5.dp)
                    .size(width = if (on) 14.dp else 5.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) skin.accent else skin.border)
            )
        }
    }
}
