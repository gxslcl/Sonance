package com.sonance.player.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Song
import com.sonance.player.data.dlna.DlnaAudioItem
import com.sonance.player.data.dlna.DlnaContainer
import com.sonance.player.data.dlna.DlnaDevice
import com.sonance.player.data.dlna.DlnaHttpMediaServer
import com.sonance.player.data.dlna.DlnaTransportState
import com.sonance.player.data.formatDuration
import com.sonance.player.data.remoteSongId
import com.sonance.player.ui.components.CoverArt
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import java.net.URI
import java.util.Locale

private enum class DlnaSection {
    SERVERS,
    RENDERERS
}

/**
 * D-LAN (DLNA / UPnP) 主界面：
 * - 「局域网曲库」浏览 NAS / 电脑 / 路由器共享目录并直连播放
 * - 「投屏设备」向局域网音箱 / 电视 / 功放无线投屏与远程遥控
 */
@Composable
fun DlnaHubScreen(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    var section by rememberSaveable { mutableStateOf(DlnaSection.SERVERS) }
    var showManualDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureDlnaDiscovered()
    }

    BackHandler(
        enabled = section == DlnaSection.SERVERS &&
            (vm.dlnaBrowseStack.size > 1 || vm.dlnaSelectedServer != null)
    ) {
        vm.navigateDlnaUp()
    }

    val servers = remember(vm.dlnaDevices) { vm.dlnaDevices.filter { it.isMediaServer } }
    val renderers = remember(vm.dlnaDevices) { vm.dlnaDevices.filter { it.isMediaRenderer } }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部切换与操作栏
        DlnaToolbar(
            section = section,
            serverCount = servers.size,
            rendererCount = renderers.size,
            scanning = vm.dlnaScanning,
            onSelectSection = { section = it },
            onRescan = {
                hapticTick(context)
                vm.scanDlnaDevices()
            },
            onOpenManual = {
                hapticTick(context)
                showManualDialog = true
            }
        )

        vm.dlnaError?.let { err ->
            DlnaErrorBanner(
                message = err,
                onDismiss = { vm.clearDlnaError() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // 若正在投屏且用户处于“局域网曲库”视角，顶上挂一条紧凑投屏状态条方便随时控/断
        val activeRenderer = vm.dlnaActiveRenderer
        if (activeRenderer != null && section == DlnaSection.SERVERS) {
            DlnaCompactCastBanner(
                vm = vm,
                renderer = activeRenderer,
                onOpenCastTab = { section = DlnaSection.RENDERERS },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        when (section) {
            DlnaSection.SERVERS -> {
                val selectedServer = vm.dlnaSelectedServer
                if (selectedServer == null) {
                    DlnaServerList(
                        servers = servers,
                        scanning = vm.dlnaScanning,
                        onSelectServer = { vm.selectDlnaServer(it) },
                        onRescan = { vm.scanDlnaDevices() },
                        onOpenManual = { showManualDialog = true }
                    )
                } else {
                    DlnaDirectoryBrowser(
                        vm = vm,
                        server = selectedServer,
                        onNavigateUp = { vm.navigateDlnaUp() },
                        onBreadcrumbClick = { idx -> vm.popDlnaBreadcrumb(idx) },
                        onRefreshFolder = { vm.refreshCurrentDlnaFolder() },
                        onOpenContainer = { vm.openDlnaContainer(it) },
                        onPlayAudio = { vm.playDlnaFolderItem(it) },
                        onPlayAllAudio = { vm.playAllDlnaFolderTracks() },
                        onEnqueueAudio = { vm.enqueueDlnaTrack(it) }
                    )
                }
            }

            DlnaSection.RENDERERS -> {
                DlnaRendererPane(
                    vm = vm,
                    renderers = renderers,
                    scanning = vm.dlnaScanning,
                    onRescan = { vm.scanDlnaDevices() },
                    onOpenManual = { showManualDialog = true }
                )
            }
        }
    }

    if (showManualDialog) {
        DlnaManualHostDialog(
            adding = vm.dlnaManualAdding,
            savedHosts = vm.dlnaSavedManualHosts(),
            onSubmit = { input ->
                vm.addDlnaManualHost(input) { ok ->
                    if (ok) showManualDialog = false
                }
            },
            onProbeSaved = { host ->
                vm.addDlnaManualHost(host) { ok ->
                    if (ok) showManualDialog = false
                }
            },
            onDeleteSaved = { vm.removeDlnaManualHost(it) },
            onDismiss = { showManualDialog = false }
        )
    }
}

/**
 * 播放页底部快速投屏面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlnaCastSheet(
    vm: SonanceViewModel,
    onOpenDlnaHub: () -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManualDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureDlnaDiscovered()
    }

    val renderers = remember(vm.dlnaDevices) { vm.dlnaDevices.filter { it.isMediaRenderer } }
    val activeRenderer = vm.dlnaActiveRenderer

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface,
        contentColor = skin.text
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = SonanceIcons.Cast,
                    contentDescription = null,
                    tint = if (activeRenderer != null) skin.accent else skin.text,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "D-LAN 无线投屏",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = skin.text,
                    modifier = Modifier.weight(1f)
                )
                DlnaPillButton(
                    text = if (vm.dlnaScanning) "扫描中" else "刷新",
                    icon = if (vm.dlnaScanning) null else SonanceIcons.Refresh,
                    accent = false,
                    onClick = {
                        hapticTick(context)
                        vm.scanDlnaDevices()
                    }
                )
                Spacer(Modifier.width(6.dp))
                DlnaPillButton(
                    text = "手动",
                    icon = SonanceIcons.Plus,
                    accent = false,
                    onClick = { showManualDialog = true }
                )
            }

            if (activeRenderer != null) {
                DlnaActiveRemoteCard(
                    vm = vm,
                    renderer = activeRenderer
                )
            }

            // 本机播放选项
            DlnaSelectableDeviceRow(
                title = "本机播放",
                subtitle = if (activeRenderer == null) "当前声音正在通过本机输出" else "点击切回手机/平板端输出",
                selected = activeRenderer == null,
                badgeText = "本机",
                onClick = {
                    if (activeRenderer != null) {
                        hapticTick(context)
                        vm.disconnectDlnaRenderer()
                    }
                    onDismiss()
                }
            )

            if (vm.dlnaScanning && renderers.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = skin.accent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在扫描同一 Wi-Fi 下的 DLNA 投屏设备…",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                }
            } else if (renderers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(skin.surface2)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "暂未发现支持 DLNA 投屏的音箱或电视。请确认设备与手机在同一 Wi-Fi，且已开启 DLNA / UPnP。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((renderers.size * 68).coerceIn(72, 260).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(renderers, key = { it.udn }) { device ->
                        val selected = activeRenderer?.udn == device.udn
                        DlnaSelectableDeviceRow(
                            title = device.friendlyName,
                            subtitle = deviceHostSubtitle(device),
                            selected = selected,
                            badgeText = if (selected) "已连接" else if (device.isManual) "手动" else null,
                            onClick = {
                                hapticTick(context)
                                vm.selectDlnaRenderer(device)
                            }
                        )
                    }
                }
            }

            // 底部入口：直达局域网曲库浏览
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(skin.surface2)
                    .border(1.dp, skin.border, RoundedCornerShape(12.dp))
                    .clickable {
                        onDismiss()
                        onOpenDlnaHub()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = SonanceIcons.Folder,
                    contentDescription = null,
                    tint = skin.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "打开 D-LAN 共享中心",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = skin.text
                    )
                    Text(
                        text = "浏览电脑 / NAS 共享音乐目录并直连播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
            }
        }
    }

    if (showManualDialog) {
        DlnaManualHostDialog(
            adding = vm.dlnaManualAdding,
            savedHosts = vm.dlnaSavedManualHosts(),
            onSubmit = { input ->
                vm.addDlnaManualHost(input) { ok ->
                    if (ok) showManualDialog = false
                }
            },
            onProbeSaved = { host ->
                vm.addDlnaManualHost(host) { ok ->
                    if (ok) showManualDialog = false
                }
            },
            onDeleteSaved = { vm.removeDlnaManualHost(it) },
            onDismiss = { showManualDialog = false }
        )
    }
}

@Composable
private fun DlnaToolbar(
    section: DlnaSection,
    serverCount: Int,
    rendererCount: Int,
    scanning: Boolean,
    onSelectSection: (DlnaSection) -> Unit,
    onRescan: () -> Unit,
    onOpenManual: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 分段切换
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(skin.surface2)
                .border(1.dp, skin.border, RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DlnaSegmentChip(
                label = if (serverCount > 0) "局域网曲库 ($serverCount)" else "局域网曲库",
                selected = section == DlnaSection.SERVERS,
                onClick = { onSelectSection(DlnaSection.SERVERS) }
            )
            DlnaSegmentChip(
                label = if (rendererCount > 0) "投屏 ($rendererCount)" else "投屏",
                selected = section == DlnaSection.RENDERERS,
                onClick = { onSelectSection(DlnaSection.RENDERERS) }
            )
        }

        Spacer(Modifier.weight(1f))

        DlnaPillButton(
            text = if (scanning) "扫描中" else "扫描",
            icon = if (scanning) null else SonanceIcons.Refresh,
            accent = false,
            onClick = onRescan
        )

        DlnaPillButton(
            text = "IP",
            icon = SonanceIcons.Plus,
            accent = false,
            onClick = onOpenManual
        )
    }
}

@Composable
private fun DlnaSegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) skin.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) skin.onAccent else skin.textDim,
            maxLines = 1
        )
    }
}

@Composable
private fun DlnaPillButton(
    text: String,
    icon: ImageVector?,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) skin.accent else skin.surface2)
            .border(1.dp, if (accent) skin.accent else skin.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) skin.onAccent else skin.textDim,
                modifier = Modifier.size(14.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = if (accent) skin.onAccent else skin.accent
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (accent) skin.onAccent else skin.text,
            maxLines = 1
        )
    }
}

@Composable
private fun DlnaErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.danger.copy(alpha = 0.14f))
            .border(1.dp, skin.danger.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = skin.danger,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = SonanceIcons.Close,
                contentDescription = "关闭错误提示",
                tint = skin.danger,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun DlnaCompactCastBanner(
    vm: SonanceViewModel,
    renderer: DlnaDevice,
    onOpenCastTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.accentDim)
            .border(1.dp, skin.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenCastTab)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = SonanceIcons.Cast,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "正在投屏至 ${renderer.friendlyName}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatDuration(vm.dlnaCastPositionMs)} / ${formatDuration(vm.dlnaCastDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
        IconButton(
            onClick = { vm.dispatchPlayPause() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (vm.dlnaTransportState.isActivePlaying) SonanceIcons.Pause else SonanceIcons.Play,
                contentDescription = "播放或暂停",
                tint = skin.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        TextButton(
            onClick = { vm.disconnectDlnaRenderer() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("断开", color = skin.accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DlnaServerList(
    servers: List<DlnaDevice>,
    scanning: Boolean,
    onSelectServer: (DlnaDevice) -> Unit,
    onRescan: () -> Unit,
    onOpenManual: () -> Unit
) {
    val skin = LocalSkin.current
    if (servers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (scanning) {
                    CircularProgressIndicator(color = skin.accent, modifier = Modifier.size(30.dp))
                    Text(
                        text = "正在搜索局域网内的媒体服务器…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = skin.text
                    )
                } else {
                    Icon(
                        imageVector = SonanceIcons.Cast,
                        contentDescription = null,
                        tint = skin.textMuted,
                        modifier = Modifier.size(38.dp)
                    )
                    Text(
                        text = "未发现 DLNA 媒体服务器",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = skin.text
                    )
                    Text(
                        text = "请确保 NAS、电脑（如 MiniDLNA / Emby / Jellyfin / Windows 媒体共享）或路由器与当前设备连在同一 Wi-Fi；若路由器隔离了组播，可直接点「手动 IP」添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DlnaPillButton(
                            text = "重新扫描",
                            icon = SonanceIcons.Refresh,
                            accent = true,
                            onClick = onRescan
                        )
                        DlnaPillButton(
                            text = "手动添加 IP",
                            icon = SonanceIcons.Plus,
                            accent = false,
                            onClick = onOpenManual
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(servers, key = { it.udn }) { server ->
            DlnaDeviceCard(
                device = server,
                roleTag = if (server.isMediaRenderer) "曲库 + 投屏" else "媒体服务器",
                onClick = { onSelectServer(server) }
            )
        }
    }
}

@Composable
private fun DlnaDeviceCard(
    device: DlnaDevice,
    roleTag: String,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surface)
            .border(1.dp, skin.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(skin.accentDim),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (device.isMediaServer) SonanceIcons.Folder else SonanceIcons.Cast,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.friendlyName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = deviceHostSubtitle(device),
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(skin.surface2)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (device.isManual) "手动 · $roleTag" else roleTag,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }
}

@Composable
private fun DlnaDirectoryBrowser(
    vm: SonanceViewModel,
    server: DlnaDevice,
    onNavigateUp: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onRefreshFolder: () -> Unit,
    onOpenContainer: (DlnaContainer) -> Unit,
    onPlayAudio: (DlnaAudioItem) -> Unit,
    onPlayAllAudio: () -> Unit,
    onEnqueueAudio: (DlnaAudioItem) -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val audioCount = remember(vm.dlnaEntries) {
        vm.dlnaEntries.count { it is DlnaAudioItem }
    }
    val playingId = vm.player.state.value.mediaId

    Column(modifier = Modifier.fillMaxSize()) {
        // 面包屑与操作条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(skin.surface2)
                    .border(1.dp, skin.border, CircleShape)
                    .clickable(onClick = onNavigateUp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SonanceIcons.ArrowLeft,
                    contentDescription = "返回上一级",
                    tint = skin.text,
                    modifier = Modifier.size(16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                vm.dlnaBrowseStack.forEachIndexed { idx, crumb ->
                    val isLast = idx == vm.dlnaBrowseStack.lastIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLast) skin.accentDim else skin.surface2)
                            .clickable(enabled = !isLast) { onBreadcrumbClick(idx) }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = crumb.title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isLast) skin.accent else skin.textDim,
                            maxLines = 1
                        )
                    }
                    if (!isLast) {
                        Text("/", color = skin.textMuted, fontSize = 12.sp)
                    }
                }
            }

            if (audioCount > 0) {
                DlnaPillButton(
                    text = "全部播放",
                    icon = SonanceIcons.Play,
                    accent = true,
                    onClick = {
                        hapticTick(context)
                        onPlayAllAudio()
                    }
                )
            } else {
                IconButton(
                    onClick = onRefreshFolder,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = SonanceIcons.Refresh,
                        contentDescription = "刷新目录",
                        tint = skin.textDim,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        if (vm.dlnaBrowseLoading && vm.dlnaEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = skin.accent, modifier = Modifier.size(28.dp))
                    Text("正在读取目录…", style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
                }
            }
            return
        }

        if (vm.dlnaEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "该目录下没有可播放的音频或子文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = vm.dlnaEntries,
                key = { entry ->
                    when (entry) {
                        is DlnaContainer -> "c:${entry.id}"
                        is DlnaAudioItem -> "a:${entry.id}"
                    }
                }
            ) { entry ->
                when (entry) {
                    is DlnaContainer -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(skin.surface)
                                .border(1.dp, skin.border, RoundedCornerShape(12.dp))
                                .clickable { onOpenContainer(entry) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(skin.surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = SonanceIcons.Folder,
                                    contentDescription = null,
                                    tint = skin.accent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = skin.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                entry.childCount?.let { count ->
                                    Text(
                                        text = "$count 项",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = skin.textMuted
                                    )
                                }
                            }
                        }
                    }

                    is DlnaAudioItem -> {
                        val expectedSongId = remember(server.udn, entry.id) {
                            remoteSongId(Song.DLNA_SOURCE, "${server.udn}/${entry.id}")
                        }
                        val isPlaying = expectedSongId == playingId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isPlaying) skin.accentDim else skin.surface)
                                .border(
                                    1.dp,
                                    if (isPlaying) skin.accent.copy(alpha = 0.45f) else skin.border,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    hapticTick(context)
                                    onPlayAudio(entry)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CoverArt(
                                albumId = -1L,
                                seed = "${entry.title}|${entry.artist}",
                                modifier = Modifier.size(44.dp),
                                cornerRadius = 8.dp,
                                sizePx = 160,
                                sourceUri = entry.albumArtUri?.let(Uri::parse),
                                songId = expectedSongId,
                                coverKey = OnlineCover.keyOf(entry.title, entry.artist)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (isPlaying) skin.accent else skin.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = buildAudioSubtitle(entry),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = skin.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (entry.durationMs > 0L) {
                                Text(
                                    text = formatDuration(entry.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = skin.textMuted
                                )
                            }
                            IconButton(
                                onClick = {
                                    hapticTick(context)
                                    onEnqueueAudio(entry)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = SonanceIcons.Plus,
                                    contentDescription = "下一首播放",
                                    tint = skin.textDim,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DlnaRendererPane(
    vm: SonanceViewModel,
    renderers: List<DlnaDevice>,
    scanning: Boolean,
    onRescan: () -> Unit,
    onOpenManual: () -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val lanIp = remember(vm.dlnaHasScannedOnce, vm.dlnaDevices.size) {
        DlnaHttpMediaServer.findLanIpv4Address(context)
    }
    val active = vm.dlnaActiveRenderer

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(skin.surface2)
                    .border(1.dp, skin.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (lanIp != null) {
                        "本机局域网地址：$lanIp（支持本机文件 Range 流推送、在线音乐及 NAS 曲目跨设备控制）"
                    } else {
                        "当前未检测到局域网 Wi-Fi IP，投屏本地文件需与目标设备连入同一 Wi-Fi。"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textDim
                )
            }
        }

        if (active != null) {
            item {
                DlnaActiveRemoteCard(vm = vm, renderer = active)
            }
        }

        if (renderers.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.surface)
                        .border(1.dp, skin.border, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (scanning) {
                        CircularProgressIndicator(color = skin.accent, modifier = Modifier.size(24.dp))
                        Text("正在搜寻可投屏的 DLNA 音箱/电视…", color = skin.textMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            text = "暂无可用的 DLNA 投屏设备",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = skin.text
                        )
                        Text(
                            text = "支持索尼 / 三星 / 小米电视、Kodi、BubbleUPnP、功放与网络音箱等标准 UPnP AVTransport 设备。",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DlnaPillButton(
                                text = "重新扫描",
                                icon = SonanceIcons.Refresh,
                                accent = true,
                                onClick = onRescan
                            )
                            DlnaPillButton(
                                text = "手动 IP",
                                icon = SonanceIcons.Plus,
                                accent = false,
                                onClick = onOpenManual
                            )
                        }
                    }
                }
            }
        } else {
            items(renderers, key = { it.udn }) { renderer ->
                val selected = active?.udn == renderer.udn
                DlnaSelectableDeviceRow(
                    title = renderer.friendlyName,
                    subtitle = deviceHostSubtitle(renderer),
                    selected = selected,
                    badgeText = if (selected) "当前投屏" else if (renderer.isManual) "手动" else "DMR",
                    onClick = {
                        hapticTick(context)
                        vm.selectDlnaRenderer(renderer)
                    }
                )
            }
        }
    }
}

@Composable
private fun DlnaActiveRemoteCard(
    vm: SonanceViewModel,
    renderer: DlnaDevice
) {
    val skin = LocalSkin.current
    val playerState = vm.player.state.value
    val activeSong = vm.songById(playerState.mediaId)
    val durationMs = maxOf(vm.dlnaCastDurationMs, activeSong?.durationMs ?: 0L, 1L)
    var draggingFraction by remember { mutableStateOf<Float?>(null) }
    val displayPosMs = if (draggingFraction != null) {
        (draggingFraction!! * durationMs).toLong()
    } else {
        vm.dlnaCastPositionMs.coerceIn(0L, durationMs)
    }
    val sliderFraction = draggingFraction ?: (displayPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(skin.surface2)
            .border(1.dp, skin.accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(skin.accentDim),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SonanceIcons.Cast,
                    contentDescription = null,
                    tint = skin.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = renderer.friendlyName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(activeSong?.title ?: "待选曲目")
                        append(" · ")
                        append(transportStatusLabel(vm.dlnaTransportState, vm.dlnaBusy))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = { vm.disconnectDlnaRenderer(stopRemote = false) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("切回本机", color = skin.accent, style = MaterialTheme.typography.labelMedium)
            }
        }

        // 进度拖动与时间
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Slider(
                value = sliderFraction,
                onValueChange = { draggingFraction = it },
                onValueChangeFinished = {
                    val targetMs = ((draggingFraction ?: 0f) * durationMs).toLong()
                    draggingFraction = null
                    vm.dispatchSeekTo(targetMs)
                },
                colors = SliderDefaults.colors(
                    thumbColor = skin.accent,
                    activeTrackColor = skin.accent,
                    inactiveTrackColor = skin.border2
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(displayPosMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            }
        }

        // 上/暂停/下 + 远端音量
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { vm.dispatchPrevious(allowRandomFallback = false) }) {
                Icon(SonanceIcons.Prev, contentDescription = "上一首", tint = skin.text)
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(skin.accent)
                    .clickable { vm.dispatchPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (vm.dlnaTransportState.isActivePlaying) SonanceIcons.Pause else SonanceIcons.Play,
                    contentDescription = "播放/暂停",
                    tint = skin.onAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { vm.dispatchNext(allowRandomFallback = false) }) {
                Icon(SonanceIcons.Next, contentDescription = "下一首", tint = skin.text)
            }

            if (vm.dlnaCastVolume >= 0) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (vm.dlnaCastVolume == 0) SonanceIcons.VolumeMute else SonanceIcons.Volume,
                    contentDescription = "远端音量",
                    tint = skin.textDim,
                    modifier = Modifier.size(18.dp)
                )
                var volDrag by remember(vm.dlnaCastVolume) { mutableIntStateOf(vm.dlnaCastVolume) }
                Slider(
                    value = volDrag / 100f,
                    onValueChange = { volDrag = (it * 100f).toInt().coerceIn(0, 100) },
                    onValueChangeFinished = { vm.setDlnaVolume(volDrag) },
                    colors = SliderDefaults.colors(
                        thumbColor = skin.accent,
                        activeTrackColor = skin.accent,
                        inactiveTrackColor = skin.border2
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$volDrag%",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
    }
}

@Composable
private fun DlnaSelectableDeviceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    badgeText: String?,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) skin.accentDim else skin.surface2)
            .border(
                1.dp,
                if (selected) skin.accent.copy(alpha = 0.55f) else skin.border,
                RoundedCornerShape(13.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (selected) SonanceIcons.Check else SonanceIcons.Cast,
            contentDescription = null,
            tint = if (selected) skin.accent else skin.textDim,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) skin.accent else skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!badgeText.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) skin.accent else skin.surface
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) skin.onAccent else skin.textDim,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun DlnaManualHostDialog(
    adding: Boolean,
    savedHosts: List<String>,
    onSubmit: (String) -> Unit,
    onProbeSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface,
        titleContentColor = skin.text,
        textContentColor = skin.text,
        title = {
            Text(
                text = "手动添加 D-LAN 设备",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "输入局域网 IP（如 192.168.1.50）、IP:端口（192.168.1.50:8200）或完整 UPnP 描述 XML 地址：",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    enabled = !adding,
                    placeholder = { Text("192.168.1.50", color = skin.textMuted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (input.isNotBlank() && !adding) onSubmit(input)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = skin.accent,
                        unfocusedBorderColor = skin.border2,
                        focusedTextColor = skin.text,
                        unfocusedTextColor = skin.text,
                        cursorColor = skin.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (savedHosts.isNotEmpty()) {
                    Text(
                        text = "历史手动记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        savedHosts.forEach { host ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(skin.surface2)
                                    .clickable(enabled = !adding) { onProbeSaved(host) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = skin.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDeleteSaved(host) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = SonanceIcons.Trash,
                                        contentDescription = "删除记录",
                                        tint = skin.textMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !adding,
                onClick = { onSubmit(input) }
            ) {
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = skin.accent)
                } else {
                    Text("连接并添加", color = skin.accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = skin.textMuted)
            }
        }
    )
}

private fun deviceHostSubtitle(device: DlnaDevice): String {
    val host = runCatching { URI(device.locationUrl).host }.getOrNull().orEmpty()
    val model = listOf(device.manufacturer, device.modelName)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")
    return listOf(model, host).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { device.locationUrl }
}

private fun buildAudioSubtitle(item: DlnaAudioItem): String {
    val ext = item.resourceUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        .uppercase(Locale.ROOT)
    val tech = buildList {
        if (ext.isNotBlank() && ext.length <= 5) add(ext)
        if (item.sampleRateHz >= 44_100) {
            val khz = if (item.sampleRateHz % 1000 == 0) {
                "${item.sampleRateHz / 1000}kHz"
            } else {
                String.format(Locale.US, "%.1fkHz", item.sampleRateHz / 1000f)
            }
            add(khz)
        }
        if (item.bitsPerSample >= 16) add("${item.bitsPerSample}bit")
    }.joinToString(" ")
    return listOf(item.artist, item.album, tech)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

private fun transportStatusLabel(state: DlnaTransportState, busy: Boolean): String {
    if (busy) return "正在推送媒体…"
    return when (state) {
        DlnaTransportState.PLAYING -> "远端播放中"
        DlnaTransportState.PAUSED_PLAYBACK -> "已暂停"
        DlnaTransportState.TRANSITIONING -> "缓冲中"
        DlnaTransportState.STOPPED -> "已停止"
        DlnaTransportState.NO_MEDIA_PRESENT -> "空闲"
        DlnaTransportState.UNKNOWN -> "已连接"
    }
}
