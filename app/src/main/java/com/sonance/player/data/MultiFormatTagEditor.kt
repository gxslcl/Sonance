package com.sonance.player.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 为 MP3 / MP4 以外的音频容器读写歌名、歌手、专辑标签（全部以流式 / 局部改写为主，避免整文件读入内存）。
 *
 * 支持格式：
 * - **FLAC** (`fLaC`)：更新 `VORBIS_COMMENT`（block type 4），保留 `STREAMINFO` / `PICTURE` / 歌词等其他注释与块；
 * - **OGG / OPUS** (`OggS`)：更新 `\x03vorbis` 或 `OpusTags` 中的 `VORBIS_COMMENT` 并重算 Ogg CRC-32 与页序号；
 * - **WAV** (`RIFF....WAVE`)：写入 `LIST INFO` (`INAM`/`IART`/`IPRD`) + `id3 ` 块并保留原有 ID3 次要帧；
 * - **AIFF / AIFC** (`FORM....AIFF/AIFC`)：写入 `ID3 ` 块并保留原有 ID3 次要帧；
 * - **DSF** (`DSD `)：替换尾部 ID3v2 标签并更新文件头 `metadataOffset` / `totalFileSize`。
 */
internal object MultiFormatTagEditor {

    private const val BUF_SIZE = 64 * 1024
    private const val DEFAULT_VENDOR = "Sonance"

