package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class GrayscalePolicyTest {
    private val policy = GrayscalePolicy("com.petertroll.quiethome", setOf("android", "com.android.systemui"))

    @Test fun selectedConfiguredAppEnablesOnlyWithGrantAndOptIn() {
        val config = GrayscaleConfig(true, setOf("social.app"))
        assertEquals(GrayscaleDecision.ENABLE, policy.decide("social.app", config, true, false))
        assertEquals(GrayscaleDecision.NO_CHANGE, policy.decide("social.app", config, false, false))
        assertEquals(GrayscaleDecision.NO_CHANGE, policy.decide("social.app", GrayscaleConfig(), true, false))
    }

    @Test fun safePackagesCanNeverEnableGrayscale() {
        val config = GrayscaleConfig(true, setOf("android", "com.android.systemui", "com.petertroll.quiethome"))
        config.packages.forEach { assertEquals(GrayscaleDecision.RESTORE, policy.decide(it, config, true, true)) }
    }

    @Test fun unselectedPackageRequiresRestoreWhenApplied() {
        assertEquals(GrayscaleDecision.RESTORE, policy.decide("other", GrayscaleConfig(true, setOf("selected")), true, true))
        assertEquals(GrayscaleDecision.NO_CHANGE, policy.decide("other", GrayscaleConfig(true, setOf("selected")), true, false))
    }

    @Test fun permissionLossToggleOffAndInterruptionMandateRestore() {
        val config = GrayscaleConfig(true, setOf("selected"))
        assertEquals(GrayscaleDecision.RESTORE, policy.decide("selected", config, false, true))
        assertEquals(GrayscaleDecision.RESTORE, policy.decide("selected", config.copy(enabled = false), true, true))
        assertEquals(GrayscaleDecision.RESTORE, policy.onControllerStopping(true))
        assertEquals(GrayscaleDecision.NO_CHANGE, policy.onControllerStopping(false))
    }
}
