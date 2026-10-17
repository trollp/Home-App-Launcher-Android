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
}
