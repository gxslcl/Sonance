package com.sonance.player.widget

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import com.sonance.player.data.CoverCache
import com.sonance.player.data.isRemoteUrl

/** 圆形唱片位图 + 从它身上取出来的主色（用于整张卡片的渐变）。 */
internal data class CoverArt(val bitmap: Bitmap, val seedColor: Int)

/**
 * 给桌面小组件准备封面位图。
 *
 * 三条来源，按「快 → 慢」的顺序试：
 * 1. [CoverCache] 里已经下载好的封面（应用内搜过封面的歌，这里直接命中）
 * 2. MediaStore 的 `albumart` URI
 * 3. 音频文件里的内嵌封面（`MediaMetadataRetriever.embeddedPicture`）
 *
 * 三条都拿不到就返回 null，渲染层会画一张同款圆形占位图。
 *
 * ⚠️ 结果是**圆形裁切 + 外侧发光圆环**的位图：`RemoteViews` 用不了
 * `clipToOutline`，圆形和光晕都只能在位图层面画掉。透明边角由位图自己负责。
 */
internal object WidgetCover {

    private const val TAG = "SonanceWidget"

    /** 位图缓存：跨进程传的是副本，本地留一份避免每次刷新都重新解码 */
    private val cache = LruCache<String, CoverArt>(8)

    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    /**
     * @param targetPx 目标边长（像素），包含发光圆环。务必别太大 ——
     *                 `RemoteViews` 走 Binder，单个位图超过 1MB 会抛异常。
     */
    fun load(context: Context, snap: WidgetState.Snapshot, targetPx: Int): CoverArt? {
        val key = "${snap.coverKey}|${snap.albumId}|${snap.sourceUri}|$targetPx"
        cache.get(key)?.let { return it }

        val raw = decodeRaw(context, snap, targetPx) ?: return null
        val seed = dominantColor(raw)
        val out = circularWithGlow(raw, targetPx, seed)
        if (out !== raw) raw.recycle()
        val art = CoverArt(out, seed)
        cache.put(key, art)
        return art
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
        //
        // ⚠️ 必须排除 http(s)：在线歌的 sourceUri 是**取流地址**，
        // `MediaMetadataRetriever.setDataSource(url)` 会真的把整个音频流拉下来。
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
     * 从封面取一个「酷狗概念版风格卡片渐变」用的高饱和明快种子色。
     *
     * 缩到 16×16 采样，给色彩鲜明（高饱和度、适中偏亮明度）的像素更高权重，
     * 跳过黑边与过曝白字，避免算术平均把鲜艳封面拉成灰暗泥色。
     * 最终将饱和度锁定在 0.55..0.88、明度锁定在 0.65..0.90，呈现清透鲜亮的年轻感。
     */
    private fun dominantColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var wSum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val c = small.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = r * 0.299f + g * 0.587f + b * 0.114f
                if (lum < 22f || lum > 240f) continue
                Color.RGBToHSV(r, g, b, hsv)
                // 越鲜艳、明度越居中的像素权重越高
                val weight = (0.25f + hsv[1] * 1.6f) * (0.4f + (1f - kotlin.math.abs(hsv[2] - 0.68f)))
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
                wSum += weight
            }
        }
        if (small !== src) small.recycle()
        if (wSum <= 0.001) return 0xFF1BB8D8.toInt()
        val avg = Color.rgb(
            (rSum / wSum).toInt().coerceIn(0, 255),
            (gSum / wSum).toInt().coerceIn(0, 255),
            (bSum / wSum).toInt().coerceIn(0, 255)
        )
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 0.88f)
        hsv[2] = hsv[2].coerceIn(0.66f, 0.90f)
        return Color.HSVToColor(hsv)
    }

    /**
     * 画「酷狗概念版同款：立体半透明霓虹发光黑胶外环 + 纹理沟槽 + 圆形封面」。
     *
     * 结构拆解（对齐参考图）：
     * - 最外层（`0.88r ~ 0.96r`）：柔和环境投影与霓虹光晕，让胶片从渐变底卡上浮起；
     * - 立体彩胶外圈（`0.61r ~ 0.89r`）：果冻感高明度半透明渐变环，叠加 2 道精细同心声槽纹、
     *   外沿高光白边与内沿立体描边；
     * - 中心专辑封面（`0 ~ 0.61r`）：居中圆裁切，边缘紧贴一圈清脆细白环。
     */
    private fun circularWithGlow(src: Bitmap, targetPx: Int, seed: Int): Bitmap {
        val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = targetPx / 2f
        val discRadius = r * 0.615f
        val ringOuterRadius = r * 0.895f
        val ringMidRadius = (discRadius + ringOuterRadius) * 0.5f
        val ringWidth = ringOuterRadius - discRadius

        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)

        // 1) 底部柔和立体阴影 + 霓虹外发光
        val glowColor = Color.HSVToColor(
            floatArrayOf(hsv[0], (hsv[1] * 0.72f).coerceIn(0.35f, 0.82f), 1f)
        )
        val ambientShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(68, 0, 12, 32)
            maskFilter = BlurMaskFilter(targetPx * 0.045f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(r, r + targetPx * 0.015f, ringOuterRadius, ambientShadow)

        val outerNeonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = targetPx * 0.055f
            color = Color.argb(145, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            maskFilter = BlurMaskFilter(targetPx * 0.032f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(r, r, ringOuterRadius - targetPx * 0.01f, outerNeonGlow)

        // 2) 果冻感宽边彩胶环（左上亮、右下润的斜向流光渐变）
        val rimLight = Color.HSVToColor(
            235,
            floatArrayOf((hsv[0] - 6f + 360f) % 360f, (hsv[1] * 0.46f).coerceIn(0.24f, 0.58f), 1f)
        )
        val rimMid = Color.HSVToColor(
            220,
            floatArrayOf(hsv[0], (hsv[1] * 0.68f).coerceIn(0.38f, 0.76f), 0.98f)
        )
        val rimDeep = Color.HSVToColor(
            230,
            floatArrayOf((hsv[0] + 8f) % 360f, (hsv[1] * 0.82f).coerceIn(0.45f, 0.85f), 0.90f)
        )
        val vinylRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            shader = android.graphics.LinearGradient(
                r - ringOuterRadius, r - ringOuterRadius,
                r + ringOuterRadius, r + ringOuterRadius,
                intArrayOf(rimLight, rimMid, rimDeep),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(r, r, ringMidRadius, vinylRimPaint)

        // 3) 彩胶环上的两道同心声槽细纹 + 外圈高光玻璃边
        val groovePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, targetPx * 0.0045f)
            color = 0x48FFFFFF
        }
        canvas.drawCircle(r, r, discRadius + ringWidth * 0.36f, groovePaint1)

        val groovePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, targetPx * 0.004f)
            color = 0x2E000000
        }
        canvas.drawCircle(r, r, discRadius + ringWidth * 0.68f, groovePaint2)

        val outerRimHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, targetPx * 0.008f)
            color = 0x88FFFFFF.toInt()
        }
        canvas.drawCircle(r, r, ringOuterRadius - outerRimHighlight.strokeWidth * 0.5f, outerRimHighlight)

        // 4) 圆形唱片封面本体
        val side = minOf(src.width, src.height)
        val scale = (discRadius * 2f) / side
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                r - (src.width / 2f) * scale,
                r - (src.height / 2f) * scale
            )
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val disc = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        canvas.drawCircle(r, r, discRadius, disc)

        // 5) 封面与彩胶环交界处的亮白内环，让层次干净利落
        val innerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, targetPx * 0.008f)
            color = 0x99FFFFFF.toInt()
        }
        canvas.drawCircle(r, r, discRadius, innerEdge)

        return out
    }
}
