package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * 合成正整数曲目 id 分段（全为正，与 [isRemoteSongId] 的负数域、`0L` 空哨兵、`-1L` 未选哨兵完全隔离）：
 * - `[0, UNINDEXED_SONG_ID_BASE)`：MediaStore 真实 `_ID`
 * - `[UNINDEXED_SONG_ID_BASE, EXTERNAL_SONG_ID_BASE)`：磁盘直扫发现的未入库本地歌曲（路径确定性哈希，重扫稳定）
 * - `[EXTERNAL_SONG_ID_BASE, ...)`：从外部（文件管理器 FileProvider / 临时 URI）直接打开且未在曲库中的临时歌曲
 */
internal const val UNINDEXED_SONG_ID_BASE: Long = 0x2000_0000_0000_0000L
internal const val EXTERNAL_SONG_ID_BASE: Long = 0x4000_0000_0000_0000L
private const val SYNTHETIC_ID_MASK: Long = 0x1FFF_FFFF_FFFF_FFFFL

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L

/** 该 id 是否属于从文件管理器等外部临时打开、尚未进入持久曲库的歌曲 */
fun isExternalSongId(id: Long): Boolean = id >= EXTERNAL_SONG_ID_BASE

/** 该 id 是否属于磁盘直扫兜底发现的未入库本地文件 */
fun isUnindexedLocalSongId(id: Long): Boolean =
    id >= UNINDEXED_SONG_ID_BASE && id < EXTERNAL_SONG_ID_BASE

internal fun stableSyntheticId(base: Long, key: String): Long {
    var h = FNV_OFFSET
    for (c in key) {
        h = (h xor c.code.toLong()) * FNV_PRIME
    }
    return base + ((h and SYNTHETIC_ID_MASK).coerceAtLeast(1L))
}

internal fun unindexedSongId(pathKey: String): Long =
    stableSyntheticId(UNINDEXED_SONG_ID_BASE, pathKey.lowercase())

internal fun externalSongId(uriString: String): Long =
    stableSyntheticId(EXTERNAL_SONG_ID_BASE, uriString)

/** 用于纯 JVM 单测与匹配解耦的曲库条目视图 */
internal data class LibraryMatchEntry(
    val id: Long,
    val folderPath: String,
    val displayName: String,
    val sizeBytes: Long
)

internal object ExternalAudioResolver {

    /** 解析外部文档 URI 中的 MediaStore 数值 ID（如 `media.documents` 的 `audio:12345`）。 */
    fun extractDocumentNumericId(authority: String?, documentId: String?): Long? {
        val doc = documentId?.trim().orEmpty()
        if (doc.isEmpty()) return null
        if (authority == "com.android.providers.media.documents") {
            val type = doc.substringBefore(':', "")
            val idPart = doc.substringAfter(':', "")
            if (type == "audio" || type == "document" || type == "media") {
                return idPart.toLongOrNull()?.takeIf { it > 0L }
            }
        }
        val tail = doc.substringAfterLast(':')
        if ((doc.startsWith("msf:") || doc.startsWith("audio:")) && tail.all { it.isDigit() }) {
            return tail.toLongOrNull()?.takeIf { it > 0L }
        }
        return null
    }

    /** 解析 `externalstorage.documents` 的 `primary:Music/Album/song.flac` 相对路径。 */
    fun extractExternalStorageRelativePath(documentId: String?): String? {
        val doc = documentId?.trim().orEmpty()
        if (!doc.contains(':')) return null
        val root = doc.substringBefore(':')
        val rel = doc.substringAfter(':').trimStart('/')
        if (rel.isEmpty()) return null
        return if (root.equals("primary", ignoreCase = true)) rel else rel
    }

