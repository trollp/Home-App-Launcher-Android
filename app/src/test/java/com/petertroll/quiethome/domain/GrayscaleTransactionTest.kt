package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class GrayscaleTransactionTest {
    private class Store : GrayscaleTransactionStorage {
        var value: GrayscaleTransaction? = null
        var rejectSave = false
        override fun load() = value
        override fun createIfAbsent(transaction: GrayscaleTransaction): Boolean {
            if (value != null) return false
            value = transaction
            return true
        }
        override fun save(transaction: GrayscaleTransaction): Boolean {
            if (rejectSave) return false
            value = transaction
            return true
        }
        override fun clear(): Boolean { value = null; return true }
    }

    private class Settings(var mode: String? = "mode-before", var enabled: String? = "enabled-before") : GrayscaleSettingsGateway {
        val writes = mutableListOf<GrayscaleSetting>()
        val fail = mutableSetOf<GrayscaleSetting>()
        var onWrite: ((GrayscaleSetting) -> Unit)? = null
        override fun readMode() = mode
        override fun readEnabled() = enabled
        override fun writeMode(value: String?): Boolean {
            writes += GrayscaleSetting.MODE; onWrite?.invoke(GrayscaleSetting.MODE)
            return if (GrayscaleSetting.MODE in fail) false else true.also { mode = value }
        }
        override fun writeEnabled(value: String?): Boolean {
            writes += GrayscaleSetting.ENABLED; onWrite?.invoke(GrayscaleSetting.ENABLED)
            return if (GrayscaleSetting.ENABLED in fail) false else true.also { enabled = value }
        }
    }

    @Test fun snapshotIsDurableBeforeEitherApplyWrite() {
        val store = Store(); val settings = Settings()
        settings.onWrite = { assertNotNull(store.value) }
        assertTrue(GrayscaleTransactionCoordinator(store, settings).apply(true))
        assertEquals(listOf(GrayscaleSetting.MODE, GrayscaleSetting.ENABLED), settings.writes)
        assertEquals(GrayscalePhase.APPLIED, store.value?.phase)
    }

    @Test fun crashBetweenWritesLeavesOriginalSnapshotForRecovery() {
        val store = Store(); val settings = Settings()
        settings.onWrite = { if (it == GrayscaleSetting.ENABLED) throw SimulatedCrash() }
        assertThrows(SimulatedCrash::class.java) { GrayscaleTransactionCoordinator(store, settings).apply(true) }
        assertEquals("mode-before", store.value?.originalMode)
        assertEquals("enabled-before", store.value?.originalEnabled)
        settings.onWrite = null
        assertTrue(GrayscaleTransactionCoordinator(store, settings).restore(true))
        assertNull(store.value)
    }

    @Test fun repeatedApplyNeverOverwritesPendingOriginalWithModifiedValues() {
        val store = Store().apply { value = GrayscaleTransaction("original-mode", "original-enabled", GrayscalePhase.APPLIED) }
        val settings = Settings("0", "1")
        assertTrue(GrayscaleTransactionCoordinator(store, settings).apply(true))
        assertEquals("original-mode", store.value?.originalMode)
        assertEquals("original-enabled", store.value?.originalEnabled)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun partialApplyImmediatelyAttemptsBothRestores() {
        val store = Store(); val settings = Settings().apply { fail += GrayscaleSetting.ENABLED }
        assertFalse(GrayscaleTransactionCoordinator(store, settings).apply(true))
        assertEquals(
            listOf(GrayscaleSetting.MODE, GrayscaleSetting.ENABLED, GrayscaleSetting.MODE, GrayscaleSetting.ENABLED),
            settings.writes
        )
        assertNotNull(store.value) // enabled restore also failed, so recovery remains pending
        assertTrue(store.value!!.modeRestored)
        assertFalse(store.value!!.enabledRestored)
    }

    @Test fun restoreAttemptsKeysIndependentlyAndPersistsPartialCompletion() {
        val store = Store().apply { value = GrayscaleTransaction("m", "e", GrayscalePhase.APPLIED) }
        val settings = Settings().apply { fail += GrayscaleSetting.MODE }
        assertFalse(GrayscaleTransactionCoordinator(store, settings).restore(true))
        assertEquals(listOf(GrayscaleSetting.MODE, GrayscaleSetting.ENABLED), settings.writes)
        assertFalse(store.value!!.modeRestored)
        assertTrue(store.value!!.enabledRestored)
        settings.fail.clear(); settings.writes.clear()
        assertTrue(GrayscaleTransactionCoordinator(store, settings).restore(true))
        assertEquals(listOf(GrayscaleSetting.MODE), settings.writes)
        assertNull(store.value)
    }

    @Test fun permissionLossRetainsPendingAndRegainRestores() {
        val store = Store().apply { value = GrayscaleTransaction("m", "e", GrayscalePhase.APPLIED) }
        val settings = Settings()
        val coordinator = GrayscaleTransactionCoordinator(store, settings)
        assertFalse(coordinator.restore(false))
        assertNotNull(store.value)
        assertEquals(GrayscalePhase.RESTORING, store.value?.phase)
        assertTrue(settings.writes.isEmpty())
        assertTrue(coordinator.restore(true))
        assertNull(store.value)
    }

    @Test fun transactionClearsOnlyAfterBothRestoreWritesSucceed() {
        val store = Store().apply { value = GrayscaleTransaction(null, null, GrayscalePhase.APPLIED) }
        val settings = Settings().apply { fail += GrayscaleSetting.ENABLED }
        assertFalse(GrayscaleTransactionCoordinator(store, settings).restore(true))
        assertNotNull(store.value)
        settings.fail.clear()
        assertTrue(GrayscaleTransactionCoordinator(store, settings).restore(true))
        assertNull(store.value)
    }

    private class SimulatedCrash : Error()
}
