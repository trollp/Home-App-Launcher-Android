package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class ModelConfig(val name: String, val reasoningEffort: String, val maxCompletionTokens: Int) {
    fun normalized(fallback: ModelConfig): ModelConfig = copy(
        name = name.trim().take(80).ifBlank { fallback.name },
        reasoningEffort = reasoningEffort.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("minimal", "low", "medium", "high") } ?: fallback.reasoningEffort,
        maxCompletionTokens = maxCompletionTokens.coerceIn(64, 16_000),
    )
}
data class GymProfile(
    val weeklyTarget: Int = 3,
    val durationMinutes: Int = 60,
    val travelMinutes: Int = 15,
    val preferredSlots: List<String> = listOf("18:00"),
)
enum class CalendarSelectionMode { ALL_VISIBLE, SELECTED }
data class PlannerProfile(
    val bedtime: LocalTime = LocalTime.of(23, 0),
    val wakeTime: LocalTime = LocalTime.of(7, 0),
    val windDownMinutes: Int = 60,
    val gym: GymProfile = GymProfile(),
    val meetingPrepMinutes: Int = 10,
    val hardConstraints: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val proactiveBudget: Int = 3,
    val importantApps: Set<String> = emptySet(),
    val includeFreeEvents: Boolean = false,
    val calendarSelectionMode: CalendarSelectionMode = CalendarSelectionMode.ALL_VISIBLE,
    val selectedCalendarIds: Set<Long> = emptySet(),
    val plannerModel: ModelConfig = ModelConfig("gpt-5-mini", "low", 4000),
    val intentModel: ModelConfig = ModelConfig("gpt-5-nano", "minimal", 800),
) {
    fun normalized() = copy(
        windDownMinutes = windDownMinutes.coerceIn(0, 180),
        gym = gym.copy(weeklyTarget = gym.weeklyTarget.coerceIn(0, 7), durationMinutes = gym.durationMinutes.coerceIn(15, 180), travelMinutes = gym.travelMinutes.coerceIn(0, 120), preferredSlots = gym.preferredSlots.take(8)),
        meetingPrepMinutes = meetingPrepMinutes.coerceIn(0, 60),
        hardConstraints = hardConstraints.map { it.trim().take(120) }.filter(String::isNotBlank).take(10),
        goals = goals.map { it.trim().take(120) }.filter(String::isNotBlank).take(5),
        proactiveBudget = proactiveBudget.coerceIn(0, 10),
        importantApps = importantApps.map { it.trim().take(200) }.filter(String::isNotBlank).take(100).toSet(),
        plannerModel = plannerModel.normalized(ModelConfig("gpt-5-mini", "low", 4000)),
        intentModel = intentModel.normalized(ModelConfig("gpt-5-nano", "minimal", 800)),
    )
}

enum class CalendarAvailability { BUSY, FREE, TENTATIVE, OUT_OF_OFFICE, UNKNOWN }
enum class CalendarAttendee { ACCEPTED, TENTATIVE, NONE, DECLINED }
data class CalendarRow(
    val eventId: Long, val begin: Long, val end: Long, val title: String?, val location: String?,
    val allDay: Boolean, val availability: CalendarAvailability, val attendeeStatus: CalendarAttendee,
    val calendarId: Long, val calendarDisplayName: String?, val accountName: String?, val description: String?,
)
data class CalendarEvent(
    val id: String, val providerEventId: Long, val beginEpochMillis: Long, val endEpochMillis: Long,
    val title: String, val location: String, val allDay: Boolean, val busy: Boolean,
    val calendarId: Long, val calendarName: String, val accountName: String, val onlineMeeting: Boolean,
)
data class CalendarSource(val id: Long, val displayName: String, val accountName: String, val visible: Boolean)

