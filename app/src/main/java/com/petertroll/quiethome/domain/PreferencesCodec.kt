package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

object PreferencesCodec {
    fun decode(raw: String): LauncherPreferences = runCatching {
        val j = JSONObject(raw)
        val legacyName = j.optString("habitName", "Routine").trim().ifEmpty { "Routine" }
        val legacyDays = j.optJSONArray("routineCompletedEpochDays").longs() + j.optLong("habitDoneEpochDay", -1).takeIf { it >= 0 }.let(::listOfNotNull)
        val decoded = j.optJSONArray("routines").routines()
        val migrateLegacy = !j.has("routines") && listOf("habitName", "habitDoneEpochDay", "routineCompletedEpochDays", "routineWeeklyTarget").any(j::has)
        val routines = if (migrateLegacy) listOf(Routine("legacy-routine", legacyName, "Spend time on $legacyName", "Take one small step on $legacyName", j.optInt("routineWeeklyTarget", 3).coerceIn(1, 7), completedEpochDays = legacyDays)) else decoded
        LauncherPreferences(
            favoritePackages = j.optJSONArray("favorites").strings(), hiddenPackages = j.optJSONArray("hidden").strings(), aliases = j.optJSONObject("aliases").stringMap(),
            theme = enum(j.optString("theme", "SYSTEM"), AppTheme.SYSTEM), textScale = j.optDouble("textScale", 1.0).toFloat().coerceIn(.85f, 1.4f), appSort = enum(j.optString("appSort", "ALPHABETICAL"), AppSort.ALPHABETICAL),
            launchCounts = j.optJSONObject("launchCounts").longMap(), lastLaunchEpochMillis = j.optJSONObject("lastLaunchEpochMillis").longMap(), habitName = legacyName,
            habitDoneEpochDay = j.optLong("habitDoneEpochDay", -1), routineCompletedEpochDays = j.optJSONArray("routineCompletedEpochDays").longs(), routineWeeklyTarget = j.optInt("routineWeeklyTarget", 3).coerceIn(1, 7),
            // Cloud planning is a new, explicit consent. Never infer it from a legacy provider/key.
            openAiEnabled = j.optBoolean("plannerCloudOptIn", false), routines = RoutineCollection.sanitize(routines),
            plannerProfile = profile(j.optJSONObject("plannerProfile"), routines), localTasks = j.optJSONArray("localTasks").tasks(),
            legacyPlannerHistory = j.optJSONObject("legacyPlannerHistory")?.toString() ?: if (j.has("feedback") || j.has("reflections")) JSONObject().put("feedback", j.optJSONArray("feedback") ?: JSONArray()).put("reflections", j.optJSONArray("reflections") ?: JSONArray()).toString() else null,
        )
    }.getOrDefault(LauncherPreferences())

    fun encode(v: LauncherPreferences): String = JSONObject()
        .put("favorites", JSONArray(v.favoritePackages.sorted())).put("hidden", JSONArray(v.hiddenPackages.sorted())).put("aliases", jsonMap(v.aliases))
        .put("theme", v.theme.name).put("textScale", v.textScale.toDouble()).put("appSort", v.appSort.name).put("launchCounts", jsonMap(v.launchCounts)).put("lastLaunchEpochMillis", jsonMap(v.lastLaunchEpochMillis))
        .put("habitName", v.habitName).put("habitDoneEpochDay", v.habitDoneEpochDay).put("routineCompletedEpochDays", JSONArray(v.routineCompletedEpochDays.sorted())).put("routineWeeklyTarget", v.routineWeeklyTarget)
        .put("plannerCloudOptIn", v.openAiEnabled).put("routines", JSONArray(RoutineCollection.sanitize(v.routines).map(::routineJson)))
        .put("plannerProfile", profileJson(v.plannerProfile.normalized())).put("localTasks", JSONArray(v.localTasks.take(200).map(::taskJson))).put("legacyPlannerHistory", v.legacyPlannerHistory?.let(::JSONObject) ?: JSONObject.NULL).toString()

