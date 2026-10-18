package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherGesturePolicyTest {
    @Test fun `downward swipe beginning at the top opens notification shade`() {
        assertEquals(
            LauncherGestureAction.OPEN_NOTIFICATIONS,
            LauncherGesturePolicy.classifySwipe(
                startX = 180f,
                startY = 12f,
                endX = 185f,
                endY = 230f,
                velocityX = 20f,
                velocityY = 1400f,
                density = 1f,
            ),
        )
    }

    @Test fun `downward swipe across Home opens notification shade`() {
        assertEquals(
            LauncherGestureAction.OPEN_NOTIFICATIONS,
            LauncherGesturePolicy.classifySwipe(180f, 180f, 185f, 410f, 20f, 1400f, 1f),
        )
    }

    @Test fun `left swipe opens app drawer`() {
        assertEquals(
            LauncherGestureAction.OPEN_DRAWER,
            LauncherGesturePolicy.classifySwipe(320f, 300f, 100f, 305f, -1400f, 10f, 1f),
        )
    }

    @Test fun `right swipe switches to previous app`() {
        assertEquals(
            LauncherGestureAction.OPEN_PREVIOUS_APP,
            LauncherGesturePolicy.classifySwipe(40f, 300f, 270f, 305f, 1400f, 10f, 1f),
        )
    }

    @Test fun `upward swipe opens browser`() {
        assertEquals(
            LauncherGestureAction.OPEN_BROWSER,
            LauncherGesturePolicy.classifySwipe(180f, 600f, 185f, 350f, 10f, -1400f, 1f),
        )
    }

    @Test fun `downward displacement never opens browser after direction reversal`() {
        assertEquals(
            LauncherGestureAction.NONE,
            LauncherGesturePolicy.classifySwipe(180f, 300f, 185f, 500f, 10f, -1400f, 1f),
        )
    }

    @Test fun `horizontal velocity dominance preserves horizontal gesture`() {
        assertEquals(
            LauncherGestureAction.OPEN_PREVIOUS_APP,
            LauncherGesturePolicy.classifySwipe(40f, 600f, 140f, 400f, 1400f, -500f, 1f),
        )
    }

    @Test fun `horizontal velocity dominance survives downward diagonal displacement`() {
        assertEquals(
            LauncherGestureAction.OPEN_PREVIOUS_APP,
            LauncherGesturePolicy.classifySwipe(40f, 300f, 140f, 500f, 1400f, 500f, 1f),
        )
    }

    @Test fun `short or diagonal movement is not a launcher gesture`() {
        assertEquals(
            LauncherGestureAction.NONE,
            LauncherGesturePolicy.classifySwipe(40f, 300f, 85f, 345f, 600f, 600f, 1f),
        )
    }

    @Test fun `slow deliberate swipes classify from distance without velocity`() {
        assertEquals(
            LauncherGestureAction.OPEN_DRAWER,
            LauncherGesturePolicy.classifySwipeByDistance(300f, 300f, 100f, 310f, 1f),
        )
        assertEquals(
            LauncherGestureAction.OPEN_PREVIOUS_APP,
            LauncherGesturePolicy.classifySwipeByDistance(100f, 300f, 300f, 290f, 1f),
        )
        assertEquals(
            LauncherGestureAction.OPEN_BROWSER,
            LauncherGesturePolicy.classifySwipeByDistance(200f, 300f, 210f, 100f, 1f),
        )
        assertEquals(
            LauncherGestureAction.OPEN_NOTIFICATIONS,
            LauncherGesturePolicy.classifySwipeByDistance(200f, 100f, 190f, 300f, 1f),
        )
        assertEquals(
            LauncherGestureAction.NONE,
            LauncherGesturePolicy.classifySwipeByDistance(100f, 100f, 140f, 145f, 1f),
        )
    }

    @Test fun `double tap locks only on non-interactive background with supported capability`() {
        assertEquals(true, LauncherGesturePolicy.canLockOnDoubleTap(false, true, 28))
        assertEquals(false, LauncherGesturePolicy.canLockOnDoubleTap(true, true, 28))
        assertEquals(false, LauncherGesturePolicy.canLockOnDoubleTap(false, false, 28))
        assertEquals(false, LauncherGesturePolicy.canLockOnDoubleTap(false, true, 27))
    }

    @Test fun `horizontal swipes can start over interactive favorites on Home`() {
        assertEquals(
            true,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_DRAWER,
                isInteractiveTarget = true,
                startsInHomeContent = true,
            ),
        )
        assertEquals(
            true,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_PREVIOUS_APP,
                isInteractiveTarget = true,
                startsInHomeContent = true,
            ),
        )
    }

    @Test fun `interactive controls outside Home content retain their touch stream`() {
        assertEquals(
            false,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_DRAWER,
                isInteractiveTarget = true,
                startsInHomeContent = false,
            ),
        )
    }

    @Test fun `bottom quick actions remain protected even though Home overlaps them`() {
        assertEquals(
            false,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_DRAWER,
                isInteractiveTarget = true,
                startsInHomeContent = true,
                startsInProtectedRegion = true,
            ),
        )
        assertEquals(
            false,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_PREVIOUS_APP,
                isInteractiveTarget = false,
                startsInHomeContent = true,
                startsInProtectedRegion = true,
            ),
        )
    }

    @Test fun `vertical swipes can start over interactive favorites on Home`() {
        assertEquals(
            true,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_BROWSER,
                isInteractiveTarget = true,
                startsInHomeContent = true,
            ),
        )
        assertEquals(
            true,
            LauncherGesturePolicy.canHandleSwipe(
                action = LauncherGestureAction.OPEN_NOTIFICATIONS,
                isInteractiveTarget = true,
                startsInHomeContent = true,
            ),
        )
    }
}
