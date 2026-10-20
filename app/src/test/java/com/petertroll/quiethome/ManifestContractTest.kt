package com.petertroll.quiethome

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ManifestContractTest {
    private val manifestFile = File("src/main/AndroidManifest.xml")
    private val manifest = manifestFile.readText()
    private val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(manifestFile)

    @Test fun `exported launcher activity has MAIN HOME and DEFAULT in one filter`() {
        val activities = document.getElementsByTagName("activity").elements()
        val launcher = activities.single { it.androidAttribute("name") == ".LauncherActivity" }
        assertEquals("true", launcher.androidAttribute("exported"))

        val hasHomeFilter = launcher.getElementsByTagName("intent-filter").elements().any { filter ->
            val actions = filter.getElementsByTagName("action").elements().map { it.androidAttribute("name") }.toSet()
            val categories = filter.getElementsByTagName("category").elements().map { it.androidAttribute("name") }.toSet()
            "android.intent.action.MAIN" in actions &&
                "android.intent.category.HOME" in categories &&
                "android.intent.category.DEFAULT" in categories
        }
        assertTrue(hasHomeFilter)
    }

    @Test fun `application disables backup and transfer`() {
        val application = document.getElementsByTagName("application").elements().single()
        assertEquals("false", application.androidAttribute("allowBackup"))
        assertEquals("false", application.androidAttribute("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.androidAttribute("dataExtractionRules"))
    }

    @Test fun `connected context requests network but never location access`() {
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
    }

    @Test fun `planner screens are private and no OAuth callback is exported`() {
        val activities = document.getElementsByTagName("activity").elements()
        val settings = activities.single { it.androidAttribute("name") == ".SettingsActivity" }
        val assistant = activities.single { it.androidAttribute("name") == ".AssistantActivity" }
        assertEquals("false", settings.androidAttribute("exported"))
        assertEquals("false", assistant.androidAttribute("exported"))
        assertFalse(manifest.contains("oauth", ignoreCase = true))
    }

    @Test fun `health connect contract is read only and has provider visibility and rationale`() {
        listOf("READ_SLEEP", "READ_EXERCISE", "READ_HEART_RATE", "READ_STEPS", "READ_TOTAL_CALORIES_BURNED", "READ_HEALTH_DATA_IN_BACKGROUND").forEach {
            assertTrue(manifest.contains("android.permission.health.$it"))
        }
        assertFalse(manifest.contains("android.permission.health.WRITE_"))
        assertTrue(manifest.contains("com.google.android.apps.healthdata"))
        assertTrue(manifest.contains("android.intent.action.VIEW_PERMISSION_USAGE"))
        assertTrue(manifest.contains("android.intent.category.HEALTH_PERMISSIONS"))
        assertTrue(manifest.contains("android.permission.START_VIEW_PERMISSION_USAGE"))
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
