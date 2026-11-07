package com.sonance.player.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.SonanceViewModel.OnlineSearchMode
import com.sonance.player.data.OnlineSource
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.formatDuration
import com.sonance.player.data.remoteSongId
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.ChipsRow
import com.sonance.player.ui.components.CountLabel
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.EqualizerBars
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.LocalDenseLayout
import com.sonance.player.ui.components.PlaceholderCover
import com.sonance.player.ui.components.SearchField
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

    // ---- 首页推荐歌单：进页面 / 换音源时拉一次 ----
    // loadRecommendedPlaylists 内部有"已经有结果就不重复拉"的判断，这里放心调
    LaunchedEffect(vm.onlineMusicEnabled, vm.onlineSourceId) {
        vm.loadRecommendedPlaylists()
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

        // ---- 搜索对象切换：单曲 / 歌单 ----
        // 右边留一个「当前音源」快捷切换：装了插件后这里才出现（没装就不占位置）。
        // 和设置页那排 chips 读写同一个 onlineSourceId，不会出现两份状态。
        var sourceMenuExpanded by remember { mutableStateOf(false) }
        val skin = LocalSkin.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (dense) 4.dp else 10.dp),
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
            if (OnlineSources.all.size > 1) {
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        text = (OnlineSources.byId(vm.onlineSourceId)?.label ?: "音源") + " ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sourceMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false }
                    ) {
                        OnlineSources.all.forEach { source ->
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
        }

        // ---- 搜索框 + 「只看可播」开关 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (dense) 6.dp else 10.dp),
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
                height = if (dense) 38.dp else 44.dp,
                onSearch = { vm.searchOnline() }
            )
            // 「只看可播」只对单曲有意义 —— 歌单是整体，没有"可播"这个属性
            if (vm.onlineSearchMode == OnlineSearchMode.SINGLE) {
                Spacer(Modifier.width(10.dp))
                PlayableFilterToggle(
                    checked = vm.onlineHideUnplayable,
                    onCheckedChange = { vm.changeOnlineHideUnplayable(it) },
                    height = if (dense) 38.dp else 44.dp
                )
            }
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

        // ---- 音源切换 + 结果统计 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (dense) 8.dp else 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (OnlineSources.all.size > 1) {
                ChipsRow(
                    chips = OnlineSources.all.map { it.label to (it.id == vm.onlineSourceId) },
                    onSelect = { vm.changeOnlineSource(OnlineSources.all[it].id) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                CountLabel("音源 · ${src.label}", Modifier.weight(1f))
            }
            if (vm.onlineSearching) {
                // 搜索中就别显示旧的统计了 —— 那个数字马上会变，看着像卡住了
                CountLabel("搜索中…")
            } else if (vm.onlineSearchMode == OnlineSearchMode.PLAYLIST) {
                if (vm.onlinePlaylistResults.isNotEmpty()) {
                    CountLabel("${vm.onlinePlaylistResults.size} 个歌单")
                }
            } else if (vm.onlineResults.isNotEmpty()) {
                val playable = vm.onlineResults.count { it.playable }
                CountLabel("可播 $playable / ${vm.onlineResults.size}")
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
    onOpenPlaylist: (RemotePlaylist) -> Unit
) {
    val skin = LocalSkin.current
    when {
        // 输入框为空 = 首页：推荐歌单 + 搜索提示
        vm.onlineQuery.isEmpty() && !vm.onlineSearching -> HomeRecommend(
            vm = vm,
            onOpenPlaylist = onOpenPlaylist,
            hint = "输入歌名、歌手或专辑名，停一下就会自动搜索。"
        )

        // 有结果、但开关把能播的都过滤掉了 —— 说明白，别显示成一个空列表
        vm.onlineResults.isNotEmpty() && vm.visibleOnlineResults.isEmpty() -> EmptyState(
            icon = SonanceIcons.Search,
            title = "这 ${vm.onlineResults.size} 首都播不了",
            subtitle = "都是会员 / 需购买的歌。关掉搜索框右边的开关能看到完整列表。"
        )

        // ★ 有结果就**先显示着**，不要换成转圈。
        // 输入即搜索是"每停一次手就搜一次"，把列表换成 spinner 会一直闪；
        // 加载状态改由上面的「搜索中…」提示，列表本身保持稳定。
        vm.visibleOnlineResults.isNotEmpty() -> LazyColumn(
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
                items = vm.visibleOnlineResults,
                // remoteId 在单个音源内唯一，正好当 key
                key = { _, pair -> pair.second.remoteId }
            ) { _, pair ->
                // first 是**原始下标**，播放队列按 onlineResults 建的，必须用它对上
                val (index, track) = pair
                val songId = remoteSongId(src.id, track.remoteId)
                OnlineTrackRow(
                    track = track,
                    thumbUrl = vm.onlineThumbUrl(track, THUMB_PX),
                    sourceLabel = src.label,
                    // 存过本地的标出来：点它播的是文件，不再联网
                    downloaded = vm.isDownloaded(track),
                    isCurrent = playerState.mediaId == songId,
                    isPlaying = playerState.isPlaying,
                    onClick = { vm.playOnline(index) }
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
                    // 把规则说清楚，省得用户以为是坏了
                    Text(
                        text = "标 VIP 的歌需要会员，取流接口拿不到播放地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
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
        vm.onlineQuery.isEmpty() && !vm.onlineSearching -> HomeRecommend(
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
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

        item {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
        }
    }
}

/** 首页推荐里的一张歌单卡片（两列网格） */
@Composable
private fun PlaylistCard(
    playlist: RemotePlaylist,
    thumbUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
        if (playlist.creatorName.isNotBlank()) {
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
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(thumbUrl, THUMB_PX)
    // 不可播的整行压暗，一眼能看出"这条点不了"
    val rowAlpha = if (track.playable) 1f else 0.45f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) skin.accentGlow else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            VipBadge()
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

/** 「VIP」小徽标。用强调色而不是固定的金色，才能跟着皮肤走 */
@Composable
private fun VipBadge() {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(skin.accentGlow)
            .border(1.dp, skin.accentDim, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "VIP",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = skin.accent
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
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "最近",
            style = MaterialTheme.typography.labelMedium,
            color = skin.textMuted,
            modifier = Modifier.padding(end = 10.dp)
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // key 用词条本身：删掉某一条时，Compose 只需要处理那一个，
            // 不会把整排胶囊全部重新摆一遍（会看到闪）
            items(items, key = { it }) { term ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(skin.surface)
                        .clickable { onPick(term) }
                        .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = term,
                        style = MaterialTheme.typography.labelLarge,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = SonanceIcons.Close,
                        contentDescription = "删除「$term」",
                        tint = skin.textMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(term) }
                            .padding(3.dp)
                    )
                }
            }
            item {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelLarge,
                    color = skin.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
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
