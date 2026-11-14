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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 合并式音效处理器。
 *
 * EQ 和虚拟低音 / 双耳交叉馈送 / 外放保护高通 / 混响 / 声场 / 峰值保护
 * 在同一条浮点链路中完成，支持 16 / 24 / 32 位整型与 32 位浮点 PCM（16 位输出启用 TPDF 抖动）。
 *
 * 算法全部是公开数学原理（64 位 RBJ biquad、BS2B 双耳串扰、互质抽头 Freeverb、
 * 零直流动态包络虚拟低音、低音锁相 M/S 声场、峰值包络软限幅），零第三方库或外部 IR 文件。
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
    private var intScratch = IntArray(0)
    private var ditherSeed = 0x517CC1B7

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        if (!PcmCodec.isSupportedEncoding(enc) || ch !in 1..32 ||
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
        if (encoding == C.ENCODING_PCM_32BIT && intScratch.size < samples) intScratch = IntArray(samples)

        PcmCodec.readToFloat(inputBuffer, floatScratch, samples, encoding)
        engine.process(floatScratch, frames)

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

    /** 切歌 / seek 必须清掉混响延迟线，否则上一首的尾巴会拖进下一首 */
    override fun onFlush() {
        dsp?.reset()
        ditherSeed = 0x517CC1B7
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
        val effectBudget = effectPreampBudgetDb(params, channels)
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
    private val speakerHpf = DoubleBiquad(min(channels, 2))
    private val crossfeed = BinauralCrossfeed(sampleRate)
    private val imager = StereoImager(sampleRate)
    private val reverb = Freeverb(sampleRate, min(channels, 2))
    private val limiter = SoftLimiter(sampleRate)
    private val rampFrames = (sampleRate * 0.03f).toInt().coerceAtLeast(1)
    private val bassAmount = LinearRamp(0f)
    private val speakerHpfMix = LinearRamp(0f)
    private val crossfeedMix = LinearRamp(0f)
    private val wet = LinearRamp(0f)
    private val decay = LinearRamp(0.5f)
    private val width = LinearRamp(1f)
    private val gain = LinearRamp(1f)
    private val limiterMix = LinearRamp(0f)
    private val limiterThreshold = LinearRamp(10f.pow(-1f / 20f))
    private var target = SoundFxParams.NEUTRAL
    private var appliedHpfHz = -1f
    private var bassRunning = false
    private var hpfRunning = false
    private var crossfeedRunning = false
    private var reverbRunning = false
    private var limiterRunning = false

    val isNeutral: Boolean
        get() = target.isNeutral &&
            bassAmount.isSettledAt(0f) &&
            speakerHpfMix.isSettledAt(0f) &&
            crossfeedMix.isSettledAt(0f) &&
            wet.isSettledAt(0f) &&
            width.isSettledAt(1f) &&
            gain.isSettledAt(1f) &&
            limiterMix.isSettledAt(0f)

    fun setTarget(p: SoundFxParams) {
        target = p.clamp()
        bassAmount.setTarget(target.virtualBass, rampFrames)
        val hpfHz = target.speakerHpfHz
        speakerHpfMix.setTarget(if (hpfHz >= 20f) 1f else 0f, rampFrames)
        if (hpfHz >= 20f && hpfHz != appliedHpfHz) {
            appliedHpfHz = hpfHz
            speakerHpf.setCoeffs(BiquadDesign.highPass(hpfHz.toDouble(), sampleRate))
        }
        crossfeedMix.setTarget(target.crossfeed, rampFrames)
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
            val hpfMix = speakerHpfMix.next()
            val cfValue = crossfeedMix.next()
            val widthValue = width.next()
            val wetValue = wet.next()
            val decayValue = decay.next()
            val limitMix = limiterMix.next()
            val threshold = limiterThreshold.next()
            for (ch in 0 until channels) buf[index + ch] *= g

            // 空间与谐波效果只处理单/双声道；多声道仍保留 EQ 与峰值保护。
            if (channels <= 2) {
                // 关键顺序：先在高通保护之前抽取完整原始低频基波去合成 2f/3f 谐波，
                // 再由 SpeakerHpf 切除小喇叭打底易破音的超低频，最后把高通以上频率区间内的
                // 谐波温和叠回主通道 —— 外放既防破音又不丢低音轮廓。
                val preHpfMono = if (channels == 2) (buf[index] + buf[index + 1]) * 0.5f else buf[index]
                if (hpfMix > 0f) {
                    for (ch in 0 until channels) {
                        val dry = buf[index + ch]
                        val filtered = speakerHpf.processSample(dry.toDouble(), ch).toFloat()
                        buf[index + ch] = dry + (filtered - dry) * hpfMix
                    }
                    hpfRunning = true
                } else if (hpfRunning) {
                    speakerHpf.reset()
                    appliedHpfHz = -1f
                    hpfRunning = false
                }
                if (bassValue > 0f) {
                    val add = bass.processMonoSample(preHpfMono, bassValue)
                    buf[index] += add
                    if (channels == 2) buf[index + 1] += add
                    bassRunning = true
                } else if (bassRunning) {
                    bass.reset()
                    bassRunning = false
                }
                if (channels == 2) {
                    if (cfValue > 0f) {
                        crossfeed.processFrame(buf, index, cfValue)
                        crossfeedRunning = true
                    } else if (crossfeedRunning) {
                        crossfeed.reset()
                        crossfeedRunning = false
                    }
                    imager.processFrame(buf, index, widthValue)
                }
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
        speakerHpf.reset()
        crossfeed.reset()
        imager.reset()
        reverb.reset()
        limiter.reset()
        bassAmount.reset(0f)
        speakerHpfMix.reset(0f)
        crossfeedMix.reset(0f)
        wet.reset(0f)
        decay.reset(0.5f)
        width.reset(1f)
        gain.reset(1f)
        limiterMix.reset(0f)
        limiterThreshold.reset(10f.pow(-1f / 20f))
        target = SoundFxParams.NEUTRAL
        appliedHpfHz = -1f
        bassRunning = false
        hpfRunning = false
        crossfeedRunning = false
        reverbRunning = false
        limiterRunning = false
    }
}

/**
 * 虚拟低音（missing fundamental + 零直流漂移动态包络谐波合成）。
 *
 * 在消除多项式负向直流抽吸的前提下兼顾饱满下潜与小单元投影力：
 * 1. **64 位二阶 Butterworth 138 Hz 低通提取**：通带平坦（<100 Hz 零无谓衰减），
 *    同时把 1 kHz 人声干净隔离在低音激励链路之外；
 * 2. **归一化全波偶次 + Chebyshev 三次谐波合成**：按瞬时包络归一化基波，再用去均值的全波整流
 *    `abs(xn) - 2/π` 生成丰润 2f 温暖谐波、用 Chebyshev T3 `(4*xn² - 3)*xn` 生成 3f 弹性锤感（零基波残留），
 *    放宽峰值压缩系数 `0.18` 让鼓点瞬态力度直接透出；
 * 3. **64 位 74 Hz 二阶高通 + 295 Hz 二阶低通**：既严格切断直流与 50 Hz 以下无用共振，又完整保留
 *    75~115 Hz 决定"厚实感"的关键二次泛音，高通以上直到 295 Hz 的 3 次谐波也能帮外放清脆立住低音轮廓。
 */
@VisibleForTesting
internal class VirtualBass(sampleRate: Int) {
    private val inputLpf = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.lowPass(138.0, sampleRate)) }
    private val outputHpf = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.highPass(74.0, sampleRate)) }
    private val outputLpf = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.lowPass(295.0, sampleRate)) }
    private val envRelease = 1f - exp(-1f / (sampleRate * 0.025f).coerceAtLeast(1f))
    private var envelope = 0f

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
        val add = processMonoSample(mono, amount)
        buf[i] += add
        if (channels == 2) buf[i + 1] += add
    }

    internal fun processMonoSample(mono: Float, amount: Float): Float {
        val x = inputLpf.processSample(mono.toDouble(), 0).toFloat()
        val magnitude = abs(x)
        envelope = if (magnitude > envelope) {
            magnitude
        } else {
            envelope + (magnitude - envelope) * envRelease
        }
        val gate = (envelope / (envelope + 0.005f)).let { it * it }
        val norm = envelope.coerceAtLeast(1e-4f)
        val xn = (x / norm).coerceIn(-1.5f, 1.5f)
        val even = (abs(xn) - TWO_OVER_PI) * 0.32f
        val odd = ((4f * xn * xn - 3f) * xn) * 0.075f
        val envScale = (envelope / (1f + envelope * 0.22f)) * gate
        val harmonic = (even + odd) * envScale
        val cleaned = outputLpf.processSample(outputHpf.processSample(harmonic.toDouble(), 0), 0)
        return cleaned.toFloat() * amount
    }

    fun reset() {
        inputLpf.reset()
        outputHpf.reset()
        outputLpf.reset()
        envelope = 0f
    }

    private companion object {
        const val TWO_OVER_PI = 0.63661977f
    }
}

