package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
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
 * [paused] 由列表 / 网格的滚动状态驱动：滚动过程中跳过加载。
 * 注意只影响"新发起的加载"，不动已经拿到图的那些 —— 早先的实现在这里会把
 * 已加载的封面重新赋成 null，导致滚动时封面闪回占位图。
 */
object ArtworkLoader {

    /** 请求尺寸归一到几档，避免同一张专辑为每个像素尺寸都缓存一份 */
    private val SIZE_BUCKETS = intArrayOf(128, 256, 512, 1024)

    private fun bucketOf(sizePx: Int): Int {
        for (b in SIZE_BUCKETS) if (sizePx <= b) return b
        return SIZE_BUCKETS.last()
    }

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

    /** 按**字节**算上限，不是按条数 —— 80 张 900px 位图能吃掉一百多 MB */
    private val maxBytes: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        (heap / 8L).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
    }

    private val cache = object : LruCache<Key, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * 确认过没有封面的条目，避免重复走一遍失败的 IPC / 文件解析。
     * 键与 [Key] 同构，未知专辑按 URI 区分，不会"一首没封面 = 全都没封面"。
     */
    private val missing = HashSet<String>(256)

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

    /** 封面刚写入缓存后调用，让上面那条短路失效 */
    fun forgetCoverMiss(coverKey: String?) {
        if (coverKey == null) return
        synchronized(coverMiss) { coverMiss.remove(coverKey) }
    }

    /** 同时进行的解码任务上限，防止快速滚动时几十个解码一起上 */
    private val decodeGate = Semaphore(permits = 2)

    /**
     * 列表 / 网格滚动中：跳过**新发起**的加载。
     *
     * 必须是 Compose 的 [mutableStateOf] 而不是普通字段 ——
     * `CoverArt` 把它当 `LaunchedEffect` 的 key 用，普通字段不参与快照系统，
     * 变化不会重启 effect，滚动停止后那些被跳过的行就永远不补图了。
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

    /** 取在线补的封面。在 Composition 里读会自动订阅变化 */
    fun online(songId: Long): ImageBitmap? = if (songId > 0) onlineCovers[songId] else null

    fun putOnline(songId: Long, bitmap: ImageBitmap?) {
        if (songId <= 0) return
        onlineCovers[songId] = bitmap
        // 简单按插入顺序淘汰：超过上限就丢掉最早的一批
        if (onlineCovers.size > MAX_ONLINE) {
            onlineCovers.keys.take(onlineCovers.size - MAX_ONLINE).forEach { onlineCovers.remove(it) }
        }
    }

    fun clearOnlineCovers() = onlineCovers.clear()

    /** 已确认没有封面的 albumId —— 上层据此决定要不要走在线补全 */
    fun isMissing(albumId: Long): Boolean =
        albumId <= 0 || synchronized(missing) { missing.contains("a:$albumId") }

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

        // 缓存命中直接返回，不切线程、不暂停检查
        cache.get(key)?.let { return it }
        // 滚动中跳过新发起的加载
        if (paused) return null

        // 两条都确认过"没有"才短路：本地没有 + 磁盘缓存也没有。
        // 少了第二条判断，同专辑里第一首失败就会把整张专辑的下载封面一起挡掉。
        val localGone = isMissingKey(missingKeyOf(albumId, sourceUri))
        val coverGone = coverKey == null || synchronized(coverMiss) { coverMiss.contains(coverKey) }
        if (localGone && coverGone) return null

        return withContext(Dispatchers.IO) {
            decodeGate.withPermit {
                cache.get(key)?.let { return@withPermit it }
                decode(context, albumId, size, key, sourceUri, coverKey)
            }
        }
    }

    private fun isMissingKey(key: String): Boolean =
        synchronized(missing) { missing.contains(key) }

    private fun decode(
        context: Context,
        albumId: Long,
        size: Int,
        key: Key,
        sourceUri: Uri?,
        coverKey: String?
    ): ImageBitmap? {
        val missKey = missingKeyOf(albumId, sourceUri)
        var bitmap: Bitmap? = null

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

            if (bitmap == null && sourceUri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bitmap = runCatching {
                        context.contentResolver.loadThumbnail(sourceUri, Size(size, size), null)
                    }.getOrNull()
                }
                if (bitmap == null) {
                    bitmap = readEmbeddedPicture(context, sourceUri, size)
                }
            }

            if (bitmap == null) synchronized(missing) { missing.add(missKey) }
        }

        // ---- 3) 本地确实没有 → 用之前下载并落盘的在线封面 ----
        // 这一步是本地文件读取，没有 IPC，所以不受上面 missing 标记的约束；
        // 但同样要记一笔「磁盘也没有」，否则每次滚动都会重新 stat 一遍。
        if (bitmap == null && coverKey != null) {
            val hit = CoverCache.lookup(context, coverKey)
            if (hit is CoverCache.Lookup.Hit) bitmap = decodeSampled(hit.bytes, size)
            if (bitmap == null) synchronized(coverMiss) { coverMiss.add(coverKey) }
        }

        if (bitmap == null) return null
        val image = bitmap.asImageBitmap()
        cache.put(key, image)
        return image
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

    /** 清掉已缓存的封面（重新扫描曲库时调用） */
    fun clear() {
        cache.evictAll()
        synchronized(missing) { missing.clear() }
        synchronized(coverMiss) { coverMiss.clear() }
        onlineCovers.clear()
    }
}
