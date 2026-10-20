package com.petertroll.quiethome.domain

import java.time.Duration
import java.time.Instant

enum class HealthRecordType { SLEEP, EXERCISE, HEART_RATE, STEPS, TOTAL_CALORIES }
enum class HealthReadStatus { AVAILABLE, NO_DATA, PERMISSION_MISSING, READ_FAILED, SDK_UNAVAILABLE }

data class HealthSummary(
    val statuses: Map<HealthRecordType, HealthReadStatus> = HealthRecordType.entries.associateWith { HealthReadStatus.SDK_UNAVAILABLE },
    val sleepMinutes24h: Int? = null,
    val sleepStageMinutes24h: Map<String, Int> = emptyMap(),
    val exerciseSessions7d: Int? = null,
    val exerciseMinutes7d: Int? = null,
    val qualifyingGymSessions7d: Int? = null,
    val heartRateAverage24h: Int? = null,
    val heartRateMinimum24h: Int? = null,
    val heartRateMaximum24h: Int? = null,
    val stepsToday: Long? = null,
    val caloriesTodayKcal: Int? = null,
    val collectedAtEpochMillis: Long? = null,
    val cached: Boolean = false,
) {
    fun bounded() = copy(
        sleepMinutes24h = sleepMinutes24h?.coerceIn(0, 24 * 60),
        sleepStageMinutes24h = sleepStageMinutes24h.entries.sortedBy { it.key }.take(8).associate { it.key.take(24) to it.value.coerceIn(0, 24 * 60) },
        exerciseSessions7d = exerciseSessions7d?.coerceIn(0, 1000),
        exerciseMinutes7d = exerciseMinutes7d?.coerceIn(0, 7 * 24 * 60),
        qualifyingGymSessions7d = qualifyingGymSessions7d?.coerceIn(0, 1000),
        heartRateAverage24h = heartRateAverage24h?.coerceIn(0, 300),
        heartRateMinimum24h = heartRateMinimum24h?.coerceIn(0, 300),
        heartRateMaximum24h = heartRateMaximum24h?.coerceIn(0, 300),
        stepsToday = stepsToday?.coerceIn(0, 1_000_000),
        caloriesTodayKcal = caloriesTodayKcal?.coerceIn(0, 100_000),
    )
}

data class HealthSleepInput(val start: Instant, val end: Instant, val stages: List<HealthSleepStageInput>)
data class HealthSleepStageInput(val start: Instant, val end: Instant, val name: String)
data class HealthExerciseInput(val start: Instant, val end: Instant, val strength: Boolean)
data class HealthHeartRateInput(val time: Instant, val beatsPerMinute: Long)
data class HealthCountInput(val start: Instant, val end: Instant, val value: Long)
data class HealthCaloriesInput(val start: Instant, val end: Instant, val kcal: Double)

/** Pure reduction layer. Inputs deliberately contain no Health Connect IDs or data origins. */
object HealthSummaries {
    fun sleep(records: List<HealthSleepInput>, since: Instant): Triple<Int, Map<String, Int>, HealthReadStatus> {
        val selected = records.filter { it.end > since }
        if (selected.isEmpty()) return Triple(0, emptyMap(), HealthReadStatus.NO_DATA)
        fun overlap(start: Instant, end: Instant, parentStart: Instant? = null, parentEnd: Instant? = null): Int {
            val clippedStart = maxOf(start, since, parentStart ?: start)
            val clippedEnd = minOf(end, parentEnd ?: end)
            return Duration.between(clippedStart, clippedEnd).toMinutes().coerceAtLeast(0).toInt()
        }
        val minutes = selected.sumOf { overlap(it.start, it.end) }.coerceAtMost(24 * 60)
        val stages = selected.flatMap { sleep -> sleep.stages.map { sleep to it } }
            .groupBy { (_, stage) -> stage.name.lowercase().take(24) }
            .mapValues { (_, values) -> values.sumOf { (sleep, stage) -> overlap(stage.start, stage.end, sleep.start, sleep.end) }.coerceAtMost(24 * 60) }
        return Triple(minutes, stages, HealthReadStatus.AVAILABLE)
    }

    fun exercise(records: List<HealthExerciseInput>, since: Instant, minimumMinutes: Int, strengthAlwaysQualifies: Boolean): Triple<Int, Int, Int> {
        val selected = records.filter { it.end > since }
        val durations = selected.map { Duration.between(maxOf(it.start, since), it.end).toMinutes().coerceAtLeast(0).toInt() }
        val qualifying = selected.zip(durations).count { (record, minutes) -> strengthAlwaysQualifies && record.strength || minutes >= minimumMinutes }
        return Triple(selected.size, durations.sum().coerceAtMost(7 * 24 * 60), qualifying)
    }

    fun heartRate(records: List<HealthHeartRateInput>, since: Instant): Triple<Int, Int, Int>? {
        val values = records.filter { it.time >= since }.map { it.beatsPerMinute.coerceIn(0, 300).toInt() }
        if (values.isEmpty()) return null
        return Triple(values.average().toInt(), values.min(), values.max())
    }

    fun counts(records: List<HealthCountInput>, since: Instant) = records.filter { it.end > since }.sumOf { it.value.coerceAtLeast(0) }.coerceAtMost(1_000_000)
    fun calories(records: List<HealthCaloriesInput>, since: Instant) = records.filter { it.end > since }.sumOf { it.kcal.coerceAtLeast(0.0) }.toInt().coerceAtMost(100_000)
}
