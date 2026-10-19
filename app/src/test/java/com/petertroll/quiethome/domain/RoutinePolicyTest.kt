package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class RoutinePolicyTest {
    @Test fun `weekly rhythm keeps progress without streak debt`() {
        val today = 20_000L
        val state = RoutineState("Gym", 3, setOf(today - 4, today - 2))
        val insight = RoutinePolicy.evaluate(state, today)
        assertEquals(2, insight.completedThisWeek)
        assertEquals(3, insight.target)
        assertFalse(insight.message.contains("lost", true))
        assertFalse(insight.message.contains("failed", true))
        assertFalse(insight.message.contains("streak", true))
    }

    @Test fun `completion toggles one date and history stays bounded`() {
        val days = (1L..500L).toSet()
        val toggled = RoutineState("Gym", 3, days).toggle(501L)
        assertTrue(501L in toggled.completedEpochDays)
        assertTrue(toggled.completedEpochDays.size <= 370)
        assertFalse(500L in toggled.toggle(500L).completedEpochDays)
    }

    @Test fun `completed target recommends recovery`() {
        val today = 20_000L
        val state = RoutineState("Gym", 3, setOf(today, today - 1, today - 2))
        assertTrue(RoutinePolicy.evaluate(state, today).message.contains("Recovery", true))
    }
}
