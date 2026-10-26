package com.sonance.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.Skin

/**
 * 歌词「跟着背景自动调色」。
 *
 * 起因：歌词页的背景是**专辑封面模糊后透出来**的。有些封面（深蓝、暗红那类）
 * 透出来以后，和皮肤自己的歌词色撞在一起 —— 当前句用的是 accent、其它行是
 * textMuted / textDim —— 明度接近时那几行就糊进背景里看不清了。
 * 更麻烦的是封面的明暗是**分段**的（上面亮、下面暗，或者反过来），
 * 所以常见的情况是"只有屏幕某一段看不清"，而不是整页都看不清。
 *
 * 做法分三步：
 *  1. [artBandColors]：把封面按高度切成若干段，算出每段的平均色（换歌时算一次，后台线程）；
 *  2. [buildBgProfile]：按歌词页**真实的绘制方式**（Crop 填满 → 放大 1.35 → 裁切，
 *     再叠上那层 skin.bg 的竖向遮罩）把封面折算成一条"自上而下的等效背景色带"；
 *  3. [ensureReadable]：把某一行歌词的颜色朝"更亮"或"更暗"挪到与它所在那一段
 *     背景的对比度达标。方向**整页只选一次**（[pickLighten]），不是每行各挑各的。
 *
 * 调色时先只动明度（色相、饱和度都不动，观感上最接近原色），实在不够才往
 * 纯白 / 纯黑靠一点。要的效果是"还看得出是原来那套颜色，只是能看清了"，
 * 不追求高反差。
 */

/** 背景色带的分段数。段数越多越贴合封面的明暗起伏，代价只是几次线性插值。 */
internal const val PROFILE_BANDS = 16

/**
 * 封面在背景里的放大倍数。
 *
 * [GlassBackdrop] 画背景时用的也是这个值 —— 两边**必须是同一个常量**：
 * 一旦只改了一处，算出来的"等效背景色带"就和屏幕上真实看到的背景对不上，
 * 歌词会被调到错误的颜色上（而且是那种很难一眼看出的偏）。
 */
internal const val ART_ZOOM = 1.35f

/** 当前句的目标对比度。WCAG 对大字号文字的门槛是 3:1，这里留一点余量。 */
internal const val TARGET_CURRENT = 4.0f

/**
 * 非当前句的目标对比度：比当前句低一档，保住「当前句最显眼」的层次。
 *
 * 门槛本来是 3:1，这里多要了约 13%。原因：[buildBgProfile] 是**算**出来的背景，
 * 和屏幕上真正画出来的那个背景之间总有一点差（模糊会把明暗交界处往对面拉，
 * 实测残差约 0.02~0.03 亮度）。调色是照着算出来的背景做的，算出来偏亮时，
 * 屏幕上就会差一口气 —— 实测刚好卡在 2.9 左右。把目标抬一档，实测才稳稳落在 3:1 以上。
 */
internal const val TARGET_OTHER = 3.4f

/* ============================================================
 * 等效背景色带
 * ============================================================ */

/**
 * 歌词区自上而下的一条背景色带。
 *
 * [at] 收一个 0~1 的纵向比例（0 = 屏幕顶、1 = 屏幕底），返回该处的等效背景色，
 * 段与段之间做线性插值，所以相邻两行的颜色不会突然跳。
 *
 * [lighten] 是**整页共用**的调色方向（true = 往亮走），由 [buildBgProfile] 按皮肤的
 * 歌词色算一次。为什么必须整页统一，见 [pickLighten]。
 */
internal class BgProfile(private val colors: List<Color>, val lighten: Boolean) {

    fun at(fraction: Float): Color {
        if (colors.isEmpty()) return Color.Black
        if (colors.size == 1) return colors[0]
        val f = fraction.coerceIn(0f, 1f) * (colors.size - 1)
        val i = f.toInt()
        if (i >= colors.size - 1) return colors.last()
        return mix(colors[i], colors[i + 1], f - i)
    }
}

