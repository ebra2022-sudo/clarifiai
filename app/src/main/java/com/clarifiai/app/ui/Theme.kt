package com.clarifiai.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Light "liquid glass" palette, after Apple's system colours. Glass surfaces are translucent white over a soft
// tinted backdrop (see Glass.kt), so text uses near-black ink rather than pure black.
val Accent = Color(0xFF0A84FF)
val OnAccent = Color.White
val Ink = Color(0xFF0B1220)
val InkMuted = Color(0xFF5E6676)
val InkFaint = Color(0xFF8E95A3)
/** Translucent white for panels drawn without live glass (dialogs, sheets, menus). */
val Panel = Color(0xF2FFFFFF)
/** Slightly tinted inner panel inside glass cards (banners, chips, menus). */
val PanelStrong = Color(0xB3FFFFFF)
val Hairline = Color(0x1A0B1220)
val Good = Color(0xFF30B158)
val Warn = Color(0xFFFF9500)
val Bad = Color(0xFFFF3B30)

private val ClarityColors = lightColorScheme(
    primary = Accent, onPrimary = OnAccent,
    secondary = Accent, onSecondary = OnAccent,
    background = Color(0xFFF2F4FA), onBackground = Ink,
    surface = Panel, onSurface = Ink,
    surfaceVariant = PanelStrong, onSurfaceVariant = InkMuted,
    surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
    surfaceContainerLow = Panel,
    outline = Hairline, outlineVariant = Hairline, error = Bad,
)

private val ClarityTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
)

@Composable
fun ClarityTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClarityColors, typography = ClarityTypography, content = content)
}
