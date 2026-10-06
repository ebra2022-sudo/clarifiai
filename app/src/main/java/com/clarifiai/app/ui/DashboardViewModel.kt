package com.clarifiai.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.clarifiai.app.billing.BillingManager
import com.clarifiai.app.billing.PlanOffer
import com.clarifiai.app.data.ApiClient
import com.clarifiai.app.data.ApiException
import com.clarifiai.app.data.AuditEvent
import com.clarifiai.app.data.AuditRequestDto
import com.clarifiai.app.data.Entitlements
import com.clarifiai.app.data.Kpis
import com.clarifiai.app.data.ProjectDto
import com.clarifiai.app.data.Tier
import com.clarifiai.app.data.Timeframe
import com.clarifiai.app.data.UpdateProjectDto
import com.clarifiai.app.pdf.PdfExportUtility
import com.clarifiai.app.pdf.ReportStore
import com.clarifiai.app.pdf.SavedReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MAX_CUSTOM_DAYS = 90 // matches the backend's CUSTOM range limit
private val CLARITY_ID_RE = Regex("^[A-Za-z0-9]{4,32}$") // matches the backend's clarity_project_id pattern

data class PaywallState(val requiredTier: Tier, val reason: String)

data class DashboardUiState(
    /** ID of the selected connected Clarity project. */
    val projectId: String = "",
    val projects: List<ProjectDto> = emptyList(),
    val projectsLoaded: Boolean = false,
    val showConnect: Boolean = false,
    /** When set, the connect dialog re-authenticates this saved project instead of adding a new one. */
    val reconnectTarget: ProjectDto? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    /** Project being renamed / edited. */
    val editing: ProjectDto? = null,
    val saving: Boolean = false,
    val editError: String? = null,
    val timeframe: Timeframe = Timeframe.TODAY,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val markdown: String = "",
    val kpis: Kpis? = null,
    val notes: List<String> = emptyList(),
    val lockedMessage: String? = null,
    val error: String? = null,
    val entitlements: Entitlements = Entitlements(),
    val paywall: PaywallState? = null,
    val offers: List<PlanOffer> = emptyList(),
    val reportTimeframeLabel: String = "",
    /** PDFs exported earlier, newest first. */
    val savedReports: List<SavedReport> = emptyList(),
    val showReports: Boolean = false,
) {
    val selectedProject: ProjectDto? get() = projects.firstOrNull { it.id == projectId }
}

sealed interface UiEvent {
    data class ReportSaved(val report: SavedReport) : UiEvent
    data class Message(val text: String) : UiEvent
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("clarity_ai", Context.MODE_PRIVATE)
    private val api = ApiClient(app)
    private val reports = ReportStore(app)
    private val _state = MutableStateFlow(DashboardUiState(projectId = prefs.getString("selected_project", "").orEmpty()))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var auditJob: Job? = null

    val billing = BillingManager(
        context = app,
        scope = viewModelScope,
        onPurchase = ::onPurchase,
        onMessage = { _events.tryEmit(UiEvent.Message(it)) },
    )

    init {
        refreshEntitlements()
        refreshProjects()
        refreshReports()
        viewModelScope.launch { billing.connectAndLoad() }
        viewModelScope.launch { billing.offers.collect { o -> _state.update { it.copy(offers = o) } } }
    }

    // ------------------------------------------------------------ connected Clarity projects

    fun refreshProjects() {
        viewModelScope.launch {
            runCatching { api.projects() }.onSuccess { r ->
                _state.update { st ->
                    // Keep the selection if it still exists, otherwise fall back to the first project.
                    val selected = st.projectId.takeIf { id -> r.projects.any { it.id == id } } ?: r.projects.firstOrNull()?.id.orEmpty()
                    prefs.edit().putString("selected_project", selected).apply()
                    st.copy(projects = r.projects, projectId = selected, projectsLoaded = true)
                }
            }
        }
    }

