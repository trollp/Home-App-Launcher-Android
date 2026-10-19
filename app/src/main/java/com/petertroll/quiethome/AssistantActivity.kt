package com.petertroll.quiethome

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.petertroll.quiethome.domain.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/** User-opened, foreground-only context assistant. It schedules no work and makes no automatic AI calls. */
class AssistantActivity : AppCompatActivity() {
    private lateinit var store: PreferencesStore
    private lateinit var credentials: CredentialStore
    private lateinit var root: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var prefs = LauncherPreferences()
    private var weather: com.petertroll.quiethome.domain.WeatherSnapshot? = null
    private var event: com.petertroll.quiethome.domain.UpcomingEvent? = null
    private var displayed: SuggestedAction? = null
    private var briefingOpenedFromIntent = false
    private var uiStarted = false

    companion object {
        const val EXTRA_OPEN_BRIEFING = "open_briefing"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreferencesStore(this)
        credentials = CredentialStore(this)
        weather = WeatherRepository(this).cached()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
        refreshContext()
    }

    override fun onResume() {
        super.onResume()
        prefs = store.load()
        val day = now().toLocalDate().toEpochDay()
        if (prefs.tomorrowProposal?.epochDay?.let { it < day } == true) {
            prefs = prefs.copy(tomorrowProposal = null)
            store.save(prefs)
        }
        render()
        if (!briefingOpenedFromIntent && intent.getBooleanExtra(EXTRA_OPEN_BRIEFING, false)) {
            briefingOpenedFromIntent = true
            morningBriefing()
        }
    }
    override fun onStart() { super.onStart(); uiStarted = true }
    override fun onStop() { uiStarted = false; super.onStop() }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun now() = ZonedDateTime.now(java.time.ZoneId.of("Europe/Stockholm"))
    private fun context(): AssistantContext {
        val now = now()
        val day = now.toLocalDate().toEpochDay()
        return AssistantContext(
            epochDay = day, localHour = now.hour, dayPhase = AssistantContext.phase(now.hour),
            weatherSummary = weather?.headline(),
            weatherTiming = weather?.rainStartsAt?.atZone(now.zone)?.let { "Rain near ${it.hour.toString().padStart(2, '0')}:00" },
            nextEventMinutes = event?.minutesUntil,
            capacity = prefs.capacity,
            routines = prefs.routines.filter { it.enabled },
            feedback = if (prefs.learningEnabled) prefs.feedback.take(20) else emptyList(),
            suppressedRoutineIds = prefs.suppressedRoutineDays.filterValues { it == day }.keys,
            learningEnabled = prefs.learningEnabled,
            tomorrowProposal = prefs.tomorrowProposal,
        )
    }

    private fun candidates() = AssistantPolicy.candidates(context())

