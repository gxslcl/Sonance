package com.sonance.player.data

/** 私有容器（KGM / KGMA / VPR）解析出的头部信息 */
class ContainerHeader(
    /** 加密载荷起始偏移（头部 0x10 处 uint32 LE） */
    val payloadOffset: Int,
    /** 加密方案版本（头部 0x14 处 uint32 LE）。已知 3 = 本实现支持的掩码方案 */
    val version: Int,
    /** 是否为 VPR 变体：魔数不同，解密后还要再异或一层 VprMaskDiff */
    val isVpr: Boolean,
    /** 17 字节密钥：头部 0x1C 起 16 字节，末尾补 0x00 */
    val key: ByteArray
)

/**
 * 酷狗私有音频容器（KGM / KGMA / VPR）脱壳算法。
 *
 * 头部布局：
 * - `[0x00, 0x10)` 16 字节魔数
 * - `[0x10, 0x14)` uint32 LE 载荷偏移
 * - `[0x14, 0x18)` uint32 LE 加密版本
 * - `[0x1C, 0x2C)` 16 字节每文件密钥（补 0x00 组成 17 字节）
 *
 * 逐字节变换（`pos` 为载荷内相对下标）：
 * ```
 * med  = f(key[pos % 17] ^ data[pos])
 * mask = f(MaskV2PreDef[pos % 272] ^ fold(pos ushr 4))
 * out  = med ^ mask
 * ```
 * 其中 `f(x) = x ^ ((x and 0x0F) shl 4)`（对合函数），`fold` 用 Table1/Table2 迭代折叠。
 *
 * 这与官方工具那张 78MB 的 `kgm.mask` 等价：后者只是 `MaskV2PreDef[pos % 272] ^ fold(pos ushr 4)`
 * 的预计算表。这里实时计算，既省掉 78MB 资源，也没有文件大小上限。
 *
 * 纯 Kotlin 实现，不依赖 Android，便于 JVM 单元测试。
 */
object PrivateAudioRestorer {

    const val OFF_MAGIC = 0x00
    const val OFF_PAYLOAD = 0x10
    const val OFF_VERSION = 0x14
    const val OFF_KEY = 0x1C
    const val KEY_LEN = 16

    /** 解析头部所需的最小字节数 = 0x1C + 16 = 44 */
    const val HEADER_MIN = OFF_KEY + KEY_LEN

    /** 本实现支持的加密版本 */
    const val SUPPORTED_VERSION = 3

    private const val MAGIC_LEN = 16
    private const val TABLE_LEN = 272
    private const val FOLD_THRESHOLD = 0x11
    private const val KEY_CYCLE = 17

    // ===== 常量（从公开实现取得，已用真实文件逐字节验证）=====

    private const val KGM_MAGIC_HEX = "7CD532EB86027F4BA8AFA68E0FFF9914"
    private const val VPR_MAGIC_HEX = "0528BC96E9E45A4391AABDD07AF53631"

    private const val TABLE1_HEX =
        "000000000000000000000000000000000001210161012101E101210161012101D223020242420202C2C2020242420202D3D3020363436303E3C3E3036343630" +
        "394B494650404040484848484040404049595959504052505E585A585E5052505D6B696B6D6270606C6C68686C6C60606D7D79797D7D70607E7C7E787E7C7E" +
        "70718381878183818E9080808080808080819191919191919190809290969092909DA3A1A3A5A3A1A3ADA2B0A0A4A4A0A0ADBDB1B1B5B5B1B1BDBDB0A0B6" +
        "B4B6B0B9CBC9C7C1C3C1C7C9CBC9C6D0C0C0C0C9D9D9D9D1D1D1D1D9D9D9D9D0C0D2D0DDEBE9EBEDE3E1E3EDEBE9EBEDE2F0E0EDFDF9F9FDFDF1F1FDFD" +
        "F9F9FDFDF0E0F00200060002000E000200060002000F1"

    private const val TABLE2_HEX =
        "000000000000000000000000000000000001230167012301EF01230167012301DF21020246460202CECE020246460202DEDE020365476503EDCFED03654765" +
        "039DBF9D63040404048C8C8C8C040404049C9C9C9C04052705EB8DAF8DEB052705DBBD9FBDDB250606CACA8E8ECACA0606DADA9E9EDADA0607E9CBE98FE9" +
        "CBE907193B197F193B19E70808080808080808181818181818181808092B096F092B09D7391B395F391B39D7290A0A4E4E0A0AD6D61A1A5E5E1A1AD6D60A" +
        "0B6D4F6D0B95B7957B1D3F1D7B95B7956B0C0C0C0C949494941C1C1C1C949494940C0D2F0DD3B597B5D33D1F3DD3B597B5D32D0E0ED2D29696D2D21E1ED2" +
        "D29696D2D20E0F00220066002200EE00220066002200FE"

