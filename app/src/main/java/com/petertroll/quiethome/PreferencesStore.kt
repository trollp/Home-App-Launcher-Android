package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.LauncherPreferences
import com.petertroll.quiethome.domain.PreferencesCodec

class PreferencesStore(context: Context) {
    private val storage = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)

    fun load(): LauncherPreferences {
        val raw = storage.getString(KEY, null) ?: return LauncherPreferences()
        return PreferencesCodec.decode(raw)
    }

    fun save(value: LauncherPreferences) {
        storage.edit().putString(KEY, PreferencesCodec.encode(value)).apply()
    }

    private companion object { const val KEY = "state" }
}
