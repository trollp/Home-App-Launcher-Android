package com.petertroll.quiethome

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class LauncherQuickActionsContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_launcher.xml").exists() }
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test fun `camera and torch controls are anchored together at screen bottom`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(root, "app/src/main/res/layout/activity_launcher.xml"))
        val quickActions = findById(document.documentElement, "@+id/bottomQuickActions")

        assertNotNull(quickActions)
        assertEquals("bottom", quickActions!!.getAttributeNS(androidNs, "layout_gravity"))
        assertEquals(quickActions, findById(document.documentElement, "@+id/cameraButton")?.parentNode)
        assertEquals(quickActions, findById(document.documentElement, "@+id/torchButton")?.parentNode)
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
