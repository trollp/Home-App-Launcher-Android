package com.petertroll.quiethome.domain

import java.time.LocalDateTime

class BlockPolicy(private val safePackages: Set<String>) {
    fun evaluate(
        packageName: String,
        rule: AppRule?,
        context: EvaluationContext,
        @Suppress("UNUSED_PARAMETER") inputMethodPackages: Set<String> = emptySet()
    ): BlockDecision {
        // No explicit rule means unknown packages, including unknown system-critical packages, fail open.
        if (packageName in safePackages || rule == null) return BlockDecision.Allow()
        if (context.graceUntilEpochMillis?.let { it > context.nowEpochMillis } == true) return BlockDecision.Allow()
        if (rule.blockDuringFocus && context.focusUntilEpochMillis?.let { it > context.nowEpochMillis } == true) return BlockDecision.Block(BlockReason.FOCUS)
        if (rule.schedules.any { active(it, context.now) }) return BlockDecision.Block(BlockReason.SCHEDULE)
        val limit = rule.dailyLimitMinutes
        if (limit != null) {
            if (!context.usageAccessAvailable) return BlockDecision.Allow(UsageWarning.ACCESS_MISSING)
            if (context.usedMinutesToday >= limit) return BlockDecision.Block(BlockReason.DAILY_LIMIT)
        }
        return BlockDecision.Allow()
    }

    private fun active(rule: ScheduleRule, now: LocalDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        val start = rule.startMinute.coerceIn(0, 1439)
        val end = rule.endMinute.coerceIn(0, 1439)
        return when {
            start == end -> false // Equal endpoints are invalid/disabled, never an all-day block.
            start < end -> rule.days.contains(now.dayOfWeek) && minute in start until end
            minute >= start -> rule.days.contains(now.dayOfWeek)
            minute < end -> rule.days.contains(now.minusDays(1).dayOfWeek)
            else -> false
        }
    }
}
