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
                needle.isEmpty() || searchScore(it, needle) > 0
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
        val comparator = when {
            needle.isNotEmpty() -> compareByDescending<DisplayApp> { searchScore(it, needle) }.then(alphabetical)
            sort == AppSort.ALPHABETICAL -> alphabetical
            sort == AppSort.MOST_USED -> compareByDescending<DisplayApp> {
                preferences.launchCounts[it.packageName] ?: 0L
            }.then(alphabetical)
            else -> compareByDescending<DisplayApp> {
                preferences.lastLaunchEpochMillis[it.packageName] ?: 0L
            }.then(alphabetical)
        }
        return filtered.sortedWith(comparator)
    }

    private fun searchScore(app: DisplayApp, needle: String): Int {
        val label = normalize(app.displayLabel)
        val packageName = normalize(app.packageName)
        if (label == needle) return 100
        if (label.startsWith(needle)) return 90
        if (label.contains(needle) || packageName.contains(needle)) return 80
        val queryTokens = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
        val terms = queryTokens.flatMap { intentTerms[it].orEmpty() }.toSet()
        return if (terms.any { label.contains(it) || packageName.contains(it) }) 60 else 0
    }

    private fun toDisplayApp(entry: AppEntry): DisplayApp {
        val alias = preferences.aliasFor(entry.packageName)
        val label = alias ?: entry.label.trim().ifEmpty { entry.packageName }
        return DisplayApp(entry.packageName, entry.activityName, label, preferences.isFavorite(entry.packageName))
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)

    private companion object {
        val intentTerms = mapOf(
            "navigate" to setOf("map", "maps"),
            "directions" to setOf("map", "maps"),
            "listen" to setOf("spotify", "music", "audio"),
            "play" to setOf("spotify", "music", "audio"),
            "music" to setOf("spotify", "music", "audio"),
            "message" to setOf("signal", "message", "chat", "whatsapp"),
            "send" to setOf("signal", "message", "chat", "whatsapp"),
            "chat" to setOf("signal", "message", "chat", "whatsapp"),
            "photo" to setOf("camera", "photo"),
            "pay" to setOf("bank", "wallet", "pay"),
        )
    }
}

data class HomeSections(val favorites: List<DisplayApp>, val allApps: List<DisplayApp>) {
    companion object {
        fun from(catalog: AppCatalog) = HomeSections(catalog.favorites(), catalog.visibleApps())
    }
}
