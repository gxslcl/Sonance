package com.sonance.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.Locale

/**
 * 同目录独立媒体文件关联器：
 * 当「音频文件 + 独立封面图片 + 独立歌词文件」放在同一个文件夹中时，
 * 自动把它们识别并配对成同一首歌的封面与歌词。
 */
internal object SidecarAssetResolver {

    internal data class SidecarSource(
        val file: File? = null,
        val uri: Uri? = null
    ) {
        fun readBytes(context: Context, maxBytes: Int): ByteArray? = runCatching {
            val bytes = when {
                file != null && file.isFile && file.canRead() -> {
                    if (file.length() > maxBytes.toLong()) return null
                    file.readBytes()
                }
                uri != null -> {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }
                else -> null
            }
            bytes?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_LYRIC_BYTES = 256 * 1024

    private val COVER_EXTS = listOf("jpg", "jpeg", "png", "webp", "bmp")
    private val STANDARD_COVER_STEMS = listOf("cover", "folder", "front", "album", "artwork", "poster", "thumb")
    private val LYRIC_EXTS = listOf("lrc", "yrc", "ttml", "srt", "txt")
    private val AUDIO_EXTS = LockedFormat.strictAudioExtensions + LockedFormat.plainUnsupportedExtensions

    private val coverMissCache = LruCache<String, Boolean>(512)
    private val coverHitCache = LruCache<String, SidecarSource>(256)
    private val lyricMissCache = LruCache<String, Boolean>(512)
    private val lyricHitCache = LruCache<String, String>(128)

    fun clear() {
        coverMissCache.evictAll()
        coverHitCache.evictAll()
        lyricMissCache.evictAll()
        lyricHitCache.evictAll()
    }

    fun resolveCoverBytes(
        context: Context,
        songUri: Uri,
        songTitle: String? = null
    ): ByteArray? {
        val key = songUri.toString()
        coverHitCache.get(key)?.let { cached ->
            return cached.readBytes(context, MAX_IMAGE_BYTES)
        }
        if (coverMissCache.get(key) == true) return null

        val found = findCoverSource(context, songUri, songTitle)
        if (found != null) {
            val bytes = found.readBytes(context, MAX_IMAGE_BYTES)
            if (bytes != null) {
                coverHitCache.put(key, found)
                return bytes
            }
        }
        coverMissCache.put(key, true)
        return null
    }

    fun resolveLyrics(
        context: Context,
        songUri: Uri,
        songTitle: String? = null
    ): String? {
        val key = songUri.toString()
        lyricHitCache.get(key)?.let { return it }
        if (lyricMissCache.get(key) == true) return null

        val found = findLyricSource(context, songUri, songTitle)
        if (found != null) {
            val bytes = found.readBytes(context, MAX_LYRIC_BYTES)
            if (bytes != null) {
                val decoded = LyricsRepository.decodeLyricsBytes(bytes).removePrefix("\uFEFF").trim()
                if (decoded.isNotEmpty()) {
                    lyricHitCache.put(key, decoded)
                    return decoded
                }
            }
        }
        lyricMissCache.put(key, true)
        return null
    }

    private fun findCoverSource(context: Context, songUri: Uri, songTitle: String?): SidecarSource? {
        val listing = inspectSongFolder(context, songUri) ?: return null
        val match = selectBestSidecarCover(
            audioBaseName = listing.audioBaseName,
            songTitle = songTitle,
            candidates = listing.entries.map { it.name },
            audioCountInFolder = listing.audioCount
        ) ?: return null
        return listing.entries.firstOrNull { it.name.equals(match, ignoreCase = true) }?.source
    }

    private fun findLyricSource(context: Context, songUri: Uri, songTitle: String?): SidecarSource? {
        val listing = inspectSongFolder(context, songUri) ?: return null
        val match = selectBestSidecarLyric(
            audioBaseName = listing.audioBaseName,
            songTitle = songTitle,
            candidates = listing.entries.map { it.name },
            audioCountInFolder = listing.audioCount
        ) ?: return null
        val entry = listing.entries.firstOrNull { it.name.equals(match, ignoreCase = true) } ?: return null
        // `.txt` 作为唯一单曲目录兜底时做轻校验，避免把无关说明文本读成歌词
        if (entry.name.substringAfterLast('.', "").equals("txt", ignoreCase = true) &&
            !entry.name.substringBeforeLast('.').equals(listing.audioBaseName, ignoreCase = true) &&
            !normalizeStem(entry.name.substringBeforeLast('.')).equals(normalizeStem(songTitle.orEmpty()))
        ) {
            val raw = entry.source.readBytes(context, MAX_LYRIC_BYTES)
                ?.let(LyricsRepository::decodeLyricsBytes)
                ?: return null
            if (!raw.contains('[') && raw.lines().count { it.isNotBlank() } < 4) return null
        }
        return entry.source
    }

    private data class FolderEntry(val name: String, val source: SidecarSource)
    private data class FolderListing(
        val audioBaseName: String,
        val audioCount: Int,
        val entries: List<FolderEntry>
    )

    private fun inspectSongFolder(context: Context, songUri: Uri): FolderListing? {
        // 1) file:// URI 直接扫父目录
        if (songUri.scheme.equals("file", ignoreCase = true)) {
            val file = songUri.path?.let(::File) ?: return null
            return inspectJavaDir(file.parentFile, file.nameWithoutExtension)
        }

        // 2) MediaStore 查询该曲目的物理路径 / RELATIVE_PATH
        var audioDisplayName = ""
        var dataPath = ""
        var relPath = ""
        if (songUri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                val cols = buildList {
                    add(MediaStore.MediaColumns.DISPLAY_NAME)
                    @Suppress("DEPRECATION")
                    add(MediaStore.MediaColumns.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.MediaColumns.RELATIVE_PATH)
                    }
                }.toTypedArray()
                context.contentResolver.query(songUri, cols, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        @Suppress("DEPRECATION")
                        val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val iRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        } else -1
                        if (iName >= 0) audioDisplayName = c.getString(iName).orEmpty()
                        if (iData >= 0) dataPath = c.getString(iData).orEmpty()
                        if (iRel >= 0) relPath = c.getString(iRel).orEmpty()
                    }
                }
            }
        }

        val audioBase = audioDisplayName.substringBeforeLast('.')
            .ifBlank { File(dataPath).nameWithoutExtension }

        // 2A) 优先试文件系统父目录（Android 10 以下 / 所有文件权限 / 自建目录最完整）
        if (dataPath.isNotBlank()) {
            val parent = File(dataPath).parentFile
            if (parent != null && parent.isDirectory && parent.canRead()) {
                val fsListing = inspectJavaDir(parent, audioBase)
                if (fsListing != null && fsListing.entries.isNotEmpty()) {
                    return fsListing
                }
            }
        }

        // 2B) 分区存储兼容：通过 MediaStore.Files + Images 查询同一文件夹中的侧车封面与歌词
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relPath.isNotBlank()) {
            val out = ArrayList<FolderEntry>()
            var audioCount = 0
            val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            runCatching {
                context.contentResolver.query(
                    filesUri,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(relPath),
                    null
                )?.use { c ->
                    val iId = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext() && out.size < 120) {
                        val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                        if (name.isBlank() || name.startsWith(".")) continue
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (ext in AUDIO_EXTS) {
                            audioCount++
                        } else if (ext in COVER_EXTS || ext in LYRIC_EXTS) {
                            val id = c.getLong(iId)
                            val uri = if (ext in COVER_EXTS) {
                                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            } else {
                                ContentUris.withAppendedId(filesUri, id)
                            }
                            out += FolderEntry(name, SidecarSource(uri = uri))
                        }
                    }
                }
            }
            if (out.isNotEmpty()) {
                return FolderListing(audioBase, audioCount.coerceAtLeast(1), out)
            }
        }

        return null
    }

    private fun inspectJavaDir(dir: File?, audioBaseName: String): FolderListing? {
        if (dir == null || !dir.isDirectory || !dir.canRead()) return null
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val entries = ArrayList<FolderEntry>()
        var audioCount = 0
        for (f in children) {
            if (!f.isFile || f.name.startsWith(".")) continue
            val ext = f.extension.lowercase(Locale.ROOT)
            if (ext in AUDIO_EXTS) {
                audioCount++
            } else if (ext in COVER_EXTS || ext in LYRIC_EXTS) {
                if (entries.size < 120) entries += FolderEntry(f.name, SidecarSource(file = f))
            }
        }
        return FolderListing(audioBaseName, audioCount.coerceAtLeast(1), entries)
    }

    @VisibleForTesting
    internal fun selectBestSidecarCover(
        audioBaseName: String,
        songTitle: String?,
        candidates: List<String>,
        audioCountInFolder: Int
    ): String? {
        val images = candidates.filter {
            it.substringAfterLast('.', "").lowercase(Locale.ROOT) in COVER_EXTS
        }
        if (images.isEmpty()) return null

        val normAudio = normalizeStem(audioBaseName)
        val normTitle = normalizeStem(songTitle.orEmpty())

        // 1. 与音频文件完全同主名（精确 / 归一化）
        images.firstOrNull { it.substringBeforeLast('.').equals(audioBaseName, ignoreCase = true) }
            ?.let { return it }
        if (normAudio.isNotEmpty()) {
            images.firstOrNull { normalizeStem(it.substringBeforeLast('.')) == normAudio }
                ?.let { return it }
        }

        // 2. 与歌曲标题同主名
        if (normTitle.isNotEmpty()) {
            images.firstOrNull { normalizeStem(it.substringBeforeLast('.')) == normTitle }
                ?.let { return it }
        }

        // 3. 通用封面命名（cover / folder / front / album / artwork ...）
        for (stem in STANDARD_COVER_STEMS) {
            images.firstOrNull { it.substringBeforeLast('.').equals(stem, ignoreCase = true) }
                ?.let { return it }
        }

        // 4. 同文件夹单曲兜底：该目录内只有 1 首音频文件时，哪怕封面文件名不同也直接关联
        if (audioCountInFolder <= 1) {
            return images.firstOrNull()
        }
        return null
    }

    @VisibleForTesting
    internal fun selectBestSidecarLyric(
        audioBaseName: String,
        songTitle: String?,
        candidates: List<String>,
        audioCountInFolder: Int
    ): String? {
        val lyrics = candidates.filter {
            it.substringAfterLast('.', "").lowercase(Locale.ROOT) in LYRIC_EXTS
        }
        if (lyrics.isEmpty()) return null

        val sorted = lyrics.sortedBy { extPriority(it.substringAfterLast('.', "").lowercase(Locale.ROOT)) }
        val normAudio = normalizeStem(audioBaseName)
        val normTitle = normalizeStem(songTitle.orEmpty())

        sorted.firstOrNull { it.substringBeforeLast('.').equals(audioBaseName, ignoreCase = true) }
            ?.let { return it }
        if (normAudio.isNotEmpty()) {
            sorted.firstOrNull { normalizeStem(it.substringBeforeLast('.')) == normAudio }
                ?.let { return it }
        }
        if (normTitle.isNotEmpty()) {
            sorted.firstOrNull { normalizeStem(it.substringBeforeLast('.')) == normTitle }
                ?.let { return it }
        }
        // 同文件夹单曲兜底：独立「歌曲 + 封面 + 歌词」三文件放在同一目录时直接匹配
        if (audioCountInFolder <= 1) {
            return sorted.firstOrNull()
        }
        return null
    }

    private fun extPriority(ext: String): Int = when (ext) {
        "yrc" -> 0
        "lrc" -> 1
        "ttml" -> 2
        "srt" -> 3
        else -> 4
    }

    private fun normalizeStem(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("""[\s\-_·•()\[\]（）【】'"]+"""), "")
}
