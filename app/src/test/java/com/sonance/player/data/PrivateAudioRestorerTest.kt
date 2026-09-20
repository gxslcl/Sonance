package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实酷狗文件**的向量验证 KGM V3 移植。
 *
 * 向量取自 `24KGoldn、iann dior - Mood (Clean)_HQ.kgm.flac`：
 * 头部 44 字节 + 载荷偏移 1024 起的 96 字节密文，以及对应的期望明文
 * （一个 ID3v2.3 标签，TIT2=Mood / TPE1=24kGoldn/iann dior）。
 * 期望值由已在 280 个真实文件上验证过的参考实现算出。
 */
class PrivateAudioRestorerTest {

    private val header = hex(
        "7CD532EB86027F4BA8AFA68E0FFF9914000400000300000001000000209E7B9BFA409A051AF402898DFFB289"
    )
    private val key = hex("209E7B9BFA409A051AF402898DFFB28900")
    private val payload = hex(
        "414F451A13EFE28999F26701EF4D7FBE2FA5ABF6234BC6B0E209D653D3C820CEB09CFF3BCD45043766DCA857F5E4C7A55F665E6EBF9ADE9C786952189DF0" +
            "6216CA6268B99B17EDDDB333E103EC35BD5E1C6C0E34A2422047B69833A816714AE6"
    )
    private val expected = hex(
        "49443303000000000065544954320000000B000001FFFE4D006F006F0064005450453100000027000001FFFE320034006B0047006F006C0064006E002F0069" +
            "0061006E006E002000640069006F00720054414C4200000015000001FFFE45006C"
    )

    // 中段向量：载荷 pos=100000 起的 256 字节。pos < 272 时 fold 循环一次都不跑，
    // 用这段才能覆盖到 fold 的迭代分支（100000 ushr 4 = 6250，迭代 2 层）。
    private val midCipher = hex(
        "6F8993402126918E22E567550BA353F7EECB78318B9132E1F0659077C95D06122530E6A37BB4D24C15F8508345A3A95BD0A372FD018C71AE0849DB338C441464" +
            "8C0D8E7569AEA81E3FF928F4F9522B488ECEFD3FDED2BDEE3931527F23F980757FE9ECBB7D95D92E7D6915815D2522E39A763155F746B29F334704029688" +
            "CEA4B99029CCC38570632A1CE838C8D6B9C900E90469C091D0A2D08A911974C9ECD3D3F1C3BB888ED08112F62D7EE01DEEC7670E7F34B536E56A5ADB024F" +
            "8980BE37C57E5EA23B827290563D15C2FF332B47C5FE9C9616C45897410EA625A9FC278A8FAB4E246653BCAC8F81E1C565F640863138A1CBA35BBD5DECB176ED347CB0E5"
    )
    private val midExpected = hex(
        "078312002F2B035048249802027020000C058220D0B8384C158000C7E5530A840CB478390158ECF94302840C0E280812980C366310A18A6F262010984878" +
            "60E00B5331D13C48A20A151870A069314829148F432061102CBB86040218BC0E4C0311048C34085C0D5122C8804F405C0B0B636EBC1513A44C14474774" +
            "A893A288184F0F0101684695920A4739E759942C2A42F93BF1663D0458CA551B97D02F81136E25F3A4CB8F4B1A5313E32B252E978F1985ABC4E1B2136EAA" +
            "F5A6EE1722322B13561536B0A1D1296962FA09C54A968E477E74ED69989C5B334E4885699191D2B8CFCE1C56BBC7F7F8ECB0B49E418235A79985AB409CF2096CD96A33EE70E882"
    )

    private fun hex(text: String): ByteArray = requireNotNull(PrivateAudioRestorer.hexToBytes(text))

    @Test
    fun `parseHeader 解析真实头部`() {
        assertTrue(PrivateAudioRestorer.isSupportedContainer(header))

        val h = requireNotNull(PrivateAudioRestorer.parseHeader(header))
        assertEquals(1024, h.payloadOffset)
        assertEquals(3, h.version)
        assertFalse(h.isVpr)
        assertArrayEquals(key, h.key)   // 16 字节头密钥 + 补 0x00
    }

