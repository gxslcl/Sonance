package com.sonance.player.data.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {

    @Test
    fun parseSsdpResponse_parsesCaseInsensitiveHeadersAndRejectsByeBye() {
        val okPacket = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            location: http://192.168.1.50:8200/rootDesc.xml
            ST: urn:schemas-upnp-org:device:MediaServer:1
            usn: uuid:4d696e69-444c-164e-9d41-001122334455::upnp:rootdevice
            SERVER: Linux/5.15 UPnP/1.0 MiniDLNA/1.3.0
        """.trimIndent().replace("\n", "\r\n")

        val parsed = parseSsdpResponse(okPacket)
        assertNotNull(parsed)
        assertEquals("http://192.168.1.50:8200/rootDesc.xml", parsed!!.location)
        assertEquals("urn:schemas-upnp-org:device:MediaServer:1", parsed.st)
        assertEquals("Linux/5.15 UPnP/1.0 MiniDLNA/1.3.0", parsed.server)

        val aliveNotify = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            LOCATION: http://192.168.1.99:49152/description.xml
            NT: urn:schemas-upnp-org:device:MediaRenderer:1
            NTS: ssdp:alive
            USN: uuid:tv-renderer-01
        """.trimIndent().replace("\n", "\r\n")
        assertEquals("http://192.168.1.99:49152/description.xml", parseSsdpResponse(aliveNotify)?.location)

        val byeNotify = aliveNotify.replace("ssdp:alive", "ssdp:byebye")
        assertNull(parseSsdpResponse(byeNotify))

        val missingLocation = "HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n"
        assertNull(parseSsdpResponse(missingLocation))
    }

    @Test
    fun resolveUpnpUrl_resolvesRelativeAndRootRelativePaths() {
        assertEquals(
            "http://192.168.1.10:8200/ctl/ContentDir",
            resolveUpnpUrl("http://192.168.1.10:8200/rootDesc.xml", "/ctl/ContentDir")
        )
        assertEquals(
            "http://192.168.1.10:8200/upnp/ctl/AVTransport",
            resolveUpnpUrl("http://192.168.1.10:8200/upnp/description.xml", "ctl/AVTransport")
        )
        assertEquals(
            "http://192.168.1.22:9000/media/1.flac",
            resolveUpnpUrl("http://192.168.1.10:8200/rootDesc.xml", "http://192.168.1.22:9000/media/1.flac")
        )
        assertNull(resolveUpnpUrl("http://192.168.1.10:8200/rootDesc.xml", "   "))
    }

    @Test
    fun parseDeviceDescription_collectsEmbeddedSubDevicesAndUrlBase() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://192.168.31.12:9000/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>客厅 NAS 音视频中心</friendlyName>
                <manufacturer>Synology</manufacturer>
                <modelName>DS923+</modelName>
                <UDN>uuid:synology-nas-923</UDN>
                <iconList>
                  <icon>
                    <mimetype>image/png</mimetype>
                    <width>48</width>
                    <url>/icons/48.png</url>
                  </icon>
                  <icon>
                    <mimetype>image/png</mimetype>
                    <width>120</width>
                    <url>/icons/120.png</url>
                  </icon>
                </iconList>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                    <SCPDURL>/scpd/ContentDirectory.xml</SCPDURL>
                    <controlURL>/ctl/ContentDir</controlURL>
                    <eventSubURL>/evt/ContentDir</eventSubURL>
                  </service>
                </serviceList>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Embedded Renderer</friendlyName>
                    <UDN>uuid:embedded-renderer</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                        <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
                        <controlURL>/ctl/AVTransport</controlURL>
                        <eventSubURL>/evt/AVTransport</eventSubURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                        <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
                        <controlURL>/ctl/RenderingControl</controlURL>
                        <eventSubURL>/evt/RenderingControl</eventSubURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()

        val device = DlnaXmlParser.parseDeviceDescription("http://192.168.31.12:50001/desc.xml", xml)
        assertNotNull(device)
        assertEquals("客厅 NAS 音视频中心", device!!.friendlyName)
        assertEquals("uuid:synology-nas-923", device.udn)
        assertTrue(device.isMediaServer)
        assertTrue(device.isMediaRenderer)
        assertEquals("http://192.168.31.12:9000/icons/120.png", device.iconUrl)
        assertEquals("http://192.168.31.12:9000/ctl/ContentDir", device.contentDirectoryService?.controlUrl)
        assertEquals("http://192.168.31.12:9000/ctl/AVTransport", device.avTransportService?.controlUrl)
        assertEquals("http://192.168.31.12:9000/ctl/RenderingControl", device.renderingControlService?.controlUrl)
    }

    @Test
    fun parseBrowseSoapResponse_parsesAudioTracksContainersAndFiltersNonAudio() {
        val innerDidl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <container id="1$4" parentID="1" childCount="18" restricted="1">
                <dc:title>无损专辑</dc:title>
                <upnp:class>object.container.storageFolder</upnp:class>
              </container>
              <item id="track-42" parentID="1" restricted="1">
                <dc:title>夜曲 (Hi-Res)</dc:title>
                <upnp:artist>周杰伦</upnp:artist>
                <upnp:album>十一月的萧邦</upnp:album>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <upnp:albumArtURI>/AlbumArt/42.jpg</upnp:albumArtURI>
                <res protocolInfo="http-get:*:audio/flac:DLNA.ORG_OP=01" duration="0:03:46.500" size="42100400" sampleFrequency="96000" bitsPerSample="24" nrAudioChannels="2">/MediaItems/42.flac</res>
              </item>
              <item id="video-99" parentID="1" restricted="1">
                <dc:title>演唱会MV</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:video/mp4:*">/MediaItems/99.mp4</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()

        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <Result>${DlnaXmlParser.escapeXml(innerDidl)}</Result>
                  <NumberReturned>3</NumberReturned>
                  <TotalMatches>3</TotalMatches>
                </u:BrowseResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val parsed = DlnaXmlParser.parseBrowseSoapResponse(soap, "http://192.168.1.10:8200/rootDesc.xml")
        assertEquals(2, parsed.entries.size)

        val folder = parsed.entries[0] as DlnaContainer
        assertEquals("无损专辑", folder.title)
        assertEquals(18, folder.childCount)

        val track = parsed.entries[1] as DlnaAudioItem
        assertEquals("夜曲 (Hi-Res)", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals("十一月的萧邦", track.album)
        assertEquals(226_500L, track.durationMs)
        assertEquals(42100400L, track.sizeBytes)
        assertEquals(96_000, track.sampleRateHz)
        assertEquals(24, track.bitsPerSample)
        assertEquals("audio/flac", track.mimeType)
        assertEquals("http://192.168.1.10:8200/MediaItems/42.flac", track.resourceUrl)
        assertEquals("http://192.168.1.10:8200/AlbumArt/42.jpg", track.albumArtUri)
    }

    @Test
    fun avTransportAndRenderingControlSoapBuildersAndParsers_roundTripCleanly() {
        val meta = DlnaXmlParser.buildDidlAudioMetadata(
            title = "A & B <Live>",
            artist = "Singer \"1\"",
            album = "Test's Album",
            mimeType = "audio/flac",
            resourceUrl = "http://192.168.1.5:12345/media_1.flac",
            durationMs = 220_000L,
            sizeBytes = 31_457_280L,
            coverUrl = "http://192.168.1.5:12345/art_1.jpg"
        )
        val setUri = DlnaXmlParser.buildSetAvTransportUriSoap(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            currentUri = "http://192.168.1.5:12345/media_1.flac",
            metadataXml = meta
        )
        val doc = DlnaXmlParser.parseXmlDocument(setUri)
        assertNotNull(doc)
        assertTrue(setUri.contains("&amp;lt;Live&amp;gt;"))

        val transportXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PLAYING</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                </u:GetTransportInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val tInfo = DlnaXmlParser.parseTransportInfoResponse(transportXml)
        assertEquals(DlnaTransportState.PLAYING, tInfo?.state)
        assertTrue(tInfo!!.state.isActivePlaying)

        val posXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <TrackDuration>00:03:40</TrackDuration>
                  <RelTime>00:02:19</RelTime>
                  <AbsTime>00:02:19</AbsTime>
                  <TrackURI>http://192.168.1.5:12345/media_1.flac</TrackURI>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val pInfo = DlnaXmlParser.parsePositionInfoResponse(posXml)
        assertEquals(220_000L, pInfo?.trackDurationMs)
        assertEquals(139_000L, pInfo?.relTimeMs)

        val volXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                  <CurrentVolume>68</CurrentVolume>
                </u:GetVolumeResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals(68, DlnaXmlParser.parseVolumeResponse(volXml))

        val faultXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <s:Fault>
                  <faultstring>UPnP Error</faultstring>
                  <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>716</errorCode><errorDescription>Resource not found</errorDescription></UPnPError></detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals("Resource not found", DlnaXmlParser.parseSoapFault(faultXml))
    }

    @Test
    fun durationMimeAndHttpRangeHelpers_handleEdgeCases() {
        assertEquals(220_000L, parseUpnpDuration("0:03:40"))
        assertEquals(3_723_500L, parseUpnpDuration("01:02:03.500"))
        assertEquals(225_000L, parseUpnpDuration("3:45"))
        assertEquals(220_500L, parseUpnpDuration("220.5"))
        assertEquals(0L, parseUpnpDuration("NOT_IMPLEMENTED"))
        assertEquals(0L, parseUpnpDuration("-1"))
        assertEquals("00:03:40", formatUpnpDuration(220_400L))
        assertEquals("01:02:03", formatUpnpDuration(3_723_000L))

        assertEquals("audio/flac", guessAudioMimeType("track.flac", null))
        assertEquals("audio/mp4", guessAudioMimeType("song.m4a", "audio/m4a; charset=binary"))
        assertEquals("audio/opus", guessAudioMimeType("voice.opus", "application/octet-stream"))

        assertEquals(HttpRangeParse.None, parseHttpRange(null, 1000L))
        assertEquals(HttpRangeParse.Valid(HttpByteRange(0L, 499L)), parseHttpRange("bytes=0-499", 1000L))
        assertEquals(HttpRangeParse.Valid(HttpByteRange(500L, 999L)), parseHttpRange("bytes=500-", 1000L))
        assertEquals(HttpRangeParse.Valid(HttpByteRange(500L, 999L)), parseHttpRange("bytes=500-99999", 1000L))
        assertEquals(HttpRangeParse.Valid(HttpByteRange(800L, 999L)), parseHttpRange("bytes=-200", 1000L))
        assertEquals(HttpRangeParse.Unsatisfiable, parseHttpRange("bytes=1000-1005", 1000L))
        assertEquals(HttpRangeParse.Unsatisfiable, parseHttpRange("bytes=400-100", 1000L))
        assertEquals(HttpRangeParse.Unsatisfiable, parseHttpRange("items=0-10", 1000L))
        assertFalse(DlnaTransportState.PAUSED_PLAYBACK.isActivePlaying)
    }
}
