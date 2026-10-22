package com.sonance.player.data

/**
 * 统一音频容器解密器接口。
 *
 * 所有私有格式（KGM / NCM / QMC / KWM）均实现此接口，
 * 由 [ContainerRestorer] 统一探测、分发、流式解密。
 *
 * ## 为什么是「一个文件一个实例」
 *
 * 解密密钥来自**每个文件自己的头部**，掩码 / 密钥流又必须跨块连续。
 * 如果做成无状态的单例 + 每次传密钥，调用方就得自己保管状态；
 * 做成「带状态的单例」则并发跑两个文件会互相污染。
 *
 * 所以 [probe] 返回的是**已经按该文件头部配置好的实例**：
 * 探测即建会话，之后 [decryptChunk] 只认 `(buf, count, startPos)`，
 * 天然线程安全，也天然支持分块流式解密。
 */
sealed interface AudioDecoder {

    /** 格式标识（小写，用于日志与界面展示） */
    val formatName: String

    /** 该文件的头部解析结果（载荷偏移、建议后缀、元数据、封面） */
    val info: HeaderInfo

    /**
     * 当前实现是否真能解开这个文件。
     *
     * 探测命中了格式但版本 / 变体不支持时返回 false，
     * 由调用方读取 [unsupportedReason] 给出准确的跳过原因
     * —— 比笼统的「非受支持的加密容器」有用得多。
     */
    val supported: Boolean get() = true

    /** [supported] 为 false 时的原因；可解时返回 null */
    val unsupportedReason: String? get() = null

    /**
     * 流式解密一块载荷数据（就地修改 buf）。
     *
     * 契约：本次传入的 [buf] 的 `[0, count)` 对应载荷内的 `[startPos, startPos + count)`。
     * 跨块调用时 [startPos] 必须单调递增且首尾相接，掩码 / 密钥流才接得上。
     *
     * @param buf      数据缓冲区（长度 ≥ count，会被就地改写）
     * @param count    本次有效字节数
     * @param startPos 这段数据在**载荷内**的起始下标（0 基）
     * @return true=成功；false=数据异常，调用方应终止
     */
    fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean

    /**
     * 从「文件开头到载荷起点」的原始字节里提取封面。
     *
     * 封面不一定在音频载荷里：NCM 把图单独放在头部区，必须在流跳过它之前拿走。
     * 默认实现直接回 [HeaderInfo.coverImage]（KGM / KWM 头部里没有图，返回 null）。
     *
     * @param prefix 文件 `[0, payloadOffset)` 的原始字节
     */
    fun extractCover(prefix: ByteArray): ByteArray? = info.coverImage

    /**
     * 整块解密（小文件 / 测试用）。
     *
     * 默认实现复制一份再走 [decryptChunk]，保证与流式路径**同一套算法**，
     * 不会出现「小文件能解、大文件解错」这种分叉。
     */
    fun decryptAll(payload: ByteArray): ByteArray =
        payload.copyOf().also { decryptChunk(it, it.size, 0) }

