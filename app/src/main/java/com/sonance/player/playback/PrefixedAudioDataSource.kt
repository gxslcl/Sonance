package com.sonance.player.playback

import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.sonance.player.data.PrivateAudioRestorer

/**
 * 本地音频容器前置清洗与即时脱壳数据源：
 *
 * 部分第三方音乐客户端（如酷狗）下载的 `.ogg` 文件无法被 Media3 原生 `OggExtractor` 识别，
 * 常见原因包括：
 * 1. 文件开头被额外写入一个或多个 `ID3v2` 标签或私有头字节，导致 `OggExtractor.sniff()` 在偏移 0 未看到 `OggS` 魔数直接报错；
 * 2. 个别文件保留了酷狗 `KGM/KGMA/VPR v3` 私有加密壳却被改名为 `.ogg`。
 *
 * 本包装器仅对本地 `file`/`content` 流在打开偏移 0 时探测一次头部（结果按 URI 缓存），
 * 自动跳过前置非 Ogg 数据或按偏移就地异或解密，彻底保持可 Seek 与零拷贝读取能力。
 */
@UnstableApi
internal class PrefixedAudioDataSource(
    private val upstream: DataSource
) : DataSource {

    sealed interface StreamTransform {
        val payloadOffset: Long

        data object NONE : StreamTransform {
            override val payloadOffset: Long = 0L
        }

        data class OffsetSkip(override val payloadOffset: Long) : StreamTransform

        class KgmDecrypt(
            override val payloadOffset: Long,
            val key: ByteArray,
            val isVpr: Boolean
        ) : StreamTransform
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PrefixedAudioDataSource(upstreamFactory.createDataSource())
    }

    private var activeTransform: StreamTransform = StreamTransform.NONE
    private var payloadPos: Long = 0L
    private var currentUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val transform = resolveTransform(dataSpec.uri)
        activeTransform = transform
        val skip = transform.payloadOffset
        if (skip <= 0L && transform === StreamTransform.NONE) {
            payloadPos = dataSpec.position
            return upstream.open(dataSpec)
        }
        val shiftedSpec = dataSpec.buildUpon()
            .setPosition((dataSpec.position + skip).coerceAtLeast(0L))
            .build()
        val remaining = upstream.open(shiftedSpec)
        payloadPos = dataSpec.position
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            val transform = activeTransform
            if (transform is StreamTransform.KgmDecrypt) {
                val startPos = payloadPos
                if (startPos >= 0L && startPos <= Int.MAX_VALUE - n.toLong()) {
                    PrivateAudioRestorer.xorChunk(
                        buf = buffer,
                        bufOffset = offset,
                        count = n,
                        startPos = startPos.toInt(),
                        key = transform.key,
                        isVpr = transform.isVpr
                    )
                }
            }
            payloadPos += n
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri ?: currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        currentUri = null
        activeTransform = StreamTransform.NONE
        upstream.close()
    }

    private fun resolveTransform(uri: Uri): StreamTransform {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return StreamTransform.NONE
        val cacheKey = uri.toString()
        if (cacheKey.isBlank()) return StreamTransform.NONE
        synchronized(transformCache) {
            transformCache.get(cacheKey)?.let { return it }
        }
        val probed = runCatching { probeTransform(uri) }.getOrDefault(StreamTransform.NONE)
        synchronized(transformCache) {
            transformCache.put(cacheKey, probed)
        }
        return probed
    }

    private fun probeTransform(uri: Uri): StreamTransform {
        val head = ByteArray(PROBE_HEAD_BYTES)
        var headLen = 0
        try {
            val openLen = upstream.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(0L)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )
            if (openLen != C.LENGTH_UNSET.toLong() && openLen < 16L) {
                return StreamTransform.NONE
            }
            headLen = readAtMost(upstream, head, head.size)
        } finally {
            runCatching { upstream.close() }
        }

        if (headLen < 16) return StreamTransform.NONE

        // 1) 若文件实质为酷狗私有加密容器（如被改为 .ogg 后缀的 KGM/KGMA/VPR v3），即时脱壳解密
        if (headLen >= PrivateAudioRestorer.HEADER_MIN) {
            val kgm = PrivateAudioRestorer.parseHeader(head)
            if (kgm != null && kgm.version == PrivateAudioRestorer.SUPPORTED_VERSION) {
                return StreamTransform.KgmDecrypt(
                    payloadOffset = kgm.payloadOffset.toLong(),
                    key = kgm.key,
                    isVpr = kgm.isVpr
                )
            }
        }

        // 2) 标准容器魔数直接放行（零开销）
        if (startsWithStandardAudioMagic(head, headLen)) {
            return StreamTransform.NONE
        }

        // 3) 若文件以 ID3v2 开头（支持嵌套多个标签及超过 16KB 的大图封面标签），解析长度并探测其后是否为 Ogg BOS 页
        if (isId3Header(head, 0)) {
            val afterId3 = skipChainedId3Offset(uri, head, headLen)
            if (afterId3 > 0L) {
                val oggOffset = findOggBosAfterOffset(uri, afterId3)
                if (oggOffset > 0L) {
                    return StreamTransform.OffsetSkip(oggOffset)
                }
            }
            return StreamTransform.NONE
        }

        // 4) 若非标准前缀也不像 MP3 裸帧头，在前 16KB 内扫描严格 Ogg BOS 页（处理酷狗等私有前置头）
        if (!looksLikeMpegFrameSync(head)) {
            val idx = findStrictOggBos(head, headLen, requireSeqZero = true)
            if (idx > 0) {
                return StreamTransform.OffsetSkip(idx.toLong())
            }
        }

        return StreamTransform.NONE
    }

    private fun skipChainedId3Offset(uri: Uri, head: ByteArray, headLen: Int): Long {
        var offset = 0L
        var hops = 0
        while (hops++ < 4 && offset <= MAX_ID3_SKIP_BYTES) {
            val hdr = if (offset + 10 <= headLen) {
                head.copyOfRange(offset.toInt(), offset.toInt() + 10)
            } else {
                readSliceAt(uri, offset, 10) ?: break
            }
            if (!isId3Header(hdr, 0)) break
            val flags = hdr[5].toInt() and 0xFF
            val tagSize = ((hdr[6].toInt() and 0x7F) shl 21) or
                ((hdr[7].toInt() and 0x7F) shl 14) or
                ((hdr[8].toInt() and 0x7F) shl 7) or
                (hdr[9].toInt() and 0x7F)
            val footer = if ((flags and 0x10) != 0) 10L else 0L
            val next = offset + 10L + tagSize.toLong() + footer
            if (next <= offset || next > MAX_ID3_SKIP_BYTES) break
            offset = next
        }
        return offset
    }

    private fun findOggBosAfterOffset(uri: Uri, afterOffset: Long): Long {
        val window = readSliceAt(uri, afterOffset, POST_ID3_SCAN_BYTES) ?: return -1L
        val rel = findStrictOggBos(window, window.size, requireSeqZero = false)
        return if (rel >= 0) afterOffset + rel else -1L
    }

    private fun readSliceAt(uri: Uri, position: Long, maxBytes: Int): ByteArray? {
        if (position < 0L || maxBytes <= 0) return null
        val buf = ByteArray(maxBytes)
        val n = try {
            upstream.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(position)
                    .setLength(maxBytes.toLong())
                    .build()
            )
            readAtMost(upstream, buf, maxBytes)
        } finally {
            runCatching { upstream.close() }
        }
        if (n <= 0) return null
        return if (n == maxBytes) buf else buf.copyOf(n)
    }

    companion object {
        private const val PROBE_HEAD_BYTES = 16 * 1024
        private const val MAX_ID3_SKIP_BYTES = 8L * 1024L * 1024L
        private const val POST_ID3_SCAN_BYTES = 4096
        private val transformCache = LruCache<String, StreamTransform>(64)

        private fun readAtMost(ds: DataSource, buf: ByteArray, limit: Int): Int {
            var total = 0
            while (total < limit) {
                val n = ds.read(buf, total, limit - total)
                if (n <= 0) break
                total += n
            }
            return total
        }

        private fun isId3Header(buf: ByteArray, off: Int): Boolean {
            if (off + 10 > buf.size) return false
            if (buf[off] != 'I'.code.toByte() || buf[off + 1] != 'D'.code.toByte() || buf[off + 2] != '3'.code.toByte()) {
                return false
            }
            val major = buf[off + 3].toInt() and 0xFF
            if (major > 4) return false
            return ((buf[off + 6].toInt() and 0x80) == 0) &&
                ((buf[off + 7].toInt() and 0x80) == 0) &&
                ((buf[off + 8].toInt() and 0x80) == 0) &&
                ((buf[off + 9].toInt() and 0x80) == 0)
        }

        private fun startsWithStandardAudioMagic(buf: ByteArray, len: Int): Boolean {
            if (len < 4) return false
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            val b2 = buf[2].toInt() and 0xFF
            val b3 = buf[3].toInt() and 0xFF
            // OggS / fLaC / RIFF / DSD  / FRM8
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true
            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return true
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true
            if (b0 == 0x44 && b1 == 0x53 && b2 == 0x44 && b3 == 0x20) return true
            if (b0 == 0x46 && b1 == 0x52 && b2 == 0x4D && b3 == 0x38) return true
            // ISO BMFF (M4A/MP4): ....ftyp
            if (len >= 8 &&
                buf[4] == 'f'.code.toByte() && buf[5] == 't'.code.toByte() &&
                buf[6] == 'y'.code.toByte() && buf[7] == 'p'.code.toByte()
            ) {
                return true
            }
            return false
        }

        private fun looksLikeMpegFrameSync(buf: ByteArray): Boolean {
            if (buf.size < 2) return false
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            return b0 == 0xFF && (b1 and 0xE0) == 0xE0
        }

        internal fun findStrictOggBos(buf: ByteArray, len: Int, requireSeqZero: Boolean): Int {
            val end = minOf(len, buf.size) - 27
            if (end < 0) return -1
            for (i in 0..end) {
                if (buf[i] != 'O'.code.toByte() ||
                    buf[i + 1] != 'g'.code.toByte() ||
                    buf[i + 2] != 'g'.code.toByte() ||
                    buf[i + 3] != 'S'.code.toByte()
                ) {
                    continue
                }
                if (buf[i + 4] != 0.toByte()) continue // stream_structure_version 必须是 0
                val headerType = buf[i + 5].toInt() and 0xFF
                if ((headerType and 0x02) == 0) continue // 必须是 BOS 起始页
                if (requireSeqZero) {
                    val seqZero = buf[i + 18] == 0.toByte() && buf[i + 19] == 0.toByte() &&
                        buf[i + 20] == 0.toByte() && buf[i + 21] == 0.toByte()
                    if (!seqZero) continue
                }
                val segs = buf[i + 26].toInt() and 0xFF
                if (segs in 1..255 && i + 27 + segs <= minOf(len, buf.size)) {
                    return i
                }
            }
            return -1
        }
    }
}
