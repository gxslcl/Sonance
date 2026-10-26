package com.sonance.player.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [NcDuration] 的时长探测单测。
 *
 * 样本全部是**手工构造的最小合法结构**：不依赖真实音频文件，
 * 只要求容器元数据（mvhd / STREAMINFO / fmt / 帧头）能被解析出预期时长。
 * 150 秒阈值的裁决语义也在这一层钉死。
 */
class NcDurationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 用户口径：小于 150 秒的一律按残曲处理 —— 阈值本身也要钉住，防止被无意改动 */
    @Test
    fun `残曲阈值是 150 秒`() {
        assertEquals(150.0, NcDuration.TRIAL_MIN_SECONDS, 0.0)
    }

    private fun write(bytes: ByteArray): File {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return f
    }

    // ===== m4a（mp4 box 结构）=====

    /** 构造 ftyp + moov(mvhd) + mdat 的最小 m4a；moov 位置可选头部或尾部 */
    private fun m4aBytes(durationMs: Long, moovAtTail: Boolean = false): ByteArray {
        fun u32(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
        )
        fun box(type: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(u32(body.size + 8))
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            return out.toByteArray()
        }
        val ftyp = box("ftyp", "M4A isom".toByteArray(Charsets.ISO_8859_1))
        // mvhd v0：ver/flags(4) + ctime(4) + mtime(4) + timescale(4) + duration(4)
        val mvhd = box(
            "mvhd",
            byteArrayOf(0, 0, 0, 0) + ByteArray(8) + u32(1000) + u32(durationMs.toInt())
        )
        val moov = box("moov", mvhd)
        val mdat = box("mdat", ByteArray(2048))
        return if (moovAtTail) ftyp + mdat + moov else ftyp + moov + mdat
    }

    @Test
    fun `m4a 从 mvhd 读出时长`() {
        val secs = NcDuration.probeSeconds(write(m4aBytes(285_900)))!!
        assertEquals(285.9, secs, 0.01)
    }

    @Test
    fun `moov 在尾部的 m4a 也能读出`() {
        val secs = NcDuration.probeSeconds(write(m4aBytes(40_000, moovAtTail = true)))!!
        assertEquals(40.0, secs, 0.01)
    }

    @Test
    fun `残曲时长低于阈值`() {
        val secs = NcDuration.probeSeconds(write(m4aBytes(10_002)))!!
        assertEquals(true, secs < NcDuration.TRIAL_MIN_SECONDS)
    }

    // ===== m4a 数据完整性（mdat 截短探测）=====

    /**
     * 构造 ftyp + moov(mvhd + trak(mdia(mdhd + minf(stbl(stts, stsc, stsz, stco))))) + mdat
     * 的最小可对账 m4a。声明时长 = 样本数 × delta；[keptSamples] 小于样本数时
     * mdat 头部声明完整大小但文件实际截短 —— 复刻网易云「块没下完」的缓存形态。
     */
    private fun m4aWithTablesBytes(
        declaredMs: Long,
        sampleCount: Int,
        deltaMs: Int,
        sampleSize: Int,
        keptSamples: Int = sampleCount
    ): ByteArray {
        fun u32(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
        )
        fun box(type: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(u32(body.size + 8))
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            return out.toByteArray()
        }
        val ftyp = box("ftyp", "M4A isom".toByteArray(Charsets.ISO_8859_1))
        // mvhd v0：ver/flags(4) + ctime(4) + mtime(4) + timescale(4) + duration(4)
        val mvhd = box("mvhd", ByteArray(12) + u32(1000) + u32(declaredMs.toInt()))
        // mdhd v0：ver/flags(4) + ctime(4) + mtime(4) + timescale(4) + duration(4)
        val mdhd = box("mdhd", ByteArray(12) + u32(1000) + u32(declaredMs.toInt()))
        // full box 表格统一是 ver/flags(4) + entryCount(4) + 条目
        val stts = box("stts", ByteArray(4) + u32(1) + u32(sampleCount) + u32(deltaMs))
        val stsc = box("stsc", ByteArray(4) + u32(1) + u32(1) + u32(sampleCount) + u32(1))
        // stsz：ver/flags(4) + sampleSize(4) + sampleCount(4)
        val stsz = box("stsz", ByteArray(4) + u32(sampleSize) + u32(sampleCount))

        fun buildMoov(chunkOffset: Int): ByteArray {
            val stco = box("stco", ByteArray(4) + u32(1) + u32(chunkOffset))
            val stbl = box("stbl", stts + stsc + stsz + stco)
            val mdia = box("mdia", mdhd + box("minf", stbl))
            return box("moov", mvhd + box("trak", mdia))
        }
        // stco 里的 chunk 偏移 = mdat 数据的绝对位置；moov 大小与偏移取值无关，两趟解决
        val dataStart = ftyp.size + buildMoov(0).size + 8
        // mdat 头部声明完整大小（截短时大于文件实际 —— 被截短缓存的标志形态）
        val mdatHeader = u32(8 + sampleCount * sampleSize) + "mdat".toByteArray(Charsets.ISO_8859_1)
        return ftyp + buildMoov(dataStart) + mdatHeader + ByteArray(keptSamples * sampleSize)
    }

    @Test
    fun `m4a 采样数据完整判 complete`() {
        val c = NcDuration.completeness(write(m4aWithTablesBytes(200_000, 100, 2_000, 100)))!!
        assertEquals(200.0, c.declaredSeconds, 0.01)
        assertEquals(200.0, c.playableSeconds, 0.01)
        assertEquals(true, c.complete)
    }

    @Test
    fun `m4a mdat 被截短判不完整`() {
        // 声明 200 秒，数据只剩一半 → 可播放 100 秒，远低于 1 秒容差
        val c = NcDuration.completeness(
            write(m4aWithTablesBytes(200_000, 100, 2_000, 100, keptSamples = 50))
        )!!
        assertEquals(200.0, c.declaredSeconds, 0.01)
        assertEquals(100.0, c.playableSeconds, 0.01)
        assertEquals(false, c.complete)
    }

    @Test
    fun `没有采样表的 m4a 判不了返回 null（放行）`() {
        // m4aBytes 只有 ftyp+moov(mvhd)+mdat，没有 trak/stbl —— 判不了就不拦
        assertNull(NcDuration.completeness(write(m4aBytes(200_000))))
    }

    @Test
    fun `垃圾文件完整性返回 null 不抛异常`() {
        assertNull(NcDuration.completeness(write(ByteArray(64))))
        assertNull(NcDuration.completeness(write("not audio at all".toByteArray())))
    }

    // ===== flac =====

    /** STREAMINFO：sampleRate(20bit) channels(3) bps(5) totalSamples(36bit) */
    private fun flacBytes(sampleRate: Int, totalSamples: Long): ByteArray {
        val d = ByteArray(34)
        d[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        d[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        d[12] = (((sampleRate and 0xF) shl 4) or 0x02).toByte()   // sampleRate 低4位 + channels
        // totalSamples 36bit：低 4 位在 d[13]（其余给 bps），低 32 位在 d[14..17]
        val v = totalSamples
        d[13] = (((v shr 32) and 0x0F).toInt() or 0x30).toByte()  // 0x30 = bps-1=3（占高 nibble）
        d[14] = ((v shr 24) and 0xFF).toInt().toByte()
        d[15] = ((v shr 16) and 0xFF).toInt().toByte()
        d[16] = ((v shr 8) and 0xFF).toInt().toByte()
        d[17] = (v and 0xFF).toInt().toByte()
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0, 0, 34))                        // 首块 STREAMINFO，长度 34
        out.write(d)
        out.write(ByteArray(64))                                   // 随意的元数据/音频占位
        return out.toByteArray()
    }

    @Test
    fun `flac 从 STREAMINFO 读出时长`() {
        val secs = NcDuration.probeSeconds(write(flacBytes(44_100, 44_100L * 200)))!!
        assertEquals(200.0, secs, 0.01)
    }

    // ===== wav =====

    private fun wavBytes(byteRate: Int, dataBytes: Int): ByteArray {
        fun u32(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
        )
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val fmt = u16(1) + u16(2) + u32(44_100) + u32(byteRate) + u16(4) + u16(16)
        return "RIFF".toByteArray() + u32(4 + 8 + fmt.size + 8 + dataBytes) +
            "WAVE".toByteArray() +
            "fmt ".toByteArray() + u32(fmt.size) + fmt +
            "data".toByteArray() + u32(dataBytes) + ByteArray(dataBytes)
    }

    @Test
    fun `wav 用 data 除以 byteRate`() {
        val secs = NcDuration.probeSeconds(write(wavBytes(176_400, 176_400 * 30)))!!
        assertEquals(30.0, secs, 0.01)
    }

    // ===== mp3 =====

    @Test
    fun `mp3 用首帧码率估算时长`() {
        // MPEG1 Layer3 128kbps：帧头 FF FB 90 00；前面放 1KB 的 ID3v2 标签
        val id3 = ByteArray(1024)
        id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
        val total = 1024 + 128_000 / 8 * 30   // 30 秒 @128kbps
        val bytes = id3 + ByteArray(total - 1024)
        bytes[1024] = 0xFF.toByte(); bytes[1025] = 0xFB.toByte(); bytes[1026] = 0x90.toByte()
        val secs = NcDuration.probeSeconds(write(bytes))!!
        assertEquals(30.0, secs, 1.0)
    }

    // ===== 判不了 / 异常 =====

    @Test
    fun `ogg 判不了时长返回 null（放行）`() {
        val bytes = "OggS".toByteArray() + ByteArray(512)
        assertNull(NcDuration.probeSeconds(write(bytes)))
    }

    @Test
    fun `垃圾文件返回 null 不抛异常`() {
        assertNull(NcDuration.probeSeconds(write(ByteArray(0))))
        assertNull(NcDuration.probeSeconds(write(ByteArray(64))))
        assertNull(NcDuration.probeSeconds(write("not audio at all".toByteArray())))
    }

    @Test
    fun `box 尺寸损坏不越界不死循环`() {
        // 顶层 box 声称比文件还大 → 直接放弃
        val bytes = byteArrayOf(0x7F, 0x7F.toByte(), 0x7F, 0x7F.toByte()) +
            "moov".toByteArray() + ByteArray(16)
        assertNull(NcDuration.probeSeconds(write(bytes)))
    }
}
