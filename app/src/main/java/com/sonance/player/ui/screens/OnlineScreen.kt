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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.OnlineSources
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
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
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

    // ---- 防抖搜索 ----
    // 旋转屏幕 / 从别的页面切回来时 LaunchedEffect 会重启，但那时结果还在，
    // 靠 onlineSearchedFor 认出来直接跳过，不白打一次接口。
    LaunchedEffect(vm.onlineQuery) {
        val q = vm.onlineQuery.trim()
        if (q.isEmpty()) {
            if (vm.onlineResults.isNotEmpty()) vm.clearOnlineSearch()
            return@LaunchedEffect
        }
        if (q == vm.onlineSearchedFor) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        // 防抖期间用户又敲了字就别搜了，等下一次触发
        if (vm.onlineQuery.trim() == q) vm.searchOnline(q)
    }

    Column(modifier.fillMaxSize()) {

        // ---- 搜索框 ----
        SearchField(
            value = vm.onlineQuery,
            onValueChange = { vm.onlineQuery = it },
            placeholder = "搜索在线歌曲",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (dense) 4.dp else 10.dp),
            height = if (dense) 38.dp else 44.dp,
            onSearch = { vm.searchOnline() }
        )

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
            if (vm.onlineResults.isNotEmpty()) {
                val playable = vm.onlineResults.count { it.playable }
                CountLabel("可播 $playable / ${vm.onlineResults.size}")
            }
        }

        // ---- 正文 ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.onlineSearching -> LoadingState(
                    title = "正在搜索…",
                    subtitle = "音源：${src.label}"
                )

                vm.onlineResults.isEmpty() -> EmptyState(
                    icon = SonanceIcons.Search,
                    title = if (vm.onlineSearchedFor.isEmpty()) "搜点什么" else "没有结果",
                    subtitle = vm.onlineNotice
                        ?: "输入歌名、歌手或专辑名，停一下就会自动搜索。"
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
                        items = vm.onlineResults,
                        // remoteId 在单个音源内唯一，正好当 key
                        key = { _, t -> t.remoteId }
                    ) { index, track ->
                        val songId = remoteSongId(src.id, track.remoteId)
                        OnlineTrackRow(
                            track = track,
                            thumbUrl = vm.onlineThumbUrl(track, THUMB_PX),
                            isCurrent = playerState.mediaId == songId,
                            isPlaying = playerState.isPlaying,
                            onClick = { vm.playOnline(index) }
                        )
                    }

                    // 尾巴上把规则说清楚，省得用户以为是坏了
                    item {
                        Text(
                            text = "标 VIP 的歌需要会员，取流接口拿不到播放地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)
                        )
                    }
                }
            }
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
private fun OnlineTrackRow(
    track: RemoteTrack,
    thumbUrl: String?,
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
            }
        }

        if (track.vip) {
            Spacer(Modifier.width(8.dp))
            VipBadge()
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

/** 搜索列表的缩略图边长。100 KB/张，比原图小 19 倍 */
private const val THUMB_PX = 200

/** 输入停顿多久才发请求。太短会边打字边发，太长会显得迟钝 */
private const val SEARCH_DEBOUNCE_MS = 450L
