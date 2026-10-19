package com.petertroll.quiethome.domain

data class CoachContext(
    val hour: Int,
    val completedThisWeek: Int,
    val weeklyTarget: Int,
    val rainStartsHour: Int?,
    val nextEventMinutes: Int? = null,
    val routineName: String = "Gym",
)

data class CoachAction(val id: String, val text: String, val reason: String)

object MicroCoachPolicy {
    fun choose(context: CoachContext): CoachAction = when {
        context.completedThisWeek >= context.weeklyTarget -> CoachAction(
            "recovery", "Recovery is the sensible action today.", "Your weekly target is complete."
        )
        context.nextEventMinutes != null && context.nextEventMinutes in 1..35 -> CoachAction(
            "pack-before-event", "Pack now; leave after your next meeting.", "Your next meeting starts soon."
        )
        context.rainStartsHour != null && context.rainStartsHour > context.hour -> CoachAction(
            "pack-before-rain", "Prepare for ${context.routineName} before the rain starts.", "Rain is expected later today."
        )
        context.hour in 14..19 -> CoachAction(
            "leave-window", "${context.routineName} is still realistic. Start within the next hour.", "There is still a useful evening window."
        )
        else -> CoachAction(
            "prepare", "Put what you need for ${context.routineName} where you can see it.", "One small preparation keeps the rhythm available."
        )
    }
}
