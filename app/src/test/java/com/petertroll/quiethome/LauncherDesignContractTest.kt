package com.petertroll.quiethome

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LauncherDesignContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_launcher.xml").exists() }
    private val layoutFile = File(root, "app/src/main/res/layout/activity_launcher.xml")
    private val sourceFile = File(root, "app/src/main/java/com/petertroll/quiethome/LauncherActivity.kt")
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(layoutFile)

    @Test fun `home keeps one primary app action and moves administration out`() {
        val homePanel = requireNotNull(findById(document.documentElement, "@+id/homePanel"))
        assertNotNull(findById(homePanel, "@+id/appsButton"))
        listOf(
            "appearanceButton",
            "permissionButton",
            "usageDashboardButton",
            "inboxButton",
            "defaultHomeButton",
        ).forEach { id -> assertEquals("$id must not remain on Home", null, findById(homePanel, "@+id/$id")) }

        val appsButton = requireNotNull(findById(homePanel, "@+id/appsButton"))
        assertEquals("@style/Widget.QuietHome.AllAppsButton", appsButton.getAttribute("style"))
        assertEquals("18sp", appsButton.getAttributeNS(androidNs, "textSize"))
        assertEquals("false", appsButton.getAttributeNS(androidNs, "textAllCaps"))
    }

    @Test fun `all apps header places settings at the top right`() {
        val drawer = requireNotNull(findById(document.documentElement, "@+id/drawerPanel"))
        val homeButton = requireNotNull(findById(drawer, "@+id/homeButton"))
        val settingsButton = requireNotNull(findById(drawer, "@+id/settingsButton"))
        assertEquals(homeButton.parentNode, settingsButton.parentNode)
        val siblings = generateSequence(homeButton.parentNode.firstChild) { it.nextSibling }
            .filterIsInstance<Element>()
            .toList()
        assertEquals(homeButton, siblings.first())
        assertEquals(settingsButton, siblings.last())
        assertEquals("@string/settings", settingsButton.getAttributeNS(androidNs, "text"))
        assertEquals("48dp", settingsButton.getAttributeNS(androidNs, "minHeight"))
    }

    @Test fun `settings menu consolidates every former home destination and respects safe flavor`() {
        val source = sourceFile.readText()
        assertTrue(source.contains("binding.settingsButton.setOnClickListener { showSettingsMenu() }"))
        val method = methodBody(source, "showSettingsMenu")
        assertTrue(method.contains("showAppearanceMenu()"))
        assertTrue(method.contains("PermissionCenterActivity::class.java"))
        assertTrue(method.contains("UsageDashboardActivity::class.java"))
        assertTrue(method.contains("NotificationInboxActivity::class.java"))
        assertTrue(method.contains("OnboardingActivity::class.java"))
        assertTrue(method.contains("special_access_features_available"))
    }

    @Test fun `launcher typography uses readable hierarchy without all caps`() {
        val heading = requireNotNull(findById(document.documentElement, "@+id/allAppsHeading"))
        assertEquals("16sp", heading.getAttributeNS(androidNs, "textSize"))
        assertEquals("false", heading.getAttributeNS(androidNs, "textAllCaps"))
        assertEquals("bold", heading.getAttributeNS(androidNs, "textStyle"))
        listOf("filterButton", "sortButton").forEach { id ->
            val control = requireNotNull(findById(document.documentElement, "@+id/$id"))
            assertEquals("$id must use readable 16sp labels", "16sp", control.getAttributeNS(androidNs, "textSize"))
            assertFalse("$id must not force uppercase", control.getAttributeNS(androidNs, "textAllCaps") == "true")
        }
    }

    @Test fun `Home omits the redundant Favorites heading`() {
        val homePanel = requireNotNull(findById(document.documentElement, "@+id/homePanel"))
        assertEquals(null, findById(homePanel, "@+id/favoritesHeading"))
        assertFalse(sourceFile.readText().contains("binding.favoritesHeading"))
    }

    @Test fun `All Apps button remains black with white text in both themes`() {
        val dayStyles = File(root, "app/src/main/res/values/styles.xml").readText()
        val nightStyles = File(root, "app/src/main/res/values-night/styles.xml").readText()
        listOf(dayStyles, nightStyles).forEach { styles ->
            assertTrue(styles.contains("name=\"Widget.QuietHome.AllAppsButton\""))
            assertTrue(styles.contains("<item name=\"backgroundTint\">#000000</item>"))
            assertTrue(styles.contains("<item name=\"android:textColor\">#FFFFFF</item>"))
        }
    }

    @Test fun `large text can expand controls and app rows without clipping`() {
        listOf("appsButton", "phoneButton", "torchButton", "cameraButton", "homeButton", "settingsButton", "search").forEach { id ->
            val control = requireNotNull(findById(document.documentElement, "@+id/$id"))
            assertEquals("$id must expand vertically", "wrap_content", control.getAttributeNS(androidNs, "layout_height"))
            assertTrue("$id must retain a touch target", control.getAttributeNS(androidNs, "minHeight").isNotBlank())
        }
        val source = sourceFile.readText()
        assertFalse(source.contains("dp(ROW_HEIGHT_DP)"))
    }

    @Test fun `visible sections expose semantic headings`() {
        val source = sourceFile.readText()
        assertFalse(source.contains("ViewCompat.setAccessibilityHeading(binding.favoritesHeading, true)"))
        assertTrue(source.contains("ViewCompat.setAccessibilityHeading(binding.allAppsHeading, true)"))
    }

    @Test fun `empty favorites state does not teach remembered long press behavior`() {
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("Long-press an app to add a favorite."))
        assertTrue(strings.contains("<string name=\"no_favorites\">No favorites yet.</string>"))
    }

    @Test fun `settings stays physically top right for the English interface`() {
        val header = requireNotNull(findById(document.documentElement, "@+id/drawerHeader"))
        assertEquals("ltr", header.getAttributeNS(androidNs, "layoutDirection"))
    }

    @Test fun `launcher feedback is persistent visible and clear of the dock`() {
        val source = sourceFile.readText()
        assertTrue(source.contains("import com.google.android.material.snackbar.Snackbar"))
        assertFalse(source.contains("Toast.makeText"))
        assertTrue(source.contains("setAction(R.string.open_accessibility_settings)"))
        assertTrue(source.contains("setAction(R.string.open_app_settings)"))
        assertTrue(source.contains("setActionTextColor(ContextCompat.getColor(this, R.color.quiet_on_primary))"))
        assertTrue(source.contains("anchorView = binding.bottomQuickActions"))
    }

    @Test fun `initial catalog load is announced instead of reporting an empty list`() {
        val source = sourceFile.readText()
        assertTrue(source.contains("private var catalogLoaded = false"))
        assertTrue(source.contains("catalogLoaded = true"))
        assertTrue(source.contains("R.string.loading_apps"))
    }

    @Test fun `hiding an app offers immediate undo`() {
        val source = sourceFile.readText()
        assertTrue(source.contains("3 -> hideApp(app)"))
        val method = methodBody(source, "hideApp")
        assertTrue(method.contains("update(preferences.hide(app.packageName))"))
        assertTrue(method.contains("setAction(R.string.undo)"))
        assertTrue(method.contains("update(previous)"))
    }

    private fun methodBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name")
        if (start < 0) return ""
        var depth = 0
        var seenOpening = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> { depth++; seenOpening = true }
                '}' -> if (seenOpening && --depth == 0) return source.substring(start, index + 1)
            }
        }
        return ""
    }

    private fun findById(element: Element, id: String): Element? {
        if (element.getAttributeNS(androidNs, "id") == id) return element
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            findById(child, id)?.let { return it }
        }
        return null
    }
}
