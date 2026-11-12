package com.sonance.player.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 直接调系统振动器给马达一次 14ms 短脉冲。
 *
 * Compose 的 `HapticFeedbackType.TextHandleMove` 只是「文本手柄移动」这类软反馈，
 * 不少 ROM 根本不驱动马达、还会被系统触摸反馈总开关吞掉，用户完全感觉不到。
 * 这里走 [Vibrator] + [VibrationEffect]，线性马达会给出明确的「咔」一下。
 * Android 12 起要经 [VibratorManager] 拿默认振动器；老版本走 VIBRATOR_SERVICE。
 *
 * 用于「歌词滚动每跨一行」和「点按切换倍速」这类需要**明确节拍感**的交互。
 */
internal fun hapticTick(context: Context) {
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 幅值 160（0~255）：不支持幅值控制的马达会忽略这一项，仍然会震
            vibrator.vibrate(VibrationEffect.createOneShot(14L, 160))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(14L)
        }
    }
}
