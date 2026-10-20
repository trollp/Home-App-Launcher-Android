package com.petertroll.quiethome

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.petertroll.quiethome.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

interface CalendarQuery { fun instances(begin: Long, end: Long): Result<List<CalendarRow>>; fun calendars(): Result<List<CalendarSource>> }
class AndroidCalendarQuery(private val resolver: ContentResolver) : CalendarQuery {
    override fun instances(begin: Long, end: Long): Result<List<CalendarRow>> = runCatching {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, begin); ContentUris.appendId(it, end) }.build()
        resolver.query(uri, INSTANCE_PROJECTION, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor -> cursorRows(cursor) } ?: emptyList()
    }
    override fun calendars(): Result<List<CalendarSource>> = runCatching {
        resolver.query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_PROJECTION, null, null, "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c ->
            buildList { while (c.moveToNext()) add(CalendarSource(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) != 0)) }
        } ?: emptyList()
    }
    private fun cursorRows(c: Cursor) = buildList { while (c.moveToNext()) add(CalendarRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getInt(5) != 0, availability(c.getInt(6)), attendee(c.getInt(7)), c.getLong(8), c.getString(9), c.getString(10), c.getString(11))) }
    // Calendar provider uses integer 3 for out-of-office (not exposed as a public SDK constant on all API stubs).
    private fun availability(v: Int) = when (v) { CalendarContract.Instances.AVAILABILITY_FREE -> CalendarAvailability.FREE; CalendarContract.Instances.AVAILABILITY_BUSY -> CalendarAvailability.BUSY; CalendarContract.Instances.AVAILABILITY_TENTATIVE -> CalendarAvailability.TENTATIVE; 3 -> CalendarAvailability.OUT_OF_OFFICE; else -> CalendarAvailability.UNKNOWN }
    private fun attendee(v: Int) = when (v) { CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> CalendarAttendee.DECLINED; CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> CalendarAttendee.ACCEPTED; CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> CalendarAttendee.TENTATIVE; else -> CalendarAttendee.NONE }
    companion object {
        val INSTANCE_PROJECTION = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.OWNER_ACCOUNT, CalendarContract.Instances.DESCRIPTION)
        val CALENDAR_PROJECTION = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.VISIBLE)
    }
}

sealed class CalendarCollection { data class Available(val events: List<CalendarEvent>) : CalendarCollection(); data object PermissionMissing : CalendarCollection(); data object NoSyncedCalendars : CalendarCollection(); data class Failed(val reason: String) : CalendarCollection() }
class CalendarProviderCollector(private val context: Context, private val query: CalendarQuery = AndroidCalendarQuery(context.contentResolver)) {
    fun collect(now: Instant, profile: PlannerProfile): Result<CalendarCollection> = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return@runCatching CalendarCollection.PermissionMissing
        val calendars = query.calendars().getOrThrow().filter { it.visible }
        if (calendars.isEmpty()) return@runCatching CalendarCollection.NoSyncedCalendars
        val selected = if (profile.calendarSelectionMode == CalendarSelectionMode.ALL_VISIBLE) calendars.map { it.id }.toSet() else profile.selectedCalendarIds
        CalendarCollection.Available(CalendarRows.collect(query.instances(now.toEpochMilli(), now.plus(Duration.ofHours(24)).toEpochMilli()).getOrThrow(), profile.includeFreeEvents, selected))
    }.recover { CalendarCollection.Failed(it.message ?: "Calendar provider unavailable") }
}
class CalendarChangeObserver(context: Context, private val changed: () -> Unit) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val resolver = context.contentResolver; private val handler = Handler(Looper.getMainLooper()); private var registered = false
    private val dispatch = Runnable { changed() }
    fun start() { if (!registered) { resolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, this); resolver.registerContentObserver(CalendarContract.Calendars.CONTENT_URI, true, this); registered = true } }
    fun stop() { if (registered) { resolver.unregisterContentObserver(this); registered = false } }
    override fun onChange(selfChange: Boolean, uri: Uri?) { handler.removeCallbacks(dispatch); handler.postDelayed(dispatch, 750) }
}

