package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentAppSelectorTest {
    @Test fun `selects latest launchable app other than launcher and system ui`() {
        val events = listOf(
            RecentAppEvent("com.example.mail", 100L, launchable = true),
            RecentAppEvent("com.android.systemui", 400L, launchable = true),
            RecentAppEvent("com.petertroll.quiethome", 500L, launchable = true),
            RecentAppEvent("com.example.maps", 300L, launchable = true),
        )

        assertEquals(
            "com.example.maps",
            RecentAppSelector.select(events, "com.petertroll.quiethome"),
        )
    }

    @Test fun `ignores packages that cannot be launched`() {
        val events = listOf(
            RecentAppEvent("com.example.background", 500L, launchable = false),
            RecentAppEvent("com.example.camera", 200L, launchable = true),
        )

        assertEquals(
            "com.example.camera",
            RecentAppSelector.select(events, "com.petertroll.quiethome"),
        )
    }

    @Test fun `returns null when there is no previous app`() {
        assertNull(
            RecentAppSelector.select(
                listOf(RecentAppEvent("com.petertroll.quiethome", 100L, launchable = true)),
                "com.petertroll.quiethome",
            ),
        )
    }
}
