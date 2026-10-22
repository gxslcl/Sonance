package com.sonance.player.data

/**
 * 酷狗私有容器（KGM / KGMA / KGG / VPR）解码器。
 *
 * 算法本体仍放在 [PrivateAudioRestorer]（那张 272 项的掩码表 + Table1/Table2 折叠，
 * 已被真实文件逐字节验证过，有单测覆盖）。这里只做「接口适配」：
 * 把探测阶段解析出的 17 字节密钥和 VPR 标记固化到实例里，
 * 让上层用统一的 `decryptChunk(buf, count, startPos)` 驱动。
 *
 * 头部布局与变换公式见 [PrivateAudioRestorer] 的文档。
 */
class KgmDecoder private constructor(
    private val key: ByteArray,
    private val isVpr: Boolean,
    private val version: Int,
    override val info: HeaderInfo
) : AudioDecoder {

    override val formatName: String get() = FORMAT

    override val supported: Boolean
        get() = version == PrivateAudioRestorer.SUPPORTED_VERSION

    override val unsupportedReason: String?
        get() = if (supported) null else "加密版本 $version 不支持（仅支持 $SUPPORTED_VERSION）"

    override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean {
        if (count <= 0) return true
        if (count > buf.size) return false
        // 掩码折叠依赖 pos 的完整 32 位，超过 2GB 的载荷会算错 —— 直接判失败，
        // 而不是悄悄写出一段听起来像噪声的音频
        if (startPos < 0 || startPos > Int.MAX_VALUE - count.toLong()) return false
        PrivateAudioRestorer.xorChunk(buf, count, startPos.toInt(), key, isVpr)
        return true
    }

    companion object {
        const val FORMAT = "kgm"

        /** 该家族的全部扩展名（不含点） */
        val extensions: List<String> = listOf("kgm", "kgma", "kgg", "vpr")

        private const val SUPPORTED_VERSION = PrivateAudioRestorer.SUPPORTED_VERSION

        /**
         * 探测头部。魔数不匹配返回 null。
         *
         * 版本不受支持时**仍然返回实例**，由 [supported] / [unsupportedReason] 表达，
         * 这样上层能给出「加密版本 5 不支持」而不是含糊的「不是加密容器」。
         */
        fun probe(header: ByteArray, fileSize: Long): AudioDecoder? {
            val parsed = PrivateAudioRestorer.parseHeader(header) ?: return null
            if (parsed.payloadOffset.toLong() > fileSize) return null
            return KgmDecoder(
                key = parsed.key,
                isVpr = parsed.isVpr,
                version = parsed.version,
                info = HeaderInfo(
                    payloadOffset = parsed.payloadOffset.toLong(),
                    suggestedExt = "", // KGM 头部不写格式，只能解出第一块再嗅探
                    isVpr = parsed.isVpr,
                    format = FORMAT,
                    metadata = mapOf("version" to parsed.version),
                    totalSize = fileSize
                )
            )
        }

        /** 廉价魔数判定：媒体库扫描时只想问「是不是加密容器」，不必完整解析 */
        fun looksLike(header: ByteArray): Boolean =
            PrivateAudioRestorer.isSupportedContainer(header)
    }
}
