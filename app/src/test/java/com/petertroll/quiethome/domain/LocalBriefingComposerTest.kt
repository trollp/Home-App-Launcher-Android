package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class LocalBriefingComposerTest {
    @Test fun `briefing is compact and omits unavailable calendar`() {
        val lines = LocalBriefingComposer.compose(
            weather = "9°C · Clear. High 10°, low 4°.",
            nextEvent = null,
            routine = "Gym is 2 of 3 this week.",
            coach = "Pack your gym bag before lunch."
        )
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.length <= 100 })
        assertFalse(lines.joinToString().contains("calendar", true))
    }
}
