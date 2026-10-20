package com.petertroll.quiethome.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthSummariesTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    @Test fun `sleep clips records and stages to the observation window`() {
        val since = now.minusSeconds(24 * 60 * 60)
        val records = listOf(
            HealthSleepInput(
                since.minusSeconds(60 * 60),
                since.plusSeconds(7 * 60 * 60),
                listOf(HealthSleepStageInput(since.minusSeconds(30 * 60), since.plusSeconds(90 * 60), "Deep")),
            ),
        )
        val (minutes, stages, status) = HealthSummaries.sleep(records, since)
        assertEquals(420, minutes)
        assertEquals(90, stages["deep"])
        assertEquals(HealthReadStatus.AVAILABLE, status)
    }

    @Test fun `sleep no data is explicit rather than invented zero availability`() {
        val result = HealthSummaries.sleep(emptyList(), now.minusSeconds(86_400))
        assertEquals(0, result.first)
        assertEquals(HealthReadStatus.NO_DATA, result.third)
    }

    @Test fun `exercise qualification honors minimum and strength preference`() {
        val since = now.minusSeconds(7 * 86_400)
        val shortStrength = HealthExerciseInput(now.minusSeconds(10 * 60), now, strength = true)
        val longCardio = HealthExerciseInput(now.minusSeconds(45 * 60), now, strength = false)
        assertEquals(Triple(2, 55, 2), HealthSummaries.exercise(listOf(shortStrength, longCardio), since, 30, true))
        assertEquals(Triple(2, 55, 1), HealthSummaries.exercise(listOf(shortStrength, longCardio), since, 30, false))
    }

    @Test fun `heart samples and daily counts are bounded and filtered`() {
        val since = now.minusSeconds(3600)
        assertEquals(Triple(160, 20, 300), HealthSummaries.heartRate(listOf(
            HealthHeartRateInput(now, 500), HealthHeartRateInput(now.minusSeconds(10), 20),
            HealthHeartRateInput(since.minusSeconds(1), 70),
        ), since))
        assertNull(HealthSummaries.heartRate(emptyList(), since))
        assertEquals(1_000_000L, HealthSummaries.counts(listOf(HealthCountInput(since, now, 2_000_000)), since))
    }

    @Test fun `exercise minutes are clipped to the observation window`() {
        val since = now.minusSeconds(7 * 86_400)
        val crossing = HealthExerciseInput(since.minusSeconds(30 * 60), since.plusSeconds(30 * 60), strength = false)
        assertEquals(Triple(1, 30, 1), HealthSummaries.exercise(listOf(crossing), since, 30, true))
    }

    @Test fun `sleep stages are clipped to their parent session`() {
        val since = now.minusSeconds(86_400)
        val sleep = HealthSleepInput(
            now.minusSeconds(8 * 3600), now.minusSeconds(3600),
            listOf(HealthSleepStageInput(now.minusSeconds(9 * 3600), now, "deep")),
        )
        val result = HealthSummaries.sleep(listOf(sleep), since)
        assertEquals(420, result.second["deep"])
    }

    @Test fun `fallback reports observed gym and neutral sleep duration`() {
        val profile = PlannerProfile()
        val snapshot = SnapshotBuilder.build(
            now, java.time.ZoneId.of("UTC"), profile, emptyList(), emptyList(), null, emptyList(), emptyList(),
            health = HealthSummary(qualifyingGymSessions7d = 2, sleepMinutes24h = 455),
        )
        val entries = FallbackPlanner.create(snapshot, profile, now).next
        assertEquals("Gym progress: 2 observed in 7 days · target 3", entries.first { it.kind == NextKind.GYM }.text)
        assertEquals("Sleep recorded: 7 h 35 min in the last 24 hours", entries.first { it.kind == NextKind.SLEEP }.text)
    }

    @Test fun `bounded summary limits every externally supplied aggregate`() {
        val bounded = HealthSummary(sleepMinutes24h = 9999, qualifyingGymSessions7d = -2, stepsToday = 9_000_000).bounded()
        assertEquals(1440, bounded.sleepMinutes24h)
        assertEquals(0, bounded.qualifyingGymSessions7d)
        assertEquals(1_000_000L, bounded.stepsToday)
    }
}