object PlannerRequestFactory {
    private const val SYSTEM = "You are the embedded daily planner for one owner in Stockholm. Use only supplied snapshot facts and reference IDs. Return concise JSON matching the strict schema. Respect sleep hours, proactive budget, hard constraints, and action limits. Never invent facts, URLs, intent URIs, secrets, diagnoses, shame, or unsupported actions."
    private fun system(snapshotJson: String): String {
        val profile = runCatching { JSONObject(snapshotJson).optJSONObject("profile") }.getOrNull()
        fun lines(key: String): String = profile?.optJSONArray(key)?.let { values ->
            (0 until values.length()).joinToString("\n") { "- ${values.optString(it)}" }
        }.orEmpty().ifBlank { "- None supplied" }
        return "$SYSTEM\n\nOwner hard constraints (verbatim):\n${lines("hardConstraints")}\n\nOwner goals (verbatim):\n${lines("goals")}"
    }
    fun body(snapshotJson: String, model: String = "gpt-5-mini", effort: String = "low", tokens: Int = 4000): String = JSONObject()
        .put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system(snapshotJson))).put(JSONObject().put("role", "user").put("content", snapshotJson)))
        .put("reasoning_effort", effort).put("max_completion_tokens", tokens).put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", "quiet_home_plan").put("strict", true).put("schema", schema()))).toString()
    private fun schema(): JSONObject {
        fun text(max: Int, nullable: Boolean = false): Any = JSONObject().put("type", if (nullable) JSONArray(listOf("string", "null")) else "string").put("maxLength", max)
        fun array(item: JSONObject, max: Int) = JSONObject().put("type", "array").put("items", item).put("maxItems", max)
        fun obj(properties: JSONObject, required: List<String>) = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
        val next = obj(JSONObject().put("refId", text(80, true)).put("time", text(5, true)).put("text", text(80)).put("kind", JSONObject().put("type", "string").put("enum", JSONArray(NextKind.entries.map { it.name }))), listOf("refId", "time", "text", "kind"))
        val digest = obj(JSONObject().put("refIds", array(JSONObject().put("type", "string"), 10)).put("text", text(120)), listOf("refIds", "text"))
        val nudge = obj(JSONObject().put("at", text(5)).put("text", text(100)).put("refId", text(80, true)).put("kind", JSONObject().put("type", "string").put("enum", JSONArray(NudgeKind.entries.map { it.name }))), listOf("at", "text", "refId", "kind"))
        fun action(tool: String, args: LinkedHashMap<String, JSONObject>, required: List<String> = args.keys.toList()) = obj(
            JSONObject().put("id", text(40)).put("tool", JSONObject().put("const", tool)).put("args", obj(JSONObject().apply { args.forEach(::put) }, required)).put("label", text(40)),
            listOf("id", "tool", "args", "label")
        )
        fun arg(max: Int = 200) = JSONObject().put("type", "string").put("maxLength", max)
        val actions = listOf(
            action("open_app", linkedMapOf("packageName" to arg())),
            action("open_event", linkedMapOf("eventId" to arg(80))),
            action("open_maps", linkedMapOf("eventId" to arg(80))),
            action("complete_task", linkedMapOf("taskId" to arg(80))),
            action("add_task", linkedMapOf("title" to arg(100))),
            action("add_task", linkedMapOf("title" to arg(100), "due" to arg(20))),
            action("create_calendar_block", linkedMapOf("start" to arg(20), "end" to arg(20), "title" to arg(60))),
            action("set_alarm", linkedMapOf("time" to arg(5))),
            action("toggle_dnd", linkedMapOf("state" to arg(3))),
            action("toggle_dnd", linkedMapOf("state" to arg(3), "until" to arg(5))),
            action("mark_gym_done", linkedMapOf()),
        )
        val action = JSONObject().put("anyOf", JSONArray(actions))
        return obj(JSONObject().put("headline", text(90)).put("next", array(next, PlanValidator.MAX_NEXT)).put("digest", array(digest, PlanValidator.MAX_DIGEST)).put("nudges", array(nudge, PlanValidator.MAX_NUDGES)).put("actions", array(action, PlanValidator.MAX_ACTIONS)), listOf("headline", "next", "digest", "nudges", "actions"))
    }
}
object PlannerResponseParser { fun content(raw: String): String { val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0); require(choice.optString("finish_reason") != "length") { "Planner response was truncated" }; return choice.getJSONObject("message").optString("content").trim().also { require(it.isNotEmpty()) { "Planner returned empty content" } } } }
class PlannerClient {
    fun create(apiKey: String, snapshot: PlannerSnapshot, profile: PlannerProfile): PlannerPlan {
        require(apiKey.isNotBlank()); val connection = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        connection.apply { connectTimeout = 10_000; readTimeout = 30_000; requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; setRequestProperty("Authorization", "Bearer $apiKey"); setRequestProperty("Content-Type", "application/json") }
        try {
            val body = PlannerRequestFactory.body(PlannerSnapshotCodec.encode(snapshot), profile.plannerModel.name, profile.plannerModel.reasoningEffort, profile.plannerModel.maxCompletionTokens)
            connection.outputStream.use { it.write(body.toByteArray()) }; require(connection.responseCode in 200..299) { "OpenAI rejected request (${connection.responseCode})" }; require(connection.contentType?.contains("application/json", true) == true)
            val content = PlannerResponseParser.content(connection.inputStream.use { BoundedNetworkReader.readUtf8(it, 128_000) })
            return PlanJson.decode(content)
        } finally { connection.disconnect() }
    }
}

