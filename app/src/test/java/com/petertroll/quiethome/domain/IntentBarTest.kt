package com.petertroll.quiethome.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class IntentBarTest {
    private val apps = listOf(IntentApp("Signal", "org.signal", "Main"), IntentApp("Simple Notes", "org.notes", "Notes"), IntentApp("Simple Calendar", "org.calendar", "Calendar"))
    private val tasks = listOf(LocalTask("task:bill", "Pay power bill", null, false, false, 1), LocalTask("task:book", "Book train", null, false, false, 1))

    @Test fun `local parser handles navigation apps alarm task completion and gym`() {
        assertEquals("org.signal", (LocalIntentParser.parse("Signal", apps, tasks) as IntentParseResult.OpenApp).app.packageName)
        assertEquals("org.signal", (LocalIntentParser.parse("open sig", apps, tasks) as IntentParseResult.OpenApp).app.packageName)
        assertEquals("org.notes", (LocalIntentParser.parse("open notes", apps, tasks) as IntentParseResult.OpenApp).app.packageName)
        assertSame(IntentParseResult.OpenApps, LocalIntentParser.parse("apps", apps, tasks))
        assertSame(IntentParseResult.OpenPlan, LocalIntentParser.parse("plan", apps, tasks))
        assertEquals("07:30", (LocalIntentParser.parse("alarm 07:30", apps, tasks) as IntentParseResult.Action).value.args["time"])
        assertEquals("Buy milk", (LocalIntentParser.parse("add task Buy milk", apps, tasks) as IntentParseResult.Action).value.args["title"])
        assertEquals("task:bill", (LocalIntentParser.parse("done power", apps, tasks) as IntentParseResult.Action).value.args["taskId"])
        assertEquals("mark_gym_done", (LocalIntentParser.parse("gym done", apps, tasks) as IntentParseResult.Action).value.tool)
    }

    @Test fun `ambiguous app or task never guesses`() {
        assertTrue(LocalIntentParser.parse("simple", apps, tasks) is IntentParseResult.Ambiguous)
        val duplicateTasks = tasks + LocalTask("task:bill2", "Pay phone bill", null, false, false, 1)
        assertTrue(LocalIntentParser.parse("done bill", apps, duplicateTasks) is IntentParseResult.Ambiguous)
    }

    @Test fun `input rejects controls newlines empties and over limit`() {
        assertTrue(LocalIntentParser.parse("a\nb", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("a\u0000b", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("a\u0085b", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("a\u2028b", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("a\u2029b", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse(" ", apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("x".repeat(241), apps, tasks) is IntentParseResult.Invalid)
        assertTrue(LocalIntentParser.parse("alarm 25:00", apps, tasks) is IntentParseResult.Unmatched)
    }

    @Test fun `cloud preflight blocks ambiguous command references but not generic words`() {
        assertEquals("app", CloudIntentPreflight.ambiguity("Could you open one of the simple apps please", apps, tasks)?.kind)
        assertNull(CloudIntentPreflight.ambiguity("Could you open Simple Calendar please", apps, tasks))
        val duplicateTasks = tasks + LocalTask("task:bill2", "Pay phone bill", null, false, false, 1)
        assertEquals("task", CloudIntentPreflight.ambiguity("please finish bill now", apps, duplicateTasks)?.kind)
        assertNull(CloudIntentPreflight.ambiguity("schedule simple focus tomorrow", apps, tasks))
        assertNull(CloudIntentPreflight.ambiguity("write a bill summary", apps, duplicateTasks))
    }

    @Test fun `payload minimization restricts validation to transmitted references`() {
        val manyApps = List(100) { IntentApp("App $it" + "x".repeat(80), "org.example.app$it") }
        val manyTasks = List(50) { LocalTask("task:$it", "Task $it" + "x".repeat(90), null, false, false, 1) }
        val context = IntentContext("schedule focus", "UTC", 123, manyApps, manyTasks, emptyList(), emptyList())
        val encoded = IntentPayloadCodec.minimize(context)
        assertTrue(encoded.transmitted.apps.size < manyApps.size)
        val snapshot = SnapshotBuilder.build(Instant.ofEpochMilli(123), ZoneId.of("UTC"), PlannerProfile(), emptyList(), manyTasks, null, emptyList(), emptyList(), allowedAppPackages = manyApps.map { it.packageName }.toSet())
        val restricted = IntentPayloadCodec.restrictValidationSnapshot(snapshot, encoded.transmitted)
        assertEquals(encoded.transmitted.apps.map { it.packageName }.toSet(), restricted.allowedAppPackages)
        assertEquals(encoded.transmitted.tasks.map { it.id }, restricted.tasks.map { it.id })
        assertNull(PlanValidator.validateAction(PlanAction("x", "open_app", mapOf("packageName" to manyApps.last().packageName), "Open"), restricted))
    }

    @Test fun `safe capability excludes DND from payload tools`() {
        val context = IntentContext("quiet please", "UTC", 123, emptyList(), emptyList(), emptyList(), emptyList(), IntentCapabilities.tools(false))
        assertFalse(JSONObject(IntentPayloadCodec.encode(context)).getJSONArray("allowedTools").toString().contains("toggle_dnd"))
    }

    @Test fun `classifier payload is bounded and explicit allow list excludes sensitive categories`() {
        val context = IntentContext("schedule focus", "Europe/Stockholm", 123, List(300) { IntentApp("App $it" + "x".repeat(80), "org.example.app$it") }, tasks,
            listOf(IntentEvent("event:1", "Meeting", 100, 200, true)), listOf(TimeSlot(300, 400)))
        val encoded = IntentPayloadCodec.encode(context)
        assertTrue(encoded.toByteArray().size <= IntentPayloadCodec.HARD_CAP_BYTES)
        val json = JSONObject(encoded)
        assertEquals(setOf("text", "zone", "now", "visibleApps", "activeTasks", "currentEvents", "freeSlots", "allowedTools"), json.keys().asSequence().toSet())
        val lower = encoded.lowercase()
        listOf("health", "usage", "notification", "activity", "account", "goal", "constraint", "credential", "history", "location\"").forEach { assertFalse(it, lower.contains(it)) }
        assertTrue(json.getJSONArray("currentEvents").getJSONObject(0).getBoolean("locationPresent"))
    }
}
