package com.petertroll.quiethome.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

/** Persisted, opt-in controls for one launchable package. */
data class AppRule(
    val gateEnabled: Boolean = false,
    val delaySeconds: Int = 5,
    val sessionMinutes: Int? = null,
    val prompt: String? = null,
    val dailyLimitMinutes: Int? = null,
    val schedules: List<ScheduleRule> = emptyList(),
    val blockDuringFocus: Boolean = false
)

enum class DaySet { ALL, WEEKDAYS, WEEKENDS;
    fun contains(day: DayOfWeek): Boolean = when (this) {
        ALL -> true
        WEEKDAYS -> day.value <= 5
        WEEKENDS -> day.value >= 6
    }
}

data class ScheduleRule(val days: DaySet, val startMinute: Int, val endMinute: Int)

data class RulesState(
    val rules: Map<String, AppRule> = emptyMap(),
    val focusUntilEpochMillis: Long? = null,
    val graceUntilEpochMillis: Map<String, Long> = emptyMap(),
    val accessibilityEnforcementEnabled: Boolean = false
)

data class EvaluationContext(
    val now: LocalDateTime,
    val usedMinutesToday: Int,
    val usageAccessAvailable: Boolean,
    val nowEpochMillis: Long = 0L,
    val focusUntilEpochMillis: Long? = null,
    val graceUntilEpochMillis: Long? = null
)

/** Pure elapsed-realtime state. It survives recreation without trusting wall-clock changes. */
data class CountdownState(
    val deadlineElapsedMillis: Long,
    val launched: Boolean = false,
    val bypassConfirmationPending: Boolean = false
) {
    fun remainingSeconds(nowElapsedMillis: Long): Long =
        ((deadlineElapsedMillis - nowElapsedMillis + 999L) / 1_000L).coerceAtLeast(0L)
    fun canLaunch(nowElapsedMillis: Long): Boolean = !launched && remainingSeconds(nowElapsedMillis) == 0L
    fun requestBypass() = copy(bypassConfirmationPending = true)
    fun confirmBypass() = copy(launched = true, bypassConfirmationPending = false)
    fun markLaunched(nowElapsedMillis: Long) = if (canLaunch(nowElapsedMillis)) copy(launched = true) else this
}

data class PermissionState(val systemAccessibilityEnabled: Boolean, val storedEnforcementEnabled: Boolean) {
    val canToggleEnforcement get() = systemAccessibilityEnabled
    val effectiveEnforcementEnabled get() = systemAccessibilityEnabled && storedEnforcementEnabled
}

enum class HomeRequestAction { NONE, OPEN_HOME_SETTINGS }
object HomeRequestPolicy {
    fun next(roleAvailable: Boolean, roleHeld: Boolean, roleLaunchSucceeded: Boolean): HomeRequestAction =
        if (!roleAvailable || roleHeld || !roleLaunchSucceeded) HomeRequestAction.OPEN_HOME_SETTINGS else HomeRequestAction.NONE
}

enum class BlockReason { DAILY_LIMIT, SCHEDULE, FOCUS }
enum class UsageWarning { ACCESS_MISSING }
sealed class BlockDecision {
    data class Allow(val warning: UsageWarning? = null) : BlockDecision()
    data class Block(val reason: BlockReason) : BlockDecision()
}

sealed class LaunchDecision {
    data object Direct : LaunchDecision()
    data class Gate(val delaySeconds: Int, val sessionMinutes: Int?, val prompt: String?) : LaunchDecision()
}
