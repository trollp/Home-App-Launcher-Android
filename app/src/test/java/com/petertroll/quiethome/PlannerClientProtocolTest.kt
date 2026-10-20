package com.petertroll.quiethome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannerClientProtocolTest {
    @Test fun `planner request is OpenAI strict schema with real system message and defaults`() {
        val body = JSONObject(PlannerRequestFactory.body("{}"))
        assertEquals("gpt-5-mini", body.getString("model"))
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals(4000, body.getInt("max_completion_tokens"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        val format = body.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getJSONObject("json_schema").getBoolean("strict"))
        assertFalse(body.toString().contains("groq", true))
        val schema = format.getJSONObject("json_schema").getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        val nextKinds = schema.getJSONObject("properties").getJSONObject("next").getJSONObject("items").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum")
        assertEquals(listOf("EVENT", "PREP", "GYM", "TASK", "REST", "SLEEP", "OTHER"), (0 until nextKinds.length()).map { nextKinds.getString(it) })
        assertTrue(schema.toString().contains("packageName"))
        assertFalse(schema.toString().contains("\"query\""))
    }

    @Test fun `planner system prompt carries owner constraints and goals verbatim`() {
        val snapshot = """{"profile":{"hardConstraints":["No meetings after 17:00"],"goals":["Train three times"]}}"""
        val body = JSONObject(PlannerRequestFactory.body(snapshot))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("No meetings after 17:00"))
        assertTrue(system.contains("Train three times"))
        assertTrue(system.contains("Stockholm"))
        assertTrue(system.contains("never diagnose"))
        assertTrue(system.contains("Body Battery"))
        assertTrue(system.contains("VO2 max"))
    }

    @Test fun `response rejects length finish and empty content`() {
        assertThrows(IllegalArgumentException::class.java) { PlannerResponseParser.content("""{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""") }
        assertThrows(IllegalArgumentException::class.java) { PlannerResponseParser.content("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}""") }
        assertEquals("{}", PlannerResponseParser.content("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}"""))
    }
}
