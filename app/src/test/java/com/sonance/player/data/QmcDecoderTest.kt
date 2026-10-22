package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * QMC 系（QQ 音乐）解码的单测。
 *
 * 这套测试的样本全部**用同一段代码现场合成**：先实现加密端（TEA-QQ 加密、
 * 掩码流），再用解密端解回来。两端任何一处移植错（哪怕差一个字节序），
 * 「合成 → 解密」的对拍就会失败 —— 这正是这类静默失败最需要的护栏。
 */
class QmcDecoderTest {

    private fun randomBytes(n: Int, seed: Int = 42) =
        Random(seed).nextBytes(n)

    // ===== TEA-QQ =====

    @Test
    fun `tea qq 加解密互逆`() {
        val key = randomBytes(16, 1)
        for (len in listOf(8, 24, 64, 200)) {
            val plain = randomBytes(len, 100 + len)
            val enc = QmcCrypto.teaEncrypt(plain, key)
            assertEquals("密文长度应是 8 的倍数", 0, enc.size % 8)
            assertEquals("密文长度应为 len+10+pad", (len + 10 + 7) / 8 * 8, enc.size)
            val dec = QmcCrypto.teaDecrypt(enc, key)
            assertNotNull("正确密钥不应解密失败", dec)
            assertEquals(plain.toList(), dec!!.toList())
        }
    }

    @Test
    fun `tea qq 错误密钥返回 null 而不是噪声`() {
        val key = randomBytes(16, 2)
        val wrong = randomBytes(16, 3)
        val enc = QmcCrypto.teaEncrypt(randomBytes(64, 4), key)
        assertNull("错误密钥应被零校验拦下", QmcCrypto.teaDecrypt(enc, wrong))
    }

    // ===== ekey 派生 =====

    /** 合成一个 deriveKey(ekey) == key 的 ekey（走 V1 路径） */
    private fun synthEkey(key: ByteArray): ByteArray {
        require(key.size >= 8)
        val simpleKey = ByteArray(8) { i ->
            kotlin.math.abs(kotlin.math.tan(106 + i * 0.1) * 100.0).toInt().toByte()
        }
        val teaKey = ByteArray(16) { i ->
            if (i % 2 == 0) simpleKey[i / 2] else key[i / 2]
        }
        val enc = QmcCrypto.teaEncrypt(key.copyOfRange(8, key.size), teaKey)
        return Base64.getEncoder().encode(key.copyOfRange(0, 8) + enc)
    }

    @Test
    fun `deriveKey v1 往返`() {
        val key = randomBytes(136, 5)
        val ekey = synthEkey(key)
        assertEquals(key.toList(), QmcCrypto.deriveKey(ekey)!!.toList())
    }

    @Test
    fun `deriveKey v2 往返`() {
        val key = randomBytes(136, 6)
        // EncV2：外层是 "QQMusic EncV2,Key:" + 双层 TEA(内层 base64(V1 rawKeyDec))
        // 注意内层必须是合法的 V1 rawKeyDec（8 字节 + QQ-TEA 密文），否则 V1 的零校验必挂
        val inner = synthEkey(key)  // 注意：它本身就是 base64 文本，不能再编一次
        val buf1 = QmcCrypto.teaEncrypt(inner, byteArrayOf(
            0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
            0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
        ))
        val v2Raw = QmcCrypto.teaEncrypt(buf1, byteArrayOf(
            0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
            0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
        ))
        val ekey = Base64.getEncoder()
            .encode("QQMusic EncV2,Key:".toByteArray() + v2Raw)
        assertEquals(key.toList(), QmcCrypto.deriveKey(ekey)!!.toList())
    }

    @Test
    fun `deriveKey 输入过短返回 null`() {
        assertNull(QmcCrypto.deriveKey(randomBytes(8, 7)))
    }

    // ===== 掩码流 =====

    private fun assertChunkedEqualsAllAtOnce(cipher: QmcCrypto.Cipher, plain: ByteArray) {
        // 一次性解密
        val all = plain.copyOf()
        cipher.decrypt(all, all.size, 0)
        // 分块解密（覆盖首段 128 / 段边界 5120 两种跳变）。
        // 契约：buf 的 [0,count) 对应绝对 offset 处的数据 —— 必须切片送进去再拷回来
        val chunked = plain.copyOf()
        var pos = 0
        val sizes = listOf(300, 128, 5120 - 300 - 128, 5120, 4096)
        while (pos < chunked.size) {
            val n = minOf(sizes[(pos / 97) % sizes.size], chunked.size - pos)
            val seg = chunked.copyOfRange(pos, pos + n)
            cipher.decrypt(seg, n, pos)
            System.arraycopy(seg, 0, chunked, pos, n)
            pos += n
        }
        assertNotEquals("加密后不应与明文相同", plain.toList(), all.toList())
        assertEquals("一次性与分块结果必须一致", all.toList(), chunked.toList())
        // XOR 掩码自反：再解一次回明文
        cipher.decrypt(all, all.size, 0)
        assertEquals(plain.toList(), all.toList())
    }

