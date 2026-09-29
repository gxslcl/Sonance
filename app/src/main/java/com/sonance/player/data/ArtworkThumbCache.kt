package com.sonance.player.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 解码后的**列表缩略图**磁盘缓存。
 *
 * 和 [CoverCache] 不是一回事：CoverCache 存的是下载来的**原图字节**，命中后还得再解码一次；
 * 这里存的是已经解码、又压成 JPEG 的小图，读出来直接能用。
 *
 * 它解决的是**冷启动**那一档：进程重启后 [ArtworkLoader] 的内存缓存是空的，
 * 磁盘上这些文件还在 —— 第一屏滚动时读文件就能出图，不必再走一轮 MediaStore
 * 的 binder IPC（那是首屏慢的大头）。
 *
 * 只存小档（≤ 256px）。播放页 900px / 专辑网格 600px 都归一到 1024 档，
 * 一张就是 4MB，落盘不划算。
 */
object ArtworkThumbCache {

    private const val TAG = "SonanceCover"
    private const val DIR = "artwork_thumbs"
    private const val EXT = ".jpg"

    /** JPEG 质量。82 对 256px 的缩略图肉眼无差，体积只有 PNG 的几分之一 */
    private const val QUALITY = 82

    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val MAX_FILES = 2000

    fun lookup(context: Context, key: String, size: Int): ByteArray? = runCatching {
        val f = fileOf(context, key, size)
        if (!f.exists() || f.length() == 0L) return@runCatching null
        f.readBytes()
    }.getOrNull()

    fun store(context: Context, key: String, size: Int, bitmap: Bitmap) {
        runCatching {
            val dir = dirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return@runCatching
            fileOf(context, key, size).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            trim(dir)
        }.onFailure { Log.w(TAG, "写缩略图缓存失败: ${it.message}") }
    }

    fun remove(context: Context, key: String, size: Int) {
        runCatching { fileOf(context, key, size).delete() }
    }

    fun clear(context: Context) {
        runCatching { dirOf(context).listFiles()?.forEach { it.delete() } }
    }

    private fun dirOf(context: Context) = File(context.cacheDir, DIR)

    /**
     * 文件名是 `sha1(键|尺寸档)`。
     *
     * 必须哈希：键里含专辑 id 或整个 content:// URI，URI 直接当文件名既不合法也太长。
     * 尺寸档也要进键 —— 128 档和 256 档是两张不同的图。
     */
    private fun fileOf(context: Context, key: String, size: Int): File {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest("$key|$size".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return File(dirOf(context), "$sb$EXT")
    }

    /** 先按条数、再按体积收：条数上限挡住小文件堆积，体积上限挡住个别大文件 */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        val byAge = files.sortedBy { it.lastModified() }
        var over = files.size - MAX_FILES
        var total = files.sumOf { it.length() }
        for (f in byAge) {
            if (over <= 0 && total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                over--
            }
        }
    }
}
