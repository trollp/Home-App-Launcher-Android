package com.petertroll.quiethome.domain

import kotlin.math.abs
import kotlin.math.sign

/** Small, axis-locked visual response while a launcher gesture is in progress. */
data class GestureNudge(val x: Float, val y: Float) {
    companion object {
        val ZERO = GestureNudge(0f, 0f)
    }
}

object GestureNudgePolicy {
    private const val MAX_OFFSET_DP = 12f
    private const val START_DISTANCE_DP = 8f
    private const val DRAG_FRACTION = 0.08f

    fun offset(dx: Float, dy: Float, density: Float, enabled: Boolean): GestureNudge {
        if (!enabled) return GestureNudge.ZERO
        val safeDensity = density.coerceAtLeast(0.1f)
        val startDistance = START_DISTANCE_DP * safeDensity
        val maximum = MAX_OFFSET_DP * safeDensity
        if (maxOf(abs(dx), abs(dy)) < startDistance) return GestureNudge.ZERO

        return if (abs(dx) >= abs(dy)) {
            GestureNudge((abs(dx) * DRAG_FRACTION).coerceAtMost(maximum) * dx.sign, 0f)
        } else {
            GestureNudge(0f, (abs(dy) * DRAG_FRACTION).coerceAtMost(maximum) * dy.sign)
        }
    }
}
