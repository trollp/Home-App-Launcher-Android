package com.petertroll.quiethome.domain

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class LauncherPreferences(
    val favoritePackages: Set<String> = emptySet(),
    val hiddenPackages: Set<String> = emptySet(),
    val aliases: Map<String, String> = emptyMap(),
    val theme: AppTheme = AppTheme.SYSTEM,
    val textScale: Float = 1f
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
}
