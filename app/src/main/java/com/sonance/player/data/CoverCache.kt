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
import java.util.concurrent.atomic.AtomicInteger

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
    // 用户手选封面是持久数据，不参与普通缓存的自动淘汰。
    private const val SELECTED_DIR = "selected_covers"
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

    private val storesSinceTrim = AtomicInteger(0)
    // 老版本可能把网络异常写成空文件。空结果仅短期有效，避免永久阻止恢复。
    private const val MISS_TTL_MS = 10L * 60 * 1000

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
            val b = DurableCacheFile.read(f) ?: return Lookup.Unknown
            if (b.isEmpty()) {
                val age = System.currentTimeMillis() - f.lastModified()
                if (age in 0 until MISS_TTL_MS) Lookup.KnownMiss else Lookup.Unknown
            } else {
                // 旧版可能留下中断写入或服务器错误页面；保留旧文件，但不把坏数据当成有效命中。
                if (!hasReadableImageHeader(b)) {
                    Log.w(TAG, "缓存封面不可解码，将尝试重新获取")
                    return Lookup.Unknown
                }
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

    fun store(context: Context, key: String?, bytes: ByteArray?): Boolean {
        if (key == null) return false
        try {
            if (bytes != null && bytes.isNotEmpty() && !hasReadableImageHeader(bytes)) {
                Log.w(TAG, "封面数据不可解码，保留已有文件")
                return false
            }
            val dir = dirOf(context)
            if (!DurableCacheFile.write(fileOf(context, key), bytes ?: ByteArray(0),
                    sync = bytes != null, preserveNonEmpty = true)) {
                Log.w(TAG, "写封面缓存失败：无法安全替换文件")
                return false
            }
            if (bytes != null && bytes.isNotEmpty()) ArtworkLoader.forgetCoverMiss(key)
            if (storesSinceTrim.incrementAndGet() >= TRIM_EVERY &&
                storesSinceTrim.getAndSet(0) >= TRIM_EVERY) {
                trim(dir)
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "写封面缓存失败: ${t.message}")
            return false
        }
    }

    /** 手选封面独立存储；保存成功后才允许界面报告已更新。 */
    fun storeSelected(context: Context, songId: Long, sourceUri: Uri?, bytes: ByteArray): Boolean {
        val saved = selectedStore(context).store(songId, sourceUri?.toString(), bytes)
        if (!saved) Log.w(TAG, "保存手选封面失败：无法安全写入")
        return saved
    }

    fun lookupSelected(context: Context, songId: Long, sourceUri: Uri?): ByteArray? {
        return selectedStore(context).lookup(songId, sourceUri?.toString())
    }

    fun hasSelected(context: Context, songId: Long, sourceUri: Uri?): Boolean {
        return selectedStore(context).exists(songId, sourceUri?.toString())
    }

    fun migrateSelectedIfAbsent(context: Context, songId: Long, sourceUri: Uri?, bytes: ByteArray): Boolean {
        return selectedStore(context).migrateIfAbsent(songId, sourceUri?.toString(), bytes)
    }

    fun removeSelected(context: Context, songId: Long, sourceUri: Uri?) {
        selectedStore(context).remove(songId, sourceUri?.toString())
    }

    fun remove(context: Context, key: String?) {
        if (key == null) return
        runCatching { DurableCacheFile.delete(fileOf(context, key)) }
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
        runCatching { dirOf(context).listFiles()?.filter { it.name.endsWith(EXT) }?.forEach { DurableCacheFile.delete(it) } }
    }

    private fun dirOf(context: Context) = java.io.File(context.filesDir, DIR)
    private fun fileOf(context: Context, key: String) = java.io.File(dirOf(context), "$key$EXT")

    private fun selectedStore(context: Context) = SelectedCoverStore(java.io.File(context.filesDir, SELECTED_DIR))

    private fun hasReadableImageHeader(bytes: ByteArray): Boolean = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) {
        false
    }

    /** 先按条数、再按体积收；删除顺序按 lastModified（≈ 最后访问时间）从旧到新 */
    private fun trim(dir: java.io.File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(EXT) } ?: return
        var overCount = files.size - MAX_FILES
        var total = files.sumOf { it.length() }
        if (overCount <= 0 && total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (overCount <= 0 && total <= MAX_BYTES) break
            val len = f.length()
            if (runCatching { DurableCacheFile.delete(f) }.getOrDefault(false)) {
                total -= len
                overCount--
            }
        }
    }
}
