package com.petertroll.quiethome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.petertroll.quiethome.databinding.ActivityBlockedBinding
import com.petertroll.quiethome.domain.BlockReason
import com.petertroll.quiethome.domain.LaunchDecision

class BlockedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBlockedBinding
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private val tick = object : Runnable { override fun run() { render(); if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(this, 250) } }
    private var actionTaken = false
    private var evaluationPending = false
    private val requestGate = ActiveRequestGate()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityBlockedBinding.inflate(layoutInflater); setContentView(binding.root)
        deadline = savedInstanceState?.getLong("deadline") ?: (SystemClock.elapsedRealtime() + 3_000)
        actionTaken = savedInstanceState?.getBoolean("actionTaken") == true
        binding.blockedTitle.text = getString(R.string.app_paused, intent.getStringExtra(MindfulGateActivity.EXTRA_LABEL).orEmpty())
        binding.blockReason.text = when (runCatching { BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty()) }.getOrNull()) {
            BlockReason.DAILY_LIMIT -> getString(R.string.daily_limit_reason)
            BlockReason.SCHEDULE -> getString(R.string.schedule_reason)
            else -> getString(R.string.focus_reason)
        }
        binding.cancelButton.setOnClickListener { goHomeSafely() }
        binding.overrideButton.setOnClickListener { override() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHomeSafely()
        })
        render()
    }
    override fun onStart() { super.onStart(); requestGate.start() }
    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onStop() {
        requestGate.stop()
        if (evaluationPending) {
            evaluationPending = false
            actionTaken = false
        }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("deadline", deadline)
        // A pending evaluation is invalidated by onStop, so it must not restore as completed.
        outState.putBoolean("actionTaken", actionTaken && !evaluationPending)
        super.onSaveInstanceState(outState)
    }
    private fun render() {
        val seconds = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
        binding.overrideButton.isEnabled = seconds == 0L && !actionTaken
        binding.overrideButton.text = if (seconds > 0) resources.getQuantityString(R.plurals.override_wait, seconds.toInt(), seconds) else getString(R.string.override_five_minutes)
    }
    private fun override() {
        if (actionTaken) return
        val pkg = intent.getStringExtra(MindfulGateActivity.EXTRA_PACKAGE) ?: return finish()
        val activity = intent.getStringExtra(MindfulGateActivity.EXTRA_ACTIVITY)
        val label = intent.getStringExtra(MindfulGateActivity.EXTRA_LABEL) ?: pkg
        val request = requestGate.newRequest() ?: return
        actionTaken = true
        evaluationPending = true
        binding.overrideButton.isEnabled = false
        RuleEvaluator.grantGraceAndEvaluate(this, pkg) { result ->
            if (!requestGate.accepts(request) || isFinishing || isDestroyed) return@grantGraceAndEvaluate
            evaluationPending = false
            // Override bypasses this block only; an intentional-opening gate still applies.
            when (val launch = result.launch) {
                LaunchDecision.Direct -> LaunchRouter.open(this, pkg, activity, label)
                is LaunchDecision.Gate -> startActivity(MindfulGateActivity.intent(this, pkg, activity, label, launch))
            }
            finish()
        }
    }
    private fun goHomeSafely() {
        if (actionTaken) return
        actionTaken = true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        runCatching { startActivity(home) }
        // Failure is intentionally fail-open; never leave this activity trapping the user.
        finish()
    }
    override fun onDestroy() { requestGate.stop(); super.onDestroy() }
    companion object {
        private const val EXTRA_REASON = "reason"
        fun intent(context: Context, pkg: String, activity: String?, label: String, reason: BlockReason) = Intent(context, BlockedActivity::class.java)
            .putExtra(MindfulGateActivity.EXTRA_PACKAGE, pkg).putExtra(MindfulGateActivity.EXTRA_ACTIVITY, activity).putExtra(MindfulGateActivity.EXTRA_LABEL, label).putExtra(EXTRA_REASON, reason.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
