package com.sonance.player.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin

/** 封面切换的过渡形态 */
enum class ArtTransition {
    /** 左右滑动：新封面从切换方向滑入**盖住**旧封面（播放页轮播同款手感） */
    SLIDE,

    /** 直接渐变：旧封面淡出、新封面淡入，时长可在设置里调 */
    FADE
}

/**
 * 「毛玻璃 / 液态玻璃」背景：把封面糊开铺在底下，再压一层遮罩保证文字可读。
 *
 * 歌词页、律动柱页、悬浮迷你播放条共用同一份实现 —— 三处的差别只有
 * **遮罩基色**（铺满整页时用皮肤底色；小卡片上要用它自己的表面色，
 * 否则卡片会比旁边的界面亮一档）、**要不要那圈折射亮线**（小控件上太抢戏）
 * 和**遮罩压多狠**（小卡片压太狠就把封面糊没了）。
 *
 * 调用方负责**加载封面**再传进来：同一张图往往还要拿去算别的（歌词自动适配背景
 * 要读它的分段平均色），各加载一份既浪费又会算出色不对版的颜色。
 *
 * 需要裁剪成圆角时把 `Modifier.clip(...)` 传进 [modifier] 即可 ——
 * 里面三层都用 `matchParentSize`，跟着调用方给的形状走。
 *
 * API 31+ 才有 RenderEffect 真模糊；低于 31 只能靠降不透明度 + 加厚遮罩近似。
 */
