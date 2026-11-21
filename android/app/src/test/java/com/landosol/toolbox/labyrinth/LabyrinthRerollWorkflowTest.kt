package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.ClearedDifficulty
import com.landosol.toolbox.protocol.labyrinth.LabyrinthApi
import com.landosol.toolbox.protocol.labyrinth.LabyrinthEnter
import com.landosol.toolbox.protocol.labyrinth.LabyrinthEnterRequest
import com.landosol.toolbox.protocol.labyrinth.LabyrinthFailureKind
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOperationResult
import com.landosol.toolbox.protocol.labyrinth.LabyrinthResume
import com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute
import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRerollWorkflowTest {
    @Test fun `new guild generation saves grant evidence with the matching opening`() = runTest {
        for (guild in 6..10) {
            val api = FakeApi(top = LabyrinthTop(null, null, null, listOf(ClearedDifficulty(guild, 1)), setOf(31)),
                enters = ArrayDeque(listOf(matchingEnter(guild.toLong()))))
            val store = FakeRouteStore()
            val result = LabyrinthRerollWorkflow(api, store).run(config().copy(guildId = guild)) as LabyrinthRerollResult.Success
            assertEquals(com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState(guild, setOf(31)), result.route.openingRelics)
            assertEquals(result.route.openingRelics, store.route?.openingRelics)
            assertTrue(api.retired.isEmpty())
        }
    }

    @Test fun `resume and interrupted entry use the actual recovered guild and current node evidence`() = runTest {
        for (interrupted in listOf(false, true)) {
            val top = LabyrinthTop(777, 6, 2, listOf(ClearedDifficulty(6, 1)), emptySet())
            val api = FakeApi(top = top, topResults = if (interrupted) ArrayDeque(listOf(
                LabyrinthOperationResult.Success(top.copy(enterId = null)), LabyrinthOperationResult.Success(top))) else ArrayDeque(),
                enterResults = if (interrupted) ArrayDeque(listOf(LabyrinthOperationResult.Failure("network", LabyrinthFailureKind.NETWORK))) else ArrayDeque(),
                resumes = ArrayDeque(listOf(LabyrinthResume(777, 6, null, matchingEnter(777).map))))
            val store = FakeRouteStore()
            val result = LabyrinthRerollWorkflow(api, store, pause = {}).run(config().copy(guildId = 6)) as LabyrinthRerollResult.Success
            assertEquals(com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState(6, emptySet()), result.route.openingRelics)
            assertEquals(777L, store.route?.enterId)
            assertTrue(api.retired.isEmpty())
        }
    }

    @Test
    fun `retires unmatched map and saves matching route`() = runTest {
        val api = FakeApi(
            top = LabyrinthTop(null, null, null, listOf(ClearedDifficulty(101, 1))),
            enters = ArrayDeque(
                listOf(
                    LabyrinthEnter(1, fullMap(area3BossQuest = 770330101, area5BossQuest = 770530101)),
                    LabyrinthEnter(2, fullMap(area3BossQuest = 770330401, area5BossQuest = 770530301)),
                ),
            ),
        )
        val store = FakeRouteStore()
        val progress = mutableListOf<LabyrinthRerollProgress>()

        val result = LabyrinthRerollWorkflow(api, store).run(config()) { progress += it }

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
        assertEquals(listOf(1L), api.retired)
        assertEquals(2L, store.route?.enterId)
        assertEquals(2, api.topCalls)
        assertTrue(progress.any { it.stage.contains("撤退") })
    }

    @Test
    fun `does not retire existing run without explicit permission`() = runTest {
        val api = FakeApi(
            top = LabyrinthTop(99, 101, 2, emptyList()),
            enters = ArrayDeque(),
            resumes = ArrayDeque(
                listOf(
                    LabyrinthResume(
                        enterId = 99,
                        guildId = 101,
                        currentBlockId = null,
                        map = fullMap(area3BossQuest = 770330101, area5BossQuest = 770530101),
                    ),
                ),
            ),
        )

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore()).run(config(retireExisting = false))

        assertEquals(LabyrinthRerollResult.NeedsExistingRunDecision(99), result)
        assertTrue(api.retired.isEmpty())
    }

    @Test
    fun `reuses matching existing run without retiring it`() = runTest {
        val map = fullMap(area3BossQuest = 770330401, area5BossQuest = 770530301)
        val api = FakeApi(
            top = LabyrinthTop(99, 101, 2, emptyList()),
            resumes = ArrayDeque(listOf(LabyrinthResume(99, 101, null, map))),
        )
        val store = FakeRouteStore()

        val result = LabyrinthRerollWorkflow(api, store).run(config(retireExisting = false))

        assertTrue(result is LabyrinthRerollResult.Success)
        assertTrue((result as LabyrinthRerollResult.Success).resumedExisting)
        assertEquals(99L, result.route.enterId)
        assertEquals(map, store.savedAllNodes)
        assertTrue(api.retired.isEmpty())
    }

    @Test
    fun `abandoning retires a matching existing run instead of resuming it`() = runTest {
        // 2026-09-17: a perfect opening lost in battle still matches the target route; the
        // failure reroll must not hand the same enterId back at 0 分 0 秒.
        val map = fullMap(area3BossQuest = 770330401, area5BossQuest = 770530301)
        val api = FakeApi(
            top = LabyrinthTop(99, 101, 2, listOf(ClearedDifficulty(101, 1))),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(LabyrinthTop(99, 101, 2, listOf(ClearedDifficulty(101, 1)))),
                    LabyrinthOperationResult.Success(unlockedTop()),
                ),
            ),
            resumes = ArrayDeque(listOf(LabyrinthResume(99, 101, null, map))),
            enters = ArrayDeque(listOf(LabyrinthEnter(2, map))),
        )
        val store = FakeRouteStore()

        val result = LabyrinthRerollWorkflow(api, store).run(config(abandonExisting = true))

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(false, (result as LabyrinthRerollResult.Success).resumedExisting)
        assertEquals(listOf(99L), api.retired)
        assertEquals(2L, result.route.enterId)
    }

    @Test
    fun `reroll until found ignores finite attempt limit`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            enters = ArrayDeque(listOf(unmatchedEnter(1), matchingEnter(2))),
        )

        val progress = mutableListOf<LabyrinthRerollProgress>()
        val result = LabyrinthRerollWorkflow(api, FakeRouteStore()).run(
            config(maxAttempts = 1, rerollUntilFound = true),
        ) { progress += it }

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
        assertTrue(progress.all { it.rerollUntilFound })
    }

    @Test
    fun `accepts 800 attempts and rejects values above limit`() = runTest {
        val success = LabyrinthRerollWorkflow(
            FakeApi(top = unlockedTop(), enters = ArrayDeque(listOf(matchingEnter(1)))),
            FakeRouteStore(),
        ).run(config(maxAttempts = 800))

        val failure = runCatching {
            LabyrinthRerollWorkflow(
                FakeApi(top = unlockedTop()),
                FakeRouteStore(),
            ).run(config(maxAttempts = 801))
        }.exceptionOrNull()

        assertTrue(success is LabyrinthRerollResult.Success)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `network interrupted enter continues only after top confirms no active run`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Success(unlockedTop()),
                ),
            ),
            enterResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Failure("连接重置", LabyrinthFailureKind.NETWORK),
                    LabyrinthOperationResult.Success(matchingEnter(2)),
                ),
            ),
        )
        val pauses = mutableListOf<Long>()

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = { pauses += it }).run(
            config(maxAttempts = 2),
        )

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
        assertTrue(1_000L in pauses)
        assertTrue(300L !in pauses)
    }

    @Test
    fun `network interrupted enter recovers matching generated run`() = runTest {
        val active = unlockedTop().copy(enterId = 777, guildId = 101, difficulty = 2)
        val map = fullMap(area3BossQuest = 770330401, area5BossQuest = 770530301)
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Success(active),
                ),
            ),
            enterResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("连接重置", LabyrinthFailureKind.NETWORK)),
            ),
            resumes = ArrayDeque(listOf(LabyrinthResume(777, 101, null, map))),
        )
        val store = FakeRouteStore()
        val checkpoints = FakeCheckpointStore()

        val result = LabyrinthRerollWorkflow(
            api = api,
            routeStore = store,
            pause = {},
            checkpointStore = checkpoints,
        ).run(config())

        assertTrue(result is LabyrinthRerollResult.Success)
        assertTrue((result as LabyrinthRerollResult.Success).resumedExisting)
        assertEquals(777L, store.route?.enterId)
        assertEquals(LabyrinthRouteVerdict.TARGET, checkpoints.value?.verdict)
        assertEquals(777L, checkpoints.value?.enterId)
        assertTrue(api.retired.isEmpty())
    }

    @Test
    fun `network interrupted enter keeps pending checkpoint when server state cannot be read`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Failure("断开 1", LabyrinthFailureKind.NETWORK),
                    LabyrinthOperationResult.Failure("断开 2", LabyrinthFailureKind.NETWORK),
                    LabyrinthOperationResult.Failure("断开 3", LabyrinthFailureKind.NETWORK),
                ),
            ),
            enterResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("连接重置", LabyrinthFailureKind.NETWORK)),
            ),
        )
        val checkpoints = FakeCheckpointStore()

        val result = LabyrinthRerollWorkflow(
            api = api,
            routeStore = FakeRouteStore(),
            pause = {},
            checkpointStore = checkpoints,
            clock = { 123L },
        ).run(config())

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertEquals(LabyrinthRouteVerdict.PENDING_VERIFICATION, checkpoints.value?.verdict)
        assertEquals(1, checkpoints.value?.attempt)
        assertEquals(null, checkpoints.value?.enterId)
        assertEquals(123L, checkpoints.value?.updatedAt)
        assertTrue(api.retired.isEmpty())
    }

    @Test
    fun `network interrupted enter marks recovered non target run without retiring it`() = runTest {
        val active = unlockedTop().copy(enterId = 778, guildId = 101, difficulty = 2)
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Success(active),
                ),
            ),
            enterResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("连接重置", LabyrinthFailureKind.NETWORK)),
            ),
            resumes = ArrayDeque(
                listOf(
                    LabyrinthResume(
                        778,
                        101,
                        null,
                        fullMap(area3BossQuest = 770330101, area5BossQuest = 770530101),
                    ),
                ),
            ),
        )
        val checkpoints = FakeCheckpointStore()

        val result = LabyrinthRerollWorkflow(
            api = api,
            routeStore = FakeRouteStore(),
            pause = {},
            checkpointStore = checkpoints,
        ).run(config())

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertEquals(LabyrinthRouteVerdict.NOT_TARGET, checkpoints.value?.verdict)
        assertEquals(778L, checkpoints.value?.enterId)
        assertTrue(api.retired.isEmpty())
    }

    @Test
    fun `network interrupted retire continues after top confirms run is gone`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            enters = ArrayDeque(listOf(unmatchedEnter(1), matchingEnter(2))),
            retireResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("连接重置", LabyrinthFailureKind.NETWORK)),
            ),
        )

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = {}).run(config(maxAttempts = 2))

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
        assertEquals(2, api.topCalls)
    }

    @Test
    fun `same non target run is retired again after confirmed stale server state`() = runTest {
        val active = unlockedTop().copy(enterId = 1, guildId = 101, difficulty = 2)
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Success(active),
                    LabyrinthOperationResult.Success(active),
                    LabyrinthOperationResult.Success(active),
                    LabyrinthOperationResult.Success(unlockedTop()),
                ),
            ),
            enters = ArrayDeque(listOf(unmatchedEnter(1), matchingEnter(2))),
        )
        val pauses = mutableListOf<Long>()

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = { pauses += it }).run(
            config(maxAttempts = 2, rerollUntilFound = true),
        )

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
        assertEquals(listOf(1L, 1L), api.retired)
        assertEquals(5, api.topCalls)
        assertTrue(1_000L in pauses)
    }

    @Test
    fun `different run after retirement stops without retrying side effect`() = runTest {
        val different = unlockedTop().copy(enterId = 2, guildId = 101, difficulty = 2)
        val checkpoints = FakeCheckpointStore()
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Success(unlockedTop()),
                    LabyrinthOperationResult.Success(different),
                ),
            ),
            enters = ArrayDeque(listOf(unmatchedEnter(1))),
        )

        val result = LabyrinthRerollWorkflow(
            api = api,
            routeStore = FakeRouteStore(),
            pause = {},
            checkpointStore = checkpoints,
        ).run(config(rerollUntilFound = true))

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertTrue((result as LabyrinthRerollResult.Failure).message.contains("不同"))
        assertEquals(listOf(1L), api.retired)
        assertEquals(LabyrinthRouteVerdict.PENDING_VERIFICATION, checkpoints.value?.verdict)
        assertEquals(2L, checkpoints.value?.enterId)
    }

    @Test
    fun `persistent same run stops after bounded retirement retries`() = runTest {
        val active = unlockedTop().copy(enterId = 1, guildId = 101, difficulty = 2)
        val checkpoints = FakeCheckpointStore()
        val api = FakeApi(
            top = active,
            topResults = ArrayDeque(listOf(LabyrinthOperationResult.Success(unlockedTop()))),
            enters = ArrayDeque(listOf(unmatchedEnter(1))),
        )

        val result = LabyrinthRerollWorkflow(
            api = api,
            routeStore = FakeRouteStore(),
            pause = {},
            checkpointStore = checkpoints,
        ).run(config(rerollUntilFound = true))

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertTrue((result as LabyrinthRerollResult.Failure).message.contains("连续尝试撤退 3 次"))
        assertEquals(listOf(1L, 1L, 1L), api.retired)
        assertEquals(LabyrinthRouteVerdict.NOT_TARGET, checkpoints.value?.verdict)
        assertEquals(1L, checkpoints.value?.enterId)
    }

    @Test
    fun `read only top retries network failures with backoff`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(
                    LabyrinthOperationResult.Failure("断开", LabyrinthFailureKind.NETWORK),
                    LabyrinthOperationResult.Failure("断开", LabyrinthFailureKind.NETWORK),
                    LabyrinthOperationResult.Success(unlockedTop()),
                ),
            ),
            enters = ArrayDeque(listOf(matchingEnter(1))),
        )
        val pauses = mutableListOf<Long>()

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = { pauses += it }).run(config())

        assertTrue(result is LabyrinthRerollResult.Success)
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }

    @Test
    fun `happy path rerolls do not add fixed pacing delays`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            enters = ArrayDeque((1L..100L).map(::unmatchedEnter)),
        )
        val pauses = mutableListOf<Long>()

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = { pauses += it }).run(
            config(maxAttempts = 100),
        )

        assertEquals(LabyrinthRerollResult.Exhausted(100), result)
        assertEquals(101, api.topCalls)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `rejected enter failure carries rejected kind`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            enterResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("会话失效", LabyrinthFailureKind.REJECTED)),
            ),
        )

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = {}).run(config())

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertEquals(
            LabyrinthFailureKind.REJECTED,
            (result as LabyrinthRerollResult.Failure).kind,
        )
    }

    @Test
    fun `rejected top failure carries rejected kind`() = runTest {
        val api = FakeApi(
            top = unlockedTop(),
            topResults = ArrayDeque(
                listOf(LabyrinthOperationResult.Failure("会话失效", LabyrinthFailureKind.REJECTED)),
            ),
        )

        val result = LabyrinthRerollWorkflow(api, FakeRouteStore(), pause = {}).run(config())

        assertTrue(result is LabyrinthRerollResult.Failure)
        assertEquals(
            LabyrinthFailureKind.REJECTED,
            (result as LabyrinthRerollResult.Failure).kind,
        )
    }

    private fun config(
        retireExisting: Boolean = true,
        maxAttempts: Int = 3,
        rerollUntilFound: Boolean = false,
        abandonExisting: Boolean = false,
    ) = LabyrinthRerollConfig(
        accountId = 7,
        guildId = 101,
        difficulty = 2,
        maxAttempts = maxAttempts,
        retireExisting = retireExisting,
        rerollUntilFound = rerollUntilFound,
        abandonExisting = abandonExisting,
    )

    private fun unlockedTop() = LabyrinthTop(null, null, null, listOf(ClearedDifficulty(101, 1)))

    private fun unmatchedEnter(id: Long) =
        LabyrinthEnter(id, fullMap(area3BossQuest = 770330101, area5BossQuest = 770530101))

    private fun matchingEnter(id: Long) =
        LabyrinthEnter(id, fullMap(area3BossQuest = 770330401, area5BossQuest = 770530301))

    private fun fullMap(area3BossQuest: Int, area5BossQuest: Int): List<LabyrinthMapNode> =
        LabyrinthRoutePolicy.AREA_REQUIREMENTS.flatMap { (area, requirements) ->
            requirements.map { (column, blockType) ->
                val blockId = area * 100L + column
                val lastColumn = requirements.keys.max()
                LabyrinthMapNode(
                    area = area,
                    column = column,
                    row = 1,
                    blockId = blockId,
                    blockType = blockType,
                    questId = when (area) {
                        3 -> area3BossQuest.takeIf { column == lastColumn }
                        5 -> area5BossQuest.takeIf { column == lastColumn }
                        else -> null
                    },
                    nextBlockIds = if (column == lastColumn) emptyList() else listOf(blockId + 1),
                    isAreaLastPoint = column == lastColumn,
                )
            }
        }

    private class FakeRouteStore : LabyrinthRouteStore {
        var route: LabyrinthRoute? = null
        var savedConfig: LabyrinthRerollConfig? = null
        var savedAttempt: Int? = null
        var savedAllNodes: List<LabyrinthMapNode> = emptyList()
        var savedCurrentBlockId: Long? = null

        override suspend fun save(
            config: LabyrinthRerollConfig,
            route: LabyrinthRoute,
            attempt: Int,
            allNodes: List<LabyrinthMapNode>,
            currentBlockId: Long?,
        ) {
            this.route = route
            this.savedConfig = config
            this.savedAttempt = attempt
            this.savedAllNodes = allNodes
            this.savedCurrentBlockId = currentBlockId
        }

        override suspend fun loadLatest(accountId: Long): LabyrinthRouteJson? = null
    }

    private class FakeCheckpointStore : LabyrinthRerollCheckpointStore {
        var value: LabyrinthRerollCheckpoint? = null

        override suspend fun save(checkpoint: LabyrinthRerollCheckpoint) {
            value = checkpoint
        }

        override suspend fun load(accountId: Long): LabyrinthRerollCheckpoint? =
            value?.takeIf { it.accountId == accountId }

        override suspend fun clear(accountId: Long) {
            if (value?.accountId == accountId) value = null
        }
    }

    private class FakeApi(
        private val top: LabyrinthTop,
        private val enters: ArrayDeque<LabyrinthEnter> = ArrayDeque(),
        private val resumes: ArrayDeque<LabyrinthResume> = ArrayDeque(),
        private val topResults: ArrayDeque<LabyrinthOperationResult<LabyrinthTop>> = ArrayDeque(),
        private val enterResults: ArrayDeque<LabyrinthOperationResult<LabyrinthEnter>> = ArrayDeque(),
        private val resumeResults: ArrayDeque<LabyrinthOperationResult<LabyrinthResume>> = ArrayDeque(),
        private val retireResults: ArrayDeque<LabyrinthOperationResult<Unit>> = ArrayDeque(),
    ) : LabyrinthApi {
        val retired = mutableListOf<Long>()
        var topCalls = 0

        override suspend fun top(): LabyrinthOperationResult<LabyrinthTop> {
            topCalls += 1
            return if (topResults.isEmpty()) LabyrinthOperationResult.Success(top) else topResults.removeFirst()
        }

        override suspend fun enter(request: LabyrinthEnterRequest): LabyrinthOperationResult<LabyrinthEnter> = when {
            enterResults.isNotEmpty() -> enterResults.removeFirst()
            else -> LabyrinthOperationResult.Success(enters.removeFirst())
        }

        override suspend fun resume(enterId: Long): LabyrinthOperationResult<LabyrinthResume> = when {
            resumeResults.isNotEmpty() -> resumeResults.removeFirst()
            else -> LabyrinthOperationResult.Success(resumes.removeFirst())
        }

        override suspend fun retire(enterId: Long): LabyrinthOperationResult<Unit> {
            retired += enterId
            return if (retireResults.isEmpty()) {
                LabyrinthOperationResult.Success(Unit)
            } else {
                retireResults.removeFirst()
            }
        }
    }
}
