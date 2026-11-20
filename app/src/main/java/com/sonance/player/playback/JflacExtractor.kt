package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import org.jflac.FLACDecoder
import org.jflac.FrameDecodeException
import org.jflac.util.ByteData
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * FLAC 纯 Java 软解提取器（legacy 专用）。
 *
 * 老车机 / 精简 ROM 可能没有 FLAC 的 MediaCodec 解码器（或被厂商裁掉），
 * 默认 FlacExtractor + MediaCodecAudioRenderer 这条路直接报错。这里用 jflac
 * 把 FLAC 帧软解成 16-bit PCM，以 audio/raw 交给默认音频渲染器 —— 不需要任何
 * 硬件解码器，且 EQ / 音效 / 可视化整条 AudioSink 链路照常生效。
 *
 * 实现要点：
 * - jflac 的 [FLACDecoder.getTotalBytesRead] 按「实际消费的比特数」计字节，
 *   不受其内部缓冲预读影响，帧边界处取值即精确文件偏移，用来建 seek 索引。
 * - seek 重开流时不能重新解析全部元数据（输入已定位在文件中部）：把首次解析
 *   时截获的「fLaC + STREAMINFO」42 字节前缀（last-block 标志置位）拼在输入
 *   前面喂给新解码器，元数据即完整，帧数据紧接真实输入。
 * - 24/32-bit 源统一降到 16-bit 输出：AudioTrack 全版本兼容，老设备优先保播放。
 */
class JflacExtractor : Extractor {

    private var trackOutput: TrackOutput? = null

    private var sampleRate = 0
    private var channels = 0
    private var bitsPerSample = 16
    private var durationUs = C.TIME_UNSET
    private var parsed = false

    /** 截获的 fLaC + STREAMINFO 前缀（last-block 已置位），seek 重开时复用 */
    private var streamInfoPrefix: ByteArray? = null
    private var decoder: FLACDecoder? = null

    /** 当前解码器对应输入流的起始文件偏移 */
    private var baseOffset = 0L

    /** 解码器已消费字节里不属于真实输入的部分（重开时的 42 字节前缀） */
    private var decoderByteBias = 0L
    private var metadataEndOffset = 0L

    /** 已输出（含已跳过）的绝对采样序号 */
    private var currentSample = 0L
    private var pendingSkip = 0L
    private var restartOffset = -1L
    private var pendingSeekTimeUs = -1L

    private var pcm = ByteArray(0)
    private val parsable = ParsableByteArray()
    private var scratch: ByteData? = null

    private var indexSamples = LongArray(256)
    private var indexOffsets = LongArray(256)
    private var indexSize = 0

    override fun sniff(input: ExtractorInput): Boolean {
        try {
            val header = ByteArray(10)
            if (!input.peekFully(header, 0, 10, true)) return false
            if (header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte()
            ) {
                val size = id3Size(header)
                if (!input.advancePeekPosition(size, true)) return false
                val magic = ByteArray(4)
                if (!input.peekFully(magic, 0, 4, true)) return false
                return isFlacMagic(magic, 0)
            }
            return isFlacMagic(header, 0)
        } finally {
            input.resetPeekPosition()
        }
    }

    private fun isFlacMagic(b: ByteArray, off: Int): Boolean =
        b[off] == 'f'.code.toByte() && b[off + 1] == 'L'.code.toByte() &&
            b[off + 2] == 'a'.code.toByte() && b[off + 3] == 'C'.code.toByte()

    /** ID3v2 头之后的负载字节数（含 footer），头本身 10 字节另计 */
    private fun id3Size(header: ByteArray): Int {
        val size = ((header[6].toInt() and 0x7f) shl 21) or
            ((header[7].toInt() and 0x7f) shl 14) or
            ((header[8].toInt() and 0x7f) shl 7) or
            (header[9].toInt() and 0x7f)
        val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
        return size + footer
    }

