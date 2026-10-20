package com.petertroll.quiethome.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A user-defined priority. Commands are deliberately limited to changing this local record. */
data class Routine(
    val id: String,
    val name: String,
    val normalAction: String,
    val smallerAction: String,
    val weeklyTarget: Int,
    val enabled: Boolean = true,
    val completedEpochDays: Set<Long> = emptySet(),
) {
    fun toggle(epochDay: Long): Routine {
        val values = if (epochDay in completedEpochDays) completedEpochDays - epochDay else completedEpochDays + epochDay
        return copy(completedEpochDays = values.sortedDescending().take(MAX_HISTORY_DAYS).toSet())
    }

    companion object { const val MAX_HISTORY_DAYS = 370 }
}

object RoutineCollection {
    const val MAX_ROUTINES = 15
    fun sanitize(values: List<Routine>): List<Routine> = values.asSequence()
        .mapIndexed { index, value ->
            val id = value.id.trim().take(40).ifEmpty { "routine-${index + 1}" }
            value.copy(
                id = id.replace(Regex("[^A-Za-z0-9_-]"), "-"),
                name = value.name.trim().take(60).ifEmpty { "Routine" },
                normalAction = value.normalAction.trim().take(100).ifEmpty { "Spend 15 minutes on ${value.name.trim().take(40)}" },
                smallerAction = value.smallerAction.trim().take(100).ifEmpty { "Take one small step on ${value.name.trim().take(40)}" },
                weeklyTarget = value.weeklyTarget.coerceIn(1, 7),
                completedEpochDays = value.completedEpochDays.filter { it >= 0 }.sortedDescending().take(Routine.MAX_HISTORY_DAYS).toSet(),
            )
        }.distinctBy { it.id }.take(MAX_ROUTINES).toList()
}

/** Compatibility view retained for old callers and migrated installs. */
data class RoutineState(
    val name: String = "Routine",
    val weeklyTarget: Int = 3,
    val completedEpochDays: Set<Long> = emptySet(),
) {
    fun toggle(epochDay: Long): RoutineState {
        val changed = if (epochDay in completedEpochDays) completedEpochDays - epochDay else completedEpochDays + epochDay
        return copy(completedEpochDays = changed.sortedDescending().take(MAX_HISTORY_DAYS).toSet())
    }
    companion object { const val MAX_HISTORY_DAYS = 370 }
}

data class RoutineInsight(val completedThisWeek: Int, val target: Int, val message: String, val momentum: String)

object RoutinePolicy {
    fun evaluate(state: RoutineState, todayEpochDay: Long): RoutineInsight {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
        val count = state.completedEpochDays.count { it in weekStart..todayEpochDay }
        val recent = state.completedEpochDays.count { it in (todayEpochDay - 27)..todayEpochDay }
        val message = when {
            count >= state.weeklyTarget -> "Weekly target complete. Recovery or another priority is available."
            count == 0 && today.dayOfWeek.value >= DayOfWeek.THURSDAY.value -> "A small return still counts. ${8 - today.dayOfWeek.value} day(s) remain this week."
            else -> "$count of ${state.weeklyTarget} this week. Keep the rhythm flexible."
        }
        val momentum = when {
            recent >= state.weeklyTarget * 3 -> "Steady rhythm"
            recent >= state.weeklyTarget * 2 -> "A pattern is forming"
            else -> "Recent activity"
        }
        return RoutineInsight(count, state.weeklyTarget, message, momentum)
    }
}
