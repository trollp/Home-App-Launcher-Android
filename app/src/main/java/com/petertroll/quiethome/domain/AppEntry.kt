package com.petertroll.quiethome.domain

/** Framework-free identity returned by a launchable-application source. */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String
)

data class DisplayApp(
    val packageName: String,
    val activityName: String,
    val displayLabel: String,
    val isFavorite: Boolean
)
