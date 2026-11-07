package com.sonance.player.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import com.sonance.player.data.netease.NcDuration
import com.sonance.player.data.netease.NcMeta
import com.sonance.player.data.netease.NcMetaFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 把**手动编辑后的歌名 / 歌手 / 专辑**写进歌曲文件本身，并顺手把文件改名成
 * 「歌名 - 歌手」——与嗅探转换的成品命名规则（[NcMetaFetcher.outputStem]）保持一致。
 *
 * ## 与 [CoverTagWriter] 的关系
 *
 * 两者的文件操作模式完全同源（分区存储下不能就地改，只能
 * 读出来 → 写临时 → 覆盖回去 → 重扫媒体库），封面那条路已经踩平了所有坑：
 * API 30+ 的 [MediaStore.createWriteRequest]、API 29 的 RecoverableSecurityException、
 * 老系统的 WRITE_EXTERNAL_STORAGE。这里复用同一套流程，只是写的帧不同：
 * - mp3：替换 `TIT2` / `TPE1` / `TALB`，**封面（APIC）和音轨号等次要帧原样保留**
 * - m4a：[Mp4TagWriter] 整文件重排 `moov.udta.meta.ilst`（转换管线同款）
 * - flac / ogg：明确不支持，不动用户文件（与 [CoverTagWriter] 同一边界）
 *
 * 写完标签后 MediaStore 里的标题 / 歌手会在重扫时从文件标签回读；
 * 改名是 best-effort —— 个别 provider 不支持时跳过，不影响标签写入结果。
 */
object SongMetaEditor {

    private const val TAG = "SonanceMeta"
    private const val BUF = 64 * 1024

    sealed interface Result {

        /** 标签写进去了（renamed = 文件是否也改成了「歌名 - 歌手」） */
        data class Written(val renamed: Boolean) : Result

        /** 在线歌没有本地文件可写 */
        data object NotLocal : Result

        /** 容器不是 mp3 / m4a（flac / ogg 暂不写） */
        data object UnsupportedFormat : Result

        /** 被系统拦下，需要用户确认（同 [CoverTagWriter.Result.NeedsPermission] 的约定） */
        data class NeedsPermission(val intentSender: IntentSender?) : Result

        /** 其它失败：读不出来、写不进去、磁盘满 */
        data object Failed : Result
    }

    /**
     * 把 [title] / [artist] / [album] 写进 [song] 对应的文件。
     *
     * 三个字段都传什么就写什么：空串表示清掉该帧（mp3）或该原子（m4a）。
     */
    suspend fun edit(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String
    ): Result {
        if (!song.isLocal) return Result.NotLocal
        val ext = song.displayName.lowercase().substringAfterLast('.', "")
        return when (ext) {
            "mp3", "mp2", "mpga", "mpa" -> editMp3(context, song, title, artist, album)
            "m4a", "m4b", "mp4" -> editMp4(context, song, title, artist, album)
            else -> Result.UnsupportedFormat
        }
    }

    /** 「歌名 - 歌手」stem，与转换成品同规则（文件系统禁用字符清洗） */
    fun outputStem(title: String, artist: String): String =
        NcMetaFetcher.outputStem(NcMeta(title, artist, "", null))

    /* ============================================================
     * mp3：替换 TIT2 / TPE1 / TALB，保留其余帧
     * ============================================================ */

