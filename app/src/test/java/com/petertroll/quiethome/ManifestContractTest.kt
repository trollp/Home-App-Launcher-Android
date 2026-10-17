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

    @Test fun `launcher requests no network access`() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