object CalendarRows {
    private val online = Regex("(?i)(teams\\.microsoft\\.com|meet\\.google\\.com|zoom\\.(us|com)|zoom meeting|microsoft teams|google meet)")
    fun collect(rows: List<CalendarRow>, includeFreeEvents: Boolean, selectedCalendarIds: Set<Long>?): List<CalendarEvent> = rows.asSequence()
        .filter { it.attendeeStatus != CalendarAttendee.DECLINED }
        .filter { includeFreeEvents || it.availability != CalendarAvailability.FREE }
        .filter { selectedCalendarIds == null || it.calendarId in selectedCalendarIds }
        .map {
            val title = it.title.orEmpty().trim().ifBlank { "Untitled event" }.take(160)
            CalendarEvent(
                id = "event:${it.eventId}:${it.begin}", providerEventId = it.eventId, beginEpochMillis = it.begin,
                endEpochMillis = maxOf(it.begin, it.end), title = title, location = it.location.orEmpty().trim().take(120),
                allDay = it.allDay, busy = it.availability != CalendarAvailability.FREE, calendarId = it.calendarId,
                calendarName = it.calendarDisplayName.orEmpty().trim().take(80), accountName = it.accountName.orEmpty().trim().take(120),
                onlineMeeting = listOf(it.title, it.location, it.description).any { value -> value != null && online.containsMatchIn(value) },
            )
        }.distinctBy { Triple(it.title.lowercase(Locale.ROOT).trim(), it.beginEpochMillis, it.endEpochMillis) }
        .sortedBy { it.beginEpochMillis }.toList()
}

data class LocalTask(val id: String, val title: String, val dueEpochMillis: Long?, val important: Boolean, val completed: Boolean, val createdAtEpochMillis: Long)
object LocalTasks {
    fun add(values: List<LocalTask>, title: String, due: Long?, important: Boolean, now: Long, id: String = "task:${UUID.randomUUID()}") =
        values + LocalTask(id, title.trim().take(120).ifBlank { "Task" }, due, important, false, now)
    fun edit(values: List<LocalTask>, id: String, title: String, due: Long?, important: Boolean) = values.map {
        if (it.id == id) it.copy(title = title.trim().take(120).ifBlank { it.title }, dueEpochMillis = due, important = important) else it
    }
    fun complete(values: List<LocalTask>, id: String, completed: Boolean = true) = values.map { if (it.id == id) it.copy(completed = completed) else it }
    fun delete(values: List<LocalTask>, id: String) = values.filterNot { it.id == id }
}

enum class ActivityKind { PLAN_SHOWN, DONE, SKIPPED, DEFERRED, DISMISSED, PROACTIVE_NUDGE, GYM_ACTUAL, SLEEP_ACTUAL, INTENT_COMMAND }
data class ActivityRecord(val id: String, val timestampEpochMillis: Long, val kind: ActivityKind, val refId: String? = null)
data class ActivitySummary(val counts: Map<ActivityKind, Int>, val last20: List<ActivityRecord>)
object ActivityLogPolicy {
    private const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    fun bound(values: List<ActivityRecord>, now: Instant = Instant.now()) = values.filter { it.timestampEpochMillis > now.toEpochMilli() - RETENTION_MILLIS }.sortedByDescending { it.timestampEpochMillis }.take(1000)
    fun summary(values: List<ActivityRecord>) = ActivitySummary(ActivityKind.entries.associateWith { kind -> values.count { it.kind == kind } }, values.sortedByDescending { it.timestampEpochMillis }.take(20))
}

data class TimeSlot(val startEpochMillis: Long, val endEpochMillis: Long) { val durationMinutes: Int get() = Duration.ofMillis(endEpochMillis - startEpochMillis).toMinutes().toInt() }
data class PlannerMetrics(val meetingCount: Int, val meetingMinutes: Int, val longestMeetingStretchMinutes: Int, val tasksDue: Int, val tasksOverdue: Int)
enum class SourceStatus { AVAILABLE, PERMISSION_MISSING, NO_SYNCED_CALENDARS, UNAVAILABLE, NOT_CONFIGURED }
data class SnapshotFreshness(
    val calendarEpochMillis: Long?, val weatherEpochMillis: Long?, val tasksEpochMillis: Long, val usageEpochMillis: Long?,
    val calendarStatus: SourceStatus = if (calendarEpochMillis == null) SourceStatus.UNAVAILABLE else SourceStatus.AVAILABLE,
    val weatherStatus: SourceStatus = if (weatherEpochMillis == null) SourceStatus.UNAVAILABLE else SourceStatus.AVAILABLE,
    val usageStatus: SourceStatus = if (usageEpochMillis == null) SourceStatus.UNAVAILABLE else SourceStatus.AVAILABLE,
)
data class PlannerSnapshot(
    val generatedAtEpochMillis: Long, val zoneId: String, val events: List<CalendarEvent>, val tasks: List<LocalTask>,
    val weather: String?, val usageMinutesByPackage: Map<String, Int>, val trainingTargets: Map<String, Int>,
    val healthAvailable: Boolean = false, val freeSlots: List<TimeSlot>, val gymCandidateSlots: List<TimeSlot>,
    val metrics: PlannerMetrics, val notificationBatchCount: Int = 0, val activity: ActivitySummary,
    val freshness: SnapshotFreshness,
    val profile: PlannerProfile = PlannerProfile(),
    val allowedAppPackages: Set<String> = emptySet(),
) {
    fun knownRefIds() = events.mapTo(mutableSetOf()) { it.id }.apply { addAll(tasks.map { it.id }); addAll(trainingTargets.keys.map { "training:$it" }) }
}