/**
 * 双耳交叉馈送（BS2B 物理头影串扰建模）：
 * 在模拟扬声器 30° 摆位的前提下，把对侧声道经过约 280 微秒声学时延与 700 Hz 头影低通后
 * 轻度馈入同侧耳朵，减轻耳机直灌导致的「头中效应」，同时保证居中单声道与高频空气感无损。
 */
@VisibleForTesting
internal class BinauralCrossfeed(sampleRate: Int) {
    private val delaySamples = (sampleRate * 0.00028f).roundToInt().coerceIn(1, 64)
    private val lpAlpha = 1f - exp(-2f * PI.toFloat() * 700f / sampleRate.coerceAtLeast(1))
    private val ringL = FloatArray(delaySamples)
    private val ringR = FloatArray(delaySamples)
    private var ringIdx = 0
    private var lpL = 0f
    private var lpR = 0f

    fun processFrame(buf: FloatArray, index: Int, amount: Float) {
        val dryL = buf[index]
        val dryR = buf[index + 1]
        val delayedL = ringL[ringIdx]
        val delayedR = ringR[ringIdx]
        ringL[ringIdx] = dryL
        ringR[ringIdx] = dryR
        ringIdx = if (ringIdx + 1 >= delaySamples) 0 else ringIdx + 1

        lpL += lpAlpha * (delayedL - lpL)
        lpR += lpAlpha * (delayedR - lpR)

        // 对正中人声（lpL == lpR）整体仅 +0.05*amount 极微变化，高频 (>1.5 kHz) 保持全透传
        val feed = 0.50f * amount.coerceIn(0f, 1f)
        buf[index] = dryL - 0.45f * feed * lpL + 0.55f * feed * lpR
        buf[index + 1] = dryR - 0.45f * feed * lpR + 0.55f * feed * lpL
    }

