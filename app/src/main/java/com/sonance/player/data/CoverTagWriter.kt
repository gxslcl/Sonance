package com.sonance.player.data

import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 把封面写进**歌曲文件本身**（MP3 的 ID3v2 `APIC` 帧）。
 *
 * ## 为什么需要它
 *
 * 应用平时把下载的封面放在私有目录（[CoverCache]），封面只跟这台设备上的这个应用走：
 * 换手机、把歌拷给别人、用别的播放器打开，封面就没了。写进文件里封面才是**跟着歌走**的。
 *
 * ## 只动封面，不动别的帧
 *
 * 走 [Id3Writer.withCoverPreserving]：原有标签里的音轨号、流派、年份、注释等帧
 * 全部原样保留，只替换 `APIC`。只有原文件压根没有可用标签（或标签结构特殊）时，
 * 才退回 [Id3Writer.buildTag] 从零建一个。
 *
 * ## 只支持 MP3
 *
 * FLAC 要改 `PICTURE` 块、M4A 要改 `moov.udta.meta.ilst` —— 后者的 `moov` 是
 * 带偏移表的容器，动它等于重排整个文件结构。与其冒险写坏用户的文件，
 * 不如明确返回 [Result.UnsupportedFormat]。
 *
 * ## 写回原文件为什么这么绕
 *
 * 分区存储下不能就地改一个文件（读写同一个 URI 会互相踩），只能
 * "读出来 → 写成带新标签的临时文件 → 覆盖回去"。而覆盖那一步：
 * - API 29+：改**别的应用创建的文件**要用户点确认（[MediaStore.createWriteRequest]）
 * - API 28 及以下：老的文件权限模型，要 `WRITE_EXTERNAL_STORAGE`
 *
 * 所以 [write] 可能返回 [Result.NeedsPermission]，由上层拉起系统弹窗、
 * 拿到授权后再调一次。
 */
object CoverTagWriter {

    private const val TAG = "SonanceCover"
    private const val BUF = 64 * 1024

    sealed interface Result {

        /** 写进去了 */
        data object Written : Result

        /** 在线歌没有本地文件可写 */
        data object NotLocal : Result

        /** 容器不是 MP3（FLAC / M4A 不在支持范围内） */
        data object UnsupportedFormat : Result

        /**
         * 被系统拦下，需要用户确认。
         *
         * [intentSender] 非空 → 用 `StartIntentSenderForResult` 拉起确认框（API 29+）；
         * 为空 → API 28 及以下，得先授予 `WRITE_EXTERNAL_STORAGE`
         */
        data class NeedsPermission(val intentSender: IntentSender?) : Result

        /** 网络之外的其它失败：读不出来、写不进去、磁盘满 */
        data object Failed : Result
    }

    /**
     * 把 [cover] 写进 [song] 对应的文件。
     *
     * @param cover 图片原始字节（JPEG 或 PNG），不会被重新编码
     */
    suspend fun write(context: Context, song: Song, cover: ByteArray): Result {
        if (!song.isLocal) return Result.NotLocal
        if (!isMp3(song)) return Result.UnsupportedFormat
        if (cover.isEmpty()) return Result.Failed

        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val temp = File(app.cacheDir, "cover-${System.nanoTime()}.tmp")
            try {
                val mime = imageMime(cover)

                // ---- 1) 读原标签：只为把除封面外的帧搬过去 ----
                val existing = readTag(app, song.uri)
                val tag = Id3Writer.withCoverPreserving(existing, cover, mime)
                    ?: Id3Writer.buildTag(song.title, song.artist, song.album, cover, mime)

                // ---- 2) 读原文件 → 写临时文件（copyWithTag 会剥掉旧标签）----
                val input = app.contentResolver.openInputStream(song.uri)
                    ?: return@withContext Result.Failed
                input.use { i ->
                    temp.outputStream().use { o -> Id3Writer.copyWithTag(i, o, tag) }
                }

                // ---- 3) 覆盖回原文件 ----
                val out = app.contentResolver.openOutputStream(song.uri, "wt")
                    ?: return@withContext Result.Failed
                out.use { o ->
                    temp.inputStream().use { i -> i.copyTo(o, BUF) }
                }

                rescan(app, song.uri)
                Log.i(TAG, "封面已写入文件：${song.displayName}")
                Result.Written
            } catch (t: Throwable) {
                permissionWall(t, app, song.uri) ?: run {
                    Log.w(TAG, "写入封面失败: ${t.javaClass.simpleName}: ${t.message}")
                    Result.Failed
                }
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    /* ============================================================
     * 文件读写
     * ============================================================ */

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
     * 是不是 ID3 容器。
     *
     * 只看扩展名 —— 内容嗅探在这里没用：真去读魔数的话，读到 ID3 只能说明
     * 这是个带 ID3 标签的文件，而 FLAC / M4A **也可能**带 ID3 标签（不规范但存在）。
     */
    private fun isMp3(song: Song): Boolean =
        when (song.displayName.lowercase().substringAfterLast('.', "")) {
            "mp3", "mp2", "mpga", "mpa" -> true
            else -> false
        }

    /** 按魔数判断图片类型：iTunes 给的地址后缀不总是可信 */
    private fun imageMime(b: ByteArray): String =
        if (b.size >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        ) {
            "image/png"
        } else {
            "image/jpeg"
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
}
