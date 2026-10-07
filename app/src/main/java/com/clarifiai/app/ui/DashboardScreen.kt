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
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.UnfoldMore
import com.clarifiai.app.data.RecordingSample
import com.clarifiai.app.data.SessionJourney
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
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

    val haze = rememberHazeState()
    val listState = rememberLazyListState()
    val titleScrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 140 } }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiquidBackground(haze) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 76.dp)) },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // No horizontal padding here: the KPI carousel runs edge to edge; other items pad themselves.
                    contentPadding = PaddingValues(top = topInset + 8.dp, bottom = bottomInset + 112.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        LargeTitleHeader(
                            tier = state.entitlements.tier, savedReports = state.savedReports.size,
                            onReports = vm::openReports, onUpgrade = vm::openUpgrade,
                        )
                    }
                    item { KpiRow(state.kpis, state.loading) }
                    item {
                        ControlsCard(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            projects = state.projects, selected = state.selectedProject, projectsLoaded = state.projectsLoaded,
                            onSelect = vm::selectProject, onConnect = vm::openConnect, onDelete = { projectToDelete = it },
                            onEdit = vm::openEdit, onReconnect = vm::openReconnect,
                            timeframe = state.timeframe, allowed = state.entitlements.features.allowedTimeframes,
                            customLabel = if (state.customStart != null && state.customEnd != null) "${state.customStart} → ${state.customEnd}" else null,
                            onTimeframe = { tf ->
                                if (tf == Timeframe.CUSTOM && Timeframe.CUSTOM.name in state.entitlements.features.allowedTimeframes) showDatePicker = true
                                else vm.setTimeframe(tf)
                            },
                            usageText = state.entitlements.usage.let { u -> u.limit?.let { "${u.used} of $it audits used this month" } },
                            historyNote = historyNote(state.timeframe, state.selectedProject),
                        )
                    }
                    item {
                        ReportCard(
                            state, modifier = Modifier.padding(horizontal = 16.dp), onUpgrade = vm::openUpgrade, onRetry = vm::runAudit,
                            onOpenRecordings = { url -> uriHandler.openUri(url) },
                            onEditProject = vm::openEdit,
                        )
                    }
                    state.reportSessions?.let { sample ->
                        item {
                            SessionsCard(sample, Modifier.padding(horizontal = 16.dp), onWatch = { url -> uriHandler.openUri(url) })
                        }
                    }
                }

                // iOS scroll-edge effect and the inline title once the large title has scrolled away.
                EdgeFade(top = true, height = topInset + 12.dp, modifier = Modifier.align(Alignment.TopCenter))
                EdgeFade(top = false, height = bottomInset + 110.dp, modifier = Modifier.align(Alignment.BottomCenter))
                Box(Modifier.align(Alignment.TopCenter)) { CompactNavBar(titleScrolledAway, "Clarity AI") }

                GlassToolbar(
                    Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 12.dp).fillMaxWidth(),
                ) {
                    GlassIconButton(onClick = vm::exportPdf, enabled = !state.exporting, size = 48.dp) {
                        if (state.exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                        else Box {
                            Icon(Icons.Outlined.PictureAsPdf, "Export PDF report", modifier = Modifier.size(22.dp),
                                tint = if (state.entitlements.features.pdfExport) Accent else InkFaint)
                            if (!state.entitlements.features.pdfExport) {
                                Icon(Icons.Filled.Lock, null, tint = Warn, modifier = Modifier.size(11.dp).align(Alignment.BottomEnd))
                            }
                        }
                    }
                    AccentPillButton(onClick = vm::runAudit, modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp)) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OnAccent)
                        else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Text(if (state.loading) "Analysing…" else "Run audit", Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
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
        GlassAlert(
            title = "Delete \"${p.name}\"?",
            message = "It's removed from your saved projects and its stored Clarity token is erased. To use it again you'll need to paste a token.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { vm.deleteProject(p.id); projectToDelete = null },
            onDismiss = { projectToDelete = null },
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

/** iOS large title with today's date as an eyebrow; actions sit as glass buttons on the trailing edge. */
@Composable
private fun LargeTitleHeader(tier: Tier, savedReports: Int, onReports: () -> Unit, onUpgrade: () -> Unit) {
    val today = remember { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(today.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = InkMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clarity AI", style = MaterialTheme.typography.headlineLarge, color = Ink)
                Spacer(Modifier.size(8.dp))
                TierBadge(tier)
            }
        }
        if (tier != Tier.MAX) {
            GlassIconButton(onClick = onUpgrade) {
                Icon(Icons.Outlined.WorkspacePremium, "Upgrade plan", tint = Accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(10.dp))
        }
        GlassIconButton(onClick = onReports) {
            BadgedBox(badge = {
                if (savedReports > 0) Badge(containerColor = Accent, contentColor = OnAccent) { Text("$savedReports") }
            }) { Icon(Icons.Outlined.FolderOpen, "Saved reports", modifier = Modifier.size(22.dp)) }
        }
    }
}

/** The real recordings behind the report, as an iOS grouped list: patterns first, then each session with its replay. */
@Composable
private fun SessionsCard(sample: RecordingSample, modifier: Modifier = Modifier, onWatch: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val sessions = if (showAll) sample.sessions else sample.sessions.take(4)
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.PlayCircle, Accent)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Sessions reviewed", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("${sample.sampledSessions} sampled from Clarity · ${sample.sessions.size} read in full",
                    style = MaterialTheme.typography.labelMedium, color = InkMuted)
            }
        }
        sample.patterns?.let { p ->
            val chips = p.mostDeadTapped.take(3).map { "Dead taps · ${it.element} (${it.sessions})" to Warn } +
                p.mostRageTapped.take(2).map { "Rage taps · ${it.element} (${it.sessions})" to Bad }
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(chips) { (text, color) -> Chip(text, color) }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassControl()) {
            sessions.forEachIndexed { i, s ->
                if (i > 0) RowSeparator(inset = 52.dp)
                SessionRow(s, onWatch)
            }
        }
        if (sample.sessions.size > 4) {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (showAll) "Show fewer" else "Show all ${sample.sessions.size}", color = Accent)
            }
        }
    }
}

