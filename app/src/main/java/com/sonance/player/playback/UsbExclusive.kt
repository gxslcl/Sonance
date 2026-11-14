package com.sonance.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sonance.player.data.Prefs

/**
 * USB 独占输出（bit-perfect 直通），参考海贝音乐的实现思路。
 *
 * Android 14（API 34）起系统提供 [AudioManager.setPreferredMixerAttributes]：
 * 为「本应用的媒体流 + 指定 USB 输出设备」声明 [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]
 * 后，系统混音器对这条流让路 —— 不做重采样、不与其他应用的声音混音，
 * PCM 按原始采样率/位深直达 USB DAC。这正是海贝「USB 独占」的底层机制。
 *
 * 两个前提：
 * - 播放流的 AudioAttributes 必须与这里声明的一致（USAGE_MEDIA + CONTENT_TYPE_MUSIC，
 *   见 PlaybackService 里 ExoPlayer 的设定），否则系统匹配不到这条规则；
 * - 规则只在声明它的进程存活期间有效，所以播放服务每次启动都按偏好补一次
 *   （[syncWithPrefs]），设备拔出时自动收掉（[watchDevices]）。
 *
 * 注意：应用内的 EQ / 音效仍作用在 PCM 上（那是解码后、送 DAC 前的处理）；
 * 要完全原汁原味，把均衡器与音效关掉即可。
 */
object UsbExclusive {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceCallback: AudioDeviceCallback? = null
    private var appliedDeviceId = -1
    private var appliedSampleRate = 0
    private var appliedEncoding = AudioFormat.ENCODING_INVALID
    private var appliedChannels = 0

    @Volatile
    private var lastTrackSampleRate = 44_100

    @Volatile
    private var lastTrackEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT

    @Volatile
    private var lastTrackChannels = 2

    private fun streamAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun usbOutput(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /**
     * 设置开关入口。成功返回 null；失败返回原因（UI 拿去 toast），
     * 失败时不写偏好，开关保持关闭，不会出现「显示开了但实际没生效」。
     */
    fun setEnabled(context: Context, on: Boolean): String? {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!on) {
            clear(app)
            prefs.usbExclusiveEnabled = false
            return null
        }
        if (Build.VERSION.SDK_INT < 34) return "USB 独占需要 Android 14 及以上系统"
        val am = app.getSystemService(AudioManager::class.java)
            ?: return "无法访问系统音频服务"
        val device = usbOutput(am)
            ?: return "未检测到 USB 音频设备，请先连接 USB DAC / 耳机再开启"
        val err = apply(am, device)
        if (err != null) return err
        prefs.usbExclusiveEnabled = true
        watchDevices(app)
        return null
    }

    /** 播放服务启动时按偏好补一次：进程重建后系统侧的规则已经没了。 */
    fun syncWithPrefs(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.usbExclusiveEnabled) return
        if (Build.VERSION.SDK_INT < 34) {
            prefs.usbExclusiveEnabled = false
            return
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val device = usbOutput(am)
        if (device == null || apply(am, device) != null) {
            // 设备没接 / 声明失败：把偏好收掉，避免界面显示「已开启」而实际走系统混音
            prefs.usbExclusiveEnabled = false
            return
        }
        watchDevices(app)
    }

