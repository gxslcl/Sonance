package com.sonance.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.SonanceViewModel
import com.sonance.player.SonanceViewModel.OnlineSearchMode
import com.sonance.player.data.OnlineSource
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.formatDuration
import com.sonance.player.data.netease.NeteaseAccount
import com.sonance.player.data.remoteSongId
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.ChipsRow
import com.sonance.player.ui.components.CountLabel
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.EqualizerBars
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.PlaceholderCover
import com.sonance.player.ui.components.RowActionSheet
import com.sonance.player.ui.components.RowSheetAction
import com.sonance.player.ui.components.SearchField
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.sheets.NeteaseAccountSheet
import com.sonance.player.ui.sheets.NeteaseLoginSheet
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 在线音乐：搜索 + 播放。
 *
 * ## 三个刻意的设计决定
 *
 * 1. **未开启时只渲染一个开启入口，一个请求都不发。** 和在线歌词、在线封面
 *    同一个原则 —— 这个播放器默认是纯本地的，联网必须由用户点一下。
 * 2. **不可播的歌照样列出来，只是打上标记。** 静默过滤掉会让用户以为搜索坏了
 *    （"我明明搜到了这首歌，怎么没有？"）；标出来才是诚实的做法。
 * 3. **列表用缩略图，播放时才下原图。** 一页 30 条，原图是 1.9 MB/张，
 *    缩略图 100 KB/张 —— 差 19 倍。而铺满播放页需要的是原图。
 *    两条路各走各的，互不污染。
 *
 * ## 输入即搜索（带防抖）
 *
 * 停手 450 ms 才发请求。同时保留了键盘的「搜索」键作为明确的立即触发 ——
 * 有些用户就是习惯打完字按回车。
 */
