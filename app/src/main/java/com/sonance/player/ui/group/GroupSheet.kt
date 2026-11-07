package com.sonance.player.ui.group

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonance.player.sync.GroupManager
import com.sonance.player.sync.GroupPeer
import com.sonance.player.sync.GroupPhase
import com.sonance.player.sync.GroupRole
import com.sonance.player.sync.GroupUiState
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val DotOnline = Color(0xFF4CAF50)
private val DotWarn = Color(0xFFFFB300)

/**
 * 多设备同步的唯一入口面板。
 *
 * 一屏完成：发现（雷达）→ 加入确认 → 连接状态 + 校准 + 退出；
 * 诊断数字收在下面的二级折叠区，默认不出现 IP / 端口这类字眼。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val state by GroupManager.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // Android 13 起 NSD 需要 NEARBY_WIFI_DEVICES；12 及以下要定位权限。
    // 拒绝也不影响手动 IP 的调试入口，只是 mDNS 发现会失败。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { GroupManager.startDiscovery() }

    // 打开面板就开始找附近设备；只在「还没入组」时做，已连接不需要重新发现
    LaunchedEffect(Unit) {
        if (GroupManager.state.value.role != GroupRole.NONE) return@LaunchedEffect
        val needed = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 23 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            GroupManager.startDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (GroupManager.state.value.role == GroupRole.NONE) GroupManager.stopDiscovery()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(skin.textMuted.copy(alpha = 0.45f))
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "多设备播放",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (state.role) {
                    GroupRole.HOST -> "本机是主机，负责时间轴与曲目推送"
                    GroupRole.GUEST -> "已作为从机加入 ${state.hostName ?: "主机"}"
                    GroupRole.NONE -> "同一 Wi-Fi 下的设备可以一起播放同一首歌"
                },
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(16.dp))

            when (state.role) {
                GroupRole.NONE -> SetupContent(state)
                else -> ActiveContent(state)
            }

            state.message?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.phase == GroupPhase.DEGRADED) DotWarn else skin.textDim
                )
            }
        }
    }

    // 主机侧：「允许 XX 加入？」确认
    val pendingName = state.pendingPeerName
    val pendingId = state.pendingPeerId
    if (pendingId != null && pendingName != null) {
        AlertDialog(
            onDismissRequest = { GroupManager.denyPeer(pendingId) },
            containerColor = skin.surface.solidSurface(),
            title = {
                Text("允许 $pendingName 加入？", color = skin.text)
            },
            text = {
                Text(
                    "加入后对方会同步播放本机的歌曲，本机也会把当前曲目传给对方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            },
            confirmButton = {
                TextButton(onClick = { GroupManager.acceptPeer(pendingId) }) {
                    Text("允许", color = skin.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { GroupManager.denyPeer(pendingId) }) {
                    Text("拒绝", color = skin.textMuted)
                }
            }
        )
    }
}

/* ------------------------------------------------------------------ 未入组：发现 */

