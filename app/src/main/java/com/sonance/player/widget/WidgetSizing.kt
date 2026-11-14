package com.sonance.player.widget

import kotlin.math.roundToInt

/**
 * 小组件封面与背景尺寸的纯 Kotlin 计算器（方便单测直测手机/平板与缩放规格）。
 */
internal enum class WidgetCoverClass {
    NONE,
    BAR,
    CARD,
    WIDE,
    LARGE
}

internal object WidgetSizing {

    /** 单张封面解码像素上限，兼顾高分屏锐度与 RemoteViews Binder 1MB IPC 预算。 */
    const val MAX_COVER_PX = 384

    /** 背景磨砂位图像素上限，保证渐变平滑同时避免占用过多 Binder 字节。 */
    const val MAX_BG_W_PX = 240
    const val MAX_BG_H_PX = 240

    fun pickSizeFromPairs(
        isLandscape: Boolean,
        candidates: List<Pair<Float, Float>>
    ): Pair<Int, Int>? {
        val valid = candidates.filter { (w, h) -> w > 20f && h > 20f }
        if (valid.isEmpty()) return null
        val picked = if (valid.size == 1) {
            valid[0]
        } else if (isLandscape) {
            valid.sortedWith(compareByDescending<Pair<Float, Float>> { it.first }.thenBy { it.second }).first()
        } else {
            valid.sortedWith(compareBy<Pair<Float, Float>> { it.first }.thenByDescending { it.second }).first()
        }
        return picked.first.roundToInt().coerceIn(110, 720) to
            picked.second.roundToInt().coerceIn(56, 720)
    }

    fun pickOrientationSize(
        isLandscape: Boolean,
        minW: Int,
        maxW: Int,
        minH: Int,
        maxH: Int,
        defaultW: Int,
        defaultH: Int
    ): Pair<Int, Int> {
        val rawW = if (isLandscape) maxOf(minW, maxW) else minW.takeIf { it > 20 } ?: maxW
        val rawH = if (isLandscape) {
            minH.takeIf { it > 20 } ?: maxH
        } else {
            maxOf(minH, maxH)
        }
        val w = rawW.takeIf { it > 20 } ?: defaultW
        val h = rawH.takeIf { it > 20 } ?: defaultH
        return w.coerceIn(110, 720) to h.coerceIn(56, 720)
    }

    /**
     * 按当前桌面宿主授予的宽高 (dp) 动态计算封面方形边长 (dp)。
     *
     * 小组件变大时（无论手机 4×4/4×5 还是平板大网格，或用户手动拖拽放大），
     * 封面按可用净空等比放大；缩小时不低于各样式基础尺寸也不挤掉标题、进度条与播控键。
     */
    fun computeCoverDp(
        coverClass: WidgetCoverClass,
        baseCoverDp: Float,
        isFourCol: Boolean,
        widthDp: Int,
        heightDp: Int
    ): Float {
        val w = widthDp.toFloat()
        val h = heightDp.toFloat()
        return when (coverClass) {
            WidgetCoverClass.NONE -> 0f
            WidgetCoverClass.BAR -> {
                val maxByH = h - 18f
                val maxByW = w * 0.24f
                minOf(maxByH, maxByW).coerceIn(baseCoverDp, 88f)
            }
            WidgetCoverClass.CARD -> {
                val pillReserve = if (isFourCol) 58f else 90f
                val maxByW = w - pillReserve
                val maxByH = h - 98f
                minOf(maxByW, maxByH).coerceIn(baseCoverDp, 196f)
            }
            WidgetCoverClass.WIDE -> {
                val maxByH = h - 24f
                val maxByRatio = (w - 26f) * 0.44f
                val maxByControls = w - (if (isFourCol) 166f else 178f)
                minOf(maxByH, maxByRatio, maxByControls).coerceIn(baseCoverDp, 224f)
            }
            WidgetCoverClass.LARGE -> {
                val chromeH = if (isFourCol) 168f else 176f
                val maxByW = w * 0.66f
                val maxByH = h - chromeH
                minOf(maxByW, maxByH).coerceIn(baseCoverDp, 280f)
            }
        }
    }

    fun computeMiniCoverDp(coverClass: WidgetCoverClass, baseMiniDp: Float, coverDp: Float): Float {
        if (baseMiniDp <= 0f) return 0f
        return when (coverClass) {
            WidgetCoverClass.LARGE -> (coverDp * 0.25f).coerceIn(baseMiniDp, 58f)
            WidgetCoverClass.CARD -> (coverDp * 0.36f).coerceIn(baseMiniDp, 44f)
            else -> baseMiniDp
        }
    }

    fun computeCoverPx(coverDp: Float, basePx: Int, density: Float): Int {
        if (coverDp <= 0f || basePx <= 0) return 0
        val scaled = (coverDp * density.coerceIn(1.5f, 3.0f)).roundToInt()
        return scaled.coerceIn(basePx, MAX_COVER_PX)
    }
}