@Composable
private fun SessionRow(s: SessionJourney, onWatch: (String) -> Unit) {
    val (icon, color) = when {
        "rage" in s.selectedFor -> Icons.Outlined.Bolt to Bad
        "dead" in s.selectedFor || "quick" in s.selectedFor -> Icons.Outlined.TouchApp to Warn
        "minute" in s.selectedFor -> Icons.Outlined.Logout to Accent
        else -> Icons.Outlined.Insights to Good
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(enabled = s.replay != null) { s.replay?.let(onWatch) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color, size = 30.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                s.selectedFor.replaceFirstChar { it.uppercase() } + (s.shortActiveTime?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink,
            )
            Text(s.keyMoments(2).joinToString("  ·  ").ifEmpty { s.started.orEmpty() },
                style = MaterialTheme.typography.labelMedium, color = InkMuted, maxLines = 2)
        }
        if (s.replay != null) Icon(Icons.Outlined.PlayCircle, "Watch replay", tint = Accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    val color = when (tier) { Tier.FREE -> InkMuted; Tier.PRO -> Accent; Tier.MAX -> Color(0xFFB7791F) }
    Text(
        tier.label().uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

// ------------------------------------------------------------------ KPI header

@Composable
private fun KpiRow(kpis: Kpis?, loading: Boolean) {
    if (kpis == null) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(4) { if (loading) ShimmerBox(Modifier.size(width = 164.dp, height = 116.dp), GlassShapes.Tile) else KpiCard("-", "-", "Run an audit", InkFaint) }
        }
        return
    }
    // Product view first: how many people came, how engaged they were, how deep they went, how many struggled.
    val healthColor = when { kpis.healthScore >= 80 -> Good; kpis.healthScore >= 60 -> Warn; else -> Bad }
    val frustratedColor = when { kpis.frustratedSessionPct >= 10 -> Bad; kpis.frustratedSessionPct >= 3 -> Warn; else -> Good }
    val cards = listOf(
        Triple("Sessions", "%,d".format(kpis.totalSessions), "%,d users".format(kpis.totalUsers)) to Ink,
        Triple("Engaged time", duration(kpis.engagedSeconds), "of ${duration(kpis.sessionSeconds)} per session") to Accent,
        Triple("Views per session", "%.1f".format(kpis.viewsPerSession), "screens or pages") to Accent,
        Triple("Frustrated sessions", "${kpis.frustratedSessionPct}%", "hit rage or dead taps") to frustratedColor,
        Triple("Session health", "${kpis.healthScore}", "out of 100") to healthColor,
        Triple("Quick backs", "${kpis.quickbackSessionPct}%", "left a screen at once") to InkMuted,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(cards) { (t, color) -> KpiCard(t.first, t.second, t.third, color) }
    }
}

@Composable
private fun KpiCard(title: String, value: String, sub: String, accent: Color) {
    GlassCard(
        modifier = Modifier.size(width = 164.dp, height = 116.dp),
        shape = GlassShapes.Tile,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = InkMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(value, color = accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
        Text(sub, color = InkMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

// ------------------------------------------------------------------ controls

@Composable
private fun ControlsCard(
    modifier: Modifier = Modifier,
    projects: List<ProjectDto>, selected: ProjectDto?, projectsLoaded: Boolean,
    onSelect: (String) -> Unit, onConnect: () -> Unit, onDelete: (ProjectDto) -> Unit,
    onEdit: (ProjectDto) -> Unit, onReconnect: (ProjectDto) -> Unit,
    timeframe: Timeframe, allowed: List<String>, customLabel: String?,
    onTimeframe: (Timeframe) -> Unit, usageText: String?, historyNote: String? = null,
) {
    GlassCard(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (projectsLoaded && projects.isEmpty()) {
                Text("Connect your Microsoft Clarity project to start auditing.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                AccentPillButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null)
                    Text("Connect Clarity project", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                ProjectPicker(projects, selected, onSelect, onConnect, onDelete, onEdit)
                if (selected?.needsReauth == true) ReconnectBanner(selected, onReconnect)
            }
            // Timeframe as a UISegmentedControl; locked ranges show a lock and open the plans sheet.
            Text("TIMEFRAME", style = MaterialTheme.typography.labelMedium, color = InkMuted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            SegmentedControl(
                options = Timeframe.entries, selected = timeframe,
                label = { when (it) {
                    Timeframe.TODAY -> "Today"; Timeframe.LAST_3_DAYS -> "3D"; Timeframe.LAST_WEEK -> "7D"
                    Timeframe.LAST_MONTH -> "30D"; Timeframe.CUSTOM -> "Custom"
                } },
                locked = { it.name !in allowed }, onSelect = onTimeframe,
            )
            if (timeframe == Timeframe.CUSTOM && customLabel != null) {
                Row(
                    Modifier.fillMaxWidth().glassControl().clickable { onTimeframe(Timeframe.CUSTOM) }.heightIn(min = 44.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Outlined.CalendarMonth, Color(0xFFFF2D55), size = 28.dp)
                    Text(customLabel, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Ink)
                    Text("Change", style = MaterialTheme.typography.bodyMedium, color = Accent)
                }
            }
            historyNote?.let {
                Row(Modifier.fillMaxWidth().clip(GlassShapes.Control).background(Accent.copy(alpha = 0.08f)).padding(12.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Text(it, Modifier.padding(start = 8.dp), color = Ink, style = MaterialTheme.typography.labelMedium)
                }
            }
            usageText?.let { Text(it, color = InkMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp)) }
    }
}

/** A frosted row-shaped control inside a glass card, used for the pickers. */
@Composable
private fun GlassControl(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassControl().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun ProjectPicker(
    projects: List<ProjectDto>, selected: ProjectDto?,
    onSelect: (String) -> Unit, onConnect: () -> Unit, onDelete: (ProjectDto) -> Unit, onEdit: (ProjectDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassControl(onClick = { expanded = true }) {
            IconTile(Icons.Outlined.Insights, Accent)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(selected?.name ?: "Choose a project", color = Ink, style = MaterialTheme.typography.titleMedium)
                selected?.let {
                    if (it.needsReauth) Text("Needs reconnecting", color = Warn, style = MaterialTheme.typography.labelMedium)
                    else Text(clarityRequestsLabel(it), color = InkMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
            Icon(Icons.Outlined.UnfoldMore, "Change project", tint = InkFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Panel, shape = GlassShapes.Control) {
            projects.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name, color = Ink, fontWeight = if (p.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                            if (p.needsReauth) Text("Needs reconnecting", color = Warn, style = MaterialTheme.typography.labelMedium)
                            else Text(clarityRequestsLabel(p), color = InkMuted, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { expanded = false; onEdit(p) }) {
                                Icon(Icons.Outlined.Edit, "Rename ${p.name}", tint = InkMuted)
                            }
                            IconButton(onClick = { expanded = false; onDelete(p) }) {
                                Icon(Icons.Outlined.Delete, "Delete ${p.name}", tint = InkMuted)
                            }
                        }
                    },
                    onClick = { expanded = false; onSelect(p.id) },
                )
            }
            DropdownMenuItem(
                text = { Text("Connect another project", color = Accent) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = Accent) },
                onClick = { expanded = false; onConnect() },
            )
        }
    }
}

@Composable
private fun ReconnectBanner(project: ProjectDto, onReconnect: (ProjectDto) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(GlassShapes.Control).background(Warn.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Warn, modifier = Modifier.size(18.dp))
        Text("Clarity stopped accepting this project's token.", Modifier.weight(1f).padding(horizontal = 8.dp),
            color = Ink, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onReconnect(project) }) { Text("Reconnect", color = Accent, fontWeight = FontWeight.Bold) }
    }
}

/** Explains, before running, what a long or custom range is built from. */
private fun historyNote(tf: Timeframe, project: ProjectDto?): String? {
    if (project == null || tf == Timeframe.TODAY || tf == Timeframe.LAST_3_DAYS) return null
    return "Totals, daily trends, audiences and session recordings come from Clarity for the whole range. " +
        "Page-level friction detail covers the latest 3 days, and recordings older than about 30 days may no longer exist in Clarity."
}

private fun duration(seconds: Int): String = if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)

private fun clarityRequestsLabel(p: ProjectDto): String {
    val left = (p.clarityRequests.limit - p.clarityRequests.used).coerceAtLeast(0)
    return "$left of ${p.clarityRequests.limit} Clarity data refreshes left today"
}

// ------------------------------------------------------------------ report

@Composable
private fun ReportCard(
    state: DashboardUiState, modifier: Modifier = Modifier, onUpgrade: () -> Unit, onRetry: () -> Unit,
    onOpenRecordings: (String) -> Unit, onEditProject: (ProjectDto) -> Unit,
) {
    // Parsing runs off the main thread. Without retainState the renderer shows an empty box while each streamed chunk
    // is re-parsed, which made the report flash blank many times a second during an audit.
    val markdownState = rememberMarkdownState(state.markdown, retainState = true)
    GlassCard(modifier = modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.AutoAwesome, Color(0xFF5E5CE6))
                Text("Executive Backlog", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, color = Ink)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(GlassShapes.Pill), color = Accent, trackColor = Hairline)
            if (state.reportDaysCovered > 0 || state.reportRecordings > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.reportDaysCovered > 0) {
                        val full = state.reportDaysCovered >= state.reportRequestedDays
                        Chip("Covers ${state.reportDaysCovered} of ${state.reportRequestedDays} day${if (state.reportRequestedDays == 1) "" else "s"}",
                            if (full) Good else Warn)
                    }
                    if (state.reportRecordings > 0) Chip("${state.reportRecordings} session recordings reviewed", Accent)
                }
            }
            // Caveats only; the recordings count is shown as a chip above.
            state.notes.filterNot { it.startsWith("Reviewed ") }.forEach { Text(it, color = Warn, style = MaterialTheme.typography.labelMedium) }
            when {
                state.loading && state.markdown.isBlank() -> ShimmerReport()
                state.error != null && state.markdown.isBlank() -> ErrorBlock(state.error, onRetry)
                state.markdown.isBlank() -> Text(
                    "Choose a connected Clarity project and a timeframe, then run an audit. You'll get a prioritised hotfix list, friction trends and a sprint roadmap.",
                    color = InkMuted,
                )
                else -> Markdown(
                    markdownState = markdownState,
                    colors = markdownColor(text = Ink, codeBackground = PanelStrong, dividerColor = Hairline),
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
                state.selectedProject?.let { DataScopeNote(it, state.reportRecordings, onOpenRecordings, onEditProject) }
            }
    }
}

/**
 * Clarity's export API returns aggregated metrics only, never session recordings or heatmaps. Say so under every
 * report, and send the user to the recordings in Clarity so they can watch the sessions behind each finding.
 */
@Composable
private fun DataScopeNote(project: ProjectDto, recordings: Int, onOpenRecordings: (String) -> Unit, onEditProject: (ProjectDto) -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassControl().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
            Text(if (recordings > 0) "Metrics + $recordings real sessions" else "Based on aggregated metrics",
                Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Text(
            if (recordings > 0) "ClarifiAI pulled $recordings session recordings from Clarity (including sessions with rage and " +
                "dead taps) and read what each user saw and tapped. Session links in the report open the replay."
            else "Clarity didn't return session recordings for this period, so this report is based on aggregated metrics. " +
                "Heatmaps aren't available through Clarity's API.",
            color = InkMuted, style = MaterialTheme.typography.bodyMedium,
        )
        val url = project.recordingsUrl
        if (url != null) {
            TextButton(onClick = { onOpenRecordings(url) }) {
                Icon(Icons.Outlined.OpenInNew, null, tint = Accent, modifier = Modifier.size(16.dp))
                Text("Watch recordings in Clarity", Modifier.padding(start = 6.dp), color = Accent, fontWeight = FontWeight.Bold)
            }
        } else {
            TextButton(onClick = { onEditProject(project) }) {
                Text("Add your Clarity project ID to link recordings", color = Accent)
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(GlassShapes.Pill).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun LockedBanner(message: String, onUpgrade: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassControl().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Warn, modifier = Modifier.size(18.dp))
            Text("Strategic Roadmap locked", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
        Text(message, color = InkMuted, style = MaterialTheme.typography.bodyMedium)
        AccentPillButton(onClick = onUpgrade) { Text("See plans", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Bad)
            Text(message, Modifier.padding(start = 10.dp), color = Ink)
        }
        TextButton(onClick = onRetry) { Text("Try again", color = Accent) }
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
        colors = listOf(Color(0x140B1220), Color.White.copy(alpha = 0.75f), Color(0x140B1220)),
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
