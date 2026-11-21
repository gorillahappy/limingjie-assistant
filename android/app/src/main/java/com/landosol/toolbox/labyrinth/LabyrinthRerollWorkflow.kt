package com.landosol.toolbox.labyrinth

import androidx.room.withTransaction
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.data.local.LabyrinthRerollCheckpointEntity
import com.landosol.toolbox.data.local.LabyrinthRouteEntity
import com.landosol.toolbox.protocol.labyrinth.LabyrinthApi
import com.landosol.toolbox.protocol.labyrinth.LabyrinthEnterRequest
import com.landosol.toolbox.protocol.labyrinth.LabyrinthFailureKind
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOperationResult
import com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute
import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

data class LabyrinthRerollConfig(
    val accountId: Long,
    val guildId: Int,
    val difficulty: Int,
    @Deprecated("目标 Block 已由原版路线条件取代；仅为旧配置兼容保留")
    val targetBlockId: Long? = null,
    val maxAttempts: Int,
    val retireExisting: Boolean,
    val routePolicy: LabyrinthRoutePolicy = LabyrinthRoutePolicy(),
    val rerollUntilFound: Boolean = false,
    /**
     * Never reuse the run the server currently holds, even when it matches the target route.
     * [retireExisting] alone only retires a *non-matching* run: an interactive reroll that finds
     * the account already sitting on a target opening keeps it. A run that was just lost in
     * battle also still "matches" (2026-09-17: perfect opening, EX lost, reroll resumed the same
     * enterId at 0 分 0 秒), so the failure-reroll and batch paths must abandon it outright.
     */
    val abandonExisting: Boolean = false,
)

data class LabyrinthRerollProgress(
    val attempt: Int,
    val maxAttempts: Int,
    val stage: String,
    val rerollUntilFound: Boolean = false,
)

sealed interface LabyrinthRerollResult {
    data class Success(
        val route: LabyrinthRoute,
        val attempt: Int,
        val resumedExisting: Boolean = false,
    ) : LabyrinthRerollResult
    data class NeedsExistingRunDecision(val enterId: Long) : LabyrinthRerollResult
    data class Exhausted(val attempts: Int) : LabyrinthRerollResult
    data class Failure(
        val message: String,
        val kind: LabyrinthFailureKind = LabyrinthFailureKind.UNKNOWN,
    ) : LabyrinthRerollResult
}

@Serializable
data class LabyrinthNodeJson(
    val area: Int,
    val column: Int,
    val row: Int,
    val blockId: Long,
    val blockType: Int,
    val questId: Int?,
    val nextBlockIds: List<Long>,
    val isAreaLastPoint: Boolean,
)

@Serializable
data class LabyrinthRouteJson(
    val enterId: Long,
    val guildId: Int,
    val difficulty: Int,
    val attempt: Int,
    val rerollUntilFound: Boolean,
    val perfectStart: Boolean,
    val thirdBlockChoice: String,
    val area3BossIds: List<Int>,
    val area5BossIds: List<Int>,
    val nodes: List<LabyrinthNodeJson>,
    val allNodes: List<LabyrinthNodeJson> = emptyList(),
    val currentBlockId: Long? = null,
    val routeEvaluationMode: String = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE.name,
    val valueAllowance: Int = 0,
    val openingRelics: LabyrinthOpeningRelicState? = null,
) {
    val blockIds: List<Long> get() = nodes.map(LabyrinthNodeJson::blockId)

    fun toLabyrinthRoute(): LabyrinthRoute = LabyrinthRoute(
        enterId = enterId,
        nodes = nodes.map { it.toLabyrinthMapNode() },
        openingRelics = openingRelics?.takeIf { it.guildId == guildId },
    )

    fun toFullMap(): List<LabyrinthMapNode> = allNodes.map { it.toLabyrinthMapNode() }
}

fun LabyrinthNodeJson.toLabyrinthMapNode(): LabyrinthMapNode = LabyrinthMapNode(
    area = area,
    column = column,
    row = row,
    blockId = blockId,
    blockType = blockType,
    questId = questId,
    nextBlockIds = nextBlockIds,
    isAreaLastPoint = isAreaLastPoint,
)

/** Missing legacy metadata is unknown; re-reading the current run supplies it without guessing. */
internal fun LabyrinthRouteJson.openingRelicState(): LabyrinthOpeningRelicState? =
    openingRelics?.takeIf { it.guildId == guildId }

