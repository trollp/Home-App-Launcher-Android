package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureNudgePolicyTest {
    @Test fun `horizontal drag produces only a small bounded horizontal nudge`() {
        assertEquals(
            GestureNudge(x = 24f, y = 0f),
            GestureNudgePolicy.offset(dx = 400f, dy = 20f, density = 2f, enabled = true),
        )
    }

    @Test fun `vertical drag produces only a small bounded vertical nudge`() {
        assertEquals(
            GestureNudge(x = 0f, y = -12f),
            GestureNudgePolicy.offset(dx = 10f, dy = -150f, density = 1f, enabled = true),
        )
    }

    @Test fun `disabled or tiny movement does not move content`() {
        assertEquals(GestureNudge.ZERO, GestureNudgePolicy.offset(300f, 0f, 1f, enabled = false))
        assertEquals(GestureNudge.ZERO, GestureNudgePolicy.offset(5f, 2f, 1f, enabled = true))
    }
}