/**
 * 整页共用的调色方向：true = 往亮走。
 *
 * 为什么必须**整页只选一次**：如果让每一行各自挑"改动更小的方向"，那么背景亮度跨过
 * 中灰的那一行就成了分水岭 —— 它上面那行被压成近黑、下面那行被提成近白，两行紧挨着、
 * 背景却几乎没差。屏幕上就是"一页三种颜色、中间硬跳一下"，非常难看。
 * （实测就是这样：上半截近黑、中间近白、下半截原色 —— 用户的原话是"效果太差"。）
 *
 * 选法：只看**本来就不达标**的那几段（对 [bases] 里任何一个颜色都不达标才算），
 * 分别算出"往亮走"和"往暗走"时**最难的那一段最多能到多少**对比度，谁的下限高就选谁。
 * 说白了就是"让最糟的那一行尽量好读"。打平时让歌词待在它本来就在的那一侧 ——
 * 原色比那几段的背景亮就继续往亮走，反之往暗走。
 *
 * @param bases 这一页会用到的歌词颜色（[Skin.textDim] / [Skin.textMuted] / [Skin.accent]）。
 *   当前句的强调色**必须**算进来 —— 否则会出"其它行都够清楚、只有当前句撞色"时
 *   一票都没有、方向随手选一个的情况（实测踩过：浅色皮肤配亮封面，当前句被推成了白色）。
 */
internal fun pickLighten(bases: List<Color>, colors: List<Color>, target: Float): Boolean {
    var lightWorst = Float.MAX_VALUE
    var darkWorst = Float.MAX_VALUE
    var failSum = 0f
    var failCount = 0
    for (bg in colors) {
        // 这一段对每个歌词色都够清楚 -> 它根本不会被改，方向与它无关
        if (bases.none { contrastRatio(it, bg) < target }) continue
        failSum += bg.luminance()
        failCount++
        lightWorst = minOf(lightWorst, contrastRatio(Color.White, bg))
        darkWorst = minOf(darkWorst, contrastRatio(Color.Black, bg))
    }
    if (failCount == 0) return true
    if (kotlin.math.abs(lightWorst - darkWorst) > 0.01f) return lightWorst > darkWorst
    return bases.first().luminance() >= failSum / failCount
}

/**
 * 把封面按**高度**切成 [bandCount] 段，取每段的平均色。
 *
 * 横向最多取 16 列采样点：歌词背景本来就是模糊的，横向精度没有意义，
 * 少读点像素能保证这次计算足够快（它在后台线程跑，但也没必要浪费）。
 *
 * 返回 null 表示这张图取不出任何颜色（全透明），此时上层不做任何调整。
 */
internal fun artBandColors(art: ImageBitmap, bandCount: Int = PROFILE_BANDS): List<Color>? {
    val w = art.width
    val h = art.height
    if (w <= 0 || h <= 0 || bandCount <= 0) return null

    val px = runCatching { art.toPixelMap() }.getOrNull() ?: return null
    val stepX = maxOf(1, w / 16)
    val out = ArrayList<Color>(bandCount)
    var carry: Color? = null

    for (b in 0 until bandCount) {
        val y0 = b * h / bandCount
        val y1 = maxOf(y0 + 1, (b + 1) * h / bandCount)
        var r = 0f
        var g = 0f
        var bl = 0f
        var n = 0
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < w) {
                val c = px[x, y]
                if (c.alpha > 0.15f) {
                    r += c.red
                    g += c.green
                    bl += c.blue
                    n++
                }
                x += stepX
            }
            y++
        }
        val color = if (n == 0) carry ?: Color.Transparent else Color(r / n, g / n, bl / n, 1f)
        if (color.alpha > 0f) carry = color
        out += color
    }

    // 开头（或整图）有空段时用第一个有效色补上，避免色带里出现"透明洞"
    val firstValid = out.firstOrNull { it.alpha > 0f } ?: return null
    for (i in out.indices) if (out[i].alpha <= 0f) out[i] = firstValid
    return out
}

/**
 * 把封面色带折算成**屏幕上实际看到的**等效背景色带。
 *
 * 复刻 [GlassBackdrop] 的画法：
 *  1. `ContentScale.Crop` 把封面填满整层（竖屏下相当于按高度铺满、左右裁掉）；
 *  2. 再 `scale(1.35f)` 放大 —— 于是**能看见的只是封面中间那 1/1.35 的一段**；
 *  3. 叠上 skin.bg 的竖向遮罩（三档：scrim / scrim+0.10 / scrim+0.22）。
 *
 * 顺序必须和真实绘制顺序一致：先底色（skin.bg），再封面（带不透明度），最后遮罩。
 * 反过来算出来的颜色会偏，越透明偏得越多。
 *
 * @param boxW/boxH 歌词页的像素尺寸（Crop 和放大都是相对它算的）
 * @param canBlur 这台机器有没有真模糊（API 31+）。没有时封面只有 45% 的不透明度，
 *   背景会明显更"淡" —— 色带必须跟着这个差异走，否则算出来的背景色偏浓，
 *   会把本来不需要提亮的歌词提亮。
 * @param liquid 液态玻璃。这一档在封面和遮罩之上**还叠了一道顶部镜面高光**，
 *   屏幕最上面那 40% 会被白光提亮。不把它算进来的话，顶部那几行的背景亮度会被
 *   低估约 0.07（实测），调出来的歌词颜色就浅一档 —— 屏幕上看着"调了，但还是发灰"。
 * @param scrimSpread 遮罩纵向加厚幅度，必须和 [GlassBackdrop] 传的同一个值，
 *   否则算出来的色带和真实背景对不上。
 * @param topHighlight 顶部镜面高光开关，同样必须和画的那边一致（主界面关掉了它，
 *   这里的色带也不能把它算进来）。
 */
