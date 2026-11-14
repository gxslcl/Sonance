package com.sonance.player.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLrcPromptTest {

    @Test
    fun buildAiLrcPrompt_includesTrackMetadataAndLrcFormatInstructions() {
        val withDuration = buildAiLrcPrompt(
            title = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L
        )
        assertTrue(withDuration.contains("晴天"))
        assertTrue(withDuration.contains("周杰伦"))
        assertTrue(withDuration.contains("4:29"))
        assertTrue(withDuration.contains("[mm:ss.xx]"))
        assertTrue(withDuration.contains("原文和翻译必须放在同一行"))
        assertTrue(withDuration.contains("只留一个空格"))
        assertTrue(withDuration.contains("绝对不要加句号或逗号"))
        assertTrue(
            withDuration.contains(
                "[00:11.20]When I was young I'd listen to the radio 当我年少时，我会聆听收音机"
            )
        )
        assertFalse(withDuration.contains("[mm:ss.xx]中文翻译"))

        val withoutDuration = buildAiLrcPrompt(
            title = "晴天",
            artist = "",
            durationMs = 0L
        )
        assertTrue(withoutDuration.contains("晴天"))
        assertTrue(withoutDuration.contains("[mm:ss.xx]"))
    }
}