    private val OGG_CRC_TABLE: IntArray = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) {
                r = if ((r and Int.MIN_VALUE) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
            }
            table[i] = r
        }
    }

    /* ============================================================
     * FLAC：重写 metadata blocks，原样流式复制音频帧
     * ============================================================ */

    fun editFlac(src: File, dst: File, title: String, artist: String, album: String): Boolean {
        return runCatching {
            src.inputStream().buffered().use { input ->
                val magic = ByteArray(4)
                if (!readExact(input, magic, 4) ||
                    magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
                ) {
                    return false
                }

                data class Block(val type: Int, val data: ByteArray)
                val blocks = ArrayList<Block>(6)
                var hasLast = false
                while (!hasLast) {
                    val hdr = ByteArray(4)
                    if (!readExact(input, hdr, 4)) return false
                    hasLast = (hdr[0].toInt() and 0x80) != 0
                    val type = hdr[0].toInt() and 0x7F
                    val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                        ((hdr[2].toInt() and 0xFF) shl 8) or
                        (hdr[3].toInt() and 0xFF)
                    if (len > 32 * 1024 * 1024) return false
                    val payload = ByteArray(len)
                    if (!readExact(input, payload, len)) return false
                    blocks += Block(type, payload)
                }
                if (blocks.isEmpty() || blocks[0].type != 0) return false

                val existingVorbis = blocks.firstOrNull { it.type == 4 }?.data
                val updatedVorbis = updateVorbisComment(existingVorbis, title, artist, album)
                val outBlocks = ArrayList<Block>(blocks.size + 1)
                var inserted = false
                for (b in blocks) {
                    if (b.type == 4) {
                        if (!inserted) {
                            outBlocks += Block(4, updatedVorbis)
                            inserted = true
                        }
                    } else {
                        outBlocks += b
                    }
                }
                if (!inserted) {
                    outBlocks.add(1, Block(4, updatedVorbis))
                }

                dst.outputStream().buffered().use { out ->
                    out.write(magic)
                    for (i in outBlocks.indices) {
                        val b = outBlocks[i]
                        val isLast = i == outBlocks.lastIndex
                        out.write(((if (isLast) 0x80 else 0x00) or (b.type and 0x7F)))
                        out.write((b.data.size ushr 16) and 0xFF)
                        out.write((b.data.size ushr 8) and 0xFF)
                        out.write(b.data.size and 0xFF)
                        out.write(b.data)
                    }
                    input.copyTo(out, BUF_SIZE)
                }
                true
            }
        }.getOrDefault(false)
    }

    /* ============================================================
     * OGG / OPUS：重写 Vorbis/Opus comment 页并维护页序与 CRC32
     * ============================================================ */

    private class OggPage(
        val headerType: Int,
        val granulePos: Long,
        val serial: Int,
        val seqNo: Int,
        val segments: IntArray,
        val body: ByteArray
    ) {
        fun encode(overrideSeqNo: Int = seqNo): ByteArray {
            val out = ByteArray(27 + segments.size + body.size)
            out[0] = 'O'.code.toByte()
            out[1] = 'g'.code.toByte()
            out[2] = 'g'.code.toByte()
            out[3] = 'S'.code.toByte()
            out[4] = 0x00
            out[5] = headerType.toByte()
            putLe64(out, 6, granulePos)
            putLe32(out, 14, serial)
            putLe32(out, 18, overrideSeqNo)
            // bytes 22..25 stay zero while computing CRC
            out[26] = segments.size.toByte()
            for (i in segments.indices) out[27 + i] = segments[i].toByte()
            System.arraycopy(body, 0, out, 27 + segments.size, body.size)
            val crc = oggCrc32(out)
            putLe32(out, 22, crc)
            return out
        }
    }

    fun editOgg(src: File, dst: File, title: String, artist: String, album: String): Boolean {
        return runCatching {
            src.inputStream().buffered().use { input ->
                val page0 = readOggPage(input) ?: return false
                if (!isSupportedOggIdPacket(page0.body)) return false

                // 收集包含 comment packet（第 2 个逻辑包）的所有连续页
                val commentPages = ArrayList<OggPage>(2)
                var packetEnded = false
                var sawAny = false
                while (!packetEnded) {
                    val p = readOggPage(input) ?: break
                    sawAny = true
                    commentPages += p
                    if (p.segments.any { it < 255 }) {
                        packetEnded = true
                    }
                }
                if (!sawAny || !packetEnded) return false

                // 拆出 comment 页上携带的全部包：包 0 为 comment packet，后续可能粘连了 setup packet
                val packets = splitOggPackets(commentPages) ?: return false
                val rawCommentPacket = packets.firstOrNull() ?: return false
                val rewrittenComment = rewriteOggCommentPacket(rawCommentPacket, title, artist, album)
                    ?: return false

                val serial = page0.serial
                var nextSeq = page0.seqNo + 1
                val tailPackets = packets.drop(1)

                dst.outputStream().buffered().use { out ->
                    out.write(page0.encode())

                    val commentOutPages = buildOggPacketPages(
                        packet = rewrittenComment,
                        serial = serial,
                        startSeq = nextSeq,
                        granulePos = 0L
                    )
                    for (cp in commentOutPages) out.write(cp.encode())
                    nextSeq += commentOutPages.size

                    for (tp in tailPackets) {
                        val lastGranule = commentPages.last().granulePos
                        val tailOut = buildOggPacketPages(
                            packet = tp,
                            serial = serial,
                            startSeq = nextSeq,
                            granulePos = if (tp === tailPackets.last()) lastGranule else 0L
                        )
                        for (p in tailOut) out.write(p.encode())
                        nextSeq += tailOut.size
                    }

                    val expectedOldNextSeq = commentPages.last().seqNo + 1
                    val seqDelta = nextSeq - expectedOldNextSeq
                    if (seqDelta == 0) {
                        input.copyTo(out, BUF_SIZE)
                    } else {
                        while (true) {
                            val page = readOggPage(input) ?: break
                            out.write(page.encode(page.seqNo + seqDelta))
                        }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun isSupportedOggIdPacket(body: ByteArray): Boolean {
        val vorbisHead = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val opusHead = "OpusHead".toByteArray(StandardCharsets.US_ASCII)
        return startsWith(body, vorbisHead) || startsWith(body, opusHead)
    }

    private fun rewriteOggCommentPacket(
        packet: ByteArray,
        title: String,
        artist: String,
        album: String
    ): ByteArray? {
        val vorbisPrefix = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val opusPrefix = "OpusTags".toByteArray(StandardCharsets.US_ASCII)
        return when {
            startsWith(packet, vorbisPrefix) -> {
                val oldVorbis = packet.copyOfRange(vorbisPrefix.size, packet.size)
                val updated = updateVorbisComment(oldVorbis, title, artist, album)
                ByteArray(vorbisPrefix.size + updated.size + 1).apply {
                    System.arraycopy(vorbisPrefix, 0, this, 0, vorbisPrefix.size)
                    System.arraycopy(updated, 0, this, vorbisPrefix.size, updated.size)
                    this[size - 1] = 0x01 // Vorbis framing bit
                }
            }
            startsWith(packet, opusPrefix) -> {
                val oldVorbis = packet.copyOfRange(opusPrefix.size, packet.size)
                val updated = updateVorbisComment(oldVorbis, title, artist, album)
                ByteArray(opusPrefix.size + updated.size).apply {
                    System.arraycopy(opusPrefix, 0, this, 0, opusPrefix.size)
                    System.arraycopy(updated, 0, this, opusPrefix.size, updated.size)
                }
            }
            else -> null
        }
    }

    private fun splitOggPackets(pages: List<OggPage>): List<ByteArray>? {
        val out = ArrayList<ByteArray>(2)
        val cur = ByteArrayOutputStream()
        for (page in pages) {
            var offset = 0
            for (segLen in page.segments) {
                if (offset + segLen > page.body.size) return null
                cur.write(page.body, offset, segLen)
                offset += segLen
                if (segLen < 255) {
                    out += cur.toByteArray()
                    cur.reset()
                }
            }
        }
        if (cur.size() > 0) return null
        return out
    }

    private fun buildOggPacketPages(
        packet: ByteArray,
        serial: Int,
        startSeq: Int,
        granulePos: Long
    ): List<OggPage> {
        val allSegs = ArrayList<Int>(packet.size / 255 + 1)
        var rem = packet.size
        while (rem >= 255) {
            allSegs += 255
            rem -= 255
        }
        allSegs += rem

        val pages = ArrayList<OggPage>(1)
        var segOff = 0
        var bodyOff = 0
        var seq = startSeq
        while (segOff < allSegs.size) {
            val endSeg = minOf(segOff + 255, allSegs.size)
            val chunkSegs = IntArray(endSeg - segOff) { allSegs[segOff + it] }
            val chunkLen = chunkSegs.sum()
            val chunkBody = packet.copyOfRange(bodyOff, bodyOff + chunkLen)
            val isContinuation = segOff > 0
            val isLastPageOfPacket = endSeg == allSegs.size
            pages += OggPage(
                headerType = if (isContinuation) 0x01 else 0x00,
                granulePos = if (isLastPageOfPacket) granulePos else -1L,
                serial = serial,
                seqNo = seq++,
                segments = chunkSegs,
                body = chunkBody
            )
            segOff = endSeg
            bodyOff += chunkLen
        }
        return pages
    }

    private fun readOggPage(input: InputStream): OggPage? {
        val hdr = ByteArray(27)
        val first = input.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        if (!readExact(input, hdr, 1, 26)) return null
        if (hdr[0] != 'O'.code.toByte() || hdr[1] != 'g'.code.toByte() ||
            hdr[2] != 'g'.code.toByte() || hdr[3] != 'S'.code.toByte()
        ) {
            return null
        }
        val numSeg = hdr[26].toInt() and 0xFF
        val segRaw = ByteArray(numSeg)
        if (!readExact(input, segRaw, numSeg)) return null
        val segs = IntArray(numSeg) { segRaw[it].toInt() and 0xFF }
        val bodySize = segs.sum()
        val body = ByteArray(bodySize)
        if (!readExact(input, body, bodySize)) return null
        return OggPage(
            headerType = hdr[5].toInt() and 0xFF,
            granulePos = le64(hdr, 6),
            serial = le32(hdr, 14),
            seqNo = le32(hdr, 18),
            segments = segs,
            body = body
        )
    }

    /* ============================================================
     * WAV (RIFF....WAVE)：写入 LIST INFO + id3 块
     * ============================================================ */

    fun editWav(src: File, dst: File, title: String, artist: String, album: String): Boolean {
        return runCatching {
            var existingId3: ByteArray? = null
            src.inputStream().buffered().use { scanIn ->
                val riffHdr = ByteArray(12)
                if (!readExact(scanIn, riffHdr, 12) ||
                    ascii4(riffHdr, 0) != "RIFF" || ascii4(riffHdr, 8) != "WAVE"
                ) {
                    return false
                }
                while (true) {
                    val chHdr = ByteArray(8)
                    val b0 = scanIn.read()
                    if (b0 < 0) break
                    chHdr[0] = b0.toByte()
                    if (!readExact(scanIn, chHdr, 1, 7)) break
                    val id = ascii4(chHdr, 0)
                    val size = le32(chHdr, 4).toLong() and 0xFFFFFFFFL
                    val padded = size + (size and 1L)
                    if (id.equals("id3 ", ignoreCase = true) && size in 10..(16 * 1024 * 1024)) {
                        val raw = ByteArray(size.toInt())
                        if (readExact(scanIn, raw, raw.size)) existingId3 = raw
                        if ((size and 1L) != 0L) scanIn.read()
                    } else {
                        if (!skipExact(scanIn, padded)) break
                    }
                }
            }

            var hasFmt = false
            var hasData = false
            src.inputStream().buffered().use { input ->
                val riffHdr = ByteArray(12)
                if (!readExact(input, riffHdr, 12)) return false

                dst.outputStream().buffered().use { out ->
                    out.write(riffHdr)
                    while (true) {
                        val chHdr = ByteArray(8)
                        val b0 = input.read()
                        if (b0 < 0) break
                        chHdr[0] = b0.toByte()
                        if (!readExact(input, chHdr, 1, 7)) break
                        val id = ascii4(chHdr, 0)
                        val size = le32(chHdr, 4).toLong() and 0xFFFFFFFFL
                        val padded = size + (size and 1L)

                        val isListInfo = if (id == "LIST" && size >= 4) {
                            val form = ByteArray(4)
                            if (!readExact(input, form, 4)) return false
                            val formType = ascii4(form, 0)
                            if (formType == "INFO") {
                                if (!skipExact(input, padded - 4L)) return false
                                true
                            } else {
                                out.write(chHdr)
                                out.write(form)
                                if (!copyExact(input, out, padded - 4L)) return false
                                false
                            }
                        } else {
                            false
                        }
                        if (isListInfo) continue
                        if (id == "LIST" && size >= 4) continue

                        if (id.equals("id3 ", ignoreCase = true)) {
                            if (!skipExact(input, padded)) return false
                            continue
                        }

                        if (id == "fmt ") hasFmt = true
                        if (id == "data") hasData = true
                        out.write(chHdr)
                        if (!copyExact(input, out, padded)) return false
                    }

                    if (!hasFmt || !hasData) return false

                    val listChunk = buildRiffListInfo(title, artist, album)
                    if (listChunk.isNotEmpty()) out.write(listChunk)

                    val id3Tag = Id3Writer.withMetaPreserving(existingId3, title, artist, album)
                        ?: Id3Writer.buildTag(title, artist, album)
                    if (id3Tag.size > 10) {
                        writeChunkLe(out, "id3 ", id3Tag)
                    }
                }
            }

            patchSizeHeader(dst, bigEndian = false)
            true
        }.getOrDefault(false)
    }

    /* ============================================================
     * AIFF / AIFC (FORM....AIFF/AIFC)：写入 ID3 块
     * ============================================================ */

    fun editAiff(src: File, dst: File, title: String, artist: String, album: String): Boolean {
        return runCatching {
            var existingId3: ByteArray? = null
            src.inputStream().buffered().use { scanIn ->
                val formHdr = ByteArray(12)
                if (!readExact(scanIn, formHdr, 12)) return false
                val formId = ascii4(formHdr, 0)
                val subType = ascii4(formHdr, 8)
                if (formId != "FORM" || (subType != "AIFF" && subType != "AIFC")) return false
                while (true) {
                    val chHdr = ByteArray(8)
                    val b0 = scanIn.read()
                    if (b0 < 0) break
                    chHdr[0] = b0.toByte()
                    if (!readExact(scanIn, chHdr, 1, 7)) break
                    val id = ascii4(chHdr, 0)
                    val size = be32(chHdr, 4).toLong() and 0xFFFFFFFFL
                    val padded = size + (size and 1L)
                    if (id == "ID3 " && size in 10..(16 * 1024 * 1024)) {
                        val raw = ByteArray(size.toInt())
                        if (readExact(scanIn, raw, raw.size)) existingId3 = raw
                        if ((size and 1L) != 0L) scanIn.read()
                    } else {
                        if (!skipExact(scanIn, padded)) break
                    }
                }
            }

            var hasComm = false
            var hasSsnd = false
            src.inputStream().buffered().use { input ->
                val formHdr = ByteArray(12)
                if (!readExact(input, formHdr, 12)) return false
                dst.outputStream().buffered().use { out ->
                    out.write(formHdr)
                    while (true) {
                        val chHdr = ByteArray(8)
                        val b0 = input.read()
                        if (b0 < 0) break
                        chHdr[0] = b0.toByte()
                        if (!readExact(input, chHdr, 1, 7)) break
                        val id = ascii4(chHdr, 0)
                        val size = be32(chHdr, 4).toLong() and 0xFFFFFFFFL
                        val padded = size + (size and 1L)
                        if (id == "ID3 ") {
                            if (!skipExact(input, padded)) return false
                            continue
                        }
                        if (id == "COMM") hasComm = true
                        if (id == "SSND") hasSsnd = true
                        out.write(chHdr)
                        if (!copyExact(input, out, padded)) return false
                    }
                    if (!hasComm || !hasSsnd) return false
                    val id3Tag = Id3Writer.withMetaPreserving(existingId3, title, artist, album)
                        ?: Id3Writer.buildTag(title, artist, album)
                    if (id3Tag.size > 10) {
                        writeChunkBe(out, "ID3 ", id3Tag)
                    }
                }
            }

            patchSizeHeader(dst, bigEndian = true)
            true
        }.getOrDefault(false)
    }

    /* ============================================================
     * DSF (DSD )：尾部 ID3v2
     * ============================================================ */

    fun editDsf(src: File, dst: File, title: String, artist: String, album: String): Boolean {
        return runCatching {
            val len = src.length()
            if (len < 28L) return false
            val header = ByteArray(28)
            var existingId3: ByteArray? = null
            var audioEnd = len
            src.inputStream().buffered().use { input ->
                if (!readExact(input, header, 28)) return false
                if (ascii4(header, 0) != "DSD ") return false
                val chunkSize = le64(header, 4)
                if (chunkSize != 28L) return false
                val metaOff = le64(header, 20)
                if (metaOff in 28L until len) {
                    audioEnd = metaOff
                    val toSkip = metaOff - 28L
                    if (!skipExact(input, toSkip)) return false
                    val metaLen = (len - metaOff).toInt()
                    if (metaLen in 10..(16 * 1024 * 1024)) {
                        val raw = ByteArray(metaLen)
                        if (readExact(input, raw, metaLen)) existingId3 = raw
                    }
                }
            }

            val id3Tag = Id3Writer.withMetaPreserving(existingId3, title, artist, album)
                ?: Id3Writer.buildTag(title, artist, album)
            val newMetaOff = if (id3Tag.size > 10) audioEnd else 0L
            val newTotalSize = audioEnd + if (id3Tag.size > 10) id3Tag.size.toLong() else 0L

            src.inputStream().buffered().use { input ->
                if (!skipExact(input, 28L)) return false
                putLe64(header, 12, newTotalSize)
                putLe64(header, 20, newMetaOff)
                dst.outputStream().buffered().use { out ->
                    out.write(header)
                    if (!copyExact(input, out, audioEnd - 28L)) return false
                    if (id3Tag.size > 10) out.write(id3Tag)
                }
            }
            true
        }.getOrDefault(false)
    }

    /* ============================================================
     * VorbisComment 公共编解码
     * ============================================================ */

    internal fun updateVorbisComment(
        existing: ByteArray?,
        title: String,
        artist: String,
        album: String
    ): ByteArray {
        var vendor = DEFAULT_VENDOR
        val kept = ArrayList<String>()
        if (existing != null && existing.size >= 8) {
            var p = 0
            val vLen = le32(existing, p)
            p += 4
            if (vLen >= 0 && p + vLen + 4 <= existing.size) {
                val parsedVendor = String(existing, p, vLen, StandardCharsets.UTF_8)
                if (parsedVendor.isNotEmpty()) vendor = parsedVendor
                p += vLen
                val count = le32(existing, p)
                p += 4
                if (count in 0..4096) {
                    for (i in 0 until count) {
                        if (p + 4 > existing.size) break
                        val len = le32(existing, p)
                        p += 4
                        if (len < 0 || p + len > existing.size) break
                        val comment = String(existing, p, len, StandardCharsets.UTF_8)
                        p += len
                        val eq = comment.indexOf('=')
                        val key = if (eq > 0) comment.substring(0, eq).uppercase() else ""
                        if (key !in REPLACEABLE_VORBIS_KEYS) {
                            kept += comment
                        }
                    }
                }
            }
        }

        val entries = ArrayList<String>(kept.size + 3)
        if (title.isNotBlank()) entries += "TITLE=$title"
        if (artist.isNotBlank()) entries += "ARTIST=$artist"
        if (album.isNotBlank()) entries += "ALBUM=$album"
        entries += kept

        val vendorBytes = vendor.toByteArray(StandardCharsets.UTF_8)
        val entryBytes = entries.map { it.toByteArray(StandardCharsets.UTF_8) }
        val total = 4 + vendorBytes.size + 4 + entryBytes.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var pos = 0
        putLe32(out, pos, vendorBytes.size); pos += 4
        System.arraycopy(vendorBytes, 0, out, pos, vendorBytes.size); pos += vendorBytes.size
        putLe32(out, pos, entryBytes.size); pos += 4
        for (eb in entryBytes) {
            putLe32(out, pos, eb.size); pos += 4
            System.arraycopy(eb, 0, out, pos, eb.size); pos += eb.size
        }
        return out
    }

    private val REPLACEABLE_VORBIS_KEYS = setOf("TITLE", "ARTIST", "ALBUM")

    /* ============================================================
     * RIFF / AIFF & IO helpers
     * ============================================================ */

    private fun buildRiffListInfo(title: String, artist: String, album: String): ByteArray {
        val sub = ByteArrayOutputStream()
        if (title.isNotBlank()) writeInfoItem(sub, "INAM", title)
        if (artist.isNotBlank()) writeInfoItem(sub, "IART", artist)
        if (album.isNotBlank()) writeInfoItem(sub, "IPRD", album)
        val subBytes = sub.toByteArray()
        if (subBytes.isEmpty()) return ByteArray(0)
        val bodySize = 4 + subBytes.size
        val out = ByteArrayOutputStream(8 + bodySize + (bodySize and 1))
        out.write("LIST".toByteArray(StandardCharsets.US_ASCII))
        val len4 = ByteArray(4).also { putLe32(it, 0, bodySize) }
        out.write(len4)
        out.write("INFO".toByteArray(StandardCharsets.US_ASCII))
        out.write(subBytes)
        if ((bodySize and 1) != 0) out.write(0)
        return out.toByteArray()
    }

    private fun writeInfoItem(out: ByteArrayOutputStream, id: String, value: String) {
        val text = value.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        val len4 = ByteArray(4).also { putLe32(it, 0, text.size) }
        out.write(len4)
        out.write(text)
        if ((text.size and 1) != 0) out.write(0)
    }

    private fun writeChunkLe(out: OutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(ByteArray(4).also { putLe32(it, 0, payload.size) })
        out.write(payload)
        if ((payload.size and 1) != 0) out.write(0)
    }

    private fun writeChunkBe(out: OutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(ByteArray(4).also { putBe32(it, 0, payload.size) })
        out.write(payload)
        if ((payload.size and 1) != 0) out.write(0)
    }

    private fun patchSizeHeader(file: File, bigEndian: Boolean) {
        val bodySize = (file.length() - 8L).toInt()
        val buf = ByteArray(4)
        if (bigEndian) putBe32(buf, 0, bodySize) else putLe32(buf, 0, bodySize)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4L)
            raf.write(buf)
        }
    }

    private fun oggCrc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
        }
        return crc
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun ascii4(b: ByteArray, off: Int): String =
        String(b, off, 4, StandardCharsets.US_ASCII)

    private fun readExact(input: InputStream, buf: ByteArray, len: Int): Boolean =
        readExact(input, buf, 0, len)

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var pos = 0
        while (pos < len) {
            val n = input.read(buf, off + pos, len - pos)
            if (n < 0) return false
            pos += n
        }
        return true
    }

    private fun skipExact(input: InputStream, bytes: Long): Boolean {
        var rem = bytes
        val scratch = ByteArray(8192)
        while (rem > 0L) {
            val toRead = minOf(rem, scratch.size.toLong()).toInt()
            val n = input.read(scratch, 0, toRead)
            if (n < 0) return false
            rem -= n.toLong()
        }
        return true
    }

    private fun copyExact(input: InputStream, output: OutputStream, bytes: Long): Boolean {
        var rem = bytes
        val buf = ByteArray(BUF_SIZE)
        while (rem > 0L) {
            val toRead = minOf(rem, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) return false
            output.write(buf, 0, n)
            rem -= n.toLong()
        }
        return true
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    private fun putLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) {
            b[off + i] = ((v ushr (i * 8)) and 0xFF).toByte()
        }
    }
}
