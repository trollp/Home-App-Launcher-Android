package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class RuleCodecTest {
    @Test fun roundTripsRulesAndFocusState() {
        val state = RulesState(
            rules = mapOf("social.app" to AppRule(true, 8, 15, "Purpose?", 45, listOf(ScheduleRule(DaySet.WEEKENDS, 1320, 420)), true)),
            focusUntilEpochMillis = 123456L,
            graceUntilEpochMillis = mapOf("social.app" to 999L),
            accessibilityEnforcementEnabled = true
        )
        assertEquals(state, RuleCodec.decode(RuleCodec.encode(state)))
    }

    @Test fun corruptAndOutOfRangeValuesBecomeSafeDefaults() {
        assertEquals(RulesState(), RuleCodec.decode("not json"))
        val decoded = RuleCodec.decode("""{"rules":{"x":{"gate":true,"delay":999,"session":-2,"limit":-4,"prompt":"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz","schedules":[{"days":"NOPE","start":-1,"end":9000}]}}}""")
        val rule = decoded.rules.getValue("x")
        assertEquals(30, rule.delaySeconds)
        assertNull(rule.sessionMinutes)
        assertNull(rule.dailyLimitMinutes)
        assertTrue(rule.prompt!!.length <= 80)
        assertTrue(rule.schedules.isEmpty())
    }

    @Test fun malformedRestrictiveFieldsFailOpen() {
        val cases = listOf(
            """{"rules":{"x":{"gate":"yes","focus":1,"limit":"1","schedules":[{"days":"ALL","start":60}]}}}""",
            """{"rules":{"x":{"schedules":[{"days":"ALL","start":"60","end":120}]}}}""",
            """{"rules":{"x":{"schedules":[{"days":"NOPE","start":60,"end":120}]}}}""",
            """{"rules":{"x":{"schedules":[{"days":"ALL","start":60,"end":60}]}}}"""
        )
        cases.forEach { raw ->
            val rule = RuleCodec.decode(raw).rules.getValue("x")
            assertFalse(rule.gateEnabled)
            assertFalse(rule.blockDuringFocus)
            assertNull(rule.dailyLimitMinutes)
            assertTrue(rule.schedules.isEmpty())
        }
    }
}
