package com.petertroll.quiethome

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContextAssistantContractTest {
    private val root = File(System.getProperty("user.dir"))
    private fun text(path: String) = File(root, path).readText()

    @Test fun `assistant activity is private and adds no component permissions`() {
        val manifest = text("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".AssistantActivity"))
        assertTrue(Regex("android:name=\"\\.AssistantActivity\"[\\s\\S]{0,160}android:exported=\"false\"").containsMatchIn(manifest))
        assertFalse(manifest.contains("AssistantService"))
        assertFalse(manifest.contains("AssistantReceiver"))
    }

    @Test fun `assistant surface has all calm explicit controls`() {
        val source = text("src/main/java/com/petertroll/quiethome/AssistantActivity.kt")
        listOf("Done", "Later", "Smaller", "Not today", "Why", "Refine with AI",
            "Morning briefing", "Evening reflection", "Weekly review", "Apply for tomorrow", "No change")
            .forEach { assertTrue("missing $it", source.contains(it)) }
        assertTrue(source.contains("Snackbar"))
        assertFalse(source.contains("startService"))
        assertFalse(source.contains("AlarmManager"))
    }

    @Test fun `settings disclosure lists exactly assistant AI fields and learning controls`() {
        val strings = text("src/main/res/values/strings.xml")
        listOf("day phase", "Stockholm weather summary", "minutes until the next event",
            "routine names", "weekly progress", "capacity", "recent feedback", "candidate IDs")
            .forEach { assertTrue("missing disclosure: $it", strings.contains(it, true)) }
        val settings = text("src/main/java/com/petertroll/quiethome/SettingsActivity.kt")
        assertTrue(settings.contains("Pause learning"))
        assertTrue(settings.contains("Clear feedback"))
        assertTrue(settings.contains("Off"))
        assertTrue(settings.contains("Once daily"))
        assertTrue(settings.contains("Context changes"))
    }

    @Test fun `release versions are exact`() {
        val gradle = text("build.gradle.kts")
        assertTrue(gradle.contains("versionCode = 16"))
        assertTrue(gradle.contains("versionName = \"0.16.1-context-assistant\""))
        assertTrue(gradle.contains("versionName = \"0.16.1-bank-safe\""))
    }

    @Test fun `launcher routes every assistant entry point to the unified activity`() {
        val launcher = text("src/main/java/com/petertroll/quiethome/LauncherActivity.kt")
        assertTrue(launcher.contains("briefingButton.setOnClickListener { openAssistantBriefing() }"))
        assertTrue(launcher.contains("AssistantActivity.EXTRA_OPEN_BRIEFING"))
        assertFalse(launcher.contains("binding.briefingButton.setOnClickListener { showBriefing() }"))
        assertTrue(launcher.contains("Priorities ·"))
    }

    @Test fun `aggregate and reflection refinements have dedicated UI result paths and weekly undo`() {
        val source = text("src/main/java/com/petertroll/quiethome/AssistantActivity.kt")
        assertTrue(source.contains("refineBriefing"))
        assertTrue(source.contains("showRefinedBriefing"))
        assertTrue(source.contains("showRefinedProposal"))
        assertTrue(source.contains("showRefinedWeekly"))
        assertTrue(source.contains("proposal.revert"))
    }

    @Test fun `launcher delegates session visibility to off safe proactivity policy`() {
        val source = text("src/main/java/com/petertroll/quiethome/LauncherActivity.kt")
        assertTrue(source.contains("ProactivityPolicy.shouldShowInSession"))
        assertFalse(source.contains("proactiveShownThisSession ||"))
    }

    @Test fun `assistant guards stale actions and asynchronous UI delivery`() {
        val source = text("src/main/java/com/petertroll/quiethome/AssistantActivity.kt")
        assertTrue(source.contains("smallerOrNull"))
        assertTrue(source.contains("canDeliverUi"))
        assertTrue(source.contains("!isFinishing && !isDestroyed"))
        assertTrue(source.contains("if (!canDeliverUi()) return@post"))
    }
}
