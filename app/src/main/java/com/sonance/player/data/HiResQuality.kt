package com.sonance.player.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

/**
 * 本地音频真实技术规格（统一供「Hi-Res 小金标判定」与「长按歌曲信息展示」复用）。
 */
data class AudioTechSpec(
    val formatLabel: String,
    val mimeType: String?,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateBps: Int,
    val bitrateEstimated: Boolean,
    val channelCount: Int,
    val sizeBytes: Long,
    val isLossless: Boolean
) {
    /** 比特率文本，如 `921 kbps`（估算时加 `约 ` 前缀） */
    fun bitrateText(): String {
        if (bitrateBps <= 0) return "未知"
        val kbps = (bitrateBps + 500) / 1000
        return if (bitrateEstimated) "约 $kbps kbps" else "$kbps kbps"
    }

    /** 采样率与位深摘要，如 `16bit / 44.1kHz FLAC`、`48kHz AAC` */
    fun summarySpecText(): String {
        val parts = ArrayList<String>(3)
        if (bitDepth > 0) parts += "${bitDepth}bit"
        if (sampleRateHz > 0) parts += HiResQuality.formatSampleRateKHz(sampleRateHz)
        val rateAndDepth = parts.joinToString(" / ")
        return when {
            rateAndDepth.isNotEmpty() && formatLabel.isNotEmpty() -> "$rateAndDepth $formatLabel"
            rateAndDepth.isNotEmpty() -> rateAndDepth
            formatLabel.isNotEmpty() -> formatLabel
            else -> "未知规格"
        }
    }
}

/**
 * 播放主界面「Hi-Res AUDIO」小金标的音质判定器 & 音频轨头规格探测器。
 *
 * 小金标按用户要求走「高音质 / 无损 / Hi-Res 情绪价值友好」策略：
 * - 本地无损格式（FLAC / WAV / ALAC / AIFF / APE / DSD / WavPack）直接点亮（无需严格 ≥88.2kHz/24bit）；
 * - 本地高码率有损（真实码率 ≥ 250 kbps，涵盖 320k MP3 与 256k+ AAC）点亮；
 * - 128k / 192k 等普通有损不点亮；
 * - 在线歌曲依据音源返回的真实音质标签：Hi-Res / 无损 / 320k（及同类 SQ/HQ/FLAC）点亮，试听 / 128k / 192k 不点亮。
 */
object HiResQuality {

    private val LOSSLESS_EXTENSIONS = setOf(
        "flac", "wav", "aiff", "aif", "alac", "ape", "dsf", "dff", "wv"
    )

    private val LOSSLESS_MIMES = setOf(
        "audio/flac",
        "audio/x-flac",
        "audio/alac",
        "audio/raw",
        "audio/wav",
        "audio/x-wav",
        "audio/aiff",
        "audio/x-aiff",
        "audio/dsd",
        "audio/dff",
        "audio/dsf"
    )

    private const val HIGH_BITRATE_THRESHOLD_BPS = 250_000
    private const val HIGH_RES_SAMPLE_RATE_HZ = 88_200
    private const val FALLBACK_AVG_BITRATE_KBPS = 290L

    private val specCache = object : LruCache<String, AudioTechSpec>(512) {}