    fun reset() {
        ringL.fill(0f)
        ringR.fill(0f)
        ringIdx = 0
        lpL = 0f
        lpR = 0f
    }
}

/**
 * 双频段 M/S 立体声声像器。
 *
 * - 展宽（`width > 1`）时：采用二阶 Butterworth 210 Hz 低通锁定低频重心（50 Hz 鼓点与贝斯
 *   不再向两侧泄露而失去冲击力），高频泛音按适度比例舒展，中置人声能量保持 100% 守恒；
 * - 收窄（`width < 1`）时：线性收拢侧翼声像，`width == 0` 时完全合并为单声道。
 */
@VisibleForTesting
internal class StereoImager(sampleRate: Int) {
    private val sideLowpass = DoubleBiquad(1).apply { setCoeffs(BiquadDesign.lowPass(210.0, sampleRate)) }
    private val airLowpass = 1f - exp(-2f * PI.toFloat() * 2200f / sampleRate.coerceAtLeast(1))
    private var midHighSide = 0f

    fun processFrame(buf: FloatArray, index: Int, width: Float) {
        val left = buf[index]
        val right = buf[index + 1]
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val lowSide = sideLowpass.processSample(side.toDouble(), 0).toFloat()
        val highSide = side - lowSide
        midHighSide += airLowpass * (highSide - midHighSide)
        val airSide = highSide - midHighSide
        val adjusted = if (width >= 1f) {
            val extra = width - 1f
            val expanded = side + extra * highSide + extra * 0.06f * airSide
            // 仅在极大展宽突发过冲时柔化侧翼防削波，正常幅值保持全线性
            if (abs(expanded) > 0.85f) {
                0.85f * (expanded / abs(expanded)) + 0.25f * tanh((expanded - 0.85f * (expanded / abs(expanded))) / 0.25f)
            } else {
                expanded
            }
        } else {
            side * width
        }
        buf[index] = mid + adjusted
        buf[index + 1] = mid - adjusted
    }

    fun reset() {
        sideLowpass.reset()
        midHighSide = 0f
    }
}

/**
 * 互质抽头早期反射 + 交叉立体声尾韵房间混响；直达声保持 100%。
 *
 * 改进点：
 * - 保持 >20 ms 纯净预延迟窗口（主混响抽头 22 ms），人声永远清晰居中不糊团；
 * - 缩放各采样率后自动规避 3/5/7 倍公因子（互质错开），消除 48k/96k 下的周期性金属颤音；
 * - 尾声左右声道 86%/14% 轻微立体声交叉融合，空间包裹度更浑然；
 * - 阻尼系数随采样率归一化，高采样率歌曲混响尾音不再偏闷。
 */
