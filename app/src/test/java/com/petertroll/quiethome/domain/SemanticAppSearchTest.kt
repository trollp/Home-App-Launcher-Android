package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class SemanticAppSearchTest {
    private val entries = listOf(
        AppEntry("com.google.android.apps.maps", "Map", "Maps"),
        AppEntry("com.spotify.music", "Music", "Spotify"),
        AppEntry("org.signal", "Signal", "Signal")
    )

    @Test fun `local intent synonyms find installed apps without AI`() {
        val catalog = AppCatalog(entries, LauncherPreferences())
        assertEquals("com.google.android.apps.maps", catalog.search("navigate").first().packageName)
        assertEquals("com.spotify.music", catalog.search("listen").first().packageName)
        assertEquals("org.signal", catalog.search("message").first().packageName)
    }

    @Test fun `exact label outranks semantic hints and hidden apps stay hidden`() {
        val prefs = LauncherPreferences(hiddenPackages = setOf("com.google.android.apps.maps"))
        assertTrue(AppCatalog(entries, prefs).search("navigate").none { it.packageName.contains("maps") })
        assertEquals("com.spotify.music", AppCatalog(entries, LauncherPreferences()).search("Spotify").first().packageName)
    }
}
