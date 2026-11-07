package com.sonance.player.sync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * 校准用脉冲播放器：每 600ms 一声 1kHz 短音，总长 19.2 秒循环。
 *
 * 关键点：整段音频一次性写进 MODE_STATIC 的 AudioTrack，只在「预定时刻」调 play()，
 * 这样两台设备相对相位只取决于调度时刻，不会被逐声播放的 start/stop 抖动污染。
 * 用户拖动滑块时按 600ms 网格重排下一段，不破坏节拍相位。
 */
class PulseCalibrator(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val sampleRate = 48_000
    private val buffer: ShortArray by lazy { buildBuffer() }

    private var track: AudioTrack? = null
    private var running = false
    private var generation = 0

    fun startAt(localStartNanos: Long) {
        stopInternal()
        running = true
        val gen = generation
        val delayMs = (GroupProtocol.nanosToMs(localStartNanos - GroupProtocol.nowNanos()))
            .coerceAtLeast(0L)
        handler.postDelayed({ if (running && gen == generation) playLoop(gen) }, delayMs)
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        generation += 1
        handler.removeCallbacksAndMessages(null)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    private fun playLoop(gen: Int) {
        if (!running || gen != generation) return
        val t = track ?: buildTrack().also { track = it }
        runCatching { t.stop() }
        runCatching { t.reloadStaticData() }
        runCatching { t.play() }
        handler.postDelayed(
            { if (running && gen == generation) playLoop(gen) },
            GroupProtocol.CAL_TOTAL_MS
        )
    }

    private fun buildTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(buffer, 0, buffer.size)
        return t
    }

    private fun buildBuffer(): ShortArray {
        val total = (sampleRate.toLong() * GroupProtocol.CAL_TOTAL_MS / 1000L).toInt()
        val out = ShortArray(total)
        val beatLen = sampleRate * GroupProtocol.CAL_BEAT_LEN_MS / 1000
        val fade = sampleRate * 5 / 1000
        val beatSamples = (sampleRate.toLong() * GroupProtocol.CAL_BEAT_MS / 1000L).toInt()
        var start = 0
        var beatIndex = 0
        while (start + beatLen <= total) {
            for (i in 0 until beatLen) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > beatLen - fade -> (beatLen - i).toDouble() / fade
                    else -> 1.0
                }
                val v = sin(2.0 * PI * 1000.0 * i / sampleRate) * env
                out[start + i] = (v * 0.55 * Short.MAX_VALUE).toInt().toShort()
            }
            beatIndex += 1
            start += beatSamples
        }
        return out
    }

    companion object {
        /** 输出路由 → 默认输出延迟估计（毫秒）。这些是经验值，最终以校准结果为准 */
        fun defaultLatencyFor(route: String): Long = when (route) {
            "bt" -> 200L
            "wired" -> 80L
            else -> 45L
        }

        fun currentRoute(context: Context): String {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "speaker"
            @Suppress("DEPRECATION")
            return when {
                am.isBluetoothA2dpOn -> "bt"
                am.isWiredHeadsetOn -> "wired"
                else -> "speaker"
            }
        }
    }
}
