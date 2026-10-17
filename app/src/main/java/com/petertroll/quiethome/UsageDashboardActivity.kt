package com.petertroll.quiethome

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityUsageDashboardBinding
import com.petertroll.quiethome.domain.UsageSummary
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class UsageDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUsageDashboardBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageDashboardBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.usageSettingsButton.setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        binding.retryButton.setOnClickListener { beginLoad() }
        binding.doneButton.setOnClickListener { finish() }
    }
    override fun onResume() { super.onResume(); beginLoad() }
    private fun beginLoad() {
        binding.stateText.setText(R.string.loading_usage)
        binding.stateText.visibility = View.VISIBLE
        binding.content.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.usageSettingsButton.visibility = View.GONE
        load(++loadGeneration)
    }
    private fun load(generation: Int) = worker.execute {
        val result = UsageStatsAdapter(applicationContext).summary()
        runOnUiThread { if (!isFinishing && !isDestroyed && generation == loadGeneration) render(result) }
    }
    private fun render(summary: UsageSummary) {
        when (summary) {
            UsageSummary.MissingPermission -> {
                binding.stateText.setText(R.string.usage_permission_needed); binding.stateText.visibility = View.VISIBLE
                showRecoveryActions()
            }
            UsageSummary.NoData -> {
                binding.stateText.setText(R.string.no_usage_data); binding.stateText.visibility = View.VISIBLE
                showRecoveryActions()
            }
            UsageSummary.QueryError -> {
                binding.stateText.setText(R.string.usage_query_error); binding.stateText.visibility = View.VISIBLE
                showRecoveryActions()
            }
            is UsageSummary.Available -> {
                binding.usageSettingsButton.visibility = View.GONE; binding.retryButton.visibility = View.GONE; binding.content.visibility = View.VISIBLE
                binding.stateText.visibility = if (summary.todayApps.isEmpty()) View.VISIBLE else View.GONE
                binding.stateText.setText(R.string.no_usage_today)
                binding.todayTotal.text = getString(R.string.today_total_format, duration(summary.todayTotalMillis))
                binding.appBreakdown.removeAllViews()
                summary.todayApps.forEach { app -> binding.appBreakdown.addView(TextView(this).apply {
                    val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app.packageName, 0)).toString() }.getOrDefault(app.packageName)
                    text = getString(R.string.usage_app_row, label, duration(app.durationMillis)); textSize = 17f; setPadding(0, 10, 0, 10)
                }) }
                val max = summary.days.maxOfOrNull { it.totalMillis }?.coerceAtLeast(1L) ?: 1L
                binding.trend.text = summary.days.joinToString("\n") { day ->
                    val bars = ((day.totalMillis * 12.0 / max).roundToInt()).coerceIn(0, 12)
                    getString(R.string.usage_trend_row, day.date.format(DateTimeFormatter.ofPattern("EEE")), "▰".repeat(bars), duration(day.totalMillis))
                }
            }
        }
    }
    private fun showRecoveryActions() {
        binding.usageSettingsButton.visibility = View.VISIBLE
        binding.retryButton.visibility = View.VISIBLE
        binding.content.visibility = View.GONE
    }
    private fun duration(millis: Long): String {
        val minutes = millis / 60_000L
        return if (minutes < 60) resources.getQuantityString(R.plurals.minutes_value, minutes.toInt(), minutes.toInt())
        else getString(R.string.hours_minutes, minutes / 60, minutes % 60)
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
