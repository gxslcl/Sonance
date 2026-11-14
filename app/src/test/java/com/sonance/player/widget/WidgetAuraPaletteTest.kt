package com.sonance.player.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAuraPaletteTest {

    @Test
    fun `neon and hyper-saturated covers are strongly restrained into morandi tones`() {
        val vividColors = listOf(
            0xFFFF2E93.toInt(), // 荧光品红
            0xFFFACC15.toInt(), // 亮柠檬黄
            0xFF00F0FF.toInt(), // 刺眼电青
            0xFFFF1A1A.toInt()  // 纯正大红
        )
        val hsl = FloatArray(3)
        for (raw in vividColors) {
            val dark = WidgetAuraPalette.fromRgb(raw, isLight = false)
            assertFalse(dark.isGrayscale)
            assertTrue("dark appliedSat ${dark.appliedSat} must be <= 0.20", dark.appliedSat <= 0.2001f)
            assertBgChannelsWithinHslCap(dark.bgMid, maxSat = 0.21f, minLum = 0.09f, maxLum = 0.19f, hsl = hsl)

            val light = WidgetAuraPalette.fromRgb(raw, isLight = true)
            assertFalse(light.isGrayscale)
            assertTrue("light appliedSat ${light.appliedSat} must be <= 0.09", light.appliedSat <= 0.0901f)
            assertBgChannelsWithinHslCap(light.bgMid, maxSat = 0.10f, minLum = 0.86f, maxLum = 0.96f, hsl = hsl)
        }
    }

    @Test
    fun `grayscale and ink covers with tiny seal stamp lock to zero saturation`() {
        // 模拟《算云烟》白纸黑字水墨封面 + 右下角 ~2% 小红印章
        val pixels = IntArray(576) { i ->
            when {
                i < 12 -> 0xFFB82A2A.toInt() // ~2% 小印章
                i % 3 == 0 -> 0xFF121212.toInt()
                i % 3 == 1 -> 0xFFF5F5F5.toInt()
                else -> 0xFF6E6E6E.toInt()
            }
        }
        val sampled = WidgetAuraPalette.extractRawColorFromPixels(pixels)
        assertNotNull(sampled)

        val dark = WidgetAuraPalette.fromRgb(sampled, isLight = false)
        assertTrue("Ink cover should lock grayscale", dark.isGrayscale)
        assertEquals(0f, dark.appliedSat, 1e-4f)
        assertNeutralGray(dark.bgTopLeft)
        assertNeutralGray(dark.bgMid)
        assertNeutralGray(dark.bgBottomRight)

        val light = WidgetAuraPalette.fromRgb(sampled, isLight = true)
        assertTrue(light.isGrayscale)
        assertNeutralGray(light.bgMid)
    }

    @Test
    fun `pure white or black cover falls back cleanly to neutral gray without tint bleed`() {
        val allBlack = IntArray(100) { 0xFF050505.toInt() }
        val allWhite = IntArray(100) { 0xFFFAFAFA.toInt() }

        for (pixels in listOf(allBlack, allWhite)) {
            val rgb = WidgetAuraPalette.extractRawColorFromPixels(pixels)
            assertNotNull(rgb)
            val p = WidgetAuraPalette.fromRgb(rgb, isLight = false)
            assertTrue(p.isGrayscale)
            assertNeutralGray(p.bgMid)
        }
    }

    @Test
    fun `null cover produces calm neutral fallback in both dark and light modes`() {
        val dark = WidgetAuraPalette.fromRgb(null, isLight = false)
        val light = WidgetAuraPalette.fromRgb(null, isLight = true)
        assertTrue(dark.isGrayscale)
        assertTrue(light.isGrayscale)
        assertNeutralGray(dark.bgMid)
        assertNeutralGray(light.bgMid)
    }

    @Test
    fun `appearance enum resolves safely with DARK default`() {
        assertEquals(WidgetAppearance.DARK, WidgetAppearance.fromPref(null))
        assertEquals(WidgetAppearance.DARK, WidgetAppearance.fromPref("unknown"))
        assertEquals(WidgetAppearance.LIGHT, WidgetAppearance.fromPref("light"))
        assertEquals(WidgetAppearance.FOLLOW_SKIN, WidgetAppearance.fromPref("FOLLOW_SKIN"))
    }

    private fun assertNeutralGray(argb: Int) {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        assertEquals("Red and green must match on neutral gray ($r,$g,$b)", r, g)
        assertEquals("Green and blue must match on neutral gray ($r,$g,$b)", g, b)
    }

    private fun assertBgChannelsWithinHslCap(
        argb: Int,
        maxSat: Float,
        minLum: Float,
        maxLum: Float,
        hsl: FloatArray
    ) {
        WidgetAuraPalette.rgbToHsl((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, hsl)
        assertTrue("Saturation ${hsl[1]} exceeded $maxSat", hsl[1] <= maxSat)
        assertTrue("Lightness ${hsl[2]} out of [$minLum, $maxLum]", hsl[2] in minLum..maxLum)
    }
}
