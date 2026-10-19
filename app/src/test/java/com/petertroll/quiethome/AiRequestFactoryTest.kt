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
        assertEquals(80, body.getInt("max_completion_tokens"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `Groq request retains deterministic temperature`() {
        val body = JSONObject(AiRequestFactory.chatCompletionsBody(
            provider = AiProvider.GROQ,
            model = "openai/gpt-oss-20b",
            prompt = "Give one action",
        ))

        assertTrue(body.has("temperature"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
    }
}
