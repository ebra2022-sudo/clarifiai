package com.clarifiai.app.ui

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifiai.app.data.Kpis
import com.clarifiai.app.data.ProjectDto
import com.clarifiai.app.data.Tier
import com.clarifiai.app.data.Timeframe
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, activity: Activity) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showDatePicker by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<ProjectDto?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is UiEvent.ReportSaved -> {
                    val result = snackbar.showSnackbar(
                        "PDF saved to your reports.", actionLabel = "Open", duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.openReport(ev.report)
                }
                is UiEvent.Message -> snackbar.showSnackbar(ev.text)
            }
        }
    }

    Scaffold(
        containerColor = Navy,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Clarity AI", fontWeight = FontWeight.Bold)
                        TierBadge(state.entitlements.tier)
                    }
                },
                navigationIcon = {
                    if (state.entitlements.tier != Tier.MAX) {
                        IconButton(onClick = vm::openUpgrade) { Icon(Icons.Outlined.WorkspacePremium, "Upgrade plan", tint = Neon) }
                    }
                },
                actions = {
                    IconButton(onClick = vm::openReports) {
                        BadgedBox(badge = {
                            if (state.savedReports.isNotEmpty()) Badge(containerColor = Neon, contentColor = Navy) {
                                Text("${state.savedReports.size}")
                            }
                        }) { Icon(Icons.Outlined.FolderOpen, "Saved reports", tint = TextPrimary) }
                    }
                    IconButton(onClick = vm::exportPdf, enabled = !state.exporting) {
                        if (state.exporting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Neon)
                        else Box {
                            Icon(Icons.Outlined.PictureAsPdf, "Export PDF report", tint = if (state.entitlements.features.pdfExport) Neon else TextMuted)
                            if (!state.entitlements.features.pdfExport) {
                                Icon(Icons.Filled.Lock, null, tint = Warn, modifier = Modifier.size(11.dp).align(Alignment.BottomEnd))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = TextPrimary),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::runAudit,
                containerColor = Neon, contentColor = Navy,
                icon = {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Navy)
                    else Icon(Icons.Filled.PlayArrow, null)
                },
                text = { Text(if (state.loading) "Analysing..." else "Run audit", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { KpiRow(state.kpis, state.loading) }
            item {
                ControlsCard(
                    projects = state.projects, selected = state.selectedProject, projectsLoaded = state.projectsLoaded,
                    onSelect = vm::selectProject, onConnect = vm::openConnect, onDelete = { projectToDelete = it },
                    onEdit = vm::openEdit, onReconnect = vm::openReconnect,
                    timeframe = state.timeframe, allowed = state.entitlements.features.allowedTimeframes,
                    customLabel = if (state.customStart != null && state.customEnd != null) "${state.customStart} to ${state.customEnd}" else null,
                    onTimeframe = { tf ->
                        if (tf == Timeframe.CUSTOM && Timeframe.CUSTOM.name in state.entitlements.features.allowedTimeframes) showDatePicker = true
                        else vm.setTimeframe(tf)
                    },
                    usageText = state.entitlements.usage.let { u -> u.limit?.let { "${u.used}/$it audits used this month" } },
                )
            }
            item {
                ReportCard(
                    state, onUpgrade = vm::openUpgrade, onRetry = vm::runAudit,
                    onOpenRecordings = { url -> uriHandler.openUri(url) },
                    onEditProject = vm::openEdit,
                )
            }
        }
    }

    if (showDatePicker) {
        DateRangeDialog(
            onDismiss = { showDatePicker = false },
            onConfirm = { s, e -> vm.setCustomRange(s, e); showDatePicker = false },
        )
    }

    if (state.showConnect) {
        ConnectProjectDialog(
            connecting = state.connecting, error = state.connectError,
            onConnect = vm::connectProject, onDismiss = vm::dismissConnect,
            reconnecting = state.reconnectTarget,
        )
    }

    state.editing?.let { p ->
        EditProjectDialog(
            project = p, saving = state.saving, error = state.editError,
            onSave = vm::saveProject, onDismiss = vm::dismissEdit,
        )
    }

    projectToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            containerColor = Surface1,
            icon = { Icon(Icons.Outlined.Delete, null, tint = Bad) },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "Are you sure you want to delete \"${p.name}\"? It's removed from your saved projects and its stored " +
                        "Clarity token is erased. To use it again you'll need to paste a token.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(p.id); projectToDelete = null }) {
                    Text("Delete", color = Bad, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { projectToDelete = null }) { Text("Cancel", color = Neon) } },
        )
    }

    if (state.showReports) {
        SavedReportsSheet(
            reports = state.savedReports,
            onOpen = vm::openReport, onShare = vm::shareReport, onSaveTo = vm::saveReportTo,
            onDelete = vm::deleteReport, onDismiss = vm::dismissReports,
        )
    }

    state.paywall?.let { pw ->
        PaywallSheet(
            currentTier = state.entitlements.tier, paywall = pw, offers = state.offers,
            onSelect = { vm.billing.launch(activity, it) },
            onRestore = vm.billing::restore,
            onDismiss = vm::dismissPaywall,
        )
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    val color = when (tier) { Tier.FREE -> TextMuted; Tier.PRO -> Neon; Tier.MAX -> Warn }
    Text(
        tier.label().uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

// ------------------------------------------------------------------ KPI header

@Composable
private fun KpiRow(kpis: Kpis?, loading: Boolean) {
    if (kpis == null) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(4) { if (loading) ShimmerBox(Modifier.size(width = 168.dp, height = 112.dp), RoundedCornerShape(18.dp)) else KpiCard("-", "-", "Run an audit", TextMuted) }
        }
        return
    }
    val healthColor = when { kpis.healthScore >= 80 -> Good; kpis.healthScore >= 60 -> Warn; else -> Bad }
    val cards = listOf(
        Triple("Rage Clicks", "%,d".format(kpis.rageClickCount), "${kpis.rageSessionPct}% of sessions") to Bad,
        Triple("Dead-Click Drop-offs", "${kpis.deadClickSessionPct}%", "%,d dead clicks".format(kpis.deadClickCount)) to Warn,
        Triple("Session Health", "${kpis.healthScore}", "out of 100") to healthColor,
        Triple("Quick Backs", "${kpis.quickbackSessionPct}%", "of sessions") to Neon,
        Triple("Rapid Scrolls", "${kpis.rapidScrollSessionPct}%", "of sessions") to Neon,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards) { (t, color) -> KpiCard(t.first, t.second, t.third, color) }
    }
}

