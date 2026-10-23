package com.sonance.player.data.bili

import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 把两条 m4s 的样本重新封装成一个标准 MP4。
 *
 * ## 为什么不用 MediaMuxer
 *
 * 试过，在这台设备（联想 TB375FC / Android 16）上直接 abort：
 *
 * ```
 * FORTIFY: write: count 18446744073709551615 > SSIZE_MAX
 *   at MPEG4Writer::addLengthPrefixedSample_l
 * ```
 *
 * 样本偏移、NAL 长度前缀、`hvcC` 参数集都逐一验证过是对的 —— 这是平台实现的问题，
 * 不是喂进去的数据有问题。所以容器自己拼。
 *
 * ## 三件影响播放体验的事
 *
 * ### 1. 必须交错（interleave）
 *
 * 第一版是「整段视频 + 整段音频」的顺序写的。播放时解码器要在相距几十 MB 的
 * 两个位置来回跳：读一帧视频跳到文件后半段读音频，再跳回来读下一帧。
 * 高码率下这种随机读会直接反映成画面卡顿。
 *
 * 现在按 0.5 秒一个交错块轮流写 `[视频][音频][视频][音频]…`，播放器顺序读文件
 * 就能同时喂饱两条轨。
 *
 * ### 2. 必须写 `ctts`
 *
 * HEVC 有 B 帧时样本按**解码顺序**存储，呈现时间 = DTS + composition offset。
 * 这段缓存里实测 cts 出现了 1072 和 5072（timescale 16000，即 67ms / 317ms）。
 * 丢掉 cts 画面就会按解码顺序出帧，每几帧就跳一下 —— 这也是「卡顿」的一种。
 *
 * ### 3. 时间值不换算
 *
 * `stts`/`ctts` 直接写 [Fmp4Sample] 里的原始 timescale 单位整数。
 * 一旦走微秒中转就会丢精度（见 BiliFmp4 的注释），长视频会累积成音画不同步。
 *
 * ## 布局
 *
 * `ftyp` + `moov` + `mdat` —— **moov 前置**（faststart）。
 *
 * 能做到前提是因为交错后每个块的字节数可由样本大小预先算出，样本偏移不依赖
 * 实际写入过程。好处有两个：播放器不用先跳到文件末尾找 `moov`，
 * 而且整个文件是一次顺序写出来的，不用回头补 `mdat` 长度。
 */
object BiliMp4Writer {

    /** mvhd 用毫秒刻度 */
    private const val MOVIE_TIMESCALE = 1000

    /**
     * 交错粒度。0.5 秒一块：1080P60 高码率下每块约 2MB，4K 约 5MB ——
     * 大到能吃满顺序读，小到不影响随机访问。
     */
    private const val INTERLEAVE_US = 500_000L