    fun selectProject(id: String) {
        prefs.edit().putString("selected_project", id).apply()
        _state.update { it.copy(projectId = id) }
    }

    fun openConnect() {
        val s = _state.value
        if (s.projects.size >= s.entitlements.features.maxProjects) {
            val next = if (s.entitlements.tier == Tier.FREE) Tier.PRO else Tier.MAX
            if (s.entitlements.tier != Tier.MAX) {
                showPaywall(next, "Your ${s.entitlements.tier.label()} plan supports ${s.entitlements.features.maxProjects} project(s).")
                return
            }
        }
        _state.update { it.copy(showConnect = true, reconnectTarget = null, connectError = null) }
    }

    /** A saved project whose token stopped working: paste a new token, keeping its name, history and slot. */
    fun openReconnect(p: ProjectDto) = _state.update { it.copy(showConnect = true, reconnectTarget = p, connectError = null) }

    fun dismissConnect() = _state.update {
        if (it.connecting) it else it.copy(showConnect = false, reconnectTarget = null, connectError = null)
    }

    fun connectProject(name: String, token: String, clarityProjectId: String) {
        val target = _state.value.reconnectTarget
        if (_state.value.connecting) return
        if ((target == null && name.isBlank()) || token.isBlank()) {
            _state.update {
                it.copy(connectError = if (target == null) "Enter a name and paste your Clarity API token." else "Paste a new Clarity API token.")
            }
            return
        }
        val clarityId = clarityProjectId.trim().takeIf { it.isNotEmpty() }
        if (clarityId != null && !CLARITY_ID_RE.matches(clarityId)) {
            _state.update { it.copy(connectError = "The Clarity project ID is the short code in your Clarity URL, letters and digits only.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, connectError = null) }
            try {
                val project = if (target != null) api.reconnectProject(target.id, token.trim())
                else api.connectProject(name.trim(), token.trim(), clarityId)
                _state.update { st ->
                    st.copy(
                        projects = st.projects.map { if (it.id == project.id) project else it }
                            .let { list -> if (list.any { it.id == project.id }) list else list + project },
                        showConnect = false, reconnectTarget = null,
                    )
                }
                selectProject(project.id)
                _events.emit(UiEvent.Message(if (target != null) "Reconnected ${project.name}." else "Connected ${project.name}."))
            } catch (e: ApiException) {
                if (e.code == "PROJECT_LIMIT" && e.requiredTier != null) {
                    _state.update { it.copy(showConnect = false) }
                    showPaywall(e.requiredTier, e.message)
                } else {
                    _state.update { it.copy(connectError = e.message) }
                }
            } finally {
                _state.update { it.copy(connecting = false) }
            }
        }
    }

    fun openEdit(p: ProjectDto) = _state.update { it.copy(editing = p, editError = null) }
    fun dismissEdit() = _state.update { if (it.saving) it else it.copy(editing = null, editError = null) }

    /** Rename the project being edited and/or set its Clarity project ID (blank clears it). */
    fun saveProject(name: String, clarityProjectId: String) {
        val p = _state.value.editing ?: return
        if (_state.value.saving) return
        val newName = name.trim()
        val newId = clarityProjectId.trim()
        when {
            newName.isEmpty() -> { _state.update { it.copy(editError = "Project name can't be empty.") }; return }
            newId.isNotEmpty() && !CLARITY_ID_RE.matches(newId) -> {
                _state.update { it.copy(editError = "The Clarity project ID is the short code in your Clarity URL, letters and digits only.") }
                return
            }
        }
        val dto = UpdateProjectDto(
            name = newName.takeIf { it != p.name },
            clarityProjectId = newId.takeIf { it != p.clarityProjectId.orEmpty() },
        )
        if (dto.name == null && dto.clarityProjectId == null) { dismissEdit(); return }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, editError = null) }
            try {
                val updated = api.updateProject(p.id, dto)
                _state.update { st -> st.copy(projects = st.projects.map { if (it.id == updated.id) updated else it }, editing = null) }
                _events.emit(UiEvent.Message("Saved ${updated.name}."))
            } catch (e: ApiException) {
                _state.update { it.copy(editError = e.message) }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                api.deleteProject(id)
                _events.emit(UiEvent.Message("Project deleted."))
            } catch (e: ApiException) {
                _events.emit(UiEvent.Message(e.message))
            }
            refreshProjects()
        }
    }

    // ------------------------------------------------------------ audit

    fun setTimeframe(tf: Timeframe) {
        val allowed = tf.name in _state.value.entitlements.features.allowedTimeframes
        if (!allowed) {
            showPaywall(tf.requiredTier, "${tf.label} reports require the ${tf.requiredTier.label()} plan.")
            return
        }
        _state.update { it.copy(timeframe = tf) }
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_CUSTOM_DAYS) {
            _events.tryEmit(UiEvent.Message("Custom ranges can cover at most $MAX_CUSTOM_DAYS days."))
            return
        }
        _state.update { it.copy(timeframe = Timeframe.CUSTOM, customStart = start, customEnd = end) }
    }

    fun showPaywall(tier: Tier, reason: String) = _state.update { it.copy(paywall = PaywallState(tier, reason)) }
    fun dismissPaywall() = _state.update { it.copy(paywall = null) }
    fun openUpgrade() = showPaywall(if (_state.value.entitlements.tier == Tier.FREE) Tier.PRO else Tier.MAX, "Choose the plan that fits your team.")

    fun refreshEntitlements() {
        viewModelScope.launch {
            runCatching { api.entitlements() }.onSuccess { e -> _state.update { it.copy(entitlements = e) } }
        }
    }

    fun runAudit() {
        val s = _state.value
        if (s.loading) return
        val project = s.selectedProject
        if (project == null) {
            if (s.projects.isEmpty()) openConnect()
            else _events.tryEmit(UiEvent.Message("Choose a project to audit."))
            return
        }
        if (project.needsReauth) {
            openReconnect(project)
            return
        }
        val pid = project.id
        if (s.timeframe.name !in s.entitlements.features.allowedTimeframes) {
            showPaywall(s.timeframe.requiredTier, "${s.timeframe.label} reports require the ${s.timeframe.requiredTier.label()} plan.")
            return
        }
        if (s.timeframe == Timeframe.CUSTOM && (s.customStart == null || s.customEnd == null)) {
            _events.tryEmit(UiEvent.Message("Pick a start and end date for the custom range."))
            return
        }
        val dto = AuditRequestDto(
            projectId = pid,
            timeframe = s.timeframe.name,
            startDate = s.customStart?.takeIf { s.timeframe == Timeframe.CUSTOM }?.toString(),
            endDate = s.customEnd?.takeIf { s.timeframe == Timeframe.CUSTOM }?.toString(),
        )
        auditJob?.cancel()
        auditJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = true, markdown = "", kpis = null, error = null, lockedMessage = null,
                    notes = emptyList(), reportTimeframeLabel = s.timeframe.label)
            }
            val sb = StringBuilder()
            var lastPush = 0L
            try {
                api.streamAudit(dto).collect { ev ->
                    when (ev) {
                        is AuditEvent.Meta -> _state.update { st ->
                            st.copy(
                                kpis = ev.kpis, notes = ev.notes,
                                entitlements = st.entitlements.copy(usage = ev.usage ?: st.entitlements.usage),
                            )
                        }
                        is AuditEvent.Delta -> {
                            sb.append(ev.text)
                            val now = System.currentTimeMillis()
                            if (now - lastPush > 60) { // throttle markdown re-parsing
                                lastPush = now
                                _state.update { it.copy(markdown = sb.toString()) }
                            }
                        }
                        is AuditEvent.Locked -> _state.update { it.copy(lockedMessage = ev.message) }
                        is AuditEvent.Failure -> _state.update { it.copy(error = ev.message) }
                        AuditEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                handleApiError(e)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Something went wrong. Please try again.") }
            } finally {
                _state.update { it.copy(loading = false, markdown = sb.toString()) }
                refreshEntitlements()
                refreshProjects() // updates today's Clarity request count
            }
        }
    }

    private fun handleApiError(e: ApiException) {
        when (e.code) {
            "UPGRADE_REQUIRED", "QUOTA_EXCEEDED", "PROJECT_LIMIT" -> {
                val tier = e.requiredTier
                if (tier != null) showPaywall(tier, e.message)
                else _state.update { it.copy(error = e.message) }
            }
            "RECONNECT_REQUIRED" -> {
                refreshProjects()
                _state.update { it.copy(error = e.message) }
            }
            "PROJECT_NOT_CONNECTED" -> {
                refreshProjects()
                _state.update { it.copy(error = e.message) }
            }
            else -> _state.update { it.copy(error = e.message) }
        }
    }

    fun exportPdf() {
        val s = _state.value
        if (!s.entitlements.features.pdfExport) {
            showPaywall(Tier.PRO, "PDF export is available on Pro and Max.")
            return
        }
        if (s.markdown.isBlank() || s.loading) {
            _events.tryEmit(UiEvent.Message("Run an audit first, then export the report."))
            return
        }
        if (s.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val now = Date()
                val projectName = s.selectedProject?.name ?: s.projectId
                val rendered = PdfExportUtility.generate(
                    getApplication(), s.markdown,
                    PdfExportUtility.ReportMeta(
                        projectName = projectName, timeframeLabel = s.reportTimeframeLabel, kpis = s.kpis,
                        notes = s.notes, whiteLabel = s.entitlements.features.whiteLabelPdf, generatedAt = now,
                    ),
                )
                val report = reports.save(
                    rendered.file,
                    SavedReport(
                        id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now),
                        projectName = projectName, timeframeLabel = s.reportTimeframeLabel, createdAt = now.time,
                        healthScore = s.kpis?.healthScore, pages = rendered.pages,
                    ),
                )
                refreshReports()
                _events.emit(UiEvent.ReportSaved(report))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Couldn't create the PDF: ${e.message ?: "unknown error"}"))
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    // ------------------------------------------------------------ saved reports

    fun refreshReports() {
        viewModelScope.launch { _state.update { it.copy(savedReports = reports.list()) } }
    }

    fun openReports() {
        refreshReports()
        _state.update { it.copy(showReports = true) }
    }

    fun dismissReports() = _state.update { it.copy(showReports = false) }

    fun openReport(r: SavedReport) {
        if (!reports.open(r)) {
            _events.tryEmit(UiEvent.Message("No PDF viewer installed, so the report opens in the share sheet instead."))
            reports.share(r)
        }
    }

    fun shareReport(r: SavedReport) = reports.share(r)

    fun deleteReport(r: SavedReport) {
        viewModelScope.launch {
            reports.delete(r.id)
            refreshReports()
            _events.emit(UiEvent.Message("Report deleted."))
        }
    }

    /** Copies a report to a file the user created with the system "save as" picker. */
    fun saveReportTo(r: SavedReport, target: Uri) {
        viewModelScope.launch {
            try {
                reports.copyTo(r.id, target)
                _events.emit(UiEvent.Message("Saved ${r.fileName}."))
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Couldn't save the report: ${e.message ?: "unknown error"}"))
            }
        }
    }

    private suspend fun onPurchase(p: Purchase): Boolean = try {
        val productId = p.products.firstOrNull() ?: return false
        val ent = api.verifySubscription(p.purchaseToken, productId)
        _state.update { it.copy(entitlements = ent, paywall = null) }
        _events.emit(UiEvent.Message("${ent.tier.label()} plan activated. Thank you!"))
        true
    } catch (e: ApiException) {
        _events.emit(UiEvent.Message("Couldn't verify purchase: ${e.message}"))
        false
    }

    override fun onCleared() {
        billing.close()
        super.onCleared()
    }
}

fun Tier.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
