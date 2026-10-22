package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sonance.player.data.TestContainers.buildKwm
import com.sonance.player.data.TestContainers.buildNcm
import com.sonance.player.data.TestContainers.kgmHeader

/**
 * 各解码器 + 工厂分发的往返测试。
 *
 * 思路：用 [TestContainers] 的**加密方向**自己造一个容器，再走 `probe -> decryptAll`，
 * 断言解出来的字节与原始音频逐字节相同。没有真实样本也能保证
 * 「探测 -> 派生密钥 -> 解密」这条链路没接错。
 *
 * 另一组测试专门验**分块解密 == 整块解密**：还原流程是 64KB 一块流式跑的，
 * 只要掩码 / 密钥流的跨块衔接错了，大文件就会在中段变成噪声，而这种错误
 * 在小样本的往返测试里是看不出来的。
 */
class ContainerRestorerTest {

    // ===== NCM =====

    @Test
    fun `ncm 往返：音频、封面、元数据、后缀都还原正确`() {
        val audio = ByteArray(5000) { (it * 31 + 7).toByte() }
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4, 5)
        val meta = """
            {"musicName":"测试歌曲","artist":[["张三",1],["李四",2]],
             "album":"测试专辑","format":"flac","bitrate":999000,"duration":180000}
        """.trimIndent()
        val file = buildNcm(audio, "0123456789abcdef".toByteArray(), meta, image)

        val decoder = ContainerRestorer.probe(file.copyOf(16 * 1024), file.size.toLong())
        assertNotNull("NCM 头部应当被识别", decoder)
        decoder!!

        assertEquals("ncm", decoder.formatName)
        // 音频起点 = 文件总长 - 音频长度（中间夹着密钥块、元数据块和封面）
        assertEquals(file.size.toLong() - audio.size, decoder.info.payloadOffset)
        assertEquals(".flac", decoder.info.suggestedExt)
        assertEquals("测试歌曲", decoder.info.title)
        assertEquals("张三 / 李四", decoder.info.artist)
        assertEquals("测试专辑", decoder.info.album)

        val payload = file.copyOfRange(decoder.info.payloadOffset.toInt(), file.size)
        assertArrayEquals(audio, decoder.decryptAll(payload))

