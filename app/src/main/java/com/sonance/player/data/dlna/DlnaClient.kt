package com.sonance.player.data.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯 Kotlin / JDK 实现的 DLNA / UPnP 协议客户端：
 * - SSDP 局域网多播与手动 IP/URL 探测
 * - `ContentDirectory:Browse` 媒体服务器目录浏览（DMS）
 * - `AVTransport` & `RenderingControl` 投屏控制（DMR）
 */
class DlnaClient(private val context: Context) {

    /**
     * 扫描同一 Wi-Fi / 局域网下的 DLNA 媒体服务器（DMS）与投屏设备（DMR）。
     */
    suspend fun discoverDevices(timeoutMs: Long = 2_400L): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val responses = collectSsdpResponses(timeoutMs)
            val locations = responses.map { it.location }.distinct()
            if (locations.isEmpty()) return@withContext emptyList()
            fetchDevicesFromLocations(locations, isManual = false)
        }

    /**
     * 手动输入设备 IP（如 `192.168.1.10` / `192.168.1.10:8200`）或完整描述 XML URL。
     */
    suspend fun probeManualDevice(rawInput: String): Result<List<DlnaDevice>> =
        withContext(Dispatchers.IO) {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("请输入局域网设备 IP 或描述地址"))
            }

            val isFullUrlWithPath = (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)) &&
                runCatching { URL(trimmed).path.orEmpty().trim('/').isNotEmpty() }.getOrDefault(false)

            if (isFullUrlWithPath) {
                val normalized = trimmed
                val device = fetchDeviceDescription(normalized, isManual = true)
                    ?: return@withContext Result.failure(IOException("无法解析该地址的 UPnP/DLNA 设备描述"))
                return@withContext Result.success(listOf(device))
            }

            val hostPort = trimmed
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
            val host = hostPort.substringBefore(':').trim()
            val explicitPort = hostPort.substringAfter(':', "").toIntOrNull()
            if (host.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("设备地址格式无效"))
            }

            val discoveredLocations = LinkedHashSet<String>()
            // 先对 <host>:1900 发一次单播 SSDP，大部分标准 UPnP 守护进程会在 ~200ms 内直接返回精确 LOCATION
            collectUnicastSsdp(host, 900L).forEach { discoveredLocations += it.location }

            val candidatePaths = listOf(
                "/rootDesc.xml",
                "/description.xml",
                "/DeviceDescription.xml",
                "/desc/device.xml",
                "/xml/device_description.xml",
                "/upnp/dev/desc.xml"
            )
            if (explicitPort != null) {
                candidatePaths.forEach { p -> discoveredLocations += "http://$host:$explicitPort$p" }
            } else {
                val commonPorts = listOf(8200, 9000, 32469, 49152, 49153, 50001, 1400, 8080)
                for (p in commonPorts) {
                    val path = when (p) {
                        8200 -> "/rootDesc.xml"
                        9000, 32469 -> "/DeviceDescription.xml"
                        50001 -> "/desc/device.xml"
                        1400 -> "/xml/device_description.xml"
                        else -> "/description.xml"
                    }
                    discoveredLocations += "http://$host:$p$path"
                }
            }

            val devices = fetchDevicesFromLocations(discoveredLocations.toList(), isManual = true)
            if (devices.isEmpty()) {
                Result.failure(IOException("未能连接到 $host 上的 DLNA 服务，请检查是否处于同一局域网或输入完整 XML 地址"))
            } else {
                Result.success(devices)
            }
        }

    suspend fun browse(
        device: DlnaDevice,
        objectId: String,
        startingIndex: Int = 0,
        requestedCount: Int = 200
    ): Result<DlnaBrowseResult> = withContext(Dispatchers.IO) {
        val svc = device.contentDirectoryService
            ?: return@withContext Result.failure(IllegalStateException("该设备未提供 ContentDirectory 服务"))
        val soap = DlnaXmlParser.buildBrowseSoapEnvelope(
            serviceType = svc.serviceType,
            objectId = objectId,
            startingIndex = startingIndex,
            requestedCount = requestedCount
        )
        postSoap(svc.controlUrl, svc.serviceType, "Browse", soap).mapCatching { responseXml ->
            DlnaXmlParser.parseBrowseSoapResponse(responseXml, device.baseUrl)
        }
    }

    suspend fun castMedia(
        device: DlnaDevice,
        mediaUrl: String,
        title: String,
        artist: String,
        album: String,
        mimeType: String,
        durationMs: Long,
        sizeBytes: Long,
        coverUrl: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = device.avTransportService
            ?: return@withContext Result.failure(IllegalStateException("该设备不支持 AVTransport 投屏"))
        val metadata = DlnaXmlParser.buildDidlAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            mimeType = mimeType,
            resourceUrl = mediaUrl,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            coverUrl = coverUrl
        )
        val setUriSoap = DlnaXmlParser.buildSetAvTransportUriSoap(
            serviceType = svc.serviceType,
            currentUri = mediaUrl,
            metadataXml = metadata
        )
        postSoap(svc.controlUrl, svc.serviceType, "SetAVTransportURI", setUriSoap)
            .onFailure { return@withContext Result.failure(it) }

        val playSoap = DlnaXmlParser.buildSimpleTransportSoap(
            serviceType = svc.serviceType,
            actionName = "Play",
            extraParams = mapOf("Speed" to "1")
        )
        postSoap(svc.controlUrl, svc.serviceType, "Play", playSoap).map { }
    }

    suspend fun play(device: DlnaDevice): Result<Unit> = transportAction(
        device = device,
        actionName = "Play",
        extraParams = mapOf("Speed" to "1")
    )

    suspend fun pause(device: DlnaDevice): Result<Unit> = transportAction(device, "Pause")

    suspend fun stop(device: DlnaDevice): Result<Unit> = transportAction(device, "Stop")

    suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit> = transportAction(
        device = device,
        actionName = "Seek",
        extraParams = mapOf(
            "Unit" to "REL_TIME",
            "Target" to formatUpnpDuration(positionMs)
        )
    )

    suspend fun getTransportInfo(device: DlnaDevice): Result<DlnaTransportInfo> = withContext(Dispatchers.IO) {
        val svc = device.avTransportService
            ?: return@withContext Result.failure(IllegalStateException("不支持 AVTransport"))
        val soap = DlnaXmlParser.buildSimpleTransportSoap(svc.serviceType, "GetTransportInfo")
        postSoap(svc.controlUrl, svc.serviceType, "GetTransportInfo", soap).mapCatching { xml ->
            DlnaXmlParser.parseTransportInfoResponse(xml)
                ?: throw IOException("无法解析传输状态")
        }
    }

    suspend fun getPositionInfo(device: DlnaDevice): Result<DlnaPositionInfo> = withContext(Dispatchers.IO) {
        val svc = device.avTransportService
            ?: return@withContext Result.failure(IllegalStateException("不支持 AVTransport"))
        val soap = DlnaXmlParser.buildSimpleTransportSoap(svc.serviceType, "GetPositionInfo")
        postSoap(svc.controlUrl, svc.serviceType, "GetPositionInfo", soap).mapCatching { xml ->
            DlnaXmlParser.parsePositionInfoResponse(xml)
                ?: throw IOException("无法解析进度信息")
        }
    }

    suspend fun getVolume(device: DlnaDevice): Result<Int> = withContext(Dispatchers.IO) {
        val svc = device.renderingControlService
            ?: return@withContext Result.failure(IllegalStateException("该设备不支持音量控制"))
        val soap = DlnaXmlParser.buildRenderingControlSoap(svc.serviceType, "GetVolume")
        postSoap(svc.controlUrl, svc.serviceType, "GetVolume", soap).mapCatching { xml ->
            DlnaXmlParser.parseVolumeResponse(xml) ?: throw IOException("无法读取音量")
        }
    }

    suspend fun setVolume(device: DlnaDevice, volumePercent: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = device.renderingControlService
            ?: return@withContext Result.failure(IllegalStateException("该设备不支持音量控制"))
        val clamped = volumePercent.coerceIn(0, 100)
        val soap = DlnaXmlParser.buildRenderingControlSoap(
            serviceType = svc.serviceType,
            actionName = "SetVolume",
            extraParams = mapOf("DesiredVolume" to clamped.toString())
        )
        postSoap(svc.controlUrl, svc.serviceType, "SetVolume", soap).map { }
    }

    private suspend fun transportAction(
        device: DlnaDevice,
        actionName: String,
        extraParams: Map<String, String> = emptyMap()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = device.avTransportService
            ?: return@withContext Result.failure(IllegalStateException("该设备不支持 AVTransport"))
        val soap = DlnaXmlParser.buildSimpleTransportSoap(svc.serviceType, actionName, extraParams)
        postSoap(svc.controlUrl, svc.serviceType, actionName, soap).map { }
    }

    private suspend fun fetchDevicesFromLocations(
        locations: List<String>,
        isManual: Boolean
    ): List<DlnaDevice> = coroutineScope {
        val semaphore = Semaphore(6)
        val byUdn = ConcurrentHashMap<String, DlnaDevice>()
        locations.map { location ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    fetchDeviceDescription(location, isManual)?.let { dev ->
                        byUdn.putIfAbsent(dev.udn, dev)
                    }
                }
            }
        }.awaitAll()
        byUdn.values.sortedWith(
            compareBy<DlnaDevice> { if (it.isMediaServer) 0 else 1 }
                .thenBy { it.friendlyName.lowercase(Locale.ROOT) }
        )
    }

    private fun fetchDeviceDescription(locationUrl: String, isManual: Boolean): DlnaDevice? {
        val conn = runCatching {
            (URL(locationUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_500
                readTimeout = 3_500
                requestMethod = "GET"
                setRequestProperty("Accept", "text/xml, application/xml")
            }
        }.getOrNull() ?: return null

        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val xml = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            DlnaXmlParser.parseDeviceDescription(locationUrl, xml, isManual = isManual)
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postSoap(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        soapBody: String
    ): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 8_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#$actionName\"")
            }
            val bytes = soapBody.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Result.success(body)
            } else {
                val fault = DlnaXmlParser.parseSoapFault(body)
                Result.failure(IOException(fault ?: "DLNA 控制失败 (HTTP $code)"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    private fun collectSsdpResponses(timeoutMs: Long): List<SsdpDiscoveryResponse> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("sonance:dlna_ssdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val found = ArrayList<SsdpDiscoveryResponse>()
        val seenLocations = HashSet<String>()
        val groupAddr = InetAddress.getByName(SSDP_MULTICAST_IP)
        val targets = listOf(
            "ssdp:all",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1"
        )
        val localIps = activeLanIpv4Addresses()

        return try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val sockets = if (localIps.isEmpty()) {
                listOf(DatagramSocket(0))
            } else {
                localIps.mapNotNull { ip ->
                    runCatching { DatagramSocket(InetSocketAddress(ip, 0)) }.getOrNull()
                }.ifEmpty { listOf(DatagramSocket(0)) }
            }

            try {
                for (sock in sockets) {
                    for (st in targets) {
                        val msg = buildMSearchPacket(SSDP_MULTICAST_IP, st)
                        runCatching { sock.send(DatagramPacket(msg, msg.size, groupAddr, SSDP_PORT)) }
                    }
                }
                Thread.sleep(110L)
                for (sock in sockets) {
                    for (st in targets) {
                        val msg = buildMSearchPacket(SSDP_MULTICAST_IP, st)
                        runCatching { sock.send(DatagramPacket(msg, msg.size, groupAddr, SSDP_PORT)) }
                    }
                }

                val buf = ByteArray(8192)
                for (sock in sockets) {
                    val sliceDeadline = minOf(deadline, SystemClock.elapsedRealtime() + (timeoutMs / sockets.size).coerceAtLeast(500L))
                    while (SystemClock.elapsedRealtime() < sliceDeadline) {
                        val remaining = (sliceDeadline - SystemClock.elapsedRealtime()).toInt().coerceIn(50, 500)
                        sock.soTimeout = remaining
                        val packet = DatagramPacket(buf, buf.size)
                        val received = runCatching {
                            sock.receive(packet)
                            true
                        }.getOrDefault(false)
                        if (!received) break
                        val raw = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                        val parsed = parseSsdpResponse(raw) ?: continue
                        if (seenLocations.add(parsed.location)) {
                            found += parsed
                        }
                    }
                }
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
            found
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun collectUnicastSsdp(host: String, timeoutMs: Long): List<SsdpDiscoveryResponse> {
        val found = ArrayList<SsdpDiscoveryResponse>()
        val targetAddr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return found
        val socket = runCatching { DatagramSocket(0) }.getOrNull() ?: return found
        try {
            val packetBytes = buildMSearchPacket(host, "ssdp:all")
            socket.send(DatagramPacket(packetBytes, packetBytes.size, targetAddr, SSDP_PORT))
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val buf = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < deadline) {
                socket.soTimeout = (deadline - SystemClock.elapsedRealtime()).toInt().coerceIn(50, 350)
                val dp = DatagramPacket(buf, buf.size)
                val ok = runCatching {
                    socket.receive(dp)
                    true
                }.getOrDefault(false)
                if (!ok) break
                val raw = String(dp.data, dp.offset, dp.length, StandardCharsets.UTF_8)
                parseSsdpResponse(raw)?.let { found += it }
            }
        } catch (_: Throwable) {
            // 单播 1900 未开启时忽略，后续会自动探测常见 XML 描述端口
        } finally {
            runCatching { socket.close() }
        }
        return found
    }

    private fun buildMSearchPacket(host: String, st: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(host).append(':').append(SSDP_PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(st).append("\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.US_ASCII)

    private fun activeLanIpv4Addresses(): List<Inet4Address> {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val addrs = ArrayList<Inet4Address>()
        for (iface in ifaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("ccmni")) {
                continue
            }
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    addrs += addr
                }
            }
        }
        return addrs
    }

    companion object {
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}
