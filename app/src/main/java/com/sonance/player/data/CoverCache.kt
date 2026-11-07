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

    /**
     * 条数 / 体积上限。
     *
     * 原来是 **300 条**：批量补封面时，第 301 首一落盘就把最早下载的那张删掉，
     * 于是曲库大于 300 首的用户「每次补完封面、重开 App 就少一部分」——
     * 少的正是最早下载的那批（[trim] 按 lastModified 删旧）。
     *
     * 现在把上限抬到远高于曲库规模，并改成**按访问时间**淘汰：
     * 读封面时顺手 touch 一下 lastModified，常用的不会因为"下载早"被误杀。
     */
    private const val MAX_FILES = 20_000

    /** 512 MB：按每张 100KB 算约 5000 张，足够覆盖大曲库；真有更大需求也是软上限 */
    private const val MAX_BYTES = 512L * 1024 * 1024

    /** 攒够多少张才扫一次目录（避免每存一张都 listFiles + 全目录 stat） */
    private const val TRIM_EVERY = 64

    /** 距上次访问不足 1 小时就不重复写时间戳，省掉滚动列表时的元数据写 */
    private const val TOUCH_INTERVAL_MS = 60L * 60 * 1000

    private var storesSinceTrim = 0

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
            if (b.isEmpty()) Lookup.KnownMiss else {
                touch(f)
                Lookup.Hit(b)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读封面缓存失败: ${t.message}")
            Lookup.Unknown
        }
    }

    /**
     * 把 lastModified 当成「最后访问时间」用：淘汰按它排序，常用封面就不会
     * 因为下载得早就被清掉。
     */
    private fun touch(f: java.io.File) {
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > TOUCH_INTERVAL_MS) {
            runCatching { f.setLastModified(now) }
        }
    }

    fun store(context: Context, key: String?, bytes: ByteArray?) {
        if (key == null) return
        try {
            val dir = dirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return
            fileOf(context, key).writeBytes(bytes ?: ByteArray(0))
            if (++storesSinceTrim >= TRIM_EVERY) {
                storesSinceTrim = 0
                trim(dir)
            }
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

    /** 先按条数、再按体积收；删除顺序按 lastModified（≈ 最后访问时间）从旧到新 */
    private fun trim(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        var overCount = files.size - MAX_FILES
        var total = files.sumOf { it.length() }
        if (overCount <= 0 && total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (overCount <= 0 && total <= MAX_BYTES) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                overCount--
            }
        }
    }
}
