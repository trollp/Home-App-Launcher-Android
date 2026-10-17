package com.petertroll.quiethome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.petertroll.quiethome.domain.AppEntry

class AndroidAppRepository(private val context: Context) {
    fun launchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val results = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return results.asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    runCatching { it.loadLabel(context.packageManager).toString() }.getOrDefault("")
                )
            }
            .sortedWith(compareBy<AppEntry> { it.packageName }.thenBy { it.activityName }.thenBy { it.label })
            .distinctBy { it.packageName }
            .toList()
    }

    fun launch(app: AppEntry): Boolean {
        val exact = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return launchWithFallback(
            exact = { context.startActivity(exact) },
            fallback = {
                val fallbackIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: return@launchWithFallback false
                context.startActivity(fallbackIntent)
                true
            }
        )
    }

    fun openInfo(packageName: String) = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )

    fun requestUninstall(packageName: String) = context.startActivity(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
    )
}

internal fun launchWithFallback(exact: () -> Unit, fallback: () -> Boolean): Boolean =
    try {
        exact()
        true
    } catch (_: RuntimeException) {
        try {
            fallback()
        } catch (_: RuntimeException) {
            false
        }
    }
