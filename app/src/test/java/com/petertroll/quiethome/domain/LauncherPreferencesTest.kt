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
}
