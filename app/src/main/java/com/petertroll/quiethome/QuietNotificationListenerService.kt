package com.petertroll.quiethome

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.petertroll.quiethome.domain.*
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Local-only filter. Every unexpected condition deliberately leaves the notification untouched. */
class QuietNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        runCatching {
            val notification = sbn.notification ?: return
            val signal = NotificationSignal(
                packageName = sbn.packageName,
                isCall = notification.category == Notification.CATEGORY_CALL,
                isAlarm = notification.category == Notification.CATEGORY_ALARM,
                isMedia = notification.category == Notification.CATEGORY_TRANSPORT || notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true,
                isNavigation = notification.category == "navigation",
                isSystem = sbn.packageName == "android" || notification.category == Notification.CATEGORY_SYSTEM,
                isOngoing = !sbn.isClearable || notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                isSafety = notification.fullScreenIntent != null || notification.category in setOf("car_emergency", "car_warning")
            )
            when (NotificationDataStore(this).loadRules().decide(signal)) {
                NotificationAction.ALLOW -> Unit
                NotificationAction.CANCEL_ONLY -> runCatching { cancelNotification(sbn.key) }
                NotificationAction.CANCEL_AND_STORE -> {
                    val private = notification.visibility == Notification.VISIBILITY_SECRET
                    val extras = notification.extras
                    val id = UUID.nameUUIDFromBytes(sbn.key.toByteArray(StandardCharsets.UTF_8)).toString()
                    val item = InboxItem.sanitized(id, sbn.packageName,
                        if (private) null else extras?.getCharSequence(Notification.EXTRA_TITLE),
                        if (private) null else extras?.getCharSequence(Notification.EXTRA_TEXT), sbn.postTime)
                    // Never cancel a batch item unless its local replacement was durably stored.
                    if (NotificationDataStore(this).add(item)) runCatching { cancelNotification(sbn.key) }
                    Unit
                }
            }
        }
    }
}