    @Test
    fun `map cipher 分块连续性`() {
        val key = randomBytes(136, 8)
        assertChunkedEqualsAllAtOnce(QmcCrypto.MapCipher(key), randomBytes(12000, 9))
    }

    @Test
    fun `rc4 cipher 分块连续性`() {
        val key = randomBytes(320, 10)
        assertChunkedEqualsAllAtOnce(QmcCrypto.RC4Cipher(key), randomBytes(12000, 11))
    }

    @Test
    fun `static cipher 分块连续性`() {
        assertChunkedEqualsAllAtOnce(QmcCrypto.StaticCipher(), randomBytes(12000, 12))
    }

    // ===== QmcDecoder 端到端（合成 QTag 文件）=====

    /** 合成一个「明文 + QTag 尾部」的 QMC 文件，密钥走 V1 派生路径 */
    private fun synthQTagFile(key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = QmcCrypto.MapCipher(key)
        val enc = plain.copyOf()
        cipher.decrypt(enc, enc.size, 0) // XOR 掩码自反：加密 = 解密
        val ekey = synthEkey(key)
        val meta = "${ekey.decodeToString()},12345,0"  // ekey 本身已是 base64 文本
        val metaBytes = meta.toByteArray(Charsets.UTF_8)
        val lenBytes = byteArrayOf(
            ((metaBytes.size ushr 24) and 0xFF).toByte(),
            ((metaBytes.size ushr 16) and 0xFF).toByte(),
            ((metaBytes.size ushr 8) and 0xFF).toByte(),
            (metaBytes.size and 0xFF).toByte()
        )
        return enc + metaBytes + lenBytes + "QTag".toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun `QTag 文件端到端还原`() {
        val key = randomBytes(136, 13)
        // 开头必须是可嗅探的音频格式：fLaC
        val plain = "fLaC".toByteArray() + randomBytes(3000, 14)
        val file = synthQTagFile(key, plain)

        val decoder = QmcDecoder.probe(
            file.copyOf(64),
            file.size.toLong(),
            file.copyOfRange(maxOf(0, file.size - QmcDecoder.TAIL_WINDOW), file.size)
        )
        assertNotNull("应命中 QTag 解码器", decoder)
        assertTrue("unsupported=" + decoder?.unsupportedReason, decoder!!.supported)
        assertEquals(".flac", decoder.info.suggestedExt)
        assertEquals(
            "payloadLength 应截掉尾部 ekey/元数据",
            plain.size.toLong(),
            decoder.info.payloadLength
        )
        val out = decoder.decryptAll(file)
        // 输出包含尾部结构字节，还原只应作用在音频载荷上
        assertEquals(plain.toList(), out.copyOfRange(0, plain.size).toList())
    }

    @Test
    fun `STag 返回不支持并给出原因`() {
        val plain = "fLaC".toByteArray() + randomBytes(100, 15)
        val file = plain + "12345678".toByteArray() + "STag".toByteArray(Charsets.US_ASCII)
        val decoder = QmcDecoder.probe(
            file.copyOf(64), file.size.toLong(),
            file.copyOfRange(maxOf(0, file.size - QmcDecoder.TAIL_WINDOW), file.size)
        )
        assertNotNull(decoder)
        assertFalse(decoder!!.supported)
        assertTrue(decoder.unsupportedReason!!.contains("STag"))
    }

    @Test
    fun `普通文件误入时被嗅探拦截而不是输出噪声`() {
        // ID3 头的普通 mp3 + 随机尾巴（伪装成裸 ekey 但长度随机）——
        // 静态/映射掩码解出来的东西不可能是合法音频，必须被拦下
        val plain = "ID3".toByteArray() + randomBytes(2000, 16)
        val file = plain + randomBytes(600, 17)
        val decoder = QmcDecoder.probe(
            file.copyOf(64), file.size.toLong(),
            file.copyOfRange(maxOf(0, file.size - QmcDecoder.TAIL_WINDOW), file.size)
        )
        // 要么被判不支持，要么解出来根本不是音频 —— 两种都不该给出可用解码器
        assertTrue(decoder == null || !decoder.supported)
    }

    @Test
    fun `没有尾部数据时返回 null`() {
        assertNull(QmcDecoder.probe(randomBytes(64), 1000L, null))
        assertNull(QmcDecoder.probe(randomBytes(64), 1000L, ByteArray(0)))
    }


}

