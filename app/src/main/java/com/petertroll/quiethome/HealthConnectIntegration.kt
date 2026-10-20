package com.petertroll.quiethome

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.petertroll.quiethome.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

object HealthPermissions {
    val foreground: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )
    val all: Set<String> = foreground + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
}

object HealthPermissionStatus {
    fun requestable(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        val client = HealthConnectClient.getOrCreate(context)
        val background = runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        return if (background) HealthPermissions.all else HealthPermissions.foreground
    }

    fun backgroundAvailableAndGranted(context: Context, granted: Set<String>): Boolean =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted && runCatching {
            HealthConnectClient.getOrCreate(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
}

/** Health Connect boundary. Every type is queried independently so one denied/bad Garmin record cannot hide the others. */
class HealthConnectCollector(private val context: Context) {
    fun collect(now: Instant, profile: PlannerProfile, background: Boolean): HealthSummary {
        if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return HealthSummaryCache(context).load()?.copy(cached = true) ?: HealthSummary()
        }
        val client = HealthConnectClient.getOrCreate(context)
        return runBlocking {
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (background && !canReadInBackground(client, granted)) return@runBlocking HealthSummaryCache(context).load()?.copy(cached = true) ?: unavailableForGranted(granted)
            val statuses = mutableMapOf<HealthRecordType, HealthReadStatus>()
            val dayAgo = now.minus(Duration.ofHours(24)); val weekAgo = now.minus(Duration.ofDays(7))

            val sleep = independently(HealthRecordType.SLEEP, SleepSessionRecord::class, granted, statuses) {
                readAll(client, SleepSessionRecord::class, weekAgo, now).map { record ->
                    HealthSleepInput(record.startTime, record.endTime, record.stages.map { HealthSleepStageInput(it.startTime, it.endTime, sleepStageName(it.stage)) })
                }
            }
            val sleepResult = sleep?.let { HealthSummaries.sleep(it, dayAgo) }
            if (sleepResult?.third == HealthReadStatus.NO_DATA) statuses[HealthRecordType.SLEEP] = HealthReadStatus.NO_DATA

            val exercise = independently(HealthRecordType.EXERCISE, ExerciseSessionRecord::class, granted, statuses) {
                readAll(client, ExerciseSessionRecord::class, weekAgo, now).map { HealthExerciseInput(it.startTime, it.endTime, it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING) }
            }
            val exerciseResult = exercise?.takeIf { it.isNotEmpty() }?.let { HealthSummaries.exercise(it, weekAgo, profile.gym.healthExerciseMinimumMinutes, profile.gym.strengthAlwaysQualifies) }
            if (exercise?.isEmpty() == true) statuses[HealthRecordType.EXERCISE] = HealthReadStatus.NO_DATA

            val heart = independently(HealthRecordType.HEART_RATE, HeartRateRecord::class, granted, statuses) {
                readAll(client, HeartRateRecord::class, dayAgo, now).flatMap { it.samples }.map { HealthHeartRateInput(it.time, it.beatsPerMinute) }
            }
            val heartResult = heart?.let { HealthSummaries.heartRate(it, dayAgo) }
            if (heart != null && heartResult == null) statuses[HealthRecordType.HEART_RATE] = HealthReadStatus.NO_DATA

            val startOfDay = now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val steps = independently(HealthRecordType.STEPS, StepsRecord::class, granted, statuses) {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, now)))[StepsRecord.COUNT_TOTAL]
            }
            if (steps == null && statuses[HealthRecordType.STEPS] == HealthReadStatus.AVAILABLE) statuses[HealthRecordType.STEPS] = HealthReadStatus.NO_DATA
            val calories = independently(HealthRecordType.TOTAL_CALORIES, TotalCaloriesBurnedRecord::class, granted, statuses) {
                client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), TimeRangeFilter.between(startOfDay, now)))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            }
            if (calories == null && statuses[HealthRecordType.TOTAL_CALORIES] == HealthReadStatus.AVAILABLE) statuses[HealthRecordType.TOTAL_CALORIES] = HealthReadStatus.NO_DATA

            HealthSummary(
                statuses = HealthRecordType.entries.associateWith { statuses[it] ?: HealthReadStatus.READ_FAILED },
                sleepMinutes24h = sleepResult?.takeIf { it.third == HealthReadStatus.AVAILABLE }?.first, sleepStageMinutes24h = sleepResult?.takeIf { it.third == HealthReadStatus.AVAILABLE }?.second.orEmpty(),
                exerciseSessions7d = exerciseResult?.first, exerciseMinutes7d = exerciseResult?.second, qualifyingGymSessions7d = exerciseResult?.third,
                heartRateAverage24h = heartResult?.first, heartRateMinimum24h = heartResult?.second, heartRateMaximum24h = heartResult?.third,
                stepsToday = steps?.coerceIn(0, 1_000_000), caloriesTodayKcal = calories?.toInt()?.coerceIn(0, 100_000),
                collectedAtEpochMillis = now.toEpochMilli(),
            ).bounded().also { HealthSummaryCache(context).save(it) }
        }
    }

    private suspend fun canReadInBackground(client: HealthConnectClient, granted: Set<String>): Boolean =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted &&
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun unavailableForGranted(granted: Set<String>) = HealthSummary(statuses = permissionStatuses(granted))
    private fun permissionStatuses(granted: Set<String>) = mapOf(
        HealthRecordType.SLEEP to SleepSessionRecord::class,
        HealthRecordType.EXERCISE to ExerciseSessionRecord::class,
        HealthRecordType.HEART_RATE to HeartRateRecord::class,
        HealthRecordType.STEPS to StepsRecord::class,
        HealthRecordType.TOTAL_CALORIES to TotalCaloriesBurnedRecord::class,
    ).mapValues { (_, type) -> if (HealthPermission.getReadPermission(type) in granted) HealthReadStatus.READ_FAILED else HealthReadStatus.PERMISSION_MISSING }

    private suspend fun <T : androidx.health.connect.client.records.Record, R> independently(type: HealthRecordType, recordType: KClass<T>, granted: Set<String>, statuses: MutableMap<HealthRecordType, HealthReadStatus>, block: suspend () -> R): R? {
        if (HealthPermission.getReadPermission(recordType) !in granted) { statuses[type] = HealthReadStatus.PERMISSION_MISSING; return null }
        return runCatching { block() }.onSuccess { statuses[type] = HealthReadStatus.AVAILABLE }.onFailure { statuses[type] = HealthReadStatus.READ_FAILED }.getOrNull()
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(client: HealthConnectClient, type: KClass<T>, start: Instant, end: Instant): List<T> {
        val records = mutableListOf<T>(); var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageToken = token))
            records += response.records; token = response.pageToken
        } while (token != null && records.size < 10_000)
        return records.take(10_000)
    }

    private fun sleepStageName(stage: Int) = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        else -> "unknown"
    }
}

