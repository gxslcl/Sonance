package com.sonance.player.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWallLayoutTest {

    private fun sampleAlbums(count: Int): List<WallAlbum> = (0 until count).map { i ->
        WallAlbum(
            key = "alb-$i",
            name = "Album $i",
            artist = "Artist $i",
            fav = i % 2 == 0,
            tracks = emptyList(),
            playContext = emptyList()
        )
    }

    @Test
    fun resolveWallGrid_selectsDensityByFormFactorAndOrientation() {
        val phonePortrait = resolveWallGrid(widthCss = 390f, heightCss = 844f, isTablet = false)
        assertEquals(6, phonePortrait.cols)

        val phoneLandscape = resolveWallGrid(widthCss = 844f, heightCss = 390f, isTablet = false)
        assertEquals(12, phoneLandscape.cols)
        val maxSpanCompact = phoneLandscape.templates.flatMap { tpl -> tpl.s.map { slot -> slot[2] } }.maxOf { it }
        assertTrue("Compact phone landscape should cap tile span <= 4, got $maxSpanCompact", maxSpanCompact <= 4)

        val tabletPortrait = resolveWallGrid(widthCss = 800f, heightCss = 1200f, isTablet = true)
        assertEquals(10, tabletPortrait.cols)

        val tabletWide = resolveWallGrid(widthCss = 1280f, heightCss = 800f, isTablet = true)
        assertEquals(12, tabletWide.cols)
    }

    @Test
    fun buildWallLayout_placesAllAlbumsWithoutOverlapAcrossSizesAndOrientations() {
        val configs = listOf(
            Triple(390f, 844f, false) to 6,
            Triple(844f, 390f, false) to 12,
            Triple(800f, 1200f, true) to 10,
            Triple(1280f, 800f, true) to 12
        )
        val counts = listOf(5, 18, 47, 120)

        for ((cfg, expectedCols) in configs) {
            val (w, h, tablet) = cfg
            for (count in counts) {
                val albums = sampleAlbums(count)
                val layout = buildWallLayout(
                    albums = albums,
                    widthCss = w,
                    heightCss = h,
                    gapCss = 8f,
                    seed = 42,
                    isTablet = tablet
                )
                assertEquals(expectedCols, layout.cols)
                assertEquals(count, layout.tiles.size)
                assertEquals(albums.map { it.key }.toSet(), layout.tiles.map { it.album.key }.toSet())

                // 检查每个瓷砖均落在安全网格横纵边界内，且任意两块互不重叠
                for (i in layout.tiles.indices) {
                    val a = layout.tiles[i]
                    assertTrue("Tile $i x=${a.x} < 0 on $w x $h", a.x >= -0.05f)
                    assertTrue("Tile $i right=${a.x + a.size} > $w", a.x + a.size <= w + 0.05f)
                    assertTrue("Tile $i y=${a.y} < 0", a.y >= -0.05f)
                    assertTrue("Tile $i bottom=${a.y + a.size} > ${layout.contentH}", a.y + a.size <= layout.contentH + 0.05f)

                    for (j in i + 1 until layout.tiles.size) {
                        val b = layout.tiles[j]
                        val overlapX = a.x + 0.5f < b.x + b.size && b.x + 0.5f < a.x + a.size
                        val overlapY = a.y + 0.5f < b.y + b.size && b.y + 0.5f < a.y + a.size
                        assertTrue(
                            "Overlap between tile $i (${a.x},${a.y},${a.size}) and $j (${b.x},${b.y},${b.size})",
                            !(overlapX && overlapY)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun computeWallFocusLayout_preservesTabletOuterMarginsAndFitsCompactLandscape() {
        // 1) 平板横屏：左侧黑胶封套（含 -0.16*s 偏移）必须远离左边框，并与右侧控件留出间距
        val td = 2f
        val tablet = computeWallFocusLayout(
            width = 1152f * td,
            height = 720f * td,
            density = td,
            barsTopPortrait = 24f * td,
            barsTopLandscape = 24f * td,
            bottom = 16f * td,
            vinylEnabled = true,
            isTablet = true
        )
        assertTrue(tablet.isWallWide)
        assertTrue(!tablet.isCompactLandscape)
        assertTrue(
            "Tablet sleeve left (${tablet.sleeveLeft(true) / td}dp) too close to left edge",
            tablet.sleeveLeft(true) >= 40f * td
        )
        assertTrue(
            "Vinyl right (${tablet.visualRight(true) / td}dp) collided with rightPane (${tablet.rightPaneX / td}dp)",
            tablet.visualRight(true) <= tablet.rightPaneX - 18f * td
        )
        assertTrue(
            "Tablet right pane must preserve balanced right margin",
            tablet.rightPaneX + tablet.rightPaneW <= 1152f * td - 40f * td
        )

        // 2) 手机横屏（390dp 高度 + 刘海横置在左侧）：无顶部虚空白，且右侧控件区完整不裁切
        val pd = 3f
        val landscapePhone = computeWallFocusLayout(
            width = 844f * pd,
            height = 390f * pd,
            density = pd,
            barsTopPortrait = 32f * pd,
            barsTopLandscape = 0f,
            bottom = 12f * pd,
            safeLeft = 34f * pd,
            safeRight = 0f,
            vinylEnabled = true,
            isTablet = false
        )
        assertTrue(landscapePhone.isWallWide)
        assertTrue(landscapePhone.isCompactLandscape)
        assertTrue(
            "Landscape sleeve left (${landscapePhone.sleeveLeft(true) / pd}dp) must clear cutout",
            landscapePhone.sleeveLeft(true) >= 48f * pd
        )
        assertTrue(
            "Landscape right column should start near top without huge wasted gap, got ${landscapePhone.rightPaneTop / pd}dp",
            landscapePhone.rightPaneTop <= 20f * pd
        )
        assertTrue(
            "Landscape right pane must have enough vertical height (>=330dp) for lyrics + controls, got ${landscapePhone.rightPaneH / pd}dp",
            landscapePhone.rightPaneH >= 330f * pd
        )

        // 3) 手机竖屏：保持上下结构且控件不溢出底部
        val portraitPhone = computeWallFocusLayout(
            width = 390f * pd,
            height = 844f * pd,
            density = pd,
            barsTopPortrait = 32f * pd,
            barsTopLandscape = 0f,
            bottom = 20f * pd,
            vinylEnabled = true,
            isTablet = false
        )
        assertTrue(!portraitPhone.isWallWide)
        assertTrue(portraitPhone.lyricsTop > portraitPhone.heroY + portraitPhone.heroSize)
        assertTrue(portraitPhone.controlsTop + portraitPhone.controlsHeight <= 844f * pd - 19f * pd)
    }
}
