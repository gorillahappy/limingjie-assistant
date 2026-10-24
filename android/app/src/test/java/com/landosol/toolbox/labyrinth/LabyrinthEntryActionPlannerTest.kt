package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryActionPlannerTest {
    @Test
    fun `home navigation gives each attempt time and retries inside its trusted label`() {
        val planner = LabyrinthEntryActionPlanner(LabyrinthEntryActionPlannerConfig(stableFrames = 1))
        planner.start(0)
        assertTrue(decide(planner, LabyrinthEntryPageState.HOME_ANNOUNCEMENT, 0) is LabyrinthEntryActionDecision.Execute)
        val rect = EntryPixelRect(1351, 1253, 172, 179)
        val anchors = mapOf(EntryAnchorId.HOME_ADVENTURE to EntryAnchorMatch(0.826, rect))
        fun home(now: Long) = planner.decide(LabyrinthEntryPageState.HOME, 2560, 1440, now, anchorMatches = anchors)
        assertTrue(home(1500) is LabyrinthEntryActionDecision.Wait)
        val first = (home(4000) as LabyrinthEntryActionDecision.Execute).action as AutomationAction.Tap
        assertTrue(home(5200) is LabyrinthEntryActionDecision.Wait)
        val retry = (home(8000) as LabyrinthEntryActionDecision.Execute).action as AutomationAction.Tap
        assertTrue(retry.point.y > first.point.y)
        assertTrue(retry.point.x >= rect.left && retry.point.x < rect.left + rect.width)
        assertTrue(retry.point.y >= rect.top && retry.point.y < rect.top + rect.height)
        assertTrue(home(12000) is LabyrinthEntryActionDecision.Execute)
        assertTrue(home(15999) is LabyrinthEntryActionDecision.Wait)
        assertTrue(home(16000) is LabyrinthEntryActionDecision.Stop)
        assertTrue((home(120001) as LabyrinthEntryActionDecision.Stop).reason.contains("总超时"))
    }

    @Test
    fun `node map view is read only and never becomes node selection`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        )

        val decision = decide(planner, LabyrinthEntryPageState.NODE_MAP_VIEW, 0L)

        assertTrue(decision is LabyrinthEntryActionDecision.Wait)
    }

    @Test
    fun `battle team selection completes the entry planner without emitting an action`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        )

        val decision = decide(
            planner,
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            0L,
        )

        assertTrue(decision is LabyrinthEntryActionDecision.Complete)
    }

    @Test
    fun `entry planner hands battle team execution to the route session`() = kotlinx.coroutines.runBlocking {
        val manager = AutomationSessionManager()
        var executorCalls = 0
        val executor = SessionBoundActionExecutor(manager) {
            executorCalls++
            AutomationBackendResult.Completed
        }
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as
            com.landosol.toolbox.automation.AutomationSessionStartResult.Started).session
        val decision = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        ).decide(
            state = LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
        )

        if (decision is LabyrinthEntryActionDecision.Execute) {
            executor.execute(session.id, decision.action)
        }

        assertTrue(decision is LabyrinthEntryActionDecision.Complete)
        assertEquals(0, executorCalls)
        manager.stop(session.id)
        Unit
    }

    @Test
    fun `configured opening roster clicks only the three guild targets in slot order`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(2)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )
        val matches = listOf(
            openingMatch("1088", "优衣(新年)", 200),
            openingMatch("1003", "怜", 400),
            openingMatch("1089", "怜(新年)", 600),
            openingMatch("9999", "无关角色", 800),
        )

        val confirmedIds = linkedSetOf<String>()
        val expectedIds = listOf("1089", "1088", "1003")
        val decisions = expectedIds.mapIndexed { index, expectedId ->
            val decision = planner.decide(
                state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                frameWidth = 1920,
                frameHeight = 1080,
                nowMillis = index.toLong(),
                anchorScores = selecting,
                openingCharacterMatches = matches.map { match ->
                    match.copy(selected = match.characterId in confirmedIds)
                },
            ) as LabyrinthEntryActionDecision.Execute
            decision.also { confirmedIds += expectedId }
        }
        val labels = decisions.map(LabyrinthEntryActionDecision.Execute::label)

        assertTrue(labels[0].contains("怜(新年)"))
        assertTrue(labels[1].contains("优衣(新年)"))
        assertTrue(labels[2].contains("怜"))
        assertTrue(labels.none { it.contains("无关角色") })

        val firstTap = decisions.first().action as AutomationAction.Tap
        assertEquals(650f, firstTap.point.x, 0.01f)
        assertEquals(345f, firstTap.point.y, 0.01f)
    }

    @Test
    fun `configured opening roster waits for visual feedback instead of blindly retapping the same target`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                openingSelectionFeedbackTimeoutMillis = 100L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )
        val matches = listOf(
            openingMatch("1075", "贪吃佩可(夏日)", 200),
            openingMatch("1351", "雪菲(夏日)", 400),
            openingMatch("1059", "可可萝", 600),
        )

        val first = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
            anchorScores = selecting,
            openingCharacterMatches = matches,
        ) as LabyrinthEntryActionDecision.Execute
        val waiting = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 1L,
            anchorScores = selecting,
            openingCharacterMatches = matches,
        )

        val timedOut = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 101L,
            anchorScores = selecting,
            openingCharacterMatches = matches,
        )

        assertTrue(first.label.contains("贪吃佩可(夏日)"))
        assertTrue(waiting is LabyrinthEntryActionDecision.Wait)
        assertTrue((waiting as LabyrinthEntryActionDecision.Wait).reason.contains("等待选中状态确认"))
        assertTrue(timedOut is LabyrinthEntryActionDecision.Stop)
        assertTrue((timedOut as LabyrinthEntryActionDecision.Stop).reason.contains("避免重复点击错误位置"))
    }

    @Test
    fun `opening feedback timeout counts only stable roster frames and keeps a hard ceiling`() {
        // 2026-09-15 22:49 bundle: the tap did select the card (the "1" badge is on the last
        // frame), but the tap itself changed the viewport signature, so the recognizer reported
        // WAITING_FOR_STABILITY with no cards for every frame until the 3 s timer expired and
        // automation stopped. Blind frames must not count as "no feedback".
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                openingSelectionFeedbackTimeoutMillis = 100L,
                openingSelectionFeedbackStableFrames = 2,
                openingSelectionFeedbackHardTimeoutMillis = 1_000L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )
        val matches = listOf(
            openingMatch("1075", "贪吃佩可(夏日)", 200),
            openingMatch("1351", "雪菲(夏日)", 400),
            openingMatch("1059", "可可萝", 600),
        )
        val stable = openingViewport(position = 0.75)
        val waiting = stable.copy(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY)
        fun decide(now: Long, obs: LabyrinthBattleTeamObservation, cards: List<LabyrinthBattleCharacterMatch> = matches) =
            planner.decide(
                state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                frameWidth = 1920, frameHeight = 1080, nowMillis = now,
                anchorScores = selecting, openingCharacterMatches = cards, openingCharacterSelection = obs,
            )

        assertTrue(decide(0L, stable) is LabyrinthEntryActionDecision.Execute)
        // Well past the soft timeout, but every frame so far was blind: keep waiting.
        for (now in listOf(150L, 300L, 450L)) {
            val d = decide(now, waiting, emptyList())
            assertTrue("now=$now $d", d is LabyrinthEntryActionDecision.Wait)
        }
        // First stable look without the badge: still one short of agreement.
        assertTrue(decide(500L, stable) is LabyrinthEntryActionDecision.Wait)
        // Second stable look agrees the card is unselected: now it is a real failure.
        assertTrue(decide(550L, stable) is LabyrinthEntryActionDecision.Stop)
    }

    @Test
    fun `opening feedback that arrives late on a stable frame clears the pending tap`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                openingSelectionFeedbackTimeoutMillis = 100L,
                openingSelectionFeedbackStableFrames = 2,
                openingSelectionFeedbackHardTimeoutMillis = 1_000L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )
        val before = listOf(
            openingMatch("1075", "贪吃佩可(夏日)", 200),
            openingMatch("1351", "雪菲(夏日)", 400),
            openingMatch("1059", "可可萝", 600),
        )
        val after = listOf(
            openingMatch("1075", "贪吃佩可(夏日)", 200, selected = true),
            openingMatch("1351", "雪菲(夏日)", 400),
            openingMatch("1059", "可可萝", 600),
        )
        val stable = openingViewport(position = 0.75)
        val waiting = stable.copy(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY)
        fun decide(now: Long, obs: LabyrinthBattleTeamObservation, cards: List<LabyrinthBattleCharacterMatch>) =
            planner.decide(
                state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                frameWidth = 1920, frameHeight = 1080, nowMillis = now,
                anchorScores = selecting, openingCharacterMatches = cards, openingCharacterSelection = obs,
            )

        assertTrue(decide(0L, stable, before) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(200L, waiting, emptyList()) is LabyrinthEntryActionDecision.Wait)
        assertTrue(decide(400L, waiting, emptyList()) is LabyrinthEntryActionDecision.Wait)
        // Feedback finally visible at 600 ms, six times the soft timeout: it must be accepted and
        // the planner must move on to the next target instead of stopping.
        val next = decide(600L, stable, after)
        assertTrue("$next", next is LabyrinthEntryActionDecision.Execute)
        assertTrue((next as LabyrinthEntryActionDecision.Execute).label.contains("2/3"))

        // Hard ceiling: a roster that never stabilises still ends, it does not hang forever.
        assertTrue(decide(601L, waiting, emptyList()) is LabyrinthEntryActionDecision.Wait)
        assertTrue(decide(1_700L, waiting, emptyList()) is LabyrinthEntryActionDecision.Stop)
    }

    @Test
    fun `configured opening roster invites only after the exact three targets are visibly selected`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(2)
        val ready = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.10,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.92,
            EntryAnchorId.INVITE_DISABLED to 0.11,
            EntryAnchorId.INVITE_ENABLED to 0.94,
        )
        val wrongSelection = listOf(
            openingMatch("1088", "优衣(新年)", 200, selected = true),
            openingMatch("1003", "怜", 400, selected = true),
            openingMatch("1089", "怜(新年)", 600),
            openingMatch("9999", "无关角色", 800, selected = true),
        )

        val refused = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
            anchorScores = ready,
            openingCharacterMatches = wrongSelection,
        )

        assertTrue(refused is LabyrinthEntryActionDecision.Wait)

        val exactSelection = wrongSelection.map { match ->
            match.copy(selected = match.characterId in setOf("1089", "1088", "1003"))
        }
        val invite = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 1L,
            anchorScores = ready,
            openingCharacterMatches = exactSelection,
        ) as LabyrinthEntryActionDecision.Execute

        assertEquals(LabyrinthEntryActionKind.INVITE_INITIAL_CHARACTERS, invite.kind)
    }

    @Test
    fun `configured opening roster scrolls when current stable viewport has no remaining target`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                openingRosterScrollIntervalMillis = 1L,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )

        val decision = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
            anchorScores = selecting,
            openingCharacterMatches = listOf(openingMatch("9999", "无关角色", 200)),
            openingCharacterSelection = openingViewport(position = 0.25),
        ) as LabyrinthEntryActionDecision.Execute

        assertEquals(LabyrinthEntryActionKind.SCROLL_INITIAL_CHARACTERS, decision.kind)
        assertTrue(decision.action is AutomationAction.Swipe)
    }

    @Test
    fun `opening search swipes when scrollbar is absent or full height and portraits are unresolved`() {
        for (visibleThumb in listOf(false, true)) {
            val planner = LabyrinthEntryActionPlanner(
                LabyrinthEntryActionPlannerConfig(stableFrames = 1, openingRosterScrollIntervalMillis = 1L),
            )
            planner.configureOpeningRoster(3)
            val unresolved = openingMatch("9999", "未知", 200).copy(characterId = null, trusted = false)
            val measured = openingViewport(position = 0.0, canScroll = false)
            val viewport = measured.copy(
                visibleCharacters = listOf(unresolved),
                scrollbar = measured.scrollbar.copy(
                    visible = visibleThumb,
                    thumbRect = measured.scrollbar.thumbRect.takeIf { visibleThumb },
                ),
            )
            val decision = planner.decide(
                state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                frameWidth = 1920,
                frameHeight = 1080,
                nowMillis = 0L,
                openingCharacterMatches = listOf(unresolved),
                openingCharacterSelection = viewport,
            ) as LabyrinthEntryActionDecision.Execute
            assertEquals(LabyrinthEntryActionKind.SCROLL_INITIAL_CHARACTERS, decision.kind)
            val swipe = decision.action as AutomationAction.Swipe
            assertTrue(swipe.start.y > swipe.end.y)
        }
    }

    @Test
    fun `configured opening roster stops only after bottom viewport confirms missing targets`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                openingRosterBottomStableFrames = 2,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )
        val viewport = openingViewport(position = 1.0)

        val first = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
            anchorScores = selecting,
            openingCharacterMatches = listOf(openingMatch("9999", "无关角色", 200)),
            openingCharacterSelection = viewport,
        )
        val second = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 1L,
            anchorScores = selecting,
            openingCharacterMatches = listOf(openingMatch("9999", "无关角色", 200)),
            openingCharacterSelection = viewport,
        )

        assertTrue(first is LabyrinthEntryActionDecision.Wait)
        assertTrue(second is LabyrinthEntryActionDecision.Stop)
        assertTrue((second as LabyrinthEntryActionDecision.Stop).reason.contains("滑到底"))
    }

    @Test
    fun `configured opening roster treats cardless stable overscroll as rebound not another page`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                openingRosterScrollIntervalMillis = 1L,
                openingRosterBottomStableFrames = 2,
                requireConfiguredOpeningRoster = true,
            ),
        )
        planner.configureOpeningRoster(1)
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )

        val blankTop = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 0L,
            anchorScores = selecting,
            openingCharacterMatches = emptyList(),
            openingCharacterSelection = openingViewport(position = 0.0),
        )
        val blankBottom = planner.decide(
            state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            frameWidth = 1920,
            frameHeight = 1080,
            nowMillis = 10L,
            anchorScores = selecting,
            openingCharacterMatches = emptyList(),
            openingCharacterSelection = openingViewport(position = 1.0),
        )

        assertTrue(blankTop is LabyrinthEntryActionDecision.Wait)
        assertTrue(blankBottom is LabyrinthEntryActionDecision.Wait)
        assertTrue((blankTop as LabyrinthEntryActionDecision.Wait).reason.contains("回弹"))
        assertTrue((blankBottom as LabyrinthEntryActionDecision.Wait).reason.contains("回弹"))
    }

    @Test
    fun `trusted pages require stability and map one action at a time`() {
        val planner = LabyrinthEntryActionPlanner()
        planner.start(0L)

        assertTrue(decide(planner, LabyrinthEntryPageState.HOME, 0L) is LabyrinthEntryActionDecision.Wait)
        val home = decide(planner, LabyrinthEntryPageState.HOME, 500L) as LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.OPEN_ADVENTURE, home.kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(1070f, 1030f)), home.action)

        assertTrue(decide(planner, LabyrinthEntryPageState.ADVENTURE, 1_000L) is LabyrinthEntryActionDecision.Wait)
        val adventure = decide(planner, LabyrinthEntryPageState.ADVENTURE, 1_700L) as
            LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.OPEN_DAWN_REALM, adventure.kind)
    }

    @Test
    fun `wide frame maps reference clicks into centered game viewport`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        )

        val decision = planner.decide(
            state = LabyrinthEntryPageState.HOME,
            frameWidth = 2560,
            frameHeight = 1080,
            nowMillis = 0L,
        ) as LabyrinthEntryActionDecision.Execute

        assertEquals(AutomationAction.Tap(ScreenPoint(1390f, 1030f)), decision.action)
    }

    @Test
    fun `wide layout uses the matched anchor center for entry actions`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        )

        val decision = planner.decide(
            state = LabyrinthEntryPageState.ADVENTURE,
            frameWidth = 2780,
            frameHeight = 1264,
            nowMillis = 0L,
            anchorMatches = mapOf(
                EntryAnchorId.LABYRINTH_ENTRY to EntryAnchorMatch(
                    score = 0.95,
                    rect = EntryPixelRect(left = 2431, top = 988, width = 223, height = 56),
                ),
            ),
        ) as LabyrinthEntryActionDecision.Execute

        assertEquals(
            AutomationAction.Tap(ScreenPoint(2542.5f, 1016f)),
            decision.action,
        )
    }

    @Test
    fun `unknown clicks are allowed only inside bounded pre announcement phase`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                pageActionIntervalMillis = 1L,
                preAnnouncementClickIntervalMillis = 1L,
                maxPreAnnouncementClicks = 2,
            ),
        )
        planner.start(0L)

        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 0L) is LabyrinthEntryActionDecision.Wait)
        assertTrue(decide(planner, LabyrinthEntryPageState.TITLE_WAITING_TAP, 1L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 2L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 3L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 4L) is LabyrinthEntryActionDecision.Stop)
    }

    @Test
    fun `repeated page actions stop after configured failure limit`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                pageActionIntervalMillis = 1L,
                homeNavigationActionIntervalMillis = 1L,
                maxPageActionAttempts = 2,
            ),
        )

        assertTrue(decide(planner, LabyrinthEntryPageState.HOME, 0L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.HOME, 1L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.HOME, 2L) is LabyrinthEntryActionDecision.Stop)
    }

    @Test
    fun `active run is resumed and completed node finishes without another click`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1),
        )

        val active = decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 0L) as
            LabyrinthEntryActionDecision.Execute
        val node = decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 1_200L)

        assertEquals(LabyrinthEntryActionKind.RESUME_DAWN_REALM, active.kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(1175f, 610f)), active.action)
        assertTrue(node is LabyrinthEntryActionDecision.Complete)
    }

    private fun openingMatch(
        id: String,
        name: String,
        left: Int,
        selected: Boolean = false,
    ) = LabyrinthBattleCharacterMatch(
        slotId = "opening-$id",
        characterId = id,
        displayName = name,
        iconVariant = "default",
        confidence = 1.0,
        screenRect = EntryPixelRect(left, 300, 100, 100),
        selected = selected,
    )

    private fun openingViewport(
        position: Double,
        canScroll: Boolean = true,
    ) = LabyrinthBattleTeamObservation(
        currentFilter = LabyrinthBattleElementFilter.ALL,
        filters = emptyList(),
        visibleCharacters = emptyList(),
        selectedCharacters = emptyList(),
        scrollbar = LabyrinthBattleScrollbarObservation(
            trackRect = EntryPixelRect(1800, 200, 20, 500),
            thumbRect = EntryPixelRect(1800, 200 + (position * 300).toInt(), 20, 200),
            visible = true,
            canScroll = canScroll,
            position = position,
        ),
        recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
        viewportRevision = 1L,
    )

    @Test
    fun `stable node page can be adopted when automation starts in the middle of a run`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                midRunNodeSelectionStableFrames = 3,
            ),
        )

        assertTrue(
            decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 0L) is
                LabyrinthEntryActionDecision.Wait,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 1L) is
                LabyrinthEntryActionDecision.Wait,
        )
        val handoff = decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 2L)

        assertTrue(handoff is LabyrinthEntryActionDecision.Complete)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, (handoff as LabyrinthEntryActionDecision.Complete).state)
    }

    @Test
    fun `active run retries only after the dawn loading interval`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                pageActionIntervalMillis = 1L,
                dawnRealmActionIntervalMillis = 8_000L,
            ),
        )

        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 0L) is
                LabyrinthEntryActionDecision.Execute,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 7_999L) is
                LabyrinthEntryActionDecision.Wait,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 8_000L) is
                LabyrinthEntryActionDecision.Execute,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 16_000L) is
                LabyrinthEntryActionDecision.Execute,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 16_001L) is
                LabyrinthEntryActionDecision.Wait,
        )
        assertTrue(
            decide(planner, LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 24_000L) is
                LabyrinthEntryActionDecision.Stop,
        )
    }

    @Test
    fun `default character targets stay on populated first row`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
            ),
        )
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )

        val points = (0L..2L).map { now ->
            val decision = decide(
                planner,
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                now,
                selecting,
            ) as LabyrinthEntryActionDecision.Execute
            (decision.action as AutomationAction.Tap).point
        }

        assertEquals(3, points.distinct().size)
        assertTrue(points.all { it.y == 430f })
        assertTrue(points.all { it.x in setOf(220f, 430f, 640f, 850f, 1065f, 1275f) })
    }

    @Test
    fun `initial selection clicks three distinct configured characters then completes acquisition`() {
        val targets = listOf(
            ScreenPoint(220f, 375f),
            ScreenPoint(430f, 375f),
            ScreenPoint(640f, 375f),
        )
        val planner = LabyrinthEntryActionPlanner(
            config = LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                pageActionIntervalMillis = 1L,
                characterSelectionClickIntervalMillis = 1L,
                characterAcquisitionClickIntervalMillis = 1L,
            ),
            characterSelectionOrder = targets,
        )
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )

        targets.forEachIndexed { index, point ->
            val decision = decide(
                planner,
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                index.toLong(),
                selecting,
            ) as LabyrinthEntryActionDecision.Execute
            assertEquals(LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER, decision.kind)
            assertEquals(AutomationAction.Tap(point), decision.action)
        }

        val invite = decide(
            planner,
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            3L,
            scores(
                EntryAnchorId.SELECTION_COUNT_NONE to 0.10,
                EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.92,
                EntryAnchorId.INVITE_DISABLED to 0.11,
                EntryAnchorId.INVITE_ENABLED to 0.94,
            ),
        ) as LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.INVITE_INITIAL_CHARACTERS, invite.kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(1630f, 950f)), invite.action)

        val transientNode = decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 4L)
        assertTrue(transientNode is LabyrinthEntryActionDecision.Wait)

        val animation = decide(planner, LabyrinthEntryPageState.UNKNOWN, 5L) as
            LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.ADVANCE_CHARACTER_ACQUISITION, animation.kind)

        val joined = decide(planner, LabyrinthEntryPageState.CHARACTER_JOINED, 6L) as
            LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.CLOSE_CHARACTER_JOINED, joined.kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(960f, 870f)), joined.action)

        val node = decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 7L)
        assertTrue(node is LabyrinthEntryActionDecision.Complete)
    }

    @Test
    fun `manual character selection never clicks a card and confirms only after three are selected`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                manualCharacterSelection = true,
                characterSelectionClickIntervalMillis = 1L,
            ),
        )
        val selecting = scores(
            EntryAnchorId.SELECTION_COUNT_NONE to 0.90,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.10,
            EntryAnchorId.INVITE_DISABLED to 0.91,
            EntryAnchorId.INVITE_ENABLED to 0.12,
        )

        val waiting = decide(
            planner,
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            0L,
            selecting,
        )
        assertTrue(waiting is LabyrinthEntryActionDecision.Wait)

        // 人工选择可持续超过入口总超时，不会被停止或被程序代选。
        val stillWaiting = decide(
            planner,
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            180_000L,
            selecting,
        )
        assertTrue(stillWaiting is LabyrinthEntryActionDecision.Wait)

        val invite = decide(
            planner,
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            180_001L,
            scores(
                EntryAnchorId.SELECTION_COUNT_NONE to 0.10,
                EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.92,
                EntryAnchorId.INVITE_DISABLED to 0.11,
                EntryAnchorId.INVITE_ENABLED to 0.94,
            ),
        ) as LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.INVITE_INITIAL_CHARACTERS, invite.kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(1630f, 950f)), invite.action)
    }

    @Test
    fun `manual invitation can be resumed safely from character joined page`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                manualCharacterSelection = true,
                pageActionIntervalMillis = 1L,
            ),
        )

        assertTrue(
            decide(
                planner,
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                0L,
            ) is LabyrinthEntryActionDecision.Wait,
        )
        val joined = decide(planner, LabyrinthEntryPageState.CHARACTER_JOINED, 1L) as
            LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.CLOSE_CHARACTER_JOINED, joined.kind)

        val node = decide(planner, LabyrinthEntryPageState.NODE_SELECTION, 2L)
        assertTrue(node is LabyrinthEntryActionDecision.Complete)
    }

    @Test
    fun `item reward popup closes through its matched close anchor`() {
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1, pageActionIntervalMillis = 1L),
        )
        val result = decide(
            planner,
            LabyrinthEntryPageState.ITEM_REWARD,
            0L,
            scores(
                EntryAnchorId.ITEM_REWARD_TITLE to 0.95,
                EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.92,
                EntryAnchorId.ITEM_REWARD_CLOSE to 0.96,
            ),
        )

        assertTrue(result is LabyrinthEntryActionDecision.Execute)
        assertEquals(LabyrinthEntryActionKind.CLOSE_ITEM_REWARD, (result as LabyrinthEntryActionDecision.Execute).kind)
        assertEquals(AutomationAction.Tap(ScreenPoint(960f, 965f)), result.action)
    }

    @Test
    fun `character acquisition animation is bounded by click count`() {
        val planner = LabyrinthEntryActionPlanner(
            config = LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                characterAcquisitionClickIntervalMillis = 1L,
                maxCharacterAcquisitionClicks = 2,
            ),
            characterSelectionOrder = listOf(
                ScreenPoint(220f, 375f),
                ScreenPoint(430f, 375f),
                ScreenPoint(640f, 375f),
            ),
        )
        val ready = scores(
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 0.92,
            EntryAnchorId.INVITE_ENABLED to 0.94,
        )

        assertTrue(
            decide(planner, LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 0L, ready) is
                LabyrinthEntryActionDecision.Execute,
        )
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 1L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 2L) is LabyrinthEntryActionDecision.Execute)
        assertTrue(decide(planner, LabyrinthEntryPageState.UNKNOWN, 3L) is LabyrinthEntryActionDecision.Stop)
    }

    private fun decide(
        planner: LabyrinthEntryActionPlanner,
        state: LabyrinthEntryPageState,
        nowMillis: Long,
        anchorScores: LabyrinthAnchorScores? = null,
    ): LabyrinthEntryActionDecision = planner.decide(state, 1920, 1080, nowMillis, anchorScores)

    private fun scores(vararg values: Pair<String, Double>): LabyrinthAnchorScores =
        LabyrinthAnchorScores(values.toMap())
}
