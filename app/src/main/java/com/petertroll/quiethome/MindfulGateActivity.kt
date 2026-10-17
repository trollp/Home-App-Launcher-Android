package com.petertroll.quiethome

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.petertroll.quiethome.databinding.ActivityMindfulGateBinding
import com.petertroll.quiethome.domain.CountdownState
import com.petertroll.quiethome.domain.LaunchDecision

class MindfulGateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMindfulGateBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: CountdownState
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (!countdown.launched && countdown.remainingSeconds(SystemClock.elapsedRealtime()) > 0) handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindfulGateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        countdown = CountdownState(
            deadlineElapsedMillis = savedInstanceState?.getLong(STATE_DEADLINE)
                ?: (SystemClock.elapsedRealtime() + intent.getIntExtra(EXTRA_DELAY, 0) * 1_000L),
            launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) == true,
            bypassConfirmationPending = savedInstanceState?.getBoolean(STATE_BYPASS_PENDING) == true
        )
        binding.gateTitle.text = getString(R.string.mindful_open_title, intent.getStringExtra(EXTRA_LABEL).orEmpty())
        binding.intentPrompt.text = intent.getStringExtra(EXTRA_PROMPT) ?: getString(R.string.default_intent_prompt)
        binding.cancelButton.setOnClickListener { finish() }
        binding.openButton.setOnClickListener { launchOnce(bypass = false) }
        binding.bypassButton.setOnClickListener { showBypassConfirmation() }
        if (countdown.bypassConfirmationPending) showBypassConfirmation()
        render()
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(tick); tick.run() }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_DEADLINE, countdown.deadlineElapsedMillis)
        outState.putBoolean(STATE_LAUNCHED, countdown.launched)
        outState.putBoolean(STATE_BYPASS_PENDING, countdown.bypassConfirmationPending)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val seconds = countdown.remainingSeconds(SystemClock.elapsedRealtime())
        binding.countdown.text = resources.getQuantityString(R.plurals.seconds_remaining, seconds.toInt(), seconds)
        binding.openButton.isEnabled = countdown.canLaunch(SystemClock.elapsedRealtime())
        binding.bypassButton.isEnabled = !countdown.launched
    }

    private fun showBypassConfirmation() {
        if (countdown.launched) return
        countdown = countdown.requestBypass()
        AlertDialog.Builder(this).setMessage(R.string.bypass_explanation)
            .setPositiveButton(R.string.bypass_once) { _, _ -> launchOnce(bypass = true) }
            .setNegativeButton(R.string.cancel) { _, _ -> countdown = countdown.copy(bypassConfirmationPending = false) }
            .setOnCancelListener { countdown = countdown.copy(bypassConfirmationPending = false) }
            .show()
    }

    private fun launchOnce(bypass: Boolean) {
        if (countdown.launched) return
        val now = SystemClock.elapsedRealtime()
        if (!bypass && !countdown.canLaunch(now)) return
        countdown = if (bypass) countdown.confirmBypass() else countdown.markLaunched(now)
        binding.openButton.isEnabled = false
        binding.bypassButton.isEnabled = false
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        if (LaunchRouter.open(this, pkg, intent.getStringExtra(EXTRA_ACTIVITY), label)) scheduleReminder(pkg, label)
        finish()
    }

    private fun scheduleReminder(pkg: String, label: String) {
        val minutes = intent.getIntExtra(EXTRA_SESSION, 0)
        if (minutes <= 0) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, R.string.reminder_permission_missing, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        ReminderReceiver.createChannel(this)
        val reminderIntent = Intent(this, ReminderReceiver::class.java).putExtra(EXTRA_LABEL, label)
        val delayMillis = minutes * 60_000L
        if (delayMillis <= 60_000L) {
            val app = applicationContext
            processHandler.postDelayed({ ReminderReceiver().onReceive(app, reminderIntent) }, delayMillis)
            return
        }
        val pending = PendingIntent.getBroadcast(this, pkg.hashCode(), reminderIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMillis,
            pending
        )
    }

    companion object {
        const val EXTRA_PACKAGE = "package"; const val EXTRA_ACTIVITY = "activity"; const val EXTRA_LABEL = "label"
        private const val EXTRA_DELAY = "delay"; private const val EXTRA_SESSION = "session"; private const val EXTRA_PROMPT = "prompt"
        private const val STATE_DEADLINE = "deadline"; private const val STATE_LAUNCHED = "launched"; private const val STATE_BYPASS_PENDING = "bypassPending"
        private val processHandler = Handler(Looper.getMainLooper())
        fun intent(context: Context, pkg: String, activity: String?, label: String, decision: LaunchDecision.Gate) = Intent(context, MindfulGateActivity::class.java)
            .putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_ACTIVITY, activity).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_DELAY, decision.delaySeconds).putExtra(EXTRA_SESSION, decision.sessionMinutes ?: 0).putExtra(EXTRA_PROMPT, decision.prompt)
    }
}
