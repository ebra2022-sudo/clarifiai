package com.clarifiai.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A funnel the user defined for a project: Clarity event names in the order users should fire them. Clarity's APIs
 * don't expose the funnels configured in its dashboard, so the app keeps them (see FunnelStore).
 */
@Serializable
data class FunnelDefinition(val name: String = "Funnel", val steps: List<String> = emptyList()) {
    val usable: Boolean get() = steps.count { it.isNotBlank() } >= 2
}

@Serializable
data class FunnelRequestDto(
    @SerialName("project_id") val projectId: String,
    val timeframe: String,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val name: String,
    val steps: List<String>,
)

@Serializable
data class FunnelStep(val name: String = "", val sessions: Int = 0)

@Serializable
data class FunnelDropOff(
    val from: String = "",
    val to: String = "",
    val reached: Int = 0,
    val dropped: Int = 0,
    val rate: Double = 0.0,
)

/** One reason people stop at the worst step; [explanation] is Markdown with proof links ("[D2 at 01:14](replay)"). */
@Serializable
data class FunnelReason(val title: String = "", val explanation: String = "", val sessions: Int? = null)

/** POST api/v1/analytics/funnel: sessions through each step, the worst drop-off and why people stop there. */
@Serializable
data class FunnelReport(
    val name: String = "",
    val steps: List<FunnelStep> = emptyList(),
    /** Earliest session start the step counts cover when Clarity's 250-session sample was the limit, else null. */
    val since: String? = null,
    @SerialName("drop_off") val dropOff: FunnelDropOff? = null,
    val reasons: List<FunnelReason> = emptyList(),
    /** The drop-off sessions read in full (ids D1, D2, ...), which the reasons cite. */
    val sessions: List<SessionJourney> = emptyList(),
    /**
     * Steps Clarity's API can't see (custom events sent with sendCustomEvent aren't exposed, only Clarity's smart
     * events). When any are listed there are no counts, since they would be meaningless.
     */
    val unavailable: List<String> = emptyList(),
)

@Serializable
data class FunnelEvent(val name: String = "", val sessions: Int = 0)

@Serializable
data class FunnelEventsResponse(val events: List<FunnelEvent> = emptyList())
