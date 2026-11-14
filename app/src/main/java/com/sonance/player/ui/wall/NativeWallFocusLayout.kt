package com.sonance.player.ui.wall

import kotlin.math.max
import kotlin.math.min

/**
 * 封面墙「浮起聚焦 / 播放态」的跨形态几何布局结果（纯 Kotlin，可直接在 JVM 单元测试中校验）。
 */
internal data class WallFocusLayoutSpec(
    val isWallWide: Boolean,
    val isCompactLandscape: Boolean,
    val topBarPaddingPx: Float,
    val heroX: Float,
    val heroY: Float,
    val heroSize: Float,
    val rightPaneX: Float,
    val rightPaneTop: Float,
    val rightPaneW: Float,
    val rightPaneH: Float,
    val lyricsTop: Float,
    val lyricsHeight: Float,
    val controlsTop: Float,
    val controlsHeight: Float,
    val backSize: Float,
    val backX: Float,
    val backY: Float
) {
    /** 唱片封套在开启黑胶模式时的实际左边界（WallCardPainter 左移 -0.16 * size）。 */
    fun sleeveLeft(vinylEnabled: Boolean): Float =
        heroX - if (vinylEnabled) heroSize * 0.16f else 0f

    /** 黑胶露边在开启黑胶模式时的实际右边界（centerX + 0.26s + 0.47s = x + 1.23s）。 */
    fun visualRight(vinylEnabled: Boolean): Float =
        heroX + heroSize * (if (vinylEnabled) 1.23f else 1f)
}

