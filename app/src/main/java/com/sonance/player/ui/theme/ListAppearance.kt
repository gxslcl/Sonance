package com.sonance.player.ui.theme

/**
 * 悬浮卡片上播放进度怎么显示。
 *
 * 之前是"环 + 底部细线"同时存在 —— 同一个进度说两遍，看着重复。
 * 现在交给用户挑：环贴在播放键上、线贴在卡片下沿，各有各的调性。
 *
 * 只影响 [MiniPlayerStyle.FLOAT]；经典贴底那条通栏本来就只有线。
 */
enum class MiniProgressStyle(val label: String) {
    RING("圆环（套在播放键上）"),
    BAR("长条（卡片下沿）"),
    BOTH("两者都显示"),
    ;

    val showsRing: Boolean get() = this != BAR
    val showsBar: Boolean get() = this != RING

    companion object {
        fun of(ordinal: Int): MiniProgressStyle =
            entries.getOrElse(ordinal) { RING }
    }
}

/**
 * 列表行的分隔方式。
 *
 * 默认 [NONE] —— 保持原来那种靠留白和封面区分的清爽感，
 * 但行多的时候确实容易看串行，所以给两个"更清楚"的选项。
 */
enum class ListRowStyle(val label: String) {
    /** 无分界线（原来的样子） */
    NONE("无分界线"),
    /** 行与行之间一条极细的线，左右缩进对齐文字 */
    DIVIDER("细分隔线"),
    /** 无分界线，但行距更宽，靠留白分开 */
    SPACED("加宽行距"),
    ;

    companion object {
        fun of(ordinal: Int): ListRowStyle =
            entries.getOrElse(ordinal) { NONE }
    }
}

/**
 * 列表里封面的缩放倍数。
 *
 * 只放大封面本身，**不动文字区**：行还是垂直居中、标题/副标题的原点和字号都不变，
 * 所以放大之后看着只是"图更大了"，而不是整行被重新排过。
 * 封面按倍数一起提高解码尺寸（[sizePx]），否则 2 倍下会拿到一张被拉糊的小图。
 */
enum class ListCoverScale(val factor: Float, val label: String) {
    X1(1.0f, "1.0×"),
    X12(1.2f, "1.2×"),
    X15(1.5f, "1.5×"),
    X2(2.0f, "2×"),
    ;

    companion object {
        fun of(ordinal: Int): ListCoverScale =
            entries.getOrElse(ordinal) { X1 }
    }
}
