package com.petertroll.quiethome.domain

enum class GrayscalePhase { APPLYING, APPLIED, RESTORING }
enum class GrayscaleSetting { MODE, ENABLED }

/** Durable undo record. A record's presence always means restoration is still owed. */
data class GrayscaleTransaction(
    val originalMode: String?,
    val originalEnabled: String?,
    val phase: GrayscalePhase = GrayscalePhase.APPLYING,
    val modeRestored: Boolean = false,
    val enabledRestored: Boolean = false
)

interface GrayscaleTransactionStorage {
    fun load(): GrayscaleTransaction?
    fun createIfAbsent(transaction: GrayscaleTransaction): Boolean
    fun save(transaction: GrayscaleTransaction): Boolean
    fun clear(): Boolean
}

interface GrayscaleSettingsGateway {
    fun readMode(): String?
    fun readEnabled(): String?
    fun writeMode(value: String?): Boolean
    fun writeEnabled(value: String?): Boolean
}

/** Pure transaction coordinator; Android storage/settings are deliberately thin adapters. */
class GrayscaleTransactionCoordinator(
    private val storage: GrayscaleTransactionStorage,
    private val settings: GrayscaleSettingsGateway
) {
    fun isApplied(): Boolean = storage.load()?.phase == GrayscalePhase.APPLIED

    fun apply(permissionGranted: Boolean): Boolean {
        if (!permissionGranted) return false
        storage.load()?.let { existing ->
            return if (existing.phase == GrayscalePhase.APPLIED) true else {
                restore(permissionGranted)
                false
            }
        }
        val transaction = GrayscaleTransaction(settings.readMode(), settings.readEnabled())
        if (!storage.createIfAbsent(transaction)) return false

        val modeWritten = safeWrite { settings.writeMode("0") }
        if (!modeWritten) {
            restore(permissionGranted)
            return false
        }
        val enabledWritten = safeWrite { settings.writeEnabled("1") }
        if (!enabledWritten) {
            restore(permissionGranted)
            return false
        }
        if (!storage.save(transaction.copy(phase = GrayscalePhase.APPLIED))) {
            restore(permissionGranted)
            return false
        }
        return true
    }

    fun restore(permissionGranted: Boolean): Boolean {
        var transaction = storage.load() ?: return true
        val restoring = transaction.copy(phase = GrayscalePhase.RESTORING)
        storage.save(restoring)
        transaction = restoring
        if (!permissionGranted) return false

        // Never short-circuit: each key has an independent best-effort restoration and durable bit.
        if (!transaction.modeRestored && safeWrite { settings.writeMode(transaction.originalMode) }) {
            val updated = transaction.copy(modeRestored = true)
            if (storage.save(updated)) transaction = updated
        }
        if (!transaction.enabledRestored && safeWrite { settings.writeEnabled(transaction.originalEnabled) }) {
            val updated = transaction.copy(enabledRestored = true)
            if (storage.save(updated)) transaction = updated
        }
        return transaction.modeRestored && transaction.enabledRestored && storage.clear()
    }

    private inline fun safeWrite(write: () -> Boolean): Boolean = try { write() } catch (_: Exception) { false }
}
