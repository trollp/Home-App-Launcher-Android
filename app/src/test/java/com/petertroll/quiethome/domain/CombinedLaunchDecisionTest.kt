package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class CombinedLaunchDecisionTest {
    private val context = EvaluationContext(LocalDateTime.of(2026, 1, 5, 12, 0), 0, true)

    @Test fun blockAndGateAreDerivedFromTheSameRuleObject() {
        val rule = AppRule(gateEnabled = true, delaySeconds = 9, schedules = listOf(ScheduleRule(DaySet.ALL, 0, 1440)))
        val result = CombinedLaunchPolicy(emptySet()).evaluate("social", rule, context)
        assertEquals(BlockDecision.Block(BlockReason.SCHEDULE), result.block)
        assertEquals(LaunchDecision.Gate(9, null, null), result.launch)
    }

    @Test fun safePackageIsDirectAndFailOpenForBlocking() {
        val rule = AppRule(gateEnabled = true, blockDuringFocus = true)
        val result = CombinedLaunchPolicy(setOf("safe")).evaluate(
            "safe", rule, context.copy(focusUntilEpochMillis = Long.MAX_VALUE)
        )
        assertEquals(BlockDecision.Allow(), result.block)
        assertEquals(LaunchDecision.Direct, result.launch)
    }

    @Test fun immutableResultKeepsDecisionWhenSourceMapChanges() {
        val rules = mutableMapOf("app" to AppRule(gateEnabled = true, delaySeconds = 4))
        val result = CombinedLaunchPolicy(emptySet()).evaluate("app", rules["app"], context)
        rules["app"] = AppRule()
        assertEquals(LaunchDecision.Gate(4, null, null), result.launch)
    }
}