        val prefix = file.copyOfRange(0, decoder.info.payloadOffset.toInt())
        assertArrayEquals(image, decoder.extractCover(prefix))
    }

    @Test
    fun `ncm 分块解密与整块一致`() {
        val audio = ByteArray(70_000) { (it * 17 + 11).toByte() }
        val file = buildNcm(audio, "fedcba9876543210".toByteArray(), """{"format":"mp3"}""", ByteArray(0))
        val decoder = ContainerRestorer.probe(file.copyOf(16 * 1024), file.size.toLong())!!
        val payload = file.copyOfRange(decoder.info.payloadOffset.toInt(), file.size)

        assertArrayEquals(decoder.decryptAll(payload), decryptInChunks(decoder, payload, 64 * 1024))
        // 块长故意取质数，专门踩 256 字节密钥流周期的边界
        assertArrayEquals(decoder.decryptAll(payload), decryptInChunks(decoder, payload, 251))
    }

    // ===== KWM =====

    @Test
    fun `kwm 往返：后缀提示与载荷解密正确`() {
        val audio = ByteArray(4096) { (it * 5 + 1).toByte() }
        val recipe = 0x1234_5678_9ABC_DEF0L
        val file = buildKwm(audio, recipe, "320mp3")

        val decoder = ContainerRestorer.probe(file.copyOf(1024), file.size.toLong())
        assertNotNull(decoder)
        decoder!!

        assertEquals("kwm", decoder.formatName)
        assertEquals(1024L, decoder.info.payloadOffset)
        assertEquals(".mp3", decoder.info.suggestedExt)

        val payload = file.copyOfRange(1024, file.size)
        assertArrayEquals(audio, decoder.decryptAll(payload))
        assertArrayEquals(audio, decryptInChunks(decoder, payload, 37))
    }

    @Test
    fun `kwm 配方按无符号 64 位解释`() {
        // 最高位为 1 的配方：有符号解释会得到负数串，掩码整体错位
        val recipe = java.lang.Long.parseUnsignedLong("FEDCBA9876543210", 16)
        val decimal = "18364758544493064720".toByteArray(Charsets.ISO_8859_1)
        val root = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk".toByteArray(Charsets.ISO_8859_1)
        val expected = ByteArray(32) { i ->
            (root[i].toInt() xor decimal[i % decimal.size].toInt()).toByte()
        }
        assertArrayEquals(expected, KwmDecoder.deriveMask(recipe))
    }

    // ===== KGM =====

    @Test
    fun `kgm 探测与分块解密与已验证原语一致`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val payload = ByteArray(1000) { (it * 13 + 5).toByte() }
        val header = kgmHeader(key, version = 3)

        val decoder = ContainerRestorer.probe(header, 44L + payload.size)
        assertNotNull(decoder)
        decoder!!

        assertEquals("kgm", decoder.formatName)
        assertEquals(44L, decoder.info.payloadOffset)
        // KGM 头部不写格式，交给解密后嗅探
        assertEquals("", decoder.info.suggestedExt)
        assertTrue(decoder.supported)

        val key17 = key.copyOf(17)
        val expected = PrivateAudioRestorer.deobfuscate(payload, key17, false)
        assertArrayEquals(expected, decoder.decryptAll(payload))
        assertArrayEquals(expected, decryptInChunks(decoder, payload, 7))
    }

    @Test
    fun `kgm 不支持的版本给出准确原因`() {
        val header = kgmHeader(ByteArray(16), version = 5)
        val decoder = ContainerRestorer.probe(header, 1024L)
        assertNotNull(decoder)
        assertFalse(decoder!!.supported)
        assertTrue(decoder.unsupportedReason!!.contains("5"))
    }

    // ===== 工厂 =====

    @Test
    fun `随机头部不被误判`() {
        val junk = ByteArray(64) { (it * 3).toByte() }
        assertNull(ContainerRestorer.probe(junk, 4096L))
        assertFalse(ContainerRestorer.looksLocked(junk))
    }

    @Test
    fun `qmc 文件按扩展名给出暂不支持的原因`() {
        assertEquals(QmcDecoder.MISSING_TAIL_REASON, ContainerRestorer.unsupportedReason("A - B.mflac"))
        assertEquals(QmcDecoder.MISSING_TAIL_REASON, ContainerRestorer.unsupportedReason("A - B.qmcflac"))
        assertNull(ContainerRestorer.unsupportedReason("A - B.mp3"))
    }

    @Test
    fun `后缀嗅探只认可靠的容器签名`() {
        assertEquals(".flac", AudioDecoder.sniffExtension("fLaC....".toByteArray()))
        assertEquals(".ogg", AudioDecoder.sniffExtension("OggS....".toByteArray()))
        assertEquals(".mp3", AudioDecoder.sniffExtension("ID3.....".toByteArray()))
        assertEquals(".mp3", AudioDecoder.sniffExtension(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        assertEquals(".m4a", AudioDecoder.sniffExtension(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)))
        assertEquals(".wav", AudioDecoder.sniffExtension("RIFF....".toByteArray()))
        // 认不出来时返回空串，让调用方回退到容器自带的格式声明
        assertEquals("", AudioDecoder.sniffExtension(byteArrayOf(1, 2, 3, 4)))
    }

    // ===== 工具 =====

    /** 按 [chunk] 长度分块解密，模拟 RestoreEngine 的流式路径 */
    private fun decryptInChunks(decoder: AudioDecoder, payload: ByteArray, chunk: Int): ByteArray {
        val out = payload.copyOf()
        var pos = 0
        while (pos < out.size) {
            val len = minOf(chunk, out.size - pos)
            val piece = out.copyOfRange(pos, pos + len)
            assertTrue(decoder.decryptChunk(piece, len, pos.toLong()))
            System.arraycopy(piece, 0, out, pos, len)
            pos += len
        }
        return out
    }
}
