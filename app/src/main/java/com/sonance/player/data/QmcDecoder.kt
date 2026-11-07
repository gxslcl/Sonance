package com.sonance.player.data

/**
 * QQ 音乐 / TME 系加密容器（`.qmc*` / `.mflac` / `.mgg` / `.tkm` / `.x2m` …）。
 *
 * ## 与 KGM / NCM 的本质区别
 *
 * QMC 文件**没有任何魔数**，密钥藏在**文件尾部**，所以探测必须拿到尾部数据：
 *  - QMCv1（qmc0/qmc3/qmcflac/qmcogg/qmcra/tkm…）：无密钥，整文件用一张全局静态表掩码；
 *  - QTag（mflac/mgg 旧式）：尾部 `ekey,songid,extra2` + 4 字节大端长度 + "QTag"；
 *  - 裸 ekey：尾部 4 字节小端长度 + ekey（可能带 EncV2 双层包装，见 [QmcCrypto.deriveKey]）；
 *  - STag / musicex（"cex\0"）：无内嵌密钥，依赖 QQ 音乐客户端的运行时数据，**不支持**。
 *
 * 因为没有魔数，识别只能靠「文件名 + 尾部结构」：[looksLikeName] 命中的文件
 * 才会读尾部窗口，解析出密钥后先用首 64 字节做嗅探验证 —— 解不出已知音频
 * 格式就如实报「密钥不符」，绝不把噪声当音频写出去（这类失败是静默的）。
 *
 * 算法移植自 unlock-music CLI（Go, MIT），见 [QmcCrypto]。
 */
object QmcDecoder {

    const val FORMAT = "qmc"

    /** 尾部探测窗口：QTag 的 ekey 可能有几百字节，4KB 足够覆盖 */
    const val TAIL_WINDOW = 4096

    /** 该家族的扩展名（不含点），与 [LockedFormat] 的登记表保持一致 */
    val extensions: List<String> = listOf(
        "qmc0", "qmc2", "qmc3", "qmcflac", "qmcogg", "qmcra",
        "tkm", "mflac", "mflac0", "mgg", "mgg1", "mggl",
        "tm0", "tm2", "tm3", "tm6", "x2m", "x3m"
    )

    /** 文件名是否属于 QMC 家族 —— 没有魔数时这是唯一线索 */
    fun looksLikeName(fileName: String): Boolean =
        LockedFormat.extensionOf(fileName) in extensions

    /**
     * 兼容旧签名：QMC 必须读尾部才能探测，两参版本永远返回 null。
     * 调用方（[ContainerRestorer]）应使用带 `tail` 的重载。
     */
    fun probe(header: ByteArray, fileSize: Long): AudioDecoder? = null

