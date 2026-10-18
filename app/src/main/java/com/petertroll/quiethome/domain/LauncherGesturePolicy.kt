package com.petertroll.quiethome.domain

import kotlin.math.abs

enum class LauncherGestureAction {
    NONE,
    LOCK_SCREEN,
    OPEN_NOTIFICATIONS,
    OPEN_BROWSER,
    OPEN_DRAWER,
    OPEN_PREVIOUS_APP,
}

/** Pure gesture classification so launcher navigation remains predictable and JVM-testable. */
object LauncherGesturePolicy {
    private const val MIN_DISTANCE_DP = 80f

    fun canLockOnDoubleTap(
        isInteractiveTarget: Boolean,
        accessibilityCapabilityAvailable: Boolean,
        sdkInt: Int,
    ): Boolean = !isInteractiveTarget && accessibilityCapabilityAvailable && sdkInt >= 28

    fun canHandleSwipe(
        action: LauncherGestureAction,
        isInteractiveTarget: Boolean,
        startsInHomeContent: Boolean,
        startsInProtectedRegion: Boolean = false,
    ): Boolean {
        if (startsInProtectedRegion) return false
        if (action == LauncherGestureAction.NONE || action == LauncherGestureAction.LOCK_SCREEN) return false
        if (!isInteractiveTarget) return true
        return startsInHomeContent
    }

    fun classifySwipeByDistance(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        density: Float,
    ): LauncherGestureAction {
        val minimumDistance = MIN_DISTANCE_DP * density.coerceAtLeast(0.1f)
        val dx = endX - startX
        val dy = endY - startY
        if (maxOf(abs(dx), abs(dy)) < minimumDistance) return LauncherGestureAction.NONE
        return if (abs(dx) > abs(dy)) {
            if (dx < 0f) LauncherGestureAction.OPEN_DRAWER else LauncherGestureAction.OPEN_PREVIOUS_APP
        } else {
            if (dy < 0f) LauncherGestureAction.OPEN_BROWSER else LauncherGestureAction.OPEN_NOTIFICATIONS
        }
    }

    fun classifySwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        velocityX: Float,
        velocityY: Float,
        density: Float,
    ): LauncherGestureAction {
        val safeDensity = density.coerceAtLeast(0.1f)
        val dx = endX - startX
        val dy = endY - startY
        val minimumDistance = MIN_DISTANCE_DP * safeDensity

        if (
            dy >= minimumDistance &&
            abs(dy) > abs(dx) &&
            abs(velocityY) > abs(velocityX) &&
            velocityY > 0f
        ) {
            return LauncherGestureAction.OPEN_NOTIFICATIONS
        }

        if (
            dy <= -minimumDistance &&
            abs(dy) > abs(dx) &&
            abs(velocityY) > abs(velocityX) &&
            velocityY < 0f
        ) {
            return LauncherGestureAction.OPEN_BROWSER
        }

        if (abs(dx) < minimumDistance || abs(velocityX) <= abs(velocityY)) {
            return LauncherGestureAction.NONE
        }

        return if (dx < 0f) {
            LauncherGestureAction.OPEN_DRAWER
        } else {
            LauncherGestureAction.OPEN_PREVIOUS_APP
        }
    }
}
