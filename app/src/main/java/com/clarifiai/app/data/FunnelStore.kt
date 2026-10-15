package com.clarifiai.app.data

import android.content.Context

/** The funnel defined for each project, kept on this device (Clarity's APIs don't expose dashboard funnels). */
class FunnelStore(context: Context) {
    private val prefs = context.getSharedPreferences("funnels", Context.MODE_PRIVATE)

    fun get(projectId: String): FunnelDefinition? = prefs.getString(projectId, null)?.let {
        runCatching { AppJson.decodeFromString<FunnelDefinition>(it) }.getOrNull()
    }

    fun put(projectId: String, funnel: FunnelDefinition?) {
        prefs.edit().apply {
            if (funnel == null || !funnel.usable) remove(projectId) else putString(projectId, AppJson.encodeToString(funnel))
        }.apply()
    }
}