    private fun profile(j: JSONObject?, routines: List<Routine>): PlannerProfile {
        if (j == null) return PlannerProfile(goals = routines.filter { it.enabled }.take(5).map { "${it.name}: ${it.weeklyTarget}/week" })
        fun strings(key: String, max: Int) = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.take(max) } ?: emptyList()
        val gym = j.optJSONObject("gym")
        return PlannerProfile(
            bedtime = runCatching { LocalTime.parse(j.optString("bedtime", "23:00")) }.getOrDefault(LocalTime.of(23, 0)), wakeTime = runCatching { LocalTime.parse(j.optString("wake", "07:00")) }.getOrDefault(LocalTime.of(7, 0)), windDownMinutes = j.optInt("windDown", 60),
            gym = GymProfile(gym?.optInt("weeklyTarget", 3) ?: 3, gym?.optInt("duration", 60) ?: 60, gym?.optInt("travel", 15) ?: 15, gym?.optJSONArray("preferredSlots")?.let { a -> (0 until a.length()).map { a.optString(it) }.take(8) }?.ifEmpty { listOf("18:00") } ?: strings("preferredSlots", 8).ifEmpty { listOf("18:00") }, gym?.optInt("healthExerciseMinimumMinutes", 30) ?: 30, gym?.optBoolean("strengthAlwaysQualifies", true) ?: true),
            meetingPrepMinutes = j.optInt("meetingPrep", 10), hardConstraints = strings("hardConstraints", 10), goals = strings("goals", 5), proactiveBudget = j.optInt("proactiveBudget", 3), importantApps = strings("importantApps", 100).toSet(), includeFreeEvents = j.optBoolean("includeFreeEvents"),
            calendarSelectionMode = enum(j.optString("calendarMode", "ALL_VISIBLE"), CalendarSelectionMode.ALL_VISIBLE), selectedCalendarIds = j.optJSONArray("selectedCalendarIds")?.let { a -> (0 until a.length()).map { a.optLong(it) }.toSet() } ?: emptySet(),
            plannerModel = ModelConfig(j.optString("plannerModel", "gpt-5-mini"), j.optString("plannerReasoning", "low"), j.optInt("plannerTokens", 4000)),
            intentModel = ModelConfig(j.optString("intentModel", "gpt-5-nano"), j.optString("intentReasoning", "minimal"), j.optInt("intentTokens", 800)),
        ).normalized()
    }
    private fun profileJson(p: PlannerProfile) = JSONObject().put("bedtime", p.bedtime.toString()).put("wake", p.wakeTime.toString()).put("windDown", p.windDownMinutes)
        .put("gym", JSONObject().put("weeklyTarget", p.gym.weeklyTarget).put("duration", p.gym.durationMinutes).put("travel", p.gym.travelMinutes).put("preferredSlots", JSONArray(p.gym.preferredSlots)).put("healthExerciseMinimumMinutes", p.gym.healthExerciseMinimumMinutes).put("strengthAlwaysQualifies", p.gym.strengthAlwaysQualifies))
        .put("meetingPrep", p.meetingPrepMinutes).put("hardConstraints", JSONArray(p.hardConstraints)).put("goals", JSONArray(p.goals)).put("proactiveBudget", p.proactiveBudget).put("importantApps", JSONArray(p.importantApps.sorted()))
        .put("includeFreeEvents", p.includeFreeEvents).put("calendarMode", p.calendarSelectionMode.name).put("selectedCalendarIds", JSONArray(p.selectedCalendarIds.sorted()))
        .put("plannerModel", p.plannerModel.name).put("plannerReasoning", p.plannerModel.reasoningEffort).put("plannerTokens", p.plannerModel.maxCompletionTokens)
        .put("intentModel", p.intentModel.name).put("intentReasoning", p.intentModel.reasoningEffort).put("intentTokens", p.intentModel.maxCompletionTokens)
    private fun routineJson(r: Routine) = JSONObject().put("id", r.id).put("name", r.name).put("normalAction", r.normalAction).put("smallerAction", r.smallerAction).put("weeklyTarget", r.weeklyTarget).put("enabled", r.enabled).put("completed", JSONArray(r.completedEpochDays.sorted()))
    private fun taskJson(t: LocalTask) = JSONObject().put("id", t.id).put("title", t.title).put("due", t.dueEpochMillis ?: JSONObject.NULL).put("important", t.important).put("completed", t.completed).put("created", t.createdAtEpochMillis)
    private fun JSONArray?.routines() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let { j -> Routine(j.optString("id"), j.optString("name"), j.optString("normalAction"), j.optString("smallerAction"), j.optInt("weeklyTarget", 3), j.optBoolean("enabled", true), j.optJSONArray("completed").longs()) } }
    private fun JSONArray?.tasks() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let { j -> LocalTask(j.optString("id"), j.optString("title"), if (j.isNull("due")) null else j.optLong("due"), j.optBoolean("important"), j.optBoolean("completed"), j.optLong("created")) } }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.id }.take(200)

    private inline fun <reified T : Enum<T>> enum(raw: String, fallback: T) = runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    private fun jsonMap(map: Map<*, *>) = JSONObject().apply { map.entries.sortedBy { it.key.toString() }.forEach { put(it.key.toString(), it.value) } }
    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
    private fun JSONArray?.longs(): Set<Long> = if (this == null) emptySet() else (0 until length()).mapNotNull { optLong(it, -1).takeIf { v -> v >= 0 } }.toSet()
    private fun JSONObject?.stringMap() = if (this == null) emptyMap() else keys().asSequence().mapNotNull { key -> optString(key).takeIf(String::isNotBlank)?.let { key to it } }.toMap()
    private fun JSONObject?.longMap() = if (this == null) emptyMap() else keys().asSequence().mapNotNull { key -> optLong(key, -1).takeIf { it >= 0 }?.let { key to it } }.toMap()
}
