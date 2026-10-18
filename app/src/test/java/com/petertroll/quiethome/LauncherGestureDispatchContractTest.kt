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

    @Test fun `fling classification distinguishes Home content from bottom controls`() {
        val body = methodBody("installSwipeNavigation")
        assertTrue(body.contains("startsInHomeContent"))
        assertTrue(body.contains("binding.homePanel"))
        assertTrue(body.contains("startsInProtectedRegion"))
        assertTrue(body.contains("binding.bottomQuickActions"))
        assertTrue(body.contains("LauncherGesturePolicy.canHandleSwipe"))
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