object SnapshotBuilder {
    fun build(
        now: Instant, zone: ZoneId, profile: PlannerProfile, events: List<CalendarEvent>, tasks: List<LocalTask>, weather: String?,
        usage: List<Pair<String, Int>>, activity: List<ActivityRecord>, routines: List<Routine> = emptyList(),
        calendarStatus: SourceStatus = SourceStatus.AVAILABLE,
        calendarCollectedAt: Long? = if (calendarStatus == SourceStatus.AVAILABLE) now.toEpochMilli() else null,
        weatherCollectedAt: Long? = if (weather != null) now.toEpochMilli() else null,
        usageCollectedAt: Long? = if (usage.isNotEmpty()) now.toEpochMilli() else null,
        allowedAppPackages: Set<String> = emptySet(),
    ): PlannerSnapshot {
        val end = now.plus(Duration.ofHours(24))
        val boundedEvents = events.filter { it.endEpochMillis > now.toEpochMilli() && it.beginEpochMillis < end.toEpochMilli() }
            .sortedBy { it.beginEpochMillis }.take(24).map { it.copy(
                id = it.id.take(80), title = it.title.take(160), location = it.location.take(120),
                calendarName = it.calendarName.take(80), accountName = it.accountName.take(120),
            ) }
        val busy = boundedEvents.filter { it.busy && !it.allDay }.sortedBy { it.beginEpochMillis }
        val waking = wakingWindows(now, end, zone, profile)
        val free = waking.flatMap { window -> subtract(window, busy) }.filter { it.durationMinutes >= 45 }.take(24)
        val gymNeeded = profile.gym.durationMinutes + profile.gym.travelMinutes * 2
        val meetingMinutes = busy.sumOf { Duration.ofMillis((it.endEpochMillis - it.beginEpochMillis).coerceAtLeast(0)).toMinutes().toInt() }
        val longest = mergedBusyMinutes(busy)
        val activeTasks = tasks.filterNot { it.completed }.sortedWith(compareByDescending<LocalTask> { it.important }.thenBy { it.dueEpochMillis ?: Long.MAX_VALUE })
            .take(50).map { it.copy(id = it.id.take(80), title = it.title.take(120)) }
        val due = activeTasks.count { it.dueEpochMillis != null && it.dueEpochMillis <= end.toEpochMilli() }
        val overdue = activeTasks.count { it.dueEpochMillis != null && it.dueEpochMillis < now.toEpochMilli() }
        val training = buildMap {
            put("gymWeeklyTarget", profile.gym.weeklyTarget)
            put("gymActual14d", activity.count { it.kind == ActivityKind.GYM_ACTUAL })
            RoutineCollection.sanitize(routines).filter { it.enabled }.forEach { routine ->
                put("routine:${routine.id}:weeklyTarget", routine.weeklyTarget)
            }
        }
        var result = PlannerSnapshot(now.toEpochMilli(), zone.id, boundedEvents, activeTasks, weather?.trim()?.take(500), usage.take(50).toMap(), training, false, free, free.filter { it.durationMinutes >= gymNeeded }, PlannerMetrics(busy.size, meetingMinutes, longest, due, overdue), 0, ActivityLogPolicy.summary(ActivityLogPolicy.bound(activity, now)), SnapshotFreshness(calendarCollectedAt, weatherCollectedAt, now.toEpochMilli(), usageCollectedAt, calendarStatus, if (weatherCollectedAt == null) SourceStatus.UNAVAILABLE else SourceStatus.AVAILABLE, if (usageCollectedAt == null) SourceStatus.UNAVAILABLE else SourceStatus.AVAILABLE), profile.normalized(), allowedAppPackages.map { it.take(200) }.take(200).toSet())
        while (runCatching { PlannerSnapshotCodec.encode(result) }.isFailure) {
            result = when {
                result.tasks.isNotEmpty() -> result.copy(tasks = result.tasks.dropLast(1))
                result.events.isNotEmpty() -> result.copy(events = result.events.dropLast(1))
                result.usageMinutesByPackage.isNotEmpty() -> result.copy(usageMinutesByPackage = result.usageMinutesByPackage.entries.toList().dropLast(1).associate { it.toPair() })
                result.activity.last20.isNotEmpty() -> result.copy(activity = result.activity.copy(last20 = result.activity.last20.dropLast(1)))
                result.allowedAppPackages.isNotEmpty() -> result.copy(allowedAppPackages = result.allowedAppPackages.drop(1).toSet())
                result.freeSlots.isNotEmpty() -> result.copy(freeSlots = result.freeSlots.dropLast(1), gymCandidateSlots = result.gymCandidateSlots.filter { it in result.freeSlots.dropLast(1) })
                result.weather != null -> result.copy(weather = null)
                else -> error("Minimal planner snapshot exceeds transport cap")
            }
        }
        return result
    }