    /**
     * 把各种形态的路径或 FileProvider URI path 规整成 `(归一化父目录, 文件名)`：
     * - `/storage/emulated/0/Download/Music/song.flac` -> `("download/music", "song.flac")`
     * - `/external_files/Music/song.mp3` -> `("music", "song.mp3")`
     * - `Music/song.mp3` -> `("music", "song.mp3")`
     */
    fun normalizeHintFolderAndName(rawPath: String?): Pair<String, String>? {
        val raw = rawPath?.trim()?.replace('\\', '/').orEmpty()
        if (raw.isEmpty()) return null
        val name = raw.substringAfterLast('/')
        if (name.isBlank()) return null
        var dir = raw.substringBeforeLast('/', "").trim('/')
        // 剥离常见外置存储绝对路径或 FileProvider 虚拟挂载前缀
        val stripPrefixes = listOf(
            "storage/emulated/0/",
            "storage/emulated/legacy/",
            "sdcard/",
            "external_files/",
            "external/",
            "shared/",
            "root/storage/emulated/0/",
            "root/sdcard/",
            "raw/storage/emulated/0/",
            "raw/"
        )
        val lower = dir.lowercase()
        for (prefix in stripPrefixes) {
            val pNoSlash = prefix.trimEnd('/')
            if (lower == pNoSlash) {
                dir = ""
                break
            }
            if (lower.startsWith(prefix)) {
                dir = dir.substring(prefix.length).trim('/')
                break
            }
        }
        // 也处理 `/storage/<UUID>/Music/...`
        if (dir.lowercase().startsWith("storage/")) {
            val rest = dir.substring("storage/".length)
            val afterUuid = rest.substringAfter('/', "")
            if (rest.contains('/')) dir = afterUuid.trim('/')
        }
        return dir.lowercase() to name
    }

    /**
     * 在曲库里精确匹配同一首歌，返回命中的下标，未命中返回 `-1`。
     *
     * 匹配优先级（从最可靠到兜底）：
     * 1. MediaStore 数值 ID 一致
     * 2. 规整后目录 + 文件名一致（兼容 API 29-35 的 `RELATIVE_PATH` 与旧版 `_DATA` 绝对路径）
     * 3. 文件名 + 文件字节数一致
     * 4. 未拿到大小信息时，全曲库仅唯一存在同名文件才命中（避免误选中同名翻唱/不同音质版本）
     */
    fun <T> matchLibrarySongIndex(
        entries: List<T>,
        entryOf: (T) -> LibraryMatchEntry,
        mediaId: Long?,
        pathHint: String?,
        displayName: String?,
        sizeBytes: Long
    ): Int {
        if (entries.isEmpty()) return -1
        if (mediaId != null && mediaId > 0L) {
            val idx = entries.indexOfFirst { entryOf(it).id == mediaId }
            if (idx >= 0) return idx
        }

        val hintPair = normalizeHintFolderAndName(pathHint)
        val targetName = displayName?.takeIf { it.isNotBlank() } ?: hintPair?.second
        val targetFolderLower = hintPair?.first

        if (!targetName.isNullOrBlank() && targetFolderLower != null && targetFolderLower.isNotEmpty()) {
            val idx = entries.indexOfFirst { item ->
                val e = entryOf(item)
                if (!e.displayName.equals(targetName, ignoreCase = true)) return@indexOfFirst false
                val normalizedFolder = normalizeHintFolderAndName("${e.folderPath}/${e.displayName}")?.first
                    ?: e.folderPath.trim('/').lowercase()
                normalizedFolder == targetFolderLower ||
                    normalizedFolder.endsWith("/$targetFolderLower") ||
                    targetFolderLower.endsWith("/$normalizedFolder")
            }
            if (idx >= 0) return idx
        }

        if (!targetName.isNullOrBlank() && sizeBytes > 0L) {
            val idx = entries.indexOfFirst { item ->
                val e = entryOf(item)
                e.displayName.equals(targetName, ignoreCase = true) && e.sizeBytes == sizeBytes
            }
            if (idx >= 0) return idx
        }

        if (!targetName.isNullOrBlank() && sizeBytes <= 0L) {
            var matched = -1
            for (i in entries.indices) {
                if (entryOf(entries[i]).displayName.equals(targetName, ignoreCase = true)) {
                    if (matched >= 0) return -1 // 不唯一，放弃猜
                    matched = i
                }
            }
            if (matched >= 0) return matched
        }

        return -1
    }

