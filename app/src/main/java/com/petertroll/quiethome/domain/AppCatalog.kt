package com.petertroll.quiethome.domain

import java.text.Normalizer
import java.util.Locale

class AppCatalog(
    entries: List<AppEntry>,
    private val preferences: LauncherPreferences
) {
    private val entries = entries
        .sortedWith(compareBy<AppEntry> { it.packageName }.thenBy { it.activityName }.thenBy { it.label })
        .distinctBy { it.packageName }

    fun visibleApps(): List<DisplayApp> = entries
        .asSequence()
        .filterNot { preferences.isHidden(it.packageName) }
        .map(::toDisplayApp)
        .sortedWith(compareBy<DisplayApp> { normalize(it.displayLabel) }.thenBy { it.packageName }.thenBy { it.activityName })
        .toList()

    fun favorites(): List<DisplayApp> = visibleApps().filter { it.isFavorite }

    fun search(query: String): List<DisplayApp> {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return visibleApps()
        return visibleApps().filter {
            normalize(it.displayLabel).contains(needle) || normalize(it.packageName).contains(needle)
        }
    }

    private fun toDisplayApp(entry: AppEntry): DisplayApp {
        val alias = preferences.aliasFor(entry.packageName)
        val label = alias ?: entry.label.trim().ifEmpty { entry.packageName }
        return DisplayApp(entry.packageName, entry.activityName, label, preferences.isFavorite(entry.packageName))
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
}

data class HomeSections(val favorites: List<DisplayApp>, val allApps: List<DisplayApp>) {
    companion object {
        fun from(catalog: AppCatalog) = HomeSections(catalog.favorites(), catalog.visibleApps())
    }
}
