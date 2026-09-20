package com.sonance.player.playback

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 均衡器：**纯软件实现**，挂在 ExoPlayer 的音频链路上做 biquad 滤波。
 *
 * ## 为什么不用 `android.media.audiofx.Equalizer`
 *
 * 系统均衡器和 `Visualizer` 走同一套 `AudioEffect` 机制。这台 realme/ColorOS
 * 的 AudioFlinger 已经明确拒绝给应用会话创建 `Visualizer`（`status: -1`），
 * `Equalizer` 同样会被拒 —— 那样 UI 只能显示"本机不支持"，功能等于没有。
 *
 * 软件实现走我们已经有的 `AudioProcessor` 链路（`PcmTapProcessor` 就是这么接的），
 * 不依赖任何系统音效服务，**全机型可用**，而且频段由我们自己定，换机不会变形。
 *
 * ## 结构
 *
 * - [EqProcessor]：透传 + 级联 biquad。挂在 `PcmTapProcessor` **之前**，
 *   于是可视化读到的是"用户实际听到的声音"（调了低音增强，柱子也跟着动）。
 * - [EqBus]：音频线程 ↔ UI 线程的共享设置。UI 只写这里，处理器在音频线程读。
 * - [EqPreset]：预设曲线（单位 dB，段数 = [EQ_CENTER_FREQS].size）。
 */

/** 10 段中心频率（Hz），31Hz–16kHz，与常见硬件均衡器的分段一致 */
val EQ_CENTER_FREQS: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** 频段数（滑杆条数） */
val EQ_BAND_COUNT: Int get() = EQ_CENTER_FREQS.size

/** 频段标签，给 UI 用：1000 以上折成 "1k" 这种短写法，否则一行放不下 */
fun eqBandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

/** 单段 Q 值。10 段按倍频程排布，Q≈1.3 时相邻段重叠适中，不会叠出一片隆起 */
internal const val EQ_Q = 1.3f

/** 增益上下限（dB），滑杆范围和预设曲线都受它约束 */
const val EQ_MIN_DB = -12f
const val EQ_MAX_DB = 12f

/**
 * 音频线程 ↔ UI 线程之间的均衡器设置总线。
 *
 * 用 [revision] 单调递增来传递"设置变了"：音频线程只在版本号变化时重算一次系数，
 * 不必每块都做浮点幂运算。
 */
object EqBus {

    /** 总开关。"关闭"预设就是 `enabled = false`，整条滤波链直接旁路，不浪费 CPU */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 各段增益（dB），长度恒为 [EQ_BAND_COUNT] */
    @Volatile
    var gainsDb: FloatArray = FloatArray(EQ_BAND_COUNT)
        private set

    @Volatile
    var revision: Long = 0L
        private set

    fun update(enabled: Boolean, gainsDb: FloatArray) {
        this.enabled = enabled
        this.gainsDb = FloatArray(EQ_BAND_COUNT) { gainsDb.getOrElse(it) { 0f } }
        revision++
    }

    /** 单测用：把总线复位，避免用例之间互相污染 */
    @VisibleForTesting
    internal fun reset() {
        enabled = false
        gainsDb = FloatArray(EQ_BAND_COUNT)
        revision = 0L
    }
}

/**
 * 级联 peaking biquad 滤波器组。
 *
 * 单独抽出来是为了**可测**：`BaseAudioProcessor` 的 `configure`/`queueInput`
 * 都是框架回调、`outputBuffer` 还是 protected，在 JVM 单测里根本驱动不起来。
 * 把纯 DSP 放到这里之后，测试可以直接喂一段正弦波进去、量输出幅度，
 * 真的验证"1kHz 抬 6dB 之后 1kHz 的能量确实变大、20Hz 基本不变"。
 *
 * 无锁：系数在音频线程重算，状态也只被音频线程读写。
 */
@VisibleForTesting
internal class EqCascade(val bandCount: Int, val channels: Int) {

    private val b0 = FloatArray(bandCount)
    private val b1 = FloatArray(bandCount)
    private val b2 = FloatArray(bandCount)
    private val a1 = FloatArray(bandCount)
    private val a2 = FloatArray(bandCount)

    /** 每声道、每段的 DF2T 状态 */
    private val z1 = Array(channels) { FloatArray(bandCount) }
    private val z2 = Array(channels) { FloatArray(bandCount) }

    /** 按当前采样率把 [gainsDb] 设计成系数（[freqs] 与 [gainsDb] 一一对应） */
    fun design(freqs: IntArray, gainsDb: FloatArray, sampleRate: Int) {
        var b = 0
        while (b < bandCount) {
            val c = designPeakingCoeffs(
                f0 = freqs.getOrElse(b) { 0 }.toFloat(),
                gainDb = gainsDb.getOrElse(b) { 0f },
                sampleRate = sampleRate
            )
            b0[b] = c[0]; b1[b] = c[1]; b2[b] = c[2]; a1[b] = c[3]; a2[b] = c[4]
            b++
        }
    }

