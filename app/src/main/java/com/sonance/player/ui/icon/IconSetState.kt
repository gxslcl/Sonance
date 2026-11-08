package com.sonance.player.ui.icon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 全局图标套系（0 原型原版 / 1 极简线条 / 2 实心圆润）。
 *
 * 用 snapshot state 承载：图标 getter 在组合期读取它，设置页改值后
 * 所有用到该图标的地方立即重组，无需重启。
 */
object IconSetState {

    const val PROTOTYPE = 0
    const val MINIMAL = 1
    const val SOLID = 2

    /** 当前套系；由 [init] 在进程启动时从 Prefs 恢复，设置页切换时实时写入 */
    var style by mutableIntStateOf(PROTOTYPE)
        private set

    fun init(value: Int) {
        style = value.coerceIn(PROTOTYPE, SOLID)
    }

    fun set(value: Int) {
        style = value.coerceIn(PROTOTYPE, SOLID)
    }
}