@Composable
private fun SetupContent(state: GroupUiState) {
    val skin = LocalSkin.current

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(
            label = "创建组合",
            onClick = { GroupManager.createGroup() },
            modifier = Modifier.weight(1f)
        )
        PillButton(
            label = "重新搜索",
            onClick = { GroupManager.stopDiscovery(); GroupManager.startDiscovery() },
            modifier = Modifier.weight(1f),
            filled = false
        )
    }

    Spacer(Modifier.height(18.dp))

    if (state.hosts.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Radar()
            Spacer(Modifier.height(12.dp))
            Text(
                "正在查找附近设备…",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "请确认两台设备连在同一个 Wi-Fi",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
        return
    }

    Text("附近设备", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
    Spacer(Modifier.height(8.dp))
    state.hosts.forEach { host ->
        DeviceRow(
            peer = host,
            subtitle = listOf(host.model, host.address)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            actionLabel = "加入",
            onAction = {
                if (host.hostIp.isNotBlank() && host.hostPort > 0) {
                    GroupManager.joinHost(host.id, host.name, host.hostIp, host.hostPort)
                }
            }
        )
        Spacer(Modifier.height(6.dp))
    }
}

/* ------------------------------------------------------------------ 已入组 */

@Composable
private fun ActiveContent(state: GroupUiState) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    var diagOpen by remember { mutableStateOf(false) }

    StatusBanner(state)

    Spacer(Modifier.height(14.dp))

    if (state.role == GroupRole.HOST) {
        Text("组内设备", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
        Spacer(Modifier.height(6.dp))
        if (state.peers.isEmpty()) {
            Text(
                "等待其他设备加入…",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
        } else {
            state.peers.forEach { peer ->
                DeviceRow(
                    peer = peer,
                    subtitle = buildString {
                        if (peer.model.isNotBlank()) append(peer.model)
                        if (peer.syncErrorMs != null) {
                            if (isNotEmpty()) append(" · ")
                            append("偏差 ${peer.syncErrorMs} ms")
                        }
                        if (peer.rttMs != null) {
                            if (isNotEmpty()) append(" · ")
                            append("RTT ${peer.rttMs} ms")
                        }
                    },
                    actionLabel = null
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    } else {
        Text("主机", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
        Spacer(Modifier.height(6.dp))
        state.peers.forEach { peer ->
            DeviceRow(
                peer = peer,
                subtitle = if (peer.online) "已连接" else "连接不稳定，正在重连",
                actionLabel = null
            )
        }
    }

    // 当前正在传的曲目
    val progress = state.transferProgress
    if (progress != null && state.transferTitle != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "正在传输：${state.transferTitle} · ${(progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = skin.accent
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(skin.surface3)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(skin.accent)
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    CalibrationCard(state)

    Spacer(Modifier.height(14.dp))

    // 诊断（二级）
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { diagOpen = !diagOpen }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (diagOpen) "收起诊断" else "诊断信息",
            style = MaterialTheme.typography.labelMedium,
            color = skin.textMuted
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (diagOpen) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = skin.textMuted
        )
    }
    if (diagOpen) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(skin.surface2)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DiagRow("角色 / 阶段", "${state.role.name} / ${state.phase.name}")
            DiagRow(
                "NSD 解析地址",
                state.resolvedHost?.let { "$it:${state.resolvedPort}" } ?: "—"
            )
            DiagRow("TCP 连接", state.tcpState ?: "—")
            DiagRow("握手耗时", state.handshakeRttMs?.let { "$it ms" } ?: "—")
            DiagRow("同步偏差", state.syncErrorMs?.let { "$it ms" } ?: "—")
            DiagRow("对时 RTT", state.rttMs?.let { "$it ms" } ?: "—")
            DiagRow("时钟偏移", state.clockOffsetMs?.let { "$it ms" } ?: "—")
            DiagRow("本机输出延迟", "${state.outputLatencyMs} ms")
            DiagRow("主机输出延迟", "${state.hostLatencyMs} ms")
            DiagRow("输出路由", state.calibrationRoute.ifBlank { "—" })
            DiagRow("当前曲目", state.trackTitle ?: "—")
            state.lastError?.let { err ->
                DiagRow("最后失败", "$err（${agoText(state.lastErrorAtMs)}）")
            }
            Spacer(Modifier.height(6.dp))
            PillButton(
                label = "导出诊断日志",
                onClick = {
                    val file = GroupManager.exportDiagLog()
                    if (file == null) {
                        Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
                    } else {
                        shareLog(context, file)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                filled = false
            )
            Text(
                "日志只含链路事件（设备名/IP/端口/耗时），不含曲库标题",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }

    // 阶段超时后给一个明确的重试入口，而不是让用户退出重进
    if (state.canRetry) {
        Spacer(Modifier.height(12.dp))
        PillButton(
            label = "重试连接",
            onClick = { GroupManager.retryJoin() },
            modifier = Modifier.fillMaxWidth(),
            filled = true
        )
    }

    Spacer(Modifier.height(20.dp))
    PillButton(
        label = if (state.role == GroupRole.HOST) "结束组合" else "退出组合",
        onClick = {
            GroupManager.stopCalibration()
            GroupManager.leave("已退出多设备播放")
        },
        modifier = Modifier.fillMaxWidth(),
        filled = false
    )
}

@Composable
private fun StatusBanner(state: GroupUiState) {
    val skin = LocalSkin.current
    val deviationMs = when (state.role) {
        GroupRole.GUEST -> state.syncErrorMs?.let { abs(it) }
        else -> state.peers.mapNotNull { it.syncErrorMs }.maxOfOrNull { abs(it) }
    }
    val healthy = state.phase == GroupPhase.SYNCING &&
        deviationMs != null && deviationMs < 30L
    val dot = when {
        state.phase == GroupPhase.DEGRADED -> DotWarn
        state.phase == GroupPhase.PAIRING -> DotWarn
        deviationMs == null -> skin.textMuted
        healthy -> DotOnline
        else -> DotWarn
    }
    // 按阶段给文案：配对 / 等待播放 / 同步 都有各自的说法，不再一律显示「正在同步」
    val title = when {
        state.phase == GroupPhase.DEGRADED -> "连接异常"
        state.role == GroupRole.HOST -> "组内 ${state.peers.size + 1} 台"
        state.phase == GroupPhase.PAIRING -> "正在配对"
        state.connectedCount <= 0 -> "尚未加入"
        else -> "已连接 ${state.connectedCount} 台"
    }
    val subtitle = when {
        state.phase == GroupPhase.DEGRADED -> state.lastError ?: state.message ?: "连接不稳定"
        state.phase == GroupPhase.PAIRING ->
            if (state.tcpState?.startsWith("已连接") == true) {
                "已连上主机，等待对方点「允许」"
            } else {
                state.message ?: "正在连接主机…"
            }
        state.role == GroupRole.HOST && state.peers.isEmpty() -> "等待其他设备加入…"
        state.phase == GroupPhase.CONNECTED -> state.message ?: "等待主机开始播放"
        deviationMs != null -> "同步偏差 $deviationMs ms"
        else -> "正在同步…"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = skin.text
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (healthy) skin.textMuted else DotWarn
            )
        }
        if (state.trackTitle != null) {
            Text(
                state.trackTitle,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.width(110.dp)
            )
        }
    }
}

@Composable
private fun CalibrationCard(state: GroupUiState) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(14.dp)
    ) {
        Text("输出延迟校准", style = MaterialTheme.typography.titleSmall, color = skin.text)
        Spacer(Modifier.height(3.dp))
        Text(
            "两台设备会播放同一串「哒哒」脉冲；拖动滑块，直到两者合成一声。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.calibrationOffsetMs} ms",
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent,
                modifier = Modifier.width(64.dp)
            )
            SonanceSlider(
                value = (state.calibrationOffsetMs / 400f).coerceIn(0f, 1f),
                onValueChange = {
                    GroupManager.setCalibrationLatency((it * 400f).roundToInt().toLong())
                },
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 ms", style = MaterialTheme.typography.labelSmall, color = skin.textDim)
            Text("400 ms", style = MaterialTheme.typography.labelSmall, color = skin.textDim)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                label = if (state.calibrationActive) "停止脉冲" else "播放校准脉冲",
                onClick = {
                    if (state.calibrationActive) GroupManager.stopCalibration()
                    else GroupManager.startCalibration()
                },
                modifier = Modifier.weight(1f),
                filled = !state.calibrationActive
            )
        }
        if (state.calibrationActive) {
            Spacer(Modifier.height(6.dp))
            Text(
                "脉冲播放中 · 每 0.6 秒一声",
                style = MaterialTheme.typography.labelSmall,
                color = skin.accent
            )
        }
    }
}

/* ------------------------------------------------------------------ 小组件 */

@Composable
private fun DeviceRow(
    peer: GroupPeer,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit = {}
) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (peer.online) DotOnline else DotWarn)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.name.ifBlank { "Android" },
                style = MaterialTheme.typography.titleSmall,
                color = skin.text
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, color = skin.textDim)
    }
}

