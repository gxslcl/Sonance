package com.sonance.player.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 人声/伴奏（卡拉OK）模式：**中置声道消除**。
 *
 * ## 原理与边界
 *
 * 人声通常录制在立体声的**正中央**（主唱轨 L=R），而伴奏的鼓、贝斯、吉他
 * 往往偏在某一侧。把两个声道相减（L−R）正好把居中的内容抵消掉，留下伴奏。
 *
 * 这是经典 DSP 技巧，不是 AI 分离 —— 效果因歌而异：
 * - 单声道文件（L 恒等于 R）相减后是全零，**无效**，所以直接旁路；
 * - 人声带宽混响或偏声道录法的歌，人声只能减弱、不能完全消掉；
 * - 输出是「侧信号」（side），声场会比原曲窄，属预期。
 *
 * ## 结构
 *
 * 与 [EqBus] 同一套约定：UI 写 [KaraokeBus]，处理器在音频线程读，开关
 * 即时生效（`enabled=false` 时整块旁路，零开销）。
 */
object KaraokeBus {

    /** true = 伴奏模式（中置消除），false = 原唱直通 */
    @Volatile
    var enabled: Boolean = false
}

/**
 * 卡拉OK 处理器：立体声逐样本 `side = (L − R)`，左右声道反相输出。
 *
 * 只处理 16bit / float 的**双声道** PCM —— 其它编码或声道数直接旁路。
 * 相减幅度最高是原信号的两倍，16bit 输出在写入前做钳制防削顶。
 */
class KaraokeProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    private var floatScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount == 2
        ) {
            channels = 2
            encoding = enc
            Log.i(TAG, "卡拉OK处理器就绪：${inputAudioFormat.sampleRate}Hz 2ch enc=$enc")
        } else {
            channels = 0
            encoding = C.ENCODING_INVALID
            Log.w(TAG, "卡拉OK：$enc ${inputAudioFormat.channelCount}ch 不支持中置消除，将直接旁路")
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val out = replaceOutputBuffer(remaining)
        if (!KaraokeBus.enabled || channels != 2) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = if (frameBytes > 0) remaining / frameBytes else 0
        if (frames <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val samples = frames * 2
        if (floatScratch.size < samples) floatScratch = FloatArray(samples)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                while (i < samples) {
                    floatScratch[i] = inputBuffer.short / 32768f
                    i++
                }
            }
            else -> {
                var i = 0
                while (i < samples) {
                    floatScratch[i] = inputBuffer.float
                    i++
                }
            }
        }

        // 中置消除：side = (L − R)，R 输出反相 —— 两声道能量对称，总响度 -3dB 左右
        var i = 0
        while (i < samples) {
            val l = floatScratch[i]
            val r = floatScratch[i + 1]
            val side = l - r
            floatScratch[i] = side
            floatScratch[i + 1] = -side
            i += 2
        }

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var j = 0
                while (j < samples) {
                    val v = floatScratch[j]
                    out.asShortBuffer().put(j, when {
                        v >= 1f -> Short.MAX_VALUE
                        v <= -1f -> Short.MIN_VALUE
                        else -> (v * 32767f).toInt().toShort()
                    })
                    j++
                }
            }
            else -> out.asFloatBuffer().put(floatScratch, 0, samples)
        }
        // asShortBuffer()/asFloatBuffer() 是视图，写它不会推进原缓冲的 position
        out.position(remaining)
        out.flip()
    }

    override fun onReset() {
        channels = 0
        encoding = C.ENCODING_INVALID
    }

    private companion object {
        const val TAG = "SonanceKaraoke"
    }
}
