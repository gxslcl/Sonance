package com.sonance.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/* ============================================================
 * 歌曲行
 * ============================================================ */

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
    var menuOpen by remember { mutableStateOf(false) }

    val thumb = (if (compact) 38.dp else 44.dp) * coverScale

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) skin.accentGlow else Color.Transparent)
            .clickable(onClick = onClick)
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

        Spacer(Modifier.width(4.dp))

        IconAction(
            icon = if (isFavorite) SonanceIcons.HeartFilled else SonanceIcons.Heart,
            contentDescription = "收藏",
            onClick = onToggleFavorite,
            iconSize = 17.dp,
            tint = if (isFavorite) skin.accent else skin.textMuted
        )

        Box {
            IconAction(
                icon = SonanceIcons.MoreVertical,
                contentDescription = "更多",
                onClick = { menuOpen = true },
                iconSize = 17.dp,
                tint = skin.textMuted
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("下一首播放", color = skin.text) },
                    onClick = { menuOpen = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "取消收藏" else "添加到收藏", color = skin.text) },
                    onClick = { menuOpen = false; onToggleFavorite() }
                )
                if (onRemoveFromLibrary != null) {
                    DropdownMenuItem(
                        // 把「不删文件」写进标题里：这是菜单里最容易让人犹豫的一项，
                        // 不说清楚的话用户根本不敢点。这个版本的 Material3 的
                        // DropdownMenuItem 没有 supportingText，只能并进一行。
                        text = { Text("从列表移除（不删文件）", color = skin.text) },
                        onClick = { menuOpen = false; onRemoveFromLibrary() }
                    )
                }
                if (onDeleteFile != null) {
                    DropdownMenuItem(
                        text = { Text("删除文件…", color = skin.text) },
                        onClick = { menuOpen = false; onDeleteFile() }
                    )
                }
            }
        }

        trailing?.invoke()
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
