package com.sonance.player.widget

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
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

/**
 * 给桌面小组件准备封面位图。
 *
 * 三条来源，按"快 → 慢"的顺序试：
 * 1. [CoverCache] 里已经下载好的封面（应用内搜过封面的歌，这里直接命中）
 * 2. MediaStore 的 `albumart` URI
 * 3. 音频文件里的内嵌封面（`MediaMetadataRetriever.embeddedPicture`）
 *
 * 三条都拿不到就返回 null，布局里的 `widget_placeholder` 顶上。
 *
 * ⚠️ 结果是**方形裁切 + 圆角**的位图：`RemoteViews` 用不了 `clipToOutline`，
 * 圆角只能在位图层面做掉。
 */
internal object WidgetCover {

    private const val TAG = "SonanceWidget"

    /** 位图缓存：跨进程传的是副本，本地留一份避免每次刷新都重新解码 */
    private val cache = LruCache<String, Bitmap>(8)

    /** `content://media/external/audio/albumart/<id>` */
    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    /**
     * @param targetPx 目标边长（像素）。务必别太大 —— `RemoteViews` 走 Binder，
     *                 单个位图超过 1MB 会直接抛 `TransactionTooLargeException`。
     */
    fun load(context: Context, snap: WidgetState.Snapshot, targetPx: Int): Bitmap? {
        val key = "${snap.coverKey}|${snap.albumId}|${snap.sourceUri}|$targetPx"
        cache.get(key)?.let { return it }

        val raw = decodeRaw(context, snap, targetPx) ?: return null
        val out = squareRounded(raw, targetPx, targetPx / 9f)
        if (out !== raw) raw.recycle()
        cache.put(key, out)
        return out
    }

    fun clear() = cache.evictAll()

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

        // 3) 文件内嵌封面
        val uriStr = snap.sourceUri
        if (!uriStr.isNullOrBlank()) {
            fromEmbedded(uriStr, targetPx)?.let { return it }
        }
        return null
    }

    private fun fromAlbumArt(context: Context, albumId: Long, targetPx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
        // API 29+ 有官方缩略图接口，由 MediaProvider 负责解码与缩放，比自己解省内存
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumb = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null)
            }.getOrNull()
            if (thumb != null) return thumb
        }
        // 老系统 / 缩略图接口失败：自己读流再按需采样
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
            // 不能写 `retriever.use {}`：AutoCloseable 是 API 29 才加到
            // MediaMetadataRetriever 上的，minSdk 24 上会抛 NoSuchMethodError
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

    /** 最接近目标尺寸的 2 的幂次采样率（BitmapFactory 只认 2 的幂） */
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
     * 居中裁成正方形 + 圆角。
     *
     * 用 `BitmapShader` 配合 `Matrix` 一次画完：比"先 createBitmap 裁切、再 clipPath 画"
     * 少一张中间位图，widget 这种要跨进程传的场景能省一点就省一点。
     */
    private fun squareRounded(src: Bitmap, targetPx: Int, radiusPx: Float): Bitmap {
        val side = minOf(src.width, src.height)
        val scale = targetPx.toFloat() / side
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-(src.width - side) / 2f * scale, -(src.height - side) / 2f * scale)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat()),
            radiusPx, radiusPx, paint
        )
        return out
    }
}
