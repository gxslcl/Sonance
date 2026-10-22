package com.sonance.player.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 单文件探测结果：要么可以还原（带计划），要么给出跳过原因 */
internal sealed interface RestoreProbe {
    class Ready(val plan: RestorePlan) : RestoreProbe
    class Skip(val reason: String) : RestoreProbe
}

/**
 * 一个文件的还原计划。
 *
 * 走到这一步时，头部已经读完、首块已经解密、后缀已经嗅探、要前置的标签 / 封面已经组好，
 * 只剩两件事：调用方决定「要不要写」（比对输出目录里已有文件的大小），以及 [writeTo] 把数据泵出去。
 *
 * ## 为什么要单独抽出来
 *
 * 这段逻辑原本长在 [RestoreEngine.processOne] 里，而那里挂着 Android 的
 * `Context` / `DocumentFile`，纯 JVM 单测根本碰不到。可它偏偏是全流程里最容易错的地方：
 * 「已经读进头部缓冲区、但其实属于载荷」的那段字节要接着用，
 * 跨块解密的 `startPos` 必须首尾相接，前置标签会改变输出大小……
 * 任何一处算错，结果都是一个**能写完、但听不了**的文件。抽成纯逻辑后，这些都能被测试钉住。
 */
internal class RestorePlan(
    private val decoder: AudioDecoder,
    private val stream: InputStream,
    private val pending: ByteArray,
    private var pendingPos: Int,
    private val firstChunk: ByteArray,
    private val firstLen: Int,
    private val firstSkip: Int,
    val headPrefix: ByteArray,
    val ext: String,
    val outName: String,
    val payloadSize: Long,
    val expectedSize: Long,
    val cover: ByteArray?,
    val info: HeaderInfo
) {
    /** 已写入输出的字节数（含前置标签），供调用方统计 */
    var written: Long = 0
        private set

    /**
     * 把解密后的音频泵进 [out]。
     *
     * @param checkActive 每块调用一次，让调用方能把协程取消传进来
     */
    fun writeTo(out: OutputStream, checkActive: () -> Unit = {}) {
        if (headPrefix.isNotEmpty()) out.write(headPrefix)
        out.write(firstChunk, firstSkip, firstLen - firstSkip)
        written = (firstLen - firstSkip).toLong() + headPrefix.size

        val buffer = ByteArray(CHUNK_SIZE)
        var pos = firstLen.toLong()
        var remaining = payloadSize - firstLen
        while (remaining > 0) {
            checkActive()
            val want = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
            pendingPos = fillFrom(stream, buffer, want, pending, pendingPos)
            // pos 必须跨块连续递增：KGM 的掩码折叠、NCM 的密钥流周期都依赖绝对下标
            if (!decoder.decryptChunk(buffer, want, pos)) throw IOException("解密失败")
            out.write(buffer, 0, want)
            written += want
            pos += want
            remaining -= want
        }
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}

/**
 * 从 [pending]（已读进内存的载荷片段）和 [stream] 依次取满 [want] 字节。
 *
 * 两处都要用：制定计划时取首块、写盘时取后续块。抽成文件级私有函数是为了
 * 保证「先消费内存里那段、再读流」的规则只有一份实现 —— 这条规则错了，
 * 整个文件的音频就会整体错位。
 *
 * @return 新的 pending 游标
 */
private fun fillFrom(
    stream: InputStream,
    buf: ByteArray,
    want: Int,
    pending: ByteArray,
    startPos: Int
): Int {
    var pos = startPos
    var filled = 0
    while (filled < want) {
        if (pos < pending.size) {
            val take = minOf(want - filled, pending.size - pos)
            System.arraycopy(pending, pos, buf, filled, take)
            pos += take
            filled += take
        } else {
            val n = stream.read(buf, filled, want - filled)
            if (n < 0) throw IOException("读取数据失败")
            filled += n
        }
    }
    return pos
}

/**
 * 探测 + 首块解密 + 标签组装。不碰 Android，可在 JVM 上单测。
 */
internal object RestorePlanner {

    /** 完整探测要读的头部窗口（NCM 的密钥块 + 元数据块 + 封面长度字段都在这个范围内） */
    const val PROBE_WINDOW = NcmDecoder.PROBE_BYTES

    /** 为了拿封面，最多允许再补读多少字节；超过就放弃封面 */
    private const val MAX_PREFIX_TAIL = 4 * 1024 * 1024

    private const val CHUNK_SIZE = 64 * 1024

    /**
     * 读文件头并制定还原计划。
     *
     * 探测分两阶段，是为了让「绝大多数普通 mp3」这条路足够便宜：
     * 先读 64 字节做魔数比对，不命中立刻返回；只有命中才继续读满 [PROBE_WINDOW]
     * 做完整解析（NCM 要在这里解两轮 AES）。
     *
     * @param tail 文件末尾的尾部窗口（QMC 探测必需）；null 表示调用方没能提供，
     *             此时 QMC 文件会在探测阶段被跳过并给出准确原因
     * @throws IOException 读流出错时抛，由调用方记为该文件失败
     */
    fun plan(
        stream: InputStream,
        fileSize: Long,
        name: String,
        tail: ByteArray? = null
    ): RestoreProbe {
        if (fileSize < AudioDecoder.HEADER_MIN) return RestoreProbe.Skip("文件过小")

        // ---- 阶段一：64 字节魔数 ----
        val head = ByteArray(AudioDecoder.HEADER_MIN)
        if (!readFully(stream, head, 0, head.size)) throw IOException("读取头部失败")
        // QMC 没有魔数，靠文件名放行 —— 它的探测在尾部窗口里做
        if (!ContainerRestorer.looksLocked(head) && !QmcDecoder.looksLikeName(name)) {
            // 先分清两种情况：已经是标准音频（mp3/flac/m4a…）根本不需要还原，
            // 报成"非受支持的加密容器"会让用户以为文件有问题
            val ext = LockedFormat.extensionOf(name)
            if (ext in LockedFormat.supportedExtensions) {
                return RestoreProbe.Skip("已是标准音频，无需还原")
            }
            return RestoreProbe.Skip(ContainerRestorer.unsupportedReason(name) ?: "非受支持的加密容器")
        }

        // ---- 阶段二：读满探测窗口 ----
        val windowLen = minOf(fileSize, PROBE_WINDOW.toLong()).toInt().coerceAtLeast(head.size)
        val header = ByteArray(windowLen)
        System.arraycopy(head, 0, header, 0, head.size)
        if (windowLen > head.size && !readFully(stream, header, head.size, windowLen - head.size)) {
            throw IOException("读取头部失败")
        }

        val decoder = ContainerRestorer.probe(header, fileSize, tail)
            ?: return RestoreProbe.Skip(ContainerRestorer.unsupportedReason(name) ?: "非受支持的加密容器")
        if (!decoder.supported) {
            return RestoreProbe.Skip(decoder.unsupportedReason ?: "加密变体不支持")
        }

        val info = decoder.info
        val payloadOffset = info.payloadOffset
        if (payloadOffset < 0 || payloadOffset > fileSize) return RestoreProbe.Skip("载荷偏移非法")
        // QMC 系的载荷长度由尾部结构决定（ekey / QTag 不算音频），截断到 audioLen
        val payloadSize = if (info.payloadLength >= 0) {
            minOf(fileSize - payloadOffset, info.payloadLength)
        } else {
            fileSize - payloadOffset
        }
        if (payloadSize <= 0) return RestoreProbe.Skip("载荷为空")

        // ---- 补齐到载荷起点，顺便把封面区读进来 ----
        // 封面在 NCM 里是头部的一段，必须在跳过它之前拿走；等流走到音频起点时就晚了
        val cover: ByteArray?
        val pending: ByteArray
        if (payloadOffset <= header.size) {
            cover = decoder.extractCover(header.copyOfRange(0, payloadOffset.toInt()))
            // 这一段字节既在头部窗口里、又属于载荷：留着，别再去流里要一遍
            pending = header.copyOfRange(payloadOffset.toInt(), header.size)
        } else {
            val extraLen = payloadOffset - header.size
            if (extraLen <= MAX_PREFIX_TAIL) {
                val tail = ByteArray(extraLen.toInt())
                if (!readFully(stream, tail, 0, tail.size)) throw IOException("读取头部失败")
                val merged = ByteArray(payloadOffset.toInt())
                System.arraycopy(header, 0, merged, 0, header.size)
                System.arraycopy(tail, 0, merged, header.size, tail.size)
                cover = decoder.extractCover(merged)
            } else {
                // 头部区异常巨大（不该发生）：放弃封面，直接跳到音频
                skipFully(stream, extraLen)
                cover = info.coverImage
            }
            pending = ByteArray(0)
        }

        // ---- 首块：解密 + 嗅探真实后缀 ----
        val firstChunk = ByteArray(CHUNK_SIZE)
        val firstLen = minOf(payloadSize, CHUNK_SIZE.toLong()).toInt()
        val pendingPos = fillFrom(stream, firstChunk, firstLen, pending, 0)
        if (!decoder.decryptChunk(firstChunk, firstLen, 0)) throw IOException("解密失败")

        val sniffed = AudioDecoder.sniffExtension(firstChunk)
        // 真实内容优先于容器自带的声明；两者都没有才退回 .mp3
        val ext = sniffed.ifBlank { info.suggestedExt }.ifBlank { ".mp3" }

        var headPrefix = ByteArray(0)
        var firstSkip = 0
        when (ext) {
            ".flac" -> if (cover != null && cover.isNotEmpty()) {
                val p = RestoreTagWriter.flacPrefix(firstChunk, cover)
                if (p != null) {
                    headPrefix = p
                    firstSkip = RestoreTagWriter.FLAC_HEAD_BYTES
                }
            }
            ".mp3" -> {
                val tag = RestoreTagWriter.mp3Tag(info, cover)
                if (tag.isNotEmpty()) {
                    headPrefix = tag
                    // 一个文件开头只能有一个 ID3v2 标签。NCM 里的 mp3 往往自带一个，
                    // 直接前置我们的标签会留下「标签里套标签」的非法结构 ——
                    // 顺手把旧标签整段跳过（它的内容已经被容器元数据取代）
                    val oldTag = Id3Writer.existingTagSize(firstChunk)
                    if (oldTag in 1..firstLen) firstSkip = oldTag
                }
            }
        }

        return RestoreProbe.Ready(
            RestorePlan(
                decoder = decoder,
                stream = stream,
                pending = pending,
                pendingPos = pendingPos,
                firstChunk = firstChunk,
                firstLen = firstLen,
                firstSkip = firstSkip,
                headPrefix = headPrefix,
                ext = ext,
                outName = PrivateAudioRestorer.outputStem(name) + ext,
                payloadSize = payloadSize,
                expectedSize = payloadSize - firstSkip + headPrefix.size,
                cover = cover,
                info = info
            )
        )
    }

    /** 读满 buffer 的 `[off, off+count)` 区间；提前 EOF 返回 false */
    private fun readFully(input: InputStream, buffer: ByteArray, off: Int, count: Int): Boolean {
        var filled = 0
        while (filled < count) {
            val n = input.read(buffer, off + filled, count - filled)
            if (n < 0) return false
            filled += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return
                remaining--
            }
        }
    }
}