/** elapsedRealtime 时间戳 → 「12 秒前 / 3 分钟前」，给「最后失败」用 */
private fun agoText(atMs: Long): String {
    if (atMs <= 0L) return "—"
    val diff = SystemClock.elapsedRealtime() - atMs
    return when {
        diff < 1000L -> "刚刚"
        diff < 60_000L -> "${diff / 1000} 秒前"
        diff < 3_600_000L -> "${diff / 60_000} 分钟前"
        else -> "${diff / 3_600_000} 小时前"
    }
}

/** 把导出的 txt 交给系统分享（微信/飞书/邮件都能发回来），不需要 adb */
private fun shareLog(context: Context, file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.covers", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "分享失败，日志路径：${file.absolutePath}", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Sonance 多设备诊断日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "没有可用分享应用，日志路径：${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

/** 发现中的雷达：三层外扩圆环，纯装饰、不吃 CPU（只有两个动画值） */
@Composable
private fun Radar() {
    val skin = LocalSkin.current
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f
            for (i in 0..2) {
                val phase = (pulse + i / 3f) % 1f
                val r = maxR * (0.25f + phase * 0.75f)
                drawCircle(
                    color = skin.accent.copy(alpha = (1f - phase) * 0.35f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 2f)
                )
            }
            drawCircle(
                color = skin.accent.copy(alpha = 0.9f),
                radius = maxR * 0.12f,
                center = center
            )
        }
        androidx.compose.material3.Icon(
            imageVector = SonanceIcons.Users,
            contentDescription = null,
            tint = skin.onAccent,
            modifier = Modifier.size(18.dp)
        )
    }
}
