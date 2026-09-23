package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `歌词写进去之后 AudioTags 能读出来`() {
        // 同样用项目自己的解析器当裁判（AudioTags 支持 USLT，见它的格式表）
        val lrc = "[00:01.00]第一行\n[00:05.50]第二行\n[00:09.00]END"
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", lyrics = lrc)
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(tagged(tag))))
    }

    @Test
    fun `封面和歌词可以同时存在且互不干扰`() {
        val lrc = "[00:02.00]词"
        val tag = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", cover, "image/jpeg", lyrics = lrc)
        val file = tagged(tag)
        assertArrayEquals(cover, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `歌词为空时不写 USLT 帧`() {
        val file = tagged(Id3Writer.buildTag("晴天", "周杰伦", "叶惠美", lyrics = "   "))
        assertEquals(null, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `歌词里的换行和方括号原样保留`() {
        // LRC 的时间戳是给播放器做滚动高亮用的，一个字符都不能动
        val lrc = "[offset:+500]\n[00:00.00]\n[00:01.234][00:03.456]重复句"
        val tag = Id3Writer.buildTag("A", "B", "C", lyrics = lrc)
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(tagged(tag))))
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
}


/* ============================================================
 * rebuildTag：写回文件时「保留原有帧，只换封面 / 歌词」
 * ============================================================ */

class Id3WriterRebuildTest {

    private val oldCover = byteArrayOf(0x11, 0x22, 0x33)
    private val newCover = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
    private val lrc = "[00:01.00]词"

    private fun framesOf(tag: ByteArray) = Id3Writer.parseFrames(tag)!!.associate { it.id to it.payload }

    private fun utf16(text: String) =
        byteArrayOf(0x01, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    @Test
    fun `重写后原有文本帧原样保留`() {
        val existing = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美")
        val rebuilt = Id3Writer.rebuildTag(existing, newCover, null, lrc)!!
        val frames = framesOf(rebuilt)
        assertArrayEquals(utf16("晴天"), frames["TIT2"])
        assertArrayEquals(utf16("周杰伦"), frames["TPE1"])
        assertArrayEquals(utf16("叶惠美"), frames["TALB"])
    }

    @Test
    fun `重写会写入新封面和歌词`() {
        val existing = Id3Writer.buildTag("晴天", "周杰伦", "叶惠美")
        val rebuilt = Id3Writer.rebuildTag(existing, newCover, null, lrc)!!
        val file = rebuilt + ByteArray(64) { 0x55 }
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(file)))
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(file)))
    }

    @Test
    fun `重写会替换旧封面而不是叠加`() {
        val existing = Id3Writer.buildTag("A", "B", "C", oldCover, "image/jpeg")
        val rebuilt = Id3Writer.rebuildTag(existing, newCover, null, null)!!
        // 整个文件里旧封面字节不应该再出现
        val s = rebuilt.joinToString(",") { it.toString() }
        assertFalse("旧封面还在标签里", s.contains(oldCover.joinToString(",")))
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
    }

    @Test
    fun `重写会替换旧歌词`() {
        val existing = Id3Writer.buildTag("A", "B", "C", lyrics = "[00:09.00]旧词")
        val rebuilt = Id3Writer.rebuildTag(existing, null, null, "[00:01.00]新词")!!
        assertEquals("[00:01.00]新词", AudioTags.lyricsFrom(ByteArrayInputStream(rebuilt)))
        assertFalse(AudioTags.lyricsFrom(ByteArrayInputStream(rebuilt))!!.contains("旧词"))
    }

    /**
     * 写回文件时**只补一样**是常态：文件里已经有歌词、只缺封面（或反过来）。
     * 没在补的那种帧必须原样留着 —— 删掉它是用户数据的净损失，
     * 而且文件表面一切正常，只有打开歌词页才会发现空了。
     * （实测在真机写回时踩到：写入后文件反而变小了 78 字节。）
     */
    @Test
    fun `只补封面时，文件里原有的歌词必须留着`() {
        val existing = Id3Writer.buildTag("A", "B", "C", oldCover, "image/jpeg", lyrics = "[00:09.00]原有歌词")
        val rebuilt = Id3Writer.rebuildTag(existing, newCover, null, null)!!
        assertEquals("[00:09.00]原有歌词", AudioTags.lyricsFrom(ByteArrayInputStream(rebuilt)))
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
    }

    @Test
    fun `只补歌词时，文件里原有的封面必须留着`() {
        val existing = Id3Writer.buildTag("A", "B", "C", oldCover, "image/jpeg")
        val rebuilt = Id3Writer.rebuildTag(existing, null, null, lrc)!!
        assertArrayEquals(oldCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(rebuilt)))
    }

    @Test
    fun `v2_4 的 UTF_8 文本帧重发为 v2_3 认的编码`() {
        // 手工拼一个 v2.4 标签：帧长是 syncsafe，文本帧编码字节是 3（UTF-8）
        val text = "晴天"
        val payload = byteArrayOf(0x03) + text.toByteArray(Charsets.UTF_8)
        val frame = "TIT2".toByteArray(Charsets.ISO_8859_1) + syncSafe(payload.size) +
            byteArrayOf(0, 0) + payload
        val body = frame
        val tag = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + syncSafe(body.size) + body

        val rebuilt = Id3Writer.rebuildTag(tag, newCover, null, null)!!
        val frames = Id3Writer.parseFrames(rebuilt)!!
        val tit2 = frames.first { it.id == "TIT2" }
        // 编码字节变成 0x01（UTF-16 + BOM），内容不变
        assertArrayEquals(utf16(text), tit2.payload)
    }

    @Test
    fun `读不懂的标签返回 null 而不是硬写`() {
        // 有 ID3 头但帧区是垃圾 —— 宁可跳过这个文件，也不能把原标签剥了
        val garbage = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) +
            byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + ByteArray(32) { 0x55 }
        assertNull(Id3Writer.rebuildTag(garbage, newCover, null, lrc))
    }

    @Test
    fun `v2_2 和带 unsync 位的标签都拒绝重写`() {
        val body = "TIT2".toByteArray(Charsets.ISO_8859_1) + be32(5) + byteArrayOf(0, 0) + byteArrayOf(0, 1, 2, 3, 4)
        val v22 = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(2, 0, 0) + syncSafe(body.size) + body
        assertNull(Id3Writer.rebuildTag(v22, newCover, null, null))
        val unsync = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0x80.toByte()) +
            syncSafe(body.size) + body
        assertNull(Id3Writer.rebuildTag(unsync, newCover, null, null))
    }

    @Test
    fun `本来没有标签时从零写`() {
        val rebuilt = Id3Writer.rebuildTag(null, newCover, null, lrc)!!
        assertArrayEquals(newCover, AudioTags.coverArtFrom(ByteArrayInputStream(rebuilt)))
        assertEquals(lrc, AudioTags.lyricsFrom(ByteArrayInputStream(rebuilt)))
        // 没有可保留的元数据 → 不该有文本帧
        assertTrue(Id3Writer.parseFrames(rebuilt)!!.none { it.id == "TIT2" })
    }

    private fun be32(n: Int) = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(), ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
    )

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
    )
}
