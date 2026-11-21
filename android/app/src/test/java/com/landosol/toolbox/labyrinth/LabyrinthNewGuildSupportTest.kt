package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthNewGuildSupportTest {
    private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
        .first { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/relics.laby-workbook.json").isFile }
    private val assets = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
    private val json = Json { ignoreUnknownKeys = true }
    private val resources = json.parseToJsonElement(File(assets, "relics.laby-workbook.json").readText())
        .jsonObject.getValue("relics").jsonArray.map { json.decodeFromJsonElement<RelicResource>(it) }
    private val catalog = LabyrinthOpeningRelicCatalog(resources)
    private val rect = EntryPixelRect(0, 0, 1, 1)

    private fun route(guild: Int, nodes: Set<Int>? = setOf(31)) = LabyrinthRouteJson(
        enterId = 123, guildId = guild, difficulty = 5, attempt = 1, rerollUntilFound = false,
        perfectStart = false, thirdBlockChoice = "EITHER", area3BossIds = emptyList(), area5BossIds = emptyList(),
        nodes = emptyList(), openingRelics = LabyrinthOpeningRelicState(guild, nodes))

    @Test fun `ten guild options initial plans and account settings agree`() {
        assertEquals((1..10).toList(), LabyrinthRerollOptions.guilds.map { it.guildId })
        assertEquals(LabyrinthRerollOptions.guilds.map { it.name }, LabyrinthOpeningRosterCatalog.guilds.map { it.guildName })
        for (guild in 6..10) {
            assertNull(LabyrinthRerollSettings(guildId = guild).validationError())
            val config = LabyrinthOpeningRosterCatalog.configs.getValue(guild)
            assertEquals(3, config.slots.size)
            val first = config.slots.map { it.candidates.first().characterId }
            val policy = LabyrinthOpeningRosterPolicy(config)
            assertEquals(first, (policy.choose(first.toSet()) as LabyrinthOpeningRosterDecision.Ready).characters.map { it.characterId })
            assertTrue(policy.choose(setOf("unrelated")) is LabyrinthOpeningRosterDecision.Missing)
        }
        assertEquals(listOf("1348", "1344", "1054"), LabyrinthOpeningRosterCatalog.configs.getValue(6).slots.map { it.candidates.first().characterId })
        assertEquals(listOf("1082", "1117", "1138"), LabyrinthOpeningRosterCatalog.configs.getValue(7).slots.map { it.candidates.first().characterId })
        assertEquals(listOf("1033", "1026", "1038"), LabyrinthOpeningRosterCatalog.configs.getValue(8).slots.map { it.candidates.first().characterId })
        assertEquals(listOf("1079", "1168", "1251"), LabyrinthOpeningRosterCatalog.configs.getValue(9).slots.map { it.candidates.first().characterId })
        assertEquals(listOf("1056", "1299", "1051"), LabyrinthOpeningRosterCatalog.configs.getValue(10).slots.map { it.candidates.first().characterId })
    }

    @Test fun `new opening candidates have bundled profiles and icons and retain fallbacks and overrides`() {
        val runtime = LabyrinthRoleDecisionDataParser.parse(File(assets, "labyrinth-role-decision.json").readText())
            as LabyrinthRoleDecisionDataResult.Ready
        val icons = json.parseToJsonElement(File(assets, "icons.json").readText()).jsonObject.getValue("characterIcons").jsonArray
            .map { it.jsonObject.getValue("ownerId").jsonPrimitive.content }.toSet()
        val candidates = (6..10).flatMap { LabyrinthOpeningRosterCatalog.configs.getValue(it).slots }.flatMap { it.candidates }
        candidates.forEach {
            assertTrue("missing profile ${it.characterId}", runtime.runtime.profiles.containsKey(it.characterId))
            assertTrue("missing icon ${it.characterId}", it.characterId in icons)
        }
        val config = LabyrinthOpeningRosterCatalog.configs.getValue(6)
        assertEquals("1116", (LabyrinthOpeningRosterPolicy(config).choose(setOf("1348", "1344", "1116"))
            as LabyrinthOpeningRosterDecision.Ready).characters.last().characterId)
        val slots = listOf(listOf("1033"), listOf("1026"), listOf("1038"))
        assertEquals(slots, LabyrinthOpeningRosterCatalog.configFor(6, mapOf(6 to slots))!!.slots.map { it.candidates.map { it.characterId } })
        assertEquals(listOf("1068"), LabyrinthOpeningRosterCatalog.grantedCharactersFor(5).map { it.characterId })
    }

    @Test fun `all ten guilds receive only their own fixed and node31 relics`() {
        for (guild in 1..10) {
            val known = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(31, 1)), rect)
            assertTrue(known.complete)
            assertEquals(listOf("${40000 + guild}", "${50000 + guild}"), known.relics.map { it.relicId })
            assertTrue(known.relics.all { !it.displayName.isNullOrBlank() && it.attributeBonus == "1" })
            val without = catalog.grants(LabyrinthOpeningRelicState(guild, emptySet()), rect)
            assertTrue(without.complete)
            assertEquals(listOf("${40000 + guild}"), without.relics.map { it.relicId })
        }
    }

    @Test fun `unknown enhancement missing metadata and mismatched guilds never invent optional relics`() {
        val unknown = catalog.grants(LabyrinthOpeningRelicState(6), rect)
        assertFalse(unknown.complete)
        assertEquals(listOf("40006"), unknown.relics.map { it.relicId })
        assertEquals(listOf("40006"), catalog.grants(LabyrinthOpeningRelicState(6, setOf(-1, 31)), rect).relics.map { it.relicId })
        assertTrue(catalog.grants(null, rect).relics.isEmpty())
        assertTrue(catalog.grants(LabyrinthOpeningRelicState(999, setOf(31)), rect).relics.isEmpty())
        assertNull(route(6).copy(openingRelics = LabyrinthOpeningRelicState(7, setOf(31))).openingRelicState())
        assertFalse(LabyrinthOpeningRelicCatalog(resources.filterNot { it.id == "50006" })
            .grants(LabyrinthOpeningRelicState(6, setOf(31)), rect).complete)
    }

    @Test fun `saved route metadata survives JSON and legacy routes stay unknown`() {
        val saved = route(10)
        val restored = json.decodeFromString<LabyrinthRouteJson>(json.encodeToString(saved))
        assertEquals(saved.openingRelics, restored.openingRelicState())
        assertEquals(saved.openingRelics, restored.toLabyrinthRoute().openingRelics)
        val legacyJson = json.parseToJsonElement(json.encodeToString(saved)).jsonObject.toMutableMap()
        legacyJson.remove("openingRelics")
        assertNull(json.decodeFromJsonElement<LabyrinthRouteJson>(JsonObject(legacyJson)).openingRelicState())
        assertNull(saved.copy(openingRelics = LabyrinthOpeningRelicState(9)).toLabyrinthRoute().openingRelics)
    }

    @Test fun `opening initialization resume and picker reset persist gifts exactly once`() = runTest {
        for (guild in 6..10) {
            val store = MemoryStore()
            val first = store.beginWithOpeningRelics(1, route(guild), catalog, 1)
            val resumed = store.beginWithOpeningRelics(1, route(guild), catalog, 2)
            assertEquals(first.observedRelics.map { it.relicId }, resumed.observedRelics.map { it.relicId })
            assertTrue(resumed.observedRelics.all { it.observationCount == 1 })
            val clean = store.beginWithOpeningRelics(1, route(guild), catalog, 3, reset = true, page = "INITIAL_CHARACTER_SELECTION")
            assertEquals(resumed.observedRelics.map { it.relicId }, clean.observedRelics.map { it.relicId })
            assertEquals(1, store.resets)
            assertEquals(2, LabyrinthRelicChoicePolicy().markStacks(clean.observedRelics).values.sum())
            println("NEW_GUILD guild=$guild relics=${clean.observedRelics.map { it.relicId }} " +
                "counts=${resumed.observedRelics.map { it.observationCount }} resetCount=${store.resets}")
            val next = store.beginWithOpeningRelics(1, route(if (guild == 10) 6 else guild + 1), catalog, 4, reset = true)
            assertFalse(next.observedRelics.any { it.relicId == "${40000 + guild}" })
        }
    }

    @Test fun `opening grants do not change ordinary repeat acquisition counts`() {
        val opening = catalog.grants(LabyrinthOpeningRelicState(6, emptySet()), rect).relics.single()
        var saved = LabyrinthRelicMerger.merge(emptyList(), listOf(opening), 1)
        saved = LabyrinthRelicMerger.merge(saved, listOf(opening), 2)
        assertEquals(1, saved.single().observationCount)
        saved = LabyrinthRelicMerger.merge(saved, listOf(opening.copy(slotId = "relic_choice_1")), 3)
        assertEquals(2, saved.single().observationCount)
        assertEquals(2, LabyrinthRelicMerger.merge(saved, listOf(opening), 4).single().observationCount)
    }

    @Test fun `old route without grant evidence preserves the existing ledger on resume`() = runTest {
        val store = MemoryStore()
        val first = store.beginWithOpeningRelics(1, route(6), catalog, 1)
        val legacy = store.beginWithOpeningRelics(1, route(6).copy(openingRelics = null), catalog, 2)
        assertEquals(first.observedRelics, legacy.observedRelics)
        val newStore = MemoryStore()
        assertTrue(newStore.beginWithOpeningRelics(2, route(6).copy(openingRelics = null), catalog, 3).observedRelics.isEmpty())
    }

    private class MemoryStore : LabyrinthRunStateStore {
        private var value = LabyrinthRunSnapshot("test", 1, "ACTIVE", null, 0, emptyList(), 0)
        var resets = 0
        override suspend fun begin(accountId: Long?, nowMillis: Long) = value
        override suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot {
            resets++
            value = value.copy(joinedCharacters = emptyList(), observedRelics = emptyList(), updatedAt = nowMillis)
            return value
        }
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean): LabyrinthRunSnapshot {
            value = value.copy(currentPage = page, updatedAt = nowMillis,
                observedRelics = LabyrinthRelicMerger.merge(value.observedRelics, relics, nowMillis))
            return value
        }
        override suspend fun load(accountId: Long?) = value
    }
}
