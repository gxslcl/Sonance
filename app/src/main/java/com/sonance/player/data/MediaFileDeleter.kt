package com.sonance.player.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把本地歌曲的**音频文件**从设备上删掉，兼容本应用四种本地曲目 URI 来源：
 * 1. `MediaStore.Audio.Media`（`content://media/.../audio/media/<id>`）
 * 2. `MediaStore.Files`（`content://media/.../file/<id>` — Hi-Res / Ogg / Ape 等未入音频表的格式）
 * 3. 未被 MediaStore 收录的直扫本地文件（`file:///...`）
 * 4. 用户通过系统目录选择器授予的 SAF 文档 URI（`content://com.android.externalstorage.documents/...`）
 *
 * 关键策略：
 * - **不再直接把原始 URI 列表整批丢进 [MediaStore.createDeleteRequest]**：`file://`、SAF URI、
 *   部分 `/file/<id>` URI 或已失效的缓存 ID 只要混入一条就会导致整个批次抛 `IllegalArgumentException` 并整批失败；
 * - 删除前先把 SAF、可直删的 `file://` 与已不存在于存储中的失效缓存条目独立处理或转成真实
 *   `MediaStore.Audio.Media` URI；批次构造失败时降级为逐条探测，隔离无法交由系统弹窗的条目并尝试安全直删兜底，
 *   确保多选时不会因一首特殊格式拖累全部曲目。
 */
object MediaFileDeleter {

    private const val TAG = "SonanceDelete"
    private const val SCAN_TIMEOUT_MS = 2_500L

    internal enum class DeleteUriKind {
        MEDIA_STORE_AUDIO,
        MEDIA_STORE_FILES,
        FILE,
        DOCUMENT,
        OTHER
    }

    internal data class ResolvedDeleteTarget(
        val song: Song,
        val targetUri: Uri,
        val filePath: String?
    )

    sealed interface Result {

        data class Deleted(
            val deletedUris: Set<Uri> = emptySet(),
            val failed: Int = 0,
            val failureReason: String? = null
        ) : Result

        /**
         * 被系统拦截，需要用户确认或权限授权。
         *
         * @property intentSender 非空 → `StartIntentSenderForResult` 确认系统弹窗；空 → API 28 及以下先申请存储权限
         * @property requestSongs 需要经系统确认后再核验残留的曲目（可能只是原目标的一个子集）
         * @property requestUris 传给系统 `createDeleteRequest` 的标准化 URI（key 为 [Song.id]），用于确认后准确复核
         * @property alreadyDeletedUris 确认弹窗前已经确认不存在或已直删成功的原始 [Song.uri] 集合
         * @property preFailedCount 系统弹窗之外在前置处理中已确认失败的条目数
         */
        data class NeedsPermission(
            val intentSender: IntentSender?,
            val requestSongs: List<Song> = emptyList(),
            val requestUris: Map<Long, Uri> = emptyMap(),
            val alreadyDeletedUris: Set<Uri> = emptySet(),
            val preFailedCount: Int = 0
        ) : Result

        data class Failed(val reason: String? = null) : Result
    }

