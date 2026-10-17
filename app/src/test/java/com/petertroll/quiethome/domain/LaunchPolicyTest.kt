package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class LaunchPolicyTest {
    private val policy = LaunchPolicy("com.petertroll.quiethome")

    @Test fun configuredAppUsesGateAndClampsDelay() {
        assertEquals(LaunchDecision.Gate(30, 10, "Why now?"), policy.decide("social.app", AppRule(gateEnabled = true, delaySeconds = 90, sessionMinutes = 10, prompt = "Why now?")))
    }

    @Test fun zeroDelayStillUsesIntentionalGate() {
        assertEquals(LaunchDecision.Gate(0, null, null), policy.decide("social.app", AppRule(gateEnabled = true, delaySeconds = 0)))
    }

    @Test fun dynamicallyDiscoveredSafetyPackageLaunchesDirectly() {
        assertEquals(LaunchDecision.Direct, LaunchPolicy(setOf("device.home")).decide("device.home", AppRule(gateEnabled = true, delaySeconds = 30)))
    }

    @Test fun disabledOrSafePackageLaunchesDirectly() {
        assertEquals(LaunchDecision.Direct, policy.decide("social.app", AppRule()))
        assertEquals(LaunchDecision.Direct, policy.decide("com.android.settings", AppRule(gateEnabled = true, delaySeconds = 10)))
        assertEquals(LaunchDecision.Direct, policy.decide("com.petertroll.quiethome", AppRule(gateEnabled = true, delaySeconds = 10)))
    }
}
