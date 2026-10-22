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
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import com.sonance.player.ui.theme.AppIcons
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
    // 在线音乐放在「播放列表」这一组，不放进「资料库」——
    // 上面那组全是本机上的东西，混一个要联网的进来会让人误会
    NavEntry(LibraryTab.ONLINE, SonanceIcons.Search, 1),
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
        LibraryTab.ONLINE to SonanceIcons.Search,
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
    /**
     * 把副标题并到标题**同一行**（`全部歌曲 · 共 315 首 · 21:19:12`）。
     *
     * 列表页开这个：标题是固定的 tab 名（最多 5 个字），和副标题同行放得下，
     * 而且能省掉一整行 —— 之前副标题独占一行，列表被白白压掉 20dp 左右。
     *
     * 详情页（专辑 / 艺术家 / 文件夹）**不要开**：那里的标题是专辑名，
     * 可能很长，和副标题挤一行会把标题截得只剩几个字。
     */
    subtitleInline: Boolean = false,
    actions: @Composable (() -> Unit)? = null,
    /**
     * 标题左边那个小方块的图案（`AppIcons` 的 id）。空串 = 内置的 Logo 矢量图。
     *
     * ⚠️ 这是**应用内**的顶栏图标，和桌面启动器图标是两码事
     * （那个走 `AppIconSwitcher` / `activity-alias`）。
     */
    logoId: String = "",
    /**
     * **双击**标题（或左侧 logo）触发的动作，null 就没有这个手势。
     *
     * 主列表页用它打开"应用名 / 桌面图标"编辑面板 —— 这两样是应用的门面，
     * 藏在设置深处没人会去改；顶栏标题就是门面本身，双击它是最近的路。
     * 详情页不传：那里的双击没有语义，反而容易误触。
     */
    onTitleDoubleTap: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current

    // 双击检测：自己比时间戳（800ms），**没有单击动作**所以不需要延迟判定 ——
    // 单击只是记一笔，第二次点击落进来就触发。
    val lastTitleTap = remember { longArrayOf(0L) }
    val titleDoubleTap: (() -> Unit)? = onTitleDoubleTap?.let { action ->
        {
            val now = System.currentTimeMillis()
            if (now - lastTitleTap[0] < 800L) {
                lastTitleTap[0] = 0L
                action()
            } else {
                lastTitleTap[0] = now
            }
        }
    }

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
                    .background(skin.surface2)
                    .then(
                        if (titleDoubleTap != null) {
                            Modifier.clickable(onClick = titleDoubleTap)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (logoId.isNotBlank()) {
                    // 用户挑的图案：直接用 AppIcons 里那份预览图，
                    // 和设置页 / 编辑面板里看到的是同一张
                    androidx.compose.foundation.Image(
                        painter = painterResource(AppIcons.byId(logoId).preview),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    androidx.compose.material3.Icon(
                        imageVector = SonanceIcons.Logo,
                        contentDescription = null,
                        tint = skin.accent,
                        modifier = Modifier.size(if (dense) 15.dp else 17.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (subtitleInline && subtitle != null) {
            // 标题 + 副标题同一行。用基线对齐，两种字号看起来才是在一条线上。
            // 标题 `weight(1f, fill = false)`：空间不够时先压缩标题（省略号），
            // 副标题（信息量更大）保持完整。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = title,
                    style = if (dense) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .alignByBaseline()
                        .then(
                            if (titleDoubleTap != null) {
                                Modifier.clickable(onClick = titleDoubleTap)
                            } else {
                                Modifier
                            }
                        )
                )
                Text(
                    text = "· $subtitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .alignByBaseline()
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (dense) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (titleDoubleTap != null) {
                            Modifier.clickable(onClick = titleDoubleTap)
                        } else {
                            Modifier
                        }
                    )
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
        }
        actions?.invoke()
    }
}
