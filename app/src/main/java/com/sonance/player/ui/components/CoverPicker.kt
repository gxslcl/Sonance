package com.sonance.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.CoverCandidate
import com.sonance.player.data.OnlineCover
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 加载一张远程图片（带内存缓存）。
 * 只用于封面候选列表这种小图，所以不引第三方图片库。
 */
@Composable
fun rememberRemoteCover(url: String?, sizePx: Int = OnlineCover.THUMB_PX): ImageBitmap? {
    var bmp by remember(url, sizePx) { mutableStateOf(OnlineCover.thumbCache.get(url.orEmpty())) }
    LaunchedEffect(url, sizePx) {
        if (url.isNullOrBlank()) {
            bmp = null
            return@LaunchedEffect
        }
        OnlineCover.thumbCache.get(url)?.let {
            bmp = it
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { OnlineCover.download(url, sizePx) }
            ?.asImageBitmap()
        if (loaded != null) OnlineCover.thumbCache.put(url, loaded)
        bmp = loaded
    }
    return bmp
}

/**
 * 「手动搜索封面」对话框。
 *
 * 场景：自动匹配到了错的封面（同名不同版本、翻唱、合集……）。
 * 预填当前歌名/歌手，可改了再搜；搜到多条时全部列出来由用户挑。
 */
@Composable
fun CoverPickerDialog(vm: SonanceViewModel) {
    val songId = vm.coverTargetSongId
    if (songId <= 0L) return
    val song = vm.songById(songId) ?: return

    val skin = LocalSkin.current
    var title by remember(songId) { mutableStateOf(song.title) }
    var artist by remember(songId) { mutableStateOf(song.artist) }

    AlertDialog(
        onDismissRequest = { vm.closeCoverPicker() },
        containerColor = skin.surface2,
        title = { Text("搜索封面", style = MaterialTheme.typography.titleMedium, color = skin.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(
                        albumId = song.albumId,
                        seed = song.title + song.artist,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 6.dp,
                        sizePx = 200,
                        sourceUri = song.uri,
                        songId = song.id
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "当前：${song.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                PickerField("歌曲名", title, { title = it }, skin)
                PickerField("歌手", artist, { artist = it }, skin)

                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PillButton(
                        label = if (vm.coverSearching) "搜索中…" else "搜索",
                        onClick = {
                            if (title.isNotBlank() && !vm.coverSearching) {
                                vm.searchCovers(title.trim(), artist.trim(), song.album)
                            }
                        }
                    )
                    PillButton(
                        label = "清除这首的封面",
                        onClick = { vm.clearCover(songId) },
                        filled = false
                    )
                }

                vm.coverNotice?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = skin.danger)
                }

                if (vm.coverSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = skin.accent,
                            trackColor = skin.surface3,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在搜索…", style = MaterialTheme.typography.bodySmall, color = skin.textDim)
                    }
                }

                if (vm.coverResults.isNotEmpty()) {
                    Text(
                        "找到 ${vm.coverResults.size} 个结果，点一个应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(vm.coverResults, key = { it.fullUrl }) { c ->
                            CoverCandidateCell(
                                candidate = c,
                                skin = skin,
                                onPick = { vm.applyCover(songId, c) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            PillButton(label = "关闭", onClick = { vm.closeCoverPicker() }, filled = false)
        }
    )
}

@Composable
private fun CoverCandidateCell(
    candidate: CoverCandidate,
    skin: Skin,
    onPick: () -> Unit
) {
    val bmp = rememberRemoteCover(candidate.thumbUrl)
    Column(
        Modifier.clickable(onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(skin.surface3)
                .border(1.dp, skin.border, RoundedCornerShape(8.dp))
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Text("…", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            candidate.collectionName.ifBlank { candidate.trackName },
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            candidate.artistName,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    skin: Skin
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(skin.surface)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                cursorBrush = SolidColor(skin.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
