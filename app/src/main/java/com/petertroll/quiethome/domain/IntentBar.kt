package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The deliberately small, privacy-bounded input to the optional intent classifier. */
data class IntentApp(val label: String, val packageName: String, val activityName: String? = null)
data class IntentEvent(val id: String, val title: String, val beginEpochMillis: Long, val endEpochMillis: Long, val locationPresent: Boolean)
data class IntentContext(
    val text: String,
    val zoneId: String,
    val nowEpochMillis: Long,
    val apps: List<IntentApp>,
    val tasks: List<LocalTask>,
    val events: List<IntentEvent>,
    val freeSlots: List<TimeSlot>,
    val allowedTools: Set<String> = IntentCapabilities.tools(dndAvailable = true),
)

sealed interface IntentParseResult {
    data class Action(val value: PlanAction) : IntentParseResult
    data class OpenApp(val app: IntentApp) : IntentParseResult
    data object OpenPlan : IntentParseResult
    data object OpenApps : IntentParseResult
    data class Ambiguous(val kind: String) : IntentParseResult
    data class Invalid(val reason: String) : IntentParseResult
    data object Unmatched : IntentParseResult
}

object IntentInputPolicy {
    const val MAX_CHARS = 240
    fun clean(value: String): String? {
        if (value.length > MAX_CHARS || value.any { java.lang.Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }) return null
        return value.trim().takeIf(String::isNotEmpty)
    }
}

object IntentCapabilities {
    private val baseTools = linkedSetOf("open_app", "open_event", "open_maps", "complete_task", "add_task", "create_calendar_block", "set_alarm", "mark_gym_done")
    fun tools(dndAvailable: Boolean): Set<String> = if (dndAvailable) baseTools + "toggle_dnd" else baseTools
}

/** Blocks cloud classification only when command-shaped wording has an ambiguous local reference. */
object CloudIntentPreflight {
    private val appCue = Regex("(?i)\\b(?:open|launch|start|use|switch|go)\\b")
    private val taskCue = Regex("(?i)\\b(?:done|finish|complete|check|mark)\\b")
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val ignored = setOf("app", "apps", "application", "task", "tasks", "please", "open", "launch", "start", "use", "switch", "go", "to", "done", "finish", "complete", "check", "off", "mark", "the", "a", "an", "my", "now", "for", "me", "one", "of")

    fun ambiguity(raw: String, apps: List<IntentApp>, tasks: List<LocalTask>): IntentParseResult.Ambiguous? {
        val text = IntentInputPolicy.clean(raw) ?: return null
        if (appCue.containsMatchIn(text) && referencedCount(text, apps.map(IntentApp::label)) > 1) return IntentParseResult.Ambiguous("app")
        val activeTitles = tasks.filterNot(LocalTask::completed).map(LocalTask::title)
        if (taskCue.containsMatchIn(text) && referencedCount(text, activeTitles) > 1) return IntentParseResult.Ambiguous("task")
        return null
    }

    private fun referencedCount(text: String, names: List<String>): Int {
        val lower = text.lowercase(Locale.ROOT)
        val fullNames = names.count { name ->
            val candidate = name.lowercase(Locale.ROOT).trim()
            candidate.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])").containsMatchIn(lower)
        }
        if (fullNames > 0) return fullNames
        val inputWords = words.findAll(lower).map { it.value }.filterNot(ignored::contains).toSet()
        if (inputWords.isEmpty()) return 0
        return names.count { name ->
            words.findAll(name.lowercase(Locale.ROOT)).map { it.value }.any { it.length >= 2 && it in inputWords }
        }
    }
}

/** Deterministic commands always win; ambiguity is surfaced instead of guessed. */
object LocalIntentParser {
    private val alarm = Regex("(?i)^alarm\\s+((?:[01]\\d|2[0-3]):[0-5]\\d)$")
    private val addTask = Regex("(?i)^(?:task|add task)\\s+(.+)$")
    private val done = Regex("(?i)^done\\s+(.+)$")

