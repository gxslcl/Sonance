package com.sonance.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.ScanState
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.ScanOutcome
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 「曲库清理」设置区块。
 *
 * 展示最近一次扫描时被自动挡在曲库之外的条目明细，
 * 并提供一键重新清理的入口。
 */
@Composable
fun LibraryCleanupContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val scanning = vm.scanState == ScanState.SCANNING

    Column(Modifier.fillMaxWidth()) {
        Text(
            "扫描时会自动把无法播放的、不属于歌曲的条目挡在曲库之外，避免它们混进列表。\n" +
                "这里显示最近一次扫描的排除结果，也可以随时手动清理一遍。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )

        // ===== 结果卡片 =====
        Spacer(Modifier.height(14.dp))
        CleanupResultCard(outcome = vm.lastScanOutcome)

        // ===== 动作 =====
        Spacer(Modifier.height(12.dp))
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = skin.accent,
                    trackColor = skin.surface3,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("正在扫描…", style = MaterialTheme.typography.bodySmall, color = skin.textDim)
            }
        } else {
            PillButton(label = "立即清理", onClick = { vm.cleanLibrary() })
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "过滤开关在上面的「媒体库」区块里，扫描时会按它们自动排除。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CleanupResultCard(outcome: ScanOutcome?) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(12.dp)
    ) {
        when {
            outcome == null -> {
                Text(
                    "还没有扫描记录，点下面的按钮开始清理",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }
            outcome.excludedTotal == 0 -> {
                Text(
                    "曲库很干净，没有需要清理的条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.accent
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${outcome.excludedTotal}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = skin.accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "个条目已排除在曲库外",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                CleanupBreakdownRow("加密容器（无法播放）", outcome.excludedLocked)
                CleanupBreakdownRow("系统不支持的格式", outcome.excludedUnsupported)
                CleanupBreakdownRow("录音 / 系统提示音", outcome.excludedNonSong)
                CleanupBreakdownRow("短音频", outcome.excludedShort)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "这些条目只是不出现在曲库里，不会删除你的文件。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
    }
}

@Composable
private fun CleanupBreakdownRow(name: String, count: Int) {
    if (count <= 0) return
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "· $count 个",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
    }
}
