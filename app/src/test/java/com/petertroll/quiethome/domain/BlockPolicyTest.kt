package com.petertroll.quiethome.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class BlockPolicyTest {
    private val policy = BlockPolicy(setOf("com.petertroll.quiethome"))
    private val monday = LocalDateTime.of(2026, 10, 19, 12, 0)

    @Test fun dailyLimitUsesLocalDateBoundary() {
        val rule = AppRule(dailyLimitMinutes = 30)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday, 29, true)) is BlockDecision.Allow)
        assertEquals(BlockReason.DAILY_LIMIT, (policy.evaluate("social.app", rule, EvaluationContext(monday, 30, true)) as BlockDecision.Block).reason)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.plusDays(1), 0, true)) is BlockDecision.Allow)
    }

    @Test fun missingUsageAccessIsVisibleButFailOpen() {
        val result = policy.evaluate("social.app", AppRule(dailyLimitMinutes = 1), EvaluationContext(monday, 999, false))
        assertEquals(BlockDecision.Allow(UsageWarning.ACCESS_MISSING), result)
    }

    @Test fun weekdayAndWeekendSchedules() {
        val weekdays = ScheduleRule(DaySet.WEEKDAYS, 9 * 60, 17 * 60)
        val weekends = ScheduleRule(DaySet.WEEKENDS, 10 * 60, 12 * 60)
        val rule = AppRule(schedules = listOf(weekdays, weekends))
        assertEquals(BlockReason.SCHEDULE, (policy.evaluate("social.app", rule, EvaluationContext(monday.withHour(10), 0, true)) as BlockDecision.Block).reason)
        val saturday = LocalDateTime.of(2026, 10, 24, 11, 0)
        assertEquals(BlockReason.SCHEDULE, (policy.evaluate("social.app", rule, EvaluationContext(saturday, 0, true)) as BlockDecision.Block).reason)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(saturday.withHour(13), 0, true)) is BlockDecision.Allow)
    }

    @Test fun overnightWindowBelongsToStartingDay() {
        val rule = AppRule(schedules = listOf(ScheduleRule(DaySet.WEEKDAYS, 22 * 60, 7 * 60)))
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.withHour(21), 0, true)) is BlockDecision.Allow)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.withHour(23), 0, true)) is BlockDecision.Block)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.plusDays(1).withHour(6), 0, true)) is BlockDecision.Block)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(LocalDateTime.of(2026, 10, 26, 6, 0), 0, true)) is BlockDecision.Allow)
    }

    @Test fun focusSessionAndGraceOverride() {
        val now = 1_800_000_000_000L
        val context = EvaluationContext(monday, 0, true, nowEpochMillis = now, focusUntilEpochMillis = now + 20 * 60_000, graceUntilEpochMillis = null)
        assertEquals(BlockReason.FOCUS, (policy.evaluate("social.app", AppRule(blockDuringFocus = true), context) as BlockDecision.Block).reason)
        assertTrue(policy.evaluate("social.app", AppRule(blockDuringFocus = true), context.copy(graceUntilEpochMillis = now + 5 * 60_000)) is BlockDecision.Allow)
        assertTrue(policy.evaluate("social.app", AppRule(blockDuringFocus = true), context.copy(nowEpochMillis = now + 20 * 60_000)) is BlockDecision.Allow)
    }

    @Test fun safePackagesCanNeverBeBlocked() {
        val always = AppRule(dailyLimitMinutes = 1, blockDuringFocus = true, schedules = listOf(ScheduleRule(DaySet.ALL, 0, 0)))
        val context = EvaluationContext(monday, 999, true, nowEpochMillis = 1_000L, focusUntilEpochMillis = 2_000L)
        for (pkg in listOf("com.petertroll.quiethome", "com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.dialer", "com.android.deskclock", "com.example.inputmethod")) {
            assertTrue(pkg, BlockPolicy(setOf("com.petertroll.quiethome", "com.example.inputmethod", "com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.dialer", "com.android.deskclock")).evaluate(pkg, always, context) is BlockDecision.Allow)
        }
    }

    @Test fun boundariesAreStartInclusiveEndExclusiveAndEqualTimesDisabled() {
        val schedule = ScheduleRule(DaySet.ALL, 9 * 60, 17 * 60)
        val rule = AppRule(schedules = listOf(schedule))
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.withHour(9), 0, true)) is BlockDecision.Block)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday.withHour(17), 0, true)) is BlockDecision.Allow)
        assertTrue(policy.evaluate("social.app", AppRule(schedules = listOf(ScheduleRule(DaySet.ALL, 0, 0))), EvaluationContext(monday, 0, true)) is BlockDecision.Allow)
    }

    @Test fun overnightEndAndGraceDeadlineAreExclusive() {
        val rule = AppRule(blockDuringFocus = true, schedules = listOf(ScheduleRule(DaySet.ALL, 22 * 60, 7 * 60)))
        assertTrue(policy.evaluate("social.app", rule.copy(blockDuringFocus = false), EvaluationContext(monday.plusDays(1).withHour(0), 0, true)) is BlockDecision.Block)
        assertTrue(policy.evaluate("social.app", rule.copy(blockDuringFocus = false), EvaluationContext(monday.plusDays(1).withHour(7), 0, true)) is BlockDecision.Allow)
        val epoch = 123_456L
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(monday, 0, true, epoch, epoch + 1, epoch)) is BlockDecision.Block)
    }

    @Test fun focusAndGraceUseEpochNotDstSensitiveLocalTime() {
        val epoch = 1_000L
        val bizarreLocalTime = LocalDateTime.of(2099, 3, 29, 3, 30)
        val rule = AppRule(blockDuringFocus = true)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(bizarreLocalTime, 0, true, epoch, epoch + 1)) is BlockDecision.Block)
        assertTrue(policy.evaluate("social.app", rule, EvaluationContext(bizarreLocalTime.minusYears(150), 0, true, epoch, epoch + 1, epoch + 1)) is BlockDecision.Allow)
    }

    @Test fun dynamicallyDiscoveredSafetyCategoriesCannotBeBlocked() {
        val safe = setOf("our.app", "device.home", "device.clock", "device.installer", "device.ime", "device.emergency")
        val restrictive = AppRule(blockDuringFocus = true)
        safe.forEach { pkg -> assertTrue(BlockPolicy(safe).evaluate(pkg, restrictive, EvaluationContext(monday, 0, true, 10, 20)) is BlockDecision.Allow) }
    }
}