    override fun init(output: ExtractorOutput) {
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
        output.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = this@JflacExtractor.durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val offset = if (!parsed) 0L else indexOffsetFor(sampleAt(timeUs))
                return SeekMap.SeekPoints(SeekPoint(timeUs, offset))
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (!parsed) {
            parseStream(input, out)
            if (pendingSeekTimeUs > 0L) {
                val t = pendingSeekTimeUs
                pendingSeekTimeUs = -1L
                seekInternal(sampleAt(t))
            } else {
                pendingSeekTimeUs = -1L
            }
        }
        if (decoder == null) {
            if (input.position != restartOffset) {
                seekPosition.position = restartOffset
                return Extractor.RESULT_SEEK
            }
            reopen(input)
        }
        val dec = decoder ?: return Extractor.RESULT_END_OF_INPUT

        var emitted = 0
        while (emitted < FRAMES_PER_READ) {
            val frameStart = baseOffset + dec.totalBytesRead - decoderByteBias
            val frame = try {
                dec.readNextFrame()
            } catch (e: EOFException) {
                null
            } ?: return Extractor.RESULT_END_OF_INPUT
            val block = frame.header.blockSize.toLong()
            if (block <= 0L) throw IOException("FLAC: bad block size $block")
            addIndex(currentSample, frameStart)

            if (pendingSkip >= block) {
                pendingSkip -= block
                currentSample += block
                continue
            }

            try {
                dec.decodeFrame(frame, scratch)
            } catch (e: FrameDecodeException) {
                // 单帧 CRC 坏：跳过这一帧继续，别让一个坏帧整首报错
                currentSample += block
                pendingSkip = 0L
                continue
            }
            val drop = pendingSkip.toInt()
            pendingSkip = 0L
            val size = interleave16(dec.channelData, drop, block.toInt())
            if (size > 0) {
                val timeUs = (currentSample + drop) * 1_000_000L / sampleRate
                parsable.reset(pcm, size)
                out.sampleData(parsable, size)
                out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
                emitted++
            }
            currentSample += block
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(positionUs: Long, timeUs: Long) {
        if (!parsed) {
            pendingSeekTimeUs = timeUs
            return
        }
        seekInternal(sampleAt(timeUs))
    }

    override fun release() {
        decoder = null
        trackOutput = null
        scratch = null
        streamInfoPrefix = null
        indexSize = 0
    }

    private fun parseStream(input: ExtractorInput, out: TrackOutput) {
        // ID3v2 前缀统一在这里跳过，不依赖 jflac 内部的跳过实现
        input.resetPeekPosition()
        val header = ByteArray(10)
        if (input.peekFully(header, 0, 10, true) &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        ) {
            input.resetPeekPosition()
            input.skipFully(10 + id3Size(header))
        } else {
            input.resetPeekPosition()
        }
        baseOffset = input.position
        decoderByteBias = 0L
        val recording = RecordingInput(input)
        val dec = FLACDecoder(recording)
        dec.readMetadata()
        val info = dec.streamInfo ?: throw IOException("FLAC: STREAMINFO missing")
        if (info.sampleRate <= 0 || info.channels <= 0 || info.bitsPerSample <= 0) {
            throw IOException("FLAC: bad STREAMINFO ${info.sampleRate}/${info.channels}/${info.bitsPerSample}")
        }
        if (recording.captured != PREFIX_LEN ||
            (recording.capture[4].toInt() and 0x7f) != 0
        ) {
            throw IOException("FLAC: STREAMINFO prefix not captured")
        }
        recording.capture[4] = (recording.capture[4].toInt() or 0x80).toByte()
        streamInfoPrefix = recording.capture

        sampleRate = info.sampleRate
        channels = info.channels
        bitsPerSample = info.bitsPerSample
        durationUs = if (info.totalSamples > 0L) {
            info.totalSamples * 1_000_000L / sampleRate
        } else {
            C.TIME_UNSET
        }
        metadataEndOffset = baseOffset + dec.totalBytesRead
        pcm = ByteArray(info.maxBlockSize * channels * 2)
        scratch = ByteData(info.maxBlockSize * channels * 4)
        decoder = dec
        parsed = true
        currentSample = 0L
        pendingSkip = 0L
        restartOffset = metadataEndOffset
        indexSize = 0
        addIndex(0L, metadataEndOffset)

        out.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                .build()
        )
    }

    /** seek 后输入已定位在文件中部：用缓存的 STREAMINFO 前缀补齐元数据 */
    private fun reopen(input: ExtractorInput) {
        val prefix = streamInfoPrefix ?: throw IOException("FLAC: reopen before parse")
        baseOffset = input.position
        decoderByteBias = PREFIX_LEN.toLong()
        val dec = FLACDecoder(
            SequenceInputStream(ByteArrayInputStream(prefix), RecordingInput(input))
        )
        dec.readMetadata()
        decoder = dec
    }

    private fun seekInternal(targetSample: Long) {
        val best = indexOf(targetSample)
        restartOffset = indexOffsets[best]
        currentSample = indexSamples[best]
        pendingSkip = (targetSample - currentSample).coerceAtLeast(0L)
        decoder = null
    }

    private fun sampleAt(timeUs: Long): Long =
        (timeUs.coerceAtLeast(0L) * sampleRate / 1_000_000L)

    /** 把解码后的各声道 int 采样交织成 16-bit 小端 PCM，[drop] 用于 seek 落帧内偏移 */
    private fun interleave16(
        data: Array<org.jflac.ChannelData>,
        drop: Int,
        block: Int
    ): Int {
        val shift = bitsPerSample - 16
        var p = 0
        for (i in drop until block) {
            for (ch in 0 until channels) {
                var s = data[ch].output[i]
                when {
                    shift > 0 -> s = s shr shift
                    shift < 0 -> s = s shl -shift
                }
                if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                pcm[p++] = (s and 0xff).toByte()
                pcm[p++] = ((s shr 8) and 0xff).toByte()
            }
        }
        return p
    }

    private fun addIndex(sample: Long, offset: Long) {
        if (indexSize == indexSamples.size) {
            indexSamples = indexSamples.copyOf(indexSize * 2)
            indexOffsets = indexOffsets.copyOf(indexSize * 2)
        }
        indexSamples[indexSize] = sample
        indexOffsets[indexSize] = offset
        indexSize++
    }

    /** 最大的「采样序号 <= target」索引项 */
    private fun indexOf(target: Long): Int {
        var lo = 0
        var hi = indexSize - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (indexSamples[mid] <= target) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    private fun indexOffsetFor(target: Long): Long = indexOffsets[indexOf(target)]

    /**
     * 包一层 InputStream 供 jflac 读取，并截获「fLaC」标记起的 42 字节
     * （4 标记 + 4 块头 + 34 STREAMINFO）。按标记扫描而不是固定偏移截获，
     * 对任何前缀残留都稳。
     */
    private class RecordingInput(private val input: ExtractorInput) : InputStream() {
        val capture = ByteArray(PREFIX_LEN)
        var captured = 0
        private var markerMatch = 0
        private val one = ByteArray(1)

        private fun record(b: Int) {
            if (captured >= PREFIX_LEN) return
            if (captured == 0) {
                if (b == (MARKER[markerMatch].toInt() and 0xff)) {
                    markerMatch++
                    if (markerMatch == 4) {
                        System.arraycopy(MARKER, 0, capture, 0, 4)
                        captured = 4
                    }
                } else {
                    markerMatch = if (b == (MARKER[0].toInt() and 0xff)) 1 else 0
                }
            } else {
                capture[captured++] = b.toByte()
            }
        }

        override fun read(): Int {
            val n = input.read(one, 0, 1)
            if (n <= 0) return -1
            val v = one[0].toInt() and 0xff
            record(v)
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = input.read(b, off, len)
            for (i in 0 until n) record(b[off + i].toInt() and 0xff)
            return n
        }
    }

    companion object {
        private const val PREFIX_LEN = 42
        private const val FRAMES_PER_READ = 4
        private val MARKER = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    }
}