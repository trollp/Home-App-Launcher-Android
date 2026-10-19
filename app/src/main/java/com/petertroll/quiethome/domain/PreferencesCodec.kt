package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject

object PreferencesCodec {
    fun decode(raw: String): LauncherPreferences = runCatching {
        val json = JSONObject(raw)
        val legacyName = json.optString("habitName", "Routine").trim().ifEmpty { "Routine" }
        val legacyDays = json.optJSONArray("routineCompletedEpochDays").longs() +
            json.optLong("habitDoneEpochDay", -1L).takeIf { it >= 0L }.let(::listOfNotNull)
        val decodedRoutines = json.optJSONArray("routines").routines()
        val shouldMigrateLegacy = !json.has("routines") && (
            json.has("habitName") || json.has("habitDoneEpochDay") || json.has("routineCompletedEpochDays") || json.has("routineWeeklyTarget"))
        val routines = if (shouldMigrateLegacy) listOf(Routine(
            id = "legacy-routine", name = legacyName,
            normalAction = "Spend time on $legacyName",
            smallerAction = "Take one small step on $legacyName",
            weeklyTarget = json.optInt("routineWeeklyTarget", 3).coerceIn(1, 7),
            completedEpochDays = legacyDays,
        )) else decodedRoutines
        LauncherPreferences(
            favoritePackages = json.optJSONArray("favorites").strings(),
            hiddenPackages = json.optJSONArray("hidden").strings(),
            aliases = json.optJSONObject("aliases").stringMap(),
            theme = enum(json.optString("theme", "SYSTEM"), AppTheme.SYSTEM),
            textScale = json.optDouble("textScale", 1.0).toFloat().coerceIn(.85f, 1.4f),
            appSort = enum(json.optString("appSort", "ALPHABETICAL"), AppSort.ALPHABETICAL),
            launchCounts = json.optJSONObject("launchCounts").longMap(),
            lastLaunchEpochMillis = json.optJSONObject("lastLaunchEpochMillis").longMap(),
            habitName = legacyName,
            habitDoneEpochDay = json.optLong("habitDoneEpochDay", -1L),
            routineCompletedEpochDays = json.optJSONArray("routineCompletedEpochDays").longs(),
            routineWeeklyTarget = json.optInt("routineWeeklyTarget", 3).coerceIn(1, 7),
            coachDeferredEpochDay = json.optLong("coachDeferredEpochDay", -1L),
            aiProvider = enum(json.optString("aiProvider", "NONE"), AiProvider.NONE),
            aiModel = json.optString("aiModel", "gpt-5-nano").trim().ifEmpty { "gpt-5-nano" },
            outlookClientId = json.optString("outlookClientId", "").trim(),
            routines = RoutineCollection.sanitize(routines),
            capacity = enum(json.optString("capacity", "NORMAL"), Capacity.NORMAL),
            feedback = json.optJSONArray("feedback").feedback(),
            reflections = json.optJSONArray("reflections").reflections(),
            learningEnabled = json.optBoolean("learningEnabled", true),
            proactivity = enum(json.optString("proactivity", "ONCE_DAILY"), Proactivity.ONCE_DAILY),
            suppressedRoutineDays = json.optJSONObject("suppressedRoutineDays").longMap(),
            laterUntilEpochMillis = json.optLong("laterUntilEpochMillis", 0L).coerceAtLeast(0L),
            lastProactiveEpochDay = json.optLong("lastProactiveEpochDay", -1L),
            lastProactiveContextSignature = json.optString("lastProactiveContextSignature", "").take(1000),
            tomorrowProposal = json.optJSONObject("tomorrowProposal")?.let {
                TomorrowProposal.normalized(it.optLong("epochDay"), it.optString("routineId"), it.optString("title"), it.optBoolean("smaller"))
            },
        )
    }.getOrDefault(LauncherPreferences())

