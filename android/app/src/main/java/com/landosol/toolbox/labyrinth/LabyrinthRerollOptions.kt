package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.ClearedDifficulty

data class LabyrinthGuildOption(
    val guildId: Int,
    val name: String,
)

enum class LabyrinthThirdBlockChoice(val label: String) {
    RELIC("必须遗物"),
    EVENT("必须事件"),
    EITHER("两者都行"),
}

enum class LabyrinthRouteEvaluationMode(val label: String) {
    VALUE_ROUTE("价值路线 v2"),
    LEGACY_TEMPLATE("旧版固定模板"),
}

enum class LabyrinthBossDifficulty(val label: String) {
    EASY("简单"),
    NORMAL("普通"),
    HARD("困难"),
}

data class LabyrinthBossOption(
    val unitId: Int,
    val name: String,
    val difficulty: LabyrinthBossDifficulty,
)

object LabyrinthRerollOptions {
    val guilds = listOf(
        LabyrinthGuildOption(1, "美食殿堂"),
        LabyrinthGuildOption(2, "破晓之星"),
        LabyrinthGuildOption(3, "咲恋救济院"),
        LabyrinthGuildOption(4, "王宫骑士团（NIGHTMARE）"),
        LabyrinthGuildOption(5, "拉比林斯"),
        LabyrinthGuildOption(6, "慈乐之音"),
        LabyrinthGuildOption(7, "恶魔伪王国军"),
        LabyrinthGuildOption(8, "伊丽莎白牧场"),
        LabyrinthGuildOption(9, "墨丘利财团"),
        LabyrinthGuildOption(10, "暮光流星群"),
    )

    val area3Bosses = listOf(
        LabyrinthBossOption(312505, "厄勒克特拉夫人", LabyrinthBossDifficulty.EASY),
        LabyrinthBossOption(319604, "冰霜魔狼", LabyrinthBossDifficulty.EASY),
        LabyrinthBossOption(303306, "暗黑滴水嘴兽", LabyrinthBossDifficulty.NORMAL),
        LabyrinthBossOption(301206, "巨型魔像", LabyrinthBossDifficulty.NORMAL),
        LabyrinthBossOption(306604, "毒液沙鳗蛇", LabyrinthBossDifficulty.HARD),
    )

    val area5Bosses = listOf(
        LabyrinthBossOption(310103, "愤怒巨龙", LabyrinthBossDifficulty.EASY),
        LabyrinthBossOption(301701, "炸脖龙", LabyrinthBossDifficulty.NORMAL),
        LabyrinthBossOption(319401, "究极守护者", LabyrinthBossDifficulty.NORMAL),
        LabyrinthBossOption(315004, "领主哥布林", LabyrinthBossDifficulty.HARD),
        LabyrinthBossOption(302501, "奇美拉", LabyrinthBossDifficulty.HARD),
    )

    val defaultArea3BossIds: Set<Int> = area3Bosses
        .filter { it.difficulty == LabyrinthBossDifficulty.EASY }
        .mapTo(linkedSetOf(), LabyrinthBossOption::unitId)

    val defaultArea5BossIds: Set<Int> = area5Bosses
        .filter { it.difficulty == LabyrinthBossDifficulty.EASY }
        .mapTo(linkedSetOf(), LabyrinthBossOption::unitId)

    fun maxUnlockedDifficulty(cleared: List<ClearedDifficulty>): Int =
        (cleared.maxOfOrNull(ClearedDifficulty::difficulty)?.plus(1) ?: 1).coerceAtMost(MAX_DIFFICULTY)

    const val DEFAULT_GUILD_ID = 1
    const val DEFAULT_DIFFICULTY = 5
    const val DEFAULT_PERFECT_START = true
    val DEFAULT_ROUTE_EVALUATION_MODE = LabyrinthRouteEvaluationMode.VALUE_ROUTE
    const val DEFAULT_VALUE_ALLOWANCE = 1
    const val MAX_VALUE_ALLOWANCE = 3
    val defaultThirdBlockChoice = LabyrinthThirdBlockChoice.RELIC
    const val MAX_DIFFICULTY = 5
    const val MAX_ATTEMPTS = 800
}

/**
 * Boss 关卡在主数据中以五个 Boss 为一组循环。这里只编码稳定的关卡编号规则，
 * 不把 AutoPCR 的数据库或运行时代码带入应用。
 */
object LabyrinthBossCatalog {
    private val area3BySlot = listOf(301206, 303306, 319604, 312505, 306604)
    private val area5BySlot = listOf(302501, 301701, 310103, 315004, 319401)

    fun unitIdForQuest(area: Int, questId: Int?): Int? {
        if (questId == null) return null
        val expectedPrefix = when (area) {
            3 -> 7703
            5 -> 7705
            else -> return null
        }
        if (questId / 100_000 != expectedPrefix) return null
        val ordinal = questId / 100 % 100
        if (ordinal !in 1..20) return null
        val bosses = if (area == 3) area3BySlot else area5BySlot
        return bosses[(ordinal - 1) % bosses.size]
    }
}
