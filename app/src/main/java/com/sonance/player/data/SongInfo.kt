package com.sonance.player.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「查看歌曲信息」面板的数据源。
 *
 * 界面只负责展示；这里把「能从 Song 直接拿到的」和「要问系统解码器的」分开，
 * 解析与格式化都是纯函数，可在 JVM 单测里跑。读解码器是 IO 操作，调用方需切到后台线程。
 */
object SongInfo {

    data class AudioDetails(
        val format: String = "",
        val sampleRateHz: Int = 0,
        val bitDepth: Int = 0,
        val bitrateKbps: Int = 0,
        val channels: Int = 0
    ) {
        val sampleRateText: String get() = if (sampleRateHz > 0) "$sampleRateHz Hz" else "未知"
        val bitDepthText: String get() = if (bitDepth > 0) "$bitDepth bit" else "未知"
        val bitrateText: String get() = if (bitrateKbps > 0) "$bitrateKbps kbps" else "未知"
        val channelsText: String get() = when (channels) {
            1 -> "单声道"
            2 -> "立体声"
            6 -> "5.1 声道"
            8 -> "7.1 声道"
            in 3..Int.MAX_VALUE -> "$channels 声道"
            else -> "未知"
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "未知"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    fun formatDate(epochSec: Long): String {
        if (epochSec <= 0L) return "未知"
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000L))
        }.getOrDefault("未知")
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "未知"
        val total = ms / 1000
        return "%02d:%02d".format(Locale.US, total / 60, total % 60)
    }

    fun sourceLabel(song: Song): String = when {
        song.isLocal -> "本机曲库"
        song.isDlna -> "局域网（DLNA）"
        song.source == "lx" -> "洛雪音源"
        song.source == "netease" -> "网易云在线"
        song.source.isBlank() -> "未知"
        else -> song.source
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return "未知"
        return name.substring(dot + 1).uppercase(Locale.US)
    }

    /**
     * 读系统解码器能给出的音频参数。用 [MediaExtractor] 的 MediaFormat，
     * 比 MetadataRetriever 的字符串键稳定（后者没有公开「位深 / 采样率」常量）。
     * 失败时返回全空，界面显示「未知」，不报错打扰用户。
     */
    fun readAudioDetails(context: Context, uri: Uri): AudioDetails = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, emptyMap())
            if (extractor.trackCount == 0) return@runCatching AudioDetails()
            val fmt = extractor.getTrackFormat(0)
            fun int(key: String): Int = if (fmt.containsKey(key)) runCatching { fmt.getInteger(key) }.getOrDefault(0) else 0
            val pcm = int(MediaFormat.KEY_PCM_ENCODING)
            val depth = when (pcm) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                21 -> 24 // ENCODING_PCM_24BIT（API 31 才公开常量，按数值兼容低版本）
                AudioFormat.ENCODING_PCM_32BIT -> 32
                AudioFormat.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }
            AudioDetails(
                format = if (fmt.containsKey(MediaFormat.KEY_MIME)) fmt.getString(MediaFormat.KEY_MIME).orEmpty() else "",
                sampleRateHz = int(MediaFormat.KEY_SAMPLE_RATE),
                bitDepth = depth,
                bitrateKbps = int(MediaFormat.KEY_BIT_RATE) / 1000,
                channels = int(MediaFormat.KEY_CHANNEL_COUNT)
            )
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrDefault(AudioDetails())
}
