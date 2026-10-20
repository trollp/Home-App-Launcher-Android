package com.petertroll.quiethome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action?.startsWith("planner-") == true) {
            val meeting = intent.action?.startsWith("planner-prep:") == true
            val channel = if (meeting) PlannerChannels.MEETING else PlannerChannels.PROACTIVE
            val channelName = if (meeting) "Meeting preparation" else "Planner nudges"
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT),
            )
            val text = intent.getStringExtra("label")?.takeIf(String::isNotBlank) ?: return
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(if (meeting) "Meeting preparation" else "Quiet Home")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(intent.action.hashCode(), notification) }
            if (!meeting) ActivityLogStore(context).append(com.petertroll.quiethome.domain.ActivityKind.PROACTIVE_NUDGE, intent.action)
            return
        }
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

object PlannerChannels {
    const val MEETING = "planner_meeting_prep"
    const val PROACTIVE = "planner_proactive"
}
