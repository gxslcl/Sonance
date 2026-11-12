package com.sonance.player.ui.wall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.sin

internal class WallPose {
    var x = 0f
    var y = 0f
    var size = 1f
    var rx = 0f
    var ry = 0f
    var z = 0f
    var scale = 1f
    var shade = 0f
    var alpha = 1f
    val matrix = Matrix()
    val inverse = Matrix()
    private val point = FloatArray(2)
    fun contains(px: Float, py: Float, vinyl: Float = 0f): Boolean {
        if (!matrix.invert(inverse)) return false
        point[0] = px; point[1] = py
        inverse.mapPoints(point)
        val left = x - size * 0.16f * vinyl
        return point[0] in left..(left + size) && point[1] in y..(y + size)
    }
    fun copyFrom(p: WallPose) {
        x = p.x; y = p.y; size = p.size; rx = p.rx; ry = p.ry; z = p.z
        scale = p.scale; shade = p.shade; alpha = p.alpha
        matrix.set(p.matrix)
    }
}

internal class WallCardPainter(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val shadowRect = RectF()
    private val path = Path()
    private val source = FloatArray(8)
    private val projected = FloatArray(8)
    private val shaderMatrix = Matrix()
    // 原版 .gloss i：300%×300% 元素上的 118° 线性渐变，一条斜向高光带（非原点高光）。
    // 方向 (sin118°, -cos118°)=(0.883,0.469)，渐变线长 3s·(|sin|+|cos|)≈4.057s，中心在卡片中心。
    private val gloss = LinearGradient(-1.291f, -0.452f, 2.291f, 1.452f,
        intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF),
        floatArrayOf(0.42f, 0.495f, 0.52f, 0.57f), Shader.TileMode.CLAMP)
    private val glaze = LinearGradient(0f, 0f, 1f, 1f,
        intArrayOf(0x22FFFFFF, 0x04FFFFFF, 0x00000000, 0x30000000),
        floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
    private val rim = LinearGradient(0f, 0f, 0.8f, 1f,
        intArrayOf(0x80FFFFFF.toInt(), 0x0AFFFFFF, 0x46000000), null, Shader.TileMode.CLAMP)
    private val vinylSheen = SweepGradient(0f, 0f,
        intArrayOf(0xFF101013.toInt(), 0xFF34343A.toInt(), 0xFF111114.toInt(), 0xFF26262D.toInt(), 0xFF101013.toInt()), null)
    private val shadow = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(12f, 0f, 4f, 0xD9000000.toInt())
        }
        Canvas(it).drawRoundRect(24f, 22f, 104f, 102f, 6f, 6f, p)
    }

    fun project(p: WallPose, rotateZ: Float = 0f) {
        val cx = p.x + p.size / 2f; val cy = p.y + p.size / 2f
        val ax = p.rx * 0.017453293f; val ay = p.ry * 0.017453293f
        val az = rotateZ * 0.017453293f
        val cosX = cos(ax); val sinX = sin(ax); val cosY = cos(ay); val sinY = sin(ay)
        val cosZ = cos(az); val sinZ = sin(az)
        val distance = 900f * density
        for (i in 0..3) {
            val x = if (i == 0 || i == 3) p.x else p.x + p.size
            val y = if (i < 2) p.y else p.y + p.size
            source[i * 2] = x; source[i * 2 + 1] = y
            val lx = (x - cx) * p.scale; val ly = (y - cy) * p.scale
            val x1 = lx * cosY + ly * sinX * sinY
            val y1 = ly * cosX
            val depth = -lx * sinY + ly * sinX * cosY + p.z
            val perspective = distance / (distance - depth).coerceAtLeast(distance * 0.15f)
            projected[i * 2] = cx + (x1 * cosZ - y1 * sinZ) * perspective
            projected[i * 2 + 1] = cy + (x1 * sinZ + y1 * cosZ) * perspective
        }
        p.matrix.setPolyToPoly(source, 0, projected, 0, 4)
    }

    fun draw(canvas: Canvas, bitmap: Bitmap?, spec: CoverSpec, pose: WallPose,
             corner: Float, gx: Float, gy: Float, glow: Float = 0f,
             elevation: Float = 1f, vinyl: Float = 0f, discAngle: Float = 0f) {
        if (pose.alpha <= 0f) return
        val a = (pose.alpha * 255).toInt().coerceIn(0, 255)
        val s = pose.size
        rect.set(pose.x, pose.y, pose.x + s, pose.y + s)
        canvas.save()
        canvas.concat(pose.matrix)
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.alpha = (a * 0.75f * elevation.coerceIn(0f, 1.5f)).toInt().coerceIn(0, 255)
        val margin = s * 0.3f
        shadowRect.set(rect.left - margin, rect.top - margin + 4f * density * elevation,
            rect.right + margin, rect.bottom + margin + 4f * density * elevation)
        canvas.drawBitmap(shadow, null, shadowRect, paint)
        if (vinyl > 0f) drawVinyl(canvas, spec, rect.centerX() + s * 0.26f * vinyl,
            rect.centerY(), s * 0.47f, discAngle, a)
        canvas.save()
        canvas.translate(-s * 0.16f * vinyl, 0f)
        path.reset(); path.addRoundRect(rect, corner, corner, Path.Direction.CW)
        canvas.clipPath(path)
        paint.shader = null; paint.color = spec.mainColor; paint.alpha = a
        if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, paint) else canvas.drawRect(rect, paint)
        shaderMatrix.setScale(s, s); shaderMatrix.postTranslate(rect.left, rect.top)
        glaze.setLocalMatrix(shaderMatrix); paint.shader = glaze; paint.alpha = a
        canvas.drawRect(rect, paint)
        // translate(gx*20%, gy*20%)：20% 以元素自身 3s 计 = 0.6s。
        shaderMatrix.setScale(s, s)
        shaderMatrix.postTranslate(rect.left + gx * 0.6f * s, rect.top + gy * 0.6f * s)
        gloss.setLocalMatrix(shaderMatrix); paint.shader = gloss
        canvas.drawRect(rect, paint)
        paint.shader = null; paint.color = Color.BLACK; paint.alpha = (pose.shade.coerceIn(0f, 1f) * a).toInt()
        canvas.drawRect(rect, paint)
        shaderMatrix.setScale(s, s); shaderMatrix.postTranslate(rect.left, rect.top)
        rim.setLocalMatrix(shaderMatrix); paint.shader = rim; paint.alpha = a
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.shader = null; paint.style = Paint.Style.FILL
        canvas.restore()
        if (glow > 0f) {
            paint.color = spec.mainColor; paint.style = Paint.Style.STROKE
            for (i in 3 downTo 1) {
                paint.alpha = (a * glow * (if (i == 1) 0.75f else 0.09f)).toInt().coerceIn(0, 255)
                paint.strokeWidth = density * i * 2f
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
            paint.style = Paint.Style.FILL
        }
        paint.alpha = 255
        canvas.restore()
    }

    private fun drawVinyl(canvas: Canvas, spec: CoverSpec, x: Float, y: Float, radius: Float, angle: Float, alpha: Int) {
        paint.style = Paint.Style.FILL
        shaderMatrix.setScale(radius, radius); shaderMatrix.postRotate(angle); shaderMatrix.postTranslate(x, y)
        vinylSheen.setLocalMatrix(shaderMatrix); paint.shader = vinylSheen; paint.alpha = alpha
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = density * 0.5f
        paint.color = 0xFF606067.toInt(); paint.alpha = alpha / 4
        for (i in 15..48) canvas.drawCircle(x, y, radius * i / 49f, paint)
        paint.style = Paint.Style.FILL; paint.color = spec.mainColor; paint.alpha = alpha
        canvas.drawCircle(x, y, radius * 0.28f, paint)
        canvas.save(); canvas.rotate(angle, x, y)
        paint.color = 0xD9FFFFFF.toInt(); paint.textAlign = Paint.Align.CENTER; paint.textSize = radius * 0.065f
        canvas.drawText("SONANCE • STEREO", x, y - radius * 0.11f, paint)
        canvas.drawText("SIDE A", x, y + radius * 0.16f, paint)
        canvas.restore()
        paint.color = 0xFF08080A.toInt(); canvas.drawCircle(x, y, radius * 0.043f, paint)
    }
}
