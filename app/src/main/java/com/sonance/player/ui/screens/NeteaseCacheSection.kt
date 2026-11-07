package com.sonance.player.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.formatSize
import com.sonance.player.data.bili.ShizukuShell
import com.sonance.player.data.netease.NeteaseCacheEntry
import com.sonance.player.data.netease.NeteaseScanResult
import com.sonance.player.data.netease.SniffApp
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/**
 * 「嗅探音乐缓存并转换」设置区块。
 *
 * 通过 Shizuku 以 shell 身份读取网易云音乐的缓存目录，按文件头魔数
 * 认出 NCM 加密容器与明文音频（含分片），NCM 走「私有格式还原」的
 * 同一套解密管线，明文直接复制，成品统一写进共用的输出文件夹。
 *
 * 默认隐藏：与「私有格式还原」共用 conversionUnlocked 彩蛋开关
 * （在「关于」里连点署名 10 次出现，一小时后自动收起）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeteaseCacheSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    // 首次进区块：用上次的扫描结果先把列表铺出来
    LaunchedEffect(Unit) { vm.neteaseLoadCached() }

    // Shizuku 状态在每次重组时重算。授权 / 启动回来后用户点一下页面即会刷新
    val shizukuInstalled = remember { ShizukuShell.isInstalled(context) }
    val shizukuRunning = ShizukuShell.isRunning()
    val shizukuGranted = ShizukuShell.hasPermission()
    val shizukuReady = shizukuInstalled && shizukuRunning && shizukuGranted

    val entries = (vm.neteaseScanState as? NeteaseScanResult.Ok)?.entries.orEmpty()
    val canConvert = entries.isNotEmpty() && vm.restoreOutputUri != null &&
        !vm.neteaseConverting && !vm.neteaseScanning

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "已选目录"
            vm.setRestoreOutputTree(uri, label)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = SonanceIcons.Download,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("嗅探音乐缓存并转换", style = MaterialTheme.typography.titleMedium, color = skin.text)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "读取网易云 / 酷狗 / QQ 音乐 / 汽水 / 波点的本地缓存，认出其中的加密容器与明文音频并转换成标准格式。\n" +
                "支持 NCM、NMSF、KGM/KGMA/VPR、QMC（mflac/mgg）等加密容器与明文音频；" +
                "波点的缓存本身是明文，直接转换；汽水的 MDL 加密暂无公开解密方案，仅识别列出；酷狗 KGG 新版加密暂不支持。\n" +
                "能力边界：QQ 在线播放缓存不带密钥（只有「下载」的歌可转换）；酷狗概念版的缓存多在应用内部存储，无 root 读不到。\n" +
                "解密与写入全部在本机完成，不碰账号；NMSF 缓存会按文件名里的歌曲 ID 联网补全歌名 / 歌手 / 专辑 / 封面，查询失败不影响转换。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        // ===== 嗅探目标多选 =====
        Spacer(Modifier.height(12.dp))
        Text("嗅探目标（可多选）", style = MaterialTheme.typography.labelMedium, color = skin.textDim)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SniffApp.entries.forEach { app ->
                val selected = app.name in vm.sniffTargets
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) skin.accent else skin.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) skin.accent.copy(alpha = 0.14f) else skin.surface2)
                        .border(
                            1.dp,
                            if (selected) skin.accent else skin.surface3,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { vm.toggleSniffTarget(app.name) }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        if ("LUNA" in vm.sniffTargets) {
            Spacer(Modifier.height(4.dp))
            Text(
                "汽水的 MDL 加密暂无公开解密方案，扫到的条目只能列出、无法转换。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }

        // ===== Shizuku 状态 =====
        when {
            !shizukuInstalled -> ShizukuStatusCard(
                title = "还没有安装 Shizuku",
                subtitle = "音乐 App 的缓存放在 Android/data 里，Android 11 起普通应用读不到。" +
                    "装一个 Shizuku，用无线调试启动服务后就能读；不需要 root。",
                actionLabel = "打开 Shizuku",
                onAction = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(ShizukuShell.SHIZUKU_PACKAGE)
                    if (intent != null) runCatching { context.startActivity(intent) }
                }
            )

            !shizukuRunning -> ShizukuStatusCard(
                title = "Shizuku 服务没有运行",
                subtitle = "打开 Shizuku，按提示用无线调试启动服务（每次重启手机后要重新启动一次），然后回来点扫描。",
                actionLabel = "去启动",
                onAction = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(ShizukuShell.SHIZUKU_PACKAGE)
                    if (intent != null) runCatching { context.startActivity(intent) }
                }
            )

            !shizukuGranted -> ShizukuStatusCard(
                title = "需要授予 Shizuku 权限",
                subtitle = "Sonance 只读取缓存文件的内容，不会写入、修改或删除所选音乐 App 的任何数据。",
                actionLabel = "去授权",
                onAction = {
                    // 必须由用户点击触发（与 B 站缓存页同一条纪律）
                    if (context is Activity) {
                        ShizukuShell.requestPermission { granted ->
                            if (granted) vm.neteaseScan()
                        }
                    }
                }
            )
        }

        // ===== 输出文件夹（与私有格式还原共用） =====
        Spacer(Modifier.height(12.dp))
        FolderPickerRow(
            title = "输出文件夹",
            hint = "与「私有格式还原」共用，转换结果平铺在这里",
            value = vm.restoreOutputLabel,
            buttonLabel = if (vm.restoreOutputLabel == null) "选择输出文件夹" else "更换输出文件夹",
            onPick = { runCatching { outputPicker.launch(null) } }
        )

        // ===== 动作 =====
        Spacer(Modifier.height(12.dp))
        if (vm.neteaseConverting) {
            PillButton(label = "取消", onClick = { vm.cancelNetease() }, filled = false)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shizukuReady) {
                    PillButton(
                        label = if (vm.neteaseScanning) "扫描中…" else "扫描缓存",
                        onClick = { if (!vm.neteaseScanning) vm.neteaseScan() },
                        filled = false
                    )
                    Spacer(Modifier.width(10.dp))
                }
                PillButton(
                    label = "全部转换",
                    onClick = { if (canConvert) vm.neteaseConvert(entries.map { it.key }) },
                    modifier = Modifier.alpha(if (canConvert) 1f else 0.4f)
                )
            }
        }

        // ===== 扫描中 =====
        if (vm.neteaseScanning) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = skin.accent,
                    trackColor = skin.surface3,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在扫描缓存目录（大文件 + 头部魔数）…",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim
                )
            }
        }

        // ===== 转换进度 =====
        if (vm.neteaseConverting) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = skin.accent,
                    trackColor = skin.surface3,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在转换 ${vm.neteaseDone}/${vm.neteaseTotal} · ${vm.neteaseCurrent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            RestoreProgressBar(done = vm.neteaseDone, total = vm.neteaseTotal, skin = skin)
        }

        // ===== 结果列表 =====
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            var hideConverted by remember { mutableStateOf(false) }
            var showClearConfirm by remember { mutableStateOf(false) }
            val shown = if (hideConverted) {
                entries.filter { it.key !in vm.neteaseConvertedKeys }
            } else entries

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (shown.isEmpty()) "已转换的 ${entries.size} 个已隐藏"
                    else "共 ${shown.size} 个 · 总计 ${formatSize(shown.sumOf { it.sizeBytes })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (hideConverted) "显示全部" else "隐藏已转换",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { hideConverted = !hideConverted }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    "清除列表",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showClearConfirm = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            shown.take(DISPLAY_LIMIT).forEach { entry ->
                EntryRow(entry = entry, vm = vm, canConvertOne = canConvert)
            }
            if (shown.size > DISPLAY_LIMIT) {
                Text(
                    "…其余 ${shown.size - DISPLAY_LIMIT} 个未列出，「全部转换」不受影响",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }

            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    containerColor = skin.surface2.solidSurface(),
                    titleContentColor = skin.text,
                    textContentColor = skin.textDim,
                    title = { Text("清除嗅探列表？") },
                    text = {
                        Text(
                            "只清掉这份扫描列表，不会删除所选音乐 App 的缓存文件、" +
                                "已转换记录和输出文件夹里的成品。下次点「扫描缓存」会重新扫描。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    confirmButton = {
                        PillButton(label = "清除", onClick = {
                            showClearConfirm = false
                            vm.neteaseClearList()
                        })
                    },
                    dismissButton = {
                        PillButton(label = "保留", onClick = { showClearConfirm = false }, filled = false)
                    }
                )
            }
        }

        // ===== 结果 =====
        vm.neteaseResult?.let { r ->
            Spacer(Modifier.height(14.dp))
            NeteaseResultBlock(r, onClear = { vm.clearNeteaseResult() })
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "NCM 的歌名 / 歌手 / 封面来自文件头部，解密时自动写回；" +
                "明文分片的文件名多为哈希，转换后可能需要手动改名。\n" +
                "转换他人缓存的音频可能涉及版权限制，请仅处理你本人有权使用的文件。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
    }
}

@Composable
private fun ShizukuStatusCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val skin = LocalSkin.current
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = skin.text)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
        Spacer(Modifier.height(10.dp))
        PillButton(label = actionLabel, onClick = onAction, filled = false)
    }
}

@Composable
private fun EntryRow(entry: NeteaseCacheEntry, vm: SonanceViewModel, canConvertOne: Boolean) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatSize(entry.sizeBytes)} · ${entry.typeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            entry.key in vm.neteaseConvertedKeys ->
                Text("已转换", style = MaterialTheme.typography.labelSmall, color = skin.accent)

            canConvertOne -> Text(
                "转换",
                style = MaterialTheme.typography.labelSmall,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.neteaseConvert(listOf(entry.key)) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun NeteaseResultBlock(result: com.sonance.player.data.netease.NcConvertSummary, onClear: () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${result.converted}", style = MaterialTheme.typography.headlineSmall, color = skin.accent)
            Spacer(Modifier.width(6.dp))
            Text(
                "个文件已转换",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("跳过 ${result.skipped}")
                if (result.trials > 0) append("（其中试听残曲 ${result.trials}）")
                if (result.incomplete > 0) append("（其中缓存不完整 ${result.incomplete}）")
                append(" · 失败 ${result.failed}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )

        if (result.failures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            result.failures.take(6).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (result.failures.size > 6) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "…以及另外 ${result.failures.size - 6} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        PillButton(label = "清除结果", onClick = onClear, filled = false)
    }
}

/** 列表最多铺多少行；超出只提示数量，避免设置页一屏塞进几百个重组节点 */
private const val DISPLAY_LIMIT = 80
