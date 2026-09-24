package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionDecision
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ResolutionEntryRegressionTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "android/app/build.gradle.kts").isFile }
    private val resources = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
    private val fixtures = File(root, "android/app/src/test/resources/labyrinth/resolution-20260923")
    private val templates by lazy {
        val assets = Json.parseToJsonElement(File(resources, "vision.json").readText())
            .jsonObject.getValue("assets").jsonArray
        LabyrinthEntryTemplateSet(assets.associate { item ->
            val asset = item.jsonObject
            asset.getValue("id").jsonPrimitive.content to
                readImage(File(resources, asset.getValue("file").jsonPrimitive.content))
        })
    }

    @Test fun `legacy cover drops label but edge mapping stays in bounds`() {
        assertNull(ReferenceCoverMapper.map(1920, 1080, EntryReferenceSize(2780, 1264),
            EntryReferenceRect(2142, 55, 426, 65)))
        for ((w, h) in sizes) {
            val rect = requireNotNull(ReferenceAnchoredMapper.map(w, h, EntryReferenceSize(1920, 1080),
                EntryReferenceRect(1504, 49, 361, 55), EntryAlignment.TOP_RIGHT))
            val scale = minOf(w / 1920.0, h / 1080.0)
            assertEquals("right inset $w x $h", 55 * scale, (w - rect.left - rect.width).toDouble(), 1.0)
            assertEquals("top inset $w x $h", 49 * scale, rect.top.toDouble(), 1.0)
        }
        assertNull(ReferenceAnchoredMapper.map(1920, 1080, EntryReferenceSize(1920, 1080),
            EntryReferenceRect(1900, 10, 100, 20), EntryAlignment.TOP_RIGHT))
    }

    @Test fun `real report and independent archived frame recognize connecting without spinner`() {
        for (name in listOf("connecting.png", "connecting-archive.png")) {
            val result = processor().process(readImage(File(fixtures, name)))
            println("REAL $name page=${result.observation.state} connecting=${result.observation.anchorScores[EntryAnchorId.DATA_CONNECTING]} logo=${result.observation.anchorScores[EntryAnchorId.TITLE_LOGO]} tap=${result.observation.anchorScores[EntryAnchorId.TITLE_TAP_PROMPT]} ms=${result.elapsedMillis}")
            assertEquals(name, LabyrinthEntryPageState.PRE_HOME_DATA_LOADING, result.observation.state)
        }
    }

    @Test fun `live joined reward modal stays actionable over the map at three sizes`() {
        val source = readImage(File(fixtures, "joined-modal-20260924.jpg"))
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 2560 to 1440)) {
            val frame = if (source.width == w && source.height == h) source else resize(source, w, h)
            val result = processor().process(frame)
            println("JOINED $w x $h page=${result.observation.state} joined=${result.observation.stateScores[LabyrinthEntryPageState.CHARACTER_JOINED]} map=${result.observation.stateScores[LabyrinthEntryPageState.NODE_SELECTION]}")
            assertEquals("$w x $h", LabyrinthEntryPageState.CHARACTER_JOINED, result.observation.state)
            val close = requireNotNull(result.anchorMatches[EntryAnchorId.JOINED_CLOSE_STANDARD])
            assertTrue("close=$close", close.score >= 0.80)
            assertTrue(close.rect.left > 0 && close.rect.left + close.rect.width < w)
        }
    }

    @Test fun `live win next button survives resolution sampling`() {
        val source = readImage(File(fixtures, "win-next-20260924.jpg"))
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 2560 to 1440)) {
            val result = processor().process(if (w == source.width && h == source.height) source else resize(source, w, h))
            val next = requireNotNull(result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON])
            println("WIN $w x $h page=${result.observation.state} next=${next.score} rect=${next.rect}")
            assertEquals("$w x $h", LabyrinthEntryPageState.BATTLE_RESULT, result.observation.state)
            assertTrue(next.score >= 0.80)
            assertTrue(next.rect.left > w * 0.65 && next.rect.top > h * 0.8)
            assertTrue(next.rect.left + next.rect.width <= w && next.rect.top + next.rect.height <= h)
        }
    }

    @Test fun `same aspect real screenshot recognizes at multiple resolutions`() {
        val source = readImage(File(fixtures, "connecting.png"))
        for ((w, h) in sizes.filter { it.first * 9 == it.second * 16 }) {
            val result = processor().process(resize(source, w, h))
            println("SCALED $w x $h page=${result.observation.state} connecting=${result.observation.anchorScores[EntryAnchorId.DATA_CONNECTING]} logo=${result.observation.anchorScores[EntryAnchorId.TITLE_LOGO]} ms=${result.elapsedMillis}")
            assertEquals("$w x $h", LabyrinthEntryPageState.PRE_HOME_DATA_LOADING, result.observation.state)
        }
    }

    @Test fun `idle title and blank frames do not become connecting`() {
        val idle = readImage(File(fixtures, "idle.png"))
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 2560 to 1440)) {
            val result = processor().process(resize(idle, w, h))
            println("IDLE $w x $h page=${result.observation.state} connecting=${result.observation.anchorScores[EntryAnchorId.DATA_CONNECTING]} logo=${result.observation.anchorScores[EntryAnchorId.TITLE_LOGO]} tap=${result.observation.anchorScores[EntryAnchorId.TITLE_TAP_PROMPT]} ms=${result.elapsedMillis}")
            assertEquals("idle $w x $h", LabyrinthEntryPageState.TITLE_WAITING_TAP, result.observation.state)
        }
        assertEquals(LabyrinthEntryPageState.UNKNOWN,
            processor().process(PixelImage(1280, 720, IntArray(1280 * 720))).observation.state)
    }

    @Test fun `synthetic edge layouts find shifted label across aspect ratios`() {
        // This is a geometry/search test, not evidence of the game's layout on these devices.
        val label = readImage(File(resources, "vision/entry_data_connecting.bmp"))
        for ((w, h) in sizes) {
            val frame = PixelImage(w, h, IntArray(w * h) { 0xff000000.toInt() })
            val expected = requireNotNull(ReferenceAnchoredMapper.map(w, h, EntryReferenceSize(1920, 1080),
                EntryReferenceRect(1504, 49, 361, 55), EntryAlignment.TOP_RIGHT))
            val scaled = resize(label, expected.width, expected.height)
            for (y in 0 until scaled.height) for (x in 0 until scaled.width) {
                frame.pixels[(expected.top + 1 + y) * w + expected.left + 2 + x] = scaled[x, y]
            }
            val matcher = GradientTemplateMatcher()
            val image = frame
            matcher.prepareFrame(image)
            val match = LocalEntryAnchorMatcher.match(image, label, expected, matcher, 8)
            matcher.clearPreparedFrame()
            assertTrue("$w x $h score=${match.score}", match.score >= 0.65)
            assertTrue(kotlin.math.abs(match.rect.left - expected.left - 2) <= 2)
        }
    }

    @Test fun `connection requires title corroboration and suppresses repeated clicks`() {
        val classifier = LabyrinthEntryPageClassifier()
        assertEquals(LabyrinthEntryPageState.UNKNOWN, classifier.classify(
            LabyrinthAnchorScores(mapOf(EntryAnchorId.DATA_CONNECTING to 0.95))).state)
        val planner = LabyrinthEntryActionPlanner(LabyrinthEntryActionPlannerConfig(stableFrames = 1))
        planner.start(0)
        for (time in listOf(0L, 1500L, 3000L)) {
            assertTrue(planner.decide(LabyrinthEntryPageState.TITLE_WAITING_TAP, 1920, 1080, time)
                is LabyrinthEntryActionDecision.Execute)
        }
        for (time in listOf(4500L, 9000L, 30000L, 60000L)) {
            assertTrue(planner.decide(LabyrinthEntryPageState.PRE_HOME_DATA_LOADING, 1920, 1080, time)
                is LabyrinthEntryActionDecision.Wait)
        }
        // Keep the existing total timeout: this change must not allow an infinite wait.
        assertTrue(planner.decide(LabyrinthEntryPageState.PRE_HOME_DATA_LOADING, 1920, 1080, 120001L)
            is LabyrinthEntryActionDecision.Stop)
    }

    private fun processor() = LabyrinthEntryFrameProcessor(templates, nodeScanRequested = { false })
    // Android's compile stubs do not expose java.desktop; use the same JVM reflection as the
    // existing fixture tests. Missing/undecodable fixtures fail rather than silently skip.
    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
    private fun resize(source: PixelImage, width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = ((x + 0.5) * source.width / width - 0.5).coerceIn(0.0, source.width - 1.0)
            val sy = ((y + 0.5) * source.height / height - 0.5).coerceIn(0.0, source.height - 1.0)
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = (x0 + 1).coerceAtMost(source.width - 1)
            val y1 = (y0 + 1).coerceAtMost(source.height - 1)
            val fx = sx - x0; val fy = sy - y0
            var color = 0xff000000.toInt()
            for (shift in listOf(16, 8, 0)) {
                fun channel(px: Int, py: Int) = (source[px, py] ushr shift) and 255
                val top = channel(x0, y0) * (1 - fx) + channel(x1, y0) * fx
                val bottom = channel(x0, y1) * (1 - fx) + channel(x1, y1) * fx
                color = color or ((top * (1 - fy) + bottom * fy + 0.5).toInt() shl shift)
            }
            pixels[y * width + x] = color
        }
        return PixelImage(width, height, pixels)
    }
    private val sizes = listOf(1280 to 720, 1600 to 900, 1920 to 1080, 2560 to 1440,
        3840 to 2160, 1920 to 1200, 2340 to 1080, 2400 to 1080, 2560 to 1080, 2780 to 1264)
}
