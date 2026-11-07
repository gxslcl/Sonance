package com.sonance.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.Album
import com.sonance.player.data.Artist
import com.sonance.player.data.MusicFolder
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Song
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/* ============================================================
 * 歌曲行
 * ============================================================ */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showAlbum: Boolean = true,
    showIndex: Boolean = true,
    /**
     * 从列表移除这一首。**传 null 就不显示这一项** —— 在线搜索结果之类的
     * 列表里没有"曲库"这个概念，摆一个"从列表移除"只会让人以为能删远程数据。
     */
    onRemoveFromLibrary: (() -> Unit)? = null,
    /**
     * 删除这一首的**音频文件**。**传 null 就不显示这一项**（在线歌、队列等场景）。
     * 二次确认对话框由调用方负责 —— 这里只负责把"用户想删"这件事报上去。
     */
    onDeleteFile: (() -> Unit)? = null,
    /**
     * 进入多选删除。**传 null 就不显示这一项**（只有本地歌曲才传）。
     */
    onEnterMultiSelect: (() -> Unit)? = null,
    /** 当前列表是否处于多选态（多选态下点击整行 = 勾选/取消，不播放） */
    selectionMode: Boolean = false,
    /** 这一行是否被勾选 */
    selected: Boolean = false,
    /**
     * 手动编辑歌名 / 歌手 / 专辑（写进文件标签）。**传 null 就不显示这一项**
     * （在线歌、队列等没有本地文件可写的场景）。
     */
    onEditInfo: (() -> Unit)? = null,
    /**
     * 听歌识曲（网易云指纹识别，自动补全信息）。**传 null 就不显示这一项**。
     */
    onRecognize: (() -> Unit)? = null,
    /** 第一次长按打开动作面板时回调，用于把「长按可更多操作」提示永久收起 */
    onLongPressHintUsed: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * 封面放大倍数（设置里的「封面大小」，1 / 1.2 / 1.5 / 2）。
     *
     * 只影响封面那一格：行高由封面撑开，**文字区完全不动** ——
     * 外层 Row 是 `CenterVertically`，标题/副标题会跟着封面一起在垂直方向居中，
     * 左起位置、字号、行距都不变，所以放大之后不会"重新排过一遍"。
     */
    coverScale: Float = 1f
) {
    val skin = LocalSkin.current
    var actionsOpen by remember { mutableStateOf(false) }

    // 行尾不再放 ⋮：低频操作全部收进长按弹出的动作面板。
    // 长按只在普通态可用；多选态下长按不做事，避免和勾选逻辑打架。
    val longPressActions: () -> Unit = {
        actionsOpen = true
        onLongPressHintUsed?.invoke()
    }

    val thumb = (if (compact) 38.dp else 44.dp) * coverScale

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> skin.accent.copy(alpha = 0.14f)
                    isCurrent -> skin.accentGlow
                    else -> Color.Transparent
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!selectionMode) longPressActions else null
            )
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 8.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIndex) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    EqualizerBars(
                        active = isPlaying,
                        color = skin.accent,
                        barWidth = 2.5.dp,
                        height = 13.dp
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }

        CoverArt(
            albumId = song.albumId,
            seed = song.album + song.artist,
            modifier = Modifier.size(thumb),
            // 圆角跟着封面等比走：2 倍大还挂 6dp 小圆角会显得很方
            cornerRadius = (if (compact) 6.dp else 7.dp) * coverScale,
            // 解码尺寸也要跟着放大，否则 2 倍下拿到的是一张被拉糊的 200px 小图
            sizePx = (200 * coverScale).toInt(),
            sourceUri = song.uri,
            songId = song.id,
            coverKey = OnlineCover.keyOf(song.title, song.artist)
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isCurrent) skin.accent else skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (showAlbum && song.album.isNotBlank()) {
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }

        if (!compact) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = song.durationText,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                modifier = Modifier.width(46.dp),
                textAlign = TextAlign.End
            )
        }

        Spacer(Modifier.width(8.dp))

        if (selectionMode) {
            // 多选态：尾部换成选择圈，行高不变；点击整行由上层切换勾选
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                SelectionCircle(selected = selected)
            }
        } else {
            // 收藏心形常驻：未收藏空心 / 已收藏实心。
            // 它是对"收藏"这个高频动作的唯一常驻入口，也让行尾保持对称。
            IconAction(
                icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
                contentDescription = "收藏",
                onClick = onToggleFavorite,
                iconSize = 17.dp,
                tint = if (isFavorite) skin.accent else skin.textMuted
            )
        }

        trailing?.invoke()
    }

    // 长按动作面板：低频/文件类操作都在这里，破坏性项排在最后
    if (actionsOpen) {
        RowActionSheet(
            title = song.title,
            subtitle = listOf(song.artist, song.album)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null },
            actions = buildList {
                add(RowSheetAction("下一首播放") { onPlayNext() })
                onEditInfo?.let { add(RowSheetAction("编辑信息…", onClick = it)) }
                onRecognize?.let { add(RowSheetAction("识别歌曲", onClick = it)) }
                onRemoveFromLibrary?.let {
                    add(RowSheetAction("从列表移除（不删文件）", onClick = it))
                }
                onDeleteFile?.let {
                    add(RowSheetAction("删除文件…", destructive = true, onClick = it))
                }
                onEnterMultiSelect?.let {
                    add(RowSheetAction("多选删除", destructive = true, onClick = it))
                }
            },
            onDismiss = { actionsOpen = false }
        )
    }
}

