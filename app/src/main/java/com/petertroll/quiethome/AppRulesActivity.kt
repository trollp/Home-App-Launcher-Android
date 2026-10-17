package com.petertroll.quiethome

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityAppRulesBinding
import com.petertroll.quiethome.domain.*

class AppRulesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppRulesBinding
    private val delays = listOf(0, 3, 5, 10, 15, 30)
    private val sessions = listOf(null, 5, 10, 15, 30, 60)
    private val limits = listOf(null, 15, 30, 60, 120)
    private val days = DaySet.entries
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityAppRulesBinding.inflate(layoutInflater); setContentView(binding.root)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        val rule = RuleStore(this).load().rules[pkg] ?: AppRule()
        binding.title.text = getString(R.string.rules_for, label)
        setup(binding.delaySpinner, delays.map { resources.getQuantityString(R.plurals.seconds_value, it, it) }); setup(binding.sessionSpinner, sessions.map { it?.let { value -> resources.getQuantityString(R.plurals.minutes_value, value, value) } ?: getString(R.string.none) })
        setup(binding.limitSpinner, limits.map { it?.let { value -> resources.getQuantityString(R.plurals.minutes_value, value, value) } ?: getString(R.string.none) }); setup(binding.daySpinner, resources.getStringArray(R.array.day_sets).toList())
        binding.gateSwitch.isChecked = rule.gateEnabled; binding.delaySpinner.setSelection(delays.indexOf(rule.delaySeconds).takeIf { it >= 0 } ?: 2)
        binding.sessionSpinner.setSelection(sessions.indexOf(rule.sessionMinutes).takeIf { it >= 0 } ?: 0); binding.promptInput.setText(rule.prompt)
        binding.limitSpinner.setSelection(limits.indexOf(rule.dailyLimitMinutes).takeIf { it >= 0 } ?: 0); binding.focusSwitch.isChecked = rule.blockDuringFocus
        rule.schedules.firstOrNull()?.let { schedule -> binding.scheduleSwitch.isChecked = true; binding.daySpinner.setSelection(days.indexOf(schedule.days)); binding.startHour.setText((schedule.startMinute / 60).toString()); binding.endHour.setText((schedule.endMinute / 60).toString()) }
        if (rule.schedules.isEmpty()) { binding.startHour.setText(R.string.default_start_hour); binding.endHour.setText(R.string.default_end_hour) }
        binding.usageNote.text = getString(if (UsageAccess(this).isGranted()) R.string.usage_limits_ready else R.string.usage_limits_note)
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener {
            val start = validHour(binding.startHour.text.toString())
            val end = validHour(binding.endHour.text.toString())
            if (binding.scheduleSwitch.isChecked && (start == null || end == null || start == end)) {
                binding.startHour.error = getString(R.string.invalid_schedule)
                binding.endHour.error = getString(R.string.invalid_schedule)
                return@setOnClickListener
            }
            val schedule = if (binding.scheduleSwitch.isChecked) listOf(ScheduleRule(days[binding.daySpinner.selectedItemPosition], start!! * 60, end!! * 60)) else emptyList()
            val updated = AppRule(binding.gateSwitch.isChecked, delays[binding.delaySpinner.selectedItemPosition], sessions[binding.sessionSpinner.selectedItemPosition], binding.promptInput.text.toString().trim().takeIf(String::isNotEmpty), limits[binding.limitSpinner.selectedItemPosition], schedule, binding.focusSwitch.isChecked)
            RuleStore(this).update { it.copy(rules = it.rules + (pkg to updated)) }; finish()
        }
    }
    private fun setup(spinner: android.widget.Spinner, values: List<String>) { spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values) }
    private fun validHour(raw: String) = raw.toIntOrNull()?.takeIf { it in 0..23 }
    companion object { const val EXTRA_PACKAGE = "package"; const val EXTRA_LABEL = "label" }
}