    private const val MASK_V2_PRE_DEF_HEX =
        "B8D53DB2E9AF788C8333715176A0CD372F3E358DA9BE98B7E78C22CE5A61DF686989FEA5B6DEA977FCC8BDBDE56D3E5A36EF694EBEE1E9661CF3D902B6F2" +
        "129B44D06FB93589B6466D73820669C1EDD785C230DFA262BE792D62623D0D7EBE48892302A0E4D57551320253FD163A213B160FC3B2BBB3E2BA3A3D13EC" +
        "F6014584A5700F93490C64CD31D5CC4C07019E001A2390BF881E3BABA63EC47347107E3B5EBCE30084FF09D4E0890F5B58704FFB65D85C531BD3C8C6BFEF" +
        "98B0504F0FEAE583588C282C8467CDD09E47DB2750CAF46363E8977F1B4B0CC2C1214CCC58F59452A3F3D3E068F40023F35E0A7B93DDAB12B213E884D7A7" +
        "9F0F324C551D043652DC03F3F94E42E93D61EF7CB6B39350"

    private const val VPR_MASK_DIFF_HEX = "25DFE8A6751E750E2F80F32DB8B6E31100"

    private val KGM_MAGIC = unhex(KGM_MAGIC_HEX)
    private val VPR_MAGIC = unhex(VPR_MAGIC_HEX)
    private val TABLE1 = unhex(TABLE1_HEX)
    private val TABLE2 = unhex(TABLE2_HEX)
    private val MASK_V2_PRE_DEF = unhex(MASK_V2_PRE_DEF_HEX)
    private val VPR_MASK_DIFF = unhex(VPR_MASK_DIFF_HEX)

    /** 头部是否命中受支持的容器魔数（KGM/KGMA 或 VPR） */
    fun isSupportedContainer(header: ByteArray): Boolean =
        startsWith(header, KGM_MAGIC) || startsWith(header, VPR_MAGIC)

    /**
     * 解析头部。魔数不匹配、字节不足或偏移字段非法时返回 null。
     * 版本是否受支持由调用方判断（见 [SUPPORTED_VERSION]）。
     */
    fun parseHeader(header: ByteArray): ContainerHeader? {
        if (header.size < HEADER_MIN) return null
        val isVpr = when {
            startsWith(header, KGM_MAGIC) -> false
            startsWith(header, VPR_MAGIC) -> true
            else -> return null
        }
        val payloadOffset = readUInt32LE(header, OFF_PAYLOAD) ?: return null
        if (payloadOffset < HEADER_MIN) return null
        val version = readUInt32LE(header, OFF_VERSION) ?: return null
        val key = ByteArray(KEY_CYCLE)
        System.arraycopy(header, OFF_KEY, key, 0, KEY_LEN)
        key[KEY_LEN] = 0
        return ContainerHeader(payloadOffset, version, isVpr, key)
    }

    /**
     * 就地解密一段载荷。
     *
     * 契约：`buf` 的 `[0, count)` 对应载荷内的 `[startPos, startPos + count)`。
     * 分块调用时，每块都应放在缓冲区**开头**，并用它在载荷内的绝对下标作为 [startPos]
     * —— 即 `RestoreEngine` 流式读取的用法。
     *
     * @param buf      数据缓冲区（会被就地改写）
     * @param count    有效字节数
     * @param startPos 这段数据在**载荷内**的起始下标（跨块调用时必须连续递增）
     * @param key      17 字节密钥（见 [ContainerHeader.key]）
     * @param isVpr    是否 VPR 变体
     */
    fun xorChunk(buf: ByteArray, count: Int, startPos: Int, key: ByteArray, isVpr: Boolean) {
        if (count <= 0 || key.isEmpty()) return
        var i = 0
        while (i < count) {
            val pos = startPos + i
            // fold 只依赖 pos ushr 4，每 16 字节才变一次，按 16 字节块算一遍即可
            val foldValue = fold(pos ushr 4)
            val block = minOf(16 - (pos and 15), count - i)
            var j = 0
            while (j < block) {
                val p = pos + j
                var med = (key[p % key.size].toInt() and 0xFF) xor (buf[i + j].toInt() and 0xFF)
                med = med xor ((med and 0x0F) shl 4)

                var msk = (MASK_V2_PRE_DEF[p % TABLE_LEN].toInt() and 0xFF) xor foldValue
                msk = msk xor ((msk and 0x0F) shl 4)

                var v = (med xor msk) and 0xFF
                if (isVpr) v = v xor (VPR_MASK_DIFF[p % KEY_CYCLE].toInt() and 0xFF)
                buf[i + j] = v.toByte()
                j++
            }
            i += block
        }
    }

