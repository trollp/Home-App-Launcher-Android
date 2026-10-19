package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class DayPhase { MORNING, AFTERNOON, EVENING, NIGHT }
enum class Capacity { LOW, NORMAL, HIGH }
enum class FeedbackKind { DONE, DEFERRED, SMALLER, NOT_TODAY }
enum class ReflectionKind { AS_PLANNED, TOO_MUCH, SMALLER_WORKED, PRIORITIES_CHANGED }
enum class Proactivity { OFF, ONCE_DAILY, CONTEXT_CHANGES }
enum class ActionCategory { ROUTINE, PREPARATION }
enum class LocalCommand { COMPLETE_ROUTINE, PREPARE_ROUTINE }

data class FeedbackEvent(val routineId: String, val dayPhase: DayPhase, val kind: FeedbackKind, val epochDay: Long)
data class ReflectionEvent(val epochDay: Long, val kind: ReflectionKind)

data class AssistantContext(
    val epochDay: Long,
    val localHour: Int,
    val dayPhase: DayPhase,
    val weatherSummary: String?,
    val weatherTiming: String?,
    val nextEventMinutes: Int?,
    val capacity: Capacity,
    val routines: List<Routine>,
    val feedback: List<FeedbackEvent>,
    val suppressedRoutineIds: Set<String>,
    val learningEnabled: Boolean,
    val tomorrowProposal: TomorrowProposal? = null,
) {
    companion object {
        fun phase(hour: Int) = when (hour) { in 5..11 -> DayPhase.MORNING; in 12..16 -> DayPhase.AFTERNOON; in 17..21 -> DayPhase.EVENING; else -> DayPhase.NIGHT }
        fun basic(day: Long, hour: Int, routines: List<Routine>) = AssistantContext(day, hour, phase(hour), null, null, null, Capacity.NORMAL, routines, emptyList(), emptySet(), true)
    }
}

data class SuggestedAction(
    val candidateId: String,
    val routineId: String,
    val title: String,
    val reason: String,
    val durationMinutes: Int,
    val category: ActionCategory,
    val command: LocalCommand,
    val isSmaller: Boolean = false,
)

object FeedbackPolicy {
    const val MAX_EVENTS = 200
    fun bound(values: List<FeedbackEvent>) = values.sortedByDescending { it.epochDay }.take(MAX_EVENTS)

    fun adjustment(values: List<FeedbackEvent>, routineId: String, phase: DayPhase): Int {
        val relevant = values.filter { it.routineId == routineId && it.dayPhase == phase }.take(20)
        return relevant.fold(0) { total, event -> total + when (event.kind) { FeedbackKind.DONE -> 2; FeedbackKind.SMALLER -> 1; FeedbackKind.DEFERRED -> -1; FeedbackKind.NOT_TODAY -> -2 } }.coerceIn(-4, 4)
    }

    fun learnedPattern(values: List<FeedbackEvent>, routineId: String, phase: DayPhase): String? {
        val relevant = values.filter { it.routineId == routineId && it.dayPhase == phase }
        if (relevant.size < 3) return null
        val dates = relevant.map { LocalDate.ofEpochDay(it.epochDay) }
        if ((dates.maxOrNull()!!.toEpochDay() - dates.minOrNull()!!.toEpochDay()) < 14) return null
        val common = relevant.groupingBy { it.kind }.eachCount().maxByOrNull { it.value } ?: return null
        if (common.value < 3) return null
        return "Based on ${relevant.size} ${phase.name.lowercase()} feedback entries across at least two weeks."
    }
}