    /**
     * 完整探测：解析尾部密钥结构 → 派生密钥 → 首块解密嗅探。
     *
     * @param header 文件开头至少 [AudioDecoder.HEADER_MIN] 字节
     * @param fileSize 文件总字节数
     * @param tail 文件末尾的 [TAIL_WINDOW] 字节（不足则取全部）；null 表示无法提供
     * @return 已配置好的解码器；无法处理时返回 `supported=false` 的实例（带准确原因）
     */
    fun probe(header: ByteArray, fileSize: Long, tail: ByteArray?): AudioDecoder? {
        if (tail == null || tail.size < 8 || fileSize < 8) return null

        var key: ByteArray? = null
        var audioLen = fileSize
        var unsupported: String? = null

        val suffix = String(tail.copyOfRange(tail.size - 4, tail.size), Charsets.US_ASCII)
        when (suffix) {
            "QTag" -> {
                // 长度字段是大端（与裸 ekey 的小端不同，官方实现就是这么写的）
                val metaLen = ((tail[tail.size - 8].toInt() and 0xFF) shl 24) or
                    ((tail[tail.size - 7].toInt() and 0xFF) shl 16) or
                    ((tail[tail.size - 6].toInt() and 0xFF) shl 8) or
                    (tail[tail.size - 5].toInt() and 0xFF)
                if (metaLen <= 0 || metaLen > tail.size - 8) {
                    unsupported = "QTag 元数据超长（$metaLen 字节，超出探测窗口）"
                } else {
                    val meta = String(
                        tail.copyOfRange(tail.size - 8 - metaLen, tail.size - 8),
                        Charsets.UTF_8
                    )
                    val parts = meta.split(",")
                    if (parts.size < 3) {
                        unsupported = "QTag 元数据格式异常"
                    } else {
                        key = QmcCrypto.deriveKey(parts[0].toByteArray(Charsets.UTF_8))
                        if (key == null) unsupported = "QTag 密钥派生失败"
                        audioLen = fileSize - 8 - metaLen
                    }
                }
            }

            "STag" -> unsupported = "STag 无内嵌密钥（需 QQ 音乐客户端运行时数据）"

            "cex\u0000" -> unsupported = "musicex 新版无内嵌密钥（需 QQ 音乐客户端运行时数据）"

            else -> {
                // 裸 ekey：小端 uint32 = ekey 长度
                val size = (tail[tail.size - 4].toInt() and 0xFF) or
                    ((tail[tail.size - 3].toInt() and 0xFF) shl 8) or
                    ((tail[tail.size - 2].toInt() and 0xFF) shl 16) or
                    ((tail[tail.size - 1].toInt() and 0xFF) shl 24)
                if (size in 1..0xFFFF && size <= tail.size - 4) {
                    var rawKey = tail.copyOfRange(tail.size - 4 - size, tail.size - 4)
                    // 官方实现会清掉尾部 NUL
                    var end = rawKey.size
                    while (end > 0 && rawKey[end - 1] == 0.toByte()) end--
                    rawKey = rawKey.copyOf(end)
                    key = QmcCrypto.deriveKey(rawKey)
                    if (key == null) unsupported = "裸 ekey 密钥派生失败"
                    audioLen = fileSize - 4 - size
                }
                // size 不在合法范围 → QMCv1：无密钥，整文件静态表掩码
            }
        }

        if (unsupported != null) return Unsupported(unsupported)
        if (audioLen <= 0) return Unsupported("音频载荷为空")

        val cipher = QmcCrypto.cipherFor(key)

        // 首块嗅探验证：QMC 没有魔数，普通文件误入这条路径时，
        // 解密结果不可能嗅探出已知音频格式 —— 在这里拦截，绝不静默输出噪声
        val sniffLen = minOf(64, header.size)
        val sniffBuf = header.copyOf(sniffLen)
        cipher.decrypt(sniffBuf, sniffBuf.size, 0)
        val sniffed = AudioDecoder.sniffExtension(sniffBuf)
        if (sniffed.isEmpty()) {
            return Unsupported(
                "无内嵌密钥或密钥不符；QQ 在线播放缓存的密钥由服务端下发，本地无法解密（只有下载的歌带 QTag 密钥）"
            )
        }

        return Decoder(cipher, audioLen, sniffed)
    }

    /** [ContainerRestorer.unsupportedReason] 用：QMC 文件尾部读取失败时的准确说明 */
    const val MISSING_TAIL_REASON = "QMC 尾部数据读取失败"

    /** 正常解码器 */
    private class Decoder(
        private val cipher: QmcCrypto.Cipher,
        private val audioLen: Long,
        sniffedExt: String
    ) : AudioDecoder {
        override val formatName = FORMAT
        override val info = HeaderInfo(
            payloadOffset = 0L,
            suggestedExt = sniffedExt,
            format = FORMAT,
            payloadLength = audioLen
        )

        override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean {
            cipher.decrypt(buf, count, startPos.toInt())
            return true
        }
    }

    /** 命中了 QMC 家族但无法解密的变体（带准确原因给界面展示） */
    private class Unsupported(override val unsupportedReason: String) : AudioDecoder {
        override val formatName = FORMAT
        override val info = HeaderInfo(payloadOffset = 0L, format = FORMAT)
        override val supported = false

        override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean = false
    }
}
