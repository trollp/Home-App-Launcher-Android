package com.petertroll.quiethome

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.domain.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/** Plan detail: cached/fallback content is immediate; collection/model refresh always follows. */
class AssistantActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })
        render(PlanStore(this).load()?.first ?: PlannerCoordinator(this).immediateFallback())
        ActivityLogStore(this).append(ActivityKind.PLAN_SHOWN)
        executor.execute { val fresh = PlannerCoordinator(this).refresh(); runOnUiThread { if (!isFinishing && !isDestroyed) render(fresh) } }
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun render(plan: PlannerPlan) {
        root.removeAllViews(); heading("Plan", 28f); heading(plan.headline, 21f)
        heading("Next", 18f)
        plan.next.forEach { item ->
            text(listOfNotNull(item.time, item.text).joinToString(" · ")) // A row tap never implies completion.
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Done" to ActivityKind.DONE, "Skip" to ActivityKind.SKIPPED, "Defer" to ActivityKind.DEFERRED, "Dismiss" to ActivityKind.DISMISSED).forEach { (label, kind) ->
                controls.addView(Button(this).apply { this.text = label; isAllCaps = false; setOnClickListener { ActivityLogStore(this@AssistantActivity).append(kind, item.refId); isEnabled = false } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(controls)
        }
        heading("Digest", 18f); if (plan.digest.isEmpty()) text("No additional digest items.") else plan.digest.forEach { text(it.text) }
        heading("Nudges", 18f); if (plan.nudges.isEmpty()) text("No nudges scheduled.") else plan.nudges.forEach { text("${it.at} · ${it.text}") }
        if (plan.actions.isNotEmpty()) {
            heading("Actions", 18f)
            val specialAccess = resources.getBoolean(R.bool.special_access_features_available)
            plan.actions.filterNot { it.tool == "toggle_dnd" && (!specialAccess || it.args.containsKey("until")) }.forEach { planned ->
                action(planned.label) { runAction(planned) }
            }
        }
        heading("Recent planner activity", 18f)
        ActivityLogStore(this).load().take(20).forEach { text("${it.kind.name.lowercase().replace('_', ' ')}${it.refId?.let { ref -> " · $ref" }.orEmpty()}") }
        action("Planner profile and tasks") { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) action("Share snapshot (debug)") { shareDebugSnapshot() }
    }

    private fun runAction(action: PlanAction) {
        executor.execute {
            val snapshot = currentSnapshot()
            val needsConfirmation = action.tool in setOf("add_task", "create_calendar_block", "set_alarm", "toggle_dnd")
            runOnUiThread {
                if (needsConfirmation) AlertDialog.Builder(this).setTitle(action.label).setMessage("Allow Quiet Home to perform this action?").setPositiveButton("Continue") { _, _ -> execute(action, snapshot, true) }.setNegativeButton("Cancel", null).show()
                else execute(action, snapshot, false)
            }
        }
    }
    private fun execute(action: PlanAction, snapshot: PlannerSnapshot, confirmed: Boolean) {
        ActionExecutor(this).execute(action, snapshot, confirmed).onSuccess { Toast.makeText(this, "Action completed", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this, it.message ?: "Action unavailable", Toast.LENGTH_LONG).show() }
    }
    private fun currentSnapshot(): PlannerSnapshot {
        val prefs = PreferencesStore(this).load(); val now = Instant.now(); val collection = CalendarProviderCollector(this).collect(now, prefs.plannerProfile).getOrNull()
        val events = (collection as? CalendarCollection.Available)?.events.orEmpty(); val status = when (collection) { is CalendarCollection.Available -> SourceStatus.AVAILABLE; CalendarCollection.PermissionMissing -> SourceStatus.PERMISSION_MISSING; CalendarCollection.NoSyncedCalendars -> SourceStatus.NO_SYNCED_CALENDARS; else -> SourceStatus.UNAVAILABLE }
        val allowed = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.filterNot { it in prefs.hiddenPackages || it == packageName }.toSet()
        val weather = WeatherRepository(this).cached()
        return SnapshotBuilder.build(now, ZoneId.systemDefault(), prefs.plannerProfile, events, prefs.localTasks, weather?.headline(), emptyList(), ActivityLogStore(this).load(), prefs.routines, status, if (status == SourceStatus.AVAILABLE) now.toEpochMilli() else null, weather?.fetchedAt?.toEpochMilli(), null, allowed)
    }
    private fun shareDebugSnapshot() {
        executor.execute { val encoded = PlannerSnapshotCodec.encode(currentSnapshot()); runOnUiThread { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, encoded), "Share planner snapshot")) } }
    }
    private fun heading(value: String, size: Float) = root.addView(TextView(this).apply { text = value; textSize = size; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(14), 0, dp(6)) })
    private fun text(value: String) = root.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, dp(4), 0, dp(8)) })
    private fun action(value: String, click: () -> Unit) = root.addView(Button(this).apply { text = value; isAllCaps = false; gravity = Gravity.START; minHeight = dp(48); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setOnClickListener { click() } })
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
