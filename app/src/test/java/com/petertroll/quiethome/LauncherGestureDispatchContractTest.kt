package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherGestureDispatchContractTest {
    private val source = File("src/main/java/com/petertroll/quiethome/LauncherActivity.kt").readText()

    @Test fun `claimed launcher gesture cancels child touch before navigation`() {
        val body = methodBody("dispatchTouchEvent")
        assertTrue(body.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(body.contains("super.dispatchTouchEvent(cancelEvent)"))
        assertTrue(body.contains("performPendingGesture"))
        assertTrue(
            body.indexOf("super.dispatchTouchEvent(cancelEvent)") <
                body.indexOf("performPendingGesture"),
        )
    }

    @Test fun `gesture arbitration distinguishes Home content from bottom controls`() {
        val body = methodBody("queueGestureAction")
        assertTrue(body.contains("startsInHomeContent"))
        assertTrue(body.contains("binding.homePanel"))
        assertTrue(body.contains("startsInProtectedRegion"))
        assertTrue(body.contains("binding.bottomQuickActions"))
        assertTrue(body.contains("LauncherGesturePolicy.canHandleSwipe"))
        assertTrue(body.contains("binding.homePanel.canScrollVertically(1)"))
        assertTrue(body.contains("binding.homePanel.canScrollVertically(-1)"))
    }

    @Test fun `Home content tracks a bounded gesture nudge and resets cleanly`() {
        val body = methodBody("applyGestureNudge")
        assertTrue(body.contains("GestureNudgePolicy.offset"))
        assertTrue(body.contains("binding.homePanel.translationX"))
        assertTrue(body.contains("binding.homePanel.translationY"))
        val reset = methodBody("resetGestureNudge")
        assertTrue(reset.contains("translationX(0f)"))
        assertTrue(reset.contains("translationY(0f)"))
    }

    @Test fun `slow swipe fallback is classified before touch delivery completes`() {
        val body = methodBody("dispatchTouchEvent")
        assertTrue(body.contains("LauncherGesturePolicy.classifySwipeByDistance"))
        assertTrue(body.contains("queueGestureAction"))
    }

    private fun methodBody(name: String): String {
        val start = source.indexOf("private fun $name").takeIf { it >= 0 }
            ?: source.indexOf("override fun $name").takeIf { it >= 0 }
            ?: return ""
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(brace + 1, index)
                }
            }
        }
        return ""
    }
}
