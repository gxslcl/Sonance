package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiLyricIslandTest {

    @Test
    fun focusJson_hasIslandStructureAndPicRef() {
        val json = XiaomiLyricIsland.buildFocusParamJson("晴天", "故事的小黄花")
        assertTrue(json.startsWith("{\"param_v2\":{"))
        assertTrue(json.contains("\"protocol\":3"))
        assertTrue(json.contains("\"param_island\""))
        assertTrue(json.contains("\"bigIslandArea\""))
        assertTrue(json.contains("\"smallIslandArea\""))
        assertTrue(json.contains("\"textInfo\":{\"title\":\"晴天\",\"content\":\"故事的小黄花\"}"))
        assertTrue(json.contains("\"aodTitle\":\"故事的小黄花\""))
        assertTrue(json.contains("miui.focus.pic_cover"))
        assertTrue(json.contains("\"enableFloat\":false"))
        assertTrue(json.endsWith("}}"))
    }

    @Test
    fun focusJson_escapesQuotesBackslashesAndNewlines() {
        val json = XiaomiLyricIsland.buildFocusParamJson("歌\"名\\A", "第一句\n第二句")
        assertTrue(json.contains("歌\\\"名\\\\A"))
        assertTrue(json.contains("第一句\\n第二句"))
        assertFalse("JSON 里不允许出现裸换行", json.contains("\n"))
        assertFalse(json.contains("\r"))
    }

    @Test
    fun escapeJson_coversControlCharacters() {
        val control = 1.toChar().toString()
        assertEquals("\\u0001", XiaomiLyricIsland.escapeJson(control))
        assertEquals("\\\\", XiaomiLyricIsland.escapeJson("\\"))
        assertEquals("\\t", XiaomiLyricIsland.escapeJson("\t"))
        assertEquals("普通歌词", XiaomiLyricIsland.escapeJson("普通歌词"))
    }
}