@Composable
private fun KpiCard(title: String, value: String, sub: String, accent: Color) {
    Card(
        modifier = Modifier.size(width = 168.dp, height = 112.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Text(value, color = accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ------------------------------------------------------------------ controls

@Composable
private fun ControlsCard(
    projects: List<ProjectDto>, selected: ProjectDto?, projectsLoaded: Boolean,
    onSelect: (String) -> Unit, onConnect: () -> Unit, onDelete: (ProjectDto) -> Unit,
    onEdit: (ProjectDto) -> Unit, onReconnect: (ProjectDto) -> Unit,
    timeframe: Timeframe, allowed: List<String>, customLabel: String?,
    onTimeframe: (Timeframe) -> Unit, usageText: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Surface1), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (projectsLoaded && projects.isEmpty()) {
                Text("Connect your Microsoft Clarity project to start auditing.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onConnect, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Navy),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text("Connect Clarity project", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
            } else {
                ProjectPicker(projects, selected, onSelect, onConnect, onDelete, onEdit)
                if (selected?.needsReauth == true) ReconnectBanner(selected, onReconnect)
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    Text(if (timeframe == Timeframe.CUSTOM && customLabel != null) customLabel else timeframe.label, Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Surface2) {
                    Timeframe.entries.forEach { tf ->
                        val locked = tf.name !in allowed
                        DropdownMenuItem(
                            text = { Text(tf.label + if (locked) "  (${tf.requiredTier.label()})" else "", color = if (locked) TextMuted else TextPrimary) },
                            trailingIcon = { if (locked) Icon(Icons.Filled.Lock, null, tint = Warn, modifier = Modifier.size(16.dp)) },
                            onClick = { expanded = false; onTimeframe(tf) },
                        )
                    }
                }
            }
            usageText?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun ProjectPicker(
    projects: List<ProjectDto>, selected: ProjectDto?,
    onSelect: (String) -> Unit, onConnect: () -> Unit, onDelete: (ProjectDto) -> Unit, onEdit: (ProjectDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        ) {
            Column(Modifier.weight(1f)) {
                Text(selected?.name ?: "Choose a project", fontWeight = FontWeight.SemiBold)
                selected?.let {
                    if (it.needsReauth) Text("Needs reconnecting", color = Warn, style = MaterialTheme.typography.labelMedium)
                    else Text(clarityRequestsLabel(it), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Surface2) {
            projects.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name, color = TextPrimary, fontWeight = if (p.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                            if (p.needsReauth) Text("Needs reconnecting", color = Warn, style = MaterialTheme.typography.labelMedium)
                            else Text(clarityRequestsLabel(p), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { expanded = false; onEdit(p) }) {
                                Icon(Icons.Outlined.Edit, "Rename ${p.name}", tint = TextMuted)
                            }
                            IconButton(onClick = { expanded = false; onDelete(p) }) {
                                Icon(Icons.Outlined.Delete, "Delete ${p.name}", tint = TextMuted)
                            }
                        }
                    },
                    onClick = { expanded = false; onSelect(p.id) },
                )
            }
            DropdownMenuItem(
                text = { Text("Connect another project", color = Neon) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = Neon) },
                onClick = { expanded = false; onConnect() },
            )
        }
    }
}

@Composable
private fun ReconnectBanner(project: ProjectDto, onReconnect: (ProjectDto) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Warn.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Warn, modifier = Modifier.size(18.dp))
        Text("Clarity stopped accepting this project's token.", Modifier.weight(1f).padding(horizontal = 8.dp),
            color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onReconnect(project) }) { Text("Reconnect", color = Neon, fontWeight = FontWeight.Bold) }
    }
}

