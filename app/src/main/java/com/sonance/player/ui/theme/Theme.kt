package com.sonance.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private fun Skin.toColorScheme() = if (isLight) lightColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentGlow,
    onPrimaryContainer = accentDim,
    secondary = accentDim,
    onSecondary = onAccent,
    tertiary = accent,
    background = bg,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surface2,
    onSurfaceVariant = textDim,
    surfaceContainerLowest = bg,
    surfaceContainerLow = surface,
    surfaceContainer = surface2,
    surfaceContainerHigh = surface2,
    surfaceContainerHighest = surface3,
    surfaceTint = accent,
    inverseSurface = text,
    inverseOnSurface = bg,
    outline = border2,
    outlineVariant = border,
    error = danger,
    onError = Color.White,
    scrim = Color(0x99000000)
) else darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentGlow,
    onPrimaryContainer = accent,
    secondary = accentDim,
    onSecondary = onAccent,
    tertiary = accent,
    background = bg,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surface2,
    onSurfaceVariant = textDim,
    surfaceContainerLowest = bg,
    surfaceContainerLow = surface,
    surfaceContainer = surface2,
    surfaceContainerHigh = surface2,
    surfaceContainerHighest = surface3,
    surfaceTint = accent,
    inverseSurface = text,
    inverseOnSurface = bg,
    outline = border2,
    outlineVariant = border,
    error = danger,
    onError = Color.White,
    scrim = Color(0xCC000000)
)

private val SonanceTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.8).sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.6).sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.2).sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp, lineHeight = 14.sp),
)

@Composable
fun SonanceTheme(skin: Skin, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = skin.isLight
                isAppearanceLightNavigationBars = skin.isLight
            }
        }
    }
    CompositionLocalProvider(LocalSkin provides skin) {
        MaterialTheme(
            colorScheme = skin.toColorScheme(),
            typography = SonanceTypography,
            content = content
        )
    }
}

/** 主题感知的系统深浅色，供极少数场景参考 */
@Composable
fun systemIsDark(): Boolean = isSystemInDarkTheme()

/** 常用尺寸令牌 */
object Dimens {
    val cardRadius = 14
    val smallRadius = 8
    val railWidth = 88
    val sidebarWidth = 232
    val nowPanelWidth = 344
    val playerBarHeight = 76
    val miniPlayerHeight = 68
    val bottomBarHeight = 64
}
