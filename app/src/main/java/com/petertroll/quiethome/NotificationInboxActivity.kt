package com.petertroll.quiethome

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityNotificationInboxBinding
import com.petertroll.quiethome.domain.InboxItem
import java.text.DateFormat
import java.util.Date

class NotificationInboxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotificationInboxBinding
    private val store by lazy { NotificationDataStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityNotificationInboxBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.clearAllButton.setOnClickListener { store.clear(); render() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, NotificationSettingsActivity::class.java)) }
        binding.doneButton.setOnClickListener { finish() }
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val items = store.loadInbox()
        binding.items.removeAllViews(); binding.emptyText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.clearAllButton.isEnabled = items.isNotEmpty()
        var previousGroup: String? = null
        items.sortedWith(compareByDescending<InboxItem> { it.postedMillis }.thenBy { it.packageName }).forEach { item ->
            val label = appLabel(item.packageName)
            val group = getString(R.string.inbox_group_format, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.postedMillis)), label)
            if (group != previousGroup) binding.items.addView(TextView(this).apply { text = group; textSize = 14f; gravity = Gravity.START; setPadding(0, 22, 0, 6) })
            previousGroup = group
            binding.items.addView(TextView(this).apply {
                text = getString(R.string.inbox_item_format,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.postedMillis)),
                    item.title ?: getString(R.string.notification_no_title), item.text ?: "")
                textSize = 17f; minHeight = 64; isClickable = true; isFocusable = true; setPadding(12, 10, 12, 10)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { openSource(item.packageName) }
                setOnLongClickListener { store.remove(item.localId); render(); true }
            })
        }
    }
    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun openSource(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null || runCatching { startActivity(launch); true }.getOrDefault(false).not()) Toast.makeText(this, R.string.source_app_unavailable, Toast.LENGTH_LONG).show()
    }
}
