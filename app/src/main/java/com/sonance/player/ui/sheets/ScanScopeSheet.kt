package com.sonance.player.ui.sheets

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.ScanFolderInfo
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SelectionCircle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/**
 * 「扫描范围」面板：多选 MediaStore 已索引的目录，只重扫所选目录。
 *
 * 曲库本来就来自 MediaStore，所以这里不给用户手填路径，也不做 SAF 任意目录导入；
 * 目录列表按目录聚合、带曲目数，扫描结果会与现有曲库合并去重。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScopeSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                else -> ScanScopeList(vm = vm)
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
private fun ScanScopeList(vm: SonanceViewModel) {
    val skin = LocalSkin.current

    if (vm.scanFoldersLoading) {
        Text(
            text = "正在读取文件夹…",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp)
        )
        return
    }

    if (vm.scanFolders.isEmpty()) {
        Text(
            text = "没有读到任何音频目录。\n请确认已授予「读取音乐文件」权限后重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = skin.textDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        )
    } else {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(vm.scanFolders, key = { it.path }) { folder ->
                FolderRow(
                    folder = folder,
                    selected = folder.path in vm.scanScopeSelection,
                    onClick = { vm.toggleScanScope(folder.path) }
                )
            }
        }
    }

    // 扩展位：后续要支持 SAF 任意目录（含 MediaStore 尚未索引的）时接在这里
    TextButton(
        enabled = false,
        onClick = {},
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        Text(
            text = "选择其他文件夹（即将支持）",
            style = MaterialTheme.typography.labelMedium,
            color = skin.textMuted
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
private fun FolderRow(folder: ScanFolderInfo, selected: Boolean, onClick: () -> Unit) {
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
                text = if (folder.path.isBlank()) {
                    "${folder.songCount} 首"
                } else {
                    "${folder.path} · ${folder.songCount} 首"
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
