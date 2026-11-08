package com.sonance.player.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解码后的封面**磁盘**缓存。
 *
 * 和 [CoverCache] 不是一回事：CoverCache 存的是下载来的**原图字节**，命中后还得再解码一次；
 * 这里存的是已经解码、又压成 JPEG 的图，读出来直接能用。
 *
 * 它解决的是**冷启动**那一档：进程重启后 [ArtworkLoader] 的内存缓存是空的，
 * 磁盘上这些文件还在 —— 第一屏滚动时读文件就能出图，不必再走一轮 MediaStore
 * 的 binder IPC（那是首屏慢、以及"重启后要等播放一会封面才回来"的大头）。
 *
 * ⚠️ 目录必须放在 `filesDir`，**不能放 `cacheDir`**：
 * 系统在存储紧张时会清 `cacheDir`，国内不少 ROM 还会定期自动清理，
 * 于是"上次明明加载过的封面，重启后又全没了"—— 这正是之前那个 bug 的根因。
 *
 * 档位：缩略图档（≤256，列表用）和大图档（播放页 / 专辑网格用）。
 * 大图落盘前会缩到 [MAX_STORE_PX]，不然一张 1024 的位图压成 JPEG 也要几百 KB，
 * 几百首歌堆起来就是几十 MB。
 */
object ArtworkThumbCache {

    private const val TAG = "SonanceCover"
    private const val DIR = "artwork_cache"
    private const val EXT = ".jpg"

    /** JPEG 质量。82 对 256px 的缩略图肉眼无差，体积只有 PNG 的几分之一 */
    private const val QUALITY = 82

    /** 大图档落盘时的边长上限 */
    private const val MAX_STORE_PX = 768

    /**
     * 条数与体积上限。
     *
     * 之前是 3000 条 / 64MB —— 大曲库（几千首 × 两个尺寸档）会不停触发淘汰，
     * 冷启动时列表要反复回源（本地文件 / MediaStore）。这里和 [CoverCache] 一起
     * 抬到明显高于常见曲库规模，淘汰只在真正超限时才发生。
     */
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_FILES = 20_000

    /**
     * 攒够多少张才做一次全目录清理。
     *
     * 原来**每存一张**就 `listFiles()` + 对每个文件 `length()` 一次 ——
     * 磁盘上三四千个文件时，存一张封面就是三四千次 stat 系统调用。
     * 快速滑动时连着存十几张，IO 立刻打满，列表就"卡住滑不动"。
     * 现在攒 40 张才扫一次，磁盘占用上限只是软约束，差这几十张无所谓。
     */
    private const val TRIM_EVERY = 40
    private const val TOUCH_INTERVAL_MS = 60L * 60 * 1000

    private val storesSinceTrim = AtomicInteger(0)
    private val clears = CacheClearCoordinator()

    /** 先登记清理屏障，再切到 IO 线程；新读写不会与清理交错。 */
    internal fun prepareClear(): CacheClearCoordinator.Ticket = clears.prepareClear()
    private fun awaitClear() = clears.awaitReady()
    fun currentGeneration(): Long = clears.currentGeneration()
    fun awaitGeneration(expected: Long): Boolean = clears.awaitGeneration(expected)
    fun <T : Any> ifCurrent(expected: Long, block: () -> T): T? = clears.ifCurrent(expected, block)

    fun lookup(context: Context, key: String, size: Int): ByteArray? = runCatching {
        awaitClear()
        val f = fileOf(context, key, size)
        DurableCacheFile.read(f)?.takeIf { it.isNotEmpty() }?.also {
            if (System.currentTimeMillis() - f.lastModified() > TOUCH_INTERVAL_MS) {
                runCatching { f.setLastModified(System.currentTimeMillis()) }
            }
        }
    }.getOrNull()

    /** 只看在不在，不读内容 —— 给 [ArtworkLoader.load] 的短路判断用（省一次完整读文件） */
    fun exists(context: Context, key: String, size: Int): Boolean = runCatching {
        awaitClear()
        val f = fileOf(context, key, size)
        f.exists() && f.length() > 0L
    }.getOrDefault(false)

    fun store(context: Context, key: String, size: Int, bitmap: Bitmap, expectedGeneration: Long) {
        runCatching {
            awaitClear()
            if (currentGeneration() != expectedGeneration) return@runCatching
            val dir = dirOf(context)
            val toSave = if (size > MAX_STORE_PX) downscale(bitmap, MAX_STORE_PX) else bitmap
            val out = ByteArrayOutputStream()
            if (!toSave.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return@runCatching
            val written = ifCurrent(expectedGeneration) {
                DurableCacheFile.write(fileOf(context, key, size), out.toByteArray())
            } ?: return@runCatching
            if (!written) {
                Log.w(TAG, "写封面缩略图失败：无法安全替换文件")
                return@runCatching
            }
            if (storesSinceTrim.incrementAndGet() >= TRIM_EVERY &&
                storesSinceTrim.getAndSet(0) >= TRIM_EVERY) {
                trim(dir)
            }
        }.onFailure { Log.w(TAG, "写封面缓存失败: ${it.message}") }
    }

    /**
     * 等比缩到边长不超过 [max]。缩不动（已经更小）就原样返回 —— 不要为了"统一"
     * 把小图放大，那只会白白多一次有损压缩。
     */
    private fun downscale(src: Bitmap, max: Int): Bitmap {
        val w = src.width
        val h = src.height
        val biggest = maxOf(w, h)
        if (biggest <= max) return src
        val scale = max.toFloat() / biggest
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    fun remove(context: Context, key: String, size: Int) {
        runCatching { DurableCacheFile.delete(fileOf(context, key, size)) }
    }

    fun removeIfUnchanged(context: Context, key: String, size: Int, bytes: ByteArray) {
        runCatching { DurableCacheFile.deleteIfUnchanged(fileOf(context, key, size), bytes) }
    }

    internal fun clear(context: Context, ticket: CacheClearCoordinator.Ticket) {
        clears.completeClear(ticket) {
            runCatching { dirOf(context).listFiles()?.filter { it.name.endsWith(EXT) }?.forEach { DurableCacheFile.delete(it) } }
        }
    }

    private fun dirOf(context: Context) = File(context.filesDir, DIR)

    /**
     * 文件名是 `sha1(键|尺寸档)`。
     *
     * 必须哈希：键里含专辑 id 或整个 content:// URI，URI 直接当文件名既不合法也太长。
     * 尺寸档也要进键 —— 256 档和 1024 档是两张不同的图。
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
        val files = dir.listFiles()?.filter { it.name.endsWith(EXT) } ?: return
        var over = files.size - MAX_FILES
        var total = files.sumOf { it.length() }
        // 绝大多数情况都在限额内 —— 先算总量再决定要不要排序。
        // 排序是 O(n log n)，几千个文件上每存一张就排一次会很肉疼。
        if (over <= 0 && total <= MAX_BYTES) return
        val byAge = files.sortedBy { it.lastModified() }
        for (f in byAge) {
            if (over <= 0 && total <= MAX_BYTES) break
            val len = f.length()
            if (DurableCacheFile.delete(f)) {
                total -= len
                over--
            }
        }
    }
}
