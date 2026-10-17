package com.petertroll.quiethome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchStrategyTest {
    @Test fun `successful exact launch does not invoke fallback`() {
        var fallbackCalled = false
        val success = launchWithFallback(
            exact = {},
            fallback = { fallbackCalled = true; true }
        )
        assertTrue(success)
        assertFalse(fallbackCalled)
    }

    @Test fun `failed exact launch reports fallback result`() {
        assertTrue(launchWithFallback(exact = { error("missing") }, fallback = { true }))
        assertFalse(launchWithFallback(exact = { error("missing") }, fallback = { false }))
    }

    @Test fun `failed fallback is converted to launch failure`() {
        assertFalse(launchWithFallback(exact = { error("missing") }, fallback = { error("also missing") }))
    }
}