    fun encode(value: LauncherPreferences): String = JSONObject()
        .put("favorites", JSONArray(value.favoritePackages.sorted()))
        .put("hidden", JSONArray(value.hiddenPackages.sorted()))
        .put("aliases", jsonMap(value.aliases))
        .put("theme", value.theme.name).put("textScale", value.textScale.toDouble()).put("appSort", value.appSort.name)
        .put("launchCounts", jsonMap(value.launchCounts)).put("lastLaunchEpochMillis", jsonMap(value.lastLaunchEpochMillis))
        .put("habitName", value.habitName).put("habitDoneEpochDay", value.habitDoneEpochDay)
        .put("routineCompletedEpochDays", JSONArray(value.routineCompletedEpochDays.sorted())).put("routineWeeklyTarget", value.routineWeeklyTarget)
        .put("coachDeferredEpochDay", value.coachDeferredEpochDay).put("aiProvider", value.aiProvider.name)
        .put("aiModel", value.aiModel).put("outlookClientId", value.outlookClientId)
        .put("routines", JSONArray(RoutineCollection.sanitize(value.routines).map { routineJson(it) }))
        .put("capacity", value.capacity.name)
        .put("feedback", JSONArray(FeedbackPolicy.bound(value.feedback).map { JSONObject().put("routineId", it.routineId).put("phase", it.dayPhase.name).put("kind", it.kind.name).put("day", it.epochDay) }))
        .put("reflections", JSONArray(ReflectionPolicy.bound(value.reflections).map { JSONObject().put("day", it.epochDay).put("kind", it.kind.name) }))
        .put("learningEnabled", value.learningEnabled).put("proactivity", value.proactivity.name)
        .put("suppressedRoutineDays", jsonMap(value.suppressedRoutineDays)).put("laterUntilEpochMillis", value.laterUntilEpochMillis)
        .put("lastProactiveEpochDay", value.lastProactiveEpochDay)
        .put("lastProactiveContextSignature", value.lastProactiveContextSignature.take(1000))
        .put("tomorrowProposal", value.tomorrowProposal?.let { proposal ->
            TomorrowProposal.normalized(proposal.epochDay, proposal.routineId, proposal.title, proposal.smaller)
        }?.let { JSONObject().put("epochDay", it.epochDay).put("routineId", it.routineId).put("title", it.title).put("smaller", it.smaller) } ?: JSONObject.NULL)
        .toString()

    private fun routineJson(it: Routine) = JSONObject().put("id", it.id).put("name", it.name)
        .put("normalAction", it.normalAction).put("smallerAction", it.smallerAction).put("weeklyTarget", it.weeklyTarget)
        .put("enabled", it.enabled).put("completed", JSONArray(it.completedEpochDays.sorted()))

    private fun JSONArray?.routines(): List<Routine> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let { j ->
            add(Routine(j.optString("id"), j.optString("name"), j.optString("normalAction"), j.optString("smallerAction"), j.optInt("weeklyTarget", 3), j.optBoolean("enabled", true), j.optJSONArray("completed").longs()))
        }
    }
    private fun JSONArray?.feedback() = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let { j -> runCatching { FeedbackEvent(j.optString("routineId").take(40), DayPhase.valueOf(j.getString("phase")), FeedbackKind.valueOf(j.getString("kind")), j.getLong("day")) }.getOrNull()?.let(::add) }
    }.let(FeedbackPolicy::bound)
    private fun JSONArray?.reflections() = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let { j -> runCatching { ReflectionEvent(j.getLong("day"), ReflectionKind.valueOf(j.getString("kind"))) }.getOrNull()?.let(::add) }
    }.let(ReflectionPolicy::bound)

    private inline fun <reified T : Enum<T>> enum(raw: String, fallback: T) = runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    private fun jsonMap(map: Map<*, *>) = JSONObject().apply { map.entries.sortedBy { it.key.toString() }.forEach { put(it.key.toString(), it.value) } }
    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else buildSet { for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(::add) }
    private fun JSONArray?.longs(): Set<Long> = if (this == null) emptySet() else buildSet { for (i in 0 until length()) optLong(i, -1).takeIf { it >= 0 }?.let(::add) }
    private fun JSONObject?.stringMap(): Map<String, String> = if (this == null) emptyMap() else buildMap { keys().forEach { key -> optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } } }
    private fun JSONObject?.longMap(): Map<String, Long> = if (this == null) emptyMap() else buildMap { keys().forEach { key -> optLong(key, -1).takeIf { it >= 0 }?.let { put(key, it) } } }
}