/** 长按动作面板的一项。destructive = true 的项会染成警示色并归入最后一组。 */
data class RowSheetAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 长按行弹出的动作面板（沿用现有的底部 sheet 视觉与 ScanScopeSheet 一致）。
 *
 * 行尾不再常驻 ⋮ 之后，这是低频操作的唯一入口；破坏性项排在最后并单独分组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    title: String,
    subtitle: String?,
    actions: List<RowSheetAction>,
    onDismiss: () -> Unit
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
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            actions.forEachIndexed { index, action ->
                if (action.destructive && index > 0 && !actions[index - 1].destructive) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        color = skin.border
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) skin.danger else skin.text
                    )
                }
            }
        }
    }
}

/** 多选态的选择圈（复用现有 Check 图标和 accent 色，不引入新视觉） */
@Composable
fun SelectionCircle(selected: Boolean, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) skin.accent else Color.Transparent)
            .border(
                1.dp,
                if (selected) skin.accent else skin.textMuted.copy(alpha = 0.5f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            androidx.compose.material3.Icon(
                imageVector = SonanceIcons.Check,
                contentDescription = null,
                tint = skin.onAccent,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/* ============================================================
 * 专辑卡片
 * ============================================================ */

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    coverCorner: Dp = 10.dp
) {
    val skin = LocalSkin.current
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(coverCorner))
        ) {
            CoverArt(
                albumId = album.albumId,
                seed = album.name + album.artist,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = 0.dp,
                sizePx = 600,
                sourceUri = album.songs.firstOrNull()?.uri,
                songId = album.songs.firstOrNull()?.id ?: -1L,
                coverKey = album.songs.firstOrNull()
                    ?.let { OnlineCover.keyOf(it.title, it.artist) }
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(skin.accent.copy(alpha = 0.94f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = SonanceIcons.Play,
                    contentDescription = "播放专辑",
                    tint = skin.onAccent,
                    modifier = Modifier.size(15.dp)
                )
            }
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(skin.accent)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text("播放中", style = MaterialTheme.typography.labelSmall, color = skin.onAccent)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isCurrent) skin.accent else skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${album.artist} · ${album.songs.size} 首",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================================================
 * 通用实体行（艺术家 / 文件夹）
 * ============================================================ */

@Composable
fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    EntityRow(
        title = artist.name,
        subtitle = "${artist.songs.size} 首 · ${artist.albumCount} 张专辑",
        seed = artist.name,
        albumId = artist.songs.firstOrNull()?.albumId ?: -1L,
        sourceUri = artist.songs.firstOrNull()?.uri,
        coverKey = artist.songs.firstOrNull()
            ?.let { OnlineCover.keyOf(it.title, it.artist) },
        circular = true,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun FolderRow(
    folder: MusicFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    EntityRow(
        title = folder.name,
        subtitle = "${folder.songs.size} 首 · ${folder.path}",
        seed = folder.path,
        albumId = folder.songs.firstOrNull()?.albumId ?: -1L,
        sourceUri = folder.songs.firstOrNull()?.uri,
        circular = false,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = SonanceIcons.Folder,
        leadingTint = skin.accent
    )
}

@Composable
private fun EntityRow(
    title: String,
    subtitle: String,
    seed: String,
    albumId: Long,
    circular: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceUri: android.net.Uri? = null,
    coverKey: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leadingTint: Color = Color.Unspecified
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(if (circular) CircleShape else RoundedCornerShape(8.dp))
                .background(skin.surface2),
            contentAlignment = Alignment.Center
        ) {
            if (leadingIcon != null) {
                androidx.compose.material3.Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                CoverArt(
                    albumId = albumId,
                    seed = seed,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    cornerRadius = if (circular) 26.dp else 8.dp,
                    sizePx = 220,
                    showPlaceholderMark = false,
                    sourceUri = sourceUri,
                    coverKey = coverKey
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Icon(
            imageVector = SonanceIcons.ArrowLeft,
            contentDescription = null,
            tint = skin.textMuted,
            modifier = Modifier
                .size(16.dp)
                .rotate(180f)
        )
    }
}

/* ============================================================
 * 紧凑歌曲行（播放队列用）
 * ============================================================ */

@Composable
fun QueueSongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) skin.accentGlow else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                EqualizerBars(active = isPlaying, color = skin.accent, barWidth = 2.5.dp, height = 12.dp)
            } else {
                Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) skin.accent else skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            song.durationText,
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        IconAction(
            icon = SonanceIcons.Trash,
            contentDescription = "从队列移除",
            onClick = onRemove,
            iconSize = 16.dp,
            tint = skin.textMuted
        )
    }
}