    @Test
    fun `deobfuscate 用真实文件向量解出 ID3 明文`() {
        assertArrayEquals(expected, PrivateAudioRestorer.deobfuscate(payload, key, false))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension(expected))
    }

    @Test
    fun `中段向量覆盖 fold 迭代分支`() {
        val buf = midCipher.copyOf()
        PrivateAudioRestorer.xorChunk(buf, buf.size, 100000, key, false)
        assertArrayEquals(midExpected, buf)
    }

    @Test
    fun `流式分块解密与整块解密结果一致`() {
        val whole = PrivateAudioRestorer.deobfuscate(payload, key, false)

        // 按 RestoreEngine 的真实用法模拟：每块读进缓冲区开头，用绝对 startPos 解密，再拼接。
        // 切分长度故意不等长且跨越 16 字节边界，验证 startPos 连续性。
        val chunked = ByteArray(payload.size)
        var pos = 0
        for (len in intArrayOf(1, 15, 17, 31, 32)) {
            val buf = payload.copyOfRange(pos, pos + len)
            PrivateAudioRestorer.xorChunk(buf, len, pos, key, false)
            System.arraycopy(buf, 0, chunked, pos, len)
            pos += len
        }
        if (pos < payload.size) {
            val len = payload.size - pos
            val buf = payload.copyOfRange(pos, pos + len)
            PrivateAudioRestorer.xorChunk(buf, len, pos, key, false)
            System.arraycopy(buf, 0, chunked, pos, len)
        }

        assertArrayEquals(whole, chunked)
    }

    @Test
    fun `魔数不符时返回 null`() {
        val bad = header.copyOf()
        bad[0] = (bad[0].toInt() xor 0xFF).toByte()
        assertNull(PrivateAudioRestorer.parseHeader(bad))
        assertFalse(PrivateAudioRestorer.isSupportedContainer(bad))
    }

    @Test
    fun `版本字段可被读取以便上层拒绝`() {
        val v5 = header.copyOf()
        v5[0x14] = 5.toByte()
        v5[0x15] = 0
        v5[0x16] = 0
        v5[0x17] = 0
        assertEquals(5, requireNotNull(PrivateAudioRestorer.parseHeader(v5)).version)
    }

    @Test
    fun `sniffExtension 判定后缀`() {
        assertEquals(".flac", PrivateAudioRestorer.sniffExtension("fLaC....".toByteArray()))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension("ID3...".toByteArray()))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension(byteArrayOf(0xFF.toByte(), 0xF3.toByte())))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension(byteArrayOf(0xFF.toByte(), 0xE3.toByte())))
        assertEquals(".mp3", PrivateAudioRestorer.sniffExtension(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `outputStem 剥掉加密容器标记`() {
        // 「包括但不限于 .kgm」—— 覆盖 LockedFormat 登记表里的多种
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.kgm.flac"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.kgma"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.kgg"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.vpr"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.ncm.mp3"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.qmcflac"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.mflac"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.mgg1"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.tkm"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.x2m"))

        // 结尾的 " (1)" 要保留，只剥容器标记
        assertEquals("A - B (1)", PrivateAudioRestorer.outputStem("A - B.kgm (1).flac"))
        assertEquals("A - B (2)", PrivateAudioRestorer.outputStem("A - B.ncm (2).mp3"))

        // 普通文件：只去掉最外层扩展名，不误伤
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.flac"))
        assertEquals("A - B", PrivateAudioRestorer.outputStem("A - B.mp3"))

        // 名字里本来就带点号
        assertEquals(
            "Mr. Polska - Move Up",
            PrivateAudioRestorer.outputStem("Mr. Polska - Move Up.kgm.flac")
        )

        // 全被剥光时兜底，不生成 ".mp3" 这种隐藏文件
        assertEquals(".kgm", PrivateAudioRestorer.outputStem(".kgm.flac"))
    }

    @Test
    fun `hexToBytes 容错与非法输入`() {
        assertArrayEquals(hex("A31F7C55"), PrivateAudioRestorer.hexToBytes("0xA3 0x1F,0x7C-0x55"))
        assertNull(PrivateAudioRestorer.hexToBytes("ABC"))
        assertNull(PrivateAudioRestorer.hexToBytes("GG"))
        assertNull(PrivateAudioRestorer.hexToBytes(""))
    }
}
