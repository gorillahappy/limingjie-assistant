package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthEventChoiceFallbackGateTest {
    private val event = EventResource("1103", "这里是公会管理协会！请问要找什么样的伙伴？", area = 1,
        choices = listOf(EventChoiceResource("11031", "减益", slot = 1), EventChoiceResource("11032", "干扰", slot = 2)))
    private val planner = LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
        LabyrinthTeamScoringConfig(attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))))

    private fun frame(now: Long, request: Long = now / 2_000 + 1): LabyrinthEntryFrameResult {
        val observation = LabyrinthEventChoiceObservation(event, event.choices.mapIndexed { index, choice ->
            LabyrinthEventChoiceVisual(choice, EntryPixelRect(200 + index * 600, 750, 300, 150), 0.8, 0.6)
        }, "减益 干扰", 0.94, 0.03, 20, false, LabyrinthEventTextTrustFailure.RIVAL_MARGIN, 0.98, 0.92)
        val crop = EntryPixelRect(150, 100, 1500, 700)
        return LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(LabyrinthEntryPageState.EVENT_CHOICE, 0.94, emptyMap(), LabyrinthAnchorScores(emptyMap()), "event"),
            emptyList(), 0, eventChoiceSelection = observation,
            eventOcrDiagnostics = LabyrinthEventOcrDiagnostics("CANDIDATE_MATCH", cropRect = crop,
                fingerprint = "1234", completeLayoutCount = 2, candidateEventId = event.id,
                buttonConfidencesByOptionCount = mapOf(2 to listOf(0.8, 0.8)),
                cache = LabyrinthEventOcrCacheDiagnostics("HIT", now % 2_000, 0, crop, request,
                    null, null, request, 500, 30, "TEXT", 5_000)),
            frameWidth = 1920, frameHeight = 1080)
    }

    private fun decision(frame: LabyrinthEntryFrameResult, owned: Set<String> = emptySet()) = planner.decide(
        requireNotNull(frame.eventChoiceSelection), owned, LabyrinthRoleDecisionContext(0), emptyMap())

    private fun advance(gate: LabyrinthEventChoiceFallbackGate, through: Long = 59_000,
                        transform: (LabyrinthEntryFrameResult) -> LabyrinthEntryFrameResult = { it }) {
        for (now in 0L..through step 1_000) {
            val f = transform(frame(now))
            assertFalse("early at $now", gate.evaluate(f, decision(f), now) is LabyrinthEventFallbackResult.Allow)
        }
    }

    @Test fun `normal recognition remains first choice even on the fallback deadline`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        val good = frame(0).let { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.copy(trusted = true)) }
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(good, decision(good), 0))
        advance(gate)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(good, decision(good), 60_000))
        assertFalse(gate.markDispatched(60_000))
    }

    @Test fun `only delayed fresh independent consistent reads authorize the original recommended choice`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        advance(gate)
        val early = frame(59_999)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(early, decision(early), 59_999))
        val f = frame(60_000)
        val normal = decision(f) as LabyrinthEventChoiceDecision.Select
        assertFalse(normal.actionSafe)
        val result = gate.evaluate(f, normal, 60_000) as LabyrinthEventFallbackResult.Allow
        assertTrue(result.decision.actionSafe)
        assertTrue(result.decision.degraded)
        assertEquals(normal.choiceId, result.decision.choiceId)
        assertEquals(normal.utility, result.decision.utility, 0.0)
        assertEquals(normal.buttonRect, result.decision.buttonRect)
        assertFalse(f.eventChoiceSelection!!.trusted) // Never relabel the OCR itself as trusted.
        println("EVENT_FALLBACK waitMillis=60000 choice=${result.decision.choiceId} degraded=true originalTrusted=false")
    }

    @Test fun `cached frames and a completion before the deadline cannot authorize fallback`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        advance(gate, 60_000) { f -> f.copy(eventOcrDiagnostics = f.eventOcrDiagnostics!!.let {
            it.copy(cache = it.cache!!.copy(cachedRequestId = 1, lastCompletedRequestId = 1)) }) }
        val freshGate = LabyrinthEventChoiceFallbackGate()
        advance(freshGate)
        val f = frame(60_000).let { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d ->
            d.copy(cache = d.cache!!.copy(ageMillis = 1_000)) }) }
        assertEquals(LabyrinthEventFallbackResult.Normal, freshGate.evaluate(f, decision(f), 60_000))
        val fresh = frame(60_001, request = 32).let { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d ->
            d.copy(cache = d.cache!!.copy(ageMillis = 0)) }) }
        assertTrue(freshGate.evaluate(fresh, decision(fresh), 60_001) is LabyrinthEventFallbackResult.Allow)
    }

    @Test fun `low score instability ties unknown metadata and other rejection causes never qualify`() {
        val mutations: List<(LabyrinthEventChoiceObservation) -> LabyrinthEventChoiceObservation> = listOf(
            { it.copy(textTrustFailure = LabyrinthEventTextTrustFailure.LOW_SCORE) },
            { it.copy(textTrustFailure = LabyrinthEventTextTrustFailure.UNSTABLE) },
            { it.copy(textTrustFailure = LabyrinthEventTextTrustFailure.UNKNOWN) },
            { it.copy(textTrustFailure = LabyrinthEventTextTrustFailure.NO_CANDIDATE) },
            { it.copy(confidence = 0.899) }, { it.copy(titleScore = 0.899) },
            { it.copy(choiceScore = 0.749) }, { it.copy(stableFrames = 1) },
            { it.copy(rivalMargin = 0.0) }, { it.copy(rivalMargin = 0.0149) },
            { it.copy(rivalMargin = 0.045) }, { it.copy(confidence = Double.NaN) },
        )
        for (mutate in mutations) advance(LabyrinthEventChoiceFallbackGate(), 60_000) {
            it.copy(eventChoiceSelection = mutate(it.eventChoiceSelection!!))
        }
    }

    @Test fun `layout missing grey buttons overlap out of frame and stale diagnostics never qualify`() {
        val mutations: List<(LabyrinthEntryFrameResult) -> LabyrinthEntryFrameResult> = listOf(
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(completeLayoutCount = null)) },
            { it.copy(eventOcrDiagnostics = null) },
            { it.copy(frameWidth = 900) },
            { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.let { o -> o.copy(choices =
                o.choices.map { v -> v.copy(enabledConfidence = 0.1) }) }) },
            { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.let { o -> o.copy(choices =
                o.choices.map { v -> v.copy(buttonConfidence = 0.2) }) }) },
            { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.let { o -> o.copy(choices =
                o.choices.map { v -> v.copy(buttonRect = o.choices.first().buttonRect) }) }) },
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(candidateEventId = "1102")) },
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(fingerprint = null)) },
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d -> d.copy(cache = d.cache!!.copy(status = "EXPIRED")) }) },
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d -> d.copy(cache = d.cache!!.copy(lastResultStatus = "EMPTY_TEXT")) }) },
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d -> d.copy(cache = d.cache!!.copy(lastCompletedRequestId = -1)) }) },
        )
        for (mutate in mutations) advance(LabyrinthEventChoiceFallbackGate(), 60_000, mutate)
    }

    @Test fun `missing owned profiles rejected choices and a missing planner remain blocked`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        for (now in 0L..60_000 step 1_000) {
            val f = frame(now)
            val missing = decision(f, setOf("unknown-owned-role")) as LabyrinthEventChoiceDecision.Select
            assertFalse(missing.nonTextSafetyPassed)
            assertFalse(gate.evaluate(f, missing, now) is LabyrinthEventFallbackResult.Allow)
        }
        val f = frame(61_000)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(f, null, 61_000))
        val rejected = planner.decide(f.eventChoiceSelection!!, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap(),
            rejectedChoiceIds = event.choices.map { it.id }.toSet())
        assertTrue(rejected is LabyrinthEventChoiceDecision.Wait)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(f, rejected, 62_000))
    }

    @Test fun `candidate fingerprint and geometry changes restart the normal wait`() {
        for (change in listOf<(LabyrinthEntryFrameResult) -> LabyrinthEntryFrameResult>(
            { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(fingerprint = "ffff0000")) },
            { it.copy(frameWidth = 2560) },
            { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.let { o -> o.copy(event = o.event.copy(id = "new")) },
                eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(candidateEventId = "new")) },
        )) {
            val gate = LabyrinthEventChoiceFallbackGate()
            advance(gate, 58_000)
            val changed = change(frame(59_000))
            assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(changed, decision(changed), 59_000))
            val later = change(frame(60_000))
            assertFalse(gate.evaluate(later, decision(later), 60_000) is LabyrinthEventFallbackResult.Allow)
        }
    }

    @Test fun `short pending OCR gaps do not grant permission or erase consistent earlier reads`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        advance(gate, 58_000)
        val pending = frame(59_000).let { it.copy(eventChoiceSelection = null,
            eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d ->
                d.copy(status = "WAITING_OCR", cache = d.cache!!.copy(pendingRequestId = 31)) }) }
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(pending, null, 59_000))
        val fresh = frame(60_000)
        assertTrue(gate.evaluate(fresh, decision(fresh), 60_000) is LabyrinthEventFallbackResult.Allow)
    }

    private fun dispatchedGate(): LabyrinthEventChoiceFallbackGate = LabyrinthEventChoiceFallbackGate().also {
        advance(it)
        val f = frame(60_000)
        assertTrue(it.evaluate(f, decision(f), 60_000) is LabyrinthEventFallbackResult.Allow)
        assertTrue(it.markDispatched(60_000))
        assertFalse(it.markDispatched(60_001))
    }

    @Test fun `one dispatch stays consumed across unknown frames and normal recognition recovery then stops`() {
        val gate = dispatchedGate()
        val unknown = frame(61_000).let { it.copy(observation = it.observation.copy(state = LabyrinthEntryPageState.UNKNOWN),
            eventChoiceSelection = null) }
        assertTrue(gate.evaluate(unknown, null, 61_000) is LabyrinthEventFallbackResult.Wait)
        val normal = frame(62_000).let { it.copy(eventChoiceSelection = it.eventChoiceSelection!!.copy(trusted = true)) }
        assertTrue(gate.evaluate(normal, decision(normal), 62_000) is LabyrinthEventFallbackResult.Wait)
        val flipped = normal.copy(eventChoiceSelection = normal.eventChoiceSelection!!.let { o ->
            o.copy(event = o.event.copy(id = "flipped"), textTrustFailure = LabyrinthEventTextTrustFailure.NONE) },
            eventOcrDiagnostics = normal.eventOcrDiagnostics!!.copy(status = "TRUSTED_MATCH", candidateEventId = "flipped"))
        assertTrue(gate.evaluate(flipped, decision(flipped), 63_000) is LabyrinthEventFallbackResult.Wait)
        assertTrue(gate.evaluate(normal, decision(normal), 69_999) is LabyrinthEventFallbackResult.Wait)
        assertTrue(gate.evaluate(normal, decision(normal), 70_000) is LabyrinthEventFallbackResult.Stop)
    }

    @Test fun `only two strong successor frames confirm progress and rearm a later visit`() {
        val gate = dispatchedGate()
        val successor = frame(61_000).let { it.copy(observation = it.observation.copy(state = LabyrinthEntryPageState.NODE_SELECTION),
            eventChoiceSelection = null) }
        assertTrue(gate.evaluate(successor, null, 61_000) is LabyrinthEventFallbackResult.Wait)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(successor, null, 62_000))
        for (now in 63_000L..122_000 step 1_000) {
            val f = frame(now)
            assertFalse(gate.evaluate(f, decision(f), now) is LabyrinthEventFallbackResult.Allow)
        }
        val fresh = frame(123_000, 100).let { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.let { d ->
            d.copy(cache = d.cache!!.copy(ageMillis = 0)) }) }
        assertTrue(gate.evaluate(fresh, decision(fresh), 123_000) is LabyrinthEventFallbackResult.Allow)
    }

    @Test fun `a weak or single successor observation cannot rearm fallback`() {
        val gate = dispatchedGate()
        val f = frame(61_000).let { it.copy(observation = it.observation.copy(state = LabyrinthEntryPageState.NODE_SELECTION, confidence = 0.79),
            eventChoiceSelection = null) }
        assertTrue(gate.evaluate(f, null, 61_000) is LabyrinthEventFallbackResult.Wait)
        assertTrue(gate.evaluate(f, null, 62_000) is LabyrinthEventFallbackResult.Wait)
        assertTrue(gate.evaluate(frame(70_000), decision(frame(70_000)), 70_000) is LabyrinthEventFallbackResult.Stop)
    }

    @Test fun `battle shop and a genuinely new normally recognized chained event confirm progress`() {
        for (page in listOf(LabyrinthEntryPageState.BATTLE_CHALLENGE, LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS, LabyrinthEntryPageState.SHOP)) {
            val gate = dispatchedGate()
            val next = frame(62_000).let { it.copy(observation = it.observation.copy(state = page), eventChoiceSelection = null) }
            assertTrue(gate.evaluate(next, null, 62_000) is LabyrinthEventFallbackResult.Wait)
            assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(next, null, 63_000))
        }
        val gate = dispatchedGate()
        val next = frame(62_000).let { f -> f.copy(eventChoiceSelection = f.eventChoiceSelection!!.let { o ->
            o.copy(event = o.event.copy(id = "next"), trusted = true, textTrustFailure = LabyrinthEventTextTrustFailure.NONE)
        }, eventOcrDiagnostics = f.eventOcrDiagnostics!!.copy(status = "TRUSTED_MATCH", candidateEventId = "next", fingerprint = "ffff0000")) }
        assertTrue(gate.evaluate(next, decision(next), 62_000) is LabyrinthEventFallbackResult.Wait)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(next, decision(next), 63_000))
    }

    @Test fun `clock rollback and changing evidence cannot extend waiting indefinitely`() {
        val changing = LabyrinthEventChoiceFallbackGate()
        for (now in 0L..119_000 step 1_000) {
            val changed = frame(now).let { it.copy(eventOcrDiagnostics = it.eventOcrDiagnostics!!.copy(
                fingerprint = if (now % 2_000 == 0L) "1234" else "ffff0000")) }
            assertFalse(changing.evaluate(changed, decision(changed), now) is LabyrinthEventFallbackResult.Allow)
        }
        assertTrue(changing.evaluate(frame(120_000), decision(frame(120_000)), 120_000) is LabyrinthEventFallbackResult.Stop)
        val gate = LabyrinthEventChoiceFallbackGate()
        advance(gate)
        val rolled = frame(1_000)
        assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(rolled, decision(rolled), 1_000))
        val f = frame(121_000)
        assertTrue(gate.evaluate(f, decision(f), 121_000) is LabyrinthEventFallbackResult.Stop)
        val consumed = dispatchedGate()
        assertTrue(consumed.evaluate(rolled, decision(rolled), 59_000) is LabyrinthEventFallbackResult.Stop)
    }

    @Test fun `unknown pages never grant fallback permission even with apparently good OCR metadata`() {
        val gate = LabyrinthEventChoiceFallbackGate()
        for (now in 0L..120_000 step 1_000) {
            val f = frame(now).let { it.copy(observation = it.observation.copy(state = LabyrinthEntryPageState.UNKNOWN)) }
            assertEquals(LabyrinthEventFallbackResult.Normal, gate.evaluate(f, decision(f), now))
            assertFalse(gate.markDispatched(now))
        }
    }
}
