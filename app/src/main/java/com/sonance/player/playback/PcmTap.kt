package com.sonance.player.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * 无锁环形缓冲：单生产者（音频线程）/ 单消费者（UI 线程），存单声道 float 采样。
 *
 * [capacity] 必须是 2 的幂，这样取模可以用位与。
 * 读取时可能读到正在写入的那一帧（撕裂），对可视化来说无伤大雅，换来的是音频线程零锁。
 */
class PcmRing(val capacity: Int) {

    private val mask = capacity - 1
    private val buf = FloatArray(capacity)

    /** 单调递增的总写入采样数；读取方用它判断"有没有新数据" */
    @Volatile
    var writePos: Long = 0L
        private set

    fun write(src: FloatArray, count: Int) {
        if (count <= 0) return
        val pos = writePos
        var i = 0
        while (i < count) {
            buf[(pos.toInt() + i) and mask] = src[i]
            i++
        }
        writePos = pos + count
    }

    /**
     * 把最近 [count] 个采样按时间顺序（旧→新）拷进 [out]，不足部分补 0。
     * @return 实际可用的采样数
     */
    fun readLatest(out: FloatArray, count: Int): Int {
        val avail = writePos.coerceAtMost(capacity.toLong()).toInt()
        val n = minOf(count, avail)
        val start = writePos - n
        var i = 0
        while (i < n) {
            out[i] = buf[(start.toInt() + i) and mask]
            i++
        }
        while (i < count) {
            out[i] = 0f
            i++
        }
        return n
    }

    fun reset() {
        writePos = 0L
    }
}

/**
 * 音频线程 ↔ UI 线程之间的共享总线。
 *
 * 不再需要 audio session：我们直接取自己播放链路上的 PCM。
 */
object PcmBus {
    val ring = PcmRing(8192)

    @Volatile
    var sampleRate: Int = 0

    @Volatile
    var channels: Int = 0

    /** 已拿到格式并且正在收到数据 */
    @Volatile
    var ready: Boolean = false

    /** 已收到的数据块数，UI 用来判断"是否还在出新数据" */
    @Volatile
    var buffersSeen: Long = 0L

    /**
     * 最近一个音频缓冲的原始 RMS（0..1）。
     *
     * 在 [PcmTapProcessor.tap] 转单声道的那一层顺手累加平方和算出，**不额外遍历、不做 FFT**。
     * 播放页的封面/晕染「呼吸」只需要一个整体能量，用不上频谱；这样比再实例化一套
     * [AudioVisualizer] 便宜得多（后者只在律动柱那一层才有）。UI 侧再按 attack/release 平滑。
     */
    @Volatile
    var rms: Float = 0f
}

/**
 * 挂在 ExoPlayer 音频链路上的 PCM 取样器。
 *
 * 为什么不用 `android.media.audiofx.Visualizer`：它在 realme/ColorOS（Android 16）上
 * 连应用自己的会话都建不起来（`Cannot initialize Visualizer engine, error: -3`），
 * 而 Android 10+ 又禁止普通应用抓全局输出。直接取自家 PCM 不需要任何权限，且各机型一致。
 *
 * 实现上是一个纯透传的 [AudioProcessor]：把输入原样交给下游，同时复制一份转成单声道 float 写进 [PcmBus]。
 */
class PcmTapProcessor : BaseAudioProcessor() {

    /** 预分配的单声道转换区（在 onConfigure 里定尺寸，queueInput 里不再分配） */
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val supported = enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT
        if (!supported) {
            scratch = FloatArray(0)
            Log.w(TAG, "PCM tap: 暂不支持的编码 $enc，可视化将没有数据")
            return inputAudioFormat
        }
        // 0.5 秒足够覆盖单次回调，超大缓冲会在 queueInput 里分块
        val frames = (inputAudioFormat.sampleRate / 2).coerceAtLeast(1024)
        scratch = FloatArray(frames)
        PcmBus.sampleRate = inputAudioFormat.sampleRate
        PcmBus.channels = inputAudioFormat.channelCount
        Log.i(
            TAG,
            "PCM tap 已配置: ${inputAudioFormat.sampleRate}Hz " +
                "${inputAudioFormat.channelCount}ch enc=$enc scratch=$frames"
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // 先取样：读完把 position 退回去，再原样透传给下游
        val start = inputBuffer.position()
        runCatching { tap(inputBuffer) }
        inputBuffer.position(start)

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        // 暂停 / seek 会走到这里：清掉缓冲，让可视化知道已经断流
        PcmBus.ring.reset()
        PcmBus.ready = false
        PcmBus.buffersSeen = 0L
        PcmBus.rms = 0f
    }

    override fun onReset() {
        PcmBus.ring.reset()
        PcmBus.ready = false
        PcmBus.buffersSeen = 0L
        PcmBus.rms = 0f
    }

    /** 从 [buffer] 当前 position 读到底，转单声道 float 写进环形缓冲 */
    private fun tap(buffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val frameBytes = fmt.bytesPerFrame
        if (ch <= 0 || frameBytes <= 0 || scratch.isEmpty()) return

        val frames = buffer.remaining() / frameBytes
        if (frames <= 0) return

        val enc = fmt.encoding
        var done = 0
        // 顺手统计能量：同一轮采样循环里累加平方和，不额外遍历
        var sumSq = 0f
        var counted = 0
        while (done < frames) {
            val n = minOf(scratch.size, frames - done)
            var i = 0
            while (i < n) {
                var sum = 0f
                var c = 0
                while (c < ch) {
                    sum += when (enc) {
                        C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                        C.ENCODING_PCM_FLOAT -> buffer.float
                        else -> 0f
                    }
                    c++
                }
                val mono = sum / ch
                scratch[i] = mono
                sumSq += mono * mono
                i++
            }
            PcmBus.ring.write(scratch, n)
            counted += n
            done += n
        }

        if (counted > 0) PcmBus.rms = sqrt(sumSq / counted)

        PcmBus.buffersSeen++
        if (!PcmBus.ready) {
            PcmBus.ready = true
            Log.i(TAG, "PCM tap 收到首个数据块: ${PcmBus.sampleRate}Hz ${PcmBus.channels}ch")
        }
    }

    companion object {
        private const val TAG = "SonanceViz"
    }
}