    /** 清滤波器状态（seek / 暂停后必须做，否则会带出一声残留的"爆音"） */
    fun reset() {
        var ch = 0
        while (ch < channels) {
            z1[ch].fill(0f)
            z2[ch].fill(0f)
            ch++
        }
    }

    /**
     * 就地处理交错（interleaved）的 float PCM。
     *
     * @param buf    交错采样，长度 = frames * channels
     * @param frames 帧数
     */
    fun process(buf: FloatArray, frames: Int) {
        var ch = 0
        while (ch < channels) {
            val s1 = z1[ch]
            val s2 = z2[ch]
            var i = 0
            while (i < frames) {
                val idx = i * channels + ch
                var x = buf[idx]
                var b = 0
                while (b < bandCount) {
                    // DF2T：状态更新必须用**输入** x，先算 y 再更新，不能把 x 覆盖掉
                    val y = b0[b] * x + s1[b]
                    s1[b] = b1[b] * x - a1[b] * y + s2[b]
                    s2[b] = b2[b] * x - a2[b] * y
                    x = y
                    b++
                }
                buf[idx] = x
                i++
            }
            ch++
        }
    }
}

/**
 * 均衡处理器：把输入原样交给下游，中间串一串 peaking biquad。
 *
 * 只处理 16bit / float PCM —— 其它编码直接旁路（保持原样透传），
 * 免得在不认识的格式上瞎改字节。
 */
class EqProcessor : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    private var cascade: EqCascade? = null

    private var appliedRevision = -1L
    private var appliedEnabled = false

    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)

    /** 当前是否真的在滤波（`enabled == false` 时整条链旁路） */
    private var filtering: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            sampleRate = 0
            channels = 0
            encoding = C.ENCODING_INVALID
            cascade = null
            Log.w(TAG, "均衡器：暂不支持的编码 $enc，将直接旁路")
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        cascade = EqCascade(EQ_BAND_COUNT, channels)
        // 采样率变了，系数必须重算
        appliedRevision = -1L
        Log.i(TAG, "均衡器已就绪：${sampleRate}Hz ${channels}ch enc=$enc")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        syncSettings()
        val out = replaceOutputBuffer(remaining)
        val cas = cascade

        if (!filtering || cas == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = if (frameBytes > 0) remaining / frameBytes else 0
        val samples = frames * channels
        if (frames <= 0 || samples <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

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

        cas.process(floatScratch, frames)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                if (shortScratch.size < samples) shortScratch = ShortArray(samples)
                var i = 0
                while (i < samples) {
                    val v = floatScratch[i]
                    shortScratch[i] = when {
                        v >= 1f -> Short.MAX_VALUE
                        v <= -1f -> Short.MIN_VALUE
                        else -> (v * 32767f).toInt().toShort()
                    }
                    i++
                }
                out.asShortBuffer().put(shortScratch, 0, samples)
            }
            else -> out.asFloatBuffer().put(floatScratch, 0, samples)
        }
        // asShortBuffer()/asFloatBuffer() 是视图，写它不会推进原缓冲的 position
        out.position(remaining)
        out.flip()
    }

    override fun onFlush() {
        cascade?.reset()
    }

    override fun onReset() {
        cascade?.reset()
        cascade = null
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
        appliedRevision = -1L
    }

    /**
     * 把 [EqBus] 里的设置同步到本地：只在版本号或开关变化时重算系数。
     *
     * 注意 [filtering] 在 `enabled == false` 时为 false —— 旁路是零成本的，
     * 不会为了"设成全 0dB"还跑一遍 10 段乘加。
     */
    private fun syncSettings() {
        val rev = EqBus.revision
        val en = EqBus.enabled
        if (rev == appliedRevision && en == appliedEnabled) return
        appliedRevision = rev
        appliedEnabled = en

        val cas = cascade
        filtering = cas != null && en && sampleRate > 0
        if (!filtering || cas == null) return
        cas.design(EQ_CENTER_FREQS, EqBus.gainsDb, sampleRate)
    }

    private companion object {
        const val TAG = "SonanceEq"
    }
}