    fun parse(raw: String, apps: List<IntentApp>, tasks: List<LocalTask>): IntentParseResult {
        val text = IntentInputPolicy.clean(raw) ?: return IntentParseResult.Invalid("Use one line of at most 240 characters.")
        if (text.equals("plan", true)) return IntentParseResult.OpenPlan
        if (text.equals("apps", true)) return IntentParseResult.OpenApps
        if (text.equals("gym done", true)) return IntentParseResult.Action(PlanAction("intent:gym", "mark_gym_done", emptyMap(), "Mark gym done"))
        alarm.matchEntire(text)?.let { return IntentParseResult.Action(PlanAction("intent:alarm", "set_alarm", mapOf("time" to it.groupValues[1]), "Set alarm")) }
        addTask.matchEntire(text)?.let {
            val title = SafePlannerText.clean(it.groupValues[1], 100) ?: return IntentParseResult.Invalid("Task title is not allowed.")
            return IntentParseResult.Action(PlanAction("intent:add-task", "add_task", mapOf("title" to title), "Add task"))
        }
        done.matchEntire(text)?.let {
            val query = it.groupValues[1].trim().lowercase(Locale.ROOT)
            val active = tasks.filterNot(LocalTask::completed)
            val exact = active.filter { task -> task.title.lowercase(Locale.ROOT) == query }
            val candidates = if (exact.isNotEmpty()) exact else active.filter { task -> task.title.lowercase(Locale.ROOT).contains(query) }
            return when (candidates.size) {
                1 -> IntentParseResult.Action(PlanAction("intent:done", "complete_task", mapOf("taskId" to candidates.single().id), "Complete task"))
                0 -> IntentParseResult.Invalid("No active task matches.")
                else -> IntentParseResult.Ambiguous("task")
            }
        }
        val appQuery = text.removePrefixIgnoreCase("open ").trim()
        val explicitOpen = text.startsWith("open ", true)
        if (appQuery.isNotEmpty()) {
            val visible = apps.distinctBy { it.packageName to it.activityName }
            val query = appQuery.lowercase(Locale.ROOT)
            val exact = visible.filter { it.label.lowercase(Locale.ROOT) == query }
            val candidates = if (exact.isNotEmpty()) exact else visible.filter { it.label.lowercase(Locale.ROOT).contains(query) }
            if (candidates.size == 1) return IntentParseResult.OpenApp(candidates.single())
            if (candidates.size > 1) return IntentParseResult.Ambiguous("app")
            if (explicitOpen) return IntentParseResult.Invalid("No visible app matches.")
        }
        return IntentParseResult.Unmatched
    }

    private fun String.removePrefixIgnoreCase(prefix: String) = if (startsWith(prefix, true)) substring(prefix.length) else this
}

object IntentPayloadCodec {
    const val HARD_CAP_BYTES = 8 * 1024
    data class Encoded(val json: String, val transmitted: IntentContext)

    fun encode(value: IntentContext): String = minimize(value).json

    /** Returns both the bytes sent and the exact facts against which a response must be validated. */
    fun minimize(value: IntentContext): Encoded {
        require(IntentInputPolicy.clean(value.text) == value.text.trim())
        val apps = value.apps.take(100).toMutableList()
        val tasks = value.tasks.filterNot { it.completed }.take(50).toMutableList()
        val events = value.events.take(24).toMutableList()
        val slots = value.freeSlots.take(24).toMutableList()
        fun json() = JSONObject().put("text", value.text.trim()).put("zone", value.zoneId.take(80)).put("now", value.nowEpochMillis)
            .put("visibleApps", JSONArray(apps.map { JSONObject().put("label", it.label.take(80)).put("package", it.packageName.take(200)) }))
            .put("activeTasks", JSONArray(tasks.map { JSONObject().put("id", it.id.take(80)).put("title", it.title.take(100)) }))
            .put("currentEvents", JSONArray(events.map { JSONObject().put("id", it.id.take(80)).put("title", it.title.take(100)).put("begin", it.beginEpochMillis).put("end", it.endEpochMillis).put("locationPresent", it.locationPresent) }))
            .put("freeSlots", JSONArray(slots.map { JSONObject().put("start", it.startEpochMillis).put("end", it.endEpochMillis) }))
            .put("allowedTools", JSONArray(value.allowedTools)).toString()
        var result = json()
        while (result.toByteArray(Charsets.UTF_8).size > HARD_CAP_BYTES) {
            when {
                apps.isNotEmpty() -> apps.removeAt(apps.lastIndex)
                tasks.isNotEmpty() -> tasks.removeAt(tasks.lastIndex)
                events.isNotEmpty() -> events.removeAt(events.lastIndex)
                slots.isNotEmpty() -> slots.removeAt(slots.lastIndex)
                else -> error("Minimal intent payload exceeds 8 KiB")
            }
            result = json()
        }
        return Encoded(result, value.copy(apps = apps, tasks = tasks, events = events, freeSlots = slots))
    }

    fun restrictValidationSnapshot(snapshot: PlannerSnapshot, transmitted: IntentContext): PlannerSnapshot {
        val taskIds = transmitted.tasks.mapTo(mutableSetOf(), LocalTask::id)
        val eventIds = transmitted.events.mapTo(mutableSetOf(), IntentEvent::id)
        return snapshot.copy(
            tasks = snapshot.tasks.filter { it.id in taskIds },
            events = snapshot.events.filter { it.id in eventIds },
            freeSlots = transmitted.freeSlots,
            allowedAppPackages = transmitted.apps.mapTo(mutableSetOf(), IntentApp::packageName),
        )
    }
}
