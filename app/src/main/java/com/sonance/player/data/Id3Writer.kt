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
    private const val VERSION_MAJOR: Int = 0x03

    /** 编码字节：UTF-16 带 BOM（v2.3 里唯一能表达中文的编码） */
    private const val ENC_UTF16_BOM: Byte = 0x01

    /** APIC 的图片类型：3 = 封面（正面） */
    private const val PIC_TYPE_FRONT_COVER: Byte = 0x03

    private const val DEFAULT_MIME = "image/jpeg"

    /**
     * 组装一个完整的 ID3v2.3 标签（含 10 字节头）。
     *
     * @param cover 封面原始字节（可以是 JPEG 或 PNG）。传 null 就不写 APIC 帧
     */
    internal fun buildTag(
        title: String,
        artist: String,
        album: String,
        cover: ByteArray? = null,
        coverMime: String? = null
    ): ByteArray {
        val frames = ArrayList<ByteArray>(4)
        if (title.isNotBlank()) frames += textFrame("TIT2", title)
        if (artist.isNotBlank()) frames += textFrame("TPE1", artist)
        if (album.isNotBlank()) frames += textFrame("TALB", album)
        // 封面为空就整个不写 APIC 帧 —— 写一个 0 字节的图会让解析器拿到空数组，
        // 上层会当成"有封面但解不出来"
        if (cover != null && cover.isNotEmpty()) {
            frames += apicFrame(cover, coverMime?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME)
        }

        return assemble(VERSION_MAJOR, frames)
    }

    /**
     * 在**保留原有帧**的前提下把封面换成 [cover]。
     *
     * 为什么不直接用 [buildTag]：那个函数是从零建标签，只写标题 / 歌手 / 专辑 / 封面。
     * 用在下载的歌上没问题（取流接口给的 mp3 本来就只有 35 字节的 `TSSE`），
     * 但用在用户自己的曲库上会把 `TRCK`（音轨号）、`TCON`（流派）、年份、`COMM`（注释）
     * 这些帧**全部抹掉** —— 换一张封面不该有这种副作用。
     *
     * 规则：
     * - 除 `APIC` 外的帧**逐字节原样搬过去**，连编码字节都不动
     * - 所有 `APIC` 帧都丢掉，只留新加的这一张（留多张会让部分播放器显示错的那张）
     * - 版本跟随原标签：v2.3 → v2.3、v2.4 → v2.4。**不能混** ——
     *   帧长度字段在 v2.3 是普通大端、v2.4 是 syncsafe，混了整套帧都会解析歪
     *
     * @param existing 原文件开头的完整 ID3v2 标签（含 10 字节头）
     * @return 新的完整标签；[existing] 缺失或结构太特殊（带 flags）时返回 null，
     *         由调用方决定退回 [buildTag]
     */
    internal fun withCoverPreserving(
        existing: ByteArray?,
        cover: ByteArray,
        mime: String
    ): ByteArray? {
        val parsed = parseFrames(existing) ?: return null
        val frames = parsed.frames
            .filterNot { it.id == "APIC" }
            .map { it.bytes }
            .toMutableList()
        frames += apicFrame(cover, mime, parsed.version)
        return assemble(parsed.version, frames)
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
        val size = syncSafe(head, 6)
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

    /** 一个原样搬过来的帧：[id] 是 4 字节帧标识，[bytes] 含 10 字节帧头 */
    private class RawFrame(val id: String, val bytes: ByteArray)

    private class ParsedFrames(val version: Int, val frames: List<RawFrame>)

    /**
     * 把原标签拆成帧列表。
     *
     * 返回 null 的三种情况（都由调用方退回 [buildTag]）：
     * - 没有标签、或不是 ID3v2、或版本不是 2.3 / 2.4
     * - 头部 flags 非 0：unsynchronisation 会把帧体按 0xFF 去同步，
     *   extended header 前面多一段结构，footer 会在帧区后面多 10 字节 ——
     *   三种都要额外解析，而现实中几乎见不到，不值得为换张封面去啃
     * - 帧长度字段越界（文件被截断 / 结构损坏）
     */
    private fun parseFrames(tag: ByteArray?): ParsedFrames? {
        if (tag == null || tag.size < 10) return null
        if (tag[0] != 'I'.code.toByte() ||
            tag[1] != 'D'.code.toByte() ||
            tag[2] != '3'.code.toByte()
        ) {
            return null
        }
        val version = tag[3].toInt() and 0xFF
        if (version != 3 && version != 4) return null
        if (tag[5].toInt() and 0xFF != 0) return null

        val end = minOf(10 + syncSafe(tag, 6), tag.size)
        var p = 10
        val frames = ArrayList<RawFrame>()
        while (p + 10 <= end) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break            // 帧区结束，后面是 padding
            val size = if (version >= 4) syncSafe(tag, p + 4) else beInt(tag, p + 4)
            if (size < 0 || p + 10 + size > end) return null
            frames += RawFrame(id, tag.copyOfRange(p, p + 10 + size))
            p += 10 + size
        }
        return ParsedFrames(version, frames)
    }

    /** 拼出完整标签（10 字节头 + 帧区）。帧本身已含各自的帧头 */
    private fun assemble(version: Int, frames: List<ByteArray>): ByteArray {
        val bodySize = frames.sumOf { it.size }
        val out = ByteArray(10 + bodySize)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = version.toByte()
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

    /** 文本帧：编码字节 + BOM + UTF-16LE */
    private fun textFrame(id: String, text: String): ByteArray = frame(id, utf16(text))

    /**
     * APIC 帧：
     * `编码(1) + MIME(ASCII, \0 结尾) + 图片类型(1) + 描述(按编码, \0 结尾) + 图片数据`
     */
    private fun apicFrame(
        cover: ByteArray,
        mime: String,
        version: Int = VERSION_MAJOR
    ): ByteArray {
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
        return frame("APIC", payload, version)
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

    /**
     * 帧头 10 字节：ID(4) + 长度(4) + flags(2)。
     *
     * 长度字段的编码随版本变：v2.3 是**普通大端**，v2.4 是 syncsafe —— 见类注释。
     */
    private fun frame(id: String, payload: ByteArray, version: Int = VERSION_MAJOR): ByteArray {
        val out = ByteArray(10 + payload.size)
        for (i in 0 until 4) out[i] = id[i].code.toByte()
        if (version >= 4) {
            writeSyncSafe(out, 4, payload.size)
        } else {
            val n = payload.size
            out[4] = ((n ushr 24) and 0xFF).toByte()
            out[5] = ((n ushr 16) and 0xFF).toByte()
            out[6] = ((n ushr 8) and 0xFF).toByte()
            out[7] = (n and 0xFF).toByte()
        }
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

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