interface LabyrinthRouteStore {
    suspend fun save(
        config: LabyrinthRerollConfig,
        route: LabyrinthRoute,
        attempt: Int,
        allNodes: List<LabyrinthMapNode> = emptyList(),
        currentBlockId: Long? = null,
    )
    suspend fun loadLatest(accountId: Long): LabyrinthRouteJson?

    /** Advances only the latest matching opening; implementations must reject route regression. */
    suspend fun updateCurrentBlock(accountId: Long, enterId: Long, currentBlockId: Long): Boolean = false
}

internal fun LabyrinthRouteJson.withAdvancedCurrentBlock(currentBlockId: Long): LabyrinthRouteJson? {
    val nextIndex = blockIds.indexOf(currentBlockId)
    if (nextIndex < 0) return null
    val savedIndex = this.currentBlockId?.let { blockIds.indexOf(it) } ?: -1
    if (savedIndex >= 0 && nextIndex < savedIndex) return null
    return copy(currentBlockId = currentBlockId)
}

enum class LabyrinthRouteVerdict(val label: String) {
    PENDING_VERIFICATION("待联网验证"),
    TARGET("目标路线"),
    NOT_TARGET("非目标路线"),
}

data class LabyrinthRerollCheckpoint(
    val accountId: Long,
    val attempt: Int,
    val enterId: Long?,
    val guildId: Int,
    val difficulty: Int,
    val policy: LabyrinthRoutePolicy,
    val verdict: LabyrinthRouteVerdict,
    val message: String?,
    val updatedAt: Long,
)

interface LabyrinthRerollCheckpointStore {
    suspend fun save(checkpoint: LabyrinthRerollCheckpoint)
    suspend fun load(accountId: Long): LabyrinthRerollCheckpoint?
    suspend fun clear(accountId: Long)
}

@Serializable
private data class LabyrinthRoutePolicyJson(
    val requiredBlockIds: List<Long>,
    val forbiddenBlockIds: List<Long>,
    val requiredBlockTypes: List<Int>,
    val forbiddenBlockTypes: List<Int>,
    val requiredAreas: List<Int>,
    val perfectStart: Boolean,
    val thirdBlockChoice: String,
    val area3BossIds: List<Int>,
    val area5BossIds: List<Int>,
    val routeEvaluationMode: String = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE.name,
    val valueAllowance: Int = 0,
)

class RoomLabyrinthRerollCheckpointStore(
    private val database: AppDatabase,
) : LabyrinthRerollCheckpointStore {
    override suspend fun save(checkpoint: LabyrinthRerollCheckpoint) {
        database.labyrinthRerollCheckpointDao().upsert(
            LabyrinthRerollCheckpointEntity(
                accountId = checkpoint.accountId,
                attemptCount = checkpoint.attempt,
                enterId = checkpoint.enterId,
                guildId = checkpoint.guildId,
                difficulty = checkpoint.difficulty,
                policyJson = Json.encodeToString(
                    LabyrinthRoutePolicyJson.serializer(),
                    checkpoint.policy.toJson(),
                ),
                status = checkpoint.verdict.name,
                message = checkpoint.message,
                updatedAt = checkpoint.updatedAt,
            ),
        )
    }

    override suspend fun load(accountId: Long): LabyrinthRerollCheckpoint? {
        val entity = database.labyrinthRerollCheckpointDao().get(accountId) ?: return null
        return runCatching {
            LabyrinthRerollCheckpoint(
                accountId = entity.accountId,
                attempt = entity.attemptCount,
                enterId = entity.enterId,
                guildId = entity.guildId,
                difficulty = entity.difficulty,
                policy = Json.decodeFromString(
                    LabyrinthRoutePolicyJson.serializer(),
                    entity.policyJson,
                ).toDomain(),
                verdict = LabyrinthRouteVerdict.valueOf(entity.status),
                message = entity.message,
                updatedAt = entity.updatedAt,
            )
        }.getOrNull()
    }

    override suspend fun clear(accountId: Long) {
        database.labyrinthRerollCheckpointDao().delete(accountId)
    }
}

private fun LabyrinthRoutePolicy.toJson() = LabyrinthRoutePolicyJson(
    requiredBlockIds = requiredBlockIds.sorted(),
    forbiddenBlockIds = forbiddenBlockIds.sorted(),
    requiredBlockTypes = requiredBlockTypes.sorted(),
    forbiddenBlockTypes = forbiddenBlockTypes.sorted(),
    requiredAreas = requiredAreas.sorted(),
    perfectStart = perfectStart,
    thirdBlockChoice = thirdBlockChoice.name,
    area3BossIds = area3BossIds.sorted(),
    area5BossIds = area5BossIds.sorted(),
    routeEvaluationMode = evaluationMode.name,
    valueAllowance = valueAllowance,
)