@Composable
fun OnlineScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    /** 点开一个歌单 —— 由上层导航到歌单详情独立页面 */
    onOpenPlaylist: (RemotePlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    val dense = LocalDenseLayout.current

    // ---- 未开启：只给入口 ----
    if (!vm.onlineMusicEnabled) {
        EmptyState(
            icon = SonanceIcons.Search,
            title = "在线音乐未开启",
            subtitle = "开启后可以搜索并播放网络上的歌曲。\n这是本应用唯一会主动联网的功能，随时可以关掉。",
            actionLabel = "开启在线音乐",
            onAction = { vm.changeOnlineMusic(true) },
            modifier = modifier
        )
        return
    }

    val src = vm.onlineSource
    if (src == null) {
        EmptyState(
            icon = SonanceIcons.Info,
            title = "没有可用的音源",
            subtitle = "音源清单是空的，试试重新安装应用。",
            modifier = modifier
        )
        return
    }

    // ---- 首页推荐歌单 & 登录后专属页：进页面 / 换音源 / 登录状态变化时拉一次 ----
    LaunchedEffect(vm.onlineMusicEnabled, vm.onlineSourceId, vm.neteaseAccount?.userId) {
        vm.loadRecommendedPlaylists()
        if (vm.neteaseAccount != null) {
            vm.loadOnlineHome()
        }
    }

    // ---- 防抖搜索 ----
    // 旋转屏幕 / 从别的页面切回来时 LaunchedEffect 会重启，但那时结果还在，
    // 靠 onlineSearchedFor 认出来直接跳过，不白打一次接口。
    LaunchedEffect(vm.onlineQuery) {
        val q = vm.onlineQuery.trim()
        if (q.isEmpty()) {
            if (vm.onlineResults.isNotEmpty() || vm.onlinePlaylistResults.isNotEmpty()) {
                vm.clearOnlineSearch()
            }
            return@LaunchedEffect
        }
        if (q == vm.onlineSearchedFor) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        // 防抖期间用户又敲了字就别搜了，等下一次触发
        if (vm.onlineQuery.trim() == q) vm.searchOnline(q)
    }

    // ---- 滚到底自动加载下一页 ----
    //
    // 判据是"最后一个可见项已经进入倒数第 3 个" —— 留 3 行余量，
    // 等真的滑到最后一行才请求的话，用户会看到明显的一段空白等待。
    val listState = rememberLazyListState()
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    // key 带上结果条数：翻完一页之后如果用户还停在底部，要能再触发一次
    // （只用 `LaunchedEffect(nearBottom)` 的话，nearBottom 一直是 true 就不会重跑）
    // 两类结果都要带上 —— 歌单模式翻页时单曲那侧的条数是不变的
    LaunchedEffect(
        listState,
        vm.onlineResults.size,
        vm.onlinePlaylistResults.size,
        vm.onlineHasMore
    ) {
        snapshotFlow { nearBottom }.first { it }
        vm.loadMoreOnline()
    }

    Column(modifier.fillMaxSize()) {

        // ---- 顶部栏：单曲 / 歌单 (+ 多音源切换) + 右侧登录/账号入口（对齐下方「只看可播」开关） ----
        var sourceMenuExpanded by remember { mutableStateOf(false) }
        var showLoginSheet by remember { mutableStateOf(false) }
        var showAccountSheet by remember { mutableStateOf(false) }
        val skin = LocalSkin.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 0.dp, bottom = if (dense) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChipsRow(
                chips = listOf(
                    "单曲" to (vm.onlineSearchMode == OnlineSearchMode.SINGLE),
                    "歌单" to (vm.onlineSearchMode == OnlineSearchMode.PLAYLIST)
                ),
                onSelect = {
                    vm.changeOnlineSearchMode(
                        if (it == 0) OnlineSearchMode.SINGLE else OnlineSearchMode.PLAYLIST
                    )
                }
            )
            if (vm.onlineSources.size > 1) {
                Spacer(Modifier.width(6.dp))
                Box {
                    Text(
                        text = (vm.onlineSources.firstOrNull { it.id == vm.onlineSourceId }?.label
                            ?: "音源") + " ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sourceMenuExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false }
                    ) {
                        vm.onlineSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.label) },
                                onClick = {
                                    vm.changeOnlineSource(source.id)
                                    sourceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            OnlineAccountActionPill(
                account = vm.neteaseAccount,
                onClick = {
                    if (vm.neteaseAccount == null) {
                        showLoginSheet = true
                    } else {
                        showAccountSheet = true
                    }
                }
            )
        }

        if (showLoginSheet) {
            NeteaseLoginSheet(
                vm = vm,
                onDismiss = { showLoginSheet = false }
            )
        }
        if (showAccountSheet) {
            NeteaseAccountSheet(
                vm = vm,
                onSwitchAccount = { showLoginSheet = true },
                onDismiss = { showAccountSheet = false }
            )
        }

        // ---- 搜索框 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (dense) 2.dp else 4.dp, bottom = if (dense) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchField(
                value = vm.onlineQuery,
                onValueChange = { vm.onlineQuery = it },
                placeholder = if (vm.onlineSearchMode == OnlineSearchMode.PLAYLIST) {
                    "搜索歌单"
                } else {
                    "搜索在线歌曲"
                },
                modifier = Modifier.weight(1f),
                height = if (dense) 36.dp else 40.dp,
                onSearch = { vm.searchOnline() }
            )
        }

        // ---- 搜索历史：输入框为空时显示，点一下直接搜 ----
        if (vm.onlineQuery.isEmpty() && vm.onlineHistory.isNotEmpty()) {
            SearchHistoryRow(
                items = vm.onlineHistory,
                onPick = { vm.searchFromHistory(it) },
                onRemove = { vm.removeOnlineHistory(it) },
                onClearAll = { vm.clearOnlineHistory() }
            )
        }

        // ---- 音源切换（多音源或未登录） + 搜索结果统计 / 仅可播筛选 ----
        val loggedIn = vm.neteaseAccount != null
        val hasSingleResults = vm.onlineSearchMode == OnlineSearchMode.SINGLE && vm.onlineResults.isNotEmpty()
        val hasVisibleSingle = vm.onlineSearchMode == OnlineSearchMode.SINGLE && vm.visibleOnlineResults.isNotEmpty()
        val hasPlaylistResults = vm.onlineSearchMode == OnlineSearchMode.PLAYLIST && vm.onlinePlaylistResults.isNotEmpty()
        // 单音源 + 已登录 + 有可展示结果时，把「可播统计 + 筛选胶囊」并入下方工具条，不再单独空占一行
        val mergeSingleToolbar = OnlineSources.all.size <= 1 && loggedIn && hasVisibleSingle && !vm.onlineSearching
        val showStatusRow = OnlineSources.all.size > 1 ||
            !loggedIn ||
            vm.onlineSearching ||
            (hasSingleResults && !mergeSingleToolbar) ||
            hasPlaylistResults
        if (showStatusRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = if (dense) 4.dp else 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (OnlineSources.all.size > 1) {
                    ChipsRow(
                        chips = OnlineSources.all.map { it.label to (it.id == vm.onlineSourceId) },
                        onSelect = { vm.changeOnlineSource(OnlineSources.all[it].id) },
                        modifier = Modifier.weight(1f)
                    )
                } else if (!loggedIn) {
                    CountLabel("音源 · ${src.label}", Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (vm.onlineSearching) {
                    CountLabel("搜索中…")
                } else if (hasPlaylistResults) {
                    CountLabel("${vm.onlinePlaylistResults.size} 个歌单")
                } else if (hasSingleResults && !mergeSingleToolbar) {
                    PlayableFilterControls(
                        playable = vm.onlineResults.count { it.playable },
                        total = vm.onlineResults.size,
                        hideUnplayable = vm.onlineHideUnplayable,
                        onToggleHideUnplayable = { vm.changeOnlineHideUnplayable(!vm.onlineHideUnplayable) }
                    )
                }
            }
        }

        // ---- 正文 ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.onlineSearchMode) {
                OnlineSearchMode.SINGLE -> SingleSearchBody(
                    vm = vm,
                    playerState = playerState,
                    src = src,
                    listState = listState,
                    mergeStatusIntoToolbar = mergeSingleToolbar,
                    onOpenPlaylist = onOpenPlaylist
                )

                OnlineSearchMode.PLAYLIST -> PlaylistSearchBody(
                    vm = vm,
                    src = src,
                    listState = listState,
                    onOpenPlaylist = onOpenPlaylist
                )
            }
        }
    }
}

