package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [Mp4TagWriter] 的重排规则测试。
 *
 * m4a 是手工构造的最小容器：ftyp + moov(mvhd + trak>mdia>minf>stbl>stco) + mdat。
 * 断言核心是 **chunk offset 必须随 moov 尺寸变化同步平移** —— 这条错了
 * 文件「打得开但全是杂音」，是此类工具最致命的失败模式。
 */
class Mp4TagWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val info = HeaderInfo(
        payloadOffset = 0,
        suggestedExt = "m4a",
        isVpr = false,
        format = "mp4",
        metadata = mapOf("title" to "晴天", "artist" to "周杰伦", "album" to "叶惠美"),
        coverImage = null,
        totalSize = 0,
        payloadLength = 0
    )

    // ===== 构造最小 m4a =====

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        val out = ByteArray(size)
        putU32(out, 0, size)
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, 4, 4)
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    private fun putU32(b: ByteArray, pos: Int, v: Int) {
        b[pos] = (v ushr 24).toByte(); b[pos + 1] = (v ushr 16).toByte()
        b[pos + 2] = (v ushr 8).toByte(); b[pos + 3] = v.toByte()
    }

    private fun readU32(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)

    private fun stcoBox(offsets: IntArray): ByteArray {
        val body = ByteArray(8 + offsets.size * 4)
        putU32(body, 0, 0)                  // version/flags
        putU32(body, 4, offsets.size)
        offsets.forEachIndexed { i, off -> putU32(body, 8 + i * 4, off) }
        return box("stco", body)
    }

    private fun moovBox(stco: ByteArray): ByteArray =
        box("moov", box("mvhd", ByteArray(100)) + box("trak", box("mdia", box("minf", box("stbl", stco)))))

    /** moov 在 mdat 之前的常规构造；chunk offset 指向 mdat 内容的真实绝对位置 */
    private fun buildM4a(moovFirst: Boolean): ByteArray {
        val ftyp = box("ftyp", "M4A \u0000\u0000\u0002\u0000mp41".toByteArray(Charsets.ISO_8859_1))
        val mdatBody = ByteArray(64) { it.toByte() }
        val mdat = box("mdat", mdatBody)
        // offset 先占位，拼完顶层后回填 mdat 内容的真实绝对位置
        val stco = stcoBox(intArrayOf(0, 0))
        val moov = moovBox(stco)
        val parts = if (moovFirst) listOf(ftyp, moov, mdat) else listOf(ftyp, mdat, moov)
        val bytes = parts.reduce { a, b -> a + b }
        // mdat 内容起点 = mdat box 起点 + 8 字节头
        val contentStart = if (moovFirst) ftyp.size + moov.size + 8 else ftyp.size + 8
        // 回填两个 chunk offset（在 bytes 里定位 stco 载荷）
        val entryBase = bytes.indexOfSlice("stco".toByteArray(Charsets.ISO_8859_1)) + 4 + 8
        putU32(bytes, entryBase, contentStart)
        putU32(bytes, entryBase + 4, contentStart + 16)
        return bytes
    }

    private fun ByteArray.indexOfSlice(pattern: ByteArray): Int {
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun chunkOffsets(bytes: ByteArray): List<Int> {
        val idx = bytes.indexOfSlice("stco".toByteArray(Charsets.ISO_8859_1))
        val count = readU32(bytes, idx + 8)
        return (0 until count).map { readU32(bytes, idx + 12 + it * 4) }
    }

    // ===== 用例 =====

    @Test
    fun `moov 在前 - 打标签后 stco 同步平移`() {
        val file = tmp.newFile("a.m4a")
        val original = buildM4a(moovFirst = true)
        file.writeBytes(original)
        val before = chunkOffsets(original)

        assertTrue(Mp4TagWriter.tag(file, info, null))

        val tagged = file.readBytes()
        assertTrue(tagged.size > original.size)
        val after = chunkOffsets(tagged)
        val delta = tagged.size - original.size
        assertEquals(before.map { it + delta }, after)
        // ftyp / mdat 内容不受影响
        assertArrayEquals(original.copyOfRange(0, 16), tagged.copyOfRange(0, 16))
        assertArrayEquals(
            original.copyOfRange(original.size - 64, original.size),
            tagged.copyOfRange(tagged.size - 64, tagged.size)
        )
    }

    @Test
    fun `moov 在后 - 打标签后 stco 不动`() {
        val file = tmp.newFile("b.m4a")
        val original = buildM4a(moovFirst = false)
        file.writeBytes(original)
        val before = chunkOffsets(original)

        assertTrue(Mp4TagWriter.tag(file, info, null))

        val tagged = file.readBytes()
        assertEquals(before, chunkOffsets(tagged))
        assertTrue(tagged.size > original.size)
    }

    @Test
    fun `封面字节按 PNG JPEG 正确嵌入`() {
        val file = tmp.newFile("c.m4a")
        file.writeBytes(buildM4a(moovFirst = true))
        // 随便一段非 PNG 字节按 JPEG（13）处理
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 1, 2, 3)
        assertTrue(Mp4TagWriter.tag(file, info, cover))
        val tagged = file.readBytes()
        assertTrue(tagged.indexOfSlice(cover) > 0)
        assertTrue(tagged.indexOfSlice("covr".toByteArray(Charsets.ISO_8859_1)) > 0)
    }

    @Test
    fun `已有旧 meta 的文件剥离重写为新标签`() {
        val file = tmp.newFile("d.m4a")
        val original = buildM4a(moovFirst = true)
        file.writeBytes(original)
        assertTrue(Mp4TagWriter.tag(file, info, null))
        val once = file.readBytes()

        // 二次打标签：旧标签（晴天）必须被剥离，新标签（旧标题）写入
        val stale = info.copy(metadata = mapOf("title" to "旧标题", "artist" to "旧歌手", "album" to "旧专辑"))
        assertTrue(Mp4TagWriter.tag(file, stale, null))
        val twice = file.readBytes()
        assertTrue(twice.indexOfSlice("旧标题".toByteArray(Charsets.UTF_8)) > 0)
        assertEquals(-1, twice.indexOfSlice("晴天".toByteArray(Charsets.UTF_8)))
        // moov 尺寸变化后 stco 仍要同步平移（本次 delta 为负也不许错位）
        assertEquals(
            chunkOffsets(once).map { it + (twice.size - once.size) },
            chunkOffsets(twice)
        )
    }

    @Test
    fun `已有 moov 层 meta 的文件也能重写`() {
        // QuickTime 风格：meta 直接挂在 moov 下（不在 udta 里）
        val file = tmp.newFile("d2.m4a")
        val ftyp = box("ftyp", "M4A \u0000\u0000\u0002\u0000mp41".toByteArray(Charsets.ISO_8859_1))
        val mdatBody = ByteArray(64)
        val stco = stcoBox(intArrayOf(0, 0))
        val metaBody = box("hdlr", ByteArray(24)) + box("ilst", box("\u00A9nam", dataUtf8("垃圾标题")))
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("meta", ByteArray(4) + metaBody) +
            box("trak", box("mdia", box("minf", box("stbl", stco)))))
        val bytes = listOf(ftyp, moov, box("mdat", mdatBody)).reduce { a, b -> a + b }
        val contentStart = ftyp.size + moov.size + 8
        val entryBase = bytes.indexOfSlice("stco".toByteArray(Charsets.ISO_8859_1)) + 4 + 8
        putU32(bytes, entryBase, contentStart)
        putU32(bytes, entryBase + 4, contentStart + 16)
        file.writeBytes(bytes)

        assertTrue(Mp4TagWriter.tag(file, info, null))
        val tagged = file.readBytes()
        assertTrue(tagged.indexOfSlice("晴天".toByteArray(Charsets.UTF_8)) > 0)
        assertEquals(-1, tagged.indexOfSlice("垃圾标题".toByteArray(Charsets.UTF_8)))
    }

    private fun dataUtf8(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val data = ByteArray(16 + payload.size)
        putU32(data, 0, 16 + payload.size)   // data atom 总尺寸
        System.arraycopy("data".toByteArray(Charsets.ISO_8859_1), 0, data, 4, 4)
        putU32(data, 8, 1)                   // type 1 = UTF-8
        System.arraycopy(payload, 0, data, 16, payload.size)
        return box("\u00A9nam", data)
    }

    @Test
    fun `结构不认识的文件返回 false 且不改动`() {
        val file = tmp.newFile("e.m4a")
        val garbage = ByteArray(512) { (it * 37).toByte() }
        file.writeBytes(garbage)
        assertFalse(Mp4TagWriter.tag(file, info, null))
        assertArrayEquals(garbage, file.readBytes())
    }

    @Test
    fun `verify 不过就放弃替换 - 原文件保持原样`() {
        val file = tmp.newFile("f.m4a")
        val original = buildM4a(moovFirst = true)
        file.writeBytes(original)
        assertFalse(Mp4TagWriter.tag(file, info, null) { false })
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `空标题空歌手只写专辑与封面 - 不会写空原子`() {
        val file = tmp.newFile("g.m4a")
        file.writeBytes(buildM4a(moovFirst = true))
        val blankInfo = info.copy(metadata = mapOf("album" to "叶惠美"))
        assertTrue(Mp4TagWriter.tag(file, blankInfo, null))
        val bytes = file.readBytes()
        assertTrue(bytes.indexOfSlice("\u00A9alb".toByteArray(Charsets.ISO_8859_1)) > 0)
        assertEquals(-1, bytes.indexOfSlice("\u00A9nam".toByteArray(Charsets.ISO_8859_1)))
    }
}
