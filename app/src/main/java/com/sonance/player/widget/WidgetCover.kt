package com.sonance.player.widget

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import com.sonance.player.data.CoverCache
import com.sonance.player.data.isRemoteUrl

/**
 * STYLE A 桌面小组件封面加载与圆角方图渲染。
 *
 * 与旧版「厚发光霓虹彩胶圆环」不同，STYLE A 坚持「专辑封面是唯一视觉主角」：
 * - 按中心裁切并画成利落圆角方图（微弱环境投影 + 1px 玻璃细边）；
 * - 解码封面时顺手采样代表色，经 [WidgetAuraPalette] 克制压缩成低饱和莫兰迪氛围底。
 */
internal object WidgetCover {

    private const val TAG = "SonanceWidget"
    private const val SAMPLE_SIDE = 24

    data class CoverBundle(
        val bitmap: Bitmap?,
        val rawRgb: Int?
    )

    private data class CachedRgb(val rgb: Int?)

    private val bitmapCache = LruCache<String, Bitmap>(16)
    private val rgbCache = LruCache<String, CachedRgb>(24)

    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    fun clear() {
        bitmapCache.evictAll()
        rgbCache.evictAll()
    }

    /**
     * 仅供「无封面显示（如极简条 2×1 / 3×1）」或实时刷新读取封面代表色，
     * 不向桌面传大图，仅提取一次 RGB 后缓存在内存与 SharedPreferences 中。
     */
    fun extractRawRgb(context: Context, snap: WidgetState.Snapshot): Int? {
        if (!snap.hasSong) return null
        val sig = WidgetState.artworkSignature(snap)
        rgbCache.get(sig)?.let { return it.rgb }

        val (savedHit, savedRgb) = WidgetState.readExtractedRgb(context, sig)
        if (savedHit) {
            rgbCache.put(sig, CachedRgb(savedRgb))
            return savedRgb
        }

        val sample = decodeRaw(context, snap, 72)
        val rgb = sample?.let { sampleRawRgb(it) }
        sample?.recycle()
        rgbCache.put(sig, CachedRgb(rgb))
        WidgetState.writeExtractedRgb(context, sig, rgb)
        return rgb
    }

    /**
     * 加载并画成 STYLE A 圆角方图封面（含轻微阴影与玻璃描边），顺带返回封面代表色。
     */
    fun loadRoundedCover(
        context: Context,
        snap: WidgetState.Snapshot,
        targetPx: Int,
        cornerRadiusRatio: Float,
        strokeColor: Int
    ): CoverBundle {
        if (!snap.hasSong || targetPx <= 0) return CoverBundle(null, null)
        val sig = WidgetState.artworkSignature(snap)
        val key = "$sig|$targetPx|$cornerRadiusRatio|$strokeColor"

        bitmapCache.get(key)?.let { cachedBmp ->
            val rgb = extractRawRgb(context, snap)
            return CoverBundle(cachedBmp, rgb)
        }

        val raw = decodeRaw(context, snap, targetPx) ?: run {
            rgbCache.put(sig, CachedRgb(null))
            WidgetState.writeExtractedRgb(context, sig, null)
            return CoverBundle(null, null)
        }

        val rawRgb = rgbCache.get(sig)?.rgb ?: run {
            val sampled = sampleRawRgb(raw)
            rgbCache.put(sig, CachedRgb(sampled))
            WidgetState.writeExtractedRgb(context, sig, sampled)
            sampled
        }

        val out = roundedSquareCover(raw, targetPx, cornerRadiusRatio, strokeColor)
        if (out !== raw) raw.recycle()
        bitmapCache.put(key, out)
        return CoverBundle(out, rawRgb)
    }

    private fun sampleRawRgb(src: Bitmap): Int? {
        val small = runCatching {
            Bitmap.createScaledBitmap(src, SAMPLE_SIDE, SAMPLE_SIDE, true)
        }.getOrNull() ?: return null
        val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        val ok = runCatching {
            small.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
        }.isSuccess
        if (small !== src) small.recycle()
        if (!ok) return null
        return WidgetAuraPalette.extractRawColorFromPixels(pixels)
    }

    private fun decodeRaw(context: Context, snap: WidgetState.Snapshot, targetPx: Int): Bitmap? {
        // 1) 下载好的封面
        val hit = CoverCache.lookup(context, snap.coverKey)
        if (hit is CoverCache.Lookup.Hit) {
            decodeBytes(hit.bytes, targetPx)?.let { return it }
        }

        // 2) MediaStore 专辑封面
        if (snap.albumId > 0) {
            fromAlbumArt(context, snap.albumId, targetPx)?.let { return it }
        }

        // 3) 本地文件内嵌封面（严格跳过 http(s) 流地址）
        val uriStr = snap.sourceUri
        if (!uriStr.isNullOrBlank() && !isRemoteUrl(uriStr)) {
            fromEmbedded(uriStr, targetPx)?.let { return it }
        }
        return null
    }

    private fun fromAlbumArt(context: Context, albumId: Long, targetPx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumb = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null)
            }.getOrNull()
            if (thumb != null) return thumb
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return decodeBytes(bytes, targetPx)
    }

    private fun fromEmbedded(uriStr: String, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(uriStr)
            val art = retriever.embeddedPicture ?: return null
            decodeBytes(art, targetPx)
        } catch (t: Throwable) {
            Log.d(TAG, "读内嵌封面失败: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeBytes(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= targetPx && h / 2 >= targetPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * STYLE A 利落圆角方图封面：
     * 铺满画布 94% 主体区域，周围留细微阴影与 1px 内敛玻璃细描边。
     */
    private fun roundedSquareCover(
        src: Bitmap,
        targetPx: Int,
        cornerRadiusRatio: Float,
        strokeColor: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val padSide = maxOf(2f, targetPx * 0.032f)
        val padTop = maxOf(1.5f, targetPx * 0.020f)
        val padBottom = maxOf(3f, targetPx * 0.044f)
        val rect = RectF(padSide, padTop, targetPx - padSide, targetPx - padBottom)
        val side = minOf(rect.width(), rect.height())
        val cx = rect.centerX()
        val cy = rect.centerY()
        val sq = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val radius = (side * cornerRadiusRatio).coerceIn(4f, side * 0.42f)

        // 柔和底阴影（使专辑像贴在磨砂琉璃上浮起）
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x48000000
            maskFilter = BlurMaskFilter(maxOf(2f, targetPx * 0.026f), BlurMaskFilter.Blur.NORMAL)
        }
        val shadowRect = RectF(sq.left, sq.top + targetPx * 0.014f, sq.right, sq.bottom + targetPx * 0.014f)
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

        // 中心裁切铺图
        val srcMin = minOf(src.width, src.height).coerceAtLeast(1)
        val scale = side / srcMin.toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                cx - (src.width * 0.5f) * scale,
                cy - (src.height * 0.5f) * scale
            )
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        canvas.drawRoundRect(sq, radius, radius, artPaint)

        // 1px 内敛玻璃边框
        val strokeW = maxOf(1.2f, targetPx * 0.010f)
        val strokeRect = RectF(
            sq.left + strokeW * 0.5f,
            sq.top + strokeW * 0.5f,
            sq.right - strokeW * 0.5f,
            sq.bottom - strokeW * 0.5f
        )
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = strokeColor
        }
        canvas.drawRoundRect(strokeRect, (radius - strokeW * 0.5f).coerceAtLeast(2f), (radius - strokeW * 0.5f).coerceAtLeast(2f), strokePaint)

        return out
    }
}
