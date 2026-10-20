package com.petertroll.quiethome.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferencesCodecTest {
    @Test fun `planner and intent model configuration round trips independently`() {
        val profile = PlannerProfile(
            plannerModel = ModelConfig("planner-custom", "medium", 2222),
            intentModel = ModelConfig("intent-custom", "minimal", 333),
        )
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(LauncherPreferences(plannerProfile = profile)))
        assertEquals(profile.plannerModel, decoded.plannerProfile.plannerModel)
        assertEquals(profile.intentModel, decoded.plannerProfile.intentModel)
    }

    @Test fun `health gym qualification preferences round trip and normalize`() {
        val profile = PlannerProfile(gym = GymProfile(healthExerciseMinimumMinutes = 75, strengthAlwaysQualifies = false))
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(LauncherPreferences(plannerProfile = profile))).plannerProfile
        assertEquals(75, decoded.gym.healthExerciseMinimumMinutes)
        assertFalse(decoded.gym.strengthAlwaysQualifies)

        val excessive = PlannerProfile(gym = GymProfile(healthExerciseMinimumMinutes = 999)).normalized()
        assertEquals(240, excessive.gym.healthExerciseMinimumMinutes)
    }

    @Test fun `corrupt state recovers to defaults`() {
        assertEquals(LauncherPreferences(), PreferencesCodec.decode("{not-json"))
    }

    @Test fun `encoded state round trips and invalid fields are constrained`() {
        val preferences = LauncherPreferences(
            favoritePackages = setOf("a"),
            hiddenPackages = setOf("b"),
            aliases = mapOf("a" to "Alpha"),
            theme = AppTheme.DARK,
            textScale = 1.25f,
            appSort = AppSort.MOST_USED,
            launchCounts = mapOf("a" to 3L),
            lastLaunchEpochMillis = mapOf("a" to 1234L),
            habitName = "Go to the gym",
            habitDoneEpochDay = 20_000L,
            routineCompletedEpochDays = setOf(19_998L, 20_000L),
            routineWeeklyTarget = 3,
            openAiEnabled = true,
            intentCloudEnabled = true,
            routines = listOf(Routine("gym", "Gym", "Train", "Stretch", 3)),
            localTasks = listOf(LocalTask("task:1", "Pay bill", null, true, false, 1234L)),
        )
        assertEquals(preferences, PreferencesCodec.decode(PreferencesCodec.encode(preferences)))
        assertEquals(AppTheme.SYSTEM, PreferencesCodec.decode("{\"theme\":\"INVALID\"}").theme)
        assertEquals(1.4f, PreferencesCodec.decode("{\"textScale\":99}").textScale)
    }

    @Test fun `legacy ai settings never migrate into cloud planner consent`() {
        val legacy = """{"aiProvider":"OPENAI","openAiEnabled":true,"habitName":"Walk","habitDoneEpochDay":20000}"""
        val migrated = PreferencesCodec.decode(legacy)
        assertFalse(migrated.openAiEnabled)
        assertFalse(migrated.intentCloudEnabled)
        assertEquals("Walk", migrated.routines.single().name)
        assertEquals(setOf(20000L), migrated.routines.single().completedEpochDays)
    }

    @Test fun `planner consent never implies intent classifier consent`() {
        val decoded = PreferencesCodec.decode("""{"plannerCloudOptIn":true}""")
        assertEquals(true, decoded.openAiEnabled)
        assertFalse(decoded.intentCloudEnabled)
    }
}