    private fun wakingWindows(now: Instant, end: Instant, zone: ZoneId, profile: PlannerProfile): List<TimeSlot> {
        val startDate = now.atZone(zone).toLocalDate()
        return (0..1).mapNotNull { offset ->
            val date = startDate.plusDays(offset.toLong())
            val start = date.atTime(profile.wakeTime).atZone(zone).toInstant()
            val stopDate = if (profile.bedtime <= profile.wakeTime) date.plusDays(1) else date
            val stop = stopDate.atTime(profile.bedtime).atZone(zone).toInstant()
            val clippedStart = maxOf(start, now); val clippedEnd = minOf(stop, end)
            if (clippedEnd > clippedStart) TimeSlot(clippedStart.toEpochMilli(), clippedEnd.toEpochMilli()) else null
        }
    }
    private fun subtract(window: TimeSlot, events: List<CalendarEvent>): List<TimeSlot> {
        var cursor = window.startEpochMillis
        val result = mutableListOf<TimeSlot>()
        events.forEach { event ->
            val start = maxOf(window.startEpochMillis, event.beginEpochMillis); val end = minOf(window.endEpochMillis, event.endEpochMillis)
            if (end > cursor && start < window.endEpochMillis) { if (start > cursor) result += TimeSlot(cursor, start); cursor = maxOf(cursor, end) }
        }
        if (cursor < window.endEpochMillis) result += TimeSlot(cursor, window.endEpochMillis)
        return result
    }
    private fun mergedBusyMinutes(events: List<CalendarEvent>): Int {
        var longest = 0L; var start = 0L; var end = 0L
        events.forEach { event ->
            if (event.beginEpochMillis > end) { longest = maxOf(longest, end - start); start = event.beginEpochMillis; end = event.endEpochMillis }
            else end = maxOf(end, event.endEpochMillis)
        }
        return maxOf(longest, end - start).let { Duration.ofMillis(it).toMinutes().toInt() }
    }
}

enum class NextKind { EVENT, PREP, GYM, TASK, REST, SLEEP, OTHER }
enum class NudgeKind { PREP, WIND_DOWN, GYM, TASK, REST, OTHER }
data class PlanNext(val refId: String?, val time: String?, val text: String, val kind: NextKind)
data class PlanDigest(val refIds: List<String>, val text: String)
data class PlanNudge(val at: String, val text: String, val refId: String?, val kind: NudgeKind)
data class PlanAction(val id: String, val tool: String, val args: Map<String, String>, val label: String)
data class PlannerPlan(val headline: String, val next: List<PlanNext>, val digest: List<PlanDigest>, val nudges: List<PlanNudge>, val actions: List<PlanAction>)

object CloudPlannerPolicy { fun canUpload(explicitOptIn: Boolean, key: String?) = explicitOptIn && !key.isNullOrBlank() }
object PlanCachePolicy { fun isFresh(source: String, signature: String, expectedSignature: String, timestamp: Long, now: Long) = source != "BOOTSTRAP_FALLBACK" && signature.isNotEmpty() && signature == expectedSignature && now - timestamp in 0 until 20 * 60_000L }

