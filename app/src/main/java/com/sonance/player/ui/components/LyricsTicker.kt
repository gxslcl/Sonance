package com.sonance.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.data.LyricLine
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.LyricsResult
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.indexAt
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

/**
 * 「歌词按钮那一行」被点开后的样子：**单行歌词**，随播放从上往下滚动切换。
 *
 * 设计约束（用户明确要求）：
 * - **不改变布局**：整行高度和原来那个「歌词」胶囊完全一致，只是内容换了
 * - 点开时右侧出现关闭图标，收起后图标消失、恢复原来的胶囊按钮
 * - 只用一行，切换时新的一句**从上方进入**、旧的一句向下退出
 *
 * 歌词来源与全屏歌词页共用 [LyricsRepository]，所以内嵌 / 本地缓存 / 在线三条路一致，
 * 并且同样只在这一行真正显示时才触发加载。
 */
@Composable
fun LyricsTickerRow(
    song: Song,
    positionMs: Long,
    durationMs: Long,
    prefs: Prefs,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    var result by remember(song.id) { mutableStateOf<LyricsResult?>(null) }

    val onlineEnabled = prefs.onlineLyricsEnabled
    val apiBase = prefs.lyricsApiBase
    val convertT2S = prefs.lyricsConvertToSimplified

    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S) {
        result = LyricsRepository.load(
            context = context,
            songId = song.id,
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            onlineEnabled = onlineEnabled,
            apiBase = apiBase,
            convertToSimplified = convertT2S
        )
    }

    val line = remember(result, positionMs, durationMs) {
        currentLineText(result?.lyrics, positionMs, durationMs)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 和「歌词」胶囊同一高度，保证布局不跳
            .height(TICKER_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(TICKER_ROW_HEIGHT / 2))
                .background(skin.surface2)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = line,
                transitionSpec = {
                    // 新的一句从上方滑入，旧的一句向下滑出 —— 就是「从上往下滚动」
                    (slideInVertically(tween(220)) { -it } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(220)) { it } + fadeOut(tween(160)))
                },
                label = "lyric-ticker"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text == NO_LYRICS) skin.textMuted else skin.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 关闭图标：只在歌词展开时出现，点一下恢复原来的「歌词」胶囊
        IconAction(
            icon = SonanceIcons.Close,
            contentDescription = "收起歌词",
            onClick = onClose,
            iconSize = 18.dp,
            tint = skin.textMuted
        )
    }
}

/** 整行高度 —— 与 LyricsEntryButton 胶囊保持一致，展开/收起时布局不跳 */
private val TICKER_ROW_HEIGHT = 30.dp

private const val NO_LYRICS = "这首歌没有歌词"

/**
 * 取"当前应该显示哪一句"。
 *
 * - 有时间轴：二分找 `timeMs <= positionMs` 的最后一行
 * - 只有纯文本：按播放进度比例估算（粗糙但能滚起来，总比一直停在第一行好）
 * - 没有歌词 / 还没加载完：给一句提示
 */
private fun currentLineText(lyrics: Lyrics?, positionMs: Long, durationMs: Long): String = when (lyrics) {
    null -> NO_LYRICS

    is Lyrics.Synced -> {
        val idx = lyrics.lines.indexAt(positionMs)
        lyrics.lines.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: NO_LYRICS
    }

    is Lyrics.Plain -> {
        val lines = lyrics.lines
        if (lines.isEmpty()) {
            NO_LYRICS
        } else if (durationMs <= 0L) {
            lines.first()
        } else {
            val frac = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 0.999f)
            lines[(frac * lines.size).toInt().coerceIn(0, lines.lastIndex)]
        }
    }
}
