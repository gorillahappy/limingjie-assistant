package com.landosol.toolbox.protocol.labyrinth

import kotlinx.serialization.Serializable

data class LabyrinthTop(
    val enterId: Long?,
    val guildId: Int?,
    val difficulty: Int?,
    val clearedDifficulties: List<ClearedDifficulty>,
    /** Null is unknown, while an empty set confirms that no enhancement node was acquired. */
    val enhanceNodeIds: Set<Int>? = null,
)

data class ClearedDifficulty(val guildId: Int, val difficulty: Int)

data class LabyrinthEnterRequest(val guildId: Int, val difficulty: Int)

data class LabyrinthEnter(
    val enterId: Long,
    val map: List<LabyrinthMapNode>,
)

data class LabyrinthResume(
    val enterId: Long,
    val guildId: Int?,
    val currentBlockId: Long?,
    val map: List<LabyrinthMapNode>,
)

data class LabyrinthMapNode(
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
data class LabyrinthOpeningRelicState(val guildId: Int, val enhanceNodeIds: Set<Int>? = null)

data class LabyrinthRoute(
    val enterId: Long,
    val nodes: List<LabyrinthMapNode>,
    val openingRelics: LabyrinthOpeningRelicState? = null,
) {
    val blockIds: List<Long> get() = nodes.map(LabyrinthMapNode::blockId)
}

sealed interface LabyrinthOperationResult<out T> {
    data class Success<T>(val value: T) : LabyrinthOperationResult<T>
    data class Failure(
        val message: String,
        val kind: LabyrinthFailureKind = LabyrinthFailureKind.UNKNOWN,
    ) : LabyrinthOperationResult<Nothing>
}

enum class LabyrinthFailureKind {
    REJECTED,
    NETWORK,
    PROTOCOL,
    UNKNOWN,
}

interface LabyrinthApi {
    suspend fun top(): LabyrinthOperationResult<LabyrinthTop>
    suspend fun enter(request: LabyrinthEnterRequest): LabyrinthOperationResult<LabyrinthEnter>
    suspend fun resume(enterId: Long): LabyrinthOperationResult<LabyrinthResume>
    suspend fun retire(enterId: Long): LabyrinthOperationResult<Unit>
}