    /** 判断 `MediaStore.Files` 里的一行是否应作为可扫描音频候选（避免把 mp4/3gp 视频误判为歌曲）。 */
    fun isScannableMediaFileCandidate(
        displayName: String,
        mimeType: String?,
        mediaType: Int?,
        audioMediaTypeValue: Int
    ): Boolean {
        val name = displayName.trim()
        if (name.isEmpty() || name.startsWith(".")) return false
        if (mediaType != null && mediaType == audioMediaTypeValue) return true
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("video/") || mime.startsWith("image/")) return false
        val ext = LockedFormat.extensionOf(name)
        if (ext in VIDEO_ONLY_EXTENSIONS) return false
        if (mime.startsWith("audio/") || mime in AUDIO_APPLICATION_MIMES) return true
        return ext in LockedFormat.strictAudioExtensions || ext in LockedFormat.plainUnsupportedExtensions
    }

    fun sanitizeTitle(rawTitle: String?, displayName: String?): String {
        val raw = rawTitle?.trim().orEmpty()
        if (raw.isNotEmpty() && !raw.equals("<unknown>", ignoreCase = true)) return raw
        val fromFile = displayName?.trim()?.substringBeforeLast('.')?.trim().orEmpty()
        return fromFile.ifBlank { "未知曲目" }
    }

    private val AUDIO_APPLICATION_MIMES = setOf(
        "application/ogg",
        "application/x-ogg",
        "application/flac",
        "application/x-flac",
        "application/itunes"
    )

    private val VIDEO_ONLY_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "mts", "3gp", "3g2", "rmvb"
    )

    /** 完整解析从外部打开的音频 URI：优先绑定到曲库已有曲目，其次查 MediaStore，再兜底读取音频标签。 */
    data class ResolvedExternal(
        val song: Song,
        val matchedInLibrary: Boolean,
        val discoveredFromMediaStore: Boolean
    )

    fun resolve(
        context: Context,
        uri: Uri,
        mimeType: String?,
        librarySongs: List<Song>,
        repo: MediaRepository
    ): ResolvedExternal? {
        val resolver = context.contentResolver
        var mediaId: Long? = extractMediaId(context, uri)
        var displayName: String? = null
        var sizeBytes = -1L
        var dataPath: String? = if (uri.scheme == "file") uri.path else null

        // 读取 OpenableColumns 与旧版 _data 列（FileProvider 通常能提供文件名、大小，部分提供真实路径）
        if (uri.scheme == "content") {
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val iName = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val iSize = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val iData = cursor.getColumnIndex("_data")
                        if (iName >= 0) displayName = cursor.getString(iName)?.takeIf { it.isNotBlank() }
                        if (iSize >= 0 && !cursor.isNull(iSize)) sizeBytes = cursor.getLong(iSize)
                        if (iData >= 0 && dataPath == null) {
                            dataPath = cursor.getString(iData)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }

        if (uri.scheme == "file" && dataPath != null) {
            val file = File(dataPath!!)
            if (displayName.isNullOrBlank()) displayName = file.name
            if (sizeBytes <= 0L && file.exists()) sizeBytes = file.length()
        }

        if (displayName.isNullOrBlank()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }

        val pathHint = dataPath
            ?: if (DocumentsContract.isDocumentUri(context, uri) &&
                uri.authority == "com.android.externalstorage.documents"
            ) {
                extractExternalStorageRelativePath(DocumentsContract.getDocumentId(uri))
            } else {
                uri.path
            }

        // 若是真实文件且能扫描，顺便通知 MediaStore 更新索引
        if (!dataPath.isNullOrBlank() && File(dataPath!!).isFile) {
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(dataPath!!), null, null) }
        }

        // 1) 优先匹配内存曲库（这样全部歌曲 / 专辑 / 文件夹页能直接亮起选中状态）
        val libIdx = matchLibrarySongIndex(
            entries = librarySongs,
            entryOf = { LibraryMatchEntry(it.id, it.folderPath, it.displayName, it.sizeBytes) },
            mediaId = mediaId,
            pathHint = pathHint,
            displayName = displayName,
            sizeBytes = sizeBytes
        )
        if (libIdx >= 0) {
            val libSong = librarySongs[libIdx]
            // 注意：若传入 URI 本身就是有效授权的 FileProvider URI，而应用尚未拿到全盘读取权限，
            // 先保留当前可读 URI 用于实际解码；有全盘权限时直接用曲库原 Song。
            return ResolvedExternal(
                song = libSong,
                matchedInLibrary = true,
                discoveredFromMediaStore = false
            )
        }

        // 2) 曲库缓存里还没收入 → 直接向 MediaStore 查一次（已去除 IS_MUSIC != 0 限制）
        val storeMatch = repo.findSingleSong(
            mediaId = mediaId,
            pathHint = pathHint,
            displayName = displayName,
            sizeBytes = sizeBytes
        )
        if (storeMatch != null) {
            return ResolvedExternal(
                song = storeMatch,
                matchedInLibrary = false,
                discoveredFromMediaStore = true
            )
        }

        // 3) 确认为纯外部文件（如未入库目录、压缩包/社交软件沙盒 FileProvider URI）：可读性校验 + 提取标签
        val canOpen = verifyReadable(context, uri)
        if (!canOpen) return null

        val meta = readMetadata(context, uri)
        val effectiveName = displayName ?: meta.title ?: "外部音频"
        val title = sanitizeTitle(meta.title, effectiveName)
        val artist = meta.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: "未知艺术家"
        val album = meta.album?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: "外部音频"
        val folderPath = pathHint?.substringBeforeLast('/', "")?.trimEnd('/') ?: ""
        val folderName = folderPath.substringAfterLast('/').ifBlank { "外部打开" }
        val extId = externalSongId("${uri}|${sizeBytes}|$effectiveName")

        return ResolvedExternal(
            song = Song(
                id = extId,
                title = title,
                artist = artist,
                album = album,
                albumId = -1L,
                durationMs = meta.durationMs,
                uri = uri,
                folderPath = folderPath,
                folderName = folderName,
                displayName = effectiveName,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
                dateAddedSec = System.currentTimeMillis() / 1000L,
                track = meta.track
            ),
            matchedInLibrary = false,
            discoveredFromMediaStore = false
        )
    }

    private fun extractMediaId(context: Context, uri: Uri): Long? {
        if (uri.authority == MediaStore.AUTHORITY) {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            if (id != null && id > 0L) return id
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val id = extractDocumentNumericId(uri.authority, DocumentsContract.getDocumentId(uri))
            if (id != null) return id
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val mediaUri = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            val id = mediaUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (id != null && id > 0L) return id
        }
        return null
    }

    private fun verifyReadable(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return true
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            val f = File(path)
            return f.isFile && f.canRead()
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrElse {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }.getOrDefault(false)
        }
    }

    internal data class AudioMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
        val track: Int = 0
    )

    internal fun readMetadataFromPath(path: String): AudioMetadata {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            extractRetrieverFields(mmr)
        } catch (_: Throwable) {
            AudioMetadata()
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readMetadata(context: Context, uri: Uri): AudioMetadata {
        val mmr = MediaMetadataRetriever()
        return try {
            val setOk = runCatching {
                mmr.setDataSource(context, uri)
                true
            }.getOrElse {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        mmr.setDataSource(pfd.fileDescriptor)
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            if (!setOk) return AudioMetadata()
            extractRetrieverFields(mmr)
        } catch (_: Throwable) {
            AudioMetadata()
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun extractRetrieverFields(mmr: MediaMetadataRetriever): AudioMetadata {
        val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
        val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()
        val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
        val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val track = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        return AudioMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            track = track
        )
    }
}