object AssistantPolicy {
    fun candidates(context: AssistantContext): List<SuggestedAction> {
        val proposal = context.tomorrowProposal?.takeIf { it.epochDay == context.epochDay }
        val weekStart = LocalDate.ofEpochDay(context.epochDay).minusDays((LocalDate.ofEpochDay(context.epochDay).dayOfWeek.value - 1).toLong()).toEpochDay()
        val values = context.routines.filter {
            it.enabled && it.id !in context.suppressedRoutineIds && context.epochDay !in it.completedEpochDays
        }.flatMap { routine ->
            val count = routine.completedEpochDays.count { it in weekStart..context.epochDay }
            val progress = "$count of ${routine.weeklyTarget} this week"
            val timing = buildList {
                context.nextEventMinutes?.takeIf { it in 1..60 }?.let { add("an event is in $it minutes") }
                context.weatherTiming?.let { add(it.lowercase()) }
            }.joinToString("; ")
            val baseReason = "${routine.name}: $progress" + if (timing.isBlank()) "." else "; $timing."
            val learnedReason = if (context.learningEnabled && FeedbackPolicy.adjustment(context.feedback, routine.id, context.dayPhase) != 0) {
                FeedbackPolicy.learnedPattern(context.feedback, routine.id, context.dayPhase)?.let { " $it" }.orEmpty()
            } else ""
            listOf(
                SuggestedAction("${routine.id}:normal", routine.id, routine.normalAction, baseReason + learnedReason, 20, ActionCategory.ROUTINE, LocalCommand.COMPLETE_ROUTINE),
                SuggestedAction("${routine.id}:small", routine.id, routine.smallerAction, "$baseReason A smaller option is available for your current capacity.$learnedReason", 5, ActionCategory.ROUTINE, LocalCommand.COMPLETE_ROUTINE, true),
            ).map { action ->
                if (proposal?.routineId == routine.id && proposal.smaller == action.isSmaller) {
                    TomorrowProposal.normalized(proposal.epochDay, proposal.routineId, proposal.title, proposal.smaller)
                        ?.let { action.copy(title = it.title) } ?: action
                } else action
            }
        }
        return values.sortedWith(compareByDescending<SuggestedAction> { action ->
            val routine = context.routines.first { it.id == action.routineId }
            val feedback = if (context.learningEnabled) FeedbackPolicy.adjustment(context.feedback, routine.id, context.dayPhase) else 0
            val capacityFit = when (context.capacity) { Capacity.LOW -> if (action.isSmaller) 5 else 0; Capacity.HIGH -> if (!action.isSmaller) 3 else 0; Capacity.NORMAL -> if (!action.isSmaller) 2 else 0 }
            capacityFit + feedback +
                (if (proposal?.routineId == action.routineId && proposal.smaller == action.isSmaller) 20 else 0)
        }.thenBy { it.candidateId })
    }

    fun suggest(context: AssistantContext): SuggestedAction = candidates(context).firstOrNull()
        ?: SuggestedAction("none:prepare", "", "Choose one useful next step", "No active routine needs attention right now.", 5, ActionCategory.PREPARATION, LocalCommand.PREPARE_ROUTINE)
}

object AssistantActions {
    private fun candidate(id: String, candidates: List<SuggestedAction>) = candidates.singleOrNull { it.candidateId == id }
        ?: throw IllegalArgumentException("Unknown candidate")

    fun done(prefs: LauncherPreferences, candidateId: String, candidates: List<SuggestedAction>, day: Long): LauncherPreferences {
        val action = candidate(candidateId, candidates)
        require(action.command == LocalCommand.COMPLETE_ROUTINE && action.routineId.isNotBlank())
        val routine = prefs.routines.singleOrNull { it.id == action.routineId }
            ?: throw IllegalArgumentException("Unknown routine")
        require(day !in routine.completedEpochDays) { "Routine is already complete" }
        return prefs.copy(routines = prefs.routines.map { if (it.id == action.routineId) it.toggle(day) else it })
    }
    fun undoDone(
        current: LauncherPreferences,
        candidateId: String,
        candidates: List<SuggestedAction>,
        day: Long,
        phase: DayPhase,
    ): LauncherPreferences {
        val action = candidate(candidateId, candidates)
        require(action.command == LocalCommand.COMPLETE_ROUTINE && action.routineId.isNotBlank())
        var removedFeedback = false
        return current.copy(
            routines = current.routines.map { routine ->
                if (routine.id == action.routineId) routine.copy(completedEpochDays = routine.completedEpochDays - day) else routine
            },
            feedback = current.feedback.filterNot { event ->
                val remove = !removedFeedback && event.routineId == action.routineId && event.epochDay == day &&
                    event.dayPhase == phase && event.kind == FeedbackKind.DONE
                if (remove) removedFeedback = true
                remove
            },
        )
    }
    fun smallerOrNull(candidateId: String, candidates: List<SuggestedAction>): SuggestedAction? {
        val action = candidates.singleOrNull { it.candidateId == candidateId } ?: return null
        return candidates.singleOrNull { it.routineId == action.routineId && it.isSmaller } ?: action
    }
}

