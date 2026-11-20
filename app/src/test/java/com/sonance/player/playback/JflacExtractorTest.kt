package com.sonance.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * [JflacExtractor] 的端到端验证：手工构造合法 FLAC（STREAMINFO + verbatim 子帧 +
 * 正确 CRC-8/CRC-16），在 JVM 上跑真实 jflac 解码，核对 PCM、时间戳与 seek 行为。
 */
class JflacExtractorTest {

    // ---------- FLAC 构造 ----------

    private class Bits {
        val out = ByteArrayOutputStream()
        private var acc = 0
        private var n = 0
        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1)
                n++
                if (n == 8) { out.write(acc); acc = 0; n = 0 }
            }
        }
        fun writeLong(value: Long, bits: Int) {
            for (i in bits - 1 downTo 0) write(((value ushr i) and 1L).toInt(), 1)
        }
        fun bytes(): ByteArray {
            check(n == 0) { "unaligned" }
            return out.toByteArray()
        }
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }

    private fun streamInfoBlock(sampleRate: Int, channels: Int, bits: Int, totalSamples: Long, last: Boolean): ByteArray {
        val b = Bits()
        b.write(16, 16)          // minBlockSize
        b.write(16, 16)          // maxBlockSize
        b.write(0, 24)           // minFrameSize
        b.write(0, 24)           // maxFrameSize
        b.write(sampleRate, 20)
        b.write(channels - 1, 3)
        b.write(bits - 1, 5)
        b.writeLong(totalSamples, 36)
        repeat(128) { b.write(0, 1) } // md5
        val payload = b.bytes()
        assertEquals(34, payload.size)
        val header = byteArrayOf(
            ((if (last) 0x80 else 0) or 0).toByte(),
            0, 0, 34
        )
        return header + payload
    }

    /** 固定 blockSize=16 的 verbatim 帧；samples[ch][i] 为原始位深下的有符号采样 */
    private fun verbatimFrame(frameNumber: Int, bits: Int, samples: Array<IntArray>): ByteArray {
        val block = samples[0].size
        val channels = samples.size
        require(block == 16)
        val bitsCode = when (bits) { 16 -> 0b000; 24 -> 0b110; else -> error("bits") }
        val header = ByteArrayOutputStream()
        header.write(0xFF); header.write(0xF8)          // sync + fixed-blocksize
        header.write(0x60)                              // blockSize=8bit-at-end, rate=from-streaminfo
        header.write(((channels - 1) shl 4) or (bitsCode shl 1))
        header.write(frameNumber)                       // UTF-8 frame number (<128)
        header.write(block - 1)                         // 8-bit blockSize-1
        val h = header.toByteArray()
        header.write(crc8(h))
        val body = Bits()
        for (ch in 0 until channels) {
            body.write(0b00000010, 8)                   // verbatim subframe, no wasted bits
            for (s in samples[ch]) body.write(s, bits)
        }
        val frameSoFar = header.toByteArray() + body.bytes()
        val crc = crc16(frameSoFar)
        return frameSoFar + byteArrayOf(((crc ushr 8) and 0xff).toByte(), (crc and 0xff).toByte())
    }

    private fun pattern16(i: Int): Pair<Int, Int> {
        val l = ((i * 37) and 0xffff) - 32768
        val r = -((i * 11) and 0x7fff)
        return l to r
    }

    private class Flac(val bytes: ByteArray, val samples: List<Pair<Int, Int>>, val bits: Int, val sampleRate: Int)

    private fun buildFlac(frames: Int, bits: Int = 16, id3: Boolean = false, extraMetadata: Boolean = false): Flac {
        val total = frames * 16
        val out = ByteArrayOutputStream()
        if (id3) {
            // ID3v2.3 头（魔数 "ID3" + 版本 3.0 + flags + synchsafe 长度 20）+ 20 字节假负载
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20))
            out.write(ByteArray(20) { if (it % 3 == 0) 'f'.code.toByte() else 0x55 })
        }
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(streamInfoBlock(44100, 2, bits, total.toLong(), last = !extraMetadata))
        if (extraMetadata) {
            // 一个 VORBIS_COMMENT 空块（vendor 长度 0 + 0 条注释），last=true
            out.write(byteArrayOf(0x84.toByte(), 0, 0, 8))
            out.write(ByteArray(8))
        }
        val samples = ArrayList<Pair<Int, Int>>()
        for (f in 0 until frames) {
            val l = IntArray(16); val r = IntArray(16)
            for (i in 0 until 16) {
                val idx = f * 16 + i
                val (a, b) = pattern16(idx)
                if (bits == 16) { l[i] = a; r[i] = b } else { l[i] = a shl 8; r[i] = b shl 8 }
                samples.add(a to b)
            }
            out.write(verbatimFrame(f, bits, arrayOf(l, r)))
        }
        return Flac(out.toByteArray(), samples, bits, 44100)
    }

    private fun expectedPcm(flac: Flac, fromSample: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in fromSample until flac.samples.size) {
            val (l, r) = flac.samples[i]
            for (s in intArrayOf(l, r)) {
                out.write(s and 0xff)
                out.write((s shr 8) and 0xff)
            }
        }
        return out.toByteArray()
    }

    // ---------- 测试替身 ----------

    private class FakeInput(val data: ByteArray) : ExtractorInput {
        var pos = 0
        var peek = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, target, offset, n)
            pos += n
            return n
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            read(target, offset, length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }
        override fun skip(length: Int): Int {
            val n = minOf(length, data.size - pos)
            pos += n
            return n
        }
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            pos += length
            return true
        }
        override fun skipFully(length: Int) { skipFully(length, false) }
        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peek >= data.size) return -1
            val n = minOf(length, data.size - peek)
            System.arraycopy(data, peek, target, offset, n)
            peek += n
            return n
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peek(target, offset, length)
            return true
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peek += length
            return true
        }
        override fun advancePeekPosition(length: Int) { advancePeekPosition(length, false) }
        override fun resetPeekPosition() { peek = pos }
        override fun getPeekPosition(): Long = peek.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) { throw e }
    }

    private class FakeTrack : TrackOutput {
        var format: Format? = null
        val pcm = ByteArrayOutputStream()
        val times = ArrayList<Long>()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
            val buf = ByteArray(length)
            val n = input.read(buf, 0, length)
            if (n > 0) pcm.write(buf, 0, n)
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
            val buf = ByteArray(length)
            data.readBytes(buf, 0, length)
            pcm.write(buf, 0, length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times.add(timeUs)
        }
    }

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }

    private fun usOf(sample: Int): Long = sample * 1_000_000L / 44100L

    /** 微秒→采样的整数往返可能比原采样少 1，断言以求值后的落点为准 */
    private fun sampleOf(us: Long): Int = (us * 44100L / 1_000_000L).toInt()

    private fun readUntilEnd(ext: JflacExtractor, input: FakeInput): Int {
        val holder = PositionHolder()
        var reads = 0
        while (reads++ < 100000) {
            when (ext.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return reads
                Extractor.RESULT_SEEK -> input.pos = holder.position.toInt()
                else -> {}
            }
        }
        error("read loop did not terminate")
    }

    // ---------- 用例 ----------

    @Test
    fun sniffRecognizesFlacWithAndWithoutId3() {
        val plain = buildFlac(2)
        val tagged = buildFlac(2, id3 = true)
        assertTrue(JflacExtractor().sniff(FakeInput(plain.bytes)))
        assertTrue(JflacExtractor().sniff(FakeInput(tagged.bytes)))
        assertFalse(JflacExtractor().sniff(FakeInput(ByteArray(64) { 0x33 })))
        assertFalse(JflacExtractor().sniff(FakeInput(ByteArray(3))))
        // sniff 不得消费读取位置
        val input = FakeInput(plain.bytes)
        JflacExtractor().sniff(input)
        assertEquals(0, input.pos)
        assertEquals(0L, input.peekPosition)
    }

    @Test
    fun decodesWholeFileToPcm16() {
        val flac = buildFlac(40)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        readUntilEnd(ext, FakeInput(flac.bytes))

        val format = out.track.format!!
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
        assertEquals(44100, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertArrayEquals(expectedPcm(flac, 0), out.track.pcm.toByteArray())
        assertEquals(640L * 1_000_000L / 44100L, out.seekMap!!.durationUs)
        assertTrue(out.seekMap!!.isSeekable)
        assertEquals(0L, out.track.times.first())
        // 时间戳单调递增
        for (i in 1 until out.track.times.size) {
            assertTrue(out.track.times[i] > out.track.times[i - 1])
        }
    }

    @Test
    fun decodesFileWithId3AndExtraMetadata() {
        val flac = buildFlac(10, id3 = true, extraMetadata = true)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        readUntilEnd(ext, FakeInput(flac.bytes))
        assertArrayEquals(expectedPcm(flac, 0), out.track.pcm.toByteArray())
    }

    @Test
    fun downconverts24BitTo16Bit() {
        val flac = buildFlac(6, bits = 24)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        readUntilEnd(ext, FakeInput(flac.bytes))
        assertArrayEquals(expectedPcm(flac, 0), out.track.pcm.toByteArray())
    }

    @Test
    fun seekBackwardLandsExactlyAndReplaysTail() {
        val flac = buildFlac(40)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        val input = FakeInput(flac.bytes)
        readUntilEnd(ext, input)

        // 目标落在第 20 帧中间（采样 325 附近）：seek 后应从整数往返落点精确续播
        val timeUs = usOf(325)
        val landed = sampleOf(timeUs)
        out.track.pcm.reset()
        out.track.times.clear()
        ext.seek(timeUs, timeUs)
        readUntilEnd(ext, input)

        assertEquals(usOf(landed), out.track.times.first())
        assertArrayEquals(expectedPcm(flac, landed), out.track.pcm.toByteArray())
    }

    @Test
    fun seekForwardBeyondIndexedRegionSkipsFrames() {
        val flac = buildFlac(40)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        val input = FakeInput(flac.bytes)
        val holder = PositionHolder()
        repeat(2) { ext.read(input, holder) } // 只建了前几帧的索引

        val timeUs = usOf(480) // 第 30 帧附近
        val landed = sampleOf(timeUs)
        out.track.pcm.reset()
        out.track.times.clear()
        ext.seek(timeUs, timeUs)
        readUntilEnd(ext, input)

        assertEquals(usOf(landed), out.track.times.first())
        assertArrayEquals(expectedPcm(flac, landed), out.track.pcm.toByteArray())
    }

    @Test
    fun seekBeforeFirstReadIsHonored() {
        val flac = buildFlac(20)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        val timeUs = usOf(160)
        val landed = sampleOf(timeUs)
        ext.seek(timeUs, timeUs)
        readUntilEnd(ext, FakeInput(flac.bytes))
        assertEquals(usOf(landed), out.track.times.first())
        assertArrayEquals(expectedPcm(flac, landed), out.track.pcm.toByteArray())
    }

    @Test
    fun seekMapPointsIntoIndexedRegion() {
        val flac = buildFlac(40)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        val input = FakeInput(flac.bytes)
        readUntilEnd(ext, input)

        val timeUs = usOf(325)
        val landed = sampleOf(timeUs)
        val point = out.seekMap!!.getSeekPoints(timeUs).first
        assertTrue(point.position > 0L && point.position < flac.bytes.size)

        // 模拟 ExoPlayer：同一实例按 SeekMap 偏移重开输入，再 seek + read
        out.track.pcm.reset()
        out.track.times.clear()
        input.pos = point.position.toInt()
        ext.seek(timeUs, timeUs)
        readUntilEnd(ext, input)
        assertEquals(usOf(landed), out.track.times.first())
        assertArrayEquals(expectedPcm(flac, landed), out.track.pcm.toByteArray())
    }

    @Test
    fun repeatedSeekStaysConsistent() {
        val flac = buildFlac(40)
        val ext = JflacExtractor()
        val out = FakeOutput()
        ext.init(out)
        val input = FakeInput(flac.bytes)
        readUntilEnd(ext, input)
        for (target in intArrayOf(500, 17, 600, 0, 333)) {
            val timeUs = usOf(target)
            val landed = sampleOf(timeUs)
            out.track.pcm.reset()
            out.track.times.clear()
            ext.seek(timeUs, timeUs)
            readUntilEnd(ext, input)
            assertEquals("target=$target", usOf(landed), out.track.times.first())
            assertArrayEquals("target=$target", expectedPcm(flac, landed), out.track.pcm.toByteArray())
        }
    }
}