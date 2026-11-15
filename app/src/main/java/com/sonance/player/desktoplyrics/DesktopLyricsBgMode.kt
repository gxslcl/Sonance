package com.sonance.player.desktoplyrics

/**
 * 桌面歌词背景样式枚举：
 * - NONE: 没有背景（纯悬浮文字 + 高对比度立体阴影，不遮挡壁纸与软件内容）
 * - LIGHT: 浅色半透明（50% 磨砂白高亮胶囊）
 * - DARK: 深色半透明（45% 磨砂黑轻透胶囊）
 */
enum class DesktopLyricsBgMode(val id: String, val label: String) {
    NONE("none", "没有背景"),
    LIGHT("light", "浅色半透明"),
    DARK("dark", "深色半透明");

    fun next(): DesktopLyricsBgMode = when (this) {
        NONE -> LIGHT
        LIGHT -> DARK
        DARK -> NONE
    }

    companion object {
        fun fromId(id: String?): DesktopLyricsBgMode =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