    fun request(context: Context, songs: List<Song>): Result {
        if (songs.isEmpty()) return Result.Deleted()
        val app = context.applicationContext
        val resolver = app.contentResolver

        val alreadyDeleted = LinkedHashSet<Uri>()
        val mediaTargets = ArrayList<ResolvedDeleteTarget>(songs.size)
        var preFailed = 0
        var lastFailureReason: String? = null

        for (song in songs) {
            when (val prep = prepareTarget(app, resolver, song)) {
                is PrepOutcome.AlreadyDeleted -> alreadyDeleted += song.uri
                is PrepOutcome.NeedsMediaRequest -> mediaTargets += prep.target
                is PrepOutcome.Failed -> {
                    preFailed++
                    if (prep.reason != null) lastFailureReason = prep.reason
                }
            }
        }

        if (mediaTargets.isEmpty()) {
            return if (alreadyDeleted.isNotEmpty()) {
                Result.Deleted(alreadyDeleted, preFailed, lastFailureReason)
            } else {
                Result.Failed(lastFailureReason ?: "未找到可删除的音频文件，或无目录写权限")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return requestApi30Plus(
                context = app,
                resolver = resolver,
                mediaTargets = mediaTargets,
                alreadyDeleted = alreadyDeleted,
                preFailedInitial = preFailed,
                lastReasonInitial = lastFailureReason
            )
        }

        return requestLegacyApi29AndBelow(
            context = app,
            resolver = resolver,
            mediaTargets = mediaTargets,
            alreadyDeleted = alreadyDeleted,
            preFailedInitial = preFailed,
            lastReasonInitial = lastFailureReason
        )
    }

    private sealed interface PrepOutcome {
        data object AlreadyDeleted : PrepOutcome
        data class NeedsMediaRequest(val target: ResolvedDeleteTarget) : PrepOutcome
        data class Failed(val reason: String?) : PrepOutcome
    }

    private fun prepareTarget(
        context: Context,
        resolver: ContentResolver,
        song: Song
    ): PrepOutcome {
        val candidateFile = localFileForSong(
            uriScheme = song.uri.scheme,
            uriPath = song.uri.path,
            folderPath = song.folderPath,
            displayName = song.displayName
        )
        val kind = classifyUri(song.uri.scheme, song.uri.authority, song.uri.path)

        return when (kind) {
            DeleteUriKind.DOCUMENT -> prepareDocumentTarget(context, resolver, song, candidateFile)
            DeleteUriKind.FILE -> prepareFileTarget(context, resolver, song, candidateFile)
            DeleteUriKind.MEDIA_STORE_AUDIO,
            DeleteUriKind.MEDIA_STORE_FILES -> prepareMediaStoreTarget(context, resolver, song, kind, candidateFile)
            DeleteUriKind.OTHER -> {
                val resolved = candidateFile?.absolutePath?.let { path ->
                    findMediaAudioUriByPath(resolver, path) ?: scanFileToUri(context, path)
                }
                if (resolved != null) {
                    PrepOutcome.NeedsMediaRequest(ResolvedDeleteTarget(song, resolved, candidateFile?.absolutePath))
                } else if (candidateFile != null && !candidateFile.exists()) {
                    PrepOutcome.AlreadyDeleted
                } else if (tryDirectFileDelete(context, candidateFile)) {
                    PrepOutcome.AlreadyDeleted
                } else {
                    PrepOutcome.Failed("不支持的文件 URI：${song.uri.scheme}")
                }
            }
        }
    }

    private fun prepareDocumentTarget(
        context: Context,
        resolver: ContentResolver,
        song: Song,
        candidateFile: File?
    ): PrepOutcome {
        val existsInDoc = documentExists(resolver, song.uri)
        if (!existsInDoc && (candidateFile == null || !candidateFile.exists())) {
            return PrepOutcome.AlreadyDeleted
        }

        val deletedDoc = runCatching { DocumentsContract.deleteDocument(resolver, song.uri) }
            .onFailure { Log.w(TAG, "DocumentsContract.deleteDocument 失败: ${it.message}") }
            .getOrDefault(false)
        if (deletedDoc) {
            candidateFile?.absolutePath?.let { notifyMediaScanner(context, it) }
            return PrepOutcome.AlreadyDeleted
        }

        val mediaUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getMediaUri(context, song.uri) }.getOrNull()
        } else null