@Composable
private fun PlayableFilterControls(
    playable: Int,
    total: Int,
    hideUnplayable: Boolean,
    onToggleHideUnplayable: () -> Unit
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CountLabel("可播 $playable / $total")
        Box(
            modifier = Modifier
                .clip(shape)
                .background(
                    if (hideUnplayable) skin.accentGlow
                    else skin.surface2.copy(alpha = if (skin.isLight) 0.85f else 0.72f)
                )
                .then(
                    if (hideUnplayable) {
                        Modifier.border(0.8.dp, skin.accent.copy(alpha = 0.45f), shape)
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onToggleHideUnplayable)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (hideUnplayable) "仅看可播 ✓" else "全部结果",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (hideUnplayable) skin.accent else skin.textDim,
                maxLines = 1
            )
        }
    }
}

/* ============================================================
 * 正文：单曲 / 歌单 两套
 * ============================================================ */

/** 单曲搜索结果。逻辑和以前完全一样，只是从 [OnlineScreen] 里拆出来 */
@Composable
private fun SingleSearchBody(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    src: OnlineSource,
    listState: LazyListState,
    mergeStatusIntoToolbar: Boolean = false,
    onOpenPlaylist: (RemotePlaylist) -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    var selectionMode by remember(src.id, vm.onlineQuery) { mutableStateOf(false) }
    var selectedIds by remember(src.id, vm.onlineQuery) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDownloadTracks by remember(src.id, vm.onlineQuery) { mutableStateOf<List<RemoteTrack>?>(null) }
    var actionTrack by remember(src.id, vm.onlineQuery) { mutableStateOf<RemoteTrack?>(null) }

    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }
    BackHandler(enabled = selectionMode) { exitSelection() }

    val visibleTracks = vm.visibleOnlineResults.map { it.second }
    val selectedTracks = remember(visibleTracks, selectedIds) {
        if (selectedIds.isEmpty()) emptyList()
        else visibleTracks.filter { it.remoteId in selectedIds }
    }

    when {
        // 输入框为空 = 首页：登录后展示方案 C 杂志画报 Hero 版，未登录保持简化推荐页
        vm.onlineQuery.isEmpty() && !vm.onlineSearching -> OnlineHomeContent(
            vm = vm,
            onOpenPlaylist = onOpenPlaylist,
            hint = "输入歌名、歌手或专辑名，停一下就会自动搜索。"
        )

        // 有结果、但开关把能播的都过滤掉了 —— 说明白并给一键恢复
        vm.onlineResults.isNotEmpty() && vm.visibleOnlineResults.isEmpty() -> EmptyState(
            icon = SonanceIcons.Search,
            title = "这 ${vm.onlineResults.size} 首都播不了",
            subtitle = "都是会员 / 需购买的歌。",
            actionLabel = "显示全部歌曲",
            onAction = { vm.changeOnlineHideUnplayable(false) }
        )

        // ★ 有结果就**先显示着**，不要换成转圈。
        // 输入即搜索是"每停一次手就搜一次"，把列表换成 spinner 会一直闪；
        // 加载状态改由上面的「搜索中…」提示，列表本身保持稳定。
        vm.visibleOnlineResults.isNotEmpty() -> {
            Column(Modifier.fillMaxSize()) {
                if (vm.playlistDownloadActive &&
                    vm.playlistDownloadOwner == SonanceViewModel.DOWNLOAD_OWNER_SEARCH
                ) {
                    OnlineDownloadProgressBanner(
                        done = vm.playlistDownloadDone,
                        total = vm.playlistDownloadTotal,
                        saved = vm.playlistDownloadSaved,
                        skipped = vm.playlistDownloadSkipped,
                        failed = vm.playlistDownloadFailed,
                        onCancel = { vm.cancelPlaylistDownload() }
                    )
                }

                val allSelected = selectedIds.size >= visibleTracks.size
                val selectedDownloadable = selectedTracks.count { it.playable && !vm.isDownloaded(it) }
                val playableTotal = vm.onlineResults.count { it.playable }
                OnlineTrackListToolbar(
                    selectionMode = selectionMode,
                    selectedCount = selectedTracks.size,
                    totalCount = visibleTracks.size,
                    downloadableCount = selectedDownloadable,
                    allSelected = allSelected,
                    downloading = vm.playlistDownloadActive,
                    hintText = "长按单曲可下载 / 入队 · 共 ${visibleTracks.size} 首",
                    leadingNote = if (mergeStatusIntoToolbar) "长按可操作" else null,
                    trailingContent = if (mergeStatusIntoToolbar) {
                        {
                            PlayableFilterControls(
                                playable = playableTotal,
                                total = vm.onlineResults.size,
                                hideUnplayable = vm.onlineHideUnplayable,
                                onToggleHideUnplayable = { vm.changeOnlineHideUnplayable(!vm.onlineHideUnplayable) }
                            )
                        }
                    } else null,
                    onEnterSelect = {
                        selectedIds = emptySet()
                        selectionMode = true
                    },
                    onToggleSelectAll = {
                        selectedIds = if (allSelected) {
                            emptySet()
                        } else {
                            visibleTracks.mapTo(HashSet()) { it.remoteId }
                        }
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

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = vm.visibleOnlineResults,
                        // remoteId 在单个音源内唯一，正好当 key
                        key = { _, pair -> pair.second.remoteId }
                    ) { _, pair ->
                        // first 是**原始下标**，播放队列按 onlineResults 建的，必须用它对上
                        val (index, track) = pair
                        val songId = remoteSongId(src.id, track.remoteId)
                        val checked = track.remoteId in selectedIds
                        OnlineTrackRow(
                            track = track,
                            thumbUrl = vm.onlineThumbUrl(track, THUMB_PX),
                            sourceLabel = src.label,
                            // 存过本地的标出来：点它播的是文件，不再联网
                            downloaded = vm.isDownloaded(track),
                            isCurrent = playerState.mediaId == songId,
                            isPlaying = playerState.isPlaying,
                            selectionMode = selectionMode,
                            selected = checked,
                            onLongClick = {
                                if (!selectionMode) {
                                    hapticTick(context)
                                    actionTrack = track
                                } else {
                                    selectedIds = if (checked) {
                                        selectedIds - track.remoteId
                                    } else {
                                        selectedIds + track.remoteId
                                    }
                                }
                            },
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (checked) {
                                        selectedIds - track.remoteId
                                    } else {
                                        selectedIds + track.remoteId
                                    }
                                } else {
                                    vm.playOnline(index)
                                }
                            }
                        )
                    }

                    item {
                        Column(Modifier.fillMaxWidth()) {
                            OnlineListFooter(
                                loadingMore = vm.onlineLoadingMore,
                                loadMoreFailed = vm.onlineLoadMoreFailed,
                                hasMore = vm.onlineHasMore,
                                endLabel = "没有更多了 · 共 ${vm.onlineResults.size} 首",
                                onRetry = { vm.retryLoadMoreOnline() }
                            )
                            // 把规则说清楚，省得用户以为是坏了。登录前后说得不一样：
                            // 未登录时"VIP 歌播不了"是常态，登录后还播不了一般是版权问题
                            Text(
                                text = if (vm.neteaseAccount == null) {
                                    "标 VIP 的歌需要登录网易云会员账号才能播放，点右上角「登录网易云」即可登录。"
                                } else {
                                    "标 VIP 的歌已按你的会员权益判断；灰掉的是当前账号仍拿不到地址的（下架或需单独购买）。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = skin.textMuted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            actionTrack?.let { target ->
                RowActionSheet(
                    title = target.title,
                    subtitle = listOf(target.artist, target.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { null },
                    actions = listOf(
                        RowSheetAction(
                            label = if (vm.isDownloaded(target)) "下载（已保存到本地）" else "下载"
                        ) {
                            vm.downloadSingleOnlineTrack(target)
                        },
                        RowSheetAction("加入队列") {
                            vm.enqueueOnlineTracks(listOf(target))
                        }
                    ),
                    onDismiss = { actionTrack = null }
                )
            }

            pendingDownloadTracks?.let { targetTracks ->
                OnlineDownloadConfirmDialog(
                    collectionTitle = if (vm.onlineQuery.isNotBlank()) "搜索「${vm.onlineQuery}」" else "在线搜索结果",
                    tracks = targetTracks,
                    isPartial = targetTracks.size < visibleTracks.size,
                    vm = vm,
                    onDismiss = { pendingDownloadTracks = null },
                    onConfirm = {
                        pendingDownloadTracks = null
                        exitSelection()
                        vm.downloadSearchTracks(targetTracks)
                    }
                )
            }
        }

        // 还没有任何结果时才有必要转圈（第一次搜索）
        vm.onlineSearching -> LoadingState(
            title = "正在搜索…",
            subtitle = "音源：${src.label}"
        )

        else -> EmptyState(
            icon = SonanceIcons.Search,
            // 三种"没结果"要分清楚：还没搜 / 搜失败 / 真的没有
            title = when {
                vm.onlineSearchedFor.isEmpty() -> "搜点什么"
                vm.onlineSearchFailed -> "搜索失败"
                else -> "没有结果"
            },
            subtitle = vm.onlineNotice
                ?: "输入歌名、歌手或专辑名，停一下就会自动搜索。"
        )
    }
}

/** 歌单搜索结果 */
@Composable
private fun PlaylistSearchBody(
    vm: SonanceViewModel,
    src: OnlineSource,
    listState: LazyListState,
    onOpenPlaylist: (RemotePlaylist) -> Unit
) {
    when {
        vm.onlineQuery.isEmpty() && !vm.onlineSearching -> OnlineHomeContent(
            vm = vm,
            onOpenPlaylist = onOpenPlaylist,
            hint = "输入关键词搜索歌单，或从上面的推荐里挑一个。"
        )

        vm.onlinePlaylistResults.isNotEmpty() -> LazyColumn(
            state = listState,
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
                items = vm.onlinePlaylistResults,
                key = { _, p -> p.remoteId }
            ) { _, playlist ->
                PlaylistRow(
                    playlist = playlist,
                    thumbUrl = vm.playlistThumbUrl(playlist, THUMB_PX),
                    onClick = { onOpenPlaylist(playlist) }
                )
            }

            item {
                OnlineListFooter(
                    loadingMore = vm.onlineLoadingMore,
                    loadMoreFailed = vm.onlineLoadMoreFailed,
                    hasMore = vm.onlineHasMore,
                    endLabel = "没有更多了 · 共 ${vm.onlinePlaylistResults.size} 个歌单",
                    onRetry = { vm.retryLoadMoreOnline() }
                )
            }
        }

        vm.onlineSearching -> LoadingState(
            title = "正在搜索歌单…",
            subtitle = "音源：${src.label}"
        )

        else -> EmptyState(
            icon = SonanceIcons.Search,
            title = when {
                vm.onlineSearchedFor.isEmpty() -> "搜点什么"
                vm.onlineSearchFailed -> "搜索失败"
                else -> "没有结果"
            },
            subtitle = vm.onlineNotice ?: "输入关键词，停一下就会自动搜索歌单。"
        )
    }
}

/**
 * 在线页右上角（对齐下方「只看可播」开关）：未登录显示「登录网易云 ▾」，已登录显示头像 + 昵称 + VIP 徽标。
 */
@Composable
private fun OnlineAccountActionPill(
    account: NeteaseAccount?,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    if (account == null) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(skin.accentGlow)
                .border(1.dp, skin.accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(skin.accent)
            )
            Text(
                text = "登录网易云 ▾",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.accent,
                maxLines = 1
            )
        }
    } else {
        val avatar = rememberRemoteCover(account.avatarUrl.takeIf { it.isNotBlank() }, 96)
        Row(
            modifier = Modifier
                .widthIn(max = 176.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(skin.surface2)
                .border(1.dp, skin.accentDim, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(skin.surface3),
                contentAlignment = Alignment.Center
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = account.nickname.take(1).ifBlank { "云" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = skin.textMuted
                    )
                }
            }
            Text(
                text = account.nickname.ifBlank { "网易云" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (account.isVip) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(skin.accent.copy(alpha = 0.22f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "VIP",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        fontWeight = FontWeight.Bold,
                        color = skin.accent
                    )
                }
            }
        }
    }
}

/** 根据登录态自动切换「简化未登录首页」与「方案 C · 杂志画报 Hero 版」。 */
@Composable
private fun OnlineHomeContent(
    vm: SonanceViewModel,
    onOpenPlaylist: (RemotePlaylist) -> Unit,
    hint: String
) {
    val account = vm.neteaseAccount
    if (account == null) {
        HomeRecommend(vm = vm, onOpenPlaylist = onOpenPlaylist, hint = hint)
    } else {
        LoggedInHeroHome(vm = vm, account = account, onOpenPlaylist = onOpenPlaylist)
    }
}

/**
 * 方案 C · 杂志画报 Hero 版：
 * 非对称 Bento Hero（每日私人推荐 + 我喜欢的 + 私人 FM）→ 我的云歌单横向卷轴 → 精选探索广场双列网格。
 */
@Composable
private fun LoggedInHeroHome(
    vm: SonanceViewModel,
    account: NeteaseAccount,
    onOpenPlaylist: (RemotePlaylist) -> Unit
) {
    val skin = LocalSkin.current
    val dailyTracks = vm.onlineDailyTracks
    val likedPlaylist = vm.onlineLikedPlaylist
    val userPlaylists = vm.onlineUserPlaylists
    val dateTag = remember { SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date()) }
    val heroCoverUrl = remember(dailyTracks) {
        dailyTracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
    }
    val heroCover = rememberRemoteCover(heroCoverUrl, 320)

    // 精选探索广场：把热门官方榜与推荐歌单去重后拆开展示（榜单用紧凑横卡，推荐用双列封面卡）
    val toplists = remember(vm.onlineToplists) { vm.onlineToplists.take(4) }
    val recommends = remember(vm.onlineToplists, vm.recommendedPlaylists) {
        val toplistIds = toplists.mapTo(HashSet()) { it.remoteId }
        vm.recommendedPlaylists.filter { it.remoteId !in toplistIds }
    }

    val homeListState = rememberLazyListState()
    val heroNearBottom by remember {
        derivedStateOf {
            val info = homeListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(
        homeListState,
        recommends.size,
        vm.recommendedHasMore,
        vm.recommendedLoadingMore
    ) {
        if (vm.recommendedHasMore && recommends.isNotEmpty()) {
            snapshotFlow { heroNearBottom }.first { it }
            vm.loadMoreRecommendedPlaylists()
        }
    }

    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 非对称 Bento Hero
        item(key = "bento_hero") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(174.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 左主卡：每日私人推荐
                Box(
                    modifier = Modifier
                        .weight(1.22f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    skin.accent.copy(alpha = 0.55f),
                                    skin.surface3
                                )
                            )
                        )
                        .border(1.dp, skin.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable { onOpenPlaylist(vm.dailyRecommendPlaylist()) }
                ) {
                    if (heroCover != null) {
                        Image(
                            bitmap = heroCover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.24f),
                                        0.45f to Color.Black.copy(alpha = 0.54f),
                                        1f to Color.Black.copy(alpha = 0.90f)
                                    )
                                )
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.14f),
                                        1f to Color.Black.copy(alpha = 0.68f)
                                    )
                                )
                        )
                    }

                    // 顶部日期角标
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.42f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "DAILY MIX",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = dateTag,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = skin.accent
                        )
                    }

                    // 底部文案与一键播按钮
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "每日私人推荐",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (dailyTracks.isNotEmpty()) {
                                    "${dailyTracks.size} 首 · ${account.nickname.ifBlank { "专属口味" }}"
                                } else if (vm.onlineHomeLoading) {
                                    "正在生成今日歌单…"
                                } else {
                                    "点此查看今日推荐"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.84f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(skin.accent.copy(alpha = 0.92f))
                                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                .clickable { vm.playDailyRecommend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = SonanceIcons.Play,
                                contentDescription = "播放每日推荐",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // 右侧两张叠放小卡：我喜欢的 + 私人 FM
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val likedThumb = likedPlaylist?.coverUrl?.let { vm.playlistThumbUrl(likedPlaylist, THUMB_PX) }
                    val likedCover = rememberRemoteCover(likedThumb, THUMB_PX)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF4A1D28),
                                        skin.surface2
                                    )
                                )
                            )
                            .border(1.dp, skin.accentDim.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .clickable {
                                if (likedPlaylist != null) onOpenPlaylist(likedPlaylist)
                                else vm.loadOnlineHome(force = true)
                            }
                    ) {
                        if (likedCover != null) {
                            Image(
                                bitmap = likedCover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.45f)
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            0f to Color(0xFF3E1823).copy(alpha = 0.92f),
                                            1f to Color(0xFF28121B).copy(alpha = 0.60f)
                                        )
                                    )
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(11.dp)
                        ) {
                            Icon(
                                imageVector = SonanceIcons.HeartFilled,
                                contentDescription = null,
                                tint = skin.accent.copy(alpha = 0.88f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.TopEnd)
                            )
                            Column(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(end = 74.dp)
                            ) {
                                Text(
                                    text = "我喜欢的",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = likedPlaylist?.let { "${it.trackCount} 首红心单曲" }
                                        ?: if (vm.onlineHomeLoading) "同步歌单中…" else "点击同步歌单",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(skin.accent.copy(alpha = 0.88f))
                                    .clickable(enabled = !vm.intelligenceLoading) {
                                        vm.playIntelligenceMode()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (vm.intelligenceLoading) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 1.5.dp,
                                        modifier = Modifier.size(10.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = SonanceIcons.HeartFilled,
                                        contentDescription = "心动模式",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Text(
                                    text = if (vm.intelligenceLoading) "开启中" else "心动",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF1D2C3F),
                                        skin.surface2
                                    )
                                )
                            )
                            .border(1.dp, skin.accentDim.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .clickable { vm.playPersonalFm() }
                            .padding(11.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.30f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "FM RADIO",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9AC9FF)
                            )
                        }
                        Icon(
                            imageVector = SonanceIcons.Waveform,
                            contentDescription = null,
                            tint = Color(0xFF7BB8FF).copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.TopEnd)
                        )
                        Column(Modifier.align(Alignment.BottomStart)) {
                            Text(
                                text = "私人 FM",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "无限漫游 · 顺耳电台",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 2. 我的云歌单横向卷轴
        item(key = "user_playlists_section") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "我的云歌单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.text
                    )
                    Text(
                        text = if (vm.onlineHomeLoading) "同步中…"
                        else if (userPlaylists.isNotEmpty()) "${userPlaylists.size} 个歌单"
                        else "点击刷新",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!vm.onlineHomeLoading && userPlaylists.isEmpty()) skin.accent else skin.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !vm.onlineHomeLoading) { vm.loadOnlineHome(force = true) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (userPlaylists.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(items = userPlaylists, key = { it.remoteId }) { pl ->
                            PlaylistCard(
                                playlist = pl,
                                thumbUrl = vm.playlistThumbUrl(pl, THUMB_PX),
                                showCreator = pl.creatorName.isNotBlank() && pl.creatorName != account.nickname,
                                modifier = Modifier.width(120.dp),
                                onClick = { onOpenPlaylist(pl) }
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(skin.surface2)
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = if (vm.onlineHomeLoading) "正在从网易云同步你的歌单…" else "暂无同步到个人歌单，点击右上角账号可重新登录或刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                    }
                }
            }
        }

        // 3. 精选探索广场（榜单紧凑横卡 + 推荐双列网格）
        if (toplists.isNotEmpty() || recommends.isNotEmpty()) {
            item(key = "explore_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "精选探索广场",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.text
                    )
                    Text(
                        text = "热歌榜 · 每日精选",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
            }

            if (toplists.isNotEmpty()) {
                items(toplists.chunked(2), key = { "toplist_" + it.first().remoteId }) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { p ->
                            ToplistCompactCard(
                                playlist = p,
                                thumbUrl = vm.playlistThumbUrl(p, THUMB_PX),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenPlaylist(p) }
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            items(recommends.chunked(2), key = { "explore_" + it.first().remoteId }) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    rowItems.forEach { p ->
                        PlaylistCard(
                            playlist = p,
                            thumbUrl = vm.playlistThumbUrl(p, THUMB_PX),
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenPlaylist(p) }
                        )
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item(key = "explore_footer") {
                OnlineListFooter(
                    loadingMore = vm.recommendedLoadingMore,
                    loadMoreFailed = vm.recommendedLoadMoreFailed,
                    hasMore = vm.recommendedHasMore,
                    endLabel = "已展示全部精选歌单 · 共 ${recommends.size} 个",
                    onRetry = { vm.retryLoadMoreRecommendedPlaylists() }
                )
            }
        } else if (vm.recommendedLoading || vm.onlineHomeLoading) {
            item(key = "explore_loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = skin.accent,
                        trackColor = skin.surface3,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * 首页（输入框为空时）：推荐歌单 + 搜索提示。
 *
 * 推荐拉不到就退回普通空态 —— 三种情况分清楚：还在拉 / 拉失败（可重试）/ 音源没给。
 */
@Composable
private fun HomeRecommend(
    vm: SonanceViewModel,
    onOpenPlaylist: (RemotePlaylist) -> Unit,
    hint: String
) {
    val skin = LocalSkin.current
    val recs = vm.recommendedPlaylists

    if (recs.isEmpty()) {
        when {
            vm.recommendedLoading -> LoadingState(
                title = "正在拉取推荐…",
                subtitle = vm.onlineSource?.label?.let { "音源：$it" }
            )

            vm.recommendedFailed -> EmptyState(
                icon = SonanceIcons.Search,
                title = "推荐拉取失败",
                subtitle = "检查一下网络，也可以直接在搜索框里搜。",
                actionLabel = "重试",
                onAction = { vm.retryRecommendedPlaylists() }
            )

            else -> EmptyState(icon = SonanceIcons.Search, title = "搜点什么", subtitle = hint)
        }
        return
    }

    val recListState = rememberLazyListState()
    val recNearBottom by remember {
        derivedStateOf {
            val info = recListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(
        recListState,
        recs.size,
        vm.recommendedHasMore,
        vm.recommendedLoadingMore
    ) {
        if (vm.recommendedHasMore && recs.isNotEmpty()) {
            snapshotFlow { recNearBottom }.first { it }
            vm.loadMoreRecommendedPlaylists()
        }
    }

    LazyColumn(
        state = recListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "推荐歌单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
        }

        // 两列网格：按 2 个一组切行。歌单本身没有"行"的概念，
        // 这样切出来的行以第一个的 remoteId 当 key 就够稳定了。
        items(recs.chunked(2), key = { it.first().remoteId }) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                rowItems.forEach { p ->
                    PlaylistCard(
                        playlist = p,
                        thumbUrl = vm.playlistThumbUrl(p, THUMB_PX),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenPlaylist(p) }
                    )
                }
                // 奇数个时补一个空位，否则最后一张会横向拉伸成整行宽
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item(key = "rec_footer") {
            Column(Modifier.fillMaxWidth()) {
                OnlineListFooter(
                    loadingMore = vm.recommendedLoadingMore,
                    loadMoreFailed = vm.recommendedLoadMoreFailed,
                    hasMore = vm.recommendedHasMore,
                    endLabel = "已展示全部推荐歌单 · 共 ${recs.size} 个",
                    onRetry = { vm.retryLoadMoreRecommendedPlaylists() }
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }
        }
    }
}

/** 官方榜紧凑横卡：收住高亮大图的视觉抢眼，方便一屏多看几张榜 */
@Composable
private fun ToplistCompactCard(
    playlist: RemotePlaylist,
    thumbUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2.copy(alpha = 0.82f))
            .border(1.dp, skin.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PlaceholderCover(
                    seed = playlist.name,
                    showMark = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (playlist.trackCount > 0) "TOP · ${playlist.trackCount} 首" else "官方榜",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 首页推荐里的一张歌单卡片（两列网格） */
@Composable
private fun PlaylistCard(
    playlist: RemotePlaylist,
    thumbUrl: String?,
    showCreator: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PlaceholderCover(
                    seed = playlist.name,
                    showMark = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 曲目数角标：压在封面右上角，省得单独占一行
            if (playlist.trackCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${playlist.trackCount} 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = skin.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (showCreator && playlist.creatorName.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = playlist.creatorName,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 歌单搜索结果里的一行 */
@Composable
private fun PlaylistRow(
    playlist: RemotePlaylist,
    thumbUrl: String?,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp)) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                PlaceholderCover(
                    seed = playlist.name,
                    showMark = false,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (playlist.creatorName.isNotBlank()) append("${playlist.creatorName} · ")
                    append("${playlist.trackCount} 首")
                },
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 列表页脚：翻页中 / 翻页失败（可点重试）/ 到底了。
 *
 * 失败给一个可点的重试：网络抖一下不该让用户重新搜一遍。
 */
@Composable
private fun OnlineListFooter(
    loadingMore: Boolean,
    loadMoreFailed: Boolean,
    hasMore: Boolean,
    endLabel: String,
    onRetry: () -> Unit
) {
    val skin = LocalSkin.current
    when {
        loadingMore -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = skin.accent,
                trackColor = skin.surface3,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp)
            )
        }

        loadMoreFailed -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "加载失败，点击重试",
                style = MaterialTheme.typography.bodySmall,
                color = skin.accent
            )
        }

        !hasMore -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            CountLabel(endLabel)
        }
    }
}

/**
 * 一条搜索结果。
 *
 * 不复用 [com.sonance.player.ui.components.SongRow]：那个组件带收藏、下一首播放、
 * 序号、专辑跳转，而这里的数据是 [RemoteTrack]（还没有变成 [com.sonance.player.data.Song]），
 * 硬套要造一堆假值，还会把用不上的按钮画出来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnlineTrackRow(
    track: RemoteTrack,
    thumbUrl: String?,
    /** 音源显示名。小字标出来，让"这首歌是从哪来的"看得见 */
    sourceLabel: String,
    /** 这首已经存到本地了 —— 点它播的是文件，不再联网取流 */
    downloaded: Boolean = false,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    // 不可播的整行压暗，一眼能看出"这条点不了"
    val rowAlpha = if (track.playable) 1f else 0.45f
    val rowBg = when {
        selectionMode && selected -> skin.accentGlow
        isCurrent && !selectionMode -> skin.accentGlow
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) skin.accent else Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        color = if (selected) skin.accent else skin.textDim,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = SonanceIcons.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Box(Modifier.size(44.dp)) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .alpha(rowAlpha)
                        .clip(RoundedCornerShape(7.dp))
                )
            } else {
                PlaceholderCover(
                    seed = track.album + track.artist,
                    showMark = false,
                    modifier = Modifier.size(44.dp)
                )
            }
            // 正在播这一首：在封面上压一个跳动的小均衡器
            if (isCurrent) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBars(
                        active = isPlaying,
                        color = skin.accent,
                        barWidth = 2.5.dp,
                        height = 15.dp
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isCurrent) skin.accent else skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(rowAlpha)
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .alpha(rowAlpha)
                )
                if (track.album.isNotBlank()) {
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
                    Text(
                        text = track.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alpha(rowAlpha)
                    )
                }
                // 来源。不设 weight —— 让它保持固有宽度，歌名/专辑过长时先被压缩的是它们
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim,
                    modifier = Modifier.alpha(rowAlpha)
                )
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim,
                    maxLines = 1,
                    modifier = Modifier.alpha(rowAlpha)
                )
            }
        }

        if (track.vip) {
            Spacer(Modifier.width(8.dp))
            VipBadge(playable = track.playable)
        } else if (!track.playable) {
            // 不是 VIP 还播不了，基本就是版权下架 / 地区限制 —— 说"无版权"比不解释更清楚
            Spacer(Modifier.width(8.dp))
            UnavailableBadge()
        }

        if (downloaded) {
            Spacer(Modifier.width(6.dp))
            DownloadedBadge()
        }

        if (track.durationMs > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                modifier = Modifier.alpha(rowAlpha)
            )
        }
    }
}

