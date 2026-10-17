package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {
    private val apps = listOf(
        AppEntry("pkg.z", "Main", "  Zebra  "),
        AppEntry("pkg.e", "Main", "Éclair"),
        AppEntry("pkg.a", "Main", "alpha"),
        AppEntry("com.example.blank", "Main", "  ")
    )

    @Test fun `visible apps are deterministic alphabetical and hidden apps are excluded`() {
        val catalog = AppCatalog(apps, LauncherPreferences(hiddenPackages = setOf("pkg.z")))
        assertEquals(listOf("alpha", "com.example.blank", "Éclair"), catalog.visibleApps().map { it.displayLabel })
    }

    @Test fun `search ignores case accents and surrounding whitespace`() {
        val catalog = AppCatalog(apps, LauncherPreferences())
        assertEquals(listOf("Éclair"), catalog.search("  ECLAI ").map { it.displayLabel })
    }

    @Test fun `aliases affect labels ordering and search`() {
        val catalog = AppCatalog(apps, LauncherPreferences(aliases = mapOf("pkg.z" to "Notes")))
        assertEquals("Notes", catalog.search("note").single().displayLabel)
    }

    @Test fun `favorites are visible alphabetically and never include hidden apps`() {
        val prefs = LauncherPreferences(favoritePackages = setOf("pkg.z", "pkg.a"), hiddenPackages = setOf("pkg.z"))
        val favorites = AppCatalog(apps, prefs).favorites()
        assertEquals(listOf("pkg.a"), favorites.map { it.packageName })
        assertTrue(favorites.single().isFavorite)
        assertFalse(AppCatalog(apps, prefs).visibleApps().any { it.packageName == "pkg.z" })
    }

    @Test fun `missing labels have deterministic package fallback`() {
        assertEquals("com.example.blank", AppCatalog(apps, LauncherPreferences()).search("blank").single().displayLabel)
    }

    @Test fun `multiple launcher activities are deduplicated by package using stable component choice`() {
        val duplicateActivities = listOf(
            AppEntry("pkg.same", "z.LastActivity", "Last"),
            AppEntry("pkg.other", "Main", "Other"),
            AppEntry("pkg.same", "a.FirstActivity", "First")
        )
        val visible = AppCatalog(duplicateActivities, LauncherPreferences()).visibleApps()
        assertEquals(2, visible.size)
        assertEquals("a.FirstActivity", visible.single { it.packageName == "pkg.same" }.activityName)
        assertEquals("First", visible.single { it.packageName == "pkg.same" }.displayLabel)
    }

    @Test fun `most used sorting ranks higher launch counts first with alphabetical ties`() {
        val preferences = LauncherPreferences(
            launchCounts = mapOf("pkg.z" to 4L, "pkg.a" to 4L, "pkg.e" to 1L)
        )
        val labels = AppCatalog(apps, preferences)
            .browse("", AppSort.MOST_USED, AppFilter.ALL)
            .map { it.displayLabel }
        assertEquals(listOf("alpha", "Zebra", "Éclair", "com.example.blank"), labels)
    }

    @Test fun `recent sorting ranks latest launch first and never launched apps alphabetically`() {
        val preferences = LauncherPreferences(
            lastLaunchEpochMillis = mapOf("pkg.e" to 20L, "pkg.z" to 10L)
        )
        val labels = AppCatalog(apps, preferences)
            .browse("", AppSort.RECENT, AppFilter.ALL)
            .map { it.displayLabel }
        assertEquals(listOf("Éclair", "Zebra", "alpha", "com.example.blank"), labels)
    }

    @Test fun `favorite and other filters are applied after normalized search`() {
        val preferences = LauncherPreferences(favoritePackages = setOf("pkg.a", "pkg.z"))
        val catalog = AppCatalog(apps, preferences)
        assertEquals(
            listOf("alpha", "Zebra"),
            catalog.browse("a", AppSort.ALPHABETICAL, AppFilter.FAVORITES).map { it.displayLabel }
        )
        assertEquals(
            listOf("com.example.blank", "Éclair"),
            catalog.browse("", AppSort.ALPHABETICAL, AppFilter.OTHERS).map { it.displayLabel }
        )
    }
}
