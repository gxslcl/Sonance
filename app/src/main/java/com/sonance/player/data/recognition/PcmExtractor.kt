package com.sonance.player.data.recognition

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlin.math.min

/**
 * 离线把歌曲文件的**开头若干秒**解成指纹引擎要的 PCM。
 *
 * ## 为什么只解 11 秒
 *
 * 网易云识曲的指纹只需要「从第 4 秒起的 6 秒音频」（见 [fingerprintSamples]），
 * 全曲解码既慢又费电。MediaExtractor + MediaCodec 标准管线解到够用就停，
 * 不到 11 秒就解到文件尾（试听残曲、短音源都适用）。
 *
 * ## 纯函数与 Android 解码分离
 *
 * 重采样 / 选窗 / 下抽的数学抽成 [linearResample] 与 [fingerprintSamples]，
 * JVM 单测直接钉死；MediaCodec 那部分没法单测，真机验证兜底。
 */
object PcmExtractor {

    private const val TAG = "SonanceFp"

    /** 最多解这么长（4s 起点 + 6s 指纹窗 + 1s 余量） */
    private const val MAX_DECODE_SECONDS = 11.0

    /** 指纹引擎要求的采样率 */
    const val TARGET_RATE = 48_000

    /** 指纹窗时长（秒）—— 与请求参数 duration 一致 */
    const val WINDOW_SECONDS = 6.0

    /** 指纹窗起点（秒）—— 与参考实现 Encode(data, 4, 6, 0) 的 4 一致 */
    const val WINDOW_FROM_SECONDS = 4.0

    /** 一次解码的结果：单声道 PCM + 它的采样率 */
    class Decoded(val mono: FloatArray, val sampleRate: Int)

    /**
     * 解码 [uri] 的开头 [MAX_DECODE_SECONDS] 秒为单声道 Float32 PCM。
     *
     * @param totalDurationMs 文件总时长（MediaStore 已知值），用于把解码上限
     *                        收到「min(11s, 全曲)」，避免对超短文件空转
     * @return 解码失败（没有音轨 / 编码器不给力）返回 null，由调用方如实报错
     */
    fun decodePrefix(context: Context, uri: Uri, totalDurationMs: Long): Decoded? = try {
        decodeImpl(context, uri, totalDurationMs)
    } catch (t: Throwable) {
        Log.w(TAG, "音频解码失败: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun decodeImpl(context: Context, uri: Uri, totalDurationMs: Long): Decoded? {
        val capSeconds = if (totalDurationMs > 0) {
            min(MAX_DECODE_SECONDS, totalDurationMs / 1000.0)
        } else {
            MAX_DECODE_SECONDS
        }

        val afd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        afd.use { fd ->
            val extractor = MediaExtractor()
            extractor.setDataSource(fd.fileDescriptor)
            try {
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                extractor.selectTrack(trackIndex)

                // 解码上限必须按**源采样率**估：MediaCodec 吐出的是原生率的帧。
                // 曾按 48k 目标率估——44.1k 的歌只解出 ~6s，指纹窗 4~10s 后段
                // 全是补零，指纹必然错配（noMatchReason=10）。加 5% 余量。
                val srcRate = runCatching {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }.getOrDefault(TARGET_RATE)
                val capSamples = (capSeconds * srcRate * 1.05).toInt()

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = runCatching {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(1)
                var pcmFloat = runCatching {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }.getOrDefault(false)

                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()

                    val out = ArrayList<Float>(capSamples * 2)
                    var inputDone = false
                    var outputDone = false
                    val info = MediaCodec.BufferInfo()
                    var spins = 0
                    while (!outputDone && spins < 3000) {
                        spins++
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                sampleRate = runCatching {
                                    of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }.getOrDefault(sampleRate)
                                channels = runCatching {
                                    of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }.getOrDefault(channels)
                                pcmFloat = runCatching {
                                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                                }.getOrDefault(pcmFloat)
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> {
                                if (outIdx >= 0) {
                                    val buf = codec.getOutputBuffer(outIdx)!!
                                    val shorts = info.size / 2
                                    if (pcmFloat) {
                                        buf.asFloatBuffer().let { fb ->
                                            val n = info.size / 4
                                            var k = 0
                                            while (k < n && out.size / channels < capSamples) {
                                                out += fb.get(k)
                                                k++
                                            }
                                        }
                                    } else {
                                        buf.asShortBuffer().let { sb ->
                                            var k = 0
                                            while (k < shorts && out.size / channels < capSamples) {
                                                out += sb.get(k) / 32768f
                                                k++
                                            }
                                        }
                                    }
                                    codec.releaseOutputBuffer(outIdx, false)
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        outputDone = true
                                    }
                                    if (out.size / channels >= capSamples) {
                                        outputDone = true
                                    }
                                }
                            }
                        }
                    }
                    if (out.isEmpty()) return null

                    // 交织 → 单声道：取第 0 声道（与参考实现 getChannelData(0) 一致）
                    val frames = out.size / channels
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) mono[f] = out[f * channels]
                    // 显式 return：穿过两层 finally 时表达式收尾的类型推断不可靠
                    return Decoded(mono, sampleRate)
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }
    }

    /* ============================================================
     * 纯函数：重采样与指纹窗抽取（JVM 可测）
     * ============================================================ */

    /** 线性插值重采样（指纹是粗粒度特征，线性插值足够） */
    internal fun linearResample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        if (fromRate <= 0 || toRate <= 0) return input
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    /**
     * 从 48kHz 单声道 PCM 里抽指纹窗 —— **严格对齐参考实现的 toNCMBuffer**：
     * 起点第 4 秒、取 6 秒、每 6 个采样取 1 个（等效 8kHz × 48000 值）。
     *
     * 不足 6 秒的音频（试听残曲）：起点退到 max(0, 全长 - 6s)，尾部补零 ——
     * 参考实现对越界读 undefined（NaN），这里补零更稳。
     */
    internal fun fingerprintSamples(mono48k: FloatArray): FloatArray {
        val rate = TARGET_RATE
        val durSec = mono48k.size.toDouble() / rate
        val from = (min(WINDOW_FROM_SECONDS, maxOf(0.0, durSec - WINDOW_SECONDS)) * rate).toInt()
        val out = FloatArray((WINDOW_SECONDS * 8000).toInt())   // 48000
        for (i in out.indices) {
            val idx = from + i * 6
            out[i] = if (idx in mono48k.indices) mono48k[idx] else 0f
        }
        return out
    }

    /** 解码结果 → 指纹输入（重采样到 48k + 抽窗），一条龙 */
    internal fun toFingerprintSamples(decoded: Decoded): FloatArray =
        fingerprintSamples(linearResample(decoded.mono, decoded.sampleRate, TARGET_RATE))
}
