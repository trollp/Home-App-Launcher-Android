package com.petertroll.quiethome

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.petertroll.quiethome.domain.RecentAppEvent
import com.petertroll.quiethome.domain.RecentAppSelector

/** Resolves the actual last foreground launchable app from accessibility or Usage Access. */
class RecentAppResolver(private val context: Context) {
    fun resolve(nowMillis: Long = System.currentTimeMillis()): String? {
        val events = runCatching { readUsageEvents(nowMillis) }.getOrDefault(emptyList()).toMutableList()
        RecentAppHistoryStore(context).load()?.let { stored ->
            events += stored.copy(launchable = isLaunchable(stored.packageName))
        }
        return RecentAppSelector.select(events, context.packageName)
    }

    private fun readUsageEvents(nowMillis: Long): List<RecentAppEvent> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val usageEvents = manager.queryEvents(nowMillis - LOOKBACK_MILLIS, nowMillis)
        val event = UsageEvents.Event()
        val latestByPackage = mutableMapOf<String, Long>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                val packageName = event.packageName ?: continue
                if (event.timeStamp > (latestByPackage[packageName] ?: Long.MIN_VALUE)) {
                    latestByPackage[packageName] = event.timeStamp
                }
            }
        }
        return latestByPackage.map { (packageName, timestamp) ->
            RecentAppEvent(packageName, timestamp, isLaunchable(packageName))
        }
    }

    private fun isLaunchable(packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    private companion object {
        const val LOOKBACK_MILLIS = 24 * 60 * 60_000L
    }
}
