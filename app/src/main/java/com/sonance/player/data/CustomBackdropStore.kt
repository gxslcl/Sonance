package com.sonance.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图本地持久化管理（支持「列表/主界面」与「播放主界面」两块独立背景）。
 */
internal object CustomBackdropStore {

    enum class Slot(val fileName: String) {
        SHELL("shell_bg.jpg"),
        PLAYER("player_bg.jpg")
    }

    private const val DIR_NAME = "custom_backdrop"
    private const val MAX_EDGE_PX = 1920
    private const val THUMB_EDGE_PX = 360

    fun exists(context: Context, slot: Slot): Boolean =
        fileOf(context, slot).let { it.isFile && it.length() > 0L }

    fun save(context: Context, slot: Slot, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false

        var sample = 1
        val longest = maxOf(w, h)
        while (longest / sample > MAX_EDGE_PX * 2) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return false

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotated = applyExifOrientation(decoded, orientation)
        val fitted = fitMaxEdge(rotated, MAX_EDGE_PX)

        val dst = fileOf(context, slot)
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "${slot.fileName}.tmp")
        FileOutputStream(tmp).use { out ->
            if (!fitted.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                return false
            }
        }
        if (fitted !== rotated && !fitted.isRecycled) fitted.recycle()
        if (rotated !== decoded && !rotated.isRecycled) rotated.recycle()
        if (!decoded.isRecycled) decoded.recycle()

        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        true
    }.onFailure {
        Log.w("SonanceBackdrop", "保存背景图失败(${slot.name}): ${it.message}")
    }.getOrDefault(false)

    fun loadBitmap(context: Context, slot: Slot): Bitmap? = runCatching {
        val f = fileOf(context, slot)
        if (!f.isFile || f.length() <= 0L) return@runCatching null
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    fun loadThumbnail(context: Context, slot: Slot): Bitmap? = runCatching {
        val f = fileOf(context, slot)
        if (!f.isFile || f.length() <= 0L) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        val max = maxOf(bounds.outWidth, bounds.outHeight)
        while (max / sample > THUMB_EDGE_PX * 2) sample *= 2
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    fun clear(context: Context, slot: Slot): Boolean = runCatching {
        val f = fileOf(context, slot)
        !f.exists() || f.delete()
    }.getOrDefault(false)

    /** Android 12 以下没有 RenderEffect 时的轻量快速柔化模糊 */
    fun softBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0 || src.isRecycled || src.width <= 0 || src.height <= 0) return src
        val targetW = (src.width / 4).coerceIn(48, 280)
        val targetH = (src.height * targetW / src.width.coerceAtLeast(1)).coerceIn(48, 420)
        val small = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val r = (radius / 2).coerceIn(1, 14)
        val w = small.width
        val h = small.height
        val pix = IntArray(w * h)
        small.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)

        repeat(2) {
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    var sumR = 0; var sumG = 0; var sumB = 0; var cnt = 0
                    val x0 = (x - r).coerceAtLeast(0)
                    val x1 = (x + r).coerceAtMost(w - 1)
                    for (kx in x0..x1) {
                        val c = pix[rowOff + kx]
                        sumR += (c ushr 16) and 0xFF
                        sumG += (c ushr 8) and 0xFF
                        sumB += c and 0xFF
                        cnt++
                    }
                    tmp[rowOff + x] = (0xFF shl 24) or
                        ((sumR / cnt) shl 16) or
                        ((sumG / cnt) shl 8) or
                        (sumB / cnt)
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var sumR = 0; var sumG = 0; var sumB = 0; var cnt = 0
                    val y0 = (y - r).coerceAtLeast(0)
                    val y1 = (y + r).coerceAtMost(h - 1)
                    for (ky in y0..y1) {
                        val c = tmp[ky * w + x]
                        sumR += (c ushr 16) and 0xFF
                        sumG += (c ushr 8) and 0xFF
                        sumB += c and 0xFF
                        cnt++
                    }
                    pix[y * w + x] = (0xFF shl 24) or
                        ((sumR / cnt) shl 16) or
                        ((sumG / cnt) shl 8) or
                        (sumB / cnt)
                }
            }
        }
        small.setPixels(pix, 0, w, 0, 0, w, h)
        return small
    }

    private fun fileOf(context: Context, slot: Slot): File =
        File(File(context.applicationContext.filesDir, DIR_NAME), slot.fileName)

    private fun fitMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest.toFloat()
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
