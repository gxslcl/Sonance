package com.sonance.player.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizingTest {

    @Test
    fun largeWidget_coverGrowsWhenWidgetIsResizedOrPlacedOnTablet() {
        val basePhone = WidgetSizing.computeCoverDp(WidgetCoverClass.LARGE, 124f, isFourCol = false, 340, 310)
        val tallPhone = WidgetSizing.computeCoverDp(WidgetCoverClass.LARGE, 124f, isFourCol = false, 360, 380)
        val tabletLarge = WidgetSizing.computeCoverDp(WidgetCoverClass.LARGE, 124f, isFourCol = false, 460, 440)

        assertTrue("Expected base cover >= 124dp, got $basePhone", basePhone >= 124f)
        assertTrue("Expected tall phone cover ($tallPhone) > base ($basePhone) + 40dp", tallPhone >= basePhone + 40f)
        assertTrue("Expected tablet cover ($tabletLarge) > tall phone ($tallPhone) + 30dp", tabletLarge >= tallPhone + 30f)
        assertTrue("Expected tablet cover <= 280dp, got $tabletLarge", tabletLarge <= 280f)

        val base4 = WidgetSizing.computeCoverDp(WidgetCoverClass.LARGE, 114f, isFourCol = true, 280, 296)
        val big4 = WidgetSizing.computeCoverDp(WidgetCoverClass.LARGE, 114f, isFourCol = true, 360, 380)
        assertTrue("4-col large widget should scale up when enlarged: $base4 -> $big4", big4 >= base4 + 70f)
    }

    @Test
    fun cardWideAndBar_coverScalesWithinSafeBounds() {
        val cardSmall = WidgetSizing.computeCoverDp(WidgetCoverClass.CARD, 60f, isFourCol = false, 180, 152)
        val cardStretched = WidgetSizing.computeCoverDp(WidgetCoverClass.CARD, 60f, isFourCol = false, 280, 280)
        assertEquals(60f, cardSmall, 0.01f)
        assertTrue("Stretched CARD cover should grow well past 120dp, got $cardStretched", cardStretched >= 150f)

        val wideSmall = WidgetSizing.computeCoverDp(WidgetCoverClass.WIDE, 112f, isFourCol = false, 320, 150)
        val wideTablet = WidgetSizing.computeCoverDp(WidgetCoverClass.WIDE, 112f, isFourCol = false, 480, 240)
        assertTrue("Wide widget cover on tablet ($wideTablet) should grow beyond $wideSmall", wideTablet >= wideSmall + 50f)

        val barNormal = WidgetSizing.computeCoverDp(WidgetCoverClass.BAR, 52f, isFourCol = false, 340, 72)
        val barTall = WidgetSizing.computeCoverDp(WidgetCoverClass.BAR, 52f, isFourCol = false, 460, 110)
        assertTrue("Tall BAR widget cover ($barTall) should grow above base ($barNormal)", barTall >= barNormal + 20f)
    }

    @Test
    fun sizeSelection_handlesPortraitWideShapesAndLandscapeOrientation() {
        val candidates = listOf(340f to 72f, 480f to 64f)
        assertEquals(340 to 72, WidgetSizing.pickSizeFromPairs(isLandscape = false, candidates))
        assertEquals(480 to 64, WidgetSizing.pickSizeFromPairs(isLandscape = true, candidates))

        val portrait = WidgetSizing.pickOrientationSize(
            isLandscape = false,
            minW = 340, maxW = 440, minH = 220, maxH = 380,
            defaultW = 340, defaultH = 310
        )
        assertEquals(340 to 380, portrait)

        val landscape = WidgetSizing.pickOrientationSize(
            isLandscape = true,
            minW = 340, maxW = 440, minH = 220, maxH = 380,
            defaultW = 340, defaultH = 310
        )
        assertEquals(440 to 220, landscape)
    }

    @Test
    fun coverPx_scalesWithDensityAndCapsUnderBinderLimit() {
        val smallPx = WidgetSizing.computeCoverPx(124f, basePx = 288, density = 3f)
        val bigPx = WidgetSizing.computeCoverPx(240f, basePx = 288, density = 3f)
        assertEquals(372, smallPx)
        assertEquals(WidgetSizing.MAX_COVER_PX, bigPx)
    }
}
