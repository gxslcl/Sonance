package com.sonance.player.data

/**
 * 还原流程专用的标签 / 封面写入工具。
 *
 * 还原出来的文件是**裸音频流**：KGM 和 KWM 容器里根本没有元数据，
 * NCM 有元数据但把封面单独放在头部区（不在音频里）。所以要么在写盘时
 * 顺手把标签和封面补回去，要么用户得到的就是一堆「文件名当标题、
 * 歌手未知、没有封面」的条目。
 *
 * 两条路径：
 * - **MP3**：在音频前面接一个 ID3v2.3 标签（[Id3Writer.buildTag]）。
 * - **FLAC**：在 STREAMINFO 之后插入一个 `METADATA_BLOCK_PICTURE`（type 6）。
 *   FLAC 的元数据块是链式的，不能简单前置，必须改写 STREAMINFO 的 last 标记。
 */
internal object RestoreTagWriter {

    /** FLAC 的 STREAMINFO 块固定 34 字节载荷 + 4 字节块头 = 38，紧跟 "fLaC" 之后 */
    private const val FLAC_MAGIC_LEN = 4
    private const val FLAC_BLOCK_HEADER = 4
    private const val STREAMINFO_LEN = 34

    /** 插入封面块时需要重新组织的首块前缀长度：`fLaC` + STREAMINFO 块 */
    const val FLAC_HEAD_BYTES = FLAC_MAGIC_LEN + FLAC_BLOCK_HEADER + STREAMINFO_LEN

    /** 按图片签名猜 MIME —— 标签里写错 MIME 会让部分播放器直接不显示封面 */
    fun mimeOf(bytes: ByteArray): String = when {
        // PNG 的完整签名是 8 字节，但前 4 字节（89 50 4E 47）已经足够唯一
        bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ->
            "image/png"
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 ->
            "image/jpeg"
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0x47 && (bytes[1].toInt() and 0xFF) == 0x49 ->
            "image/gif"
        bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0x42 && (bytes[1].toInt() and 0xFF) == 0x4D ->
            "image/bmp"
        else -> "image/jpeg"
    }

    /** 组装一个完整的 FLAC `METADATA_BLOCK_PICTURE` 块（含 4 字节块头） */
    fun flacPictureBlock(cover: ByteArray, mime: String, isLast: Boolean): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        val payloadLen = 4 + 4 + mimeBytes.size + 4 + 16 + 4 + cover.size
        val out = ByteArray(FLAC_BLOCK_HEADER + payloadLen)
        var p = 0
        // 块头：bit7 = last-metadata-block，低 7 位 = 块类型（6 = PICTURE）
        out[p++] = ((if (isLast) 0x80 else 0x00) or 6).toByte()
        out[p++] = ((payloadLen ushr 16) and 0xFF).toByte()
        out[p++] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[p++] = (payloadLen and 0xFF).toByte()
        // 图片类型 3 = 封面（正面）
        p = putInt(out, p, 3)
        p = putInt(out, p, mimeBytes.size)
        System.arraycopy(mimeBytes, 0, out, p, mimeBytes.size); p += mimeBytes.size
        p = putInt(out, p, 0) // 描述串长度
        p = putInt(out, p, 0) // 宽（未知）
        p = putInt(out, p, 0) // 高
        p = putInt(out, p, 0) // 色深
        p = putInt(out, p, 0) // 索引色数
        p = putInt(out, p, cover.size)
        System.arraycopy(cover, 0, out, p, cover.size)
        return out
    }

    /**
     * 为 FLAC 组装「插在首块之前」的字节。
     *
     * @param firstChunk 解密后的第一个数据块（必须以 `fLaC` 开头）
     * @return 要写在最前面的字节；同时调用方需要跳过首块的前 [FLAC_HEAD_BYTES] 字节
     *         （因为 STREAMINFO 已经被重新写进返回值里了）。不是合法 FLAC 时返回 null。
     */
    fun flacPrefix(firstChunk: ByteArray, cover: ByteArray): ByteArray? {
        if (firstChunk.size < FLAC_HEAD_BYTES) return null
        if (!(firstChunk[0].toInt() == 0x66 && firstChunk[1].toInt() == 0x4C &&
                firstChunk[2].toInt() == 0x61 && firstChunk[3].toInt() == 0x43)
        ) return null

        val head = firstChunk[4].toInt() and 0xFF
        val type = head and 0x7F
        if (type != 0) return null // 第一个块必须是 STREAMINFO
        val wasLast = (head and 0x80) != 0
        val infoLen = ((firstChunk[5].toInt() and 0xFF) shl 16) or
            ((firstChunk[6].toInt() and 0xFF) shl 8) or
            (firstChunk[7].toInt() and 0xFF)
        if (infoLen != STREAMINFO_LEN) return null

        val picture = flacPictureBlock(cover, mimeOf(cover), isLast = wasLast)

        val out = ByteArray(FLAC_HEAD_BYTES + picture.size)
        System.arraycopy(firstChunk, 0, out, 0, FLAC_HEAD_BYTES)
        // 插入了后续块，STREAMINFO 就不再是最后一块
        out[4] = (head and 0x7F).toByte()
        System.arraycopy(picture, 0, out, FLAC_HEAD_BYTES, picture.size)
        return out
    }

    /** 为 MP3 组装 ID3v2.3 标签；没有任何可用信息时返回空数组（不写空标签） */
    fun mp3Tag(info: HeaderInfo, cover: ByteArray?): ByteArray {
        if (info.title.isBlank() && info.artist.isBlank() && info.album.isBlank() && cover == null) {
            return ByteArray(0)
        }
        return Id3Writer.buildTag(
            title = info.title,
            artist = info.artist,
            album = info.album,
            cover = cover,
            coverMime = cover?.let { mimeOf(it) }
        )
    }

    private fun putInt(buf: ByteArray, off: Int, v: Int): Int {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }
}
