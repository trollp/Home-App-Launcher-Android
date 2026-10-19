package com.petertroll.quiethome.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ContextAssistantTest {
    private val today = LocalDate.of(2026, 10, 19).toEpochDay()
    private fun routine(id: String = "read") = Routine(
        id = id,
        name = "Reading",
        normalAction = "Read for 20 minutes",
        smallerAction = "Read one page",
        weeklyTarget = 4,
        completedEpochDays = setOf(today - 1),
    )

    @Test fun `routine collection is generic constrained and history bounded`() {
        val sanitized = RoutineCollection.sanitize((1..7).map {
            Routine("id-$it", " Routine $it ", "Normal", "Small", 99, true, (1L..500L).toSet())
        })
        assertEquals(5, sanitized.size)
        assertEquals(7, sanitized.first().weeklyTarget)
        assertEquals(370, sanitized.first().completedEpochDays.size)
        assertFalse(LauncherPreferences().routines.any { it.name.contains("gym", true) })
    }

    @Test fun `legacy single habit migrates once preserving completion history`() {
        val old = """{"habitName":"Call family","routineWeeklyTarget":2,"habitDoneEpochDay":20000,"routineCompletedEpochDays":[19998,19999]}"""
        val decoded = PreferencesCodec.decode(old)
        assertEquals(1, decoded.routines.size)
        assertEquals("Call family", decoded.routines.single().name)
        assertEquals(setOf(19998L, 19999L, 20000L), decoded.routines.single().completedEpochDays)
        assertEquals(decoded, PreferencesCodec.decode(PreferencesCodec.encode(decoded)))
    }

    @Test fun `fresh state has no privileged default routine`() {
        assertTrue(PreferencesCodec.decode("{}").routines.isEmpty())
        assertTrue(LauncherPreferences().routines.isEmpty())
    }

    @Test fun `assistant state round trips with bounded local history`() {
        val value = LauncherPreferences(
            routines = listOf(routine()), capacity = Capacity.HIGH,
            feedback = listOf(FeedbackEvent("read", DayPhase.EVENING, FeedbackKind.DONE, today)),
            reflections = listOf(ReflectionEvent(today, ReflectionKind.AS_PLANNED)),
            learningEnabled = false, proactivity = Proactivity.CONTEXT_CHANGES,
            suppressedRoutineDays = mapOf("read" to today), laterUntilEpochMillis = 1234,
            tomorrowProposal = TomorrowProposal(today + 1, "read", "Read one page", true),
        )
        assertEquals(value, PreferencesCodec.decode(PreferencesCodec.encode(value)))
    }

    @Test fun `local candidate policy respects suppression capacity and feedback modestly`() {
        val other = routine("walk").copy(name = "Walking", normalAction = "Walk 20 minutes", smallerAction = "Walk 5 minutes")
        val context = AssistantContext(
            epochDay = today, localHour = 18, dayPhase = DayPhase.EVENING,
            weatherSummary = "8°C, rain later", weatherTiming = "Rain near 20:00",
            nextEventMinutes = 45, capacity = Capacity.LOW,
            routines = listOf(routine(), other), feedback = listOf(
                FeedbackEvent("read", DayPhase.EVENING, FeedbackKind.DEFERRED, today - 1),
                FeedbackEvent("read", DayPhase.EVENING, FeedbackKind.DEFERRED, today - 2),
                FeedbackEvent("walk", DayPhase.EVENING, FeedbackKind.DONE, today - 1),
            ), suppressedRoutineIds = setOf("read"), learningEnabled = true,
        )
        val candidates = AssistantPolicy.candidates(context)
        assertTrue(candidates.all { it.routineId != "read" })
        assertTrue(candidates.first().isSmaller)
        assertTrue(candidates.all { it.reason.isNotBlank() && it.durationMinutes in 1..240 })
        assertEquals("walk:small", AssistantPolicy.suggest(context).candidateId)
    }

    @Test fun `feedback claims patterns only with enough evidence across two weeks`() {
        val oneWeek = (1L..3L).map { FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.SMALLER, today - it) }
        assertTrue(FeedbackPolicy.learnedPattern(oneWeek, "read", DayPhase.MORNING) == null)
        val enough = oneWeek + FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.SMALLER, today - 15)
        assertNotNull(FeedbackPolicy.learnedPattern(enough, "read", DayPhase.MORNING))
        assertTrue(FeedbackPolicy.bound((1L..500L).map { FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DONE, it) }).size <= 200)
    }

    @Test fun `quick controls validate candidate ids and done can be undone`() {
        val prefs = LauncherPreferences(routines = listOf(routine()))
        val candidate = AssistantPolicy.candidates(AssistantContext.basic(today, 9, prefs.routines)).first()
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.done(prefs, "made-up", listOf(candidate), today) }
        val done = AssistantActions.done(prefs, candidate.candidateId, listOf(candidate), today)
        assertTrue(today in done.routines.single().completedEpochDays)
        assertEquals(prefs, AssistantActions.undoDone(done, candidate.candidateId, listOf(candidate), today, DayPhase.MORNING))
    }

    @Test fun `completed routines cannot be completed again or erased by undo`() {
        val completed = routine().copy(completedEpochDays = routine().completedEpochDays + today)
        val prefs = LauncherPreferences(routines = listOf(completed))
        assertTrue(AssistantPolicy.candidates(AssistantContext.basic(today, 9, prefs.routines)).isEmpty())

        val stale = SuggestedAction(
            "read:normal", "read", "Read for 20 minutes", "Already complete.", 20,
            ActionCategory.ROUTINE, LocalCommand.COMPLETE_ROUTINE,
        )
        assertNull(AssistantActions.smallerOrNull(stale.candidateId, emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            AssistantActions.done(prefs, stale.candidateId, listOf(stale), today)
        }
        assertTrue(today in prefs.routines.single().completedEpochDays)
    }

    @Test fun `weekly review uses thresholds source counts and reversible proposal`() {
        val completions = setOf(today - 1, today - 8, today - 15)
        val prefs = LauncherPreferences(routines = listOf(routine().copy(completedEpochDays = completions)))
        val review = WeeklyReviewPolicy.review(prefs, today)
        assertTrue(review.observations.size <= 3)
        assertTrue(review.observations.all { it.sourceCount >= 3 })
        review.proposal?.let { proposal -> assertEquals(prefs, proposal.revert(proposal.apply(prefs))) }
    }

    @Test fun `reflection is bounded and produces one unapplied tomorrow proposal`() {
        val reflections = (1L..100L).map { ReflectionEvent(it, ReflectionKind.TOO_MUCH) }
        assertTrue(ReflectionPolicy.bound(reflections).size <= 60)
        val proposal = ReflectionPolicy.tomorrowProposal(ReflectionKind.SMALLER_WORKED, listOf(routine()), today)
        assertNotNull(proposal)
        assertEquals(today + 1, proposal!!.epochDay)
        assertTrue(proposal.smaller)
    }

    @Test fun `tomorrow proposal bounds valid local action titles to safe AI title length`() {
        val longAction = "Read " + "a".repeat(95)
        val longRoutine = routine().copy(normalAction = longAction, smallerAction = longAction)

        val proposal = ReflectionPolicy.tomorrowProposal(ReflectionKind.AS_PLANNED, listOf(longRoutine), today)

        assertNotNull(proposal)
        assertEquals(80, proposal!!.title.length)
        assertEquals(longAction.take(80), proposal.title)
        val suggestion = AssistantPolicy.suggest(
            AssistantContext.basic(today + 1, 9, listOf(longRoutine)).copy(tomorrowProposal = proposal),
        )
        assertEquals(proposal.title, suggestion.title)
    }

    @Test fun `malformed persisted tomorrow proposal is discarded without resetting preferences`() {
        val decoded = PreferencesCodec.decode(
            """{"capacity":"HIGH","tomorrowProposal":{"epochDay":$today,"routineId":"read","title":"https://bad.example","smaller":false}}""",
        )

        assertEquals(Capacity.HIGH, decoded.capacity)
        assertNull(decoded.tomorrowProposal)

        val longTitle = "Read " + "b".repeat(95)
        val bounded = PreferencesCodec.decode(
            """{"tomorrowProposal":{"epochDay":$today,"routineId":"read","title":"$longTitle","smaller":false}}""",
        ).tomorrowProposal
        assertNotNull(bounded)
        assertEquals(longTitle.take(80), bounded!!.title)
    }

    @Test fun `today tomorrow proposal overrides matching candidate end to end while stale is harmless`() {
        val prefs = LauncherPreferences(
            routines = listOf(routine(), routine("walk").copy(name = "Walking")),
            capacity = Capacity.HIGH,
            tomorrowProposal = TomorrowProposal(today, "read", "Read the gentler chapter", true),
        )
        val context = AssistantContext.basic(today, 9, prefs.routines).copy(
            capacity = prefs.capacity,
            tomorrowProposal = prefs.tomorrowProposal,
        )
        val suggestion = AssistantPolicy.suggest(context)
        assertEquals("read", suggestion.routineId)
        assertTrue(suggestion.isSmaller)
        assertEquals("Read the gentler chapter", suggestion.title)

        val stale = context.copy(tomorrowProposal = prefs.tomorrowProposal!!.copy(epochDay = today - 1))
        assertNotEquals("Read the gentler chapter", AssistantPolicy.suggest(stale).title)
    }

    @Test fun `context signature changes only for bounded relevant context and policy is persisted`() {
        val base = AssistantContext.basic(today, 9, listOf(routine()))
        val signature = ProactivityPolicy.contextSignature(base)
        assertEquals(signature, ProactivityPolicy.contextSignature(base.copy(weatherSummary = "arbitrary prose")))
        assertNotEquals(signature, ProactivityPolicy.contextSignature(base.copy(capacity = Capacity.LOW)))
        assertNotEquals(signature, ProactivityPolicy.contextSignature(base.copy(nextEventMinutes = 20)))
        assertFalse(ProactivityPolicy.shouldShow(Proactivity.CONTEXT_CHANGES, today, today, signature, signature))
        assertTrue(ProactivityPolicy.shouldShow(Proactivity.CONTEXT_CHANGES, today, today, signature, "old"))
        assertFalse(ProactivityPolicy.shouldShow(Proactivity.ONCE_DAILY, today, today, signature, "old"))
        assertTrue(ProactivityPolicy.shouldShow(Proactivity.ONCE_DAILY, today, today - 1, signature, signature))

        val value = LauncherPreferences(lastProactiveContextSignature = signature)
        assertEquals(signature, PreferencesCodec.decode(PreferencesCodec.encode(value)).lastProactiveContextSignature)
    }

    @Test fun `proactivity off is never overridden by a launcher session flag`() {
        assertFalse(ProactivityPolicy.shouldShowInSession(Proactivity.OFF, true, today, today - 1, "new", "old"))
        assertTrue(ProactivityPolicy.shouldShowInSession(Proactivity.ONCE_DAILY, true, today, today, "same", "same"))
    }

    @Test fun `ranking explanation appears only for threshold qualified feedback that affects score`() {
        val evidence = listOf(
            FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DONE, today - 15),
            FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DONE, today - 2),
            FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DONE, today - 1),
        )
        val context = AssistantContext.basic(today, 9, listOf(routine())).copy(feedback = evidence)
        val explained = AssistantPolicy.candidates(context).first()
        assertTrue(explained.reason.contains("3 morning feedback entries"))

        val belowThreshold = AssistantPolicy.candidates(context.copy(feedback = evidence.take(2))).first()
        assertFalse(belowThreshold.reason.contains("feedback entries"))
        val learningPaused = AssistantPolicy.candidates(context.copy(learningEnabled = false)).first()
        assertFalse(learningPaused.reason.contains("feedback entries"))
    }

    @Test fun `undo done removes only its completion and feedback without overwriting concurrent preferences`() {
        val prefs = LauncherPreferences(routines = listOf(routine()), capacity = Capacity.NORMAL)
        val candidates = AssistantPolicy.candidates(AssistantContext.basic(today, 9, prefs.routines))
        val action = candidates.first { !it.isSmaller }
        val done = AssistantActions.done(prefs, action.candidateId, candidates, today).copy(
            capacity = Capacity.LOW,
            feedback = listOf(
                FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DONE, today),
                FeedbackEvent("read", DayPhase.MORNING, FeedbackKind.DEFERRED, today),
            ),
        )
        val undone = AssistantActions.undoDone(done, action.candidateId, candidates, today, DayPhase.MORNING)
        assertEquals(Capacity.LOW, undone.capacity)
        assertFalse(today in undone.routines.single().completedEpochDays)
        assertEquals(listOf(FeedbackKind.DEFERRED), undone.feedback.map { it.kind })
    }
}
