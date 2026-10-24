package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import com.landosol.toolbox.labyrinth.labyrinthPreservesPendingNodeTransitionAcrossPage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LabyrinthEventFlowRegressionTest {
    private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
        .first { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/vision.json").isFile }
    private val assets = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `shop imprint joined screenshot belongs to rewards without opening invitation`() {
        val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val templates = paths.mapNotNull { (id, path) ->
            File(root, "android/app/src/main/assets/$path").takeIf { it.isFile }?.let { id to readImage(it) }
        }.toMap()
        val result = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(templates))
            .process(fixture("shop-character-joined-20260915.png"))
        assertEquals("${result.observation}", LabyrinthEntryPageState.CHARACTER_JOINED, result.observation.state)
        assertTrue("${result.observation.anchorScores.values}",
            com.landosol.toolbox.labyrinth.labyrinthShopJoinedRewardOwnsRoute(result, Long.MIN_VALUE, 1_000))
        val shopJoined = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(templates))
            .process(fixture("shop-character-joined-20260915.png"), skipJoinedCharacters = true)
        assertTrue("Shop must still recognize joined roles", shopJoined.characterMatches.isNotEmpty())
        val withoutShop = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(
            templates.mapValues { (key, value) ->
                if (key.startsWith("entry.shop.")) PixelImage(11, 11,
                    IntArray(121) { 0xff000000.toInt() or ((it * 73939) and 0xffffff) }) else value
            },
        ))
        val recognized = withoutShop.process(fixture("shop-character-joined-20260915.png"))
        val skipped = withoutShop.process(fixture("shop-character-joined-20260915.png"), skipJoinedCharacters = true)
        assertEquals(LabyrinthEntryPageState.CHARACTER_JOINED, skipped.observation.state)
        assertTrue(recognized.characterMatches.isNotEmpty())
        assertTrue(skipped.characterMatches.isEmpty())
        assertEquals(recognized.anchorMatches[EntryAnchorId.JOINED_CLOSE_STANDARD],
            skipped.anchorMatches[EntryAnchorId.JOINED_CLOSE_STANDARD])
    }

    @Test
    fun `event move screenshot yields confirm target at native and scaled resolutions`() {
        val original = fixture("event-move-confirmation-20260913.png")
        for (frame in listOf(original, scale(original, 1280, 720), scale(original, 1920, 1080))) {
            val result = requireNotNull(LabyrinthNodeMoveConfirmationDetector().detect(frame))
            val rect = result.confirmButtonRect
            assertTrue(result.confidence >= 0.75)
            assertTrue((rect.left + rect.width / 2.0) / frame.width in 0.52..0.71)
            assertTrue((rect.top + rect.height / 2.0) / frame.height in 0.65..0.73)
            // The cancel button sits to the left of confirm on the same row and stays inside
            // the dialog body, so a dismiss tap can never land on the map behind it.
            val cancel = result.cancelButtonRect
            assertTrue((cancel.left + cancel.width / 2.0) / frame.width in 0.29..0.48)
            assertTrue((cancel.top + cancel.height / 2.0) / frame.height in 0.65..0.73)
            assertTrue(cancel.left + cancel.width <= rect.left)
        }
    }

    @Test
    fun `guild event page is not a movement modal`() {
        assertNull(LabyrinthNodeMoveConfirmationDetector().detect(fixture("event-guild-tank-healer-20260913.png")))
    }

    @Test
    fun `movement modal remains compatible with map ownership with bundled templates`() {
        val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS +
            AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val templates = paths.mapNotNull { (id, path) ->
            val file = File(root, "android/app/src/main/assets/$path")
            if (file.isFile) id to readImage(file) else null
        }.toMap()
        val processor = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(templates))
        val result = processor.process(fixture("event-move-confirmation-20260913.png"))
        assertNotNull(result.nodeMoveConfirmation)
        assertTrue("actual=${result.observation.state}", result.observation.state in setOf(
            LabyrinthEntryPageState.UNKNOWN, LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
        ))
        val event = processor.process(fixture("event-guild-tank-healer-20260913.png"))
        assertEquals("event anchors=${event.observation.anchorScores.values.filterKeys { it.contains("event") }}",
            LabyrinthEntryPageState.EVENT_CHOICE, event.observation.state)
    }

    @Test
    fun `event overlay requires both strong anchors before overriding a clearer background map`() {
        val map = mapOf(EntryAnchorId.NODE_HEADER to 0.95, EntryAnchorId.NODE_RELICS to 0.95,
            EntryAnchorId.NODE_RETURN to 0.95)
        fun scores(title: Double, button: Double) = LabyrinthAnchorScores(map + mapOf(
            EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to title,
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to button))
        val classifier = LabyrinthEntryPageClassifier()
        val event = classifier.classify(scores(0.8, 0.85))
        assertEquals(LabyrinthEntryPageState.EVENT_CHOICE, event.state)
        assertEquals(0.8, event.confidence, 0.001)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.95, 0.0)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.0, 0.95)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.60, 0.95)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageClassifier(minScore = 0.90).classify(scores(0.70, 0.75)).state)
    }

    @Test
    fun `node tap survives unknown frames before confirmation but expires and rejects unrelated pages`() {
        for (page in listOf(LabyrinthEntryPageState.UNKNOWN, LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS, LabyrinthEntryPageState.PRE_HOME_DATA_LOADING)) {
            assertTrue(labyrinthPreservesPendingNodeTransitionAcrossPage(page, null, 5_999, 6_000, 0))
            assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(page, null, 6_000, 6_000, 0))
        }
        assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION, null, 100, 6_000, 0))
        assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(
            LabyrinthEntryPageState.UNKNOWN, null, 100, 6_000))
    }

    @Test
    fun `an unaffordable option is greyed blue and only the usable one scores as enabled`() {
        // The stone-slab event with 1,550 coins in hand: option 1 is free, options 2 and 3 cost
        // 2000. The game paints all three blue, so the permissive blue score cannot separate
        // them and the run kept tapping an option the game refuses. Colours below are the medians
        // measured on that screenshot with the production 8x16 sample grid.
        val usable = 0xFF63A6F7.toInt()
        val greyed = 0xFF5265AD.toInt()
        val rect = EntryPixelRect(0, 0, 200, 80)
        fun scores(color: Int) = labyrinthEventBlueButtonConfidence(rect) { _, _ -> color } to
            labyrinthEventEnabledButtonConfidence(rect) { _, _ -> color }

        val (usableBlue, usableEnabled) = scores(usable)
        val (greyedBlue, greyedEnabled) = scores(greyed)
        // Both read as "a blue button is drawn here", which is what layout detection needs.
        assertTrue("usable=$usableBlue greyed=$greyedBlue", usableBlue >= 0.18 && greyedBlue >= 0.18)
        // Only the usable one reads as actionable.
        assertTrue("usable=$usableEnabled", usableEnabled >= 0.35)
        assertTrue("greyed=$greyedEnabled", greyedEnabled < 0.35)
    }

    @Test
    fun `usable event buttons stay well above the enabled threshold on real screenshots`() {
        val frame = fixture("event-single-choice-fishing-20260911.png")
        val document = json.decodeFromString<LabyrinthEventOcrDocument>(File(assets, "labyrinth-event-ocr.json").readText())
        val mapper = LabyrinthEventLayoutMapper(document.layouts)
        val rect = mapper.buttonRects(1, frame.width, frame.height).single()
        val enabled = labyrinthEventEnabledButtonConfidence(rect) { x, y -> frame[x, y] }
        assertTrue("enabled=$enabled", enabled >= 0.35)
    }

    @Test
    fun `guild OCR crop contains complete reward line and button centers land on choice buttons`() {
        val frame = fixture("event-guild-tank-healer-20260913.png")
        val document = json.decodeFromString<LabyrinthEventOcrDocument>(File(assets, "labyrinth-event-ocr.json").readText())
        val mapper = LabyrinthEventLayoutMapper(document.layouts)
        val confidences = (1..3).associateWith { count ->
            mapper.buttonRects(count, frame.width, frame.height).map { rect ->
                labyrinthEventBlueButtonConfidence(rect) { x, y -> frame[x, y] }
            }
        }
        assertEquals("button evidence=$confidences", 2, labyrinthCompleteEventLayoutCount(confidences, 0.12))
        assertTrue(confidences.getValue(2).all { it >= 0.18 })
        val crop = requireNotNull(mapper.ocrBounds(2, frame.width, frame.height))
        // Supplied 1056x594 screenshot: final reward line reaches y=449; preserve its descenders.
        assertTrue("crop=$crop", crop.top + crop.height >= 452)
        val rects = mapper.buttonRects(2, frame.width, frame.height)
        assertEquals(2, rects.size)
        for ((index, rect) in rects.withIndex()) {
            val centerX = rect.left + rect.width / 2
            val centerY = rect.top + rect.height / 2
            assertTrue(centerX in (if (index == 0) 315..435 else 620..740))
            assertTrue(centerY in 470..510)
        }
        val events = json.parseToJsonElement(File(assets, "events.placeholder.json").readText())
            .jsonObject.getValue("events").jsonArray.map { json.decodeFromJsonElement<EventResource>(it) }
        val match = LabyrinthEventTextRecognizer(events).recognize(
            "这里是公会管理协会！请问要找什么样的伙伴？ 想要坦克型成为伙伴！想要治疗型成为伙伴！" +
                "坦克型职能的随机印记×2 +阿尔法金币×200 治疗型职能的随机印记×2 +阿尔法金币×200")
        assertEquals("1101", match.event?.id)
        assertTrue("score=${match.score}, margin=${match.margin}", match.trusted)
    }

    @Test
    fun `all guild and arena variants retain identity against the complete production catalog`() {
        val events = json.parseToJsonElement(File(assets, "events.placeholder.json").readText())
            .jsonObject.getValue("events").jsonArray.map { json.decodeFromJsonElement<EventResource>(it) }
        val recognizer = LabyrinthEventTextRecognizer(events)
        for (id in listOf("1101", "1102", "1103", "1104", "1105", "1109", "1113", "1117")) {
            val event = events.single { it.id == id }
            val match = recognizer.recognize(event.name + event.choices.joinToString(" ") { it.name + it.description })
            assertEquals(id, match.event?.id)
            assertTrue("event=$id, score=${match.score}, margin=${match.margin}", match.trusted)
        }
    }

    @Test
    fun `captured debuff disrupt OCR separates guild variants in the complete catalog`() {
        val events = json.parseToJsonElement(File(assets, "events.placeholder.json").readText())
            .jsonObject.getValue("events").jsonArray.map { json.decodeFromJsonElement<EventResource>(it) }
        val rawText = File(root,
            "android/app/src/test/resources/labyrinth/event-guild-debuff-disrupt-20261024.txt").readText()
        val match = LabyrinthEventTextRecognizer(events).recognize(rawText)
        println("GUILD_CAPTURE event=${match.event?.id} score=${match.score} margin=${match.margin} trusted=${match.trusted}")
        assertEquals("1103", match.event?.id)
        assertTrue("score=${match.score}, margin=${match.margin}", match.trusted)
        assertTrue(match.margin >= 0.045)
        assertEquals(listOf("11031", "11032"), match.event!!.choices.sortedBy { it.slot }.map { it.id })
    }

    @Test
    fun `old OCR callback cannot replace or cancel the new request even for identical images`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        val old = requireNotNull(cache.begin(key, 0))
        cache.clear()
        val fresh = requireNotNull(cache.begin(key, 10))
        cache.complete(old, "old event", 20)
        assertNull(cache.read(key, 20))
        assertNull(cache.begin(key, 21)) // stale callback did not cancel the active request
        cache.complete(fresh, "fresh event", 30)
        assertEquals("fresh event", cache.read(key, 31))
    }

    @Test
    fun `OCR cache expires on static pages and is scoped to crop geometry`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        cache.complete(requireNotNull(cache.begin(key, 0)), "event", 2_000)
        assertEquals("event", cache.read(key, 2_001)) // slow first read is still usable
        assertNull(cache.read(key.copy(rect = EntryPixelRect(20, 10, 100, 100)), 2_001))
        assertEquals("event", cache.read(key, 4_000)) // allow repeated observations on slower frames
        assertNull(cache.read(key, 7_000))
    }

    @Test
    fun `timed out OCR cannot overwrite retry result`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        val old = requireNotNull(cache.begin(key, 0))
        assertNull(cache.begin(key, 4_999))
        val retry = requireNotNull(cache.begin(key, 5_000))
        cache.complete(retry, "retry", 5_100)
        cache.complete(old, "old", 5_200)
        assertEquals("retry", cache.read(key, 5_201))
    }

    private fun fixture(name: String) = readImage(File(root, "android/app/src/test/resources/labyrinth/$name"))

    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun scale(frame: PixelImage, width: Int, height: Int) = PixelImage(width, height,
        IntArray(width * height) { i -> frame[(i % width) * frame.width / width, (i / width) * frame.height / height] })
}
