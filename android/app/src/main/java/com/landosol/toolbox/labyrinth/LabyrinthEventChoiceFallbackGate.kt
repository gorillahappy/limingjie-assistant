package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventTextTrustFailure

internal sealed interface LabyrinthEventFallbackResult {
    data object Normal : LabyrinthEventFallbackResult
    data class Allow(val decision: LabyrinthEventChoiceDecision.Select) : LabyrinthEventFallbackResult
    data class Wait(val reason: String) : LabyrinthEventFallbackResult
    data class Stop(val reason: String) : LabyrinthEventFallbackResult
}

/** Stateful, pure-JVM permission gate. A timeout alone never grants click permission. */
internal class LabyrinthEventChoiceFallbackGate {
    private data class Evidence(
        val eventId: String,
        val choiceId: String,
        val buttons: List<Pair<String, EntryPixelRect>>,
        val crop: EntryPixelRect,
        val width: Int,
        val height: Int,
    )

    private var blockedSince: Long? = null
    private var consistentSince: Long? = null
    private var lastSeenAt: Long? = null
    private var lastNow: Long? = null
    private var evidence: Evidence? = null
    private var fingerprint: Long? = null
    private var lastRequestId: Long? = null
    private var reads = 0
    private var permitIssued = false
    private var dispatchedAt: Long? = null
    private var exitIdentity: String? = null
    private var exitFrames = 0

    fun reset() {
        blockedSince = null
        lastNow = null
        dispatchedAt = null
        exitIdentity = null
        exitFrames = 0
        clearEvidence()
    }

    private fun clearEvidence() {
        consistentSince = null
        lastSeenAt = null
        evidence = null
        fingerprint = null
        lastRequestId = null
        reads = 0
        permitIssued = false
    }

    fun evaluate(
        frame: LabyrinthEntryFrameResult,
        normalDecision: LabyrinthEventChoiceDecision?,
        now: Long,
    ): LabyrinthEventFallbackResult {
        val page = frame.observation.state
        if (lastNow?.let { now < it } == true) {
            if (dispatchedAt != null) return LabyrinthEventFallbackResult.Stop("降级点击后时钟回退，停止并保留现场")
            reset()
        }
        lastNow = now
        dispatchedAt?.let { dispatched ->
            // UNKNOWN or an OCR candidate flip on the unchanged image is not proof of exit.
            if (now - dispatched >= OBSERVE_MILLIS) {
                return LabyrinthEventFallbackResult.Stop("事件降级选择后10秒未确认页面推进；停止，不再次尝试")
            }
            val successor = confirmedSuccessorIdentity(frame, normalDecision)
            if (successor != null) {
                exitFrames = if (exitIdentity == successor) exitFrames + 1 else 1
                exitIdentity = successor
                if (exitFrames >= 2) {
                    reset()
                    return LabyrinthEventFallbackResult.Normal
                }
            } else {
                exitFrames = 0
                exitIdentity = null
            }
            return LabyrinthEventFallbackResult.Wait("已尝试一次事件降级选择，观察页面是否推进")
        }
        if (page != LabyrinthEntryPageState.EVENT_CHOICE) {
            blockedSince = null
            clearEvidence()
            return LabyrinthEventFallbackResult.Normal
        }
        val decision = normalDecision as? LabyrinthEventChoiceDecision.Select
        // Normal recognition always wins, including a recovery on the fallback deadline frame.
        if (decision?.actionSafe == true) {
            blockedSince = null
            clearEvidence()
            return LabyrinthEventFallbackResult.Normal
        }
        val blocked = blockedSince ?: now.also { blockedSince = it }
        if (now - blocked >= MAX_BLOCKED_MILLIS) {
            return LabyrinthEventFallbackResult.Stop("事件识别持续120秒未满足正常或受限降级条件；停止并保留现场")
        }
        val observation = frame.eventChoiceSelection
        val diagnostic = frame.eventOcrDiagnostics
        val currentHash = diagnostic?.fingerprint?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }
        // Cache expiry while a new read is pending may briefly remove the observation. Preserve
        // a short, geometrically identical gap, but never authorize a click using missing text.
        if (observation == null && diagnostic?.status == "WAITING_OCR" &&
            diagnostic.cache?.pendingRequestId != null &&
            lastSeenAt?.let { now - it in 0..OCR_GAP_MILLIS } == true &&
            diagnostic.completeLayoutCount == evidence?.buttons?.size &&
            diagnostic.buttonConfidencesByOptionCount[diagnostic.completeLayoutCount]
                ?.let { scores -> scores.size == evidence?.buttons?.size && scores.all { it >= 0.55 } } == true &&
            diagnostic.cropRect == evidence?.crop && currentHash != null &&
            fingerprint?.let { java.lang.Long.bitCount(it xor currentHash) <= 4 } == true
        ) return LabyrinthEventFallbackResult.Normal

