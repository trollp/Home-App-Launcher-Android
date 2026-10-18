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
    private const val TOP_EDGE_DP = 56f

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
        return startsInHomeContent && (
            action == LauncherGestureAction.OPEN_DRAWER ||
                action == LauncherGestureAction.OPEN_PREVIOUS_APP
            )
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
            startY <= TOP_EDGE_DP * safeDensity &&
            dy >= minimumDistance &&
            abs(dy) > abs(dx) &&
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
