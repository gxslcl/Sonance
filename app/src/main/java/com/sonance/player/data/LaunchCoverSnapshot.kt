package com.sonance.player.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import java.io.File

/**
 * 「上一次播放封面 + 背景色带」的落盘快照：冷启动首帧同步读回，
 * 让播放页封面与主界面沉浸背景**第一帧就在**，而不是先空白 / 先兜底色再渐变。
 *
 * 只保留最近一首：文件名带 songId（`launch_cover_<id>.jpg` / `launch_bands_<id>.txt`），
 * 写新的时删旧的。解码结果进程内缓存一次，避免首帧多处重复解码。
 */
object LaunchCoverSnapshot {
    private const val COVER_PREFIX = "launch_cover_"
    private const val BANDS_PREFIX = "launch_bands_"

    @Volatile
    private var cachedCover: Pair<Long, ImageBitmap>? = null

    @Volatile
    private var cachedBands: Pair<Long, List<Color>>? = null

    private fun coverFile(context: Context, songId: Long) =
        File(context.filesDir, "$COVER_PREFIX$songId.jpg")

    private fun bandsFile(context: Context, songId: Long) =
        File(context.filesDir, "$BANDS_PREFIX$songId.txt")

    private fun firstFile(context: Context, prefix: String): File? =
        context.filesDir.listFiles { f -> f.name.startsWith(prefix) && f.isFile }
            ?.maxByOrNull { it.lastModified() }

    private fun idOf(file: File, prefix: String): Long =
        file.name.removePrefix(prefix).substringBefore('.').toLongOrNull() ?: -1L

    /** 落盘封面（调用方保证在后台线程）。无效 songId 忽略。 */
    fun saveCover(context: Context, songId: Long, bitmap: ImageBitmap) {
        if (songId == 0L || songId == -1L) return
        runCatching {
            val raw = bitmap.asAndroidBitmap()
            val software = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                raw.config == android.graphics.Bitmap.Config.HARDWARE
            ) {
                raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            } else {
                raw
            }
            val out = coverFile(context, songId)
            val tmp = File(context.filesDir, "$COVER_PREFIX.songid.tmp")
            tmp.outputStream().use { software.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            if (software !== raw) software.recycle()
            context.filesDir.listFiles { f -> f.name.startsWith(COVER_PREFIX) && f != out && f != tmp }
                ?.forEach { it.delete() }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
            cachedCover = songId to bitmap
        }
    }

    /** 落盘背景色带（调用方保证在后台线程）。 */
    fun saveBands(context: Context, songId: Long, bands: List<Color>) {
        if (songId == 0L || songId == -1L || bands.isEmpty()) return
        runCatching {
            val out = bandsFile(context, songId)
            out.writeText(bands.joinToString(",") { it.toArgb().toString() })
            context.filesDir.listFiles { f -> f.name.startsWith(BANDS_PREFIX) && f != out }
                ?.forEach { it.delete() }
            cachedBands = songId to bands
        }
    }

    /** 同步取指定歌的快照封面；没有则 null。首帧组合里调用安全（进程内只解码一次）。 */
    fun peekCover(context: Context, songId: Long): ImageBitmap? {
        if (songId == 0L || songId == -1L) return null
        cachedCover?.let { if (it.first == songId) return it.second }
        val f = coverFile(context, songId)
        if (!f.exists()) return null
        return decode(f)?.also { cachedCover = songId to it }
    }

    /** 同步取指定歌的背景色带快照；没有则 null。进程内缓存一次，绘制期调用零 I/O。 */
    fun peekBands(context: Context, songId: Long): List<Color>? {
        if (songId == 0L || songId == -1L) return null
        cachedBands?.let { if (it.first == songId) return it.second }
        val f = bandsFile(context, songId)
        if (!f.exists()) return null
        return runCatching {
            f.readText().split(",").mapNotNull { it.trim().toIntOrNull()?.let(::Color) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { cachedBands = songId to it }
    }

    /** 同步取最近一首的快照封面（不校验 id，供背景首帧种子化）。 */
    fun peekAnyCover(context: Context): ImageBitmap? {
        cachedCover?.let { return it.second }
        val f = firstFile(context, COVER_PREFIX) ?: return null
        val id = idOf(f, COVER_PREFIX)
        return decode(f)?.also { cachedCover = id to it }
    }

    /** 最近一首快照的 songId；没有则 -1。 */
    fun anySongId(context: Context): Long {
        cachedCover?.let { return it.first }
        val f = firstFile(context, COVER_PREFIX) ?: return -1L
        return idOf(f, COVER_PREFIX)
    }

    /** 同步取最近一首的背景色带；没有则 null。 */
    fun peekAnyBands(context: Context): List<Color>? {
        val f = firstFile(context, BANDS_PREFIX) ?: return null
        return runCatching {
            f.readText().split(",").mapNotNull { it.trim().toIntOrNull()?.let(::Color) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun decode(f: File): ImageBitmap? =
        runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
}