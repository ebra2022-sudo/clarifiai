@file:OptIn(ExperimentalHazeApi::class)

package com.clarifiai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource

/** Corner radii used across the app, so every surface nests with consistent curvature. */
object GlassShapes {
    val Card = RoundedCornerShape(28.dp)
    val Tile = RoundedCornerShape(24.dp)
    val Control = RoundedCornerShape(18.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/** The backdrop that glass surfaces sample. Provided by [LiquidBackground]; null in previews and dialogs. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Soft, light, multi-tinted backdrop. Glass needs colour behind it to read as glass. */
@Composable
fun LiquidBackground(state: HazeState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(state)
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFF5F8FF), Color(0xFFF0F2F9), Color(0xFFF8F4FB))))
                    glow(Color(0xFF7DB9FF), 0.55f, Offset(size.width * 0.05f, size.height * 0.06f), size.width * 0.85f)
                    glow(Color(0xFFBBA6FF), 0.42f, Offset(size.width * 1.0f, size.height * 0.28f), size.width * 0.75f)
                    glow(Color(0xFF8FE3C8), 0.32f, Offset(size.width * 0.1f, size.height * 0.68f), size.width * 0.85f)
                    glow(Color(0xFFFFC9A3), 0.36f, Offset(size.width * 0.95f, size.height * 0.98f), size.width * 0.8f)
                },
        )
        // The design is always light. Haze picks its dark glass material when the phone is in night mode, which
        // turned the white glass grey, so present a day-mode configuration to everything inside.
        val config = LocalConfiguration.current
        val dayConfig = remember(config) {
            Configuration(config).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO }
        }
        CompositionLocalProvider(LocalConfiguration provides dayConfig, LocalHazeState provides state) { content() }
    }
}

private fun DrawScope.glow(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, radius),
        radius, center,
    )
}

/** Hairline edge that catches light at the top-left, like the rim of a glass pane. */
private val RimBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color(0x140B1220)))

/**
 * Liquid-glass material: refracts and frosts the [LiquidBackground] behind it, with a specular rim.
 * Falls back to frosted white where live glass isn't available (dialogs, older Android versions).
 */
@Composable
fun Modifier.liquidGlass(shape: RoundedCornerShape, tint: Color = Color.White.copy(alpha = 0.42f)): Modifier {
    val haze = LocalHazeState.current
    val style = remember(shape, tint) {
        GlassStyle.regular.then {
            shape(shape)
            tint(tint)
            backgroundColor(Color.White.copy(alpha = 0.18f))
        }
    }
    val material = if (haze != null) Modifier.hazeGlass(HazeInput.Sources(haze), style) else Modifier.background(Panel, shape)
    return this
        .then(material)
        .border(0.8.dp, RimBrush, shape)
        .clip(shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = GlassShapes.Card,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.liquidGlass(shape).padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
}

/** Round glass button for toolbar icons. */
@Composable
fun GlassIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(size)
            .liquidGlass(GlassShapes.Pill, tint = Color.White.copy(alpha = 0.62f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides Ink) { content() } }
}

/** Primary action: a glossy, saturated pill (filled tint with a light top sheen and a bright rim). */
@Composable
fun AccentPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Accent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(GlassShapes.Pill)
            .background(Brush.verticalGradient(listOf(lerpWhite(color, 0.18f), color)))
            .drawBehind {
                // Specular sheen across the top half.
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.5f to Color.White.copy(alpha = 0f)))
            }
            .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.1f))), GlassShapes.Pill)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { CompositionLocalProvider(LocalContentColor provides OnAccent) { content() } }
}

/** Secondary control inside a glass card (pickers, chips): a lighter frosted pill with a hairline. */
fun Modifier.glassControl(shape: RoundedCornerShape = GlassShapes.Control): Modifier =
    this.clip(shape).background(PanelStrong).border(0.8.dp, Hairline, shape)

private fun lerpWhite(c: Color, t: Float) = Color(
    red = c.red + (1f - c.red) * t, green = c.green + (1f - c.green) * t, blue = c.blue + (1f - c.blue) * t, alpha = c.alpha,
)

/** Small icon-sized circle for glass list rows. */
@Composable
fun GlassBadge(color: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center, content = content)
}

/** Bottom sheets and dialogs open in their own window, so they use frosted white instead of live glass. */
val SheetColor = Color(0xF7FBFCFF)
val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
val DialogShape = RoundedCornerShape(30.dp)
