package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryPageClassifierTest {
    private val classifier = LabyrinthEntryPageClassifier()

    @Test fun `strong character joined modal owns the visible map despite small score margin`() {
        for (mapScore in listOf(0.9027125150761172, 0.99)) {
            val result = classifier.classify(scores(
                EntryAnchorId.JOINED_HEADER_STANDARD to 0.9581154721987861,
                EntryAnchorId.JOINED_CLOSE_STANDARD to 0.9667412600030884,
                EntryAnchorId.NODE_HEADER_STANDARD to mapScore,
                EntryAnchorId.NODE_CHARACTERS_STANDARD to mapScore,
                EntryAnchorId.NODE_RETURN_STANDARD to mapScore,
            ))
            assertEquals(LabyrinthEntryPageState.CHARACTER_JOINED, result.state)
            assertEquals(0.9581154721987861, result.confidence, 0.000001)
        }
    }

    @Test fun `weak or incomplete joined evidence cannot override the map`() {
        for ((header, close) in listOf(0.79 to 0.99, 0.99 to 0.79, 0.0 to 0.99, 0.99 to 0.0)) {
            val result = classifier.classify(scores(
                EntryAnchorId.JOINED_HEADER_STANDARD to header,
                EntryAnchorId.JOINED_CLOSE_STANDARD to close,
                EntryAnchorId.NODE_HEADER_STANDARD to 0.91,
                EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.96,
                EntryAnchorId.NODE_RETURN_STANDARD to 0.93,
            ))
            assertEquals(LabyrinthEntryPageState.NODE_SELECTION, result.state)
        }
    }

    @Test
    fun `battle in progress relies on the stable menu anchor only`() {
        val result = classifier.classify(
            scores(EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON to 0.91),
        )

        assertEquals(LabyrinthEntryPageState.BATTLE_IN_PROGRESS, result.state)
        assertEquals(0.91, result.confidence, 0.0001)
    }

    @Test
    fun `opening roster shell wins over spurious role reward anchors`() {
        val anchors = scores(
            EntryAnchorId.SELECTION_HEADER_STANDARD to 0.92,
            EntryAnchorId.SELECTION_COUNT_NONE_STANDARD to 0.88,
            EntryAnchorId.INVITE_DISABLED_STANDARD to 0.91,
            EntryAnchorId.ROLE_REWARD_TITLE to 0.77,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.74,
        )

        assertTrue(labyrinthOpeningRosterOwnsInitialSelection(anchors))
        assertEquals(false, labyrinthRoleRewardOwnsInitialSelection(anchors))
    }

    @Test
    fun `role reward owns initial selection when opening shell is absent`() {
        val anchors = scores(
            EntryAnchorId.ROLE_REWARD_TITLE to 0.93,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.88,
        )

        assertEquals(false, labyrinthOpeningRosterOwnsInitialSelection(anchors))
        assertTrue(labyrinthRoleRewardOwnsInitialSelection(anchors))
    }

    @Test
    fun `unknown and ambiguous pages remain hard gated`() {
        val weak = classifier.classify(scores(EntryAnchorId.TITLE_LOGO to 0.44))
        val ambiguous = classifier.classify(
            scores(
                EntryAnchorId.TITLE_LOGO to 0.90,
                EntryAnchorId.TITLE_TAP_PROMPT to 0.80,
                EntryAnchorId.LOADING_NOTICE to 0.85,
                EntryAnchorId.LOADING_PROGRESS to 0.78,
            ),
        )

        assertEquals(LabyrinthEntryPageState.UNKNOWN, weak.state)
        assertEquals(LabyrinthEntryPageState.UNKNOWN, ambiguous.state)
        assertEquals(0.0, ambiguous.confidence, 0.0001)
        assertTrue(ambiguous.reason != null)
    }

    @Test
    fun `dawn home active marker separates active and idle states`() {
        val idle = classifier.classify(
            scores(
                EntryAnchorId.DAWN_START to 0.91,
                EntryAnchorId.DAWN_TICKET_LABEL to 0.72,
                EntryAnchorId.DAWN_ACTIVE to 0.10,
            ),
        )
        val active = classifier.classify(
            scores(
                EntryAnchorId.DAWN_START to 0.91,
                EntryAnchorId.DAWN_TICKET_LABEL to 0.72,
                EntryAnchorId.DAWN_ACTIVE to 0.86,
            ),
        )

        assertEquals(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, idle.state)
        assertEquals(LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, active.state)
    }

    @Test
    fun `role reward selection variant is initial character selection`() {
        val result = classifier.classify(
            scores(
                EntryAnchorId.ROLE_REWARD_TITLE to 0.93,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.88,
            ),
        )

        assertEquals(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, result.state)
        assertEquals(0.88, result.confidence, 0.0001)
    }

    @Test
    fun `item reward popup is a distinct trusted page`() {
        val result = classifier.classify(
            scores(
                EntryAnchorId.ITEM_REWARD_TITLE to 0.93,
                EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.89,
                EntryAnchorId.ITEM_REWARD_CLOSE to 0.96,
            ),
        )

        assertEquals(LabyrinthEntryPageState.ITEM_REWARD, result.state)
        assertEquals(0.89, result.confidence, 0.0001)
    }

    @Test
    fun `strong item reward modal owns the frame over a visible map`() {
        // Scores taken from the 2026-09-17 final-settlement recording (t7.5).
        val result = classifier.classify(
            scores(
                EntryAnchorId.ITEM_REWARD_TITLE to 1.00,
                EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.88,
                EntryAnchorId.ITEM_REWARD_CLOSE to 1.00,
                EntryAnchorId.NODE_HEADER_STANDARD to 0.82,
                EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.90,
                EntryAnchorId.NODE_RETURN_STANDARD to 0.87,
            ),
        )
        assertEquals(LabyrinthEntryPageState.ITEM_REWARD, result.state)

        // A weak modal does not get to override the map.
        val weak = classifier.classify(
            scores(
                EntryAnchorId.ITEM_REWARD_TITLE to 0.60,
                EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.55,
                EntryAnchorId.ITEM_REWARD_CLOSE to 0.62,
                EntryAnchorId.NODE_HEADER_STANDARD to 0.82,
                EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.90,
                EntryAnchorId.NODE_RETURN_STANDARD to 0.87,
            ),
        )
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, weak.state)
    }

    @Test
    fun `event choice and animation are distinct trusted states when optional anchors exist`() {
        val choice = classifier.classify(
            scores(
                EntryAnchorId.EVENT_CHOICE_TITLE to 0.91,
                EntryAnchorId.EVENT_CHOICE_INSTRUCTION to 0.88,
                EntryAnchorId.EVENT_CHOICE_OPTION to 0.90,
            ),
        )
        val animation = classifier.classify(
            scores(
                EntryAnchorId.EVENT_ANIMATION_TITLE to 0.92,
                EntryAnchorId.EVENT_ANIMATION_SKIP to 0.87,
            ),
        )

        assertEquals(LabyrinthEntryPageState.EVENT_CHOICE, choice.state)
        assertEquals(LabyrinthEntryPageState.EVENT_ANIMATION, animation.state)
    }

    @Test
    fun `final settlement pages can be classified independently of score result`() {
        val summary = classifier.classify(
            scores(
                EntryAnchorId.RUN_CLEAR_CHARACTER_SUMMARY_TITLE to 0.91,
                EntryAnchorId.RUN_CLEAR_NEXT_BUTTON to 0.89,
            ),
        )
        val chest = classifier.classify(
            scores(
                EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE to 0.92,
                EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON to 0.90,
            ),
        )

        assertEquals(LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY, summary.state)
        assertEquals(LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT, chest.state)
    }

    @Test
    fun `link choice page requires all three link choice anchors`() {
        val incomplete = classifier.classify(
            scores(
                EntryAnchorId.LINK_CHOICE_TITLE to 0.92,
                EntryAnchorId.LINK_CHOICE_INSTRUCTION to 0.91,
            ),
        )
        val complete = classifier.classify(
            scores(
                EntryAnchorId.LINK_CHOICE_TITLE to 0.92,
                EntryAnchorId.LINK_CHOICE_INSTRUCTION to 0.91,
                EntryAnchorId.LINK_CHOICE_REMAINING to 0.88,
                EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE to 0.92,
            ),
        )

        assertEquals(LabyrinthEntryPageState.UNKNOWN, incomplete.state)
        assertEquals(LabyrinthEntryPageState.LINK_CHOICE, complete.state)
        assertEquals(0.88, complete.confidence, 0.0001)
    }

    @Test
    fun `relic choice page uses its own instruction anchor`() {
        val result = classifier.classify(
            scores(
                EntryAnchorId.LINK_CHOICE_TITLE to 0.92,
                EntryAnchorId.RELIC_CHOICE_INSTRUCTION to 0.91,
                EntryAnchorId.LINK_CHOICE_REMAINING to 0.88,
            ),
        )

        assertEquals(LabyrinthEntryPageState.RELIC_CHOICE, result.state)
        assertEquals(0.88, result.confidence, 0.0001)
    }

    @Test
    fun `node selection requires header while map view uses the same bottom controls without it`() {
        val incomplete = classifier.classify(
            scores(
                EntryAnchorId.NODE_HEADER to 0.82,
                EntryAnchorId.NODE_CHARACTERS to 0.80,
            ),
        )
        val legacyComplete = classifier.classify(
            scores(
                EntryAnchorId.NODE_HEADER to 0.82,
                EntryAnchorId.NODE_RELICS to 0.76,
                EntryAnchorId.NODE_RETURN to 0.79,
            ),
        )
        val mapView = classifier.classify(
            scores(
                EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.84,
                EntryAnchorId.NODE_RETURN_STANDARD to 0.91,
            ),
        )

        assertEquals(LabyrinthEntryPageState.UNKNOWN, incomplete.state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, legacyComplete.state)
        assertEquals(LabyrinthEntryPageState.NODE_MAP_VIEW, mapView.state)

        val standard = classifier.classify(
            scores(
                EntryAnchorId.NODE_HEADER_STANDARD to 0.88,
                EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.84,
                EntryAnchorId.NODE_RETREAT_STANDARD to 0.91,
            ),
        )
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, standard.state)
    }

    @Test
    fun `ordinary battle challenge page requires title and challenge button`() {
        val incomplete = classifier.classify(
            scores(EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL to 0.91),
        )
        val complete = classifier.classify(
            scores(
                EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL to 0.94,
                EntryAnchorId.BATTLE_CHALLENGE_BUTTON to 0.89,
                EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON to 0.87,
            ),
        )

        assertEquals(LabyrinthEntryPageState.UNKNOWN, incomplete.state)
        assertEquals(LabyrinthEntryPageState.BATTLE_CHALLENGE, complete.state)
        assertEquals(0.89, complete.confidence, 0.0001)
    }

    @Test
    fun `strong challenge button also recognizes EX or Boss challenge pages without normal title`() {
        val result = classifier.classify(
            scores(EntryAnchorId.BATTLE_CHALLENGE_BUTTON to 0.86),
        )

        assertEquals(LabyrinthEntryPageState.BATTLE_CHALLENGE, result.state)
        assertEquals(0.86, result.confidence, 0.0001)
    }

    @Test
    fun `battle team selection page requires team header and start button`() {
        val incomplete = classifier.classify(
            scores(
                EntryAnchorId.BATTLE_TEAM_HEADER to 0.93,
                EntryAnchorId.BATTLE_TEAM_FILTER_BAR to 0.91,
            ),
        )
        val complete = classifier.classify(
            scores(
                EntryAnchorId.BATTLE_TEAM_HEADER to 0.94,
                EntryAnchorId.BATTLE_TEAM_FILTER_BAR to 0.89,
                EntryAnchorId.BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY to 0.87,
                EntryAnchorId.BATTLE_TEAM_START_BUTTON to 0.91,
            ),
        )

        assertEquals(LabyrinthEntryPageState.UNKNOWN, incomplete.state)
        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, complete.state)
        assertEquals(0.91, complete.confidence, 0.0001)
    }

    @Test
    fun `strong team start button keeps Boss team editor visible when heading differs`() {
        val result = classifier.classify(
            scores(EntryAnchorId.BATTLE_TEAM_START_BUTTON to 0.87),
        )

        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, result.state)
        assertEquals(0.87, result.confidence, 0.0001)
    }

    @Test
    fun `strong result next button trusts current win page without the legacy reward banner`() {
        val currentWin = classifier.classify(
            scores(
                EntryAnchorId.BATTLE_RESULT_REWARD_BANNER to 0.03,
                EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to 0.92,
            ),
        )
        val insufficientSingleAnchor = classifier.classify(
            scores(
                EntryAnchorId.BATTLE_RESULT_REWARD_BANNER to 0.03,
                EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to 0.79,
            ),
        )

        assertEquals(LabyrinthEntryPageState.BATTLE_RESULT, currentWin.state)
        assertEquals(0.92, currentWin.confidence, 0.0001)
        assertEquals(LabyrinthEntryPageState.UNKNOWN, insufficientSingleAnchor.state)
    }

    @Test
    fun `standard layout alternatives preserve multi anchor page gates`() {
        val loading = classifier.classify(
            scores(
                EntryAnchorId.TITLE_LOGO to 0.88,
                EntryAnchorId.LOADING_PROGRESS to 0.81,
            ),
        )
        val adventure = classifier.classify(
            scores(
                EntryAnchorId.ADVENTURE_HEADER_STANDARD to 0.91,
                EntryAnchorId.LABYRINTH_ENTRY to 0.73,
            ),
        )
        val activeDawnHome = classifier.classify(
            scores(
                EntryAnchorId.DAWN_START_STANDARD to 0.92,
                EntryAnchorId.DAWN_TICKET_LABEL_STANDARD to 0.86,
                EntryAnchorId.DAWN_ACTIVE_STANDARD to 0.89,
            ),
        )

        assertEquals(LabyrinthEntryPageState.GAME_LOADING_PROGRESS, loading.state)
        assertEquals(LabyrinthEntryPageState.ADVENTURE, adventure.state)
        assertEquals(LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, activeDawnHome.state)
    }

    private fun scores(vararg values: Pair<String, Double>): LabyrinthAnchorScores =
        LabyrinthAnchorScores(values.toMap())
}
