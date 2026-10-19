package com.petertroll.quiethome

import com.petertroll.quiethome.domain.AiProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestFactoryTest {
    @Test fun `OpenAI GPT-5 request omits unsupported temperature`() {
        val body = JSONObject(AiRequestFactory.chatCompletionsBody(
            provider = AiProvider.OPENAI,
            model = "gpt-5-nano",
            prompt = "Give one action",
        ))

        assertEquals("gpt-5-nano", body.getString("model"))
        assertEquals(256, body.getInt("max_completion_tokens"))
        assertEquals("minimal", body.getString("reasoning_effort"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `Groq request retains deterministic temperature`() {
        val body = JSONObject(AiRequestFactory.chatCompletionsBody(
            provider = AiProvider.GROQ,
            model = "openai/gpt-oss-20b",
            prompt = "Give one action",
        ))

        assertEquals(80, body.getInt("max_completion_tokens"))
        assertFalse(body.has("reasoning_effort"))
        assertTrue(body.has("temperature"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
    }

    @Test fun `aggregate requests receive provider specific completion budgets without changing short defaults`() {
        val openAi = JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.OPENAI, "gpt-5-nano", "aggregate", AiTask.AGGREGATE))
        val groq = JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.GROQ, "model", "aggregate", AiTask.AGGREGATE))
        assertEquals(768, openAi.getInt("max_completion_tokens"))
        assertEquals(512, groq.getInt("max_completion_tokens"))
        assertFalse(openAi.has("temperature"))
        assertEquals("minimal", openAi.getString("reasoning_effort"))
        assertEquals(0.2, groq.getDouble("temperature"), 0.0)
        assertEquals(256, JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.OPENAI, "model", "short")).getInt("max_completion_tokens"))
        assertEquals(80, JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.GROQ, "model", "short")).getInt("max_completion_tokens"))
    }
}
