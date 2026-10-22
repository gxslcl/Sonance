package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.graphics.ColorUtils
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 专辑封面加载。
 *
 * 取图顺序：
 * 1. 内存 LRU（按「专辑 id + 尺寸档位」，专辑未知时再带 URI 指纹，并带上 coverKey）
 * 2. MediaStore 专辑封面：API 29+ 用 `loadThumbnail`，低版本回退传统 `albumart` URI
 * 3. `sourceUri`（歌曲自身的 content:// URI）：用它再试一次
 *    —— 专辑级查询拿不到时，从音频文件里抠内嵌封面（`loadThumbnail`，
 *    再不行用 `MediaMetadataRetriever.embeddedPicture`）
 * 4. 传了 `coverKey` 就再读一次 [CoverCache] —— **之前下载并落盘的在线封面**。
 *    没有这一步，下载的封面只活在 [onlineCovers] 里，重启就没了，
 *    列表要等播放到那首歌才显示封面
 * 5. 都没有 → 由界面显示占位图；上层可再通过 [putOnline] 补在线封面
 *
 * [paused] 由列表 / 网格的滚动状态驱动：滚动中**降低**解码并发（见 [withDecodeGate]），
 * 不是停掉加载。早先的实现在这里直接跳过加载，代价是停下来那一刻所有可见行一起开工、
 * 全排在闸门后面 —— 用户看到的就是"停下后还要等半秒到一秒"。
 * 唯一要守住的底线是：**不动已经拿到图的那些**，否则滚动时封面会闪回占位图。
 */
/**
 * 封面下边缘的三个取样色，用来把封面的颜色淡淡地渗到背景上。
 *
 * 三个值都是不透明的 ARGB（透明度交给界面按档位控制）。
 */
data class CoverTint(val left: Int, val center: Int, val right: Int)

/**
 * 一次封面请求。列表把「即将进入视野」的行交给 [ArtworkLoader.prefetch] 时用。
 *
 * 只带定位封面需要的三样东西：尺寸由调用方统一给（同一份列表只有一个尺寸档），
 * songId 是界面层的事，这里用不上。
 */
data class CoverRequest(
    val albumId: Long,
    val sourceUri: Uri?,
    val coverKey: String?
)

object ArtworkLoader {

    /** 请求尺寸归一到几档，避免同一张专辑为每个像素尺寸都缓存一份 */
    private val SIZE_BUCKETS = intArrayOf(128, 256, 512, 1024)

    private fun bucketOf(sizePx: Int): Int {
        for (b in SIZE_BUCKETS) if (sizePx <= b) return b
        return SIZE_BUCKETS.last()
    }

    /**
     * 是不是**远程**地址（在线歌的取流 URL）。
     *
     * 判据在 [isRemoteUri] 里（和桌面小组件共用一份），这里保留这个名字只是
     * 让调用点读起来像一句话。为什么要挡、不挡会怎样，见 [isRemoteUrl] 的说明。
     */
    private fun Uri.isRemote(): Boolean = isRemoteUri(this)

    /**
     * 缓存键 = 专辑 id + 尺寸档位（专辑未知时再带上 URI 指纹）。
     *
     * **尺寸必须进键**：列表要 200px 缩略图、播放页要 900px 大图，
     * 只用 albumId 做键的话，播放页会直接命中列表留下的 200px 小图，
     * 放大到整屏就是糊的。
     *
     * **URI 指纹**是为了 albumId <= 0 的情况：只用 (albumId, size) 会让所有
     * "未知专辑"的歌共用一个键，第一首的封面被套到后面每一首上。
     */
    private class Key(
        val albumId: Long,
        val size: Int,
        val uriKey: Int,
        /**
         * 这首歌的在线封面缓存 key（见 [OnlineCover.keyOf]）。
         *
         * 必须进键：同一张专辑下的两首歌各有各的下载封面（标题不同 → key 不同），
         * 只用 (albumId, size) 会让它们互相顶掉。
         */
        val coverKey: String?
    ) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.albumId == albumId &&
                other.size == size && other.uriKey == uriKey && other.coverKey == coverKey

