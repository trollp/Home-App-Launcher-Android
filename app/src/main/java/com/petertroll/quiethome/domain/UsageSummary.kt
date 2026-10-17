package com.petertroll.quiethome.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageInterval(val packageName: String, val startMillis: Long, val endMillis: Long)
data class AppUsage(val packageName: String, val durationMillis: Long)
data class DayUsage(val date: LocalDate, val totalMillis: Long)

sealed class UsageSummary {
    data object MissingPermission : UsageSummary()
    data object NoData : UsageSummary()
    data object QueryError : UsageSummary()
    data class Available(
        val todayTotalMillis: Long,
        val todayApps: List<AppUsage>,
        val days: List<DayUsage>
    ) : UsageSummary()

    companion object {
        fun fromQuery(permissionGranted: Boolean?, querySucceeded: Boolean, nowMillis: Long, zone: ZoneId, intervals: List<UsageInterval>): UsageSummary = when {
            permissionGranted == null || !querySucceeded -> QueryError
            !permissionGranted -> MissingPermission
            else -> aggregate(true, nowMillis, zone, intervals)
        }

        fun aggregate(granted: Boolean, nowMillis: Long, zone: ZoneId, intervals: List<UsageInterval>): UsageSummary {
            if (!granted) return MissingPermission
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val starts = dates.associateWith { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            val ends = dates.associateWith { it.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(nowMillis) }
            val packageDurations = mutableMapOf<String, Long>()
            val totals = mutableMapOf<LocalDate, Long>()
            intervals.asSequence()
                .filter { it.packageName.isNotBlank() && it.endMillis > it.startMillis && it.startMillis < nowMillis }
                .forEach { interval ->
                    dates.forEach { date ->
                        val overlap = (minOf(interval.endMillis, ends.getValue(date)) - maxOf(interval.startMillis, starts.getValue(date))).coerceAtLeast(0L)
                        if (overlap > 0) {
                            totals[date] = (totals[date] ?: 0L) + overlap
                            if (date == today) packageDurations[interval.packageName] = (packageDurations[interval.packageName] ?: 0L) + overlap
                        }
                    }
                }
            val apps = packageDurations.map { AppUsage(it.key, it.value) }
                .sortedWith(compareByDescending<AppUsage> { it.durationMillis }.thenBy { it.packageName })
            if (apps.isEmpty() && totals.values.all { it == 0L }) return NoData
            return Available(apps.sumOf { it.durationMillis }, apps, dates.map { DayUsage(it, totals[it] ?: 0L) })
        }
    }
}
