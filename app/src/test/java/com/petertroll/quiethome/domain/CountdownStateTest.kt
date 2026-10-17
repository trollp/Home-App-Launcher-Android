package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class CountdownStateTest {
    @Test fun remainingUsesElapsedDeadlineAcrossRecreation() {
        val state = CountdownState(deadlineElapsedMillis = 10_001)
        assertEquals(2, state.remainingSeconds(8_001))
        assertEquals(0, state.remainingSeconds(10_001))
    }

    @Test fun launchGuardIsOneShotAndBypassRequiresConfirmation() {
        var state = CountdownState(10_000)
        assertFalse(state.canLaunch(9_999))
        state = state.requestBypass()
        assertTrue(state.bypassConfirmationPending)
        state = state.confirmBypass()
        assertTrue(state.launched)
        assertFalse(state.canLaunch(20_000))
    }
}