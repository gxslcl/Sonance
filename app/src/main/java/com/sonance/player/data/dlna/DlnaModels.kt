package com.sonance.player.data.dlna

import androidx.compose.runtime.Immutable
import java.net.URI
import java.util.Locale

enum class DlnaDeviceRole {
    MEDIA_SERVER,
    MEDIA_RENDERER
}

@Immutable
data class DlnaService(
    val serviceType: String,
    val serviceId: String,
    val controlUrl: String,
    val eventSubUrl: String,
    val scpdUrl: String
)

@Immutable
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val baseUrl: String,
    val locationUrl: String,
    val deviceType: String,
    val roles: Set<DlnaDeviceRole>,
    val iconUrl: String? = null,
    val contentDirectoryService: DlnaService? = null,
    val avTransportService: DlnaService? = null,
    val renderingControlService: DlnaService? = null,
    val isManual: Boolean = false
) {
    val isMediaServer: Boolean get() = DlnaDeviceRole.MEDIA_SERVER in roles && contentDirectoryService != null
    val isMediaRenderer: Boolean get() = DlnaDeviceRole.MEDIA_RENDERER in roles && avTransportService != null
}

@Immutable
sealed interface DlnaBrowseEntry {
    val id: String
    val parentId: String
    val title: String
}

@Immutable
data class DlnaContainer(
    override val id: String,
    override val parentId: String,
    override val title: String,
    val childCount: Int? = null,
    val albumArtUri: String? = null
) : DlnaBrowseEntry

@Immutable
data class DlnaAudioItem(
    override val id: String,
    override val parentId: String,
    override val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val resourceUrl: String,
    val protocolInfo: String,
    val mimeType: String,
    val bitrateBps: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val nrAudioChannels: Int,
    val albumArtUri: String? = null
) : DlnaBrowseEntry

@Immutable
data class DlnaBrowseResult(
    val entries: List<DlnaBrowseEntry>,
    val numberReturned: Int,
    val totalMatches: Int
)

enum class DlnaTransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
    UNKNOWN;

    val isActivePlaying: Boolean get() = this == PLAYING || this == TRANSITIONING

    companion object {
        fun parse(raw: String?): DlnaTransportState = when (raw.orEmpty().trim().uppercase(Locale.ROOT)) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> PAUSED_PLAYBACK
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            "NO_MEDIA_PRESENT" -> NO_MEDIA_PRESENT
            else -> UNKNOWN
        }
    }
}

@Immutable
data class DlnaTransportInfo(
    val state: DlnaTransportState,
    val status: String
)

@Immutable
data class DlnaPositionInfo(
    val trackDurationMs: Long,
    val relTimeMs: Long,
    val absTimeMs: Long,
    val trackUri: String
)

data class SsdpDiscoveryResponse(
    val location: String,
    val usn: String,
    val st: String,
    val server: String
)

data class HttpByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = (endInclusive - start + 1L).coerceAtLeast(0L)
}

sealed interface HttpRangeParse {
    data object None : HttpRangeParse
    data class Valid(val range: HttpByteRange) : HttpRangeParse
    data object Unsatisfiable : HttpRangeParse
}

/**
 * 解析 SSDP `M-SEARCH` 单播回包或 `NOTIFY` 组播包头部。
 *
 * 非 SSDP 响应或缺少 `LOCATION` 头时返回 null。
 */
fun parseSsdpResponse(rawPacket: String): SsdpDiscoveryResponse? {
    val lines = rawPacket.lineSequence().map { it.trimEnd('\r') }.toList()
    val startLine = lines.firstOrNull()?.trim()?.uppercase(Locale.ROOT) ?: return null
    val isSsdp = startLine.startsWith("HTTP/1.1 200") ||
        startLine.startsWith("HTTP/1.0 200") ||
        startLine.startsWith("NOTIFY * HTTP/1.")
    if (!isSsdp) return null

    val headers = HashMap<String, String>(8)
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isBlank()) break
        val sep = line.indexOf(':')
        if (sep <= 0) continue
        val key = line.substring(0, sep).trim().uppercase(Locale.ROOT)
        val value = line.substring(sep + 1).trim()
        if (key.isNotEmpty() && key !in headers) {
            headers[key] = value
        }
    }

    val location = headers["LOCATION"]?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    } ?: return null

    // NOTIFY 包可能声明 `NTS: ssdp:byebye` 下线通告，忽略避免把离线设备扫进来
    if (headers["NTS"]?.equals("ssdp:byebye", ignoreCase = true) == true) return null

    return SsdpDiscoveryResponse(
        location = location,
        usn = headers["USN"].orEmpty(),
        st = headers["ST"] ?: headers["NT"].orEmpty(),
        server = headers["SERVER"].orEmpty()
    )
}

/**
 * 把设备描述/DIDL 里的相对控制路径或资源地址拼成完整 HTTP URL。
 */
