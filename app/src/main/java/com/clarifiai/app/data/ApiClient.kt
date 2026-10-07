package com.clarifiai.app.data

import android.content.Context
import com.clarifiai.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

@Serializable enum class Tier { FREE, PRO, MAX }

enum class Timeframe(val label: String, val requiredTier: Tier) {
    TODAY("Today", Tier.FREE),
    LAST_3_DAYS("Last 3 days", Tier.FREE),
    LAST_WEEK("Last 7 days", Tier.PRO),
    LAST_MONTH("Last 30 days", Tier.MAX),
    CUSTOM("Custom range", Tier.MAX),
}

@Serializable
data class AuditRequestDto(
    @SerialName("project_id") val projectId: String,
    val timeframe: String,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
data class Kpis(
    @SerialName("health_score") val healthScore: Int = 0,
    @SerialName("total_sessions") val totalSessions: Int = 0,
    @SerialName("rage_click_count") val rageClickCount: Int = 0,
    @SerialName("rage_session_pct") val rageSessionPct: Double = 0.0,
    @SerialName("dead_click_count") val deadClickCount: Int = 0,
    @SerialName("dead_click_session_pct") val deadClickSessionPct: Double = 0.0,
    @SerialName("quickback_session_pct") val quickbackSessionPct: Double = 0.0,
    @SerialName("rapid_scroll_session_pct") val rapidScrollSessionPct: Double = 0.0,
    @SerialName("script_error_session_pct") val scriptErrorSessionPct: Double = 0.0,
    @SerialName("error_click_count") val errorClickCount: Int = 0,
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("views_per_session") val viewsPerSession: Double = 0.0,
    @SerialName("engaged_seconds") val engagedSeconds: Int = 0,
    @SerialName("session_seconds") val sessionSeconds: Int = 0,
    @SerialName("frustrated_session_pct") val frustratedSessionPct: Double = 0.0,
)

@Serializable
data class Usage(val used: Int = 0, val limit: Int? = null, val period: String = "")

@Serializable
data class Features(
    @SerialName("allowed_timeframes") val allowedTimeframes: List<String> = listOf("TODAY", "LAST_3_DAYS"),
    @SerialName("pdf_export") val pdfExport: Boolean = false,
    @SerialName("white_label_pdf") val whiteLabelPdf: Boolean = false,
    @SerialName("top_elements") val topElements: Int = 3,
    @SerialName("device_breakdown") val deviceBreakdown: Boolean = false,
    @SerialName("full_roadmap") val fullRoadmap: Boolean = false,
    @SerialName("max_projects") val maxProjects: Int = 1,
)

@Serializable
data class Entitlements(
    val tier: Tier = Tier.FREE,
    @SerialName("expires_at") val expiresAt: String? = null,
    val usage: Usage = Usage(),
    val features: Features = Features(),
)

@Serializable
data class VerifyRequestDto(
    @SerialName("purchase_token") val purchaseToken: String,
    @SerialName("product_id") val productId: String,
)

@Serializable
data class ClarityRequests(val used: Int = 0, val limit: Int = 0)

/** A Clarity project the user connected. The token stays on the server; the app only sees this summary. */
@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String = "",
    /** "active", or "needs_reauth" when Clarity stopped accepting the saved token. */
    val status: String = "active",
    /** Clarity's own project ID, used only to open recordings in the Clarity dashboard. */
    @SerialName("clarity_project_id") val clarityProjectId: String? = null,
    @SerialName("token_expires_at") val tokenExpiresAt: String? = null,
    /** Daily snapshots saved for this project; ranges longer than 3 days are built from them. */
    @SerialName("history_days") val historyDays: Int = 0,
    @SerialName("history_since") val historySince: String? = null,
    @SerialName("clarity_requests") val clarityRequests: ClarityRequests = ClarityRequests(),
) {
    val needsReauth: Boolean get() = status == "needs_reauth"

    /** Clarity's session recordings for this project. The export API can't return recordings, so we link out. */
    val recordingsUrl: String? get() = clarityProjectId?.let { "https://clarity.microsoft.com/projects/view/$it/impressions" }
}

@Serializable
data class ProjectsResponse(
    val projects: List<ProjectDto> = emptyList(),
    @SerialName("max_projects") val maxProjects: Int = 1,
)

@Serializable
data class ConnectProjectDto(
    val name: String,
    @SerialName("clarity_token") val clarityToken: String,
    @SerialName("clarity_project_id") val clarityProjectId: String? = null,
)

/** Null fields are left out of the JSON, so the server leaves them unchanged. */
@Serializable
data class UpdateProjectDto(
    val name: String? = null,
    @SerialName("clarity_project_id") val clarityProjectId: String? = null,
)

@Serializable
data class ReconnectProjectDto(@SerialName("clarity_token") val clarityToken: String)

private val reviewedRecordingsNote = Regex("^Reviewed (\\d+) real session recordings")

