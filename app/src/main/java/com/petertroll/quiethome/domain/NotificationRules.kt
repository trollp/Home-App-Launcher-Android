package com.petertroll.quiethome.domain

import org.json.JSONArray
import org.json.JSONObject

enum class NotificationMode { ALLOW, BATCH, SILENCE }
enum class NotificationAction { ALLOW, CANCEL_AND_STORE, CANCEL_ONLY }

data class NotificationSignal(
    val packageName: String,
    val isCall: Boolean = false,
    val isAlarm: Boolean = false,
    val isMedia: Boolean = false,
    val isNavigation: Boolean = false,
    val isSystem: Boolean = false,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isSafety: Boolean = false
)

data class NotificationRules(val modes: Map<String, NotificationMode> = emptyMap(), val retentionDays: Int = 7) {
    fun decide(signal: NotificationSignal): NotificationAction {
        if (signal.packageName == QUIET_HOME || signal.isCall || signal.isAlarm || signal.isMedia ||
            signal.isNavigation || signal.isSystem || signal.isOngoing || signal.isForegroundService || signal.isSafety
        ) return NotificationAction.ALLOW
        return when (modes[signal.packageName] ?: NotificationMode.ALLOW) {
            NotificationMode.ALLOW -> NotificationAction.ALLOW
            NotificationMode.BATCH -> NotificationAction.CANCEL_AND_STORE
            NotificationMode.SILENCE -> NotificationAction.CANCEL_ONLY
        }
    }
    companion object { const val QUIET_HOME = "com.petertroll.quiethome" }
}

object NotificationRulesCodec {
    private const val MAX_PACKAGES = 500
    fun encode(value: NotificationRules): String = JSONObject().apply {
        put("retentionDays", value.retentionDays.coerceIn(1, 30))
        put("modes", JSONObject().apply { value.modes.entries.take(MAX_PACKAGES).forEach { put(it.key, it.value.name) } })
    }.toString()

    fun decode(raw: String?): NotificationRules = runCatching {
        val root = JSONObject(raw ?: return NotificationRules())
        val objectModes = root.optJSONObject("modes") ?: JSONObject()
        val modes = buildMap {
            val keys = objectModes.keys()
            repeat(MAX_PACKAGES) {
                if (!keys.hasNext()) return@repeat
                val key = keys.next().takeIf { it.isNotBlank() && it.length <= 255 } ?: return@repeat
                val mode = runCatching { NotificationMode.valueOf(objectModes.optString(key)) }.getOrNull() ?: return@repeat
                if (mode != NotificationMode.ALLOW) put(key, mode)
            }
        }
        NotificationRules(modes, root.optInt("retentionDays", 7).coerceIn(1, 30))
    }.getOrDefault(NotificationRules())
}

data class InboxItem(val localId: String, val packageName: String, val title: String?, val text: String?, val postedMillis: Long) {
    companion object {
        fun sanitized(localId: String, packageName: String, title: CharSequence?, text: CharSequence?, postedMillis: Long): InboxItem {
            fun clean(value: CharSequence?): String? = value?.toString()?.filterNot { it.isISOControl() }?.trim()?.take(300)?.ifBlank { null }
            return InboxItem(localId.take(100), packageName.take(255), clean(title), clean(text), postedMillis.coerceAtLeast(0L))
        }
    }
}

object NotificationInboxPolicy {
    fun normalize(items: List<InboxItem>, nowMillis: Long, retentionDays: Int, maxItems: Int = 200): List<InboxItem> {
        val cutoff = nowMillis - retentionDays.coerceIn(1, 30) * 86_400_000L
        return items.asSequence().filter { it.postedMillis >= cutoff && it.postedMillis <= nowMillis + 86_400_000L }
            .sortedByDescending { it.postedMillis }.distinctBy { it.localId }.take(maxItems.coerceIn(1, 500)).toList()
    }
}

object NotificationInboxCodec {
    fun encode(items: List<InboxItem>): String = JSONArray().apply { items.take(500).forEach { item -> put(JSONObject().apply {
        put("id", item.localId); put("package", item.packageName); put("title", item.title ?: JSONObject.NULL)
        put("text", item.text ?: JSONObject.NULL); put("posted", item.postedMillis)
    }) } }.toString()
    fun decode(raw: String?): List<InboxItem> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until minOf(array.length(), 500)).mapNotNull { i -> array.optJSONObject(i)?.let { value ->
            val id = value.optString("id"); val pkg = value.optString("package")
            if (id.isBlank() || pkg.isBlank()) null else InboxItem.sanitized(id, pkg, value.optString("title").takeUnless { value.isNull("title") }, value.optString("text").takeUnless { value.isNull("text") }, value.optLong("posted"))
        } }
    }.getOrDefault(emptyList())
}
