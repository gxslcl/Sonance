package com.sonance.player.ui.wall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 原生封面墙的「引擎」：程序封面 + 种子布局 + 物理。
 *
 * 全部物理量沿用 5.5 原型的 CSS 像素空间（_scrollY / vel / 模板单位_），渲染时才乘
 * 设备密度换算成像素 —— 这样摩擦 / 回弹 / 弯曲阈值与原型逐字一致，手感不漂移。
 * 与 WebView 版的根本区别：没有 DOM / 样式级联 / 渲染进程，封面是缓存位图，整面墙
 * 由单个 Canvas + 单条 withFrameNanos 帧循环驱动，震动脉冲与滚动节拍在同一帧算出。
 */

// ===== 确定性随机（与 wall.html 逐位一致） =====

internal fun wallHashStr(s: String): Int {
    var h = 0x811C9DC5.toInt()
    for (ch in s) {
        h = h xor ch.code
        h *= 16777619
    }
    return h
}

internal class WallRng(seed: Int) {
    private var a = seed
    fun next(): Float {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}

internal fun hsl(h: Float, s: Float, l: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f
    return ColorUtils.HSLToColor(floatArrayOf(hh, s / 100f, l / 100f))
}

// ===== 封面规格（专辑名 + 艺术家 → 确定性艺术封面） =====

// 索引就是 CoverSpec.style；0..7 保持原型定义，8..15 为新增程序艺术。
internal val COVER_STYLE_NAMES: List<String> = listOf(
    "光点渐层", "同心唱片", "斜纹律动", "波点矩阵",
    "锥形色谱", "巨字海报", "双域日轮", "双色光雾",
    "山水剪纸", "包豪斯拼贴", "星图轨道", "等高地形",
    "霓虹线网", "日落海浪", "欧普棋盘", "极简印刷"
)

internal class CoverSpec(
    val title: String,
    val artist: String,
    val h1: Float,
    val h2: Float,
    val style: Int,
    val px: Float,
    val py: Float,
    val ang: Float,
    val label: Boolean
) {
    val mainColor: Int = hsl(h1, 70f, 50f)
}

internal fun decorateCover(name: String, artist: String): CoverSpec {
    val r = WallRng(wallHashStr("$name|$artist"))
    val h1 = floor(r.next() * 360f)
    val h2 = h1 + 60f + floor(r.next() * 180f)
    // Float 随机数极端情况下会舍入到 1，仍须保证索引落在 0..15。
    val style = floor(r.next() * COVER_STYLE_NAMES.size).toInt().coerceIn(COVER_STYLE_NAMES.indices)
    val px = 20f + floor(r.next() * 60f)
    val py = 20f + floor(r.next() * 60f)
    val ang = floor(r.next() * 180f)
    val label = r.next() < 0.45f
    return CoverSpec(name, artist, h1, h2, style, px, py, ang, label)
}

/** 后台生成缓存位图：16 种程序艺术；不烘焙釉面高光，不依赖时钟或外部图片。 */
internal fun renderCoverBitmap(spec: CoverSpec, res: Int, cornerRes: Float): Bitmap {
    require(res > 0) { "Cover resolution must be positive" }
    val size = res.coerceAtMost(512)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val rf = size.toFloat()
    val h1 = spec.h1
    val h2 = spec.h2
    val style = if (spec.style in COVER_STYLE_NAMES.indices) spec.style else 7
    // label 不参与艺术种子：切换文字层不会改变底图；手工指定 style 可直接预览。
    val artRng = WallRng(wallHashStr("${spec.title}|${spec.artist}|$style|$h1|$h2|${spec.px}|${spec.py}|${spec.ang}"))
    val phase = Math.toRadians(spec.ang.toDouble()).toFloat()
    val path = Path()

    fun farthestCorner(cx: Float, cy: Float): Float =
        max(max(hypot(cx, cy), hypot(rf - cx, cy)), max(hypot(cx, rf - cy), hypot(rf - cx, rf - cy)))

    fun cssLinear(angleDeg: Float, colors: IntArray, pos: FloatArray?, mode: Shader.TileMode): LinearGradient {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val len = rf * (abs(dx) + abs(dy))
        val cx = rf / 2f
        val cy = rf / 2f
        return LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f, colors, pos, mode)
    }

    fun fill(shader: Shader?) {
        p.shader = shader
        c.drawRect(0f, 0f, rf, rf, p)
        p.shader = null
    }

