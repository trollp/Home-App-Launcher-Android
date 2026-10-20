package com.petertroll.quiethome

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class IntentBarContractTest {
    private fun source(path: String) = File("src/main/$path").readText()

    @Test fun `home intent UI is bounded single line go action and rotation state`() {
        val xml = source("res/layout/activity_launcher.xml")
        assertTrue(xml.contains("@+id/intentInput")); assertTrue(xml.contains("android:maxLength=\"240\""))
        assertTrue(xml.contains("android:maxLines=\"1\"")); assertTrue(xml.contains("android:imeOptions=\"actionGo\""))
        assertTrue(xml.contains("@+id/intentStatus")); assertTrue(xml.contains("android:layout_height=\"48dp\""))
        val activity = source("java/com/petertroll/quiethome/LauncherActivity.kt")
        assertTrue(activity.contains("STATE_INTENT")); assertTrue(activity.contains("intentRequestGate")); assertTrue(activity.contains("LaunchRouter.fromLauncher"))
        assertFalse(activity.contains("Ask AI")); assertFalse(activity.contains("Refine"))
    }

    @Test fun `intent logging cannot receive raw command`() {
        val activity = source("java/com/petertroll/quiethome/LauncherActivity.kt")
        val logger = activity.substring(activity.indexOf("private fun logIntent"), activity.indexOf("private fun setIntentStatus"))
        assertFalse(logger.contains("intentInput")); assertFalse(logger.contains("binding")); assertTrue(logger.contains("ActivityKind.INTENT_COMMAND"))
    }
}
