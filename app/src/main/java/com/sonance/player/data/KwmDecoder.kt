package com.sonance.player.data

/**
 * 酷我音乐 `.kwm` 解码器。
 *
 * ## 格式
 * ```
 * 0x000  12 字节  魔数 "yeelion-kuwo"（旧版 "yeelion-kuwo-tme"，16 字节）
 * 0x018   8 字节  「配方」recipe，按小端 uint64 解释
 * 0x030   8 字节  码率 + 后缀，例如 "320mp3" / "flac"
 * 0x400   ...     异或后的音频
 * ```
 *
 * ## 掩码派生（32 字节）
 * ```
 * n = recipe 按小端读出的无符号 64 位整数
 * s = n 的十进制字符串
 * mask[i] = ROOT[i] ^ s[i % len(s)]
 * ```
 * 解密就是 `out[i] = data[0x400 + i] ^ mask[i and 31]` —— 纯异或，无状态，
 * 所以分块解密只需要知道块在载荷内的绝对下标。
 *
 * KWM 不携带歌曲元数据，歌名 / 歌手靠文件名解析 + 在线补全（沿用现有流程）。
 */
class KwmDecoder private constructor(
    private val mask: ByteArray,
    override val info: HeaderInfo
) : AudioDecoder {

    override val formatName: String get() = FORMAT

    override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean {
        if (count <= 0) return true
        if (count > buf.size || startPos < 0) return false
        var i = 0
        var m = (startPos and 31L).toInt()
        while (i < count) {
            buf[i] = (buf[i].toInt() xor mask[m].toInt()).toByte()
            i++
            m = (m + 1) and 31
        }
        return true
    }

    companion object {
        const val FORMAT = "kwm"

        val extensions: List<String> = listOf("kwm")

        private const val MAGIC = "yeelion-kuwo"

        /** 头部定长 1024 字节，音频从 0x400 开始 */
        private const val HEADER_SIZE = 0x400

        private const val RECIPE_OFF = 0x18
        private const val SUFFIX_OFF = 0x30

        /** 32 字节固定串，掩码的基底 */
        private val ROOT = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk".toByteArray(Charsets.ISO_8859_1)

        /** 探测需要读到后缀字段，0x38 就够；但头部定长 1024，读满更稳 */
        const val PROBE_BYTES = HEADER_SIZE

        fun looksLike(header: ByteArray): Boolean =
            AudioDecoder.startsWith(header, MAGIC)

        fun probe(header: ByteArray, fileSize: Long): AudioDecoder? {
            if (!looksLike(header)) return null
            if (header.size < SUFFIX_OFF + 8) return null
            if (fileSize <= HEADER_SIZE) return null

            val recipe = AudioDecoder.readUInt64LE(header, RECIPE_OFF) ?: return null
            val mask = deriveMask(recipe)
            val declared = AudioDecoder.normalizeExt(suffixHint(header))

            return KwmDecoder(
                mask = mask,
                info = HeaderInfo(
                    payloadOffset = HEADER_SIZE.toLong(),
                    suggestedExt = declared,
                    format = FORMAT,
                    totalSize = fileSize
                )
            )
        }

        /**
         * 8 字节配方 → 32 字节掩码。
         *
         * 配方按**无符号** 64 位解释：最高位为 1 时十进制串是 20 位，
         * 用有符号 Long 会得到负数串（开头多一个 `-`），掩码整体错位。
         */
        internal fun deriveMask(recipe: Long): ByteArray {
            val s = java.lang.Long.toUnsignedString(recipe).toByteArray(Charsets.ISO_8859_1)
            val out = ByteArray(32)
            for (i in 0 until 32) {
                out[i] = (ROOT[i].toInt() xor s[i % s.size].toInt()).toByte()
            }
            return out
        }

        /**
         * 头部 0x30 处写着「前导数字(码率) + 后缀」，例如 `320mp3` → `mp3`、`flac` → `flac`。
         * 剥掉前导数字即可。读不到返回空串（交给解密后嗅探兜底）。
         */
        internal fun suffixHint(header: ByteArray): String {
            if (header.size < SUFFIX_OFF + 8) return ""
            var end = SUFFIX_OFF
            while (end < SUFFIX_OFF + 8 && header[end].toInt() != 0) end++
            var start = SUFFIX_OFF
            while (start < end && header[start].toInt() in 48..57) start++
            return String(header, start, end - start, Charsets.ISO_8859_1).trim()
        }
    }
}
