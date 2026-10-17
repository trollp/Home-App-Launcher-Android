package com.petertroll.quiethome

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.petertroll.quiethome.domain.PackageSafety

/** Discovers device-specific packages that must never be redirected or gated. */
class SafetyPackageAdapter(private val context: Context) {
    fun safePackages(): Set<String> = buildSet {
        add(context.packageName)
        addAll(PackageSafety.baseline)
        val pm = context.packageManager

        fun candidates(intent: Intent) {
            runCatching {
                pm.queryIntentActivities(intent, 0).forEach { add(it.activityInfo.packageName) }
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.let(::add)
            }
        }

        candidates(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        candidates(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        candidates(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")))
        candidates(Intent(AlarmClock.ACTION_SET_ALARM))
        candidates(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        candidates(Intent(Settings.ACTION_SETTINGS))
        candidates(Intent("android.intent.action.MANAGE_PERMISSIONS"))
        candidates(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        candidates(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(Uri.parse("content://invalid"), "application/vnd.android.package-archive"))
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { add(it.packageName) }

        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            listOf(RoleManager.ROLE_HOME, RoleManager.ROLE_DIALER, RoleManager.ROLE_EMERGENCY).forEach { role ->
                if (roles?.isRoleAvailable(role) == true) candidates(roles.createRequestRoleIntent(role))
            }
        }
    }
}
