package com.sonance.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.data.LyricLine
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.indexAt
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏「竖屏歌词」模式（竖排卷轴）。
 *
 * 设计要点：
 * 1. **从右往左列序、自上而下逐字**：当前句锚定在舞台中偏右黄金分割位，已唱列向右淡出、
 *    未唱列在左侧排开，支持左右横向滑动浏览与点击列跳转进度。
 * 2. **中英/数字混排与英文专门适配**：
 *    - 汉字、假名等 CJK 字符正立逐字竖排，竖排标点自动映射（`（` → `︵`、`《` → `︽` 等）；
 *    - 2~3 位的短数字或缩写（如 `99`、`OK`、`DJ`）作为横排紧凑字块（Tate-chu-yoko）正立内嵌在竖列中；
 *    - 较长的英文单词/短语（如 `Love`、`Forever`、`2026`）按词聚合，**顺时针旋转 90°** 并置换宽高布局槽位，
 *      不会把英文拆成歪斜的单字母竖串；
 *    - 超长句子（如纯英文长句或多句合并）自动拆分为并列子列，绝不冲出屏幕下边界。
 * 3. **三级兜底**：
 *    - 同步歌词为空列表或全部是空白行 → 自动回退到竖排曲名/歌手诗笺展示；
 *    - 纯文本歌词（无时间轴 `Lyrics.Plain`） → [VerticalPlainLyrics] 竖排卷轴自由横滑阅读；
 *    - 无歌词（`lyrics == null`） → [VerticalEmptyLyrics] 竖排曲名诗笺 + 手动搜索 / 清除缓存入口。
 */
