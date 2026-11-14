package com.sonance.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.AudioTechSpec
import com.sonance.player.data.HiResQuality
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.Song
import com.sonance.player.data.formatSize
import com.sonance.player.ui.theme.HiResBadgeSize
import com.sonance.player.ui.theme.LocalSkin

/**
 * 解析当前歌是否应该在封面上点亮「Hi-Res AUDIO」小金标。
 * - 在线歌直接读取真实流音质标签（Hi-Res / 无损 / 320k）；
 * - 本地歌优先同步判断扩展名与探测缓存，否则异步读音频头并缓存结果。
 */
@Composable
fun rememberHiResBadgeVisible(song: Song, streamQuality: String?): Boolean {
    val context = LocalContext.current
    if (!song.isLocal) {
        val label = streamQuality ?: remember(song.source, song.remoteId) {
            song.remoteId?.let { rid -> OnlineSources.byId(song.source)?.streamQualityLabel(rid) }
        }
        return HiResQuality.shouldHighlightOnlineQuality(label)
    }

    var localShow by remember(song.id, song.sizeBytes) {
        mutableStateOf(HiResQuality.peekLocalBadge(song) == true)
    }
    LaunchedEffect(song.id, song.sizeBytes) {
        localShow = HiResQuality.peekLocalBadge(song) ?: HiResQuality.probeLocalBadge(context, song)
    }
    return localShow
}

/** 返回小金标含外底衬框在内的总占高，供播放页精确对齐封面外右下角位置。 */
fun hiResBadgeTotalDp(badgeSize: HiResBadgeSize): Dp {
    val scale = badgeSize.badgeDp / 24f
    return (28f * scale).dp
}

/**
 * 经典 JAS 黑金小金标（上黄黑字「Hi·Res」+ 金色微笑分割弧线 + 下黑金字「AUDIO」）。
 * 外层带极轻暗色微底衬框，支持小 / 中 / 大三档矢量等比缩放。
 */
@Composable
fun HiResGoldBadge(
    badgeSize: HiResBadgeSize = HiResBadgeSize.SMALL,
    modifier: Modifier = Modifier
) {
    // 固定尺寸徽章文字不随系统大字体膨胀撑破，按 fontScale 反比归一化字号
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(0.8f)
    val scale = badgeSize.badgeDp / 24f
    val sealShape = RoundedCornerShape((3f * scale).dp)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape((4.5f * scale).dp))
            .background(Color(0xFF100B0F).copy(alpha = 0.45f))
            .padding((2f * scale).dp)
    ) {
        Box(
            modifier = Modifier
                .size((24f * scale).dp)
                .clip(sealShape)
                .border((0.8f * scale).dp, Color(0xFFF4B71A).copy(alpha = 0.72f), sealShape)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFF4AD), Color(0xFFFFD52E), Color(0xFFD98E00)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                )
                val curveY = h * 0.53f
                val dipY = h * 0.74f
                val darkBottom = Path().apply {
                    moveTo(0f, curveY)
                    quadraticTo(w * 0.5f, dipY, w, curveY)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(darkBottom, color = Color(0xFF1D1417))

                val seamHighlight = Path().apply {
                    moveTo(0f, curveY)
                    quadraticTo(w * 0.5f, dipY, w, curveY)
                }
                drawPath(
                    path = seamHighlight,
                    color = Color(0xFFFFDE63),
                    style = Stroke(width = (0.9f * scale).dp.toPx())
                )
            }
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Hi·Res",
                        color = Color(0xFF1D1417),
                        fontSize = (6.8f * scale / fontScale).sp,
                        lineHeight = (7.2f * scale / fontScale).sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-0.35f * scale).sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxWidth()
                        .padding(top = (1f * scale).dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "AUDIO",
                        color = Color(0xFFF6B818),
                        fontSize = (4.4f * scale / fontScale).sp,
                        lineHeight = (4.8f * scale / fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (0.3f * scale).sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 歌曲音频技术规格卡片（供长按菜单的「识别到歌曲」与「编辑歌曲信息」共享）。
 * 展示格式、占用大小、比特率以及采样率 / 位深（如 `16bit / 44.1kHz FLAC`）。
 */
@Composable
fun SongAudioSpecCard(
    song: Song,
    modifier: Modifier = Modifier
) {
    if (!song.isLocal) return
    val context = LocalContext.current
    val skin = LocalSkin.current
    var spec by remember(song.id, song.sizeBytes) {
        mutableStateOf<AudioTechSpec?>(HiResQuality.peekAudioTechSpec(song))
    }
    LaunchedEffect(song.id, song.sizeBytes) {
        if (spec == null) {
            spec = HiResQuality.probeAudioTechSpec(context, song)
        }
    }

    val currentSpec = spec
    val formatLabel = currentSpec?.formatLabel ?: HiResQuality.inferFormatLabel(song.displayName, null)
    val summaryText = currentSpec?.summarySpecText() ?: "$formatLabel · 读取中…"
    val sizeText = formatSize(song.sizeBytes)
    val bitrateText = currentSpec?.bitrateText() ?: "读取中…"
    val sampleBitText = when {
        currentSpec == null -> "读取中…"
        currentSpec.bitDepth > 0 && currentSpec.sampleRateHz > 0 ->
            "${currentSpec.bitDepth}bit / ${HiResQuality.formatSampleRateKHz(currentSpec.sampleRateHz)}"
        currentSpec.sampleRateHz > 0 ->
            HiResQuality.formatSampleRateKHz(currentSpec.sampleRateHz)
        currentSpec.bitDepth > 0 -> "${currentSpec.bitDepth}bit"
        else -> "未知"
    }
    val showGoldBadge = currentSpec?.let { HiResQuality.shouldHighlightLocalAudio(song, it) }
        ?: HiResQuality.isLosslessExtension(song.displayName)

    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(skin.surface2.copy(alpha = 0.72f))
            .border(0.8.dp, skin.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = summaryText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
            if (showGoldBadge) {
                HiResGoldBadge()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SpecMetaCell("格式", formatLabel, Modifier.weight(1f))
            SpecMetaCell("占用大小", sizeText, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SpecMetaCell("比特率", bitrateText, Modifier.weight(1f))
            SpecMetaCell("采样 / 位深", sampleBitText, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpecMetaCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = skin.text
        )
    }
}
