package com.sonance.player.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.sonance.player.data.MotionFeel
import com.sonance.player.data.PageTransitionStyle
import com.sonance.player.data.PlayerSheetStyle
import com.sonance.player.data.Prefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 全局流体动效配置快照：经 [LocalMotionConfig] 注入树内，供按钮、转场、播放页共同消费。
 */
@Immutable
data class MotionConfig(
    val feel: MotionFeel = MotionFeel.FLUID,
    val sheetStyle: PlayerSheetStyle = PlayerSheetStyle.DEPTH_SHEET,
    val pageStyle: PageTransitionStyle = PageTransitionStyle.FLUID_DEPTH,
    val touchSpringFeedback: Boolean = true,
    val playerCoverCornerDp: Int = 20,
    val miniSwipeUpToOpen: Boolean = true
)

val LocalMotionConfig = staticCompositionLocalOf { MotionConfig() }
val LocalPrefs = staticCompositionLocalOf<Prefs?> { null }

private const val SHEET_VELOCITY_THRESHOLD_PX_S = 650f

/**
 * 按全局动效手感缩放基础毫秒数，并保底 80ms 避免动画坍缩成跳帧硬切。
 */
fun scaleMotionDuration(baseMs: Int, feel: MotionFeel): Int =
    (baseMs * feel.durationScale).roundToInt().coerceAtLeast(80)

/**
 * 全屏播放页跟手拖拽抬手后的落点决策（速度优先，其次按位移比例）。
 *
 * @param progress 0f=完全收起，1f=完全展开
 * @param velocityPxPerSec 纵向速度（负值=手指上滑/展开，正值=手指下滑/收起）
 * @param currentlyOpen 起手前播放页是否已展开
 */
fun resolveSheetTargetOpen(
    progress: Float,
    velocityPxPerSec: Float,
    currentlyOpen: Boolean
): Boolean {
    if (velocityPxPerSec < -SHEET_VELOCITY_THRESHOLD_PX_S) return true
    if (velocityPxPerSec > SHEET_VELOCITY_THRESHOLD_PX_S) return false
    return if (currentlyOpen) progress > 0.78f else progress > 0.22f
}

fun sheetSpringSpec(feel: MotionFeel): SpringSpec<Float> =
    spring(dampingRatio = feel.springDamping, stiffness = feel.springStiffness)

fun pageTransitionSpec(
    style: PageTransitionStyle,
    feel: MotionFeel,
    forward: Boolean
): ContentTransform = when (style) {
    PageTransitionStyle.FLUID_DEPTH -> {
        val inAlphaMs = scaleMotionDuration(220, feel)
        val inScaleMs = scaleMotionDuration(260, feel)
        val outAlphaMs = scaleMotionDuration(160, feel)
        val outScaleMs = scaleMotionDuration(220, feel)
        (fadeIn(tween(inAlphaMs)) + scaleIn(
            tween(inScaleMs, easing = FastOutSlowInEasing),
            initialScale = if (forward) 0.96f else 1.03f
        )) togetherWith (fadeOut(tween(outAlphaMs)) + scaleOut(
            tween(outScaleMs, easing = FastOutSlowInEasing),
            targetScale = if (forward) 1.02f else 0.96f
        ))
    }
    PageTransitionStyle.SLIDE -> {
        val ms = scaleMotionDuration(250, feel)
        val sign = if (forward) 1 else -1
        (slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { w -> sign * (w * 0.22f).roundToInt() } +
            fadeIn(tween(scaleMotionDuration(200, feel)))) togetherWith
            (slideOutHorizontally(tween(ms, easing = FastOutSlowInEasing)) { w -> -sign * (w * 0.16f).roundToInt() } +
                fadeOut(tween(scaleMotionDuration(160, feel))))
    }
    PageTransitionStyle.FADE -> {
        fadeIn(tween(scaleMotionDuration(200, feel))) togetherWith
            fadeOut(tween(scaleMotionDuration(150, feel)))
    }
    PageTransitionStyle.NONE -> EnterTransition.None togetherWith ExitTransition.None
}

/**
 * 纯 GPU 零重组按压微弹修饰符：仅在 [PointerEventPass.Initial] 只读观察按下/抬起，
 * 绝不消费触摸事件，与外层 `clickable` / 滚动手势 100% 兼容。
 */
fun Modifier.pressSpring(
    enabled: Boolean = true,
    pressedScale: Float = 0.955f
): Modifier {
    if (!enabled) return this
    return composed {
        val scope = rememberCoroutineScope()
        val scale = remember { Animatable(1f) }
        this
            .pointerInput(pressedScale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    scope.launch {
                        scale.animateTo(pressedScale, tween(75, easing = FastOutSlowInEasing))
                    }
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    scope.launch {
                        scale.animateTo(
                            1f,
                            spring(dampingRatio = 0.72f, stiffness = 540f)
                        )
                    }
                }
            }
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
    }
}
