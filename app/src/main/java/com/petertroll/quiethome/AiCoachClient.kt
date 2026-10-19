package com.petertroll.quiethome

import com.petertroll.quiethome.domain.AiProvider
import com.petertroll.quiethome.domain.AiSuggestionProtocol
import com.petertroll.quiethome.domain.AiReflectionProtocol
import com.petertroll.quiethome.domain.AiAggregateProtocol
import com.petertroll.quiethome.domain.AggregateKind
import com.petertroll.quiethome.domain.RewriteItem
import com.petertroll.quiethome.domain.TomorrowProposal
import com.petertroll.quiethome.domain.AssistantContext
import com.petertroll.quiethome.domain.SuggestedAction
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class AiCoachClient {
    fun refine(provider: AiProvider, apiKey: String, model: String, context: AssistantContext, candidates: List<SuggestedAction>): SuggestedAction {
        val supplied = AiSuggestionProtocol.suppliedCandidates(candidates)
        val prompt = AiSuggestionProtocol.request(context, supplied)
        return AiSuggestionProtocol.parseResponse(request(provider, apiKey, model, prompt), supplied)
    }

    fun refineProposal(provider: AiProvider, apiKey: String, model: String, proposal: TomorrowProposal): TomorrowProposal {
        val prompt = AiReflectionProtocol.request(proposal)
        return AiReflectionProtocol.parseResponse(request(provider, apiKey, model, prompt), proposal)
    }

    fun refineAggregate(provider: AiProvider, apiKey: String, model: String, kind: AggregateKind, items: List<RewriteItem>): List<RewriteItem> {
        val supplied = AiAggregateProtocol.normalizeItems(items)
        val prompt = AiAggregateProtocol.request(kind, supplied)
        return AiAggregateProtocol.parseResponse(request(provider, apiKey, model, prompt, AiTask.AGGREGATE), kind, supplied)
    }

    fun suggest(provider: AiProvider, apiKey: String, model: String, facts: String): String {
        val prompt = "Give exactly one concrete action in at most 18 words. No praise, shame, diagnosis, or invented facts. Facts: ${facts.take(500)}"
        return request(provider, apiKey, model, prompt).also { text ->
            require(text.length in 3..180 && !text.contains('\n')) { "Provider returned an invalid suggestion" }
        }
    }

    private fun request(provider: AiProvider, apiKey: String, model: String, prompt: String, task: AiTask = AiTask.SHORT): String {
        require(provider != AiProvider.NONE)
        require(apiKey.isNotBlank())
        val endpoint = when (provider) {
            AiProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
            AiProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            AiProvider.NONE -> error("AI is disabled")
        }
        val body = AiRequestFactory.chatCompletionsBody(provider, model, prompt, task)
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
            val maxVisibleChars = if (task == AiTask.AGGREGATE) AiAggregateProtocol.MAX_RESPONSE_CHARS else 1_000
            require(text.length in 2..maxVisibleChars) { "Provider returned an invalid response" }
            return text
        } finally {
            connection.disconnect()
        }
    }
}

enum class AiTask { SHORT, AGGREGATE }

object AiRequestFactory {
    fun chatCompletionsBody(provider: AiProvider, model: String, prompt: String, task: AiTask = AiTask.SHORT): String {
        require(provider != AiProvider.NONE)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_completion_tokens", when {
                task == AiTask.AGGREGATE && provider == AiProvider.OPENAI -> 768
                task == AiTask.AGGREGATE && provider == AiProvider.GROQ -> 512
                provider == AiProvider.OPENAI -> 256
                else -> 80
            })
            .apply {
                // GPT-5 reasoning consumes completion tokens before producing visible text.
                // Give nano enough headroom and use its lowest reasoning effort for this short task.
                if (provider == AiProvider.OPENAI) put("reasoning_effort", "minimal")
                if (provider == AiProvider.GROQ) put("temperature", 0.2)
            }
            .toString()
    }
}
