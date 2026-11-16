package com.sonance.player.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「自定义封面 → 内置封面」的几套现成图。
 *
 * 视觉语言直接取自封面墙里那种「渐变底 + 几何母题」的占位封面：同心圆、条形、
 * 斜带、涟漪、点阵、放射。这里不重新画插画，只是把封面墙那套母题固定成
 * 8 组配色/构图，点一下就能落到歌曲上，和封面墙里的卡片摆在一起不会打架。
 */
object BuiltinCovers {

    data class Preset(val id: String, val label: String, val colors: List<Int>, val motif: Int)

    val presets: List<Preset> = listOf(
        Preset("aurora", "极光", listOf(0xFF1B2A41.toInt(), 0xFF2E6E7E.toInt(), 0xFF7FD8BE.toInt()), 3),
        Preset("sunset", "落日", listOf(0xFF3B1E3C.toInt(), 0xFFB14A5E.toInt(), 0xFFF2A65A.toInt()), 5),
        Preset("deepsea", "深海", listOf(0xFF0A1B33.toInt(), 0xFF17456B.toInt(), 0xFF3D8FB3.toInt()), 0),
        Preset("mint", "薄荷", listOf(0xFF123B36.toInt(), 0xFF2E7D6B.toInt(), 0xFFB7E4C7.toInt()), 4),
        Preset("neon", "霓虹", listOf(0xFF241046.toInt(), 0xFF6C2EB9.toInt(), 0xFFF06AB5.toInt()), 2),
        Preset("morning", "晨雾", listOf(0xFF5B4B6E.toInt(), 0xFFC48C9B.toInt(), 0xFFF4D8AE.toInt()), 1),
        Preset("forest", "森野", listOf(0xFF102A21.toInt(), 0xFF2F5D42.toInt(), 0xFF9BCB7A.toInt()), 0),
        Preset("starlit", "星轨", listOf(0xFF090F26.toInt(), 0xFF27356B.toInt(), 0xFF8AA8F0.toInt()), 3)
    )

    fun find(id: String?): Preset? = presets.firstOrNull { it.id == id }

    /** 渲染成正方形位图，交给调用方压缩后写入手选封面缓存。 */
    fun render(preset: Preset, sizePx: Int): Bitmap {
        val s = sizePx.coerceIn(128, 1024)
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, s.toFloat(), s.toFloat(),
            preset.colors.toIntArray(), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, s.toFloat(), s.toFloat(), paint)
        paint.shader = null

        val ink = 0xFFFFFFFF.toInt()
        paint.color = ink
        when (preset.motif) {
            0 -> drawConcentric(canvas, paint, s)
            1 -> drawBars(canvas, paint, s)
            2 -> drawBands(canvas, paint, s)
            3 -> drawRipple(canvas, paint, s)
            4 -> drawDots(canvas, paint, s)
            else -> drawSunburst(canvas, paint, s)
        }

        // 封面墙那条细内圈 + 中心小圆点：让内置封面一眼看出是同一套视觉
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.008f
        paint.color = 0x33FFFFFF
        canvas.drawCircle(s / 2f, s / 2f, s * 0.44f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(s / 2f, s / 2f, s * 0.028f, paint)
        return bitmap
    }

    private fun drawConcentric(c: Canvas, p: Paint, s: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.012f
        for (i in 1..5) {
            p.alpha = (26 + i * 12).coerceAtMost(255)
            c.drawCircle(s * 0.5f, s * 0.52f, s * 0.09f * i, p)
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun drawBars(c: Canvas, p: Paint, s: Int) {
        val n = 9
        val gap = s * 0.038f
        val w = (s - gap * (n + 1)) / n
        for (i in 0 until n) {
            val t = (i + 1).toFloat() / n
            val h = s * (0.14f + 0.42f * kotlin.math.sin(t * 2.2f))
            p.alpha = (32 + (t * 90).toInt()).coerceAtMost(255)
            c.drawRoundRect(
                gap + i * (w + gap), s * 0.82f - h,
                gap + i * (w + gap) + w, s * 0.82f,
                w * 0.5f, w * 0.5f, p
            )
        }
        p.alpha = 255
    }

    private fun drawBands(c: Canvas, p: Paint, s: Int) {
        p.strokeWidth = s * 0.06f
        for (i in -6..10) {
            p.alpha = if (i % 2 == 0) 34 else 16
            val x = i * s * 0.14f
            c.drawLine(x, s.toFloat(), x + s * 0.9f, 0f, p)
        }
        p.alpha = 255
    }

    private fun drawRipple(c: Canvas, p: Paint, s: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.016f
        repeat(6) { i ->
            p.alpha = (90 - i * 12).coerceAtLeast(12)
            c.drawArc(
                s * 0.12f + i * s * 0.02f, s * 0.18f + i * s * 0.05f,
                s * (0.76f - i * 0.04f), s * (0.76f - i * 0.04f),
                200f, 140f, false, p
            )
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun drawDots(c: Canvas, p: Paint, s: Int) {
        val n = 8
        for (row in 0 until n) {
            for (col in 0 until n) {
                val d = kotlin.math.hypot(row - n / 2f, col - n / 2f)
                p.alpha = (12 + (40 * (1f - d / n)).toInt()).coerceAtLeast(8)
                val r = s * (0.006f + 0.004f * (1f - d / n))
                c.drawCircle((col + 0.5f) * s / n, (row + 0.5f) * s / n, r, p)
            }
        }
        p.alpha = 255
    }

    private fun drawSunburst(c: Canvas, p: Paint, s: Int) {
        val cx = s * 0.5f
        val cy = s * 0.88f
        repeat(14) { i ->
            val a = (Math.PI * (i + 0.5) / 14).toFloat()
            p.alpha = 14 + (i % 3) * 12
            c.drawLine(cx, cy, cx + cos(a) * s * 1.2f, cy - sin(a) * s * 1.2f, p)
        }
        p.alpha = 255
    }
}