@VisibleForTesting
internal class Freeverb(private val sampleRate: Int, channels: Int) {
    private val sendHighpass = exp(-2f * PI.toFloat() * 220f / sampleRate)
    private val sendLowpass = 1f - exp(-2f * PI.toFloat() * 6200f / sampleRate)
    private val dampRateCompensation = (44100f / sampleRate.coerceAtLeast(8000)).coerceIn(0.25f, 2f)
    private val hpState = FloatArray(channels)
    private val hpPrevInput = FloatArray(channels)
    private val lpState = FloatArray(channels)
    private val tailScratch = FloatArray(channels)
    private val preDelayTap = (sampleRate * 0.022f).toInt().coerceIn(1, sampleRate)
    private val earlyTap1 = (sampleRate * 0.026f).toInt().coerceIn(1, sampleRate)
    private val earlyTap2 = (sampleRate * 0.034f).toInt().coerceIn(1, sampleRate)
    private val ringSize = (earlyTap2 + 8).coerceAtLeast(preDelayTap + 1)
    private val preDelay = Array(channels) { FloatArray(ringSize) }
    private var writeIndex = 0
    private val combs = Array(channels) { ch ->
        Array(COMB_TAPS.size) { k ->
            Comb(scaleCoprime(COMB_TAPS[k] + if (ch == 1) STEREO_SPREAD else 0, sampleRate))
        }
    }
    private val allpass = Array(channels) { ch ->
        Array(ALLPASS_TAPS.size) { k ->
            Allpass(scaleCoprime(ALLPASS_TAPS[k] + if (ch == 1) STEREO_SPREAD else 0, sampleRate))
        }
    }

    private fun scaleCoprime(base: Int, sr: Int): Int {
        var n = (base * sr / 44100f).toInt().coerceAtLeast(11)
        if (n % 2 == 0) n += 1
        while (n % 3 == 0 || n % 5 == 0 || n % 7 == 0) n += 2
        return n
    }

    fun process(buf: FloatArray, frames: Int, channels: Int, wet: Float, decay: Float) {
        var i = 0
        repeat(frames) {
            processFrame(buf, i, channels, wet, decay)
            i += channels
        }
    }

    fun processFrame(buf: FloatArray, i: Int, channels: Int, wet: Float, decay: Float) {
        val d = decay.coerceIn(0f, 1f)
        val baseFeedback = 0.50f + d * 0.38f
        val feedback = baseFeedback.pow(dampRateCompensation)
        val rawDamp = (0.22f + d * 0.34f).coerceIn(0.05f, 0.85f)
        val damp = 1f - (1f - rawDamp).pow(dampRateCompensation)
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
            val send = preDelay[ch][idxMain]
            var y = 0f
            for (k in combs[ch].indices) y += combs[ch][k].process(send, feedback, damp)
            y *= 0.095f
            for (k in allpass[ch].indices) y = allpass[ch][k].process(y)
            tailScratch[ch] = y
        }

        for (ch in 0 until channels) {
            val direct = if (ch == 0) left else right
            val opp = if (channels == 2) 1 - ch else 0
            val early = preDelay[ch][idxE1] * 0.24f - preDelay[opp][idxE2] * 0.16f
            val tail = if (channels == 2) {
                tailScratch[ch] * 0.86f + tailScratch[opp] * 0.14f
            } else {
                tailScratch[0]
            }
            buf[i + ch] = direct + (early + tail) * wet
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
        tailScratch.fill(0f)
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
 * 声道联动的采样峰值保护：零额外采样延迟、超阈值不抽吸，同时留出自然鼓点瞬态冲击力。
 *
 * 1. **1.4 ms 音乐性起控 + 95 ms 快速释放**：让底鼓与军鼓最前面的 1 ms 瞬态锐度透出，
 *    持续大动态仍平滑介入不泵音（瞬态越界由 [softCeil] 同步圆滑兜底，严格不破音）；
 * 2. **正确追踪目标增益 `want`**：释放分支平滑逼近 `want`，杜绝高电平调幅颤振；
 * 3. **圆滑天花板按 `mix` 同步交叉渐入渐出**，开关瞬间零咔哒。
 */
@VisibleForTesting
internal class SoftLimiter(sampleRate: Int) {
    private var gain = 1f
    private var envelope = 0f
    private val envelopeRelease =
        1f - exp(-1f / (sampleRate * 0.035f).coerceAtLeast(1f))
    private val attackCoef =
        1f - exp(-1f / (sampleRate * 0.0014f).coerceAtLeast(1f))
    private val releaseCoef =
        1f - exp(-1f / (sampleRate * 0.095f).coerceAtLeast(1f))

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
            gain + (want - gain) * releaseCoef
        }
        val m = mix.coerceIn(0f, 1f)
        val scale = 1f + (gain - 1f) * m
        var scaledPeak = 0f
        for (ch in 0 until channels) {
            buf[index + ch] *= scale
            scaledPeak = max(scaledPeak, abs(buf[index + ch]))
        }
        if (scaledPeak > threshold * KNEE && m > 0f) {
            val k = softCeil(scaledPeak, threshold) / scaledPeak
            val blendK = 1f + (k - 1f) * m
            for (ch in 0 until channels) buf[index + ch] *= blendK
        }
    }

    /** 阈值 95% 之前完全透明；之后用双曲正切圆滑收口，输出严格不超过 [th]。 */
    private fun softCeil(peak: Float, th: Float): Float {
        val knee = th * KNEE
        val room = th - knee
        return knee + room * tanh((peak - knee) / room)
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