    private suspend fun editMp3(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val temp = File(app.cacheDir, "meta-${System.nanoTime()}.tmp")
        try {
            // 旧标签只为搬帧：TIT2/TPE1/TALB 之外的原样保留（含 APIC）
            val existing = readTag(app, song.uri)
            val tag = Id3Writer.withMetaPreserving(existing, title, artist, album)
                ?: Id3Writer.buildTag(title, artist, album)

            val input = app.contentResolver.openInputStream(song.uri)
                ?: return@withContext Result.Failed
            input.use { i ->
                temp.outputStream().use { o -> Id3Writer.copyWithTag(i, o, tag) }
            }
            val out = app.contentResolver.openOutputStream(song.uri, "wt")
                ?: return@withContext Result.Failed
            out.use { o ->
                temp.inputStream().use { i -> i.copyTo(o, BUF) }
            }

            val renamed = renameBestEffort(app, song, title, artist)
            rescan(app, song.uri)
            Log.i(TAG, "歌曲信息已写入文件：${song.displayName}")
            Result.Written(renamed)
        } catch (t: Throwable) {
            permissionWall(t, app, song.uri) ?: run {
                Log.w(TAG, "写入歌曲信息失败: ${t.javaClass.simpleName}: ${t.message}")
                Result.Failed
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /* ============================================================
     * m4a：Mp4TagWriter 整文件重排 ilst
     * ============================================================ */

    private suspend fun editMp4(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // Mp4TagWriter 只吃 java.io.File —— 先把原文件拉到缓存，改完拷回去
        val temp = File(app.cacheDir, "meta-${System.nanoTime()}.m4a")
        try {
            app.contentResolver.openInputStream(song.uri)?.use { i ->
                temp.outputStream().use { o -> i.copyTo(o, BUF) }
            } ?: return@withContext Result.Failed

            val info = HeaderInfo(
                payloadOffset = 0,
                metadata = mapOf(
                    "title" to title,
                    "artist" to artist,
                    "album" to album
                )
            )
            // verify：重排后 moov 必须还能解出时长，否则放弃替换（原文件不受影响）
            val ok = Mp4TagWriter.tag(
                temp,
                info,
                cover = null,
                verify = { f -> NcDuration.probeSeconds(f) != null }
            )
            if (!ok) return@withContext Result.Failed

            val out = app.contentResolver.openOutputStream(song.uri, "wt")
                ?: return@withContext Result.Failed
            out.use { o ->
                temp.inputStream().use { i -> i.copyTo(o, BUF) }
            }

            val renamed = renameBestEffort(app, song, title, artist)
            rescan(app, song.uri)
            Log.i(TAG, "歌曲信息已写入文件：${song.displayName}")
            Result.Written(renamed)
        } catch (t: Throwable) {
            permissionWall(t, app, song.uri) ?: run {
                Log.w(TAG, "写入歌曲信息失败: ${t.javaClass.simpleName}: ${t.message}")
                Result.Failed
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /* ============================================================
     * 改名与重扫
     * ============================================================ */

    /**
     * 文件名改成「歌名 - 歌手.原扩展名」。**best-effort**：
     * MediaStore 行（update DISPLAY_NAME）与 SAF 文档（renameDocument）各试一次，
     * 都不行就放弃 —— 标签已经写成功，媒体库显示不受影响。
     */
    private fun renameBestEffort(
        context: Context,
        song: Song,
        title: String,
        artist: String
    ): Boolean {
        val stem = outputStem(title, artist)
        if (stem.isBlank()) return false
        val ext = song.displayName.substringAfterLast('.', "")
        val newName = if (ext.isBlank()) stem else "$stem.$ext"
        if (newName == song.displayName) return false

        // 1) MediaStore 行：改 DISPLAY_NAME（API 30+ 支持且等于改名）
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            context.contentResolver.update(song.uri, values, null, null)
        }.onSuccess {
            Log.i(TAG, "文件已改名：$newName")
            return true
        }

        // 2) SAF 文档 URI：renameDocument 返回新 Uri
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, song.uri, newName)
        }.onSuccess {
            Log.i(TAG, "SAF 文件已改名：$newName")
            return true
        }

        Log.w(TAG, "文件改名未生效（provider 不支持），标签已写入")
        return false
    }

    /** 让媒体库重新读一遍这个文件（标题/歌手从标签回读） */
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
     * 读旧标签（mp3 用）与权限墙 —— 与 CoverTagWriter 同款
     * ============================================================ */

    /** 只读文件开头的 ID3v2 标签（含 10 字节头），不含音频数据 */
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

    private fun permissionWall(t: Throwable, context: Context, uri: Uri): Result? {
        if (t !is SecurityException) return null

        recoverableSender(t)?.let { return Result.NeedsPermission(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = runCatching {
                MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
            }.getOrNull()
            return if (sender != null) Result.NeedsPermission(sender) else Result.Failed
        }

        return Result.NeedsPermission(null)
    }

    /** RecoverableSecurityException 是 API 29 才有的类，独立函数避免老系统类加载 */
    private fun recoverableSender(t: Throwable): IntentSender? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return null
        val rse = t as? android.app.RecoverableSecurityException ?: return null
        return rse.userAction.actionIntent.intentSender
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
