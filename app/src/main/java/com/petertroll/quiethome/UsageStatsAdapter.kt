package com.petertroll.quiethome

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.petertroll.quiethome.domain.UsageInterval
import com.petertroll.quiethome.domain.UsageSummary
import java.time.Instant
import java.time.ZoneId

/** Android-only usage adapter. Call [summary] from a worker thread. */
class UsageStatsAdapter(private val context: Context) {
    fun summary(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): UsageSummary {
        val access = UsageAccess(context)
        val permission = runCatching { access.grantStatus() }.getOrNull() ?: return UsageSummary.QueryError
        if (!permission) return UsageSummary.MissingPermission
        return runCatching {
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val start = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(start, nowMillis)
                ?: return UsageSummary.QueryError
            val resumed = mutableMapOf<String, Long>()
            val intervals = mutableListOf<UsageInterval>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> resumed[pkg] = event.timeStamp.coerceAtLeast(start)
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> resumed.remove(pkg)?.let { began ->
                        if (event.timeStamp > began) intervals += UsageInterval(pkg, began, event.timeStamp.coerceAtMost(nowMillis))
                    }
                }
            }
            resumed.forEach { (pkg, began) -> if (began < nowMillis) intervals += UsageInterval(pkg, began, nowMillis) }
            UsageSummary.aggregate(true, nowMillis, zone, intervals)
        }.getOrElse { UsageSummary.QueryError }
    }
}