internal fun buildBgProfile(
    bands: List<Color>,
    artW: Int,
    artH: Int,
    boxW: Float,
    boxH: Float,
    skin: Skin,
    visibility: Int,
    canBlur: Boolean,
    liquid: Boolean = false,
    scrimSpread: Float = 0.22f,
    topHighlight: Boolean = true
): BgProfile? {
    if (bands.isEmpty() || artW <= 0 || artH <= 0 || boxW <= 0f || boxH <= 0f) return null

    val scale = maxOf(boxW / artW, boxH / artH) * ART_ZOOM
    val visibleH = boxH / scale
    val topInArt = (artH - visibleH) / 2f

    // 和 GlassBackdrop 一致：拿不到真模糊时封面只有 45% 的不透明度
    val artAlpha = GlassStyle.artAlpha(visibility) * if (canBlur) 1f else 0.45f
    val scrim = GlassStyle.scrimAlpha(visibility)
    val scrimMid = (scrim + scrimSpread * 0.45f).coerceAtMost(0.97f)
    val scrimBottom = (scrim + scrimSpread).coerceAtMost(0.99f)

    val out = ArrayList<Color>(PROFILE_BANDS)
    for (i in 0 until PROFILE_BANDS) {
        val f = (i + 0.5f) / PROFILE_BANDS
        val artColor = sampleBands(bands, ((topInArt + f * visibleH) / artH).coerceIn(0f, 1f))
        val overArt = mix(skin.bg, artColor, artAlpha)
        val s = if (f <= 0.5f) {
            scrim + (scrimMid - scrim) * (f / 0.5f)
        } else {
            scrimMid + (scrimBottom - scrimMid) * ((f - 0.5f) / 0.5f)
        }
        val masked = mix(overArt, skin.bg, s)
        // 液态玻璃的最后一道：顶部镜面高光（白光，从上往下衰减）。画在遮罩**之后**，
        // 所以顺序上必须放在最后一步，否则会把遮罩的压暗算反。
        out += if (liquid && topHighlight) mix(masked, Color.White, liquidHighlightAlpha(f)) else masked
    }
    // 方向整页定一次 —— 三种歌词色都参与（当前句的强调色也算），用非当前句的目标
    // （页面上绝大多数行是它）。
    return BgProfile(
        out,
        pickLighten(listOf(skin.textDim, skin.textMuted, skin.accent), out, TARGET_OTHER)
    )
}

/**
 * 液态玻璃「顶部镜面高光」的白色不透明度（对应 [GlassBackdrop] 里那条 verticalGradient）。
 *
 * 三档：顶边 0.20 → 18% 处 0.07 → 40% 处 0，再往下没有。段间线性插值，
 * 和 Compose 的 `Brush.verticalGradient` 对显式档位的处理一致。
 */
private fun liquidHighlightAlpha(f: Float): Float = when {
    f <= 0f -> 0.20f
    f < 0.18f -> 0.20f + (0.07f - 0.20f) * (f / 0.18f)
    f < 0.40f -> 0.07f * (1f - (f - 0.18f) / 0.22f)
    else -> 0f
}

/**
 * 内嵌歌词（播放页点「歌词」按钮、封面那块换出来的歌词）的等效背景色带。
 *
 * 与全屏歌词页（[buildBgProfile]）的差别：这里的背景不是「封面模糊」，
 * 而是**晕染层** —— 播放页的晕染已经整张烘焙成位图（[GlowFrame].image），
 * 再叠在皮肤底色 skin.bg 上。晕染有十几种模式、强度和蒙版的组合，
 * 与其把整套绘制管线复刻一遍，不如直接**采样成品位图**：取中轴一列
 * （内嵌歌词横跨全宽，中轴就是它的平均处境），自上而下取 [PROFILE_BANDS] 个点，
 * 按各自的不透明度折算成不透明背景色 —— 和歌词真正叠上去的那层颜色一致。
 *
 * 晕染关闭（image 为 null）时返回 null —— 维持皮肤原本的配色。
 */
