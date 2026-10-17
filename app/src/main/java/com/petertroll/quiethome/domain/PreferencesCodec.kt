package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject

object PreferencesCodec {
    fun decode(raw: String): LauncherPreferences = runCatching {
        val json = JSONObject(raw)
        LauncherPreferences(
            favoritePackages = json.optJSONArray("favorites").strings(),
            hiddenPackages = json.optJSONArray("hidden").strings(),
            aliases = json.optJSONObject("aliases").stringMap(),
            theme = runCatching { AppTheme.valueOf(json.optString("theme", "SYSTEM")) }
                .getOrDefault(AppTheme.SYSTEM),
            textScale = json.optDouble("textScale", 1.0).toFloat().coerceIn(.85f, 1.4f)
        )
    }.getOrDefault(LauncherPreferences())

    fun encode(value: LauncherPreferences): String {
        val aliases = JSONObject().apply {
            value.aliases.toSortedMap().forEach { (key, alias) -> put(key, alias) }
        }
        return JSONObject()
            .put("favorites", JSONArray(value.favoritePackages.sorted()))
            .put("hidden", JSONArray(value.hiddenPackages.sorted()))
            .put("aliases", aliases)
            .put("theme", value.theme.name)
            .put("textScale", value.textScale.toDouble())
            .toString()
    }

    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else buildSet {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONObject?.stringMap(): Map<String, String> = if (this == null) emptyMap() else buildMap {
        keys().forEach { key -> optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } }
    }
}