data class ReviewObservation(val text: String, val sourceCount: Int)
data class ReviewProposal(val routineId: String, val oldTarget: Int, val newTarget: Int) {
    fun apply(prefs: LauncherPreferences) = prefs.copy(routines = prefs.routines.map { if (it.id == routineId) it.copy(weeklyTarget = newTarget) else it })
    fun revert(prefs: LauncherPreferences) = prefs.copy(routines = prefs.routines.map { if (it.id == routineId) it.copy(weeklyTarget = oldTarget) else it })
}
data class WeeklyReview(val observations: List<ReviewObservation>, val proposal: ReviewProposal?)

object WeeklyReviewPolicy {
    fun review(prefs: LauncherPreferences, today: Long): WeeklyReview {
        val observations = prefs.routines.mapNotNull { routine ->
            val relevant = routine.completedEpochDays.filter { it in (today - 20)..today }
            val count = relevant.size
            if (count < 3 || relevant.maxOrNull()!! - relevant.minOrNull()!! < 7) null else ReviewObservation("${routine.name} was completed $count times in the last three weeks.", count)
        }.take(3)
        val proposalRoutine = prefs.routines.firstOrNull {
            val relevant = it.completedEpochDays.filter { day -> day in (today - 20)..today }
            relevant.size >= 3 && relevant.maxOrNull()!! - relevant.minOrNull()!! >= 7
        }
        val proposal = proposalRoutine?.let {
            val average = (it.completedEpochDays.count { day -> day in (today - 20)..today } / 3).coerceIn(1, 7)
            if (average == it.weeklyTarget) null else ReviewProposal(it.id, it.weeklyTarget, average)
        }
        return WeeklyReview(observations, proposal)
    }
}

data class TomorrowProposal(val epochDay: Long, val routineId: String, val title: String, val smaller: Boolean) {
    companion object {
        const val MAX_TITLE_CHARS = 80
        fun normalized(epochDay: Long, routineId: String, title: String, smaller: Boolean): TomorrowProposal? = runCatching {
            TomorrowProposal(
                epochDay = epochDay,
                routineId = routineId.trim().take(40),
                title = SafeAiText.require(title.trim().take(MAX_TITLE_CHARS), MAX_TITLE_CHARS),
                smaller = smaller,
            )
        }.getOrNull()
    }
}
object ReflectionPolicy {
    fun bound(values: List<ReflectionEvent>) = values.sortedByDescending { it.epochDay }.take(60)
    fun tomorrowProposal(kind: ReflectionKind, routines: List<Routine>, today: Long): TomorrowProposal? {
        val routine = routines.firstOrNull { it.enabled } ?: return null
        val smaller = kind == ReflectionKind.TOO_MUCH || kind == ReflectionKind.SMALLER_WORKED
        return TomorrowProposal.normalized(today + 1, routine.id, if (smaller) routine.smallerAction else routine.normalAction, smaller)
    }
}

