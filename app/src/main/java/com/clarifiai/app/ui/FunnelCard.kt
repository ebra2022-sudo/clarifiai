package com.clarifiai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.clarifiai.app.data.FunnelDefinition
import com.clarifiai.app.data.FunnelEvent
import com.clarifiai.app.data.FunnelReport
import com.clarifiai.app.data.ReplayLinks
import com.clarifiai.app.data.cleanPlaceholders
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlin.math.roundToInt

private val FunnelTint = Color(0xFF5E5CE6)

/**
 * The project's funnel for the report's range: sessions through each step, the worst drop-off, and the top reasons
 * people stop there, each with proof links that open the replay at the moment. Shown only for projects with a funnel.
 */
@Composable
fun FunnelCard(
    funnel: FunnelDefinition,
    report: FunnelReport?,
    loading: Boolean,
    error: String?,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.FilterAlt, FunnelTint)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(funnel.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("${funnel.steps.size} steps · why users don't finish", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit funnel", tint = InkMuted) }
        }
        when {
            loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(GlassShapes.Pill), color = FunnelTint, trackColor = Hairline)
                Text("Counting each step and reading the sessions that dropped off…", color = InkMuted, style = MaterialTheme.typography.labelMedium)
            }
            error != null -> {
                Text(error, color = Bad, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Try again", color = Accent) }
            }
            report != null -> FunnelResult(report)
        }
    }
}

@Composable
private fun FunnelResult(report: FunnelReport) {
    val first = report.steps.firstOrNull()?.sessions?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.steps.forEachIndexed { i, step ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text("${i + 1}. ${step.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ink, maxLines = 1)
                    Text("%,d".format(step.sessions), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(GlassShapes.Pill).background(Hairline)) {
                    Box(Modifier.fillMaxWidth(step.sessions.toFloat() / first).height(8.dp).clip(GlassShapes.Pill).background(FunnelTint))
                }
                report.steps.getOrNull(i + 1)?.let { next ->
                    val lost = step.sessions - next.sessions
                    if (lost > 0 && step.sessions > 0) {
                        val worst = report.dropOff?.from == step.name
                        Text("−${"%,d".format(lost)} (${(lost * 100f / step.sessions).roundToInt()}%) didn't reach the next step",
                            style = MaterialTheme.typography.labelMedium, color = if (worst) Bad else InkMuted,
                            fontWeight = if (worst) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        report.since?.let {
            Text("Counted from ${it.take(16)} UTC, as far back as Clarity's 250-session sample reaches for every step.",
                style = MaterialTheme.typography.labelSmall, color = InkFaint)
        }
    }
    val drop = report.dropOff
    if (drop == null) {
        Text("Everyone who started the funnel reached the last step in this period.", color = Good, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(GlassShapes.Control).background(Bad.copy(alpha = 0.08f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("BIGGEST DROP-OFF", style = MaterialTheme.typography.labelSmall, color = Bad, fontWeight = FontWeight.SemiBold)
        Text("${"%,d".format(drop.dropped)} of ${"%,d".format(drop.reached)} (${(drop.rate * 100).roundToInt()}%) reached ${drop.from} but never ${drop.to}.",
            style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
    if (report.reasons.isEmpty()) {
        Text("Couldn't work out reasons from the sessions reviewed.", color = InkMuted, style = MaterialTheme.typography.labelMedium)
        return
    }
    Text("Top reasons users don't complete it", style = MaterialTheme.typography.titleMedium, color = Ink)
    report.reasons.forEachIndexed { i, r ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${i + 1}. ${r.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink)
            r.sessions?.let {
                Text("Seen in $it of ${report.sessions.size} drop-off sessions reviewed", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            }
            val md = rememberMarkdownState(ReplayLinks.deepLink(cleanPlaceholders(r.explanation)))
            Markdown(markdownState = md, colors = markdownColor(text = Ink, codeBackground = PanelStrong, dividerColor = Hairline))
        }
    }
}

/** Define the funnel: a name and Clarity events in order, picked from the events Clarity recorded or typed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FunnelEditorDialog(
    current: FunnelDefinition?,
    events: List<FunnelEvent>,
    eventsLoading: Boolean,
    onSave: (FunnelDefinition?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current?.name ?: "Activation funnel") }
    val steps = remember { mutableStateListOf<String>().apply { addAll(current?.steps.orEmpty()) } }
    var typed by rememberSaveable { mutableStateOf("") }
    val fieldColors = clarityFieldColors()
    fun add(event: String) {
        val e = event.trim()
        if (e.isNotEmpty() && e !in steps && steps.size < 8) steps += e
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(dismissOnClickOutside = false, decorFitsSystemWindows = false),
        containerColor = SheetColor,
        shape = DialogShape,
        title = { Text(if (current == null) "Set up a funnel" else "Edit funnel") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add your app's Clarity events in the order users should reach them. Each report then shows how many " +
                    "sessions get through every step, and the top reasons people stop at the worst one.",
                    style = MaterialTheme.typography.labelMedium, color = InkMuted)
                OutlinedTextField(
                    shape = GlassShapes.Control, value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    label = { Text("Funnel name") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                )
                Column(Modifier.fillMaxWidth().glassControl()) {
                    if (steps.isEmpty()) {
                        Text("No steps yet. Add at least two.", Modifier.padding(14.dp), color = InkFaint, style = MaterialTheme.typography.bodyMedium)
                    }
                    steps.forEachIndexed { i, step ->
                        if (i > 0) RowSeparator(inset = 14.dp)
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. $step", Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            if (i > 0) IconButton(onClick = { steps.add(i - 1, steps.removeAt(i)) }) {
                                Icon(Icons.Outlined.ArrowUpward, "Move $step up", tint = InkMuted, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { steps.removeAt(i) }) {
                                Icon(Icons.Outlined.Close, "Remove $step", tint = InkMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Text("EVENTS IN CLARITY", style = MaterialTheme.typography.labelSmall, color = InkMuted, fontWeight = FontWeight.SemiBold)
                when {
                    eventsLoading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    events.isEmpty() -> Text("Clarity reported no custom events for this project recently. Type event names below.",
                        style = MaterialTheme.typography.labelMedium, color = InkFaint)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        events.filter { it.name !in steps }.forEach { e ->
                            Text("+ ${e.name} · ${"%,d".format(e.sessions)}", color = Accent, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(GlassShapes.Pill).background(Accent.copy(alpha = 0.10f))
                                    .clickable { add(e.name) }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                OutlinedTextField(
                    shape = GlassShapes.Control, value = typed, onValueChange = { typed = it.take(120) }, singleLine = true,
                    label = { Text("Event name") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add(typed); typed = "" }),
                    trailingIcon = { TextButton(onClick = { add(typed); typed = "" }, enabled = typed.isNotBlank()) { Text("Add", color = Accent) } },
                )
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove funnel", color = Bad) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(FunnelDefinition(name, steps.toList())) }, enabled = steps.size >= 2) {
                Text("Save", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = InkMuted) } },
    )
}
