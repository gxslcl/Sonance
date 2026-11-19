package com.sonance.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.StatsRange
import com.sonance.player.data.StatsSummary
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.glassCardBg
import com.sonance.player.ui.theme.glassCardBorder
import com.sonance.player.ui.theme.softPillBg

/**
 * 听歌统计页：本周 / 本月 / 全部 三档。
 *
 * 数据来自 [SonanceViewModel.listenStatsSummary]（播放中按真实出声时长记账），
 * [SonanceViewModel.statsRevision] 变化时重算 —— 边听边看也能实时涨。
 */
@Composable
fun ListenStatsScreen(vm: SonanceViewModel, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    var range by remember { mutableStateOf(StatsRange.WEEK) }
    val summary = remember(vm.statsRevision, range) { vm.listenStatsSummary(range) }
    val cardBg = skin.glassCardBg()
    val cardBorder = skin.glassCardBorder()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 与首页分段器同款的柔和胶囊切换 ----
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(skin.softPillBg())
                .border(0.7.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatsRange.entries.forEach { r ->
                val on = range == r
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (on) skin.accent.copy(alpha = if (skin.isLight) 0.14f else 0.24f)
                            else Color.Transparent
                        )
                        .clickable { range = r }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) skin.accent else skin.textDim,
                        maxLines = 1
                    )
                }
            }
        }

        if (summary.totalSeconds <= 0L && summary.totalPlays <= 0) {
            EmptyState(
                icon = SonanceIcons.EqualizerBars,
                title = "这一档还没有收听记录",
                subtitle = "播放歌曲后，这里会记下你的收听时长与偏好",
                modifier = Modifier.fillMaxWidth().height(300.dp)
            )
            return@Column
        }

        // ---- 三列核心指标玻璃卡（不再把连续听歌拉成孤立长条） ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryCard(
                label = "收听时长",
                value = summary.totalText,
                highlight = true,
                modifier = Modifier.weight(1.15f)
            )
            SummaryCard(
                label = "播放次数",
                value = "${summary.totalPlays} 次",
                modifier = Modifier.weight(0.95f)
            )
            SummaryCard(
                label = "连续听歌",
                value = if (summary.streakDays > 0) "${summary.streakDays} 天" else "0 天",
                modifier = Modifier.weight(0.9f)
            )
        }

        // ---- 收听节奏玻璃卡 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cardBg)
                .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            val peakBar = summary.bars.maxByOrNull { it.seconds }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("收听节奏", modifier = Modifier.weight(1f))
                if (peakBar != null && peakBar.seconds > 0L) {
                    Text(
                        text = "峰值 ${peakBar.label} · ${formatBarDuration(peakBar.seconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(skin.accentGlow)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            StatsBarChart(summary = summary)
        }

        // ---- 歌手偏好玻璃卡 ----
        if (summary.topArtists.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(cardBg)
                    .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("歌手偏好")
                val maxPlays = summary.topArtists.first().second.coerceAtLeast(1)
                summary.topArtists.forEachIndexed { idx, (artist, plays) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (idx == 0) skin.accent else skin.textMuted,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.labelMedium,
                            color = skin.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(92.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(skin.softPillBg())
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((plays.toFloat() / maxPlays).coerceAtLeast(0.08f))
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                skin.accent.copy(alpha = if (idx == 0) 0.95f else 0.72f),
                                                skin.accent.copy(alpha = 0.45f)
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "$plays 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textDim
                        )
                    }
                }
            }
        }

        // ---- 单曲 Top 5 玻璃卡 ----
        if (summary.topSongs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(cardBg)
                    .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("单曲 Top 5", modifier = Modifier.weight(1f))
                    Text(
                        text = "点击即播",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                summary.topSongs.forEachIndexed { index, stat ->
                    val topRank = index < 3
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val song = vm.songById(stat.songId)
                                if (song != null) vm.playFrom(listOf(song), song)
                                else vm.showToast("这首歌已不在曲库")
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (index == 0) skin.accentGlow else skin.softPillBg()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (topRank) skin.accent else skin.textMuted
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stat.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = skin.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stat.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${stat.plays} 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textDim,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(skin.softPillBg())
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = skin.text,
        modifier = modifier
    )
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val skin = LocalSkin.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(skin.glassCardBg(highlighted = highlight))
            .border(0.8.dp, skin.glassCardBorder(highlighted = highlight), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (highlight) skin.accent else skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatBarDuration(seconds: Long): String {
    val mins = (seconds / 60L).coerceAtLeast(if (seconds > 0) 1L else 0L)
    return if (mins >= 60) "${mins / 60}h${mins % 60}m" else "${mins}m"
}

/** 柱状图：高度按秒数线性缩放，带顶部时长提示与圆润渐变柱形。 */
@Composable
private fun StatsBarChart(summary: StatsSummary) {
    val skin = LocalSkin.current
    val bars = summary.bars
    val maxSeconds = (bars.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { bar ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (bar.seconds > 0L) formatBarDuration(bar.seconds) else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.5.sp,
                    color = if (bar.highlight) skin.accent else skin.textMuted,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                val fraction = bar.seconds.toFloat() / maxSeconds
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .height(if (bar.seconds > 0) (96 * fraction).dp.coerceAtLeast(6.dp) else 4.dp)
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(
                            when {
                                bar.seconds <= 0 -> Brush.verticalGradient(
                                    listOf(skin.softPillBg(), skin.softPillBg())
                                )
                                bar.highlight -> Brush.verticalGradient(
                                    listOf(skin.accent, skin.accent.copy(alpha = 0.65f))
                                )
                                else -> Brush.verticalGradient(
                                    listOf(skin.accent.copy(alpha = 0.52f), skin.accent.copy(alpha = 0.22f))
                                )
                            }
                        )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (bar.highlight) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (bar.highlight) skin.accent else skin.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 「最近播放」页顶部的本周概览卡。设置里可关（默认开）；
 * 本周还没听过歌时不显示，避免新用户一进来就看到一排零。
 */
@Composable
fun WeekOverviewCard(
    vm: SonanceViewModel,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val summary = remember(vm.statsRevision) { vm.listenStatsSummary(StatsRange.WEEK) }
    if (summary.totalSeconds <= 0L && summary.totalPlays <= 0) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(skin.glassCardBg())
            .border(0.8.dp, skin.glassCardBorder(), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenStats)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本周听歌足迹",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(skin.accentGlow)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "完整报告",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textDim
                )
                Icon(
                    imageVector = SonanceIcons.ChevronDown,
                    contentDescription = null,
                    tint = skin.textDim,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = summary.totalText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = buildString {
                    append("播放 ${summary.totalPlays} 次")
                    if (summary.streakDays > 0) append(" · 连续 ${summary.streakDays} 天")
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        summary.topArtists.firstOrNull()?.let { (artist, _) ->
            Spacer(Modifier.height(5.dp))
            Text(
                text = "本周最爱歌手 · $artist",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}