/**
 * 「VIP」小徽标。用强调色而不是固定的金色，才能跟着皮肤走。
 *
 * [playable] 为 false（未登录 / 非会员）时降成中性色：那是一句"你需要会员"的提示，
 * 不是一条可以点的权益。可播时用强调色，表示"这首歌走会员通道已经放行"。
 */
@Composable
private fun VipBadge(playable: Boolean = false) {
    val skin = LocalSkin.current
    val fg = if (playable) skin.accent else skin.textMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (playable) skin.accentGlow else skin.surface2)
            .border(1.dp, if (playable) skin.accentDim else skin.surface2, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "VIP",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

/** 「无版权」徽标：确认拿不到地址、又不是 VIP 权益问题的那一类 */
@Composable
private fun UnavailableBadge() {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(skin.surface2)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "无版权",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = skin.textMuted
        )
    }
}

/**
 * 「已存」小徽标 —— 这首歌已经在本地有一份，点它播的是文件、不再联网。
 *
 * 用**对勾**而不是下载箭头：它说的是"这里已经有一份了"（状态），
 * 不是"点这里下载"（动作）。和 VIP 徽标同一套底色，跟着皮肤走。
 */
@Composable
private fun DownloadedBadge() {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(skin.accentGlow)
            .border(1.dp, skin.accentDim, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = SonanceIcons.Check,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.size(10.dp)
        )
        Text(
            text = "已存",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = skin.accent
        )
    }
}

