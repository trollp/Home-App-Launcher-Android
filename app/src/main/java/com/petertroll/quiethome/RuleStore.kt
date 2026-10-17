package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.RuleCodec
import com.petertroll.quiethome.domain.RulesState

class RuleStore(context: Context) {
    private val storage = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
    fun load(): RulesState = synchronized(lock) {
        val raw = storage.getString(KEY, null) ?: return@synchronized RulesState()
        RuleCodec.decode(raw)
    }
    fun save(value: RulesState): Boolean = synchronized(lock) { storage.edit().putString(KEY, RuleCodec.encode(value)).commit() }
    fun update(block: (RulesState) -> RulesState): RulesState = synchronized(lock) {
        block(load()).also { check(save(it)) { "Could not persist rules" } }
    }
    private companion object { const val KEY = "state"; val lock = Any() }
}
