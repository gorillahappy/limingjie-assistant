package com.landosol.toolbox.labyrinth

import androidx.room.withTransaction
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.data.local.LabyrinthRunCharacterEntity
import com.landosol.toolbox.data.local.LabyrinthRunRelicEntity
import com.landosol.toolbox.data.local.LabyrinthRunStateEntity
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch

data class LabyrinthJoinedCharacter(
    val characterId: String,
    val displayName: String,
    val orderIndex: Int,
    val confidence: Double,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

data class LabyrinthObservedRelic(
    val relicId: String,
    val displayName: String,
    val attribute: String,
    val attributeBonus: String,
    val effect: String,
    val confidence: Double,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val observationCount: Int,
)

data class LabyrinthRunSnapshot(
    val runKey: String,
    val accountId: Long?,
    val status: String,
    val currentPage: String?,
    val unrecognizedCharacterCount: Int,
    val joinedCharacters: List<LabyrinthJoinedCharacter>,
    val updatedAt: Long,
    val observedRelics: List<LabyrinthObservedRelic> = emptyList(),
)

interface LabyrinthRunStateStore {
    suspend fun begin(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot

    suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot

    suspend fun recordPage(
        accountId: Long?,
        page: String,
        characters: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        relics: List<LabyrinthRelicMatch> = emptyList(),
        replaceCharacters: Boolean = false,
    ): LabyrinthRunSnapshot

    suspend fun load(accountId: Long?): LabyrinthRunSnapshot?
}

object LabyrinthRosterMerger {
    fun merge(
        existing: List<LabyrinthJoinedCharacter>,
        matches: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        replaceExisting: Boolean = false,
    ): List<LabyrinthJoinedCharacter> {
        val merged = existing
            .takeUnless { replaceExisting }
            .orEmpty()
            .sortedBy(LabyrinthJoinedCharacter::orderIndex)
            .map { character ->
                character.copy(characterId = canonicalLabyrinthRoleId(character.characterId))
            }
            .distinctBy(LabyrinthJoinedCharacter::characterId)
            .toMutableList()
        var nextOrder = (merged.maxOfOrNull(LabyrinthJoinedCharacter::orderIndex) ?: -1) + 1
        matches
            .filter {
                it.trusted && !it.characterId.isNullOrBlank() && !it.displayName.isNullOrBlank()
            }
            .distinctBy { canonicalLabyrinthRoleId(requireNotNull(it.characterId)) }
            .forEach { match ->
                val characterId = canonicalLabyrinthRoleId(requireNotNull(match.characterId))
                val displayName = requireNotNull(match.displayName)
                val index = merged.indexOfFirst { it.characterId == characterId }
                if (index >= 0) {
                    merged[index] = merged[index].copy(
                        displayName = displayName,
                        confidence = maxOf(merged[index].confidence, match.confidence),
                        lastSeenAt = nowMillis,
                    )
                } else {
                    merged += LabyrinthJoinedCharacter(
                        characterId = characterId,
                        displayName = displayName,
                        orderIndex = nextOrder++,
                        confidence = match.confidence,
                        firstSeenAt = nowMillis,
                        lastSeenAt = nowMillis,
                    )
                }
            }
        return merged
    }
}

object LabyrinthRelicMerger {
    fun merge(
        existing: List<LabyrinthObservedRelic>,
        matches: List<LabyrinthRelicMatch>,
        nowMillis: Long,
    ): List<LabyrinthObservedRelic> {
        val merged = existing.toMutableList()
        matches
            .filter {
                !it.relicId.isNullOrBlank() &&
                    !it.displayName.isNullOrBlank() &&
                    !it.attribute.isNullOrBlank()
            }
            .distinctBy(LabyrinthRelicMatch::relicId)
            .forEach { match ->
                val relicId = requireNotNull(match.relicId)
                val displayName = requireNotNull(match.displayName)
                val attribute = requireNotNull(match.attribute)
                val index = merged.indexOfFirst { it.relicId == relicId }
                if (index >= 0) {
                    val previous = merged[index]
                    merged[index] = previous.copy(
                        displayName = displayName,
                        attribute = attribute,
                        attributeBonus = match.attributeBonus.orEmpty(),
                        effect = match.effect.orEmpty(),
                        confidence = maxOf(previous.confidence, match.confidence),
                        lastSeenAt = nowMillis,
                        // Fixed opening grants are facts about this run, not repeat acquisitions.
                        observationCount = if (match.slotId.startsWith("opening:")) previous.observationCount
                            else previous.observationCount + 1,
                    )
                } else {
                    merged += LabyrinthObservedRelic(
                        relicId = relicId,
                        displayName = displayName,
                        attribute = attribute,
                        attributeBonus = match.attributeBonus.orEmpty(),
                        effect = match.effect.orEmpty(),
                        confidence = match.confidence,
                        firstSeenAt = nowMillis,
                        lastSeenAt = nowMillis,
                        observationCount = 1,
                    )
                }
            }
        return merged.sortedWith(compareBy(LabyrinthObservedRelic::firstSeenAt, LabyrinthObservedRelic::relicId))
    }
}

class RoomLabyrinthRunStateStore(
    private val database: AppDatabase,
) : LabyrinthRunStateStore {
    override suspend fun begin(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        val current = dao.getState(key)
        dao.upsertState(
            current?.copy(status = ACTIVE, updatedAt = nowMillis) ?: LabyrinthRunStateEntity(
                runKey = key,
                accountId = accountId,
                status = ACTIVE,
                currentPage = null,
                unrecognizedCharacterCount = 0,
                startedAt = nowMillis,
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        dao.deleteCharacters(key)
        dao.deleteRelics(key)
        dao.upsertState(
            LabyrinthRunStateEntity(
                runKey = key,
                accountId = accountId,
                status = ACTIVE,
                currentPage = null,
                unrecognizedCharacterCount = 0,
                startedAt = nowMillis,
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun recordPage(
        accountId: Long?,
        page: String,
        characters: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        relics: List<LabyrinthRelicMatch>,
        replaceCharacters: Boolean,
    ): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        val current = dao.getState(key) ?: LabyrinthRunStateEntity(
            runKey = key,
            accountId = accountId,
            status = ACTIVE,
            currentPage = null,
            unrecognizedCharacterCount = 0,
            startedAt = nowMillis,
            updatedAt = nowMillis,
        )
        val existing = dao.getCharacters(key).map(LabyrinthRunCharacterEntity::toDomain)
        val merged = LabyrinthRosterMerger.merge(
            existing = existing,
            matches = characters,
            nowMillis = nowMillis,
            replaceExisting = replaceCharacters,
        )
        dao.deleteCharacters(key)
        dao.insertCharacters(merged.map { it.toEntity(key) })
        val existingRelics = dao.getRelics(key).map(LabyrinthRunRelicEntity::toDomain)
        val mergedRelics = LabyrinthRelicMerger.merge(existingRelics, relics, nowMillis)
        dao.deleteRelics(key)
        dao.insertRelics(mergedRelics.map { it.toEntity(key) })
        dao.upsertState(
            current.copy(
                status = ACTIVE,
                currentPage = page,
                unrecognizedCharacterCount = if (characters.isNotEmpty()) {
                    characters.count { it.characterId == null || it.displayName == null }
                } else {
                    current.unrecognizedCharacterCount
                },
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun load(accountId: Long?): LabyrinthRunSnapshot? = database.withTransaction {
        val key = runKey(accountId)
        if (database.labyrinthRunStateDao().getState(key) == null) null else snapshot(key)
    }

    private suspend fun snapshot(key: String): LabyrinthRunSnapshot {
        val dao = database.labyrinthRunStateDao()
        val state = requireNotNull(dao.getState(key))
        return LabyrinthRunSnapshot(
            runKey = state.runKey,
            accountId = state.accountId,
            status = state.status,
            currentPage = state.currentPage,
            unrecognizedCharacterCount = state.unrecognizedCharacterCount,
            joinedCharacters = dao.getCharacters(key).map(LabyrinthRunCharacterEntity::toDomain),
            updatedAt = state.updatedAt,
            observedRelics = dao.getRelics(key).map(LabyrinthRunRelicEntity::toDomain),
        )
    }

    private fun runKey(accountId: Long?): String = accountId?.let { "account:$it" } ?: UNBOUND_RUN_KEY

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val UNBOUND_RUN_KEY = "account:unbound"
    }
}

private fun LabyrinthRunCharacterEntity.toDomain() = LabyrinthJoinedCharacter(
    characterId = characterId,
    displayName = displayName,
    orderIndex = orderIndex,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun LabyrinthJoinedCharacter.toEntity(runKey: String) = LabyrinthRunCharacterEntity(
    runKey = runKey,
    characterId = characterId,
    displayName = displayName,
    orderIndex = orderIndex,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun LabyrinthRunRelicEntity.toDomain() = LabyrinthObservedRelic(
    relicId = relicId,
    displayName = displayName,
    attribute = attribute,
    attributeBonus = attributeBonus,
    effect = effect,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    observationCount = observationCount,
)

private fun LabyrinthObservedRelic.toEntity(runKey: String) = LabyrinthRunRelicEntity(
    runKey = runKey,
    relicId = relicId,
    displayName = displayName,
    attribute = attribute,
    attributeBonus = attributeBonus,
    effect = effect,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    observationCount = observationCount,
)
