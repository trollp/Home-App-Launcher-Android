package com.petertroll.quiethome.domain

/** Critical packages are always fail-open. Device-specific IMEs are supplied by the Android edge. */
object PackageSafety {
    val baseline = setOf(
        "com.android.settings", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.dialer", "com.google.android.dialer",
        "com.android.deskclock", "com.google.android.deskclock"
    )
    fun isSafe(packageName: String, ownPackage: String, inputMethodPackages: Set<String> = emptySet()): Boolean =
        packageName == ownPackage || packageName in baseline || packageName in inputMethodPackages ||
            packageName.contains("emergency", ignoreCase = true)
}

class LaunchPolicy(private val safePackages: Set<String>) {
    constructor(ownPackage: String) : this(PackageSafety.baseline + ownPackage)
    fun decide(packageName: String, rule: AppRule?): LaunchDecision {
        if (packageName in safePackages || rule?.gateEnabled != true) return LaunchDecision.Direct
        return LaunchDecision.Gate(
            rule.delaySeconds.coerceIn(0, 30),
            rule.sessionMinutes?.takeIf { it > 0 }?.coerceAtMost(240),
            rule.prompt?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
        )
    }
}
