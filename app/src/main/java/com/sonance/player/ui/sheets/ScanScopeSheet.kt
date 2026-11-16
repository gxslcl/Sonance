package com.sonance.player.ui.sheets

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.ExternalStoragePaths
import com.sonance.player.data.ScanFolderInfo
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SelectionCircle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/**
 * 「扫描范围」面板：多选 MediaStore / 外置卷已发现目录，并支持通过 SAF 选择车机 U 盘或外置文件夹。
 *
 * 目录列表按目录聚合、带曲目数，扫描结果会与现有曲库合并去重。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScopeSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(true)) {
            vm.showToast("所选路径不是有效目录，请重新选择")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val fallback = runCatching {
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.let { seg -> seg.substringAfter(':', "").trim('/').ifBlank { seg.trimEnd(':') } }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val label = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
            ?.trimEnd(':')
            ?.takeIf { it.isNotBlank() }
            ?: fallback
            ?: "U盘 / 外置目录"
        runCatching { vm.addSafScanTree(uri, label) }
            .onFailure { vm.showToast("无法读取所选外置目录：${it.message ?: "权限受限"}") }
    }

    val launchExternalFolderPicker: () -> Unit = {
        val hasPicker = runCatching {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
        }.getOrDefault(true)
        if (!hasPicker) {
            vm.showToast("当前系统未提供目录选择器，将直接查找已挂载外置目录")
            vm.loadScanFolders()
        } else {
            runCatching { folderPicker.launch(null) }
                .onFailure { err ->
                    val msg = if (err is ActivityNotFoundException) {
                        "当前系统未提供目录选择器，将直接查找已挂载外置目录"
                    } else {
                        "无法打开目录选择器：${err.message ?: "系统受限"}"
                    }
                    vm.showToast(msg)
                    vm.loadScanFolders()
                }
        }
    }

    LaunchedEffect(Unit) {
        vm.clearScopedScanResult()
        vm.loadScanFolders()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
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
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Header(vm = vm)

            when {
                vm.scopedScanRunning -> ScanProgress(vm = vm)
                vm.scopedScanResult != null -> ScanResult(vm = vm, onDismiss = onDismiss)
                else -> ScanScopeList(
                    vm = vm,
                    onPickExternalFolder = launchExternalFolderPicker
                )
            }
        }
    }
}

@Composable
private fun Header(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val allSelected = vm.scanFolders.isNotEmpty() &&
        vm.scanFolders.all { it.path in vm.scanScopeSelection }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "扫描范围",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Text(
                text = "选择要重新扫描的文件夹，可多选",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        if (!vm.scopedScanRunning) {
            TextButton(onClick = {
                if (allSelected) {
                    vm.updateScanScopeSelection(emptySet())
                } else {
                    vm.updateScanScopeSelection(vm.scanFolders.mapTo(HashSet()) { it.path })
                }
            }) {
                Text(
                    text = if (allSelected) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.accent
                )
            }
        }
    }
}

@Composable
private fun ScanScopeList(
    vm: SonanceViewModel,
    onPickExternalFolder: () -> Unit
) {
    val skin = LocalSkin.current
    val uniqueFolders = remember(vm.scanFolders) { vm.scanFolders.distinctBy { it.path } }

    if (vm.scanFoldersLoading) {
        Text(
            text = "正在读取文件夹…",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp)
        )
        return
    }

    if (uniqueFolders.isEmpty()) {
        Text(
            text = "没有读到任何音频目录。\n如在车机中扫描 U 盘或外置目录，请点击下方「选择 U 盘 / 外置文件夹」。",
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        )
    } else {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(uniqueFolders, key = { it.path }) { folder ->
                val isSaf = folder.path.startsWith(ExternalStoragePaths.SAF_SCOPE_PREFIX)
                FolderRow(
                    folder = folder,
                    selected = folder.path in vm.scanScopeSelection,
                    isSaf = isSaf,
                    onClick = { vm.toggleScanScope(folder.path) },
                    onRemove = if (isSaf) {
                        { vm.removeSafScanTree(folder.path.removePrefix(ExternalStoragePaths.SAF_SCOPE_PREFIX)) }
                    } else null
                )
            }
        }
    }

    TextButton(
        enabled = !vm.scopedScanRunning,
        onClick = onPickExternalFolder,
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        Text(
            text = "+ 选择 U 盘 / 外置文件夹",
            style = MaterialTheme.typography.labelMedium,
            color = skin.accent
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = {
            val all = vm.scanFolders.mapTo(HashSet()) { it.path }
            vm.startScopedScan(all)
        }) {
            Text(
                text = "扫描全部文件夹",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textDim
            )
        }
        Spacer(Modifier.weight(1f))
        val count = vm.scanScopeSelection.size
        if (count > 0) {
            PillButton(
                label = "开始扫描（$count）",
                onClick = { vm.startScopedScan(vm.scanScopeSelection) }
            )
        } else {
            Text(
                text = "请先选择文件夹",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: ScanFolderInfo,
    selected: Boolean,
    isSaf: Boolean = false,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionCircle(selected = selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    isSaf -> "已授权外置目录 · ${folder.songCount} 首"
                    folder.path.isBlank() -> "${folder.songCount} 首"
                    else -> "${folder.path} · ${folder.songCount} 首"
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) {
                Text(
                    text = "移除",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
    }
}

@Composable
private fun ScanProgress(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "正在扫描…",
            style = MaterialTheme.typography.titleMedium,
            color = skin.text
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "已扫描 ${vm.scopedScanScanned} 个文件",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textDim
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = skin.accent,
            trackColor = skin.surface2
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.cancelScopedScan() }) {
            Text(
                text = "取消扫描",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textDim
            )
        }
    }
}

@Composable
private fun ScanResult(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val result = vm.scopedScanResult ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "扫描完成",
            style = MaterialTheme.typography.titleMedium,
            color = skin.text
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildString {
                append("已扫描 ${result.scanned} 个文件，新增 ${result.added} 首")
                if (result.removed > 0) append("，移除 ${result.removed} 首")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                label = "重新扫描",
                onClick = { vm.startScopedScan(vm.scanScopeSelection) },
                filled = false
            )
            PillButton(label = "完成", onClick = onDismiss)
        }
    }
}
