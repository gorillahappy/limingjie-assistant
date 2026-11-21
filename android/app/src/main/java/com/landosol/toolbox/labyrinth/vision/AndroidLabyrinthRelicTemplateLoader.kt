package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import android.graphics.BitmapFactory
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.gamedata.GameIconPackDocument
import com.landosol.toolbox.gamedata.RelicResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Loads the catalog relic icons and their attribute metadata from the active bundled pack. */
class AndroidLabyrinthRelicTemplateLoader(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): List<LabyrinthRelicTemplate> {
        val relics = loadRelics().associateBy(RelicResource::id)
        val icons = load<GameIconPackDocument>(ICONS_FILE)
        return icons.relicIcons.mapNotNull { icon ->
            val relic = relics[icon.ownerId] ?: return@mapNotNull null
            LabyrinthRelicTemplate(
                relicId = relic.id,
                displayName = relic.name.ifBlank { relic.id },
                attribute = relic.mark
                    .ifBlank { relic.tags.firstOrNull().orEmpty() }
                    .ifBlank { SPECIAL_ATTRIBUTE },
                attributeBonus = relic.markBonus,
                effect = relic.effect,
                image = loadImage("$PACK_ROOT/${icon.file}"),
            )
        }
    }

    fun loadRelics(): List<RelicResource> {
        val root = json.parseToJsonElement(readAsset(RELICS_FILE)).jsonObject
        return root["relics"]?.jsonArray.orEmpty().map { json.decodeFromJsonElement<RelicResource>(it) }
    }

    private inline fun <reified T> load(file: String): T = json.decodeFromString(readAsset(file))

    private fun readAsset(path: String): String = context.assets.open("$PACK_ROOT/$path").use { input ->
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun loadImage(path: String) = context.assets.open(path).use { input ->
        val bitmap = requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode relic icon: $path" }
        AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }

    private companion object {
        const val PACK_ROOT = "resource-packs/cn-bilibili"
        const val RELICS_FILE = "relics.laby-workbook.json"
        const val ICONS_FILE = "icons.json"
        const val SPECIAL_ATTRIBUTE = "特殊"
    }
}
