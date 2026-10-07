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

// Apple's text styles (HIG, default sizes): Large Title 34, Title 3 20, Headline 17 semibold, Body 17,
// Subheadline 15, Footnote 13, Caption 12, with the system's tight tracking at large sizes.
private val ClarityTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun ClarityTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClarityColors, typography = ClarityTypography, content = content)
}