@Composable
fun GlassBackdrop(
    /** 已经加载好的封面；null 时只剩遮罩（仍然比一层纯色有层次） */
    art: ImageBitmap?,
    style: GlassStyle,
    blurDp: Int,
    visibility: Int,
    modifier: Modifier = Modifier,
    /** 遮罩的基础色，默认皮肤底色 */
    scrimColor: Color = LocalSkin.current.bg,
    /**
     * 液态玻璃的「上边缘折射亮线」。
     * 整页时它像玻璃边缘的反光；迷你播放条那种 60dp 高的小卡片上会变成
     * 一条突兀的白线，所以可以按需要关掉（顶部镜面高光保留）。
     */
    edgeHighlight: Boolean = true,
    /**
     * 遮罩从上到下的加厚幅度。整页内容多、要压得狠；小卡片上传小一点
     * （比如 0.10f）保留玻璃的通透感。
     */
    scrimSpread: Float = 0.22f,
    /**
     * 液态玻璃的「顶部镜面高光」（顶部 40% 从 0.20 白衰减到 0）。
     * 播放页它是质感，主界面这种**整页铺底 + 顶部有标题/搜索框**的场景里，
     * 浅色封面会被这层白光顶得字都看不清 —— 可以关掉。
     */
    topHighlight: Boolean = true,
    /**
     * 封面切换的过渡形态（见 [ArtTransition]）：滑动 / 渐变，设置页可选
     */
    transition: ArtTransition = ArtTransition.SLIDE,
    /**
     * 过渡时长（毫秒）。滑动模式建议 300~500；渐变模式由设置页的
     * 「过渡时长」滑杆传入，实时生效
     */
    transitionMs: Int = 380,
    /**
     * 切换方向：+1 = 下一首（新图从右滑入），-1 = 上一首（从左滑入）。
     * 渐变模式忽略方向。
     */
    slideDir: Int = 1
) {
    if (style == GlassStyle.OFF) {
        // 「关闭」就是真的什么都不画：只剩调用方自己的底色
        return
    }

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val artAlpha = GlassStyle.artAlpha(visibility) * if (canBlur) 1f else 0.45f
    val scrim = GlassStyle.scrimAlpha(visibility)

    var boxW by remember { mutableFloatStateOf(0f) }
    Box(modifier.onSizeChanged { boxW = it.width.toFloat() }) {

        // —— 封面切换的过渡（滑动 / 渐变）——
        // 换歌时封面位图是「整张替换」，直接换就是一帧闪变。
        //  ** 层序铁律 **：动画变换的 graphicsLayer 必须挂在 blur 层**外面**。
        // RenderEffect 的离屏缓冲按图层 bounds 裁切 —— 变换若在 blur 层里面，
        // 每一帧都在重画模糊缓冲（卡顿根源）；挂在外面，模糊纹理只算一次，
        // 动画只是搬运缓存纹理。
        //
        //  ** 形态：漂移淡化（取代 40% 宽硬边横扫）** —— 硬边横扫在整页模糊背景
        //  上方向感太重、观感生硬。现在改为：新图带 10% 宽的方向性位移**缓缓淡入**，
        //  旧图静止垫底、反向轻漂 4% 做视差纵深。旧图全程满盖，任何时刻都不会露
        //  底色；没有硬边 wipe，每帧只有一层的位移+透明度在动，自然和流畅都要。
        var currArt by remember { mutableStateOf(art) }
        var prevArt by remember { mutableStateOf<ImageBitmap?>(null) }
        var slideFrom by remember { mutableStateOf(1) } // 本次过渡的方向快照
        val slide = remember { Animatable(1f) }
        LaunchedEffect(art) {
            if (art == currArt) return@LaunchedEffect
            prevArt = currArt
            currArt = art
            slideFrom = slideDir
            slide.snapTo(0f)
            slide.animateTo(
                1f,
                tween(
                    durationMillis = transitionMs.coerceIn(150, 2000),
                    // 渐变用线性（透明度混合才均匀）；滑动用标准减速曲线，
                    // 「落定」而不是「冲到位」
                    easing = if (transition == ArtTransition.FADE) LinearEasing
                    else FastOutSlowInEasing
                )
            )
            prevArt = null
        }
        if (currArt != null || prevArt != null) {
            val matrix = remember(style.liquid, visibility) {
                ColorMatrix().apply {
                    setToSaturation(GlassStyle.saturation(style.liquid, visibility))
                }
            }
            val colorFilter = if (style.liquid) ColorFilter.colorMatrix(matrix) else null
            val blurMod = if (canBlur && blurDp > 0) Modifier.blur(blurDp.dp) else Modifier
            // 滑动判定读的是状态（prevArt/currArt/boxW），放在 lambda 里：
            // 动画帧不触发重组，boxW 首次测量后也能即时生效。
            // 只有单侧有图（无封面 / 首帧）时退化为纯淡化。
            prevArt?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val p = slide.value
                            val sliding = transition == ArtTransition.SLIDE &&
                                prevArt != null && currArt != null && boxW > 0f
                            translationX = if (sliding) -slideFrom * 0.04f * boxW * p else 0f
                            alpha = if (sliding) artAlpha else artAlpha * (1f - p)
                        }
                        .scale(ART_ZOOM)
                        .then(blurMod)
                )
            }
            currArt?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    // 1.35 倍放大是为了把模糊后发虚的边缘推出可视区。
                    // 溢出部分由调用方给的 clip / clipToBounds 裁掉 —— 之前漏了这一步时，
                    // 图会向左右各溢出 17.5% 的宽度，播放页三层横向轮播时盖到邻层上，
                    // 表现为"一条只在中途出现的竖条"。
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val p = slide.value
                            val sliding = transition == ArtTransition.SLIDE &&
                                prevArt != null && currArt != null && boxW > 0f
                            translationX = if (sliding) slideFrom * 0.10f * boxW * (1f - p) else 0f
                            alpha = artAlpha * p
                        }
                        .scale(ART_ZOOM)
                        .then(blurMod)
                )
            }
        }

        // 遮罩：上浅下深，保证叠在它上面的文字始终可读
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scrimColor.copy(alpha = scrim),
                            scrimColor.copy(alpha = (scrim + scrimSpread * 0.45f).coerceAtMost(0.97f)),
                            scrimColor.copy(alpha = (scrim + scrimSpread).coerceAtMost(0.99f))
                        )
                    )
                )
        )

        if (style.liquid && topHighlight) {
            // 顶部镜面高光
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.White.copy(alpha = 0.20f),
                            0.18f to Color.White.copy(alpha = 0.07f),
                            0.40f to Color.Transparent,
                            1.0f to Color.Transparent
                        )
                    )
            )
            // 上边缘折射亮线
            if (edgeHighlight) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.30f),
                                        Color.White.copy(alpha = 0.42f),
                                        Color.Transparent
                                    )
                                ),
                                start = Offset(0f, 0.5f),
                                end = Offset(size.width, 0.5f),
                                strokeWidth = 1.2.dp.toPx()
                            )
                        }
                )
            }
        }
    }
}