internal fun buildInlineBgProfile(glowImage: ImageBitmap?, skin: Skin): BgProfile? {
    if (glowImage == null || glowImage.width <= 0 || glowImage.height <= 0) return null
    val px = runCatching { glowImage.toPixelMap() }.getOrNull() ?: return null
    val x = glowImage.width / 2
    val out = ArrayList<Color>(PROFILE_BANDS)
    for (i in 0 until PROFILE_BANDS) {
        val f = (i + 0.5f) / PROFILE_BANDS
        val y = (f * (glowImage.height - 1)).toInt()
        val c = px[x, y]
        // 晕染位图带 alpha：真实屏幕颜色 = 皮肤底色上 source-over 这一层
        out += mix(skin.bg, Color(c.red, c.green, c.blue, 1f), c.alpha)
    }
    // 方向整页定一次，与全屏歌词页同一套目标值 —— 两边的「看得清」必须是同一个标准
    return BgProfile(
        out,
        pickLighten(listOf(skin.textDim, skin.textMuted, skin.accent), out, TARGET_OTHER)
    )
}

/* ============================================================
 * 对比度校正
 * ============================================================ */

/** 调色时最多试多少档。档数越多越贴合"刚好够用"的那个点，代价只是几十次浮点运算。 */
private const val MAX_STEPS = 24

/**
 * 按背景把歌词颜色调到"看得清"。
 *
 * 方向由调用方给定（[BgProfile.lighten]，整页统一），这里只负责"沿着这一个方向
 * 走到刚好够用的那一档" —— 走得越少越好，绝不为了保险多推一截。
 *
 * 走的路（见 [walk]）分两段：先只动明度（饱和度不动，观感上最像原色），
 * 明度到顶 / 到底之后再往纯白 / 纯黑靠。少了后半段的话，浅色背景上的高饱和
 * 强调色会因为"明度已经到顶"而提不动，达不到目标。
 *
 * 方向走到底（纯白 / 纯黑）都够不到目标时，返回这一方向上对比度最高的那一档 ——
 * 仍然是"能看清多少算多少"，而不是掉头往反方向跳（那正是整页花掉的原因）。
 *
 * @param target 目标对比度（WCAG 对比度，1~21）
 * @param lighten true = 往亮走，false = 往暗走
 */
internal fun ensureReadable(fg: Color, bg: Color, target: Float, lighten: Boolean): Color {
    if (contrastRatio(fg, bg) >= target) return fg

    // 原色的 H/S/V 只算一次 —— 下面几十档都拿它当起点，
    // 每档重新转一遍是白费功夫（这段代码每个可见行、每次重组都会跑）。
    val hsv = FloatArray(3)
    rgbToHsv(fg.red, fg.green, fg.blue, hsv)
    val h = hsv[0]
    val s = hsv[1]
    val v0 = hsv[2]

    var best = fg
    var bestRatio = contrastRatio(fg, bg)
    for (i in 1..MAX_STEPS) {
        val cand = walk(h, s, v0, lighten, i / MAX_STEPS.toFloat())
        val ratio = contrastRatio(cand, bg)
        if (ratio >= target) return cand
        if (ratio > bestRatio) {
            bestRatio = ratio
            best = cand
        }
    }
    return best
}

/**
 * 从 H/S/V 为 [h] / [s] / [v0] 的颜色出发，朝「更亮」（[lighten] = true）或
 * 「更暗」走 [t]（0~1）之后的颜色。
 *
 * 分两段走，是为了尽量保住原来的鲜艳度：
 *  - 前半段（t ≤ 0.5）只推明度，**饱和度一点不动** —— 观感上还是原来那个颜色；
 *  - 明度到顶 / 到底之后，后半段才往纯白 / 纯黑靠，把剩下的对比度补上。
 *    少了后半段的话，浅色背景上的高饱和强调色会因为"明度已经到顶"而提不动。
 */
private fun walk(h: Float, s: Float, v0: Float, lighten: Boolean, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    val half = 0.5f

    return if (lighten) {
        if (k <= half) {
            hsvColor(h, s, v0 + (1f - v0) * (k / half))
        } else {
            mix(hsvColor(h, s, 1f), Color.White, (k - half) / half)
        }
    } else {
        if (k <= half) hsvColor(h, s, v0 * (1f - k / half)) else Color.Black
    }
}

