package com.sonance.player.data

import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs

/**
 * 把封面写进**歌曲文件本身**。
 *
 * ## 为什么需要它
 *
 * 应用平时把下载的封面放在私有目录（[CoverCache]），封面只跟这台设备上的这个应用走：
 * 换手机、把歌拷给别人、用别的播放器打开，封面就没了。写进文件里封面才是**跟着歌走**的。
 *
 * ## 支持的容器
 *
 * - **MP3**：ID3v2 `APIC`。走 [Id3Writer.withCoverPreserving]，原有标签里的音轨号、
 *   流派、年份、注释等帧全部原样保留，只替换 `APIC`。
 * - **FLAC**：`METADATA_BLOCK_PICTURE`（type 6）。元数据块是链式的：保留除旧 PICTURE
 *   以外的所有块，在音频帧之前补一个 PICTURE，并修正 last-metadata-block 标记。
 *   音频帧整体后移不影响播放（FLAC 不记录音频绝对偏移）。
 * - **M4A / MP4**：**不支持**。现有的 [Mp4TagWriter] 会整块重建 `moov.udta.meta`、
 *   丢掉用户原有的年份 / 流派 / 歌词等原子；只替换 `covr` 的增量写法还没做。
 *   自动补封面走这里会返回 [Result.UnsupportedFormat]，封面仍留在应用私有目录。
 *
 * ## 写入顺序（尽量不弄坏原文件）
 *
 * 1. 先在 `cacheDir` 生成带新封面的**临时文件**；
 * 2. 校验临时文件：`MediaMetadataRetriever` 能读出封面、时长与原文件基本一致；
 * 3. 把原文件备份到 `cacheDir`；
 * 4. 覆盖回原文件；
 * 5. 覆盖后再校验一次（时长），不对就用备份回滚。
 *
 * ⚠️ 第 4 步是通过 `content://` 流式覆盖，系统没有提供原子替换接口：
 * 如果进程在这一步被杀，原文件仍可能损坏（备份留在 `cacheDir`，但不会自动回滚）。
 * 所以自动补封面只在**确实能写**的文件上执行，写不动的（分区存储没授权）会返回
 * [Result.NeedsPermission]，由调用方决定是否拉起系统授权框。
 */
object CoverTagWriter {

    private const val TAG = "SonanceCover"
    private const val BUF = 64 * 1024

    sealed interface Result {

        /** 写进去了 */
        data object Written : Result

        /**
         * 文件里已经有内嵌封面，本次没有重写。
         *
         * 自动补封面用它做幂等：封面已经跟着歌走了，没必要再改一遍文件
         * （重复写会白白改变文件大小与修改时间）。
         */
        data object AlreadyHasCover : Result

        /** 在线歌没有本地文件可写 */
        data object NotLocal : Result

        /** 容器不在支持范围内（FLAC 之外的 WAV / OGG，或 M4A / MP4） */
        data object UnsupportedFormat : Result

        /**
         * 被系统拦下，需要用户确认。
         *
         * [intentSender] 非空 → 用 `StartIntentSenderForResult` 拉起确认框（API 29+）；
         * 为空 → API 28 及以下，得先授予 `WRITE_EXTERNAL_STORAGE`
         */
        data class NeedsPermission(val intentSender: IntentSender?) : Result

        /** 网络之外的其它失败：读不出来、写不进去、磁盘满、校验没过 */
        data object Failed : Result
    }

    private enum class Kind { MP3, FLAC }

