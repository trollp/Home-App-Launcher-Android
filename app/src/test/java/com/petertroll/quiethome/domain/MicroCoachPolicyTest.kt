package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class MicroCoachPolicyTest {
    @Test fun `coach gives one concrete non punitive action`() {
        val action = MicroCoachPolicy.choose(
            CoachContext(hour = 16, completedThisWeek = 1, weeklyTarget = 3, rainStartsHour = 18)
        )
        assertTrue(action.text.length in 10..120)
        assertTrue(action.text.contains("before", true) || action.text.contains("leave", true) || action.text.contains("pack", true))
        listOf("failed", "lost", "shame", "streak").forEach { assertFalse(action.text.contains(it, true)) }
    }

    @Test fun `target complete favors recovery`() {
        val action = MicroCoachPolicy.choose(CoachContext(18, 3, 3, null))
        assertTrue(action.text.contains("Recovery", true))
    }
}