@Composable
internal fun VerticalSyncedLyrics(
    lines: List<LyricLine>,
    song: Song,
    positionMs: Long,
    durationMs: Long,
    timelineOffsetMs: Long,
    isPlaying: Boolean,
    fontSizeSp: Int,
    onSeek: (Long) -> Unit,
    bgProfile: BgProfile?,
    adaptMode: LyricAdaptMode,
    skin: Skin,
    /**
     * 手指落在这个舞台上时通知外层播放页锁住横向翻页。
     *
     * 竖屏歌词的「横滑翻列」和外层三层轮播抢同一条水平手势：外层先吃掉
     * 一次滑动，用户就会觉得「想翻歌词结果切了页」。按下即上锁、抬手解锁，
     * 保证一次手势只做一件事。
     */
    onDragLockChange: (Boolean) -> Unit = {}
) {
    // 兜底 1：过滤掉完全空白的无效时间轴行；若整首歌没有有效歌词文本，转入竖排诗笺兜底
    val validLines = remember(lines) {
        lines.map { line ->
            if (line.text.isBlank()) line.copy(text = "•••") else line
        }
    }
    if (validLines.isEmpty()) {
        VerticalPlainLyrics(
            lines = listOf(song.title, song.artist.ifBlank { "未知艺术家" }, "暂无歌词内容"),
            fontSizeSp = fontSizeSp,
            bgProfile = bgProfile,
            adaptMode = adaptMode
        )
        return
    }

    key(validLines) {
        val smoothPos = rememberSmoothPosition(positionMs, isPlaying, alignmentKey = timelineOffsetMs)
        val nextTimes = remember(validLines) { lyricNextTimes(validLines.map { it.timeMs }) }
        val current by remember(smoothPos) { derivedStateOf { validLines.indexAt(smoothPos.longValue) } }

        val stage = remember { mutableFloatStateOf(current.coerceAtLeast(0).toFloat()) }
        val maxStage = remember(validLines) { validLines.lastIndex.toFloat().coerceAtLeast(0f) }
        val lastTouch = remember { longArrayOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val settleJob = remember { arrayOfNulls<Job>(1) }
        val returnJob = remember { arrayOfNulls<Job>(1) }

        suspend fun animateStage(target: Float) {
            val anim = Animatable(stage.floatValue)
            anim.animateTo(
                target.coerceIn(0f, maxStage),
                tween(360, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            ) {
                stage.floatValue = value
            }
        }

        fun scheduleReturn() {
            returnJob[0]?.cancel()
            returnJob[0] = scope.launch {
                delay(5_000L)
                if (dragging) return@launch
                val target = current.coerceAtLeast(0).toFloat()
                if (abs(target - stage.floatValue) < 0.001f) return@launch
                settleJob[0]?.cancel()
                animateStage(target)
            }
        }

        fun settleTo(target: Float) {
            lastTouch[0] = android.os.SystemClock.uptimeMillis()
            returnJob[0]?.cancel()
            settleJob[0]?.cancel()
            settleJob[0] = scope.launch {
                animateStage(target)
            }
        }

        LaunchedEffect(current, dragging) {
            if (dragging) return@LaunchedEffect
            if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < 5_000L) return@LaunchedEffect
            val target = current.coerceAtLeast(0).toFloat()
            if (abs(target - stage.floatValue) < 0.001f) return@LaunchedEffect
            if (abs(target - stage.floatValue) > 4f) {
                stage.floatValue = target
            } else {
                animateStage(target)
            }
        }

        val density = LocalDensity.current
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // 左右边缘柔和渐隐，营造卷轴展开感
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.08f to Color.Black,
                            0.92f to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            val w = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            // 单列基础宽度与列间距（随字号自适应）
            val baseSlotPx = with(density) { (fontSizeSp * 2.55f).dp.toPx() }
            val maxCharsPerSubCol = remember(h, fontSizeSp, density) {
                val charHeightPx = with(density) { (fontSizeSp * 1.36f).dp.toPx() }.coerceAtLeast(14f)
                ((h * 0.72f) / charHeightPx).toInt().coerceIn(6, 22)
            }

            // 预分词 & 计算每句占几列（长英文句或长中文句自动拆成多列，防止竖向溢出）
            val tokenizedLines = remember(validLines, maxCharsPerSubCol) {
                validLines.map { line ->
                    val cols = splitTokensIntoColumns(tokenizeVerticalLine(line.text), maxCharsPerSubCol)
                    val transCols = line.translation?.takeIf { it.isNotBlank() }?.let {
                        splitTokensIntoColumns(tokenizeVerticalLine(it), (maxCharsPerSubCol * 1.25f).toInt())
                    }.orEmpty()
                    VerticalLineLayout(
                        line = line,
                        mainColumns = cols,
                        translationColumns = transCols
                    )
                }
            }

            // 每句的横向槽位宽度（多列句自动加宽，互不重叠）
            val slotWidths = remember(tokenizedLines, baseSlotPx, density) {
                val subColStep = with(density) { (fontSizeSp * 1.48f).dp.toPx() }
                val transStep = with(density) { (fontSizeSp * 1.12f).dp.toPx() }
                tokenizedLines.map { item ->
                    val extraMain = (item.mainColumns.size - 1).coerceAtLeast(0) * subColStep
                    val extraTrans = item.translationColumns.size * transStep
                    baseSlotPx + extraMain + extraTrans
                }
            }
            val centers = remember(slotWidths, baseSlotPx) {
                lyricStageCenters(slotWidths, baseSlotPx, baseSlotPx * 0.18f)
            }

            val a = stage.floatValue
            val anchorCenter = lyricStageAnchor(centers, a)
            // 当前句停在屏幕偏右 0.62 处（传统右起竖排视觉重心），左侧留出更多空间给未唱列
            val anchorX = w * 0.60f
            val from = maxOf(0, floor(a).toInt() - 5)
            val to = minOf(tokenizedLines.lastIndex, ceil(a).toInt() + 5)

            // 竖排暗纹装饰线（古典宣纸/竹简细栏线意境，极低透明度不抢戏）
            VerticalStageGridDecoration(skin = skin, anchorX = anchorX)

            // 离开这一层（切页 / 收起播放页）时一定要解锁，否则外层 pager 会永久卡住
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { onDragLockChange(false) }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    // 独立的手势观察层：只负责「按下 → 通知外层别再横滑」，不消费任何事件
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onDragLockChange(true)
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                onDragLockChange(false)
                            }
                        }
                    }
                    .pointerInput(centers) {
                        if (centers.size < 2) return@pointerInput
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                settleJob[0]?.cancel()
                                returnJob[0]?.cancel()
                                tracker.resetTracking()
                                dragging = true
                                onDragLockChange(true)
                                lastTouch[0] = android.os.SystemClock.uptimeMillis()
                            },
                            onDragCancel = {
                                dragging = false
                                onDragLockChange(false)
                                scheduleReturn()
                            },
                            onDragEnd = {
                                dragging = false
                                onDragLockChange(false)
                                lastTouch[0] = android.os.SystemClock.uptimeMillis()
                                val velocity = tracker.calculateVelocity().x
                                if (abs(velocity) > 120f) {
                                    // 列从右向左排：手指往右拖（dragAmount > 0）是看左边未来的列（行号增大）
                                    val travel = velocity * 0.11f
                                    val base = lyricStageAnchor(centers, stage.floatValue)
                                    settleTo(lyricIndexAtCenter(centers, base + travel))
                                }
                                scheduleReturn()
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val base = lyricStageAnchor(centers, stage.floatValue)
                            // 列从右往左排：未来句在左侧（x 更小），往右拉（dragAmount > 0）把左侧的未来句拉向中心
                            val next = (base + dragAmount).coerceIn(centers.first(), centers.last())
                            val snapped = lyricIndexAtCenter(centers, next)
                            val lineBefore = round(stage.floatValue).toInt()
                            stage.floatValue = snapped
                            if (round(snapped).toInt() != lineBefore) hapticTick(context)
                            tracker.addPosition(change.uptimeMillis, change.position)
                            lastTouch[0] = android.os.SystemClock.uptimeMillis()
                        }
                    }
            ) {
                for (index in from..to) {
                    key(index) {
                        val item = tokenizedLines[index]
                        val line = item.line
                        val diff = index - a
                        val absDiff = abs(diff)
                        val t = (1f - absDiff).coerceIn(0f, 1f)
                        val emphasis = t * t * (3f - 2f * t)
                        val edgeFade = (5.2f - absDiff).coerceIn(0f, 1f)

                        // 右起竖排：index < a（已唱）在右侧（+x），index > a（未唱）在左侧（-x）
                        val distancePx = centers[index] - anchorCenter
                        val xCenter = anchorX - distancePx
                        val scale = (0.84f + 0.18f * emphasis) * (1f - absDiff * 0.03f).coerceAtLeast(0.75f)
                        val alpha = ((0.64f - absDiff * 0.11f).coerceAtLeast(0.15f) + 0.36f * emphasis) * edgeFade
                        // 错落有致的诗行起伏：距离中心越远稍微下沉一点
                        val yShift = absDiff * with(density) { 10.dp.toPx() }

                        val isCurrent = index == current
                        val baseColor = when {
                            isCurrent -> lerp(skin.textDim, skin.accent, emphasis.coerceAtLeast(0.65f))
                            index < current -> skin.textMuted
                            else -> skin.textDim
                        }
                        val color = if (bgProfile != null && shouldAdapt(isCurrent, adaptMode)) {
                            lyricColor(baseColor, 0.45f, isCurrent, bgProfile)
                        } else {
                            baseColor
                        }

                        val lineProgress = if (isCurrent) {
                            lyricLineProgress(line.timeMs, nextTimes[index], durationMs, smoothPos.longValue)
                        } else if (index < current) 1f else 0f

                        val slotWidthDp = with(density) { slotWidths[index].toDp() }

                        Box(
                            contentAlignment = Alignment.TopCenter,
                            modifier = Modifier
                                .width(slotWidthDp)
                                .fillMaxHeight()
                                .padding(top = 24.dp, bottom = 20.dp)
                                .graphicsLayer {
                                    translationX = xCenter - slotWidths[index] / 2f
                                    translationY = yShift
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                                .zIndex(emphasis + 2f)
                                .then(
                                    if (alpha > 0.08f && line.timeMs >= 0L) {
                                        Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            onSeek(line.timeMs)
                                            settleTo(index.toFloat())
                                        }
                                    } else Modifier
                                )
                        ) {
                            VerticalLyricColumnGroup(
                                item = item,
                                fontSizeSp = fontSizeSp,
                                color = color,
                                accentColor = skin.accent,
                                emphasis = emphasis,
                                isCurrent = isCurrent,
                                lineProgress = lineProgress
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 竖排歌词单句列组（含顶部朱印风时间点、右侧主歌词子列、左侧译文子列、侧边流光进度线）。
 */
@Composable
private fun VerticalLyricColumnGroup(
    item: VerticalLineLayout,
    fontSizeSp: Int,
    color: Color,
    accentColor: Color,
    emphasis: Float,
    isCurrent: Boolean,
    lineProgress: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight()
    ) {
        // 顶部朱砂风小印章 / 时间戳（仅当前句或邻近句浮现）
        Box(
            modifier = Modifier
                .height(22.dp)
                .graphicsLayer { alpha = (emphasis * 1.15f).coerceIn(0f, 1f) },
            contentAlignment = Alignment.Center
        ) {
            if (item.line.timeMs >= 0L) {
                val sec = (item.line.timeMs / 1000L).coerceAtLeast(0L)
                val timeStr = "${(sec / 60).toString().padStart(2, '0')}:${(sec % 60).toString().padStart(2, '0')}"
                Text(
                    text = timeStr,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 主列与译文列：遵循古籍从右往左阅读习惯（右侧放原文第一列，往左依次展开子列和译文列）
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top
        ) {
            // 译文列排在左侧
            if (item.translationColumns.isNotEmpty()) {
                item.translationColumns.reversed().forEach { transTokens ->
                    VerticalTokenColumn(
                        tokens = transTokens,
                        fontSizeSp = (fontSizeSp * 0.72f).toInt().coerceAtLeast(11),
                        color = color.copy(alpha = 0.52f + 0.28f * emphasis),
                        fontWeight = if (emphasis > 0.5f) FontWeight.Medium else FontWeight.Normal,
                        emphasis = emphasis * 0.5f
                    )
                }
            }

            // 原文子列：第一列在最右，后续换行子列依次往左排
            val totalChars = remember(item.mainColumns) {
                item.mainColumns.sumOf { col -> col.sumOf { it.weightChars } }.coerceAtLeast(1)
            }
            var charsBefore = 0
            val columnsWithOffsets = item.mainColumns.map { col ->
                val startChar = charsBefore
                val span = col.sumOf { it.weightChars }
                charsBefore += span
                Triple(col, startChar, span)
            }

            columnsWithOffsets.reversed().forEach { (colTokens, startChar, spanChars) ->
                val subProgress = if (!isCurrent) {
                    if (lineProgress >= 1f) 1f else 0f
                } else {
                    val subStart = startChar.toFloat() / totalChars
                    val subEnd = (startChar + spanChars).toFloat() / totalChars
                    if (subEnd <= subStart) 1f
                    else ((lineProgress - subStart) / (subEnd - subStart)).coerceIn(0f, 1f)
                }
                VerticalTokenColumn(
                    tokens = colTokens,
                    fontSizeSp = fontSizeSp,
                    color = color,
                    fontWeight = if (emphasis > 0.5f) FontWeight.Bold else FontWeight.Medium,
                    emphasis = emphasis,
                    karaokeProgress = if (isCurrent) subProgress else null
                )
            }

            // 当前句右侧伴随一条竖向流光进度细线
            if (emphasis > 0.08f) {
                Canvas(
                    Modifier
                        .width(2.5.dp)
                        .fillMaxHeight(0.68f)
                        .padding(top = 4.dp)
                ) {
                    val trackH = size.height
                    drawRoundRect(
                        color = accentColor.copy(alpha = 0.16f * emphasis),
                        size = androidx.compose.ui.geometry.Size(size.width, trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width)
                    )
                    val activeH = (trackH * lineProgress.coerceIn(0f, 1f))
                    if (activeH > 1f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(accentColor, lerp(accentColor, Color.White, 0.35f))
                            ),
                            size = androidx.compose.ui.geometry.Size(size.width, activeH),
                            alpha = emphasis,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 渲染单条竖向 Token 序列（支持中文逐字正立、短数字横排块、长英文单词顺时针 90° 竖排）。
 */
@Composable
private fun VerticalTokenColumn(
    tokens: List<VerticalToken>,
    fontSizeSp: Int,
    color: Color,
    fontWeight: FontWeight,
    emphasis: Float,
    karaokeProgress: Float? = null
) {
    val totalUnits = remember(tokens) { tokens.sumOf { it.weightChars }.coerceAtLeast(1) }
    var consumedUnits = 0

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tokens.forEach { token ->
            val tokenStart = consumedUnits.toFloat() / totalUnits
            val tokenEnd = (consumedUnits + token.weightChars).toFloat() / totalUnits
            consumedUnits += token.weightChars

            val tokenColor = if (karaokeProgress != null) {
                val p = if (tokenEnd <= tokenStart) {
                    if (karaokeProgress >= tokenStart) 1f else 0f
                } else {
                    ((karaokeProgress - tokenStart) / (tokenEnd - tokenStart)).coerceIn(0f, 1f)
                }
                val eased = p * p * (3f - 2f * p)
                lerp(color.copy(alpha = 0.42f), color, eased)
            } else {
                color
            }

            val shadow = if (emphasis > 0.25f) {
                Shadow(color.copy(alpha = emphasis * 0.45f), Offset.Zero, 14f * emphasis)
            } else null

            when (token) {
                is VerticalToken.UprightChar -> {
                    Text(
                        text = token.ch.toString(),
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.15f).sp,
                        fontWeight = fontWeight,
                        color = tokenColor,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow),
                        textAlign = TextAlign.Center
                    )
                }

                is VerticalToken.CompactHorizontal -> {
                    // 2~3 位短数字/短缩写（如 "99"、"OK"）：正立紧凑横排（Tate-chu-yoko）
                    Text(
                        text = token.text,
                        fontSize = (fontSizeSp * 0.78f).sp,
                        lineHeight = fontSizeSp.sp,
                        fontWeight = fontWeight,
                        color = tokenColor,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                is VerticalToken.RotatedWord -> {
                    // 英文单词/长数字：顺时针旋转 90°，并在布局阶段交换宽高，让竖列自然顺延
                    Text(
                        text = token.word,
                        fontSize = (fontSizeSp * 0.90f).sp,
                        lineHeight = fontSizeSp.sp,
                        fontWeight = fontWeight,
                        color = tokenColor,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .rotate90Layout()
                    )
                }

                VerticalToken.SpacerGap -> {
                    Spacer(Modifier.height((fontSizeSp * 0.38f).dp))
                }
            }
        }
    }
}

/**
 * 兜底 2：纯文本无时间轴歌词（[ com.sonance.player.data.Lyrics.Plain ]）的竖排卷轴视图。
 *
 * 采用 RTL（从右往左）的 `LazyRow` 呈现整首诗笺，用户可以顺畅地横向滑动品读。
 */
@Composable
internal fun VerticalPlainLyrics(
    lines: List<String>,
    fontSizeSp: Int,
    bgProfile: BgProfile? = null,
    adaptMode: LyricAdaptMode = LyricAdaptMode.ALL
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val nonEmpty = remember(lines) {
        lines.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("•••") }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val maxCharsPerSubCol = remember(h, fontSizeSp, density) {
            val charHeightPx = with(density) { (fontSizeSp * 1.36f).dp.toPx() }.coerceAtLeast(14f)
            ((h * 0.75f) / charHeightPx).toInt().coerceIn(6, 22)
        }
        val columns = remember(nonEmpty, maxCharsPerSubCol) {
            nonEmpty.flatMap { line ->
                splitTokensIntoColumns(tokenizeVerticalLine(line), maxCharsPerSubCol)
            }
        }
        val listState = rememberLazyListState()
        val baseColor = if (bgProfile != null && shouldAdapt(false, adaptMode)) {
            lyricColor(skin.text, 0.5f, false, bgProfile)
        } else {
            skin.textDim
        }

        VerticalStageGridDecoration(skin = skin, anchorX = with(density) { maxWidth.toPx() } * 0.65f)

        // 从右向左排列每一列
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy((fontSizeSp * 0.95f).dp)
            ) {
                itemsIndexed(columns) { idx, colTokens ->
                    val isFirst = idx == 0
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        VerticalTokenColumn(
                            tokens = colTokens,
                            fontSizeSp = fontSizeSp,
                            color = if (isFirst) skin.accent else baseColor,
                            fontWeight = if (isFirst) FontWeight.SemiBold else FontWeight.Normal,
                            emphasis = if (isFirst) 0.5f else 0f
                        )
                    }
                }
            }
        }
    }
}

/**
 * 兜底 3：无歌词时（`lyrics == null`）的竖排诗笺空状态。
 *
 * 右侧竖排展示当前歌曲名与艺术家，中央提供「手动搜索」与「清除缓存」入口，
 * 保证切到竖屏歌词模式时即使遇到无歌词曲目也不会突兀退回横排提示框。
 */
@Composable
internal fun VerticalEmptyLyrics(
    song: Song,
    onlineEnabled: Boolean,
    fontSizeSp: Int,
    onManualSearch: () -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val titleTokens = remember(song.title) {
        splitTokensIntoColumns(tokenizeVerticalLine(song.title.ifBlank { "未知曲目" }), 12).firstOrNull()
            ?: listOf(VerticalToken.UprightChar('♪'))
    }
    val artistTokens = remember(song.artist) {
        splitTokensIntoColumns(tokenizeVerticalLine(song.artist.ifBlank { "未知艺术家" }), 14).firstOrNull()
            ?: emptyList()
    }
    val hintTokens = remember(onlineEnabled) {
        val text = if (onlineEnabled) "暂未寻得词卷 · 可手动检索" else "本地未嵌词卷 · 可开启在线歌词"
        tokenizeVerticalLine(text)
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        VerticalStageGridDecoration(skin = skin, anchorX = 0f)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.height(280.dp)
            ) {
                // 左列：提示语
                VerticalTokenColumn(
                    tokens = hintTokens,
                    fontSizeSp = (fontSizeSp * 0.76f).toInt().coerceIn(13, 18),
                    color = skin.textMuted,
                    fontWeight = FontWeight.Normal,
                    emphasis = 0f
                )
                // 中列：歌手名
                if (artistTokens.isNotEmpty()) {
                    VerticalTokenColumn(
                        tokens = artistTokens,
                        fontSizeSp = (fontSizeSp * 0.88f).toInt().coerceIn(15, 22),
                        color = skin.textDim,
                        fontWeight = FontWeight.Medium,
                        emphasis = 0.25f
                    )
                }
                // 右列（主列）：歌名
                VerticalTokenColumn(
                    tokens = titleTokens,
                    fontSizeSp = (fontSizeSp + 2).coerceIn(18, 28),
                    color = skin.accent,
                    fontWeight = FontWeight.Bold,
                    emphasis = 0.85f
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onlineEnabled) {
                    PillButton(label = "手动搜索歌词", onClick = onManualSearch)
                }
                PillButton(
                    label = "清除本首缓存",
                    onClick = {
                        val base = Prefs(context).lyricsApiBase
                        com.sonance.player.data.LyricsRepository.forget(context, song.title, song.artist, base)
                    },
                    filled = false
                )
            }
        }
    }
}

/**
 * 竖排舞台底纹：极淡的竖向竹简/诗笺分栏细线与焦点呼吸光晕。
 */
@Composable
private fun VerticalStageGridDecoration(skin: Skin, anchorX: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val step = 54.dp.toPx()
        val lineColor = skin.text.copy(alpha = 0.035f)
        var x = size.width - 28.dp.toPx()
        while (x > 20.dp.toPx()) {
            drawLine(
                color = lineColor,
                start = Offset(x, size.height * 0.08f),
                end = Offset(x, size.height * 0.90f),
                strokeWidth = 1f
            )
            x -= step
        }
        if (anchorX > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(skin.accent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(anchorX, size.height * 0.42f),
                    radius = size.minDimension * 0.45f
                ),
                radius = size.minDimension * 0.45f,
                center = Offset(anchorX, size.height * 0.42f)
            )
        }
    }
}

/* ============================================================
 * 竖排分词与中英/标点排版引擎
 * ============================================================ */

internal data class VerticalLineLayout(
    val line: LyricLine,
    val mainColumns: List<List<VerticalToken>>,
    val translationColumns: List<List<VerticalToken>>
)

internal sealed class VerticalToken(val weightChars: Int) {
    /** 单个正立字符（汉字、假名、韩文或已转换的竖排标点） */
    data class UprightChar(val ch: Char) : VerticalToken(1)

    /** 2~3 个字符的短数字或全大写缩写（纵中横 Tate-chu-yoko） */
    data class CompactHorizontal(val text: String) : VerticalToken(1)

    /** 顺时针旋转 90° 的英文单词或长数字串 */
    data class RotatedWord(val word: String) : VerticalToken(
        (word.length * 0.55f).toInt().coerceIn(2, 6)
    )

    /** 词间小留白 */
    data object SpacerGap : VerticalToken(1)
}

/**
 * 把一行歌词拆成适合竖排的 [VerticalToken] 序列：
 * - CJK 字符：逐字正立；
 * - 标点符号：映射为竖排标点（如 `︵` `︶` `︽` `︾` `︱`）或精简过滤；
 * - 英文 / 数字 / 拉丁字符：按连续词块聚合。2~3 位纯数字或全大写缩写走纵中横 [VerticalToken.CompactHorizontal]；
 *   其余英文单词走顺时针 90° 旋转 [VerticalToken.RotatedWord]，若单词超过 14 个字母则按音节优雅切分。
 */
internal fun tokenizeVerticalLine(raw: String): List<VerticalToken> {
    val text = raw.trim()
    if (text.isEmpty()) return listOf(VerticalToken.UprightChar('•'))

    val out = ArrayList<VerticalToken>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> {
                // 连续空白合并为一个轻量气口（仅在两侧不是空白时保留）
                if (out.isNotEmpty() && out.last() != VerticalToken.SpacerGap) {
                    out.add(VerticalToken.SpacerGap)
                }
                while (i < text.length && text[i].isWhitespace()) i++
            }

            isLatinWordChar(c) -> {
                val start = i
                while (i < text.length && isLatinWordChar(text[i])) i++
                val word = text.substring(start, i)
                // 若后面紧跟空白，词本身已有上下 padding，不必再叠加额外 SpacerGap
                while (i < text.length && text[i].isWhitespace()) i++
                appendLatinWordTokens(out, word)
            }

            else -> {
                val mapped = mapToVerticalPunctuation(c)
                if (mapped != null) {
                    out.add(VerticalToken.UprightChar(mapped))
                }
                i++
            }
        }
    }
    while (out.lastOrNull() == VerticalToken.SpacerGap) {
        out.removeAt(out.lastIndex)
    }
    return if (out.isEmpty()) listOf(VerticalToken.UprightChar('•')) else out
}