    /** 整块解密（返回新数组，便于测试与小文件使用） */
    fun deobfuscate(payload: ByteArray, key: ByteArray, isVpr: Boolean): ByteArray {
        val out = payload.copyOf()
        xorChunk(out, out.size, 0, key, isVpr)
        return out
    }

    /** 依据解密后数据头部判定后缀，返回 ".flac" 或 ".mp3" */
    fun sniffExtension(buf: ByteArray): String {
        if (buf.size >= 4 &&
            (buf[0].toInt() and 0xFF) == 0x66 && (buf[1].toInt() and 0xFF) == 0x4C &&
            (buf[2].toInt() and 0xFF) == 0x61 && (buf[3].toInt() and 0xFF) == 0x43
        ) return ".flac" // 'fLaC'
        if (buf.size >= 3 &&
            (buf[0].toInt() and 0xFF) == 0x49 && (buf[1].toInt() and 0xFF) == 0x44 &&
            (buf[2].toInt() and 0xFF) == 0x33
        ) return ".mp3" // 'ID3'
        if (buf.size >= 2) {
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return ".mp3" // MPEG 帧同步
        }
        return ".mp3"
    }

    /**
     * 由源文件名推导输出文件的主干名：剥掉最外层扩展名，以及名字里残留的加密容器标记。
     *
     * 判定用的是 [LockedFormat] 的完整登记表，因此「包括但不限于 .kgm」——
     * kgma / kgg / vpr / ncm / qmc* / mflac / mgg / tkm / x2m … 都会被清掉。
     *
     * ```
     * "A - B.kgm.flac"     -> "A - B"
     * "A - B.kgm (1).flac" -> "A - B (1)"
     * "A - B.ncm.mp3"      -> "A - B"
     * "A - B.qmcflac"      -> "A - B"
     * "A - B.flac"         -> "A - B"      (本来就是普通文件，原样保留)
     * ```
     */
    fun outputStem(sourceName: String): String {
        var stem = sourceName.substringBeforeLast('.', sourceName)
        // 结尾的 " (1)" 先摘下来，剥完容器标记再补回去
        val tail = TRAILING_INDEX.find(stem)?.value.orEmpty()
        if (tail.isNotEmpty()) stem = stem.dropLast(tail.length)

        // 反复剥掉结尾的容器标记，例如 "X.kgm" -> "X"
        while (true) {
            val dot = stem.lastIndexOf('.')
            if (dot <= 0 || !LockedFormat.isLocked(stem)) break
            stem = stem.substring(0, dot)
        }

        // 全被剥光时兜底，避免生成 ".mp3" 这种隐藏文件
        if (stem.isBlank()) {
            stem = sourceName.substringBeforeLast('.', sourceName).ifBlank { "restored" }
        }
        return stem + tail
    }

    private val TRAILING_INDEX = Regex("""\s*\(\d+\)$""")

    /** 容错解析十六进制：忽略空格、冒号、逗号、短横线、"0x" 前缀；奇数长度或非法字符返回 null；空串返回 null */
    fun hexToBytes(text: String): ByteArray? {
        val cleaned = text
            .replace("0x", "", ignoreCase = true)
            .filterNot { it == ' ' || it == ':' || it == ',' || it == '-' || it == '\t' || it == '\n' || it == '\r' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // ===== 内部实现 =====

    /**
     * 用 Table1/Table2 迭代折叠 `offset`，得到掩码表的第 offset 项。
     * 等价于官方 78MB `kgm.mask` 里 `MaskV2[offset]` 的值。
     */
    private fun fold(offset: Int): Int {
        var off = offset
        var v = 0
        while (off >= FOLD_THRESHOLD) {
            v = v xor (TABLE1[off % TABLE_LEN].toInt() and 0xFF)
            off = off ushr 4
            v = v xor (TABLE2[off % TABLE_LEN].toInt() and 0xFF)
            off = off ushr 4
        }
        return v and 0xFF
    }

    private fun startsWith(buf: ByteArray, prefix: ByteArray): Boolean {
        if (buf.size < prefix.size) return false
        for (i in prefix.indices) {
            if (buf[i] != prefix[i]) return false
        }
        return true
    }

    private fun readUInt32LE(buf: ByteArray, off: Int): Int? {
        if (off < 0 || off + 4 > buf.size) return null
        val v = (buf[off].toLong() and 0xFFL) or
                ((buf[off + 1].toLong() and 0xFFL) shl 8) or
                ((buf[off + 2].toLong() and 0xFFL) shl 16) or
                ((buf[off + 3].toLong() and 0xFFL) shl 24)
        if (v > Int.MAX_VALUE.toLong()) return null
        return v.toInt()
    }

    /** 解析内部常量用的十六进制串（内容固定，长度必然合法） */
    private fun unhex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
