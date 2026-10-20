package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class PlannerPhase1Test {
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val event = CalendarEvent("event:1", 1, now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli(), " Team sync ", "Room", false, true, 1, "Work", "work@example.com", true)
    private val profile = PlannerProfile()

    @Test fun `profile defaults and bounds match phase one`() {
        assertEquals(LocalTime.of(23, 0), profile.bedtime)
        assertEquals(LocalTime.of(7, 0), profile.wakeTime)
        assertEquals(60, profile.windDownMinutes)
        assertEquals(3, profile.proactiveBudget)
        assertEquals("gpt-5-mini", profile.plannerModel.name)
        assertEquals("low", profile.plannerModel.reasoningEffort)
        assertEquals(4000, profile.plannerModel.maxCompletionTokens)
        assertEquals("gpt-5-nano", profile.intentModel.name)
        assertEquals(15, RoutineCollection.MAX_ROUTINES)
        assertEquals(10, PlannerProfile(hardConstraints = List(20) { "x".repeat(200) }).normalized().hardConstraints.size)
        assertTrue(PlannerProfile(goals = List(20) { "x".repeat(200) }).normalized().goals.all { it.length <= 120 })
    }

    @Test fun `calendar rows filter free declined selected and deduplicate`() {
        val rows = listOf(
            CalendarRow(1, 100, 200, " Sync ", "", false, CalendarAvailability.BUSY, CalendarAttendee.ACCEPTED, 4, "Work", "a", "meet.google.com/secret"),
            CalendarRow(2, 100, 200, "sync", "", false, CalendarAvailability.BUSY, CalendarAttendee.TENTATIVE, 4, "Work", "a", null),
            CalendarRow(3, 300, 400, "Free", "", false, CalendarAvailability.FREE, CalendarAttendee.ACCEPTED, 4, "Work", "a", null),
            CalendarRow(4, 500, 600, "No", "", false, CalendarAvailability.BUSY, CalendarAttendee.DECLINED, 4, "Work", "a", null),
        )
        val result = CalendarRows.collect(rows, includeFreeEvents = false, selectedCalendarIds = null)
        assertEquals(1, result.size)
        assertEquals("Sync", result.single().title)
        assertTrue(result.single().onlineMeeting)
        assertFalse(result.single().toString().contains("meet.google.com"))
        assertEquals(2, CalendarRows.collect(rows, true, null).size)
    }

    @Test fun `snapshot derives free slots metrics tasks and stays bounded`() {
        val task = LocalTask("task:1", "Pay bill", now.plusSeconds(1800).toEpochMilli(), true, false, now.toEpochMilli())
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile, listOf(event), listOf(task), null, emptyList(), emptyList())
        assertEquals(1, snapshot.metrics.meetingCount)
        assertEquals(60, snapshot.metrics.meetingMinutes)
        assertEquals(60, snapshot.metrics.longestMeetingStretchMinutes)
        assertEquals(1, snapshot.metrics.tasksDue)
        assertTrue(snapshot.freeSlots.all { it.durationMinutes >= 45 })
        assertTrue(snapshot.gymCandidateSlots.all { it.durationMinutes >= profile.gym.durationMinutes + profile.gym.travelMinutes * 2 })
        val encoded = PlannerSnapshotCodec.encode(snapshot)
        assertTrue(encoded.toByteArray().size <= PlannerSnapshotCodec.HARD_CAP_BYTES)
        assertEquals(snapshot.copy(generatedAtEpochMillis = snapshot.generatedAtEpochMillis), PlannerSnapshotCodec.decode(encoded))
    }

    @Test fun `snapshot builder always fits the transport cap for bounded worst case input`() {
        val longText = "x".repeat(500)
        val events = (1L..100L).map { id ->
            CalendarEvent("event:$id", id, now.plusSeconds(id * 60).toEpochMilli(), now.plusSeconds(id * 60 + 30).toEpochMilli(), longText, longText, false, true, id, longText, longText, false)
        }
        val tasks = (1L..200L).map { id -> LocalTask("task:$id", longText, null, true, false, now.toEpochMilli()) }
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile, events, tasks, longText, (1..100).map { "package.$it" to Int.MAX_VALUE }, emptyList())
        assertTrue(PlannerSnapshotCodec.encode(snapshot).toByteArray().size <= PlannerSnapshotCodec.HARD_CAP_BYTES)
    }

    @Test fun `validator normalizes event time drops unsafe unknown and over budget output`() {
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile.copy(proactiveBudget = 1), listOf(event), emptyList(), null, emptyList(), emptyList())
        val raw = PlannerPlan(
            headline = "A calm next step",
            next = listOf(
                PlanNext("event:1", "09:30", "Join sync", NextKind.EVENT),
                PlanNext("missing", "10:00", "Unknown", NextKind.TASK),
                PlanNext(null, null, "https://bad", NextKind.OTHER),
            ),
            digest = listOf(PlanDigest(listOf("event:1", "missing"), "One meeting")),
            nudges = listOf(
                PlanNudge("09:00", "Prepare", "event:1", NudgeKind.PREP),
                PlanNudge("10:30", "Optional thought", null, NudgeKind.OTHER),
            ),
            actions = listOf(PlanAction("a", "open_event", mapOf("eventId" to "event:1"), "Open"), PlanAction("b", "shell", emptyMap(), "Bad")),
        )
        val valid = PlanValidator.validate(raw, snapshot, profile, now)
        assertEquals("09:00", valid.next.single().time)
        assertEquals(listOf("event:1"), valid.digest.single().refIds)
        assertEquals(2, valid.nudges.size) // meeting prep is budget exempt
        assertEquals("open_event", valid.actions.single().tool)
    }

    @Test fun `validator rejects actions with missing malformed or unexpected arguments`() {
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile, listOf(event), emptyList(), null, emptyList(), emptyList(), allowedAppPackages = setOf("com.example.app"))
        val raw = PlannerPlan("Plan", emptyList(), emptyList(), emptyList(), listOf(
            PlanAction("missing", "open_app", emptyMap(), "Open"),
            PlanAction("extra", "open_maps", mapOf("eventId" to "event:1", "uri" to "intent:bad"), "Map"),
            PlanAction("alarm", "set_alarm", mapOf("time" to "99:00"), "Alarm"),
            PlanAction("temporary-dnd", "toggle_dnd", mapOf("state" to "on", "until" to "10:00"), "Quiet until ten"),
            PlanAction("unicode", "add_task", mapOf("title" to "one\u2028two"), "Add"),
            PlanAction("valid", "open_maps", mapOf("eventId" to "event:1"), "Map"),
        ))
        assertEquals(listOf("valid"), PlanValidator.validate(raw, snapshot, profile, now).actions.map { it.id })
    }

    @Test fun `fallback is deterministic and never blank`() {
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile, listOf(event), emptyList(), null, emptyList(), emptyList())
        val first = FallbackPlanner.create(snapshot, profile, now)
        assertEquals(first, FallbackPlanner.create(snapshot, profile, now))
        assertTrue(first.headline.isNotBlank())
        assertTrue(first.next.isNotEmpty())
        assertTrue(first.next.any { it.kind == NextKind.GYM })
    }

    @Test fun `activity log retains rolling fourteen days and last twenty snapshot entries`() {
        val records = (0 until 40).map { ActivityRecord("r$it", now.minusSeconds(it * 86_400L).toEpochMilli(), ActivityKind.PLAN_SHOWN, "x") }
        val bounded = ActivityLogPolicy.bound(records, now)
        assertTrue(bounded.size <= 14)
        assertEquals(bounded.take(20), ActivityLogPolicy.summary(bounded).last20)
    }

    @Test fun `local task APIs use stable ids and complete without replacement`() {
        val original = LocalTask("task:stable", "First", null, false, false, 1)
        val edited = LocalTasks.edit(listOf(original), "task:stable", "Changed", 2, true)
        assertEquals("task:stable", edited.single().id)
        assertTrue(LocalTasks.complete(edited, "task:stable", true).single().completed)
    }

    @Test fun `snapshot carries profile source truth allowed apps and full counts`() {
        val records = (0 until 30).map { ActivityRecord("r$it", now.minusSeconds(it.toLong()).toEpochMilli(), ActivityKind.DONE) }
        val configured = profile.copy(hardConstraints = listOf("  No meetings after five  "), goals = listOf("Ship"), importantApps = setOf("com.bank"))
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), configured, emptyList(), emptyList(), null, emptyList(), records, calendarStatus = SourceStatus.PERMISSION_MISSING, calendarCollectedAt = null, allowedAppPackages = setOf("com.allowed"))
        val roundTrip = PlannerSnapshotCodec.decode(PlannerSnapshotCodec.encode(snapshot))
        assertEquals(listOf("No meetings after five"), roundTrip.profile.hardConstraints)
        assertEquals(30, roundTrip.activity.counts[ActivityKind.DONE])
        assertEquals(20, roundTrip.activity.last20.size)
        assertEquals(SourceStatus.PERMISSION_MISSING, roundTrip.freshness.calendarStatus)
        assertNull(roundTrip.freshness.calendarEpochMillis)
        assertEquals(setOf("com.allowed"), roundTrip.allowedAppPackages)
    }

    @Test fun `validator truncates text rejects newline and grounds free slots and apps`() {
        val free = TimeSlot(now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli())
        val snapshot = SnapshotBuilder.build(now, ZoneId.of("UTC"), profile, listOf(event), emptyList(), null, emptyList(), emptyList(), allowedAppPackages = setOf("com.allowed.app")).copy(freeSlots = listOf(free))
        val raw = PlannerPlan("h".repeat(120), listOf(PlanNext(null, null, "x".repeat(100), NextKind.OTHER), PlanNext(null, null, "bad\nline", NextKind.OTHER)), emptyList(), emptyList(), listOf(
            PlanAction("app", "open_app", mapOf("packageName" to "com.hidden.app"), "Open"),
            PlanAction("block", "create_calendar_block", mapOf("start" to free.startEpochMillis.toString(), "end" to free.endEpochMillis.toString(), "title" to "Focus"), "Block"),
            PlanAction("outside", "create_calendar_block", mapOf("start" to free.endEpochMillis.toString(), "end" to (free.endEpochMillis + 1000).toString(), "title" to "No"), "No"),
        ))
        val valid = PlanValidator.validate(raw, snapshot, profile, now)
        assertEquals(90, valid.headline.length)
        assertEquals(80, valid.next.single().text.length)
        assertEquals(listOf("block"), valid.actions.map { it.id })
    }
}
