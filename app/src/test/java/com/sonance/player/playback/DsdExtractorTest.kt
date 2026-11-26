package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DsdExtractorTest {

    private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val avail = data.size - pos
            if (avail <= 0) return C.RESULT_END_OF_INPUT
            val n = minOf(avail, length)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            peekPos = pos
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val avail = data.size - pos
            if (avail < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, pos, target, offset, length)
            pos += length
            peekPos = pos
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val n = minOf(data.size - pos, length)
            pos += n
            peekPos = pos
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - pos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            pos += length
            peekPos = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            val avail = data.size - peekPos
            if (avail <= 0) return C.RESULT_END_OF_INPUT
            val n = minOf(avail, length)
            System.arraycopy(data, peekPos, target, offset, n)
            peekPos += n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - peekPos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, peekPos, target, offset, length)
            peekPos += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - peekPos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPos = pos
        }

        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            pos = position.toInt()
            peekPos = pos
            throw e
        }
    }

    private class CapturingOutput : ExtractorOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        var ended = false
        var totalPcmBytes = 0L
        var sampleCalls = 0
        var lastPtsUs = -1L

        private val track = object : TrackOutput {
            override fun format(format: Format) {
                this@CapturingOutput.format = format
            }

            override fun sampleData(
                input: androidx.media3.common.DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int
            ): Int = length

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                totalPcmBytes += length
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?
            ) {
                sampleCalls++
                lastPtsUs = timeUs
            }
        }

        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun le64(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 32) and 0xFF).toByte(),
        ((v ushr 40) and 0xFF).toByte(),
        ((v ushr 48) and 0xFF).toByte(),
        ((v ushr 56) and 0xFF).toByte()
    )

    private fun buildDsf(
        channels: Int = 2,
        dsdSampleRate: Int = 2_822_400,
        blockSizePerCh: Int = 4096,
        declaredSampleCountPerCh: Long = 100L * 8L * 4096L,
        actualDataBytes: Int = channels * blockSizePerCh * 2
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val headerAndFmt = 28 + 52 + 12
        val totalSize = headerAndFmt.toLong() + actualDataBytes.toLong()
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write(le64(28L))
        out.write(le64(totalSize))
        out.write(le64(0L))

        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le64(52L))
        out.write(le32(1))
        out.write(le32(0))
        out.write(le32(2))
        out.write(le32(channels))
        out.write(le32(dsdSampleRate))
        out.write(le32(1))
        out.write(le64(declaredSampleCountPerCh))
        out.write(le32(blockSizePerCh)) // 位于 fmtBody[32..35]
        out.write(le32(0))

        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le64(12L + actualDataBytes.toLong()))
        out.write(ByteArray(actualDataBytes) { 0x55.toByte() })
        return out.toByteArray()
    }

    @Test
    fun `200MB-like DSF uses real 4096 block size from fmt offset 32 and decodes full blocks`() {
        // 200MB stereo DSD64 ~ 800,000,000 samples per ch (high 32 bits == 0 in fmtBody[28..31])
        val dsf = buildDsf(
            channels = 2,
            dsdSampleRate = 2_822_400,
            blockSizePerCh = 4096,
            declaredSampleCountPerCh = 800_000_000L,
            actualDataBytes = 2 * 4096 * 2
        )
        val extractor = DsdExtractor()
        val input = ByteArrayExtractorInput(dsf)
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()

        val output = CapturingOutput()
        extractor.init(output)
        val pos = PositionHolder()
        var steps = 0
        while (steps++ < 20) {
            val res = extractor.read(input, pos)
            if (res == Extractor.RESULT_END_OF_INPUT) break
        }

        val fmt = output.format
        assertNotNull(fmt)
        assertEquals(88_200, fmt!!.sampleRate)
        assertEquals(2, fmt.channelCount)
        assertEquals(C.ENCODING_PCM_24BIT, fmt.pcmEncoding)
        // 2 个 4096 块只发 2 次包；24-bit 立体声每帧 6 字节
        assertEquals(2, output.sampleCalls)
        assertEquals(2L * (4096L / 4L) * 6L, output.totalPcmBytes)

        val sm = output.seekMap
        assertNotNull(sm)
        assertTrue(sm!!.isSeekable)
        assertTrue(sm.durationUs > 0L)
        val midSeek = sm.getSeekPoints(sm.durationUs / 2L)
        assertTrue(midSeek.first.position >= 92L)
    }

    @Test
    fun `truncated trailing DSF block decodes cleanly without ArrayIndexOutOfBoundsException`() {
        // 截断末块：不足一个完整的 2*4096 帧
        val dsf = buildDsf(
            channels = 2,
            dsdSampleRate = 2_822_400,
            blockSizePerCh = 4096,
            declaredSampleCountPerCh = 5000L * 8L,
            actualDataBytes = 2 * 4096 + 1024
        )
        val extractor = DsdExtractor()
        val input = ByteArrayExtractorInput(dsf)
        val output = CapturingOutput()
        extractor.init(output)
        val pos = PositionHolder()
        var steps = 0
        while (steps++ < 20) {
            if (extractor.read(input, pos) == Extractor.RESULT_END_OF_INPUT) break
        }
        assertTrue(output.totalPcmBytes > 0L)
        assertEquals(2, output.sampleCalls)
    }

    @Test
    fun `DSD512 outputs clean 88200Hz 24bit PCM via multi-stage halfband decimation`() {
        val dsf = buildDsf(
            channels = 2,
            dsdSampleRate = 22_579_200,
            blockSizePerCh = 4096,
            declaredSampleCountPerCh = 4096L * 8L,
            actualDataBytes = 2 * 4096
        )
        val extractor = DsdExtractor()
        val input = ByteArrayExtractorInput(dsf)
        val output = CapturingOutput()
        extractor.init(output)
        extractor.read(input, PositionHolder())
        val fmt = output.format!!
        assertEquals(88_200, fmt.sampleRate)
        assertEquals(C.ENCODING_PCM_24BIT, fmt.pcmEncoding)
    }

    @Test
    fun `malformed huge fmt chunk size throws ParserException instead of OOM`() {
        val bad = buildDsf(actualDataBytes = 64)
        // Corrupt fmt chunk size at offset 32..39 -> 2 GB
        val giant = le64(2_000_000_000L)
        System.arraycopy(giant, 0, bad, 32, 8)
        val extractor = DsdExtractor()
        extractor.init(CapturingOutput())
        try {
            extractor.read(ByteArrayExtractorInput(bad), PositionHolder())
            fail("Expected ParserException")
        } catch (_: ParserException) {
            // Expected
        }
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun be64(v: Long) = byteArrayOf(
        ((v ushr 56) and 0xFF).toByte(),
        ((v ushr 48) and 0xFF).toByte(),
        ((v ushr 40) and 0xFF).toByte(),
        ((v ushr 32) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun buildDff(
        channels: Int = 2,
        dsdSampleRate: Int = 2_822_400,
        actualDataBytes: Int = 16 * 1024
    ): ByteArray {
        val propPayload = ByteArrayOutputStream().apply {
            write("SND ".toByteArray(Charsets.US_ASCII))
            write("FS  ".toByteArray(Charsets.US_ASCII))
            write(be64(4L))
            write(be32(dsdSampleRate))
            write("CHNL".toByteArray(Charsets.US_ASCII))
            write(be64(2L))
            write(byteArrayOf(((channels ushr 8) and 0xFF).toByte(), (channels and 0xFF).toByte()))
        }.toByteArray()

        val frmBody = ByteArrayOutputStream().apply {
            write("DSD ".toByteArray(Charsets.US_ASCII))
            write("PROP".toByteArray(Charsets.US_ASCII))
            write(be64(propPayload.size.toLong()))
            write(propPayload)
            write("DSD ".toByteArray(Charsets.US_ASCII))
            write(be64(actualDataBytes.toLong()))
            write(ByteArray(actualDataBytes) { 0x55.toByte() })
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("FRM8".toByteArray(Charsets.US_ASCII))
            write(be64(frmBody.size.toLong()))
            write(frmBody)
        }.toByteArray()
    }

    @Test
    fun `DSF and DFF seekMap getDurationUs and seek work without StackOverflowError`() {
        val dsf = buildDsf(
            channels = 2,
            dsdSampleRate = 2_822_400,
            blockSizePerCh = 4096,
            declaredSampleCountPerCh = 4L * 4096L * 8L,
            actualDataBytes = 4 * 2 * 4096
        )
        runSeekVerify(dsf)

        val dff = buildDff(
            channels = 2,
            dsdSampleRate = 2_822_400,
            actualDataBytes = 16 * 1024
        )
        runSeekVerify(dff)
    }

    private fun runSeekVerify(data: ByteArray) {
        val extractor = DsdExtractor()
        val input = ByteArrayExtractorInput(data)
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()
        val output = CapturingOutput()
        extractor.init(output)
        val pos = PositionHolder()
        var steps = 0
        while (output.seekMap == null && steps++ < 10) {
            if (extractor.read(input, pos) == Extractor.RESULT_SEEK) {
                input.setRetryPosition(pos.position, RuntimeException()) // won't trigger in valid files; handled cleanly
            }
        }
        val seekMap = output.seekMap
        assertNotNull(seekMap)
        assertTrue(seekMap!!.isSeekable)
        val dur = seekMap.durationUs
        assertTrue("Expected positive durationUs, got $dur", dur > 0L)
        val sp = seekMap.getSeekPoints(dur / 2L).first
        assertTrue(sp.position > 0L)

        extractor.seek(sp.position, sp.timeUs)
        val seekInput = ByteArrayExtractorInput(data)
        seekInput.skipFully(sp.position.toInt())
        val prevBytes = output.totalPcmBytes
        val res = extractor.read(seekInput, pos)
        assertTrue(res == Extractor.RESULT_CONTINUE || output.totalPcmBytes > prevBytes)
        assertTrue(output.totalPcmBytes > prevBytes)
    }

    /**
     * 稳定 4 阶 Delta-Sigma 调制器（CIFB 拓扑），
     * 用于在 JVM 单测中合成真实带有强烈超声量化噪声（60 kHz~1.4 MHz）的 1-bit DSD 流。
     */
    private fun encodeDsd4thOrder(
        totalBytes: Int,
        dsdRateHz: Int,
        lsbFirst: Boolean = true,
        signalFn: (sampleIdx: Long, dsdRateHz: Int) -> Double
    ): ByteArray {
        val out = ByteArray(totalBytes)
        val a0 = 0.0085
        val a1 = 0.072
        val a2 = 0.286
        val a3 = 0.75
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var seed = 0x1234567
        var sampleIdx = 0L
        for (byteIdx in 0 until totalBytes) {
            var byteVal = 0
            for (bit in 0 until 8) {
                seed = seed * 1664525 + 1013904223
                val dither = ((seed ushr 16) / 65536.0 - 0.5) * 1e-4
                val x = signalFn(sampleIdx++, dsdRateHz) + dither
                val y = if (s3 >= 0.0) 1.0 else -1.0
                val err = x - y
                val prev0 = s0
                val prev1 = s1
                val prev2 = s2
                s0 = (s0 + a0 * err).coerceIn(-8.0, 8.0)
                s1 = (s1 + prev0 + a1 * err).coerceIn(-8.0, 8.0)
                s2 = (s2 + prev1 + a2 * err).coerceIn(-8.0, 8.0)
                s3 = (s3 + prev2 + a3 * err).coerceIn(-8.0, 8.0)
                if (y > 0.0) {
                    val bitPos = if (lsbFirst) bit else 7 - bit
                    byteVal = byteVal or (1 shl bitPos)
                }
            }
            out[byteIdx] = byteVal.toByte()
        }
        return out
    }

    @Test
    fun `delta-sigma DSD64 silence decodes with noise floor well below -90 dBFS`() {
        val pcmSamples = 1600
        val stride = 4
        val dsdBytes = encodeDsd4thOrder(pcmSamples * stride, 2_822_400, lsbFirst = true) { _, _ -> 0.0 }
        val dec = DsdExtractor.DsdChannelDecimator(DsdExtractor.STAGE1_LUT_LSB, halfbandStages = 0)
        val pcm = DoubleArray(pcmSamples) { f ->
            dec.decimateFrame(dsdBytes, f * stride, stepInSrc = 1).toDouble()
        }
        val steady = pcm.copyOfRange(150, pcmSamples)
        val mean = steady.average()
        val rms = kotlin.math.sqrt(steady.sumOf { (it - mean) * (it - mean) } / steady.size)
        val dbFs = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-12))
        assertTrue("DSD64 silence noise floor too high: $dbFs dBFS (rms=$rms)", dbFs < -90.0)
    }

    @Test
    fun `DSD64 1kHz and 18kHz sine waves preserve high SNR and flat passband`() {
        val pcmSamples = 1800
        val stride = 4
        val outRate = 88_200.0
        val dsd1k = encodeDsd4thOrder(pcmSamples * stride, 2_822_400, lsbFirst = true) { n, sr ->
            0.25 * kotlin.math.sin(2.0 * kotlin.math.PI * 1_000.0 * n / sr)
        }
        val dsd18k = encodeDsd4thOrder(pcmSamples * stride, 2_822_400, lsbFirst = true) { n, sr ->
            0.25 * kotlin.math.sin(2.0 * kotlin.math.PI * 18_000.0 * n / sr)
        }
        val dec1k = DsdExtractor.DsdChannelDecimator(DsdExtractor.STAGE1_LUT_LSB, halfbandStages = 0)
        val dec18k = DsdExtractor.DsdChannelDecimator(DsdExtractor.STAGE1_LUT_LSB, halfbandStages = 0)
        val pcm1k = DoubleArray(pcmSamples) { f -> dec1k.decimateFrame(dsd1k, f * stride, 1).toDouble() }
        val pcm18k = DoubleArray(pcmSamples) { f -> dec18k.decimateFrame(dsd18k, f * stride, 1).toDouble() }

        // 1323 samples = 15.0 exact cycles of 1 kHz / 18 kHz @ 88,200 Hz (zero rectangular-window leakage)
        val winStart = 200
        val winLen = 1323
        val amp1k = measureToneAmplitude(pcm1k, outRate, 1_000.0, winStart, winLen)
        val amp18k = measureToneAmplitude(pcm18k, outRate, 18_000.0, winStart, winLen)
        assertTrue("1 kHz amplitude unexpected: $amp1k", amp1k in 0.22..0.28)
        val diffDb = 20.0 * kotlin.math.log10(amp18k / amp1k)
        assertTrue("18 kHz roll-off too large: $diffDb dB", kotlin.math.abs(diffDb) < 0.6)

        val snrDb = measureToneSnrDb(pcm1k, outRate, 1_000.0, winStart, winLen)
        assertTrue("Expected DSD64 1kHz SNR > 80 dB, got $snrDb dB", snrDb > 80.0)
    }

    @Test
    fun `DSD128 and DSD256 halfband stages decode silence below -90 dBFS`() {
        for (hb in listOf(1, 2)) {
            val pcmSamples = 1000
            val stride = 4 shl hb
            val dsdRate = 2_822_400 shl hb
            val dsdBytes = encodeDsd4thOrder(pcmSamples * stride, dsdRate, lsbFirst = false) { _, _ -> 0.0 }
            val dec = DsdExtractor.DsdChannelDecimator(DsdExtractor.STAGE1_LUT_MSB, halfbandStages = hb)
            val pcm = DoubleArray(pcmSamples) { f -> dec.decimateFrame(dsdBytes, f * stride, 1).toDouble() }
            val steady = pcm.copyOfRange(150, pcmSamples)
            val rms = kotlin.math.sqrt(steady.sumOf { it * it } / steady.size)
            val dbFs = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-12))
            assertTrue("DSD (hb=$hb) noise floor too high: $dbFs dBFS", dbFs < -90.0)
        }
    }

    private fun measureToneAmplitude(samples: DoubleArray, sr: Double, hz: Double, start: Int, count: Int): Double {
        var s = 0.0
        var c = 0.0
        val end = start + count
        for (i in start until end) {
            val ph = 2.0 * kotlin.math.PI * hz * i / sr
            s += samples[i] * kotlin.math.sin(ph)
            c += samples[i] * kotlin.math.cos(ph)
        }
        return 2.0 * kotlin.math.sqrt(s * s + c * c) / count
    }

    private fun measureToneSnrDb(samples: DoubleArray, sr: Double, hz: Double, start: Int, count: Int): Double {
        var sinSum = 0.0
        var cosSum = 0.0
        val end = start + count
        for (i in start until end) {
            val ph = 2.0 * kotlin.math.PI * hz * i / sr
            sinSum += samples[i] * kotlin.math.sin(ph)
            cosSum += samples[i] * kotlin.math.cos(ph)
        }
        val aSin = 2.0 * sinSum / count
        val aCos = 2.0 * cosSum / count
        var sigSq = 0.0
        var noiseSq = 0.0
        for (i in start until end) {
            val ph = 2.0 * kotlin.math.PI * hz * i / sr
            val sig = aSin * kotlin.math.sin(ph) + aCos * kotlin.math.cos(ph)
            val err = samples[i] - sig
            sigSq += sig * sig
            noiseSq += err * err
        }
        return 10.0 * kotlin.math.log10(sigSq / noiseSq.coerceAtLeast(1e-24))
    }
}
