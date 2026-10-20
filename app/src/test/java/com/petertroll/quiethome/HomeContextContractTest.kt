package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HomeContextContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_launcher.xml").exists() }
    private val layout = File(root, "app/src/main/res/layout/activity_launcher.xml").readText()
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
    private val strings = File(root, "app/src/main/res/values/strings.xml").readText()

    @Test fun `home has compact date weather planner and priorities above favorites`() {
        val ids = listOf("dateWeatherRow", "date", "weatherText", "habitButton", "plannerHeadline", "plannerNextItems", "plannerUpdated", "plannerDigest", "favoriteList")
        ids.forEach { assertTrue("missing $it", layout.contains("@+id/$it")) }
        assertFalse("battery duplicates the system status bar", layout.contains("@+id/batteryText"))
        assertTrue("top padding should not create an empty band", layout.contains("android:paddingTop=\"0dp\""))
        val rowStart = layout.indexOf("@+id/dateWeatherRow")
        val rowEnd = layout.indexOf("</LinearLayout>", rowStart)
        val row = layout.substring(rowStart, rowEnd)
        assertTrue(row.indexOf("@+id/date") < row.indexOf("@+id/weatherText"))
        val weather = row.substring(row.indexOf("@+id/weatherText"))
        assertTrue("weather needs a full touch target", weather.contains("android:minHeight=\"48dp\""))
        assertTrue(layout.indexOf("@+id/weatherText") < layout.indexOf("@+id/favoriteList"))
        assertTrue(layout.indexOf("@+id/habitButton") < layout.indexOf("@+id/favoriteList"))
    }

    @Test fun `connected edition has Internet but no location permission and names SMHI`() {
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("ACCESS_COARSE_LOCATION"))
        assertTrue(strings.contains("SMHI"))
        assertTrue(strings.contains("Celsius"))
    }

    @Test fun `copy avoids punitive game mechanics`() {
        listOf("leaderboard", "confetti", "lost streak", "failure counter", "shame").forEach {
            assertFalse(strings.contains(it, true))
        }
    }
}
