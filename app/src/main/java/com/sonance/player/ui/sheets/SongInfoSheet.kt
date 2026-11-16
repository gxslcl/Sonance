package com.sonance.player.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Song
import com.sonance.player.data.SongInfo
import com.sonance.player.ui.components.CoverArt
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 长按菜单「查看歌曲信息」的详情面板。
 *
 * 视觉沿用 RowActionSheet 的底部抽屉规范，但内容做成三段卡片：
 * 「这首歌」放身份信息，「文件」放路径与大小，「声音」放解码器读到的真实参数。
 * 每项左侧是一个极淡的图标底片，右侧值右对齐、长路径可折行，不裁掉关键信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoSheet(song: Song, isFavorite: Boolean, onToggleFavorite: () -> Unit, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var details by remember(song.id) { mutableStateOf(SongInfo.AudioDetails()) }

    LaunchedEffect(song.id, song.uri) {
        if (song.isLocal) details = withContext(Dispatchers.IO) {
            SongInfo.readAudioDetails(context, song.uri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(skin.textMuted.copy(alpha = 0.45f))
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // 顶部：封面 + 标题 + 收藏胶囊
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(
                    albumId = song.albumId,
                    seed = song.album + song.artist,
                    modifier = Modifier.size(84.dp),
                    cornerRadius = 12.dp,
                    sizePx = 400,
                    sourceUri = song.uri,
                    songId = song.id,
                    coverKey = OnlineCover.keyOf(song.title, song.artist)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isFavorite) skin.accentGlow else skin.surface2)
                            .border(1.dp, if (isFavorite) skin.accent else skin.border, RoundedCornerShape(20.dp))
                            .clickable(onClick = onToggleFavorite)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                                contentDescription = null,
                                tint = if (isFavorite) skin.accent else skin.textMuted,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (isFavorite) "已收藏" else "收藏",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFavorite) skin.accent else skin.textMuted
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            InfoCard("这首歌", SonanceIcons.Music) {
                InfoRow("歌手", song.artist.ifBlank { "未知" })
                InfoRow("专辑", song.album.ifBlank { "未知" })
                InfoRow("时长", SongInfo.formatDuration(song.durationMs))
                if (song.track > 0) InfoRow("音轨", song.track.toString())
                InfoRow("来源", SongInfo.sourceLabel(song))
                if (!song.isLocal && !song.remoteId.isNullOrBlank()) InfoRow("音源 ID", song.remoteId)
            }

            Spacer(Modifier.height(12.dp))
            InfoCard("文件", SonanceIcons.Folder) {
                InfoRow("文件名", song.displayName.ifBlank { "未知" })
                if (song.isLocal) {
                    InfoRow("所在文件夹", song.folderName.ifBlank { "未知" })
                    InfoRow("完整路径", song.uri.toString(), multiLine = true)
                    InfoRow("大小", SongInfo.formatSize(song.sizeBytes))
                    InfoRow("添加时间", SongInfo.formatDate(song.dateAddedSec))
                } else {
                    InfoRow("说明", "在线曲目没有本地文件，收藏或下载后才会落到设备")
                }
            }

            if (song.isLocal) {
                Spacer(Modifier.height(12.dp))
                InfoCard("声音", SonanceIcons.Equalizer) {
                    InfoRow("格式", SongInfo.extensionOf(song.displayName))
                    InfoRow("编码", details.format.ifBlank { "未知" })
                    InfoRow("采样率", details.sampleRateText)
                    InfoRow("位深", details.bitDepthText)
                    InfoRow("码率", details.bitrateText)
                    InfoRow("声道", details.channelsText)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surface2)
            .border(1.dp, skin.border.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = skin.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = skin.text)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, multiLine: Boolean = false) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = if (multiLine) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = skin.text,
            maxLines = if (multiLine) 3 else 1,
            overflow = if (multiLine) TextOverflow.Ellipsis else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
