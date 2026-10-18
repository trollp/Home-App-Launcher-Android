package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HighContrastThemeContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/values/styles.xml").exists() }

    @Test fun `light theme uses pure black on white surfaces`() {
        val styles = File(root, "app/src/main/res/values/styles.xml").readText()
        val colors = File(root, "app/src/main/res/values/colors.xml").readText()

        assertTrue(styles.contains("<item name=\"android:colorBackground\">@color/quiet_background</item>"))
        assertTrue(styles.contains("<item name=\"colorOnBackground\">@color/quiet_on_background</item>"))
        assertTrue(styles.contains("<item name=\"colorOnSurface\">@color/quiet_on_surface</item>"))
        assertTrue(colors.contains("<color name=\"quiet_background\">#FFFFFF</color>"))
        assertTrue(colors.contains("<color name=\"quiet_on_background\">#000000</color>"))
    }

    @Test fun `dark theme uses pure white on black surfaces`() {
        val colors = File(root, "app/src/main/res/values-night/colors.xml")
        assertTrue(colors.exists())
        val text = colors.readText()
        assertTrue(text.contains("<color name=\"quiet_background\">#000000</color>"))
        assertTrue(text.contains("<color name=\"quiet_on_background\">#FFFFFF</color>"))
        assertTrue(text.contains("<color name=\"quiet_on_surface\">#FFFFFF</color>"))
    }
}
