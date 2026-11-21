package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.ClearedDifficulty
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRoutePolicyTest {
    @Test
    fun `ten guild options retain the original reroll defaults`() {
        assertEquals((1..10).toList(), LabyrinthRerollOptions.guilds.map { it.guildId })
        assertEquals("拉比林斯", LabyrinthRerollOptions.guilds[4].name)
        assertEquals("暮光流星群", LabyrinthRerollOptions.guilds.last().name)
        assertEquals(1, LabyrinthRerollOptions.DEFAULT_GUILD_ID)
        assertEquals(5, LabyrinthRerollOptions.DEFAULT_DIFFICULTY)
        assertTrue(LabyrinthRerollOptions.DEFAULT_PERFECT_START)
        assertEquals(LabyrinthRouteEvaluationMode.VALUE_ROUTE, LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE)
        assertEquals(1, LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE)
        assertEquals(LabyrinthThirdBlockChoice.RELIC, LabyrinthRerollOptions.defaultThirdBlockChoice)
        assertEquals(setOf(312505, 319604), LabyrinthRerollOptions.defaultArea3BossIds)
        assertEquals(setOf(310103), LabyrinthRerollOptions.defaultArea5BossIds)
        assertEquals(800, LabyrinthRerollOptions.MAX_ATTEMPTS)

        val state = LabyrinthUiState()
        assertEquals(1, state.selectedGuildId)
        assertEquals(5, state.selectedDifficulty)
        assertTrue(state.perfectStart)
        assertEquals(LabyrinthRouteEvaluationMode.VALUE_ROUTE, state.routeEvaluationMode)
        assertEquals(1, state.valueAllowance)
        assertEquals(LabyrinthThirdBlockChoice.RELIC, state.thirdBlockChoice)
        assertEquals(setOf(312505, 319604), state.selectedArea3BossIds)
        assertEquals(setOf(310103), state.selectedArea5BossIds)
    }

    @Test
    fun `unlocked difficulty follows cleared maximum plus one`() {
        assertEquals(1, LabyrinthRerollOptions.maxUnlockedDifficulty(emptyList()))
        assertEquals(4, LabyrinthRerollOptions.maxUnlockedDifficulty(listOf(ClearedDifficulty(2, 3))))
        assertEquals(5, LabyrinthRerollOptions.maxUnlockedDifficulty(listOf(ClearedDifficulty(2, 5))))
    }

    @Test
    fun `perfect start checks fixed blocks and third block preference`() {
        val relicRoute = route(area = 3, bossQuest = 770330401)
        val eventRoute = relicRoute.map { node ->
            if (node.column == 3) node.copy(blockType = LabyrinthRoutePolicy.EVENT_BLOCK_TYPE) else node
        }

        assertTrue(
            LabyrinthRoutePolicy(
                perfectStart = true,
                thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
            ).accepts(3, relicRoute),
        )
        assertFalse(
            LabyrinthRoutePolicy(
                perfectStart = true,
                thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
            ).accepts(3, eventRoute),
        )
        assertTrue(
            LabyrinthRoutePolicy(
                perfectStart = true,
                thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER,
            ).accepts(3, eventRoute),
        )
    }

    @Test
    fun `non perfect start ignores fixed block pattern but still checks boss`() {
        val route = route(area = 3, bossQuest = 770330401).map { node ->
            if (node.column == 2) node.copy(blockType = 99) else node
        }

        assertTrue(LabyrinthRoutePolicy(perfectStart = false).accepts(3, route))
        assertFalse(LabyrinthRoutePolicy(perfectStart = true).accepts(3, route))
    }

    @Test
    fun `boss selection maps quest to selected unit`() {
        val ladyRoute = route(area = 3, bossQuest = 770330401)
        val giantOnly = LabyrinthRoutePolicy(area3BossIds = setOf(301206))
        val acceptAny = LabyrinthRoutePolicy(area3BossIds = emptySet())

        assertFalse(giantOnly.accepts(3, ladyRoute))
        assertTrue(acceptAny.accepts(3, ladyRoute))
        assertEquals(312505, LabyrinthBossCatalog.unitIdForQuest(3, 770331901))
        assertEquals(310103, LabyrinthBossCatalog.unitIdForQuest(5, 770531801))
    }

    @Test
    fun `matcher finds one valid route for every required area`() {
        val nodes = (1..5).flatMap { area ->
            route(
                area = area,
                bossQuest = when (area) {
                    3 -> 770330401
                    5 -> 770530301
                    else -> null
                },
            )
        }

        val normal = LabyrinthOpeningRouteMatcher().search(
            enterId = 7,
            nodes = nodes,
            difficulty = 2,
            policy = LabyrinthRoutePolicy(),
        )
        val firstDifficulty = LabyrinthOpeningRouteMatcher().search(
            enterId = 7,
            nodes = nodes,
            difficulty = 1,
            policy = LabyrinthRoutePolicy(),
        )

        assertTrue(normal is RouteSearchResult.Found)
        assertEquals(setOf(1, 2, 3, 4, 5), (normal as RouteSearchResult.Found).route.nodes.mapTo(mutableSetOf()) { it.area })
        assertTrue(firstDifficulty is RouteSearchResult.Found)
        assertEquals(setOf(1, 2, 3), (firstDifficulty as RouteSearchResult.Found).route.nodes.mapTo(mutableSetOf()) { it.area })
    }

    @Test
    fun `matcher backtracks to selected third block type`() {
        val area3 = route(area = 3, bossQuest = 770330401).toMutableList()
        val second = area3.indexOfFirst { it.column == 2 }
        val relic = area3.first { it.column == 3 }
        val event = relic.copy(
            row = 2,
            blockId = 399,
            blockType = LabyrinthRoutePolicy.EVENT_BLOCK_TYPE,
        )
        area3[second] = area3[second].copy(nextBlockIds = listOf(relic.blockId, event.blockId))
        area3 += event
        val nodes = route(1, null) + route(2, null) + area3

        val result = LabyrinthOpeningRouteMatcher().search(
            enterId = 7,
            nodes = nodes,
            difficulty = 1,
            policy = LabyrinthRoutePolicy(
                perfectStart = true,
                thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT,
            ),
        )

        assertTrue(result is RouteSearchResult.Found)
        val blockIds = (result as RouteSearchResult.Found).route.blockIds
        assertTrue(399L in blockIds)
        assertFalse(relic.blockId in blockIds)
    }

    @Test
    fun `matcher resumes from the route containing the current block`() {
        val nodes = (1..5).flatMap { area ->
            route(
                area = area,
                bossQuest = when (area) {
                    3 -> 770330401
                    5 -> 770530301
                    else -> null
                },
            )
        }

        val result = LabyrinthOpeningRouteMatcher().search(
            enterId = 7,
            nodes = nodes,
            difficulty = 2,
            policy = LabyrinthRoutePolicy(),
            currentBlockId = 303,
        )

        assertTrue(result is RouteSearchResult.Found)
        val route = (result as RouteSearchResult.Found).route
        assertEquals(setOf(3, 4, 5), route.nodes.mapTo(mutableSetOf()) { it.area })
        assertTrue(303L in route.blockIds)
    }

    @Test
    fun `value route chooses the branch with the most valuable nodes`() {
        val nodes = listOf(
            valueNode(4, 1, 1, 401, 1, next = listOf(411, 412)),
            valueNode(4, 2, 1, 411, 2, next = listOf(421)),
            valueNode(4, 2, 2, 412, 3, next = listOf(421)),
            valueNode(4, 3, 1, 421, 6, next = listOf(431)),
            valueNode(4, 4, 1, 431, 7),
        )
        val result = LabyrinthValueRouteEvaluator(
            LabyrinthRoutePolicy(
                evaluationMode = LabyrinthRouteEvaluationMode.VALUE_ROUTE,
                valueAllowance = 0,
                area3BossIds = emptySet(),
                area5BossIds = emptySet(),
            ),
        ).best(4, nodes)

        requireNotNull(result)
        assertEquals(3, result.upperBound)
        assertEquals(3, result.score)
        assertEquals(0, result.shortfall)
        assertTrue(412L in result.route.map { it.blockId })
        assertFalse(411L in result.route.map { it.blockId })
    }

    @Test
    fun `value route allowance accepts one missing valuable column but zero rejects it`() {
        val nodes = buildList {
            add(valueNode(1, 1, 1, 101, 1, next = listOf(102)))
            add(valueNode(1, 2, 1, 102, 2, next = listOf(103)))
            // Valuable EX exists in column 2 but is not reachable from the start.
            add(valueNode(1, 2, 2, 199, 3, next = listOf(103)))
            add(valueNode(1, 3, 1, 103, 6))
            add(valueNode(2, 1, 1, 201, 1, next = listOf(202)))
            add(valueNode(2, 2, 1, 202, 6))
            add(valueNode(3, 1, 1, 301, 1, next = listOf(302)))
            add(valueNode(3, 2, 1, 302, 8, questId = 770330401))
        }
        fun search(allowance: Int) = LabyrinthOpeningRouteMatcher().search(
            enterId = 55,
            nodes = nodes,
            difficulty = 1,
            policy = LabyrinthRoutePolicy(
                evaluationMode = LabyrinthRouteEvaluationMode.VALUE_ROUTE,
                valueAllowance = allowance,
                thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER,
                area3BossIds = emptySet(),
                area5BossIds = emptySet(),
            ),
        )

        assertTrue(search(0) is RouteSearchResult.NoRoute)
        assertTrue(search(1) is RouteSearchResult.Found)
    }

    @Test
    fun `value route either accepts event but prefers relic on an equal score tie`() {
        val nodes = listOf(
            valueNode(5, 1, 1, 501, 1, next = listOf(511, 512)),
            valueNode(5, 2, 1, 511, 5, next = listOf(521)),
            valueNode(5, 2, 2, 512, 6, next = listOf(521)),
            valueNode(5, 3, 1, 521, 8, questId = 770530301),
        )
        val result = LabyrinthValueRouteEvaluator(
            LabyrinthRoutePolicy(
                evaluationMode = LabyrinthRouteEvaluationMode.VALUE_ROUTE,
                valueAllowance = 0,
                thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER,
                area3BossIds = emptySet(),
                area5BossIds = emptySet(),
            ),
        ).best(5, nodes)

        requireNotNull(result)
        assertEquals(0, result.shortfall)
        assertTrue(512L in result.route.map { it.blockId })
        assertFalse(511L in result.route.map { it.blockId })
    }

    private fun route(area: Int, bossQuest: Int?): List<LabyrinthMapNode> {
        val requirements = requireNotNull(LabyrinthRoutePolicy.AREA_REQUIREMENTS[area])
        val lastColumn = requirements.keys.max()
        return requirements.map { (column, blockType) ->
            val blockId = area * 100L + column
            LabyrinthMapNode(
                area = area,
                column = column,
                row = 1,
                blockId = blockId,
                blockType = blockType,
                questId = bossQuest.takeIf { column == lastColumn },
                nextBlockIds = if (column == lastColumn) emptyList() else listOf(blockId + 1),
                isAreaLastPoint = column == lastColumn,
            )
        }
    }

    private fun valueNode(
        area: Int,
        column: Int,
        row: Int,
        blockId: Long,
        blockType: Int,
        next: List<Long> = emptyList(),
        questId: Int? = null,
    ) = LabyrinthMapNode(
        area = area,
        column = column,
        row = row,
        blockId = blockId,
        blockType = blockType,
        questId = questId,
        nextBlockIds = next,
        isAreaLastPoint = next.isEmpty(),
    )
}
