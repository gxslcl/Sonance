package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.nmsf`（网易云现行播放缓存）解码测试。
 *
 * 头 24 字节取自真机实测（2026-10，同一批缓存所有文件完全一致）：
 * 解密 = 整体 XOR 0xA3，解出标准 m4a（ftyp / "M4A " / "isom"）。
 */
class NmsDecoderTest {

    /** 真机实测的 m4a 缓存头 24 字节 */
    private val realHeader = hexToBytes(
        "a3a3a3bfc5d7dad3ee97e283a3a3a1a3ee97e283cad0ccce"
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    @Test
    fun `真机实测头 XOR 后解出 m4a 签名`() {
        val plain = NmsDecoder.probe(realHeader, 999L)!!
            .decryptAll(realHeader)
            .map { it.toInt() and 0xFF }
        // 00 00 00 1C 66 74 79 70 = ftyp box（28 字节）
        assertEquals(listOf(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70), plain.subList(0, 8))
        // minor version 00 00 02 00 + "M4A " + "isom"
        assertEquals(listOf(0x00, 0x00, 0x02, 0x00), plain.subList(12, 16))
        assertEquals("M4A ", plain.subList(16, 20).map { it.toChar() }.joinToString(""))
        assertEquals("isom", plain.subList(20, 24).map { it.toChar() }.joinToString(""))
    }

    @Test
    fun `probe 命中并给出载荷起点 0`() {
        val decoder = NmsDecoder.probe(realHeader, 12345678L)
        assertNotNull(decoder)
        assertEquals(0L, decoder!!.info.payloadOffset)
        assertEquals("nmsf", decoder.formatName)
    }

    @Test
    fun `probe 拒绝普通明文音频与垃圾数据`() {
        // 标准 m4a（不加密）不该被 nms 认领 —— 它本来就不需要还原
        assertNull(NmsDecoder.probe(hexToBytes("00000018667479704d344120"), 999L))
        assertNull(NmsDecoder.probe(ByteArray(0), 999L))
        assertNull(NmsDecoder.probe(ByteArray(8), 0L))
        assertNull(NmsDecoder.probe(hexToBytes("7b226d6574" + "61206263"), 999L)) // {"met…
    }

    @Test
    fun `流式分块解密与一次性解密结果一致`() {
        val payload = ByteArray(200) { (it * 7 + 3).toByte() }
        val all = NmsDecoder.probe(realHeader, 999L)!!.decryptAll(payload)

        // 按 RestorePlan.writeTo 的真实调用方式：工作缓冲反复覆盖、解密后立刻写走
        val work = ByteArray(200)
        val out = ByteArray(200)
        val decoder = NmsDecoder.probe(realHeader, 999L)!!
        var pos = 0L
        var off = 0
        for (size in listOf(64, 64, 64, 8)) {
            payload.copyInto(work, 0, off, off + size)
            assertTrue(decoder.decryptChunk(work, size, pos))
            System.arraycopy(work, 0, out, off, size)
            pos += size
            off += size
        }
        assertTrue(all.contentEquals(out))
    }

    @Test
    fun `looksLocked 放行 nmsf 头`() {
        assertTrue(ContainerRestorer.looksLocked(realHeader))
        assertFalse(ContainerRestorer.looksLocked(hexToBytes("00000018667479704d344120")))
    }

    @Test
    fun `文件名带 nmsf 被识别为网易云私有格式`() {
        assertEquals("网易云音乐", LockedFormat.sourceOf("1842025914_256001_611696b087907ece18b76baccb233250_0.nmsf"))
        assertEquals("nmsf", LockedFormat.extensionOf("1842025914_256001_611696b087907ece18b76baccb233250_0.nmsf"))
        assertTrue(LockedFormat.isLocked("1842025914_256001_611696b087907ece18b76baccb233250_0.nmsf"))
    }
}
