package com.sonance.player.ui.theme

/**
 * 列表页底部那个「正在播放」条的两种形态。
 *
 * - [CLASSIC]：贴底通栏，方角，顶上一条进度线 —— 原来那副样子。
 * - [FLOAT]：离屏边有间距的**大圆角悬浮卡片**，带投影，可以叠玻璃。
 *
 * 两种都保留：有人就喜欢通栏的稳重，而悬浮卡片在列表里更像"浮"着的一层。
 * 默认用悬浮（[of] 的兜底也是它），因为它才是这次要的样子。
 */
enum class MiniPlayerStyle(val label: String) {
    CLASSIC("经典贴底"),
    FLOAT("悬浮卡片"),
    ;

    companion object {
        fun of(ordinal: Int): MiniPlayerStyle =
            entries.getOrElse(ordinal) { FLOAT }
    }
}
