package com.petertroll.quiethome

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityGrayscaleSettingsBinding
import java.util.concurrent.Executors

class GrayscaleSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGrayscaleSettingsBinding
    private val store by lazy { GrayscaleStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityGrayscaleSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.grantCommand.text = getString(R.string.adb_grant_command, packageName)
        binding.revokeCommand.text = getString(R.string.adb_revoke_command, packageName)
        binding.copyGrant.setOnClickListener { copy(binding.grantCommand.text) }
        binding.copyRevoke.setOnClickListener { copy(binding.revokeCommand.text) }
        binding.doneButton.setOnClickListener { finish() }
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        binding.grantStatus.text = getString(if (granted) R.string.secure_settings_granted else R.string.secure_settings_not_granted)
        GrayscaleController.restore(this) // Also recovers a transaction after permission is re-granted.
        val config = store.loadConfig()
        binding.enableSwitch.setOnCheckedChangeListener(null); binding.enableSwitch.isEnabled = granted
        binding.enableSwitch.isChecked = config.enabled && granted
        if (!granted && config.enabled) { store.save(config.copy(enabled = false)); GrayscaleController.restore(this) }
        binding.enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return@setOnCheckedChangeListener
            store.save(store.loadConfig().copy(enabled = checked)); if (!checked) GrayscaleController.restore(this)
        }
        binding.apps.removeAllViews(); binding.loading.visibility = View.VISIBLE
        worker.execute {
            val safe = SafetyPackageAdapter(applicationContext).safePackages()
            val apps = AndroidAppRepository(applicationContext).launchableApps().filterNot { it.packageName in safe }.sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isDestroyed) { binding.loading.visibility = View.GONE; apps.forEach { app ->
                binding.apps.addView(CheckBox(this).apply {
                    text = app.label.ifBlank { app.packageName }; isChecked = store.loadConfig().packages.contains(app.packageName)
                    setOnCheckedChangeListener { _, selected -> val current = store.loadConfig(); val packages = current.packages.toMutableSet(); if (selected) packages += app.packageName else packages -= app.packageName; store.save(current.copy(packages = packages)) }
                })
            } } }
        }
    }
    private fun copy(value: CharSequence) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.adb_command), value)) }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
