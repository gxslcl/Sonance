package com.sonance.player.data

/**
 * 已知的加密 / DRM 音乐容器格式登记表。
 *
 * 这些格式是把原始音频套了一层厂商私有加密壳，只有对应厂商的客户端才能解密播放。
 *
 * 本播放器对它们分两步走：
 *  1. **识别与说明**（本文件 + 扫描时的拦截，见 UnplayableFileScanner）；
 *  2. 「私有格式还原」（RestoreEngine + ContainerRestorer）：酷狗 KGM/KGMA/KGG/VPR、
 *     网易云 NCM、酷我 KWM 已支持**本机解密还原**为标准 flac/mp3，算法与密钥内置；
 *     QQ 音乐 QMC/MFLAC 系列目前只识别，解码暂未支持（见 ContainerRestorer）。
 */
object LockedFormat {

    /** 扩展名 -> 来源平台 */
    private val registry: Map<String, String> = mapOf(
        "kgm" to "酷狗音乐",
        "kgma" to "酷狗音乐",
        "kgg" to "酷狗音乐",
        "vpr" to "酷狗音乐",
        "kwm" to "酷我音乐",
        "ncm" to "网易云音乐",
        "qmc0" to "QQ 音乐",
        "qmc2" to "QQ 音乐",
        "qmc3" to "QQ 音乐",
        "qmcflac" to "QQ 音乐",
        "qmcogg" to "QQ 音乐",
        "qmcra" to "QQ 音乐",
        "tkm" to "QQ 音乐",
        "mflac" to "QQ 音乐",
        "mflac0" to "QQ 音乐",
        "mgg" to "QQ 音乐",
        "mgg1" to "QQ 音乐",
        "mggl" to "QQ 音乐",
        "tm0" to "QQ 音乐",
        "tm2" to "QQ 音乐",
        "tm3" to "QQ 音乐",
        "tm6" to "QQ 音乐",
        "x2m" to "QQ 音乐",
        "x3m" to "QQ 音乐",
    )

    /** 平台原生解码器支持的格式（ExoPlayer / MediaCodec） */
    val supportedExtensions: List<String> = listOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus",
        "wav", "amr", "3gp", "mp4", "mka", "weba"
    )

    /** 没有加密、但系统解码器不认识的格式 */
    val plainUnsupportedExtensions: List<String> = listOf(
        "ape", "wma", "dsf", "dff", "aiff", "aif", "tak", "wv", "tta", "mpc", "ra", "mid", "midi"
    )

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isLocked(fileName: String): Boolean = registry.containsKey(extensionOf(fileName))

    fun sourceOf(fileName: String): String? = registry[extensionOf(fileName)]

    fun isPlainUnsupported(fileName: String): Boolean =
        plainUnsupportedExtensions.contains(extensionOf(fileName))

    /** 全部已知加密扩展名（小写），供命名清理与扫描过滤复用 */
    val allExtensions: Set<String> = registry.keys

    /**
     * 文件名里**任意一段**是否命中加密扩展名。
     *
     * 用来识别 `X.kgm.flac` 这种「外壳是普通后缀、里面藏着加密标记」的命名
     * —— 只比较最后一段的 [isLocked] 会漏掉它。
     *
     * 注意：命中只代表**可疑**，不能当最终判定 —— 转换后的成品常常长这样：
     * `X.kgm.flac`（加密，要排除）vs `X.kgm.mp3`（已转换，要保留），
     * 两者名字一模一样。最终判定必须读文件头（见 [ContainerRestorer.looksLocked]）。
     */
    fun looksLikeLockedContainer(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.split('.', ' ', '\t', '(', ')', '[', ']').any { it in registry }
    }

    /** 录音 / 系统提示音等「不是歌曲」的目录特征 */
    private val nonSongFolderTokens: List<String> = listOf(
        "ringtone", "notification", "alarm",
        "recording", "recorder", "callrecord", "call record", "voice recorder",
        "录音", "通话录音", "语音备忘录", "录音机"
    )

    /** 目录路径是否属于录音 / 系统提示音这类非歌曲目录 */
    fun isNonSongFolder(folderPath: String): Boolean {
        if (folderPath.isEmpty()) return false
        val p = folderPath.lowercase()
        return nonSongFolderTokens.any { p.contains(it) }
    }

    /** 去重后的来源平台列表，用于界面展示 */
    val sources: List<String> = registry.values.toSortedSet().toList()

    /** 按来源平台聚合扩展名，例如「酷狗音乐：kgm / kgma / kgg / vpr」 */
    val groupedBySource: List<Pair<String, List<String>>> = registry.entries
        .groupBy({ it.value }, { it.key })
        .map { (source, exts) -> source to exts.sorted() }
        .sortedBy { it.first }
}
