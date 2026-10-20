package com.petertroll.quiethome

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.petertroll.quiethome.databinding.ActivityPermissionCenterBinding
import com.petertroll.quiethome.domain.PermissionState
import kotlinx.coroutines.runBlocking

class PermissionCenterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPermissionCenterBinding
    private lateinit var store: RuleStore
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val healthPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityPermissionCenterBinding.inflate(layoutInflater); setContentView(binding.root); store = RuleStore(this)
        binding.homeButton.setOnClickListener { openHome() }
        binding.calendarButton.setOnClickListener { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
        binding.healthButton.setOnClickListener { requestHealthPermissions() }
        binding.usageButton.setOnClickListener { openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        binding.usageDashboardButton.setOnClickListener { startActivity(Intent(this, UsageDashboardActivity::class.java)) }
        binding.notificationAccessButton.setOnClickListener { openSetting(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        binding.notificationSettingsButton.setOnClickListener { startActivity(Intent(this, NotificationSettingsActivity::class.java)) }
        binding.notificationInboxButton.setOnClickListener { startActivity(Intent(this, NotificationInboxActivity::class.java)) }
        binding.notificationButton.setOnClickListener { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        binding.accessibilityButton.setOnClickListener { openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        binding.grayscaleButton.setOnClickListener { startActivity(Intent(this, GrayscaleSettingsActivity::class.java)) }
        binding.externalEnforcementSwitch.setOnCheckedChangeListener { _, checked ->
            if (QuietEnforcementService.isSystemEnabled(this)) store.update { it.copy(accessibilityEnforcementEnabled = checked) }
        }
        binding.focusButton.setOnClickListener { store.update { it.copy(focusUntilEpochMillis = System.currentTimeMillis() + 25 * 60_000L) }; render() }
        binding.endFocusButton.setOnClickListener { store.update { it.copy(focusUntilEpochMillis = null) }; render() }
        binding.doneButton.setOnClickListener { finish() }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        binding.homeStatus.text = status(R.string.default_home_explanation, isDefaultHome())
        val calendar = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        binding.calendarStatus.text = status(R.string.calendar_permission_explanation, calendar)
        binding.calendarButton.isEnabled = !calendar
        renderHealth()
        binding.usageStatus.text = status(R.string.usage_explanation, UsageAccess(this).isGranted())
        binding.notificationAccessStatus.text = status(R.string.notification_access_explanation, isNotificationListenerEnabled())
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        binding.notificationStatus.text = status(R.string.notification_permission_explanation, notifications)
        binding.notificationButton.isEnabled = Build.VERSION.SDK_INT >= 33 && !notifications
        val accessibility = QuietEnforcementService.isSystemEnabled(this)
        binding.accessibilityStatus.text = status(R.string.accessibility_explanation, accessibility)
        val stored = store.load()
        if (!accessibility && stored.accessibilityEnforcementEnabled) store.save(stored.copy(accessibilityEnforcementEnabled = false))
        val permissionState = PermissionState(accessibility, accessibility && stored.accessibilityEnforcementEnabled)
        binding.externalEnforcementSwitch.setOnCheckedChangeListener(null)
        binding.externalEnforcementSwitch.isEnabled = permissionState.canToggleEnforcement
        binding.externalEnforcementSwitch.isChecked = permissionState.effectiveEnforcementEnabled
        binding.externalEnforcementSwitch.setOnCheckedChangeListener { _, checked ->
            if (QuietEnforcementService.isSystemEnabled(this)) store.update { it.copy(accessibilityEnforcementEnabled = checked) }
        }
        val grayscale = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        binding.grayscaleStatus.text = status(R.string.grayscale_explanation, grayscale)
        val focus = stored.focusUntilEpochMillis?.let { it > System.currentTimeMillis() } == true
        binding.focusButton.isEnabled = !focus; binding.endFocusButton.isEnabled = focus
    }

    private fun status(explanation: Int, granted: Boolean) = getString(R.string.status_format, getString(explanation), getString(if (granted) R.string.granted else R.string.not_enabled))
    private fun renderHealth() {
        val sdk = if (Build.VERSION.SDK_INT >= 28) HealthConnectClient.getSdkStatus(this) else HealthConnectClient.SDK_UNAVAILABLE
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            val state = if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Provider missing or needs an update" else "Unavailable on this device"
            binding.healthStatus.text = "${getString(R.string.health_permission_explanation)}\n$state"
            binding.healthButton.isEnabled = sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            return
        }
        val granted = runCatching { runBlocking { HealthConnectClient.getOrCreate(this@PermissionCenterActivity).permissionController.getGrantedPermissions() } }.getOrDefault(emptySet())
        val foreground = granted.containsAll(HealthPermissions.foreground)
        val background = HealthPermissionStatus.backgroundAvailableAndGranted(this, granted)
        val state = when {
            foreground && background -> "Read access granted, including background"
            foreground -> "Read access granted; background access not granted or unavailable"
            granted.intersect(HealthPermissions.foreground).isNotEmpty() -> "Some read categories granted"
            else -> getString(R.string.not_enabled)
        }
        binding.healthStatus.text = "${getString(R.string.health_permission_explanation)}\n$state"
        binding.healthButton.isEnabled = true
    }
    private fun requestHealthPermissions() {
        if (Build.VERSION.SDK_INT < 28) return
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> healthPermissions.launch(HealthPermissionStatus.requestable(this))
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            }.recoverCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
            }
        }
    }
    private fun openSetting(action: String, data: Uri? = null) {
        val primary = Intent(action).apply { this.data = data }
        runCatching { startActivity(primary) }.recoverCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun openHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val role = getSystemService(RoleManager::class.java)
            if (role?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !role.isRoleHeld(RoleManager.ROLE_HOME)) {
                val launched = runCatching { startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME)); true }.getOrDefault(false)
                if (launched) return
            }
        }
        openSetting(Settings.ACTION_HOME_SETTINGS)
    }
    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        @Suppress("DEPRECATION") return packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName
    }
    private fun isNotificationListenerEnabled(): Boolean {
        val expected = ComponentName(this, QuietNotificationListenerService::class.java)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }
}