/**
 * 设计一个 peaking（钟形）biquad，返回 `[b0, b1, b2, a1, a2]`（已按 a0 归一化）。
 *
 * 用 RBJ Audio EQ Cookbook 的标准公式：
 * ```
 * A     = 10^(gainDb / 40)
 * w0    = 2π·f0/Fs
 * alpha = sin(w0) / (2Q)
 * b0 = (1 + alpha·A)/a0   b1 = -2cos(w0)/a0   b2 = (1 - alpha·A)/a0
 * a1 = -2cos(w0)/a0       a2 = (1 - alpha/A)/a0    a0 = 1 + alpha/A
 * ```
 * 差分方程 `y = b0·x + b1·x₁ + b2·x₂ - a1·y₁ - a2·y₂`，正好对上 DF2T 的写法。
 *
 * 频率超过 Nyquist 或增益为 0 时返回单位增益（直通），避免高采样率设备上
 * 16kHz 段在 32kHz 采样时被算成一段乱滤波。
 */
@VisibleForTesting
internal fun designPeakingCoeffs(
    f0: Float,
    gainDb: Float,
    sampleRate: Int,
    q: Float = EQ_Q
): FloatArray {
    val fs = sampleRate.toFloat()
    if (gainDb == 0f || f0 <= 0f || fs <= 0f || f0 >= fs * 0.5f) {
        return floatArrayOf(1f, 0f, 0f, 0f, 0f)
    }
    val a = 10f.pow(gainDb / 40f)
    val w0 = 2.0 * PI * f0 / fs
    val cosW = cos(w0).toFloat()
    val sinW = sin(w0).toFloat()
    val alpha = sinW / (2f * q)
    val a0 = 1f + alpha / a
    return floatArrayOf(
        (1f + alpha * a) / a0,
        (-2f * cosW) / a0,
        (1f - alpha * a) / a0,
        (-2f * cosW) / a0,
        (1f - alpha / a) / a0
    )
}

/**
 * 均衡器预设。
 *
 * 每个预设直接给 **10 段增益（dB）**，与 [EQ_CENTER_FREQS] 一一对应 ——
 * 不再做"归一化 → 按设备段数映射"，因为软件均衡器的段数由我们自己决定，
 * 所有设备完全一致。
 *
 * 增益普遍压在 ±8dB 以内：峰值再高，16bit 输出一削顶就是失真，得不偿失。
 */
enum class EqPreset(val label: String, val curve: FloatArray) {

    /** 关闭：整条滤波链旁路 */
    OFF("关闭", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),

    /** 流行：中频抬一点、两端略降，人声和旋律更靠前 */
    POP("流行", floatArrayOf(-1f, -0.5f, 1f, 3f, 4f, 3.5f, 2f, 1f, 1.5f, 2f)),

    /** 人声：中高频突出，低频让路，听歌听清咬字 */
    VOCAL("人声", floatArrayOf(-4f, -2.5f, -1f, 0.5f, 2.5f, 4f, 4.5f, 3f, 1f, -1f)),

    /** 摇滚：典型 V 型，低频和镲片两头翘 */
    ROCK("摇滚", floatArrayOf(5f, 3.5f, 2f, -1f, -1.5f, 0f, 1.5f, 3f, 4f, 4.5f)),

    /** 爵士：低频温暖、中频略退、高频通透 */
    JAZZ("爵士", floatArrayOf(3.5f, 2.5f, 1f, 1.5f, -1f, -1f, 0f, 1.5f, 2.5f, 3f)),

    /** 古典：整体平直，两端轻微抬起补厅堂感 */
    CLASSICAL("古典", floatArrayOf(3.5f, 2.5f, 1.5f, 0f, -1f, -1f, 0f, 1.5f, 3f, 3.5f)),

    /** 舞曲：低频有力 + 中高频明亮，节奏感强 */
    DANCE("舞曲", floatArrayOf(6f, 5f, 2.5f, 0f, 1f, 2.5f, 3.5f, 4f, 3f, 1f)),

    /** 低音增强：低频拉满，其余递减以免轰头 */
    BASS("低音增强", floatArrayOf(8f, 7f, 5.5f, 3f, 1f, -0.5f, -1.5f, -2f, -2f, -2f)),

    /** 高音增强：齿音与细节变清楚，低频收掉 */
    TREBLE("高音增强", floatArrayOf(-4f, -3f, -2f, -1f, 0f, 1f, 2.5f, 4.5f, 6f, 6.5f)),

    /** 柔和：整体压两端，睡前/长时间听不累 */
    SOFT("柔和", floatArrayOf(1.5f, 1.5f, 1f, 0.5f, 0f, -0.5f, -1f, -2f, -3f, -3.5f));

    companion object {
        /** 按名字取预设（持久化用），找不到就回落到 [OFF] */
        fun fromName(name: String?): EqPreset =
            entries.firstOrNull { it.name == name } ?: OFF

        /** 预设曲线，长度保证等于 [EQ_BAND_COUNT]，并且夹在 [EQ_MIN_DB]..[EQ_MAX_DB] 内 */
        fun curveOf(preset: EqPreset): FloatArray =
            FloatArray(EQ_BAND_COUNT) {
                preset.curve.getOrElse(it) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
            }
    }
}