object PlanJson {
    fun encode(p: PlannerPlan) = JSONObject().put("headline", p.headline).put("next", JSONArray(p.next.map { JSONObject().put("refId", it.refId ?: JSONObject.NULL).put("time", it.time ?: JSONObject.NULL).put("text", it.text).put("kind", it.kind.name) })).put("digest", JSONArray(p.digest.map { JSONObject().put("refIds", JSONArray(it.refIds)).put("text", it.text) })).put("nudges", JSONArray(p.nudges.map { JSONObject().put("at", it.at).put("text", it.text).put("refId", it.refId ?: JSONObject.NULL).put("kind", it.kind.name) })).put("actions", JSONArray(p.actions.map { JSONObject().put("id", it.id).put("tool", it.tool).put("args", JSONObject(it.args)).put("label", it.label) })).toString()

    fun decode(raw: String): PlannerPlan {
        require(raw.length <= 64_000)
        val j = JSONObject(raw)
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun JSONObject.stringMap() = keys().asSequence().associateWith { getString(it) }
        fun JSONObject.nullableString(key: String) = if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
        return PlannerPlan(
            j.getString("headline"),
            j.getJSONArray("next").objects().map { PlanNext(it.nullableString("refId"), it.nullableString("time"), it.getString("text"), NextKind.valueOf(it.getString("kind"))) },
            j.getJSONArray("digest").objects().map { PlanDigest(it.getJSONArray("refIds").strings(), it.getString("text")) },
            j.getJSONArray("nudges").objects().map { PlanNudge(it.getString("at"), it.getString("text"), it.nullableString("refId"), NudgeKind.valueOf(it.getString("kind"))) },
            j.getJSONArray("actions").objects().map { PlanAction(it.getString("id"), it.getString("tool"), it.getJSONObject("args").stringMap(), it.getString("label")) },
        )
    }
}