object PlanValidator {
    const val MAX_NEXT = 5; const val MAX_DIGEST = 5; const val MAX_NUDGES = 2; const val MAX_ACTIONS = 3
    private val time = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val requiredArgs = mapOf(
        "open_app" to setOf("packageName"), "open_event" to setOf("eventId"), "open_maps" to setOf("eventId"),
        "complete_task" to setOf("taskId"), "add_task" to setOf("title"), "create_calendar_block" to setOf("start", "end", "title"),
        "set_alarm" to setOf("time"), "toggle_dnd" to setOf("state"), "mark_gym_done" to emptySet(),
    )
    private val optionalArgs = mapOf("add_task" to setOf("due"), "toggle_dnd" to setOf("until"))

    fun validate(raw: PlannerPlan, snapshot: PlannerSnapshot, profile: PlannerProfile, now: Instant): PlannerPlan {
        val refs = snapshot.knownRefIds(); val events = snapshot.events.associateBy { it.id }; val tasks = snapshot.tasks.associateBy { it.id }
        val zone = ZoneId.of(snapshot.zoneId)
        val next = raw.next.mapNotNull { item ->
            val text = SafePlannerText.clean(item.text, 80) ?: return@mapNotNull null
            if (!grounded(item.kind, item.refId, events.keys, tasks.keys)) return@mapNotNull null
            var at = item.time
            if (item.kind in setOf(NextKind.EVENT, NextKind.PREP) && item.refId != null) at = events[item.refId]?.let { formatTime(it.beginEpochMillis, zone) }
            if (at != null && (!time.matches(at) || !isNext24(at, now, zone))) return@mapNotNull null
            item.copy(time = at, text = text)
        }.take(MAX_NEXT)
        val digest = raw.digest.mapNotNull { item -> SafePlannerText.clean(item.text, 120)?.let { item.copy(refIds = item.refIds.filter(refs::contains).distinct().take(10), text = it) } }.take(MAX_DIGEST)
        val today = now.atZone(zone).toLocalDate()
        var remaining = (profile.proactiveBudget - snapshot.activity.last20.count { it.kind == ActivityKind.PROACTIVE_NUDGE && Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate() == today }).coerceAtLeast(0)
        val nudges = raw.nudges.mapNotNull { item ->
            if (!time.matches(item.at) || !isNext24(item.at, now, zone) || !groundedNudge(item, events.keys, tasks.keys)) return@mapNotNull null
            val text = SafePlannerText.clean(item.text, 100) ?: return@mapNotNull null
            val windDown = profile.bedtime.minusMinutes(profile.windDownMinutes.toLong())
            if (insideSleep(item.at, profile) && !(item.kind == NudgeKind.WIND_DOWN && LocalTime.parse(item.at) == windDown)) return@mapNotNull null
            if (item.kind != NudgeKind.PREP && item.kind != NudgeKind.WIND_DOWN && remaining-- <= 0) return@mapNotNull null
            item.copy(text = text)
        }.take(MAX_NUDGES)
        val actions = raw.actions.mapNotNull { validateAction(it, snapshot) }.distinctBy { it.id }.take(MAX_ACTIONS)
        return PlannerPlan(SafePlannerText.clean(raw.headline, 90) ?: "Your plan is ready", next, digest, nudges, actions)
    }

