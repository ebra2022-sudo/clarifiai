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
    /** "S3": how report findings cite this session ("[S3 at 04:11](replay)"). */
    val id: String? = null,
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
    val frustrationEvents: Int get() = events.count(::isFrustration)

    /** The moments worth reading first: dead and rage taps, else the first few taps. Each starts with its mm:ss time. */
    fun keyMoments(max: Int = 4): List<String> {
        val trouble = events.filter(::isFrustration).map(::cleanPlaceholders)
        return (trouble.ifEmpty { events.map(::cleanPlaceholders) }).take(max)
    }

    /** "04 minutes and 18 seconds" -> "4m 18s". */
    val shortActiveTime: String? get() = activeTime?.let { t ->
        val parts = Regex("(\\d+)\\s*(hour|minute|second)").findAll(t).associate { it.groupValues[2] to it.groupValues[1].toInt() }
        listOfNotNull(parts["hour"]?.let { "${it}h" }, parts["minute"]?.let { "${it}m" }, parts["second"]?.let { "${it}s" })
            .joinToString(" ").ifEmpty { t }
    }
}

private fun isFrustration(event: String) = "dead tap" in event || "rage taps" in event

/**
 * Older reports and cached samples used bracketed placeholders ("[unlabelled]", "[icon]") that read like broken
 * text in a report. Turns them into words wherever report text is shown or printed.
 */
fun cleanPlaceholders(text: String): String = text
    .replace(Regex("""['"]?\[unlabell?ed]['"]?"""), "an unlabelled element")
    .replace(Regex("""['"]?\[icon]['"]?"""), "an icon")
    .replace("[redacted]", "an email address")

/** A pattern element's display name ("[unlabelled]" from older samples -> "Unlabelled element"). */
fun elementName(raw: String): String = when (raw.trim()) {
    "[unlabelled]", "[unlabeled]" -> "Unlabelled element"
    "[icon]" -> "Icon"
    else -> raw
}

/**
 * Links that open a Clarity replay at the moment a finding cites ("S3 at 04:11").
 *
 * Clarity's player starts at the `t` query parameter, in milliseconds: its own Share dialog writes `t=1000 × seconds`
 * when "start at" is chosen, and both the player and shared-recording pages read it back as an integer. Replays start
 * [LEAD_IN_SECONDS] before the cited moment so the viewer sees what led up to it.
 */
object ReplayLinks {
    const val TIME_PARAM = "t"
    const val LEAD_IN_SECONDS = 8
    private val citation = Regex("""\[([^\]]*?\b(\d{1,3}):(\d{2})\b[^\]]*)]\((https://clarity\.microsoft\.com/player/[^)\s]+)\)""")

    /** The replay URL starting a little before [seconds] into the session; any start time on the URL is replaced. */
    fun at(url: String, seconds: Int?): String {
        if (seconds == null) return url
        val base = url.replace(Regex("""([?&])$TIME_PARAM=\d*&?"""), "$1").trimEnd('?', '&')
        return base + (if ("?" in base) "&" else "?") + TIME_PARAM + "=" + (seconds - LEAD_IN_SECONDS).coerceAtLeast(0) * 1000L
    }

    /** Seconds from a citation's text, e.g. "S3 at 04:11" -> 251. */
    fun seconds(label: String): Int? = Regex("""\b(\d{1,3}):(\d{2})\b""").find(label)
        ?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** Points every "[S3 at 04:11](replay)" citation in a report at its moment. */
    fun deepLink(markdown: String): String = citation.replace(markdown) { m ->
        val secs = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        "[${m.groupValues[1]}](${at(m.groupValues[4], secs)})"
    }
}
