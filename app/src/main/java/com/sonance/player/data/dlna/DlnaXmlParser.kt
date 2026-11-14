package com.sonance.player.data.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 纯 Kotlin + JDK DOM 的 UPnP / DIDL-Lite / SOAP XML 解析与报文构建。
 *
 * 不依赖 Android 独有 `XmlPullParserFactory`，确保协议层全部可在纯 JVM 单元测试验证。
 */
object DlnaXmlParser {

    private val AUDIO_EXTS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "wma", "aiff", "aif", "dsf", "dff", "alac", "mp4"
    )

    internal fun parseXmlDocument(xml: String): Document? {
        val clean = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (clean.isEmpty()) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(clean)))
        }.getOrNull()
    }

    /** 解析 UPnP 设备描述 XML，支持 `URLBase` 以及嵌入式 `<deviceList>` 子设备服务收集 */
    fun parseDeviceDescription(
        locationUrl: String,
        xml: String,
        isManual: Boolean = false
    ): DlnaDevice? {
        val doc = parseXmlDocument(xml) ?: return null
        val root = doc.documentElement ?: return null
        val urlBase = directChildText(root, "URLBase")?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: locationUrl

        val rootDevice = firstChildElement(root, "device") ?: return null
        val udn = directChildText(rootDevice, "UDN").orEmpty().ifBlank { locationUrl }
        val friendlyName = directChildText(rootDevice, "friendlyName").orEmpty().ifBlank { "D-LAN 设备" }
        val manufacturer = directChildText(rootDevice, "manufacturer").orEmpty()
        val modelName = directChildText(rootDevice, "modelName").orEmpty()
        val deviceType = directChildText(rootDevice, "deviceType").orEmpty()
        val iconUrl = parseBestIconUrl(rootDevice, urlBase)

        val collectedServices = ArrayList<DlnaService>()
        val deviceTypes = ArrayList<String>()
        collectDevicesAndServices(rootDevice, urlBase, collectedServices, deviceTypes)

        val cdService = collectedServices.firstOrNull {
            it.serviceType.contains("ContentDirectory", ignoreCase = true)
        }
        val avService = collectedServices.firstOrNull {
            it.serviceType.contains("AVTransport", ignoreCase = true)
        }
        val rcService = collectedServices.firstOrNull {
            it.serviceType.contains("RenderingControl", ignoreCase = true)
        }

        val roles = buildSet {
            if (cdService != null || deviceTypes.any { it.contains("MediaServer", ignoreCase = true) }) {
                add(DlnaDeviceRole.MEDIA_SERVER)
            }
            if (avService != null || deviceTypes.any { it.contains("MediaRenderer", ignoreCase = true) }) {
                add(DlnaDeviceRole.MEDIA_RENDERER)
            }
        }
        if (cdService == null && avService == null) return null

        return DlnaDevice(
            udn = udn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            baseUrl = urlBase,
            locationUrl = locationUrl,
            deviceType = deviceType,
            roles = roles,
            iconUrl = iconUrl,
            contentDirectoryService = cdService,
            avTransportService = avService,
            renderingControlService = rcService,
            isManual = isManual
        )
    }

    private fun collectDevicesAndServices(
        deviceEl: Element,
        baseUrl: String,
        outServices: MutableList<DlnaService>,
        outTypes: MutableList<String>
    ) {
        directChildText(deviceEl, "deviceType")?.let { outTypes += it }
        firstChildElement(deviceEl, "serviceList")?.let { listEl ->
            for (svcEl in childElements(listEl, "service")) {
                val type = directChildText(svcEl, "serviceType").orEmpty()
                val id = directChildText(svcEl, "serviceId").orEmpty()
                val control = resolveUpnpUrl(baseUrl, directChildText(svcEl, "controlURL")) ?: continue
                val eventSub = resolveUpnpUrl(baseUrl, directChildText(svcEl, "eventSubURL")).orEmpty()
                val scpd = resolveUpnpUrl(baseUrl, directChildText(svcEl, "SCPDURL")).orEmpty()
                if (type.isNotBlank()) {
                    outServices += DlnaService(
                        serviceType = type,
                        serviceId = id,
                        controlUrl = control,
                        eventSubUrl = eventSub,
                        scpdUrl = scpd
                    )
                }
            }
        }
        firstChildElement(deviceEl, "deviceList")?.let { subList ->
            for (childDevice in childElements(subList, "device")) {
                collectDevicesAndServices(childDevice, baseUrl, outServices, outTypes)
            }
        }
    }

    private fun parseBestIconUrl(deviceEl: Element, baseUrl: String): String? {
        val iconList = firstChildElement(deviceEl, "iconList") ?: return null
        var bestScore = -1
        var bestUrl: String? = null
        for (icon in childElements(iconList, "icon")) {
            val url = resolveUpnpUrl(baseUrl, directChildText(icon, "url")) ?: continue
            val width = directChildText(icon, "width")?.toIntOrNull() ?: 0
            val mime = directChildText(icon, "mimetype").orEmpty().lowercase(Locale.ROOT)
            val bonus = if ("png" in mime || "jpeg" in mime || "jpg" in mime) 10_000 else 0
            val score = bonus + width
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    // ===== ContentDirectory:Browse =====

    fun buildBrowseSoapEnvelope(
        serviceType: String,
        objectId: String,
        startingIndex: Int = 0,
        requestedCount: Int = 200
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:Browse xmlns:u="${escapeXml(serviceType)}">
              <ObjectID>${escapeXml(objectId)}</ObjectID>
              <BrowseFlag>BrowseDirectChildren</BrowseFlag>
              <Filter>*</Filter>
              <StartingIndex>$startingIndex</StartingIndex>
              <RequestedCount>$requestedCount</RequestedCount>
              <SortCriteria></SortCriteria>
            </u:Browse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    fun parseBrowseSoapResponse(soapXml: String, baseUrl: String): DlnaBrowseResult {
        val doc = parseXmlDocument(soapXml) ?: return DlnaBrowseResult(emptyList(), 0, 0)
        val resultEl = findFirstElementByLocalName(doc.documentElement, "Result")
            ?: return DlnaBrowseResult(emptyList(), 0, 0)
        val didlRaw = resultEl.textContent?.trim().orEmpty()
        val numberReturned = findFirstElementByLocalName(doc.documentElement, "NumberReturned")
            ?.textContent?.trim()?.toIntOrNull() ?: 0
        val totalMatches = findFirstElementByLocalName(doc.documentElement, "TotalMatches")
            ?.textContent?.trim()?.toIntOrNull() ?: numberReturned

        val entries = if (didlRaw.isNotEmpty()) parseDidlLite(didlRaw, baseUrl) else emptyList()
        return DlnaBrowseResult(
            entries = entries,
            numberReturned = if (numberReturned > 0) numberReturned else entries.size,
            totalMatches = if (totalMatches > 0) totalMatches else entries.size
        )
    }

    fun parseDidlLite(didlXml: String, baseUrl: String): List<DlnaBrowseEntry> {
        val doc = parseXmlDocument(didlXml) ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()
        val out = ArrayList<DlnaBrowseEntry>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = localNameOf(node)
            when (name) {
                "container" -> parseContainer(node, baseUrl)?.let { out += it }
                "item" -> parseAudioItem(node, baseUrl)?.let { out += it }
            }
        }
        return out
    }

    private fun parseContainer(el: Element, baseUrl: String): DlnaContainer? {
        val id = el.getAttribute("id").trim()
        if (id.isEmpty()) return null
        val parentId = el.getAttribute("parentID").trim().ifEmpty { "0" }
        val title = directChildText(el, "title").orEmpty().ifBlank { "未命名目录" }
        val childCount = el.getAttribute("childCount").trim().toIntOrNull()
        val art = resolveUpnpUrl(baseUrl, directChildText(el, "albumArtURI"))
        return DlnaContainer(
            id = id,
            parentId = parentId,
            title = title,
            childCount = childCount,
            albumArtUri = art
        )
    }

    private fun parseAudioItem(el: Element, baseUrl: String): DlnaAudioItem? {
        val id = el.getAttribute("id").trim()
        if (id.isEmpty()) return null
        val parentId = el.getAttribute("parentID").trim().ifEmpty { "0" }
        val upnpClass = directChildText(el, "class").orEmpty()

        val resElements = childElements(el, "res")
        val selectedRes = pickAudioResource(resElements, upnpClass, baseUrl) ?: return null

        val url = resolveUpnpUrl(baseUrl, selectedRes.textContent) ?: return null
        val protocolInfo = selectedRes.getAttribute("protocolInfo").trim()
        val protocolMime = protocolInfo.split(':').getOrNull(2)?.takeIf { it.isNotBlank() && it != "*" }
        val mime = guessAudioMimeType(url, protocolMime)
        if (!isLikelyAudio(upnpClass, protocolInfo, url)) return null

        val title = directChildText(el, "title").orEmpty().ifBlank {
            url.substringBefore('?').substringAfterLast('/').ifBlank { "未知曲目" }
        }
        val artist = directChildText(el, "artist")
            ?: directChildText(el, "creator")
            ?: "未知艺术家"
        val album = directChildText(el, "album") ?: "局域网共享"
        val durationMs = parseUpnpDuration(selectedRes.getAttribute("duration"))
        val sizeBytes = selectedRes.getAttribute("size").trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val bitrateBps = selectedRes.getAttribute("bitrate").trim().toIntOrNull()?.let {
            // UPnP 规范单位为 Bytes/sec（部分服务器写 bps/kbps，>10000 视作 bps，否则乘 8）
            if (it in 1..600_000) it * 8 else it
        } ?: 0
        val sampleRate = selectedRes.getAttribute("sampleFrequency").trim().toIntOrNull() ?: 0
        val bitsPerSample = selectedRes.getAttribute("bitsPerSample").trim().toIntOrNull() ?: 0
        val channels = selectedRes.getAttribute("nrAudioChannels").trim().toIntOrNull() ?: 0
        val art = resolveUpnpUrl(baseUrl, directChildText(el, "albumArtURI"))

        return DlnaAudioItem(
            id = id,
            parentId = parentId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            resourceUrl = url,
            protocolInfo = protocolInfo,
            mimeType = mime,
            bitrateBps = bitrateBps,
            sampleRateHz = sampleRate,
            bitsPerSample = bitsPerSample,
            nrAudioChannels = channels,
            albumArtUri = art
        )
    }

    private fun pickAudioResource(
        resElements: List<Element>,
        upnpClass: String,
        baseUrl: String
    ): Element? {
        if (resElements.isEmpty()) return null
        val audioRes = resElements.firstOrNull { el ->
            val url = resolveUpnpUrl(baseUrl, el.textContent).orEmpty()
            val proto = el.getAttribute("protocolInfo")
            url.isNotBlank() && ("audio/" in proto.lowercase(Locale.ROOT) ||
                hasAudioExtension(url))
        }
        if (audioRes != null) return audioRes
        if (upnpClass.startsWith("object.item.audioItem", ignoreCase = true)) {
            return resElements.firstOrNull { !resolveUpnpUrl(baseUrl, it.textContent).isNullOrBlank() }
        }
        return null
    }

    private fun isLikelyAudio(upnpClass: String, protocolInfo: String, url: String): Boolean {
        val lcClass = upnpClass.lowercase(Locale.ROOT)
        if (lcClass.startsWith("object.item.videoitem") || lcClass.startsWith("object.item.imageitem")) {
            return false
        }
        if (lcClass.startsWith("object.item.audioitem")) return true
        val lcProto = protocolInfo.lowercase(Locale.ROOT)
        if ("audio/" in lcProto || "application/ogg" in lcProto) return true
        if ("video/" in lcProto || "image/" in lcProto) return false
        return hasAudioExtension(url)
    }

    private fun hasAudioExtension(url: String): Boolean {
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return ext in AUDIO_EXTS
    }

    // ===== AVTransport & RenderingControl SOAP =====

    fun buildDidlAudioMetadata(
        title: String,
        artist: String,
        album: String,
        mimeType: String,
        resourceUrl: String,
        durationMs: Long,
        sizeBytes: Long,
        coverUrl: String?
    ): String {
        val durationAttr = if (durationMs > 0L) " duration=\"${formatUpnpDuration(durationMs)}\"" else ""
        val sizeAttr = if (sizeBytes > 0L) " size=\"$sizeBytes\"" else ""
        val artTag = if (!coverUrl.isNullOrBlank()) {
            "<upnp:albumArtURI>${escapeXml(coverUrl)}</upnp:albumArtURI>"
        } else {
            ""
        }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>${escapeXml(title.ifBlank { "Sonance" })}</dc:title>
                <dc:creator>${escapeXml(artist)}</dc:creator>
                <upnp:artist>${escapeXml(artist)}</upnp:artist>
                <upnp:album>${escapeXml(album)}</upnp:album>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                $artTag
                <res protocolInfo="http-get:*:${escapeXml(mimeType)}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"$durationAttr$sizeAttr>${escapeXml(resourceUrl)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    fun buildSetAvTransportUriSoap(
        serviceType: String,
        currentUri: String,
        metadataXml: String
    ): String = buildSoapEnvelope(
        serviceType = serviceType,
        actionName = "SetAVTransportURI",
        params = listOf(
            "InstanceID" to "0",
            "CurrentURI" to currentUri,
            "CurrentURIMetaData" to metadataXml
        )
    )

    fun buildSimpleTransportSoap(
        serviceType: String,
        actionName: String,
        extraParams: Map<String, String> = emptyMap()
    ): String {
        val params = ArrayList<Pair<String, String>>(extraParams.size + 1)
        params += "InstanceID" to "0"
        extraParams.forEach { (k, v) -> params += k to v }
        return buildSoapEnvelope(serviceType, actionName, params)
    }

    fun buildRenderingControlSoap(
        serviceType: String,
        actionName: String,
        extraParams: Map<String, String> = emptyMap()
    ): String {
        val params = ArrayList<Pair<String, String>>(extraParams.size + 2)
        params += "InstanceID" to "0"
        params += "Channel" to "Master"
        extraParams.forEach { (k, v) -> params += k to v }
        return buildSoapEnvelope(serviceType, actionName, params)
    }

    private fun buildSoapEnvelope(
        serviceType: String,
        actionName: String,
        params: List<Pair<String, String>>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n")
        append("  <s:Body>\n")
        append("    <u:").append(actionName).append(" xmlns:u=\"").append(escapeXml(serviceType)).append("\">\n")
        for ((k, v) in params) {
            append("      <").append(k).append('>').append(escapeXml(v)).append("</").append(k).append(">\n")
        }
        append("    </u:").append(actionName).append(">\n")
        append("  </s:Body>\n")
        append("</s:Envelope>")
    }

    fun parseTransportInfoResponse(soapXml: String): DlnaTransportInfo? {
        val doc = parseXmlDocument(soapXml) ?: return null
        val root = doc.documentElement ?: return null
        val stateRaw = findFirstElementByLocalName(root, "CurrentTransportState")?.textContent ?: return null
        val status = findFirstElementByLocalName(root, "CurrentTransportStatus")?.textContent?.trim().orEmpty()
        return DlnaTransportInfo(
            state = DlnaTransportState.parse(stateRaw),
            status = status
        )
    }

    fun parsePositionInfoResponse(soapXml: String): DlnaPositionInfo? {
        val doc = parseXmlDocument(soapXml) ?: return null
        val root = doc.documentElement ?: return null
        val durationEl = findFirstElementByLocalName(root, "TrackDuration")
        val relEl = findFirstElementByLocalName(root, "RelTime")
        if (durationEl == null && relEl == null) return null
        val absEl = findFirstElementByLocalName(root, "AbsTime")
        val uriEl = findFirstElementByLocalName(root, "TrackURI")
        return DlnaPositionInfo(
            trackDurationMs = parseUpnpDuration(durationEl?.textContent),
            relTimeMs = parseUpnpDuration(relEl?.textContent),
            absTimeMs = parseUpnpDuration(absEl?.textContent),
            trackUri = uriEl?.textContent?.trim().orEmpty()
        )
    }

    fun parseVolumeResponse(soapXml: String): Int? {
        val doc = parseXmlDocument(soapXml) ?: return null
        val root = doc.documentElement ?: return null
        return findFirstElementByLocalName(root, "CurrentVolume")
            ?.textContent?.trim()?.toIntOrNull()?.coerceIn(0, 100)
    }

    fun parseSoapFault(soapXml: String): String? {
        val doc = parseXmlDocument(soapXml) ?: return null
        val root = doc.documentElement ?: return null
        val errDesc = findFirstElementByLocalName(root, "errorDescription")?.textContent?.trim()
        if (!errDesc.isNullOrBlank()) return errDesc
        val faultStr = findFirstElementByLocalName(root, "faultstring")?.textContent?.trim()
        return faultStr?.takeIf { it.isNotBlank() }
    }

    fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun localNameOf(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':')

    private fun directChildText(parent: Element, localName: String): String? {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i) as? Element ?: continue
            if (localNameOf(child).equals(localName, ignoreCase = true)) {
                return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun firstChildElement(parent: Element, localName: String): Element? {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i) as? Element ?: continue
            if (localNameOf(child).equals(localName, ignoreCase = true)) return child
        }
        return null
    }

    private fun childElements(parent: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i) as? Element ?: continue
            if (localNameOf(child).equals(localName, ignoreCase = true)) out += child
        }
        return out
    }

    private fun findFirstElementByLocalName(root: Element?, localName: String): Element? {
        root ?: return null
        if (localNameOf(root).equals(localName, ignoreCase = true)) return root
        val list = root.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i) as? Element ?: continue
            findFirstElementByLocalName(child, localName)?.let { return it }
        }
        return null
    }
}