        val cache = diagnostic?.cache
        val choices = observation?.choices.orEmpty().sortedBy { it.choice.slot }
        val count = choices.size
        val validButtons = count in 2..3 && choices.all { visual ->
            val rect = visual.buttonRect
            visual.buttonConfidence >= 0.55 &&
                rect.left.toLong() + rect.width <= frame.frameWidth &&
                rect.top.toLong() + rect.height <= frame.frameHeight
        } && choices.indices.all { left ->
            (left + 1 until count).all { right -> !overlaps(choices[left].buttonRect, choices[right].buttonRect) }
        }
        val eligible = observation != null && decision != null && !observation.trusted &&
            observation.textTrustFailure == LabyrinthEventTextTrustFailure.RIVAL_MARGIN &&
            observation.confidence in 0.90..1.0 && observation.titleScore in 0.90..1.0 &&
            observation.choiceScore in 0.75..1.0 && observation.rivalMargin in 0.015..<0.045 &&
            observation.stableFrames >= 2 && decision.nonTextSafetyPassed &&
            decision.eventId == observation.event.id &&
            diagnostic?.status == "CANDIDATE_MATCH" && diagnostic.candidateEventId == observation.event.id &&
            diagnostic.completeLayoutCount == count && validButtons &&
            choices.map { it.choice } == observation.event.choices.sortedBy { it.slot } &&
            choices.map { it.choice.slot } == (1..count).toList() &&
            choices.map { it.choice.id }.distinct().size == count &&
            choices.singleOrNull { it.choice.id == decision.choiceId }?.let {
                it.buttonRect == decision.buttonRect && it.enabledConfidence >= 0.35
            } == true &&
            currentHash != null && diagnostic.cropRect != null &&
            cache?.status == "HIT" && cache.lastResultStatus == "TEXT" &&
            cache.cachedRequestId != null && cache.cachedRequestId == cache.lastCompletedRequestId &&
            cache.cachedCropRect == diagnostic.cropRect && cache.ageMillis?.let { it in 0 until 5_000 } == true
        if (!eligible) {
            clearEvidence()
            return LabyrinthEventFallbackResult.Normal
        }
        val next = Evidence(observation!!.event.id, decision!!.choiceId,
            choices.map { it.choice.id to it.buttonRect }, diagnostic!!.cropRect!!, frame.frameWidth, frame.frameHeight)
        if (evidence != next || lastSeenAt?.let { now - it > OCR_GAP_MILLIS } == true ||
            fingerprint?.let { java.lang.Long.bitCount(it xor currentHash!!) > 4 } == true ||
            lastRequestId?.let { cache!!.cachedRequestId!! < it } == true
        ) {
            clearEvidence()
            evidence = next
            fingerprint = currentHash
            consistentSince = now
        }
        lastSeenAt = now
        if (lastRequestId != cache!!.cachedRequestId) {
            reads++
            lastRequestId = cache.cachedRequestId
        }
        val waited = now - requireNotNull(consistentSince)
        // A fresh independent completion must occur after the wait, not just a reused cached frame.
        if (waited < WAIT_MILLIS || reads < 3 || cache.ageMillis!! > 2_000 || cache.ageMillis > waited - WAIT_MILLIS) {
            return LabyrinthEventFallbackResult.Normal
        }
        permitIssued = true
        return LabyrinthEventFallbackResult.Allow(decision.copy(
            actionSafe = true,
            degraded = true,
            safetyNote = null,
            explanation = "正常识别持续卡住，使用一次受限降级选择；${decision.explanation}",
        ))
    }

    /** Consume before dispatch, even if the executor later rejects or loses the gesture. */
    fun markDispatched(now: Long): Boolean {
        if (!permitIssued || dispatchedAt != null) return false
        permitIssued = false
        dispatchedAt = now
        return true
    }

    private fun confirmedSuccessorIdentity(
        frame: LabyrinthEntryFrameResult,
        decision: LabyrinthEventChoiceDecision?,
    ): String? {
        val page = frame.observation.state
        if (page in CONFIRMED_SUCCESSORS && frame.observation.confidence >= 0.80 && frame.eventChoiceSelection == null) {
            return page.name
        }
        // Some events chain straight into another event without an intermediate animation.
        // Require normal trusted recognition AND a new read AND a materially changed image.
        val observation = frame.eventChoiceSelection ?: return null
        val diagnostic = frame.eventOcrDiagnostics ?: return null
        val cache = diagnostic.cache ?: return null
        val hash = diagnostic.fingerprint?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }
            ?: return null
        return observation.event.id.takeIf {
            page == LabyrinthEntryPageState.EVENT_CHOICE && it != evidence?.eventId &&
                observation.trusted && observation.textTrustFailure == LabyrinthEventTextTrustFailure.NONE &&
                observation.confidence >= 0.90 && observation.stableFrames >= 2 &&
                decision is LabyrinthEventChoiceDecision.Select && decision.actionSafe && !decision.degraded &&
                decision.eventId == it && diagnostic.status == "TRUSTED_MATCH" && diagnostic.candidateEventId == it &&
                cache.status == "HIT" && cache.lastResultStatus == "TEXT" &&
                cache.cachedRequestId == cache.lastCompletedRequestId &&
                cache.cachedRequestId?.let { request -> lastRequestId?.let { request > it } } == true &&
                cache.ageMillis?.let { age -> age in 0..2_000 } == true &&
                fingerprint?.let { original -> java.lang.Long.bitCount(original xor hash) > 8 } == true
        }?.let { "EVENT:$it" }
    }

    private fun overlaps(a: EntryPixelRect, b: EntryPixelRect): Boolean =
        a.left.toLong() < b.left.toLong() + b.width && b.left.toLong() < a.left.toLong() + a.width &&
            a.top.toLong() < b.top.toLong() + b.height && b.top.toLong() < a.top.toLong() + a.height

    companion object {
        const val WAIT_MILLIS = 60_000L
        const val MAX_BLOCKED_MILLIS = 120_000L
        const val OBSERVE_MILLIS = 10_000L
        private const val OCR_GAP_MILLIS = 3_000L
        private val CONFIRMED_SUCCESSORS = setOf(
            LabyrinthEntryPageState.EVENT_ANIMATION, LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            LabyrinthEntryPageState.CHARACTER_JOINED, LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.RELIC_CHOICE, LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.BATTLE_CHALLENGE, LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS, LabyrinthEntryPageState.SHOP,
        )
    }
}
