package com.petertroll.quiethome

import com.petertroll.quiethome.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantProtocolTest {
    private val candidate = SuggestedAction(
        candidateId = "read:normal", routineId = "read", title = "Read for 20 minutes",
        reason = "Reading is available this evening.", durationMinutes = 20,
        category = ActionCategory.ROUTINE, command = LocalCommand.COMPLETE_ROUTINE,
    )

    @Test fun `AI payload exposes bounded facts and event timing but never calendar title`() {
        val context = AssistantContext(
            epochDay = 20_000, localHour = 8, dayPhase = DayPhase.MORNING,
            weatherSummary = "7°C and cloudy", weatherTiming = "Rain after 18:00",
            nextEventMinutes = 25, capacity = Capacity.NORMAL,
            routines = listOf(Routine("read", "Reading", "Read", "One page", 3)),
            feedback = emptyList(), suppressedRoutineIds = emptySet(), learningEnabled = true,
        )
        val payload = AiSuggestionProtocol.request(context, listOf(candidate))
        assertTrue(payload.contains("25"))
        assertTrue(payload.contains("read:normal"))
        assertFalse(payload.contains("Board meeting"))
        assertFalse(payload.contains("title stays local"))
        assertTrue(payload.length < 4000)
    }

    @Test fun `paused learning omits stored feedback from AI payload`() {
        val feedback = listOf(FeedbackEvent("private-routine", DayPhase.EVENING, FeedbackKind.DEFERRED, 19_999))
        val paused = AssistantContext.basic(20_000, 18, emptyList()).copy(
            feedback = feedback,
            learningEnabled = false,
        )
        val payload = AiSuggestionProtocol.request(paused, listOf(candidate))
        assertFalse(payload.contains("private-routine"))
        assertEquals(0, JSONObject(payload).getJSONObject("context").getJSONArray("recentFeedback").length())
    }

    @Test fun `AI may only choose known candidate and refine safe text`() {
        val valid = AiSuggestionProtocol.parseResponse(
            """{"candidateId":"read:normal","title":"Read quietly","reason":"A short calm option fits now."}""",
            listOf(candidate),
        )
        assertEquals(LocalCommand.COMPLETE_ROUTINE, valid.command)
        assertEquals("Read quietly", valid.title)
        assertThrows(IllegalArgumentException::class.java) {
            AiSuggestionProtocol.parseResponse("""{"candidateId":"invented","title":"Do it","reason":"Why"}""", listOf(candidate))
        }
        listOf(
            """{"candidateId":"read:normal","title":"https://bad.example","reason":"Why"}""",
            """{"candidateId":"read:normal","title":"Line\nbreak","reason":"Why"}""",
            """{"candidateId":"read:normal","title":"Do it","reason":"javascript:bad"}""",
            """{"candidateId":"read:normal","title":"Do it","reason":"Why","url":"https://bad"}""",
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { AiSuggestionProtocol.parseResponse(bad, listOf(candidate)) } }
    }

    @Test fun `AI rejects a candidate omitted from the exact transmitted subset`() {
        val candidates = (0 until 10).map { candidate.copy(candidateId = "candidate-$it") }
        val supplied = AiSuggestionProtocol.suppliedCandidates(candidates)
        val payloadIds = JSONObject(
            AiSuggestionProtocol.request(AssistantContext.basic(20_000, 9, emptyList()), candidates),
        ).getJSONArray("candidates")
        assertEquals(supplied.map { it.candidateId }, (0 until payloadIds.length()).map { payloadIds.getJSONObject(it).getString("candidateId") })
        assertThrows(IllegalArgumentException::class.java) {
            AiSuggestionProtocol.parseResponse(
                """{"candidateId":"candidate-9","title":"Do it","reason":"Fits now"}""",
                candidates,
            )
        }
    }

    @Test fun `AI request asks for strict JSON selection only`() {
        val prompt = AiSuggestionProtocol.systemPrompt()
        assertTrue(prompt.contains("candidateId"))
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("cannot", true))
    }

    @Test fun `provider request behavior remains compatible`() {
        val openAi = JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.OPENAI, "gpt-5-nano", "x"))
        assertFalse(openAi.has("temperature"))
        assertTrue(openAi.getInt("max_completion_tokens") >= 256)
        assertEquals("minimal", openAi.getString("reasoning_effort"))
        val groq = JSONObject(AiRequestFactory.chatCompletionsBody(AiProvider.GROQ, "openai/gpt-oss-20b", "x"))
        assertEquals(0.2, groq.getDouble("temperature"), 0.0)
    }

    @Test fun `worst case suggestion request is valid bounded JSON and omits completion recency`() {
        val huge = "x".repeat(10_000)
        val routines = (1..20).map { Routine(huge + it, huge, huge, huge, 7, completedEpochDays = (1L..500L).toSet()) }
        val context = AssistantContext(
            20_000, 23, DayPhase.NIGHT, huge, huge, Int.MAX_VALUE, Capacity.HIGH,
            routines, (1..100).map { FeedbackEvent(huge, DayPhase.NIGHT, FeedbackKind.DONE, it.toLong()) },
            emptySet(), true,
        )
        val request = AiSuggestionProtocol.request(context, List(20) { candidate.copy(candidateId = "$it:$huge", title = huge, reason = huge) })
        JSONObject(request)
        assertTrue(request.length <= AiSuggestionProtocol.MAX_REQUEST_CHARS)
        assertFalse(request.contains("recentCompletionDaysAgo"))
    }

    @Test fun `reflection refinement returns only a validated tomorrow proposal title`() {
        val proposal = TomorrowProposal(20_001, "read", "Read one page", true)
        val request = AiReflectionProtocol.request(proposal)
        assertEquals("REFLECTION", JSONObject(request).getString("kind"))
        val refined = AiReflectionProtocol.parseResponse("""{"title":"Read a quiet page"}""", proposal)
        assertEquals(proposal.copy(title = "Read a quiet page"), refined)
        listOf("""{"title":"https://bad"}""", """{"title":"line\nbreak"}""", """{"title":"Fine","command":"done"}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { AiReflectionProtocol.parseResponse(it, proposal) }
        }
    }

    @Test fun `aggregate refinement is complete kind bound safe and preserves item identities`() {
        val briefing = listOf(
            RewriteItem("weather", "Stockholm: rain after 18:00."),
            RewriteItem("event", "Next event is in 25 minutes."),
            RewriteItem("routines", "Reading 1/3 · Walking 2/4."),
        )
        val request = AiAggregateProtocol.request(AggregateKind.BRIEFING, briefing)
        assertEquals("BRIEFING", JSONObject(request).getString("kind"))
        assertFalse(request.contains("Board meeting"))
        val raw = """{"kind":"BRIEFING","items":[{"id":"weather","text":"Rain is expected after 18:00."},{"id":"event","text":"Your next event is in 25 minutes."},{"id":"routines","text":"Reading is 1/3; Walking is 2/4."}]}"""
        val result = AiAggregateProtocol.parseResponse(raw, AggregateKind.BRIEFING, briefing)
        assertEquals(briefing.map { it.id }, result.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            AiAggregateProtocol.parseResponse(raw.replace("routines", "unknown"), AggregateKind.BRIEFING, briefing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiAggregateProtocol.parseResponse(raw.replace("Rain is expected", "https://example.com"), AggregateKind.BRIEFING, briefing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiAggregateProtocol.parseResponse(raw.replace("BRIEFING", "WEEKLY"), AggregateKind.BRIEFING, briefing)
        }
    }

    @Test fun `aggregate uses one exact bounded subset for request and response validation`() {
        val source = (0 until 10).map { RewriteItem("item-$it", "Fact $it remains exact.") }
        val normalized = AiAggregateProtocol.normalizeItems(source)
        val payload = JSONObject(AiAggregateProtocol.request(AggregateKind.WEEKLY, source)).getJSONArray("items")
        assertEquals(normalized.map { it.id }, (0 until payload.length()).map { payload.getJSONObject(it).getString("id") })
        val raw = JSONObject().put("kind", "WEEKLY").put("items", org.json.JSONArray(normalized.map {
            JSONObject().put("id", it.id).put("text", it.text)
        })).toString()
        assertEquals(normalized, AiAggregateProtocol.parseResponse(raw, AggregateKind.WEEKLY, source))
    }

    @Test fun `complete four section five routine briefing round trips with exact numeric facts`() {
        val source = listOf(
            RewriteItem("weather", "Stockholm is 7 C; rain starts after 18:00."),
            RewriteItem("event", "The next event begins in 25 minutes."),
            RewriteItem("routines", "Reading 1/3; Walking 2/4; Music 3/5; Family 4/6; Rest 5/7."),
            RewriteItem("action", "Read for 20 minutes after 18:00; progress is 1/3."),
        )
        val response = JSONObject().put("kind", "BRIEFING").put("items", org.json.JSONArray(source.map {
            JSONObject().put("id", it.id).put("text", it.text)
        })).toString()

        assertTrue(response.length > 200)
        assertEquals(source, AiAggregateProtocol.parseResponse(response, AggregateKind.BRIEFING, source))
        val request = AiAggregateProtocol.request(AggregateKind.BRIEFING, source)
        listOf("7", "18", "00", "25", "1", "3", "2", "4", "5", "6", "20").forEach { assertTrue(request.contains(it)) }
    }
}
