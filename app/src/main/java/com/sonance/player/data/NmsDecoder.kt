package com.sonance.player.data

/**
 * 网易云音乐 `.nmsf` 播放缓存解码器。
 *
 * ## 格式（真机实测，2026-10）
 *
 * 网易云安卓端的播放缓存放两份形态：
 * - 老形态 `.ncm` 整文件（PC 端居多），见 [NcmDecoder]；
 * - 现行形态 `files/Cache/Music3/<分桶>/<歌曲id>_<码率>_<md5>_<分片号>.nmsf`
 *   —— **明文音频整体 XOR 0xA3**，无头部结构、无偏移、无元数据块。
 *
 * 判定依据（同一批缓存所有文件头 24 字节完全一致）：
 * ```
 * 密文  a3 a3 a3 bf | c5 d7 da d3 | ee 97 e2 83 | a3 a3 a1 a3 | ee 97 e2 83 | ca d0 cc ce
 * XOR   a3 a3 a3 a3 ...（重复 0xA3）
 * 明文  00 00 00 18 | 66 74 79 70 | 4d 34 41 20 | ...         | 4d 34 41 20 | 69 73 6f 6d
 *               box长 | "ftyp"      | "M4A "      |  minor ver  |  "M4A "     | "isom"
 * ```
 * 即解出标准 m4a（AAC）。位置 16-19 / 20-23 两段独立解出 "M4A " / "isom"
 * 不是巧合能解释的 —— 密钥就是单字节 0xA3。
 *
 * 无状态单字节异或：`decryptChunk` 不依赖 startPos，天然支持分块流式解密。
 * 元数据 / 封面都留在 m4a 的 moov box 里（明文自带），播放管线直接可读，
 * 这里不需要也不解析 —— 所以 [HeaderInfo] 除了偏移与格式标识外全空。
 */
class NmsDecoder private constructor(
    override val info: HeaderInfo
) : AudioDecoder {

    override val formatName: String get() = FORMAT

    override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean {
        if (count <= 0) return true
        if (count > buf.size || startPos < 0) return false
        for (i in 0 until count) {
            buf[i] = (buf[i].toInt() xor MASK).toByte()
        }
        return true
    }

    companion object {
        const val FORMAT = "nmsf"

        /** 解密密钥：单字节 0xA3 */
        const val MASK = 0xA3

        /**
         * 头部按 0xA3 异或后是否为已知的明文音频签名。
         *
         * 与 [AudioDecoder.sniffExtension] 同一套标准 —— nmsf 里可能是 m4a，
         * 理论上也可能是 mp3 / flac（码率字段带 256000 / 80000 的变体），
         * 全部放行，真实后缀交给解密后的嗅探判定。
         */
        fun looksLike(header: ByteArray): Boolean {
            if (header.size < 8) return false
            val b = IntArray(8) { header[it].toInt() xor MASK and 0xFF }
            val b0 = b[0]
            val b1 = b[1]
            val b2 = b[2]
            val b3 = b[3]

            // "ftyp" 在第 4~7 字节（m4a / mp4）
            if (b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70) return true
            // ID3（mp3）
            if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return true
            // MPEG 帧同步
            if (b0 == 0xFF && b1 and 0xE0 == 0xE0) return true
            // fLaC / OggS / RIFF
            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return true
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true
            return false
        }

        fun probe(header: ByteArray, fileSize: Long): AudioDecoder? {
            if (!looksLike(header)) return null
            if (fileSize <= 0L) return null
            return NmsDecoder(
                info = HeaderInfo(
                    payloadOffset = 0L,
                    suggestedExt = "",
                    format = FORMAT,
                    totalSize = fileSize
                )
            )
        }
    }
}