    /**
     * 当正在播放的音轨格式变化时调用：按设备支持的位深与采样率动态重协商 USB 独占属性
     * （例如从 44.1kHz/16bit 切到 96kHz/24bit 时，不再被锁死在 44.1k/16bit 重采样）。
     */
    fun syncTrackFormat(context: Context, sampleRateHz: Int, pcmEncoding: Int, channelCount: Int) {
        if (sampleRateHz <= 0) return
        lastTrackSampleRate = sampleRateHz
        lastTrackEncoding = pcmEncoding
        lastTrackChannels = channelCount.coerceIn(1, 2)
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 34 || !Prefs(app).usbExclusiveEnabled) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val device = usbOutput(am) ?: return
        apply(am, device)
    }

    private fun toAndroidEncoding(pcmEncoding: Int): Int = when (pcmEncoding) {
        androidx.media3.common.C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        androidx.media3.common.C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
        androidx.media3.common.C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        else -> AudioFormat.ENCODING_PCM_16BIT
    }

    private fun encodingRank(enc: Int): Int = when (enc) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_32BIT -> 3
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
        AudioFormat.ENCODING_PCM_16BIT -> 1
        else -> 0
    }

    private fun apply(am: AudioManager, device: AudioDeviceInfo): String? {
        if (Build.VERSION.SDK_INT < 34) return "USB 独占需要 Android 14 及以上系统"
        return runCatching {
            val desiredRate = lastTrackSampleRate.coerceAtLeast(8000)
            val desiredEnc = toAndroidEncoding(lastTrackEncoding)
            val desiredChannelMask = if (lastTrackChannels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val supportedBitPerfect = runCatching {
                am.getSupportedMixerAttributes(device)
                    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            }.getOrDefault(emptyList())

            val bestSupported = supportedBitPerfect.maxByOrNull { attr ->
                val f = attr.format
                var score = 0
                score += when {
                    f.sampleRate == desiredRate -> 10_000
                    f.sampleRate == desiredRate * 2 -> 3_000
                    f.sampleRate == 48_000 || f.sampleRate == 44_100 -> 1_000
                    f.sampleRate > desiredRate -> 500
                    else -> 0
                }
                score += if (f.encoding == desiredEnc) 500 else encodingRank(f.encoding) * 80
                if (f.channelMask == desiredChannelMask) score += 200
                score
            }

            val chosenFormat = if (bestSupported != null) {
                val f = bestSupported.format
                val targetRate = if (f.sampleRate == AudioFormat.SAMPLE_RATE_UNSPECIFIED) desiredRate else f.sampleRate
                val targetEnc = if (f.encoding == AudioFormat.ENCODING_INVALID) desiredEnc else f.encoding
                val targetMask = if (f.channelMask == AudioFormat.CHANNEL_INVALID) desiredChannelMask else f.channelMask
                if (device.id == appliedDeviceId &&
                    targetRate == appliedSampleRate &&
                    targetEnc == appliedEncoding &&
                    lastTrackChannels == appliedChannels
                ) {
                    return@runCatching
                }
                AudioFormat.Builder()
                    .setEncoding(targetEnc)
                    .setSampleRate(targetRate)
                    .setChannelMask(targetMask)
                    .build()
            } else {
                val rates = device.sampleRates
                val rate = when {
                    rates.contains(desiredRate) -> desiredRate
                    rates.contains(44100) -> 44100
                    rates.contains(48000) -> 48000
                    rates.isNotEmpty() -> rates[0]
                    else -> desiredRate
                }
                val encodings = device.encodings
                val enc = when {
                    encodings.contains(desiredEnc) -> desiredEnc
                    encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    encodings.contains(AudioFormat.ENCODING_PCM_32BIT) -> AudioFormat.ENCODING_PCM_32BIT
                    else -> AudioFormat.ENCODING_PCM_16BIT
                }
                if (device.id == appliedDeviceId &&
                    rate == appliedSampleRate &&
                    enc == appliedEncoding &&
                    lastTrackChannels == appliedChannels
                ) {
                    return@runCatching
                }
                AudioFormat.Builder()
                    .setEncoding(enc)
                    .setSampleRate(rate)
                    .setChannelMask(desiredChannelMask)
                    .build()
            }

            val mixer = AudioMixerAttributes.Builder(chosenFormat)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
            am.setPreferredMixerAttributes(streamAttributes(), device, mixer)
            appliedDeviceId = device.id
            appliedSampleRate = chosenFormat.sampleRate
            appliedEncoding = chosenFormat.encoding
            appliedChannels = lastTrackChannels
        }.exceptionOrNull()?.let { "USB 独占开启失败：${it.message ?: "设备不支持"}" }
    }

    private fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == appliedDeviceId } ?: usbOutput(am)
            if (device != null) am.clearPreferredMixerAttributes(streamAttributes(), device)
        }
        appliedDeviceId = -1
        appliedSampleRate = 0
        appliedEncoding = AudioFormat.ENCODING_INVALID
        appliedChannels = 0
    }

    /** USB 设备拔出后系统规则随之失效：把偏好一起收掉，保持界面与实际一致。 */
    private fun watchDevices(context: Context) {
        if (deviceCallback != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (removedDevices.none { it.id == appliedDeviceId }) return
                appliedDeviceId = -1
                appliedSampleRate = 0
                appliedEncoding = AudioFormat.ENCODING_INVALID
                appliedChannels = 0
                Prefs(context).usbExclusiveEnabled = false
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, mainHandler)
    }
}