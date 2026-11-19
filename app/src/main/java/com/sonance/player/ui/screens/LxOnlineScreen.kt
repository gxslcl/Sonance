package com.sonance.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.RemoteTrack
import com.sonance.player.data.lx.LxBoard
import com.sonance.player.data.lx.LxPlatform
import com.sonance.player.data.lx.LxQuality
import com.sonance.player.data.lx.LxSearchMode
import com.sonance.player.data.lx.LxSheetDetailTarget
import com.sonance.player.data.lx.LxSheetSummary
import com.sonance.player.data.remoteSongId
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.PlaceholderCover
import com.sonance.player.ui.components.TagChip
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/**
 * 洛雪自定义音源独立在线页面（与内置网易云 / 插件页完全分离）。
 *
 * 布局结构：
 * - 顶部脚本状态卡 + 多平台切换（酷我 / QQ / 酷狗 / 网易云）+ 一键导入 `.js`
 * - 搜索框（支持单曲 / 歌单切换与独立搜索历史）
 * - 默认探索区：精选歌单（带平台分类标签）+ 排行榜（按榜单分组）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LxOnlineScreen(
    vm: SonanceViewModel,
    playerState: PlayerUiState,
    onOpenSheet: (LxSheetDetailTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importLxScriptFromUri(uri)
    }

    var showScriptPicker by remember { mutableStateOf(false) }
    var showUrlImportDialog by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<RemoteTrack?>(null) }

    val activeScript = vm.lxActiveScript
    val availablePlatforms = remember(activeScript) {
        activeScript?.supportedCatalogPlatforms()?.takeIf { it.isNotEmpty() } ?: LxPlatform.ALL
    }
    val currentPlatform = remember(vm.lxPlatform, availablePlatforms) {
        availablePlatforms.firstOrNull { it.id == vm.lxPlatform } ?: availablePlatforms.first()
    }

    LaunchedEffect(vm.onlineMusicEnabled, currentPlatform.id) {
        if (vm.onlineMusicEnabled) {
            vm.loadLxHome()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部洛雪音源 & 平台控制条
        LxHeaderPanel(
            scriptName = activeScript?.meta?.name,
            scriptInfoLine = activeScript?.displayVersionLine(),
            qualityBadge = LxQuality.byId(vm.lxQuality).badge,
            importing = vm.lxImporting,
            platforms = availablePlatforms,
            selectedPlatformId = currentPlatform.id,
            onPickScript = { showScriptPicker = true },
            onImportClick = { showScriptPicker = true },
            onSelectPlatform = { vm.changeLxPlatform(it) }
        )

        if (!vm.onlineMusicEnabled) {
            EmptyState(
                icon = SonanceIcons.Cloud,
                title = "在线音乐未开启",
                subtitle = "洛雪曲库搜索、榜单、歌单、歌词与封面需要联网访问平台接口",
                actionLabel = "开启在线音乐",
                onAction = { vm.changeOnlineMusic(true) },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        vm.lxImportError?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(skin.surface2)
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.dismissLxImportError() }) {
                    Text("关闭", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                }
            }
        }

        if (activeScript == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(skin.accentGlow)
                    .border(1.dp, skin.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(SonanceIcons.Info, null, tint = skin.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "尚未导入洛雪 .js 音源：可浏览与搜索曲库，导入脚本后即可点播",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.text,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                PillButton(
                    label = "URL",
                    compact = true,
                    filled = false,
                    enabled = !vm.lxImporting,
                    onClick = { showUrlImportDialog = true },
                    modifier = Modifier.border(1.dp, skin.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(6.dp))
                PillButton(
                    label = "本地 .js",
                    compact = true,
                    enabled = !vm.lxImporting,
                    onClick = {
                        importLauncher.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"))
                    }
                )
            }
        }

        // 搜索栏 + 单曲/歌单模式切换
        LxSearchBar(
            query = vm.lxQuery,
            placeholder = "搜索${currentPlatform.displayName}歌曲、歌手或歌单",
            onQueryChange = { vm.updateLxQuery(it) },
            onSearch = {
                keyboard?.hide()
                vm.searchLx()
            },
            onClear = { vm.clearLxSearch() },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TagChip(
                label = "单曲",
                selected = vm.lxSearchMode == LxSearchMode.SONG,
                onClick = { vm.changeLxSearchMode(LxSearchMode.SONG) }
            )
            TagChip(
                label = "歌单",
                selected = vm.lxSearchMode == LxSearchMode.SHEET,
                onClick = { vm.changeLxSearchMode(LxSearchMode.SHEET) }
            )
            Spacer(Modifier.weight(1f))
            if (vm.lxQuery.isBlank()) {
                TagChip(
                    label = "精选歌单",
                    selected = vm.lxHomeSectionTab == 0,
                    onClick = { vm.changeLxHomeSectionTab(0) }
                )
                TagChip(
                    label = "排行榜",
                    selected = vm.lxHomeSectionTab == 1,
                    onClick = { vm.changeLxHomeSectionTab(1) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (vm.lxQuery.isNotBlank()) {
                when (vm.lxSearchMode) {
                    LxSearchMode.SONG -> LxSongSearchBody(
                        vm = vm,
                        platform = currentPlatform,
                        playerState = playerState,
                        onLongClickTrack = {
                            hapticTick(context)
                            actionTrack = it
                        }
                    )
                    LxSearchMode.SHEET -> LxSheetSearchBody(
                        vm = vm,
                        onOpenSheet = { s ->
                            onOpenSheet(
                                LxSheetDetailTarget(
                                    id = s.id,
                                    platform = s.platform,
                                    title = s.name,
                                    subtitle = buildString {
                                        if (s.creatorName.isNotBlank()) append(s.creatorName)
                                        if (s.trackCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${s.trackCount} 首")
                                        }
                                    },
                                    coverUrl = s.coverUrl,
                                    isBoard = false
                                )
                            )
                        }
                    )
                }
            } else {
                LxExploreBody(
                    vm = vm,
                    onOpenSheet = onOpenSheet
                )
            }
        }
    }

    if (showScriptPicker) {
        LxScriptPickerDialog(
            vm = vm,
            onImportLocalClick = {
                showScriptPicker = false
                importLauncher.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"))
            },
            onImportUrlClick = {
                showScriptPicker = false
                showUrlImportDialog = true
            },
            onDismiss = { showScriptPicker = false }
        )
    }

    if (showUrlImportDialog) {
        LxImportUrlDialog(
            importing = vm.lxImporting,
            onConfirm = { url ->
                showUrlImportDialog = false
                vm.importLxScriptFromUrl(url)
            },
            onDismiss = { showUrlImportDialog = false }
        )
    }

    val pendingTrack = actionTrack
    if (pendingTrack != null) {
        val trackIndex = remember(pendingTrack, vm.lxSongResults) {
            vm.lxSongResults.indexOfFirst { it.remoteId == pendingTrack.remoteId }.coerceAtLeast(0)
        }
        LxTrackActionDialog(
            track = pendingTrack,
            downloaded = vm.isLxTrackDownloaded(currentPlatform.id, pendingTrack.remoteId),
            onPlayNow = {
                actionTrack = null
                vm.playLxTrackAt(vm.lxSongResults, trackIndex, currentPlatform.id)
            },
            onEnqueue = {
                actionTrack = null
                vm.enqueueLxTracks(listOf(pendingTrack), currentPlatform.id)
            },
            onDownload = {
                actionTrack = null
                vm.downloadSingleLxTrack(pendingTrack, currentPlatform.id)
            },
            onDismiss = { actionTrack = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LxHeaderPanel(
    scriptName: String?,
    scriptInfoLine: String?,
    qualityBadge: String,
    importing: Boolean,
    platforms: List<LxPlatform>,
    selectedPlatformId: String,
    onPickScript: () -> Unit,
    onImportClick: () -> Unit,
    onSelectPlatform: (String) -> Unit
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .border(1.dp, skin.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(SonanceIcons.Cloud, null, tint = skin.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scriptName ?: "未导入洛雪音源",
                        style = MaterialTheme.typography.titleSmall,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (scriptName != null) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(skin.accentGlow)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                qualityBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.accent
                            )
                        }
                    }
                }
                if (!scriptInfoLine.isNullOrBlank()) {
                    Text(
                        scriptInfoLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = skin.accent
                )
                Spacer(Modifier.width(8.dp))
            }
            PillButton(
                label = if (scriptName != null) "切换" else "导入",
                compact = true,
                filled = scriptName == null,
                onClick = if (scriptName != null) onPickScript else onImportClick
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            platforms.forEach { p ->
                TagChip(
                    label = p.displayName,
                    selected = p.id == selectedPlatformId,
                    onClick = { onSelectPlatform(p.id) }
                )
            }
        }
    }
}

@Composable
private fun LxSearchBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .border(1.dp, skin.border2, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(SonanceIcons.Search, null, tint = skin.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                cursorBrush = SolidColor(skin.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(SonanceIcons.Close, "清空", tint = skin.textMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            PillButton(label = "搜索", compact = true, onClick = onSearch)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LxExploreBody(
    vm: SonanceViewModel,
    onOpenSheet: (LxSheetDetailTarget) -> Unit
) {
    val skin = LocalSkin.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (vm.lxHistory.isNotEmpty()) {
            item(key = "history") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("最近搜索", style = MaterialTheme.typography.labelLarge, color = skin.text)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "清空",
                            style = MaterialTheme.typography.labelMedium,
                            color = skin.textMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.clearLxHistory() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        vm.lxHistory.forEach { term ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(skin.surface2)
                                    .border(1.dp, skin.border, RoundedCornerShape(16.dp))
                                    .clickable { vm.searchLxFromHistory(term) }
                                    .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    term,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = skin.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    SonanceIcons.Close,
                                    "删除",
                                    tint = skin.textMuted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { vm.removeLxHistory(term) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (vm.lxHomeSectionTab == 0) {
            if (vm.lxSheetTags.isNotEmpty()) {
                item(key = "sheet_tags") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        vm.lxSheetTags.forEach { tag ->
                            TagChip(
                                label = tag.name,
                                selected = vm.lxSelectedSheetTag?.id == tag.id && vm.lxSelectedSheetTag?.name == tag.name,
                                onClick = { vm.selectLxSheetTag(tag) }
                            )
                        }
                    }
                }
            }

            when {
                vm.lxHomeSheetsLoading -> item(key = "sheets_loading") {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        LoadingState(title = "正在加载精选歌单…")
                    }
                }
                vm.lxHomeSheetsError != null -> item(key = "sheets_error") {
                    EmptyState(
                        icon = SonanceIcons.Album,
                        title = vm.lxHomeSheetsError ?: "歌单加载失败",
                        subtitle = "请检查网络或切换其他曲库平台",
                        actionLabel = "重试",
                        onAction = { vm.loadLxHome(force = true) },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
                vm.lxHomeSheets.isEmpty() -> item(key = "sheets_empty") {
                    EmptyState(
                        icon = SonanceIcons.Album,
                        title = "暂无歌单",
                        subtitle = "试试上方其他分类标签或搜索歌单关键词",
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                }
                else -> {
                    val rows = vm.lxHomeSheets.chunked(2)
                    itemsIndexed(
                        items = rows,
                        key = { idx, pair -> "sheet_row_${idx}_${pair.first().id}" }
                    ) { _, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { s ->
                                LxSquareCard(
                                    title = s.name,
                                    subtitle = buildString {
                                        if (s.creatorName.isNotBlank()) append(s.creatorName)
                                        if (s.trackCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${s.trackCount}首")
                                        }
                                    },
                                    coverUrl = s.coverUrl,
                                    seed = "lx_sheet_${s.id}",
                                    onClick = {
                                        onOpenSheet(
                                            LxSheetDetailTarget(
                                                id = s.id,
                                                platform = s.platform,
                                                title = s.name,
                                                subtitle = s.creatorName,
                                                coverUrl = s.coverUrl,
                                                isBoard = false
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            when {
                vm.lxToplistsLoading -> item(key = "boards_loading") {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        LoadingState(title = "正在加载排行榜…")
                    }
                }
                vm.lxToplistsError != null -> item(key = "boards_error") {
                    EmptyState(
                        icon = SonanceIcons.EqualizerBars,
                        title = vm.lxToplistsError ?: "排行榜加载失败",
                        subtitle = "请检查网络连接后重试",
                        actionLabel = "重试",
                        onAction = { vm.loadLxHome(force = true) },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
                vm.lxToplists.isEmpty() -> item(key = "boards_empty") {
                    EmptyState(
                        icon = SonanceIcons.EqualizerBars,
                        title = "暂无榜单数据",
                        subtitle = "当前平台暂未返回榜单",
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                }
                else -> {
                    vm.lxToplists.forEach { group ->
                        item(key = "group_${group.title}") {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = skin.text,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        val rows = group.boards.chunked(2)
                        itemsIndexed(
                            items = rows,
                            key = { idx, pair -> "board_${group.title}_${idx}_${pair.first().id}" }
                        ) { _, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pair.forEach { board ->
                                    LxBoardCard(
                                        board = board,
                                        onClick = {
                                            onOpenSheet(
                                                LxSheetDetailTarget(
                                                    id = board.id,
                                                    platform = board.platform,
                                                    title = board.name,
                                                    subtitle = board.intro,
                                                    coverUrl = board.coverUrl,
                                                    isBoard = true,
                                                    boardPeriod = board.period
                                                )
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LxSongSearchBody(
    vm: SonanceViewModel,
    platform: LxPlatform,
    playerState: PlayerUiState,
    onLongClickTrack: (RemoteTrack) -> Unit
) {
    val skin = LocalSkin.current
    when {
        vm.lxSongSearching -> LoadingState(title = "正在搜索${platform.displayName}单曲…")
        vm.lxSongError != null -> EmptyState(
            icon = SonanceIcons.Search,
            title = vm.lxSongError ?: "搜索失败",
            subtitle = "请检查网络或切换平台后重试",
            actionLabel = "重试",
            onAction = { vm.searchLx() }
        )
        vm.lxSongResults.isEmpty() -> EmptyState(
            icon = SonanceIcons.Music,
            title = "未找到相关单曲",
            subtitle = "可尝试更换关键词或切换顶部音乐平台"
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "count_hint") {
                val totalText = if (vm.lxSongTotal > 0) "共 ${vm.lxSongTotal} 首 · " else ""
                Text(
                    "${totalText}已加载 ${vm.lxSongResults.size} 首 · 长按单曲可入队或下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            itemsIndexed(
                items = vm.lxSongResults,
                key = { _, t -> "${platform.id}:${t.remoteId}" }
            ) { index, track ->
                val songId = remoteSongId(platform.sourceId, track.remoteId)
                OnlineTrackRow(
                    track = track,
                    thumbUrl = track.coverUrl,
                    sourceLabel = platform.shortLabel,
                    downloaded = vm.isLxTrackDownloaded(platform.id, track.remoteId),
                    isCurrent = playerState.mediaId == songId,
                    isPlaying = playerState.isPlaying,
                    onLongClick = { onLongClickTrack(track) },
                    onClick = { vm.playLxTrackAt(vm.lxSongResults, index, platform.id) }
                )
            }
            if (vm.lxSongHasMore) {
                item(key = "load_more_songs") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PillButton(
                            label = if (vm.lxSongLoadingMore) "正在加载…" else "加载更多单曲",
                            enabled = !vm.lxSongLoadingMore,
                            filled = false,
                            onClick = { vm.loadMoreLx() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LxSheetSearchBody(
    vm: SonanceViewModel,
    onOpenSheet: (LxSheetSummary) -> Unit
) {
    when {
        vm.lxSheetSearching -> LoadingState(title = "正在搜索歌单…")
        vm.lxSheetError != null -> EmptyState(
            icon = SonanceIcons.Album,
            title = vm.lxSheetError ?: "歌单搜索失败",
            subtitle = "请检查网络或切换平台后重试",
            actionLabel = "重试",
            onAction = { vm.searchLx() }
        )
        vm.lxSheetResults.isEmpty() -> EmptyState(
            icon = SonanceIcons.Album,
            title = "未找到相关歌单",
            subtitle = "可尝试更换关键词或切换顶部音乐平台"
        )
        else -> {
            val rows = vm.lxSheetResults.chunked(2)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = rows,
                    key = { idx, pair -> "search_sheet_${idx}_${pair.first().id}" }
                ) { _, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { s ->
                            LxSquareCard(
                                title = s.name,
                                subtitle = buildString {
                                    if (s.creatorName.isNotBlank()) append(s.creatorName)
                                    if (s.trackCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${s.trackCount}首")
                                    }
                                },
                                coverUrl = s.coverUrl,
                                seed = "lx_sheet_${s.id}",
                                onClick = { onOpenSheet(s) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (vm.lxSheetHasMore) {
                    item(key = "load_more_sheets") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PillButton(
                                label = if (vm.lxSheetLoadingMore) "正在加载…" else "加载更多歌单",
                                enabled = !vm.lxSheetLoadingMore,
                                filled = false,
                                onClick = { vm.loadMoreLx() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LxSquareCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val cover = rememberRemoteCover(coverUrl, 360)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .border(1.dp, skin.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(skin.surface3)
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PlaceholderCover(seed = seed, showMark = true, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = skin.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LxBoardCard(
    board: LxBoard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LxSquareCard(
        title = board.name,
        subtitle = board.intro.ifBlank { "排行榜单" },
        coverUrl = board.coverUrl,
        seed = "lx_board_${board.platform}_${board.id}",
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun LxScriptPickerDialog(
    vm: SonanceViewModel,
    onImportLocalClick: () -> Unit,
    onImportUrlClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2.solidSurface(),
        titleContentColor = skin.text,
        textContentColor = skin.textMuted,
        title = { Text(if (vm.lxScripts.isEmpty()) "导入洛雪音源脚本" else "切换洛雪音源脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.lxScripts.isEmpty()) {
                    Text(
                        "尚未导入任何洛雪 .js 音源脚本，可从本地文件选择或直接输入直链 URL 导入。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    vm.lxScripts.forEach { rec ->
                        val active = rec.id == vm.lxActiveScriptId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) skin.accentGlow else skin.surface3)
                                .border(
                                    1.dp,
                                    if (active) skin.accent else skin.border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    vm.selectLxScript(rec.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rec.meta.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = skin.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sub = rec.displayVersionLine()
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = skin.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (active) {
                                Icon(SonanceIcons.Check, null, tint = skin.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onImportUrlClick) { Text("URL 导入", color = skin.accent) }
                TextButton(onClick = onImportLocalClick) { Text("本地 .js", color = skin.accent) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = skin.textMuted) }
        }
    )
}

@Composable
internal fun LxImportUrlDialog(
    importing: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val clipboard = LocalClipboardManager.current
    var urlInput by remember { mutableStateOf("") }

    val trimmed = urlInput.trim().trim('"', '\'', '“', '”', '<', '>')
    val hasSchemeError = trimmed.isNotEmpty() &&
        !trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    val canSubmit = !importing && trimmed.isNotEmpty() && !hasSchemeError

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2.solidSurface(),
        titleContentColor = skin.text,
        textContentColor = skin.textMuted,
        title = { Text("通过 URL 导入洛雪音源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "请输入标准洛雪 v2（globalThis.lx）.js 脚本的 http / https 直链，导入后将自动校验并启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = {
                        Text(
                            "https://example.com/lx-source.js",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textDim
                        )
                    },
                    maxLines = 4,
                    isError = hasSchemeError,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit) onConfirm(trimmed) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = skin.accent,
                        unfocusedBorderColor = skin.border2,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedTextColor = skin.text,
                        unfocusedTextColor = skin.text,
                        cursorColor = skin.accent
                    )
                )
                if (hasSchemeError) {
                    Text(
                        "仅支持 http:// 或 https:// 开头的脚本直链",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (urlInput.isNotEmpty()) {
                        TextButton(onClick = { urlInput = "" }) {
                            Text("清空", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                        }
                    }
                    TextButton(
                        onClick = {
                            val clip = clipboard.getText()?.text?.trim().orEmpty()
                            if (clip.isNotEmpty()) urlInput = clip
                        }
                    ) {
                        Text("从剪贴板粘贴", style = MaterialTheme.typography.labelSmall, color = skin.accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(trimmed) }
            ) {
                Text(
                    if (importing) "导入中…" else "导入",
                    color = if (canSubmit) skin.accent else skin.textDim
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = skin.textMuted) }
        }
    )
}

@Composable
internal fun LxTrackActionDialog(
    track: RemoteTrack,
    downloaded: Boolean,
    onPlayNow: () -> Unit,
    onEnqueue: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2.solidSurface(),
        titleContentColor = skin.text,
        textContentColor = skin.textMuted,
        title = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val subtitle = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
                }
                val unavailableLabel = track.unavailableReason ?: "暂无可播音源"
                if (!track.playable) {
                    Text(
                        text = "$unavailableLabel，不支持播放或下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                LxDialogActionItem(
                    icon = SonanceIcons.Play,
                    label = "立即播放",
                    enabled = track.playable,
                    onClick = onPlayNow
                )
                LxDialogActionItem(
                    icon = SonanceIcons.Queue,
                    label = "加入播放队列",
                    enabled = track.playable,
                    onClick = onEnqueue
                )
                LxDialogActionItem(
                    icon = SonanceIcons.Download,
                    label = when {
                        downloaded -> "已下载到本地曲库"
                        !track.playable -> "不可下载（$unavailableLabel）"
                        else -> "下载这首歌到本地"
                    },
                    enabled = track.playable && !downloaded,
                    onClick = onDownload
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = skin.textMuted) }
        }
    )
}

@Composable
private fun LxDialogActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(skin.surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) skin.accent else skin.textMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) skin.text else skin.textMuted
        )
    }
}
