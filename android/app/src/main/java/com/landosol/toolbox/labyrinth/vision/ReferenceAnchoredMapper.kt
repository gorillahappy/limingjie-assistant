package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt

/** Position within the viewport; the control size and edge distance scale together. */
enum class EntryAlignment(val x: Double, val y: Double) {
    TOP_LEFT(0.0, 0.0), TOP_CENTER(0.5, 0.0), TOP_RIGHT(1.0, 0.0),
    CENTER_LEFT(0.0, 0.5), CENTER(0.5, 0.5), CENTER_RIGHT(1.0, 0.5),
    BOTTOM_LEFT(0.0, 1.0), BOTTOM_CENTER(0.5, 1.0), BOTTOM_RIGHT(1.0, 1.0),
}

/** Use only for controls known to follow an edge. Existing FIT/COVER layouts are unchanged. */
object ReferenceAnchoredMapper {
    fun map(
        frameWidth: Int,
        frameHeight: Int,
        referenceSize: EntryReferenceSize,
        referenceRect: EntryReferenceRect,
        alignment: EntryAlignment,
    ): EntryPixelRect? {
        require(frameWidth > 0 && frameHeight > 0)
        val scale = minOf(
            frameWidth.toDouble() / referenceSize.width,
            frameHeight.toDouble() / referenceSize.height,
        )
        val offsetX = (frameWidth - referenceSize.width * scale) * alignment.x
        val offsetY = (frameHeight - referenceSize.height * scale) * alignment.y
        val left = (offsetX + referenceRect.x * scale).roundToInt()
        val top = (offsetY + referenceRect.y * scale).roundToInt()
        val right = (offsetX + (referenceRect.x + referenceRect.width) * scale).roundToInt()
        val bottom = (offsetY + (referenceRect.y + referenceRect.height) * scale).roundToInt()
        // Never clamp a partial control: stretching a truncated crop can create false matches.
        if (left < 0 || top < 0 || right > frameWidth || bottom > frameHeight) return null
        if (right - left < 3 || bottom - top < 3) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }
}

/** Bounded translation/scale search, preserving the winning rectangle for downstream users. */
internal object LocalEntryAnchorMatcher {
    fun match(
        frame: PixelImage,
        template: PixelImage,
        expected: EntryPixelRect,
        matcher: GradientTemplateMatcher,
        radius: Int,
    ): EntryAnchorMatch {
        require(radius in 1..64)
        var best = EntryAnchorMatch(matcher.scoreScaleAware(frame, expected, template), expected)
        if (best.score >= 0.90) return best
        val step = (radius / 2).coerceAtLeast(1)
        // Coarse search followed by progressively finer translation and scale steps.
        // A single 1px refinement misses 2-4px errors on 1440p/4K frames.
        val offsets = listOf(-radius, -step, 0, step, radius).distinct()
        for (factor in listOf(1.0, 0.97, 1.03)) {
            val width = (expected.width * factor).roundToInt().coerceAtLeast(3)
            val height = (expected.height * factor).roundToInt().coerceAtLeast(3)
            for (dy in offsets) for (dx in offsets) {
                val left = expected.left + (expected.width - width) / 2 + dx
                val top = expected.top + (expected.height - height) / 2 + dy
                if (left < 0 || top < 0 || left + width > frame.width || top + height > frame.height) continue
                val rect = EntryPixelRect(left, top, width, height)
                val score = matcher.scoreScaleAware(frame, rect, template)
                if (score > best.score) best = EntryAnchorMatch(score, rect)
            }
        }
        var refineStep = (step / 2).coerceAtLeast(1)
        while (true) {
            val coarse = best.rect
            for (dy in listOf(-refineStep, 0, refineStep)) for (dx in listOf(-refineStep, 0, refineStep)) {
                val left = coarse.left + dx
                val top = coarse.top + dy
                if (left < 0 || top < 0 || left + coarse.width > frame.width || top + coarse.height > frame.height) continue
                val rect = EntryPixelRect(left, top, coarse.width, coarse.height)
                val score = matcher.scoreScaleAware(frame, rect, template)
                if (score > best.score) best = EntryAnchorMatch(score, rect)
            }
            if (refineStep == 1) break
            refineStep = (refineStep / 2).coerceAtLeast(1)
        }
        val coarse = best.rect
        for (factor in listOf(0.99, 1.01)) {
            val width = (coarse.width * factor).roundToInt().coerceAtLeast(3)
            val height = (coarse.height * factor).roundToInt().coerceAtLeast(3)
            for (dy in -1..1) for (dx in -1..1) {
                val left = coarse.left + (coarse.width - width) / 2 + dx
                val top = coarse.top + (coarse.height - height) / 2 + dy
                if (left < 0 || top < 0 || left + width > frame.width || top + height > frame.height) continue
                val rect = EntryPixelRect(left, top, width, height)
                val score = matcher.scoreScaleAware(frame, rect, template)
                if (score > best.score) best = EntryAnchorMatch(score, rect)
            }
        }
        return best
    }
}
