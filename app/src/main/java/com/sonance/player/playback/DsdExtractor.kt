package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.PI
import kotlin.math.cos

/**
 * 纯 Kotlin 实现的 DSD 无损音频（`.dsf` / `.dff`）Media3 解复用与 64-tap FIR LUT 降采样提取器。
 *
 * 将 1-bit DSD64 (2.8224 MHz) / DSD128 (5.6448 MHz) / DSD256 (11.2896 MHz) 实时降采样为
 * Hi-Res 标准线性 PCM（88.2 kHz / 176.4 kHz · 16-bit LE），以 `audio/raw` 喂给系统与 USB DAC 音频链路，
 * 支持精确拖动进度（SeekMap）。
 */
@androidx.media3.common.util.UnstableApi
internal class DsdExtractor : Extractor {

    private enum class ContainerKind { NONE, DSF, DFF }

    private var kind = ContainerKind.NONE
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var channelCount = 2
    private var dsdSampleRate = 2_822_400
    private var pcmSampleRate = 88_200
    private var strideBytes = 4 // 4 bytes = 32 bits (DSD64/128), 8 bytes = 64 bits (DSD256)
    private var lsbFirst = true
    private var dsfBlockSize = 4096

    private var dataStartOffset = 0L
    private var dataByteLength = 0L
    private var sampleCountPerChannel = 0L
    private var durationUs = C.TIME_UNSET
    private var currentDataPos = 0L
    private var currentPcmSampleIndex = 0L

    // Sliding 8-byte window per channel for 64-tap symmetric Blackman-Harris FIR LUT
    private var channelHistory = Array(2) { ByteArray(8) }
    private var activeLut = LUT_MSB
    private var readScratch = ByteArray(16 * 1024)
    private var pcmScratch = ByteArray(32 * 1024)
    private val pcmByteArray = ParsableByteArray()

