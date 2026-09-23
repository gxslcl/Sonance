package com.sonance.player.data

import android.content.Context
import android.net.Uri
import java.io.InputStream

/**
 * 直接从音频文件里读**内嵌的封面与歌词**，不依赖 MediaStore，也不需要文件系统权限。
 *
 * 为什么需要它：
 * - `MediaStore.Audio.Albums` 的专辑缩略图库并不总是把每个文件的内嵌封面都提取出来
 *   （尤其是通过 SAF / 非常规目录放进去的文件），于是大量曲目只能落到占位图。
 * - Android 13+ 拿 `READ_MEDIA_AUDIO` 读不到同目录的 `.lrc` 侧车文件（它不是音频），
 *   但**内嵌**在音频文件里的歌词随便读。
 *
 * 覆盖的容器与字段：
 * | 容器 | 封面 | 歌词 |
 * |---|---|---|
 * | MP3 / ID3v2.3 / 2.4 | `APIC` | `USLT` |
 * | FLAC | `PICTURE` (block 6) | `VORBIS_COMMENT` (block 4) 的 `LYRICS` / `UNSYNCEDLYRICS` |
 * | M4A / MP4 | `moov.udta.meta.ilst.covr` | `moov.udta.meta.ilst.©lyr` |
 *
 * 全部按 tag 结构 skip，不会把整个音频读进内存。
 * 解析逻辑由 `AudioTagsTest` 用人工构造的最小容器覆盖（真机上凑不齐三种格式的样本）。
 */
object AudioTags {

    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 512 * 1024
    private const val MAX_PAYLOAD = MAX_IMAGE_BYTES + MAX_TEXT_BYTES

    /* ============================================================
     * 对外 API
     * ============================================================ */

