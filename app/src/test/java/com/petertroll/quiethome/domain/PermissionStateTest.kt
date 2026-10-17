package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class PermissionStateTest {
    @Test fun internalEnforcementCannotRemainEnabledAfterServiceRevocation() {
        assertFalse(PermissionState(systemAccessibilityEnabled = false, storedEnforcementEnabled = true).effectiveEnforcementEnabled)
        assertFalse(PermissionState(false, true).canToggleEnforcement)
        assertTrue(PermissionState(true, true).effectiveEnforcementEnabled)
    }

    @Test fun failedHomeRoleRequestMapsToSettingsFallback() {
        assertEquals(HomeRequestAction.OPEN_HOME_SETTINGS, HomeRequestPolicy.next(roleAvailable = true, roleHeld = false, roleLaunchSucceeded = false))
        assertEquals(HomeRequestAction.NONE, HomeRequestPolicy.next(true, false, true))
    }
}