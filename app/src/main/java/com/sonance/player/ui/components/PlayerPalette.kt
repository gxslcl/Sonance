package com.sonance.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.PlayButtonColorMode
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.theme.LocalSkin

@Immutable
data class PlayButtonPalette(val light: Color, val main: Color, val deep: Color, val icon: Color = Color.White)

/** Same-hue gradients, not a fixed rainbow. Neutral covers deliberately stay neutral. */
internal fun playButtonPalette(seed: Color): PlayButtonPalette {
    val hsv = paletteHsv(seed)
    val saturation = hsv[1].coerceAtMost(0.75f)
    val main = ensureReadable(Color.hsv(hsv[0], saturation, 0.76f), Color.White, 4f, lighten = false)
    val value = paletteHsv(main)[2]
    fun tone(shift: Float, value: Float, saturationScale: Float): Color {
        val c = Color.hsv((hsv[0] + shift + 360f) % 360f, saturation * saturationScale, value)
        // Keep all stops readable against the white play glyph, including yellow/white covers.
        return ensureReadable(c, Color.White, 3.5f, lighten = false)
    }
    return PlayButtonPalette(tone(-8f, (value * 1.12f).coerceAtMost(1f), 0.85f), main,
        tone(8f, value * 0.78f, 1f))
}

internal fun originalPlayButtonPalette(icon: Color): PlayButtonPalette =
    PlayButtonPalette(Color(0xFF38BDF8), Color(0xFF6366F1), Color(0xFFEC4899), icon)

/** Shared by main player, library mini player, lyric floating player and wide-screen controls. */
@Composable
internal fun rememberPlayerControlPalette(state: PlayerUiState, song: Song? = null): PlayButtonPalette {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val prefs = remember(context) { Prefs(context) }
    val title = state.title.ifBlank { song?.title.orEmpty() }
    val artist = state.artist.ifBlank { song?.artist.orEmpty() }
    val albumId = if (state.albumId > 0L) state.albumId else song?.albumId ?: state.albumId
    val uri = remember(state.sourceUri, song?.uri) {
        state.sourceUri?.let { android.net.Uri.parse(it) } ?: song?.uri
    }
    val key = remember(title, artist) { OnlineCover.keyOf(title, artist) }
    // Read the observable tint map outside remember so asynchronously decoded colors update all controls.
    val tint = ArtworkLoader.tintOf(albumId, uri, key)
    val colors = if (tint != null) listOf(Color(tint.left), Color(tint.center), Color(tint.right))
        else if (ArtworkLoader.isArtworkGone(albumId, uri, key)) skin.gradientFor(title + artist)
        else emptyList()
    LaunchedEffect(albumId, uri, key) {
        if (state.hasItem || song != null) ArtworkLoader.prefetchTint(context, albumId, uri, key)
    }
    return rememberPlayButtonPalette(prefs.playBtnColorMode, colors, skin.accent, skin.onAccent)
}

internal fun coverPaletteSeed(colors: List<Color>, fallback: Color): Color {
    if (colors.isEmpty()) return fallback
    val average = Color(colors.sumOf { it.red.toDouble() }.toFloat() / colors.size,
        colors.sumOf { it.green.toDouble() }.toFloat() / colors.size,
        colors.sumOf { it.blue.toDouble() }.toFloat() / colors.size)
    val colorful = colors.maxBy { paletteHsv(it)[1] }
    return lerp(average, colorful, 0.25f)
}

@Composable
internal fun rememberPlayButtonPalette(
    mode: PlayButtonColorMode, coverColors: List<Color>, themeColor: Color, originalIcon: Color
): PlayButtonPalette {
    var lastCover by remember { mutableStateOf<Color?>(null) }
    val newCover = if (coverColors.isNotEmpty()) coverPaletteSeed(coverColors, themeColor) else null
    LaunchedEffect(newCover) { if (newCover != null) lastCover = newCover }
    // Async artwork loading must not flash back to the theme between two covers.
    val seed = if (mode == PlayButtonColorMode.COVER) newCover ?: lastCover ?: themeColor else themeColor
    val target = remember(seed, mode, originalIcon) {
        if (mode == PlayButtonColorMode.ORIGINAL) {
            originalPlayButtonPalette(originalIcon)
        } else playButtonPalette(seed)
    }
    val spec = tween<Color>(700, easing = FastOutSlowInEasing)
    val light by animateColorAsState(target.light, spec, label = "playPaletteLight")
    val main by animateColorAsState(target.main, spec, label = "playPaletteMain")
    val deep by animateColorAsState(target.deep, spec, label = "playPaletteDeep")
    val icon by animateColorAsState(target.icon, spec, label = "playPaletteIcon")
    return PlayButtonPalette(light, main, deep, icon)
}

/** Pause freezes the gradient orientation instead of snapping back to 135 degrees. */
@Composable
internal fun rememberButtonOrbit(isPlaying: Boolean): FloatState {
    val angle = remember { mutableFloatStateOf(if (isPlaying) 0f else 135f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.08f)
            angle.floatValue = (angle.floatValue + dt * 360f / 11f) % 360f
            last = now
        }
    }
    return angle
}

/** Related to the backdrop, but with restrained saturation and a readable luminance gap. */
internal fun harmonizedIndicatorColor(background: Color, themeAccent: Color): Color {
    val bgHsv = paletteHsv(background)
    val seed = if (bgHsv[1] < 0.08f) themeAccent else background
    val hsv = paletteHsv(seed)
    val candidate = Color.hsv((hsv[0] + 12f) % 360f, hsv[1].coerceIn(0.18f, 0.48f), 0.78f)
    val lighten = contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)
    return ensureReadable(candidate, background, 3.4f, lighten)
}

/** Pure RGB to HSV: no android.graphics.Color stub calls in JVM tests. */
private fun paletteHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta < 0.00001f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return floatArrayOf((hue + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
}