    fun readCoverArt(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            scan(it, wantCover = true, wantLyrics = false).cover
        }
    }.getOrNull()

    fun readLyrics(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            scan(it, wantCover = false, wantLyrics = true).lyrics
        }
    }.getOrNull()

    /**
     * 一趟同时取**封面和歌词**（「把缓存写回文件」探测候选用）。
     *
     * 以前只有 `readCoverArt` / `readLyrics` 两个入口，各开一遍文件；
     * 而且它们各自只认一种帧、找到就提前返回。这里两个都要，
     * 所以 `scan` 也得改成「想要的都找到了才返回」（见里面的注释）。
     *
     * @return 封面字节（可能为 null）和歌词文本（可能为 null）
     */
    fun readTags(context: Context, uri: Uri): Pair<ByteArray?, String?> {
        val found = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                scan(it, wantCover = true, wantLyrics = true)
            }
        }.getOrNull() ?: return null to null
        return found?.cover to found?.lyrics
    }

    /**
     * 纯流版本的入口，**只给单元测试用**。
     * tag 解析逻辑全在这里，真机跑不了的时候靠它兜住正确性。
     */
    @androidx.annotation.VisibleForTesting
    internal fun coverArtFrom(input: InputStream): ByteArray? =
        scan(input, wantCover = true, wantLyrics = false).cover

    @androidx.annotation.VisibleForTesting
    internal fun lyricsFrom(input: InputStream): String? =
        scan(input, wantCover = false, wantLyrics = true).lyrics

    /** 纯流版本：一趟同时取两样（给「写回文件」的测试用，规则见 [readTags]） */
    @androidx.annotation.VisibleForTesting
    internal fun tagsFrom(input: InputStream): Pair<ByteArray?, String?> =
        scan(input, wantCover = true, wantLyrics = true).let { it.cover to it.lyrics }

    /* ============================================================
     * 容器分派
     * ============================================================ */

    private class Found {
        var cover: ByteArray? = null
        var lyrics: String? = null
    }

    /**
     * 先读 4 字节魔数，再按容器各取所需 —— **不要一次读 12 字节**：
     * ID3v2 的头只有 10 字节，多读的 2 字节会把后面的帧头整体挪位，
     * `APIC` / `USLT` 就永远匹配不上（这是修过的一个真实 bug）。
     */
    private fun scan(input: InputStream, wantCover: Boolean, wantLyrics: Boolean): Found {
        val found = Found()
        val magic = ByteArray(4)
        if (!readFully(input, magic, 4)) return found

        when {
            // "ID3"：还差 6 字节补完 10 字节头
            magic[0] == 'I'.code.toByte() &&
                magic[1] == 'D'.code.toByte() &&
                magic[2] == '3'.code.toByte() -> {
                val rest = ByteArray(6)
                if (!readFully(input, rest, 6)) return found
                scanId3v2(input, magic, rest, found, wantCover, wantLyrics)
            }

            // "fLaC"：还差 第一个 block header(4) + 该 block 的前 4 字节数据
            magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte() -> {
                val more = ByteArray(8)
                if (!readFully(input, more, 8)) return found
                scanFlac(input, more, found, wantCover, wantLyrics)
            }

            // MP4 家族：magic 是第一个 atom 的 size，还差 type(4) + 4 字节
            else -> {
                val more = ByteArray(8)
                if (!readFully(input, more, 8)) return found
                scanMp4(input, magic, more, found, wantCover, wantLyrics)
            }
        }
        return found
    }

    /* ============================================================
     * ID3v2 (MP3)
     * ============================================================ */

    /**
     * @param magic 头 4 字节（"ID3" + major）
     * @param rest  头后 6 字节（revision + flags + syncsafe size）。
     *              进入时流位置正好在第 10 字节，也就是第一个帧头之前。
     */
    private fun scanId3v2(
        input: InputStream,
        magic: ByteArray,
        rest: ByteArray,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        val major = magic[3].toInt() and 0xFF
        val flags = rest[1].toInt() and 0xFF
        val tagSize = syncSafe(rest, 2)

        var remaining = tagSize

        if (flags and 0x40 != 0) {
            // extended header：先读 4 字节长度字段，再按版本补齐
            val ext = ByteArray(4)
            if (!readFully(input, ext, 4)) return
            remaining -= 4
            val declared = if (major >= 4) syncSafe(ext, 0) else beInt(ext, 0)
            // v2.3 的长度字段**不含自己**，v2.4 的含
            val extra = if (major >= 4) declared - 4L else declared.toLong()
            if (extra > 0) {
                if (!skipFully(input, extra)) return
                remaining -= extra.toInt()
            }
        }

        var scanned = 0
        while (remaining > 10 && scanned < 2 * 1024 * 1024) {
            val fh = ByteArray(10)
            if (!readFully(input, fh, 10)) return
            remaining -= 10
            scanned += 10

            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') return                      // 帧区结束（padding）

            val size = if (major >= 4) syncSafe(fh, 4) else beInt(fh, 4)
            if (size <= 0 || size > MAX_PAYLOAD) {
                if (!skipFully(input, size.toLong())) return
                remaining -= size
                continue
            }

            val wanted = (wantCover && id == "APIC") || (wantLyrics && id == "USLT")
            if (wanted) {
                val body = ByteArray(size)
                if (!readFully(input, body, size)) return
                if (id == "APIC") {
                    val img = extractApicImage(body)
                    if (img != null) {
                        found.cover = img
                        // 两个都要时要继续扫 —— 封面帧通常排在最前，提前 return
                        // 会把后面的歌词帧丢掉（写回文件时要一次拿齐两样）
                        if (!wantLyrics || found.lyrics != null) return
                    }
                } else {
                    val t = extractUsltText(body)
                    if (!t.isNullOrBlank()) {
                        found.lyrics = t
                        if (!wantCover || found.cover != null) return
                    }
                }
            } else if (!skipFully(input, size.toLong())) {
                return
            }
            remaining -= size
        }
    }

    /** APIC = encoding(1) + mime\0 + pictureType(1) + description\0 + image */
    private fun extractApicImage(body: ByteArray): ByteArray? {
        if (body.size < 4) return null
        val enc = body[0].toInt() and 0xFF
        var i = 1
        while (i < body.size && body[i] != 0.toByte()) i++
        if (i >= body.size) return null
        i++                        // 跳过 mime 终止符
        if (i >= body.size) return null
        i++                        // picture type
        i = skipEncodedString(body, i, enc)
        if (i < 0 || i >= body.size) return null
        val len = body.size - i
        if (len <= 0 || len > MAX_IMAGE_BYTES) return null
        return body.copyOfRange(i, body.size)
    }

    /** USLT = encoding(1) + language(3) + contentDescriptor(enc-terminated) + lyrics text */
    private fun extractUsltText(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt() and 0xFF
        val i = skipEncodedString(body, 4, enc)               // 跳过 language + 描述
        if (i < 0 || i >= body.size) return null
        return decodeText(body, i, body.size - i, enc)
    }

    /** 跳过以 0 / 0x0000 结尾的文本（按编码决定终止符宽度） */
    private fun skipEncodedString(b: ByteArray, start: Int, enc: Int): Int {
        var i = start
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size && !(b[i] == 0.toByte() && b[i + 1] == 0.toByte())) i += 2
            return i + 2
        }
        while (i < b.size && b[i] != 0.toByte()) i++
        return i + 1
    }

    private fun decodeText(b: ByteArray, offset: Int, len: Int, enc: Int): String? {
        if (len <= 0) return null
        return runCatching {
            when (enc) {
                1 -> String(b, offset, len, Charsets.UTF_16)       // 带 BOM
                2 -> String(b, offset, len, Charsets.UTF_16BE)
                3 -> String(b, offset, len, Charsets.UTF_8)
                else -> String(b, offset, len, Charsets.ISO_8859_1)
            }
        }.getOrNull()
    }

    /* ============================================================
     * FLAC
     * ============================================================ */

    /**
     * @param more 第一个 metadata block 的 header(4) + 该 block 数据的前 4 字节
     */
    private fun scanFlac(
        input: InputStream,
        more: ByteArray,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        var last = (more[0].toInt() and 0x80) != 0
        var type = more[0].toInt() and 0x7F
        var len = ((more[1].toInt() and 0xFF) shl 16) or
            ((more[2].toInt() and 0xFF) shl 8) or
            (more[3].toInt() and 0xFF)
        // 第一个 block 的数据已预读 4 字节（block 本身更短时按实际算）
        var preloaded = minOf(4, len)
        var guard = 0

        while (guard++ < 64) {
            val wanted = (wantCover && type == 6) || (wantLyrics && type == 4)
            if (wanted && len in 8..MAX_PAYLOAD) {
                val body = ByteArray(len)
                if (preloaded > 0) {
                    val n = minOf(preloaded, len)
                    System.arraycopy(more, 4, body, 0, n)
                    if (len > n && !readFully(input, body, len, n)) return
                } else if (!readFully(input, body, len)) {
                    return
                }
                if (type == 6) {
                    val img = extractFlacPicture(body)
                    if (img != null) {
                        found.cover = img
                        return
                    }
                } else {
                    val t = extractVorbisLyrics(body)
                    if (!t.isNullOrBlank()) {
                        found.lyrics = t
                        return
                    }
                }
                preloaded = 0
            } else {
                val toSkip = len - preloaded
                if (toSkip > 0 && !skipFully(input, toSkip.toLong())) return
                preloaded = 0
            }

            if (last) return
            val hdr = ByteArray(4)
            if (!readFully(input, hdr, 4)) return
            last = (hdr[0].toInt() and 0x80) != 0
            type = hdr[0].toInt() and 0x7F
            len = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
        }
    }

    /** PICTURE = type(4) mimeLen(4) mime descLen(4) desc w h d c (各4) dataLen(4) data */
    private fun extractFlacPicture(b: ByteArray): ByteArray? {
        var i = 4
        if (i + 4 > b.size) return null
        i += 4 + beInt(b, i)                    // 跳过 mime
        if (i + 4 > b.size) return null
        i += 4 + beInt(b, i)                    // 跳过 description
        i += 16                                 // width/height/depth/colors
        if (i + 4 > b.size) return null
        val dataLen = beInt(b, i)
        i += 4
        if (dataLen <= 0 || i + dataLen > b.size || dataLen > MAX_IMAGE_BYTES) return null
        return b.copyOfRange(i, i + dataLen)
    }

    /** VORBIS_COMMENT = vendorLen(4 LE) vendor count(4 LE) [len(4 LE) "KEY=value"]... */
    private fun extractVorbisLyrics(b: ByteArray): String? {
        var i = 0
        if (i + 4 > b.size) return null
        val vendorLen = leInt(b, i)
        i += 4 + vendorLen
        if (i + 4 > b.size) return null
        val count = leInt(b, i)
        i += 4
        if (count < 0 || count > 4096) return null

        var fallback: String? = null
        var c = 0
        while (c < count && i + 4 <= b.size) {
            val len = leInt(b, i)
            i += 4
            if (len < 0 || i + len > b.size) break
            val entry = runCatching { String(b, i, len, Charsets.UTF_8) }.getOrNull()
            i += len
            if (entry != null) {
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).trim().uppercase()
                    val value = entry.substring(eq + 1)
                    if (value.isNotBlank()) {
                        when {
                            key == "LYRICS" || key == "UNSYNCEDLYRICS" ||
                                key == "UNSYNCED LYRICS" -> return value
                            key.startsWith("LYRICS") && fallback == null -> fallback = value
                        }
                    }
                }
            }
            c++
        }
        return fallback
    }

    /* ============================================================
     * MP4 / M4A
     * ============================================================ */

    /**
     * @param magic 第一个 atom 的 size(4)
     * @param more  第一个 atom 的 type(4) + 其数据前 4 字节
     */
    private fun scanMp4(
        input: InputStream,
        magic: ByteArray,
        more: ByteArray,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        var size = beInt(magic, 0).toLong() and 0xFFFFFFFFL
        val type = String(more, 0, 4, Charsets.ISO_8859_1)
        if (size == 0L) size = Long.MAX_VALUE

        // 已消费 12 字节（size + type + 4）
        if (type == "moov") {
            walkMoov(input, size - 12L, found, wantCover, wantLyrics)
            return
        }
        if (!skipFully(input, size - 12L)) return

        var guard = 0
        while (guard++ < 64) {
            val hdr = ByteArray(8)
            if (!readFully(input, hdr, 8)) return
            var sz = beInt(hdr, 0).toLong() and 0xFFFFFFFFL
            val t = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (sz == 1L) {
                val ext = ByteArray(8)
                if (!readFully(input, ext, 8)) return
                sz = beLong(ext, 0)
                if (sz < 16) return
                if (t == "moov") {
                    walkMoov(input, sz - 16L, found, wantCover, wantLyrics)
                    return
                }
                if (!skipFully(input, sz - 16L)) return
                continue
            }
            if (sz < 8L) return
            if (t == "moov") {
                walkMoov(input, sz - 8L, found, wantCover, wantLyrics)
                return
            }
            if (!skipFully(input, sz - 8L)) return
        }
    }

    /**
     * 遍历 [len] 字节内的 atom。
     * [handle] 返回 true 表示它已经把该 atom 的 body 消费掉了；返回 false 则由这里跳过。
     */
    private inline fun forEachAtom(
        input: InputStream,
        len: Long,
        handle: (type: String, bodyLen: Long) -> Boolean
    ) {
        var remaining = len
        while (remaining >= 8) {
            val hdr = ByteArray(8)
            if (!readFully(input, hdr, 8)) return
            remaining -= 8
            var sz = beInt(hdr, 0).toLong() and 0xFFFFFFFFL
            val t = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (sz == 1L) {
                val ext = ByteArray(8)
                if (!readFully(input, ext, 8)) return
                remaining -= 8
                sz = beLong(ext, 0)
            }
            if (sz < 8L) return
            val body = sz - 8L
            if (!handle(t, body) && !skipFully(input, body)) return
            remaining -= body
        }
    }

    private fun walkMoov(
        input: InputStream,
        len: Long,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        forEachAtom(input, len) { t, body ->
            if (t == "udta") {
                walkUdta(input, body, found, wantCover, wantLyrics)
                true
            } else {
                false
            }
        }
    }

    private fun walkUdta(
        input: InputStream,
        len: Long,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        forEachAtom(input, len) { t, body ->
            if (t == "meta") {
                // meta 是 FullBox，前面有 4 字节 version/flags
                if (skipFully(input, 4)) {
                    walkMeta(input, body - 4, found, wantCover, wantLyrics)
                }
                true
            } else {
                false
            }
        }
    }

    private fun walkMeta(
        input: InputStream,
        len: Long,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        forEachAtom(input, len) { t, body ->
            if (t == "ilst") {
                walkIlst(input, body, found, wantCover, wantLyrics)
                true
            } else {
                false
            }
        }
    }

    /** ilst 的**子节点**才是 covr / ©lyr；早先这里把 ilst 整体当容器跳过了，永远找不到。 */
    private fun walkIlst(
        input: InputStream,
        len: Long,
        found: Found,
        wantCover: Boolean,
        wantLyrics: Boolean
    ) {
        forEachAtom(input, len) { t, body ->
            val isCovr = t == "covr"
            val isLyr = t == "\u00A9lyr" || t == "lyr"
            if ((wantCover && isCovr) || (wantLyrics && isLyr)) {
                val payload = walkDataPayload(input, body)
                if (payload != null) {
                    if (isCovr && payload.size <= MAX_IMAGE_BYTES) {
                        found.cover = payload
                    } else if (isLyr && payload.size <= MAX_TEXT_BYTES) {
                        val s = runCatching { String(payload, Charsets.UTF_8) }.getOrNull()
                        if (!s.isNullOrBlank()) found.lyrics = s
                    }
                }
                true                       // body 一定被消费了
            } else {
                false
            }
        }
    }

    /**
     * 在 `covr` / `©lyr` 容器里找第一个 `data` 原子并取出 payload。
     *
     * - `data` atom = size(4) type(4) dataType(4) locale(4) payload —— 头部 16 字节，
     *   所以 8 字节 atom 头之后还要再跳 8 字节
     * - **无论找到与否都精确消费 [len] 字节**，否则调用方按容器长度扣减 remaining
     *   时流位置会错，后续 atom 全部解析歪
     */
    private fun walkDataPayload(input: InputStream, len: Long): ByteArray? {
        var remaining = len
        var result: ByteArray? = null

        while (remaining >= 8) {
            val hdr = ByteArray(8)
            if (!readFully(input, hdr, 8)) return null
            remaining -= 8
            val sz = beInt(hdr, 0).toLong() and 0xFFFFFFFFL
            val t = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (sz < 8L) return null
            val body = sz - 8L

            if (t == "data" && result == null) {
                if (!skipFully(input, 8)) return null          // dataType + locale
                val payload = body - 8
                if (payload in 1..MAX_PAYLOAD.toLong()) {
                    val out = ByteArray(payload.toInt())
                    if (!readFully(input, out, payload.toInt())) return null
                    result = out
                } else if (!skipFully(input, payload)) {
                    return null
                }
            } else if (!skipFully(input, body)) {
                return null
            }
            remaining -= body
        }

        if (remaining > 0 && !skipFully(input, remaining)) return null
        return result
    }

    /* ============================================================
     * 工具
     * ============================================================ */

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

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun beLong(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 56) or
            ((b[off + 1].toLong() and 0xFF) shl 48) or
            ((b[off + 2].toLong() and 0xFF) shl 40) or
            ((b[off + 3].toLong() and 0xFF) shl 32) or
            ((b[off + 4].toLong() and 0xFF) shl 24) or
            ((b[off + 5].toLong() and 0xFF) shl 16) or
            ((b[off + 6].toLong() and 0xFF) shl 8) or
            (b[off + 7].toLong() and 0xFF)

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean =
        readFully(input, buf, len, 0)

    private fun readFully(input: InputStream, buf: ByteArray, len: Int, offset: Int): Boolean {
        var off = offset
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** InputStream.skip 可能少跳，必须循环 */
    private fun skipFully(input: InputStream, n: Long): Boolean {
        if (n <= 0) return true
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) {
                left -= s
                continue
            }
            if (input.read() < 0) return false
            left--
        }
        return true
    }
}
