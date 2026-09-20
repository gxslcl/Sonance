package com.sonance.player.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.LockedFormat
import com.sonance.player.data.UnplayableFile
import com.sonance.player.data.formatSize
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 「格式与兼容性」设置区块。
 *
 * 作用是把「为什么某些歌在列表里根本看不到」讲清楚：
 * 这些文件是厂商私有加密容器，本播放器只识别、不转换。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatCompatSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val result = vm.compatResult

    val folderPicker = rememberLauncherForActivityResult(
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
            vm.scanUnplayable()
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
                imageVector = SonanceIcons.Waveform,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("格式与兼容性", style = MaterialTheme.typography.titleMedium, color = skin.text)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "本播放器直接调用系统解码器。下面这些格式能否播放，取决于文件和系统，而不是播放器本身。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        // ===== 可播放 =====
        Spacer(Modifier.height(14.dp))
        FormatLabel("可以直接播放", skin.accent)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LockedFormat.supportedExtensions.forEach { ext -> ExtChip(ext, skin.accent, skin.accentGlow) }
        }

        // ===== 未加密但系统解不了 =====
        Spacer(Modifier.height(14.dp))
        FormatLabel("没有加密，但系统解不了", skin.textDim)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LockedFormat.plainUnsupportedExtensions.forEach { ext ->
                ExtChip(ext, skin.textDim, skin.surface3)
            }
        }

        // ===== 加密容器 =====
        Spacer(Modifier.height(14.dp))
        FormatLabel("加密容器（无法播放，本播放器不提供解密）", skin.danger)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LockedFormat.groupedBySource.forEach { (source, exts) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim,
                        modifier = Modifier.width(84.dp)
                    )
                    Text(
                        text = exts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(skin.surface2)
                .padding(12.dp)
        ) {
            Text(
                "这类文件是把原始音频套了一层厂商私有的加密壳，只有对应厂商的客户端能解开。\n\n" +
                    "另外它们不会出现在系统媒体库里 —— Android 的媒体扫描器不认识这些扩展名，会直接跳过。" +
                    "所以在歌曲列表里根本看不到，而不是「点开报错」。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
        }

        // ===== 检查工具 =====
        Spacer(Modifier.height(14.dp))
        FormatLabel("在本机找一找", skin.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "选一个文件夹（比如酷狗的下载目录），我会遍历它并把上面这些格式列出来。\n" +
                "文件只在你的设备上被读取，不会上传到任何地方。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            PillButton(
                label = if (vm.compatTreeUri == null) "选择文件夹" else "换个文件夹",
                onClick = { runCatching { folderPicker.launch(null) } }
            )
            if (vm.compatTreeUri != null) {
                PillButton(
                    label = "重新检查",
                    onClick = { vm.scanUnplayable() },
                    filled = false
                )
            }
        }

        vm.compatTreeLabel?.let { label ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已授权目录：$label",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (vm.compatScanning) {
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
                    "正在扫描… 已遍历 ${vm.compatProgress} 个文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim
                )
            }
        }

        result?.let { r ->
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(skin.surface2)
                    .padding(12.dp)
            ) {
                if (r.totalCount == 0) {
                    Text(
                        "在 ${r.foldersScanned} 个文件夹里没有发现无法播放的文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${r.totalCount}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = skin.accent
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "个文件无法播放 · 共 ${formatSize(r.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textDim,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "加密容器 ${r.locked.size} 个 · 未加密但系统不支持 ${r.plainUnsupported.size} 个" +
                            if (r.truncated) " · 文件较多，结果已截断" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )

                    if (r.locked.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ResultGroup("加密容器（无法转换）", r.locked, skin.danger, skin)
                    }
                    if (r.plainUnsupported.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ResultGroup("未加密（理论上可转码）", r.plainUnsupported, skin.textDim, skin)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "对加密容器，可行的做法是：\n" +
                            "· 在对应平台查一下能否下载标准格式，或联系客服\n" +
                            "· 从提供无 DRM 文件的渠道重新获取（Bandcamp、Qobuz 等购买即为 flac/mp3）",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton(label = "清除结果", onClick = { vm.clearCompatResult() }, filled = false)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FormatLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun ExtChip(
    ext: String,
    textColor: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = ext,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ResultGroup(
    title: String,
    files: List<UnplayableFile>,
    titleColor: androidx.compose.ui.graphics.Color,
    skin: com.sonance.player.ui.theme.Skin
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = titleColor)
        Spacer(Modifier.height(6.dp))
        files.take(6).forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(skin.border2)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = f.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatSize(f.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
        if (files.size > 6) {
            Spacer(Modifier.height(4.dp))
            Text(
                "…以及另外 ${files.size - 6} 个同类文件",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
    }
}
