package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPrivacyContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/petertroll/quiethome/HealthConnectIntegration.kt").exists() }

    @Test fun `health cache stores bounded aggregate fields but not record identity or origin`() {
        val integration = File(root, "app/src/main/java/com/petertroll/quiethome/HealthConnectIntegration.kt").readText()
        val cache = integration.substringAfter("class HealthSummaryCache")
        assertTrue(cache.contains("qualifyingGymSessions7d"))
        assertTrue(cache.contains("sleepMinutes24h"))
        assertFalse(cache.contains("metadata"))
        assertFalse(cache.contains("dataOrigin"))
        assertFalse(cache.contains("recordId"))
    }

    @Test fun `planner worker explicitly uses background health mode and fallback reads cache`() {
        val planner = File(root, "app/src/main/java/com/petertroll/quiethome/PlannerIntegration.kt").readText()
        assertTrue(planner.contains("refresh(background = true)"))
        assertTrue(planner.contains("HealthSummaryCache(context).load()?.copy(cached = true)"))
    }

    @Test fun `daily steps and calories use Health Connect aggregate API`() {
        val integration = File(root, "app/src/main/java/com/petertroll/quiethome/HealthConnectIntegration.kt").readText()
        assertTrue(integration.contains("AggregateRequest(setOf(StepsRecord.COUNT_TOTAL)"))
        assertTrue(integration.contains("AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL)"))
    }
}
