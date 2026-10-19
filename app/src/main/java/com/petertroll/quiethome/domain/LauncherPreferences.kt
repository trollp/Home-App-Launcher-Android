package com.petertroll.quiethome.domain

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class AppSort { ALPHABETICAL, MOST_USED, RECENT }
enum class AppFilter { ALL, FAVORITES, OTHERS }
enum class AiProvider { NONE, OPENAI, GROQ }

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
    val habitDoneEpochDay: Long = -1L,
    val routineCompletedEpochDays: Set<Long> = emptySet(),
    val routineWeeklyTarget: Int = 3,
    val coachDeferredEpochDay: Long = -1L,
    val aiProvider: AiProvider = AiProvider.NONE,
    val aiModel: String = "gpt-5-nano",
    val outlookClientId: String = "",
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
        copy(habitName = name.trim().ifEmpty { "Gym" })

    fun routineState(): RoutineState = RoutineState(
        name = habitName,
        weeklyTarget = routineWeeklyTarget,
        completedEpochDays = routineCompletedEpochDays + habitDoneEpochDay.takeIf { it >= 0L }.let(::listOfNotNull),
    )

    fun isHabitDone(epochDay: Long): Boolean = epochDay in routineState().completedEpochDays

    fun toggleHabit(epochDay: Long): LauncherPreferences {
        val toggled = routineState().toggle(epochDay)
        return copy(routineCompletedEpochDays = toggled.completedEpochDays, habitDoneEpochDay = -1L)
    }
}
