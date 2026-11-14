package com.sonance.player.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 人声/伴奏（卡拉OK）模式：**频带限制中置人声消除**。
 *
 * ## 声学原理升级
 *
 * 旧版整段直接做 `side = L - R` 并反相输出 `(side, -side)`，会连同正中央的低音底鼓、贝斯一起砍空，
 * 遇到部分真单声道外放手机反相求和还会完全静音。
 * 现在改为先提取中置通道 `mid = (L + R) * 0.5`，再用二阶 160 Hz 高通 + 4.8 kHz 低通锁定主唱人声核心频段，
 * 从左右声道同相位等量抵消 `0.92 * band(mid)`：
 * - `<160 Hz` 底鼓与贝斯保持丰满有力；
 * - `>4.8 kHz` 鼓镲与高频细节自然保留；
 * - 立体声伴奏乐器保持原有左右声像，单声道扬声器求和也不会抵消变静音。
 */
object KaraokeBus {

    /** true = 伴奏模式（频带中置人声消除），false = 原唱直通 */
    @Volatile
    var enabled: Boolean = false
}

/**
 * 卡拉OK 处理器：立体声频带限制中置消除（支持 16 / 24 / 32 位整型与 32 位浮点 PCM）。
 */
class KaraokeProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var vocalHpf: DoubleBiquad? = null
    private var vocalLpf: DoubleBiquad? = null

    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)
    private var intScratch = IntArray(0)
    private var ditherSeed = 0x2468ACE1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val sr = inputAudioFormat.sampleRate
        if (PcmCodec.isSupportedEncoding(enc) && inputAudioFormat.channelCount == 2 && sr > 0) {
            channels = 2
            encoding = enc
            vocalHpf = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.highPass(160.0, sr)) }
            vocalLpf = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.lowPass(4800.0, sr)) }
            Log.i(TAG, "卡拉OK处理器就绪：${sr}Hz 2ch enc=$enc")
        } else {
            channels = 0
            encoding = C.ENCODING_INVALID
            vocalHpf = null
            vocalLpf = null
            Log.w(TAG, "卡拉OK：$enc ${inputAudioFormat.channelCount}ch 不支持中置消除，将直接旁路")
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val out = replaceOutputBuffer(remaining)
        val hpf = vocalHpf
        val lpf = vocalLpf
        if (!KaraokeBus.enabled || channels != 2 || hpf == null || lpf == null) {
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
        if (encoding == C.ENCODING_PCM_32BIT && intScratch.size < samples) intScratch = IntArray(samples)

        PcmCodec.readToFloat(inputBuffer, floatScratch, samples, encoding)

        // 只抵消 160 Hz..4.8 kHz 的居中主唱频段，保留低频贝斯底鼓与立体声高频伴奏
        var i = 0
        while (i < samples) {
            val l = floatScratch[i]
            val r = floatScratch[i + 1]
            val mid = (l + r) * 0.5
            val band = lpf.processSample(hpf.processSample(mid, 0), 0).toFloat() * 0.92f
            floatScratch[i] = l - band
            floatScratch[i + 1] = r - band
            i += 2
        }

        val (nextSeed, updatedShorts) = PcmCodec.writeFromFloat(
            src = floatScratch,
            outBuffer = out,
            remainingBytes = remaining,
            samples = samples,
            encoding = encoding,
            shortScratch = shortScratch,
            intScratch = intScratch,
            applyDither = true,
            ditherSeed = ditherSeed
        )
        ditherSeed = nextSeed
        shortScratch = updatedShorts
    }

    override fun onFlush() {
        vocalHpf?.reset()
        vocalLpf?.reset()
        ditherSeed = 0x2468ACE1
    }

    override fun onReset() {
        vocalHpf?.reset()
        vocalLpf?.reset()
        vocalHpf = null
        vocalLpf = null
        channels = 0
        encoding = C.ENCODING_INVALID
    }

    private companion object {
        const val TAG = "SonanceKaraoke"
    }
}