/** How many real Clarity session recordings the report reviewed, from the server's data notes. */
fun reviewedRecordings(notes: List<String>): Int =
    notes.firstNotNullOfOrNull { reviewedRecordingsNote.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

sealed interface AuditEvent {
    data class Meta(val kpis: Kpis, val notes: List<String>, val daysCovered: Int, val usage: Usage?) : AuditEvent
    data class Delta(val text: String) : AuditEvent
    data class Locked(val message: String) : AuditEvent
    data class Failure(val code: String, val message: String) : AuditEvent
    data object Done : AuditEvent
}

class ApiException(
    val httpStatus: Int,
    val code: String,
    override val message: String,
    val requiredTier: Tier? = null,
) : Exception(message)

class DeviceIdProvider(context: Context) {
    private val prefs = context.getSharedPreferences("clarity_ai", Context.MODE_PRIVATE)
    val id: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
}

class ApiClient(context: Context) {
    private val deviceId = DeviceIdProvider(context).id
    private val baseUrl = BuildConfig.BASE_URL
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Debug builds only: asks a backend running with DEV_ALLOW_TIER_OVERRIDE=true to treat us as this tier. */
    private val devTier: String? = BuildConfig.DEV_TIER.takeIf { BuildConfig.DEBUG && it.isNotBlank() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // streaming responses stay open while Claude writes
        .retryOnConnectionFailure(true)
        .build()

    private fun request(path: String) = Request.Builder().url(baseUrl + path)
        .header("X-Device-Id", deviceId).header("Accept", "application/json")
        .apply { devTier?.let { header("X-Dev-Tier", it) } }

    suspend fun entitlements(): Entitlements = withContext(Dispatchers.IO) {
        execute(request("api/v1/account/entitlements").get().build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun verifySubscription(purchaseToken: String, productId: String): Entitlements = withContext(Dispatchers.IO) {
        val body = AppJson.encodeToString(VerifyRequestDto(purchaseToken, productId)).toRequestBody(jsonType)
        execute(request("api/v1/subscription/verify").post(body).build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun projects(): ProjectsResponse = withContext(Dispatchers.IO) {
        execute(request("api/v1/projects").get().build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun connectProject(name: String, clarityToken: String, clarityProjectId: String?): ProjectDto = withContext(Dispatchers.IO) {
        val body = AppJson.encodeToString(ConnectProjectDto(name, clarityToken, clarityProjectId)).toRequestBody(jsonType)
        execute(request("api/v1/projects").post(body).build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun updateProject(id: String, dto: UpdateProjectDto): ProjectDto = withContext(Dispatchers.IO) {
        val body = AppJson.encodeToString(dto).toRequestBody(jsonType)
        execute(request("api/v1/projects/$id").patch(body).build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun reconnectProject(id: String, clarityToken: String): ProjectDto = withContext(Dispatchers.IO) {
        val body = AppJson.encodeToString(ReconnectProjectDto(clarityToken)).toRequestBody(jsonType)
        execute(request("api/v1/projects/$id/token").put(body).build()) { AppJson.decodeFromString(it.body.string()) }
    }

    suspend fun deleteProject(id: String): Unit = withContext(Dispatchers.IO) {
        execute(request("api/v1/projects/$id").delete().build()) { }
    }

    fun streamAudit(dto: AuditRequestDto): Flow<AuditEvent> = flow {
        val body = AppJson.encodeToString(dto).toRequestBody(jsonType)
        val call = http.newCall(request("api/v1/analytics/audit").post(body).build())
        try {
            val response = try { call.execute() } catch (e: IOException) { throw networkError(e) }
            response.use { resp ->
                if (!resp.isSuccessful) throw parseError(resp)
                val source = resp.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = try { source.readUtf8Line() } catch (e: IOException) { throw networkError(e) } ?: break
                    if (line.isBlank()) continue
                    parseEvent(line)?.let { emit(it) }
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun <T> execute(req: Request, parse: (Response) -> T): T {
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw parseError(resp)
                return parse(resp)
            }
        } catch (e: IOException) {
            throw networkError(e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun networkError(e: IOException) =
        ApiException(0, "NETWORK", "Can't reach the server. Check your connection and try again.")

    private fun parseError(resp: Response): ApiException {
        val raw = runCatching { resp.body.string() }.getOrNull().orEmpty()
        val detail = runCatching { AppJson.parseToJsonElement(raw).jsonObject["detail"] }.getOrNull()
        return when (detail) {
            is JsonObject -> ApiException(
                resp.code,
                detail["code"]?.jsonPrimitive?.content ?: "ERROR",
                detail["message"]?.jsonPrimitive?.content ?: "Request failed (HTTP ${resp.code}).",
                (detail["required_tier"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { runCatching { Tier.valueOf(it) }.getOrNull() },
            )
            is JsonArray -> ApiException(resp.code, "INVALID_REQUEST", "The request was rejected as invalid. Check the project ID and dates.")
            is JsonPrimitive -> ApiException(resp.code, "ERROR", detail.content)
            else -> ApiException(resp.code, "ERROR", "Request failed (HTTP ${resp.code}).")
        }
    }

    private fun parseEvent(line: String): AuditEvent? {
        val obj = runCatching { AppJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        return when (obj["type"]?.jsonPrimitive?.content) {
            "meta" -> AuditEvent.Meta(
                kpis = obj["kpis"]?.let { AppJson.decodeFromJsonElement<Kpis>(it) } ?: Kpis(),
                notes = obj["notes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                daysCovered = obj["days_covered"]?.jsonPrimitive?.intOrNull ?: 0,
                usage = obj["usage"]?.let { AppJson.decodeFromJsonElement<Usage>(it) },
            )
            "delta" -> AuditEvent.Delta(obj["text"]?.jsonPrimitive?.content.orEmpty())
            "locked" -> AuditEvent.Locked(obj["message"]?.jsonPrimitive?.content.orEmpty())
            "error" -> AuditEvent.Failure(
                obj["code"]?.jsonPrimitive?.content ?: "ERROR",
                obj["message"]?.jsonPrimitive?.content ?: "Audit failed.",
            )
            "done" -> AuditEvent.Done
            else -> null
        }
    }
}
