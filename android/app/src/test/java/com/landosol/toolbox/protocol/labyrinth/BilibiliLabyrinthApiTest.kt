package com.landosol.toolbox.protocol.labyrinth

import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.bilibili.GameAccountProfile
import com.landosol.toolbox.protocol.bilibili.GameSessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliLabyrinthApiTest {
    @Test fun `enhancement node parser keeps unknown distinct from confirmed empty`() {
        assertEquals(emptySet<Int>(), parseLabyrinthEnhanceNodeIds(emptyList<Int>()))
        assertEquals(setOf(1, 31), parseLabyrinthEnhanceNodeIds(listOf(1L, "31", 31)))
        for (invalid in listOf(null, "31", listOf(31, -1), listOf(31, 0), listOf(31, 1.5),
            listOf(31, Long.MAX_VALUE), listOf(mapOf("node_id" to 31)))) {
            assertEquals(null, parseLabyrinthEnhanceNodeIds(invalid))
        }
    }

    @Test fun `top response forwards acquired enhancement nodes without changing requests`() = runTest {
        val session = FakeSession(ArrayDeque(listOf(GameSessionResult.Success(mapOf(
            "guild_id" to 6L, "labyrinth_enhance_node_list" to listOf(31L, 2L))))))
        val top = (BilibiliLabyrinthApi(session).top() as LabyrinthOperationResult.Success).value
        assertEquals(setOf(31, 2), top.enhanceNodeIds)
        assertEquals(6, top.guildId)
        assertEquals(listOf("labyrinth/top"), session.paths)
        assertTrue(session.fields.single().isEmpty())
    }

    @Test
    fun `parses top enter map and retire requests`() = runTest {
        val session = FakeSession(
            ArrayDeque(
                listOf(
                    GameSessionResult.Success(
                        mapOf(
                            "enter_id" to 0L,
                            "guild_cleared_difficulty_list" to listOf(
                                mapOf("guild_id" to 101L, "difficulty" to 2L),
                            ),
                        ),
                    ),
                    GameSessionResult.Success(
                        mapOf(
                            "enter_id" to 700L,
                            "map_list" to listOf(
                                mapOf(
                                    "area" to 1L,
                                    "column" to 1L,
                                    "row" to 0L,
                                    "block_id" to 10L,
                                    "block_type" to 3L,
                                    "quest_id" to 1001L,
                                    "next_block_id_list" to listOf(20L),
                                    "is_area_last_point" to true,
                                ),
                            ),
                        ),
                    ),
                    GameSessionResult.Success(
                        mapOf(
                            "guild_id" to 101L,
                            "block_id" to 10L,
                            "map_list" to listOf(
                                mapOf(
                                    "area" to 1L,
                                    "column" to 1L,
                                    "row" to 0L,
                                    "block_id" to 10L,
                                    "block_type" to 3L,
                                    "next_block_id_list" to listOf(20L),
                                    "IsAreaLastPoint" to false,
                                ),
                            ),
                        ),
                    ),
                    GameSessionResult.Success(emptyMap()),
                ),
            ),
        )
        val api = BilibiliLabyrinthApi(session)

        val top = api.top()
        val enter = api.enter(LabyrinthEnterRequest(101, 2))
        val resume = api.resume(700)
        val retire = api.retire(700)

        assertTrue(top is LabyrinthOperationResult.Success)
        assertEquals(101, (top as LabyrinthOperationResult.Success).value.clearedDifficulties.single().guildId)
        assertTrue(enter is LabyrinthOperationResult.Success)
        assertEquals(listOf(20L), (enter as LabyrinthOperationResult.Success).value.map.single().nextBlockIds)
        assertTrue(enter.value.map.single().isAreaLastPoint)
        assertTrue(resume is LabyrinthOperationResult.Success)
        assertEquals(10L, (resume as LabyrinthOperationResult.Success).value.currentBlockId)
        assertEquals(101, resume.value.guildId)
        assertTrue(retire is LabyrinthOperationResult.Success)
        assertEquals(listOf("labyrinth/top", "labyrinth/enter", "labyrinth/resume", "labyrinth/retire"), session.paths)
        assertEquals(101L, session.fields[1]["guild_id"])
        assertEquals(700L, session.fields[2]["enter_id"])
    }

    @Test
    fun `preserves network failure kind for safe workflow recovery`() = runTest {
        val api = BilibiliLabyrinthApi(
            FakeSession(ArrayDeque(listOf(GameSessionResult.NetworkFailure("连接被重置")))),
        )

        val result = api.top()

        assertTrue(result is LabyrinthOperationResult.Failure)
        assertEquals(LabyrinthFailureKind.NETWORK, (result as LabyrinthOperationResult.Failure).kind)
    }

    private class FakeSession(
        private val results: ArrayDeque<GameSessionResult>,
    ) : BilibiliGameSession {
        override val profile = GameAccountProfile(1, "Fixture", 1, "11.4.0")
        val paths = mutableListOf<String>()
        val fields = mutableListOf<LinkedHashMap<String, Any?>>()

        override suspend fun request(
            path: String,
            fields: LinkedHashMap<String, Any?>,
        ): GameSessionResult {
            paths += path
            this.fields += LinkedHashMap(fields)
            return results.removeFirst()
        }
    }
}
