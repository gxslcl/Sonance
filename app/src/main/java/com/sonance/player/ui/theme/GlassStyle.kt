package com.sonance.player.ui.theme

/**
 * 歌词页（以及其它覆盖层）的背景玻璃样式。
 *
 * 前四档是常规毛玻璃，差别只在「糊多少、透多少」；
 * [LIQUID] 是仿 Apple「液态玻璃」的观感，除了更强的模糊，还额外叠加了三样东西：
 *   1. **饱和度提升** —— 背景颜色透出来但不发灰（Apple 的玻璃是"有色"的）
 *   2. **顶部镜面高光** —— 一道从上往下衰减的白光
 *   3. **边缘折射描边** —— 一圈带渐变的内描边，模拟玻璃边界的折射
 */
enum class GlassStyle(
    val label: String,
    /** 默认模糊半径（dp） */
    val defaultBlurDp: Int,
    /** 默认"背景可见度" 0~100，越大越透、遮罩越薄 */
    val defaultVisibility: Int,
    /** 是否启用液态玻璃的额外效果（饱和度 / 高光 / 折射描边） */
    val liquid: Boolean = false
) {
    OFF("关闭", 0, 0, false),
    SUBTLE("淡", 12, 30, false),
    NORMAL("标准", 30, 55, false),
    STRONG("强", 55, 78, false),
    LIQUID("液态玻璃", 48, 92, true),
    ;

    companion object {
        fun of(ordinal: Int): GlassStyle = entries.getOrElse(ordinal) { NORMAL }

        /** 可见度 → 封面不透明度（0~1） */
        fun artAlpha(visibility: Int): Float = (visibility.coerceIn(0, 100) / 100f) * 0.9f + 0.1f

        /** 可见度 → 遮罩不透明度（0~1），越透遮得越薄，但保留下限保证文字可读 */
        fun scrimAlpha(visibility: Int): Float {
            val v = visibility.coerceIn(0, 100) / 100f
            return (0.92f - v * 0.46f).coerceIn(0.42f, 0.94f)
        }

        /** 液态玻璃的饱和度倍率 */
        fun saturation(liquid: Boolean, visibility: Int): Float =
            if (liquid) 1.15f + (visibility.coerceIn(0, 100) / 100f) * 0.75f else 1f
    }
}
