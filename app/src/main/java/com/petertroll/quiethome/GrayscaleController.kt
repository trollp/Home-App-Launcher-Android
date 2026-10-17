package com.petertroll.quiethome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.petertroll.quiethome.domain.*
import java.util.concurrent.Executors

/** Uses only the public WRITE_SECURE_SETTINGS contract and documented Settings provider APIs. */
@SuppressLint("ApplySharedPref") // Restore state must be durable before changing a device-wide setting.
object GrayscaleController {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "quiet-home-grayscale").apply { isDaemon = true } }

    fun foreground(context: Context, packageName: String?) = worker.execute {
        val app = context.applicationContext
        runCatching {
            val granted = hasPermission(app)
            val store = GrayscaleStore(app)
            val transaction = transaction(app, store)
            // APPLYING/RESTORING means a prior operation was interrupted. Recover before a new decision.
            if (granted && store.load()?.phase != GrayscalePhase.APPLIED) transaction.restore(true)
            val decision = GrayscalePolicy(app.packageName, SafetyPackageAdapter(app).safePackages())
                .decide(packageName, store.loadConfig(), granted, transaction.isApplied())
            when (decision) {
                GrayscaleDecision.ENABLE -> transaction.apply(granted)
                GrayscaleDecision.RESTORE -> transaction.restore(granted)
                GrayscaleDecision.NO_CHANGE -> Unit
            }
        }.onFailure { restoreNow(app) }
    }

    /** Queue a restore/recovery attempt; permission loss deliberately leaves the snapshot pending. */
    fun restore(context: Context) = worker.execute { restoreNow(context.applicationContext) }

    private fun restoreNow(context: Context) {
        transaction(context, GrayscaleStore(context)).restore(hasPermission(context))
    }

    private fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun transaction(context: Context, store: GrayscaleStore) =
        GrayscaleTransactionCoordinator(store, object : GrayscaleSettingsGateway {
            override fun readMode(): String? = Settings.Secure.getString(context.contentResolver, MODE)
            override fun readEnabled(): String? = Settings.Secure.getString(context.contentResolver, ENABLED)
            override fun writeMode(value: String?): Boolean = Settings.Secure.putString(context.contentResolver, MODE, value)
            override fun writeEnabled(value: String?): Boolean = Settings.Secure.putString(context.contentResolver, ENABLED, value)
        })
}
