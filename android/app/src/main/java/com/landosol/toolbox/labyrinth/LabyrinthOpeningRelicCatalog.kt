package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState

/** Adapted from SatenRuiko10032/limingjie-assistant (CC BY-NC-SA 4.0). Fixed grants from labyrinth_enter_guild and its type-26 labyrinth_enter_reward entries. */
class LabyrinthOpeningRelicCatalog(relics: List<RelicResource>) {
    private val byId = relics.associateBy(RelicResource::id)

    fun grants(state: LabyrinthOpeningRelicState?, frameRect: EntryPixelRect): LabyrinthOpeningRelicGrant {
        val pair = FIXED_RELICS[state?.guildId] ?: return LabyrinthOpeningRelicGrant(emptyList(), false)
        val nodes = state?.enhanceNodeIds?.takeIf { ids -> ids.all { it > 0 } }
        val ids = listOfNotNull(pair.first, pair.second.takeIf { nodes?.contains(ADDITIONAL_RELIC_NODE_ID) == true })
        val matches = ids.mapNotNull { id ->
            val relic = byId[id] ?: return@mapNotNull null
            val mark = LabyrinthRelicMark.fromLabel(relic.mark) ?: return@mapNotNull null
            val bonus = relic.markBonus.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (relic.name.isBlank()) return@mapNotNull null
            LabyrinthRelicMatch(
                slotId = "opening:$id", relicId = id, displayName = relic.name,
                attribute = mark.label, attributeBonus = bonus.toString(), effect = relic.effect,
                confidence = 1.0, screenRect = frameRect, iconRect = frameRect,
            )
        }
        return LabyrinthOpeningRelicGrant(matches, nodes != null && matches.size == ids.size)
    }

    companion object {
        const val ADDITIONAL_RELIC_NODE_ID = 31
        private val FIXED_RELICS = mapOf(
            1 to ("40001" to "50001"),
            2 to ("40002" to "50002"),
            3 to ("40003" to "50003"),
            4 to ("40004" to "50004"),
            5 to ("40005" to "50005"),
            6 to ("40006" to "50006"),
            7 to ("40007" to "50007"),
            8 to ("40008" to "50008"),
            9 to ("40009" to "50009"),
            10 to ("40010" to "50010"),
        )
    }
}

data class LabyrinthOpeningRelicGrant(val relics: List<LabyrinthRelicMatch>, val complete: Boolean)

/** Shared by startup/resume and the first opening-picker reset; no game action is performed. */
internal suspend fun LabyrinthRunStateStore.beginWithOpeningRelics(
    accountId: Long?,
    route: LabyrinthRouteJson?,
    catalog: LabyrinthOpeningRelicCatalog?,
    nowMillis: Long,
    reset: Boolean = false,
    page: String? = null,
): LabyrinthRunSnapshot {
    val previous = if (reset) startNew(accountId, nowMillis) else begin(accountId, nowMillis)
    val grants = catalog?.grants(route?.openingRelicState(), EntryPixelRect(0, 0, 1, 1))?.relics.orEmpty()
    return if (grants.isEmpty()) previous else recordPage(accountId,
        page ?: previous.currentPage ?: "OPENING_RELICS", emptyList(), nowMillis, relics = grants)
}
