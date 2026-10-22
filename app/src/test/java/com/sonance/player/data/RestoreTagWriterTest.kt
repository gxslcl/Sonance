package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RestoreTagWriter] 的二进制结构测试。
 *
 * 这块是整条还原链路里最容易「静默出错」的地方：FLAC 的元数据块是链式的，
 * 插入封面块必须同时改写 STREAMINFO 的 last 标记。改错了不会抛异常，
 * 只会得到一个能写完、但任何播放器都打不开的文件 —— 所以这里按字节解析回来校验，
 * 而不是只断言「长度变大了」。
 */
class RestoreTagWriterTest {

    private val cover = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6
    )

    /** 构造一个只有 STREAMINFO 的最小 FLAC 头：`fLaC` + 块头 + 34 字节 */
    private fun flacHead(streamInfoLast: Boolean): ByteArray {
        val out = ByteArray(4 + 4 + 34 + 8)
        out[0] = 'f'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 'a'.code.toByte()
        out[3] = 'C'.code.toByte()
        out[4] = ((if (streamInfoLast) 0x80 else 0x00) or 0).toByte() // type 0 = STREAMINFO
        out[5] = 0
        out[6] = 0
        out[7] = 34
        for (i in 0 until 34) out[8 + i] = (i + 1).toByte()
        // 后面跟 8 字节「音频帧」占位，用来确认没被吃掉
        for (i in 0 until 8) out[42 + i] = (0xA0 + i).toByte()
        return out
    }

    @Test
    fun `streaminfo 是最后一块时 封面块接管 last 标记`() {
        val chunk = flacHead(streamInfoLast = true)
        val prefix = RestoreTagWriter.flacPrefix(chunk, cover)
        assertNotNull(prefix)
        prefix!!

        assertEquals("fLaC", String(prefix, 0, 4, Charsets.ISO_8859_1))
        // STREAMINFO 的 last 标记必须被清掉，否则解析器读到它就停止找元数据块了
        assertEquals(0x00, prefix[4].toInt())
        assertEquals(34, beInt(prefix, 5, 3))
        for (i in 0 until 34) assertEquals((i + 1).toByte(), prefix[8 + i])

        // 新块：type 6 = PICTURE，因为原来 STREAMINFO 是最后一块，它要接管 last
        val blockAt = RestoreTagWriter.FLAC_HEAD_BYTES
        assertEquals(0x86, prefix[blockAt].toInt() and 0xFF)
        val payloadLen = beInt(prefix, blockAt + 1, 3)
        assertEquals(prefix.size - blockAt - 4, payloadLen)

        val pic = prefix.copyOfRange(blockAt + 4, prefix.size)
        assertEquals(3, beInt(pic, 0, 4))              // 图片类型 3 = 正面封面
        val mimeLen = beInt(pic, 4, 4)
        assertEquals("image/jpeg", String(pic, 8, mimeLen, Charsets.ISO_8859_1))
        var p = 8 + mimeLen
        assertEquals(0, beInt(pic, p, 4)); p += 4      // 描述串长度
        assertEquals(0, beInt(pic, p, 4)); p += 4      // 宽
        assertEquals(0, beInt(pic, p, 4)); p += 4      // 高
        assertEquals(0, beInt(pic, p, 4)); p += 4      // 色深
        assertEquals(0, beInt(pic, p, 4)); p += 4      // 索引色数
        assertEquals(cover.size, beInt(pic, p, 4)); p += 4
        assertArrayEquals(cover, pic.copyOfRange(p, p + cover.size))
    }

    @Test
    fun `streaminfo 后面还有块时 封面块不抢 last 标记`() {
        val chunk = flacHead(streamInfoLast = false)
        val prefix = RestoreTagWriter.flacPrefix(chunk, cover)!!
        assertEquals(0x00, prefix[4].toInt())
        // type 6 且 last = 0
        assertEquals(0x06, prefix[RestoreTagWriter.FLAC_HEAD_BYTES].toInt() and 0xFF)
    }

    @Test
    fun `首块跳过长度正好覆盖 fLaC 与 streaminfo`() {
        val chunk = flacHead(streamInfoLast = true)
        assertEquals(42, RestoreTagWriter.FLAC_HEAD_BYTES)
        // 跳过 42 字节后，剩下的应当正好是音频帧占位（0xA0 起）
        assertEquals(0xA0.toByte(), chunk[RestoreTagWriter.FLAC_HEAD_BYTES])
    }

    @Test
    fun `不是 flac 或数据太短时不插入`() {
        assertNull(RestoreTagWriter.flacPrefix("ID3.....".toByteArray(), cover))
        assertNull(RestoreTagWriter.flacPrefix(ByteArray(10), cover))
    }

    @Test
    fun `mime 按签名判断`() {
        assertEquals("image/jpeg", RestoreTagWriter.mimeOf(cover))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertEquals("image/png", RestoreTagWriter.mimeOf(png))
    }

    @Test
    fun `没有任何可用信息时不写空标签`() {
        val info = HeaderInfo(payloadOffset = 0, format = "kgm")
        assertTrue(RestoreTagWriter.mp3Tag(info, null).isEmpty())
    }

    @Test
    fun `有元数据时写出合法的 id3v2 头`() {
        val info = HeaderInfo(
            payloadOffset = 0,
            format = "ncm",
            metadata = mapOf("title" to "测试歌曲", "artist" to "张三", "album" to "测试专辑")
        )
        val tag = RestoreTagWriter.mp3Tag(info, cover)
        assertTrue(tag.size > 10)
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        assertEquals(0x03, tag[3].toInt()) // v2.3
        // 标签总长必须与头里写的 syncsafe 长度一致
        assertEquals(tag.size, 10 + syncSafe(tag, 6))
    }

    private fun beInt(buf: ByteArray, off: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 8) or (buf[off + i].toInt() and 0xFF)
        return v
    }

    private fun syncSafe(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0x7F) shl 21) or
            ((buf[off + 1].toInt() and 0x7F) shl 14) or
            ((buf[off + 2].toInt() and 0x7F) shl 7) or
            (buf[off + 3].toInt() and 0x7F)
}
