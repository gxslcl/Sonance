package com.sonance.player.data.bili

import java.io.File
import java.io.RandomAccessFile

/**
 * 一个样本。
 *
 * **时间值一律保存原始 timescale 单位**（音视频各自的 timescale 不同：
 * B 站常见 video=16000、audio=44100），不预先换算成微秒。
 *
 * 换算会引入致命的取整误差：video timescale=16000 时每帧 duration=272
 * （≈17ms），换成微秒是 17000µs，写 `stts` 时再换回 timescale 单位得
 * int(17000*16000/1e6)=272 —— 单看没问题，但一旦源 timescale 除不尽
 * （比如 59.94fps 在 1000 刻度下），每帧就丢零点几毫秒，一段一小时的视频
 * 累积下来音画能差出好几秒。
 *
 * 这里直接保留 `dtsRaw/durationRaw/ctsRaw` 三个整数，写容器时原值输出，
 * 全程零换算。
 */
data class Fmp4Sample(
    /** 在 m4s 文件里的字节偏移 */
    val offset: Long,
    val size: Int,
    /** 解码时间（DTS），轨 timescale 单位 */
    val dtsRaw: Long,
    /** 该样本持续时长，轨 timescale 单位；0 表示未知，写容器时兜底 */
    val durationRaw: Int,
    /**
     * composition offset（PTS − DTS），轨 timescale 单位。
     *
     * 有 B 帧时非 0。实测这段缓存里出现了 1072 和 5072（timescale 16000，
     * 也就是 67ms / 317ms 的呈现延迟），丢掉它画面会按解码顺序出帧 ——
     * 每几帧就来回跳一下，看起来就是「卡顿」。必须写进 `ctts`。
     */
    val ctsRaw: Int,
    /** 是否关键帧 */
    val isSync: Boolean
) {
    /** 交错排序用的粗略时间（微秒）。只要能把样本分进半秒一块就够了 */
    fun timeUs(timescale: Int): Long =
        if (timescale > 0) dtsRaw * 1_000_000L / timescale else 0L
}

/** 一条轨的样本表 */
data class Fmp4Track(
    val trackId: Int,
    val timescale: Int,
    val samples: List<Fmp4Sample>,
    /**
     * `stsd` 里那条 sample entry 的原始字节（video 是 `hvc1/hev1/avc1` 带 `hvcC/avcC`，
     * audio 是 `mp4a` 带 `esds`）。写 MP4 时原样复用 —— 编码参数一个字节都不用自己拼。
     */
    val sampleEntry: ByteArray? = null
)

/**
 * 极简 fragmented MP4 解析器：只做一件事 —— 把 `moof` 里的样本索引读出来。
 *
 * ## 为什么要自己写
 *
 * B 站的 m4s 是**没有 `sidx` 的 fMP4**：轨道声明在 `moov`（含 `mvex`），
 * 真正的样本全部散落在后面的 `moof/traf/trun` 里。
 *
 * 实测两条现成的路都死了：
 * - `MediaExtractor`：`getTrackFormat` 能读出编码参数，但 `sampleTime` 立刻 -1，
 *   一个样本都拿不到 —— 它不认这种结构里的 `moof`；
 * - Media3 的 `Mp4Extractor` 虽然能顺序播，但拿不到可合并的时间轴，
 *   `MergingMediaSource` 合并音视频时直接越界。
 *
 * 所以这里只解析索引（几十 KB 的 box），样本数据留给 `MediaMuxer` 拷贝 ——
 * 全程不碰编码。
 *
 * ## 只实现 B 站实际用到的那些 flag
 *
 * `trun` 里每个字段有没有值由 flags 决定，规范里组合很多。B 站客户端固定用
 * 「default-base-is-moof + data_offset + 每样本 size/duration/flags/cts」这套，
 * 缺省值全都从 `tfhd` 的 default 里取，两条兜底都在。
 */
object BiliFmp4 {

    /** 单个 moof 里最多认这么多样本，防止解析跑飞 */
    private const val MAX_SAMPLES = 2_000_000

    fun parse(file: File): Fmp4Track? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var timescale = 0
                var trackId = -1
                var sampleEntry: ByteArray? = null
                val samples = ArrayList<Fmp4Sample>(8192)
                val length = raf.length()
                var pos = 0L

                while (pos + 8 <= length && samples.size < MAX_SAMPLES) {
                    raf.seek(pos)
                    var size = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = readType(raf)
                    var headerSize = 8L
                    if (size == 1L) {
                        size = raf.readLong()
                        headerSize = 16
                    } else if (size == 0L) {
                        size = length - pos
                    }
                    if (size < headerSize) break

                    val payload = pos + headerSize
                    when (type) {
                        "moov" -> {
                            val info = readMoov(raf, payload, size - headerSize)
                            if (info.timescale > 0) timescale = info.timescale
                            if (info.trackId > 0) trackId = info.trackId
                            if (info.sampleEntry != null) sampleEntry = info.sampleEntry
                        }

                        "moof" -> {
                            if (timescale > 0) {
                                readMoof(raf, pos, payload, size - headerSize, samples)
                            }
                        }
                    }
                    pos += size
                }