        override fun hashCode(): Int =
            (((albumId * 31L + size) * 31L + uriKey) * 31L + (coverKey?.hashCode() ?: 0)).hashCode()
    }

    private fun keyOf(albumId: Long, size: Int, sourceUri: Uri?, coverKey: String?): Key =
        Key(albumId, size, if (albumId > 0) 0 else (sourceUri?.toString()?.hashCode() ?: 0), coverKey)

    /**
     * **本地**封面（MediaStore / 内嵌）的缓存键 —— 不带 coverKey。
     *
     * 这是性能关键：同一张专辑的每首歌都有自己的 coverKey（标题不同），
     * 如果用完整键去缓存本地封面，那么 12 首歌的专辑会被**逐首**重新探测一遍
     * MediaStore（每次都是一轮 binder IPC + 解码）—— 快速滚动后列表要等
     * 一两秒才出封面，主要就是这笔开销。
     *
     * 本地封面跟歌无关、只跟专辑和尺寸有关，所以单独用这个键共用一份结果。
     */
    private fun localKeyOf(albumId: Long, size: Int, sourceUri: Uri?): Key =
        Key(albumId, size, if (albumId > 0) 0 else (sourceUri?.toString()?.hashCode() ?: 0), null)

    /** 尺寸档 ≤ 它算「缩略图」，和小图缓存分开算（见 [thumbCache]） */
    private const val THUMB_BUCKET = 256

    /**
     * 大图缓存进内存前缩到的边长上限。
     *
     * 播放页要 900px（归一到 1024 档），单张 ARGB_8888 就是 **4MB**。
     * 而播放页的分页器同时组合着 3 页 —— 三张就是 12MB，正好把大图 LRU 撑爆，
     * 于是左右滑一次就把刚解好的封面挤出去，滑回来又是占位图 ——
     * 这就是"列表里有封面、进播放页却没有"的直接成因。
     * 缩到 768（2.3MB/张）之后三页加一起也才 7MB，翻页不再互相顶掉。
     * 屏幕上一个封面最多也就 400 多 dp，768px 完全够看。
     */
    private const val LARGE_PX = 768

    /** 按**字节**算上限，不是按条数 —— 80 张 900px 位图能吃掉一百多 MB */
    private val maxBytes: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        (heap / 8L).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
    }

    /**
     * 内存缓存按**尺寸档分成两个**，不是一个。
     *
     * 为什么必须分：列表要 200px（归一到 256 档，单张 256KB），播放页要 900px、
     * 专辑网格要 600px（都归一到 **1024** 档，单张 **4MB**，是缩略图的 16 倍）。
     * 混在一个 LRU 里，进一次播放页、或者滚一遍专辑网格，就能把整个列表的缩略图
     * 全部挤出去 —— 于是"明明加载过的封面，滚回来还要重新解码"，
     * 这正是快速滑动时占位图迟迟不消的原因之一。分开之后大图只会挤掉大图。
     */
    private val thumbCache = object : LruCache<Key, ImageBitmap>(maxBytes * 5 / 8) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * 大图：数量少但每张贵。
     *
     * 预算从 2/5 提到 3/8 之外另说——真正关键的是 [LARGE_PX] 把单张成本砍掉一半，
     * 否则"3 页 × 4MB"刚好卡在容量线上，翻一次页就全军覆没。
     */
    private val largeCache = object : LruCache<Key, ImageBitmap>(maxBytes * 3 / 8) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private fun cacheFor(size: Int): LruCache<Key, ImageBitmap> =
        if (size <= THUMB_BUCKET) thumbCache else largeCache

    /**
     * 确认过没有封面的条目，避免重复走一遍失败的 IPC / 文件解析。
     * 键与 [Key] 同构，未知专辑按 URI 区分，不会"一首没封面 = 全都没封面"。
     */
    private val missing = HashSet<String>(256)

    /**
     * [missing] / [coverMiss] 的版本号 —— 也就是「又确认了一首歌没有封面」。
     *
     * 晕染层要用它区分两件完全不同的事：
     * - 「封面还在找」（探测中 / 正在解码 / 在线封面正在下载）→ 先不要画，等真色；
     * - 「封面确实没有」（见 [isArtworkGone]）→ 才回落到占位配色。
     *
     * 那两个字面集合是普通 Set、还是在 IO 线程写的，界面直接读建立不了依赖，
     * 所以每次记一笔就 +1，界面把它放进 `remember` 的 key 里（见 `FullPlayer`）。
     */
    var artworkGoneVersion by mutableIntStateOf(0)
        private set

    /** 记一笔「这张也确认没有封面」。只有真的新增才推进版本号，避免无谓的重组 */
    private fun markMissing(key: String) {
        val added = synchronized(missing) { missing.add(key) }
        if (added) artworkGoneVersion++
    }

    /**
     * 封面是不是**确认彻底没有**了：本地探测过没有，磁盘缓存里也没有下载下来的封面。
     *
     * 别拿 [isMissing] 代替 —— 它的语义是「**本地**没有」（旧用途是决定要不要走在线补全，
     * 还把「没有专辑 ID」也算进去）。磁盘上存在线封面时它会误报成「没有」，
     * 于是晕染先套一层占位色、等磁盘封面解码完再突变，又变成闪一下。
     */
    fun isArtworkGone(albumId: Long, sourceUri: Uri?, coverKey: String?): Boolean {
        if (!isMissingKey(missingKeyOf(albumId, sourceUri))) return false
        if (coverKey == null) return true
        return synchronized(coverMiss) { coverMiss.contains(coverKey) }
    }

    private fun missingKeyOf(albumId: Long, sourceUri: Uri?): String =
        if (albumId > 0) "a:$albumId" else "u:${sourceUri?.toString() ?: "?"}"

    /**
     * 确认过「磁盘缓存里也没有这张下载封面」的 coverKey。
     *
     * 单独一个集合，**不复用 [missing]** —— 后者的语义是「本地（MediaStore / 内嵌）
     * 没有封面」，还被 [isMissing] 对外暴露；混在一起会把语义搅乱，
     * 而且会让「同专辑的第一首没封面」把后面每首歌的下载封面一起挡掉。
     */
    private val coverMiss = HashSet<String>(256)

    private fun markCoverMiss(key: String) {
        val added = synchronized(coverMiss) { coverMiss.add(key) }
        if (added) artworkGoneVersion++
    }

    /** 封面刚写入缓存后调用，让上面那条短路失效 */
    fun forgetCoverMiss(coverKey: String?) {
        if (coverKey == null) return
        // 从「没有」变回「可能有」，晕染那边也要重新判断
        if (synchronized(coverMiss) { coverMiss.remove(coverKey) }) artworkGoneVersion++
        // 色卡也要一起丢掉：这张封面**换掉了**（用户手动搜了一张新的），
        // 留着旧色卡的话，屏幕上是新封面、背景却还是旧封面取出来的颜色。
        forgetTint("t:c$coverKey")
    }

    /**
     * 解码并发闸门，**按是否在滑动分两档**。
     *
     * 早先是"滚动中直接不加载"，代价是停下来那一刻所有可见行同时开工，
     * 全挤在一个 Semaphore(6) 后面排队 —— 用户看到的就是"停下后还要等 0.5~1 秒"。
     * 现在滚动中不禁止、只降并发：滑动时最多 2 个在解，停下来立刻回到 6 个。
     * 滚到哪，路上那几行的封面其实已经解好了，占位图基本不出现。
     *
     * 非滑动档取 6：一屏十来行基本能一波跑完，而 256px 的 RGB_565 缩略图
     * 每张约 128KB，6 张同时在解也就几百 KB，内存无压力。
     */
    private val idleGate = Semaphore(permits = 6)
    private val scrollGate = Semaphore(permits = 2)

    /** 在闸门里跑 [block]。`paused` 在**真正开工时**读，排队期间状态变了也能跟上 */
    private suspend fun <T> withDecodeGate(block: suspend () -> T): T =
        if (paused) scrollGate.withPermit { block() } else idleGate.withPermit { block() }

    /**
     * 列表 / 网格滚动中 —— 只用来**降低**解码并发（见 [withDecodeGate]），不再用来跳过加载。
     *
     * 它**不再是 `LaunchedEffect` 的 key**：当 key 用的话，滚动开始和结束各会重启一次
     * 整列表的 effect，几十行同时重新发起加载 —— 那正是"停下后集体开工"的成因之一。
     * 保持 [mutableStateOf] 是因为它仍然由列表那边写入、并要参与快照。
     */
    var paused: Boolean by mutableStateOf(false)

    /** 在线补的封面：songId -> 位图。值为 null 表示"确认没有"，避免重复请求 */
    val onlineCovers: SnapshotStateMap<Long, ImageBitmap?> = mutableStateMapOf()

    /** onlineCovers 的上限：每张最多 FULL_PX(800) 约 1.3~2.5MB，不设限会一路涨到 OOM */
    private const val MAX_ONLINE = 120

    fun albumContentUri(albumId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)

    @Suppress("DEPRECATION")
    fun legacyAlbumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    /**
     * 取在线补的封面。在 Composition 里读会自动订阅变化。
     *
     * ⚠️ 判据必须是 `!= 0`，**不能写成 `> 0`** ——
     * 在线歌的 id 是**负数**（见 `com.sonance.player.data.remoteSongId`）。
     * 写成 `> 0` 的后果不是"少一张封面"这么简单：
     * ① [putOnline] 存不进去；② 这里连 `SnapshotStateMap` 都**不读**，
     * 于是组合不会订阅它 —— [com.sonance.player.ui.components.CoverArt] 的
     * `LaunchedEffect` 依赖的 key 又都不变，封面下载完之后**界面不会刷新**。
     * 表现是"在线歌的封面要滚走再滚回来才出现"，而日志里一切正常。
     *
     * 0 是"没有歌曲"的哨兵，只有它该短路。
     */
    fun online(songId: Long): ImageBitmap? = if (songId != 0L) onlineCovers[songId] else null

    fun putOnline(songId: Long, bitmap: ImageBitmap?) {
        // 同 [online]：在线歌是负数 id，不能按 `<= 0` 挡掉
        if (songId == 0L) return
        onlineCovers[songId] = bitmap
        // 简单按插入顺序淘汰：超过上限就丢掉最早的一批
        if (onlineCovers.size > MAX_ONLINE) {
            onlineCovers.keys.take(onlineCovers.size - MAX_ONLINE).forEach { onlineCovers.remove(it) }
        }
    }

    fun clearOnlineCovers() = onlineCovers.clear()

    /* ============================================================
     * 封面取色：把封面的颜色淡淡渗到背景上时用
     * ============================================================ */

    /** 取样边长。24 足够算出「这一段大概什么颜色」，再大只是白费 CPU */
    private const val TINT_SAMPLE = 24

    /**
     * 预取封面时的解码边长。
     *
     * 取色只要 24×24，这里给大一点是为了让预取出来的位图还能顶一次真实的封面显示
     * （[bucketOf] 会把尺寸归一到几档，192 落在小档里，代价很低）。
     */
    private const val PREFETCH_PX = 192

    /**
     * 按封面来源存的色卡。
     *
     * 用 [SnapshotStateMap] 而不是普通 map：色卡是异步解码时顺手算出来的，
     * 界面读它要能自动重组（跟 [onlineCovers] 一个道理）。
     *
     * 键跟来源绑定 —— 本地封面按专辑共用（`a:<albumId>` / `u:<uri>`），
     * 磁盘上下载的在线封面按歌（`c:<coverKey>`）。
     */
    private val tints: SnapshotStateMap<String, CoverTint> = mutableStateMapOf()

    /**
     * 界面同步读色卡；还没取到就是 null，此时界面退回皮肤占位色。
     *
     * 查询顺序**必须和 [com.sonance.player.ui.components.CoverArt] 的显示优先级一致**
     * （那里是 `online ?: bitmap`，在线封面优先）。反过来的话，用户手动给某首歌
     * 换了一张在线封面时，屏幕上显示的是新图、晕染却还按本地专辑封面取色 ——
     * 颜色和封面不是一回事。
     */
    fun tintOf(albumId: Long, sourceUri: Uri?, coverKey: String?): CoverTint? {
        if (coverKey != null) tints["t:c$coverKey"]?.let { return it }
        return tints["t:" + missingKeyOf(albumId, sourceUri)]
    }

    private fun rememberTint(key: String, bitmap: Bitmap) {
        if (tints.containsKey(key)) return
        val tint = extractTint(bitmap) ?: return
        tints[key] = tint
    }

    private fun forgetTint(key: String) {
        if (tints.remove(key) != null) artworkGoneVersion++
    }

    /**
     * 预取色卡：提前把某首歌的封面解出来、算一遍颜色。
     *
     * 色卡是「解码封面时顺手算」的，所以换歌瞬间新歌多半还没有颜色可用 ——
     * 那段时间晕染只能沿用上一首的色，表现为「颜色慢半拍才变过来」。
     * 提前给相邻的下一首算一遍，切歌时新色已经就绪，过渡就完全跟得上了。
     *
     * 走的就是 [load] 那条路（封面通常已在内存 / 磁盘缓存里，命中时几乎不花钱），
     * 顺带把封面位图也预热了，上下滑动切歌时封面出现得更快。
     *
     * 已经有色卡就直接返回 —— 别为了"预取"把已经算好的东西再解一遍。
     */
    suspend fun prefetchTint(context: Context, albumId: Long, sourceUri: Uri?, coverKey: String?) {
        if (tintOf(albumId, sourceUri, coverKey) != null) return
        withContext(Dispatchers.IO) {
            try {
                load(context, albumId, sizePx = PREFETCH_PX, sourceUri = sourceUri, coverKey = coverKey)
            } catch (c: CancellationException) {
                // 用户快速跳歌时这个协程会被取消。必须原样抛出 —— 吞掉 CancellationException
                // 会让结构化并发失效（外层以为还活着）。
                throw c
            } catch (t: Throwable) {
                // 预取是尽力而为：失败不影响任何功能，只是这首歌的颜色要等界面自己去解码时才算。
                Log.w("SonanceCover", "预取色卡失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * 取封面**下边缘**左 / 中 / 右三段的平均色。
     *
     * 为什么不是整图主色：晕染只画在封面下方，用整图主色会和封面本身脱节 ——
     * 上半是天空、下半是草地的封面，下方却晕出天空的蓝，看着就像贴上去的。
     */
    private fun extractTint(src: Bitmap): CoverTint? {
        val small = runCatching {
            Bitmap.createScaledBitmap(src, TINT_SAMPLE, TINT_SAMPLE, true)
        }.getOrNull() ?: return null
        val px = IntArray(TINT_SAMPLE * TINT_SAMPLE)
        val got = runCatching {
            small.getPixels(px, 0, TINT_SAMPLE, 0, 0, TINT_SAMPLE, TINT_SAMPLE)
        }.isSuccess
        if (src !== small) small.recycle()
        if (!got) return null

        // 只看下边缘：底部约 1/3 的高度
        val y0 = TINT_SAMPLE * 2 / 3
        val seg = TINT_SAMPLE / 3
        var left = average(px, 0, seg, y0, strict = true)
        var center = average(px, seg, seg * 2, y0, strict = true)
        var right = average(px, seg * 2, TINT_SAMPLE, y0, strict = true)
        // 严格取样（跳过近黑 / 近白）一段都没剩 —— 说明这张封面下半是整片黑或整片白，
        // 黑白人像照片基本都是这种情况（人物衣服占满下半）。
        // 这时退一步把全部像素算进来，至少取出**封面自己的灰**。
        //
        // 不能就这么放弃：放弃后会回落到皮肤的占位色，于是黑白封面会被染成
        // 皮肤的青绿 —— 颜色完全是错的（用户就是这么发现问题的）。
        if (left == null || center == null || right == null) {
            left = left ?: average(px, 0, seg, y0, strict = false)
            center = center ?: average(px, seg, seg * 2, y0, strict = false)
            right = right ?: average(px, seg * 2, TINT_SAMPLE, y0, strict = false)
        }
        if (left == null || center == null || right == null) return null
        return CoverTint(normalize(left), normalize(center), normalize(right))
    }

    /**
     * 一段区域的平均色。
     *
     * [strict] = true 时跳过近黑 / 近白像素 —— 它们一多就会把平均色拖成脏灰；
     * 但封面下半整片是黑时会被跳光，那时上层会用 `strict = false` 再取一次。
     */
    private fun average(px: IntArray, x0: Int, x1: Int, y0: Int, strict: Boolean): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in y0 until TINT_SAMPLE) {
            for (x in x0 until x1) {
                val c = px[y * TINT_SAMPLE + x]
                if (Color.alpha(c) < 200) continue
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                if (strict) {
                    if (maxOf(rr, gg, bb) < 24) continue   // 近黑
                    if (minOf(rr, gg, bb) > 232) continue  // 近白
                }
                r += rr
                g += gg
                b += bb
                n++
            }
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * 把色夹到「能在背景上淡淡显出来」的范围：
     * 太暗在深色背景上等于没画，太灰显脏，太亮刺眼。
     */
    private fun normalize(argb: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(Color.red(argb), Color.green(argb), Color.blue(argb), hsl)
        // 纯灰（黑白封面）**不要**去提饱和度：灰的 hue 算出来是 0，也就是红，
        // 一提就变成暗红 —— 封面明明是黑白的，背景却泛红，一眼就是错的。
        // 这类封面保留它自己的灰，只把亮度抬到看得见的程度。
        val gray = hsl[1] < 0.06f
        if (!gray) hsl[1] = hsl[1].coerceIn(0.12f, 0.85f)
        // 亮度下限 0.36：纯黑封面提上来是深灰，深色皮肤上也看得见；
        // 上限 0.60：避免接近白色的封面色把背景洗成一片白。
        hsl[2] = hsl[2].coerceIn(0.36f, 0.60f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** 已确认没有封面的 albumId —— 上层据此决定要不要走在线补全 */
    fun isMissing(albumId: Long): Boolean =
        albumId <= 0 || synchronized(missing) { missing.contains("a:$albumId") }

    /**
     * **同步**查内存缓存，命中就立刻返回，否则 null。
     *
     * 给 UI 首帧用。为什么需要它：`load()` 是 suspend 的，即使缓存命中也要
     * 走一次协程调度（`withContext(Dispatchers.IO)`），实际要等好几帧 ——
     * 表现就是"已经加载过的封面，滚回来还要等半秒才出来"。
     *
     * 另外它也**不看 [paused]**：滚动中看到已经缓存的封面应该立刻显示，
     * 只有"需要解码的新封面"才该在滚动时跳过。
     *
     * 在 Composition 里调用是安全的：内部只有两次 `LruCache.get`（各自加锁，
     * 但只是一次哈希查找），可见行数量级下开销可忽略。
     */
    fun peek(
        albumId: Long,
        sizePx: Int,
        sourceUri: Uri? = null,
        coverKey: String? = null
    ): ImageBitmap? {
        if (albumId <= 0 && sourceUri == null && coverKey == null) return null
        val size = bucketOf(sizePx)
        val cache = cacheFor(size)
        // 先查本地键（同专辑共用），再查带 coverKey 的完整键
        cache.get(localKeyOf(albumId, size, sourceUri))?.let { return it }
        return cache.get(keyOf(albumId, size, sourceUri, coverKey))
    }

    /**
     * 同 [peek]，但**本尺寸档没有时退到缩略图档**。
     *
     * 这是"列表里有封面、进播放页却先闪一张占位图"的最后一公里：
     * 900px 那张要重新探一次 MediaStore（一轮 binder IPC + 解码），
     * 而列表滚过的时候 256 档早就躺在内存里了。先拿真图顶上，
     * 等大图解好再换，比先闪占位图强得多。
     *
     * 只给 **首帧** 用（`CoverArt` 的 `remember` 初值）。调用方随后还是会
     * 发起一次 [load] 去取本尺寸档的图 —— 所以这里返回小图不会让大图永远不加载。
     */
    fun peekAny(
        albumId: Long,
        sizePx: Int,
        sourceUri: Uri? = null,
        coverKey: String? = null
    ): ImageBitmap? {
        peek(albumId, sizePx, sourceUri, coverKey)?.let { return it }
        if (bucketOf(sizePx) <= THUMB_BUCKET) return null
        peek(albumId, THUMB_BUCKET, sourceUri, coverKey)?.let { return it }
        return null
    }

    /**
     * 预取用的独立作用域。
     *
     * 不能挂在 `LaunchedEffect` 上：预取是"顺手提前做"的事，而滚动中列表项的 effect
     * 每帧都在重建/取消，挂上去的话预取刚发起就被取消，等于没做。
     * 放在这里，调用方被取消时取消的只是**等待**，实际解码照常跑完并落进缓存。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 同一张封面正在解码时的共享任务。
     *
     * 为什么需要：快速滑动时同一张专辑的十几首歌会**同时**进入视野，
     * 各自的 `load` 同时未命中缓存、同时去探 MediaStore —— 十几轮重复的 binder IPC。
     * 有了它，第二个请求直接等第一个的结果。
     *
     * 用 [Deferred] 而不是"标记位 + 轮询"：等待方 `await()` 拿到的是同一个位图实例，
     * 而且调用方被取消时**不会**连带取消这个共享任务（取消的只是 await）。
     */
    private val inFlight = HashMap<Key, Deferred<ImageBitmap?>>()

    /**
     * 预取一批封面。给列表用：算出即将进入视野的行，提前把它们的封面解出来放进缓存。
     *
     * 已经是缓存的、以及正在解的直接跳过（后者靠 [inFlight] 去重），
     * 所以滚动中每帧调用一次也很便宜。
     *
     * **不阻塞调用方**，也不受调用方生命周期影响（见 [workScope]）。
     */
    fun prefetch(context: Context, requests: List<CoverRequest>, sizePx: Int) {
        if (requests.isEmpty()) return
        val app = context.applicationContext
        for (r in requests) {
            if (r.albumId <= 0 && r.sourceUri == null && r.coverKey == null) continue
            if (peek(r.albumId, sizePx, r.sourceUri, r.coverKey) != null) continue
            workScope.launch {
                try {
                    load(app, r.albumId, sizePx, r.sourceUri, r.coverKey)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 预取是尽力而为：失败只意味着这一行要等界面自己来解
                    Log.w("SonanceCover", "预取封面失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    /**
     * 冷启动预热。
     *
     * 进程重启后内存缓存是空的，光靠"列表滚到哪解到哪"就会有那么一两秒
     * 满屏占位图 —— 用户看到的就是"重启后封面没了，要等一会才回来"。
     * 曲库就位后把开头这些歌的封面提前解出来（命中的多半只是读一次磁盘文件），
     * 第一屏就能直接出图。
     *
     * 不阻塞调用方、也不受调用方生命周期影响（走的还是 [prefetch] / [workScope]）。
     */
    fun warmUp(context: Context, requests: List<CoverRequest>, sizePx: Int, limit: Int = 80) {
        if (requests.isEmpty()) return
        prefetch(context, requests.take(limit), sizePx)
    }

    suspend fun load(
        context: Context,
        albumId: Long,
        sizePx: Int = 512,
        sourceUri: Uri? = null,
        /**
         * 这首歌的在线封面缓存 key（[OnlineCover.keyOf]）。
         *
         * 传了它，本地没有封面时会继续去读**之前下载并落盘**的那张 ——
         * 这是「重启后列表要等播放到这首歌才显示封面」的修复点：
         * 以前只有 [onlineCovers]（纯内存）能提供下载封面，重启即丢。
         */
        coverKey: String? = null
    ): ImageBitmap? {
        if (albumId <= 0 && sourceUri == null && coverKey == null) return null
        val size = bucketOf(sizePx)
        val key = keyOf(albumId, size, sourceUri, coverKey)
        val localKey = localKeyOf(albumId, size, sourceUri)
        val cache = cacheFor(size)

        // 缓存命中直接返回，不切线程、不看 paused。
        // 先查**本地键**：同专辑的第二首歌起就能直接命中，不必再探一次 MediaStore。
        cache.get(localKey)?.let { return it }
        cache.get(key)?.let { return it }

        // 两条都确认过"没有"才短路：本地没有 + 磁盘缓存也没有。
        // 少了第二条判断，同专辑里第一首失败就会把整张专辑的下载封面一起挡掉。
        val localGone = isMissingKey(missingKeyOf(albumId, sourceUri))
        val coverGone = coverKey == null || synchronized(coverMiss) { coverMiss.contains(coverKey) }
        // 磁盘缓存有货就**不能**短路：那个"没有"是上一次探测的结论，
        // 而磁盘上的图是那之后才写进去的（比如上次播放时解出来的大图）。
        // 漏掉这一条就是"明明加载过，重启后又是空的"之一。
        val diskHas = ArtworkThumbCache.exists(context, missingKeyOf(albumId, sourceUri), size)
        if (localGone && coverGone && !diskHas) return null

        return decodeShared(context, albumId, size, key, localKey, sourceUri, coverKey)
    }

    /**
     * 发起一次解码，或者搭上同一张封面已经在跑的那次 —— 见 [inFlight]。
     *
     * 解码本身跑在 [workScope] 里，所以调用方（列表项、预取）被取消时
     * 它照常跑完并落进缓存，下一个滚到这里的行直接命中。
     */
    private suspend fun decodeShared(
        context: Context,
        albumId: Long,
        size: Int,
        key: Key,
        localKey: Key,
        sourceUri: Uri?,
        coverKey: String?
    ): ImageBitmap? {
        val job = synchronized(inFlight) {
            inFlight[key] ?: workScope.async(start = CoroutineStart.LAZY) {
                try {
                    withDecodeGate {
                        val cache = cacheFor(size)
                        // 排队期间可能已经被别人解好了，再查一次
                        cache.get(localKey)?.let { return@withDecodeGate it }
                        cache.get(key)?.let { return@withDecodeGate it }
                        decode(context, albumId, size, key, localKey, sourceUri, coverKey)
                    }
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        // 懒启动：注册进 inFlight 之后才开跑，避免任务在登记前就结束、finally 白删一次
        job.start()
        return job.await()
    }

    private fun isMissingKey(key: String): Boolean =
        synchronized(missing) { missing.contains(key) }

    private fun decode(
        context: Context,
        albumId: Long,
        size: Int,
        key: Key,
        localKey: Key,
        sourceUri: Uri?,
        coverKey: String?
    ): ImageBitmap? {
        val missKey = missingKeyOf(albumId, sourceUri)
        val cache = cacheFor(size)
        // 只有**本地**来源才配磁盘缩略图：在线歌的 sourceUri 是带时效的取流地址，
        // 拿它当键存下来的图第二天就是垃圾；而 coverKey 那条路本来就有 [CoverCache] 兜着。
        val localSource = albumId > 0 || (sourceUri != null && !sourceUri.isRemote())
        var bitmap: Bitmap? = null

        // ---- 0) 磁盘封面缓存 ----
        // 进程重启后内存缓存是空的，但磁盘上还留着上次解码好的图。
        // 读它只要一次文件 IO，能整段跳过 MediaStore 的 binder IPC —— 首屏慢的大头。
        //
        // ⚠️ 这里**不再限制只查缩略图档**：播放页的 900px 之前被排除在外，
        // 于是每次进播放页都要重新探一遍 MediaStore，慢的时候就是"没有封面"。
        if (localSource) {
            ArtworkThumbCache.lookup(context, missKey, size)?.let { bytes ->
                val cached = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                if (cached != null) {
                    rememberTint("t:$missKey", cached)
                    val image = cached.asImageBitmap()
                    cache.put(localKey, image)
                    return image
                }
                // 文件坏了（比如写到一半被系统的清理任务截断）：当没有，继续往下探测
                ArtworkThumbCache.remove(context, missKey, size)
            }
        }

        // ---- 1) MediaStore 专辑封面 + 2) 音频文件内嵌封面 ----
        if (!isMissingKey(missKey)) {
            if (albumId > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bitmap = runCatching {
                        context.contentResolver.loadThumbnail(
                            albumContentUri(albumId), Size(size, size), null
                        )
                    }.getOrNull()
                }
                if (bitmap == null) {
                    bitmap = runCatching {
                        context.contentResolver.openInputStream(legacyAlbumArtUri(albumId))?.use {
                            decodeSampled(it.readBytes(), size)
                        }
                    }.getOrNull()
                }
            }

            if (bitmap == null && sourceUri != null && !sourceUri.isRemote()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bitmap = runCatching {
                        context.contentResolver.loadThumbnail(sourceUri, Size(size, size), null)
                    }.getOrNull()
                }
                if (bitmap == null) {
                    bitmap = readEmbeddedPicture(context, sourceUri, size)
                }
            }

            if (bitmap == null) markMissing(missKey)
        }

        // 本地拿到图 → 按**本地键**缓存（同专辑、同尺寸的歌共用一份）。
        // 这是"同专辑逐首重复探测 MediaStore"的修复点。
        if (bitmap != null) {
            // 解码已经发生过了，顺手把色卡算出来 —— 不额外解码，也不额外读文件
            rememberTint("t:$missKey", bitmap)
            // 大图压到 [LARGE_PX]：不压的话三页分页器就能把大图 LRU 撑爆（见它的注释）
            val fitted = if (size > THUMB_BUCKET) fitLarge(bitmap) else bitmap
            val image = fitted.asImageBitmap()
            cache.put(localKey, image)
            // 顺手落盘：下次冷启动滚到这里就不用再探 MediaStore 了。
            // 两个档位都存（大图档落盘时会自己缩到 768），播放页重启后也能立刻出图。
            if (localSource) {
                ArtworkThumbCache.store(context, missKey, size, fitted)
            }
            return image
        }

        // ---- 3) 本地确实没有 → 用之前下载并落盘的在线封面 ----
        // 这一步是本地文件读取，没有 IPC，所以不受上面 missing 标记的约束；
        // 但同样要记一笔「磁盘也没有」，否则每次滚动都会重新 stat 一遍。
        // 下载封面是**按歌**的（每首的 coverKey 不同），所以这里用完整键。
        if (coverKey != null) {
            val hit = CoverCache.lookup(context, coverKey)
            if (hit is CoverCache.Lookup.Hit) bitmap = decodeSampled(hit.bytes, size)
            if (bitmap == null) markCoverMiss(coverKey)
        }

        if (bitmap == null) {
            // 最后一重兜底：本地探测失败、磁盘缓存里也没有在线下载封面，
            // 但缩略图档的磁盘缓存里**有**这张图 —— 那就先拿来用。
            // 大图档是后来才加的，老用户磁盘上只有 256 档那份；
            // 与其给占位图，不如给一张稍软的真实封面。
            if (localSource && size > THUMB_BUCKET) {
                ArtworkThumbCache.lookup(context, missKey, THUMB_BUCKET)?.let { bytes ->
                    val small = runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (small != null) {
                        rememberTint("t:$missKey", small)
                        val image = small.asImageBitmap()
                        cache.put(localKey, image)
                        return image
                    }
                    ArtworkThumbCache.remove(context, missKey, THUMB_BUCKET)
                }
            }
            return null
        }
        if (coverKey != null) rememberTint("t:c$coverKey", bitmap)
        val image = if (size > THUMB_BUCKET) fitLarge(bitmap).asImageBitmap() else bitmap.asImageBitmap()
        cache.put(key, image)
        return image
    }

    /** 等比缩到边长不超过 [LARGE_PX]；本来就小就原样返回，不做无谓的有损放大 */
    private fun fitLarge(src: Bitmap): Bitmap {
        val biggest = maxOf(src.width, src.height)
        if (biggest <= LARGE_PX) return src
        val scale = LARGE_PX.toFloat() / biggest
        return runCatching {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        }.getOrDefault(src)
    }

    /**
     * 用 [MediaMetadataRetriever] 抠内嵌封面。
     *
     * ⚠️ 这里必须用 `release()`，**不能用 `use { }`**：
     * `MediaMetadataRetriever` 是 **API 29** 才 implements `AutoCloseable` 的
     * （`close()` 继承自它，类本身只声明了 `release()`），而本应用 minSdk = 24。
     * 用 `use` 会在 Android 7~9 上抛 `NoSuchMethodError` —— 虽然被外层 runCatching
     * 吞掉不会崩，但**已经读到的封面会被丢弃**，等于内嵌封面兜底完全失效，
     * 而且 `release()` 永不执行，每次泄漏一个 native 实例。
     */
    private fun readEmbeddedPicture(context: Context, sourceUri: Uri, size: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, sourceUri)
            mmr.embeddedPicture?.let { decodeSampled(it, size) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        val max = maxOf(opts.outWidth, opts.outHeight)
        while (max / sample > target * 2) sample *= 2
        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }

    /**
     * 清掉已缓存的封面（重新扫描曲库时调用）。
     *
     * 磁盘缩略图也要一起清：它按专辑 id / URI 做键，重扫之后这两样都可能变，
     * 留着就是永远命不中的垃圾。删除是文件 IO，丢给 [workScope] 去做，
     * 别让调用方（可能正在主线程）等它。
     */
    fun clear(context: Context) {
        thumbCache.evictAll()
        largeCache.evictAll()
        synchronized(missing) { missing.clear() }
        synchronized(coverMiss) { coverMiss.clear() }
        // 色卡也要清：封面缓存都清了，按旧封面算出来的颜色自然也不作数了
        tints.clear()
        artworkGoneVersion++
        onlineCovers.clear()
        val app = context.applicationContext
        workScope.launch { ArtworkThumbCache.clear(app) }
    }
}