        val fallbackUri = mediaUri
            ?: candidateFile?.absolutePath?.let { findMediaAudioUriByPath(resolver, it) }
        if (fallbackUri != null) {
            return PrepOutcome.NeedsMediaRequest(
                ResolvedDeleteTarget(song, fallbackUri, candidateFile?.absolutePath)
            )
        }
        if (tryDirectFileDelete(context, candidateFile)) return PrepOutcome.AlreadyDeleted
        return PrepOutcome.Failed("该外置/授权目录缺少删除权限，请在目录管理中重新授权或用系统文件管理器删除")
    }

    private fun prepareFileTarget(
        context: Context,
        resolver: ContentResolver,
        song: Song,
        candidateFile: File?
    ): PrepOutcome {
        val path = candidateFile?.absolutePath ?: song.uri.path
        if (path != null && !File(path).exists()) {
            val staleMedia = findMediaUriByPath(resolver, path)
            return if (staleMedia != null) {
                PrepOutcome.NeedsMediaRequest(ResolvedDeleteTarget(song, staleMedia, path))
            } else {
                PrepOutcome.AlreadyDeleted
            }
        }

        if (tryDirectFileDelete(context, candidateFile)) {
            return PrepOutcome.AlreadyDeleted
        }

        if (path != null) {
            val mediaUri = findMediaAudioUriByPath(resolver, path)
                ?: scanFileToAudioUri(context, resolver, path)
                ?: findMediaUriByPath(resolver, path)
            if (mediaUri != null) {
                return PrepOutcome.NeedsMediaRequest(ResolvedDeleteTarget(song, mediaUri, path))
            }
        }
        return PrepOutcome.Failed("系统禁止直接删除该路径下的文件（未纳入媒体库）")
    }

    private fun prepareMediaStoreTarget(
        context: Context,
        resolver: ContentResolver,
        song: Song,
        kind: DeleteUriKind,
        candidateFile: File?
    ): PrepOutcome {
        val info = queryMediaStoreInfo(resolver, song.uri)
        val path = info?.dataPath ?: candidateFile?.absolutePath

        if (info == null) {
            if (path == null || !File(path).exists()) {
                return PrepOutcome.AlreadyDeleted
            }
            if (tryDirectFileDelete(context, File(path))) {
                return PrepOutcome.AlreadyDeleted
            }
            val reindexed = findMediaAudioUriByPath(resolver, path) ?: scanFileToAudioUri(context, resolver, path)
            return if (reindexed != null) {
                PrepOutcome.NeedsMediaRequest(ResolvedDeleteTarget(song, reindexed, path))
            } else {
                PrepOutcome.Failed("文件已脱离媒体库且无直接删除权限")
            }
        }

        val normalized = normalizeMediaStoreUri(context, resolver, song.uri, kind, info)
        return PrepOutcome.NeedsMediaRequest(ResolvedDeleteTarget(song, normalized, path))
    }

    private fun normalizeMediaStoreUri(
        context: Context,
        resolver: ContentResolver,
        original: Uri,
        kind: DeleteUriKind,
        info: MediaStoreRowInfo
    ): Uri {
        if (kind == DeleteUriKind.MEDIA_STORE_AUDIO) return original

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val viaSystem = runCatching { MediaStore.getMediaUri(context, original) }.getOrNull()
            if (viaSystem != null && !viaSystem.path.orEmpty().contains("/file/")) {
                return viaSystem
            }
        }

        val volume = extractMediaVolume(original.pathSegments)
        if (info.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO && info.id > 0L && volume != null) {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), info.id)
        }

        if (!info.dataPath.isNullOrBlank()) {
            val inAudio = findMediaAudioUriByPath(resolver, info.dataPath)
            if (inAudio != null) return inAudio
        }

        return original
    }

    private fun requestApi30Plus(
        context: Context,
        resolver: ContentResolver,
        mediaTargets: List<ResolvedDeleteTarget>,
        alreadyDeleted: LinkedHashSet<Uri>,
        preFailedInitial: Int,
        lastReasonInitial: String?
    ): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Result.Failed()

        var failed = preFailedInitial
        var lastReason = lastReasonInitial

        // 快速路径：先尝试整批创建系统删除确认弹窗
        val batchSender = runCatching {
            MediaStore.createDeleteRequest(resolver, mediaTargets.map { it.targetUri }).intentSender
        }.onFailure {
            Log.w(TAG, "createDeleteRequest 整批提交失败，降级逐条兼容处理: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        if (batchSender != null) {
            return Result.NeedsPermission(
                intentSender = batchSender,
                requestSongs = mediaTargets.map { it.song },
                requestUris = mediaTargets.associate { it.song.id to it.targetUri },
                alreadyDeletedUris = alreadyDeleted,
                preFailedCount = failed
            )
        }

        // 慢路径：逐条区分「系统可受理」与「必须直接处理（如非音频分类的 /file/<id>）」
        val acceptedTargets = ArrayList<ResolvedDeleteTarget>(mediaTargets.size)
        val rejectedTargets = ArrayList<ResolvedDeleteTarget>()
        for (target in mediaTargets) {
            val singleOk = runCatching {
                MediaStore.createDeleteRequest(resolver, listOf(target.targetUri))
                true
            }.getOrDefault(false)
            if (singleOk) {
                acceptedTargets += target
            } else {
                rejectedTargets += target
            }
        }

        var recoverableSender: IntentSender? = null
        var recoverableTarget: ResolvedDeleteTarget? = null

        for (target in rejectedTargets) {
            // 若是 MediaStore.Files 里无法进 createDeleteRequest 的文件（如未分类的 .ape/.dsf 等），
            // 优先让媒体库按音频再扫描一次，若仍不行则尝试 resolver.delete / File.delete
            val rescanned = target.filePath?.let { scanFileToAudioUri(context, resolver, it) }
            if (rescanned != null && runCatching {
                    MediaStore.createDeleteRequest(resolver, listOf(rescanned))
                    true
                }.getOrDefault(false)
            ) {
                acceptedTargets += target.copy(targetUri = rescanned)
                continue
            }

            when (val direct = tryDeleteContentOrFile(context, resolver, target)) {
                is DirectOutcome.Deleted -> alreadyDeleted += target.song.uri
                is DirectOutcome.Recoverable -> {
                    if (recoverableSender == null) {
                        recoverableSender = direct.intentSender
                        recoverableTarget = target
                    } else {
                        failed++
                    }
                }
                is DirectOutcome.SecurityDenied -> {
                    failed++
                    lastReason = "缺少删除「${target.song.title.ifBlank { target.song.displayName }}」的系统存储权限"
                }
                is DirectOutcome.Failed -> {
                    failed++
                    lastReason = direct.reason
                }
            }
        }

        if (acceptedTargets.isNotEmpty()) {
            val finalSender = runCatching {
                MediaStore.createDeleteRequest(resolver, acceptedTargets.map { it.targetUri }).intentSender
            }.onFailure {
                Log.w(TAG, "createDeleteRequest 重建批次失败: ${it.javaClass.simpleName}: ${it.message}")
                lastReason = "系统媒体库拒绝删除请求：${it.message ?: it.javaClass.simpleName}"
            }.getOrNull()
            if (finalSender != null) {
                if (recoverableTarget != null) failed++
                return Result.NeedsPermission(
                    intentSender = finalSender,
                    requestSongs = acceptedTargets.map { it.song },
                    requestUris = acceptedTargets.associate { it.song.id to it.targetUri },
                    alreadyDeletedUris = alreadyDeleted,
                    preFailedCount = failed
                )
            }
            failed += acceptedTargets.size
        }

        if (recoverableSender != null && recoverableTarget != null) {
            return Result.NeedsPermission(
                intentSender = recoverableSender,
                requestSongs = listOf(recoverableTarget.song),
                requestUris = mapOf(recoverableTarget.song.id to recoverableTarget.targetUri),
                alreadyDeletedUris = alreadyDeleted,
                preFailedCount = failed
            )
        }

        if (alreadyDeleted.isNotEmpty()) {
            return Result.Deleted(alreadyDeleted, failed, lastReason)
        }
        return Result.Failed(lastReason ?: "该文件不受系统媒体库批量删除支持，或缺少写入权限")
    }

    private fun requestLegacyApi29AndBelow(
        context: Context,
        resolver: ContentResolver,
        mediaTargets: List<ResolvedDeleteTarget>,
        alreadyDeleted: LinkedHashSet<Uri>,
        preFailedInitial: Int,
        lastReasonInitial: String?
    ): Result {
        var failed = preFailedInitial
        var lastReason = lastReasonInitial
        for (target in mediaTargets) {
            when (val direct = tryDeleteContentOrFile(context, resolver, target)) {
                is DirectOutcome.Deleted -> alreadyDeleted += target.song.uri
                is DirectOutcome.Recoverable -> {
                    return Result.NeedsPermission(
                        intentSender = direct.intentSender,
                        requestSongs = listOf(target.song),
                        requestUris = mapOf(target.song.id to target.targetUri),
                        alreadyDeletedUris = alreadyDeleted,
                        preFailedCount = failed
                    )
                }
                is DirectOutcome.SecurityDenied -> {
                    return Result.NeedsPermission(
                        intentSender = null,
                        requestSongs = mediaTargets.map { it.song },
                        requestUris = mediaTargets.associate { it.song.id to it.targetUri },
                        alreadyDeletedUris = alreadyDeleted,
                        preFailedCount = failed
                    )
                }
                is DirectOutcome.Failed -> {
                    failed++
                    lastReason = direct.reason
                }
            }
        }
        return if (alreadyDeleted.isNotEmpty() || failed > 0) {
            Result.Deleted(alreadyDeleted, failed, lastReason)
        } else {
            Result.Failed(lastReason ?: "删除失败")
        }
    }

    private sealed interface DirectOutcome {
        data object Deleted : DirectOutcome
        data class Recoverable(val intentSender: IntentSender) : DirectOutcome
        data object SecurityDenied : DirectOutcome
        data class Failed(val reason: String) : DirectOutcome
    }

    private fun tryDeleteContentOrFile(
        context: Context,
        resolver: ContentResolver,
        target: ResolvedDeleteTarget
    ): DirectOutcome {
        try {
            val rows = resolver.delete(target.targetUri, null, null)
            if (rows > 0 || !rowExists(resolver, target.targetUri)) {
                return DirectOutcome.Deleted
            }
        } catch (t: Throwable) {
            Log.w(TAG, "直接 delete(${target.targetUri}) 失败: ${t.javaClass.simpleName}: ${t.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && t is RecoverableSecurityException) {
                return DirectOutcome.Recoverable(t.userAction.actionIntent.intentSender)
            }
            if (t is SecurityException && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return DirectOutcome.SecurityDenied
            }
        }

        val path = target.filePath
        if (path != null) {
            val f = File(path)
            if (!f.exists() || tryDirectFileDelete(context, f)) {
                runCatching { resolver.delete(target.targetUri, null, null) }
                return DirectOutcome.Deleted
            }
        }

        return DirectOutcome.Failed("系统拒绝删除「${target.song.title.ifBlank { target.song.displayName }}」，缺少访问权限或存储只读")
    }

    /**
     * 系统授权删除之后，检查哪些条目**仍然存活**（同时核对标准化 MediaStore URI、原始 URI 与本地文件）。
     */
    fun remaining(
        context: Context,
        songs: List<Song>,
        resolvedUris: Map<Long, Uri> = emptyMap()
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        return songs.filter { song ->
            val checkedUri = resolvedUris[song.id] ?: song.uri
            val file = localFileForSong(song.uri.scheme, song.uri.path, song.folderPath, song.displayName)
            val fileExists = file?.exists() == true
            val rowAlive = when (classifyUri(checkedUri.scheme, checkedUri.authority, checkedUri.path)) {
                DeleteUriKind.MEDIA_STORE_AUDIO,
                DeleteUriKind.MEDIA_STORE_FILES -> rowExists(resolver, checkedUri)
                DeleteUriKind.DOCUMENT -> documentExists(resolver, checkedUri)
                DeleteUriKind.FILE -> false
                DeleteUriKind.OTHER -> rowExists(resolver, checkedUri)
            }
            // MediaStore 行已消失时，如果底层本地文件也已不存在则确认删净；
            // 若底层文件仍在，保底视为未删掉以免误报成功。
            rowAlive || fileExists
        }
    }

    private data class MediaStoreRowInfo(
        val id: Long,
        val dataPath: String?,
        val mediaType: Int
    )

    private fun queryMediaStoreInfo(resolver: ContentResolver, uri: Uri): MediaStoreRowInfo? =
        runCatching {
            val cols = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            resolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val data = c.getString(1)
                val type = runCatching { c.getInt(2) }.getOrDefault(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
                MediaStoreRowInfo(id, data, type)
            }
        }.getOrElse {
            // 部分厂商 MediaStore.Audio 查询不认 Files.FileColumns.MEDIA_TYPE，退回基础列再查一次
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    MediaStoreRowInfo(c.getLong(0), c.getString(1), MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
                }
            }.getOrNull()
        }

    private fun rowExists(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    private fun documentExists(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    private fun findMediaAudioUriByPath(resolver: ContentResolver, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA}=?",
                arrayOf(path),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                } else null
            }
        }.getOrNull()
    }

    private fun findMediaUriByPath(resolver: ContentResolver, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        return findMediaAudioUriByPath(resolver, path) ?: runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val type = runCatching { c.getInt(1) }.getOrDefault(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
                if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO) {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                }
            }
        }.getOrNull()
    }

    private fun scanFileToAudioUri(context: Context, resolver: ContentResolver, path: String): Uri? {
        val scanned = scanFileToUri(context, path) ?: return null
        if (!scanned.path.orEmpty().contains("/file/")) return scanned
        val info = queryMediaStoreInfo(resolver, scanned) ?: return scanned
        return normalizeMediaStoreUri(context, resolver, scanned, DeleteUriKind.MEDIA_STORE_FILES, info)
    }

    private fun scanFileToUri(context: Context, path: String): Uri? {
        var result: Uri? = null
        val latch = CountDownLatch(1)
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                result = uri
                latch.countDown()
            }
            latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        return result
    }

    private fun tryDirectFileDelete(context: Context, file: File?): Boolean {
        if (file == null) return false
        if (!file.exists()) return true
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (deleted) {
            notifyMediaScanner(context, file.absolutePath)
        }
        return deleted
    }

    private fun notifyMediaScanner(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
    }

    @VisibleForTesting
    internal fun classifyUri(scheme: String?, authority: String?, path: String?): DeleteUriKind {
        val s = scheme?.lowercase()
        return when (s) {
            "file" -> DeleteUriKind.FILE
            "content" -> when {
                authority == "com.android.externalstorage.documents" ||
                    authority == "com.android.providers.downloads.documents" ||
                    authority?.endsWith(".documents") == true -> DeleteUriKind.DOCUMENT
                authority == MediaStore.AUTHORITY || authority == "media" -> {
                    val p = path.orEmpty()
                    if (p.contains("/file/") || p.endsWith("/file")) {
                        DeleteUriKind.MEDIA_STORE_FILES
                    } else {
                        DeleteUriKind.MEDIA_STORE_AUDIO
                    }
                }
                else -> DeleteUriKind.OTHER
            }
            else -> DeleteUriKind.OTHER
        }
    }

    @VisibleForTesting
    internal fun extractMediaVolume(pathSegments: List<String>?): String? {
        if (pathSegments.isNullOrEmpty()) return null
        val first = pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return if (first == "audio" || first == "file" || first == "video" || first == "images") null else first
    }

    @VisibleForTesting
    internal fun localFileForSong(
        uriScheme: String?,
        uriPath: String?,
        folderPath: String,
        displayName: String
    ): File? {
        if (uriScheme.equals("file", ignoreCase = true) && !uriPath.isNullOrBlank()) {
            return File(uriPath)
        }
        if (folderPath.startsWith("/") && displayName.isNotBlank()) {
            return File(folderPath, displayName)
        }
        return null
    }
}
