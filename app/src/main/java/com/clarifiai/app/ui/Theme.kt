package com.clarifiai.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Navy = Color(0xFF0F172A)
val Surface1 = Color(0xFF1E293B)
val Surface2 = Color(0xFF273449)
val Border = Color(0xFF334155)
val Neon = Color(0xFF38BDF8)
val TextPrimary = Color(0xFFE2E8F0)
val TextMuted = Color(0xFF94A3B8)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

private val ClarityColors = darkColorScheme(
    primary = Neon, onPrimary = Navy,
    secondary = Neon, onSecondary = Navy,
    background = Navy, onBackground = TextPrimary,
    surface = Surface1, onSurface = TextPrimary,
    surfaceVariant = Surface2, onSurfaceVariant = TextMuted,
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2,
    outline = Border, error = Bad,
)

private val ClarityTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun ClarityTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClarityColors, typography = ClarityTypography, content = content)
}
