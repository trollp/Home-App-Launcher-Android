package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

object IntentRequestFactory {
    private const val SYSTEM = "Classify one launcher command using only supplied facts. Return at most one action matching the strict schema. Never guess an ambiguous reference and never invent IDs, packages, times, locations, URLs, or tools. Return null when uncertain."

    fun body(payload: String, model: String = "gpt-5-nano", effort: String = "minimal", tokens: Int = 800, allowedTools: Set<String> = IntentCapabilities.tools(true)): String {
        require(payload.toByteArray(Charsets.UTF_8).size <= IntentPayloadCodec.HARD_CAP_BYTES)
        return JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM)).put(JSONObject().put("role", "user").put("content", payload)))
            .put("reasoning_effort", effort).put("max_completion_tokens", tokens)
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", "quiet_home_intent").put("strict", true).put("schema", schema(allowedTools))))
            .toString()
    }

    private fun schema(allowedTools: Set<String>): JSONObject {
        fun text(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        fun obj(properties: JSONObject, required: Collection<String>) = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
        fun action(tool: String, args: LinkedHashMap<String, JSONObject>, required: Collection<String> = args.keys) = obj(
            JSONObject().put("id", text(40)).put("tool", JSONObject().put("const", tool))
                .put("args", obj(JSONObject().apply { args.forEach { (key, value) -> put(key, value) } }, required)).put("label", text(40)),
            listOf("id", "tool", "args", "label"),
        )
        fun arg(max: Int = 200) = text(max)
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
            action("mark_gym_done", linkedMapOf()),
        ).filter { it.getJSONObject("properties").getJSONObject("tool").getString("const") in allowedTools }
        val nullableAction = JSONObject().put("anyOf", JSONArray().put(JSONObject().put("type", "null")).put(JSONObject().put("anyOf", JSONArray(actions))))
        return obj(JSONObject().put("action", nullableAction), listOf("action"))
    }
}

object IntentResponseParser {
    fun action(content: String): PlanAction? {
        require(content.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        val root = JSONObject(content)
        require(root.keys().asSequence().toSet() == setOf("action"))
        if (root.isNull("action")) return null
        val value = root.getJSONObject("action")
        require(value.keys().asSequence().toSet() == setOf("id", "tool", "args", "label"))
        val args = value.getJSONObject("args")
        return PlanAction(value.getString("id"), value.getString("tool"), args.keys().asSequence().associateWith { args.getString(it) }, value.getString("label"))
    }
}

class IntentClient {
    fun classify(apiKey: String, context: IntentContext, profile: PlannerProfile): PlanAction? {
        require(apiKey.isNotBlank())
        val connection = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        connection.apply { connectTimeout = 10_000; readTimeout = 30_000; requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; setRequestProperty("Authorization", "Bearer $apiKey"); setRequestProperty("Content-Type", "application/json") }
        try {
            val config = profile.intentModel.normalized(ModelConfig("gpt-5-nano", "minimal", 800))
            val encoded = IntentPayloadCodec.minimize(context)
            val body = IntentRequestFactory.body(encoded.json, config.name, config.reasoningEffort, config.maxCompletionTokens, encoded.transmitted.allowedTools)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode in 200..299) { "OpenAI rejected request (${connection.responseCode})" }
            require(connection.contentType?.contains("application/json", true) == true)
            val content = PlannerResponseParser.content(connection.inputStream.use { BoundedNetworkReader.readUtf8(it, 8 * 1024) })
            return IntentResponseParser.action(content)
        } finally { connection.disconnect() }
    }
}

data class CollectedIntentContext(val classifier: IntentContext, val validationSnapshot: PlannerSnapshot)

class IntentContextCollector(private val context: Context) {
    fun collect(text: String, visibleApps: List<IntentApp>): CollectedIntentContext {
        val clean = IntentInputPolicy.clean(text) ?: error("Invalid command")
        val prefs = PreferencesStore(context).load()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val collection = CalendarProviderCollector(context).collect(now, prefs.plannerProfile).getOrNull()
        val events = (collection as? CalendarCollection.Available)?.events.orEmpty()
        val status = when (collection) {
            is CalendarCollection.Available -> SourceStatus.AVAILABLE
            CalendarCollection.PermissionMissing -> SourceStatus.PERMISSION_MISSING
            CalendarCollection.NoSyncedCalendars -> SourceStatus.NO_SYNCED_CALENDARS
            else -> SourceStatus.UNAVAILABLE
        }
        // This snapshot exists only for local validation. The encoded classifier payload below is an explicit allow-list.
        val snapshot = SnapshotBuilder.build(now, zone, prefs.plannerProfile, events, prefs.localTasks, null, emptyList(), emptyList(), prefs.routines, status, if (status == SourceStatus.AVAILABLE) now.toEpochMilli() else null, allowedAppPackages = visibleApps.map { it.packageName }.toSet())
        val allowedTools = IntentCapabilities.tools(context.resources.getBoolean(R.bool.special_access_features_available))
        val candidate = IntentContext(clean, zone.id, now.toEpochMilli(), visibleApps, snapshot.tasks, snapshot.events.map { IntentEvent(it.id, it.title, it.beginEpochMillis, it.endEpochMillis, it.location.isNotBlank()) }, snapshot.freeSlots, allowedTools)
        val classifier = IntentPayloadCodec.minimize(candidate).transmitted
        return CollectedIntentContext(classifier, IntentPayloadCodec.restrictValidationSnapshot(snapshot, classifier))
    }
}