enum class PlanSource { BOOTSTRAP_FALLBACK, LOCAL_REFRESH, CLOUD_REFRESH }
data class StoredPlan(val plan: PlannerPlan, val timestamp: Long, val source: PlanSource, val snapshotSignature: String)
class PlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("planner_state", Context.MODE_PRIVATE)
    fun entry(): StoredPlan? = runCatching {
        val raw = prefs.getString("plan", null) ?: return null
        StoredPlan(PlanJson.decode(raw), prefs.getLong("timestamp", 0), PlanSource.valueOf(prefs.getString("source", PlanSource.BOOTSTRAP_FALLBACK.name)!!), prefs.getString("snapshotSignature", "").orEmpty())
    }.getOrNull()
    fun load(): Pair<PlannerPlan, Long>? = entry()?.let { it.plan to it.timestamp }
    fun save(plan: PlannerPlan, source: PlanSource = PlanSource.LOCAL_REFRESH, snapshotSignature: String = "", timestamp: Long = System.currentTimeMillis()) { prefs.edit().putString("plan", PlanJson.encode(plan)).putLong("timestamp", timestamp).putString("source", source.name).putString("snapshotSignature", snapshotSignature).apply() }
    fun isFresh(signature: String, now: Long = System.currentTimeMillis()) = entry()?.let { PlanCachePolicy.isFresh(it.source.name, it.snapshotSignature, signature, it.timestamp, now) } == true
    fun invalidate() { prefs.edit().remove("snapshotSignature").apply() }
}
class ActivityLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("planner_activity", Context.MODE_PRIVATE)
    fun load(): List<ActivityRecord> = runCatching { JSONArray(prefs.getString("records", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { j -> runCatching { ActivityRecord(j.getString("id"), j.optLong("at", j.optLong("timestamp")), ActivityKind.valueOf(j.getString("kind")), j.optString("ref", j.optString("refId")).takeIf(String::isNotBlank)) }.getOrNull() } } }.getOrDefault(emptyList())
    fun append(kind: ActivityKind, ref: String? = null): ActivityRecord { val now = Instant.now(); val record = ActivityRecord("log:${now.toEpochMilli()}:${kind.name}", now.toEpochMilli(), kind, ref); val values = ActivityLogPolicy.bound(load() + record, now); prefs.edit().putString("records", JSONArray(values.map { JSONObject().put("id", it.id).put("at", it.timestampEpochMillis).put("kind", it.kind.name).put("ref", it.refId ?: JSONObject.NULL) }).toString()).apply(); return record }
    fun remove(id: String) { val values = load().filterNot { it.id == id }; prefs.edit().putString("records", JSONArray(values.map { JSONObject().put("id", it.id).put("at", it.timestampEpochMillis).put("kind", it.kind.name).put("ref", it.refId ?: JSONObject.NULL) }).toString()).apply() }
}

class PlannerCoordinator(private val context: Context) {
    fun immediateFallback(): PlannerPlan {
        val prefs = PreferencesStore(context).load(); val now = Instant.now()
        val snapshot = SnapshotBuilder.build(now, ZoneId.systemDefault(), prefs.plannerProfile, emptyList(), prefs.localTasks, WeatherRepository(context).cached()?.headline(), emptyList(), ActivityLogStore(context).load(), prefs.routines, calendarStatus = SourceStatus.UNAVAILABLE, allowedAppPackages = allowedPackages(prefs.hiddenPackages))
        return FallbackPlanner.create(snapshot, prefs.plannerProfile, now).also { PlanStore(context).save(it, PlanSource.BOOTSTRAP_FALLBACK) }
    }
    fun refresh(calendarBypass: Boolean = false): PlannerPlan {
        val prefs = PreferencesStore(context).load(); val now = Instant.now(); val collection = CalendarProviderCollector(context).collect(now, prefs.plannerProfile).getOrElse { CalendarCollection.Failed(it.message ?: "Unavailable") }
        val events = (collection as? CalendarCollection.Available)?.events.orEmpty()
        val status = when (collection) { is CalendarCollection.Available -> SourceStatus.AVAILABLE; CalendarCollection.PermissionMissing -> SourceStatus.PERMISSION_MISSING; CalendarCollection.NoSyncedCalendars -> SourceStatus.NO_SYNCED_CALENDARS; is CalendarCollection.Failed -> SourceStatus.UNAVAILABLE }
        val usageSummary = UsageStatsAdapter(context).summary(now.toEpochMilli(), ZoneId.systemDefault())
        val usage = (usageSummary as? UsageSummary.Available)?.todayApps.orEmpty().map { it.packageName to (it.durationMillis / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val weather = WeatherRepository(context).cached()
        val snapshot = SnapshotBuilder.build(now, ZoneId.systemDefault(), prefs.plannerProfile, events, prefs.localTasks, weather?.headline(), usage, ActivityLogStore(context).load(), prefs.routines, status, if (status == SourceStatus.AVAILABLE) now.toEpochMilli() else null, weather?.fetchedAt?.toEpochMilli(), if (usageSummary is UsageSummary.Available) now.toEpochMilli() else null, allowedPackages(prefs.hiddenPackages))
        val signature = snapshot.copy(generatedAtEpochMillis = 0, freshness = snapshot.freshness.copy(calendarEpochMillis = snapshot.freshness.calendarEpochMillis?.let { 0 }, weatherEpochMillis = snapshot.freshness.weatherEpochMillis?.let { 0 }, tasksEpochMillis = 0, usageEpochMillis = snapshot.freshness.usageEpochMillis?.let { 0 })).hashCode().toString()
        val plans = PlanStore(context)
        if (!calendarBypass && plans.isFresh(signature)) {
            val cached = plans.entry()!!.plan
            MeetingPrepScheduler.sync(context, events, prefs.plannerProfile, cached, now.toEpochMilli())
            return cached
        }
        val fallback = FallbackPlanner.create(snapshot, prefs.plannerProfile, now)
        val storedKey = CredentialStore(context).openAiKey(); val key = storedKey.takeIf { CloudPlannerPolicy.canUpload(prefs.openAiEnabled, it) }
        val validated = if (key == null) fallback else runCatching { PlanValidator.validate(PlannerClient().create(key, snapshot, prefs.plannerProfile), snapshot, prefs.plannerProfile, now) }.getOrNull()?.takeUnless { it.next.isEmpty() && it.digest.isEmpty() && it.nudges.isEmpty() && it.actions.isEmpty() } ?: fallback
        val source = if (key != null && validated !== fallback) PlanSource.CLOUD_REFRESH else PlanSource.LOCAL_REFRESH
        plans.save(validated, source, signature)
        MeetingPrepScheduler.sync(context, events, prefs.plannerProfile, validated, now.toEpochMilli())
        return validated
    }
    private fun allowedPackages(hidden: Set<String>): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.filterNot { it in hidden || it == context.packageName }.toSet()
    }
    companion object { fun schedule(context: Context) { val request = PeriodicWorkRequestBuilder<PlannerWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()).build(); WorkManager.getInstance(context).enqueueUniquePeriodicWork("planner-refresh", ExistingPeriodicWorkPolicy.UPDATE, request) } }
}
class PlannerWorker(context: Context, params: WorkerParameters) : Worker(context, params) { override fun doWork(): Result = runCatching { PlannerCoordinator(applicationContext).refresh(); Result.success() }.getOrElse { Result.retry() } }

