package com.sonance.player.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.Song
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.QueueSongRow
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Song>,
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onShuffle: () -> Unit,
    onClear: () -> Unit
) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "播放队列",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = skin.text
                    )
                    Text(
                        "${queue.size} 首 · 第 ${(state.queueIndex + 1).coerceAtLeast(1)} 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PillButton(label = "随机", onClick = onShuffle, filled = false)
                    PillButton(label = "清空", onClick = onClear, filled = false)
                }
            }

            if (queue.isEmpty()) {
                Box(Modifier.height(240.dp)) {
                    EmptyState(
                        icon = SonanceIcons.Queue,
                        title = "队列是空的",
                        subtitle = "选一首歌开始播放吧"
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }.collect { ArtworkLoader.paused = it }
                }

                // 打开队列时直接定位到正在播放的那一首。
                //
                // 用 scrollToItem 而不是 animateScrollToItem：面板刚弹出来，
                // 这一下是「初始位置」，让用户看着列表滚过去反而像卡了一下。
                // 首次组合时列表还没量过，visibleItemsInfo 是空的 —— 会正常走到滚动分支，
                // LazyListState 会把这次滚动记下来、在第一轮 measure 时应用。
                //
                // 已经在可视区里就不动：播放中自动切歌时列表会跟着走（这是想要的），
                // 但用户自己翻到别处、当前曲恰好还在可视区内时不该被拽走。
                LaunchedEffect(state.queueIndex, queue.size) {
                    if (queue.isEmpty()) return@LaunchedEffect
                    val target = state.queueIndex.coerceIn(0, queue.lastIndex)
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.any { it.index == target }) return@LaunchedEffect
                    listState.scrollToItem(target)
                }

                val playAtI = remember { { i: Int -> onPlayAt(i) } }
                val removeAtI = remember { { i: Int -> onRemoveAt(i) } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    itemsIndexed(queue, key = { i, s -> "$i-${s.id}" }) { index, song ->
                        QueueSongRow(
                            song = song,
                            index = index,
                            isCurrent = state.queueIndex == index,
                            isPlaying = state.isPlaying,
                            onClick = { playAtI(index) },
                            onRemove = { removeAtI(index) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