object ProactivityPolicy {
    fun contextSignature(context: AssistantContext): String {
        val eventBucket = when (context.nextEventMinutes) {
            null -> "none"
            in Int.MIN_VALUE..15 -> "now"
            in 16..60 -> "soon"
            in 61..180 -> "later"
            else -> "distant"
        }
        val weatherBucket = context.weatherTiming?.lowercase()?.let {
            when {
                "rain" in it && ("near" in it || "soon" in it) -> "rain-soon"
                "rain" in it -> "rain-later"
                else -> "timed-weather"
            }
        } ?: "none"
        val weekStart = context.epochDay - (LocalDate.ofEpochDay(context.epochDay).dayOfWeek.value - 1)
        val progress = context.routines.filter { it.enabled }.sortedBy { it.id }.joinToString(",") { routine ->
            "${routine.id.take(40)}:${routine.completedEpochDays.count { it in weekStart..context.epochDay }}/${routine.weeklyTarget}:${routine.id in context.suppressedRoutineIds}"
        }
        return "${context.dayPhase}|${context.capacity}|$eventBucket|$weatherBucket|$progress"
    }

    fun shouldShow(mode: Proactivity, today: Long, lastDay: Long, signature: String, lastSignature: String) = when (mode) {
        Proactivity.OFF -> false
        Proactivity.ONCE_DAILY -> lastDay != today
        Proactivity.CONTEXT_CHANGES -> signature != lastSignature
    }

    fun shouldShowInSession(mode: Proactivity, shownThisSession: Boolean, today: Long, lastDay: Long, signature: String, lastSignature: String): Boolean =
        mode != Proactivity.OFF && (shownThisSession || shouldShow(mode, today, lastDay, signature, lastSignature))
}

/** Strict selection protocol: the provider can edit words, never invent an executable action. */
object AiSuggestionProtocol {
    const val MAX_REQUEST_CHARS = 4000
    fun systemPrompt() = "Return JSON only with exactly candidateId, title, reason. Select one supplied candidateId. You cannot create commands, URLs, intents, settings changes, or calendar writes."

    fun suppliedCandidates(candidates: List<SuggestedAction>): List<SuggestedAction> = candidates.take(5).map {
        it.copy(candidateId = it.candidateId.take(48), title = it.title.take(64), reason = it.reason.take(140))
    }.also { supplied ->
        require(supplied.isNotEmpty() && supplied.all { it.candidateId.isNotBlank() })
        require(supplied.map { it.candidateId }.distinct().size == supplied.size)
    }

    fun request(context: AssistantContext, candidates: List<SuggestedAction>): String {
        val supplied = suppliedCandidates(candidates)
        val contextJson = JSONObject()
            .put("dayPhase", context.dayPhase.name)
            .put("localHour", context.localHour)
            .put("weatherSummary", context.weatherSummary?.take(120) ?: JSONObject.NULL)
            .put("weatherTiming", context.weatherTiming?.take(120) ?: JSONObject.NULL)
            .put("nextEventMinutes", context.nextEventMinutes ?: JSONObject.NULL)
            .put("capacity", context.capacity.name)
            .put("routines", JSONArray(context.routines.take(5).map { routine ->
                val weekStart = context.epochDay - (LocalDate.ofEpochDay(context.epochDay).dayOfWeek.value - 1)
                JSONObject().put("id", routine.id.take(40)).put("name", routine.name.take(60)).put("weeklyTarget", routine.weeklyTarget)
                    .put("completedThisWeek", routine.completedEpochDays.count { it in weekStart..context.epochDay })
            }))
            .put("recentFeedback", JSONArray(
                if (context.learningEnabled) context.feedback.take(8).map { "${it.routineId.take(40)}:${it.dayPhase}:${it.kind}" }
                else emptyList<String>(),
            ))
        val candidateJson = JSONArray(supplied.map {
            JSONObject().put("candidateId", it.candidateId).put("title", it.title).put("reason", it.reason)
                .put("durationMinutes", it.durationMinutes).put("category", it.category.name)
        })
        return JSONObject().put("instruction", systemPrompt()).put("context", contextJson)
            .put("candidates", candidateJson).toString().also { require(it.length <= MAX_REQUEST_CHARS) }
    }

