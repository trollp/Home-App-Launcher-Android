package com.petertroll.quiethome

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.petertroll.quiethome.domain.*
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** Serial worker edge for storage, package discovery, UsageStats and both launch decisions. */
object RuleEvaluator {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quiet-home-rules").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun evaluate(
        context: Context,
        packageName: String,
        requireExternalEnforcement: Boolean = false,
        callback: (CombinedLaunchDecision) -> Unit
    ) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val state = RuleStore(app).load() // Exactly one persisted snapshot for both decisions.
                evaluateSnapshot(app, packageName, state, requireExternalEnforcement)
            }.getOrElse { failOpen() }
            main.post { callback(result) }
        }
    }

    /** The durable grace update and its one-snapshot re-evaluation share this same serial worker. */
    fun grantGraceAndEvaluate(
        context: Context,
        packageName: String,
        durationMillis: Long = 5 * 60_000L,
        callback: (CombinedLaunchDecision) -> Unit
    ) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val until = System.currentTimeMillis() + durationMillis
                val state = RuleStore(app).update {
                    it.copy(graceUntilEpochMillis = it.graceUntilEpochMillis + (packageName to until))
                }
                evaluateSnapshot(app, packageName, state, requireExternalEnforcement = false)
            }.getOrElse { failOpen() }
            main.post { callback(result) }
        }
    }

    private fun evaluateSnapshot(
        app: Context,
        packageName: String,
        state: RulesState,
        requireExternalEnforcement: Boolean
    ): CombinedLaunchDecision {
        val safePackages = SafetyPackageAdapter(app).safePackages()
        val usage = UsageAccess(app)
        val nowEpoch = System.currentTimeMillis()
        val context = EvaluationContext(
            now = LocalDateTime.now(),
            usedMinutesToday = usage.minutesToday(packageName, nowEpoch),
            usageAccessAvailable = usage.isGranted(),
            nowEpochMillis = nowEpoch,
            focusUntilEpochMillis = state.focusUntilEpochMillis,
            graceUntilEpochMillis = state.graceUntilEpochMillis[packageName]
        )
        val combined = CombinedLaunchPolicy(safePackages).evaluate(packageName, state.rules[packageName], context)
        return if (requireExternalEnforcement && !state.accessibilityEnforcementEnabled) {
            combined.copy(block = BlockDecision.Allow())
        } else combined
    }

    private fun failOpen() = CombinedLaunchDecision(BlockDecision.Allow(), LaunchDecision.Direct)
}
