package com.sonance.player.data

import java.io.InputStream
import java.io.OutputStream

/**
 * 给下载下来的音频写 ID3v2 标签。
 *
 * ## 为什么必须自己写
 *
 * 实测网易云的取流接口返回的 MP3 **自带的标签只有一个 35 字节的 `TSSE` 帧**
 * （内容是 `Lavf58.76.100`，编码器信息），**没有标题、歌手、专辑、封面**。
 * 不写标签的话，这些文件在系统音乐库里就是一堆"文件名当标题、歌手未知"的条目。
 *
 * ## 为什么是 v2.3 而不是 v2.4
 *
 * v2.4 规定**帧长度也用 syncsafe 编码**，但现实中大量文件写的是普通大端 ——
 * 解析器只能靠猜，Android 的 MediaScanner 在这一点上历来不稳。
 * v2.3 的帧长度是普通大端、没有歧义，兼容性最好。
 *
 * ## 为什么文本用 UTF-16 而不是 UTF-8
 *
 * v2.3 规范里的编码字节只有 `0x00`（latin1）和 `0x01`（UTF-16 带 BOM），
 * **没有 UTF-8**（那是 v2.4 才加的）。写 UTF-8 虽然在很多播放器上也能显示，
 * 但不符合规范，MediaScanner 可能读成乱码 —— 中文歌名会全变成问号。
 *
 * ## 为什么要剥掉原有标签
 *
 * 一个文件开头**只能有一个** ID3v2 标签。直接前置我们的标签、
 * 把原来那 35 字节留在中间，会产生一个"看起来能播、但标签区结构非法"的文件。
 */
internal object Id3Writer {

    /** ID3v2.3 */
    private const val VERSION_MAJOR: Byte = 0x03

    /** 编码字节：UTF-16 带 BOM（v2.3 里唯一能表达中文的编码） */
    private const val ENC_UTF16_BOM: Byte = 0x01

    /** APIC 的图片类型：3 = 封面（正面） */
    private const val PIC_TYPE_FRONT_COVER: Byte = 0x03

    private const val DEFAULT_MIME = "image/jpeg"

    /**
     * 组装一个完整的 ID3v2.3 标签（含 10 字节头）。
     *
     * @param cover 封面原始字节（可以是 JPEG 或 PNG）。传 null 就不写 APIC 帧
     * @param lyrics 歌词文本（LRC 原样存）。传 null / 空白就不写 USLT 帧
     */
    internal fun buildTag(
        title: String,
        artist: String,
        album: String,
        cover: ByteArray? = null,
        coverMime: String? = null,
        lyrics: String? = null
    ): ByteArray {
        val frames = ArrayList<ByteArray>(5)
        if (title.isNotBlank()) frames += textFrame("TIT2", title)
        if (artist.isNotBlank()) frames += textFrame("TPE1", artist)
        if (album.isNotBlank()) frames += textFrame("TALB", album)
        // 封面为空就整个不写 APIC 帧 —— 写一个 0 字节的图会让解析器拿到空数组，
        // 上层会当成"有封面但解不出来"
        if (cover != null && cover.isNotEmpty()) {
            frames += apicFrame(cover, coverMime?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME)
        }
        // 歌词同理：空字符串不写帧。歌词文本按**原样**存（LRC 的时间轴要留着，
        // 播放器就是靠 `[mm:ss.xx]` 做滚动高亮的）
        if (!lyrics.isNullOrBlank()) frames += lyricsFrame(lyrics)

        val bodySize = frames.sumOf { it.size }
        val out = ByteArray(10 + bodySize)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = VERSION_MAJOR
        out[4] = 0x00          // revision
        out[5] = 0x00          // flags：不用 unsynchronisation / extended header
        writeSyncSafe(out, 6, bodySize)
        var p = 10
        for (f in frames) {
            f.copyInto(out, p)
            p += f.size
        }
        return out
    }

    /**
     * 文件开头那个 ID3v2 标签**一共占多少字节**（含 10 字节头）。
     *
     * 没有标签、或数据不够判断时返回 0。
     */
    internal fun existingTagSize(head: ByteArray): Int {
        if (head.size < 10) return 0
        if (head[0] != 'I'.code.toByte() ||
            head[1] != 'D'.code.toByte() ||
            head[2] != '3'.code.toByte()
        ) {
            return 0
        }
        // 标签长度是 **syncsafe**（每字节只用低 7 位）—— 这是 ID3v2 头部的规定，
        // 和 v2.3 的帧长度（普通大端）不一样，两者最容易搞混
        val size = ((head[6].toInt() and 0x7F) shl 21) or
            ((head[7].toInt() and 0x7F) shl 14) or
            ((head[8].toInt() and 0x7F) shl 7) or
            (head[9].toInt() and 0x7F)
        // flags 的 bit4（0x10）表示标签后面跟一个 10 字节 footer
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /**
     * 把 [source] 的音频数据（**跳过原有的 ID3v2 标签**）前面接上 [tag]，写进 [dest]。
     *
     * 全程流式，不会把整首歌读进内存 —— 一首 4 分钟的歌是 4MB 上下，
     * 同时下载几首时全塞进内存没必要。
     */
    internal fun copyWithTag(source: InputStream, dest: OutputStream, tag: ByteArray) {
        dest.write(tag)

        // 先读 10 字节判断有没有旧标签
        val head = ByteArray(10)
        var filled = 0
        while (filled < 10) {
            val n = source.read(head, filled, 10 - filled)
            if (n < 0) break
            filled += n
        }
        if (filled == 0) return

        val skipTotal = if (filled == 10) existingTagSize(head) else 0
        // 已经读进来的这 filled 字节里，属于旧标签的部分要丢掉
        val dropFromHead = minOf(skipTotal, filled)
        if (filled > dropFromHead) dest.write(head, dropFromHead, filled - dropFromHead)

        // 旧标签还没读到的剩余部分用 skip 丢掉
        var rest = (skipTotal - dropFromHead).toLong()
        while (rest > 0) {
            val s = source.skip(rest)
            if (s <= 0) break
            rest -= s
        }

        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            dest.write(buf, 0, n)
        }
    }

