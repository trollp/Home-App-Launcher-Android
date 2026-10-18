package com.petertroll.quiethome

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LauncherQuickActionsContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_launcher.xml").exists() }
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test fun `phone torch and camera controls are anchored together with phone at bottom left`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(root, "app/src/main/res/layout/activity_launcher.xml"))
        val quickActions = findById(document.documentElement, "@+id/bottomQuickActions")

        assertNotNull(quickActions)
        assertEquals("bottom", quickActions!!.getAttributeNS(androidNs, "layout_gravity"))
        val phoneButton = findById(document.documentElement, "@+id/phoneButton")
        assertEquals(quickActions, phoneButton?.parentNode)
        assertEquals(quickActions, findById(document.documentElement, "@+id/cameraButton")?.parentNode)
        assertEquals(quickActions, findById(document.documentElement, "@+id/torchButton")?.parentNode)
        val firstControl = generateSequence(quickActions.firstChild) { it.nextSibling }
            .filterIsInstance<Element>()
            .first()
        assertEquals(phoneButton, firstControl)
        listOf("phoneButton", "torchButton", "cameraButton").forEach { id ->
            val control = requireNotNull(findById(document.documentElement, "@+id/$id"))
            assertEquals("0dp", control.getAttributeNS(androidNs, "layout_width"))
            assertEquals("1", control.getAttributeNS(androidNs, "layout_weight"))
        }
    }

    @Test fun `phone control opens the default dialer`() {
        val source = File(
            root,
            "app/src/main/java/com/petertroll/quiethome/LauncherActivity.kt"
        ).readText()

        assertTrue(source.contains("binding.phoneButton.setOnClickListener { openPhone() }"))
        val openPhone = Regex(
            "private fun openPhone\\(\\) \\{([\\s\\S]*?)\\n    \\}",
        ).find(source)?.groupValues?.get(1).orEmpty()
        assertTrue(openPhone.contains("Intent(Intent.ACTION_DIAL)"))
        assertTrue(openPhone.contains("ActivityNotFoundException"))
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