    fun write(
        videoFile: File,
        video: Fmp4Track,
        audioFile: File,
        audio: Fmp4Track,
        out: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val videoEntry = video.sampleEntry ?: return false
        val audioEntry = audio.sampleEntry ?: return false
        val vs = video.samples
        val aud = audio.samples
        if (vs.isEmpty() || aud.isEmpty()) return false

        val (width, height) = readVideoSize(videoEntry)
        if (width <= 0 || height <= 0) return false
        val (channels, sampleRate) = readAudioParams(audioEntry)

        out.parentFile?.mkdirs()
        out.delete()

        return try {
            // ---- 1. 切成交错块 ----
            val blocks = buildBlocks(vs, video.timescale, aud, audio.timescale)

            // ---- 2. 在 mdat 里排布，拿到每条轨的 chunk 表 ----
            val videoChunks = ArrayList<Chunk>()
            val audioChunks = ArrayList<Chunk>()
            var cursor = 0L
            for (block in blocks) {
                if (block.vEnd > block.vStart) {
                    videoChunks.add(Chunk(cursor, block.vEnd - block.vStart))
                    cursor += sumRange(vs, block.vStart, block.vEnd)
                }
                if (block.aEnd > block.aStart) {
                    audioChunks.add(Chunk(cursor, block.aEnd - block.aStart))
                    cursor += sumRange(aud, block.aStart, block.aEnd)
                }
            }
            val mdatPayload = cursor

            val ftypBytes = ftyp()
            val headerSize = if (mdatPayload + 8 > 0xFFFFFFFFL) 16 else 8

            // ---- 3. 生成 moov ----
            //
            // stco 里的偏移 = mdat内相对偏移 + base，base 含 moov 自身长度 ——
            // 而 moov 长度在生成前未知，这是鸡生蛋。好在 moov 的大小只取决于
            // 表的条目数（与 offset 的**数值**无关，除了 32/64 位的差别），
            // 所以先用占位 base 生成一次拿到大小，再算出真正的 base 重新生成：
            //
            //   第一次：base0 = ftyp + header（故意偏小），得到 moovSize0
            //   若超 32 位 → 升级 co64，moov 会多出 4 字节/条，size 可精确推出
            //   第二次：base  = ftyp + moovSize(最终) + header，重生成 —— 数值正确
            val relativeMax = maxChunkOffset(videoChunks, audioChunks)
            val probe = buildMoov(
                video = video,
                videoChunks = videoChunks,
                videoEntry = videoEntry,
                width = width,
                height = height,
                audio = audio,
                audioChunks = audioChunks,
                audioEntry = audioEntry,
                channels = channels,
                sampleRate = sampleRate,
                useCo64 = false,
                base = ftypBytes.size.toLong() + headerSize
            )
            val needCo64 = relativeMax + ftypBytes.size + probe.size + headerSize > 0xFFFFFFFFL
            // co64 比 stco 每条多 4 字节，两条轨各一份表
            val moovSize: Long = if (needCo64) {
                probe.size.toLong() + 4L * (videoChunks.size + audioChunks.size)
            } else {
                probe.size.toLong()
            }
            val moov = buildMoov(
                video = video,
                videoChunks = videoChunks,
                videoEntry = videoEntry,
                width = width,
                height = height,
                audio = audio,
                audioChunks = audioChunks,
                audioEntry = audioEntry,
                channels = channels,
                sampleRate = sampleRate,
                useCo64 = needCo64,
                base = ftypBytes.size.toLong() + moovSize + headerSize.toLong()
            )

            android.util.Log.d(
                "BiliMp4",
                "layout: video ts=${video.timescale} ${width}x$height samples=${vs.size} " +
                    "audio ts=${audio.timescale} samples=${aud.size} | " +
                    "blocks=${blocks.size} videoChunks=${videoChunks.size} audioChunks=${audioChunks.size} " +
                    "mdat=$mdatPayload moov=${moov.size} base=${ftypBytes.size + moov.size + headerSize} co64=$needCo64"
            )

            // ---- 4. 一次顺序写出 ----
            var written = 0L
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { bos ->
                bos.write(ftypBytes)
                bos.write(moov)
                bos.write(mdatHeader(mdatPayload))

                val buf = ByteArray(1 shl 20)
                RandomAccessFile(videoFile, "r").use { vraf ->
                    RandomAccessFile(audioFile, "r").use { araf ->
                        var vPos = -1L
                        var aPos = -1L
                        var lastReport = 0L
                        for (block in blocks) {
                            for (i in block.vStart until block.vEnd) {
                                val sample = vs[i]
                                // fMP4 的样本在源里基本是连续的，能省则省一次 seek
                                if (vPos != sample.offset) vraf.seek(sample.offset)
                                written += copySample(vraf, bos, sample.size, buf)
                                vPos = sample.offset + sample.size
                            }
                            for (i in block.aStart until block.aEnd) {
                                val sample = aud[i]
                                if (aPos != sample.offset) araf.seek(sample.offset)
                                written += copySample(araf, bos, sample.size, buf)
                                aPos = sample.offset + sample.size
                            }
                            // 每 8MB 报一次，别让进度回调比复制本身还贵
                            if (written - lastReport > 8L * 1024 * 1024) {
                                lastReport = written
                                onProgress((written.toFloat() / mdatPayload).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                bos.flush()
            }

            onProgress(1f)
            verify(out)
            out.length() > 0 && written > 0
        } catch (t: Throwable) {
            android.util.Log.e("BiliMp4", "write failed: ${t.javaClass.simpleName} ${t.message}")
            out.delete()
            false
        }
    }

    /* ---------------- 交错切块 ---------------- */

    /** 一个交错块里两条轨各占的样本区间（左闭右开） */
    private class InterleaveBlock(
        val vStart: Int, val vEnd: Int,
        val aStart: Int, val aEnd: Int
    )

    /**
     * 按解码时间把两条轨归并成若干块，每块覆盖约 [INTERLEAVE_US] 的内容。
     *
     * 两个 Iterable 都是有序的，双指针推进即可，不用排序。
     */
    private fun buildBlocks(
        vs: List<Fmp4Sample>, vTs: Int,
        aud: List<Fmp4Sample>, aTs: Int
    ): List<InterleaveBlock> {
        val blocks = ArrayList<InterleaveBlock>()
        var vi = 0
        var ai = 0
        while (vi < vs.size || ai < aud.size) {
            val anchor = when {
                vi < vs.size -> vs[vi].timeUs(vTs)
                else -> aud[ai].timeUs(aTs)
            }
            val limit = anchor + INTERLEAVE_US
            val vStart = vi
            val aStart = ai
            while (vi < vs.size && vs[vi].timeUs(vTs) < limit) vi++
            while (ai < aud.size && aud[ai].timeUs(aTs) < limit) ai++

            if (vi == vStart && ai == aStart) {
                // 时间戳异常（全是 0 之类）也要保证能往前走
                if (vi < vs.size) vi++
                else if (ai < aud.size) ai++
                else break
            }
            blocks.add(InterleaveBlock(vStart, vi, aStart, ai))
        }
        return blocks
    }

    private fun sumRange(samples: List<Fmp4Sample>, from: Int, to: Int): Long {
        var total = 0L
        for (i in from until to) total += samples[i].size.toLong()
        return total
    }

    /* ---------------- moov ---------------- */

    /** 一条轨里的一个 chunk：`mdat` 内相对起点 + 该 chunk 含多少样本 */
    private class Chunk(val offsetInMdat: Long, val sampleCount: Int)

    private fun maxChunkOffset(videoChunks: List<Chunk>, audioChunks: List<Chunk>): Long {
        var max = 0L
        videoChunks.forEach { if (it.offsetInMdat > max) max = it.offsetInMdat }
        audioChunks.forEach { if (it.offsetInMdat > max) max = it.offsetInMdat }
        return max
    }

    private fun buildMoov(
        video: Fmp4Track,
        videoChunks: List<Chunk>,
        videoEntry: ByteArray,
        width: Int,
        height: Int,
        audio: Fmp4Track,
        audioChunks: List<Chunk>,
        audioEntry: ByteArray,
        channels: Int,
        sampleRate: Int,
        useCo64: Boolean,
        base: Long
    ): ByteArray {
        var videoTotal = trackDurationRaw(video.samples)
        var audioTotal = trackDurationRaw(audio.samples)

        // ⚠️ 音频时长以**视频轨**为准校准。
        //
        // 某些条目的 audio.m4s 里，trun 不带 per-sample duration，
        // tfhd 的 default_sample_duration 又是个脏值（实测拿到过 29 ——
        // AAC 一帧正常是 1024）。算下来整条音轨只有 7.8 秒：
        // 音频放完 → NuPlayer 失去渲染时钟锚点 → 视频停在最后一帧不动，
        // 症状就是「前几秒正常，然后卡住」。
        //
        // B 站缓存的两条轨必然等长，所以对不上的时候直接按视频轨换算。
        val videoUs = videoTotal * 1_000_000L / video.timescale.coerceAtLeast(1)
        val audioUs = audioTotal * 1_000_000L / audio.timescale.coerceAtLeast(1)
        var audioUniformDuration: Int? = null
        if (videoUs > 0 && audioUs > 0 && (audioUs < videoUs * 0.9 || audioUs > videoUs * 1.1)) {
            audioTotal = videoUs * audio.timescale / 1_000_000L
            audioUniformDuration =
                (audioTotal / audio.samples.size).toInt().coerceAtLeast(1)
            android.util.Log.w(
                "BiliMp4",
                "audio duration mismatch: audio=${audioUs / 1000}ms video=${videoUs / 1000}ms, " +
                    "force audio duration=$audioUniformDuration/${audio.timescale}"
            )
        }

        val movieDurationMs = maxOf(videoUs, audioUs) / 1000

        val mvhd = ByteArrayOutputStream().apply {
            writeInt(0) // creation
            writeInt(0) // modification
            writeInt(MOVIE_TIMESCALE)
            writeInt(movieDurationMs.toInt())
            writeInt(0x00010000) // rate 1.0
            writeShort(0x0100)   // volume 1.0
            writeShort(0)
            writeInt(0); writeInt(0) // reserved
            writeMatrix(this)
            repeat(6) { writeInt(0) } // pre_defined
            writeInt(3) // next_track_id
        }.toByteArray()

        val videoTrak = buildTrak(
            trackId = 1,
            isVideo = true,
            timescale = video.timescale,
            totalDuration = videoTotal,
            entry = videoEntry,
            samples = video.samples,
            chunks = videoChunks,
            useCo64 = useCo64,
            base = base,
            width = width,
            height = height,
            channels = channels,
            sampleRate = sampleRate
        )
        val audioTrak = buildTrak(
            trackId = 2,
            isVideo = false,
            timescale = audio.timescale,
            totalDuration = audioTotal,
            entry = audioEntry,
            samples = audio.samples,
            chunks = audioChunks,
            useCo64 = useCo64,
            base = base,
            width = 0,
            height = 0,
            channels = channels,
            sampleRate = sampleRate,
            uniformDuration = audioUniformDuration
        )

        val moov = ByteArrayOutputStream()
        moov.write(fullBox("mvhd", 0, 0, mvhd))
        moov.write(videoTrak)
        moov.write(audioTrak)
        return box("moov", moov.toByteArray())
    }

    private fun buildTrak(
        trackId: Int,
        isVideo: Boolean,
        timescale: Int,
        totalDuration: Long,
        entry: ByteArray,
        samples: List<Fmp4Sample>,
        chunks: List<Chunk>,
        useCo64: Boolean,
        base: Long,
        width: Int,
        height: Int,
        channels: Int,
        sampleRate: Int,
        uniformDuration: Int? = null
    ): ByteArray {
        // mdhd 的 duration 用轨自己的 timescale，原始整数直接累加，零误差
        val mdhd = ByteArrayOutputStream().apply {
            writeInt(0)
            writeInt(0)
            writeInt(timescale)
            writeInt(totalDuration.toInt())
            writeShort(0x55C4) // language = und
            writeShort(0)
        }.toByteArray()

        val tkhdDuration = (totalDuration * MOVIE_TIMESCALE / timescale.coerceAtLeast(1))
        val tkhd = ByteArrayOutputStream().apply {
            writeInt(0) // creation
            writeInt(0) // modification
            writeInt(trackId)
            writeInt(0) // reserved
            writeInt(tkhdDuration.toInt())
            writeInt(0); writeInt(0) // reserved
            writeShort(0) // layer
            writeShort(0) // alternate_group
            writeShort(if (isVideo) 0 else 0x0100) // volume
            writeShort(0)
            writeMatrix(this)
            writeInt(if (isVideo) width shl 16 else 0)
            writeInt(if (isVideo) height shl 16 else 0)
        }.toByteArray()

        val handlerName = if (isVideo) "VideoHandler" else "SoundHandler"
        val hdlr = ByteArrayOutputStream().apply {
            writeInt(0)
            write(if (isVideo) "vide".toByteArray() else "soun".toByteArray())
            repeat(3) { writeInt(0) }
            write(handlerName.toByteArray(Charsets.US_ASCII))
            write(0)
        }.toByteArray()

        val mediaHeader = if (isVideo) {
            val body = ByteArrayOutputStream().apply {
                writeShort(0)
                writeShort(0); writeShort(0); writeShort(0)
            }.toByteArray()
            fullBox("vmhd", 0, 1, body)
        } else {
            val body = ByteArrayOutputStream().apply { writeInt(0) }.toByteArray()
            fullBox("smhd", 0, 0, body)
        }

        val dinf = box(
            "dinf",
            fullBox(
                "dref", 0, 0,
                ByteArrayOutputStream().apply {
                    writeInt(1)
                    write(fullBox("url ", 0, 1, ByteArray(0)))
                }.toByteArray()
            )
        )

        val stbl = buildStbl(entry, samples, chunks, useCo64, base, uniformDuration)

        val minf = box("minf", concat(mediaHeader, dinf, box("stbl", stbl)))
        val mdia = box("mdia", concat(fullBox("mdhd", 0, 0, mdhd), fullBox("hdlr", 0, 0, hdlr), minf))

        // tkhd flags=3：track enabled + in movie
        return box("trak", concat(fullBox("tkhd", 0, 3, tkhd), mdia))
    }

    private fun buildStbl(
        entry: ByteArray,
        samples: List<Fmp4Sample>,
        chunks: List<Chunk>,
        useCo64: Boolean,
        base: Long,
        uniformDuration: Int? = null
    ): ByteArray {
        val stsd = fullBox(
            "stsd", 0, 0,
            ByteArrayOutputStream().apply {
                writeInt(1)
                write(entry)
            }.toByteArray()
        )

        // ---- stts：解码时长，run-length 合并 ----
        // uniformDuration：音频轨兜底用（见 buildMoov 里的校准），全表统一
        val sttsEntries = ArrayList<IntArray>()
        samples.forEach { sample ->
            val duration = uniformDuration
                ?: (if (sample.durationRaw > 0) sample.durationRaw else 1)
            val last = sttsEntries.lastOrNull()
            if (last != null && last[1] == duration) last[0]++
            else sttsEntries.add(intArrayOf(1, duration))
        }
        val stts = fullBox(
            "stts", 0, 0,
            ByteArrayOutputStream().apply {
                writeInt(sttsEntries.size)
                sttsEntries.forEach { writeInt(it[0]); writeInt(it[1]) }
            }.toByteArray()
        )

        // ---- ctts：呈现偏移（PTS - DTS）----
        //
        // 有它播放器才能把 B 帧按呈现顺序摆回去。没有它时画面按解码顺序出，
        // 这段缓存 cts 高达 5072/16000 ≈ 317ms，等于每几帧就跳一下。
        // version=1 用有符号值：有些编码器的 cts 会出现负数。
        val hasCTS = samples.any { it.ctsRaw != 0 }
        val ctts = if (hasCTS) {
            val cttsEntries = ArrayList<IntArray>()
            samples.forEach { sample ->
                val last = cttsEntries.lastOrNull()
                if (last != null && last[1] == sample.ctsRaw) last[0]++
                else cttsEntries.add(intArrayOf(1, sample.ctsRaw))
            }
            fullBox(
                "ctts", 1, 0,
                ByteArrayOutputStream().apply {
                    writeInt(cttsEntries.size)
                    cttsEntries.forEach { writeInt(it[0]); writeInt(it[1]) }
                }.toByteArray()
            )
        } else {
            ByteArray(0)
        }

        // ---- stss：关键帧。没有它拖进度落非关键帧上就是一片花屏 ----
        val syncIndexes = ArrayList<Int>()
        samples.forEachIndexed { index, sample -> if (sample.isSync) syncIndexes.add(index + 1) }
        val stss = if (syncIndexes.isNotEmpty() && syncIndexes.size < samples.size) {
            fullBox(
                "stss", 0, 0,
                ByteArrayOutputStream().apply {
                    writeInt(syncIndexes.size)
                    syncIndexes.forEach { writeInt(it) }
                }.toByteArray()
            )
        } else {
            ByteArray(0)
        }

        // ---- stsc：相邻 chunk 样本数相同时合并条目 ----
        val stscEntries = ArrayList<IntArray>()
        chunks.forEachIndexed { index, chunk ->
            val last = stscEntries.lastOrNull()
            if (last != null && last[1] == chunk.sampleCount) {
                // 合并进上一条，什么都不用做
            } else {
                stscEntries.add(intArrayOf(index + 1, chunk.sampleCount, 1))
            }
        }
        val stsc = fullBox(
            "stsc", 0, 0,
            ByteArrayOutputStream().apply {
                writeInt(stscEntries.size)
                stscEntries.forEach { writeInt(it[0]); writeInt(it[1]); writeInt(it[2]) }
            }.toByteArray()
        )

        val stsz = fullBox(
            "stsz", 0, 0,
            ByteArrayOutputStream().apply {
                writeInt(0) // sample_size = 0 表示逐个给
                writeInt(samples.size)
                samples.forEach { writeInt(it.size) }
            }.toByteArray()
        )

        val offsets = fullBox(
            if (useCo64) "co64" else "stco", 0, 0,
            ByteArrayOutputStream().apply {
                writeInt(chunks.size)
                chunks.forEach { chunk ->
                    val absolute = chunk.offsetInMdat + base
                    if (useCo64) writeLong(absolute) else writeInt(absolute.toInt())
                }
            }.toByteArray()
        )

        return concat(stsd, stts, ctts, stss, stsc, stsz, offsets)
    }

    /* ---------------- 辅助 ---------------- */

    private fun trackDurationRaw(samples: List<Fmp4Sample>): Long {
        var total = 0L
        samples.forEach { total += (if (it.durationRaw > 0) it.durationRaw else 1).toLong() }
        return total
    }

    private fun mdatHeader(payload: Long): ByteArray {
        val out = ByteArrayOutputStream(16)
        if (payload + 8 > 0xFFFFFFFFL) {
            // largesize：size 字段置 1，真正的长度跟在 type 后面
            out.writeInt(1)
            out.write("mdat".toByteArray(Charsets.US_ASCII))
            out.writeLong(payload + 16)
        } else {
            out.writeInt((payload + 8).toInt())
            out.write("mdat".toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    /**
     * 精确拷 [size] 字节。
     *
     * 必须用 [RandomAccessFile.readFully]：`read` 对大块请求可能部分满足
     * （一次不满也不报错），如果拿返回值当"读完了"用，下一个样本就会从
     * 错误的文件位置开始拷 —— 产物表面结构完整、ffmpeg 都能认，但样本
     * 字节是错位的，解码器报 `Error splitting the input into NAL units`，
     * 画面一片黑。读不满（到 EOF）就抛异常走失败分支，绝不静默错位。
     */
    private fun copySample(
        src: RandomAccessFile,
        dst: BufferedOutputStream,
        size: Int,
        buffer: ByteArray
    ): Long {
        var remaining = size.toLong()
        var copied = 0L
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            src.readFully(buffer, 0, want)
            dst.write(buffer, 0, want)
            remaining -= want
            copied += want
        }
        return copied
    }

    /**
     * 写完立刻用系统解封装器验一遍 —— 且要**真的读样本**。
     *
     * 只看 trackCount 查不出数据错位：`stco` 偏移错的时候轨道声明照样完整
     * （`stsd/stts` 都来自解析结果），但一读样本全是垃圾 —— 之前那个
     * 「ffmpeg 报 Error splitting NAL units、播放黑屏」就是这么漏检的。
     * 每条轨读 3 个样本，读出来大小合理才算过。
     */
    private fun verify(file: File) {
        runCatching {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val tracks = (0 until extractor.trackCount).map { index ->
                    val format = extractor.getTrackFormat(index)
                    format.getString(MediaFormat.KEY_MIME) + "@" +
                        (format.getLong(MediaFormat.KEY_DURATION) / 1000) + "ms"
                }
                val buf = java.nio.ByteBuffer.allocateDirect(4 shl 20)
                val info = android.media.MediaCodec.BufferInfo()
                val readCounts = (0 until extractor.trackCount).map { index ->
                    extractor.selectTrack(index)
                    var ok = 0
                    repeat(3) {
                        buf.clear()
                        val size = extractor.readSampleData(buf, 0)
                        if (size > 0) {
                            ok++
                            extractor.advance()
                        }
                    }
                    extractor.unselectTrack(index)
                    ok
                }
                android.util.Log.d(
                    "BiliMp4",
                    "verify tracks=${extractor.trackCount} $tracks sampleReads=$readCounts"
                )
            } finally {
                extractor.release()
            }
        }.onFailure {
            android.util.Log.e("BiliMp4", "verify failed: ${it.javaClass.simpleName} ${it.message}")
        }
    }

    /** VisualSampleEntry 里 width/height 固定在 entry 偏移 32/34 */
    private fun readVideoSize(entry: ByteArray): Pair<Int, Int> {
        if (entry.size < 36) return 0 to 0
        val w = ((entry[32].toInt() and 0xFF) shl 8) or (entry[33].toInt() and 0xFF)
        val h = ((entry[34].toInt() and 0xFF) shl 8) or (entry[35].toInt() and 0xFF)
        return w to h
    }

    /** AudioSampleEntry：channelcount@24，samplerate(16.16)@32 */
    private fun readAudioParams(entry: ByteArray): Pair<Int, Int> {
        if (entry.size < 36) return 2 to 44100
        val channels = ((entry[24].toInt() and 0xFF) shl 8) or (entry[25].toInt() and 0xFF)
        val rate = ((entry[32].toInt() and 0xFF) shl 24) or
            ((entry[33].toInt() and 0xFF) shl 16) or
            ((entry[34].toInt() and 0xFF) shl 8) or
            (entry[35].toInt() and 0xFF)
        return channels.coerceAtLeast(1) to (rate ushr 16).coerceAtLeast(1)
    }

    private fun ftyp(): ByteArray = box(
        "ftyp",
        ByteArrayOutputStream().apply {
            write("isom".toByteArray())
            writeInt(0x200)
            write("isom".toByteArray())
            write("iso2".toByteArray())
            write("avc1".toByteArray())
            write("mp41".toByteArray())
        }.toByteArray()
    )

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        out.writeInt(8 + payload.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val head = byteArrayOf(
            version.toByte(),
            ((flags shr 16) and 0xFF).toByte(),
            ((flags shr 8) and 0xFF).toByte(),
            (flags and 0xFF).toByte()
        )
        return box(type, head + payload)
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /** MP4 的 3×3 变换矩阵，单位矩阵的 16.16 定点写法 */
    private fun writeMatrix(out: ByteArrayOutputStream) {
        out.writeInt(0x00010000); out.writeInt(0); out.writeInt(0)
        out.writeInt(0); out.writeInt(0x00010000); out.writeInt(0)
        out.writeInt(0); out.writeInt(0); out.writeInt(0x40000000)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24); write(value ushr 16); write(value ushr 8); write(value)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value ushr 8); write(value)
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        writeInt((value ushr 32).toInt())
        writeInt(value.toInt())
    }
}
