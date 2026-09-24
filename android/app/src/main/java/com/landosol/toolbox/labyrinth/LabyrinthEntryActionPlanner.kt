package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.CoordinateMapper
import com.landosol.toolbox.automation.PixelRect
import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

data class LabyrinthEntryActionPlannerConfig(
    val stableFrames: Int = 2,
    val manualCharacterSelection: Boolean = false,
    val requireConfiguredOpeningRoster: Boolean = false,
    val pageActionIntervalMillis: Long = 1_200L,
    val dawnRealmActionIntervalMillis: Long = 8_000L,
    val preAnnouncementClickIntervalMillis: Long = 900L,
    val maxPageActionAttempts: Int = 3,
    val maxPreAnnouncementClicks: Int = 30,
    val preAnnouncementTimeoutMillis: Long = 45_000L,
    val characterSelectionClickIntervalMillis: Long = 2_000L,
    val openingSelectionFeedbackTimeoutMillis: Long = 3_000L,
    /** Stable roster frames that must agree the tapped card is still unselected before stopping. */
    val openingSelectionFeedbackStableFrames: Int = 2,
    /** Absolute ceiling regardless of stability, so a permanently unstable roster cannot hang. */
    val openingSelectionFeedbackHardTimeoutMillis: Long = 12_000L,
    val openingRosterScrollIntervalMillis: Long = 900L,
    val maxOpeningRosterScrolls: Int = 12,
    val openingRosterBottomStableFrames: Int = 3,
    val characterAcquisitionClickIntervalMillis: Long = 600L,
    val maxCharacterAcquisitionClicks: Int = 40,
    val characterAcquisitionTimeoutMillis: Long = 30_000L,
    val selectionReadyMinScore: Double = 0.45,
    /** A stable map seen without any opening roster page is treated as a mid-run handoff. */
    val midRunNodeSelectionStableFrames: Int = 6,
    val totalTimeoutMillis: Long = 120_000L,
) {
    init {
        require(stableFrames > 0)
        require(pageActionIntervalMillis > 0)
        require(dawnRealmActionIntervalMillis > 0)
        require(preAnnouncementClickIntervalMillis > 0)
        require(maxPageActionAttempts > 0)
        require(maxPreAnnouncementClicks > 0)
        require(preAnnouncementTimeoutMillis > 0)
        require(characterSelectionClickIntervalMillis > 0)
        require(openingSelectionFeedbackTimeoutMillis > 0)
        require(openingSelectionFeedbackStableFrames > 0)
        require(openingSelectionFeedbackHardTimeoutMillis >= openingSelectionFeedbackTimeoutMillis)
        require(openingRosterScrollIntervalMillis > 0)
        require(maxOpeningRosterScrolls > 0)
        require(openingRosterBottomStableFrames > 0)
        require(characterAcquisitionClickIntervalMillis > 0)
        require(maxCharacterAcquisitionClicks > 0)
        require(characterAcquisitionTimeoutMillis > 0)
        require(selectionReadyMinScore in 0.0..1.0)
        require(midRunNodeSelectionStableFrames >= stableFrames)
        require(totalTimeoutMillis >= preAnnouncementTimeoutMillis)
        require(totalTimeoutMillis >= characterAcquisitionTimeoutMillis)
    }
}

enum class LabyrinthEntryActionKind {
    TITLE_CONTINUE,
    PRE_ANNOUNCEMENT_CONTINUE,
    CLOSE_ANNOUNCEMENT,
    OPEN_ADVENTURE,
    OPEN_DAWN_REALM,
    START_DAWN_REALM,
    RESUME_DAWN_REALM,
    SELECT_INITIAL_CHARACTER,
    SCROLL_INITIAL_CHARACTERS,
    INVITE_INITIAL_CHARACTERS,
    ADVANCE_CHARACTER_ACQUISITION,
    CLOSE_CHARACTER_JOINED,
    CLOSE_ITEM_REWARD,
}

sealed interface LabyrinthEntryActionDecision {
    data class Execute(
        val kind: LabyrinthEntryActionKind,
        val label: String,
        val action: AutomationAction,
    ) : LabyrinthEntryActionDecision

    data class Wait(val reason: String) : LabyrinthEntryActionDecision
    data class Complete(val state: LabyrinthEntryPageState, val reason: String) : LabyrinthEntryActionDecision
    data class Stop(val reason: String) : LabyrinthEntryActionDecision
}