    // 新风格统一使用归一化坐标；路径与画笔在本次后台生成中复用。
    fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        p.color = color
        c.drawRect(left * rf, top * rf, right * rf, bottom * rf, p)
    }

    fun disc(x: Float, y: Float, radius: Float, color: Int) {
        p.color = color
        c.drawCircle(x * rf, y * rf, radius * rf, p)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.002f) {
        p.color = color
        p.strokeWidth = max(0.6f, width * rf)
        c.drawLine(x1 * rf, y1 * rf, x2 * rf, y2 * rf, p)
    }

    fun strokePath(color: Int, width: Float = 0.002f) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.6f, width * rf)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    fun note(text: String, x: Float, y: Float, color: Int, textSize: Float = 0.024f) {
        p.color = color
        p.typeface = Typeface.MONOSPACE
        p.textSize = textSize * rf
        p.textAlign = Paint.Align.LEFT
        c.drawText(text, x * rf, y * rf, p)
        p.typeface = Typeface.DEFAULT
    }

    when (style) {
        0 -> {
            fill(cssLinear(160f, intArrayOf(hsl(h1, 60f, 40f), hsl(h1 + 40f, 70f, 12f)), null, Shader.TileMode.CLAMP))
            val cx = spec.px / 100f * rf
            val cy = spec.py / 100f * rf
            val dr = 0.216f * farthestCorner(cx, cy)
            val dot = hsl(h2, 90f, 66f)
            fill(RadialGradient(cx, cy, dr, intArrayOf(dot, dot, Color.TRANSPARENT), floatArrayOf(0f, 0.972f, 1f), Shader.TileMode.CLAMP))
        }
        1 -> {
            val a = hsl(h1, 70f, 55f)
            val b = hsl(h2, 55f, 18f)
            p.color = a
            c.drawRect(0f, 0f, rf, rf, p)
            val step = 0.05f * 0.707f * rf
            val n = ceil(0.78f * rf / step).toInt()
            for (k in n - 1 downTo 0) {
                p.color = if (k % 2 == 0) a else b
                c.drawCircle(rf / 2f, rf / 2f, (k + 1) * step, p)
            }
        }
        2 -> {
            val a = hsl(h1, 70f, 52f)
            val b = hsl(h2, 65f, 26f)
            val rad = Math.toRadians(spec.ang.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val period = 0.18f * rf
            fill(LinearGradient(0f, 0f, dx * period, dy * period, intArrayOf(a, a, b, b), floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT))
        }
        3 -> {
            p.color = hsl(h1, 45f, 20f)
            c.drawRect(0f, 0f, rf, rf, p)
            val cell = rf / 6f
            val dotR = 0.22f * cell
            p.color = hsl(h2, 90f, 70f)
            for (i in 0..5) for (j in 0..5) c.drawCircle((i + 0.5f) * cell, (j + 0.5f) * cell, dotR, p)
        }
        4 -> {
            val cx = spec.px / 100f * rf
            val cy = spec.py / 100f * rf
            val colors = intArrayOf(hsl(h1, 80f, 55f), hsl(h2, 80f, 42f), hsl(h1 + 180f, 60f, 25f), hsl(h1, 80f, 55f))
            val sweep = SweepGradient(cx, cy, colors, floatArrayOf(0f, 0.33f, 0.66f, 1f))
            val m = Matrix()
            m.setRotate(spec.ang - 90f, cx, cy)
            sweep.setLocalMatrix(m)
            fill(sweep)
        }
        5 -> {
            p.color = hsl(h1, 35f, 86f)
            c.drawRect(0f, 0f, rf, rf, p)
            // 按 Unicode code point 取首字，不能拆开 emoji / 扩展汉字的代理对。
            val ch = if (spec.title.isEmpty()) "♪" else spec.title.substring(0, spec.title.offsetByCodePoints(0, 1))
            p.color = hsl(h2, 70f, 30f)
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 0.8f * rf
            p.textAlign = Paint.Align.LEFT
            val bounds = android.graphics.Rect()
            p.getTextBounds(ch, 0, ch.length, bounds)
            val glyphW = max(p.measureText(ch), bounds.width().toFloat()).coerceAtLeast(1f)
            val glyphH = bounds.height().toFloat().coerceAtLeast(1f)
            p.textSize *= min(1f, min(0.88f * rf / glyphW, 0.82f * rf / glyphH))
            p.getTextBounds(ch, 0, ch.length, bounds)
            // 保留左下巨字构图，同时给下伸部、中文和 emoji 的实际字形留出安全边。
            c.drawText(ch, 0.06f * rf - min(0, bounds.left), 0.96f * rf - max(0, bounds.bottom), p)
            p.typeface = Typeface.DEFAULT
        }
        6 -> {
            p.color = hsl(h1, 65f, 58f)
            c.drawRect(0f, 0f, rf, rf / 2f, p)
            p.color = hsl(h2, 50f, 14f)
            c.drawRect(0f, rf / 2f, rf, rf, p)
            p.color = hsl(h1 + 30f, 90f, 62f)
            c.drawCircle(rf / 2f, rf / 2f, 0.246f * 0.707f * rf, p)
        }
        7 -> {
            p.color = hsl(h1 + 200f, 40f, 10f)
            c.drawRect(0f, 0f, rf, rf, p)
            fun glow(cx: Float, cy: Float, color: Int) {
                val r = 0.55f * farthestCorner(cx, cy)
                fill(RadialGradient(cx, cy, r, intArrayOf(color, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP))
            }
            glow(0.22f * rf, 0.78f * rf, hsl(h1, 90f, 58f))
            glow(0.78f * rf, 0.22f * rf, hsl(h2, 90f, 55f))
        }
        8 -> {
            // 山水剪纸：宣纸、赭日、四层冷暖山脊、留白水道与小印章。
            val paper = Color.rgb(242, 231, 209)
            val ink = Color.rgb(40, 65, 66)
            rect(0f, 0f, 1f, 1f, paper)
            disc(0.72f + (spec.px - 50f) * 0.001f, 0.25f, 0.105f, Color.rgb(199, 98, 69))
            val mountains = intArrayOf(
                hsl(h1 + 120f, 19f, 66f), hsl(h1 + 135f, 24f, 51f),
                hsl(h1 + 150f, 28f, 36f), ink
            )
            for (layer in 0..3) {
                val base = 0.46f + layer * 0.135f
                path.reset()
                path.moveTo(-0.08f * rf, base * rf)
                for (i in 0..5) {
                    val x = -0.08f + (i + 1) * 0.2f
                    val y = base + (artRng.next() - 0.5f) * 0.085f
                    path.quadTo((x - 0.10f) * rf, (base - 0.12f - artRng.next() * 0.08f) * rf, x * rf, y * rf)
                }
                path.lineTo(1.12f * rf, rf)
                path.lineTo(-0.08f * rf, rf)
                path.close()
                p.color = mountains[layer]
                c.drawPath(path, p)
                strokePath(Color.argb(35, 255, 245, 214), 0.003f)
            }
            path.reset()
            path.moveTo(0.59f * rf, 0.56f * rf)
            path.cubicTo(0.40f * rf, 0.70f * rf, 0.73f * rf, 0.78f * rf, 0.35f * rf, rf)
            path.lineTo(0.56f * rf, rf)
            path.cubicTo(0.79f * rf, 0.77f * rf, 0.44f * rf, 0.68f * rf, 0.59f * rf, 0.56f * rf)
            p.color = paper
            c.drawPath(path, p)
            for (i in 0..2) {
                val x = 0.22f + i * 0.067f
                val y = 0.29f - i * 0.023f
                path.reset()
                path.moveTo((x - 0.02f) * rf, y * rf)
                path.quadTo(x * rf, (y - 0.012f) * rf, (x + 0.01f) * rf, (y + 0.006f) * rf)
                path.quadTo((x + 0.022f) * rf, (y - 0.008f) * rf, (x + 0.04f) * rf, y * rf)
                strokePath(ink, 0.002f)
            }
            rect(0.84f, 0.61f, 0.92f, 0.72f, Color.rgb(174, 63, 47))
            for (i in 0..2) line(0.855f + i * 0.023f, 0.628f, 0.855f + i * 0.023f, 0.699f, paper, 0.004f)
            line(0.852f, 0.66f, 0.906f, 0.66f, paper, 0.004f)
        }
        9 -> {
            // 包豪斯：红圆、蓝色斜切块、黄色三角、黑色半圆与节奏短线。
            val paper = Color.rgb(237, 227, 207)
            val ink = Color.rgb(32, 35, 37)
            rect(0f, 0f, 1f, 1f, paper)
            disc(0.31f, 0.34f, 0.245f, Color.rgb(209, 67, 49))
            c.save()
            c.rotate(-12f + spec.ang / 30f, 0.77f * rf, 0.30f * rf)
            rect(0.61f, 0.11f, 1.04f, 0.47f, Color.rgb(36, 80, 156))
            c.restore()
            path.reset()
            path.moveTo(0.56f * rf, 0.25f * rf)
            path.lineTo(0.94f * rf, 0.69f * rf)
            path.lineTo(0.34f * rf, 0.69f * rf)
            path.close()
            p.color = Color.rgb(229, 174, 45)
            c.drawPath(path, p)
            p.color = ink
            c.drawArc(RectF(0.13f * rf, 0.16f * rf, 0.67f * rf, 0.70f * rf), 90f, 180f, true, p)
            for (i in 0..5) rect(0.065f, 0.58f + i * 0.023f, 0.32f, 0.588f + i * 0.023f, paper)
            for (i in 0..3) for (j in 0..2) disc(0.71f + i * 0.055f, 0.52f + j * 0.055f, 0.006f, ink)
            rect(0f, 0.74f, 1f, 1f, paper)
            rect(0.07f, 0.79f, 0.14f, 0.93f, Color.rgb(209, 67, 49))
            note("FORM / 09", 0.07f, 0.077f, ink)
            line(0.19f, 0.765f, 0.93f, 0.765f, ink)
        }
        10 -> {
            // 星图：细星、倾斜椭圆轨道、星座连线、行星与罗盘刻度。
            val sky = hsl(218f + h1 % 32f, 43f, 10f)
            val ice = Color.rgb(171, 205, 215)
            val gold = Color.rgb(229, 184, 114)
            rect(0f, 0f, 1f, 1f, sky)
            repeat(90) {
                val x = artRng.next()
                val y = artRng.next()
                val radius = 0.001f + artRng.next() * 0.002f
                disc(x, y, radius, ColorUtils.setAlphaComponent(ice, 65 + (artRng.next() * 170f).toInt()))
            }
            c.save()
            c.rotate(-28f + spec.ang * 0.10f, 0.53f * rf, 0.43f * rf)
            for (orbit in 0..3) {
                val rx = 0.18f + orbit * 0.085f
                val ry = 0.075f + orbit * 0.043f
                p.color = ColorUtils.setAlphaComponent(ice, 85 + orbit * 18)
                p.style = Paint.Style.STROKE
                p.strokeWidth = max(0.6f, 0.0015f * rf)
                c.drawOval(RectF((0.53f - rx) * rf, (0.43f - ry) * rf, (0.53f + rx) * rf, (0.43f + ry) * rf), p)
                p.style = Paint.Style.FILL
                val a = phase + orbit * 1.7f
                disc(0.53f + cos(a) * rx, 0.43f + sin(a) * ry, 0.009f + orbit * 0.002f, gold)
            }
            c.restore()
            var prevX = 0f
            var prevY = 0f
            for (i in 0..6) {
                val x = 0.13f + i * 0.116f
                val y = 0.39f + sin(i * 1.35f + phase) * 0.13f
                if (i > 0) line(prevX, prevY, x, y, ColorUtils.setAlphaComponent(ice, 155), 0.0015f)
                disc(x, y, if (i == 3) 0.009f else 0.004f, Color.rgb(228, 237, 231))
                if (i % 3 == 0) note("A-${i + 1}", x + 0.013f, y - 0.018f, ice, 0.018f)
                prevX = x
                prevY = y
            }
            for (i in 0 until 12) {
                val a = i * (Math.PI.toFloat() / 6f)
                line(0.85f + cos(a) * 0.04f, 0.13f + sin(a) * 0.04f, 0.85f + cos(a) * 0.047f, 0.13f + sin(a) * 0.047f, ice)
            }
            line(0.85f, 0.095f, 0.85f, 0.165f, gold)
            line(0.815f, 0.13f, 0.885f, 0.13f, gold)
            note("N", 0.842f, 0.071f, ice)
            note("CELESTIAL / 33", 0.07f, 0.72f, ice)
            line(0.40f, 0.765f, 0.60f, 0.765f, gold)
        }
        11 -> {
            // 等高地形：闭合扰动等高线、五级主曲线、峰值标记与比例尺。
            val paper = Color.rgb(223, 225, 200)
            val ink = Color.rgb(46, 68, 50)
            val cx = 0.45f + (spec.px - 50f) * 0.001f
            val cy = 0.38f + (spec.py - 50f) * 0.001f
            rect(0f, 0f, 1f, 1f, paper)
            c.save()
            c.clipRect(0f, 0f, rf, 0.74f * rf)
            for (level in 20 downTo 1) {
                val radius = 0.028f + level * 0.029f
                path.reset()
                for (i in 0..96) {
                    val a = i * (2f * Math.PI.toFloat() / 96f)
                    val r = radius * (1f + 0.13f * sin(a * 3f + phase) + 0.07f * cos(a * 5f - phase))
                    val x = (cx + cos(a) * r) * rf
                    val y = (cy + sin(a) * r * 0.81f) * rf
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.color = hsl(78f + h1 % 32f, 18f + (20 - level) * 0.5f, 44f + level * 1.8f)
                c.drawPath(path, p)
                strokePath(ColorUtils.setAlphaComponent(ink, if (level % 5 == 0) 190 else 95), if (level % 5 == 0) 0.0025f else 0.0012f)
            }
            // 填色结束后标注，避免被下一条等高带覆盖。
            for (level in 5..15 step 5) {
                val r = (0.028f + level * 0.029f) * (1f + 0.13f * sin(phase) + 0.07f * cos(phase))
                note("${(21 - level) * 40}", cx + r - 0.034f, cy - 0.009f, ink, 0.021f)
            }
            line(cx - 0.012f, cy, cx + 0.012f, cy, ink)
            line(cx, cy - 0.012f, cx, cy + 0.012f, ink)
            note("SUMMIT", cx - 0.037f, cy + 0.04f, ink, 0.018f)
            c.restore()
            for (i in 0..3) rect(0.07f + i * 0.04f, 0.765f, 0.11f + i * 0.04f, 0.772f, if (i % 2 == 0) ink else Color.rgb(170, 182, 151))
            note("1 km", 0.24f, 0.776f, ink, 0.018f)
            line(0.90f, 0.10f, 0.90f, 0.19f, ink)
            line(0.88f, 0.12f, 0.90f, 0.10f, ink)
            line(0.92f, 0.12f, 0.90f, 0.10f, ink)
            note("N", 0.892f, 0.087f, ink)
        }
        12 -> {
            // 霓虹线网：透视网格、分段信号环与波形；彩色线条不是釉面反光。
            val cyan = Color.rgb(67, 223, 217)
            val pink = Color.rgb(240, 85, 167)
            fill(cssLinear(175f, intArrayOf(Color.rgb(16, 12, 37), Color.rgb(4, 16, 29)), null, Shader.TileMode.CLAMP))
            val vx = 0.50f + (spec.px - 50f) * 0.0015f
            val horizon = 0.49f
            for (i in -8..16) line(vx, horizon, i / 8f, 1f, ColorUtils.setAlphaComponent(cyan, 130), 0.0015f)
            for (i in 1..12) {
                val t = i / 12f
                val y = horizon + (1f - horizon) * t * t
                line(0f, y, 1f, y, ColorUtils.setAlphaComponent(cyan, 140), 0.0015f)
            }
            for (i in 0..5) {
                line(0f, i * 0.095f, vx, horizon, ColorUtils.setAlphaComponent(pink, 65), 0.0012f)
                line(1f, i * 0.095f, vx, horizon, ColorUtils.setAlphaComponent(pink, 65), 0.0012f)
            }
            val orbit = RectF((vx - 0.20f) * rf, 0.15f * rf, (vx + 0.20f) * rf, 0.55f * rf)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(0.8f, 0.006f * rf)
            for (i in 0..7) {
                p.color = if (i % 2 == 0) cyan else pink
                c.drawArc(orbit, spec.ang + i * 45f, 31f, false, p)
            }
            p.style = Paint.Style.FILL
            path.reset()
            for (i in 0..64) {
                val t = i / 64f
                val x = vx - 0.26f + t * 0.52f
                val y = 0.35f + sin(t * 28f + phase) * sin(t * Math.PI.toFloat()) * 0.047f
                if (i == 0) path.moveTo(x * rf, y * rf) else path.lineTo(x * rf, y * rf)
            }
            strokePath(ColorUtils.setAlphaComponent(pink, 38), 0.012f)
            strokePath(pink, 0.003f)
            disc(vx, horizon, 0.008f, cyan)
            note("SIGNAL / 12", 0.07f, 0.09f, cyan)
            note("FM  /  STEREO", 0.67f, 0.69f, pink, 0.019f)
        }
        13 -> {
            // 日落海浪：暖色条纹太阳、六层正弦浪线、碎金反射与飞鸟。
            fill(cssLinear(180f, intArrayOf(Color.rgb(213, 119, 131), Color.rgb(242, 177, 139), Color.rgb(83, 84, 132)), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP))
            val sunX = 0.60f + (spec.px - 50f) * 0.001f
            val sun = Color.rgb(255, 216, 139)
            // 圆形本身以 AA 绘制；透明条纹露出天空，不额外叠白色高光。
            p.shader = LinearGradient(0f, 0f, 0f, 0.036f * rf, intArrayOf(sun, sun, Color.TRANSPARENT, Color.TRANSPARENT), floatArrayOf(0f, 0.73f, 0.73f, 1f), Shader.TileMode.REPEAT)
            c.drawCircle(sunX * rf, 0.35f * rf, 0.215f * rf, p)
            p.shader = null
            c.save()
            c.clipRect(0f, 0f, rf, 0.35f * rf)
            disc(sunX, 0.35f, 0.215f, sun)
            c.restore()
            rect(0f, 0.56f, 1f, 1f, Color.rgb(34, 65, 96))
            for (layer in 0..5) {
                path.reset()
                for (i in 0..48) {
                    val x = i / 48f
                    val y = 0.57f + layer * 0.078f + sin(x * 9f + phase + layer * 0.65f) * 0.018f + sin(x * 21f - layer) * 0.006f
                    if (i == 0) path.moveTo(x * rf, y * rf) else path.lineTo(x * rf, y * rf)
                }
                path.lineTo(rf, rf)
                path.lineTo(0f, rf)
                path.close()
                p.color = hsl(198f + layer * 5f, 41f, 39f - layer * 3.8f)
                c.drawPath(path, p)
                strokePath(Color.argb(110, 144, 195, 200), 0.002f)
            }
            for (i in 0..21) {
                val t = i / 21f
                val x = sunX + (artRng.next() - 0.5f) * (0.03f + t * 0.16f)
                val w = (0.012f + artRng.next() * 0.025f) * (1f - t * 0.6f)
                val y = 0.58f + t * 0.19f
                line(x - w, y, x + w, y, ColorUtils.setAlphaComponent(sun, (150f - t * 95f).toInt()), 0.0025f)
            }
            for (i in 0..2) {
                val x = 0.16f + i * 0.065f
                val y = 0.26f - i * 0.028f
                line(x - 0.016f, y, x, y + 0.007f, Color.rgb(70, 58, 85))
                line(x, y + 0.007f, x + 0.020f, y - 0.003f, Color.rgb(70, 58, 85))
            }
            note("SIDE A / SUNSET", 0.07f, 0.09f, Color.rgb(86, 54, 72))
        }
        14 -> {
            // 欧普棋盘：共享曲边采样的扭曲方格，叠加朱红圆环，避免直格子同质化。
            val paper = Color.rgb(244, 238, 221)
            val ink = Color.rgb(29, 34, 43)
            val red = Color.rgb(226, 73, 47)
            rect(0f, 0f, 1f, 1f, paper)
            val pi = Math.PI.toFloat()
            fun gridPoint(u: Float, v: Float, move: Boolean = false) {
                val x = u + 0.035f * sin(v * 2f * pi + phase) * sin(u * pi)
                val y = v + 0.060f * sin(u * 2f * pi + phase) * sin(v * pi)
                if (move) path.moveTo(x * rf, y * rf) else path.lineTo(x * rf, y * rf)
            }
            for (row in 0 until 12) for (col in 0 until 12) {
                if ((row + col) % 2 != 0) continue
                path.reset()
                gridPoint(col / 12f, row / 12f, true)
                for (s in 1..4) gridPoint((col + s / 4f) / 12f, row / 12f)
                for (s in 1..4) gridPoint((col + 1) / 12f, (row + s / 4f) / 12f)
                for (s in 1..4) gridPoint((col + 1 - s / 4f) / 12f, (row + 1) / 12f)
                for (s in 1..4) gridPoint(col / 12f, (row + 1 - s / 4f) / 12f)
                path.close()
                p.color = ink
                c.drawPath(path, p)
            }
            p.color = red
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.022f * rf
            c.drawCircle(0.52f * rf, 0.40f * rf, 0.215f * rf, p)
            p.style = Paint.Style.FILL
            rect(0.055f, 0.055f, 0.24f, 0.12f, paper)
            note("OP / 14", 0.075f, 0.098f, ink, 0.031f)
        }
        15 -> {
            // 极简印刷：大留白、双色错版拱门、半调网点、细框与印刷定位线。
            val paper = Color.rgb(241, 236, 221)
            val ink = Color.rgb(31, 52, 53)
            val vermilion = Color.rgb(204, 87, 61)
            rect(0f, 0f, 1f, 1f, paper)
            path.reset()
            path.moveTo(0.43f * rf, 0.63f * rf)
            path.lineTo(0.43f * rf, 0.37f * rf)
            path.cubicTo(0.43f * rf, 0.11f * rf, 0.80f * rf, 0.11f * rf, 0.80f * rf, 0.37f * rf)
            path.lineTo(0.80f * rf, 0.63f * rf)
            path.close()
            c.save()
            c.translate(0.025f * rf, -0.015f * rf)
            p.color = vermilion
            c.drawPath(path, p)
            c.restore()
            p.color = ink
            c.drawPath(path, p)
            path.reset()
            path.moveTo(0.52f * rf, 0.64f * rf)
            path.lineTo(0.52f * rf, 0.38f * rf)
            path.cubicTo(0.52f * rf, 0.23f * rf, 0.71f * rf, 0.23f * rf, 0.71f * rf, 0.38f * rf)
            path.lineTo(0.71f * rf, 0.64f * rf)
            path.close()
            p.color = paper
            c.drawPath(path, p)
            for (row in 0 until 12) for (col in 0 until 8) {
                disc(0.15f + col * 0.023f, 0.37f + row * 0.023f, 0.0015f + row * 0.00028f, ink)
            }
            p.color = ColorUtils.setAlphaComponent(ink, 125)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(0.6f, 0.0012f * rf)
            c.drawRect(0.06f * rf, 0.06f * rf, 0.94f * rf, 0.94f * rf, p)
            p.style = Paint.Style.FILL
            for (x in floatArrayOf(0.06f, 0.94f)) for (y in floatArrayOf(0.06f, 0.94f)) {
                line(x - 0.018f, y, x + 0.018f, y, ink)
                line(x, y - 0.018f, x, y + 0.018f, ink)
            }
            note("SONANCE / EDITION", 0.10f, 0.12f, ink, 0.021f)
            note("33 / STEREO", 0.10f, 0.69f, ink, 0.021f)
            line(0.10f, 0.85f, 0.90f, 0.85f, vermilion)
        }
    }

    // 只有纸质风格保留极轻的确定性纸粒；统一的静态高光已移除，gloss 交给主渲染层。
    if (style == 8 || style == 9 || style == 11 || style == 15) {
        repeat(160) {
            val x = artRng.next()
            val y = artRng.next()
            disc(x, y, 0.001f + artRng.next() * 0.0012f, Color.argb(9, 45, 36, 28))
        }
    }

    // 原型 0..7 保留 10% 粗标题 / 6.5% 歌手 / 7% 边距；新增风格仅调整自身排版。
    if (spec.label) {
        if (style == 12) {
            rect(0f, 0.78f, 1f, 1f, Color.rgb(6, 14, 27))
            line(0.07f, 0.78f, 0.35f, 0.78f, Color.rgb(67, 223, 217), 0.003f)
        } else if (style == 14) {
            rect(0f, 0.76f, 1f, 1f, Color.rgb(244, 238, 221))
            line(0.07f, 0.775f, 0.29f, 0.775f, Color.rgb(226, 73, 47), 0.005f)
        }
        val dark = style == 5 || style == 8 || style == 9 || style == 11 || style == 14 || style == 15
        val textColor = if (dark) Color.argb(224, 20, 20, 24) else Color.argb(240, 255, 255, 255)
        val left = (when (style) { 9 -> 0.19f; 15 -> 0.10f; else -> 0.07f }) * rf
        val maxW = (when (style) { 8 -> 0.50f; 9 -> 0.74f; 15 -> 0.80f; else -> 0.86f }) * rf
        val titleSize = (when (style) { 8 -> 0.09f; 12 -> 0.085f; 15 -> 0.095f; else -> 0.10f }) * rf
        val artistSize = (when (style) { 8 -> 0.052f; 11, 12, 15 -> 0.055f; 10, 13 -> 0.06f; else -> 0.065f }) * rf
        val artistBaseline = (when (style) { 5 -> 0.21f; 8 -> 0.205f; 15 -> 0.91f; else -> 0.93f }) * rf
        val titleBaseline = when (style) { 8 -> 0.13f * rf; 15 -> 0.81f * rf; else -> artistBaseline - artistSize * 1.35f }
        val centered = style == 10 || style == 13
        // 风格 5 巨字在左下，标题/歌手移到右上错开，避免与首字重叠。
        val right = style == 5
        val x = when { centered -> 0.5f * rf; right -> 0.93f * rf; else -> left }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = textColor
        tp.typeface = when (style) {
            8, 15 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            12 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.DEFAULT_BOLD
        }
        tp.textAlign = when { centered -> Paint.Align.CENTER; right -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        tp.textSize = titleSize
        if (!dark) tp.setShadowLayer(0.02f * rf, 0f, 0.01f * rf, Color.argb(90, 0, 0, 0))
        val title = TextUtils.ellipsize(spec.title, tp, maxW, TextUtils.TruncateAt.END).toString()
        c.drawText(title, x, titleBaseline, tp)
        tp.typeface = if (style == 10 || style == 11 || style == 12 || style == 15) Typeface.MONOSPACE else Typeface.DEFAULT
        tp.textSize = artistSize
        tp.clearShadowLayer()
        if (style >= 8) tp.color = ColorUtils.setAlphaComponent(textColor, if (dark) 185 else 205)
        val artist = TextUtils.ellipsize(spec.artist, tp, maxW, TextUtils.TruncateAt.END).toString()
        c.drawText(artist, x, artistBaseline, tp)
    }

    // 必须让透明角也参与 DST_IN：直接 drawRoundRect 只处理圆角内部，角外旧像素不会消失。
    // 整张透明 mask 位图一次合成；AA 覆盖率保留半透明边缘，角外 alpha = 0。
    val radius = if (cornerRes.isFinite()) (cornerRes * (rf / res)).coerceIn(0f, rf / 2f) else 0f
    if (radius > 0f) {
        val maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val maskCanvas = Canvas(maskBitmap)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.color = Color.WHITE
            maskCanvas.drawRoundRect(RectF(0f, 0f, rf, rf), radius, radius, maskPaint)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            c.drawBitmap(maskBitmap, 0f, 0f, maskPaint)
        } finally {
            // 软件 Canvas 已同步消费 mask；临时内存最多 512² × 4 字节，不进入封面缓存。
            maskBitmap.recycle()
        }
    }
    return bmp
}

// ===== 布局：单元网格 + 区块模板（与原型 TPL 一致） =====

internal class WallTile(
    val album: WallAlbum,
    val spec: CoverSpec,
    val x: Float,
    val y: Float,
    val size: Float,
    val span: Int
) {
    // 按压弹簧状态
    var ps = 1f; var psv = 0f; var pst = 1f
    var rx = 0f; var rxv = 0f; var rxt = 0f
    var ry = 0f; var ryv = 0f; var ryt = 0f
    var pressGx = 0f; var pressGy = 0f
    var active = false
}

internal class WallLayout(
    val tiles: List<WallTile>,
    val blockEdges: FloatArray,
    val contentH: Float,
    val maxY: Float
)

private class Tpl(val id: Char, val w: Float, val h: Float, val big: Boolean, val s: Array<IntArray>)

private val TPL = arrayOf(
    Tpl('A', 3f, 4f, true, arrayOf(intArrayOf(0, 0, 4), intArrayOf(4, 0, 2), intArrayOf(4, 2, 2))),
    Tpl('B', 3f, 4f, true, arrayOf(intArrayOf(2, 0, 4), intArrayOf(0, 0, 2), intArrayOf(0, 2, 2))),
    Tpl('C', 3f, 2f, false, arrayOf(intArrayOf(0, 0, 2), intArrayOf(2, 0, 2), intArrayOf(4, 0, 2))),
    Tpl('D', 2.2f, 3f, false, arrayOf(intArrayOf(0, 0, 3), intArrayOf(3, 0, 3))),
    Tpl('E', 0.35f, 6f, true, arrayOf(intArrayOf(0, 0, 6))),
    Tpl('G', 1f, 6f, true, arrayOf(intArrayOf(2, 2, 4), intArrayOf(0, 0, 2), intArrayOf(2, 0, 2), intArrayOf(4, 0, 2), intArrayOf(0, 2, 2), intArrayOf(0, 4, 2))),
    Tpl('I', 1f, 6f, true, arrayOf(intArrayOf(2, 0, 4), intArrayOf(0, 0, 2), intArrayOf(0, 2, 2), intArrayOf(0, 4, 2), intArrayOf(2, 4, 2), intArrayOf(4, 4, 2))),
    Tpl('J', 1f, 6f, true, arrayOf(intArrayOf(0, 0, 4), intArrayOf(4, 0, 2), intArrayOf(4, 2, 2), intArrayOf(4, 4, 2), intArrayOf(0, 4, 2), intArrayOf(2, 4, 2)))
)

/** 在 CSS 像素空间铺墙（widthCss / heightCss / topInsetCss 均为 CSS px）。 */
internal fun buildWallLayout(
    albums: List<WallAlbum>,
    widthCss: Float,
    heightCss: Float,
    gapCss: Float,
    topInsetCss: Float,
    seed: Int
): WallLayout {
    val pad = 10f
    val gap = gapCss
    val top = (56f + topInsetCss).roundToInt().toFloat()
    val bottom = 150f
    val unit = (widthCss - pad * 2f + gap) / 6f
    val r = WallRng(seed)
    fun shuffle(list: MutableList<WallAlbum>): MutableList<WallAlbum> {
        for (i in list.size - 1 downTo 1) {
            val j = floor(r.next() * (i + 1)).toInt()
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        }
        return list
    }
    val fav = shuffle(albums.filter { it.fav }.toMutableList())
    val nor = shuffle(albums.filter { !it.fav }.toMutableList())
    fun take(big: Boolean): WallAlbum? =
        if (big) (fav.removeFirstOrNull() ?: nor.removeFirstOrNull())
        else (nor.removeFirstOrNull() ?: fav.removeFirstOrNull())

    val tiles = ArrayList<WallTile>()
    val edges = ArrayList<Float>()
    var y = top
    var last: Tpl? = null
    var lastE = -99
    var bi = 0
    val specs = HashMap<String, CoverSpec>()
    while (fav.size + nor.size > 0) {
        val remain = fav.size + nor.size
        var c = TPL.filter { it.s.size <= remain && (last == null || it.id != last!!.id) }
        if (last != null && last!!.big) c = c.filter { it.id != 'E' }
        if (bi - lastE < 5) c = c.filter { it.id != 'E' }
        if (c.isEmpty()) c = TPL.filter { it.s.size <= remain }
        if (c.isEmpty()) break
        val tot = c.sumOf { it.w.toDouble() }.toFloat()
        var x = r.next() * tot
        var tp = c[0]
        for (t in c) { x -= t.w; if (x <= 0f) { tp = t; break } }
        edges.add(y)
        for (cell in tp.s) {
            val a = take(cell[2] >= 4) ?: break
            val spec = specs.getOrPut(a.key) { decorateCover(a.name, a.artist) }
            tiles.add(WallTile(a, spec, pad + cell[0] * unit, y + cell[1] * unit, cell[2] * unit - gap, cell[2]))
        }
        y += tp.h * unit
        if (tp.id == 'E') lastE = bi
        last = tp
        bi++
    }
    edges.add(y)
    val contentH = y - gap + bottom
    val maxY = max(0f, contentH - heightCss)
    return WallLayout(tiles, edges.toFloatArray(), contentH, maxY)
}

// ===== 调参（snapshot 支撑，滑杆即时生效） =====

internal class WallTune {
    var maxAngle by mutableStateOf(28f)
    var rest by mutableStateOf(0.22f)
    var vmax by mutableStateOf(4500f)
    var radius by mutableStateOf(650f)
    var springK by mutableStateOf(90f)
    var zeta by mutableStateOf(0.42f)
    var friction by mutableStateOf(2.6f)
    var pressTilt by mutableStateOf(9f)
    var pressScale by mutableStateOf(0.95f)
    var rippleAmp by mutableStateOf(0.05f)
    var rippleSpeed by mutableStateOf(1400f)
    var gap by mutableStateOf(7f)
    var gyroDeg by mutableStateOf(4f)
    var parallax by mutableStateOf(true)
    var drift by mutableStateOf(true)
    var haptics by mutableStateOf("light")
    var vinyl by mutableStateOf(true)
    var autoPlay by mutableStateOf(true)
    var seed by mutableStateOf(7)
}

// ===== 物理引擎 =====

internal class ScrollAnim(val from: Float, val to: Float, val t0: Long, val dur: Float, val done: () -> Unit)
internal class Ripple(val x: Float, val y: Float, val t0: Long, val amp: Float)
internal class Intro(val ox: Float, val oy: Float, val t0: Long)

internal class WallEngine(val tune: WallTune) {
    var scrollY = 0f
    var vel = 0f
    var dragging = false
    var userMotion = false
    var maxY = 0f
    var heightCss = 1f
    var widthCss = 1f

    var curve = 0.22f
    var curveV = 0f
    var forceCurve = 0f
    var tilt = 0f
    var gx = 0f; var gy = 0f; var gxT = 0f; var gyT = 0f
    var gxR = 0f; var gyR = 0f
    var dim = 0f; var dimT = 0f
    var dimExcept: WallTile? = null

    var anim: ScrollAnim? = null
    val ripples = ArrayList<Ripple>()
    var intro: Intro? = null
    var edgeIdx = -1
    var lastInput = 0L
    var fabLastScroll = 0L
    // 输入侧在 down / 实际拖动采样时更新，停顿只衰减光影速度，不改手指控制的位置。
    var lastDragTime = 0L
    var driftDir = 1
    var dirty = true

    // 长按 / 按压
    var pressing: WallTile? = null
    var pressStart = 0L
    var pressMoved = false
    var longFired = false

    var current: WallTile? = null
    var playerOpen = false
    var obscured = false
    var busy by mutableStateOf(false)
    var lockPending = false

    private var lastScrollHaptic = 0L
    private var hasScrollHaptic = false

    val activeTiles = ArrayList<WallTile>()

    // 上一帧渲染值（用于「确有变化才重绘」判定）
    private var rY = Float.NaN
    private var rGX = Float.NaN
    private var rGY = Float.NaN
    private var rC = Float.NaN
    private var rTilt = Float.NaN
    private var rDim = Float.NaN

    fun markInput(nowMs: Long) { lastInput = nowMs; dirty = true }

    // 半隐式 Euler：先更新 v，再由调用侧 x += v * dt；保持原运算顺序，不分配结果数组。
    private fun springVelocity(x: Float, v: Float, target: Float, k: Float, z: Float, dt: Float): Float {
        val cc = 2f * sqrt(k) * z
        val a = k * (target - x) - cc * v
        return v + a * dt
    }

    private fun outOfBounds(): Boolean = scrollY < 0f || scrollY > maxY

    private fun canDrift(): Boolean =
        tune.drift && maxY > 0f && !busy && !playerOpen && !obscured &&
            !dragging && pressing == null && anim == null && !lockPending && vel == 0f && !outOfBounds()

    fun driftCond(nowMs: Long): Boolean = canDrift() && nowMs - lastInput >= 12_000L

    /** 仅等待漂移的 12 秒门槛时返回剩余毫秒；不可漂移或已到期返回 null（到期由 active 接管）。 */
    fun idleDelay(now: Long): Long? {
        if (!canDrift()) return null
        val elapsed = (now - lastInput).coerceAtLeast(0L)
        return if (elapsed < 12_000L) 12_000L - elapsed else null
    }

    // 滚动（自动 / 手动）时不再随速度追加弯曲与倾斜动效：弯曲恒为静止弯曲度，
    // 仅保留聚焦展开时刻意设置的 forceCurve；倾斜完全去除。
    private fun curveTarget(): Float {
        var target = tune.rest
        if (forceCurve > 0f) target = max(target, forceCurve)
        return target.coerceIn(-0.3f, 1.25f)
    }

    private fun tiltTarget(): Float = 0f

    fun active(nowMs: Long): Boolean {
        val tx = if (tune.parallax) gxT else 0f
        val ty = if (tune.parallax) gyT else 0f
        // step 将小残差归到精确目标；既不会在弹簧极值误停，也不会永久追逐浮点尾数。
        // busy / lockPending 的外部异步任务自行 wake，不应单独占用帧循环。
        return dirty || dragging || anim != null || vel != 0f || outOfBounds() ||
            activeTiles.isNotEmpty() || ripples.isNotEmpty() || intro != null ||
            curveV != 0f || curve != curveTarget() || dim != dimT || tilt != tiltTarget() ||
            gx != tx || gy != ty || driftCond(nowMs) || pressing != null
    }

    /**
     * 推进一帧。返回是否需要重绘。haptic 与滚动节拍同帧触发（无桥、无 post）。
     * nowMs / dt 单位：毫秒 / 秒。
     */
    fun step(nowMs: Long, dt: Float, haptic: (Int, String, Int) -> Unit): Boolean {
        val minY = 0f
        var settled = false
        // 用帧开始时的状态保留最终 settle / 消失帧，不能只看更新后的非空列表。
        val hadActiveTiles = activeTiles.isNotEmpty()
        val hadRipples = ripples.isNotEmpty()
        val hadIntro = intro != null
        // ---- 滚动 ----
        val a = anim
        if (a != null) {
            // 只把 Long 差值转 Float，绝对 uptime 始终保持整数精度。
            val pr = if (a.dur <= 0f) 1f else ((nowMs - a.t0).toFloat() / a.dur).coerceIn(0f, 1f)
            val e = 1f - (1f - pr).let { it * it * it * it }
            val ny = a.from + (a.to - a.from) * e
            vel = if (dt > 0f) (ny - scrollY) / dt else 0f
            scrollY = ny
            if (pr >= 1f) { anim = null; vel = 0f; userMotion = false; settled = true; a.done() }
        } else if (dragging) {
            val pausedMs = nowMs - lastDragTime - 80L
            if (pausedMs > 0L) {
                val decayDt = min(dt, pausedMs.toFloat() / 1000f)
                vel *= exp(-decayDt * 12f)
                if (abs(vel) < 6f) vel = 0f
            }
        } else {
            if (outOfBounds()) {
                val b = if (scrollY < minY) minY else maxY
                if ((scrollY - b) * vel > 0f) vel *= exp(-dt * 16f)
                val n = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
                val h = dt / n
                repeat(n) {
                    vel = springVelocity(scrollY, vel, b, 170f, 1f, h)
                    scrollY += vel * h
                }
                if (abs(scrollY - b) < 0.4f && abs(vel) < 12f) {
                    scrollY = b; vel = 0f; userMotion = false; settled = true
                }
            } else {
                scrollY += vel * dt
                vel *= exp(-tune.friction * dt)
                if (abs(vel) < 6f) { vel = 0f; userMotion = false }
                // 仅本帧从界内跨入界外触发一次；后续全部走回弹分支，不重复 L3。
                // 手指拖动的越界触觉仍由输入侧负责，释放后不会补发。
                if (outOfBounds() && abs(vel) > 200f) haptic(3, "撞到边缘", 0)
            }
            if (driftCond(nowMs)) {
                scrollY += driftDir * 9f * dt
                if (scrollY > maxY) { scrollY = maxY; driftDir = -1 }
                if (scrollY < minY) { scrollY = minY; driftDir = 1 }
            }
        }

        // ---- 弯曲 / 倾斜 / 暗化 ----
        val target = curveTarget()
        run {
            val n = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
            val h = dt / n
            repeat(n) {
                curveV = springVelocity(curve, curveV, target, tune.springK, tune.zeta, h)
                curve += curveV * h
            }
        }
        curve = curve.coerceIn(-0.3f, 1.25f)
        if (abs(curve - target) < 0.0005f && abs(curveV) < 0.002f) {
            if (curve != target || curveV != 0f) settled = true
            curve = target; curveV = 0f
        }
        val tiltT = tiltTarget()
        tilt += (tiltT - tilt) * (1f - exp(-dt * 12f))
        if (abs(tilt - tiltT) < 0.02f) {
            if (tilt != tiltT) settled = true
            tilt = tiltT
        }
        dim += (dimT - dim) * (1f - exp(-dt * 10f))
        if (abs(dim - dimT) < 0.002f) {
            if (dim != dimT) settled = true
            dim = dimT
        }

        // ---- 陀螺仪视差 ----
        val tx = if (tune.parallax) gxT else 0f
        val ty = if (tune.parallax) gyT else 0f
        val k = 1f - exp(-dt * 6f)
        gx += (tx - gx) * k
        gy += (ty - gy) * k
        if (abs(gx - tx) < 0.004f) gx = tx
        if (abs(gy - ty) < 0.004f) gy = ty
        gxR = (gx * 100f).roundToInt() / 100f
        gyR = (gy * 100f).roundToInt() / 100f

        // ---- 按压弹簧 ----
        if (activeTiles.isNotEmpty()) {
            val n = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
            val h = dt / n
            val it = activeTiles.iterator()
            while (it.hasNext()) {
                val t = it.next()
                repeat(n) {
                    t.psv = springVelocity(t.ps, t.psv, t.pst, 420f, 0.5f, h); t.ps += t.psv * h
                    t.rxv = springVelocity(t.rx, t.rxv, t.rxt, 300f, 0.55f, h); t.rx += t.rxv * h
                    t.ryv = springVelocity(t.ry, t.ryv, t.ryt, 300f, 0.55f, h); t.ry += t.ryv * h
                }
                if (abs(t.ps - t.pst) < 0.0005f && abs(t.psv) < 0.002f &&
                    abs(t.rx - t.rxt) < 0.02f && abs(t.ry - t.ryt) < 0.02f &&
                    abs(t.rxv) < 0.05f && abs(t.ryv) < 0.05f
                ) {
                    t.ps = t.pst; t.rx = t.rxt; t.ry = t.ryt
                    t.psv = 0f; t.rxv = 0f; t.ryv = 0f
                    t.active = false; it.remove()
                }
            }
        }

        // ---- 波纹 / 入场 ----
        if (ripples.isNotEmpty()) ripples.removeAll { nowMs - it.t0 > 2600L }
        val introSnapshot = intro
        if (introSnapshot != null && (nowMs - introSnapshot.t0).toFloat() > 440f + hypot(widthCss, heightCss + 300f) * 0.38f) intro = null

        // ---- 长按判定（与帧循环同源，计时不靠手势 await） ----
        val pt = pressing
        if (pt != null && !pressMoved && !longFired && nowMs - pressStart > 450L) {
            longFired = true
        }

        // ---- 滚动节拍震动（同帧） ----
        val c = scrollY + heightCss / 2f
        val edges = blockEdges
        var lo = 0; var hi = edges.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (edges[m] <= c) lo = m + 1 else hi = m }
        if (lo != edgeIdx) {
            val sp = abs(vel)
            if (edgeIdx >= 0 && (dragging || userMotion || anim != null) && sp > 40f && sp < 6000f && !busy &&
                (!hasScrollHaptic || nowMs - lastScrollHaptic >= 35L)
            ) {
                val dur = lerp(15f, 6f, sp / 6000f).roundToInt()
                lastScrollHaptic = nowMs; hasScrollHaptic = true
                haptic(1, if (anim != null) "滚筒" else "滚动", dur)
            }
            // 无论是否被限频，都消费本帧最新 edge；一次跨多格只响一次，不排队补播。
            edgeIdx = lo
        }

        // ---- 是否需要重绘 ----
        val need = abs(scrollY - rY) > 0.05f || gxR != rGX || gyR != rGY ||
            abs(curve - rC) > 0.0005f || abs(tilt - rTilt) > 0.02f || abs(dim - rDim) > 0.002f ||
            hadActiveTiles || hadRipples || hadIntro || activeTiles.isNotEmpty() || ripples.isNotEmpty() ||
            intro != null || settled || dirty
        if (need) {
            rY = scrollY; rGX = gxR; rGY = gyR; rC = curve; rTilt = tilt; rDim = dim; dirty = false
        }
        return need
    }

    var blockEdges: FloatArray = FloatArray(0)

    fun addRipple(t: WallTile, amp: Float, nowMs: Long) {
        ripples.add(Ripple(t.x + t.size / 2f, t.y + t.size / 2f, nowMs, amp))
    }

    fun pressTile(t: WallTile, lx: Float, ly: Float) {
        t.rxt = -ly * tune.pressTilt
        t.ryt = lx * tune.pressTilt
        t.pst = tune.pressScale
        t.pressGx = lx * 0.8f
        t.pressGy = ly * 0.8f
        if (!t.active) { t.active = true; activeTiles.add(t) }
    }

    fun releaseTile(t: WallTile) {
        t.rxt = 0f; t.ryt = 0f; t.pst = 1f; t.pressGx = 0f; t.pressGy = 0f
        if (!t.active) { t.active = true; activeTiles.add(t) }
    }
}

internal fun clamp(v: Float, a: Float, b: Float): Float = if (v < a) a else if (v > b) b else v
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
internal fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = clamp((x - e0) / (e1 - e0), 0f, 1f)
    return t * t * (3f - 2f * t)
}
internal fun easeOutCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }
internal fun easeOutBack(t: Float): Float {
    val c1 = 1.5f; val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).let { it * it * it } + c1 * (t - 1f).let { it * it }
}
internal fun rubber(x: Float, d: Float): Float = (1f - 1f / (x * 0.55f / d + 1f)) * d