    fun validateAction(action: PlanAction, snapshot: PlannerSnapshot): PlanAction? {
        val required = requiredArgs[action.tool] ?: return null; val optional = optionalArgs[action.tool].orEmpty()
        if (!action.args.keys.containsAll(required) || action.args.keys.any { it !in required && it !in optional }) return null
        val id = SafePlannerText.clean(action.id, 40) ?: return null; val label = SafePlannerText.clean(action.label, 40) ?: return null
        if (action.args.values.any { SafePlannerText.clean(it, 200) == null }) return null
        val events = snapshot.events.associateBy { it.id }; val tasks = snapshot.tasks.associateBy { it.id }
        when (action.tool) {
            "open_app" -> if (!packageName.matches(action.args.getValue("packageName")) || action.args.getValue("packageName") !in snapshot.allowedAppPackages) return null
            "open_event", "open_maps" -> if (action.args["eventId"] !in events || action.tool == "open_maps" && events[action.args["eventId"]]?.location.isNullOrBlank()) return null
            "complete_task" -> if (tasks[action.args["taskId"]]?.completed != false) return null
            "add_task" -> if (action.args.getValue("title").length > 100 || action.args["due"]?.toLongOrNull()?.let { it <= 0 } == true) return null
            "create_calendar_block" -> {
                val start = action.args["start"]?.toLongOrNull() ?: return null; val end = action.args["end"]?.toLongOrNull() ?: return null
                if (action.args.getValue("title").length > 60 || end <= start || snapshot.freeSlots.none { start >= it.startEpochMillis && end <= it.endEpochMillis }) return null
            }
            "set_alarm" -> if (!time.matches(action.args.getValue("time"))) return null
            "toggle_dnd" -> if (action.args["state"] !in setOf("on", "off") || action.args["until"]?.let { !time.matches(it) } == true) return null
        }
        return action.copy(id = id, label = label, args = action.args.mapValues { (key, value) -> SafePlannerText.clean(value, when { key == "title" && action.tool == "add_task" -> 100; key == "title" -> 60; else -> 200 }) ?: return null })
    }
    private fun grounded(kind: NextKind, ref: String?, events: Set<String>, tasks: Set<String>) = when (kind) {
        NextKind.EVENT, NextKind.PREP -> ref != null && ref in events
        NextKind.TASK -> ref != null && ref in tasks
        NextKind.GYM -> ref == null || ref.startsWith("training:")
        else -> ref == null
    }
    private fun groundedNudge(item: PlanNudge, events: Set<String>, tasks: Set<String>) = when (item.kind) {
        NudgeKind.PREP -> item.refId != null && item.refId in events
        NudgeKind.TASK -> item.refId != null && item.refId in tasks
        NudgeKind.GYM -> item.refId == null || item.refId.startsWith("training:")
        else -> item.refId == null
    }
    private fun formatTime(epoch: Long, zone: ZoneId) = Instant.ofEpochMilli(epoch).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun isNext24(value: String, now: Instant, zone: ZoneId): Boolean {
        val local = LocalTime.parse(value); var at = now.atZone(zone).toLocalDate().atTime(local).atZone(zone)
        if (at.toInstant() < now) at = at.plusDays(1)
        return !at.toInstant().isAfter(now.plus(Duration.ofHours(24)))
    }
    private fun insideSleep(value: String, profile: PlannerProfile): Boolean {
        val candidate = LocalTime.parse(value)
        return if (profile.bedtime <= profile.wakeTime) candidate >= profile.bedtime || candidate < profile.wakeTime else candidate >= profile.bedtime && candidate < profile.wakeTime
    }
}

object SafePlannerText {
    private val forbidden = Regex("(?i)(https?://|intent:|javascript:|content:|file:|data:|android-app:)")
    fun clean(value: String, max: Int): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || forbidden.containsMatchIn(trimmed) || trimmed.any { it == '\n' || it == '\r' || it.code < 0x20 }) return null
        return trimmed.take(max)
    }
}

object FallbackPlanner {
    fun create(snapshot: PlannerSnapshot, profile: PlannerProfile, now: Instant): PlannerPlan {
        val zone = ZoneId.of(snapshot.zoneId); val entries = mutableListOf<PlanNext>()
        snapshot.events.firstOrNull()?.let { entries += PlanNext(it.id, Instant.ofEpochMilli(it.beginEpochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), it.title.take(80), NextKind.EVENT) }
        snapshot.tasks.firstOrNull()?.let { entries += PlanNext(it.id, it.dueEpochMillis?.let { due -> Instant.ofEpochMilli(due).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }, it.title.take(80), NextKind.TASK) }
        snapshot.weather?.let { entries += PlanNext(null, null, it.take(80), NextKind.OTHER) }
        entries += PlanNext("training:gymWeeklyTarget", null, "Gym progress: target ${profile.gym.weeklyTarget} this week", NextKind.GYM)
        return PlannerPlan(if (snapshot.events.isNotEmpty()) "Next up: ${snapshot.events.first().title}".take(90) else "A clear view of the next 24 hours", entries.take(5), emptyList(), meetingPrep(snapshot, profile, zone), emptyList())
    }
    fun meetingPrep(snapshot: PlannerSnapshot, profile: PlannerProfile, zone: ZoneId): List<PlanNudge> = snapshot.events.filter { it.busy && !it.allDay }.take(2).map {
        val at = Instant.ofEpochMilli(it.beginEpochMillis).minusSeconds(profile.meetingPrepMinutes * 60L).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        PlanNudge(at, "Prepare for ${it.title}".take(100), it.id, NudgeKind.PREP)
    }
}