    /**
     * 把 [cover] 写进 [song] 对应的文件。
     *
     * @param cover 图片原始字节（JPEG 或 PNG），不会被重新编码
     * @param skipIfPresent true = 文件里已经有封面就直接返回 [Result.AlreadyHasCover]
     *                      （自动补封面用；手动「选封面」必须为 false，否则换不掉旧图）
     */
    suspend fun write(
        context: Context,
        song: Song,
        cover: ByteArray,
        skipIfPresent: Boolean = false
    ): Result {
        if (!song.isLocal) return Result.NotLocal
        val kind = kindOf(song) ?: return Result.UnsupportedFormat
        if (cover.isEmpty()) return Result.Failed

        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val stamp = System.nanoTime()
            val temp = File(app.cacheDir, "cover-$stamp.tmp")
            val backup = File(app.cacheDir, "cover-$stamp.bak")
            var replaced = false
            try {
                if (skipIfPresent && embeddedCoverExists(app, song.uri)) {
                    return@withContext Result.AlreadyHasCover
                }

                val originalMs = probeDuration(app, song.uri) ?: song.durationMs

                // ---- 1) 生成带新封面的临时文件 ----
                val built = when (kind) {
                    Kind.MP3 -> buildMp3(app, song, cover, temp)
                    Kind.FLAC -> buildFlac(app, song.uri, cover, temp)
                }
                if (!built) {
                    Log.w(TAG, "生成带封面的临时文件失败：${song.displayName}")
                    return@withContext Result.Failed
                }

                // ---- 2) 校验临时文件（封面在、时长没变）----
                if (!verifyFile(temp, originalMs)) {
                    Log.w(TAG, "临时文件校验未通过，放弃覆盖：${song.displayName}")
                    return@withContext Result.Failed
                }

                // ---- 3) 备份原文件 ----
                if (!copyUriToFile(app, song.uri, backup)) {
                    Log.w(TAG, "备份原文件失败，放弃覆盖：${song.displayName}")
                    return@withContext Result.Failed
                }

                // ---- 4) 覆盖 ----
                replaced = true
                replaceUri(app, song.uri, temp)

                // ---- 5) 覆盖后再校验，不对就回滚 ----
                if (!verifyUri(app, song.uri, originalMs)) {
                    Log.w(TAG, "覆盖后校验未通过，回滚：${song.displayName}")
                    if (!restoreUri(app, song.uri, backup)) {
                        Log.e(TAG, "回滚失败！原文件备份保留在 ${backup.absolutePath}")
                    }
                    return@withContext Result.Failed
                }

                rescan(app, song.uri)
                Log.i(TAG, "封面已写入文件（$kind）：${song.displayName}")
                Result.Written
            } catch (t: Throwable) {
                // 已经开始覆盖才需要回滚；连写权限都没有时备份也不用动
                if (replaced && backup.length() > 0) {
                    runCatching { restoreUri(app, song.uri, backup) }
                }
                permissionWall(t, app, song.uri) ?: run {
                    Log.w(TAG, "写入封面失败: ${t.javaClass.simpleName}: ${t.message}")
                    Result.Failed
                }
            } finally {
                runCatching { temp.delete() }
                runCatching { backup.delete() }
            }
        }
    }

    /* ============================================================
     * 各容器的生成
     * ============================================================ */

    /** MP3：只换 APIC，其余帧原样搬过去 */
    private fun buildMp3(context: Context, song: Song, cover: ByteArray, out: File): Boolean {
        return runCatching {
            val mime = RestoreTagWriter.mimeOf(cover)
            val existing = readTag(context, song.uri)
            val tag = Id3Writer.withCoverPreserving(existing, cover, mime)
                ?: Id3Writer.buildTag(song.title, song.artist, song.album, cover, mime)
            val input = context.contentResolver.openInputStream(song.uri) ?: return false
            input.use { i ->
                out.outputStream().use { o -> Id3Writer.copyWithTag(i, o, tag) }
            }
            out.length() > 0
        }.getOrDefault(false)
    }

    /** FLAC：保留所有元数据块，只补 / 换 PICTURE，音频帧原样拷到末尾 */
    private fun buildFlac(context: Context, uri: Uri, cover: ByteArray, out: File): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri) ?: return false
        }.getOrNull()?.use { input ->
            runCatching {
                out.outputStream().use { o -> writeFlacWithCover(input, o, cover) }
                out.length() > 0
            }.getOrDefault(false)
        } ?: false
    }

    /**
     * @return true = 结构合法且写完了；false = 不是 FLAC / 元数据块异常
     */
    private fun writeFlacWithCover(input: InputStream, out: OutputStream, cover: ByteArray): Boolean {
        val magic = ByteArray(4)
        if (!readFully(input, magic, 4)) return false
        if (!(magic[0].toInt() == 0x66 && magic[1].toInt() == 0x4C &&
                magic[2].toInt() == 0x61 && magic[3].toInt() == 0x43)
        ) return false
        out.write(magic)

        var blocks = 0
        while (true) {
            val head = ByteArray(4)
            if (!readFully(input, head, 4)) return false
            val type = head[0].toInt() and 0x7F
            val isLast = (head[0].toInt() and 0x80) != 0
            val len = ((head[1].toInt() and 0xFF) shl 16) or
                ((head[2].toInt() and 0xFF) shl 8) or
                (head[3].toInt() and 0xFF)
            if (type <= 0 || type > 126) return false
            if (++blocks > 4096) return false

            if (type == 6) {
                // 旧封面：整块丢掉（等会儿统一写一张新的）
                if (!skipFully(input, len.toLong())) return false
            } else {
                // 先清掉 last 标记 —— 后面还要补 PICTURE，它才是最后一块
                out.write(byteArrayOf(type.toByte(), head[1], head[2], head[3]))
                if (!copyFully(input, out, len.toLong())) return false
            }
            if (isLast) break
        }

        out.write(RestoreTagWriter.flacPictureBlock(cover, RestoreTagWriter.mimeOf(cover), isLast = true))
        if (!copyFully(input, out, Long.MAX_VALUE)) return false
        return true
    }

    /* ============================================================
     * 校验
     * ============================================================ */

    /** 临时文件：必须能读出封面，且时长与原文件基本一致 */
    private fun verifyFile(file: File, expectMs: Long): Boolean {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val hasCover = mmr.embeddedPicture?.isNotEmpty() == true
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            hasCover && durationClose(ms, expectMs)
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 覆盖后的 URI：时长必须还能读出来，且与原文件基本一致 */
    private fun verifyUri(context: Context, uri: Uri, expectMs: Long): Boolean {
        val ms = probeDuration(context, uri) ?: return false
        return durationClose(ms, expectMs)
    }

    /**
     * 时长比对。
     *
     * 写标签只动了元数据，音频一字节没变，理论上完全相等；但部分编码器
     * （尤其 VBR MP3）的时长本来就有几百毫秒误差，这里留 1.2 秒 / 1% 的余量，
     * 避免把正常的文件判成损坏。
     */
    private fun durationClose(actualMs: Long, expectMs: Long): Boolean {
        if (actualMs <= 0L) return false
        if (expectMs <= 0L) return true
        val tolerance = maxOf(1200L, expectMs / 100L)
        return abs(actualMs - expectMs) <= tolerance
    }

    private fun probeDuration(context: Context, uri: Uri): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun embeddedCoverExists(context: Context, uri: Uri): Boolean {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture?.isNotEmpty() == true
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { mmr.release() }
        }
    }

    /* ============================================================
     * 文件读写
     * ============================================================ */

    private fun copyUriToFile(context: Context, uri: Uri, dst: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { i ->
            dst.outputStream().use { o -> i.copyTo(o, BUF) }
        } ?: return false
        dst.length() > 0
    }.getOrDefault(false)

    private fun replaceUri(context: Context, uri: Uri, src: File) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("openOutputStream 返回 null")
        out.use { o -> src.inputStream().use { i -> i.copyTo(o, BUF) } }
    }

    private fun restoreUri(context: Context, uri: Uri, backup: File): Boolean = runCatching {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { o -> backup.inputStream().use { i -> i.copyTo(o, BUF) } }
        true
    }.getOrDefault(false)

    /**
     * 只读文件开头的 ID3v2 标签（含 10 字节头），**不含音频数据**。
     *
     * 单独读一遍是刻意的：标签最多几百 KB，而音频是几 MB ——
     * 为了省这一遍去把整个文件读进内存，不划算。
     */
    private fun readTag(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(10)
            if (!readFully(input, head, 10)) return@use null
            val total = Id3Writer.existingTagSize(head)
            if (total <= 10) return@use null
            val body = ByteArray(total - 10)
            if (!readFully(input, body, body.size)) return@use null
            head + body
        }
    }.getOrNull()

    /**
     * 让媒体库重新读一遍这个文件。
     *
     * 不重扫的话 MediaStore 缓存的专辑缩略图还是**旧封面** ——
     * 文件里换了、列表里没换，看起来就像没生效。
     */
    private fun rescan(context: Context, uri: Uri) {
        val path = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (path.isNullOrBlank()) return
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
    }

    /* ============================================================
     * 权限
     * ============================================================ */

    /**
     * 判断这次失败是不是"系统要求用户确认"。
     *
     * @return 需要授权时返回对应结果；真的失败了返回 null
     */
    private fun permissionWall(t: Throwable, context: Context, uri: Uri): Result? {
        if (t !is SecurityException) return null

        // API 29：异常自己带着"让用户确认"的 IntentSender
        recoverableSender(t)?.let { return Result.NeedsPermission(it) }

        // API 30+：向 MediaStore 要一个写请求，系统会弹「允许修改此文件？」
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = runCatching {
                MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
            }.getOrNull()
            // 拿不到写请求说明这个 URI 根本不受 MediaStore 管（比如从文件管理器
            // 用 SAF 打开的），那就不是"授权一下就能解决"的事，直接算失败
            return if (sender != null) Result.NeedsPermission(sender) else Result.Failed
        }

        // API 28 及以下：老的文件权限模型，缺 WRITE_EXTERNAL_STORAGE
        return Result.NeedsPermission(null)
    }

    /**
     * 单独一个函数：`RecoverableSecurityException` 是 **API 29 才有的类**，
     * 把它写在独立函数里、且上面先按版本短路返回，老系统上才不会被类加载到。
     */
    private fun recoverableSender(t: Throwable): IntentSender? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return null
        val rse = t as? android.app.RecoverableSecurityException ?: return null
        return rse.userAction.actionIntent.intentSender
    }

    /* ============================================================
     * 杂项
     * ============================================================ */

    /**
     * 容器类型。
     *
     * 只看扩展名 —— 内容嗅探在这里没用：真去读魔数的话，读到 ID3 只能说明
     * 这是个带 ID3 标签的文件，而 FLAC / M4A **也可能**带 ID3 标签（不规范但存在）。
     * WAV / OGG / APE 等一律返回 null（不硬塞）。
     */
    private fun kindOf(song: Song): Kind? =
        when (song.displayName.lowercase().substringAfterLast('.', "")) {
            "mp3", "mp2", "mpga", "mpa" -> Kind.MP3
            "flac" -> Kind.FLAC
            else -> null
        }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, len: Long): Boolean {
        var left = len
        val buf = ByteArray(BUF)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return false
            left -= n
        }
        return true
    }

    /** [len] 为 [Long.MAX_VALUE] 表示一直拷到流末尾 */
    private fun copyFully(input: InputStream, out: OutputStream, len: Long): Boolean {
        var left = len
        val buf = ByteArray(BUF)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return len == Long.MAX_VALUE
            out.write(buf, 0, n)
            if (len != Long.MAX_VALUE) left -= n
        }
        return true
    }
}
