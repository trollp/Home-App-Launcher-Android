package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageSummaryTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun clipsIntervalsAtLocalMidnightIncludingDst() {
        val result = UsageSummary.aggregate(
            true, at("2024-03-10T17:00:00Z"), zone,
            listOf(UsageInterval("a", at("2024-03-10T04:50:00Z"), at("2024-03-10T05:10:00Z")))
        ) as UsageSummary.Available
        assertEquals(10 * 60_000L, result.days[5].totalMillis)
        assertEquals(10 * 60_000L, result.days[6].totalMillis)
    }

    @Test fun combinesMultipleEventsAndSortsAppsByDurationThenPackage() {
        val result = UsageSummary.aggregate(true, at("2024-05-08T16:00:00Z"), zone, listOf(
            UsageInterval("b", at("2024-05-08T14:00:00Z"), at("2024-05-08T14:20:00Z")),
            UsageInterval("a", at("2024-05-08T13:00:00Z"), at("2024-05-08T13:20:00Z")),
            UsageInterval("b", at("2024-05-08T15:00:00Z"), at("2024-05-08T15:10:00Z"))
        )) as UsageSummary.Available
        assertEquals(listOf("b", "a"), result.todayApps.map { it.packageName })
        assertEquals(50 * 60_000L, result.todayTotalMillis)
    }

    @Test fun missingPermissionIsDistinctAndActionable() {
        assertSame(UsageSummary.MissingPermission, UsageSummary.aggregate(false, 1L, zone, emptyList()))
    }

    @Test fun successfulEmptyQueryIsNoDataRatherThanAvailable() {
        assertSame(UsageSummary.NoData, UsageSummary.aggregate(true, at("2024-05-08T16:00:00Z"), zone, emptyList()))
    }

    @Test fun adapterStatesMapNullAndFailedQueriesToError() {
        val now = at("2024-05-08T16:00:00Z")
        assertSame(UsageSummary.QueryError, UsageSummary.fromQuery(null, true, now, zone, emptyList()))
        assertSame(UsageSummary.QueryError, UsageSummary.fromQuery(true, false, now, zone, emptyList()))
        assertSame(UsageSummary.MissingPermission, UsageSummary.fromQuery(false, true, now, zone, emptyList()))
        assertSame(UsageSummary.NoData, UsageSummary.fromQuery(true, true, now, zone, emptyList()))
        assertTrue(UsageSummary.fromQuery(true, true, now, zone, listOf(
            UsageInterval("app", now - 60_000L, now)
        )) is UsageSummary.Available)
    }

    @Test fun invalidAndFutureIntervalsAreIgnoredOrClipped() {
        val now = at("2024-05-08T16:00:00Z")
        val result = UsageSummary.aggregate(true, now, zone, listOf(
            UsageInterval("a", now + 1, now + 2), UsageInterval("a", now, now - 1), UsageInterval("", now - 5, now)
        ))
        assertSame(UsageSummary.NoData, result)
    }
}
