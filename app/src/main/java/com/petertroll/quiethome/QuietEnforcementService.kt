package com.petertroll.quiethome

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.petertroll.quiethome.domain.*

/** Optional, user-enabled enforcement for app openings that did not originate in Quiet Home. */
class QuietEnforcementService : AccessibilityService() {
    private val requestGate = ActiveRequestGate()
    private var lastQueuedPackage: String? = null
    private var lastQueuedAt = 0L
    override fun onServiceConnected() {
        super.onServiceConnected()
        requestGate.start()
        lastQueuedPackage = null
        lastQueuedAt = 0L
        GrayscaleController.restore(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        runCatching {
            val pkg = event.packageName?.toString() ?: return
            if (!requestGate.isActive()) return
            // This only queues policy work; secure-setting reads/writes never run on this callback.
            GrayscaleController.foreground(this, pkg)
            val nowElapsed = SystemClock.elapsedRealtime()
            // Collapse duplicate window callbacks, but never suppress a later genuine opening.
            if (pkg == lastQueuedPackage && nowElapsed - lastQueuedAt < 300L) return
            lastQueuedPackage = pkg
            lastQueuedAt = nowElapsed
            val token = requestGate.newRequest() ?: return
            RuleEvaluator.evaluate(this, pkg, requireExternalEnforcement = true) { result ->
                if (!requestGate.accepts(token)) return@evaluate
                if (result.block is BlockDecision.Allow && result.block.warning == UsageWarning.ACCESS_MISSING) {
                    UsageWarningNotifier.showRateLimited(this)
                }
                if (result.block is BlockDecision.Block) {
                    runCatching { startActivity(BlockedActivity.intent(this, pkg, null, appLabel(pkg), result.block.reason)) }
                    // Fail open if the explanatory activity cannot be launched.
                }
            }
        } // Deliberately fail open: accessibility must never trap the user or destabilize System UI.
    }
    override fun onInterrupt() { requestGate.stop(); GrayscaleController.restore(this) }
    override fun onDestroy() { requestGate.stop(); GrayscaleController.restore(this); super.onDestroy() }
    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    companion object {
        fun isSystemEnabled(context: Context): Boolean {
            val expected = ComponentName(context, QuietEnforcementService::class.java)
            val masterEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            return masterEnabled && Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
        }
    }
}
