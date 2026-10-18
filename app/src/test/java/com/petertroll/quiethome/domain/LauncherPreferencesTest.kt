package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPreferencesTest {
    @Test fun `preference changes are immutable and reversible`() {
        val original = LauncherPreferences()
        val changed = original.toggleFavorite("one").hide("two").rename("one", " Mail ")
        assertTrue(changed.isFavorite("one"))
        assertTrue(changed.isHidden("two"))
        assertEquals("Mail", changed.aliasFor("one"))
        assertFalse(changed.toggleFavorite("one").isFavorite("one"))
        assertFalse(changed.unhide("two").isHidden("two"))
        assertEquals(null, changed.rename("one", " ").aliasFor("one"))
        assertEquals(LauncherPreferences(), original)
    }

    @Test fun `home sections derive favorites and complete catalog`() {
        val apps = listOf(AppEntry("b", "Main", "Beta"), AppEntry("a", "Main", "Alpha"))
        val sections = HomeSections.from(AppCatalog(apps, LauncherPreferences(favoritePackages = setOf("b"))))
        assertEquals(listOf("Beta"), sections.favorites.map { it.displayLabel })
        assertEquals(listOf("Alpha", "Beta"), sections.allApps.map { it.displayLabel })
    }

    @Test fun `Home keeps every favorite as the list grows`() {
        val apps = (1..12).map { index -> AppEntry("pkg.$index", "Main", "App $index") }
        val favorites = apps.map { it.packageName }.toSet()
        val sections = HomeSections.from(AppCatalog(apps, LauncherPreferences(favoritePackages = favorites)))
        assertEquals(12, sections.favorites.size)
        assertEquals(apps.map { it.packageName }.toSet(), sections.favorites.map { it.packageName }.toSet())
    }

    @Test fun `recording launches updates count and latest time immutably`() {
        val original = LauncherPreferences()
        val updated = original.recordLaunch("pkg.app", 1234L).recordLaunch("pkg.app", 5678L)
        assertEquals(2L, updated.launchCounts["pkg.app"])
        assertEquals(5678L, updated.lastLaunchEpochMillis["pkg.app"])
        assertTrue(original.launchCounts.isEmpty())
    }

    @Test fun `daily habit toggles only the selected day and name is trimmed`() {
        val named = LauncherPreferences().renameHabit("  Go to the gym  ")
        val done = named.toggleHabit(20_000L)
        assertEquals("Go to the gym", named.habitName)
        assertTrue(done.isHabitDone(20_000L))
        assertFalse(done.isHabitDone(20_001L))
        assertFalse(done.toggleHabit(20_000L).isHabitDone(20_000L))
    }
}
