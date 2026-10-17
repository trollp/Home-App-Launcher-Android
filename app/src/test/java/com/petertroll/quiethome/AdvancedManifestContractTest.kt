package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdvancedManifestContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "app/src/main/AndroidManifest.xml").exists() }
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

    @Test fun optionalEnforcementIsDeclaredSafely() {
        assertTrue(manifest.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifest.contains("@xml/accessibility_service_config"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }

    @Test fun gateAndBlockedScreensAreNotExported() {
        assertTrue(manifest.contains("android:name=\".MindfulGateActivity\"\n            android:exported=\"false\""))
        assertTrue(manifest.contains("android:name=\".BlockedActivity\"\n            android:exported=\"false\""))
    }
}
