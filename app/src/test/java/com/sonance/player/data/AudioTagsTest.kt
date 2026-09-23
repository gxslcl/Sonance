package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 内嵌封面 / 歌词解析器的单元测试。
 *
 * 这些 tag 结构在真机上很难凑齐样本（要 MP3 / FLAC / M4A 各来一份带封面的文件），
 * 所以这里**手工构造最小合法容器**来钉住解析逻辑 —— 字节布局一旦写歪，这里就会红。
 */
class AudioTagsTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 6, 0xFF.toByte(), 0xD9.toByte())

    /* ============================================================
     * 构造工具
     * ============================================================ */

    private fun syncSafe(n: Int): ByteArray = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(),
        ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte()
    )

    private fun be32(n: Int) = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte()
    )

    private fun le32(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 24) and 0xFF).toByte()
    )

    private fun out(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    /** ID3v2 帧：id(4) + size(4) + flags(2) + body。v4 的 size 用 syncsafe */
    private fun id3Frame(id: String, body: ByteArray, major: Int): ByteArray = out {
        ascii(id)
        write(if (major >= 4) syncSafe(body.size) else be32(body.size))
        write(byteArrayOf(0, 0))
        write(body)
    }

    /** 完整 ID3v2 文件：10 字节头 + 若干帧 */
    private fun id3File(major: Int, vararg frames: ByteArray): ByteArray = out {
        val payload = out { frames.forEach { write(it) } }
        ascii("ID3")
        write(byteArrayOf(major.toByte(), 0, 0))   // version + flags
        write(syncSafe(payload.size))
        write(payload)
    }

    /** APIC body: enc(1) + mime\0 + picType(1) + desc + 终止符 + image */
    private fun apicBody(mime: String, image: ByteArray, enc: Int = 0, desc: ByteArray = ByteArray(0)) =
        out {
            write(enc)
            ascii(mime)
            write(0)
            write(3)
            write(desc)
            // 描述终止符宽度由编码决定：UTF-16 系是 2 字节，其余 1 字节
            if (enc == 1 || enc == 2) {
                write(0); write(0)
            } else {
                write(0)
            }
            write(image)
        }

    /** USLT body: enc(1) + lang(3) + desc\0 + text */
    private fun usltBody(text: String, enc: Int = 3) = out {
        write(enc)
        ascii("eng")
        write(0)                                    // 空描述（enc!=1/2 时单字节终止）
        write(text.toByteArray(if (enc == 3) Charsets.UTF_8 else Charsets.ISO_8859_1))
    }

    /* ============================================================
     * MP3 / ID3v2
     * ============================================================ */

    @Test
    fun `ID3v2_3 能取出 APIC 封面`() {
        val file = id3File(3, id3Frame("APIC", apicBody("image/jpeg", jpeg), 3))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `ID3v2_4 的 syncsafe 帧长度也能取出封面`() {
        val file = id3File(4, id3Frame("APIC", apicBody("image/png", jpeg), 4))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `APIC 前面有其他帧也能找到封面`() {
        val file = id3File(
            3,
            id3Frame("TIT2", out { write(0); ascii("某首歌") }, 3),
            id3Frame("TPE1", out { write(0); ascii("某个人") }, 3),
            id3Frame("APIC", apicBody("image/jpeg", jpeg), 3)
        )
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `ID3v2 能取出 USLT 歌词`() {
        val text = "[00:01.00]第一行\n[00:05.00]第二行"
        val file = id3File(3, id3Frame("USLT", usltBody(text), 3))
        assertEquals(text, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `ID3v2 UTF-16 描述的 APIC 不会把描述吃进图片`() {
        // enc=1，描述是 UTF-16LE 的 "x" = 78 00，再加双字节终止符 00 00
        val desc = byteArrayOf(0x78, 0x00)
        val file = id3File(3, id3Frame("APIC", apicBody("image/jpeg", jpeg, enc = 1, desc = desc), 3))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `没有目标帧时返回 null`() {
        val file = id3File(3, id3Frame("TIT2", out { write(0); ascii("只有标题") }, 3))
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertNull(AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    /* ============================================================
     * FLAC
     * ============================================================ */

    /** FLAC metadata block header: last(1bit)+type(7bit) + 3 字节长度 */
    private fun flacBlockHeader(type: Int, len: Int, last: Boolean) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        (len and 0xFF).toByte()
    )

    private fun flacPictureBody(image: ByteArray) = out {
        write(be32(3))                     // picture type
        val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
        write(be32(mime.size)); write(mime)
        write(be32(0))                     // 空描述
        write(be32(500)); write(be32(500)); write(be32(24)); write(be32(0))
        write(be32(image.size)); write(image)
    }

    private fun flacFile(vararg blocks: Pair<Int, ByteArray>): ByteArray = out {
        ascii("fLaC")
        blocks.forEachIndexed { i, (type, body) ->
            write(flacBlockHeader(type, body.size, last = i == blocks.lastIndex))
            write(body)
        }
    }

    @Test
    fun `FLAC 能取出 PICTURE 封面`() {
        val file = flacFile(6 to flacPictureBody(jpeg))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `FLAC 跳过 STREAMINFO 后仍能拿到 PICTURE`() {
        val streamInfo = ByteArray(34) { 0x11 }          // 类型 0，34 字节
        val file = flacFile(0 to streamInfo, 6 to flacPictureBody(jpeg))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    private fun vorbisBody(vararg comments: String) = out {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        write(le32(vendor.size)); write(vendor)          // FLAC 的 vorbis comment 是小端
        write(le32(comments.size))
        comments.forEach { c ->
            val b = c.toByteArray(Charsets.UTF_8)
            write(le32(b.size)); write(b)
        }
    }

    @Test
    fun `FLAC 能从 VORBIS_COMMENT 取 LYRICS`() {
        val text = "[00:02.00]甲\n[00:04.00]乙"
        val file = flacFile(4 to vorbisBody("TITLE=某首歌", "LYRICS=$text"))
        assertEquals(text, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `FLAC 的 UNSYNCEDLYRICS 也能取`() {
        val file = flacFile(4 to vorbisBody("UNSYNCEDLYRICS=纯文本歌词"))
        assertEquals("纯文本歌词", AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `FLAC 有封面时不会误取成歌词`() {
        val file = flacFile(6 to flacPictureBody(jpeg), 4 to vorbisBody("LYRICS=歌词在这"))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertEquals("歌词在这", AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    /* ============================================================
     * MP4 / M4A
     * ============================================================ */

    private fun atom(type: String, payload: ByteArray) = out {
        write(be32(payload.size + 8))
        ascii(type)
        write(payload)
    }

    private fun mp4DataAtom(payload: ByteArray) = out {
        // data: size + "data" + type(4) + locale(4) + payload
        write(be32(payload.size + 16))
        ascii("data")
        write(be32(1)); write(be32(0))
        write(payload)
    }

    private fun mp4File(ilstChildren: ByteArray): ByteArray = out {
        write(atom("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)))
        val ilst = atom("ilst", ilstChildren)
        val meta = atom("meta", ByteArray(4) + ilst)       // meta 有 4 字节 version/flags
        val udta = atom("udta", meta)
        write(atom("moov", udta))
    }

    @Test
    fun `M4A 能取出 covr 封面`() {
        val file = mp4File(atom("covr", mp4DataAtom(jpeg)))
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `M4A 能取出 c-lyr 歌词`() {
        val text = "[00:03.00]第一句\n[00:07.00]第二句"
        val file = mp4File(atom("\u00A9lyr", mp4DataAtom(text.toByteArray(Charsets.UTF_8))))
        assertEquals(text, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `M4A 里 covr 在前 c-lyr 在后两个都能取`() {
        val text = "歌词"
        val children = atom("covr", mp4DataAtom(jpeg)) +
            atom("\u00A9lyr", mp4DataAtom(text.toByteArray(Charsets.UTF_8)))
        val file = mp4File(children)
        assertArrayEquals(jpeg, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertEquals(text, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `covr 解析失败后仍能继续找到后面的 c-lyr`() {
        // 构造一个 covr，里面的 data 长度非法（payload <= 0），应跳过而不是整体放弃
        val badCovr = atom("covr", out {
            write(be32(8)); ascii("data")          // 只有头，payload 为 0
        })
        val text = "后面的歌词"
        val children = badCovr + atom("\u00A9lyr", mp4DataAtom(text.toByteArray(Charsets.UTF_8)))
        val file = mp4File(children)
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertEquals(text, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    /* ============================================================
     * 健壮性
     * ============================================================ */

    @Test
    fun `不是音频文件时安静返回 null`() {
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream("hello world!!".toByteArray())))
        assertNull(AudioTags.lyricsFrom(ByteArrayInputStream("hello world!!".toByteArray())))
    }

    @Test
    fun `空输入不崩`() {
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(ByteArray(0))))
        assertNull(AudioTags.lyricsFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `截断的 ID3 头不崩`() {
        assertNull(AudioTags.coverArtFrom(ByteArrayInputStream(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))))
    }
}


/**
 * 「一趟同时取封面和歌词」（[AudioTags.readTags] 的流版本）。
 *
 * 这条链路是给「写回文件」用的：候选探测要一次拿齐两样。
 * 以前 `scan` 找到封面就提前 return，封面帧又几乎总在歌词帧前面，
 * 所以两个 flag 一起开时**歌词永远拿不到** —— 这里专门钉住两种帧序。
 */
class AudioTagsBothTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun frame(id: String, payload: ByteArray) =
        id.toByteArray(Charsets.ISO_8859_1) + be32(payload.size) + byteArrayOf(0, 0) + payload

    private fun be32(n: Int) = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte()
    )

    private fun utf16(text: String) = byteArrayOf(0x01, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun apic(bytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x00) + mime + byteArrayOf(0x00, 0x03, 0x00) + bytes
        return frame("APIC", payload)
    }

    private fun uslt(text: String): ByteArray {
        val payload = byteArrayOf(0x01) + "XXX".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00, 0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        return frame("USLT", payload)
    }

    private fun tag(vararg frames: ByteArray): ByteArray {
        val body = frames.reduce { a, b -> a + b }
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) + syncSafe(body.size) + body
    }

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
    )

    private fun both(bytes: ByteArray) = AudioTags.tagsFrom(ByteArrayInputStream(bytes))

    @Test
    fun `封面在前歌词在后 两样都能拿到`() {
        val lrc = "[00:01.00]词"
        val (cover, lyrics) = both(tag(apic(jpeg), uslt(lrc)))
        assertArrayEquals(jpeg, cover)
        assertEquals(lrc, lyrics)
    }

    @Test
    fun `歌词在前封面在后 两样都能拿到`() {
        val lrc = "[00:01.00]词"
        val (cover, lyrics) = both(tag(uslt(lrc), apic(jpeg)))
        assertArrayEquals(jpeg, cover)
        assertEquals(lrc, lyrics)
    }

    @Test
    fun `只有其中一样时不空手`() {
        val (cover, lyrics) = both(tag(apic(jpeg)))
        assertArrayEquals(jpeg, cover)
        assertEquals(null, lyrics)
    }
}
