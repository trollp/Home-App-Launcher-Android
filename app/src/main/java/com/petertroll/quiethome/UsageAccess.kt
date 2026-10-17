package com.petertroll.quiethome

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.Instant
import java.time.ZoneId

/** Android edge around UsageStatsManager. Denied/unreliable queries fail open.
 *
 * Android reports daily aggregate buckets rather than exact foreground intervals. We query from the
 * current local-day boundary and cap the result to elapsed time in that day. The value is therefore
 * deliberately approximate, but handles multiple activities/sessions and midnight more safely than
 * pairing lifecycle events.
 */
class UsageAccess(private val context: Context) {
    fun grantStatus(): Boolean? {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun isGranted(): Boolean = grantStatus() == true

    fun minutesToday(packageName: String, nowMillis: Long = System.currentTimeMillis()): Int {
        if (!isGranted()) return 0
        return runCatching {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val stats = context.getSystemService(UsageStatsManager::class.java)
                ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, nowMillis)
                .orEmpty()
            val aggregate = stats.asSequence()
                .filter { it.packageName == packageName }
                .sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
                .coerceAtMost((nowMillis - start).coerceAtLeast(0L))
            (aggregate / 60_000L).toInt()
        }.getOrDefault(0)
    }
}
