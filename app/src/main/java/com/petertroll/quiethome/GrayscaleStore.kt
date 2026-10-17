package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.GrayscaleConfig
import com.petertroll.quiethome.domain.GrayscalePhase
import com.petertroll.quiethome.domain.GrayscaleTransaction
import com.petertroll.quiethome.domain.GrayscaleTransactionStorage
import org.json.JSONArray
import org.json.JSONObject

class GrayscaleStore(context: Context) : GrayscaleTransactionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val restorePrefs = context.applicationContext.getSharedPreferences(RESTORE_FILE, Context.MODE_PRIVATE)

    fun loadConfig(): GrayscaleConfig = synchronized(configLock) { runCatching {
        val root = JSONObject(prefs.getString(CONFIG, null) ?: return GrayscaleConfig())
        val array = root.optJSONArray("packages") ?: JSONArray()
        val packages = (0 until minOf(array.length(), 500)).mapNotNull { array.optString(it).takeIf { p -> p.isNotBlank() && p.length <= 255 } }.toSet()
        GrayscaleConfig(root.optBoolean("enabled", false), packages)
    }.getOrDefault(GrayscaleConfig()) }

    fun save(value: GrayscaleConfig): Boolean = synchronized(configLock) { runCatching {
        val raw = JSONObject().put("enabled", value.enabled).put("packages", JSONArray(value.packages.take(500))).toString()
        prefs.edit().putString(CONFIG, raw).commit()
    }.getOrDefault(false) }

    override fun load(): GrayscaleTransaction? = synchronized(restoreLock) {
        if (!restorePrefs.contains(PHASE) && !restorePrefs.contains(OLD_ENABLED)) return@synchronized null
        val phase = runCatching { GrayscalePhase.valueOf(restorePrefs.getString(PHASE, null).orEmpty()) }
            .getOrDefault(if (restorePrefs.getBoolean("applied", false)) GrayscalePhase.APPLIED else GrayscalePhase.APPLYING)
        GrayscaleTransaction(
            originalMode = decode(restorePrefs.getString(OLD_MODE, NULL)),
            originalEnabled = decode(restorePrefs.getString(OLD_ENABLED, NULL)),
            phase = phase,
            modeRestored = restorePrefs.getBoolean(MODE_RESTORED, false),
            enabledRestored = restorePrefs.getBoolean(ENABLED_RESTORED, false)
        )
    }

    override fun createIfAbsent(transaction: GrayscaleTransaction): Boolean = synchronized(restoreLock) {
        if (load() != null) return@synchronized false
        persist(transaction)
    }

    override fun save(transaction: GrayscaleTransaction): Boolean = synchronized(restoreLock) {
        if (load() == null) false else persist(transaction)
    }

    override fun clear(): Boolean = synchronized(restoreLock) { restorePrefs.edit().clear().commit() }

    private fun persist(value: GrayscaleTransaction): Boolean = restorePrefs.edit()
        .putString(OLD_MODE, value.originalMode ?: NULL)
        .putString(OLD_ENABLED, value.originalEnabled ?: NULL)
        .putString(PHASE, value.phase.name)
        .putBoolean(MODE_RESTORED, value.modeRestored)
        .putBoolean(ENABLED_RESTORED, value.enabledRestored)
        .remove("applied")
        .commit()

    private fun decode(value: String?): String? = value?.takeUnless { it == NULL }

    companion object {
        private const val FILE = "grayscale_local_config"
        private const val CONFIG = "config"
        private const val RESTORE_FILE = "grayscale_restore_state"
        private const val NULL = "<quiet-home-null>"
        private const val OLD_ENABLED = "old_enabled"
        private const val OLD_MODE = "old_mode"
        private const val PHASE = "phase"
        private const val MODE_RESTORED = "mode_restored"
        private const val ENABLED_RESTORED = "enabled_restored"
        private val configLock = Any()
        private val restoreLock = Any()
    }
}
