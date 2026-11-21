package com.landosol.toolbox.labyrinth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LabyrinthBossTeamMode {
    MULTI_TEAM,
    SINGLE_TEAM,
}

/** User preferences only: game facts and recognition safety thresholds are not editable. */
@Serializable
data class LabyrinthStrategySettings(
    val playerWeight: Int = 70,
    val damageWeight: Int = 50,
    val survivalWeight: Int = 23,
    val functionWeight: Int = 13,
    val formationWeight: Int = 4,
    val cohesionWeight: Int = 10,
    /** Apply the game's same-attribute damage tiers (2/3/4/5 = 8/16/25/75%). */
    val attributePairingBonusEnabled: Boolean = true,
    val duplicateRolePenalty: Int = 8,
    /** EX/Boss team-level soft penalty applied after player/system blending. */
    val mixedDamagePenalty: Int = 15,
    /** Per-role Effective Effect bonus applied only to that role's system-side score. */
    val effectiveCharacterSystemBonus: Int = 8,
    val secondTeamWeight: Int = 35,
    val thirdTeamWeight: Int = 20,
    /** Boss battle composition mode. Multi-team is the product default. */
    val bossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
    /** Boss-only soft preference: favor a coherent physical or magic damage system. */
    val preferPureBossDamageSystem: Boolean = true,
    /** Single-team Boss fallback: after the configured retries are exhausted, retry in multi-team mode. */
    val singleBossFallbackToMultiAfterThreeFailures: Boolean = true,
    /** Number of "重新挑战" taps allowed in single-team Boss mode before switching to multi-team. */
    val singleBossRetryCountBeforeMulti: Int = 2,
    /** After the third failed combat attempt, abandon the run and start rerolling via the API. */
    val rerollAfterThreeBattleFailures: Boolean = true,
    val baselineLastArea: Int = 3,
    val baselineTarget: Int = 4,
    val debuffPivotMinimum: Int = 12,
    val debuffPivotTolerance: Int = 2,
    val buyRelics: Boolean = true,
    val refreshShop: Boolean = true,
    val refreshFromArea: Int = 5,
    val normalWinRate: Int = 95,
    val extremeWinRate: Int = 70,
    val personalRoleScores: Map<String, Int> = emptyMap(),
    /**
     * Per-guild opening picks, replacing the shipped plan: guild id -> three slots, each an
     * ordered candidate list. The first candidate a slot finds on screen wins, so the order is the
     * user's preference order. Guilds left out here keep the shipped plan.
     */
    val openingRosters: Map<Int, List<List<String>>> = emptyMap(),
    /**
     * Preferred relic marks, strongest first. Empty keeps the built-in ordering.
     *
     * Only the choices the policy is otherwise indifferent about are affected; reaching a 15 tier
     * immediately still wins regardless of order.
     */
    val relicMarkPriority: List<LabyrinthRelicMark> = emptyList(),
) {
    fun validationError(): String? = when {
        listOf(playerWeight, damageWeight, survivalWeight, functionWeight, formationWeight,
            cohesionWeight, secondTeamWeight, thirdTeamWeight, normalWinRate, extremeWinRate)
            .any { it !in 0..100 } -> "权重和胜率必须在 0–100 之间"
        damageWeight + survivalWeight + functionWeight + formationWeight + cohesionWeight == 0 ->
            "系统评分至少保留一项非零权重"
        duplicateRolePenalty !in 0..30 -> "重复纯职能扣分必须在 0–30 之间"
        mixedDamagePenalty !in 0..30 -> "物法混编扣分必须在 0–30 之间"
        effectiveCharacterSystemBonus !in 0..30 -> "有效效果角色系统加分必须在 0–30 之间"
        singleBossRetryCountBeforeMulti !in 0..10 -> "单队Boss重试次数必须在 0–10 之间"
        baselineLastArea !in 0..5 -> "补基础层数的截止区域必须在 0–5 之间"
        baselineTarget !in 1..4 -> "基础目标层数必须在 1–4 之间"
        debuffPivotMinimum !in 5..15 -> "转追削弱的最低层数必须在 5–15 之间"
        debuffPivotTolerance !in 0..5 -> "削弱落后容忍层数必须在 0–5 之间"
        refreshFromArea !in 1..5 -> "商店刷新起始区域必须在 1–5 之间"
        personalRoleScores.any { (id, score) -> !id.matches(Regex("[0-9]{4,6}")) || score !in 0..100 } ->
            "个人角色评分必须使用有效角色 ID，分数在 0–100 之间"
        openingRosterError() != null -> openingRosterError()
        relicMarkPriority.distinct().size != relicMarkPriority.size -> "遗物优先级不能重复"
        else -> null
    }

    /** First problem across every configured guild, named so the settings screen can show it. */
    fun openingRosterError(): String? = openingRosters.firstNotNullOfOrNull { (guildId, slots) ->
        val guild = LabyrinthOpeningRosterCatalog.configs[guildId]
            ?: return@firstNotNullOfOrNull "开局方案包含未知公会 $guildId"
        openingRosterOverrideError(slots)?.let { "${guild.guildName}：$it" }
    }

    fun applyTo(document: LabyrinthRoleDecisionDocument): LabyrinthRoleDecisionDocument {
        require(validationError() == null)
        val total = (damageWeight + survivalWeight + functionWeight + formationWeight + cohesionWeight).toDouble()
        return document.copy(
            characters = if (personalRoleScores.isEmpty()) document.characters else document.characters.map { role ->
                personalRoleScores[role.characterId]?.let { role.copy(personalScore = it.toDouble()) } ?: role
            },
            scoring = document.scoring.copy(
                attributeDamageBonus = if (attributePairingBonusEnabled) {
                    document.scoring.attributeDamageBonus
                } else {
                    document.scoring.attributeDamageBonus.keys.associateWith { 0.0 }
                },
                playerWeight = playerWeight / 100.0,
                systemDamageWeight = damageWeight / total,
                systemSurvivalWeight = survivalWeight / total,
                systemFunctionWeight = functionWeight / total,
                systemFormationWeight = formationWeight / total,
                systemCohesionWeight = cohesionWeight / total,
                duplicatePureRolePenalty = duplicateRolePenalty.toDouble(),
                battleMixedDamagePenalty = mixedDamagePenalty.toDouble(),
                encounterEffectiveCharacterBonus = effectiveCharacterSystemBonus.toDouble(),
            ),
            roleChoice = document.roleChoice.copy(
                candidateTeamWeights = listOf(1.0, secondTeamWeight / 100.0, thirdTeamWeight / 100.0),
            ),
        )
    }

    fun relicPolicy() = LabyrinthRelicChoicePolicy(LabyrinthRelicChoicePolicyConfig(
        baselineTarget = baselineTarget,
        debuffPivotMinimum = debuffPivotMinimum,
        debuffPivotTolerance = debuffPivotTolerance,
        baselineLastArea = baselineLastArea,
        markPriority = relicMarkPriority.distinct(),
    ))
}

/** Read one immutable snapshot at session start; saving never mutates a running planner. */
data class LabyrinthStrategySnapshot(
    val settings: LabyrinthStrategySettings,
    val roleRuntime: LabyrinthRoleDecisionRuntime?,
    val openingRelicCatalog: LabyrinthOpeningRelicCatalog? = null,
)

internal object LabyrinthStrategySettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(raw: String?): LabyrinthStrategySettings = runCatching {
        raw?.let { json.decodeFromString<LabyrinthStrategySettings>(it) }
            ?.takeIf { it.validationError() == null }
    }.getOrNull() ?: LabyrinthStrategySettings()

    fun encode(settings: LabyrinthStrategySettings): String {
        require(settings.validationError() == null)
        return encodeDraft(settings)
    }

    fun encodeDraft(settings: LabyrinthStrategySettings): String =
        json.encodeToString(LabyrinthStrategySettings.serializer(), settings)

    fun decodeDraft(raw: String): LabyrinthStrategySettings =
        json.decodeFromString<LabyrinthStrategySettings>(raw)
}
