package com.clarifiai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Building blocks that follow Apple's Human Interface Guidelines: segmented controls, grouped rows with Settings-style
// icon tiles, a floating glass toolbar, a compact navigation bar that appears when the large title scrolls away, and
// iOS-style alerts. Minimum touch target is 44 pt throughout.

/** iOS tertiary system fill, the track of a segmented control. */
private val SegmentTrack = Color(0x1F767680)

/** UISegmentedControl: a tinted track with a white sliding thumb. Locked options show a lock and still call [onSelect]. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    locked: (T) -> Boolean = { false },
) {
    BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(SegmentTrack).padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val index = options.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState(segment * index, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "thumb")
        Box(
            Modifier.offset(x = x).width(segment).fillMaxHeight()
                .shadow(3.dp, RoundedCornerShape(9.dp), ambientColor = Color(0x14000000), spotColor = Color(0x29000000))
                .clip(RoundedCornerShape(9.dp)).background(Color.White),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                Row(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(o) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (locked(o)) Icon(Icons.Filled.Lock, null, tint = InkFaint, modifier = Modifier.size(10.dp).padding(end = 1.dp))
                    Text(
                        label(o), fontSize = 13.sp, maxLines = 1,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (locked(o)) InkFaint else Ink,
                    )
                }
            }
        }
    }
}

/** A Settings-app style icon: white symbol on a coloured squircle. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.58f)) }
}

/** Inset hairline between grouped rows, aligned with the row text as in iOS lists. */
@Composable
fun RowSeparator(inset: Dp = 44.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = Hairline)
}

/** iOS 26 floating toolbar: a glass capsule holding the screen's main actions. */
@Composable
fun GlassToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.liquidGlass(GlassShapes.Pill, tint = Color.White.copy(alpha = 0.55f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

/** The inline navigation bar shown once the large title has scrolled away (frosted, with a hairline). */
@Composable
fun CompactNavBar(visible: Boolean, title: String) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Column(Modifier.fillMaxWidth().background(Color(0xF7F7F8FC))) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp), contentAlignment = Alignment.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            }
            HorizontalDivider(thickness = 0.5.dp, color = Hairline)
        }
    }
}

/** iOS scroll-edge effect: content softly fades out under the bars instead of being cut off. */
@Composable
fun EdgeFade(top: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val base = Color(0xFFF5F6FB)
    val colors = listOf(base.copy(alpha = 0.92f), base.copy(alpha = 0f))
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(if (top) colors else colors.reversed())))
}

/** UIAlertController: 270 pt wide, centred title and message, actions split by hairlines; destructive in red. */
@Composable
fun GlassAlert(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = "Cancel",
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(270.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2FAFAFC))) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 19.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(message, fontSize = 13.sp, lineHeight = 18.sp, color = Ink, textAlign = TextAlign.Center)
            }
            HorizontalDivider(thickness = 0.5.dp, color = Color(0x4D3C3C43))
            Row(Modifier.height(44.dp)) {
                AlertAction(cancelLabel, Accent, FontWeight.Normal, onDismiss)
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(Color(0x4D3C3C43)))
                AlertAction(confirmLabel, if (destructive) Bad else Accent, FontWeight.SemiBold, onConfirm)
            }
        }
    }
}

@Composable
private fun RowScope.AlertAction(label: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 17.sp, fontWeight = weight, color = color)
    }
}