/** Stateful single-step planner. Every call represents one newly recognized frame. */
class LabyrinthEntryActionPlanner(
    private val config: LabyrinthEntryActionPlannerConfig = LabyrinthEntryActionPlannerConfig(),
    private val coordinateMapper: CoordinateMapper = CoordinateMapper(PixelSize(1920, 1080)),
    characterSelectionOrder: List<ScreenPoint> = DEFAULT_CHARACTER_CANDIDATES.shuffled(),
) {
    private val characterSelectionTargets = characterSelectionOrder.distinct().take(REQUIRED_INITIAL_CHARACTERS)
    private var startedAt = Long.MIN_VALUE
    private var preAnnouncementStartedAt = Long.MIN_VALUE
    private var lastState: LabyrinthEntryPageState? = null
    private var stableFrameCount = 0
    private var attemptsForState = 0
    private var preAnnouncementClicks = 0
    private var lastActionAt = Long.MIN_VALUE
    private var selectedCharacterClicks = 0
    private var characterAcquisitionStartedAt = Long.MIN_VALUE
    private var characterAcquisitionClicks = 0
    private var characterAcquisitionActive = false
    private var joinedPageDetected = false
    private var joinedCloseRequested = false
    private var openingRosterStarted = false
    private var openingRosterCompleted = false
    private var resumedExistingRun = false
    private var openingRosterPolicy: LabyrinthOpeningRosterPolicy? = null
    private val selectedOpeningCharacterIds = linkedSetOf<String>()
    private var pendingOpeningCharacterId: String? = null
    private var pendingOpeningCharacterName: String? = null
    private var pendingOpeningCharacterClickedAt = Long.MIN_VALUE
    private var pendingOpeningStableFramesSeen = 0
    private var openingRosterScrollAttempts = 0
    private var openingRosterBottomMissFrames = 0

    init {
        require(characterSelectionTargets.size == REQUIRED_INITIAL_CHARACTERS) {
            "At least $REQUIRED_INITIAL_CHARACTERS distinct character targets are required"
        }
    }

    fun start(nowMillis: Long) {
        startedAt = nowMillis
        preAnnouncementStartedAt = Long.MIN_VALUE
        lastState = null
        stableFrameCount = 0
        attemptsForState = 0
        preAnnouncementClicks = 0
        lastActionAt = Long.MIN_VALUE
        selectedCharacterClicks = 0
        characterAcquisitionStartedAt = Long.MIN_VALUE
        characterAcquisitionClicks = 0
        characterAcquisitionActive = false
        joinedPageDetected = false
        joinedCloseRequested = false
        openingRosterStarted = false
        openingRosterCompleted = false
        resumedExistingRun = false
        selectedOpeningCharacterIds.clear()
        pendingOpeningCharacterId = null
        pendingOpeningCharacterName = null
        pendingOpeningCharacterClickedAt = Long.MIN_VALUE
        pendingOpeningStableFramesSeen = 0
        openingRosterScrollAttempts = 0
        openingRosterBottomMissFrames = 0
    }

    fun configureOpeningRoster(
        guildId: Int?,
        openingRosters: Map<Int, List<List<String>>> = emptyMap(),
        displayNameFor: (String) -> String? = { null },
    ) {
        openingRosterPolicy = LabyrinthOpeningRosterCatalog.policyFor(guildId, openingRosters, displayNameFor)
        selectedOpeningCharacterIds.clear()
        pendingOpeningCharacterId = null
        pendingOpeningCharacterName = null
        pendingOpeningCharacterClickedAt = Long.MIN_VALUE
        pendingOpeningStableFramesSeen = 0
        openingRosterScrollAttempts = 0
        openingRosterBottomMissFrames = 0
    }

    fun decide(
        state: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
        nowMillis: Long,
        anchorScores: LabyrinthAnchorScores? = null,
        anchorMatches: Map<String, EntryAnchorMatch> = emptyMap(),
        openingCharacterMatches: List<LabyrinthBattleCharacterMatch> = emptyList(),
        openingCharacterSelection: LabyrinthBattleTeamObservation? = null,
    ): LabyrinthEntryActionDecision {
        if (startedAt == Long.MIN_VALUE) start(nowMillis)
        // 人工选人不应消耗入口总超时；选满后由程序确认邀请并继续角色获得流程。
        if (config.manualCharacterSelection && state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            startedAt = nowMillis
        }
        if (state == LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE) {
            // A resumed server run has already passed the opening roster. It may go directly to
            // the map and must not be held behind the fresh-run roster gate.
            resumedExistingRun = true
        }
        if (state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            openingRosterStarted = true
        }
        if (nowMillis - startedAt > config.totalTimeoutMillis) {
            return LabyrinthEntryActionDecision.Stop("入口流程超过总超时")
        }
        observe(state)
        if (
            state == LabyrinthEntryPageState.NODE_SELECTION &&
            !openingRosterStarted &&
            !resumedExistingRun &&
            stableFrameCount >= config.midRunNodeSelectionStableFrames
        ) {
            // A fresh run exposes a short node-map glimpse before initial role selection. A map
            // that remains stable beyond that window is a user-started mid-run session instead.
            resumedExistingRun = true
        }
        if (characterAcquisitionActive) {
            return planCharacterAcquisition(state, nowMillis, frameWidth, frameHeight)
        }
        return when (state) {
            LabyrinthEntryPageState.TITLE_WAITING_TAP -> planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.TITLE_CONTINUE,
                "点击标题页继续",
                TITLE_CONTINUE,
                anchorMatches = anchorMatches,
                anchorIds = listOf(EntryAnchorId.TITLE_TAP_PROMPT),
            ).also { decision ->
                if (decision is LabyrinthEntryActionDecision.Execute && preAnnouncementStartedAt == Long.MIN_VALUE) {
                    preAnnouncementStartedAt = nowMillis
                }
            }

            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING ->
                LabyrinthEntryActionDecision.Wait("正在进行数据连接，等待加载完成")

            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            -> planPreAnnouncementClick(nowMillis, frameWidth, frameHeight, allowStartingPhase = true)

            LabyrinthEntryPageState.UNKNOWN ->
                planPreAnnouncementClick(nowMillis, frameWidth, frameHeight, allowStartingPhase = false)

            LabyrinthEntryPageState.HOME_ANNOUNCEMENT -> {
                preAnnouncementStartedAt = Long.MIN_VALUE
                planPageAction(
                    nowMillis,
                    frameWidth,
                    frameHeight,
                    LabyrinthEntryActionKind.CLOSE_ANNOUNCEMENT,
                    "关闭主页公告",
                    ANNOUNCEMENT_CLOSE,
                    anchorMatches = anchorMatches,
                    anchorIds = listOf(EntryAnchorId.ANNOUNCEMENT_CLOSE),
                )
            }

            LabyrinthEntryPageState.HOME -> planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.OPEN_ADVENTURE,
                "打开冒险",
                HOME_ADVENTURE,
                anchorMatches = anchorMatches,
                anchorIds = listOf(EntryAnchorId.HOME_ADVENTURE),
            )

            LabyrinthEntryPageState.ADVENTURE -> planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.OPEN_DAWN_REALM,
                "打开黎明界迷宫",
                ADVENTURE_DAWN_REALM,
                anchorMatches = anchorMatches,
                anchorIds = listOf(EntryAnchorId.LABYRINTH_ENTRY),
            )

            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE -> planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.START_DAWN_REALM,
                "点击出发",
                DAWN_REALM_START,
                config.dawnRealmActionIntervalMillis,
                anchorMatches = anchorMatches,
                anchorIds = listOf(EntryAnchorId.DAWN_START, EntryAnchorId.DAWN_START_STANDARD),
            )

            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE -> planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.RESUME_DAWN_REALM,
                "继续当前黎明界挑战",
                DAWN_REALM_START,
                config.dawnRealmActionIntervalMillis,
                anchorMatches = anchorMatches,
                anchorIds = listOf(EntryAnchorId.DAWN_START, EntryAnchorId.DAWN_START_STANDARD),
            )

            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> planInitialCharacterSelection(
                anchorScores = anchorScores,
                nowMillis = nowMillis,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                openingCharacterMatches = openingCharacterMatches,
                openingCharacterSelection = openingCharacterSelection,
            )

            LabyrinthEntryPageState.CHARACTER_JOINED -> {
                if (!config.manualCharacterSelection) {
                    LabyrinthEntryActionDecision.Stop("未经过初始邀请流程却进入角色加入页面")
                } else {
                    // 用户可能在识别到“已选满”前手动按下邀请；从加入结果页安全接管。
                    openingRosterStarted = true
                    characterAcquisitionStartedAt = nowMillis
                    characterAcquisitionClicks = 0
                    characterAcquisitionActive = true
                    joinedPageDetected = false
                    joinedCloseRequested = false
                    planCharacterAcquisition(state, nowMillis, frameWidth, frameHeight)
                }
            }

            LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别连结印记三选一，交由路线执行阶段处理",
            )

            LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别迷宫遗物三选一，交由人工选择",
            )

            LabyrinthEntryPageState.EVENT_CHOICE,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别事件页面，交由路线执行阶段处理",
            )

            LabyrinthEntryPageState.ITEM_REWARD -> planItemRewardClose(
                nowMillis = nowMillis,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                anchorMatches = anchorMatches,
            )

            LabyrinthEntryPageState.SHOP,
            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
            -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别黎明界商店界面，保持只读并等待人工购买或退出",
            )

            LabyrinthEntryPageState.NODE_MAP_VIEW ->
                LabyrinthEntryActionDecision.Wait("当前为查看地图浏览态，等待返回可操作页面")

            LabyrinthEntryPageState.NODE_SELECTION ->
                if (resumedExistingRun || openingRosterCompleted) {
                    LabyrinthEntryActionDecision.Complete(
                        state,
                        if (config.manualCharacterSelection) {
                            "已完成人工选人开局并进入节点选择"
                        } else {
                            "已完成随机三角色开局并进入节点选择"
                        },
                    )
                } else {
                    LabyrinthEntryActionDecision.Wait(
                        "等待初始选人流程完成；稳定节点页 ${stableFrameCount}/${config.midRunNodeSelectionStableFrames} 帧后可中途接管",
                    )
                }

            LabyrinthEntryPageState.BATTLE_CHALLENGE -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别普通战斗挑战页，等待战斗流程接入",
            )

            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别战斗前队伍编组页，等待战斗选人流程接入",
            )

            LabyrinthEntryPageState.BATTLE_IN_PROGRESS -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别战斗中画面，交由路线执行阶段等待战斗结束",
            )

            LabyrinthEntryPageState.BATTLE_FAILED -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别战斗失败页，交由路线执行阶段处理有限重试",
            )

            LabyrinthEntryPageState.BATTLE_RESULT -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别战斗结算页，交由路线执行阶段处理",
            )

            LabyrinthEntryPageState.RUN_CLEAR_RESULT -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别通关结算页",
            )

            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            -> LabyrinthEntryActionDecision.Complete(
                state,
                "已识别最终结算页面，交由路线执行阶段继续处理",
            )
        }
    }

    private fun planInitialCharacterSelection(
        anchorScores: LabyrinthAnchorScores?,
        nowMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        openingCharacterMatches: List<LabyrinthBattleCharacterMatch>,
        openingCharacterSelection: LabyrinthBattleTeamObservation?,
    ): LabyrinthEntryActionDecision {
        if (stableFrameCount < config.stableFrames) {
            return LabyrinthEntryActionDecision.Wait("等待角色选择页面稳定")
        }
        val configuredPolicy = openingRosterPolicy
        if (!config.manualCharacterSelection && configuredPolicy == null && config.requireConfiguredOpeningRoster) {
            return LabyrinthEntryActionDecision.Wait("当前开局没有可用的公会初始三人配置，禁止随机选人")
        }
        val recognizedOpening = openingCharacterMatches.filter { !it.characterId.isNullOrBlank() }
        if (configuredPolicy != null) {
            selectedOpeningCharacterIds += recognizedOpening
                .filter(LabyrinthBattleCharacterMatch::selected)
                .mapNotNull(LabyrinthBattleCharacterMatch::characterId)

            pendingOpeningCharacterId?.let { pendingId ->
                if (pendingId in selectedOpeningCharacterIds) {
                    pendingOpeningCharacterId = null
                    pendingOpeningCharacterName = null
                    pendingOpeningCharacterClickedAt = Long.MIN_VALUE
        pendingOpeningStableFramesSeen = 0
                    pendingOpeningStableFramesSeen = 0
                } else {
                    // Feedback can only be observed on a STABLE roster frame: while the recognizer
                    // reports WAITING_FOR_STABILITY it returns no cards at all, so "not selected"
                    // there is absence of evidence, not evidence of absence. The tap itself
                    // perturbs the viewport signature (dimmed card, new badge), which on a slow
                    // host can hold the recognizer in WAITING for longer than the wall-clock
                    // timeout. Count stable frames actually inspected, and only give up after a
                    // few of them agree that the card is still unselected.
                    // A caller that supplies no viewport observation has no stability signal;
                    // its card list is the only evidence, so every such frame counts as inspected.
                    if (openingCharacterSelection == null ||
                        openingCharacterSelection.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE
                    ) {
                        pendingOpeningStableFramesSeen++
                    }
                    val elapsed = nowMillis - pendingOpeningCharacterClickedAt
                    val timedOut = elapsed >= config.openingSelectionFeedbackTimeoutMillis
                    val stableAgreement = pendingOpeningStableFramesSeen >= config.openingSelectionFeedbackStableFrames
                    val hardTimedOut = elapsed >= config.openingSelectionFeedbackHardTimeoutMillis
                    if (!(timedOut && stableAgreement) && !hardTimedOut) {
                        return LabyrinthEntryActionDecision.Wait(
                            "已点击初始角色${pendingOpeningCharacterName ?: pendingId}，等待选中状态确认" +
                                "（稳定帧${pendingOpeningStableFramesSeen}/${config.openingSelectionFeedbackStableFrames}）",
                        )
                    }
                    return LabyrinthEntryActionDecision.Stop(
                        "点击初始角色${pendingOpeningCharacterName ?: pendingId}后未观察到选中反馈；" +
                            "已停止自动点击，避免重复点击错误位置",
                    )
                }
            }
        }
        if (selectionReady(anchorScores)) {
            if (!config.manualCharacterSelection && configuredPolicy != null) {
                if (!configuredPolicy.isSatisfied(selectedOpeningCharacterIds)) {
                    return LabyrinthEntryActionDecision.Wait(
                        "画面虽为3/3，但累计选中角色未满足当前公会配置；" +
                            configuredPolicy.missingReason(selectedOpeningCharacterIds),
                    )
                }
            }
            val action = mapTap(INITIAL_INVITE, frameWidth, frameHeight)
                ?: return LabyrinthEntryActionDecision.Stop("邀请按钮坐标无法映射")
            if (!intervalElapsed(nowMillis, config.characterSelectionClickIntervalMillis)) {
                return LabyrinthEntryActionDecision.Wait("等待邀请动作间隔")
            }
            lastActionAt = nowMillis
            characterAcquisitionStartedAt = nowMillis
            characterAcquisitionClicks = 0
            characterAcquisitionActive = true
            joinedPageDetected = false
            joinedCloseRequested = false
            return LabyrinthEntryActionDecision.Execute(
                LabyrinthEntryActionKind.INVITE_INITIAL_CHARACTERS,
                if (config.manualCharacterSelection) {
                    "确认邀请人工选择的三个初始角色"
                } else {
                    "邀请三个初始角色"
                },
                action,
            )
        }
        if (config.manualCharacterSelection) {
            return LabyrinthEntryActionDecision.Wait("等待人工选择三名初始角色；选满后程序自动确认")
        }
        if (configuredPolicy != null) {
            if (configuredPolicy.isSatisfied(selectedOpeningCharacterIds)) {
                openingRosterBottomMissFrames = 0
                return LabyrinthEntryActionDecision.Wait("已选择配置中的三个初始角色，等待 3/3 与邀请按钮就绪")
            }
            if (openingCharacterSelection?.recognitionState == LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY) {
                return LabyrinthEntryActionDecision.Wait("初始角色列表正在滚动，等待视口稳定")
            }
            if (
                openingCharacterSelection?.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE &&
                recognizedOpening.isEmpty()
            ) {
                // At either end elastic scrolling can expose a temporary blank band before the
                // list rebounds to the first/last real row. A cardless frame is not a new page or
                // bottom-boundary evidence, so never chain another blind scroll from it.
                openingRosterBottomMissFrames = 0
                return LabyrinthEntryActionDecision.Wait("初始角色列表越界回弹中，等待角色卡片重新出现")
            }

            val availableIds = recognizedOpening.mapNotNull(LabyrinthBattleCharacterMatch::characterId).toSet()
            val target = configuredPolicy.nextVisibleCandidate(
                availableCharacterIds = availableIds,
                selectedCharacterIds = selectedOpeningCharacterIds,
            )
            if (target != null) {
                openingRosterBottomMissFrames = 0
                if (!intervalElapsed(nowMillis, config.characterSelectionClickIntervalMillis)) {
                    return LabyrinthEntryActionDecision.Wait("等待角色选择点击间隔")
                }
                val match = recognizedOpening.firstOrNull { it.characterId == target.characterId }
                    ?: return LabyrinthEntryActionDecision.Wait("目标角色${target.displayName}暂未稳定识别")
                lastActionAt = nowMillis
                pendingOpeningCharacterId = target.characterId
                pendingOpeningCharacterName = target.displayName
                pendingOpeningCharacterClickedAt = nowMillis
                pendingOpeningStableFramesSeen = 0
                return LabyrinthEntryActionDecision.Execute(
                    LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER,
                    "选择初始角色${(selectedOpeningCharacterIds.size + 1).coerceAtMost(REQUIRED_INITIAL_CHARACTERS)}/" +
                        "$REQUIRED_INITIAL_CHARACTERS：${target.displayName}",
                    AutomationAction.Tap(
                        ScreenPoint(
                            // Use the portrait body, away from the role/attribute badges along the
                            // lower edge. The old 16%/86% point landed on the purple role icon and
                            // produced no selection feedback on the current opening-roster UI.
                            x = match.screenRect.left + match.screenRect.width * 0.50f,
                            y = match.screenRect.top + match.screenRect.height * 0.45f,
                        ),
                    ),
                )
            }

            val viewport = openingCharacterSelection
                ?: return LabyrinthEntryActionDecision.Wait("初始角色视口尚未生成，等待滚动条与卡片布局识别")
            val scrollbar = viewport.scrollbar
            val atBottom = !scrollbar.canScroll || scrollbar.position >= OPENING_SCROLL_BOTTOM_POSITION
            if (atBottom) {
                openingRosterBottomMissFrames++
                return if (openingRosterBottomMissFrames >= config.openingRosterBottomStableFrames) {
                    LabyrinthEntryActionDecision.Stop(configuredPolicy.missingReason(selectedOpeningCharacterIds))
                } else {
                    LabyrinthEntryActionDecision.Wait(
                        "已到初始角色列表底部，复核剩余目标 " +
                            "$openingRosterBottomMissFrames/${config.openingRosterBottomStableFrames}",
                    )
                }
            }

            openingRosterBottomMissFrames = 0
            if (openingRosterScrollAttempts >= config.maxOpeningRosterScrolls) {
                return LabyrinthEntryActionDecision.Stop("初始角色列表滚动达到上限，仍未找到配置中的三名角色")
            }
            if (!intervalElapsed(nowMillis, config.openingRosterScrollIntervalMillis)) {
                return LabyrinthEntryActionDecision.Wait("当前视口没有剩余目标，等待初始角色列表滚动间隔")
            }
            val scroll = openingRosterScroll(frameWidth, frameHeight)
                ?: return LabyrinthEntryActionDecision.Stop("初始角色列表滚动坐标无法映射")
            openingRosterScrollAttempts++
            lastActionAt = nowMillis
            return LabyrinthEntryActionDecision.Execute(
                LabyrinthEntryActionKind.SCROLL_INITIAL_CHARACTERS,
                "当前视口无剩余目标，向下搜索初始角色 ${openingRosterScrollAttempts}/${config.maxOpeningRosterScrolls}",
                scroll,
            )
        }
        if (selectedCharacterClicks >= REQUIRED_INITIAL_CHARACTERS) {
            return LabyrinthEntryActionDecision.Wait("已点击三个角色，等待 3/3 与邀请按钮就绪")
        }
        if (!intervalElapsed(nowMillis, config.characterSelectionClickIntervalMillis)) {
            return LabyrinthEntryActionDecision.Wait("等待角色选择点击间隔")
        }
        val targetIndex = selectedCharacterClicks
        val action = mapTap(characterSelectionTargets[targetIndex], frameWidth, frameHeight)
            ?: return LabyrinthEntryActionDecision.Stop("角色卡片坐标无法映射")
        selectedCharacterClicks++
        lastActionAt = nowMillis
        return LabyrinthEntryActionDecision.Execute(
            LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER,
            "随机选择初始角色 " + (targetIndex + 1) + "/$REQUIRED_INITIAL_CHARACTERS",
            action,
        )
    }

    private fun selectionReady(anchorScores: LabyrinthAnchorScores?): Boolean {
        if (anchorScores == null) return false
        val complete = maxOf(
            anchorScores[EntryAnchorId.SELECTION_COUNT_COMPLETE],
            anchorScores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD],
        )
        val none = maxOf(
            anchorScores[EntryAnchorId.SELECTION_COUNT_NONE],
            anchorScores[EntryAnchorId.SELECTION_COUNT_NONE_STANDARD],
        )
        val enabled = maxOf(
            anchorScores[EntryAnchorId.INVITE_ENABLED],
            anchorScores[EntryAnchorId.INVITE_ENABLED_STANDARD],
        )
        val disabled = maxOf(
            anchorScores[EntryAnchorId.INVITE_DISABLED],
            anchorScores[EntryAnchorId.INVITE_DISABLED_STANDARD],
        )
        return complete >= config.selectionReadyMinScore &&
            enabled >= config.selectionReadyMinScore &&
            complete > none &&
            enabled > disabled
    }

    private fun planCharacterAcquisition(
        state: LabyrinthEntryPageState,
        nowMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): LabyrinthEntryActionDecision {
        if (nowMillis - characterAcquisitionStartedAt > config.characterAcquisitionTimeoutMillis) {
            return LabyrinthEntryActionDecision.Stop("角色获得流程超时")
        }
        if (state == LabyrinthEntryPageState.NODE_SELECTION && joinedCloseRequested) {
            openingRosterCompleted = true
            return LabyrinthEntryActionDecision.Complete(
                state,
                if (config.manualCharacterSelection) {
                    "已完成人工选人开局并进入节点选择"
                } else {
                    "已完成随机三角色开局并进入节点选择"
                },
            )
        }
        if (state == LabyrinthEntryPageState.NODE_SELECTION) {
            return LabyrinthEntryActionDecision.Wait("角色获得过程中忽略瞬时节点页面")
        }
        if (state == LabyrinthEntryPageState.CHARACTER_JOINED) {
            joinedPageDetected = true
            return planPageAction(
                nowMillis,
                frameWidth,
                frameHeight,
                LabyrinthEntryActionKind.CLOSE_CHARACTER_JOINED,
                "关闭角色加入结果",
                CHARACTER_JOINED_CLOSE,
            ).also { decision ->
                if (decision is LabyrinthEntryActionDecision.Execute) joinedCloseRequested = true
            }
        }
        if (state == LabyrinthEntryPageState.ITEM_REWARD) {
            return planItemRewardClose(
                nowMillis = nowMillis,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            )
        }
        if (joinedPageDetected) {
            return LabyrinthEntryActionDecision.Wait("等待关闭后的节点选择页面")
        }
        if (state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            return LabyrinthEntryActionDecision.Wait("等待进入角色获得动画")
        }
        if (state != LabyrinthEntryPageState.UNKNOWN) {
            return LabyrinthEntryActionDecision.Wait("角色获得动画期间忽略非目标页面")
        }
        if (characterAcquisitionClicks >= config.maxCharacterAcquisitionClicks) {
            return LabyrinthEntryActionDecision.Stop("角色获得动画点击达到次数上限")
        }
        if (!intervalElapsed(nowMillis, config.characterAcquisitionClickIntervalMillis)) {
            return LabyrinthEntryActionDecision.Wait("等待角色获得动画点击间隔")
        }
        val action = mapTap(CHARACTER_ACQUISITION_CONTINUE, frameWidth, frameHeight)
            ?: return LabyrinthEntryActionDecision.Stop("角色获得动画点击坐标无法映射")
        characterAcquisitionClicks++
        lastActionAt = nowMillis
        return LabyrinthEntryActionDecision.Execute(
            LabyrinthEntryActionKind.ADVANCE_CHARACTER_ACQUISITION,
            "推进角色获得动画 " + characterAcquisitionClicks + "/" + config.maxCharacterAcquisitionClicks,
            action,
        )
    }

    private fun planItemRewardClose(
        nowMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        anchorMatches: Map<String, EntryAnchorMatch> = emptyMap(),
    ): LabyrinthEntryActionDecision = planPageAction(
        nowMillis = nowMillis,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        kind = LabyrinthEntryActionKind.CLOSE_ITEM_REWARD,
        label = "关闭获得道具界面",
        referencePoint = ITEM_REWARD_CLOSE,
        anchorMatches = anchorMatches,
        anchorIds = listOf(EntryAnchorId.ITEM_REWARD_CLOSE),
    )

    private fun observe(state: LabyrinthEntryPageState) {
        if (lastState == state) {
            stableFrameCount++
        } else {
            lastState = state
            stableFrameCount = 1
            attemptsForState = 0
        }
    }

    private fun planPageAction(
        nowMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        kind: LabyrinthEntryActionKind,
        label: String,
        referencePoint: ScreenPoint,
        actionIntervalMillis: Long = config.pageActionIntervalMillis,
        anchorMatches: Map<String, EntryAnchorMatch> = emptyMap(),
        anchorIds: List<String> = emptyList(),
    ): LabyrinthEntryActionDecision {
        if (stableFrameCount < config.stableFrames) {
            return LabyrinthEntryActionDecision.Wait("等待页面稳定")
        }
        if (!intervalElapsed(nowMillis, actionIntervalMillis)) {
            return LabyrinthEntryActionDecision.Wait("等待动作间隔")
        }
        if (attemptsForState >= config.maxPageActionAttempts) {
            return LabyrinthEntryActionDecision.Stop("${lastState?.name} 动作连续失败")
        }
        val action = mapAnchorTap(anchorMatches, anchorIds)
            ?: mapTap(referencePoint, frameWidth, frameHeight)
            ?: return LabyrinthEntryActionDecision.Stop("动作坐标无法映射到当前画面")
        attemptsForState++
        lastActionAt = nowMillis
        return LabyrinthEntryActionDecision.Execute(kind, label, action)
    }

    private fun mapAnchorTap(
        anchorMatches: Map<String, EntryAnchorMatch>,
        anchorIds: List<String>,
    ): AutomationAction.Tap? = anchorIds
        .asSequence()
        .mapNotNull(anchorMatches::get)
        .filter { it.score >= ACTION_ANCHOR_MIN_SCORE }
        .maxByOrNull(EntryAnchorMatch::score)
        ?.rect
        ?.let { rect ->
            AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
        }

    private fun planPreAnnouncementClick(
        nowMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        allowStartingPhase: Boolean,
    ): LabyrinthEntryActionDecision {
        if (preAnnouncementStartedAt == Long.MIN_VALUE) {
            if (!allowStartingPhase) return LabyrinthEntryActionDecision.Wait("未知页面禁止启动点击")
            preAnnouncementStartedAt = nowMillis
        }
        if (nowMillis - preAnnouncementStartedAt > config.preAnnouncementTimeoutMillis) {
            return LabyrinthEntryActionDecision.Stop("公告前受限点击超时")
        }
        if (preAnnouncementClicks >= config.maxPreAnnouncementClicks) {
            return LabyrinthEntryActionDecision.Stop("公告前点击达到次数上限")
        }
        if (!intervalElapsed(nowMillis, config.preAnnouncementClickIntervalMillis)) {
            return LabyrinthEntryActionDecision.Wait("等待公告前点击间隔")
        }
        val action = mapTap(PRE_ANNOUNCEMENT_CONTINUE, frameWidth, frameHeight)
            ?: return LabyrinthEntryActionDecision.Stop("公告前点击坐标无法映射")
        preAnnouncementClicks++
        lastActionAt = nowMillis
        return LabyrinthEntryActionDecision.Execute(
            LabyrinthEntryActionKind.PRE_ANNOUNCEMENT_CONTINUE,
            "推进公告前画面",
            action,
        )
    }

    private fun intervalElapsed(nowMillis: Long, intervalMillis: Long): Boolean =
        lastActionAt == Long.MIN_VALUE || nowMillis - lastActionAt >= intervalMillis

    private fun mapTap(referencePoint: ScreenPoint, frameWidth: Int, frameHeight: Int): AutomationAction.Tap? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val mapping = coordinateMapper.createMapping(
            PixelRect(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat()),
        )
        return mapping.toScreen(referencePoint)?.let(AutomationAction::Tap)
    }

    private fun openingRosterScroll(frameWidth: Int, frameHeight: Int): AutomationAction.Swipe? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val mapping = coordinateMapper.createMapping(
            PixelRect(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat()),
        )
        val start = mapping.toScreen(OPENING_SCROLL_START) ?: return null
        val end = mapping.toScreen(OPENING_SCROLL_END) ?: return null
        return AutomationAction.Swipe(
            start = start,
            end = end,
            durationMillis = OPENING_SCROLL_DURATION_MILLIS,
        )
    }

    private companion object {
        val TITLE_CONTINUE = ScreenPoint(950f, 950f)
        val PRE_ANNOUNCEMENT_CONTINUE = ScreenPoint(950f, 930f)
        val ANNOUNCEMENT_CLOSE = ScreenPoint(955f, 960f)
        val HOME_ADVENTURE = ScreenPoint(1070f, 1030f)
        val ADVENTURE_DAWN_REALM = ScreenPoint(1735f, 805f)
        val DAWN_REALM_START = ScreenPoint(1175f, 610f)
        val INITIAL_INVITE = ScreenPoint(1630f, 950f)
        val OPENING_SCROLL_START = ScreenPoint(1580f, 760f)
        val OPENING_SCROLL_END = ScreenPoint(1580f, 420f)
        val CHARACTER_ACQUISITION_CONTINUE = ScreenPoint(960f, 780f)
        val CHARACTER_JOINED_CLOSE = ScreenPoint(960f, 870f)
        val ITEM_REWARD_CLOSE = ScreenPoint(960f, 965f)
        const val ACTION_ANCHOR_MIN_SCORE = 0.45
        const val REQUIRED_INITIAL_CHARACTERS = 3
        const val OPENING_SCROLL_DURATION_MILLIS = 360L
        const val OPENING_SCROLL_BOTTOM_POSITION = 0.95
        val DEFAULT_CHARACTER_CANDIDATES = listOf(
            ScreenPoint(220f, 430f),
            ScreenPoint(430f, 430f),
            ScreenPoint(640f, 430f),
            ScreenPoint(850f, 430f),
            ScreenPoint(1065f, 430f),
            ScreenPoint(1275f, 430f),
        )
    }
}
