package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class MultiFormatTagEditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dummyAudio = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0x11, 0x22)
    private val sampleCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `editFlac writes title artist album and preserves picture lyrics and audio`() {
        val streamInfo = ByteArray(34) { 0x10 }
        val vorbisOld = MultiFormatTagEditor.updateVorbisComment(null, "OldTitle", "OldArtist", "OldAlbum").let { base ->
            // Inject LYRICS entry using same Vorbis helper
            val lyrics = "LYRICS=[00:01.00]测试歌词".toByteArray(StandardCharsets.UTF_8)
            val b = ByteArrayOutputStream()
            b.write("TestVendor".toByteArray(StandardCharsets.UTF_8).let { v -> le32(v.size) + v })
            b.write(le32(2))
            val t = "TITLE=Old".toByteArray(StandardCharsets.UTF_8)
            b.write(le32(t.size)); b.write(t)
            b.write(le32(lyrics.size)); b.write(lyrics)
            b.toByteArray()
        }
        val picBlock = RestoreTagWriter.flacPictureBlock(sampleCover, "image/jpeg", isLast = true).drop(4).toByteArray()

        val flacBytes = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(StandardCharsets.US_ASCII))
            write(flacBlockHeader(0, streamInfo.size, isLast = false)); write(streamInfo)
            write(flacBlockHeader(4, vorbisOld.size, isLast = false)); write(vorbisOld)
            write(flacBlockHeader(6, picBlock.size, isLast = true)); write(picBlock)
            write(dummyAudio)
        }.toByteArray()

        val src = File(tmp.root, "in.flac").apply { writeBytes(flacBytes) }
        val dst = File(tmp.root, "out.flac")

        assertTrue(MultiFormatTagEditor.editFlac(src, dst, "新歌名", "新歌手", "新专辑"))

        val outBytes = dst.readBytes()
        assertArrayEquals(dummyAudio, outBytes.copyOfRange(outBytes.size - dummyAudio.size, outBytes.size))
        assertArrayEquals(sampleCover, AudioTags.coverArtFrom(ByteArrayInputStream(outBytes)))
        assertEquals("[00:01.00]测试歌词", AudioTags.lyricsFrom(ByteArrayInputStream(outBytes)))

        val strOut = String(outBytes, StandardCharsets.UTF_8)
        assertTrue(strOut.contains("TITLE=新歌名"))
        assertTrue(strOut.contains("ARTIST=新歌手"))
        assertTrue(strOut.contains("ALBUM=新专辑"))
    }

    @Test
    fun `editOgg updates OpusTags and preserves audio page`() {
        val opusHead = "OpusHead".toByteArray(StandardCharsets.US_ASCII) + ByteArray(11)
        val opusComment = "OpusTags".toByteArray(StandardCharsets.US_ASCII) +
            MultiFormatTagEditor.updateVorbisComment(null, "Old", "OldA", "OldAlb")
        val audioPayload = byteArrayOf(0x78, 0x01, 0x02, 0x03)

        val oggBytes = ByteArrayOutputStream().apply {
            write(makeOggPage(headerType = 0x02, granule = 0L, serial = 1234, seq = 0, body = opusHead))
            write(makeOggPage(headerType = 0x00, granule = 0L, serial = 1234, seq = 1, body = opusComment))
            write(makeOggPage(headerType = 0x04, granule = 960L, serial = 1234, seq = 2, body = audioPayload))
        }.toByteArray()

        val src = File(tmp.root, "in.opus").apply { writeBytes(oggBytes) }
        val dst = File(tmp.root, "out.opus")

        assertTrue(MultiFormatTagEditor.editOgg(src, dst, "月半小夜曲", "李克勤", "Fate"))
        val outStr = String(dst.readBytes(), StandardCharsets.UTF_8)
        assertTrue(outStr.contains("OpusTags"))
        assertTrue(outStr.contains("TITLE=月半小夜曲"))
        assertTrue(outStr.contains("ARTIST=李克勤"))
        assertTrue(outStr.contains("ALBUM=Fate"))
        assertTrue(dst.readBytes().size > opusHead.size + audioPayload.size)
    }

    @Test
    fun `editWav writes LIST INFO and id3 chunk preserving fmt and data`() {
        val fmtChunk = chunkLe("fmt ", ByteArray(16) { 1 })
        val dataChunk = chunkLe("data", dummyAudio)
        val riffBody = "WAVE".toByteArray(StandardCharsets.US_ASCII) + fmtChunk + dataChunk
        val wavBytes = "RIFF".toByteArray(StandardCharsets.US_ASCII) + le32(riffBody.size) + riffBody

        val src = File(tmp.root, "in.wav").apply { writeBytes(wavBytes) }
        val dst = File(tmp.root, "out.wav")

        assertTrue(MultiFormatTagEditor.editWav(src, dst, "海阔天空", "Beyond", "乐与怒"))

        val outBytes = dst.readBytes()
        val declaredSize = le32Val(outBytes, 4)
        assertEquals(outBytes.size - 8, declaredSize)
        val utf8 = String(outBytes, StandardCharsets.UTF_8)
        assertTrue(utf8.contains("LIST"))
        assertTrue(utf8.contains("INAM"))
        assertTrue(utf8.contains("id3 "))
        assertTrue(utf8.contains("海阔天空"))
    }

    @Test
    fun `editAiff writes ID3 chunk preserving COMM and SSND`() {
        val commChunk = chunkBe("COMM", ByteArray(18) { 2 })
        val ssndChunk = chunkBe("SSND", dummyAudio)
        val formBody = "AIFF".toByteArray(StandardCharsets.US_ASCII) + commChunk + ssndChunk
        val aiffBytes = "FORM".toByteArray(StandardCharsets.US_ASCII) + be32(formBody.size) + formBody

        val src = File(tmp.root, "in.aiff").apply { writeBytes(aiffBytes) }
        val dst = File(tmp.root, "out.aiff")

        assertTrue(MultiFormatTagEditor.editAiff(src, dst, "晴天", "周杰伦", "叶惠美"))
        val outBytes = dst.readBytes()
        val declaredSize = be32Val(outBytes, 4)
        assertEquals(outBytes.size - 8, declaredSize)
        val str = String(outBytes, StandardCharsets.ISO_8859_1)
        assertTrue(str.contains("ID3 "))
    }

    @Test
    fun `editDsf writes trailing ID3v2 and updates header offsets`() {
        val hdr = ByteArray(28)
        "DSD ".toByteArray(StandardCharsets.US_ASCII).copyInto(hdr, 0)
        putLe64(hdr, 4, 28L)
        putLe64(hdr, 12, 28L + dummyAudio.size)
        putLe64(hdr, 20, 0L)

        val src = File(tmp.root, "in.dsf").apply { writeBytes(hdr + dummyAudio) }
        val dst = File(tmp.root, "out.dsf")

        assertTrue(MultiFormatTagEditor.editDsf(src, dst, "夜曲", "周杰伦", "十一月的萧邦"))
        val outBytes = dst.readBytes()
        val newTotal = le64Val(outBytes, 12)
        val metaOff = le64Val(outBytes, 20)
        assertEquals(outBytes.size.toLong(), newTotal)
        assertEquals((28 + dummyAudio.size).toLong(), metaOff)
        assertNotNull(outBytes.copyOfRange(metaOff.toInt(), outBytes.size).takeIf {
            it[0] == 'I'.code.toByte() && it[1] == 'D'.code.toByte() && it[2] == '3'.code.toByte()
        })
    }

    private fun flacBlockHeader(type: Int, len: Int, isLast: Boolean): ByteArray = byteArrayOf(
        ((if (isLast) 0x80 else 0x00) or (type and 0x7F)).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        (len and 0xFF).toByte()
    )

    private fun makeOggPage(headerType: Int, granule: Long, serial: Int, seq: Int, body: ByteArray): ByteArray {
        val segs = ArrayList<Int>()
        var rem = body.size
        while (rem >= 255) {
            segs += 255
            rem -= 255
        }
        segs += rem
        val out = ByteArray(27 + segs.size + body.size)
        "OggS".toByteArray(StandardCharsets.US_ASCII).copyInto(out, 0)
        out[5] = headerType.toByte()
        putLe64(out, 6, granule)
        putLe32(out, 14, serial)
        putLe32(out, 18, seq)
        out[26] = segs.size.toByte()
        segs.forEachIndexed { idx, s -> out[27 + idx] = s.toByte() }
        body.copyInto(out, 27 + segs.size)
        return out
    }

    private fun chunkLe(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(StandardCharsets.US_ASCII) + le32(payload.size) + payload +
            if ((payload.size and 1) != 0) byteArrayOf(0) else ByteArray(0)

    private fun chunkBe(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(StandardCharsets.US_ASCII) + be32(payload.size) + payload +
            if ((payload.size and 1) != 0) byteArrayOf(0) else ByteArray(0)

    private fun le32(v: Int): ByteArray = ByteArray(4).also { putLe32(it, 0, v) }
    private fun be32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun le32Val(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun be32Val(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte(); b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun le64Val(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun putLe64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v ushr (i * 8)) and 0xFF).toByte()
    }
}
