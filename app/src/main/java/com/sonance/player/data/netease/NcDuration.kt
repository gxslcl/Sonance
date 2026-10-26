package com.sonance.player.data.netease

import java.io.File
import java.io.RandomAccessFile

/**
 * 音频**真实时长**探测 —— 区分「完整歌曲」与「试听残曲」的最终裁决。
 *
 * 背景：网易云按 320KB 固定块缓存音频，没播完 / 只试听的歌只留下一两块，
 * 文件本身是一个完整封装的短音频（mp4 的 moov / flac 的 STREAMINFO 都在），
 * 只是内容被截短了。所以**解密之后**读容器元数据里的时长，比任何
 * 按大小猜」都可靠：试听 30 秒、块截断 10~40 秒全部现形。
 *
 * 探测不到时长（ogg、损坏文件）返回 null —— 判不了就不拦，宁可放过。
 * 全部纯 JVM 实现，单测直接覆盖。
 */
object NcDuration {

    /**
     * 残曲判定阈值：**低于此时长一律视为残曲，不进曲库**。
     * 用户口径：小于 150 秒的都是残曲（试听 / 未播完的缓存残段）。
     */
    const val TRIAL_MIN_SECONDS = 150.0

    fun probeSeconds(file: File): Double? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            when (val head = readHead(raf, 12)) {
                is Mp4Head -> mp4Seconds(raf, file.length())
                is FlacHead -> flacSeconds(raf)
                is WavHead -> wavSeconds(raf)
                else -> {
                    if (head != null) mp3Seconds(file) else null
                }
            }
        }
    }.getOrNull()

    // ===== MP4 / M4A =====

    private object Mp4Head
    private object FlacHead
    private object WavHead

    private fun readHead(raf: RandomAccessFile, n: Int): Any? {
        raf.seek(0)
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = raf.read(b, off, n - off)
            if (r <= 0) break
            off += r
        }
        if (off < 8) return null
        // ftyp
        if (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        ) return Mp4Head
        // fLaC
        if (b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()
        ) return FlacHead
        // RIFF....WAVE
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            off >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() &&
            b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()
        ) return WavHead
        return b // 可能是 mp3（含 ID3 或裸帧），交给 mp3Seconds
    }

    /**
     * 遍历顶层 box 找 moov，再在 moov 里找 mvhd。
     * mdat 体积大但只需跳过 —— [RandomAccessFile.seek] 不读内容，
     * 即使 moov 在文件尾部（非 faststart）也只是几次 seek。
     */
    private fun mp4Seconds(raf: RandomAccessFile, len: Long): Double? {
        var pos = 0L
        while (pos + 8 <= len) {
            val size = readU32(raf, pos)
            val type = readType(raf, pos + 4)
            var header = 8
            val boxSize = when {
                size == 1L -> {
                    header = 16
                    readU64(raf, pos + 8)
                }
                size == 0L -> len - pos   // 到文件尾
                else -> size
            }
            if (boxSize < header || pos + boxSize > len) return null
            if (type == "moov") {
                val d = mvhdSeconds(raf, pos + header, pos + boxSize)
                if (d != null) return d
            }
            pos += boxSize
        }
        return null
    }

    /** moov 的 body 范围内找 mvhd 子 box（通常就是第一个） */
    private fun mvhdSeconds(raf: RandomAccessFile, from: Long, to: Long): Double? {
        var pos = from
        while (pos + 8 <= to) {
            val size = readU32(raf, pos)
            val type = readType(raf, pos + 4)
            if (size < 8 || pos + size > to) return null
            if (type == "mvhd") {
                val version = readU8(raf, pos + 8)
                val ts: Long
                val dur: Long
                if (version == 1L) {
                    ts = readU32(raf, pos + 28)
                    dur = readU64(raf, pos + 32)
                } else {
                    ts = readU32(raf, pos + 20)
                    dur = readU32(raf, pos + 24)
                }
                if (ts > 0) return dur.toDouble() / ts
                return null
            }
            pos += size
        }
        return null
    }

    // ===== m4a 数据完整性 =====

    /** m4a 完整性探测结果：容器声明的时长 vs 音频数据实际覆盖的时长 */
    class Mp4Completeness internal constructor(
        /** mvhd 声明的总时长（秒）——缓存被截短时它声明的仍是整首歌的时长 */
        val declaredSeconds: Double,
        /** 采样数据真正落进 mdat 的部分折算的时长（秒） */
        val playableSeconds: Double
    ) {
        /** 数据截短视为不完整；1 秒以内算封装误差 */
        val complete: Boolean get() = playableSeconds >= declaredSeconds - 1.0
    }

    /**
     * m4a 的**数据完整性**探测：网易云按块缓存，没缓存完整的歌解出来是
     * 「moov 完整（声明的还是整首时长，能骗过 150 秒阈值）+ mdat 只有前半段」
     * ——播放器放到数据尽头就报 2008。这里解析采样表（stts/stsc/stsz/stco/co64），
     * 把每个采样的数据是否落在 mdat 里逐一对账，算出**实际可播放时长**。
     *
     * 只认 stbl 结构；fragmented mp4（moof）与解析不了的文件返回 null ——
     * 判不了就不拦，宁可放过（与 [probeSeconds] 同一哲学）。
     */
    fun completeness(file: File): Mp4Completeness? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (readHead(raf, 12) != Mp4Head) return@runCatching null
            mp4Completeness(raf, file.length())
        }
    }.getOrNull()

    private fun mp4Completeness(raf: RandomAccessFile, len: Long): Mp4Completeness? {
        var moov: LongArray? = null
        var mdatEnd = -1L
        var pos = 0L
        while (pos + 8 <= len) {
            val size = readU32(raf, pos)
            val type = readType(raf, pos + 4)
            var header = 8L
            val boxSize = when {
                size == 1L -> { header = 16; readU64(raf, pos + 8) }
                size == 0L -> len - pos
                else -> size
            }
            if (boxSize < header) return null
            when (type) {
                // 被截短的 mdat：声明的 size 比文件实际还大 —— 数据到哪算哪
                "mdat" -> mdatEnd = minOf(pos + boxSize, len)
                else -> {
                    if (pos + boxSize > len) return null
                    if (type == "moov") moov = longArrayOf(pos + header, pos + boxSize)
                }
            }
            pos += boxSize
        }
        val mv = moov ?: return null
        val declared = mvhdSeconds(raf, mv[0], mv[1]) ?: return null
        if (declared <= 0.0 || mdatEnd <= 0) return null

        // moov 里找第一组采样表齐全的 trak（音频轨）：trak → mdia → (mdhd, minf → stbl)
        var p = mv[0]
        while (p + 8 <= mv[1]) {
            val size = readU32(raf, p)
            val type = readType(raf, p + 4)
            var header = 8
            val boxSize = when {
                size == 1L -> { header = 16; readU64(raf, p + 8) }
                size == 0L -> mv[1] - p
                else -> size
            }
            if (boxSize < header || p + boxSize > mv[1]) return null
            if (type == "trak") {
                val r = trakCompleteness(raf, p + header, p + boxSize, mdatEnd, declared)
                if (r != null) return r
            }
            p += boxSize
        }
        return null
    }

    /** [from, to) 范围内第一个 [type] 子 box 的 body 范围（[起点, 终点)） */
    private fun boxBody(raf: RandomAccessFile, from: Long, to: Long, type: String): LongArray? {
        var pos = from
        while (pos + 8 <= to) {
            val size = readU32(raf, pos)
            val t = readType(raf, pos + 4)
            var header = 8L
            val boxSize = when {
                size == 1L -> { header = 16; readU64(raf, pos + 8) }
                size == 0L -> to - pos
                else -> size
            }
            if (boxSize < header || pos + boxSize > to) return null
            if (t == type) return longArrayOf(pos + header, pos + boxSize)
            pos += boxSize
        }
        return null
    }

    /** full box 表格的解析起点：跳过 version/flags(4) + entryCount(4)，返回 (条目区起点, 条目数) */
    private fun tableHead(raf: RandomAccessFile, box: LongArray): Pair<Long, Long>? {
        val count = readU32(raf, box[0] + 4)
        if (count < 0 || count > 2_000_000L) return null   // 异常大的表按解析失败处理
        return (box[0] + 8) to count
    }

    private fun trakCompleteness(
        raf: RandomAccessFile,
        from: Long,
        to: Long,
        mdatEnd: Long,
        declared: Double
    ): Mp4Completeness? {
        val mdia = boxBody(raf, from, to, "mdia") ?: return null
        val mdhd = boxBody(raf, mdia[0], mdia[1], "mdhd") ?: return null
        val version = readU8(raf, mdhd[0])
        val timescale: Long
        if (version == 1L) {
            timescale = readU32(raf, mdhd[0] + 20)
        } else {
            timescale = readU32(raf, mdhd[0] + 12)
        }
        if (timescale <= 0) return null

        val minf = boxBody(raf, mdia[0], mdia[1], "minf") ?: return null
        val stbl = boxBody(raf, minf[0], minf[1], "stbl") ?: return null
        val sttsBox = boxBody(raf, stbl[0], stbl[1], "stts") ?: return null
        val stscBox = boxBody(raf, stbl[0], stbl[1], "stsc") ?: return null
        val stszBox = boxBody(raf, stbl[0], stbl[1], "stsz") ?: return null
        val stcoBox = boxBody(raf, stbl[0], stbl[1], "stco")
        val co64Box = if (stcoBox == null) boxBody(raf, stbl[0], stbl[1], "co64") else null
        if (stcoBox == null && co64Box == null) return null

        // ---- stts：[count, delta, ...] 成对 ----
        val (sttsPos, sttsCount) = tableHead(raf, sttsBox) ?: return null
        if (sttsCount <= 0L) return null
        val stts = LongArray((sttsCount * 2).toInt())
        for (i in stts.indices) {
            stts[i] = readU32(raf, sttsPos + i * 4L)
        }

        // ---- stsc：[firstChunk, samplesPerChunk, sdi, ...] 三元组，sdi 不用 ----
        val (stscPos, stscCount) = tableHead(raf, stscBox) ?: return null
        if (stscCount <= 0L) return null
        val stsc = LongArray(stscCount.toInt() * 3)
        for (i in stsc.indices) stsc[i] = readU32(raf, stscPos + i * 4L)

        // ---- stsz：固定 sampleSize 或逐样本数组 ----
        val uniform = readU32(raf, stszBox[0] + 4)
        val sampleCount = readU32(raf, stszBox[0] + 8)
        if (sampleCount <= 0L || sampleCount > 2_000_000L) return null
        val uniformSize = uniform
        val sizes = if (uniformSize > 0L) LongArray(0) else {
            LongArray(sampleCount.toInt()) { i -> readU32(raf, stszBox[0] + 12 + i * 4L) }
        }

        // ---- stco / co64：chunk 绝对偏移 ----
        val offsets: LongArray = if (stcoBox != null) {
            val (p, n) = tableHead(raf, stcoBox) ?: return null
            if (n <= 0L) return null
            LongArray(n.toInt()) { i -> readU32(raf, p + i * 4L) }
        } else {
            val (p, n) = tableHead(raf, co64Box!!) ?: return null
            if (n <= 0L) return null
            LongArray(n.toInt()) { i -> readU64(raf, p + i * 8L) }
        }

        // 每个 chunk 的采样数（stsc 条目覆盖到下一 firstChunk 为止）
        val chunkCount = offsets.size
        val spcs = LongArray(chunkCount)
        var e = 0
        for (c in 1..chunkCount) {
            while ((e + 1) * 3 < stsc.size && stsc[(e + 1) * 3] <= c) e++
            spcs[c - 1] = stsc[e * 3 + 1]
            if (spcs[c - 1] <= 0L) return null
        }

        // 逐 chunk 逐采样对账：数据落在 mdat 里的才算可播放时长
        val cursor = SttsCursor(stts)
        var coveredDur = 0.0
        var totalDur = 0.0
        var sampleIdx = 0
        for (c in 1..chunkCount) {
            val off = offsets[c - 1]
            var bytes = 0L
            repeat(spcs[c - 1].toInt()) {
                val d = cursor.next() ?: return null
                val sz = if (uniformSize > 0L) uniformSize else sizes.getOrElse(sampleIdx) { return null }
                totalDur += d
                if (off >= 0 && off + bytes + sz <= mdatEnd) coveredDur += d
                sampleIdx++
                bytes += sz
            }
        }
        if (totalDur <= 0.0) return null
        return Mp4Completeness(
            declaredSeconds = declared,
            playableSeconds = coveredDur / timescale
        )
    }

    /** stts 的游标：按采样顺序逐个吐 delta，条目间自动衔接 */
    private class SttsCursor(private val stts: LongArray) {
        private val entries = stts.size / 2
        private var e = 0
        private var left = if (entries > 0) stts[0] else 0L

        fun next(): Long? {
            while (left <= 0L) {
                e++
                if (e >= entries) return null
                left = stts[e * 2]
            }
            left--
            return stts[e * 2 + 1]
        }
    }

    // ===== FLAC =====

    /** STREAMINFO 固定是第一个块：'fLaC' + 4 字节块头 + 34 字节数据 */
    private fun flacSeconds(raf: RandomAccessFile): Double? {
        val d = ByteArray(34)
        raf.seek(8)
        if (readFully(raf, d) != 34) return null
        fun u(i: Int): Long = d[i].toLong() and 0xFF
        // STREAMINFO 位流（大端位序）：sampleRate<20> channels<3> bps<5> totalSamples<36>
        // → sampleRate = b10b11 + b12 高 4 位；totalSamples = b13 低 4 位 + b14..b17
        val sampleRate = (u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)
        if (sampleRate <= 0L) return null
        val totalSamples =
            ((u(13) and 0x0F) shl 32) or (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17)
        return totalSamples.toDouble() / sampleRate
    }

    // ===== WAV =====

    /** 遍历 chunk：data 的大小 ÷ fmt 里的 byteRate */
    private fun wavSeconds(raf: RandomAccessFile): Double? {
        val len = raf.length()
        var pos = 12L
        var byteRate = 0L
        var dataSize = 0L
        while (pos + 8 <= len) {
            val id = readType(raf, pos)
            val size = readU32(raf, pos + 4)
            if (size < 0 || pos + 8 + size > len) return null
            when (id) {
                "fmt " -> byteRate = readU32(raf, pos + 16)   // data 内偏移 8
                "data" -> dataSize = size
            }
            if (byteRate > 0 && dataSize > 0) return dataSize.toDouble() / byteRate
            pos += 8 + size + (size and 1)   // chunk 按 2 字节对齐
        }
        return null
    }

    // ===== MP3 =====

    /**
     * 没有总时长字段，用首个帧头的码率估算：
     * `(文件大小 × 8) ÷ 码率`。VBR 与 ID3 标签会带来 ±10% 级误差，
     * 但判定阈值是 150 秒、残曲在几十秒级，误差完全不构成干扰。
     */
    private fun mp3Seconds(file: File): Double? {
        val head = file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            val n = s.read(buf)
            if (n <= 0) null else buf.copyOf(n)
        } ?: return null

        var pos = 0
        // 跳过 ID3v2（大小按 syncsafe 编码）
        if (head.size >= 10 && head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        ) {
            val sz = ((head[6].toLong() and 0x7F) shl 21) or
                ((head[7].toLong() and 0x7F) shl 14) or
                ((head[8].toLong() and 0x7F) shl 7) or
                (head[9].toLong() and 0x7F)
            pos = (10 + sz).toInt()
        }
        while (pos + 4 <= head.size) {
            val b0 = head[pos].toInt() and 0xFF
            val b1 = head[pos + 1].toInt() and 0xFF
            val b2 = head[pos + 2].toInt() and 0xFF
            if (b0 == 0xFF && b1 and 0xE0 == 0xE0 && b1 and 0x06 != 0 && // sync + 非保留 version/layer
                b2 and 0xF0 != 0xF0 && b2 and 0x0C != 0x0C
            ) {
                val versionBits = (b1 shr 3) and 0x03   // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
                val brIdx = (b2 shr 4) and 0x0F
                val kbps = when {
                    versionBits == 3 -> MP3_V1_L3.getOrNull(brIdx)
                    versionBits == 2 || versionBits == 0 -> MP3_V2_L3.getOrNull(brIdx)
                    else -> null
                } ?: return null
                if (kbps <= 0) return null
                return (file.length() - pos) * 8.0 / (kbps * 1000.0)
            }
            pos++
        }
        return null
    }

    // ===== 基础读取 =====

    private fun readU8(raf: RandomAccessFile, pos: Long): Long {
        raf.seek(pos)
        return raf.read().toLong() and 0xFF
    }

    private fun readU32(raf: RandomAccessFile, pos: Long): Long {
        raf.seek(pos)
        val b = ByteArray(4)
        if (readFully(raf, b) != 4) return -1
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    private fun readU64(raf: RandomAccessFile, pos: Long): Long {
        raf.seek(pos)
        val b = ByteArray(8)
        if (readFully(raf, b) != 8) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun readType(raf: RandomAccessFile, pos: Long): String {
        raf.seek(pos)
        val b = ByteArray(4)
        if (readFully(raf, b) != 4) return ""
        return String(b, Charsets.ISO_8859_1)
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = raf.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private val MP3_V1_L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
    )
    private val MP3_V2_L3 = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160
    )
}
