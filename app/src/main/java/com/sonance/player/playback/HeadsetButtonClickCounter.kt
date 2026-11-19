package com.sonance.player.playback

/**
 * 耳机线控单键连击状态机（1 击播放/暂停，2 击下一曲，3 击上一曲）。
 * 抽成纯 Kotlin 类以便 JVM 单测和在 MediaSession.Callback 中复用。
 */
internal class HeadsetButtonClickCounter(
    private val clickWindowMs: Long = 380L
) {
    enum class Action {
        PLAY_PAUSE,
        NEXT,
        PREVIOUS
    }

    private var clickCount = 0
    private var lastClickAtMs = 0L

    /**
     * 记录一次有效按下事件。
     * @return 若第 3 击立即命中，直接返回 [Action.PREVIOUS] 并清空计数；否则返回 null，等待超时结算。
     */
    fun onClick(nowMs: Long): Action? {
        if (clickCount == 0 || nowMs - lastClickAtMs > clickWindowMs) {
            clickCount = 1
        } else {
            clickCount += 1
        }
        lastClickAtMs = nowMs

        if (clickCount >= 3) {
            clickCount = 0
            return Action.PREVIOUS
        }
        return null
    }

    /**
     * 超时窗口结束后结算 1 击 / 2 击动作。
     */
    fun consumePending(): Action? {
        val count = clickCount
        clickCount = 0
        return when (count) {
            1 -> Action.PLAY_PAUSE
            2 -> Action.NEXT
            else -> null
        }
    }

    fun reset() {
        clickCount = 0
        lastClickAtMs = 0L
    }
}
