package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import kotlin.math.max

sealed interface LabyrinthEventChoiceDecision {
    data class Select(
        val eventId: String,
        val choiceId: String,
        val label: String,
        val buttonRect: EntryPixelRect,
        val utility: Double,
        val explanation: String,
        /** False keeps the recommendation visible in Dry Run but forbids a live tap. */
        val actionSafe: Boolean,
        val safetyNote: String? = null,
    ) : LabyrinthEventChoiceDecision

    data class Wait(val reason: String) : LabyrinthEventChoiceDecision
}

/** Android port of the event utility model used by role-team-review/index.html. */
class LabyrinthEventChoicePlanner(
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val optimizer: LabyrinthTeamOptimizer,
    private val strategy: LabyrinthStrategySettings = LabyrinthStrategySettings(),
) {
    private val evaluationCache = linkedMapOf<String, List<EvaluatedChoice>>()

    fun decide(
        observation: LabyrinthEventChoiceObservation,
        acquiredCharacterIds: Set<String>,
        context: LabyrinthRoleDecisionContext,
        relicStacks: Map<LabyrinthRelicMark, Int>,
        /**
         * Options already tapped on this visit that left the page unchanged. The game renders a
         * cost-gated option in the same blue as a free one (2026-09-21 screenshot: 1,550 coins in
         * hand, two options priced 消耗2000, all three buttons blue), so pixels cannot predict the
         * refusal -- only the refusal itself can. Retiring them turns a hang into the next choice.
         */
        rejectedChoiceIds: Set<String> = emptySet(),
    ): LabyrinthEventChoiceDecision {
        val event = observation.event
        if (event.choices.isEmpty()) return LabyrinthEventChoiceDecision.Wait("事件${event.id}没有可选项")
        if (rejectedChoiceIds.isNotEmpty() && event.choices.all { it.id in rejectedChoiceIds }) {
            return LabyrinthEventChoiceDecision.Wait(
                "事件${event.id}的${event.choices.size}个选项都已点击且页面未变化，没有可执行选项",
            )
        }
        val canonicalAcquired = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).toSet()
        val cacheKey = buildString {
            append(event.id)
            append('|')
            append(canonicalAcquired.sorted().joinToString(","))
            append("|def=")
            append(context.defenseMarkStacks)
            append("|targets=")
            append(context.targetCount)
            append("|marks=")
            append(LabyrinthRelicMark.entries.joinToString(",") { relicStacks[it].orZero().toString() })
        }
        val ranked = evaluationCache.getOrPut(cacheKey) {
            val evaluation = EvaluationContext(canonicalAcquired, context, relicStacks)
            event.choices.map { choice ->
                val utility = optionUtility(choice.id.toIntOrNull(), evaluation, mutableSetOf())
                EvaluatedChoice(choice, utility.value, utility.detail)
            }.sortedWith(
                compareByDescending<EvaluatedChoice>(EvaluatedChoice::utility)
                    .thenBy { it.choice.slot },
            ).also {
                if (evaluationCache.size >= MAX_CACHE_ENTRIES) {
                    evaluationCache.remove(evaluationCache.keys.first())
                }
            }
        }
        val visualsByChoiceId = observation.choices.associateBy { it.choice.id }
        // Some event options are condition-gated and rendered disabled.  Ranking the whole
        // catalog first and only then checking the winning button used to pin the workflow on a
        // grey high-utility option forever.  Prefer the best option that is *currently* blue; if
        // none is actionable, keep the global recommendation visible but unsafe for diagnostics.
        val selectable = ranked.filterNot { it.choice.id in rejectedChoiceIds }.ifEmpty { ranked }
        // "Currently usable" means the bright blue, not merely blue: the game dims an option the
        // player cannot afford but keeps it blue, so the dimmed fill used to pass this filter and
        // the run tapped a 消耗2000 option holding 1,550 coins until it gave up.
        val best = selectable.firstOrNull { evaluated ->
            visualsByChoiceId[evaluated.choice.id]
                ?.enabledConfidence
                ?.let { it >= MINIMUM_ENABLED_BUTTON_CONFIDENCE }
                ?: false
        } ?: selectable.first()
        val visual = observation.choices.firstOrNull { it.choice.id == best.choice.id }
            ?: return LabyrinthEventChoiceDecision.Wait("事件${event.id}推荐项${best.choice.id}没有按钮映射")
        val missingOwnedProfiles = canonicalAcquired.filterNot(profiles::containsKey)
        val rolePoolChoice = best.choice.id.toIntOrNull() in ROLE_CLASS_BY_CHOICE
        val buttonEnabled = visual.enabledConfidence >= MINIMUM_ENABLED_BUTTON_CONFIDENCE
        val rejected = best.choice.id in rejectedChoiceIds
        val actionSafe = observation.trusted && buttonEnabled && !rejected &&
            (!rolePoolChoice || missingOwnedProfiles.isEmpty())
        val safetyNote = when {
            !observation.trusted -> "事件文字仍有歧义或尚未稳定确认"
            rejected -> "选项${best.choice.id}已点击且页面未变化，不再重复点击"
            !buttonEnabled ->
                "推荐按钮为灰蓝不可用状态(亮蓝${"%.2f".format(visual.enabledConfidence)})，通常是金币或条件不足"
            rolePoolChoice && missingOwnedProfiles.isNotEmpty() ->
                "已有角色资料缺失：${missingOwnedProfiles.sorted().joinToString()}"
            else -> null
        }
        return LabyrinthEventChoiceDecision.Select(
            eventId = event.id,
            choiceId = best.choice.id,
            label = best.choice.name,
            buttonRect = visual.buttonRect,
            utility = best.utility,
            explanation = best.detail,
            actionSafe = actionSafe,
            safetyNote = safetyNote,
        )
    }

    private fun optionUtility(
        choiceId: Int?,
        evaluation: EvaluationContext,
        visiting: MutableSet<Int>,
    ): Utility {
        choiceId ?: return Utility(0.0, "选项ID无效")
        ROLE_CLASS_BY_CHOICE[choiceId]?.let { roleClass ->
            val pool = rolePool(roleClass, evaluation)
            return Utility(
                pool.average * 2 + coinUtility(200, evaluation.relicStacks),
                "$roleClass 未获得角色${pool.values.size}人；随机×2平均边际${"%.2f".format(pool.average * 2)}；α币200",
            )
        }
        BATTLE_REWARDS[choiceId]?.let { (normal, reward) ->
            val rate = (if (normal) strategy.normalWinRate else strategy.extremeWinRate) / 100.0
            return Utility(
                coinUtility(reward, evaluation.relicStacks) * rate,
                "${if (normal) "普通" else "极难"}战斗奖励α币$reward；按${(rate * 100).toInt()}%胜率估值",
            )
        }
        val coins = { value: Int -> coinUtility(value, evaluation.relicStacks) }
        return when (choiceId) {
            11073 -> Utility(coins(1750), "50%×1500＋50%×2000，期望α币1750")
            11072 -> Utility(
                0.5 * randomClassSelectableRoleUtility(1, evaluation) +
                    0.5 * allSelectableRoleUtility(1, evaluation),
                "50%随机职阶自选1＋50%全角色自选1",
            )
            11071 -> Utility(
                0.5 * randomRelicUtility(2, evaluation.relicStacks) +
                    0.5 * randomRelicUtility(3, evaluation.relicStacks),
                "50%★2遗物＋50%★3遗物",
            )
            11082 -> Utility(coins(1000), "固定α币1000")
            11081 -> Utility(
                0.5 * randomRelicUtility(1, evaluation.relicStacks) +
                    0.5 * randomClassRandomRoleUtility(2, evaluation),
                "50%★1遗物＋50%随机职阶随机角色×2",
            )
            11101, 11102, 11103 -> Utility(coins((5000.0 / 3).toInt()), "猜拳三项奖励结构相同，固定按第一项消除随机点击")
            11111 -> Utility(coins(2000), "固定α币2000")
            11112 -> Utility(
                (randomRelicUtility(1, evaluation.relicStacks) +
                    randomRelicUtility(2, evaluation.relicStacks) +
                    randomRelicUtility(3, evaluation.relicStacks)) / 3,
                "★1～3随机遗物按三档等权估值",
            )
            11121 -> Utility(coins(2000), "固定α币2000")
            11122 -> Utility(
                specificRoleUtility("纯", evaluation) + allRandomRoleUtility(3, evaluation),
                "纯加入＋全角色随机×2～4，按期望3人",
            )
            13141 -> Utility(
                0.5 * (randomRelicUtility(1, evaluation.relicStacks) + continuation(2314, evaluation, visiting)) +
                    0.5 * coins(CHAIN_FAILURE_COIN),
                "钓鱼第1段：50%★1遗物并继续；失败500α币",
            )
            23141 -> Utility(
                0.25 * (randomRelicUtility(2, evaluation.relicStacks) + continuation(3314, evaluation, visiting)) +
                    0.75 * coins(CHAIN_FAILURE_COIN),
                "钓鱼第2段：25%★2遗物并继续；失败500α币",
            )
            33141 -> Utility(
                0.10 * randomRelicUtility(3, evaluation.relicStacks) + 0.90 * coins(CHAIN_FAILURE_COIN),
                "钓鱼第3段：10%★3遗物；失败500α币",
            )
            13151 -> Utility(
                0.5 * continuation(2315, evaluation, visiting) + 0.5 * coins(CHAIN_FAILURE_COIN),
                "料理第1段：50%继续；失败500α币",
            )
            23152 -> Utility(randomClassRandomRoleUtility(2, evaluation), "结束料理并获得随机职阶随机角色×2")
            23151 -> Utility(
                0.5 * continuation(3315, evaluation, visiting) + 0.5 * coins(CHAIN_FAILURE_COIN),
                "继续料理：50%进入第3段；失败500α币",
            )
            33152 -> Utility(randomClassSelectableRoleUtility(2, evaluation), "随机职阶角色自选×2")
            33151 -> Utility(
                0.5 * allSelectableRoleUtility(2, evaluation) + 0.5 * coins(CHAIN_FAILURE_COIN),
                "50%全角色自选×2；失败500α币",
            )
            13161 -> Utility(
                0.5 * (coins(2000) + continuation(2316, evaluation, visiting)) +
                    0.5 * coins(CHAIN_FAILURE_COIN),
                "老虎机第1段：50%α币2000并继续；失败500α币",
            )
            23162, 33162 -> Utility(0.0, "结束挑战，保留已有收益")
            23161 -> Utility(
                0.5 * (coins(4000) + continuation(3316, evaluation, visiting)) +
                    0.5 * coins(CHAIN_FAILURE_COIN),
                "老虎机第2段：50%α币4000并继续；失败500α币",
            )
            33161 -> Utility(0.5 * coins(8000) + 0.5 * coins(CHAIN_FAILURE_COIN), "老虎机第3段：50%α币8000；失败500α币")
            else -> Utility(0.0, "已收录，暂无跨资源效用规则")
        }
    }

    private fun continuation(
        eventId: Int,
        evaluation: EvaluationContext,
        visiting: MutableSet<Int>,
    ): Double {
        if (!visiting.add(eventId)) return 0.0
        val best = CONTINUATION_CHOICES[eventId].orEmpty()
            .maxOfOrNull { optionUtility(it, evaluation, visiting).value }
            ?: 0.0
        visiting.remove(eventId)
        return best
    }

    private fun rolePool(roleClass: String, evaluation: EvaluationContext): RolePool {
        return evaluation.rolePools.getOrPut(roleClass) {
            val owned = acquiredProfiles(evaluation)
            val values = profiles.values.asSequence()
                .filter { it.roleClass == roleClass && it.characterId !in evaluation.acquiredIds }
                .filter(LabyrinthRoleProfile::isStrictDecisionReady)
                .map { candidate -> candidateMarginal(candidate, owned, evaluation.context) }
                .sortedDescending()
                .toList()
            RolePool(values, values.averageOrZero())
        }
    }

    private fun allRoleValues(evaluation: EvaluationContext): List<Double> =
        evaluation.allRoleValues ?: profiles.values.asSequence()
            .filter { it.roleClass in ROLE_CLASSES && it.characterId !in evaluation.acquiredIds }
            .filter(LabyrinthRoleProfile::isStrictDecisionReady)
            .map { candidate -> candidateMarginal(candidate, acquiredProfiles(evaluation), evaluation.context) }
            .sortedDescending()
            .toList()
            .also { evaluation.allRoleValues = it }

    private fun acquiredProfiles(evaluation: EvaluationContext): List<LabyrinthRoleProfile> =
        evaluation.acquiredProfiles ?: evaluation.acquiredIds.mapNotNull(profiles::get)
            .sortedByDescending { individualPriority(it, evaluation.context.targetCount) }
            .take(EVENT_OWNED_ROSTER_LIMIT)
            .also { evaluation.acquiredProfiles = it }

    private fun candidateMarginal(
        candidate: LabyrinthRoleProfile,
        owned: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): Double {
        val baseline = optimizer.bestFormation(owned, context)
        val pool = (owned + candidate).distinctBy(LabyrinthRoleProfile::characterId)
        val first = optimizer.bestFormation(pool, context)
        val forced = optimizer.bestFormation(pool, context, candidate.characterId)
        return max(
            0.0,
            (first?.score ?: 0.0) + (forced?.score ?: 0.0) * 0.35 - (baseline?.score ?: 0.0) * 1.35,
        )
    }

    private fun individualPriority(role: LabyrinthRoleProfile, targetCount: Int): Double {
        val physical = when (targetCount.coerceAtMost(3)) {
            1 -> role.physicalDamage1Target
            2 -> role.physicalDamage2Target
            else -> role.physicalDamage3Target
        } ?: role.physicalDamagePotential
        val magic = when (targetCount.coerceAtMost(3)) {
            1 -> role.magicDamage1Target
            2 -> role.magicDamage2Target
            else -> role.magicDamage3Target
        } ?: role.magicDamagePotential
        return role.effectiveUserScore.orZero() + physical.orZero() + magic.orZero() +
            role.functions.reliableVanguard.orZero() * 0.5 +
            role.functions.physicalDefenseDown.orZero() * 0.3 +
            role.functions.magicDefenseDown.orZero() * 0.3
    }

    private fun randomClassRandomRoleUtility(count: Int, evaluation: EvaluationContext): Double {
        val pools = ROLE_CLASSES.map { rolePool(it, evaluation) }.filter { it.values.isNotEmpty() }
        return pools.map { it.average * count }.averageOrZero()
    }

    private fun randomClassSelectableRoleUtility(count: Int, evaluation: EvaluationContext): Double {
        val pools = ROLE_CLASSES.map { rolePool(it, evaluation) }.filter { it.values.isNotEmpty() }
        return pools.map { it.values.take(count).sum() }.averageOrZero()
    }

    private fun allRandomRoleUtility(count: Int, evaluation: EvaluationContext): Double =
        allRoleValues(evaluation).averageOrZero() * count

    private fun allSelectableRoleUtility(count: Int, evaluation: EvaluationContext): Double =
        allRoleValues(evaluation).take(count).sum()

    private fun specificRoleUtility(name: String, evaluation: EvaluationContext): Double {
        val role = profiles.values.firstOrNull { it.displayName == name } ?: return 0.0
        if (role.characterId in evaluation.acquiredIds) return 0.0
        return candidateMarginal(role, acquiredProfiles(evaluation), evaluation.context)
    }

    private fun randomRelicUtility(rarity: Int, stacks: Map<LabyrinthRelicMark, Int>): Double =
        LabyrinthRelicMark.entries.map { relicMarkUtility(it, rarity.coerceIn(1, 3), stacks) }.average()

    private fun bestRelicUtility(rarity: Int, stacks: Map<LabyrinthRelicMark, Int>): Double =
        LabyrinthRelicMark.entries.maxOf { relicMarkUtility(it, rarity, stacks) }

    private fun coinUtility(coins: Int, stacks: Map<LabyrinthRelicMark, Int>): Double =
        coins / 1800.0 * max(2.5, bestRelicUtility(1, stacks))

    private fun relicMarkUtility(
        mark: LabyrinthRelicMark,
        amount: Int,
        stacks: Map<LabyrinthRelicMark, Int>,
    ): Double {
        var value = 0.0
        var current = stacks[mark].orZero()
        val baseline = setOf(LabyrinthRelicMark.ACCELERATION, LabyrinthRelicMark.CRITICAL, LabyrinthRelicMark.DEFENSE)
        val baselineComplete = baseline.all { stacks[it].orZero() >= 4 }
        repeat(amount) {
            val next = current + 1
            value += when {
                !baselineComplete && mark in baseline && current < 4 -> 8.0
                !baselineComplete -> 2.5
                else -> {
                    val unfinished = LabyrinthRelicMark.entries.filter { stacks[it].orZero() < 15 }
                    val highest = unfinished.maxByOrNull { stacks[it].orZero() }
                    val debuffPivot = stacks[LabyrinthRelicMark.DEBUFF].orZero() >= 12 &&
                        (highest == null || stacks[LabyrinthRelicMark.DEBUFF].orZero() + 2 >= stacks[highest].orZero())
                    val focus = if (debuffPivot) LabyrinthRelicMark.DEBUFF else highest
                    if (mark == focus) 6.0 else 3.0
                }
            }
            if (current < 15 && next >= 15) {
                value += if (mark == LabyrinthRelicMark.DEBUFF) 16.0 else 10.0
            }
            current = next
        }
        return value
    }

    private data class EvaluationContext(
        val acquiredIds: Set<String>,
        val context: LabyrinthRoleDecisionContext,
        val relicStacks: Map<LabyrinthRelicMark, Int>,
        val rolePools: MutableMap<String, RolePool> = mutableMapOf(),
        var acquiredProfiles: List<LabyrinthRoleProfile>? = null,
        var allRoleValues: List<Double>? = null,
    )

    private data class RolePool(val values: List<Double>, val average: Double)
    private data class Utility(val value: Double, val detail: String)
    private data class EvaluatedChoice(val choice: EventChoiceResource, val utility: Double, val detail: String)

    private companion object {
        val ROLE_CLASS_BY_CHOICE = mapOf(
            11011 to "掩护者", 11012 to "治疗者",
            11021 to "增幅者", 11022 to "增益者",
            11031 to "减益者", 11032 to "妨碍者",
            11041 to "攻击者", 11042 to "破防者",
        )
        val ROLE_CLASSES = ROLE_CLASS_BY_CHOICE.values.distinct()
        val BATTLE_REWARDS = mapOf(
            11051 to (true to 200), 11052 to (false to 300),
            11091 to (true to 300), 11092 to (false to 450),
            11131 to (true to 400), 11132 to (false to 600),
            11171 to (true to 500), 11172 to (false to 750),
        )
        val CONTINUATION_CHOICES = mapOf(
            2314 to listOf(23141), 3314 to listOf(33141),
            2315 to listOf(23152, 23151), 3315 to listOf(33152, 33151),
            2316 to listOf(23162, 23161), 3316 to listOf(33162, 33161),
        )
        const val CHAIN_FAILURE_COIN = 500
        const val EVENT_OWNED_ROSTER_LIMIT = 12
        const val MINIMUM_BLUE_BUTTON_CONFIDENCE = 0.18
        /**
         * Bright-blue fraction a button must reach to count as usable.
         *
         * Measured: usable buttons score 0.54-0.55 (2026-09-21 stone-slab screenshot and the
         * single-choice fishing fixture), unaffordable ones exactly 0.00. 0.35 sits in the middle
         * of that gap with room for a differently sized button or a compressed screenshot.
         */
        const val MINIMUM_ENABLED_BUTTON_CONFIDENCE = 0.35
        const val MAX_CACHE_ENTRIES = 12
    }
}

private fun Double?.orZero(): Double = this ?: 0.0
private fun Int?.orZero(): Int = this ?: 0
private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
