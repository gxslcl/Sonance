package com.sonance.player.data

/**
 * 私有加密容器的统一入口。
 *
 * 上层（[RestoreEngine] / [MediaRepository]）只跟这个对象打交道，
 * 不需要知道具体是酷狗、网易云还是酷我 —— 加一种新格式只要在这里的
 * [probes] 列表里挂一个探测函数，别处一行都不用改。
 *
 * ## 两个层次的探测
 *
 * - [looksLocked]：只看前 64 字节的魔数，**极廉价**。媒体库扫描要遍历几千首歌，
 *   每首都完整解析头部（NCM 要解 AES）是不可接受的，所以那里只用它。
 * - [probe]：完整探测，会解析密钥、元数据、封面，返回**已配置好的解码器实例**。
 *   还原流程才用它。
 */
object ContainerRestorer {

    /**
     * 各格式的探测函数，按「命中概率 × 解析成本」排序：
     * 魔数比对的格式在前，需要解 AES 的 NCM 在后；
     * QMC 没有魔数、密钥在尾部，必须最后且要带 [tail] 窗口。
     */
    private val probes: List<(ByteArray, Long, ByteArray?) -> AudioDecoder?> = listOf(
        { h, s, t -> KgmDecoder.probe(h, s) },
        { h, s, t -> KwmDecoder.probe(h, s) },
        { h, s, t -> NcmDecoder.probe(h, s) },
        { h, s, t -> QmcDecoder.probe(h, s, t) }
    )

    /** 支持的格式标识，供界面展示 */
    val supportedFormats: List<String> = listOf(
        KgmDecoder.FORMAT, NcmDecoder.FORMAT, KwmDecoder.FORMAT, QmcDecoder.FORMAT
    )

    /**
     * 完整探测：命中返回已按该文件头部配置好的解码器，否则 null。
     *
     * @param header   文件开头至少 [AudioDecoder.HEADER_MIN] 字节；NCM 需要 [NcmDecoder.PROBE_BYTES]
     * @param fileSize 文件总字节数，用于校验载荷偏移是否合法
     * @param tail     文件末尾的尾部窗口（QMC 探测必需，其它格式忽略）；
     *                 null 时 QMC 直接跳过
     */
    fun probe(header: ByteArray, fileSize: Long, tail: ByteArray? = null): AudioDecoder? {
        if (header.size < AudioDecoder.HEADER_MIN || fileSize <= 0L) return null
        for (p in probes) {
            val d = p(header, fileSize, tail)
            if (d != null) return d
        }
        return null
    }

    /** [probe] 的便捷版本：只要头部信息 */
    fun probeHeader(header: ByteArray, fileSize: Long): HeaderInfo? =
        probe(header, fileSize)?.info

    /**
     * 廉价判定：这 64 字节是不是某个已知加密容器的魔数。
     *
     * 只做 `startsWith` 比对，不做任何解密，可以放心在扫描循环里逐首调用。
     */
    fun looksLocked(header: ByteArray): Boolean =
        KgmDecoder.looksLike(header) ||
            KwmDecoder.looksLike(header) ||
            NcmDecoder.looksLike(header)

    /**
     * 探测不到时，按文件名给出**准确**的跳过原因。
     *
     * 区分「QMC 暂不支持」和「根本不是加密容器」很重要：前者用户还能期待
     * 后续版本支持，后者说明这个文件本来就不该被还原流程处理。
     */
    fun unsupportedReason(fileName: String): String? = when {
        // QMC 现在能解了：走到这里说明尾部窗口都没读到（读流失败等极端情况）
        QmcDecoder.looksLikeName(fileName) -> QmcDecoder.MISSING_TAIL_REASON
        LockedFormat.isLocked(fileName) -> "未能识别该加密容器的头部"
        else -> null
    }
}
