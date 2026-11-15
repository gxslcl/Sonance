package com.sonance.player.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.sonance.player.data.netease.LocalCacheCandidate
import com.sonance.player.data.netease.LocalCacheConvertSummary
import com.sonance.player.data.netease.LocalCacheRules
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 「转换本机缓存（免 Shizuku）」隐藏工具卡片。
 *
 * 与「私有格式还原」「Shizuku 缓存嗅探」共同挂在 10 连击署名解锁区内：
 * 无需 Shizuku，自动结合当前本机登录的网易云账号补全信息；
 * 仅在有条目真正转换并验证通过后，于系统音乐目录新建独立文件夹 `Music/本机缓存转换` 保存。
 */
@Composable
fun LocalCacheSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadLocalCacheDoneKeys() }

    val report = vm.localCacheReport
    val candidates = report?.candidates.orEmpty()
    val canConvert = candidates.isNotEmpty() && !vm.localCacheConverting && !vm.localCacheScanning

    val sourcePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val label = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "已选目录"
            vm.setLocalCacheTree(uri, label)
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
                imageVector = SonanceIcons.Refresh,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "转换本机缓存（免 Shizuku）",
                style = MaterialTheme.typography.titleSmall,
                color = skin.text
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "独立目录",
                style = MaterialTheme.typography.labelSmall,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(skin.accentGlow)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "无需 Shizuku：自动扫描本机可读的下载/缓存目录（含下方的本机授权目录），" +
                "并结合本机登录的网易云账号补全信息。转换成功后自动新建独立文件夹" +
                "「${LocalCacheRules.STANDALONE_DISPLAY_PATH}」保存并并入曲库。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        if (report?.androidDataBlocked != false) {
            Spacer(Modifier.height(8.dp))
            Text(
                "说明：受 Android 11+ 系统隔离限制，其它 App 的沙盒私有目录（Android/data）即便在本机已登录网易云" +
                    "仍需 Shizuku 才能读取；若缓存在 Download/netease 等公开目录，可直接通过下方选择器授权读取。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }

        Spacer(Modifier.height(12.dp))
        FolderPickerRow(
            title = "额外扫描的本机缓存目录（可选）",
            hint = "如 Download/netease/cloudmusic 等非媒体扩展名目录",
            value = vm.localCacheTreeLabel,
            buttonLabel = if (vm.localCacheTreeLabel == null) "选择本机目录" else "更换目录",
            onPick = { runCatching { sourcePicker.launch(null) } }
        )

        if (vm.localCacheTreeUri != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "清除已选目录",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.setLocalCacheTree(null, null) }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        if (vm.localCacheConverting) {
            PillButton(label = "取消转换", onClick = { vm.cancelLocalCacheConvert() }, filled = false)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    label = if (vm.localCacheScanning) "扫描中…" else "扫描本机缓存",
                    onClick = { if (!vm.localCacheScanning) vm.scanLocalCache() },
                    filled = false
                )
                Spacer(Modifier.width(10.dp))
                PillButton(
                    label = "一键转换并建新目录",
                    onClick = { if (canConvert) vm.convertLocalCache() },
                    modifier = Modifier.alpha(if (canConvert) 1f else 0.4f)
                )
            }
        }

        if (vm.localCacheScanning) {
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
                    "正在扫描本机可读缓存目录…",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim
                )
            }
        }

        if (vm.localCacheConverting) {
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
                    "正在转换 ${vm.localCacheDone}/${vm.localCacheTotal} · ${vm.localCacheCurrent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            RestoreProgressBar(done = vm.localCacheDone, total = vm.localCacheTotal, skin = skin)
        }

        if (report != null) {
            Spacer(Modifier.height(12.dp))
            if (candidates.isEmpty()) {
                Text(
                    buildString {
                        append("暂未发现可免 Shizuku 转换的本机缓存")
                        if (report.scannedSources.isNotEmpty()) {
                            append("（已扫：${report.scannedSources.joinToString(" / ")}）")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            } else {
                var hideConverted by remember { mutableStateOf(false) }
                val visible = remember(candidates, hideConverted, vm.localCacheConvertedKeys) {
                    if (hideConverted) candidates.filter { it.entry.key !in vm.localCacheConvertedKeys }
                    else candidates
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "发现 ${candidates.size} 首（新建目标：${LocalCacheRules.STANDALONE_DISPLAY_PATH}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textDim,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (hideConverted) "显示全部" else "隐藏已转",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { hideConverted = !hideConverted }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "清空",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.clearLocalCacheList() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                visible.take(LOCAL_DISPLAY_LIMIT).forEach { item ->
                    LocalCandidateRow(
                        item = item,
                        converted = item.entry.key in vm.localCacheConvertedKeys,
                        canConvertOne = !vm.localCacheConverting && !vm.localCacheScanning,
                        onConvertOne = { vm.convertLocalCache(listOf(item.entry.key)) }
                    )
                }
                if (visible.size > LOCAL_DISPLAY_LIMIT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "其余 ${visible.size - LOCAL_DISPLAY_LIMIT} 首已折叠，点「一键转换并建新目录」可全部处理",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
            }
        }

        vm.localCacheResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            LocalResultCard(result = result) { vm.clearLocalCacheList() }
        }
    }
}

@Composable
private fun LocalCandidateRow(
    item: LocalCacheCandidate,
    converted: Boolean,
    canConvertOne: Boolean,
    onConvertOne: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.entry.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatSize(item.entry.sizeBytes)} · ${item.entry.typeLabel} · ${item.sourceLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            converted -> Text("已转换", style = MaterialTheme.typography.labelSmall, color = skin.accent)
            canConvertOne -> Text(
                "转换",
                style = MaterialTheme.typography.labelSmall,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onConvertOne)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun LocalResultCard(result: LocalCacheConvertSummary, onClear: () -> Unit) {
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
                "首已保存到独立文件夹「${result.standaloneFolderPath}」",
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
            Spacer(Modifier.height(6.dp))
            result.failures.take(4).forEach { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PillButton(label = "收起记录", onClick = onClear, filled = false)
    }
}

private const val LOCAL_DISPLAY_LIMIT = 80
