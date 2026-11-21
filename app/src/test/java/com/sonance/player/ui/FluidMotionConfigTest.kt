package com.sonance.player.ui

import com.sonance.player.data.MotionFeel
import com.sonance.player.data.PageTransitionStyle
import com.sonance.player.data.PlayerSheetStyle
import com.sonance.player.data.SettingsBaseline
import com.sonance.player.ui.theme.resolveSheetTargetOpen
import com.sonance.player.ui.theme.scaleMotionDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidMotionConfigTest {

    @Test
    fun motionEnumsParseKnownIdsAndFallbackSafely() {
        assertEquals(MotionFeel.SNAPPY, MotionFeel.fromId("snappy"))
        assertEquals(MotionFeel.GENTLE, MotionFeel.fromId("gentle"))
        assertEquals(MotionFeel.FLUID, MotionFeel.fromId("unknown_id"))
        assertEquals(MotionFeel.FLUID, MotionFeel.fromId(null))

        assertEquals(PlayerSheetStyle.SLIDE_UP, PlayerSheetStyle.fromId("slide_up"))
        assertEquals(PlayerSheetStyle.ZOOM_FADE, PlayerSheetStyle.fromId("zoom_fade"))
        assertEquals(PlayerSheetStyle.DEPTH_SHEET, PlayerSheetStyle.fromId("unknown"))

        assertEquals(PageTransitionStyle.SLIDE, PageTransitionStyle.fromId("slide"))
        assertEquals(PageTransitionStyle.FADE, PageTransitionStyle.fromId("fade"))
        assertEquals(PageTransitionStyle.NONE, PageTransitionStyle.fromId("none"))
        assertEquals(PageTransitionStyle.FLUID_DEPTH, PageTransitionStyle.fromId("missing"))
    }

    @Test
    fun scaleMotionDurationRespectsFeelMultiplierAndMinimumFloor() {
        assertEquals(312, scaleMotionDuration(400, MotionFeel.SNAPPY))
        assertEquals(400, scaleMotionDuration(400, MotionFeel.FLUID))
        assertEquals(500, scaleMotionDuration(400, MotionFeel.GENTLE))
        assertEquals(80, scaleMotionDuration(40, MotionFeel.SNAPPY))
    }

    @Test
    fun resolveSheetTargetOpenPrioritizesFlingVelocityThenDisplacement() {
        // Fast upward swipe opens even from tiny displacement
        assertTrue(resolveSheetTargetOpen(progress = 0.08f, velocityPxPerSec = -900f, currentlyOpen = false))
        // Fast downward swipe closes even near top
        assertFalse(resolveSheetTargetOpen(progress = 0.92f, velocityPxPerSec = 900f, currentlyOpen = true))

        // Slow drag when closed: > 0.22 opens, <= 0.22 stays closed
        assertFalse(resolveSheetTargetOpen(progress = 0.18f, velocityPxPerSec = 0f, currentlyOpen = false))
        assertTrue(resolveSheetTargetOpen(progress = 0.30f, velocityPxPerSec = 0f, currentlyOpen = false))

        // Slow drag when open: < 0.78 closes, >= 0.78 stays open
        assertTrue(resolveSheetTargetOpen(progress = 0.85f, velocityPxPerSec = 0f, currentlyOpen = true))
        assertFalse(resolveSheetTargetOpen(progress = 0.70f, velocityPxPerSec = 0f, currentlyOpen = true))
    }

    @Test
    fun settingsBaselineIncludesAllFluidMotionKeys() {
        val keys = SettingsBaseline.specs.associate { it.key to it.type }
        assertEquals(SettingsBaseline.Type.STRING, keys["motion_feel"])
        assertEquals(SettingsBaseline.Type.STRING, keys["player_sheet_style"])
        assertEquals(SettingsBaseline.Type.STRING, keys["page_transition_style"])
        assertEquals(SettingsBaseline.Type.BOOLEAN, keys["touch_spring_feedback"])
        assertEquals(SettingsBaseline.Type.INT, keys["player_cover_corner_dp"])
        assertEquals(SettingsBaseline.Type.BOOLEAN, keys["mini_swipe_up_to_open"])
    }
}