    // Leftover bytes when reading DFF interleaved chunks
    private var dffRemainder = ByteArray(0)
    private var dsfBlockBuf = ByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val scratch = ByteArray(16)
        if (!input.peekFully(scratch, 0, 16, /* allowEndOfInput= */ true)) return false
        return (scratch[0] == 'D'.code.toByte() && scratch[1] == 'S'.code.toByte() &&
            scratch[2] == 'D'.code.toByte() && scratch[3] == ' '.code.toByte()) ||
            (scratch[0] == 'F'.code.toByte() && scratch[1] == 'R'.code.toByte() &&
                scratch[2] == 'M'.code.toByte() && scratch[3] == '8'.code.toByte() &&
                scratch[12] == 'D'.code.toByte() && scratch[13] == 'S'.code.toByte() &&
                scratch[14] == 'D'.code.toByte() && scratch[15] == ' '.code.toByte())
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            parseHeader(input)
            headerParsed = true
            if (input.position != dataStartOffset) {
                seekPosition.position = dataStartOffset
                currentDataPos = 0L
                return Extractor.RESULT_SEEK
            }
        }

        val remaining = (dataByteLength - currentDataPos).coerceAtLeast(0L)
        if (remaining <= 0L) return Extractor.RESULT_END_OF_INPUT

        return when (kind) {
            ContainerKind.DSF -> readDsfBlock(input, remaining)
            ContainerKind.DFF -> readDffChunk(input, remaining)
            ContainerKind.NONE -> Extractor.RESULT_END_OF_INPUT
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        for (hist in channelHistory) hist.fill(SILENCE_BYTE)
        dffRemainder = ByteArray(0)
        if (!headerParsed) {
            currentDataPos = 0L
            currentPcmSampleIndex = 0L
            return
        }
        if (position <= dataStartOffset) {
            currentDataPos = 0L
            currentPcmSampleIndex = 0L
        } else {
            val offsetInData = (position - dataStartOffset).coerceIn(0L, dataByteLength)
            currentDataPos = offsetInData
            val byteSamplesPerCh = when (kind) {
                ContainerKind.DSF -> {
                    val frameBytes = channelCount.toLong() * dsfBlockSize.toLong()
                    val blocks = if (frameBytes > 0L) offsetInData / frameBytes else 0L
                    blocks * dsfBlockSize.toLong()
                }
                ContainerKind.DFF -> offsetInData / channelCount.coerceAtLeast(1).toLong()
                ContainerKind.NONE -> 0L
            }
            currentPcmSampleIndex = byteSamplesPerCh / strideBytes.coerceAtLeast(1)
        }
    }

    override fun release() {
        readScratch = ByteArray(0)
        pcmScratch = ByteArray(0)
        dsfBlockBuf = ByteArray(0)
        dffRemainder = ByteArray(0)
    }

    private fun parseHeader(input: ExtractorInput) {
        val header = ByteArray(28)
        input.readFully(header, 0, 16)
        when {
            header[0] == 'D'.code.toByte() && header[1] == 'S'.code.toByte() &&
                header[2] == 'D'.code.toByte() && header[3] == ' '.code.toByte() -> {
                kind = ContainerKind.DSF
                input.readFully(header, 16, 12) // finish 28-byte DSD chunk
                val dsdChunkSize = readLeLong(header, 4)
                if (dsdChunkSize > 28L) {
                    input.skipFully((dsdChunkSize - 28L).toInt())
                }
                val fmtHead = ByteArray(12)
                input.readFully(fmtHead, 0, 12)
                if (fmtHead[0] != 'f'.code.toByte() || fmtHead[1] != 'm'.code.toByte() ||
                    fmtHead[2] != 't'.code.toByte() || fmtHead[3] != ' '.code.toByte()
                ) {
                    throw ParserException.createForMalformedContainer("Invalid DSF fmt chunk", null)
                }
                val fmtSize = readLeLong(fmtHead, 4).toInt().coerceAtLeast(52)
                val fmtBody = ByteArray((fmtSize - 12).coerceAtLeast(40))
                input.readFully(fmtBody, 0, fmtBody.size)
                // Offset relative to fmt chunk start - 12:
                // 12..15 formatVersion, 16..19 formatId, 20..23 channelType
                channelCount = readLeInt(fmtBody, 12).coerceIn(1, 8)
                dsdSampleRate = readLeInt(fmtBody, 16).coerceAtLeast(44_100)
                val bitsPerSample = readLeInt(fmtBody, 20)
                lsbFirst = bitsPerSample == 1
                sampleCountPerChannel = readLeLong(fmtBody, 24).coerceAtLeast(0L)
                dsfBlockSize = readLeInt(fmtBody, 28).coerceAtLeast(64)

                val dataHead = ByteArray(12)
                input.readFully(dataHead, 0, 12)
                if (dataHead[0] != 'd'.code.toByte() || dataHead[1] != 'a'.code.toByte() ||
                    dataHead[2] != 't'.code.toByte() || dataHead[3] != 'a'.code.toByte()
                ) {
                    throw ParserException.createForMalformedContainer("Invalid DSF data chunk", null)
                }
                val rawDataChunkSize = readLeLong(dataHead, 4)
                dataStartOffset = input.position
                val reportedPayload = (rawDataChunkSize - 12L).coerceAtLeast(0L)
                dataByteLength = if (input.length != C.LENGTH_UNSET.toLong()) {
                    minOf(reportedPayload, (input.length - dataStartOffset).coerceAtLeast(0L))
                } else {
                    reportedPayload
                }
                val validSampleBytesPerCh = (sampleCountPerChannel + 7L) / 8L
                val maxPlayablePayload = validSampleBytesPerCh * channelCount
                if (maxPlayablePayload in 1L until dataByteLength && dsfBlockSize > 0) {
                    // Keep full DSF blocks until covering sampleCountPerChannel
                    val frameSize = channelCount.toLong() * dsfBlockSize
                    val neededBlocks = (validSampleBytesPerCh + dsfBlockSize - 1L) / dsfBlockSize
                    dataByteLength = minOf(dataByteLength, neededBlocks * frameSize)
                }
            }

            header[0] == 'F'.code.toByte() && header[1] == 'R'.code.toByte() &&
                header[2] == 'M'.code.toByte() && header[3] == '8'.code.toByte() -> {
                kind = ContainerKind.DFF
                lsbFirst = false // DSDIFF uses MSB-first
                val frmSize = readBeLong(header, 4)
                val fileEnd = if (frmSize > 4L) 12L + frmSize else Long.MAX_VALUE
                var foundData = false
                val chunkHead = ByteArray(12)
                while (input.position + 12L <= fileEnd) {
                    if (!input.readFully(chunkHead, 0, 12, /* allowEndOfInput= */ true)) break
                    val id = asciiTag(chunkHead, 0)
                    val chunkSize = readBeLong(chunkHead, 4).coerceAtLeast(0L)
                    when (id) {
                        "PROP" -> {
                            val propBody = ByteArray(chunkSize.toInt().coerceAtMost(64 * 1024))
                            input.readFully(propBody, 0, propBody.size)
                            if (chunkSize > propBody.size) {
                                input.skipFully((chunkSize - propBody.size).toInt())
                            }
                            parseDffProp(propBody)
                        }
                        "DSD " -> {
                            dataStartOffset = input.position
                            dataByteLength = if (input.length != C.LENGTH_UNSET.toLong()) {
                                minOf(chunkSize, (input.length - dataStartOffset).coerceAtLeast(0L))
                            } else {
                                chunkSize
                            }
                            sampleCountPerChannel = (dataByteLength / channelCount.coerceAtLeast(1)) * 8L
                            foundData = true
                            break
                        }
                        "DST " -> throw ParserException.createForMalformedContainer(
                            "Compressed DST in DFF is unsupported",
                            null
                        )
                        else -> {
                            val skip = chunkSize + (chunkSize and 1L)
                            skipLarge(input, skip)
                        }
                    }
                }
                if (!foundData) {
                    throw ParserException.createForMalformedContainer("DFF DSD data chunk not found", null)
                }
            }

            else -> throw ParserException.createForMalformedContainer("Not a DSD container", null)
        }

        // Choose Hi-Res PCM sample rate divisible into integer byte stride (4 or 8 DSD bytes)
        strideBytes = when {
            dsdSampleRate >= 10_000_000 -> 8 // DSD256 -> 176400 Hz
            else -> 4 // DSD64 -> 88200 Hz, DSD128 -> 176400 Hz
        }
        pcmSampleRate = (dsdSampleRate / (strideBytes * 8)).coerceAtLeast(44_100)
        durationUs = if (sampleCountPerChannel > 0L) {
            sampleCountPerChannel * 1_000_000L / dsdSampleRate
        } else {
            val bytesPerCh = dataByteLength / channelCount.coerceAtLeast(1)
            (bytesPerCh * 8L * 1_000_000L) / dsdSampleRate
        }

        channelHistory = Array(channelCount) { ByteArray(8) { SILENCE_BYTE } }
        activeLut = if (lsbFirst) LUT_LSB else LUT_MSB
        dsfBlockBuf = if (kind == ContainerKind.DSF) ByteArray(channelCount * dsfBlockSize) else ByteArray(0)

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channelCount)
                .setSampleRate(pcmSampleRate)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setPeakBitrate(pcmSampleRate * channelCount * 16)
                .setAverageBitrate(pcmSampleRate * channelCount * 16)
                .build()
        )
        output?.seekMap(createSeekMap())
        output?.endTracks()
    }

    private fun parseDffProp(propBody: ByteArray) {
        // Starts with 4-byte property type (e.g., "SND "), followed by sub-chunks (4-byte ID + 8-byte BE size)
        var pos = 4
        while (pos + 12 <= propBody.size) {
            val subId = asciiTag(propBody, pos)
            val subSize = readBeLong(propBody, pos + 4).toInt().coerceAtLeast(0)
            val dataPos = pos + 12
            if (dataPos + subSize > propBody.size) break
            when (subId) {
                "FS  " -> if (subSize >= 4) {
                    dsdSampleRate = readBeInt(propBody, dataPos).coerceAtLeast(44_100)
                }
                "CHNL" -> if (subSize >= 2) {
                    channelCount = (((propBody[dataPos].toInt() and 0xFF) shl 8) or
                        (propBody[dataPos + 1].toInt() and 0xFF)).coerceIn(1, 8)
                }
            }
            pos = dataPos + subSize + (subSize and 1)
        }
    }

    private fun readDsfBlock(input: ExtractorInput, remaining: Long): Int {
        val frameSize = channelCount * dsfBlockSize
        if (frameSize <= 0) return Extractor.RESULT_END_OF_INPUT
        val toRead = minOf(frameSize.toLong(), remaining).toInt()
        if (toRead < channelCount * strideBytes) return Extractor.RESULT_END_OF_INPUT
        if (!input.readFully(dsfBlockBuf, 0, toRead, /* allowEndOfInput= */ true)) {
            return Extractor.RESULT_END_OF_INPUT
        }
        currentDataPos += toRead

        val blockBytesPerCh = toRead / channelCount
        // Clamp final block valid bytes based on sampleCountPerChannel
        val consumedBytesPerChBefore = ((currentDataPos - toRead) / frameSize) * dsfBlockSize
        val totalValidBytesPerCh = (sampleCountPerChannel + 7L) / 8L
        val validBytesPerCh = if (totalValidBytesPerCh > 0L) {
            minOf(blockBytesPerCh.toLong(), (totalValidBytesPerCh - consumedBytesPerChBefore).coerceAtLeast(0L)).toInt()
        } else {
            blockBytesPerCh
        }

        val outFrames = validBytesPerCh / strideBytes
        if (outFrames <= 0) return Extractor.RESULT_END_OF_INPUT

        val pcmBytesNeeded = outFrames * channelCount * 2
        if (pcmScratch.size < pcmBytesNeeded) pcmScratch = ByteArray(pcmBytesNeeded)

        val lut = activeLut
        val step = strideBytes
        val bytesPerFrameOut = channelCount * 2
        for (ch in 0 until channelCount) {
            val chBase = ch * dsfBlockSize
            val hist = channelHistory[ch]
            var hPos = 0
            for (f in 0 until outFrames) {
                val srcBase = chBase + f * step
                // Push `step` new bytes into 8-byte ring history
                for (k in 0 until step) {
                    hist[(hPos + k) and 7] = dsfBlockBuf[srcBase + k]
                }
                hPos = (hPos + step) and 7
                var acc = 0
                for (tap in 0 until 8) {
                    val b = hist[(hPos + tap) and 7].toInt() and 0xFF
                    acc += lut[tap][b]
                }
                val sample16 = acc.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val dstIdx = f * bytesPerFrameOut + ch * 2
                pcmScratch[dstIdx] = (sample16 and 0xFF).toByte()
                pcmScratch[dstIdx + 1] = ((sample16 ushr 8) and 0xFF).toByte()
            }
            // Re-align history array so index 0 is oldest for next block
            if (hPos != 0) {
                val rotated = ByteArray(8)
                for (tap in 0 until 8) rotated[tap] = hist[(hPos + tap) and 7]
                channelHistory[ch] = rotated
            }
        }

        emitPcm(pcmBytesNeeded, outFrames)
        return Extractor.RESULT_CONTINUE
    }

    private fun readDffChunk(input: ExtractorInput, remaining: Long): Int {
        val groupBytes = channelCount * strideBytes
        val targetRead = minOf(readScratch.size.toLong(), remaining).toInt()
        val carry = dffRemainder.size
        if (readScratch.size < carry + targetRead) {
            readScratch = readScratch.copyOf(carry + targetRead)
        }
        if (carry > 0) {
            System.arraycopy(dffRemainder, 0, readScratch, 0, carry)
        }
        val n = input.read(readScratch, carry, targetRead)
        if (n == C.RESULT_END_OF_INPUT || n <= 0) {
            return if (carry >= groupBytes) {
                processDffBuffer(carry, groupBytes)
            } else {
                Extractor.RESULT_END_OF_INPUT
            }
        }
        currentDataPos += n
        return processDffBuffer(carry + n, groupBytes)
    }

    private fun processDffBuffer(totalBytes: Int, groupBytes: Int): Int {
        val outFrames = totalBytes / groupBytes
        val usedBytes = outFrames * groupBytes
        val rem = totalBytes - usedBytes
        dffRemainder = if (rem > 0) readScratch.copyOfRange(usedBytes, totalBytes) else ByteArray(0)
        if (outFrames <= 0) return Extractor.RESULT_CONTINUE

        val pcmBytesNeeded = outFrames * channelCount * 2
        if (pcmScratch.size < pcmBytesNeeded) pcmScratch = ByteArray(pcmBytesNeeded)

        val lut = activeLut
        val step = strideBytes
        val chCount = channelCount
        for (f in 0 until outFrames) {
            val base = f * groupBytes
            for (ch in 0 until chCount) {
                val hist = channelHistory[ch]
                // Shift old history left by `step` and append `step` interleaved bytes for this channel
                if (step < 8) {
                    System.arraycopy(hist, step, hist, 0, 8 - step)
                }
                for (k in 0 until step) {
                    hist[8 - step + k] = readScratch[base + k * chCount + ch]
                }
                var acc = 0
                for (tap in 0 until 8) {
                    acc += lut[tap][hist[tap].toInt() and 0xFF]
                }
                val sample16 = acc.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val dstIdx = f * chCount * 2 + ch * 2
                pcmScratch[dstIdx] = (sample16 and 0xFF).toByte()
                pcmScratch[dstIdx + 1] = ((sample16 ushr 8) and 0xFF).toByte()
            }
        }

        emitPcm(pcmBytesNeeded, outFrames)
        return Extractor.RESULT_CONTINUE
    }

    private fun emitPcm(pcmBytes: Int, outFrames: Int) {
        val out = trackOutput ?: return
        val ptsUs = (currentPcmSampleIndex * 1_000_000L) / pcmSampleRate.coerceAtLeast(1)
        currentPcmSampleIndex += outFrames
        pcmByteArray.reset(pcmScratch, pcmBytes)
        out.sampleData(pcmByteArray, pcmBytes)
        out.sampleMetadata(ptsUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes, 0, null)
    }

    private fun createSeekMap(): SeekMap = object : SeekMap {
        override fun isSeekable(): Boolean = durationUs > 0L && dataByteLength > 0L
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (durationUs <= 0L || dataByteLength <= 0L) {
                return SeekMap.SeekPoints(SeekPoint.START)
            }
            val clampedTimeUs = timeUs.coerceIn(0L, durationUs)
            val bytePosInData = when (kind) {
                ContainerKind.DSF -> {
                    val frameBytes = channelCount.toLong() * dsfBlockSize.toLong()
                    val samplesPerBlock = dsfBlockSize.toLong() * 8L
                    val blockIdx = ((clampedTimeUs * dsdSampleRate) / 1_000_000L) / samplesPerBlock.coerceAtLeast(1L)
                    (blockIdx * frameBytes).coerceIn(0L, dataByteLength)
                }
                ContainerKind.DFF -> {
                    val frameIdx = ((clampedTimeUs * dsdSampleRate) / 1_000_000L) / 8L
                    (frameIdx * channelCount).coerceIn(0L, dataByteLength)
                }
                ContainerKind.NONE -> 0L
            }
            val exactTimeUs = (clampedTimeUs).coerceIn(0L, durationUs)
            return SeekMap.SeekPoints(SeekPoint(exactTimeUs, dataStartOffset + bytePosInData))
        }
    }

    private fun skipLarge(input: ExtractorInput, bytes: Long) {
        var left = bytes
        while (left > 0L) {
            val step = minOf(left, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            left -= step
        }
    }

    companion object {
        // Alternating 01010101 (0x55/0xAA) represents zero-DC DSD silence
        private const val SILENCE_BYTE: Byte = 0x55

        private val LUT_LSB: Array<IntArray> by lazy { buildLut(lsb = true) }
        private val LUT_MSB: Array<IntArray> by lazy { buildLut(lsb = false) }

        /**
         * Builds an 8-byte (64-tap) Blackman-Harris windowed decimation lookup table.
         * Compensates SACD 50% modulation depth so 0 dBFS SACD plays cleanly at ~-1 dBFS PCM.
         */
        private fun buildLut(lsb: Boolean): Array<IntArray> {
            val n = 64
            val weights = DoubleArray(n)
            var sum = 0.0
            for (k in 0 until n) {
                val a = 2.0 * PI * k / (n - 1)
                val w = 0.35875 - 0.48829 * cos(a) + 0.14128 * cos(2.0 * a) - 0.01168 * cos(3.0 * a)
                weights[k] = w
                sum += w
            }
            // SACD 0 dBFS peaks at ~0.5 modulation index -> scale * 1.78 maps it cleanly into 16-bit PCM
            val scale = (Short.MAX_VALUE * 1.78) / sum
            return Array(8) { byteIdx ->
                IntArray(256) { v ->
                    var acc = 0.0
                    for (bit in 0 until 8) {
                        val tapIdx = byteIdx * 8 + bit
                        val bitPos = if (lsb) bit else 7 - bit
                        val polarity = if (((v ushr bitPos) and 1) == 1) 1.0 else -1.0
                        acc += polarity * weights[tapIdx] * scale
                    }
                    acc.toInt()
                }
            }
        }

        private fun asciiTag(b: ByteArray, off: Int): String =
            String(byteArrayOf(b[off], b[off + 1], b[off + 2], b[off + 3]), Charsets.US_ASCII)

        private fun readLeInt(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun readLeLong(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFFL) or
                ((b[off + 1].toLong() and 0xFFL) shl 8) or
                ((b[off + 2].toLong() and 0xFFL) shl 16) or
                ((b[off + 3].toLong() and 0xFFL) shl 24) or
                ((b[off + 4].toLong() and 0xFFL) shl 32) or
                ((b[off + 5].toLong() and 0xFFL) shl 40) or
                ((b[off + 6].toLong() and 0xFFL) shl 48) or
                ((b[off + 7].toLong() and 0xFFL) shl 56)

        private fun readBeInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        private fun readBeLong(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFFL) shl 56) or
                ((b[off + 1].toLong() and 0xFFL) shl 48) or
                ((b[off + 2].toLong() and 0xFFL) shl 40) or
                ((b[off + 3].toLong() and 0xFFL) shl 32) or
                ((b[off + 4].toLong() and 0xFFL) shl 24) or
                ((b[off + 5].toLong() and 0xFFL) shl 16) or
                ((b[off + 6].toLong() and 0xFFL) shl 8) or
                (b[off + 7].toLong() and 0xFFL)
    }
}
