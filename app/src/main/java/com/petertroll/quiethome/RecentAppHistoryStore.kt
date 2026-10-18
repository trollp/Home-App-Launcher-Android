package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.RecentAppEvent

/** Small local bridge from accessibility foreground events to the launcher gesture. */
class RecentAppHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(packageName: String, timestampMillis: Long) {
        preferences.edit()
            .putString(PACKAGE, packageName)
            .putLong(TIMESTAMP, timestampMillis)
            .apply()
    }

    fun load(): RecentAppEvent? {
        val packageName = preferences.getString(PACKAGE, null) ?: return null
        return RecentAppEvent(packageName, preferences.getLong(TIMESTAMP, 0L), launchable = true)
    }

    private companion object {
        const val FILE = "recent_app_history"
        const val PACKAGE = "package"
        const val TIMESTAMP = "timestamp"
    }
}