    companion object {

        /** 探测所需的最小头部字节数：KGM 头部 44 字节是各格式里最长的定长头 */
        const val HEADER_MIN = 64

        /**
         * 按解密后的真实内容判定后缀。
         *
         * 只认几个可靠的容器签名：`fLaC` / `ID3` / MPEG 帧同步 / `OggS` / `ftyp` / `RIFF`。
         * 认不出来返回空串 —— 让调用方回退到容器自带的格式声明，
         * 而不是像早期版本那样无脑给 `.mp3`（会给 flac 文件起错后缀）。
         */
        fun sniffExtension(buf: ByteArray): String {
            if (buf.size >= 4 &&
                (buf[0].toInt() and 0xFF) == 0x66 && (buf[1].toInt() and 0xFF) == 0x4C &&
                (buf[2].toInt() and 0xFF) == 0x61 && (buf[3].toInt() and 0xFF) == 0x43
            ) return ".flac" // 'fLaC'

            if (buf.size >= 4 &&
                (buf[0].toInt() and 0xFF) == 0x4F && (buf[1].toInt() and 0xFF) == 0x67 &&
                (buf[2].toInt() and 0xFF) == 0x67 && (buf[3].toInt() and 0xFF) == 0x53
            ) return ".ogg" // 'OggS'

            if (buf.size >= 3 &&
                (buf[0].toInt() and 0xFF) == 0x49 && (buf[1].toInt() and 0xFF) == 0x44 &&
                (buf[2].toInt() and 0xFF) == 0x33
            ) return ".mp3" // 'ID3'

            if (buf.size >= 2) {
                val b0 = buf[0].toInt() and 0xFF
                val b1 = buf[1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return ".mp3" // MPEG 帧同步
            }

            if (buf.size >= 8 &&
                (buf[4].toInt() and 0xFF) == 0x66 && (buf[5].toInt() and 0xFF) == 0x74 &&
                (buf[6].toInt() and 0xFF) == 0x79 && (buf[7].toInt() and 0xFF) == 0x70
            ) return ".m4a" // 'ftyp'

            if (buf.size >= 4 &&
                (buf[0].toInt() and 0xFF) == 0x52 && (buf[1].toInt() and 0xFF) == 0x49 &&
                (buf[2].toInt() and 0xFF) == 0x46 && (buf[3].toInt() and 0xFF) == 0x46
            ) return ".wav" // 'RIFF'

            return ""
        }

        /** 把后缀规范化成带点的形式：`flac` / `.FLAC` → `.flac`；空串原样返回 */
        fun normalizeExt(raw: String?): String {
            val s = raw?.trim()?.lowercase().orEmpty()
            if (s.isEmpty()) return ""
            val bare = s.removePrefix(".")
            return if (bare.isEmpty()) "" else ".$bare"
        }

        fun readUInt32LE(buf: ByteArray, off: Int): Int? =
            if (off >= 0 && off + 4 <= buf.size) {
                (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
            } else null

        fun readUInt64LE(buf: ByteArray, off: Int): Long? =
            if (off >= 0 && off + 8 <= buf.size) {
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
                v
            } else null

        fun startsWith(buf: ByteArray, prefix: ByteArray): Boolean {
            if (buf.size < prefix.size) return false
            for (i in prefix.indices) if (buf[i] != prefix[i]) return false
            return true
        }

        fun startsWith(buf: ByteArray, prefix: String): Boolean =
            startsWith(buf, prefix.toByteArray(Charsets.ISO_8859_1))
    }
}

/**
 * 探测成功时返回的头部信息。
 *
 * 字段按用途分组：
 * - 必需：payloadOffset
 * - 可选：suggestedExt / isVpr / format / metadata / coverImage
 */
data class HeaderInfo(
    /** 加密载荷在文件中的起始字节偏移 */
    val payloadOffset: Long,

    /**
     * 容器自带的格式声明（含点），如 `.flac`。
     *
     * **空串表示「不知道，需要解密后嗅探」** —— KGM 就是这种情况：
     * 头部只写了密钥和偏移，真实格式只能解出第一块数据才看得出来。
     */
    val suggestedExt: String = "",

    /** 是否为 KGM VPR 变体（需再异或一层 VprMaskDiff） */
    val isVpr: Boolean = false,

    /** 格式标识，同 [AudioDecoder.formatName] */
    val format: String = "",

    /** 元数据：歌名、歌手、专辑、年份等；供写入输出文件标签 */
    val metadata: Map<String, Any> = emptyMap(),

    /** 封面图字节（JPEG/PNG），若有则写入输出文件封面并存入封面缓存 */
    val coverImage: ByteArray? = null,

    /** 原始文件总大小 */
    val totalSize: Long = 0L,

    /**
     * 载荷长度（解密后的字节数）；**-1 表示「到文件尾」**。
     *
     * QMC 系需要它：密钥/元数据挂在**文件尾部**，载荷是 `[payloadOffset, payloadOffset+payloadLength)`，
     * 尾部那段不能写进输出文件。其它格式不用关心（保持 -1）。
     */
    val payloadLength: Long = -1L
) {
    val title: String get() = metadata["title"] as? String ?: ""
    val artist: String get() = metadata["artist"] as? String ?: ""
    val album: String get() = metadata["album"] as? String ?: ""

    /** 解密后的载荷字节数 */
    val payloadSize: Long get() = if (totalSize > payloadOffset) totalSize - payloadOffset else 0L

    /** 生成输出文件名（去加密后缀 + 真实后缀） */
    fun outputName(sourceName: String, ext: String = suggestedExt): String =
        PrivateAudioRestorer.outputStem(sourceName) + ext
}
