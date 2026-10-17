package com.petertroll.quiethome.domain

data class GrayscaleConfig(val enabled: Boolean = false, val packages: Set<String> = emptySet())
enum class GrayscaleDecision { ENABLE, RESTORE, NO_CHANGE }

class GrayscalePolicy(private val ownPackage: String, private val safePackages: Set<String>) {
    fun decide(foregroundPackage: String?, config: GrayscaleConfig, permissionGranted: Boolean, currentlyApplied: Boolean): GrayscaleDecision {
        val shouldApply = permissionGranted && config.enabled && foregroundPackage != null &&
            foregroundPackage != ownPackage && foregroundPackage !in safePackages && foregroundPackage in config.packages
        return when {
            shouldApply && !currentlyApplied -> GrayscaleDecision.ENABLE
            !shouldApply && currentlyApplied -> GrayscaleDecision.RESTORE
            else -> GrayscaleDecision.NO_CHANGE
        }
    }
    fun onControllerStopping(currentlyApplied: Boolean) = if (currentlyApplied) GrayscaleDecision.RESTORE else GrayscaleDecision.NO_CHANGE
}
