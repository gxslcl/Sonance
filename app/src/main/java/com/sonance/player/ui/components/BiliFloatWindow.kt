package com.sonance.player.ui.components

import android.content.Intent
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sonance.player.playback.BiliVideoSession
import com.sonance.player.ui.VideoPlayerActivity
import com.sonance.player.ui.icon.SonanceIcons
import kotlin.math.roundToInt

/**
 * 应用内视频浮窗（不是系统级 PiP / 悬浮窗权限）。
 *
 * 这是按用户指定的基线回退后的版本：
 * - 拖动：只认「浮窗上 1/3」起手（按实际窗口高度的 1/3 计算），该区域任意方向都移动窗口；
 * - 纵向滑动：下 2/3 起手时上滑下一首、下滑上一首，仍留在浮窗内；
 * - 单击：回全屏；
 * - 底部按钮：上一曲 · 播放/暂停 · 下一曲 · 全屏 · 关闭。
 *
 * 手势仍沿用该版的做法：一个覆盖视频区 / 标题条的透明手势层，
 * 拖动与纵向切条在同一个 `detectDragGestures` 里按「起手区域」分流，
 * 单击用独立的 `detectTapGestures`。没有「根容器统一手势 + 按钮矩形让行」，
 * 也没有改按钮的命中判定。
 */
@Composable
fun BiliFloatWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = remember { BiliVideoSession.of(context) }
    if (!session.floating || session.items.isEmpty()) return

    val density = LocalDensity.current
    val windowW = 300.dp
    val windowH = 169.dp
    val marginPx = with(density) { 12.dp.toPx() }
    // 纵向滑动切条的判定阈值（沿用旧版 28dp，不变）
    val switchThresholdPx = with(density) { 28.dp.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        if (maxW <= 0f || maxH <= 0f) return@BoxWithConstraints
        val wPx = with(density) { windowW.toPx() }
        val hPx = with(density) { windowH.toPx() }
        // 拖动区：浮窗实际高度的上 1/3（随 hPx 重算，不写死 dp）
        val dragZonePx = hPx / 3f
        val topInset = with(density) { 72.dp.toPx() }
        val bottomInset = with(density) { 96.dp.toPx() }

        fun minNx() = (marginPx + wPx / 2f) / maxW
        fun maxNx() = (maxW - marginPx - wPx / 2f).coerceAtLeast(marginPx + wPx / 2f) / maxW
        fun minNy() = (topInset + hPx / 2f) / maxH
        fun maxNy() = (maxH - bottomInset - hPx / 2f).coerceAtLeast(topInset + hPx / 2f) / maxH

        var nx by remember(maxW) {
            mutableFloatStateOf(
                (session.floatX.takeIf { it >= 0f } ?: 0.82f).coerceIn(minNx(), maxNx())
            )
        }
        var ny by remember(maxH) {
            mutableFloatStateOf(
                (session.floatY.takeIf { it >= 0f } ?: 0.68f).coerceIn(minNy(), maxNy())
            )
        }

        // 记录每次按下的纵坐标（只读事件、不消费），用于把拖动区边界落在真正的起手点上
        val downY = remember { mutableFloatStateOf(-1f) }

        val title = session.current?.title.orEmpty()

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (nx * maxW - wPx / 2f).roundToInt(),
                        (ny * maxH - hPx / 2f).roundToInt()
                    )
                }
                .size(windowW, windowH)
                .shadow(10.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
        ) {
            // 视频画面：会话的唯一宿主
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> TextureView(ctx).also { session.attachHost(it) } },
                onRelease = { tv -> session.detachHost(tv) }
            )

            // 顶部标题条（视觉层 + 拖动由手势层统一处理）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // 准备中 / 出错
            if (!session.prepared) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (session.error != null) {
                        Text(
                            text = session.error ?: "",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // 手势层：上 1/3 拖动窗口；下 2/3 上下滑切条、单击回全屏
            Box(
                Modifier
                    .fillMaxSize()
                    // 只读取按下位置（不消费、不参与命中判定），让 1/3 边界对应真正的起手点
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                if (down != null) downY.floatValue = down.position.y
                            }
                        }
                    }
                    // 拖动：起手落在上 1/3 → 任意方向都移动窗口；落在下 2/3 → 只做上下切条。
                    .pointerInput(maxW, maxH, dragZonePx) {
                        var total = Offset.Zero
                        var fromDragZone = false
                        detectDragGestures(
                            onDragStart = {
                                total = Offset.Zero
                                fromDragZone = downY.floatValue in 0f..dragZonePx
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                total += amount
                                if (fromDragZone) {
                                    nx = (nx + amount.x / maxW).coerceIn(minNx(), maxNx())
                                    ny = (ny + amount.y / maxH).coerceIn(minNy(), maxNy())
                                }
                            },
                            onDragEnd = {
                                if (fromDragZone) {
                                    session.saveFloatPosition(nx, ny)
                                } else if (total.y <= -switchThresholdPx) {
                                    session.next()
                                } else if (total.y >= switchThresholdPx) {
                                    session.previous()
                                }
                            },
                            onDragCancel = {
                                if (fromDragZone) session.saveFloatPosition(nx, ny)
                            }
                        )
                    }
                    // 单击：回全屏。按钮在自己那一层消费 down，这里不会误触发。
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { openFullscreen(context, session) }
                        )
                    }
            )

            // 底部渐变压暗（纯视觉，不参与命中）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )

            // 底部最小控制集：上一曲 · 播放/暂停 · 下一曲 · 全屏 · 关闭
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatIconButton(
                    icon = SonanceIcons.Prev,
                    description = "上一首",
                    enabled = session.canPrevious,
                    onClick = { session.previous() }
                )
                FloatIconButton(
                    icon = if (session.isPlaying) SonanceIcons.Pause else SonanceIcons.Play,
                    description = if (session.isPlaying) "暂停" else "播放",
                    onClick = { session.toggle() }
                )
                FloatIconButton(
                    icon = SonanceIcons.Next,
                    description = "下一首",
                    enabled = session.canNext,
                    onClick = { session.next() }
                )
                FloatIconButton(
                    icon = SonanceIcons.Maximize,
                    description = "全屏播放",
                    onClick = { openFullscreen(context, session) }
                )
                FloatIconButton(
                    icon = SonanceIcons.Close,
                    description = "关闭浮窗",
                    onClick = { session.closeFloating() }
                )
            }
        }
    }
}

@Composable
private fun FloatIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .pointerInput(enabled, onClick) {
                detectTapGestures(onTap = { if (enabled) onClick() })
            }
    )
}

/** 浮窗转全屏：会话不重建，Activity 只是换一个宿主 */
private fun openFullscreen(context: android.content.Context, session: BiliVideoSession) {
    val current = session.current ?: return
    val keys = session.items.map { it.key }
    val intent = Intent(context, VideoPlayerActivity::class.java).apply {
        putExtra(VideoPlayerActivity.EXTRA_VIDEO, session.currentPathOrNull ?: current.fallbackPath)
        putStringArrayListExtra(VideoPlayerActivity.EXTRA_KEYS, ArrayList(keys))
        putExtra(VideoPlayerActivity.EXTRA_KEY, current.key)
        putExtra(VideoPlayerActivity.EXTRA_TITLE, current.title)
        putExtra(VideoPlayerActivity.EXTRA_OWNER, current.owner)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    session.exitFloating()
    context.startActivity(intent)
}
