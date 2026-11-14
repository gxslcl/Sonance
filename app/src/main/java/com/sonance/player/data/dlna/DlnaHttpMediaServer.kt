package com.sonance.player.data.dlna

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级局域网 HTTP/1.1 Range 流媒体服务（供本机向 DLNA/UPnP 渲染器投屏本地音频与封面）。
 *
 * 仅暴露通过 [registerAudio] / [registerCover] 显式登记的内存条目，
 * 支持 `HEAD`、`GET` 以及 `Range: bytes=...` `206 Partial Content` 拖进度请求，
 * 无任意本地文件路径暴露风险。
 */
class DlnaHttpMediaServer(private val context: Context) {

    data class HostedTrackUrls(
        val mediaUrl: String,
        val coverUrl: String?,
        val mimeType: String,
        val sizeBytes: Long
    )

    private data class HostedAudio(
        val uri: Uri,
        val mimeType: String,
        val fileSizeHint: Long
    )

    private data class HostedCover(
        val bytes: ByteArray,
        val mimeType: String
    )

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    private val audioEntries = ConcurrentHashMap<String, HostedAudio>()
    private val coverEntries = ConcurrentHashMap<String, HostedCover>()

    val port: Int
        get() = serverSocket?.localPort?.takeIf { it > 0 } ?: 0