internal fun computeWallFocusLayout(
    width: Float,
    height: Float,
    density: Float,
    barsTopPortrait: Float,
    barsTopLandscape: Float,
    bottom: Float,
    safeLeft: Float = 0f,
    safeRight: Float = 0f,
    vinylEnabled: Boolean = true,
    isTablet: Boolean = false
): WallFocusLayoutSpec {
    val d = density.coerceAtLeast(1f)
    val safeW = width.coerceAtLeast(1f)
    val safeH = height.coerceAtLeast(1f)
    val isWallWide = safeW >= 560f * d && safeW > safeH

    if (!isWallWide) {
        val available = (safeH - bottom).coerceAtLeast(1f)
        val topBarPaddingPx = barsTopPortrait + 10f * d
        val topBarSafe = max(54f * d, barsTopPortrait + 56f * d)
        val heroSize = min(safeW * 0.62f, available * 0.34f).coerceAtLeast(1f)
        val heroX = (safeW - heroSize) / 2f
        val heroY = topBarSafe
        val coverBottom = heroY + heroSize
        val lyricsTop = coverBottom + 10f * d
        val controlsHeight = (306f * d).coerceAtMost(available * 0.5f).coerceAtLeast(1f)
        val controlsTop = safeH - bottom - controlsHeight
        val lyricsHeight = (controlsTop - lyricsTop - 6f * d).coerceAtLeast(1f)
        val backSize = min(safeW - 36f * d, available - 100f * d).coerceAtLeast(1f)
        val backX = (safeW - backSize) / 2f
        val backY = (available - backSize) / 2f
        return WallFocusLayoutSpec(
            isWallWide = false,
            isCompactLandscape = false,
            topBarPaddingPx = topBarPaddingPx,
            heroX = heroX,
            heroY = heroY,
            heroSize = heroSize,
            rightPaneX = 0f,
            rightPaneTop = lyricsTop,
            rightPaneW = safeW,
            rightPaneH = (safeH - bottom - lyricsTop).coerceAtLeast(1f),
            lyricsTop = lyricsTop,
            lyricsHeight = lyricsHeight,
            controlsTop = controlsTop,
            controlsHeight = controlsHeight,
            backSize = backSize,
            backX = backX,
            backY = backY
        )
    }

    // 宽屏分为两档：手机横屏等矮屏（isCompactLandscape）与平板宽屏（isWallWide && !isCompactLandscape）
    val isCompactLandscape = (safeH < 520f * d && !isTablet) || safeH < 460f * d
    val activeBarsTop = if (isCompactLandscape) barsTopLandscape else max(barsTopPortrait, barsTopLandscape)
    val topBarPaddingPx = activeBarsTop + 10f * d

    val vinylLeftFactor = if (vinylEnabled) 0.16f else 0f
    val vinylRightFactor = if (vinylEnabled) 0.23f else 0f
    val vinylSpanFactor = 1f + vinylLeftFactor + vinylRightFactor

    val heroSize: Float
    val heroX: Float
    val heroY: Float
    val rightPaneX: Float
    val rightPaneW: Float
    val rightPaneTop: Float
    val rightPaneH: Float

    if (isCompactLandscape) {
        val leftMargin = max(24f * d, safeLeft + 16f * d)
        val rightMargin = max(22f * d, safeRight + 14f * d)
        val paneGap = 22f * d
        val contentW = (safeW - leftMargin - rightMargin - paneGap).coerceAtLeast(1f)
        val leftAreaW = contentW * 0.43f

        // 左侧封面避开左上角按钮且垂直居中；右侧整列顶部紧接安全区，消除大片无效留白。
        val coverTopSafe = activeBarsTop + 20f * d
        val coverBottomSafe = max(bottom, 14f * d)
        val usableCoverH = (safeH - coverTopSafe - coverBottomSafe).coerceAtLeast(1f)
        heroSize = minOf(leftAreaW / vinylSpanFactor, usableCoverH * 0.84f, 272f * d).coerceAtLeast(1f)

        val visualW = heroSize * vinylSpanFactor
        val visualLeft = leftMargin + ((leftAreaW - visualW) / 2f).coerceAtLeast(0f)
        heroX = visualLeft + heroSize * vinylLeftFactor
        heroY = coverTopSafe + ((usableCoverH - heroSize) / 2f).coerceAtLeast(0f)

        rightPaneX = leftMargin + leftAreaW + paneGap
        rightPaneW = (safeW - rightMargin - rightPaneX).coerceAtLeast(1f)
        rightPaneTop = activeBarsTop + 12f * d
        val rightBottom = safeH - max(bottom, 10f * d)
        rightPaneH = (rightBottom - rightPaneTop).coerceAtLeast(1f)
    } else {
        // 平板宽屏：外侧保持足够呼吸边距，封面+黑胶整套在左侧栏居中，右侧保持同等外边距对称对齐。
        val outerMarginBase = (safeW * 0.045f).coerceIn(36f * d, 56f * d)
        val leftMargin = max(outerMarginBase, safeLeft + 28f * d)
        val rightMargin = max(outerMarginBase, safeRight + 28f * d)
        val paneGap = 28f * d
        val contentW = (safeW - leftMargin - rightMargin - paneGap).coerceAtLeast(1f)
        val leftAreaW = contentW * 0.45f

        val topSafe = max(activeBarsTop + 56f * d, 58f * d)
        val bottomSafe = max(bottom, 18f * d)
        val usableH = (safeH - topSafe - bottomSafe).coerceAtLeast(1f)
        heroSize = minOf(leftAreaW / vinylSpanFactor, usableH * 0.72f, 380f * d).coerceAtLeast(1f)

        val visualW = heroSize * vinylSpanFactor
        val visualLeft = leftMargin + ((leftAreaW - visualW) / 2f).coerceAtLeast(0f)
        heroX = visualLeft + heroSize * vinylLeftFactor
        heroY = topSafe + ((usableH - heroSize) / 2f).coerceAtLeast(0f)

        rightPaneX = leftMargin + leftAreaW + paneGap
        rightPaneW = (safeW - rightMargin - rightPaneX).coerceAtLeast(1f)
        rightPaneTop = topSafe
        rightPaneH = usableH
    }

    val backTopSafe = activeBarsTop + 52f * d
    val backUsableH = (safeH - backTopSafe - max(bottom, 14f * d)).coerceAtLeast(1f)
    val backSize = min(safeW * 0.54f, backUsableH - 12f * d).coerceAtLeast(1f)
    val backX = (safeW - backSize) / 2f
    val backY = backTopSafe + ((backUsableH - backSize) / 2f).coerceAtLeast(0f)

    return WallFocusLayoutSpec(
        isWallWide = true,
        isCompactLandscape = isCompactLandscape,
        topBarPaddingPx = topBarPaddingPx,
        heroX = heroX,
        heroY = heroY,
        heroSize = heroSize,
        rightPaneX = rightPaneX,
        rightPaneTop = rightPaneTop,
        rightPaneW = rightPaneW,
        rightPaneH = rightPaneH,
        lyricsTop = rightPaneTop,
        lyricsHeight = (rightPaneH * 0.48f).coerceAtLeast(1f),
        controlsTop = rightPaneTop + rightPaneH * 0.50f,
        controlsHeight = (rightPaneH * 0.50f).coerceAtLeast(1f),
        backSize = backSize,
        backX = backX,
        backY = backY
    )
}