                if (samples.isEmpty() || timescale <= 0) null
                else Fmp4Track(trackId, timescale, samples, sampleEntry)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /* ---------------- moov ---------------- */

    private data class MoovInfo(
        val trackId: Int,
        val timescale: Int,
        val sampleEntry: ByteArray?
    )

    private fun readMoov(raf: RandomAccessFile, start: Long, size: Long): MoovInfo {
        var trackId = -1
        var timescale = 0
        var sampleEntry: ByteArray? = null

        walk(raf, start, start + size, depth = 0) { type, payload, payloadSize, version ->
            when (type) {
                "tkhd" -> {
                    if (payloadSize >= 24) {
                        // v1 的时间字段是 64 位，trackId 往后挪 8 字节
                        val offset = if (version == 1) 20L else 12L
                        raf.seek(payload + offset)
                        val id = raf.readInt()
                        if (id > 0) trackId = id
                    }
                }

                "mdhd" -> {
                    val offset = if (version == 1) 20L else 12L
                    if (payloadSize >= offset + 4) {
                        raf.seek(payload + offset)
                        val ts = raf.readInt()
                        if (ts > 0) timescale = ts
                    }
                }

                "stsd" -> {
                    // fullbox(4) + entry_count(4)，紧跟第一条 sample entry
                    if (payloadSize > 8) {
                        raf.seek(payload + 4)
                        val count = raf.readInt()
                        if (count > 0) {
                            val entryStart = payload + 8
                            raf.seek(entryStart)
                            val entrySize = raf.readInt()
                            if (entrySize in 16..(payloadSize - 8).toInt()) {
                                val bytes = ByteArray(entrySize)
                                raf.seek(entryStart)
                                raf.readFully(bytes)
                                sampleEntry = bytes
                            }
                        }
                    }
                }
            }
        }
        return MoovInfo(trackId, timescale, sampleEntry)
    }

    /* ---------------- moof ---------------- */

    private fun readMoof(
        raf: RandomAccessFile,
        moofStart: Long,
        start: Long,
        size: Long,
        out: MutableList<Fmp4Sample>
    ) {
        // 一个 moof 里通常只有一个 traf（我们的 m4s 是单轨文件）
        walk(raf, start, start + size, depth = 0) { type, payload, payloadSize, _ ->
            if (type == "traf") {
                readTraf(raf, moofStart, payload, payloadSize, out)
            }
        }
    }

    private fun readTraf(
        raf: RandomAccessFile,
        moofStart: Long,
        start: Long,
        size: Long,
        out: MutableList<Fmp4Sample>
    ) {
        var baseDataOffset = -1L
        var defaultSampleDuration = 0
        var defaultSampleSize = 0
        var defaultSampleFlags = 0
        var baseMediaDecodeTime = 0L
        var hasBaseMediaDecodeTime = false
        var samplesInTraf = 0

        // 一个 traf 里可能有多个 trun，时间必须接力累加 ——
        // 所以 dts 用盒子传引用，读完一个 trun 由它带回结束值给下一个
        val dtsInOut = LongArray(1)

        // traf 里 box 顺序不定（tfhd 一定在最前），所以先扫一遍拿 tfhd/tfdt，
        // 第二遍再处理 trun —— 避免依赖顺序
        walk(raf, start, start + size, depth = 0) { type, payload, payloadSize, version ->
            when (type) {
                "tfhd" -> {
                    if (payloadSize < 8) return@walk
                    raf.seek(payload)
                    val flags = raf.readInt() and 0x00FFFFFF
                    raf.readInt() // trackId，单轨文件用不上
                    var cursor = payload + 8
                    if (flags and 0x000001 != 0) {
                        raf.seek(cursor); baseDataOffset = raf.readLong(); cursor += 8
                    }
                    if (flags and 0x000002 != 0) {
                        cursor += 4 // sample_description_index
                    }
                    if (flags and 0x000008 != 0) {
                        raf.seek(cursor); defaultSampleDuration = raf.readInt(); cursor += 4
                    }
                    if (flags and 0x000010 != 0) {
                        raf.seek(cursor); defaultSampleSize = raf.readInt(); cursor += 4
                    }
                    if (flags and 0x000020 != 0) {
                        raf.seek(cursor); defaultSampleFlags = raf.readInt()
                    }
                }

                "tfdt" -> {
                    raf.seek(payload + 4) // version + flags
                    baseMediaDecodeTime = if (version == 1) raf.readLong() else raf.readInt().toLong() and 0xFFFFFFFFL
                    hasBaseMediaDecodeTime = true
                }

                "trun" -> {
                    dtsInOut[0] = if (hasBaseMediaDecodeTime) baseMediaDecodeTime else dtsInOut[0]
                    val count = readTrun(
                        raf = raf,
                        payload = payload,
                        payloadSize = payloadSize,
                        version = version,
                        moofStart = moofStart,
                        baseDataOffset = baseDataOffset,
                        defaultDuration = defaultSampleDuration,
                        defaultSize = defaultSampleSize,
                        defaultFlags = defaultSampleFlags,
                        dtsInOut = dtsInOut,
                        out = out
                    )
                    samplesInTraf += count
                }
            }
        }
        if (samplesInTraf == 0) return
    }

