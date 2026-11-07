package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 下载时写的 ID3v2.3 标签。
 *
 * 这块错了**不会报错**，只会让文件在系统音乐库里显示成"文件名当标题、歌手未知"，
 * 或者更糟 —— 中文歌名变乱码。所以关键验证是**写出来再用项目自己的解析器读回去**：
 * [AudioTags] 已经在解析 ID3v2，拿它当裁判比自己再写一个解析器可信得多。
 */
class Id3WriterTest {

    /** 假的音频数据：一个合法的 MPEG 帧同步头 + 填充 */
    private val audio = ByteArray(512) { if (it < 2) 0xFF.toByte() else (it and 0xFF).toByte() }

    private val cover = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),   // JPEG 头
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    )

    /** 把标签和音频拼起来，模拟"下载完并写好标签"的文件 */
    private fun tagged(tag: ByteArray): ByteArray = tag + audio

    // ===== 写出来的标签能不能被项目自己的解析器读回来 =====

    @Test
    fun `封面写进去之后 AudioTags 能读出来`() {
        // ★ 这条是核心：不用自己再写一个解析器当裁判，
        // 直接拿项目已有的 AudioTags（它本来就在解析 ID3v2.3/2.4）验
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", cover, "image/jpeg")
        val file = tagged(tag)
        assertArrayEquals(cover, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `没有封面时不写 APIC，解析器返回 null`() {
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", cover = null)
        assertTrue(tag.isNotEmpty())
        // 有文本帧但没有图 → 解析器拿不到封面，也不该崩
        assertEquals(null, AudioTags.coverArtFrom(ByteArrayInputStream(tagged(tag))))
    }

    // ===== 标签结构 =====

    @Test
    fun `标签头是 ID3v2_3 且长度是 syncsafe`() {
        val tag = Id3Writer.buildTag("A", "B", "C")
        assertEquals('I'.code.toByte(), tag[0])
        assertEquals('D'.code.toByte(), tag[1])
        assertEquals('3'.code.toByte(), tag[2])
        assertEquals(3, tag[3].toInt())          // v2.3
        assertEquals(0, tag[4].toInt())          // revision
        assertEquals(0, tag[5].toInt())          // flags
        // syncsafe：4 个字节的高位必须都是 0
        for (i in 6..9) assertTrue("第 $i 字节高位必须为 0", tag[i].toInt() and 0x80 == 0)
        // 声明的长度 + 10 字节头 = 整个标签长度
        val declared = ((tag[6].toInt() and 0x7F) shl 21) or
            ((tag[7].toInt() and 0x7F) shl 14) or
            ((tag[8].toInt() and 0x7F) shl 7) or
            (tag[9].toInt() and 0x7F)
        assertEquals(tag.size, declared + 10)
    }

    @Test
    fun `帧长度是普通大端而不是 syncsafe`() {
        // ★ v2.3 和 v2.4 最容易搞混的地方：v2.3 的帧长度是普通大端。
        // 写成 syncsafe 的话，长度超过 127 的帧（带封面的 APIC 一定是）会整体错位
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", cover, "image/jpeg")
        // 第一个帧的 ID 是 TIT2
        assertEquals("TIT2", String(tag, 10, 4, Charsets.US_ASCII))
        val n = ((tag[14].toInt() and 0xFF) shl 24) or
            ((tag[15].toInt() and 0xFF) shl 16) or
            ((tag[16].toInt() and 0xFF) shl 8) or
            (tag[17].toInt() and 0xFF)
        // "晴天" 两个汉字 = 4 字节 UTF-16LE + 1 编码字节 + 2 BOM = 7
        assertEquals(7, n)
    }

    @Test
    fun `文本帧用 UTF-16 带 BOM 编码`() {
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美")
        val payload = tag.copyOfRange(20, 27)   // TIT2 的 payload（10 头 + 10 帧头 = 20）
        assertEquals(1, payload[0].toInt())                        // 编码字节 0x01 = UTF-16
        assertEquals(0xFF, payload[1].toInt() and 0xFF)            // BOM 小端
        assertEquals(0xFE, payload[2].toInt() and 0xFF)
        assertEquals("晴天", String(payload, 3, 4, Charsets.UTF_16LE))
    }

    @Test
    fun `中文歌名不会变成乱码`() {
        // v2.3 规范里没有 UTF-8，写 UTF-8 的话 MediaScanner 可能读成问号。
        // 这里用 UTF-16 读回来，必须一字不差
        val tag = Id3Writer.buildTag("味道 (live)", "张学友", "活出生命Live演唱会")
        val s = String(tag, Charsets.ISO_8859_1)
        // 逐帧扫一遍，把所有文本帧解出来
        val texts = readTextFrames(tag)
        assertEquals("味道 (live)", texts["TIT2"])
        assertEquals("张学友", texts["TPE1"])
        assertEquals("活出生命Live演唱会", texts["TALB"])
        assertFalse(s.contains("?"))
    }

    @Test
    fun `空字段不写帧`() {
        val tag = Id3Writer.buildTag("晴天", "", "")
        val texts = readTextFrames(tag)
        assertEquals("晴天", texts["TIT2"])
        assertFalse("空歌手不该写 TPE1", texts.containsKey("TPE1"))
        assertFalse("空专辑不该写 TALB", texts.containsKey("TALB"))
    }

    @Test
    fun `三个字段都没有时标签仍然合法`() {
        val tag = Id3Writer.buildTag("", "", "")
        assertEquals(10, tag.size)     // 只有头
        // 空标签也仍然是一个占 10 字节的标签，拼的时候要跳过
        assertEquals(10, Id3Writer.existingTagSize(tag))
    }

    // ===== 识别并跳过旧标签 =====

    @Test
    fun `认得出没有标签的文件`() {
        assertEquals(0, Id3Writer.existingTagSize(audio))
        assertEquals(0, Id3Writer.existingTagSize(ByteArray(0)))
        assertEquals(0, Id3Writer.existingTagSize(ByteArray(4)))
    }

    @Test
    fun `算得出旧标签的总长度`() {
        // 网易云返回的流自带一个 35 字节的 TSSE 标签（10 头 + 25 内容）
        val tag = Id3Writer.buildTag("A", "B", "C")
        assertEquals(tag.size, Id3Writer.existingTagSize(tag))
    }

    @Test
    fun `带 footer 的旧标签要连 footer 一起跳过`() {
        // flags 的 bit4（0x10）表示标签后面跟 10 字节 footer，
        // 漏掉它会把 footer 当成音频数据写进去
        val head = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04, 0x00, 0x10,      // v2.4，flags 带 footer 位
            0, 0, 0, 20            // syncsafe 长度 20
        )
        assertEquals(10 + 20 + 10, Id3Writer.existingTagSize(head))
    }

    // ===== 拼接：剥掉旧标签 + 保留音频 =====

    @Test
    fun `拼接时剥掉旧标签并保留音频`() {
        val oldTag = Id3Writer.buildTag("旧", "旧", "旧")           // 模拟流自带的标签
        val source = oldTag + audio
        val newTag = Id3Writer.buildTag("新标题", "新歌手", "新专辑")

        val out = ByteArrayOutputStream()
        Id3Writer.copyWithTag(ByteArrayInputStream(source), out, newTag)
        val result = out.toByteArray()

        // 结果 = 新标签 + 原始音频（**没有**旧标签）
        assertArrayEquals(newTag + audio, result)
        val texts = readTextFrames(result)
        assertEquals("新标题", texts["TIT2"])
        assertFalse("旧标签必须被剥掉", texts.containsValue("旧"))
    }

    @Test
    fun `源文件没有标签时原样保留音频`() {
        val newTag = Id3Writer.buildTag("新", "新", "新")
        val out = ByteArrayOutputStream()
        Id3Writer.copyWithTag(ByteArrayInputStream(audio), out, newTag)
        assertArrayEquals(newTag + audio, out.toByteArray())
    }

    @Test
    fun `源文件比 10 字节还短也不会崩`() {
        val newTag = Id3Writer.buildTag("新", "新", "新")
        val out = ByteArrayOutputStream()
        Id3Writer.copyWithTag(ByteArrayInputStream(byteArrayOf(1, 2, 3)), out, newTag)
        assertArrayEquals(newTag + byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun `空源文件只写出标签`() {
        val newTag = Id3Writer.buildTag("新", "新", "新")
        val out = ByteArrayOutputStream()
        Id3Writer.copyWithTag(ByteArrayInputStream(ByteArray(0)), out, newTag)
        assertArrayEquals(newTag, out.toByteArray())
    }

    @Test
    fun `写出来的文件能被 AudioTags 找到封面`() {
        // 端到端：源文件自带一个旧标签 → 剥掉 → 写新标签（含封面）→ 解析器读得到
        val oldTag = Id3Writer.buildTag("Lavf", "", "")
        val source = oldTag + audio
        val newTag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", cover, "image/jpeg")
        val out = ByteArrayOutputStream()
        Id3Writer.copyWithTag(ByteArrayInputStream(source), out, newTag)
        assertArrayEquals(cover, AudioTags.coverArtFrom(ByteArrayInputStream(out.toByteArray())))
    }

    // ===== 换封面：保留原有帧 =====

    /**
     * 给用户自己的曲库换封面时，**除了封面之外的帧一个都不能少**。
     *
     * 这条是 [Id3Writer.withCoverPreserving] 存在的全部理由：走 [Id3Writer.buildTag]
     * 的话，音轨号、流派、年份这些会被一起抹掉 —— 换张封面不该有这种副作用。
     */
    @Test
    fun `换封面保留音轨号和流派`() {
        val original = tagOf(
            3,
            frameV(3, "TIT2", utf16("晴天")),
            frameV(3, "TRCK", utf16("3/12")),
            frameV(3, "TCON", utf16("Pop")),
            frameV(3, "APIC", apicPayload(cover))
        )
        val newCover = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 1, 2, 3, 4
        )

        val rebuilt = Id3Writer.withCoverPreserving(original, newCover, "image/jpeg")
        assertNotNull(rebuilt)

        val texts = readTextFrames(rebuilt!!)
        assertEquals("晴天", texts["TIT2"])
        assertEquals("3/12", texts["TRCK"])
        assertEquals("Pop", texts["TCON"])
        assertEquals(3, rebuilt[3].toInt())          // 原标签是 v2.3，产出也必须是 v2.3
    }

    /**
     * 一个文件里留多张封面会让部分播放器显示**错的那张**（通常是最先遇到的那张），
     * 所以旧的 APIC 要全丢掉，只留新写的这一张。
     */
    @Test
    fun `换封面后只剩一张封面`() {
        val original = tagOf(
            3,
            frameV(3, "TIT2", utf16("晴天")),
            frameV(3, "APIC", apicPayload(cover)),
            frameV(3, "APIC", apicPayload(cover))
        )
        val newCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9, 9)

        val rebuilt = Id3Writer.withCoverPreserving(original, newCover, "image/jpeg")!!
        assertEquals(1, countFrames(rebuilt, "APIC"))
        // 端到端：拿项目自己的解析器读回来，必须是新那张
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
    }

    /**
     * v2.4 的帧长度是 syncsafe，v2.3 是普通大端 —— 混着写整套帧都会解析歪。
     * 所以版本必须**跟着原标签走**，不能一律按 v2.3 重建。
     */
    @Test
    fun `原标签是 v2_4 时产出 v2_4`() {
        val original = tagOf(
            4,
            frameV(4, "TIT2", utf16("晴天")),
            frameV(4, "TRCK", utf16("1/1"))
        )
        val newCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7)

        val rebuilt = Id3Writer.withCoverPreserving(original, newCover, "image/jpeg")!!
        assertEquals(4, rebuilt[3].toInt())
        // APIC 的帧长度字段必须是 syncsafe（每字节高位为 0）；按大端写的话这里会挂
        val apicStart = 10 + frameV(4, "TIT2", utf16("晴天")).size +
            frameV(4, "TRCK", utf16("1/1")).size
        assertEquals("APIC", String(rebuilt, apicStart, 4, Charsets.US_ASCII))
        for (i in 4..7) {
            assertTrue(
                "v2.4 的帧长度必须是 syncsafe",
                rebuilt[apicStart + i].toInt() and 0x80 == 0
            )
        }
        // AudioTags 按 v2.4 的规则解析，能读出来就说明长度字段写对了
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
    }

    /** 没有原标签 → 返回 null，由调用方退回 [Id3Writer.buildTag] 从零建 */
    @Test
    fun `没有原标签时返回 null`() {
        assertNull(Id3Writer.withCoverPreserving(null, cover, "image/jpeg"))
        assertNull(Id3Writer.withCoverPreserving(audio, cover, "image/jpeg"))
        assertNull(Id3Writer.withCoverPreserving(ByteArray(0), cover, "image/jpeg"))
    }

    /**
     * 头部 flags 非 0（unsynchronisation / extended header / footer）时结构特殊，
     * 原样搬帧会写出坏文件 —— 宁可返回 null 退回 [Id3Writer.buildTag]。
     */
    @Test
    fun `原标签带 flags 时返回 null`() {
        val normal = tagOf(3, frameV(3, "TIT2", utf16("晴天")))
        for (flag in intArrayOf(0x80, 0x40, 0x10)) {
            val withFlag = normal.copyOf()
            withFlag[5] = flag.toByte()
            assertNull(
                "flags=0x${flag.toString(16)} 时不该硬搬",
                Id3Writer.withCoverPreserving(withFlag, cover, "image/jpeg")
            )
        }
    }

    // ===== 编辑信息：替换标题/歌手/专辑并保留其余帧 =====

    /**
     * 手动编辑歌名歌手时，**除了标题/歌手/专辑之外的帧一个都不能少** ——
     * 尤其是封面：改个名字不该把 APIC 弄丢。
     */
    @Test
    fun `编辑信息替换标题歌手专辑并保留封面和次要帧`() {
        val original = tagOf(
            3,
            frameV(3, "TIT2", utf16("旧标题")),
            frameV(3, "TPE1", utf16("旧歌手")),
            frameV(3, "TALB", utf16("旧专辑")),
            frameV(3, "TRCK", utf16("3/12")),
            frameV(3, "APIC", apicPayload(cover))
        )

        val rebuilt = Id3Writer.withMetaPreserving(original, "晴天", "周杰伦", "叶惠美")
        assertNotNull(rebuilt)

        val texts = readTextFrames(rebuilt!!)
        assertEquals("晴天", texts["TIT2"])
        assertEquals("周杰伦", texts["TPE1"])
        assertEquals("叶惠美", texts["TALB"])
        assertEquals("3/12", texts["TRCK"])
        assertEquals(3, rebuilt[3].toInt())          // 版本跟随原标签
        // 封面帧还在且能被项目解析器读出来
        assertEquals(1, countFrames(rebuilt, "APIC"))
        assertArrayEquals(cover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
    }

    /** 空串不写帧：只改歌手时不应多出一个空标题帧 */
    @Test
    fun `编辑信息时空字段不写帧`() {
        val original = tagOf(3, frameV(3, "TIT2", utf16("旧标题")))

        val rebuilt = Id3Writer.withMetaPreserving(original, "", "新歌手", "")!!
        val texts = readTextFrames(rebuilt)
        assertFalse("空标题不该写 TIT2", texts.containsKey("TIT2"))
        assertEquals("新歌手", texts["TPE1"])
        assertFalse(texts.containsKey("TALB"))
    }

    /** 原标签是 v2.4 时，新写的文本帧长度也必须是 syncsafe */
    @Test
    fun `编辑信息在 v2_4 上产出 v2_4`() {
        val original = tagOf(4, frameV(4, "TRCK", utf16("1/1")))

        val rebuilt = Id3Writer.withMetaPreserving(original, "晴", "周", "叶")!!
        assertEquals(4, rebuilt[3].toInt())
        val texts = readTextFrames(rebuilt)
        assertEquals("晴", texts["TIT2"])
        assertEquals("周", texts["TPE1"])
        assertEquals("叶", texts["TALB"])
        assertEquals("1/1", texts["TRCK"])
    }

    /** 没有原标签 → 返回 null，由调用方退回 buildTag 从零建 */
    @Test
    fun `编辑信息没有原标签时返回 null`() {
        assertNull(Id3Writer.withMetaPreserving(null, "a", "b", "c"))
        assertNull(Id3Writer.withMetaPreserving(audio, "a", "b", "c"))
    }

    // ===== 测试用的迷你解析器 =====

    /**
     * 把标签里的文本帧解出来。
     *
     * 只解 `T***` 帧，且只处理 UTF-16（我们自己写的就是这个）。
     * 封面走 [AudioTags] 验，这里不重复实现 APIC 解析。
     */
    private fun readTextFrames(tag: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (tag.size < 10) return out
        val bodySize = ((tag[6].toInt() and 0x7F) shl 21) or
            ((tag[7].toInt() and 0x7F) shl 14) or
            ((tag[8].toInt() and 0x7F) shl 7) or
            (tag[9].toInt() and 0x7F)
        var i = 10
        val end = 10 + bodySize
        while (i + 10 <= end && i + 10 <= tag.size) {
            val id = String(tag, i, 4, Charsets.US_ASCII)
            if (id[0] == '\u0000') break
            val n = ((tag[i + 4].toInt() and 0xFF) shl 24) or
                ((tag[i + 5].toInt() and 0xFF) shl 16) or
                ((tag[i + 6].toInt() and 0xFF) shl 8) or
                (tag[i + 7].toInt() and 0xFF)
            if (n <= 0 || i + 10 + n > tag.size) break
            if (id.startsWith("T")) {
                val p = i + 10
                val enc = tag[p].toInt()
                if (enc == 1) {
                    // BOM + UTF-16LE
                    out[id] = String(tag, p + 3, n - 3, Charsets.UTF_16LE)
                }
            }
            i += 10 + n
        }
        return out
    }

    // ===== 造标签用的最小工具 =====

    /** 文本帧的 payload：编码字节 0x01 + BOM + UTF-16LE */
    private fun utf16(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16LE)
        val out = ByteArray(3 + body.size)
        out[0] = 1
        out[1] = 0xFF.toByte()
        out[2] = 0xFE.toByte()
        body.copyInto(out, 3)
        return out
    }

    /** APIC 的 payload：编码 + MIME\0 + 图片类型 + 空描述\0 + 图片数据 */
    private fun apicPayload(image: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + mime.size + 1 + 1 + 1 + image.size)
        var p = 0
        out[p++] = 0                              // 描述用 latin1
        mime.copyInto(out, p)
        p += mime.size
        out[p++] = 0                              // MIME 结束符
        out[p++] = 3                              // 图片类型：封面
        out[p++] = 0                              // 空描述的结束符
        image.copyInto(out, p)
        return out
    }

    /**
     * 手工造一个帧（含 10 字节帧头）。
     * [version] 决定长度字段的编码 —— 这正是 v2.3 / v2.4 唯一的差别。
     */
    private fun frameV(version: Int, id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        for (i in 0 until 4) out[i] = id[i].code.toByte()
        val n = payload.size
        if (version >= 4) {
            out[4] = ((n ushr 21) and 0x7F).toByte()
            out[5] = ((n ushr 14) and 0x7F).toByte()
            out[6] = ((n ushr 7) and 0x7F).toByte()
            out[7] = (n and 0x7F).toByte()
        } else {
            out[4] = ((n ushr 24) and 0xFF).toByte()
            out[5] = ((n ushr 16) and 0xFF).toByte()
            out[6] = ((n ushr 8) and 0xFF).toByte()
            out[7] = (n and 0xFF).toByte()
        }
        payload.copyInto(out, 10)
        return out
    }

    /** 把帧拼成一个完整标签（10 字节头 + 帧区） */
    private fun tagOf(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.sumOf { it.size }
        val out = ByteArray(10 + body)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = version.toByte()
        out[4] = 0
        out[5] = 0
        out[6] = ((body ushr 21) and 0x7F).toByte()
        out[7] = ((body ushr 14) and 0x7F).toByte()
        out[8] = ((body ushr 7) and 0x7F).toByte()
        out[9] = (body and 0x7F).toByte()
        var p = 10
        for (f in frames) {
            f.copyInto(out, p)
            p += f.size
        }
        return out
    }

    /** 数某个帧出现了几次。按 v2.3 的大端长度扫（只给 v2.3 的用例用） */
    private fun countFrames(tag: ByteArray, id: String): Int {
        var count = 0
        var i = 10
        val end = 10 + (((tag[6].toInt() and 0x7F) shl 21) or
            ((tag[7].toInt() and 0x7F) shl 14) or
            ((tag[8].toInt() and 0x7F) shl 7) or
            (tag[9].toInt() and 0x7F))
        while (i + 10 <= end && i + 10 <= tag.size) {
            val fid = String(tag, i, 4, Charsets.US_ASCII)
            if (fid[0] == '\u0000') break
            val n = ((tag[i + 4].toInt() and 0xFF) shl 24) or
                ((tag[i + 5].toInt() and 0xFF) shl 16) or
                ((tag[i + 6].toInt() and 0xFF) shl 8) or
                (tag[i + 7].toInt() and 0xFF)
            if (n <= 0 || i + 10 + n > tag.size) break
            if (fid == id) count++
            i += 10 + n
        }
        return count
    }
}