fun resolveUpnpUrl(baseUrl: String, relativeOrAbsolute: String?): String? {
    val target = relativeOrAbsolute?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (target.startsWith("http://", ignoreCase = true) ||
        target.startsWith("https://", ignoreCase = true)
    ) {
        return target
    }
    return runCatching {
        URI(baseUrl.trim()).resolve(target).toString()
    }.getOrNull() ?: runCatching {
        val base = URI(baseUrl.trim())
        val origin = buildString {
            append(base.scheme)
            append("://")
            append(base.rawAuthority)
        }
        if (target.startsWith("/")) "$origin$target" else "$origin/$target"
    }.getOrNull()
}

/**
 * 解析 UPnP 时长字符串：`H:MM:SS`、`HH:MM:SS.sss`、`MM:SS` 或纯秒数。
 */
fun parseUpnpDuration(raw: String?): Long {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() && it != "-1" && it != "NOT_IMPLEMENTED" }
        ?: return 0L
    // 部分服务器携带帧后缀 `HH:MM:SS/frames` 或逗号小数
    val sanitized = clean.substringBefore('/').replace(',', '.')
    val parts = sanitized.split(':')
    return when (parts.size) {
        1 -> {
            val sec = parts[0].toDoubleOrNull() ?: return 0L
            (sec * 1000.0).toLong().coerceAtLeast(0L)
        }
        2 -> {
            val min = parts[0].toLongOrNull() ?: return 0L
            val sec = parts[1].toDoubleOrNull() ?: return 0L
            ((min * 60L) * 1000L + (sec * 1000.0).toLong()).coerceAtLeast(0L)
        }
        3 -> {
            val hour = parts[0].toLongOrNull() ?: return 0L
            val min = parts[1].toLongOrNull() ?: return 0L
            val sec = parts[2].toDoubleOrNull() ?: return 0L
            ((hour * 3600L + min * 60L) * 1000L + (sec * 1000.0).toLong()).coerceAtLeast(0L)
        }
        else -> 0L
    }
}

/**
 * 格式化毫秒为 UPnP `AVTransport:Seek` 要求的 `HH:MM:SS`。
 */
fun formatUpnpDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

/**
 * 归一化或根据扩展名猜测音频 MIME 类型。
 */
fun guessAudioMimeType(fileNameOrExt: String?, explicitMime: String? = null): String {
    val cleanedExplicit = explicitMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (!cleanedExplicit.isNullOrBlank() && cleanedExplicit != "*" && cleanedExplicit != "application/octet-stream") {
        if (cleanedExplicit == "audio/x-flac") return "audio/flac"
        if (cleanedExplicit == "audio/x-wav") return "audio/wav"
        if (cleanedExplicit == "audio/m4a" || cleanedExplicit == "audio/aac") return "audio/mp4"
        if (cleanedExplicit.startsWith("audio/") || cleanedExplicit == "application/ogg") return cleanedExplicit
    }
    val ext = fileNameOrExt
        ?.substringBefore('?')
        ?.substringAfterLast('/')
        ?.substringAfterLast('.', missingDelimiterValue = fileNameOrExt)
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4", "m4b" -> "audio/mp4"
        "alac" -> "audio/alac"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "ape" -> "audio/ape"
        "wma" -> "audio/x-ms-wma"
        "dsf", "dff" -> "audio/dsd"
        "aif", "aiff" -> "audio/aiff"
        else -> "audio/mpeg"
    }
}

/**
 * 解析 HTTP `Range: bytes=...` 请求头（供局域网投屏 Range 206 流媒体服务端使用）。
 */
fun parseHttpRange(rangeHeader: String?, contentLength: Long): HttpRangeParse {
    if (rangeHeader.isNullOrBlank()) return HttpRangeParse.None
    if (contentLength <= 0L) return HttpRangeParse.Unsatisfiable
    val trimmed = rangeHeader.trim()
    if (!trimmed.startsWith("bytes=", ignoreCase = true)) return HttpRangeParse.Unsatisfiable
    val spec = trimmed.substring(6).substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return HttpRangeParse.Unsatisfiable

    val startPart = spec.substring(0, dash).trim()
    val endPart = spec.substring(dash + 1).trim()
    return if (startPart.isEmpty()) {
        // 后缀范围 `bytes=-500`
        val suffix = endPart.toLongOrNull() ?: return HttpRangeParse.Unsatisfiable
        if (suffix <= 0L) return HttpRangeParse.Unsatisfiable
        val start = (contentLength - suffix).coerceAtLeast(0L)
        HttpRangeParse.Valid(HttpByteRange(start, contentLength - 1L))
    } else {
        val start = startPart.toLongOrNull() ?: return HttpRangeParse.Unsatisfiable
        if (start < 0L || start >= contentLength) return HttpRangeParse.Unsatisfiable
        val end = if (endPart.isEmpty()) {
            contentLength - 1L
        } else {
            val rawEnd = endPart.toLongOrNull() ?: return HttpRangeParse.Unsatisfiable
            if (rawEnd < start) return HttpRangeParse.Unsatisfiable
            rawEnd.coerceAtMost(contentLength - 1L)
        }
        HttpRangeParse.Valid(HttpByteRange(start, end))
    }
}
