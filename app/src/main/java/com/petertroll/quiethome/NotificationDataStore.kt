package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.*

class NotificationDataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun loadRules(): NotificationRules = synchronized(lock) { NotificationRulesCodec.decode(prefs.getString(RULES, null)) }
    fun saveRules(rules: NotificationRules): Boolean = synchronized(lock) {
        runCatching {
            val inbox = NotificationInboxPolicy.normalize(
                NotificationInboxCodec.decode(prefs.getString(INBOX, null)), System.currentTimeMillis(), rules.retentionDays
            )
            prefs.edit().putString(RULES, NotificationRulesCodec.encode(rules))
                .putString(INBOX, NotificationInboxCodec.encode(inbox)).commit()
        }.getOrDefault(false)
    }
    fun loadInbox(nowMillis: Long = System.currentTimeMillis()): List<InboxItem> = synchronized(lock) {
        val rules = loadRules()
        NotificationInboxPolicy.normalize(NotificationInboxCodec.decode(prefs.getString(INBOX, null)), nowMillis, rules.retentionDays)
    }
    fun add(item: InboxItem, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        runCatching {
            val normalized = NotificationInboxPolicy.normalize(listOf(item) + NotificationInboxCodec.decode(prefs.getString(INBOX, null)), nowMillis, loadRules().retentionDays)
            val encoded = NotificationInboxCodec.encode(normalized)
            encoded.length <= MAX_JSON_CHARS && prefs.edit().putString(INBOX, encoded).commit()
        }.getOrDefault(false)
    }
    fun remove(localId: String): Boolean = synchronized(lock) {
        val kept = NotificationInboxCodec.decode(prefs.getString(INBOX, null)).filterNot { it.localId == localId }
        prefs.edit().putString(INBOX, NotificationInboxCodec.encode(kept)).commit()
    }
    fun clear(): Boolean = synchronized(lock) { prefs.edit().remove(INBOX).commit() }
    companion object { private const val FILE = "notification_local_data"; private const val RULES = "rules"; private const val INBOX = "inbox"; private const val MAX_JSON_CHARS = 250_000; private val lock = Any() }
}