object MeetingPrepScheduler {
    const val CHANNEL_MEETING = PlannerChannels.MEETING
    const val CHANNEL_PROACTIVE = PlannerChannels.PROACTIVE

    fun sync(context: Context, events: List<CalendarEvent>, profile: PlannerProfile, plan: PlannerPlan, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences("planner_meeting_alarms", Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_MEETING, "Meeting preparation", NotificationManager.IMPORTANCE_DEFAULT))
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_PROACTIVE, "Planner nudges", NotificationManager.IMPORTANCE_DEFAULT))

        val meetings = events.filter { it.busy && !it.allDay && it.beginEpochMillis - profile.meetingPrepMinutes * 60_000L > now }.associateBy { it.id }
        reconcile(alarms, context, prefs.getStringSet("meetingIds", prefs.getStringSet("ids", emptySet())).orEmpty(), meetings.keys, "planner-prep")
        meetings.values.forEach { event ->
            val modelText = plan.next.firstOrNull { it.refId == event.id && it.kind == NextKind.PREP }?.text
                ?: plan.nudges.firstOrNull { it.refId == event.id && it.kind == NudgeKind.PREP }?.text
            val at = event.beginEpochMillis - profile.meetingPrepMinutes * 60_000L
            val operation = pending(context, event.id, "planner-prep", modelText ?: "Prepare for ${event.title}", PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEach
            schedule(alarms, at, operation)
        }

        val zone = ZoneId.systemDefault()
        val proactive = plan.nudges.filter { it.kind != NudgeKind.PREP }.mapNotNull { nudge ->
            val at = nextEpoch(nudge.at, now, zone) ?: return@mapNotNull null
            val id = "${nudge.kind.name}:${nudge.at}:${nudge.refId.orEmpty()}"
            Triple(id, at, nudge.text)
        }.associateBy { it.first }
        reconcile(alarms, context, prefs.getStringSet("nudgeIds", emptySet()).orEmpty(), proactive.keys, "planner-nudge")
        proactive.values.forEach { (id, at, text) ->
            pending(context, id, "planner-nudge", text, PendingIntent.FLAG_UPDATE_CURRENT)?.let { schedule(alarms, at, it) }
        }

        prefs.edit().remove("ids").putStringSet("meetingIds", meetings.keys).putStringSet("nudgeIds", proactive.keys).apply()
    }

    private fun nextEpoch(value: String, now: Long, zone: ZoneId): Long? = runCatching {
        val time = java.time.LocalTime.parse(value)
        val instant = Instant.ofEpochMilli(now)
        var target = instant.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
        if (target.toEpochMilli() <= now) target = target.plus(Duration.ofDays(1))
        target.toEpochMilli().takeIf { it <= now + Duration.ofHours(24).toMillis() }
    }.getOrNull()

    private fun reconcile(alarms: AlarmManager, context: Context, old: Set<String>, wanted: Set<String>, type: String) {
        (old - wanted).forEach { id -> pending(context, id, type, "", PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel) }
    }

    private fun schedule(alarms: AlarmManager, at: Long, operation: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    private fun pending(context: Context, id: String, type: String, label: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        "$type:$id".hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction("$type:$id").putExtra("label", label),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}

class ActionExecutor(private val context: Context) {
    fun execute(action: PlanAction, snapshot: PlannerSnapshot, confirmed: Boolean = false): Result<Unit> = runCatching {
        val currentPrefs = PreferencesStore(context).load(); val currentSnapshot = snapshot.copy(tasks = currentPrefs.localTasks.filterNot { it.completed }, allowedAppPackages = currentAllowed(currentPrefs.hiddenPackages))
        val valid = PlanValidator.validateAction(action, currentSnapshot) ?: error("Action is no longer valid")
        when (valid.tool) {
            "open_app" -> context.packageManager.getLaunchIntentForPackage(valid.args.getValue("packageName"))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity) ?: error("App unavailable")
            "open_event" -> { val event = currentSnapshot.events.first { it.id == valid.args.getValue("eventId") }; context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.providerEventId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "open_maps" -> { val event = currentSnapshot.events.first { it.id == valid.args.getValue("eventId") }; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(event.location)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "complete_task" -> { val id = valid.args.getValue("taskId"); require(currentPrefs.localTasks.any { it.id == id && !it.completed }); PreferencesStore(context).save(currentPrefs.copy(localTasks = LocalTasks.complete(currentPrefs.localTasks, id))) }
            "add_task" -> { require(confirmed) { "Confirmation required" }; val due = valid.args["due"]?.toLongOrNull(); PreferencesStore(context).save(currentPrefs.copy(localTasks = LocalTasks.add(currentPrefs.localTasks, valid.args.getValue("title"), due, false, System.currentTimeMillis()))) }
            "create_calendar_block" -> { require(confirmed) { "Confirmation required" }; val start = valid.args.getValue("start").toLong(); val end = valid.args.getValue("end").toLong(); context.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(CalendarContract.Events.TITLE, valid.args.getValue("title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "set_alarm" -> { require(confirmed) { "Confirmation required" }; val t = java.time.LocalTime.parse(valid.args.getValue("time")); context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "toggle_dnd" -> { require(confirmed) { "Confirmation required" }; val manager = context.getSystemService(NotificationManager::class.java) ?: error("DND unavailable"); require(manager.isNotificationPolicyAccessGranted) { "DND access is not granted" }; manager.setInterruptionFilter(if (valid.args.getValue("state") == "on") NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL) }
            "mark_gym_done" -> ActivityLogStore(context).append(ActivityKind.GYM_ACTUAL)
            else -> error("Unsupported action")
        }
    }
    fun undoCompleteTask(taskId: String): Boolean { val p = PreferencesStore(context).load(); if (p.localTasks.none { it.id == taskId && it.completed }) return false; PreferencesStore(context).save(p.copy(localTasks = LocalTasks.complete(p.localTasks, taskId, false))); return true }
    fun undoGym(recordId: String): Boolean { val store = ActivityLogStore(context); if (store.load().none { it.id == recordId && it.kind == ActivityKind.GYM_ACTUAL }) return false; store.remove(recordId); return true }
    private fun currentAllowed(hidden: Set<String>): Set<String> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.filterNot { it in hidden || it == context.packageName }.toSet()
}