/** WCAG 相对对比度 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/* ============================================================
 * 给某一行歌词挑颜色
 * ============================================================ */

/**
 * 一行歌词该用什么颜色。
 *
 * [screenFraction] 是这一行**在屏幕上的纵向位置**（0 = 顶、1 = 底），
 * 由调用方按"当前句停在哪、每行多高"换算出来。这样屏幕上半段取的就是上半段的
 * 背景色，某一段背景偏亮时只有那一段的歌词会被提亮，其余行不受影响。
 *
 * 往哪个方向调由 [BgProfile.lighten] 定（整页一次），所以同一页里不会出现
 * "上一行近黑、下一行近白"这种跳变。
 */
internal fun lyricColor(
    base: Color,
    screenFraction: Float,
    isCurrent: Boolean,
    profile: BgProfile
): Color = ensureReadable(
    fg = base,
    bg = profile.at(screenFraction),
    target = if (isCurrent) TARGET_CURRENT else TARGET_OTHER,
    lighten = profile.lighten
)

/**
 * 某一行要不要跟着背景调色。
 *
 * 抽成一个纯函数，是为了让「档位语义」能被单元测试钉住 —— 尤其是
 * [LyricAdaptMode.CURRENT_ONLY]：它只该放过当前句，其余行必须原样不动。
 */
internal fun shouldAdapt(isCurrent: Boolean, mode: LyricAdaptMode): Boolean = when (mode) {
    LyricAdaptMode.OFF -> false
    LyricAdaptMode.CURRENT_ONLY -> isCurrent
    LyricAdaptMode.ALL -> true
}

/* ============================================================
 * 纯文本歌词：按行取色
 * ============================================================ */

/** [visibleFractions] 的输入：某一项的序号、相对视口顶部的偏移、自身高度（px）。 */
internal data class VisibleLine(val index: Int, val offset: Int, val size: Int)

/**
 * 把 LazyColumn 量出来的**可见项**换算成「每一项中心在屏幕上的纵向比例」。
 *
 * 纯文本歌词没有时间轴，推不出"第几行对应屏幕哪一块"；而且行高也不齐（长句会折行），
 * 所以也不能像 [SyncedLyrics] 那样用"当前句 + 行号差"算。最省事的办法是直接问
 * LazyColumn 要它刚量出来的位置 —— 只取可见的那几项，屏外的行本来也看不见，
 * 不需要给它们挑颜色。
 *
 * @param viewportHeightPx 视口高度。取不到（≤ 0）时返回空表，调用方会退回
 *   "整页按中段取色"的老行为。
 */
internal fun visibleFractions(boxes: List<VisibleLine>, viewportHeightPx: Int): Map<Int, Float> {
    if (viewportHeightPx <= 0) return emptyMap()
    val out = HashMap<Int, Float>(boxes.size)
    for (b in boxes) {
        out[b.index] = ((b.offset + b.size / 2f) / viewportHeightPx).coerceIn(0f, 1f)
    }
    return out
}

/* ============================================================
 * 小工具
 * ============================================================ */

private fun mix(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f
    )
}

private fun sampleBands(bands: List<Color>, fraction: Float): Color {
    if (bands.size == 1) return bands[0]
    val f = fraction.coerceIn(0f, 1f) * (bands.size - 1)
    val i = f.toInt()
    if (i >= bands.size - 1) return bands.last()
    return mix(bands[i], bands[i + 1], f - i)
}

/*
 * 下面是自己的 RGB ↔ HSV 换算，没有用 android.graphics.Color。
 * 原因：这两个函数是纯数学，放在这里能直接被单元测试覆盖 ——
 * 「调完还看不看得清」这种事必须能算出来，不能只靠肉眼看截图。
 */

internal fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    out[2] = max
    out[1] = if (max <= 0f) 0f else d / max
    out[0] = when {
        d <= 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
}

internal fun hsvColor(h: Float, s: Float, v: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val value = v.coerceIn(0f, 1f)
    val c = value * sat
    val hp = hue / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    var r = 0f
    var g = 0f
    var b = 0f
    when {
        hp < 1f -> { r = c; g = x }
        hp < 2f -> { r = x; g = c }
        hp < 3f -> { g = c; b = x }
        hp < 4f -> { g = x; b = c }
        hp < 5f -> { r = x; b = c }
        else -> { r = c; b = x }
    }
    val m = value - c
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}
