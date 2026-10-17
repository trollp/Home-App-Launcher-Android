package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesCodecTest {
    @Test fun `corrupt state recovers to defaults`() {
        assertEquals(LauncherPreferences(), PreferencesCodec.decode("{not-json"))
    }

    @Test fun `encoded state round trips and invalid fields are constrained`() {
        val preferences = LauncherPreferences(
            favoritePackages = setOf("a"),
            hiddenPackages = setOf("b"),
            aliases = mapOf("a" to "Alpha"),
            theme = AppTheme.DARK,
            textScale = 1.25f,
            appSort = AppSort.MOST_USED,
            launchCounts = mapOf("a" to 3L),
            lastLaunchEpochMillis = mapOf("a" to 1234L),
            habitName = "Go to the gym",
            habitDoneEpochDay = 20_000L
        )
        assertEquals(preferences, PreferencesCodec.decode(PreferencesCodec.encode(preferences)))
        assertEquals(AppTheme.SYSTEM, PreferencesCodec.decode("{\"theme\":\"INVALID\"}").theme)
        assertEquals(1.4f, PreferencesCodec.decode("{\"textScale\":99}").textScale)
    }
}
