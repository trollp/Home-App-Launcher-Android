package com.petertroll.quiethome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        createChannel(context)
        val label = intent.getStringExtra(MindfulGateActivity.EXTRA_LABEL) ?: context.getString(R.string.an_app)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(context.getString(R.string.session_finished))
            .setContentText(context.getString(R.string.session_finished_body, label)).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(label.hashCode(), notification) }
    }
    companion object {
        private const val CHANNEL = "session_reminders"
        fun createChannel(context: Context) { context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.session_reminders), NotificationManager.IMPORTANCE_DEFAULT)) }
    }
}
