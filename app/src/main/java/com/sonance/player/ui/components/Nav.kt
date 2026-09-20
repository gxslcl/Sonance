package com.sonance.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.LibraryTab
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

private data class NavEntry(
    val tab: LibraryTab,
    val icon: ImageVector,
    val section: Int
)

private val navEntries = listOf(
    NavEntry(LibraryTab.SONGS, SonanceIcons.Music, 0),
    NavEntry(LibraryTab.ALBUMS, SonanceIcons.Album, 0),
    NavEntry(LibraryTab.ARTISTS, SonanceIcons.Users, 0),
    NavEntry(LibraryTab.FOLDERS, SonanceIcons.Folder, 0),
    NavEntry(LibraryTab.RECENT, SonanceIcons.Clock, 1),
    NavEntry(LibraryTab.FAVORITES, SonanceIcons.Heart, 1),
    NavEntry(LibraryTab.SETTINGS, SonanceIcons.Palette, 2)
)

private val sectionTitles = listOf("资料库", "播放列表", "")

/* ============================================================
 * 侧边导航（平板 / 宽屏）
 * ============================================================ */

@Composable
fun SideNavigation(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    expanded: Boolean,
    songCount: Int,
    favoriteCount: Int,
    storageText: String,
    storageFraction: Float,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current
    val width = when {
        expanded -> 232.dp
        dense -> 72.dp
        else -> 88.dp
    }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(skin.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = if (dense) 10.dp else 20.dp)
    ) {
        // 品牌（矮屏窄栏下省略）
        if (expanded || !dense) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (expanded) 22.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(skin.bg),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Logo,
                        contentDescription = null,
                        tint = skin.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
                if (expanded) {
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(
                            text = "Sonance",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = skin.text
                        )
                        Text(
                            text = "本地音乐播放器",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (dense) 10.dp else 18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (expanded) 16.dp else 18.dp)
                    .height(1.dp)
                    .background(skin.border)
            )
            Spacer(Modifier.height(10.dp))
        }

        var lastSection = -1
        navEntries.forEach { entry ->
            if (entry.section != lastSection) {
                lastSection = entry.section
                if (expanded) {
                    val title = sectionTitles.getOrElse(entry.section) { "" }
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textMuted,
                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp)
                        )
                    } else {
                        Spacer(Modifier.height(14.dp))
                    }
                } else {
                    if (entry.section == 1) Spacer(Modifier.height(if (dense) 6.dp else 10.dp))
                    if (entry.section == 2) Spacer(Modifier.height(if (dense) 8.dp else 14.dp))
                }
            }

            val badge = when (entry.tab) {
                LibraryTab.SONGS -> songCount.takeIf { it > 0 }?.toString()
                LibraryTab.FAVORITES -> favoriteCount.takeIf { it > 0 }?.toString()
                else -> null
            }
            NavRow(
                icon = entry.icon,
                label = entry.tab.label,
                selected = selected == entry.tab,
                expanded = expanded,
                badge = badge,
                onClick = { onSelect(entry.tab) }
            )
        }

        Spacer(Modifier.weight(1f, fill = true))
        Spacer(Modifier.height(16.dp))

        if (expanded) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("本地音乐", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(skin.surface3)
                ) {
                    if (storageFraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(storageFraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(skin.accent)
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(storageText, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            }
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    if (expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) skin.accentGlow else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) skin.accent else skin.textDim,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) skin.accent else skin.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (badge != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(skin.surface3)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                }
            }
        }
    } else {
        val dense = LocalDenseLayout.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (dense) 10.dp else 16.dp, vertical = if (dense) 1.dp else 2.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) skin.accentGlow else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(vertical = if (dense) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (dense) 3.dp else 5.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) skin.accent else skin.textDim,
                modifier = Modifier.size(if (dense) 18.dp else 21.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) skin.accent else skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/* ============================================================
 * 底部导航（手机）
 * ============================================================ */

@Composable
fun AppBottomBar(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val items = LibraryTab.bottomBar
    val icons = mapOf(
        LibraryTab.SONGS to SonanceIcons.Music,
        LibraryTab.ALBUMS to SonanceIcons.Album,
        LibraryTab.ARTISTS to SonanceIcons.Users,
        LibraryTab.RECENT to SonanceIcons.Clock,
        LibraryTab.FAVORITES to SonanceIcons.Heart,
        LibraryTab.SETTINGS to SonanceIcons.Palette
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(skin.surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { tab ->
            val isSel = selected == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icons[tab] ?: SonanceIcons.Music,
                    contentDescription = tab.label,
                    tint = if (isSel) skin.accent else skin.textMuted,
                    modifier = Modifier.size(21.dp)
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSel) skin.accent else skin.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/* ============================================================
 * 顶栏（手机）
 * ============================================================ */

@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (dense) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            IconAction(
                icon = SonanceIcons.ArrowLeft,
                contentDescription = "返回",
                onClick = onBack,
                iconSize = 20.dp,
                tint = skin.text
            )
        } else {
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .size(if (dense) 26.dp else 30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(skin.surface2),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = SonanceIcons.Logo,
                    contentDescription = null,
                    tint = skin.accent,
                    modifier = Modifier.size(if (dense) 15.dp else 17.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (dense) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null && !dense) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions?.invoke()
    }
}
