package com.sonance.player.playback

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 合并式音效处理器。
 *
 * EQ 和虚拟低音 / 混响 / 声场 / 峰值保护在同一条浮点链路中完成，最后才量化为 PCM。
 * 这样 EQ 增益不会先在 16 位中间缓冲削顶，再被后面的前置增益“救”回来。
 *
 * 算法全部是公开的数学原理（RBJ biquad、Freeverb 式 comb/allpass、missing-fundamental
 * 谐波生成、M/S 矩阵、峰值限幅），自己实现，不引第三方引擎、不加载 IR 文件。
 * 纯 DSP 部分抽成 [SoundFxDsp]，JVM 单测里可以直接喂正弦波量输出。
 */
class SoundFxProcessor : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var dsp: SoundChainDsp? = null
    private var appliedFxRevision = -1L
    private var appliedEqRevision = -1L

    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || ch !in 1..32 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            sampleRate = 0
            channels = 0
            encoding = C.ENCODING_INVALID
            dsp = null
            Log.w(TAG, "音效：不支持的编码 $enc / ${ch}ch，直接旁路")
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = ch
        encoding = enc
        dsp = SoundChainDsp(sampleRate, ch)
        appliedFxRevision = -1L
        appliedEqRevision = -1L
        Log.i(TAG, "音效处理器已就绪：${sampleRate}Hz ${ch}ch enc=$enc")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        syncSettings()
        val out = replaceOutputBuffer(remaining)
        val engine = dsp
        if (engine == null || !engine.needsProcessing) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = if (frameBytes > 0) remaining / frameBytes else 0
        val samples = frames * channels
        if (frames <= 0 || samples <= 0 || remaining % frameBytes != 0) {
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

        engine.process(floatScratch, frames)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                if (shortScratch.size < samples) shortScratch = ShortArray(samples)
                var i = 0
                while (i < samples) {
                    val v = floatScratch[i]
                    shortScratch[i] = when {
                        v >= 1f -> Short.MAX_VALUE
                        v <= -1f -> Short.MIN_VALUE
                        else -> (v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
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

    /** 切歌 / seek 必须清掉混响延迟线，否则上一首的尾巴会拖进下一首 */
    override fun onFlush() {
        dsp?.reset()
        // reset 清除了目标参数；即使总线版本没变，下一块也必须重新同步。
        appliedFxRevision = -1L
        appliedEqRevision = -1L
    }

    override fun onReset() {
        dsp?.reset()
        dsp = null
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
        appliedFxRevision = -1L
        appliedEqRevision = -1L
    }

    private fun syncSettings() {
        val fxRev = SoundFxBus.revision
        val eqRev = EqBus.revision
        if (fxRev == appliedFxRevision && eqRev == appliedEqRevision) return
        appliedFxRevision = fxRev
        appliedEqRevision = eqRev
        val bypass = SoundFxBus.bypass
        dsp?.setTarget(
            eqEnabled = EqBus.enabled && !bypass,
            gainsDb = EqBus.gainsDb,
            fx = if (SoundFxBus.enabled && !bypass) SoundFxBus.params else SoundFxParams.NEUTRAL
        )
    }

    private companion object {
        const val TAG = "SoundFx"
    }
}

/** 整条链共用浮点缓冲；只在最终输出前量化。 */
@VisibleForTesting
internal class SoundChainDsp(private val sampleRate: Int, private val channels: Int) {
    private val rampFrames = (sampleRate * 0.03f).toInt().coerceAtLeast(1)
    private val eq = SmoothedEqualizer(sampleRate, channels, rampFrames)
    private val fx = SoundFxDsp(sampleRate, channels)
    private val preamp = LinearRamp(1f)
    private val activeMix = LinearRamp(0f)
    private val safety = SoftLimiter(sampleRate)
    private var dryScratch = FloatArray(0)
    private var lastGains: FloatArray? = null
    private var eqBoostDb = 0f

    val needsProcessing: Boolean
        get() = !eq.isNeutral || !fx.isNeutral || !preamp.isSettledAt(1f) || !activeMix.isSettledAt(0f)

    fun setTarget(eqEnabled: Boolean, gainsDb: FloatArray, fx: SoundFxParams) {
        val gains = FloatArray(EQ_BAND_COUNT) { i ->
            val value = gainsDb.getOrElse(i) { 0f }
            if (eqEnabled && value.isFinite()) value.coerceIn(EQ_MIN_DB, EQ_MAX_DB) else 0f
        }
        val params = fx.clamp()
        activeMix.setTarget(if (gains.any { it != 0f } || !params.isNeutral) 1f else 0f, rampFrames)
        eq.setTarget(gains)
        // 前置增益在 EQ 之前，而非 EQ 后的 16 位缓冲中。
        this.fx.setTarget(params.copy(preampDb = 0f))
        if (lastGains?.contentEquals(gains) != true) {
            eqBoostDb = EqResponse(gains, sampleRate).maxBoostDb()
            lastGains = gains.copyOf()
        }
        val widthBudget = if (channels == 2 && params.stereoWidth > 1f) {
            12f * log10(params.stereoWidth)
        } else 0f
        val effectBudget = if (channels <= 2) {
            widthBudget + params.virtualBass * 0.25f + params.reverbWet * 0.25f
        } else 0f
        val boost = eqBoostDb + effectBudget
        // 见 eqAutoPreampDb：日常曲线只留轻量净空，切换预设时响度稳定不塌陷。
        val automaticDb = eqAutoPreampDb(boost)
        val gainDb = min(params.preampDb, automaticDb)
        preamp.setTarget(10f.pow(gainDb / 20f), rampFrames)
    }

    fun process(buf: FloatArray, frames: Int) {
        val samples = frames * channels
        if (dryScratch.size < samples) dryScratch = FloatArray(samples)
        buf.copyInto(dryScratch, endIndex = samples)
        var index = 0
        repeat(frames) {
            val gain = preamp.next()
            for (ch in 0 until channels) {
                val sample = buf[index + ch]
                buf[index + ch] = (if (sample.isFinite()) sample.coerceIn(-8f, 8f) else 0f) * gain
            }
            eq.processFrame(buf, index)
            index += channels
        }
        fx.process(buf, frames)
        // 无论用户是否启用更早介入的峰值保护，最终 PCM 都不应发生硬削顶。
        safety.process(buf, frames, channels, -0.1f)
        // 旁通时交叉淡回原始 PCM，即使限幅器正在释放也不会在切换点突然跳响。
        index = 0
        repeat(frames) {
            val mix = activeMix.next()
            for (ch in 0 until channels) {
                val dry = dryScratch[index + ch].let { if (it.isFinite()) it else 0f }
                buf[index + ch] = dry + (buf[index + ch] - dry) * mix
            }
            index += channels
        }
        // 完全旁通后不会继续驱动释放包络；清掉旧状态，避免隔很久再开启时突然变小声。
        if (!needsProcessing) safety.reset()
    }

    fun reset() {
        eq.reset()
        fx.reset()
        preamp.reset(1f)
        activeMix.reset(0f)
        safety.reset()
        lastGains = null
        eqBoostDb = 0f
    }
}

/** 两组滤波器交叉渐变，切预设或拖动滑杆时不在一个采样上突变系数。 */
private class SmoothedEqualizer(
    private val sampleRate: Int,
    private val channels: Int,
    private val rampFrames: Int
) {
    private var current = EqCascade(EQ_BAND_COUNT, channels)
    private var incoming = EqCascade(EQ_BAND_COUNT, channels)
    private var currentGains = FloatArray(EQ_BAND_COUNT)
    private var incomingGains = currentGains.copyOf()
    private var desiredGains = currentGains.copyOf()
    private var currentFlat = true
    private var incomingFlat = true
    private var remaining = 0

    init {
        current.design(EQ_CENTER_FREQS, currentGains, sampleRate)
        incoming.design(EQ_CENTER_FREQS, incomingGains, sampleRate)
    }

    val isNeutral: Boolean
        get() = remaining == 0 && currentFlat && desiredGains.all { it == 0f }

    fun setTarget(gains: FloatArray) {
        if (desiredGains.contentEquals(gains)) return
        desiredGains = gains.copyOf()
        // 按住/松开 A-B 时允许正在进行的渐变原路返回，而不是先走到终点再折返。
        if (remaining > 0 && desiredGains.contentEquals(currentGains)) {
            if (remaining == rampFrames) {
                remaining = 0
            } else {
                val old = current
                current = incoming
                incoming = old
                val oldGains = currentGains
                currentGains = incomingGains
                incomingGains = oldGains
                val oldFlat = currentFlat
                currentFlat = incomingFlat
                incomingFlat = oldFlat
                remaining = rampFrames - remaining
            }
        }
        if (remaining == 0) startIfNeeded()
    }

    private fun startIfNeeded() {
        if (currentGains.contentEquals(desiredGains)) return
        incomingGains = desiredGains.copyOf()
        incomingFlat = incomingGains.all { it == 0f }
        incoming.reset()
        incoming.design(EQ_CENTER_FREQS, incomingGains, sampleRate)
        remaining = rampFrames
    }

    fun processFrame(buf: FloatArray, index: Int) {
        if (remaining == 0) {
            if (!currentFlat) {
                for (ch in 0 until channels) buf[index + ch] = current.processSample(buf[index + ch], ch)
            }
            return
        }
        val mix = (rampFrames - remaining + 1).toFloat() / rampFrames
        for (ch in 0 until channels) {
            val input = buf[index + ch]
            val a = if (currentFlat) input else current.processSample(input, ch)
            val b = if (incomingFlat) input else incoming.processSample(input, ch)
            buf[index + ch] = a + (b - a) * mix
        }
        remaining--
        if (remaining == 0) {
            val old = current
            current = incoming
            incoming = old
            currentGains = incomingGains
            currentFlat = incomingFlat
            startIfNeeded() // 拖动期间只追最新目标，不堆积过期曲线
        }
    }

    fun reset() {
        current.reset()
        incoming.reset()
        currentGains = FloatArray(EQ_BAND_COUNT)
        incomingGains = currentGains.copyOf()
        desiredGains = currentGains.copyOf()
        currentFlat = true
        incomingFlat = true
        current.design(EQ_CENTER_FREQS, currentGains, sampleRate)
        incoming.design(EQ_CENTER_FREQS, incomingGains, sampleRate)
        remaining = 0
    }
}

/** 逐采样线性渐变，不依赖 Media3 一次交来多少帧。 */
private class LinearRamp(initial: Float) {
    private var value = initial
    private var target = initial
    private var step = 0f
    private var remaining = 0

    fun setTarget(next: Float, frames: Int) {
        if (next == target) return
        target = next
        remaining = frames.coerceAtLeast(1)
        step = (target - value) / remaining
    }

    fun next(): Float {
        if (remaining > 0) {
            value += step
            remaining--
            if (remaining == 0) value = target
        }
        return value
    }

    fun isSettledAt(expected: Float): Boolean = remaining == 0 && value == expected

    fun reset(initial: Float) {
        value = initial
        target = initial
        step = 0f
        remaining = 0
    }
}

/** 纯 Kotlin 效果链，供合并处理器与 JVM 信号测试共用。 */
@VisibleForTesting
internal class SoundFxDsp(private val sampleRate: Int, private val channels: Int) {
    private val bass = VirtualBass(sampleRate)
    private val imager = StereoImager(sampleRate)
    private val reverb = Freeverb(sampleRate, min(channels, 2))
    private val limiter = SoftLimiter(sampleRate)
    private val rampFrames = (sampleRate * 0.03f).toInt().coerceAtLeast(1)
    private val bassAmount = LinearRamp(0f)
    private val wet = LinearRamp(0f)
    private val decay = LinearRamp(0.5f)
    private val width = LinearRamp(1f)
    private val gain = LinearRamp(1f)
    private val limiterMix = LinearRamp(0f)
    private val limiterThreshold = LinearRamp(10f.pow(-1f / 20f))
    private var target = SoundFxParams.NEUTRAL
    private var bassRunning = false
    private var reverbRunning = false
    private var limiterRunning = false

    val isNeutral: Boolean
        get() = target.isNeutral && bassAmount.isSettledAt(0f) && wet.isSettledAt(0f) &&
            width.isSettledAt(1f) && gain.isSettledAt(1f) && limiterMix.isSettledAt(0f)

    fun setTarget(p: SoundFxParams) {
        target = p.clamp()
        bassAmount.setTarget(target.virtualBass, rampFrames)
        wet.setTarget(target.reverbWet, rampFrames)
        decay.setTarget(target.reverbDecay, rampFrames)
        width.setTarget(target.stereoWidth, rampFrames)
        gain.setTarget(10f.pow(target.preampDb / 20f), rampFrames)
        limiterMix.setTarget(if (target.limiter) 1f else 0f, rampFrames)
        limiterThreshold.setTarget(10f.pow(target.limiterThresholdDb / 20f), rampFrames)
    }

    fun process(buf: FloatArray, frames: Int) {
        var index = 0
        repeat(frames) {
            val g = gain.next()
            val bassValue = bassAmount.next()
            val widthValue = width.next()
            val wetValue = wet.next()
            val decayValue = decay.next()
            val limitMix = limiterMix.next()
            val threshold = limiterThreshold.next()
            for (ch in 0 until channels) buf[index + ch] *= g

            // 空间效果只处理单/双声道；多声道仍保留各声道 EQ 和峰值保护。
            if (channels <= 2) {
                if (bassValue > 0f) {
                    bass.processFrame(buf, index, channels, bassValue)
                    bassRunning = true
                } else if (bassRunning) {
                    bass.reset()
                    bassRunning = false
                }
                if (channels == 2) imager.processFrame(buf, index, widthValue)
                if (wetValue > 0f) {
                    reverb.processFrame(buf, index, channels, wetValue, decayValue)
                    reverbRunning = true
                } else if (reverbRunning) {
                    reverb.reset()
                    reverbRunning = false
                }
            }
            if (limitMix > 0f) {
                limiter.processFrame(buf, index, channels, threshold, limitMix)
                limiterRunning = true
            } else if (limiterRunning) {
                limiter.reset()
                limiterRunning = false
            }
            index += channels
        }
    }

    fun reset() {
        bass.reset()
        imager.reset()
        reverb.reset()
        limiter.reset()
        bassAmount.reset(0f)
        wet.reset(0f)
        decay.reset(0.5f)
        width.reset(1f)
        gain.reset(1f)
        limiterMix.reset(0f)
        limiterThreshold.reset(10f.pow(-1f / 20f))
        target = SoundFxParams.NEUTRAL
        bassRunning = false
        reverbRunning = false
        limiterRunning = false
    }
}

/**
 * 虚拟低音（missing fundamental + 动态跟踪偶/奇次谐波合成）。
 *
 * 手机与平板扬声器很难还原 100 Hz 以下的基频，普通入耳式耳机在低频下潜上也容易干瘪。
 * 这里通过提取超低频包络并生成缺失基频的**偶次（温暖厚度）+ 奇次（结实弹性）谐波族**，
 * 让听觉系统自然还原出深沉饱满的低音基石。
 *
 * 核心声学改进：
 * 1. **四级 140 Hz 低通 + 通带增益补偿**：保证 1 kHz 人声频段抑制超过 -68 dB（绝不污染人声），
 *    同时把 50~90 Hz 底鼓基频的滤波衰减补偿回满幅度，解决旧版四级串联后低频先缩水一半的问题。
 * 2. **动态跟踪软驱动（Level-Compensated Harmonic Exciter）**：
 *    纯多项式 `x²` / `x³` 在中小音量（如 `x = 0.15`）下会急剧退化到千分之几，导致平时几乎听不见效果；
 *    而硬除以包络又会在安静段落放大底噪。这里引入带噪声门限（-45 dBFS）的包络反比软增益，
 *    使生成的 2f/3f 谐波与基频保持恒定的黄金比例：轻敲底鼓有轻柔弹性，重砸底鼓有澎湃下潜，安静段落零底噪。
 * 3. **70 Hz 高通去直流 + 280 Hz 二阶谐波聚焦低通**：把谐波能量严格收束在 85~260 Hz 的胸腔共鸣甜区，
 *    谐波随阶数自然衰减（2f > 3f > 4f），绝不窜入中高频变成毛刺。
 */
@VisibleForTesting
internal class VirtualBass(sampleRate: Int) {
    private val lpA = 1f - exp(-2f * PI.toFloat() * 140f / sampleRate)
    private val hpB = exp(-2f * PI.toFloat() * 70f / sampleRate)
    private val outputLpA = 1f - exp(-2f * PI.toFloat() * 280f / sampleRate)
    private val envRelease = 1f - exp(-1f / (sampleRate * 0.025f).coerceAtLeast(1f))
    private var lp1 = 0f
    private var lp2 = 0f
    private var lp3 = 0f
    private var lp4 = 0f
    private var envelope = 0f
    private var hp = 0f
    private var prev = 0f
    private var outputLp1 = 0f
    private var outputLp2 = 0f

    fun process(buf: FloatArray, frames: Int, channels: Int, amount: Float) {
        if (amount <= 0f) return
        var i = 0
        repeat(frames) {
            processFrame(buf, i, channels, amount)
            i += channels
        }
    }

    fun processFrame(buf: FloatArray, i: Int, channels: Int, amount: Float) {
        val mono = if (channels == 2) (buf[i] + buf[i + 1]) * 0.5f else buf[i]
        lp1 += lpA * (mono - lp1)
        lp2 += lpA * (lp1 - lp2)
        lp3 += lpA * (lp2 - lp3)
        lp4 += lpA * (lp3 - lp4)

        // 补偿四级 RC 低通在 60~80 Hz 处的通带插损（约 1.32x），同时仍对 1 kHz 保持 >65 dB 隔离
        val x = lp4 * 1.32f
        val magnitude = abs(x)
        envelope = if (magnitude > envelope) {
            magnitude
        } else {
            envelope + (magnitude - envelope) * envRelease
        }

        // 只留谐波，严禁混入直流与原始基频（否则会改变 60 Hz 本身的幅度）：
        //   x² 的直流分量 = A²/2，减掉后纯出 2f 偶次谐波（温暖丰满度）；
        //   x³ 的基频分量 = 3A²/4·x，减掉后纯出 3f 奇次谐波（手机喇叭也能听见的结实弹性）。
        val env2 = envelope * envelope
        val even = x * x - 0.5f * env2
        val odd = x * x * x - 0.75f * env2 * x

        // 动态跟踪增益：带平滑门限（< -45 dBFS 自动归零），中小音量下适度托起二次/三次谐波比例
        val gate = (envelope / (envelope + 0.006f)).let { it * it }
        val evenScale = gate / (envelope + 0.22f)
        val oddScale = gate / (env2 + 0.14f)
        val harmonic = 0.11f * even * evenScale + 0.065f * odd * oddScale

        hp = hpB * (hp + harmonic - prev)
        prev = harmonic
        outputLp1 += outputLpA * (hp - outputLp1)
        outputLp2 += outputLpA * (outputLp1 - outputLp2)
        val add = outputLp2 * amount
        buf[i] += add
        if (channels == 2) buf[i + 1] += add
    }

    fun reset() {
        lp1 = 0f
        lp2 = 0f
        lp3 = 0f
        lp4 = 0f
        envelope = 0f
        hp = 0f
        prev = 0f
        outputLp1 = 0f
        outputLp2 = 0f
    }
}

/**
 * 双频段 M/S 立体声声像器。
 *
 * - 展宽（`width > 1`）时：一阶 210 Hz 分频锁死低频中心（50 Hz 鼓点与贝斯绝不往两边跑，
 *   避免耳机头中发空及手机/平板双扬声器空气声学反相抵消），同时对 >2.2 kHz 的高频空气泛音
 *   赋予更舒展的横向散射，中置人声（Mid）保持 100% 原始能量守恒。
 * - 收窄（`width < 1`，如「人声 · 贴耳」设 0.90）时：平稳收拢两侧伴奏，使中置人声自然贴近；
 *   `width == 0` 时完全合并为单声道。
 */
@VisibleForTesting
internal class StereoImager(sampleRate: Int) {
    private val lowpass = 1f - exp(-2f * PI.toFloat() * 210f / sampleRate)
    private val airLowpass = 1f - exp(-2f * PI.toFloat() * 2200f / sampleRate)
    private var lowSide = 0f
    private var midHighSide = 0f

    fun processFrame(buf: FloatArray, index: Int, width: Float) {
        val left = buf[index]
        val right = buf[index + 1]
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        lowSide += lowpass * (side - lowSide)
        val highSide = side - lowSide
        midHighSide += airLowpass * (highSide - midHighSide)
        val airSide = highSide - midHighSide
        val adjusted = if (width >= 1f) {
            val extra = width - 1f
            // 中高频按目标宽度展开，高频泛音额外补一点空气感但保持在自然区间
            side + extra * highSide + extra * 0.06f * airSide
        } else {
            side * width
        }
        buf[index] = mid + adjusted
        buf[index + 1] = mid - adjusted
    }

    fun reset() {
        lowSide = 0f
        midHighSide = 0f
    }
}

/**
 * 带早期横向反射（Early Reflections）的轻量房间/舞台混响；直达声保持 100%。
 *
 * 决定空间感"高级不高级、真不真"的四个要素：
 * - **22 ms 预延迟**：直达声和第一波空间反射严格分开，人声永远清晰贴在前面，绝不糊成澡堂回音；
 * - **双抽头早期横向反射（26 ms / 35 ms）**：真实 Livehouse 和影院的临场感首先来自左右侧墙的早期反射，
 *   加入早期反射后，不需要拖很长的尾音就能一耳朵听出立体舞台包裹感；
 * - **送入前高通 220 Hz / 低通 6.2 kHz**：低频不进混响（防浑浊轰头），极高频齿音不反复折射；
 * - **阻尼随衰减自适应**：余韵越长高频吸收越自然，尾音温润消散。
 */
@VisibleForTesting
internal class Freeverb(private val sampleRate: Int, channels: Int) {
    private val sendHighpass = exp(-2f * PI.toFloat() * 220f / sampleRate)
    private val sendLowpass = 1f - exp(-2f * PI.toFloat() * 6200f / sampleRate)
    private val hpState = FloatArray(channels)
    private val hpPrevInput = FloatArray(channels)
    private val lpState = FloatArray(channels)
    // 预延迟环扩充到 38 ms，主混响仍从 22 ms 抽头读取（保持 >20 ms 纯净预延迟窗口），
    // 同时在 26 ms 与 34 ms 处取左右交叉的早期横向反射
    private val preDelayTap = (sampleRate * 0.022f).toInt().coerceIn(1, sampleRate)
    private val earlyTap1 = (sampleRate * 0.026f).toInt().coerceIn(1, sampleRate)
    private val earlyTap2 = (sampleRate * 0.034f).toInt().coerceIn(1, sampleRate)
    private val ringSize = ( earlyTap2 + 8 ).coerceAtLeast(preDelayTap + 1)
    private val preDelay = Array(channels) { FloatArray(ringSize) }
    private var writeIndex = 0
    private val combs = Array(channels) { ch ->
        Array(COMB_TAPS.size) { k ->
            Comb(scale(COMB_TAPS[k] + if (ch == 1) STEREO_SPREAD else 0))
        }
    }
    private val allpass = Array(channels) { ch ->
        Array(ALLPASS_TAPS.size) { k ->
            Allpass(scale(ALLPASS_TAPS[k] + if (ch == 1) STEREO_SPREAD else 0))
        }
    }

    private fun scale(base: Int): Int =
        (base * sampleRate / 44100f).toInt().coerceAtLeast(8)

    fun process(buf: FloatArray, frames: Int, channels: Int, wet: Float, decay: Float) {
        var i = 0
        repeat(frames) {
            processFrame(buf, i, channels, wet, decay)
            i += channels
        }
    }

    fun processFrame(buf: FloatArray, i: Int, channels: Int, wet: Float, decay: Float) {
        val d = decay.coerceIn(0f, 1f)
        val feedback = 0.50f + d * 0.38f
        val damp = 0.24f + d * 0.30f
        val left = buf[i]
        val right = if (channels == 2) buf[i + 1] else left
        val idxMain = (writeIndex - preDelayTap + ringSize) % ringSize
        val idxE1 = (writeIndex - earlyTap1 + ringSize) % ringSize
        val idxE2 = (writeIndex - earlyTap2 + ringSize) % ringSize

        for (ch in 0 until channels) {
            val direct = if (ch == 0) left else right
            val other = if (ch == 0) right else left
            val input = direct * 0.82f + other * 0.18f
            hpState[ch] = sendHighpass * (hpState[ch] + input - hpPrevInput[ch])
            hpPrevInput[ch] = input
            lpState[ch] += sendLowpass * (hpState[ch] - lpState[ch])
            preDelay[ch][writeIndex] = lpState[ch]
        }

        for (ch in 0 until channels) {
            val direct = if (ch == 0) left else right
            val opp = if (channels == 2) 1 - ch else 0
            val send = preDelay[ch][idxMain]
            // 早期反射：本声道 26 ms + 对侧声道 34 ms 交叉反射，塑造立体房间轮廓
            val early = preDelay[ch][idxE1] * 0.22f - preDelay[opp][idxE2] * 0.16f
            var y = 0f
            for (k in combs[ch].indices) y += combs[ch][k].process(send, feedback, damp)
            y /= combs[ch].size
            for (k in allpass[ch].indices) y = allpass[ch][k].process(y)
            buf[i + ch] = direct + (early + y * 0.68f) * wet
        }
        writeIndex = if (++writeIndex >= ringSize) 0 else writeIndex
    }

    fun reset() {
        combs.forEach { ch -> ch.forEach { it.reset() } }
        allpass.forEach { ch -> ch.forEach { it.reset() } }
        preDelay.forEach { it.fill(0f) }
        writeIndex = 0
        hpState.fill(0f)
        hpPrevInput.fill(0f)
        lpState.fill(0f)
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f

        fun process(input: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = input + store * feedback
            idx = if (++idx >= buf.size) 0 else idx
            return out
        }

        fun reset() {
            buf.fill(0f)
            idx = 0
            store = 0f
        }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0

        fun process(input: Float): Float {
            val bufOut = buf[idx]
            val out = bufOut - input * 0.5f
            buf[idx] = input + bufOut * 0.5f
            idx = if (++idx >= buf.size) 0 else idx
            return out
        }

        fun reset() {
            buf.fill(0f)
            idx = 0
        }
    }

    private companion object {
        val COMB_TAPS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TAPS = intArrayOf(556, 441, 341, 225)
        const val STEREO_SPREAD = 23
    }
}

/**
 * 声道联动的采样峰值保护：只在接近上限时作用，不宣称真峰值或扬声器硬件保护。
 *
 * 三级处理，顺序不能换：
 * 1. **峰值包络**（上升立即跟随、50 ms 慢放）：旧版逐采样用瞬时峰值算增益，
 *    一个 60 Hz 周期里增益要"松-压"好几次，正弦被削成锯齿 —— 这就是低频
 *    "发毛"的真正来源，不是削顶。
 * 2. **增益包络**（2 ms 起控 / 150 ms 释放）：起控够快不漏瞬态，释放够慢不抽吸。
 *    稳态目标留在阈值下方 7%，天花板平时根本不参与工作。
 * 3. **圆滑天花板**：起控那 2 ms 里的残余过冲用双曲正切收口，保证输出永不越界，
 *    又不像硬砍那样一下子长出刺耳谐波。
 *
 * 天花板按**帧内最大声道**统一缩放，左右比例保持不变，声像不会跑。
 */
@VisibleForTesting
internal class SoftLimiter(sampleRate: Int) {
    private var gain = 1f
    private var envelope = 0f
    private val envelopeRelease =
        1f - exp(-1f / (sampleRate * 0.05f).coerceAtLeast(1f))
    private val attackCoef =
        1f - exp(-1f / (sampleRate * 0.002f).coerceAtLeast(1f))
    private val releaseCoef =
        1f - exp(-1f / (sampleRate * 0.15f).coerceAtLeast(1f))

    fun process(buf: FloatArray, frames: Int, channels: Int, thresholdDb: Float) {
        val th = 10f.pow(thresholdDb.coerceIn(-12f, 0f) / 20f)
        var i = 0
        repeat(frames) {
            processFrame(buf, i, channels, th, 1f)
            i += channels
        }
    }

    fun processFrame(buf: FloatArray, index: Int, channels: Int, threshold: Float, mix: Float) {
        var peak = 0f
        for (ch in 0 until channels) peak = max(peak, abs(buf[index + ch]))
        envelope = if (peak > envelope) peak else envelope + (peak - envelope) * envelopeRelease
        val target = threshold * TARGET
        val want = if (envelope > target) target / envelope else 1f
        gain = if (want < gain) {
            gain + (want - gain) * attackCoef
        } else {
            gain + (1f - gain) * releaseCoef
        }
        val scale = 1f + (gain - 1f) * mix.coerceIn(0f, 1f)
        var scaledPeak = 0f
        for (ch in 0 until channels) {
            buf[index + ch] *= scale
            scaledPeak = max(scaledPeak, abs(buf[index + ch]))
        }
        if (scaledPeak > threshold * KNEE) {
            val k = softCeil(scaledPeak, threshold) / scaledPeak
            for (ch in 0 until channels) buf[index + ch] *= k
        }
    }

    /** 阈值 95% 之前完全透明；之后用双曲正切圆滑收口，输出严格不超过 [th]。 */
    private fun softCeil(peak: Float, th: Float): Float {
        val knee = th * KNEE
        val room = th - knee
        return knee + room * kotlin.math.tanh((peak - knee) / room)
    }

    fun reset() {
        gain = 1f
        envelope = 0f
    }

    private companion object {
        const val KNEE = 0.95f

        /** 稳态目标比阈值低 7%（约 0.6 dB），留出天花板不参与工作的空间 */
        const val TARGET = 0.93f
    }
}
