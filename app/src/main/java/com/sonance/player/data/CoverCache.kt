package com.sonance.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
