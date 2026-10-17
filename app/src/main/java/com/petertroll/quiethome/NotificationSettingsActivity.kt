package com.petertroll.quiethome

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityNotificationSettingsBinding
import com.petertroll.quiethome.domain.NotificationMode
import java.util.concurrent.Executors

class NotificationSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotificationSettingsBinding
    private val store by lazy { NotificationDataStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val retentionValues = intArrayOf(1, 3, 7, 14, 30)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityNotificationSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        val labels = retentionValues.map { resources.getQuantityString(R.plurals.days_value, it, it) }
        binding.retentionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.doneButton.setOnClickListener { finish() }
    }
    override fun onResume() {
        super.onResume(); val rules = store.loadRules()
        binding.retentionSpinner.setSelection(retentionValues.indexOf(rules.retentionDays).coerceAtLeast(0))
        binding.retentionSpinner.onItemSelectedListener = SimpleItemSelectedListener { position -> saveRetention(retentionValues[position]) }
        binding.appModes.removeAllViews(); binding.loading.visibility = View.VISIBLE
        worker.execute {
            val apps = AndroidAppRepository(applicationContext).launchableApps().sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isDestroyed) { binding.loading.visibility = View.GONE; apps.forEach { app -> addApp(app.packageName, app.label.ifBlank { app.packageName }) } } }
        }
    }
    private fun addApp(pkg: String, label: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        row.addView(TextView(this).apply { text = label; textSize = 17f })
        row.addView(Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(this@NotificationSettingsActivity, R.array.notification_modes, android.R.layout.simple_spinner_dropdown_item)
            setSelection((store.loadRules().modes[pkg] ?: NotificationMode.ALLOW).ordinal)
            onItemSelectedListener = SimpleItemSelectedListener { position ->
                val current = store.loadRules(); val next = current.modes.toMutableMap(); val mode = NotificationMode.entries[position]
                if (mode == NotificationMode.ALLOW) next.remove(pkg) else next[pkg] = mode
                store.saveRules(current.copy(modes = next))
            }
        })
        binding.appModes.addView(row)
    }
    private fun saveRetention(days: Int) { val current = store.loadRules(); store.saveRules(current.copy(retentionDays = days)) }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