private fun isLatinWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
        c in '\u00C0'..'\u024F' || c == '\'' || c == '-' || c == '&' || c == '+' || c == '/'

private fun appendLatinWordTokens(out: MutableList<VerticalToken>, word: String) {
    if (word.length == 1) {
        out.add(VerticalToken.UprightChar(word[0]))
        return
    }
    val allDigits = word.all { it.isDigit() }
    val allUpperShort = word.length <= 2 && word.all { it in 'A'..'Z' || it.isDigit() }
    if ((allDigits && word.length <= 3) || allUpperShort) {
        out.add(VerticalToken.CompactHorizontal(word))
        return
    }
    // 超长英文单词分段，防止单个旋转词高度超过一列
    val chunkSize = 14
    var pos = 0
    while (pos < word.length) {
        val end = minOf(word.length, pos + chunkSize)
        val part = word.substring(pos, end)
        out.add(VerticalToken.RotatedWord(part))
        pos = end
    }
}

/**
 * 横排标点转竖排标点（Unicode CJK Compatibility Forms）。
 * 句读逗号/句号在竖排诗行中可用气口留白或旁点代替，保持版面干净。
 */
private fun mapToVerticalPunctuation(c: Char): Char? = when (c) {
    '(' , '（' -> '︵'
    ')' , '）' -> '︶'
    '[' , '【', '〔' -> '︻'
    ']' , '】', '〕' -> '︼'
    '{' , '｛' -> '︷'
    '}' , '｝' -> '︸'
    '<' , '《' -> '︽'
    '>' , '》' -> '︾'
    '〈' -> '︿'
    '〉' -> '﹀'
    '「' -> '﹁'
    '」' -> '﹂'
    '『' -> '﹃'
    '』' -> '﹄'
    '—', '－', '–', '―' -> '︱'
    '~', '～' -> '≀'
    '…' -> '⋮'
    '、', '，', ',', '。', '.' -> '·'
    else -> c
}

