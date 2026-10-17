package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    @Test fun defaultAndUnknownPackagesAreAllowed() {
        assertEquals(NotificationAction.ALLOW, NotificationRules().decide(NotificationSignal("x")))
    }

    @Test fun configuredBatchAndSilenceAreApplied() {
        val rules = NotificationRules(mapOf("a" to NotificationMode.BATCH, "b" to NotificationMode.SILENCE))
        assertEquals(NotificationAction.CANCEL_AND_STORE, rules.decide(NotificationSignal("a")))
        assertEquals(NotificationAction.CANCEL_ONLY, rules.decide(NotificationSignal("b")))
    }

    @Test fun criticalAndOngoingSignalsAlwaysFailOpen() {
        val rules = NotificationRules(mapOf("x" to NotificationMode.SILENCE))
        listOf(
            NotificationSignal("x", isCall = true), NotificationSignal("x", isAlarm = true),
            NotificationSignal("x", isMedia = true), NotificationSignal("x", isNavigation = true),
            NotificationSignal("x", isSystem = true), NotificationSignal("x", isOngoing = true),
            NotificationSignal("x", isForegroundService = true), NotificationSignal("x", isSafety = true),
            NotificationSignal("com.petertroll.quiethome")
        ).forEach { assertEquals(NotificationAction.ALLOW, rules.decide(it)) }
    }

    @Test fun codecRoundTripsAndMalformedPersistenceFailsOpen() {
        val rules = NotificationRules(mapOf("a" to NotificationMode.BATCH), retentionDays = 3)
        assertEquals(rules, NotificationRulesCodec.decode(NotificationRulesCodec.encode(rules)))
        assertEquals(NotificationRules(), NotificationRulesCodec.decode("{bad"))
    }

    @Test fun codecBoundsRetentionAndPackageCount() {
        val decoded = NotificationRulesCodec.decode("""{"retentionDays":999,"modes":{"ok":"SILENCE","bad":"wat"}}""")
        assertEquals(30, decoded.retentionDays)
        assertEquals(NotificationMode.SILENCE, decoded.modes["ok"])
        assertNull(decoded.modes["bad"])
    }

    @Test fun inboxDeduplicatesBoundsAndExpiresOldItems() {
        val now = 10L * 86_400_000L
        val items = listOf(
            InboxItem("id", "a", "first", "one", now - 100),
            InboxItem("id", "a", "latest", "two", now - 50),
            InboxItem("old", "b", "old", null, now - 4 * 86_400_000L),
            InboxItem("new", "b", "new", null, now)
        )
        val kept = NotificationInboxPolicy.normalize(items, now, retentionDays = 3, maxItems = 2)
        assertEquals(listOf("new", "id"), kept.map { it.localId })
        assertEquals("latest", kept[1].title)
    }

    @Test fun inboxCodecRedactsControlsAndHandlesEmptyAndMalformedText() {
        val clean = InboxItem.sanitized("id", "p", "  hi\u0000there  ", "   ", 4)
        assertEquals("hithere", clean.title)
        assertNull(clean.text)
        assertEquals(emptyList<InboxItem>(), NotificationInboxCodec.decode("not json"))
        assertEquals(listOf(clean), NotificationInboxCodec.decode(NotificationInboxCodec.encode(listOf(clean))))
    }
}