    private fun render() {
        if (!::root.isInitialized) return
        prefs = store.load()
        root.removeAllViews()
        heading("Assistant", 28f)
        text("Local context · Stockholm · ${context().dayPhase.name.lowercase()}")

        heading("Capacity", 18f)
        row().apply {
            Capacity.entries.forEach { value -> addView(button(value.name.lowercase().replaceFirstChar { it.uppercase() }, {
                displayed = null; save(prefs.copy(capacity = value))
            }, 1f)) }
        }

        heading("Priorities today", 18f)
        if (prefs.routines.isEmpty()) {
            text("No routines yet. Add up to five priorities in Settings.")
            action("Manage routines") { startActivity(Intent(this, SettingsActivity::class.java)) }
        } else prefs.routines.forEach { routine ->
            val done = context().epochDay in routine.completedEpochDays
            action((if (done) "✓ " else "○ ") + routine.name + " · ${weekCount(routine)}/${routine.weeklyTarget}") {
                save(prefs.copy(routines = prefs.routines.map { if (it.id == routine.id) it.toggle(context().epochDay) else it }))
            }
        }

        heading("One useful action", 18f)
        val deferred = System.currentTimeMillis() < prefs.laterUntilEpochMillis
        val available = candidates()
        val suggestion = displayed?.takeIf { shown -> available.any { it.candidateId == shown.candidateId } }
            ?: if (!deferred) available.firstOrNull() else null
        displayed = suggestion
        if (suggestion == null) {
            text(if (deferred) "Deferred for now. Your routines remain available above." else "No active priority needs attention right now.")
        } else suggestionCard(suggestion)

        action("Morning briefing") { morningBriefing() }
        action("Evening reflection") { eveningReflection() }
        val dow = now().dayOfWeek
        if (dow == java.time.DayOfWeek.SUNDAY || dow == java.time.DayOfWeek.MONDAY) action("Weekly review") { weeklyReview() }
        action("Open Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun suggestionCard(action: SuggestedAction) {
        heading(action.title, 20f)
        text("${action.durationMinutes} min · ${action.category.name.lowercase()}")
        text(action.reason)
        row().apply {
            addView(button("Done", { complete(action) }, 1f))
            addView(button("Later", { feedback(action, FeedbackKind.DEFERRED); displayed = null; save(prefs.copy(laterUntilEpochMillis = System.currentTimeMillis() + 2 * 60 * 60 * 1000)) }, 1f))
            addView(button("Smaller", {
                val smaller = AssistantActions.smallerOrNull(action.candidateId, candidates())
                if (smaller == null) {
                    displayed = null
                    render()
                } else {
                    feedback(action, FeedbackKind.SMALLER)
                    displayed = smaller
                    render()
                }
            }, 1f))
        }
        row().apply {
            addView(button("Not today", {
                feedback(action, FeedbackKind.NOT_TODAY)
                displayed = null
                save(prefs.copy(suppressedRoutineDays = prefs.suppressedRoutineDays + (action.routineId to context().epochDay)))
            }, 1f))
            addView(button("Why", { AlertDialog.Builder(this@AssistantActivity).setTitle("Why this appeared").setMessage(action.reason).setPositiveButton(android.R.string.ok, null).show() }, 1f))
            addView(button("Refine with AI", { refine(action, candidates()) }, 1f))
        }
    }

    private fun complete(action: SuggestedAction) {
        val allowed = candidates()
        val day = context().epochDay
        val phase = context().dayPhase
        val updated = runCatching { AssistantActions.done(prefs, action.candidateId, allowed, day) }.getOrElse { return }
        displayed = null
        save(updated.copy(feedback = FeedbackPolicy.bound(updated.feedback + FeedbackEvent(action.routineId, phase, FeedbackKind.DONE, day))))
        Snackbar.make(root, "Marked done.", Snackbar.LENGTH_LONG).setAction("Undo") {
            displayed = action
            save(AssistantActions.undoDone(store.load(), action.candidateId, allowed, day, phase))
        }.show()
    }

    private fun feedback(action: SuggestedAction, kind: FeedbackKind) {
        if (action.routineId.isBlank() || candidates().none { it.candidateId == action.candidateId }) return
        prefs = prefs.copy(feedback = FeedbackPolicy.bound(prefs.feedback + FeedbackEvent(action.routineId, context().dayPhase, kind, context().epochDay)))
        store.save(prefs)
    }

    private fun morningBriefing() {
        val c = context()
        val local = buildList {
            add("Stockholm: ${c.weatherSummary ?: "weather unavailable"}${c.weatherTiming?.let { "; $it" } ?: ""}.")
            event?.let { add("Next: ${it.title} in ${it.minutesUntil} minutes. The title stays on this device.") }
            if (c.routines.isEmpty()) add("No active priorities configured.") else add(c.routines.joinToString(" · ") { "${it.name} ${weekCount(it)}/${it.weeklyTarget}" })
            add("Best action: ${AssistantPolicy.suggest(c).title}. ${AssistantPolicy.suggest(c).reason}")
        }.joinToString("\n\n")
        AlertDialog.Builder(this).setTitle("Morning briefing").setMessage(local)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Refine with AI") { _, _ -> refineBriefing(briefingRewriteItems(c)) }
            .setNegativeButton("Weekly review") { _, _ -> weeklyReview() }.show()
    }

    private fun briefingRewriteItems(c: AssistantContext): List<RewriteItem> = buildList {
        add(RewriteItem("weather", "Stockholm: ${c.weatherSummary ?: "weather unavailable"}${c.weatherTiming?.let { "; $it" } ?: ""}."))
        c.nextEventMinutes?.let { add(RewriteItem("event", "Next event is in $it minutes.")) }
        add(RewriteItem("routines", if (c.routines.isEmpty()) "No active priorities configured." else c.routines.joinToString(" · ") { "${it.name} ${weekCount(it)}/${it.weeklyTarget}" }))
        val suggestion = AssistantPolicy.suggest(c)
        add(RewriteItem("action", "Best action: ${suggestion.title}. ${suggestion.reason}"))
    }

    private fun eveningReflection() {
        val labels = arrayOf("As planned", "Too much", "Smaller worked", "Priorities changed")
        AlertDialog.Builder(this).setTitle("Evening reflection").setItems(labels) { _, index ->
            val kind = ReflectionKind.entries[index]
            val proposal = ReflectionPolicy.tomorrowProposal(kind, prefs.routines, context().epochDay)
            prefs = prefs.copy(reflections = ReflectionPolicy.bound(prefs.reflections + ReflectionEvent(context().epochDay, kind)))
            store.save(prefs)
            if (proposal == null) render() else AlertDialog.Builder(this)
                .setTitle("One action for tomorrow").setMessage(proposal.title)
                .setPositiveButton("Apply for tomorrow") { _, _ -> save(prefs.copy(tomorrowProposal = proposal)) }
                .setNegativeButton("No change", null)
                .setNeutralButton("Refine wording with AI") { _, _ -> refineProposal(proposal) }.show()
        }.show()
    }

    private fun weeklyReview() {
        val review = WeeklyReviewPolicy.review(prefs, context().epochDay)
        val facts = if (review.observations.isEmpty()) "Not enough local evidence yet. Observations need at least 3 sources." else
            review.observations.joinToString("\n") { "• ${it.text} (sources: ${it.sourceCount})" }
        val proposal = review.proposal
        AlertDialog.Builder(this).setTitle("Weekly review").setMessage(facts + (proposal?.let { "\n\nReversible proposal: weekly target ${it.oldTarget} → ${it.newTarget}." } ?: ""))
            .setPositiveButton(if (proposal == null) "Close" else "Apply proposal") { _, _ -> proposal?.let { applyWeeklyProposal(it) } }
            .setNegativeButton("No change", null)
            .setNeutralButton("Refine wording with AI") { _, _ -> refineWeekly(weeklyRewriteItems(review)) }.show()
    }

    private fun weeklyRewriteItems(review: WeeklyReview): List<RewriteItem> = buildList {
        if (review.observations.isEmpty()) add(RewriteItem("evidence", "Not enough local evidence yet. Observations need at least 3 sources."))
        else review.observations.forEachIndexed { index, observation ->
            add(RewriteItem("observation-$index", "${observation.text} Sources: ${observation.sourceCount}."))
        }
        review.proposal?.let { add(RewriteItem("proposal", "Reversible proposal: weekly target ${it.oldTarget} to ${it.newTarget}.")) }
    }

    private fun applyWeeklyProposal(proposal: ReviewProposal) {
        save(proposal.apply(prefs))
        Snackbar.make(root, "Weekly proposal applied.", Snackbar.LENGTH_LONG).setAction("Undo") {
            save(proposal.revert(store.load()))
        }.show()
    }

    private fun refineProposal(proposal: TomorrowProposal) {
        withAi("Refining tomorrow’s wording…", { client, provider, key, model ->
            client.refineProposal(provider, key, model, proposal)
        }) { refined -> showRefinedProposal(refined) }
    }

    private fun showRefinedProposal(proposal: TomorrowProposal) {
        AlertDialog.Builder(this).setTitle("One action for tomorrow").setMessage(proposal.title)
            .setPositiveButton("Apply for tomorrow") { _, _ -> save(prefs.copy(tomorrowProposal = proposal)) }
            .setNegativeButton("No change", null).show()
    }

    private fun refineBriefing(items: List<RewriteItem>) {
        withAi("Refining the complete briefing…", { client, provider, key, model ->
            client.refineAggregate(provider, key, model, AggregateKind.BRIEFING, items)
        }) { refined -> showRefinedBriefing(refined) }
    }

    private fun showRefinedBriefing(items: List<RewriteItem>) {
        AlertDialog.Builder(this).setTitle("Refined morning briefing")
            .setMessage(items.joinToString("\n\n") { it.text })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun refineWeekly(items: List<RewriteItem>) {
        withAi("Refining the complete weekly review…", { client, provider, key, model ->
            client.refineAggregate(provider, key, model, AggregateKind.WEEKLY, items)
        }) { refined -> showRefinedWeekly(refined) }
    }

    private fun showRefinedWeekly(items: List<RewriteItem>) {
        AlertDialog.Builder(this).setTitle("Refined weekly review")
            .setMessage(items.joinToString("\n\n") { it.text })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun <T> withAi(
        message: String,
        request: (AiCoachClient, AiProvider, String, String) -> T,
        success: (T) -> Unit,
    ) {
        val key = credentials.apiKey(prefs.aiProvider)
        if (prefs.aiProvider == AiProvider.NONE || key == null) {
            Snackbar.make(root, "AI is not configured. The local version remains available.", Snackbar.LENGTH_LONG).show()
            return
        }
        val provider = prefs.aiProvider
        val model = prefs.aiModel
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
        executor.execute {
            val result = runCatching { request(AiCoachClient(), provider, key, model) }
            main.post {
                if (!canDeliverUi()) return@post
                result.onSuccess(success).onFailure { Snackbar.make(root, "AI refinement unavailable: ${it.message}", Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    private fun refine(local: SuggestedAction, allowed: List<SuggestedAction>) {
        val key = credentials.apiKey(prefs.aiProvider)
        if (prefs.aiProvider == AiProvider.NONE || key == null) return Snackbar.make(root, "AI is not configured. The local suggestion remains available.", Snackbar.LENGTH_LONG).show()
        Snackbar.make(root, "Refining only after your request…", Snackbar.LENGTH_SHORT).show()
        val snapshot = context()
        executor.execute {
            val result = runCatching { AiCoachClient().refine(prefs.aiProvider, key, prefs.aiModel, snapshot, allowed.ifEmpty { listOf(local) }) }
            main.post {
                if (!canDeliverUi()) return@post
                result.onSuccess { displayed = it; showRefined(it) }.onFailure { Snackbar.make(root, "AI refinement unavailable: ${it.message}", Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    private fun showRefined(action: SuggestedAction) = AlertDialog.Builder(this).setTitle("Refined wording")
        .setMessage("${action.title}\n\n${action.reason}\n\nLocal command unchanged: ${action.command.name.lowercase().replace('_', ' ')}")
        .setPositiveButton("Use wording") { _, _ -> displayed = action; render() }.setNegativeButton("Keep local", null).show()

    private fun refreshContext() {
        executor.execute {
            val latestWeather = runCatching { WeatherRepository(this).refresh() }.getOrNull()
            val clientId = prefs.outlookClientId
            val latestEvent = if (clientId.isNotBlank() && credentials.hasOutlookConnection()) runCatching { OutlookCalendarClient(this).nextEvent(clientId) }.getOrNull() else null
            main.post {
                if (!canDeliverUi()) return@post
                latestWeather?.let { weather = it }
                event = latestEvent
                displayed = null
                render()
            }
        }
    }

    private fun canDeliverUi(): Boolean = uiStarted && !isFinishing && !isDestroyed

    private fun weekCount(routine: Routine): Int {
        val date = LocalDate.ofEpochDay(context().epochDay)
        val start = date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()
        return routine.completedEpochDays.count { it in start..context().epochDay }
    }
    private fun save(value: LauncherPreferences) { prefs = value; store.save(value); render() }
    private fun heading(value: String, size: Float) = root.addView(TextView(this).apply { text = value; textSize = size; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(14), 0, dp(5)) })
    private fun text(value: String) = root.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, dp(3), 0, dp(7)) })
    private fun action(value: String, click: () -> Unit) = root.addView(button(value, click).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; root.addView(this) }
    private fun button(value: String, click: () -> Unit, weight: Float = 0f) = Button(this).apply { text = value; isAllCaps = false; minHeight = dp(48); setOnClickListener { click() }; if (weight > 0) layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
