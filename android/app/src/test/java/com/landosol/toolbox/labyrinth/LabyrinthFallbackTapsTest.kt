package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryReferenceMapping
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameProcessor
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.ReferenceCoverMapper
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper
import com.landosol.toolbox.labyrinth.vision.ReferenceAnchoredMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthFallbackTapsTest {
    /**
     * Every fallback that names an anchor must actually lie inside that anchor's declared
     * reference rectangle when both are mapped to the same frame. Otherwise "anchor missed but the
     * button is still there" and "we are guessing" would be indistinguishable to the future gate.
     */
    @Test
    fun `anchored fallback taps land inside their declared anchor geometry`() {
        for ((frameWidth, frameHeight) in listOf(1920 to 1080, 2400 to 1080, 1600 to 720)) {
            LABYRINTH_FALLBACK_TAP_POLICIES.filter { it.anchorIds.isNotEmpty() }.forEach { policy ->
                val tap = policy.tap.rect(frameWidth, frameHeight)
                val tapX = tap.left + tap.width / 2
                val tapY = tap.top + tap.height / 2
                val covered = policy.anchorIds.any { anchorId ->
                    LabyrinthEntryFrameProcessor.DEFINITIONS_BY_ID[anchorId].orEmpty().any { definition ->
                        val rect = when (definition.mapping) {
                            EntryReferenceMapping.COVER -> ReferenceCoverMapper.map(
                                frameWidth, frameHeight, definition.referenceSize, definition.referenceRect,
                            )
                            EntryReferenceMapping.FIT -> ReferenceFitMapper.map(
                                frameWidth, frameHeight, definition.referenceSize, definition.referenceRect,
                            )
                            EntryReferenceMapping.ANCHORED -> ReferenceAnchoredMapper.map(
                                frameWidth, frameHeight, definition.referenceSize, definition.referenceRect,
                                definition.alignment,
                            )
                        } ?: return@any false
                        tapX in rect.left until rect.left + rect.width &&
                            tapY in rect.top until rect.top + rect.height
                    }
                }
                assertTrue(
                    "${policy.page} ${policy.tap} at ${frameWidth}x$frameHeight is outside ${policy.anchorIds}",
                    covered,
                )
            }
        }
    }

    @Test
    fun `fallback taps stay inside the frame at every supported size`() {
        for ((frameWidth, frameHeight) in listOf(1920 to 1080, 2400 to 1080, 1280 to 720, 960 to 540)) {
            LabyrinthFallbackTap.entries.forEach { tap ->
                val rect = tap.rect(frameWidth, frameHeight)
                assertTrue("$tap $rect", rect.left >= 0 && rect.top >= 0)
                assertTrue("$tap $rect", rect.left + rect.width <= frameWidth)
                assertTrue("$tap $rect", rect.top + rect.height <= frameHeight)
            }
        }
    }

    @Test
    fun `pages without a policy row may not fall back and unknown keeps only blind probes`() {
        assertTrue(labyrinthAllowedFallbackTaps(LabyrinthEntryPageState.NODE_SELECTION).isEmpty())
        assertTrue(labyrinthAllowedFallbackTaps(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION).isEmpty())
        assertTrue(labyrinthAllowedFallbackTaps(LabyrinthEntryPageState.HOME).isEmpty())
        // Only the post-Boss settlement advance may still fall back on UNKNOWN; the reward and
        // portrait blind taps were removed because they mis-clicked map nodes after a battle.
        assertEquals(
            setOf(
                LabyrinthFallbackTap.CENTER,
                LabyrinthFallbackTap.BOTTOM_RIGHT,
                LabyrinthFallbackTap.BOTTOM_CENTER,
            ),
            labyrinthAllowedFallbackTaps(LabyrinthEntryPageState.UNKNOWN),
        )
        assertTrue(
            LABYRINTH_FALLBACK_TAP_POLICIES
                .filter { it.page == LabyrinthEntryPageState.UNKNOWN }
                .all { it.anchorIds.isEmpty() },
        )
    }
}