/**
 * 搜索历史：输入框为空时显示的一排胶囊。
 *
 * ## 为什么是胶囊横排，而不是下拉框
 *
 * 它和「音源切换」那排胶囊在视觉上是同一个东西，用户的肌肉记忆可以直接复用；
 * 横向滑动，超出就滑 —— 竖着占地方会把搜索结果往下挤。
 *
 * ## 为什么只有输入框为空时才显示
 *
 * 用户一开始打字，说明他正要搜一个**不在历史里**的词，
 * 这时候历史排在正中间只会挡路。
 *
 * 每个胶囊整体可点（= 搜这个词），右侧的 × 单独可点（= 只删这一条）。
 * 内层的 clickable 会消费掉这次点击，不会同时触发外层的搜索。
 */
@Composable
private fun SearchHistoryRow(
    items: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "最近",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim,
            modifier = Modifier.padding(end = 6.dp)
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // key 用词条本身：删掉某一条时，Compose 只需要处理那一个，
            // 不会把整排胶囊全部重新摆一遍（会看到闪）
            items(items, key = { it }) { term ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.surface2.copy(alpha = 0.65f))
                        .clickable { onPick(term) }
                        .padding(start = 9.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = term,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = SonanceIcons.Close,
                        contentDescription = "删除「$term」",
                        tint = skin.textDim,
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(term) }
                            .padding(2.dp)
                    )
                }
            }
            item {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * 「只看可播」开关。
 *
 * 用 Switch 而不是图标按钮：这是**真的过滤**（列表会少掉几行），
 * 开关能一眼看出当前是开是关；图标按钮看不出状态，容易让人以为没生效。
 *
 * 缩到 0.72 是为了和 44dp 的搜索框持平 —— Material 默认的 Switch 比它高，
 * 原样放进去会顶出搜索框那一行。
 */
@Composable
private fun PlayableFilterToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    height: Dp
) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (checked) skin.accentGlow else skin.surface3)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.72f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = skin.accent,
                checkedTrackColor = skin.accentDim,
                uncheckedThumbColor = skin.textDim,
                uncheckedTrackColor = skin.surface3,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = skin.accent,
                disabledUncheckedThumbColor = skin.textDim
            )
        )
    }
}

/** 搜索列表的缩略图边长。100 KB/张，比原图小 19 倍 */
private const val THUMB_PX = 200

/** 输入停顿多久才发请求。太短会边打字边发，太长会显得迟钝 */
private const val SEARCH_DEBOUNCE_MS = 450L
