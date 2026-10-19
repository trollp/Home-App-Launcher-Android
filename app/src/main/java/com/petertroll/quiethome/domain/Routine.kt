package com.petertroll.quiethome.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class RoutineState(
    val name: String = "Gym",
    val weeklyTarget: Int = 3,
    val completedEpochDays: Set<Long> = emptySet(),
) {
    fun toggle(epochDay: Long): RoutineState {
        val changed = if (epochDay in completedEpochDays) completedEpochDays - epochDay else completedEpochDays + epochDay
        return copy(completedEpochDays = changed.sortedDescending().take(MAX_HISTORY_DAYS).toSet())
    }

    companion object { const val MAX_HISTORY_DAYS = 370 }
}

data class RoutineInsight(
    val completedThisWeek: Int,
    val target: Int,
    val message: String,
    val momentum: String,
)

object RoutinePolicy {
    fun evaluate(state: RoutineState, todayEpochDay: Long): RoutineInsight {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
        val count = state.completedEpochDays.count { it in weekStart..todayEpochDay }
        val recent = state.completedEpochDays.count { it in (todayEpochDay - 27)..todayEpochDay }
        val message = when {
            count >= state.weeklyTarget -> "Weekly target complete. Recovery is the sensible option."
            count == 0 && today.dayOfWeek.value >= DayOfWeek.THURSDAY.value ->
                "A small return still counts. ${8 - today.dayOfWeek.value} day(s) remain this week."
            else -> "$count of ${state.weeklyTarget} this week. Keep the rhythm flexible."
        }
        val momentum = when {
            recent >= state.weeklyTarget * 3 -> "Steady momentum"
            recent >= state.weeklyTarget * 2 -> "Building momentum"
            else -> "Finding rhythm"
        }
        return RoutineInsight(count, state.weeklyTarget, message, momentum)
    }
}
