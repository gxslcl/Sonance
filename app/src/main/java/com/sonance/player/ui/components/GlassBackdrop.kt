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
    slideDir: Int = 1,
    /**
     * 均衡遮罩的逐停靠点不透明度（[equalizeScrimAlphas] 的返回值，均匀纵向分布）。
     *
     * 非空时遮罩不再是固定的三档，而是按这份值画多档渐变 —— 沉浸皮肤主界面
     * 用它把合成亮度钉在恒定深色区间，换歌只有色相在动。每组「封面+遮罩」
     * 在各自的离屏图层里先合成钉亮，再整层混合（见下方过渡结构注释）。
     * 歌词页 / 律动柱页 / 迷你播放条不传，维持原画法。
     */
    scrimAlphas: List<Float>? = null
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
        //  ** 组化合成（钉亮的关键）**：每组「封面+遮罩」在各自的 graphicsLayer
        //  里先合成（组内 scrim 盖住自己的封面，合成亮度钉在目标 L），整层再按
        //  组透明度混合 —— p·L + (1-p)·L = L，过渡全程数学上精确钉亮。
        //  之前的做法是「遮罩按进度插值盖在混合中的封面上」：两组各自单独标定
        //  的遮罩插值后盖在 p·新 + (1-p)·旧的混合色上，中段合成亮度偏离目标
        //  （暗→亮封面切换冲高、亮→暗切换凹谷），真机录屏实测过冲 +7~+10、
        //  凹谷深 10~14 个灰度级 —— 观感就是「闪一下」。
        //  位移也挂在组层上（组内图有 1.35 倍溢出 + 模糊，位移 ≤10% 宽不会露洞；
        //  纵向渐变遮罩不受横移影响）。每帧只有组层的位移+透明度在动。
        var currArt by remember { mutableStateOf(art) }
        var prevArt by remember { mutableStateOf<ImageBitmap?>(null) }
        var slideFrom by remember { mutableStateOf(1) } // 本次过渡的方向快照
        val slide = remember { Animatable(1f) }

        // —— 均衡遮罩的新旧值快照 ——
        // 遮罩不透明度由封面色带算出（随封面同帧换上）。换歌时旧值快照进
        // prevScrims，**和旧封面编进同一组**（同组内自洽钉亮）；新值进 curr 组。
        var currScrims by remember { mutableStateOf(scrimAlphas) }
        var prevScrims by remember { mutableStateOf<List<Float>?>(null) }
        LaunchedEffect(scrimAlphas) {
            if (scrimAlphas == currScrims) return@LaunchedEffect
            prevScrims = currScrims
            currScrims = scrimAlphas
        }
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
        if (currArt == null && prevArt == null) {
            // 无封面兜底：只剩遮罩（均衡值随封面一起没了，走固定三档）
            ScrimLayer(
                scrims = currScrims,
                scrimColor = scrimColor,
                scrim = scrim,
                scrimSpread = scrimSpread,
                modifier = Modifier.matchParentSize()
            )
        } else {
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
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val p = slide.value
                            // ** 覆盖式淡化（滑动/渐变共用）**：只要新图存在，旧组就全程
                            // 满不透明度、被新组盖上来 —— 旧组自己淡出的「真交叉淡化」
                            // 会在中段把底色透出来。只在换到「没有封面的歌」时才淡出
                            alpha = if (currArt != null) 1f else 1f - p
                            val sliding = transition == ArtTransition.SLIDE &&
                                currArt != null && boxW > 0f
                            translationX = if (sliding) -slideFrom * 0.04f * boxW * p else 0f
                        }
                ) {
                    // 组内底色：封面带不透明度（可见度 <100 时 <1），若直接在透明
                    // 缓冲上合成，组的像素 alpha <1 —— 两层叠加时中段多混进一截
                    // 旧组、动画结束移除旧组的瞬间又少一截（≈2.5 个灰度级，
                    // 真机录屏实锤「过渡快结束时跳一下」）。垫上遮罩同色实底后
                    // 组先合成到完全不透明，p·L + (1-p)·L = L 才严格成立。
                    Box(Modifier.matchParentSize().background(scrimColor))
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = artAlpha } // API<31 无真模糊，降不透明度近似
                            .scale(ART_ZOOM)
                            .then(blurMod)
                    )
                    ScrimLayer(
                        scrims = prevScrims,
                        scrimColor = scrimColor,
                        scrim = scrim,
                        scrimSpread = scrimSpread,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            currArt?.let { bmp ->
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val p = slide.value
                            val sliding = transition == ArtTransition.SLIDE &&
                                prevArt != null && boxW > 0f
                            translationX = if (sliding) slideFrom * 0.10f * boxW * (1f - p) else 0f
                            alpha = p
                        }
                ) {
                    // 同上：组内垫遮罩同色实底，组合成到不透明再参与整层混合
                    Box(Modifier.matchParentSize().background(scrimColor))
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
                            .graphicsLayer { alpha = artAlpha } // API<31 无真模糊，降不透明度近似
                            .scale(ART_ZOOM)
                            .then(blurMod)
                    )
                    ScrimLayer(
                        scrims = currScrims,
                        scrimColor = scrimColor,
                        scrim = scrim,
                        scrimSpread = scrimSpread,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }

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

/**
 * 遮罩层：上浅下深，保证叠在它上面的文字始终可读。
 *
 * 均衡模式（[scrims] 非空且 ≥2 档）画多档停靠点渐变 —— 值由
 * [equalizeScrimAlphas] 按当前封面单独标定，组内合成亮度钉在目标区间；
 * 固定模式（null）维持原来的三档。不参与新旧值插值 —— 插值语义已由
 * 「新旧两组各自钉亮后整层混合」取代。
 */
@Composable
private fun ScrimLayer(
    scrims: List<Float>?,
    scrimColor: Color,
    scrim: Float,
    scrimSpread: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.drawBehind {
            val stops: List<Pair<Float, Color>> = if (scrims == null || scrims.size < 2) {
                listOf(
                    0f to scrimColor.copy(alpha = scrim),
                    0.5f to scrimColor.copy(alpha = (scrim + scrimSpread * 0.45f).coerceAtMost(0.97f)),
                    1f to scrimColor.copy(alpha = (scrim + scrimSpread).coerceAtMost(0.99f))
                )
            } else {
                val n = scrims.size
                List(n) { i ->
                    (i.toFloat() / (n - 1)) to scrimColor.copy(alpha = scrims[i].coerceIn(0f, 1f))
                }
            }
            drawRect(Brush.verticalGradient(*stops.toTypedArray()))
        }
    )
}