    fun ensureStarted(): Int {
        synchronized(lock) {
            val existing = serverSocket
            if (existing != null && !existing.isClosed && acceptJob?.isActive == true) {
                return existing.localPort
            }
            stopInternal()
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", 0))
            }
            serverSocket = socket
            val srvScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = srvScope
            acceptJob = srvScope.launch {
                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    srvScope.launch { handleClient(client) }
                }
            }
            return socket.localPort
        }
    }

    fun registerTrackForCasting(
        songId: Long,
        uri: Uri,
        mimeType: String,
        fileSizeHint: Long,
        coverBytes: ByteArray? = null,
        coverMime: String = "image/jpeg"
    ): HostedTrackUrls? {
        val localPort = ensureStarted()
        val hostIp = findLanIpv4Address(context) ?: return null
        val token = UUID.randomUUID().toString().replace("-", "").take(12)

        // 只保留最近几首，避免长时间连播无限增长；用前缀覆盖同 ID 旧条目
        val audioKey = "media_${songId}_$token"
        audioEntries.keys.filter { it.startsWith("media_${songId}_") }.forEach { audioEntries.remove(it) }
        if (audioEntries.size > 16) {
            val excess = audioEntries.keys.take(audioEntries.size - 16)
            excess.forEach { audioEntries.remove(it) }
        }
        val actualSize = probeUriLength(uri).takeIf { it > 0L } ?: fileSizeHint.coerceAtLeast(0L)
        audioEntries[audioKey] = HostedAudio(uri = uri, mimeType = mimeType, fileSizeHint = actualSize)

        var coverUrl: String? = null
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val artKey = "art_${songId}_$token"
            coverEntries.keys.filter { it.startsWith("art_${songId}_") }.forEach { coverEntries.remove(it) }
            if (coverEntries.size > 16) {
                val excess = coverEntries.keys.take(coverEntries.size - 16)
                excess.forEach { coverEntries.remove(it) }
            }
            coverEntries[artKey] = HostedCover(coverBytes, coverMime)
            coverUrl = "http://$hostIp:$localPort/$artKey"
        }

        val ext = extensionForMime(mimeType)
        val mediaUrl = "http://$hostIp:$localPort/$audioKey.$ext"
        return HostedTrackUrls(
            mediaUrl = mediaUrl,
            coverUrl = coverUrl,
            mimeType = mimeType,
            sizeBytes = actualSize
        )
    }

    fun stop() {
        synchronized(lock) {
            stopInternal()
            audioEntries.clear()
            coverEntries.clear()
        }
    }

    private fun stopInternal() {
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeSimpleError(out, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeSimpleError(out, 405, "Method Not Allowed")
                return
            }

            val path = URLDecoder.decode(rawTarget.trimStart('/'), "UTF-8")
            val rangeHeader = headers["range"]
            val headOnly = method == "HEAD"

            val artKey = path.substringBefore('.')
            val cover = coverEntries[artKey]
            if (cover != null) {
                serveBytes(
                    out = out,
                    bytes = cover.bytes,
                    mimeType = cover.mimeType,
                    rangeHeader = rangeHeader,
                    headOnly = headOnly
                )
                return
            }

            val audio = audioEntries[path.substringBefore('.')]
            if (audio == null) {
                writeSimpleError(out, 404, "Not Found")
                return
            }
            serveAudio(out, audio, rangeHeader, headOnly)
        } catch (_: IOException) {
            // TV / 音箱主动断开 Range 探测连接属正常行为
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveBytes(
        out: BufferedOutputStream,
        bytes: ByteArray,
        mimeType: String,
        rangeHeader: String?,
        headOnly: Boolean
    ) {
        val total = bytes.size.toLong()
        when (val range = parseHttpRange(rangeHeader, total)) {
            HttpRangeParse.Unsatisfiable -> {
                writeRangeNotSatisfiable(out, total)
                return
            }
            HttpRangeParse.None -> {
                writeHeaders(out, 200, "OK", mimeType, total, null)
                if (!headOnly) {
                    out.write(bytes)
                }
            }
            is HttpRangeParse.Valid -> {
                val r = range.range
                writeHeaders(out, 206, "Partial Content", mimeType, total, r)
                if (!headOnly) {
                    out.write(bytes, r.start.toInt(), r.length.toInt())
                }
            }
        }
        out.flush()
    }

    private fun serveAudio(
        out: BufferedOutputStream,
        audio: HostedAudio,
        rangeHeader: String?,
        headOnly: Boolean
    ) {
        val total = probeUriLength(audio.uri).takeIf { it > 0L } ?: audio.fileSizeHint
        when (val range = parseHttpRange(rangeHeader, total)) {
            HttpRangeParse.Unsatisfiable -> {
                writeRangeNotSatisfiable(out, total)
                return
            }
            HttpRangeParse.None -> {
                writeHeaders(out, 200, "OK", audio.mimeType, total, null)
                if (!headOnly) {
                    streamUriRange(audio.uri, 0L, if (total > 0L) total else Long.MAX_VALUE, out)
                }
            }
            is HttpRangeParse.Valid -> {
                val r = range.range
                writeHeaders(out, 206, "Partial Content", audio.mimeType, total, r)
                if (!headOnly) {
                    streamUriRange(audio.uri, r.start, r.length, out)
                }
            }
        }
        out.flush()
    }

    private fun probeUriLength(uri: Uri): Long {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path?.let { File(it).takeIf(File::isFile)?.length() } ?: -1L
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                when {
                    afd.declaredLength >= 0L -> afd.declaredLength
                    afd.length >= 0L -> afd.length
                    else -> afd.parcelFileDescriptor.statSize
                }
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun streamUriRange(uri: Uri, startOffset: Long, maxBytes: Long, out: OutputStream) {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val file = uri.path?.let(::File) ?: return
            RandomAccessFile(file, "r").use { raf ->
                if (startOffset > 0L) raf.seek(startOffset)
                val buf = ByteArray(64 * 1024)
                var remaining = maxBytes
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = raf.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            return
        }

        val afd: AssetFileDescriptor? = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        }.getOrNull()
        if (afd != null) {
            afd.use {
                val stream = FileInputStream(it.fileDescriptor)
                val baseOffset = it.startOffset.coerceAtLeast(0L)
                val seekTarget = baseOffset + startOffset
                val positioned = runCatching {
                    stream.channel.position(seekTarget)
                    true
                }.getOrDefault(false)
                if (!positioned && startOffset > 0L) {
                    skipFully(stream, seekTarget)
                }
                copyLimited(stream, out, maxBytes)
            }
            return
        }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            if (startOffset > 0L) skipFully(stream, startOffset)
            copyLimited(stream, out, maxBytes)
        }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var left = bytesToSkip
        val scratch = ByteArray(8 * 1024)
        while (left > 0L) {
            val skipped = stream.skip(left)
            if (skipped > 0L) {
                left -= skipped
                continue
            }
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n <= 0) break
            left -= n
        }
    }

    private fun copyLimited(stream: InputStream, out: OutputStream, maxBytes: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = maxBytes
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun writeHeaders(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        mimeType: String,
        totalLength: Long,
        range: HttpByteRange?
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(mimeType).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (range != null) {
                append("Content-Range: bytes ")
                    .append(range.start).append('-').append(range.endInclusive)
                    .append('/').append(totalLength).append("\r\n")
                append("Content-Length: ").append(range.length).append("\r\n")
            } else if (totalLength > 0L) {
                append("Content-Length: ").append(totalLength).append("\r\n")
            }
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun writeRangeNotSatisfiable(out: BufferedOutputStream, totalLength: Long) {
        val header = buildString {
            append("HTTP/1.1 416 Range Not Satisfiable\r\n")
            if (totalLength > 0L) append("Content-Range: bytes */").append(totalLength).append("\r\n")
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun writeSimpleError(out: BufferedOutputStream, code: Int, reason: String) {
        val body = "$code $reason".toByteArray(StandardCharsets.US_ASCII)
        val header = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readHttpLine(input: InputStream): String? {
        val buf = StringBuilder(128)
        var prevCr = false
        while (buf.length < 4096) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) {
                if (prevCr && buf.isNotEmpty() && buf[buf.length - 1] == '\r') {
                    buf.setLength(buf.length - 1)
                }
                return buf.toString()
            }
            prevCr = b == '\r'.code
            buf.append(b.toChar())
        }
        return buf.toString()
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
        "audio/flac" -> "flac"
        "audio/wav" -> "wav"
        "audio/mp4", "audio/alac" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/ape" -> "ape"
        "audio/dsd" -> "dsf"
        "audio/aiff" -> "aiff"
        else -> "mp3"
    }

    companion object {
        /** 获取当前 Wi-Fi / 有线局域网的站点本地 IPv4 地址（排除回环、蜂窝与 VPN 隧道口） */
        fun findLanIpv4Address(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val active = cm.activeNetwork
                val nets = if (active != null) listOf(active) else cm.allNetworks.toList()
                for (net in nets) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!isLan) continue
                    val lp: LinkProperties = cm.getLinkProperties(net) ?: continue
                    val addr = lp.linkAddresses
                        .map { it.address }
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    if (addr != null) return addr.hostAddress
                }
            }
            // 兜底枚举网卡（例如本地热点或旧系统回调为空时）
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            for (iface in ifaces) {
                val name = iface.name.lowercase(Locale.ROOT)
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("ccmni")) {
                    continue
                }
                val ip = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                if (ip != null) return ip.hostAddress
            }
            return null
        }

        /** 对图像二进制字节流的极简格式探测 */
        fun detectImageMime(bytes: ByteArray): String = when {
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