/**
 * 将一行的 Token 序列按每列最大字符权重拆分为 1~N 列。
 * 保证纯英文长句或多句合并歌词自动折成多列并排，不会溢出屏幕底部。
 */
internal fun splitTokensIntoColumns(
    tokens: List<VerticalToken>,
    maxWeightPerColumn: Int
): List<List<VerticalToken>> {
    val limit = maxWeightPerColumn.coerceAtLeast(5)
    val result = ArrayList<List<VerticalToken>>()
    var current = ArrayList<VerticalToken>()
    var currentWeight = 0

    for (token in tokens) {
        if (token == VerticalToken.SpacerGap && current.isEmpty()) continue
        if (current.isNotEmpty() && currentWeight + token.weightChars > limit) {
            while (current.lastOrNull() == VerticalToken.SpacerGap) {
                current.removeAt(current.lastIndex)
            }
            if (current.isNotEmpty()) result.add(current)
            current = ArrayList()
            currentWeight = 0
            if (token == VerticalToken.SpacerGap) continue
        }
        current.add(token)
        currentWeight += token.weightChars
    }
    while (current.lastOrNull() == VerticalToken.SpacerGap) {
        current.removeAt(current.lastIndex)
    }
    if (current.isNotEmpty()) result.add(current)
    return if (result.isEmpty()) listOf(listOf(VerticalToken.UprightChar('•'))) else result
}

/**
 * 将水平测量的英文单词顺时针旋转 90°，并在布局阶段把测量宽高对调（`width = placeable.height`, `height = placeable.width`），
 * 使旋转后的英文单词在 `Column` 中拥有精确的纵向占位高度，绝不与上下汉字重叠。
 */
private fun Modifier.rotate90Layout(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = 0,
            maxWidth = androidx.compose.ui.unit.Constraints.Infinity,
            minHeight = 0,
            maxHeight = androidx.compose.ui.unit.Constraints.Infinity
        )
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = (placeable.height - placeable.width) / 2,
            y = (placeable.width - placeable.height) / 2,
            layerBlock = {
                rotationZ = 90f
            }
        )
    }
}
