package com.petertroll.quiethome

import com.petertroll.quiethome.domain.AiProvider
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class AiCoachClient {
    fun suggest(provider: AiProvider, apiKey: String, model: String, facts: String): String {
        require(provider != AiProvider.NONE)
        require(apiKey.isNotBlank())
        val endpoint = when (provider) {
            AiProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
            AiProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            AiProvider.NONE -> error("AI is disabled")
        }
        val prompt = "Give exactly one concrete action in at most 18 words. No praise, shame, diagnosis, or invented facts. Facts: ${facts.take(500)}"
        val body = AiRequestFactory.chatCompletionsBody(provider, model, prompt)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            require(connection.responseCode in 200..299) { "Provider rejected the request (${connection.responseCode})" }
            require(connection.contentType?.contains("application/json", true) == true) { "Provider returned non-JSON data" }
            val response = connection.inputStream.use { BoundedNetworkReader.readUtf8(it, 64_000) }
            val text = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            require(text.length in 3..180 && !text.contains('\n')) {
                "Provider returned an invalid suggestion"
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}

object AiRequestFactory {
    fun chatCompletionsBody(provider: AiProvider, model: String, prompt: String): String {
        require(provider != AiProvider.NONE)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_completion_tokens", if (provider == AiProvider.OPENAI) 256 else 80)
            .apply {
                // GPT-5 reasoning consumes completion tokens before producing visible text.
                // Give nano enough headroom and use its lowest reasoning effort for this short task.
                if (provider == AiProvider.OPENAI) put("reasoning_effort", "minimal")
                if (provider == AiProvider.GROQ) put("temperature", 0.2)
            }
            .toString()
    }
}
