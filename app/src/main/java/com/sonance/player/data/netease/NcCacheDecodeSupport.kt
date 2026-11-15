package com.sonance.player.data.netease

import android.content.Context
import com.sonance.player.data.CoverCache
import com.sonance.player.data.HeaderInfo
import com.sonance.player.data.Mp4TagWriter
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.QmcDecoder
import com.sonance.player.data.RestorePlanner
import com.sonance.player.data.RestoreProbe
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

/**
 * 缓存音频解密、在线元数据补全、真实时长/完整性裁决与临时成品生成共用管线。
 *
 * 供「Shizuku 嗅探」([NeteaseCacheRepository]) 与「免 Shizuku 本机缓存转换」([LocalCacheConverter])
 * 共同复用，确保容器解密、试听残曲拦截、MP4 标签补写与封面入库逻辑完全一致。
 */
internal object NcCacheDecodeSupport {

    sealed interface PreparedAudio {
        data class Ready(
            val tempFile: File,
            val outName: String,
            val ext: String,
            val finalSize: Long,
            val info: HeaderInfo,
            val cover: ByteArray?
        ) : PreparedAudio

        data class Skipped(
            val reason: String,
            val trial: Boolean = false,
            val incomplete: Boolean = false
        ) : PreparedAudio
    }

    /** 读文件尾部窗口（QMC 密钥结构在最后 4KB 内），文件过短则全读。 */
    fun readTail(src: File): ByteArray? = runCatching {
        RandomAccessFile(src, "r").use { raf ->
            val len = raf.length().toInt()
            if (len <= 0) null
            else {
                val window = minOf(len, QmcDecoder.TAIL_WINDOW)
                raf.seek((len - window).toLong())
                ByteArray(window).also { raf.readFully(it) }
            }
        }
    }.getOrNull()

    /**
     * 解密加密容器（NCM / NMSF / KGM / QMC）至临时文件并完成元数据补全与完整性校验。
     * 调用方负责在写出完成后删除 [decFile]。
     */
    suspend fun prepareEncrypted(
        context: Context,
        src: File,
        decFile: File,
        displayName: String,
        containerExt: String,
        tail: ByteArray?,
        songId: String?,
        metaCache: MutableMap<String, NcMeta?>,
        scopeContext: CoroutineContext,
        trialMinSeconds: Double = NcDuration.TRIAL_MIN_SECONDS,
        legacyShouldSkip: (outName: String, expectedSize: Long, enrichable: Boolean) -> Boolean = { _, _, _ -> false },
        finalShouldSkip: (outName: String, finalSize: Long) -> Boolean = { _, _ -> false }
    ): PreparedAudio {
        src.inputStream().use { stream ->
            val probe = RestorePlanner.plan(stream, src.length(), "$displayName$containerExt", tail)
            val plan = when (probe) {
                is RestoreProbe.Ready -> probe.plan
                is RestoreProbe.Skip -> return PreparedAudio.Skipped(probe.reason)
            }

            val enrichable = songId != null && plan.info.title.isBlank()
            if (legacyShouldSkip(plan.outName, plan.expectedSize, enrichable)) {
                return PreparedAudio.Skipped("已转换过")
            }

            val meta = if (songId != null) metaCache.getOrPut(songId) { NcMetaFetcher.fetch(songId) } else null
            val fileArtist = plan.info.artist
            if (meta != null) {
                val artist = listOf(fileArtist, meta.artist).firstOrNull { it.isNotBlank() }
                    ?: splitArtistFromTitle(meta.title)
                val mergedMeta = NcMeta(meta.title, artist, meta.album, meta.cover)
                plan.applyMeta(
                    newInfo = plan.info.withMetadata(mergedMeta),
                    newCover = meta.cover,
                    outStem = NcMetaFetcher.outputStem(mergedMeta)
                )
            } else if (fileArtist.isBlank()) {
                val artist = splitArtistFromTitle(plan.info.title)
                if (artist.isNotBlank()) {
                    plan.applyMeta(
                        newInfo = plan.info.withMetadata(
                            NcMeta(plan.info.title, artist, plan.info.album, plan.cover)
                        ),
                        newCover = null,
                        outStem = null
                    )
                }
            }

            decFile.parentFile?.mkdirs()
            decFile.outputStream().use { out ->
                plan.writeTo(out) { scopeContext.ensureActive() }
            }

            val secs = NcDuration.probeSeconds(decFile)
            if (secs != null && secs < trialMinSeconds) {
                decFile.delete()
                return PreparedAudio.Skipped("试听残曲（约 ${fmtSecs(secs)}秒）", trial = true)
            }

            val integrity = NcDuration.completeness(decFile)
            if (integrity != null && !integrity.complete) {
                decFile.delete()
                return PreparedAudio.Skipped(
                    "缓存不完整（约 ${fmtSecs(integrity.playableSeconds)}/${fmtSecs(integrity.declaredSeconds)}秒）",
                    incomplete = true
                )
            }

            if (plan.ext == ".m4a") {
                Mp4TagWriter.tag(decFile, plan.info, plan.cover) { f -> NcDuration.probeSeconds(f) != null }
            }

            val finalSize = decFile.length()
            if (finalShouldSkip(plan.outName, finalSize)) {
                decFile.delete()
                return PreparedAudio.Skipped("已转换过")
            }

            storeCover(context, plan.info, plan.cover)
            return PreparedAudio.Ready(
                tempFile = decFile,
                outName = plan.outName,
                ext = plan.ext,
                finalSize = finalSize,
                info = plan.info,
                cover = plan.cover
            )
        }
    }