    fun extractExtension(displayName: String): String {
        val slash = displayName.lastIndexOf('/')
        val dot = displayName.lastIndexOf('.')
        if (dot <= slash || dot >= displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isLosslessExtension(displayName: String): Boolean {
        return extractExtension(displayName) in LOSSLESS_EXTENSIONS
    }

    fun isLosslessMime(mimeType: String?): Boolean {
        val m = mimeType?.trim()?.lowercase(Locale.ROOT) ?: return false
        return m in LOSSLESS_MIMES || m.contains("flac") || m.contains("alac") || m.contains("dsd")
    }

    fun inferFormatLabel(displayName: String, mimeType: String?): String {
        val ext = extractExtension(displayName)
        if (ext.isNotEmpty()) {
            return when (ext) {
                "aif" -> "AIFF"
                else -> ext.uppercase(Locale.ROOT)
            }
        }
        val m = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            m.contains("flac") -> "FLAC"
            m.contains("alac") -> "ALAC"
            m.contains("wav") || m == "audio/raw" -> "WAV"
            m.contains("aiff") -> "AIFF"
            m.contains("mpeg") || m.contains("mp3") -> "MP3"
            m.contains("mp4a") || m.contains("aac") -> "AAC"
            m.contains("opus") -> "OPUS"
            m.contains("vorbis") || m.contains("ogg") -> "OGG"
            m.contains("dsd") || m.contains("dsf") || m.contains("dff") -> "DSD"
            m.isNotEmpty() -> m.substringAfter('/').uppercase(Locale.ROOT)
            else -> "未知"
        }
    }

    fun formatSampleRateKHz(sampleRateHz: Int): String {
        if (sampleRateHz <= 0) return ""
        return if (sampleRateHz % 1000 == 0) {
            "${sampleRateHz / 1000}kHz"
        } else {
            val khz = sampleRateHz / 1000.0
            val formatted = String.format(Locale.US, "%.1f", khz)
            if (formatted.endsWith(".0")) "${formatted.dropLast(2)}kHz" else "${formatted}kHz"
        }
    }

    /** 在线音源音质标签判定：试听 / 128k / 192k 不亮；320k / 无损 / Hi-Res 亮 */
    fun shouldHighlightOnlineQuality(label: String?): Boolean {
        val raw = label?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (raw.isEmpty() || raw.contains("试听")) return false
        return raw.contains("hi-res") ||
            raw.contains("hires") ||
            raw.contains("无损") ||
            raw.contains("320") ||
            raw.contains("flac") ||
            raw.contains("sq") ||
            raw.contains("hq")
    }

    /** 本地曲目规格判定纯函数，方便单元测试直接覆盖 */
    fun shouldHighlightLocalAudio(
        displayName: String,
        mimeType: String?,
        sampleRateHz: Int,
        bitrateBps: Int,
        sizeBytes: Long = 0L,
        durationMs: Long = 0L
    ): Boolean {
        if (isLosslessExtension(displayName) || isLosslessMime(mimeType)) return true
        if (sampleRateHz >= HIGH_RES_SAMPLE_RATE_HZ) return true
        if (bitrateBps >= HIGH_BITRATE_THRESHOLD_BPS) return true
        if (bitrateBps <= 0 && durationMs >= 5_000L && sizeBytes > 0L) {
            val avgKbps = (sizeBytes * 8L) / durationMs
            if (avgKbps >= FALLBACK_AVG_BITRATE_KBPS) return true
        }
        return false
    }

    internal data class HeaderPcmInfo(
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channelCount: Int
    )

    /**
     * 解析 FLAC STREAMINFO 块或 WAV fmt 块中的真实采样率、声道数与位深（纯字节计算，便于单测）。
     * Android `MediaExtractor` 读取 FLAC 时经常不返回 `KEY_PCM_ENCODING`，通过 64B 文件头即可补齐。
     */
    internal fun parseLosslessHeader(bytes: ByteArray): HeaderPcmInfo? {
        if (bytes.size < 16) return null
        var offset = 0
        // 若文件头带 ID3v2 标签，跳过 10 + synchsafe 长度
        if (bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte() &&
            bytes.size >= 10
        ) {
            val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            offset = 10 + tagSize
        }

        // 1) FLAC: "fLaC" + block header(4B) + STREAMINFO(至少 18B 读取到 bps)
        if (offset + 22 <= bytes.size &&
            bytes[offset] == 'f'.code.toByte() &&
            bytes[offset + 1] == 'L'.code.toByte() &&
            bytes[offset + 2] == 'a'.code.toByte() &&
            bytes[offset + 3] == 'C'.code.toByte()
        ) {
            val blockType = bytes[offset + 4].toInt() and 0x7F
            if (blockType == 0) {
                val s = offset + 8
                val b10 = bytes[s + 10].toInt() and 0xFF
                val b11 = bytes[s + 11].toInt() and 0xFF
                val b12 = bytes[s + 12].toInt() and 0xFF
                val b13 = bytes[s + 13].toInt() and 0xFF
                val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                val channels = ((b12 ushr 1) and 0x07) + 1
                val bitDepth = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                if (sampleRate > 0 && bitDepth in 4..32) {
                    return HeaderPcmInfo(sampleRate, bitDepth, channels)
                }
            }
        }

        // 2) WAV: "RIFF" .... "WAVE", 查找 "fmt " chunk
        if (offset == 0 && bytes.size >= 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        ) {
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = leInt(bytes, pos + 4)
                if (chunkId == "fmt " && chunkSize >= 16 && pos + 8 + 16 <= bytes.size) {
                    val data = pos + 8
                    val channels = leShort(bytes, data + 2)
                    val sampleRate = leInt(bytes, data + 4)
                    val bitDepth = leShort(bytes, data + 14)
                    if (sampleRate > 0 && bitDepth in 4..64) {
                        return HeaderPcmInfo(sampleRate, bitDepth, channels)
                    }
                    return null
                }
                if (chunkSize < 0) break
                val step = 8L + chunkSize + (chunkSize and 1)
                if (pos + step > bytes.size) break
                pos += step.toInt()
            }
        }
        return null
    }

