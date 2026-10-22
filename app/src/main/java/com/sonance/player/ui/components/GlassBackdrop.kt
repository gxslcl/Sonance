package com.sonance.player.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin

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
    scrimSpread: Float = 0.22f
) {
    if (style == GlassStyle.OFF) {
        // 「关闭」就是真的什么都不画：只剩调用方自己的底色
        return
    }

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val artAlpha = GlassStyle.artAlpha(visibility) * if (canBlur) 1f else 0.45f
    val scrim = GlassStyle.scrimAlpha(visibility)

    Box(modifier) {

        art?.let { bmp ->
            val matrix = remember(style.liquid, visibility) {
                ColorMatrix().apply {
                    setToSaturation(GlassStyle.saturation(style.liquid, visibility))
                }
            }
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (style.liquid) ColorFilter.colorMatrix(matrix) else null,
                // 1.35 倍放大是为了把模糊后发虚的边缘推出可视区。
                // 溢出部分由调用方给的 clip / clipToBounds 裁掉 —— 之前漏了这一步时，
                // 图会向左右各溢出 17.5% 的宽度，播放页三层横向轮播时盖到邻层上，
                // 表现为"一条只在中途出现的竖条"。
                modifier = Modifier
                    .matchParentSize()
                    .scale(ART_ZOOM)
                    .then(if (canBlur && blurDp > 0) Modifier.blur(blurDp.dp) else Modifier)
                    .alpha(artAlpha)
            )
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

        if (style.liquid) {
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