    // ===== 内部构造 =====

    /** 文本帧：编码字节 + BOM + UTF-16LE */
    private fun textFrame(id: String, text: String): ByteArray = frame(id, utf16(text))

    /**
     * USLT（非同步歌词）帧：
     * `编码(1) + 语言(3, ASCII) + 描述(按编码, 以 \0 结尾) + 歌词正文(按编码)`
     *
     * 三个决定：
     * - **正文原样存 LRC**（`[mm:ss.xx]词`）。放纯文本的话时间轴就丢了，
     *   Poweramp / Musicolet / foobar2000 这些读 USLT 的播放器都是自己解析 LRC
     *   来做滚动高亮的，必须把标签留着。
     * - **描述留空**（只写 BOM + 结束符）。写"歌词"这类描述，有些解析器会把它
     *   连同正文一起显示出来。
     * - **语言用 `XXX`**（ISO-639-2 里的"未定义"）。用 `chi` 之类的具体值并不更准，
     *   中文歌里夹英文、纯英文歌都会对不上。
     */
    private fun lyricsFrame(lyrics: String): ByteArray {
        // 正文用 UTF-16LE + BOM（和文本帧同一种编码，中文才不会是问号）
        val body = lyrics.toByteArray(Charsets.UTF_16LE)
        val payload = ByteArray(1 + 3 + 4 + 2 + body.size)
        var p = 0
        payload[p++] = ENC_UTF16_BOM
        payload[p++] = 'X'.code.toByte()
        payload[p++] = 'X'.code.toByte()
        payload[p++] = 'X'.code.toByte()
        // 空描述：BOM + UTF-16 的结束符（两个 0 字节）
        payload[p++] = 0xFF.toByte()
        payload[p++] = 0xFE.toByte()
        payload[p++] = 0x00
        payload[p++] = 0x00
        // 正文自己的 BOM
        payload[p++] = 0xFF.toByte()
        payload[p++] = 0xFE.toByte()
        body.copyInto(payload, p)
        return frame("USLT", payload)
    }

    /**
     * APIC 帧：
     * `编码(1) + MIME(ASCII, \0 结尾) + 图片类型(1) + 描述(按编码, \0 结尾) + 图片数据`
     */
    private fun apicFrame(cover: ByteArray, mime: String): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + mimeBytes.size + 1 + 1 + 1 + cover.size)
        var p = 0
        payload[p++] = 0x00                       // 描述用 latin1（描述本身为空）
        mimeBytes.copyInto(payload, p)
        p += mimeBytes.size
        payload[p++] = 0x00                       // MIME 结束符
        payload[p++] = PIC_TYPE_FRONT_COVER
        payload[p++] = 0x00                       // 空描述的结束符
        cover.copyInto(payload, p)
        return frame("APIC", payload)
    }

    private fun utf16(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16LE)
        val out = ByteArray(1 + 2 + body.size)
        out[0] = ENC_UTF16_BOM
        out[1] = 0xFF.toByte()   // BOM 小端
        out[2] = 0xFE.toByte()
        body.copyInto(out, 3)
        return out
    }

    /** 帧头 10 字节：ID(4) + 长度(4，**普通大端**) + flags(2) */
    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        for (i in 0 until 4) out[i] = id[i].code.toByte()
        val n = payload.size
        out[4] = ((n ushr 24) and 0xFF).toByte()
        out[5] = ((n ushr 16) and 0xFF).toByte()
        out[6] = ((n ushr 8) and 0xFF).toByte()
        out[7] = (n and 0xFF).toByte()
        out[8] = 0x00
        out[9] = 0x00
        payload.copyInto(out, 10)
        return out
    }

    /** 标签长度：syncsafe，每字节只用低 7 位 */
    private fun writeSyncSafe(out: ByteArray, at: Int, value: Int) {
        out[at] = ((value ushr 21) and 0x7F).toByte()
        out[at + 1] = ((value ushr 14) and 0x7F).toByte()
        out[at + 2] = ((value ushr 7) and 0x7F).toByte()
        out[at + 3] = (value and 0x7F).toByte()
    }
}
