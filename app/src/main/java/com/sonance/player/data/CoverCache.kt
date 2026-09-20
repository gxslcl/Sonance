package com.sonance.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在线封面的本地缓存（借鉴 [LyricsCache] 的做法）。
 *
 * 存的是图片字节，key 为 `sha1(cover|title|artist)`：
 * - 用歌名歌手而不是 MediaStore id —— 重新扫描后 id 会变，缓存就废了
 * - 既存"找到了"，也存"确认没有"（空文件哨兵），避免同一首反复打接口
 */
object CoverCache {

    private const val TAG = "SonanceCover"
    private const val DIR = "cover_cache"
    private const val EXT = ".jpg"
    private const val MAX_FILES = 300

    /** 查询结果。和 [LyricsCache.Lookup] 一样是**三态** —— */
    sealed interface Lookup {
        /** 有图 */
        data class Hit(val bytes: ByteArray) : Lookup {
            // ByteArray 的 equals 是引用比较，data class 会因此失真；这里显式实现
            override fun equals(other: Any?): Boolean =
                other is Hit && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** 之前查过、确认没有（空文件哨兵），别再发请求 */
        data object KnownMiss : Lookup

        /** 没查过 */
        data object Unknown : Lookup
    }

    fun lookup(context: Context, key: String?): Lookup {
        if (key == null) return Lookup.Unknown
        return try {
            val f = fileOf(context, key)
            if (!f.exists()) return Lookup.Unknown
            val b = f.readBytes()
            if (b.isEmpty()) Lookup.KnownMiss else Lookup.Hit(b)
        } catch (t: Throwable) {
            Log.w(TAG, "读封面缓存失败: ${t.message}")
            Lookup.Unknown
        }
    }

    fun store(context: Context, key: String?, bytes: ByteArray?) {
        if (key == null) return
        try {
            val dir = dirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return
            fileOf(context, key).writeBytes(bytes ?: ByteArray(0))
            trim(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "写封面缓存失败: ${t.message}")
        }
    }

    fun remove(context: Context, key: String?) {
        if (key == null) return
        runCatching { fileOf(context, key).delete() }
    }

    /**
     * 这首歌是否已经有**真实**封面（空文件是"确认没有"的哨兵，不算）。
     */
    fun hasCover(context: Context, key: String?): Boolean {
        if (key == null) return false
        return runCatching {
            val f = fileOf(context, key)
            f.exists() && f.length() > 0
        }.getOrDefault(false)
    }

    /**
     * 封面文件的 `content://` URI，经 FileProvider 暴露（authority = `<包名>.covers`）。
     *
     * 存在的理由：MediaSession 要把封面交给通知栏和锁屏，而它们只认 URI 或位图 ——
     * 拿不到应用私有目录里的 `File`。没有下载过封面时返回 null，
     * 调用方据此退回 MediaStore 的 albumart URI。
     */
    fun contentUri(context: Context, key: String?): Uri? {
        if (key == null) return null
        if (!hasCover(context, key)) return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.covers",
                fileOf(context, key)
            )
        }.getOrNull()
    }

    fun count(context: Context): Int =
        runCatching { dirOf(context).listFiles()?.size ?: 0 }.getOrDefault(0)

    fun sizeKb(context: Context): Long = runCatching {
        dirOf(context).listFiles()?.sumOf { it.length() }?.div(1024) ?: 0L
    }.getOrDefault(0L)

    fun clear(context: Context) {
        runCatching { dirOf(context).listFiles()?.forEach { it.delete() } }
    }

    private fun dirOf(context: Context) = java.io.File(context.filesDir, DIR)
    private fun fileOf(context: Context, key: String) = java.io.File(dirOf(context), "$key$EXT")

    private fun trim(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
