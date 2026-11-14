package com.sonance.player.data.netease

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NeteaseStreamTest {

    @Test
    fun `selectBestAttempt skips trial tier for VIP and selects full stream at lower level`() {
        val levels = listOf(NeteaseLevel.HIRES, NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        val selection = NeteaseStream.selectBestAttempt(levels, isVip = true) { lv ->
            when (lv) {
                NeteaseLevel.HIRES -> NeteaseStream.Attempt.Ok(
                    NeteaseStreamInfo("https://cdn/trial.mp3", NeteaseLevel.EXHIGH, 320000, "mp3", trial = true),
                    1200L
                )
                NeteaseLevel.LOSSLESS -> NeteaseStream.Attempt.Ok(
                    NeteaseStreamInfo("https://cdn/full.flac", NeteaseLevel.LOSSLESS, 999000, "flac", trial = false),
                    1200L
                )
                else -> error("should not reach $lv")
            }
        }
        assertTrue(selection is NeteaseStream.Selection.Found)
        val found = (selection as NeteaseStream.Selection.Found).attempt.info
        assertFalse(found.trial)
        assertEquals("https://cdn/full.flac", found.url)
        assertEquals(NeteaseLevel.LOSSLESS, found.level)
    }

    @Test
    fun `selectBestAttempt falls back to EXHIGH when higher tiers return trial or 404`() {
        val levels = listOf(NeteaseLevel.HIRES, NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        val selection = NeteaseStream.selectBestAttempt(levels, isVip = true) { lv ->
            when (lv) {
                NeteaseLevel.HIRES -> NeteaseStream.Attempt.Retry(404)
                NeteaseLevel.LOSSLESS -> NeteaseStream.Attempt.Ok(
                    NeteaseStreamInfo("https://cdn/trial.mp3", NeteaseLevel.EXHIGH, 320000, "mp3", trial = true),
                    1200L
                )
                NeteaseLevel.EXHIGH -> NeteaseStream.Attempt.Ok(
                    NeteaseStreamInfo("https://cdn/full320.mp3", NeteaseLevel.EXHIGH, 320000, "mp3", trial = false),
                    1200L
                )
                NeteaseLevel.STANDARD -> error("should not reach STANDARD")
                else -> error("unexpected $lv")
            }
        }
        val found = (selection as NeteaseStream.Selection.Found).attempt.info
        assertFalse(found.trial)
        assertEquals("https://cdn/full320.mp3", found.url)
    }

    @Test
    fun `selectBestAttempt falls back to trial only when every VIP level is trial`() {
        val levels = listOf(NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        val selection = NeteaseStream.selectBestAttempt(levels, isVip = true) { lv ->
            NeteaseStream.Attempt.Ok(
                NeteaseStreamInfo("https://cdn/trial-${lv.apiValue}.mp3", lv, 128000, "mp3", trial = true),
                1200L
            )
        }
        val found = (selection as NeteaseStream.Selection.Found).attempt.info
        assertTrue(found.trial)
        assertEquals("https://cdn/trial-lossless.mp3", found.url)
    }

    @Test
    fun `parsePlayerUrlResponse detects freeTrialInfo accurately`() {
        val trialJson = """{"code":200,"data":[{"id":1,"url":"http://a/t.mp3","br":128000,"type":"mp3","level":"standard","expi":1200,"fee":1,"freeTrialInfo":{"start":0,"end":30}}]}"""
        val fullJson = """{"code":200,"data":[{"id":1,"url":"http://a/f.flac","br":999000,"type":"flac","level":"lossless","expi":1200,"fee":1,"freeTrialInfo":null}]}"""

        val trialAttempt = NeteaseStream.parsePlayerUrlResponse(trialJson) as NeteaseStream.Attempt.Ok
        val fullAttempt = NeteaseStream.parsePlayerUrlResponse(fullJson) as NeteaseStream.Attempt.Ok

        assertTrue(trialAttempt.info.trial)
        assertFalse(fullAttempt.info.trial)
        assertEquals(NeteaseLevel.LOSSLESS, fullAttempt.info.level)
    }

    @Test
    fun `parsePlayerUrlResponse flags 30s truncated stream when freeTrialInfo is null`() {
        val truncatedJson = """{"code":200,"data":[{"id":571069109,"url":"http://a/30s.flac","br":1647442,"type":"flac","level":"hires","expi":1200,"time":30024,"freeTrialInfo":null}]}"""
        val genuineShortJson = """{"code":200,"data":[{"id":999,"url":"http://a/short.mp3","br":320000,"type":"mp3","level":"exhigh","expi":1200,"time":30024,"freeTrialInfo":null}]}"""

        val truncatedAttempt = NeteaseStream.parsePlayerUrlResponse(truncatedJson, expectedDurationMs = 323_210L) as NeteaseStream.Attempt.Ok
        val shortInterlude = NeteaseStream.parsePlayerUrlResponse(genuineShortJson, expectedDurationMs = 30_000L) as NeteaseStream.Attempt.Ok

        assertTrue(truncatedAttempt.info.trial)
        assertFalse(shortInterlude.info.trial)
    }

    @Test
    fun `parsePlayerUrlResponse detects 30s audition from size br or trial cdn path`() {
        val cdnTrialJson = """{"code":200,"data":[{"id":2714278532,"url":"http://m802.music.126.net/abc/jd-musicrep-ts/foo.flac","br":1694870,"size":54700000,"type":"flac","level":"hires","expi":1200,"time":258320,"freeTrialInfo":null}]}"""
        val smallSizeJson = """{"code":200,"data":[{"id":2714278532,"url":"http://m802.music.126.net/ymusic/obj/foo.flac","br":1694870,"size":6355000,"type":"flac","level":"hires","expi":1200,"time":258320,"freeTrialInfo":null}]}"""
        val fullFlacJson = """{"code":200,"data":[{"id":2714278532,"url":"http://m802.music.126.net/ymusic/obj/full.flac","br":1694870,"size":54600000,"type":"flac","level":"hires","expi":1200,"time":258320,"freeTrialInfo":null,"freeTrialPrivilege":{"resConsumable":true,"userConsumable":true}}]}"""

        val cdnAttempt = NeteaseStream.parsePlayerUrlResponse(cdnTrialJson, expectedDurationMs = 258_320L) as NeteaseStream.Attempt.Ok
        val smallSizeAttempt = NeteaseStream.parsePlayerUrlResponse(smallSizeJson, expectedDurationMs = 258_320L) as NeteaseStream.Attempt.Ok
        val fullAttempt = NeteaseStream.parsePlayerUrlResponse(fullFlacJson, expectedDurationMs = 258_320L) as NeteaseStream.Attempt.Ok

        assertTrue(cdnAttempt.info.trial)
        assertTrue(smallSizeAttempt.info.trial)
        assertFalse(fullAttempt.info.trial)
    }

    @Test
    fun `encryptEapiParams round-trips with NetEase AES-128-ECB key`() {
        val payload = JSONObject().apply {
            put("ids", "[571069109]")
            put("level", "hires")
            put("encodeType", "flac")
        }
        val hex = NeteaseHttp.encryptEapiParams("/api/song/enhance/player/url/v1", payload)
        val encrypted = ByteArray(hex.length / 2) { idx ->
            hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
        }
        val plain = String(cipher.doFinal(encrypted), Charsets.UTF_8)
        val parts = plain.split("-36cd479b6b5-")
        assertEquals(3, parts.size)
        assertEquals("/api/song/enhance/player/url/v1", parts[0])
        assertEquals(payload.toString(), parts[1])
        assertEquals(32, parts[2].length)
    }
}
