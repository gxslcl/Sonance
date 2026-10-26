package com.sonance.player.data

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * M4A（MP4 容器）的标签写入：把歌名 / 歌手 / 专辑 / 封面写进
 * `moov.udta.meta.ilst`（iTunes 风格原子）。
 *
 * ## 为什么还原管线需要它
 *
 * [RestoreTagWriter] 的两条路（MP3 前置 ID3、FLAC 插 PICTURE 块）都依赖
 * 「标签在音频数据之前」这个前提，MP4 做不到：元数据住在 moov box 里、
 * 音频在 mdat，两者的相对位置因文件而异。所以 m4a 只能**整文件重排**。
 *
 * ## 为什么敢动 moov（CoverTagWriter 不敢，这里敢）
 *
 * [CoverTagWriter] 拒绝 M4A 是怕写坏**用户的**文件；这里的处理对象是
 * 转换管线里**刚解密出来的本地临时文件**，写坏最多这一条转换失败。
 * 而且替换前允许调用方对临时文件做完整性检查（[tag] 的 verify 参数），
 * 检查不过就放弃替换 —— 原文件保持原样。
 *
 * ## 重排规则
 *
 * 1. 解析顶层 box 序列（ftyp / moov / mdat / free…），定位 moov 与第一个 mdat；
 * 2. moov 里已有的 `meta`（moov 层或 udta 层）整体剥离 —— 调用方给的元数据是
 *    权威来源，旧标签直接覆盖（网易云缓存的 ilst 只有内部原子、没有歌名歌手，
 *    保留它成品就永远是「未知艺术家」）；
 * 3. 新建的 `meta`（含 hdlr + ilst）装进 udta（没有 udta 就连它一起建）挂到 moov 末尾；
 * 4. moov 变大 delta 字节。若 moov 在 mdat 之前，mdat 的绝对位置整体后移 delta，
 *    所有 chunk offset（stco 32 位 / co64 64 位）必须同步 +delta，否则音频整段错位；
 *    moov 在 mdat 之后则无需动。
 * 5. 先写临时文件、检查、再替换，任何结构异常都返回 false 且不碰原文件。
 *
 * 纯 JVM 实现（Log 除外），单测用手工构造的最小 m4a 覆盖。
 */
internal object Mp4TagWriter {

    private const val TAG = "SonanceNc"

