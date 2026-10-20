package com.petertroll.quiethome

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.petertroll.quiethome.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class SettingsActivity : AppCompatActivity() {
    private lateinit var store: PreferencesStore; private lateinit var credentials: CredentialStore; private lateinit var root: LinearLayout
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store = PreferencesStore(this); credentials = CredentialStore(this); root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(40)) }; setContentView(ScrollView(this).apply { addView(root) }); render() }
    override fun onResume() { super.onResume(); if (::root.isInitialized) render() }
    private fun render() {
        root.removeAllViews(); val prefs = store.load(); val p = prefs.plannerProfile
        title("Planner settings")
        section("Profile")
        action("Sleep ${p.bedtime}–${p.wakeTime} · wind-down ${p.windDownMinutes} min") { editSleep() }
        action("Gym ${p.gym.weeklyTarget}/week · ${p.gym.durationMinutes} min + ${p.gym.travelMinutes} min travel") { editGym() }
        action("Meeting preparation · ${p.meetingPrepMinutes} min") { editNumber("Meeting preparation minutes", p.meetingPrepMinutes, 0, 60) { saveProfile(p.copy(meetingPrepMinutes = it)) } }
        action("Hard constraints (${p.hardConstraints.size}/10)") { editLines("Hard constraints, one per line", p.hardConstraints, 10) { saveProfile(p.copy(hardConstraints = it)) } }
        action("Goals (${p.goals.size}/5)") { editLines("Goals, one per line", p.goals, 5) { saveProfile(p.copy(goals = it)) } }
        action("Proactive budget · ${p.proactiveBudget}/day") { editNumber("Proactive nudges per day", p.proactiveBudget, 0, 10) { saveProfile(p.copy(proactiveBudget = it)) } }
        action("Important apps (${p.importantApps.size})") { editLines("Important app package names, one per line", p.importantApps.sorted(), 100) { saveProfile(p.copy(importantApps = it.toSet())) } }
        action("Preferred gym slots · ${p.gym.preferredSlots.joinToString()}") { editLines("Preferred times (HH:mm), one per line", p.gym.preferredSlots, 8) { slots -> require(slots.all { runCatching { LocalTime.parse(it) }.isSuccess }); saveProfile(p.copy(gym = p.gym.copy(preferredSlots = slots))) } }
        action("Planner model · ${p.plannerModel.name} · ${p.plannerModel.reasoningEffort} · ${p.plannerModel.maxCompletionTokens}") { editModel("Planner model", p.plannerModel) { saveProfile(p.copy(plannerModel = it)) } }
        action("Intent model · ${p.intentModel.name} · ${p.intentModel.reasoningEffort} · ${p.intentModel.maxCompletionTokens}") { editModel("Intent-bar model", p.intentModel) { saveProfile(p.copy(intentModel = it)) } }

        section("Routines and priorities")
        action("Add routine (${prefs.routines.size}/${RoutineCollection.MAX_ROUTINES})") { editRoutine(null) }
        if (prefs.routines.isEmpty()) text("No routines. Legacy routine data is migrated automatically.")
        prefs.routines.forEach { routine -> action("${if (routine.enabled) "●" else "○"} ${routine.name} · ${routine.weeklyTarget}/week") { routineMenu(routine) } }

        section("Local tasks")
        action("Add task") { editTask(null) }
        if (prefs.localTasks.isEmpty()) text("No local tasks.") else prefs.localTasks.sortedBy { it.completed }.forEach { task -> action("${if (task.completed) "✓" else "○"} ${task.title}${if (task.important) " · important" else ""}") { taskMenu(task) } }

        section("Calendars")
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted) action("Allow read-only calendar access") { calendarPermission.launch(Manifest.permission.READ_CALENDAR) } else {
            val calendars = AndroidCalendarQuery(contentResolver).calendars().getOrDefault(emptyList()).filter { it.visible }
            if (calendars.isEmpty()) text("No visible synced calendars. If you use Outlook, enable calendar sync in the Outlook app/account settings. Work or MDM policy may block provider sync.")
            action(if (p.calendarSelectionMode == CalendarSelectionMode.ALL_VISIBLE) "Calendars · all visible (default)" else "Calendars · ${p.selectedCalendarIds.size} selected") { chooseCalendars(calendars) }
        }
        action("Include free events · ${if (p.includeFreeEvents) "on" else "off"}") { saveProfile(p.copy(includeFreeEvents = !p.includeFreeEvents)) }

        section("OpenAI")
        text("Cloud planning is off by default. If you explicitly enable it, the bounded snapshot—including calendar titles, locations, local tasks, profile, usage summaries and recent planner activity—is sent to OpenAI. Local fallback works without a key or consent.")
        action("Send planner snapshot to OpenAI · ${if (prefs.openAiEnabled) "ON" else "OFF"}") { store.save(prefs.copy(openAiEnabled = !prefs.openAiEnabled)); PlanStore(this).invalidate(); render() }
        action(if (credentials.hasOpenAiKey()) "Replace OpenAI key" else "Add OpenAI key") { enterKey() }
        if (credentials.hasOpenAiKey()) action("Delete OpenAI key") { credentials.clearOpenAiKey(); store.save(prefs.copy(openAiEnabled = false)); render() }
        section("Privacy")
        action("Clear 14-day planner activity") { getSharedPreferences("planner_activity", MODE_PRIVATE).edit().clear().apply(); message("Planner activity cleared.") }
        action("Delete routine history") { store.save(prefs.copy(routines = prefs.routines.map { it.copy(completedEpochDays = emptySet()) }, routineCompletedEpochDays = emptySet(), habitDoneEpochDay = -1)); message("Routine history deleted.") }
    }
    private fun editSleep() { val p = store.load().plannerProfile; val input = EditText(this).apply { setText("${p.bedtime},${p.wakeTime},${p.windDownMinutes}"); hint = "bedtime,wake,wind-down minutes" }; dialog("Sleep schedule", input) { val v = it.split(','); if (v.size == 3) saveProfile(p.copy(bedtime = LocalTime.parse(v[0].trim()), wakeTime = LocalTime.parse(v[1].trim()), windDownMinutes = v[2].trim().toInt())) } }
    private fun editGym() { val p = store.load().plannerProfile; val input = EditText(this).apply { setText("${p.gym.weeklyTarget},${p.gym.durationMinutes},${p.gym.travelMinutes},${p.gym.preferredSlots.joinToString(";")}"); hint = "weekly,duration,travel,preferred times" }; dialog("Gym target", input) { val v = it.split(','); if (v.size >= 3) saveProfile(p.copy(gym = GymProfile(v[0].trim().toInt(), v[1].trim().toInt(), v[2].trim().toInt(), v.getOrNull(3)?.split(';') ?: listOf("18:00")))) } }
    private fun editNumber(label: String, value: Int, min: Int, max: Int, save: (Int) -> Unit) { val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString()) }; dialog(label, input) { save(it.toIntOrNull()?.coerceIn(min, max) ?: value) } }
    private fun editModel(label: String, value: ModelConfig, save: (ModelConfig) -> Unit) { val input = EditText(this).apply { setText("${value.name},${value.reasoningEffort},${value.maxCompletionTokens}"); hint = "model,effort,max tokens" }; dialog(label, input) { raw -> val fields = raw.split(',').map(String::trim); require(fields.size == 3); save(ModelConfig(fields[0], fields[1], fields[2].toInt()).normalized(value)) } }
    private fun editLines(label: String, values: List<String>, max: Int, save: (List<String>) -> Unit) { val input = EditText(this).apply { setText(values.joinToString("\n")); minLines = 5 }; dialog(label, input) { save(it.lines().map(String::trim).filter(String::isNotBlank).map { line -> line.take(120) }.take(max)) } }
    private fun chooseCalendars(calendars: List<CalendarSource>) { if (calendars.isEmpty()) return; val p = store.load().plannerProfile; val labels = listOf("All visible calendars") + calendars.map { "${it.displayName} · ${it.accountName}" }; val checked = BooleanArray(labels.size) { index -> index == 0 && p.calendarSelectionMode == CalendarSelectionMode.ALL_VISIBLE || index > 0 && calendars[index - 1].id in p.selectedCalendarIds }; AlertDialog.Builder(this).setTitle("Calendar selection").setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, value -> checked[which] = value }.setPositiveButton("Save") { _, _ -> if (checked[0]) saveProfile(p.copy(calendarSelectionMode = CalendarSelectionMode.ALL_VISIBLE, selectedCalendarIds = emptySet())) else saveProfile(p.copy(calendarSelectionMode = CalendarSelectionMode.SELECTED, selectedCalendarIds = calendars.indices.filter { checked[it + 1] }.map { calendars[it].id }.toSet())) }.setNegativeButton("Cancel", null).show() }
    private fun editRoutine(existing: Routine?) {
        if (existing == null && store.load().routines.size >= RoutineCollection.MAX_ROUTINES) return message("Routine limit reached.")
        val input = EditText(this).apply { setText(existing?.let { "${it.name}\n${it.normalAction}\n${it.smallerAction}\n${it.weeklyTarget}" }.orEmpty()); hint = "Name\nNormal action\nSmaller action\nWeekly target"; minLines = 4 }
        dialog(if (existing == null) "Add routine" else "Edit routine", input) { raw ->
            val lines = raw.lines(); require(lines.isNotEmpty())
            val routine = Routine(existing?.id ?: "routine:${UUID.randomUUID()}", lines.getOrElse(0) { "Routine" }, lines.getOrElse(1) { "" }, lines.getOrElse(2) { "" }, lines.getOrNull(3)?.toIntOrNull() ?: 3, existing?.enabled ?: true, existing?.completedEpochDays ?: emptySet())
            val prefs = store.load(); val values = if (existing == null) prefs.routines + routine else prefs.routines.map { if (it.id == existing.id) routine else it }
            store.save(prefs.copy(routines = RoutineCollection.sanitize(values))); render()
        }
    }
    private fun routineMenu(routine: Routine) { val options = arrayOf(if (routine.enabled) "Disable" else "Enable", "Edit", "Delete"); AlertDialog.Builder(this).setTitle(routine.name).setItems(options) { _, which -> val prefs = store.load(); when (which) { 0 -> store.save(prefs.copy(routines = prefs.routines.map { if (it.id == routine.id) it.copy(enabled = !it.enabled) else it })); 1 -> return@setItems editRoutine(routine); 2 -> store.save(prefs.copy(routines = prefs.routines.filterNot { it.id == routine.id })) }; render() }.show() }
    private fun editTask(existing: LocalTask?) {
        var due = existing?.dueEpochMillis
        val title = EditText(this).apply { setText(existing?.title.orEmpty()); hint = "Task" }
        val important = CheckBox(this).apply { text = "Important"; isChecked = existing?.important == true }
        val dueButton = Button(this).apply { isAllCaps = false; fun update() { text = due?.let { "Due ${java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}" } ?: "No due date" }; update(); setOnClickListener { val initial = due?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(); DatePickerDialog(this@SettingsActivity, { _, y, m, d -> due = LocalDate.of(y, m + 1, d).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); update() }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show() }; setOnLongClickListener { due = null; update(); true } }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(important); addView(dueButton) }
        AlertDialog.Builder(this).setTitle(if (existing == null) "Add task" else "Edit task").setMessage("Long-press the due-date button to clear it.").setView(form).setPositiveButton("Save") { _, _ -> val prefs = store.load(); val tasks = if (existing == null) LocalTasks.add(prefs.localTasks, title.text.toString(), due, important.isChecked, System.currentTimeMillis()) else LocalTasks.edit(prefs.localTasks, existing.id, title.text.toString(), due, important.isChecked); store.save(prefs.copy(localTasks = tasks)); render() }.setNegativeButton("Cancel", null).show()
    }
    private fun taskMenu(task: LocalTask) { val options = arrayOf(if (task.completed) "Mark open" else "Complete", "Toggle important", "Edit", "Delete"); AlertDialog.Builder(this).setTitle(task.title).setItems(options) { _, which -> val prefs = store.load(); when (which) { 0 -> store.save(prefs.copy(localTasks = LocalTasks.complete(prefs.localTasks, task.id, !task.completed))); 1 -> store.save(prefs.copy(localTasks = LocalTasks.edit(prefs.localTasks, task.id, task.title, task.dueEpochMillis, !task.important))); 2 -> return@setItems editTask(task); 3 -> store.save(prefs.copy(localTasks = LocalTasks.delete(prefs.localTasks, task.id))) }; PlanStore(this).invalidate(); render() }.show() }
    private fun enterKey() { val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "Dedicated low-limit OpenAI key" }; AlertDialog.Builder(this).setTitle("OpenAI API key").setMessage("Encrypted with Android Keystore. Saving a key does not enable cloud planning. Enable snapshot sharing separately after reviewing the disclosure.").setView(input).setPositiveButton("Save") { _, _ -> credentials.saveOpenAiKey(input.text.toString()); PlannerCoordinator.schedule(this); render() }.setNegativeButton("Cancel", null).show() }
    private fun dialog(title: String, input: EditText, save: (String) -> Unit) { AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> runCatching { save(input.text.toString()) }.onFailure { message("Invalid value") } }.setNegativeButton("Cancel", null).show() }
    private fun saveProfile(value: PlannerProfile) { val prefs = store.load(); store.save(prefs.copy(plannerProfile = value.normalized())); PlanStore(this).invalidate(); render() }
    private fun title(v: String) = root.addView(TextView(this).apply { text = v; textSize = 28f; gravity = Gravity.START })
    private fun section(v: String) = root.addView(TextView(this).apply { text = v; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(24), 0, dp(8)) })
    private fun text(v: String) = root.addView(TextView(this).apply { text = v; textSize = 16f; setPadding(0, dp(4), 0, dp(8)) })
    private fun action(v: String, click: () -> Unit) = root.addView(Button(this).apply { text = v; isAllCaps = false; minHeight = dp(48); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setOnClickListener { click() } })
    private fun message(v: String) = Snackbar.make(root, v, Snackbar.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
