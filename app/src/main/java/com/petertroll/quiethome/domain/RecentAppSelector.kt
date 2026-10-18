package com.petertroll.quiethome.domain

data class RecentAppEvent(
    val packageName: String,
    val timestampMillis: Long,
    val launchable: Boolean,
)

object RecentAppSelector {
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    fun select(events: List<RecentAppEvent>, launcherPackage: String): String? =
        events.asSequence()
            .filter { it.launchable }
            .filter { it.packageName != launcherPackage && it.packageName != SYSTEM_UI_PACKAGE }
            .maxByOrNull { it.timestampMillis }
            ?.packageName
}
