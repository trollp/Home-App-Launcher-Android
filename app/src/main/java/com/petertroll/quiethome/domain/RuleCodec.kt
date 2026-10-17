package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject

object RuleCodec {
    fun encode(state: RulesState): String = JSONObject().apply {
        put("rules", JSONObject().apply { state.rules.toSortedMap().forEach { (pkg, rule) -> put(pkg, encodeRule(rule)) } })
        state.focusUntilEpochMillis?.let { put("focusUntil", it) }
        put("grace", JSONObject().apply { state.graceUntilEpochMillis.toSortedMap().forEach { (pkg, value) -> put(pkg, value) } })
        put("accessibility", state.accessibilityEnforcementEnabled)
    }.toString()

    fun decode(raw: String): RulesState = runCatching {
        val root = JSONObject(raw)
        val rulesJson = root.optJSONObject("rules")
        val rules = buildMap {
            rulesJson?.keys()?.forEach { pkg ->
                if (pkg.isNotBlank()) (rulesJson.opt(pkg) as? JSONObject)?.let { put(pkg, decodeRule(it)) }
            }
        }
        val graceJson = root.optJSONObject("grace")
        val grace = buildMap {
            graceJson?.keys()?.forEach { pkg -> positiveLong(graceJson.opt(pkg))?.let { put(pkg, it) } }
        }
        RulesState(
            rules,
            positiveLong(root.opt("focusUntil")),
            grace,
            root.opt("accessibility") as? Boolean ?: false
        )
    }.getOrDefault(RulesState())

    private fun encodeRule(rule: AppRule) = JSONObject().apply {
        put("gate", rule.gateEnabled); put("delay", rule.delaySeconds)
        rule.sessionMinutes?.let { put("session", it) }; rule.prompt?.let { put("prompt", it) }
        rule.dailyLimitMinutes?.let { put("limit", it) }; put("focus", rule.blockDuringFocus)
        put("schedules", JSONArray().apply { rule.schedules.forEach { put(JSONObject().put("days", it.days.name).put("start", it.startMinute).put("end", it.endMinute)) } })
    }

    private fun decodeRule(json: JSONObject): AppRule = AppRule(
        gateEnabled = json.opt("gate") as? Boolean ?: false,
        delaySeconds = intValue(json.opt("delay"))?.coerceIn(0, 30) ?: 5,
        sessionMinutes = intValue(json.opt("session"))?.takeIf { it > 0 }?.coerceAtMost(240),
        prompt = (json.opt("prompt") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
        dailyLimitMinutes = intValue(json.opt("limit"))?.takeIf { it > 0 }?.coerceAtMost(1440),
        schedules = json.optJSONArray("schedules").let { array -> buildList {
            if (array != null) for (i in 0 until array.length()) (array.opt(i) as? JSONObject)?.let { item ->
                val days = (item.opt("days") as? String)?.let { runCatching { DaySet.valueOf(it) }.getOrNull() }
                val start = intValue(item.opt("start"))
                val end = intValue(item.opt("end"))
                if (days != null && start != null && end != null && start in 0..1439 && end in 0..1439 && start != end) {
                    add(ScheduleRule(days, start, end))
                }
            }
        } },
        blockDuringFocus = json.opt("focus") as? Boolean ?: false
    )

    private fun intValue(value: Any?): Int? = (value as? Number)?.let {
        val long = it.toLong()
        long.takeIf { candidate -> candidate in Int.MIN_VALUE..Int.MAX_VALUE && it.toDouble() == long.toDouble() }?.toInt()
    }

    private fun positiveLong(value: Any?): Long? = (value as? Number)?.toLong()?.takeIf { it >= 0L }
}
