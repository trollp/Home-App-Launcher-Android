package com.petertroll.quiethome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntentProtocolTest {
    @Test fun `request uses dedicated defaults strict schema and one nullable action`() {
        val body = JSONObject(IntentRequestFactory.body("""{"text":"hello"}"""))
        assertEquals("gpt-5-nano", body.getString("model"))
        assertEquals("minimal", body.getString("reasoning_effort"))
        assertEquals(800, body.getInt("max_completion_tokens"))
        val format = body.getJSONObject("response_format").getJSONObject("json_schema")
        assertTrue(format.getBoolean("strict"))
        val schema = format.getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(listOf("action"), (0 until schema.getJSONArray("required").length()).map { schema.getJSONArray("required").getString(it) })
        val text = schema.toString()
        assertTrue(text.contains("open_app")); assertTrue(text.contains("mark_gym_done")); assertFalse(text.contains("shell"))
        assertFalse(text.contains("until"))
    }

    @Test fun `safe capability removes DND from strict schema`() {
        val body = JSONObject(IntentRequestFactory.body("""{"text":"hello"}""", allowedTools = com.petertroll.quiethome.domain.IntentCapabilities.tools(false)))
        val schema = body.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").toString()
        assertFalse(schema.contains("toggle_dnd"))
        assertTrue(schema.contains("set_alarm"))
    }

    @Test fun `response parser is fail closed`() {
        assertNull(IntentResponseParser.action("""{"action":null}"""))
        val action = IntentResponseParser.action("""{"action":{"id":"a","tool":"set_alarm","args":{"time":"08:00"},"label":"Alarm"}}""")!!
        assertEquals("set_alarm", action.tool)
        assertThrows(Exception::class.java) { IntentResponseParser.action("""{"action":null,"extra":"bad"}""") }
        assertThrows(Exception::class.java) { IntentResponseParser.action("""{"action":{"id":"a","tool":"set_alarm","args":{"time":8},"label":"Alarm"}}""") }
        assertThrows(Exception::class.java) { IntentResponseParser.action("not json") }
        assertThrows(IllegalArgumentException::class.java) { IntentResponseParser.action(" ".repeat(8193)) }
    }

    @Test fun `request rejects oversized payload even before network`() {
        assertThrows(IllegalArgumentException::class.java) { IntentRequestFactory.body("x".repeat(8193)) }
    }
}