    /**
     * 把 [info] 的歌名 / 歌手 / 专辑与 [cover] 写进 m4a。
     *
     * @param verify 替换前对**写好的临时文件**做完整性检查（moov 重排是否成功），
     *               返回 false 就放弃替换；null 表示不检查
     * @return true = 写入成功；false = 结构不认识 / 检查未过 / 写失败
     */
    fun tag(
        file: File,
        info: HeaderInfo,
        cover: ByteArray?,
        verify: ((File) -> Boolean)? = null
    ): Boolean {
        if (!file.isFile) return false
        return try {
            val bytes = file.readBytes()
            val patched = patch(bytes, info, cover) ?: return false
            val tmp = File(file.parentFile, file.name + ".tagtmp")
            try {
                tmp.writeBytes(patched)
                if (verify != null && !verify(tmp)) {
                    Log.w(TAG, "m4a 标签写入后的完整性检查未通过，放弃替换")
                    return false
                }
                if (!tmp.renameTo(file)) {
                    // rename 不覆盖已存在的目标时：先删再试（临时产物，可承受）
                    file.delete()
                    if (!tmp.renameTo(file)) return false
                }
                true
            } finally {
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "m4a 标签写入失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    // ===== 结构改写 =====

    /** moov 的一个子 box：[offset] 起、[headerSize] 字节头、[end] 是下一个 box 的起点 */
    private class Box(val type: String, val offset: Int, val headerSize: Int, val end: Int) {
        val contentStart: Int get() = offset + headerSize
    }

    /**
     * 生成打过标签的完整文件字节；结构不认识返回 null（调用方放弃）。
     */
    private fun patch(bytes: ByteArray, info: HeaderInfo, cover: ByteArray?): ByteArray? {
        val top = parseBoxes(bytes, 0, bytes.size) ?: return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val mdat = top.firstOrNull { it.type == "mdat" }
        val moovChildren = parseBoxes(bytes, moov.contentStart, moov.end) ?: return null

        val meta = buildMetaBox(info, cover)
        val newMoov: ByteArray = buildNewMoov(bytes, moov, moovChildren, meta) ?: return null
        val oldMoovSize = moov.end - moov.offset
        val delta = (newMoov.size - oldMoovSize).toLong()

        val out = ByteArray(bytes.size + delta.toInt())
        System.arraycopy(bytes, 0, out, 0, moov.offset)
        System.arraycopy(newMoov, 0, out, moov.offset, newMoov.size)
        System.arraycopy(bytes, moov.end, out, moov.offset + newMoov.size, bytes.size - moov.end)

        // moov 在 mdat 前面：mdat 整体后移 delta，chunk offset 全部跟着 +delta。
        // 范围从 moov 的 body 开始（parseBoxes 处理的是「子 box 序列」，moov 自身不在其中）
        if (mdat != null && mdat.offset > moov.offset) {
            if (!shiftChunkOffsets(out, moov.contentStart, moov.offset + newMoov.size, delta)) {
                return null
            }
        }
        return out
    }

    /**
     * 把 moov 重写为「旧内容剥掉 meta + 新 udta(meta)」。
     *
     * 调用方给的 info / cover 是权威来源，旧标签不保留：moov 层的 `meta`、
     * udta 层的 `meta` 一律丢弃；udta 剥完还剩别的子 box 就原样保留
     * （容器头尺寸同步改小），剥完为空就连 udta 一起丢，最后统一挂一个新的
     * `udta(meta)`。剥旧填新后 moov 变小也没问题 —— mdat 平移量 delta 允许为负。
     */
    private fun buildNewMoov(
        bytes: ByteArray,
        moov: Box,
        children: List<Box>,
        meta: ByteArray
    ): ByteArray? {
        val parts = ArrayList<ByteArray>(children.size + 1)
        for (child in children) {
            when (child.type) {
                "meta" -> Unit   // 旧 moov 层 meta：丢弃
                "udta" -> {
                    val inner = parseBoxes(bytes, child.contentStart, child.end)
                    if (inner == null) {
                        // 结构读不出来：整块原样保留，不冒险丢内容
                        parts += bytes.copyOfRange(child.offset, child.end)
                    } else {
                        val kept = inner.filter { it.type != "meta" }
                        if (kept.isNotEmpty()) {
                            val body = kept.map { bytes.copyOfRange(it.offset, it.end) }
                                .reduce { a, b -> a + b }
                            parts += buildHeader(child, child.headerSize + body.size) + body
                        }
                        // kept 为空：udta 里本来只有 meta，连容器一起丢
                    }
                }
                else -> parts += bytes.copyOfRange(child.offset, child.end)
            }
        }
        parts += box("udta", meta)
        val body = parts.reduce { a, b -> a + b }
        // moov 头保持原头型（32 位 / 64 位 largesize）；声明尺寸 = 头 + body
        return buildHeader(moov, moov.headerSize + body.size) + body
    }

    /** 按 box 原来的头型（普通 / largesize）生成同型新头 */
    private fun buildHeader(box: Box, newSize: Int): ByteArray = when (box.headerSize) {
        16 -> u32(1) + box.type4 + u64(newSize.toLong())
        else -> u32(newSize) + box.type4
    }

    /**
     * meta 是 full box（4 字节 version/flags）+ hdlr + ilst。
     * hdlr 按 QuickTime 规范填 `mdir/appl`（mutagen 同款），缺了它
     * MediaMetadataRetriever 会把整个 meta 当作不认识的块跳过。
     */
    private fun buildMetaBox(info: HeaderInfo, cover: ByteArray?): ByteArray {
        val atoms = ArrayList<ByteArray>(4)
        putTextAtom(atoms, "\u00A9nam", info.title)
        putTextAtom(atoms, "\u00A9ART", info.artist)
        putTextAtom(atoms, "\u00A9alb", info.album)
        if (cover != null && cover.isNotEmpty()) {
            // data type：13 = JPEG，14 = PNG（写错部分播放器不认）
            val dataType = if (isPng(cover)) 14 else 13
            atoms += ilstAtom("covr", dataAtom(dataType, cover))
        }
        val ilst = if (atoms.isEmpty()) ByteArray(0) else atoms.reduce { a, b -> a + b }
        val hdlr = box(
            "hdlr",
            ByteArray(4) +                      // version/flags
                ByteArray(4) +                  // pre_defined
                "mdir".toByteArray(StandardCharsets.ISO_8859_1) +
                "appl".toByteArray(StandardCharsets.ISO_8859_1) +
                ByteArray(12) + byteArrayOf(0)  // reserved + 空 name
        )
        return box("meta", ByteArray(4) + hdlr + box("ilst", ilst))
    }

    private fun putTextAtom(atoms: MutableList<ByteArray>, type: String, raw: String) {
        val v = raw.trim()
        if (v.isEmpty()) return
        // data type 1 = UTF-8 文本
        atoms += ilstAtom(type, dataAtom(1, v.toByteArray(Charsets.UTF_8)))
    }

    /** ilst 的子原子：`<type4><data atom>` */
    private fun ilstAtom(type: String, data: ByteArray): ByteArray =
        box(type, data)

    /** `<size>"data"<type flags><locale 0><payload>` */
    private fun dataAtom(typeFlags: Int, payload: ByteArray): ByteArray =
        box("data", u32(typeFlags) + u32(0) + payload)

    private fun isPng(b: ByteArray): Boolean = b.size >= 4 &&
        (b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun box(type: String, body: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        require(typeBytes.size == 4) { "box type 必须是 4 字节: $type" }
        return u32(body.size + 8) + typeBytes + body
    }

    // ===== chunk offset 修正 =====

    /**
     * 在 `[from, to)` 范围内递归找 stco / co64，把每个 entry 加上 [delta]。
     * 只下钻容器类型，绝不把 mdat / free 的载荷当 box 头解析。
     */
    private fun shiftChunkOffsets(bytes: ByteArray, from: Int, to: Int, delta: Long): Boolean {
        val boxes = parseBoxes(bytes, from, to) ?: return false
        for (b in boxes) {
            when (b.type) {
                "stco" -> {
                    var p = b.contentStart + 4     // 跳过 version/flags
                    val n = u32(bytes, p).toInt(); p += 4
                    repeat(n) {
                        val v = u32(bytes, p).toLong() and 0xFFFFFFFFL
                        val shifted = v + delta
                        if (shifted < 0 || shifted > 0xFFFFFFFFL) return false
                        putU32(bytes, p, shifted.toInt())
                        p += 4
                    }
                }
                "co64" -> {
                    var p = b.contentStart + 4
                    val n = u32(bytes, p).toInt(); p += 4
                    repeat(n) {
                        putU64(bytes, p, u64(bytes, p) + delta)
                        p += 8
                    }
                }
                // 只有这几个是「子 box 序列」容器；stco/co64 的载荷不是 box，绝不能递归
                "trak", "mdia", "minf", "stbl", "edts", "udta" ->
                    if (!shiftChunkOffsets(bytes, b.contentStart, b.end, delta)) return false
            }
        }
        return true
    }

    // ===== box 遍历 =====

    /**
     * 解析 `[from, to)` 里的连续 box 序列。
     * 结构可疑（尺寸越界、尾部长度不足一个头）返回 null；尾部 <8 字节的零头容忍忽略。
     */
    private fun parseBoxes(bytes: ByteArray, from: Int, to: Int): List<Box>? {
        if (from < 0 || to > bytes.size || from > to) return null
        val out = ArrayList<Box>(8)
        var pos = from
        while (pos + 8 <= to) {
            var size = u32(bytes, pos).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L) {
                if (pos + 16 > to) return null
                size = u64(bytes, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()          // 到区域末尾
            }
            if (size < header) return null
            val end = pos + size
            if (end > to) return null
            val type = String(bytes, pos + 4, 4, StandardCharsets.ISO_8859_1)
            out += Box(type, pos, header, end.toInt())
            pos = end.toInt()
        }
        return out
    }

    // ===== 字节读写（大端） =====

    private fun u32(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)

    private fun putU32(b: ByteArray, pos: Int, v: Int) {
        b[pos] = (v ushr 24).toByte(); b[pos + 1] = (v ushr 16).toByte()
        b[pos + 2] = (v ushr 8).toByte(); b[pos + 3] = v.toByte()
    }

    private fun u64(b: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
        return v
    }

    private fun putU64(b: ByteArray, pos: Int, v: Long) {
        for (i in 0 until 8) b[pos + i] = (v ushr ((7 - i) * 8)).toByte()
    }

    // ===== 小工具 =====

    private val Box.type4: ByteArray
        get() {
            val raw = type.toByteArray(StandardCharsets.ISO_8859_1)
            return if (raw.size == 4) raw else ByteArray(4)
        }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = copyOf(size + other.size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun u64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr ((7 - i) * 8)).toByte()
        return out
    }
}