    fun parseResponse(raw: String, candidates: List<SuggestedAction>): SuggestedAction {
        require(raw.length in 2..1000)
        val json = JSONObject(raw)
        require(jsonKeys(json) == setOf("candidateId", "title", "reason"))
        val selected = suppliedCandidates(candidates).singleOrNull { it.candidateId == json.getString("candidateId") } ?: throw IllegalArgumentException("Unknown candidate")
        return selected.copy(
            title = SafeAiText.require(json.getString("title"), 80),
            reason = SafeAiText.require(json.getString("reason"), 180),
        )
    }
}

private fun jsonKeys(json: JSONObject): Set<String> = buildSet {
    json.keys().forEachRemaining { add(it) }
}

internal object SafeAiText {
    fun require(value: String, max: Int): String {
        val clean = value.trim()
        require(clean.length in 2..max && !clean.contains('\n') && !clean.contains('\r'))
        require(!Regex("(?i)(https?://|www\\.|javascript:|intent:)").containsMatchIn(clean))
        return clean
    }
}

object AiReflectionProtocol {
    fun request(proposal: TomorrowProposal): String = JSONObject()
        .put("kind", "REFLECTION")
        .put("instruction", "Return JSON only with exactly title. Rewrite wording only; do not add commands, URLs, or facts.")
        .put("title", proposal.title.take(80))
        .toString()

    fun parseResponse(raw: String, proposal: TomorrowProposal): TomorrowProposal {
        require(raw.length in 2..300)
        val json = JSONObject(raw)
        require(jsonKeys(json) == setOf("title"))
        return proposal.copy(title = SafeAiText.require(json.getString("title"), 80))
    }
}

enum class AggregateKind { BRIEFING, WEEKLY }
data class RewriteItem(val id: String, val text: String)

object AiAggregateProtocol {
    const val MAX_REQUEST_CHARS = 4000
    const val MAX_RESPONSE_CHARS = 4000

    fun normalizeItems(items: List<RewriteItem>): List<RewriteItem> = items.take(8).map {
        RewriteItem(it.id.trim().take(32), it.text.trim().take(300))
    }.also { bounded ->
        require(bounded.isNotEmpty() && bounded.all { it.id.isNotBlank() && it.text.isNotBlank() })
        require(bounded.map { it.id }.distinct().size == bounded.size)
    }

    fun request(kind: AggregateKind, items: List<RewriteItem>): String {
        val bounded = normalizeItems(items)
        return JSONObject()
            .put("kind", kind.name)
            .put("instruction", "Return JSON only with exactly kind and items. Include each supplied id exactly once in the same order. Rewrite wording only; preserve every fact and number. No URLs, commands, advice, or newlines.")
            .put("items", JSONArray(bounded.map { JSONObject().put("id", it.id).put("text", it.text) }))
            .toString().also { require(it.length <= MAX_REQUEST_CHARS) }
    }

    fun parseResponse(raw: String, kind: AggregateKind, source: List<RewriteItem>): List<RewriteItem> {
        require(raw.length in 2..MAX_RESPONSE_CHARS)
        val bounded = normalizeItems(source)
        val json = JSONObject(raw)
        require(jsonKeys(json) == setOf("kind", "items") && json.getString("kind") == kind.name)
        val array = json.getJSONArray("items")
        require(array.length() == bounded.size)
        return bounded.indices.map { index ->
            val item = array.getJSONObject(index)
            require(jsonKeys(item) == setOf("id", "text") && item.getString("id") == bounded[index].id)
            val text = SafeAiText.require(item.getString("text"), 300)
            val sourceNumbers = Regex("\\d+").findAll(bounded[index].text).map { it.value }.toList().groupingBy { it }.eachCount()
            val responseNumbers = Regex("\\d+").findAll(text).map { it.value }.toList().groupingBy { it }.eachCount()
            require(sourceNumbers == responseNumbers)
            RewriteItem(bounded[index].id, text)
        }
    }
}