/** Persists only the bounded aggregate; raw records, samples, IDs and origins never cross this boundary. */
class HealthSummaryCache(context: Context) {
    private val prefs = context.getSharedPreferences("planner_health_summary", Context.MODE_PRIVATE)
    fun save(value: HealthSummary) { prefs.edit().putString("summary", encode(value.copy(cached = false))).apply() }
    fun load(): HealthSummary? = prefs.getString("summary", null)?.let { runCatching { decode(it) }.getOrNull() }
    private fun encode(v: HealthSummary) = JSONObject().put("statuses", JSONObject(v.statuses.mapKeys { it.key.name }.mapValues { it.value.name }))
        .put("sleep", v.sleepMinutes24h ?: JSONObject.NULL).put("stages", JSONObject(v.sleepStageMinutes24h)).put("sessions", v.exerciseSessions7d ?: JSONObject.NULL).put("exerciseMinutes", v.exerciseMinutes7d ?: JSONObject.NULL).put("gym", v.qualifyingGymSessions7d ?: JSONObject.NULL)
        .put("hrAvg", v.heartRateAverage24h ?: JSONObject.NULL).put("hrMin", v.heartRateMinimum24h ?: JSONObject.NULL).put("hrMax", v.heartRateMaximum24h ?: JSONObject.NULL).put("steps", v.stepsToday ?: JSONObject.NULL).put("calories", v.caloriesTodayKcal ?: JSONObject.NULL).put("at", v.collectedAtEpochMillis ?: JSONObject.NULL).toString()
    private fun decode(raw: String): HealthSummary {
        val j = JSONObject(raw)
        val statuses = j.getJSONObject("statuses")
        fun intOrNull(key: String) = if (j.isNull(key)) null else j.getInt(key)
        fun longOrNull(key: String) = if (j.isNull(key)) null else j.getLong(key)
        val stages = j.getJSONObject("stages")
        return HealthSummary(
            statuses = HealthRecordType.entries.associateWith {
                runCatching { HealthReadStatus.valueOf(statuses.getString(it.name)) }.getOrDefault(HealthReadStatus.READ_FAILED)
            },
            sleepMinutes24h = intOrNull("sleep"),
            sleepStageMinutes24h = stages.keys().asSequence().associateWith { stages.getInt(it) },
            exerciseSessions7d = intOrNull("sessions"),
            exerciseMinutes7d = intOrNull("exerciseMinutes"),
            qualifyingGymSessions7d = intOrNull("gym"),
            heartRateAverage24h = intOrNull("hrAvg"),
            heartRateMinimum24h = intOrNull("hrMin"),
            heartRateMaximum24h = intOrNull("hrMax"),
            stepsToday = longOrNull("steps"),
            caloriesTodayKcal = intOrNull("calories"),
            collectedAtEpochMillis = longOrNull("at"),
            cached = true,
        ).bounded()
    }
}