    /** 校验明文缓存音频（试听时长 / m4a 完整性）与在线元数据命名。 */
    suspend fun preparePlain(
        context: Context,
        src: File,
        displayName: String,
        plainExt: String,
        songId: String?,
        metaCache: MutableMap<String, NcMeta?>,
        trialMinSeconds: Double = NcDuration.TRIAL_MIN_SECONDS,
        shouldSkip: (outName: String, sizeBytes: Long) -> Boolean = { _, _ -> false }
    ): PreparedAudio {
        val ext = plainExt.ifBlank { ".mp3" }
        val meta = if (songId != null) metaCache.getOrPut(songId) { NcMetaFetcher.fetch(songId) } else null
        val stem = meta?.let { NcMetaFetcher.outputStem(it) }?.ifBlank { null }
            ?: sanitizeName(displayName)
        val outName = "$stem$ext"
        val sizeBytes = src.length()
        if (shouldSkip(outName, sizeBytes)) {
            return PreparedAudio.Skipped("已转换过")
        }

        val secs = NcDuration.probeSeconds(src)
        if (secs != null && secs < trialMinSeconds) {
            return PreparedAudio.Skipped("试听残曲（约 ${fmtSecs(secs)}秒）", trial = true)
        }

        val integrity = NcDuration.completeness(src)
        if (integrity != null && !integrity.complete) {
            return PreparedAudio.Skipped(
                "缓存不完整（约 ${fmtSecs(integrity.playableSeconds)}/${fmtSecs(integrity.declaredSeconds)}秒）",
                incomplete = true
            )
        }

        val info = if (meta != null) {
            HeaderInfo(
                payloadOffset = 0L,
                suggestedExt = ext,
                metadata = buildMap {
                    if (meta.title.isNotBlank()) put("title", meta.title)
                    if (meta.artist.isNotBlank()) put("artist", meta.artist)
                    if (meta.album.isNotBlank()) put("album", meta.album)
                }
            )
        } else {
            HeaderInfo(payloadOffset = 0L, suggestedExt = ext)
        }
        storeCover(context, info, meta?.cover)
        return PreparedAudio.Ready(
            tempFile = src,
            outName = outName,
            ext = ext,
            finalSize = sizeBytes,
            info = info,
            cover = meta?.cover
        )
    }

    fun containerExtFor(entry: NeteaseCacheEntry): String {
        val srcName = entry.fragments.firstOrNull()?.substringAfterLast('/') ?: ""
        val srcExt = com.sonance.player.data.LockedFormat.extensionOf(srcName)
        return when (entry.kind) {
            NcKind.NCM -> ".ncm"
            NcKind.NMS -> ".nmsf"
            NcKind.KGM -> if (srcExt.isBlank()) ".kgm" else ".$srcExt"
            NcKind.QMC -> if (srcExt.isBlank()) ".mflac" else ".$srcExt"
            else -> ""
        }
    }

    fun storeCover(context: Context, info: HeaderInfo, cover: ByteArray?) {
        if (cover == null || cover.isEmpty()) return
        if (info.title.isBlank() && info.artist.isBlank()) return
        val key = OnlineCover.keyOf(info.title, info.artist) ?: return
        runCatching { CoverCache.store(context, key, cover) }
    }

    fun HeaderInfo.withMetadata(meta: NcMeta): HeaderInfo {
        val extra = buildMap {
            if (meta.title.isNotBlank()) put("title", meta.title)
            if (meta.artist.isNotBlank()) put("artist", meta.artist)
            if (meta.album.isNotBlank()) put("album", meta.album)
        }
        if (extra.isEmpty()) return this
        return copy(metadata = metadata + extra)
    }

    fun splitArtistFromTitle(title: String): String {
        val t = title.trim()
        val i = t.lastIndexOf(" - ")
        if (i <= 0 || i + 3 >= t.length) return ""
        return t.substring(i + 3).trim()
    }

    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        ".flac" -> "audio/flac"
        ".ogg" -> "audio/ogg"
        ".m4a" -> "audio/mp4"
        ".wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_')
            .replace(':', '_').replace('*', '_')
            .replace('?', '_').replace('"', '_')
            .replace('<', '_').replace('>', '_')
            .replace('|', '_')
            .ifBlank { "未命名缓存" }

    fun fmtSecs(secs: Double): String = "%.0f".format(secs)
}