private fun LabyrinthRoutePolicyJson.toDomain() = LabyrinthRoutePolicy(
    requiredBlockIds = requiredBlockIds.toSet(),
    forbiddenBlockIds = forbiddenBlockIds.toSet(),
    requiredBlockTypes = requiredBlockTypes.toSet(),
    forbiddenBlockTypes = forbiddenBlockTypes.toSet(),
    requiredAreas = requiredAreas.toSet(),
    perfectStart = perfectStart,
    evaluationMode = runCatching { LabyrinthRouteEvaluationMode.valueOf(routeEvaluationMode) }
        .getOrDefault(LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE),
    valueAllowance = valueAllowance.coerceIn(0, LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE),
    thirdBlockChoice = LabyrinthThirdBlockChoice.valueOf(thirdBlockChoice),
    area3BossIds = area3BossIds.toSet(),
    area5BossIds = area5BossIds.toSet(),
)

class RoomLabyrinthRouteStore(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : LabyrinthRouteStore {
    override suspend fun save(
        config: LabyrinthRerollConfig,
        route: LabyrinthRoute,
        attempt: Int,
        allNodes: List<LabyrinthMapNode>,
        currentBlockId: Long?,
    ) {
        val routeJson = LabyrinthRouteJson(
            enterId = route.enterId,
            guildId = config.guildId,
            difficulty = config.difficulty,
            attempt = attempt,
            rerollUntilFound = config.rerollUntilFound,
            perfectStart = config.routePolicy.perfectStart,
            thirdBlockChoice = config.routePolicy.thirdBlockChoice.name,
            area3BossIds = config.routePolicy.area3BossIds.sorted(),
            area5BossIds = config.routePolicy.area5BossIds.sorted(),
            nodes = route.nodes.map { node ->
                LabyrinthNodeJson(
                    area = node.area,
                    column = node.column,
                    row = node.row,
                    blockId = node.blockId,
                    blockType = node.blockType,
                    questId = node.questId,
                    nextBlockIds = node.nextBlockIds,
                    isAreaLastPoint = node.isAreaLastPoint,
                )
            },
            allNodes = allNodes.map { node ->
                LabyrinthNodeJson(
                    area = node.area,
                    column = node.column,
                    row = node.row,
                    blockId = node.blockId,
                    blockType = node.blockType,
                    questId = node.questId,
                    nextBlockIds = node.nextBlockIds,
                    isAreaLastPoint = node.isAreaLastPoint,
                )
            },
            currentBlockId = currentBlockId,
            routeEvaluationMode = config.routePolicy.evaluationMode.name,
            valueAllowance = config.routePolicy.valueAllowance,
            openingRelics = route.openingRelics?.takeIf { it.guildId == config.guildId },
        )
        database.labyrinthRouteDao().insert(
            LabyrinthRouteEntity(
                accountId = config.accountId,
                runId = null,
                enterId = route.enterId,
                difficulty = config.difficulty,
                attemptCount = attempt,
                routeJson = Json.encodeToString(LabyrinthRouteJson.serializer(), routeJson),
                createdAt = clock(),
            ),
        )
    }

    override suspend fun loadLatest(accountId: Long): LabyrinthRouteJson? {
        val entity = database.labyrinthRouteDao().getLatestByAccount(accountId) ?: return null
        return try {
            Json.decodeFromString(LabyrinthRouteJson.serializer(), entity.routeJson)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun updateCurrentBlock(
        accountId: Long,
        enterId: Long,
        currentBlockId: Long,
    ): Boolean = database.withTransaction {
        val dao = database.labyrinthRouteDao()
        val entity = dao.getLatestByAccount(accountId) ?: return@withTransaction false
        if (entity.enterId != enterId) return@withTransaction false
        val saved = runCatching {
            Json.decodeFromString(LabyrinthRouteJson.serializer(), entity.routeJson)
        }.getOrNull() ?: return@withTransaction false
        if (saved.enterId != enterId) return@withTransaction false
        val advanced = saved.withAdvancedCurrentBlock(currentBlockId) ?: return@withTransaction false
        if (advanced == saved) return@withTransaction true
        dao.updateRouteJson(
            id = entity.id,
            routeJson = Json.encodeToString(LabyrinthRouteJson.serializer(), advanced),
        ) == 1
    }
}

data class ResolvedLabyrinthRoute(
    val route: LabyrinthRoute,
    val allNodes: List<LabyrinthMapNode>,
    val currentBlockId: Long?,
    val guildId: Int?,
    val difficulty: Int,
)

sealed interface ExistingLabyrinthRouteResult {
    data class Found(val value: ResolvedLabyrinthRoute) : ExistingLabyrinthRouteResult
    data object NoMatchingRoute : ExistingLabyrinthRouteResult
    data class Failure(
        val message: String,
        val kind: LabyrinthFailureKind = LabyrinthFailureKind.UNKNOWN,
    ) : ExistingLabyrinthRouteResult
}

/** Reads an active run without creating, moving, or retiring it. */
class ExistingLabyrinthRouteResolver(
    private val api: LabyrinthApi,
    private val matcher: LabyrinthOpeningRouteMatcher = LabyrinthOpeningRouteMatcher(),
) {
    suspend fun resolve(
        top: LabyrinthTop,
        policy: LabyrinthRoutePolicy,
    ): ExistingLabyrinthRouteResult {
        val enterId = top.enterId
            ?: return ExistingLabyrinthRouteResult.Failure("当前没有黎明界开局")
        val difficulty = top.difficulty
            ?: return ExistingLabyrinthRouteResult.Failure(
                "现有黎明界缺少难度信息",
                LabyrinthFailureKind.PROTOCOL,
            )
        return when (val resumed = api.resume(enterId)) {
            is LabyrinthOperationResult.Failure -> ExistingLabyrinthRouteResult.Failure(
                resumed.message,
                resumed.kind,
            )

            is LabyrinthOperationResult.Success -> {
                val value = resumed.value
                if (value.map.isEmpty()) {
                    return ExistingLabyrinthRouteResult.Failure(
                        "现有黎明界没有返回地图节点",
                        LabyrinthFailureKind.PROTOCOL,
                    )
                }
                if (top.guildId != null && value.guildId != null && top.guildId != value.guildId) {
                    return ExistingLabyrinthRouteResult.Failure(
                        "现有黎明界公会信息不一致",
                        LabyrinthFailureKind.PROTOCOL,
                    )
                }
                if (value.currentBlockId != null && value.map.none { it.blockId == value.currentBlockId }) {
                    return ExistingLabyrinthRouteResult.Failure(
                        "现有黎明界当前节点不在地图中",
                        LabyrinthFailureKind.PROTOCOL,
                    )
                }
                when (
                    val search = matcher.search(
                        enterId = enterId,
                        nodes = value.map,
                        difficulty = difficulty,
                        policy = policy,
                        currentBlockId = value.currentBlockId,
                    )
                ) {
                    is RouteSearchResult.Found -> ExistingLabyrinthRouteResult.Found(
                        ResolvedLabyrinthRoute(
                            route = search.route.copy(openingRelics = (value.guildId ?: top.guildId)?.let {
                                LabyrinthOpeningRelicState(it, top.enhanceNodeIds)
                            }),
                            allNodes = value.map,
                            currentBlockId = value.currentBlockId,
                            guildId = value.guildId ?: top.guildId,
                            difficulty = difficulty,
                        ),
                    )

                    RouteSearchResult.NoRoute -> ExistingLabyrinthRouteResult.NoMatchingRoute
                }
            }
        }
    }

}

class LabyrinthRerollWorkflow(
    private val api: LabyrinthApi,
    private val routeStore: LabyrinthRouteStore,
    private val matcher: LabyrinthOpeningRouteMatcher = LabyrinthOpeningRouteMatcher(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val checkpointStore: LabyrinthRerollCheckpointStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val existingRouteResolver = ExistingLabyrinthRouteResolver(api, matcher)

    constructor(
        api: LabyrinthApi,
        routeStore: LabyrinthRouteStore,
        searcher: LabyrinthRouteSearcher,
    ) : this(api, routeStore, LabyrinthOpeningRouteMatcher(searcher))

    suspend fun run(
        config: LabyrinthRerollConfig,
        onProgress: (LabyrinthRerollProgress) -> Unit = {},
    ): LabyrinthRerollResult {
        require(config.guildId > 0) { "公会 ID 必须大于 0" }
        require(config.difficulty in 1..LabyrinthRerollOptions.MAX_DIFFICULTY) {
            "难度必须在 1 到 ${LabyrinthRerollOptions.MAX_DIFFICULTY} 之间"
        }
        require(config.maxAttempts in 1..LabyrinthRerollOptions.MAX_ATTEMPTS) {
            "尝试次数必须在 1 到 ${LabyrinthRerollOptions.MAX_ATTEMPTS} 之间"
        }

        onProgress(progress(config, 0, "检查当前黎明界状态"))
        var openingRelicState: LabyrinthOpeningRelicState? = null
        when (val top = readTopWithRetry(config, 0, "读取当前黎明界状态", onProgress)) {
            is LabyrinthOperationResult.Failure -> return LabyrinthRerollResult.Failure(top.message, top.kind)
            is LabyrinthOperationResult.Success -> {
                openingRelicState = LabyrinthOpeningRelicState(config.guildId, top.value.enhanceNodeIds)
                val existing = top.value.enterId
                if (existing != null && config.abandonExisting) {
                    onProgress(progress(config, 0, "放弃现有开局（不复用）"))
                } else if (existing != null) {
                    onProgress(progress(config, 0, "读取现有开局路线"))
                    when (val resolved = existingRouteResolver.resolve(top.value, config.routePolicy)) {
                        is ExistingLabyrinthRouteResult.Failure -> {
                            saveCheckpoint(
                                config = config,
                                attempt = 0,
                                enterId = existing,
                                verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                message = resolved.message,
                            )
                            return LabyrinthRerollResult.Failure(
                                "读取现有黎明界失败：${resolved.message}",
                                resolved.kind,
                            )
                        }

                        is ExistingLabyrinthRouteResult.Found -> {
                            val value = resolved.value
                            val sameGuild = value.guildId == null || value.guildId == config.guildId
                            if (sameGuild && value.difficulty == config.difficulty) {
                                saveCheckpoint(
                                    config = config,
                                    attempt = 0,
                                    enterId = existing,
                                    verdict = LabyrinthRouteVerdict.TARGET,
                                    message = "现有开局符合目标路线",
                                )
                                routeStore.save(
                                    config = config,
                                    route = value.route,
                                    attempt = 0,
                                    allNodes = value.allNodes,
                                    currentBlockId = value.currentBlockId,
                                )
                                return LabyrinthRerollResult.Success(
                                    route = value.route,
                                    attempt = 0,
                                    resumedExisting = true,
                                )
                            }
                            saveCheckpoint(
                                config = config,
                                attempt = 0,
                                enterId = existing,
                                verdict = LabyrinthRouteVerdict.NOT_TARGET,
                                message = "现有开局的公会或难度与当前配置不一致",
                            )
                        }

                        ExistingLabyrinthRouteResult.NoMatchingRoute -> saveCheckpoint(
                            config = config,
                            attempt = 0,
                            enterId = existing,
                            verdict = LabyrinthRouteVerdict.NOT_TARGET,
                            message = "现有开局不符合当前路线条件",
                        )
                    }
                    if (!config.retireExisting) {
                        return LabyrinthRerollResult.NeedsExistingRunDecision(existing)
                    }
                }
                val maxUnlocked = LabyrinthRerollOptions.maxUnlockedDifficulty(top.value.clearedDifficulties)
                if (config.difficulty > maxUnlocked) {
                    return LabyrinthRerollResult.Failure(
                        "黎明界难度 ${config.difficulty} 尚未解锁，当前最高可挑战难度为 $maxUnlocked",
                    )
                }
                existing?.let {
                    val retirementFailure = retireUntilGone(
                        config = config,
                        attempt = 0,
                        expectedEnterId = it,
                        retireStage = "撤退现有开局",
                        confirmationContext = "确认现有开局已撤退",
                        onProgress = onProgress,
                    )
                    if (retirementFailure != null) return retirementFailure
                    checkpointStore?.clear(config.accountId)
                }
            }
        }

        var attempt = 0
        while (config.rerollUntilFound || attempt < config.maxAttempts) {
            attempt += 1
            onProgress(progress(config, attempt, "生成路线"))
            saveCheckpoint(
                config = config,
                attempt = attempt,
                enterId = null,
                verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                message = "进入请求结果待确认",
            )
            val entered = when (val result = api.enter(LabyrinthEnterRequest(config.guildId, config.difficulty))) {
                is LabyrinthOperationResult.Success -> result.value
                is LabyrinthOperationResult.Failure -> {
                    if (result.kind != LabyrinthFailureKind.NETWORK) {
                        checkpointStore?.clear(config.accountId)
                        return LabyrinthRerollResult.Failure("第 $attempt 次进入失败：${result.message}", result.kind)
                    }
                    onProgress(progress(config, attempt, "进入响应中断，确认服务端状态"))
                    pause(UNCERTAIN_REQUEST_SETTLE_MILLIS)
                    when (val top = readTopWithRetry(config, attempt, "确认进入结果", onProgress)) {
                        is LabyrinthOperationResult.Failure -> return LabyrinthRerollResult.Failure(
                            "第 $attempt 次进入响应中断，且无法确认服务端状态：${top.message}。请先检查状态。",
                            top.kind,
                        )
                        is LabyrinthOperationResult.Success -> {
                            if (top.value.enterId != null) {
                                onProgress(progress(config, attempt, "恢复服务端已生成的路线"))
                                when (val resolved = existingRouteResolver.resolve(top.value, config.routePolicy)) {
                                    is ExistingLabyrinthRouteResult.Found -> {
                                        val value = resolved.value
                                        val sameGuild = value.guildId == null || value.guildId == config.guildId
                                        if (sameGuild && value.difficulty == config.difficulty) {
                                            saveCheckpoint(
                                                config = config,
                                                attempt = attempt,
                                                enterId = top.value.enterId,
                                                verdict = LabyrinthRouteVerdict.TARGET,
                                                message = "断线后恢复的开局符合目标路线",
                                            )
                                            routeStore.save(
                                                config = config,
                                                route = value.route,
                                                attempt = attempt,
                                                allNodes = value.allNodes,
                                                currentBlockId = value.currentBlockId,
                                            )
                                            return LabyrinthRerollResult.Success(
                                                route = value.route,
                                                attempt = attempt,
                                                resumedExisting = true,
                                            )
                                        }
                                        saveCheckpoint(
                                            config = config,
                                            attempt = attempt,
                                            enterId = top.value.enterId,
                                            verdict = LabyrinthRouteVerdict.NOT_TARGET,
                                            message = "断线后恢复的开局公会或难度不匹配",
                                        )
                                        return LabyrinthRerollResult.Failure(
                                            "第 $attempt 次进入响应中断，服务端已生成其他公会或难度的开局，已保留现场。",
                                        )
                                    }

                                    ExistingLabyrinthRouteResult.NoMatchingRoute -> {
                                        saveCheckpoint(
                                            config = config,
                                            attempt = attempt,
                                            enterId = top.value.enterId,
                                            verdict = LabyrinthRouteVerdict.NOT_TARGET,
                                            message = "断线后恢复的开局不符合目标路线",
                                        )
                                        return LabyrinthRerollResult.Failure(
                                            "第 $attempt 次进入响应中断，服务端已生成开局，但路线不符合当前条件，已保留现场。",
                                        )
                                    }

                                    is ExistingLabyrinthRouteResult.Failure -> {
                                        saveCheckpoint(
                                            config = config,
                                            attempt = attempt,
                                            enterId = top.value.enterId,
                                            verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                            message = resolved.message,
                                        )
                                        return LabyrinthRerollResult.Failure(
                                            "第 $attempt 次进入响应中断，服务端已生成开局，但恢复路线失败：${resolved.message}。已保留现场。",
                                            resolved.kind,
                                        )
                                    }
                                }
                            }
                            checkpointStore?.clear(config.accountId)
                            onProgress(progress(config, attempt, "已确认未生成开局，继续刷新"))
                            paceAttempts(config, attempt, onProgress)
                            continue
                        }
                    }
                }
            }
            saveCheckpoint(
                config = config,
                attempt = attempt,
                enterId = entered.enterId,
                verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                message = "已取得地图，正在判断路线",
            )
            val search = matcher.search(
                enterId = entered.enterId,
                nodes = entered.map,
                difficulty = config.difficulty,
                policy = config.routePolicy,
            )
            if (search is RouteSearchResult.Found) {
                saveCheckpoint(
                    config = config,
                    attempt = attempt,
                    enterId = entered.enterId,
                    verdict = LabyrinthRouteVerdict.TARGET,
                    message = "当前开局符合目标路线",
                )
                val route = search.route.copy(openingRelics = openingRelicState)
                routeStore.save(config, route, attempt, allNodes = entered.map)
                return LabyrinthRerollResult.Success(route, attempt)
            }
            saveCheckpoint(
                config = config,
                attempt = attempt,
                enterId = entered.enterId,
                verdict = LabyrinthRouteVerdict.NOT_TARGET,
                message = "当前开局不符合目标路线",
            )
            val retirementFailure = retireUntilGone(
                config = config,
                attempt = attempt,
                expectedEnterId = entered.enterId,
                retireStage = "路线不匹配，撤退",
                confirmationContext = "确认撤退后的服务端状态",
                onProgress = onProgress,
            )
            if (retirementFailure != null) return retirementFailure
            checkpointStore?.clear(config.accountId)
            paceAttempts(config, attempt, onProgress)
        }
        return LabyrinthRerollResult.Exhausted(config.maxAttempts)
    }

    private suspend fun saveCheckpoint(
        config: LabyrinthRerollConfig,
        attempt: Int,
        enterId: Long?,
        verdict: LabyrinthRouteVerdict,
        message: String,
    ) {
        checkpointStore?.save(
            LabyrinthRerollCheckpoint(
                accountId = config.accountId,
                attempt = attempt,
                enterId = enterId,
                guildId = config.guildId,
                difficulty = config.difficulty,
                policy = config.routePolicy,
                verdict = verdict,
                message = message,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun retireUntilGone(
        config: LabyrinthRerollConfig,
        attempt: Int,
        expectedEnterId: Long,
        retireStage: String,
        confirmationContext: String,
        onProgress: (LabyrinthRerollProgress) -> Unit,
    ): LabyrinthRerollResult.Failure? {
        var lastRetireStatus = ""
        repeat(RETIRE_REQUEST_MAX_ATTEMPTS) { retireIndex ->
            val requestNumber = retireIndex + 1
            val retrySuffix = if (retireIndex == 0) "" else "（重试 $requestNumber/$RETIRE_REQUEST_MAX_ATTEMPTS）"
            onProgress(progress(config, attempt, retireStage + retrySuffix))
            val retireInterrupted = when (val retired = api.retire(expectedEnterId)) {
                is LabyrinthOperationResult.Success -> {
                    lastRetireStatus = "撤退接口已响应成功"
                    false
                }
                is LabyrinthOperationResult.Failure -> {
                    lastRetireStatus = "撤退响应中断：${retired.message}"
                    if (retired.kind != LabyrinthFailureKind.NETWORK) {
                        saveCheckpoint(
                            config = config,
                            attempt = attempt,
                            enterId = expectedEnterId,
                            verdict = LabyrinthRouteVerdict.NOT_TARGET,
                            message = lastRetireStatus,
                        )
                        return LabyrinthRerollResult.Failure(
                            "第 $attempt 次撤退失败：${retired.message}",
                            retired.kind,
                        )
                    }
                    onProgress(progress(config, attempt, "撤退响应中断，确认服务端状态"))
                    pause(UNCERTAIN_REQUEST_SETTLE_MILLIS)
                    true
                }
            }

            when (
                val confirmation = confirmNoActiveRun(
                    config = config,
                    attempt = attempt,
                    expectedEnterId = expectedEnterId,
                    context = confirmationContext,
                    onProgress = onProgress,
                )
            ) {
                ActiveRunConfirmation.Gone -> {
                    if (retireInterrupted) {
                        onProgress(progress(config, attempt, "已确认开局实际撤退成功"))
                    }
                    return null
                }
                is ActiveRunConfirmation.Stop -> return confirmation.failure
                is ActiveRunConfirmation.SameRunStillPresent -> {
                    if (retireIndex == RETIRE_REQUEST_MAX_ATTEMPTS - 1) {
                        val message =
                            "$confirmationContext 后仍存在同一开局（Enter ID 尾号 " +
                                "${confirmation.enterId % 10_000}）；已连续尝试撤退 " +
                                "$RETIRE_REQUEST_MAX_ATTEMPTS 次，$lastRetireStatus，已停止并保留现场。"
                        saveCheckpoint(
                            config = config,
                            attempt = attempt,
                            enterId = confirmation.enterId,
                            verdict = LabyrinthRouteVerdict.NOT_TARGET,
                            message = message,
                        )
                        return LabyrinthRerollResult.Failure(message)
                    }
                    val waitMillis = RETIRE_RETRY_BACKOFF_MILLIS[retireIndex]
                    onProgress(
                        progress(
                            config,
                            attempt,
                            "服务端仍保留同一开局，${waitMillis / 1_000} 秒后重试撤退",
                        ),
                    )
                    pause(waitMillis)
                }
            }
        }
        error("unreachable retirement loop")
    }

    private suspend fun confirmNoActiveRun(
        config: LabyrinthRerollConfig,
        attempt: Int,
        expectedEnterId: Long,
        context: String,
        onProgress: (LabyrinthRerollProgress) -> Unit,
    ): ActiveRunConfirmation {
        onProgress(progress(config, attempt, context))
        repeat(ACTIVE_RUN_CONFIRM_READS) { readIndex ->
            when (val top = readTopWithRetry(config, attempt, context, onProgress)) {
                is LabyrinthOperationResult.Failure -> {
                    markCheckpointPending(
                        config = config,
                        attempt = attempt,
                        enterId = checkpointStore?.load(config.accountId)?.enterId,
                        message = "$context 失败：${top.message}",
                    )
                    return ActiveRunConfirmation.Stop(
                        LabyrinthRerollResult.Failure(
                            "$context 失败：${top.message}。为避免重复有副作用请求，已停止。",
                            top.kind,
                        ),
                    )
                }
                is LabyrinthOperationResult.Success -> {
                    val enterId = top.value.enterId ?: return ActiveRunConfirmation.Gone
                    if (enterId != expectedEnterId) {
                        markCheckpointPending(
                            config = config,
                            attempt = attempt,
                            enterId = enterId,
                            message = "$context 后出现了不同的服务端开局",
                        )
                        return ActiveRunConfirmation.Stop(
                            LabyrinthRerollResult.Failure(
                                "$context 后出现了不同的服务端开局（Enter ID 尾号 " +
                                    "${enterId % 10_000}），已停止并保留现场。",
                            ),
                        )
                    }
                    if (readIndex == ACTIVE_RUN_CONFIRM_READS - 1) {
                        return ActiveRunConfirmation.SameRunStillPresent(enterId)
                    }
                    onProgress(progress(config, attempt, "服务端仍显示开局，短暂等待后再次确认"))
                    pause(ACTIVE_RUN_CONFIRM_DELAY_MILLIS)
                }
            }
        }
        error("unreachable active-run confirmation loop")
    }

    private sealed interface ActiveRunConfirmation {
        data object Gone : ActiveRunConfirmation
        data class SameRunStillPresent(val enterId: Long) : ActiveRunConfirmation
        data class Stop(val failure: LabyrinthRerollResult.Failure) : ActiveRunConfirmation
    }

    private suspend fun markCheckpointPending(
        config: LabyrinthRerollConfig,
        attempt: Int,
        enterId: Long?,
        message: String,
    ) {
        val existing = checkpointStore?.load(config.accountId)
        checkpointStore?.save(
            existing?.copy(
                attempt = attempt,
                enterId = enterId ?: existing.enterId,
                verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                message = message,
                updatedAt = clock(),
            ) ?: LabyrinthRerollCheckpoint(
                accountId = config.accountId,
                attempt = attempt,
                enterId = enterId,
                guildId = config.guildId,
                difficulty = config.difficulty,
                policy = config.routePolicy,
                verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                message = message,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun readTopWithRetry(
        config: LabyrinthRerollConfig,
        attempt: Int,
        context: String,
        onProgress: (LabyrinthRerollProgress) -> Unit,
    ): LabyrinthOperationResult<LabyrinthTop> {
        var lastFailure: LabyrinthOperationResult.Failure? = null
        repeat(TOP_READ_MAX_ATTEMPTS) { retryIndex ->
            when (val result = api.top()) {
                is LabyrinthOperationResult.Success -> return result
                is LabyrinthOperationResult.Failure -> {
                    lastFailure = result
                    if (result.kind != LabyrinthFailureKind.NETWORK || retryIndex == TOP_READ_MAX_ATTEMPTS - 1) {
                        return result
                    }
                    val waitMillis = TOP_RETRY_BACKOFF_MILLIS[retryIndex]
                    onProgress(
                        progress(
                            config,
                            attempt,
                            "$context 网络中断，${waitMillis / 1_000} 秒后重试状态读取",
                        ),
                    )
                    pause(waitMillis)
                }
            }
        }
        return checkNotNull(lastFailure)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun paceAttempts(
        config: LabyrinthRerollConfig,
        attempt: Int,
        onProgress: (LabyrinthRerollProgress) -> Unit,
    ) {
        // Happy-path requests are already serialized by Enter -> Retire -> Top. Avoid a
        // fixed inter-attempt delay here; network uncertainty and stale server state still
        // use the bounded backoff paths elsewhere in this workflow.
    }

    private fun progress(config: LabyrinthRerollConfig, attempt: Int, stage: String) = LabyrinthRerollProgress(
        attempt = attempt,
        maxAttempts = config.maxAttempts,
        stage = stage,
        rerollUntilFound = config.rerollUntilFound,
    )

    private companion object {
        const val UNCERTAIN_REQUEST_SETTLE_MILLIS = 1_000L
        const val TOP_READ_MAX_ATTEMPTS = 3
        const val ACTIVE_RUN_CONFIRM_READS = 3
        const val ACTIVE_RUN_CONFIRM_DELAY_MILLIS = 1_000L
        const val RETIRE_REQUEST_MAX_ATTEMPTS = 3
        val TOP_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L)
        val RETIRE_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L)
    }
}
