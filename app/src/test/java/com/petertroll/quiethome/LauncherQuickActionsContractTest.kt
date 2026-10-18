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
    private val appNs = "http://schemas.android.com/apk/res-auto"

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

    @Test fun `quick actions use matching icon-only controls with accessible names`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(root, "app/src/main/res/layout/activity_launcher.xml"))
        val expected = mapOf(
            "phoneButton" to Pair("@drawable/ic_phone_24", "@string/open_phone"),
            "torchButton" to Pair("@drawable/ic_flashlight_24", "@string/torch"),
            "cameraButton" to Pair("@drawable/ic_camera_24", "@string/open_camera"),
        )
        expected.forEach { (id, values) ->
            val control = requireNotNull(findById(document.documentElement, "@+id/$id"))
            assertTrue(control.tagName.endsWith("MaterialButton"))
            assertEquals("", control.getAttributeNS(androidNs, "text"))
            assertEquals(values.first, control.getAttributeNS(appNs, "icon"))
            assertEquals(values.second, control.getAttributeNS(androidNs, "contentDescription"))
            assertEquals("24dp", control.getAttributeNS(appNs, "iconSize"))
            assertEquals("@style/Widget.QuietHome.IconOnly", control.getAttribute("style"))
        }
        listOf("ic_phone_24.xml", "ic_flashlight_24.xml", "ic_camera_24.xml").forEach { name ->
            val vector = File(root, "app/src/main/res/drawable/$name")
            assertTrue("Missing matching vector asset: $name", vector.isFile)
            val content = vector.readText()
            assertTrue(content.contains("android:width=\"24dp\""))
            assertTrue(content.contains("android:height=\"24dp\""))
        }
        val torchOn = File(root, "app/src/main/res/drawable/ic_flashlight_on_24.xml").readText()
        assertTrue("Torch on icon must add visible light rays outside the torch body", torchOn.contains("M3,15h4v2H3z"))
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