private fun clarityRequestsLabel(p: ProjectDto): String {
    val left = (p.clarityRequests.limit - p.clarityRequests.used).coerceAtLeast(0)
    return "$left of ${p.clarityRequests.limit} Clarity data refreshes left today"
}

// ------------------------------------------------------------------ report

@Composable
private fun ReportCard(
    state: DashboardUiState, onUpgrade: () -> Unit, onRetry: () -> Unit,
    onOpenRecordings: (String) -> Unit, onEditProject: (ProjectDto) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1), border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Neon)
                Text("Executive Backlog", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neon, trackColor = Border)
            state.notes.forEach { Text(it, color = Warn, style = MaterialTheme.typography.labelMedium) }
            when {
                state.loading && state.markdown.isBlank() -> ShimmerReport()
                state.error != null && state.markdown.isBlank() -> ErrorBlock(state.error, onRetry)
                state.markdown.isBlank() -> Text(
                    "Choose a connected Clarity project and a timeframe, then run an audit. You'll get a prioritised hotfix list, friction trends and a sprint roadmap.",
                    color = TextMuted,
                )
                else -> Markdown(
                    content = state.markdown,
                    colors = markdownColor(text = TextPrimary, codeBackground = Surface2, dividerColor = Border),
                    // The renderer's default headings are display-sized; keep them in proportion to the card.
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleLarge,
                        h3 = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
                    ),
                )
            }
            if (state.error != null && state.markdown.isNotBlank()) Text(state.error, color = Bad)
            state.lockedMessage?.let { LockedBanner(it, onUpgrade) }
            if (state.markdown.isNotBlank() && !state.loading) {
                state.selectedProject?.let { DataScopeNote(it, onOpenRecordings, onEditProject) }
            }
        }
    }
}

/**
 * Clarity's export API returns aggregated metrics only, never session recordings or heatmaps. Say so under every
 * report, and send the user to the recordings in Clarity so they can watch the sessions behind each finding.
 */
@Composable
private fun DataScopeNote(project: ProjectDto, onOpenRecordings: (String) -> Unit, onEditProject: (ProjectDto) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Neon, modifier = Modifier.size(18.dp))
            Text("Based on aggregated metrics", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Clarity's Data Export API shares counts like rage and dead clicks, not session recordings or heatmaps, " +
                "so this report hasn't watched any sessions. Check the pages it flags in Clarity's recordings before you fix them.",
            color = TextMuted, style = MaterialTheme.typography.bodyMedium,
        )
        val url = project.recordingsUrl
        if (url != null) {
            TextButton(onClick = { onOpenRecordings(url) }) {
                Icon(Icons.Outlined.OpenInNew, null, tint = Neon, modifier = Modifier.size(16.dp))
                Text("Watch recordings in Clarity", Modifier.padding(start = 6.dp), color = Neon, fontWeight = FontWeight.Bold)
            }
        } else {
            TextButton(onClick = { onEditProject(project) }) {
                Text("Add your Clarity project ID to link recordings", color = Neon)
            }
        }
    }
}

@Composable
private fun LockedBanner(message: String, onUpgrade: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Warn, modifier = Modifier.size(18.dp))
            Text("Strategic Roadmap locked", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
        Text(message, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onUpgrade, colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Navy)) { Text("See plans", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Bad)
            Text(message, Modifier.padding(start = 10.dp), color = TextPrimary)
        }
        TextButton(onClick = onRetry) { Text("Try again", color = Neon) }
    }
}

// ------------------------------------------------------------------ shimmer

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -400f, targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shift",
    )
    val brush = Brush.linearGradient(
        colors = listOf(Border.copy(alpha = 0.35f), Color(0xFF475569).copy(alpha = 0.6f), Border.copy(alpha = 0.35f)),
        start = Offset(shift, 0f), end = Offset(shift + 420f, 140f),
    )
    Box(modifier.clip(shape).background(brush))
}

@Composable
private fun ShimmerReport() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.5f, 1f, 0.92f, 0.8f, 0.45f, 1f, 0.88f, 0.6f, 0.5f, 0.95f, 0.7f).forEachIndexed { i, w ->
            val heading = i == 0 || i == 4 || i == 8
            ShimmerBox(Modifier.fillMaxWidth(w).height(if (heading) 22.dp else 13.dp))
            if (heading) Spacer(Modifier.height(2.dp))
        }
    }
}

// ------------------------------------------------------------------ date range

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState(
        // The backend validates dates in UTC and rejects future end dates, so stop at today's UTC date.
        selectableDates = object : SelectableDates {
            private val todayUtcMillis = LocalDate.now(ZoneOffset.UTC).toEpochDay() * 86_400_000L
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtcMillis
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null && end != null,
                onClick = {
                    val s = Instant.ofEpochMilli(start!!).atZone(ZoneOffset.UTC).toLocalDate()
                    val e = Instant.ofEpochMilli(end!!).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(s, e)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(520.dp))
    }
}
