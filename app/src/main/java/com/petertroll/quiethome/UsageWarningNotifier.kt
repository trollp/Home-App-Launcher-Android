package com.petertroll.quiethome

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Visible, rate-limited disclosure for external enforcement when usage access is absent. */
object UsageWarningNotifier {
    private const val CHANNEL = "limit_warnings"
    private const val LAST_SHOWN = "usage_warning_last_shown"
    private const val RATE_LIMIT_MILLIS = 6 * 60 * 60 * 1_000L

    fun showRateLimited(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("warnings", Context.MODE_PRIVATE)
        if (nowMillis - prefs.getLong(LAST_SHOWN, 0L) < RATE_LIMIT_MILLIS) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.limit_warnings), NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.daily_limits_unavailable))
            .setContentText(context.getString(R.string.usage_fail_open))
            .setAutoCancel(true)
            .build()
        if (runCatching { manager.notify(4102, notification); true }.getOrDefault(false)) {
            prefs.edit().putLong(LAST_SHOWN, nowMillis).apply()
        }
    }
}
