package com.landosol.toolbox.protocol.labyrinth

import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.bilibili.GameSessionResult

class BilibiliLabyrinthApi(
    private val session: BilibiliGameSession,
) : LabyrinthApi {
    override suspend fun top(): LabyrinthOperationResult<LabyrinthTop> = when (
        val result = session.request("labyrinth/top", linkedMapOf())
    ) {
        is GameSessionResult.Success -> runCatching {
            val cleared = result.data.list("guild_cleared_difficulty_list").mapNotNull { item ->
                item.asMap()?.let { map ->
                    ClearedDifficulty(
                        guildId = map.int("guild_id") ?: return@let null,
                        difficulty = map.int("difficulty") ?: return@let null,
                    )
                }
            }
            LabyrinthTop(
                enterId = result.data.long("enter_id")?.takeIf { it > 0 },
                guildId = result.data.int("guild_id")?.takeIf { it > 0 },
                difficulty = result.data.int("difficulty")?.takeIf { it > 0 },
                clearedDifficulties = cleared,
                enhanceNodeIds = parseLabyrinthEnhanceNodeIds(result.data["labyrinth_enhance_node_list"]),
            )
        }.fold(
            onSuccess = { LabyrinthOperationResult.Success(it) },
            onFailure = {
                LabyrinthOperationResult.Failure("黎明界顶部信息无法解析", LabyrinthFailureKind.PROTOCOL)
            },
        )
        is GameSessionResult.Rejected -> LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.REJECTED)
        is GameSessionResult.NetworkFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.NETWORK)
        is GameSessionResult.ProtocolFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.PROTOCOL)
    }

    override suspend fun enter(request: LabyrinthEnterRequest): LabyrinthOperationResult<LabyrinthEnter> = when (
        val result = session.request(
            "labyrinth/enter",
            linkedMapOf(
                "guild_id" to request.guildId.toLong(),
                "difficulty" to request.difficulty.toLong(),
            ),
        )
    ) {
        is GameSessionResult.Success -> runCatching {
            val enterId = result.data.long("enter_id") ?: error("enter_id missing")
            val nodes = result.data.list("map_list").map { item -> parseNode(item.asMap() ?: error("node missing")) }
            LabyrinthEnter(enterId, nodes)
        }.fold(
            onSuccess = { LabyrinthOperationResult.Success(it) },
            onFailure = {
                LabyrinthOperationResult.Failure("黎明界地图无法解析", LabyrinthFailureKind.PROTOCOL)
            },
        )
        is GameSessionResult.Rejected -> LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.REJECTED)
        is GameSessionResult.NetworkFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.NETWORK)
        is GameSessionResult.ProtocolFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.PROTOCOL)
    }

    override suspend fun resume(enterId: Long): LabyrinthOperationResult<LabyrinthResume> = when (
        val result = session.request("labyrinth/resume", linkedMapOf("enter_id" to enterId))
    ) {
        is GameSessionResult.Success -> runCatching {
            val nodes = result.data.list("map_list").map { item -> parseNode(item.asMap() ?: error("node missing")) }
            LabyrinthResume(
                enterId = enterId,
                guildId = result.data.int("guild_id")?.takeIf { it > 0 },
                currentBlockId = result.data.long("block_id")?.takeIf { it > 0 },
                map = nodes,
            )
        }.fold(
            onSuccess = { LabyrinthOperationResult.Success(it) },
            onFailure = {
                LabyrinthOperationResult.Failure("现有黎明界地图无法解析", LabyrinthFailureKind.PROTOCOL)
            },
        )
        is GameSessionResult.Rejected -> LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.REJECTED)
        is GameSessionResult.NetworkFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.NETWORK)
        is GameSessionResult.ProtocolFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.PROTOCOL)
    }

    override suspend fun retire(enterId: Long): LabyrinthOperationResult<Unit> = when (
        val result = session.request("labyrinth/retire", linkedMapOf("enter_id" to enterId))
    ) {
        is GameSessionResult.Success -> LabyrinthOperationResult.Success(Unit)
        is GameSessionResult.Rejected -> LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.REJECTED)
        is GameSessionResult.NetworkFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.NETWORK)
        is GameSessionResult.ProtocolFailure ->
            LabyrinthOperationResult.Failure(result.message, LabyrinthFailureKind.PROTOCOL)
    }

    private fun parseNode(map: Map<Any?, Any?>): LabyrinthMapNode = LabyrinthMapNode(
        area = map.int("area") ?: 0,
        column = map.int("column") ?: 0,
        row = map.int("row") ?: 0,
        blockId = map.long("block_id") ?: error("block_id missing"),
        blockType = map.int("block_type") ?: 0,
        questId = map.int("quest_id")?.takeIf { it > 0 },
        nextBlockIds = map.list("next_block_id_list").mapNotNull { it.asLong() },
        isAreaLastPoint = map.boolean("IsAreaLastPoint")
            ?: map.boolean("is_area_last_point")
            ?: false,
    )
}

/** Adapted from SatenRuiko10032/limingjie-assistant; missing or malformed lists stay unknown. */
internal fun parseLabyrinthEnhanceNodeIds(value: Any?): Set<Int>? {
    val nodes = value as? List<*> ?: return null
    return nodes.map { node ->
        val id = when (node) {
            is Byte, is Short, is Int, is Long -> (node as Number).toLong()
            is String -> node.toLongOrNull()
            else -> null
        } ?: return null
        if (id !in 1..Int.MAX_VALUE.toLong()) return null
        id.toInt()
    }.toSet()
}

private fun Any?.asMap(): Map<Any?, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<Any?, Any?>
}

private fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

private fun Map<*, *>.long(key: String): Long? = this[key].asLong()

private fun Map<*, *>.int(key: String): Int? = long(key)?.toInt()

private fun Map<*, *>.boolean(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> null
}

private fun Map<*, *>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
