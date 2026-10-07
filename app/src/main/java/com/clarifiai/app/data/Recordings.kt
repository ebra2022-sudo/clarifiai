package com.clarifiai.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Real Clarity session recordings the server reviewed for a report (POST api/v1/analytics/recordings). */
@Serializable
data class RecordingSample(
    @SerialName("sampled_sessions") val sampledSessions: Int = 0,
    val patterns: RecordingPatterns? = null,
    val sessions: List<SessionJourney> = emptyList(),
)

@Serializable
data class ElementCount(val element: String = "", val sessions: Int = 0)

@Serializable
data class ScreenCount(val screen: String = "", val sessions: Int = 0)

@Serializable
data class RecordingPatterns(
    @SerialName("most_dead_tapped") val mostDeadTapped: List<ElementCount> = emptyList(),
    @SerialName("most_rage_tapped") val mostRageTapped: List<ElementCount> = emptyList(),
    @SerialName("most_tapped") val mostTapped: List<ElementCount> = emptyList(),
    @SerialName("sessions_with_dead_taps") val sessionsWithDeadTaps: Int = 0,
    @SerialName("sessions_with_rage_taps") val sessionsWithRageTaps: Int = 0,
    @SerialName("median_active_seconds") val medianActiveSeconds: Int? = null,
    @SerialName("last_screen_seen") val lastScreenSeen: List<ScreenCount> = emptyList(),
)

@Serializable
data class JourneyStep(val screen: String = "", val at: String? = null, val events: List<String> = emptyList())

/** One recording read in full: why it was picked, when, and what the user did, in order. */
@Serializable
data class SessionJourney(
    @SerialName("selected_for") val selectedFor: String = "",
    val started: String? = null,
    @SerialName("active_time") val activeTime: String? = null,
    @SerialName("screens_visited") val screensVisited: Int? = null,
    val taps: Int? = null,
    val replay: String? = null,
    val journey: List<JourneyStep> = emptyList(),
    @SerialName("events_omitted") val eventsOmitted: Int? = null,
) {
    val events: List<String> get() = journey.flatMap { it.events }
    val frustrationEvents: Int get() = events.count { it.startsWith("dead tap") || it.startsWith("rage taps") }

    /** The moments worth reading first: dead and rage taps, else the first few taps. */
    fun keyMoments(max: Int = 4): List<String> {
        val trouble = events.filter { it.startsWith("dead tap") || it.startsWith("rage taps") }
        return (trouble.ifEmpty { events }).take(max)
    }

    /** "04 minutes and 18 seconds" -> "4m 18s". */
    val shortActiveTime: String? get() = activeTime?.let { t ->
        val parts = Regex("(\\d+)\\s*(hour|minute|second)").findAll(t).associate { it.groupValues[2] to it.groupValues[1].toInt() }
        listOfNotNull(parts["hour"]?.let { "${it}h" }, parts["minute"]?.let { "${it}m" }, parts["second"]?.let { "${it}s" })
            .joinToString(" ").ifEmpty { t }
    }
}
