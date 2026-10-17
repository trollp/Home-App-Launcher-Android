package com.petertroll.quiethome.domain

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class AppSort { ALPHABETICAL, MOST_USED, RECENT }
enum class AppFilter { ALL, FAVORITES, OTHERS }

data class LauncherPreferences(
    val favoritePackages: Set<String> = emptySet(),
    val hiddenPackages: Set<String> = emptySet(),
    val aliases: Map<String, String> = emptyMap(),
    val theme: AppTheme = AppTheme.SYSTEM,
    val textScale: Float = 1f,
    val appSort: AppSort = AppSort.ALPHABETICAL,
    val launchCounts: Map<String, Long> = emptyMap(),
    val lastLaunchEpochMillis: Map<String, Long> = emptyMap(),
    val habitName: String = "Go to the gym",
    val habitDoneEpochDay: Long = -1L
) {
    fun isFavorite(packageName: String) = packageName in favoritePackages
    fun isHidden(packageName: String) = packageName in hiddenPackages
    fun aliasFor(packageName: String) = aliases[packageName]

    fun toggleFavorite(packageName: String) = copy(
        favoritePackages = if (isFavorite(packageName)) favoritePackages - packageName else favoritePackages + packageName
    )
    fun hide(packageName: String) = copy(hiddenPackages = hiddenPackages + packageName)
    fun unhide(packageName: String) = copy(hiddenPackages = hiddenPackages - packageName)
    fun rename(packageName: String, alias: String): LauncherPreferences {
        val clean = alias.trim()
        return copy(aliases = if (clean.isEmpty()) aliases - packageName else aliases + (packageName to clean))
    }

    fun recordLaunch(packageName: String, epochMillis: Long): LauncherPreferences {
        val current = launchCounts[packageName] ?: 0L
        val next = if (current == Long.MAX_VALUE) current else current + 1L
        return copy(
            launchCounts = launchCounts + (packageName to next),
            lastLaunchEpochMillis = lastLaunchEpochMillis + (packageName to epochMillis)
        )
    }

    fun renameHabit(name: String): LauncherPreferences =
        copy(habitName = name.trim().ifEmpty { "Daily goal" })

    fun isHabitDone(epochDay: Long): Boolean = habitDoneEpochDay == epochDay

    fun toggleHabit(epochDay: Long): LauncherPreferences =
        copy(habitDoneEpochDay = if (isHabitDone(epochDay)) -1L else epochDay)
}
