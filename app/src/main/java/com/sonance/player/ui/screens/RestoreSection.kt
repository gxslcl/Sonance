package com.sonance.player.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.RestoreResult
import com.sonance.player.data.formatSize
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin

/**
 * 「私有格式还原」设置区块。
 *
 * 把本播放器支持的私有 / 混淆音频容器还原成标准 .flac / .mp3，
 * 全部在本机完成，不上传任何数据。
 */
@Composable
fun RestoreSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current

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
            val label = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "已选目录"
            vm.setCompatTree(uri, label)
        }
    }

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
            val label = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "已选目录"
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
                imageVector = SonanceIcons.Refresh,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("私有格式还原", style = MaterialTheme.typography.titleMedium, color = skin.text)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "把本播放器支持的私有 / 混淆音频容器还原成标准 .flac / .mp3。\n" +
                "整个过程都在你的设备本地完成，文件不会被上传到任何地方。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        // ===== 支持的格式 =====
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(skin.surface2)
                .padding(12.dp)
        ) {
            Text(
                "自动识别并还原：\n" +
                    "· 酷狗 .kgm / .kgma / .kgg / .vpr（加密版本 3）\n" +
                    "· 网易云 .ncm（含内嵌封面与歌名 / 歌手 / 专辑）\n" +
                    "· 酷我 .kwm\n" +
                    "解密密钥来自每个文件自己的头部，无需手动配置。\n" +
                    "QQ 音乐 .qmc* / .mflac / .mgg 目前只识别、不解密。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "输出后缀按解密后的真实内容判定（fLaC → .flac，ID3 / MPEG 帧 → .mp3），" +
                    "不看原文件名 —— 文件名里的 .flac 往往是错的。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }

        // ===== 源 / 输出文件夹 =====
        Spacer(Modifier.height(14.dp))
        FolderPickerRow(
            title = "源文件夹",
            hint = "与「格式与兼容性」共用的已授权目录",
            value = vm.restoreSourceLabel,
            buttonLabel = if (vm.restoreSourceLabel == null) "选择源文件夹" else "更换源文件夹",
            onPick = { runCatching { sourcePicker.launch(null) } }
        )
        Spacer(Modifier.height(10.dp))
        FolderPickerRow(
            title = "输出文件夹",
            hint = "文件按源目录结构写入此处",
            value = vm.restoreOutputLabel,
            buttonLabel = if (vm.restoreOutputLabel == null) "选择输出文件夹" else "更换输出文件夹",
            onPick = { runCatching { outputPicker.launch(null) } }
        )

        // ===== 预览开关 =====
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("仅预览（不写盘）", style = MaterialTheme.typography.titleSmall, color = skin.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "只分析并列出将要生成的文件，不写入任何内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = vm.restoreDryRun,
                onCheckedChange = { vm.setRestoreDryRun(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = skin.onAccent,
                    checkedTrackColor = skin.accent,
                    checkedBorderColor = skin.accent,
                    uncheckedThumbColor = skin.textMuted,
                    uncheckedTrackColor = skin.surface3,
                    uncheckedBorderColor = skin.border2
                )
            )
        }

        // ===== 动作 =====
        Spacer(Modifier.height(12.dp))
        if (vm.restoring) {
            PillButton(label = "取消", onClick = { vm.cancelRestore() }, filled = false)
        } else {
            PillButton(
                label = "开始还原",
                onClick = { if (vm.restoreCanStart) vm.startRestore() },
                modifier = Modifier.alpha(if (vm.restoreCanStart) 1f else 0.4f)
            )
        }

        // ===== 进度 =====
        if (vm.restoring) {
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
                    "正在处理 ${vm.restoreDone}/${vm.restoreTotal} · ${vm.restoreCurrent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim
                )
            }
            Spacer(Modifier.height(8.dp))
            RestoreProgressBar(done = vm.restoreDone, total = vm.restoreTotal, skin = skin)
        }

        // ===== 结果 =====
        vm.restoreResult?.let { r ->
            Spacer(Modifier.height(14.dp))
            RestoreResultBlock(result = r, onClear = { vm.clearRestoreResult() })
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "还原私有 / 加密容器可能涉及相关条款或版权限制，请仅处理你本人拥有的文件。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
    }
}

@Composable
internal fun FolderPickerRow(
    title: String,
    hint: String,
    value: String?,
    buttonLabel: String,
    onPick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value ?: "未选择",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = skin.textDim)
        }
        Spacer(Modifier.width(12.dp))
        PillButton(label = buttonLabel, onClick = onPick, filled = false)
    }
}

@Composable
internal fun RestoreProgressBar(done: Int, total: Int, skin: Skin) {
    val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(skin.surface3)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(skin.accent)
        )
    }
}

@Composable
private fun RestoreResultBlock(result: RestoreResult, onClear: () -> Unit) {
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
                "个文件已还原",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "跳过 ${result.skipped} · 失败 ${result.failed} · 共 ${result.totalFiles} 个文件",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "输出格式：.flac = ${result.flacCount} | .mp3 = ${result.mp3Count}",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "数据体积：${formatSize(result.bytesIn)} → ${formatSize(result.bytesOut)}",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )

        if (result.dryRun) {
            Spacer(Modifier.height(6.dp))
            Text("预览模式：未写入任何文件", style = MaterialTheme.typography.labelSmall, color = skin.accent)
        }

        if (result.skipReasons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            result.skipReasons.forEach { (reason, count) ->
                Text("$reason：$count 个", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            }
        }

        if (result.skippedSamples.isNotEmpty()) {
            Text(
                "跳过样本",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textDim,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            )
            result.skippedSamples.take(6).forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }

        if (result.failures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("失败明细", style = MaterialTheme.typography.labelSmall, color = skin.danger)
            Spacer(Modifier.height(4.dp))
            result.failures.take(6).forEach { f ->
                Text(
                    text = f,
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
