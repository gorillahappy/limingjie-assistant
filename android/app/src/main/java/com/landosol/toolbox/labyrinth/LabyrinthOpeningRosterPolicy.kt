package com.landosol.toolbox.labyrinth

/** One mandatory opening slot. The first available candidate wins. */
data class LabyrinthOpeningRosterSlot(
    val candidates: List<LabyrinthOpeningCharacter>,
) {
    init {
        require(candidates.isNotEmpty())
        require(candidates.map(LabyrinthOpeningCharacter::characterId).distinct().size == candidates.size)
    }
}

data class LabyrinthOpeningCharacter(
    val characterId: String,
    val displayName: String,
) {
    init {
        require(characterId.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class LabyrinthOpeningRosterConfig(
    val guildId: Int,
    val guildName: String,
    val slots: List<LabyrinthOpeningRosterSlot>,
    /**
     * Characters the guild grants automatically at the opening, on top of the three picks. They
     * arrive as their own 角色加入 popup (2026-09-17: 拉比林斯 grants 菈比莉斯塔) and belong to
     * the run roster from the first frame, whether or not that popup's portrait is recognised.
     */
    val grantedCharacters: List<LabyrinthOpeningCharacter> = emptyList(),
) {
    init {
        require(guildId > 0)
        require(guildName.isNotBlank())
        require(slots.size == REQUIRED_OPENING_CHARACTERS) {
            "Opening roster must contain exactly $REQUIRED_OPENING_CHARACTERS slots"
        }
    }

    companion object {
        const val REQUIRED_OPENING_CHARACTERS = 3
    }
}

sealed interface LabyrinthOpeningRosterDecision {
    data class Ready(
        val characters: List<LabyrinthOpeningCharacter>,
        val reasons: List<String>,
    ) : LabyrinthOpeningRosterDecision

    data class Missing(
        val missingSlotNumbers: List<Int>,
        val reason: String,
    ) : LabyrinthOpeningRosterDecision
}

/** Resolves the three configured slots without replacing unavailable choices with unrelated roles. */
class LabyrinthOpeningRosterPolicy(
    private val config: LabyrinthOpeningRosterConfig,
) {
    /** True once every mandatory slot is satisfied by one of its configured candidates. */
    fun isSatisfied(selectedCharacterIds: Set<String>): Boolean =
        config.slots.all { slot ->
            slot.candidates.any { candidate -> candidate.characterId in selectedCharacterIds }
        }

    /** Mandatory slot numbers that are still missing from the accumulated opening selection. */
    fun missingSlotNumbers(selectedCharacterIds: Set<String>): List<Int> =
        config.slots.mapIndexedNotNull { index, slot ->
            (index + 1).takeIf {
                slot.candidates.none { candidate -> candidate.characterId in selectedCharacterIds }
            }
        }

    /**
     * Picks one currently visible candidate for an as-yet unsatisfied slot.
     *
     * This is intentionally viewport-local: the planner keeps selected ids across scrolls, while
     * this policy only answers whether the current screen contains something useful to click.
     */
    fun nextVisibleCandidate(
        availableCharacterIds: Set<String>,
        selectedCharacterIds: Set<String>,
    ): LabyrinthOpeningCharacter? {
        config.slots.forEach { slot ->
            if (slot.candidates.any { it.characterId in selectedCharacterIds }) return@forEach
            slot.candidates.firstOrNull { it.characterId in availableCharacterIds }?.let { return it }
        }
        return null
    }

    fun missingReason(selectedCharacterIds: Set<String>): String {
        val missing = missingSlotNumbers(selectedCharacterIds)
        return if (missing.isEmpty()) {
            "${config.guildName}初始方案已满足"
        } else {
            "${config.guildName}初始方案第${missing.joinToString("、")}槽滑到底仍未找到可靠候选，禁止随机补位"
        }
    }

    fun choose(availableCharacterIds: Set<String>): LabyrinthOpeningRosterDecision {
        val selected = mutableListOf<LabyrinthOpeningCharacter>()
        val reasons = mutableListOf<String>()
        val missing = mutableListOf<Int>()
        config.slots.forEachIndexed { index, slot ->
            val choice = slot.candidates.firstOrNull { it.characterId in availableCharacterIds }
            if (choice == null) {
                missing += index + 1
            } else {
                selected += choice
                val priority = slot.candidates.indexOf(choice) + 1
                reasons += if (priority == 1) {
                    "第${index + 1}槽选择${choice.displayName}（首选）"
                } else {
                    "第${index + 1}槽选择${choice.displayName}（第${priority}顺位替代）"
                }
            }
        }
        if (missing.isNotEmpty()) {
            return LabyrinthOpeningRosterDecision.Missing(
                missingSlotNumbers = missing,
                reason = "${config.guildName}初始方案第${missing.joinToString("、")}槽没有识别到可用候选，禁止随机补位",
            )
        }
        check(selected.map(LabyrinthOpeningCharacter::characterId).distinct().size == selected.size) {
            "Opening roster selected the same character more than once"
        }
        return LabyrinthOpeningRosterDecision.Ready(selected, reasons)
    }
}

/** User-confirmed opening selections. Slash-separated names are priority alternatives in one slot. */
object LabyrinthOpeningRosterCatalog {
    private fun character(id: String, name: String) = LabyrinthOpeningCharacter(id, name)
    private fun slot(vararg candidates: LabyrinthOpeningCharacter) = LabyrinthOpeningRosterSlot(candidates.toList())

    val configs: Map<Int, LabyrinthOpeningRosterConfig> = listOf(
        LabyrinthOpeningRosterConfig(
            guildId = 1,
            guildName = "美食殿堂",
            slots = listOf(
                slot(character("1075", "贪吃佩可(夏日)")),
                slot(character("1351", "雪菲(夏日)")),
                slot(character("1059", "可可萝")),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 2,
            guildName = "破晓之星",
            slots = listOf(
                slot(character("1089", "怜(新年)")),
                slot(character("1088", "优衣(新年)")),
                slot(
                    character("1003", "怜"),
                    character("1801", "日和(公主)"),
                    character("1225", "怜(夏日)"),
                ),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 3,
            guildName = "咲恋救济院",
            // 女仆 / 花女仆 / 圣诞咲恋.
            slots = listOf(
                slot(character("1025", "铃莓")),
                slot(character("1308", "铃莓(春日)")),
                slot(character("1145", "咲恋(圣诞节)")),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 4,
            guildName = "王宫骑士团（NIGHTMARE）",
            slots = listOf(
                slot(character("1242", "纯(圣诞节)")),
                slot(character("1339", "克莉丝提娜(始源)")),
                slot(
                    character("1136", "纯(夏日)"),
                    character("1115", "克莉丝提娜(圣诞节)"),
                    character("1236", "智(万圣节)"),
                    character("1238", "克莉丝提娜(狂野)"),
                ),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 5,
            guildName = "拉比林斯",
            slots = listOf(
                slot(character("1091", "静流(情人节)")),
                slot(character("1171", "静流(夏日)")),
                slot(character("1011", "璃乃")),
            ),
            grantedCharacters = listOf(character("1068", "菈比莉斯塔")),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 6, guildName = "慈乐之音",
            slots = listOf(
                slot(character("1348", "纺希（吉奥·矶汉拿）")),
                slot(character("1344", "千歌（礼服）")),
                slot(character("1054", "纺希"), character("1116", "望(圣诞节)")),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 7, guildName = "恶魔伪王国军",
            slots = listOf(
                slot(character("1082", "宫子(万圣节)")),
                slot(character("1117", "伊莉亚(圣诞节)")),
                slot(character("1138", "依里(天使)")),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 8, guildName = "伊丽莎白牧场",
            slots = listOf(slot(character("1033", "真阳")), slot(character("1026", "铃")), slot(character("1038", "栞"))),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 9, guildName = "墨丘利财团",
            slots = listOf(slot(character("1079", "珠希(夏日)")), slot(character("1168", "珠希(工作服)")), slot(character("1251", "珠希(咖啡厅)"))),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 10, guildName = "暮光流星群",
            slots = listOf(slot(character("1056", "流夏")), slot(character("1299", "惠理子(教官)")), slot(character("1051", "深月"))),
        ),
    ).associateBy(LabyrinthOpeningRosterConfig::guildId)

    /** Guilds a user may configure, in catalog order. */
    val guilds: List<LabyrinthOpeningRosterConfig> = configs.values.sortedBy(LabyrinthOpeningRosterConfig::guildId)

    /** Names this catalog already knows, so an override of a default pick keeps a readable label. */
    private val knownNames: Map<String, String> = configs.values
        .flatMap { config -> config.slots.flatMap(LabyrinthOpeningRosterSlot::candidates) + config.grantedCharacters }
        .associate { it.characterId to it.displayName }

    /** Characters [guildId] grants at the opening without a pick; empty for unknown guilds. */
    fun grantedCharactersFor(guildId: Int?): List<LabyrinthOpeningCharacter> =
        guildId?.let(configs::get)?.grantedCharacters.orEmpty()

    /**
     * The opening plan actually in force for [guildId].
     *
     * A user override replaces only the three pick slots. Guild identity and the characters the
     * guild grants for free are game facts, so they are never editable.
     *
     * [displayNameFor] supplies labels for ids this catalog has never shipped; the planner's
     * progress messages name the picks, so falling back to a bare id is a last resort.
     */
    fun configFor(
        guildId: Int?,
        overrides: Map<Int, List<List<String>>> = emptyMap(),
        displayNameFor: (String) -> String? = { null },
    ): LabyrinthOpeningRosterConfig? {
        val base = guildId?.let(configs::get) ?: return null
        val override = overrides[base.guildId]?.takeIf { openingRosterOverrideError(it) == null } ?: return base
        return base.copy(
            slots = override.map { candidates ->
                LabyrinthOpeningRosterSlot(
                    candidates.map { id ->
                        LabyrinthOpeningCharacter(id, displayNameFor(id) ?: knownNames[id] ?: id)
                    },
                )
            },
        )
    }

    fun policyFor(
        guildId: Int?,
        overrides: Map<Int, List<List<String>>> = emptyMap(),
        displayNameFor: (String) -> String? = { null },
    ): LabyrinthOpeningRosterPolicy? =
        configFor(guildId, overrides, displayNameFor)?.let(::LabyrinthOpeningRosterPolicy)
}

/**
 * Why one guild's override cannot be used, or null when it is well formed.
 *
 * An override that fails this is ignored rather than applied partially: a half-built opening plan
 * would pick the wrong characters silently, which is worse than falling back to the shipped one.
 */
fun openingRosterOverrideError(slots: List<List<String>>): String? {
    if (slots.size != LabyrinthOpeningRosterConfig.REQUIRED_OPENING_CHARACTERS) {
        return "开局方案必须正好有 ${LabyrinthOpeningRosterConfig.REQUIRED_OPENING_CHARACTERS} 个槽位"
    }
    slots.forEachIndexed { index, candidates ->
        if (candidates.isEmpty()) return "第${index + 1}槽至少要有一个候选角色"
        if (candidates.any { !it.matches(CHARACTER_ID_PATTERN) }) return "第${index + 1}槽包含无效角色 ID"
        if (candidates.distinct().size != candidates.size) return "第${index + 1}槽有重复角色"
    }
    // The planner fills each slot independently, so a character listed twice could be picked twice
    // and leave the opening one short.
    val all = slots.flatten()
    if (all.distinct().size != all.size) return "同一角色不能出现在多个槽位"
    return null
}

private val CHARACTER_ID_PATTERN = Regex("[0-9]{4,6}")
