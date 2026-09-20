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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专辑封面加载。
 *
 * 取图顺序：
 * 1. 内存 LRU（按 albumId）
 * 2. MediaStore 专辑封面：API 29+ 用 `loadThumbnail`，低版本回退传统 `albumart` URI
 * 3. `sourceUri`（歌曲自身的 content:// URI）：用它再试一次
 *    —— 专辑级查询拿不到时，从音频文件里抠内嵌封面（`loadThumbnail`，
 *    再不行用 `MediaMetadataRetriever.embeddedPicture`）
 * 4. 都没有 → 由界面显示占位图；上层可再通过 [putOnline] 补在线封面
 *
 * [paused] 由列表 / 网格的滚动状态驱动：滚动过程中跳过加载。
 * 注意只影响"新发起的加载"，不动已经拿到图的那些 —— 早先的实现在这里会把
 * 已加载的封面重新赋成 null，导致滚动时封面闪回占位图。
 */
object ArtworkLoader {

    private const val MAX_ENTRIES = 80
    private val cache = LruCache<Long, ImageBitmap>(MAX_ENTRIES)
    private val missing = HashSet<Long>(256)

    /** 列表 / 网格滚动中：跳过新的封面加载 */
    @Volatile
    var paused: Boolean = false

    /** 在线补的封面：songId -> 位图。值为 null 表示"确认没有"，避免重复请求 */
    val onlineCovers: SnapshotStateMap<Long, ImageBitmap?> = mutableStateMapOf()

    fun albumContentUri(albumId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)

    @Suppress("DEPRECATION")
    fun legacyAlbumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    /** 取在线补的封面。在 Composition 里读会自动订阅变化 */
    fun online(songId: Long): ImageBitmap? = if (songId > 0) onlineCovers[songId] else null

    fun putOnline(songId: Long, bitmap: ImageBitmap?) {
        if (songId > 0) onlineCovers[songId] = bitmap
    }

    fun clearOnlineCovers() = onlineCovers.clear()

    /** 已确认没有封面的 albumId —— 上层据此决定要不要走在线补全 */
    fun isMissing(albumId: Long): Boolean =
        albumId <= 0 || synchronized(missing) { missing.contains(albumId) }

    suspend fun load(
        context: Context,
        albumId: Long,
        sizePx: Int = 512,
        sourceUri: Uri? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (albumId <= 0 && sourceUri == null) return@withContext null
        if (albumId > 0) {
            cache.get(albumId)?.let { return@withContext it }
            synchronized(missing) { if (missing.contains(albumId)) return@withContext null }
        }

        var bitmap: Bitmap? = null

        // ---- 2) MediaStore 专辑封面 ----
        if (albumId > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bitmap = runCatching {
                    context.contentResolver.loadThumbnail(
                        albumContentUri(albumId), Size(sizePx, sizePx), null
                    )
                }.getOrNull()
            }
            if (bitmap == null) {
                bitmap = runCatching {
                    context.contentResolver.openInputStream(legacyAlbumArtUri(albumId))?.use {
                        decodeSampled(it.readBytes(), sizePx)
                    }
                }.getOrNull()
            }
        }

        // ---- 3) 退回音频文件自身的内嵌封面 ----
        if (bitmap == null && sourceUri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bitmap = runCatching {
                    context.contentResolver.loadThumbnail(sourceUri, Size(sizePx, sizePx), null)
                }.getOrNull()
            }
            if (bitmap == null) {
                bitmap = runCatching {
                    MediaMetadataRetriever().use { mmr ->
                        mmr.setDataSource(context, sourceUri)
                        mmr.embeddedPicture?.let { decodeSampled(it, sizePx) }
                    }
                }.getOrNull()
            }
        }

        if (bitmap == null) {
            if (albumId > 0) synchronized(missing) { missing.add(albumId) }
            null
        } else {
            val image = bitmap.asImageBitmap()
            if (albumId > 0) cache.put(albumId, image)
            image
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
        onlineCovers.clear()
    }
}