    private fun readTrun(
        raf: RandomAccessFile,
        payload: Long,
        payloadSize: Long,
        version: Int,
        moofStart: Long,
        baseDataOffset: Long,
        defaultDuration: Int,
        defaultSize: Int,
        defaultFlags: Int,
        dtsInOut: LongArray,
        out: MutableList<Fmp4Sample>
    ): Int {
        if (payloadSize < 8) return 0
        raf.seek(payload)
        val flags = raf.readInt() and 0x00FFFFFF
        val sampleCount = raf.readInt()
        if (sampleCount <= 0 || sampleCount > MAX_SAMPLES) return 0

        var cursor = payload + 8
        var dataOffset = -1L
        if (flags and 0x000001 != 0) {
            raf.seek(cursor); dataOffset = raf.readInt().toLong(); cursor += 4
        }
        var firstSampleFlags = -1
        if (flags and 0x000004 != 0) {
            raf.seek(cursor); firstSampleFlags = raf.readInt(); cursor += 4
        }

        val hasDuration = flags and 0x000100 != 0
        val hasSize = flags and 0x000200 != 0
        val hasFlags = flags and 0x000400 != 0
        val hasCts = flags and 0x000800 != 0

        // 样本起点：有 data_offset 时相对 moof 起点（B 站用 default-base-is-moof）
        var sampleOffset = when {
            dataOffset >= 0 && baseDataOffset > 0 -> baseDataOffset + dataOffset
            dataOffset >= 0 -> moofStart + dataOffset
            baseDataOffset > 0 -> baseDataOffset
            else -> moofStart
        }

        var dts = dtsInOut[0]
        val perSampleBytes = (if (hasDuration) 4 else 0) + (if (hasSize) 4 else 0) +
            (if (hasFlags) 4 else 0) + (if (hasCts) 4 else 0)
        if (perSampleBytes == 0 && sampleCount > 100_000) return 0

        raf.seek(cursor)
        val chunk = ByteArray(perSampleBytes.coerceAtLeast(1) * minOf(sampleCount, 4096))
        var chunkPos = 0
        var chunkLen = 0

        repeat(sampleCount) { index ->
            var duration = defaultDuration
            var size = defaultSize
            var cts = 0
            var sampleFlags = if (index == 0 && firstSampleFlags >= 0) firstSampleFlags else defaultFlags

            if (perSampleBytes > 0) {
                if (chunkPos + perSampleBytes > chunkLen) {
                    chunkLen = raf.read(chunk, 0, minOf(chunk.size, (sampleCount - index) * perSampleBytes))
                    chunkPos = 0
                    if (chunkLen < perSampleBytes) return 0
                }
                var p = chunkPos
                if (hasDuration) { duration = readInt(chunk, p); p += 4 }
                if (hasSize) { size = readInt(chunk, p); p += 4 }
                if (hasFlags) { sampleFlags = readInt(chunk, p); p += 4 }
                if (hasCts) { cts = readInt(chunk, p); p += 4 }
                chunkPos = p
            } else if (size <= 0) {
                // 没有每样本 size 也没有默认 size，说明结构不认识，别再往下猜
                return 0
            }

            if (size <= 0) return 0

            // flag bit 16 = sample_is_non_sync_sample
            val isSync = (sampleFlags and 0x00010000) == 0
            out.add(
                Fmp4Sample(
                    offset = sampleOffset,
                    size = size,
                    dtsRaw = dts,
                    durationRaw = if (duration > 0) duration else 0,
                    ctsRaw = cts,
                    isSync = isSync
                )
            )
            sampleOffset += size
            dts += duration
        }
        dtsInOut[0] = dts
        return sampleCount
    }

    /* ---------------- 通用 box 遍历 ---------------- */

    /**
     * 遍历 [start, end) 区间内的 box，回调 (类型, payload 起点, payload 长度, version)。
     * 只往下钻容器型 box（moov/trak/mdia/minf/stbl/mvex/traf），最多 [maxDepth] 层。
     */
    private fun walk(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        maxDepth: Int = 4,
        onBox: (type: String, payload: Long, payloadSize: Long, version: Int) -> Unit
    ) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = readType(raf)
            var header = 8L
            if (size == 1L) {
                if (pos + 16 > end) return
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || pos + size > end) return

            val payload = pos + header
            val payloadSize = size - header

            var version = 0
            if (type in FULL_BOXES && payloadSize >= 4) {
                raf.seek(payload)
                version = (raf.readInt() ushr 24) and 0xFF
            }
            onBox(type, payload, payloadSize, version)

            if (type in CONTAINERS && depth < maxDepth) {
                walk(raf, payload, payload + payloadSize, depth + 1, maxDepth, onBox)
            }
            pos += size
        }
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "edts")
    private val FULL_BOXES = setOf("tkhd", "mdhd", "tfhd", "tfdt", "trun", "mfhd")

    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}
