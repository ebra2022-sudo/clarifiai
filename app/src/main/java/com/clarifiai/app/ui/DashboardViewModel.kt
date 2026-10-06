package com.clarifiai.app.ui

import android.app.Application
import android.content.Context
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
import com.clarifiai.app.data.Tier
import com.clarifiai.app.data.Timeframe
import com.clarifiai.app.pdf.PdfExportUtility
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
import java.io.File
import java.time.LocalDate

data class PaywallState(val requiredTier: Tier, val reason: String)

data class DashboardUiState(
    val projectId: String = "",
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
)

sealed interface UiEvent {
    data class SharePdf(val file: File) : UiEvent
    data class Message(val text: String) : UiEvent
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("clarity_ai", Context.MODE_PRIVATE)
    private val api = ApiClient(app)
    private val projectIdRegex = Regex("^[A-Za-z0-9_\\-]{1,128}$")

    private val _state = MutableStateFlow(DashboardUiState(projectId = prefs.getString("project_id", "").orEmpty()))
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
        viewModelScope.launch { billing.connectAndLoad() }
        viewModelScope.launch { billing.offers.collect { o -> _state.update { it.copy(offers = o) } } }
    }

    fun setProjectId(v: String) = _state.update { it.copy(projectId = v.trim().take(128)) }

    fun setTimeframe(tf: Timeframe) {
        val allowed = tf.name in _state.value.entitlements.features.allowedTimeframes
        if (!allowed) {
            showPaywall(tf.requiredTier, "${tf.label} reports require the ${tf.requiredTier.label()} plan.")
            return
        }
        _state.update { it.copy(timeframe = tf) }
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) =
        _state.update { it.copy(timeframe = Timeframe.CUSTOM, customStart = start, customEnd = end) }

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
        val pid = s.projectId.trim()
        if (!projectIdRegex.matches(pid)) {
            _state.update { it.copy(error = "Enter a valid Clarity project ID (letters, numbers, - and _).") }
            return
        }
        if (s.timeframe.name !in s.entitlements.features.allowedTimeframes) {
            showPaywall(s.timeframe.requiredTier, "${s.timeframe.label} reports require the ${s.timeframe.requiredTier.label()} plan.")
            return
        }
        if (s.timeframe == Timeframe.CUSTOM && (s.customStart == null || s.customEnd == null)) {
            _state.update { it.copy(error = "Pick a start and end date for the custom range.") }
            return
        }
        prefs.edit().putString("project_id", pid).apply()
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
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val file = PdfExportUtility.generate(
                    getApplication(), s.markdown,
                    PdfExportUtility.ReportMeta(
                        projectId = s.projectId, timeframeLabel = s.reportTimeframeLabel, kpis = s.kpis,
                        notes = s.notes, whiteLabel = s.entitlements.features.whiteLabelPdf,
                    ),
                )
                _events.emit(UiEvent.SharePdf(file))
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Couldn't create the PDF: ${e.message ?: "unknown error"}"))
            } finally {
                _state.update { it.copy(exporting = false) }
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