object PlannerSnapshotCodec {
    const val HARD_CAP_BYTES = 16 * 1024
    fun encode(value: PlannerSnapshot): String {
        val json = JSONObject().put("generatedAt", value.generatedAtEpochMillis).put("zone", value.zoneId)
            .put("events", JSONArray(value.events.map(::eventJson))).put("tasks", JSONArray(value.tasks.map(::taskJson)))
            .put("weather", value.weather ?: JSONObject.NULL).put("usage", JSONObject(value.usageMinutesByPackage))
            .put("training", JSONObject(value.trainingTargets)).put("healthAvailable", false)
            .put("freeSlots", JSONArray(value.freeSlots.map(::slotJson))).put("gymSlots", JSONArray(value.gymCandidateSlots.map(::slotJson)))
            .put("metrics", JSONObject().put("meetingCount", value.metrics.meetingCount).put("meetingMinutes", value.metrics.meetingMinutes).put("longest", value.metrics.longestMeetingStretchMinutes).put("tasksDue", value.metrics.tasksDue).put("tasksOverdue", value.metrics.tasksOverdue))
            .put("notificationBatchCount", value.notificationBatchCount)
            .put("activity", JSONObject().put("counts", JSONObject(value.activity.counts.mapKeys { it.key.name })).put("last20", JSONArray(value.activity.last20.map { JSONObject().put("id", it.id).put("at", it.timestampEpochMillis).put("kind", it.kind.name).put("ref", it.refId ?: JSONObject.NULL) })))
            .put("profile", profileJson(value.profile)).put("allowedAppPackages", JSONArray(value.allowedAppPackages.sorted()))
            .put("freshness", freshnessJson(value))
        return json.toString().also { require(it.toByteArray().size <= HARD_CAP_BYTES) { "Planner snapshot exceeds 16KB" } }
    }
    fun decode(raw: String): PlannerSnapshot {
        require(raw.toByteArray().size <= HARD_CAP_BYTES)
        val j = JSONObject(raw); val events = j.getJSONArray("events").objects().map { e -> CalendarEvent(e.getString("id"), e.getLong("providerId"), e.getLong("begin"), e.getLong("end"), e.getString("title"), e.getString("location"), e.getBoolean("allDay"), e.getBoolean("busy"), e.getLong("calendarId"), e.getString("calendarName"), e.getString("accountName"), e.getBoolean("online")) }
        val tasks = j.getJSONArray("tasks").objects().map { t -> LocalTask(t.getString("id"), t.getString("title"), t.optLongOrNull("due"), t.getBoolean("important"), t.getBoolean("completed"), t.getLong("created")) }
        val metrics = j.getJSONObject("metrics"); val freshness = j.getJSONObject("freshness")
        val activity = j.getJSONObject("activity"); val records = activity.getJSONArray("last20").objects().map { ActivityRecord(it.getString("id"), it.getLong("at"), ActivityKind.valueOf(it.getString("kind")), it.optString("ref").takeIf(String::isNotBlank)) }
        val countsJson = activity.getJSONObject("counts"); val counts = ActivityKind.entries.associateWith { countsJson.optInt(it.name) }
        return PlannerSnapshot(j.getLong("generatedAt"), j.getString("zone"), events, tasks, j.optString("weather").takeIf(String::isNotBlank), j.getJSONObject("usage").intMap(), j.getJSONObject("training").intMap(), false, j.getJSONArray("freeSlots").slots(), j.getJSONArray("gymSlots").slots(), PlannerMetrics(metrics.getInt("meetingCount"), metrics.getInt("meetingMinutes"), metrics.getInt("longest"), metrics.getInt("tasksDue"), metrics.getInt("tasksOverdue")), j.getInt("notificationBatchCount"), ActivitySummary(counts, records), SnapshotFreshness(freshness.optLongOrNull("calendar"), freshness.optLongOrNull("weather"), freshness.getLong("tasks"), freshness.optLongOrNull("usage"), source(freshness, "calendarStatus"), source(freshness, "weatherStatus"), source(freshness, "usageStatus")), profile(j.getJSONObject("profile")), j.getJSONArray("allowedAppPackages").strings().toSet())
    }
    private fun eventJson(e: CalendarEvent) = JSONObject().put("id", e.id).put("providerId", e.providerEventId).put("begin", e.beginEpochMillis).put("end", e.endEpochMillis).put("title", e.title).put("location", e.location).put("allDay", e.allDay).put("busy", e.busy).put("calendarId", e.calendarId).put("calendarName", e.calendarName).put("accountName", e.accountName).put("online", e.onlineMeeting)
    private fun taskJson(t: LocalTask) = JSONObject().put("id", t.id).put("title", t.title).put("due", t.dueEpochMillis ?: JSONObject.NULL).put("important", t.important).put("completed", t.completed).put("created", t.createdAtEpochMillis)
    private fun slotJson(s: TimeSlot) = JSONObject().put("start", s.startEpochMillis).put("end", s.endEpochMillis)
    private fun profileJson(p: PlannerProfile) = JSONObject().put("bedtime", p.bedtime.toString()).put("wakeTime", p.wakeTime.toString()).put("windDownMinutes", p.windDownMinutes).put("meetingPrepMinutes", p.meetingPrepMinutes).put("proactiveBudget", p.proactiveBudget).put("hardConstraints", JSONArray(p.hardConstraints)).put("goals", JSONArray(p.goals)).put("importantApps", JSONArray(p.importantApps.sorted())).put("calendarSelectionMode", p.calendarSelectionMode.name).put("selectedCalendarIds", JSONArray(p.selectedCalendarIds.sorted())).put("includeFreeEvents", p.includeFreeEvents).put("gym", JSONObject().put("weeklyTarget", p.gym.weeklyTarget).put("durationMinutes", p.gym.durationMinutes).put("travelMinutes", p.gym.travelMinutes).put("preferredSlots", JSONArray(p.gym.preferredSlots)))
    private fun profile(j: JSONObject): PlannerProfile { val gym = j.getJSONObject("gym"); return PlannerProfile(LocalTime.parse(j.getString("bedtime")), LocalTime.parse(j.getString("wakeTime")), j.getInt("windDownMinutes"), GymProfile(gym.getInt("weeklyTarget"), gym.getInt("durationMinutes"), gym.getInt("travelMinutes"), gym.getJSONArray("preferredSlots").strings()), j.getInt("meetingPrepMinutes"), j.getJSONArray("hardConstraints").strings(), j.getJSONArray("goals").strings(), j.getInt("proactiveBudget"), j.getJSONArray("importantApps").strings().toSet(), j.getBoolean("includeFreeEvents"), CalendarSelectionMode.valueOf(j.getString("calendarSelectionMode")), j.getJSONArray("selectedCalendarIds").longs().toSet()) }
    private fun freshnessJson(v: PlannerSnapshot): JSONObject {
        fun age(at: Long?): Any = at?.let { (v.generatedAtEpochMillis - it).coerceAtLeast(0) / 60_000L } ?: JSONObject.NULL
        return JSONObject().put("calendar", v.freshness.calendarEpochMillis ?: JSONObject.NULL).put("weather", v.freshness.weatherEpochMillis ?: JSONObject.NULL).put("tasks", v.freshness.tasksEpochMillis).put("usage", v.freshness.usageEpochMillis ?: JSONObject.NULL)
            .put("calendarStatus", v.freshness.calendarStatus.name).put("weatherStatus", v.freshness.weatherStatus.name).put("usageStatus", v.freshness.usageStatus.name)
            .put("calendarAgeMinutes", age(v.freshness.calendarEpochMillis)).put("weatherAgeMinutes", age(v.freshness.weatherEpochMillis)).put("taskAgeMinutes", age(v.freshness.tasksEpochMillis)).put("usageAgeMinutes", age(v.freshness.usageEpochMillis))
    }
    private fun source(j: JSONObject, key: String) = runCatching { SourceStatus.valueOf(j.getString(key)) }.getOrDefault(SourceStatus.UNAVAILABLE)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.longs() = (0 until length()).map { getLong(it) }
    private fun JSONArray.slots() = objects().map { TimeSlot(it.getLong("start"), it.getLong("end")) }
    private fun JSONObject.optLongOrNull(key: String) = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.intMap() = keys().asSequence().associateWith { getInt(it) }
}
