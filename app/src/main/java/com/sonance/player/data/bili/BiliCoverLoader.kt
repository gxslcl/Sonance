package com.sonance.player.data.bili

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * B 站封面缩略图。
 *
 * `entry.json` 里给的封面地址是 `http://i1.hdslb.com/...`，而 Android 9 起默认
 * 禁止明文 HTTP —— 所以统一改写成 https 再请求。图片本身是公开的 CDN 资源，
 * 不需要登录态。
 *
 * 只做内存缓存（60 张）：缓存列表一屏十来条，翻两页就够；落盘缓存没必要，
 * 反正重新联网拿一张也就几十毫秒。
 */
object BiliCoverLoader {

    private val cache = LruCache<String, Bitmap>(60)

    /** 同时最多 4 个请求，避免一次滚动把几十张封面全怼出去 */
    private val semaphore = kotlinx.coroutines.sync.Semaphore(4)

    suspend fun load(url: String?, targetPx: Int = 320): Bitmap? {
        if (url.isNullOrBlank()) return null
        val key = "$url@$targetPx"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            semaphore.acquire()
            try {
                cache.get(key)?.let { return@withContext it }
                val httpsUrl = url.replaceFirst("http://", "https://")
                val conn = (URL(httpsUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                }
                try {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bytes.size, targetPx) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { cache.put(key, it) }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                null
            } finally {
                semaphore.release()
            }
        }
    }

    /** 按字节数粗估采样率，避免把 2K 封面整张解进内存 */
    private fun sampleSizeFor(byteCount: Int, targetPx: Int): Int = when {
        byteCount > 800_000 && targetPx <= 200 -> 4
        byteCount > 200_000 -> 2
        else -> 1
    }
}
