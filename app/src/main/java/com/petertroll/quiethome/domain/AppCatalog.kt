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

    fun search(query: String): List<DisplayApp> = browse(query, AppSort.ALPHABETICAL, AppFilter.ALL)

    fun browse(query: String, sort: AppSort, filter: AppFilter): List<DisplayApp> {
        val needle = normalize(query.trim())
        val filtered = visibleApps().asSequence()
            .filter {
                needle.isEmpty() || normalize(it.displayLabel).contains(needle) ||
                    normalize(it.packageName).contains(needle)
            }
            .filter {
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.FAVORITES -> it.isFavorite
                    AppFilter.OTHERS -> !it.isFavorite
                }
            }
            .toList()
        val alphabetical = compareBy<DisplayApp> { normalize(it.displayLabel) }
            .thenBy { it.packageName }
            .thenBy { it.activityName }
        val comparator = when (sort) {
            AppSort.ALPHABETICAL -> alphabetical
            AppSort.MOST_USED -> compareByDescending<DisplayApp> {
                preferences.launchCounts[it.packageName] ?: 0L
            }.then(alphabetical)
            AppSort.RECENT -> compareByDescending<DisplayApp> {
                preferences.lastLaunchEpochMillis[it.packageName] ?: 0L
            }.then(alphabetical)
        }
        return filtered.sortedWith(comparator)
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
