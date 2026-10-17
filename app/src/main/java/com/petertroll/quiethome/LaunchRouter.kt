package com.petertroll.quiethome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.petertroll.quiethome.domain.BlockDecision
import com.petertroll.quiethome.domain.LaunchDecision
import java.util.WeakHashMap

object LaunchRouter {
    private val ownerGates = WeakHashMap<Activity, ActiveRequestGate>()

    /** Marks a launch owner active. Called from the owner's onStart callback. */
    fun ownerStarted(owner: Activity) = synchronized(ownerGates) {
        ownerGates.getOrPut(owner, ::ActiveRequestGate).start()
    }

    /** Invalidates pending launches before the owner's onStop callback completes. */
    fun ownerStopped(owner: Activity) = synchronized(ownerGates) {
        ownerGates.remove(owner)?.stop()
        Unit
    }

    /** Storage, UsageStats and both decisions always happen on RuleEvaluator's worker. */
    fun fromLauncher(owner: Activity, packageName: String, activityName: String?, label: String) {
        val request = synchronized(ownerGates) {
            val gate = ownerGates[owner] ?: return
            val token = gate.newRequest() ?: return
            gate to token
        }
        RuleEvaluator.evaluate(owner, packageName) { result ->
            val currentOwnerRequest = synchronized(ownerGates) {
                ownerGates[owner] === request.first && request.first.accepts(request.second)
            }
            if (!currentOwnerRequest || owner.isFinishing || owner.isDestroyed) return@evaluate
            if (result.block is BlockDecision.Block) {
                owner.startActivity(BlockedActivity.intent(owner, packageName, activityName, label, result.block.reason))
                return@evaluate
            }
            if (result.block is BlockDecision.Allow && result.block.warning != null) {
                UsageWarningNotifier.showRateLimited(owner)
            }
            when (val launch = result.launch) {
                LaunchDecision.Direct -> open(owner, packageName, activityName, label)
                is LaunchDecision.Gate -> owner.startActivity(MindfulGateActivity.intent(owner, packageName, activityName, label, launch))
            }
        }
    }

    /** Try the exact launcher component first, then recover with the package launch intent. */
    fun open(context: Context, packageName: String, activityName: String?, label: String): Boolean {
        val exact = activityName?.let {
            Intent(Intent.ACTION_MAIN).setClassName(packageName, it).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (exact != null && runCatching { context.startActivity(exact); true }.getOrDefault(false)) return true
        val fallback = context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback ?: error("No launch intent")); true }.getOrElse {
            Toast.makeText(context, context.getString(R.string.launch_failed, label), Toast.LENGTH_SHORT).show()
            false
        }
    }
}