    private fun leShort(b: ByteArray, i: Int): Int {
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun leInt(b: ByteArray, i: Int): Int {
        return (b[i].toInt() and 0xFF) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 3].toInt() and 0xFF) shl 24)
    }

    private fun pcmEncodingToBitDepth(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_FLOAT -> 32
        21 -> 24 // AudioFormat.ENCODING_PCM_24BIT_PACKED (API 31+)
        22 -> 32 // AudioFormat.ENCODING_PCM_32BIT (API 31+)
        else -> 0
    }

    private fun cacheKey(song: Song): String = "${song.id}_${song.sizeBytes}"

    fun peekAudioTechSpec(song: Song): AudioTechSpec? {
        if (!song.isLocal) return null
        return specCache.get(cacheKey(song))
    }

    fun shouldHighlightLocalAudio(song: Song, spec: AudioTechSpec): Boolean =
        shouldHighlightLocalAudio(
            displayName = song.displayName,
            mimeType = spec.mimeType,
            sampleRateHz = spec.sampleRateHz,
            bitrateBps = spec.bitrateBps,
            sizeBytes = song.sizeBytes,
            durationMs = song.durationMs
        )

    /** 同步快速命中（缓存 / 无损扩展名），不触发任何 IO */
    fun peekLocalBadge(song: Song): Boolean? {
        if (!song.isLocal) return null
        if (isLosslessExtension(song.displayName)) return true
        val cached = specCache.get(cacheKey(song)) ?: return null
        return shouldHighlightLocalAudio(song, cached)
    }

    /**
     * 后台轻量探测本地音频真实轨头（供播放页小金标与长按歌曲详情共享缓存）。
     */
    suspend fun probeAudioTechSpec(context: Context, song: Song): AudioTechSpec {
        val key = cacheKey(song)
        specCache.get(key)?.let { return it }

        val spec = withContext(Dispatchers.IO) {
            specCache.get(key)?.let { return@withContext it }
            var mime: String? = null
            var sampleRate = 0
            var bitrate = 0
            var bitDepth = 0
            var channels = 0

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, song.uri, null)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val trackMime = fmt.getString(MediaFormat.KEY_MIME)
                    if (trackMime?.startsWith("audio/") == true) {
                        mime = trackMime
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            bitrate = fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            bitDepth = pcmEncodingToBitDepth(fmt.getInteger(MediaFormat.KEY_PCM_ENCODING))
                        }
                        if (bitDepth <= 0 && fmt.containsKey("bits-per-sample")) {
                            bitDepth = fmt.getInteger("bits-per-sample")
                        }
                        break
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { extractor.release() }
            }

            if (bitrate <= 0 || bitDepth <= 0 || mime == null || sampleRate <= 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, song.uri)
                    if (mime == null) {
                        mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    }
                    if (bitrate <= 0) {
                        val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                        if (br != null && br > 0) bitrate = br
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (sampleRate <= 0) {
                            sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                                ?.toIntOrNull() ?: 0
                        }
                        if (bitDepth <= 0) {
                            bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                                ?.toIntOrNull() ?: 0
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { retriever.release() }
                }
            }

            val lossless = isLosslessExtension(song.displayName) || isLosslessMime(mime)
            if ((bitDepth <= 0 || sampleRate <= 0) && lossless) {
                val header = runCatching {
                    context.contentResolver.openInputStream(song.uri)?.use { readPrefix(it, 256) }
                }.getOrNull()
                val pcm = header?.let(::parseLosslessHeader)
                if (pcm != null) {
                    if (sampleRate <= 0) sampleRate = pcm.sampleRateHz
                    if (bitDepth <= 0) bitDepth = pcm.bitDepth
                    if (channels <= 0) channels = pcm.channelCount
                }
            }

            // FLAC / WAV 等容器常不填 KEY_BIT_RATE，有真实大小和时长时补算平均码率
            var estimated = false
            if (bitrate <= 0 && song.sizeBytes > 0L && song.durationMs >= 1_000L) {
                bitrate = ((song.sizeBytes * 8_000L) / song.durationMs).toInt()
                estimated = bitrate > 0
            }

            AudioTechSpec(
                formatLabel = inferFormatLabel(song.displayName, mime),
                mimeType = mime,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                bitrateBps = bitrate,
                bitrateEstimated = estimated,
                channelCount = channels,
                sizeBytes = song.sizeBytes,
                isLossless = lossless
            ).also { specCache.put(key, it) }
        }
        return spec
    }

    private fun readPrefix(input: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            val n = input.read(buf, read, maxBytes - read)
            if (n <= 0) break
            read += n
        }
        return if (read == maxBytes) buf else buf.copyOf(read)
    }

    suspend fun probeLocalBadge(context: Context, song: Song): Boolean {
        if (!song.isLocal) return false
        peekLocalBadge(song)?.let { return it }
        val spec = probeAudioTechSpec(context, song)
        return shouldHighlightLocalAudio(song, spec)
    }
}
