package com.landosol.toolbox.labyrinth.vision

enum class LabyrinthEntryPageState {
    TITLE_WAITING_TAP,
    GAME_LOADING_PROGRESS,
    PRE_HOME_DATA_LOADING,
    HOME_ANNOUNCEMENT,
    HOME,
    ADVENTURE,
    DAWN_REALM_HOME_IDLE,
    DAWN_REALM_HOME_ACTIVE,
    INITIAL_CHARACTER_SELECTION,
    CHARACTER_JOINED,
    LINK_CHOICE,
    RELIC_CHOICE,
    EVENT_CHOICE,
    EVENT_ANIMATION,
    ITEM_REWARD,
    SHOP,
    SHOP_PURCHASE_CONFIRMATION,
    SHOP_PURCHASE_COMPLETE,
    SHOP_EXIT_CONFIRMATION,
    NODE_SELECTION,
    NODE_MAP_VIEW,
    BATTLE_CHALLENGE,
    BATTLE_TEAM_SELECTION,
    BATTLE_IN_PROGRESS,
    BATTLE_FAILED,
    BATTLE_RESULT,
    RUN_CLEAR_RESULT,
    RUN_CLEAR_CONGRATULATIONS,
    RUN_CLEAR_CHARACTER_SUMMARY,
    RUN_CLEAR_REWARD_ANIMATION,
    RUN_CLEAR_CHEST_ANIMATION,
    RUN_CLEAR_CHEST_RESULT,
    UNKNOWN,
}

data class LabyrinthEntryPageObservation(
    val state: LabyrinthEntryPageState,
    val confidence: Double,
    val stateScores: Map<LabyrinthEntryPageState, Double>,
    val anchorScores: LabyrinthAnchorScores,
    val reason: String? = null,
)

/**
 * Classifies measured entry-flow anchors. UNKNOWN is a hard action gate: weak and ambiguous
 * observations never produce a clickable page state.
 */
class LabyrinthEntryPageClassifier(
    private val minScore: Double = 0.45,
    private val minMargin: Double = 0.08,
) {
    init {
        require(minScore in 0.0..1.0)
        require(minMargin in 0.0..1.0)
    }

    fun classify(anchorScores: LabyrinthAnchorScores): LabyrinthEntryPageObservation {
        // The title remains visible during login; the bottom-right spinner is not always shown.
        // Require both the title and a strong connection label to suppress title-page retries.
        val titleConnectingScore = minimum(
            anchorScores[EntryAnchorId.TITLE_LOGO],
            anchorScores[EntryAnchorId.DATA_CONNECTING],
        )
        val titleIsConnecting = titleConnectingScore >= maxOf(minScore, 0.60)
        val shopDialogScore = maxOf(
            anchorScores[EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE],
            anchorScores[EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE],
            anchorScores[EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE],
        )
        val battleResultNextScore = anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]
        val finalCharacterSummaryTitleScore = anchorScores[EntryAnchorId.RUN_CLEAR_CHARACTER_SUMMARY_TITLE]
        val eventChoiceScore = maxOf(
            minimum(
                anchorScores[EntryAnchorId.EVENT_CHOICE_TITLE],
                anchorScores[EntryAnchorId.EVENT_CHOICE_INSTRUCTION],
                anchorScores[EntryAnchorId.EVENT_CHOICE_OPTION],
            ),
            minimum(
                anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE],
                anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON],
            ),
        )
        val battleChallengeButtonScore = anchorScores[EntryAnchorId.BATTLE_CHALLENGE_BUTTON]
        val battleChallengeScore = if (battleChallengeButtonScore >= BATTLE_CHALLENGE_STRONG_BUTTON_SCORE) {
            // EX/Boss challenge pages reuse the bottom-right action button but can have a
            // different title banner. The button alone is safe at a stricter threshold.
            battleChallengeButtonScore
        } else {
            minimum(
                anchorScores[EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL],
                battleChallengeButtonScore,
            )
        }
        val battleTeamStartScore = anchorScores[EntryAnchorId.BATTLE_TEAM_START_BUTTON]
        val battleTeamScore = if (battleTeamStartScore >= BATTLE_TEAM_STRONG_START_BUTTON_SCORE) {
            // The Boss editor can use a different heading while retaining the stable start
            // button. Treat that button as sufficient only at a strict threshold.
            battleTeamStartScore
        } else {
            minimum(
                anchorScores[EntryAnchorId.BATTLE_TEAM_HEADER],
                battleTeamStartScore,
            )
        }
        val battleResultScore = if (finalCharacterSummaryTitleScore >= FINAL_PAGE_TITLE_SCORE) {
            // The final character summary reuses the same bottom-right next button as a battle
            // result, so its dedicated title must win the ranking.
            0.0
        } else if (battleResultNextScore >= BATTLE_RESULT_STRONG_NEXT_SCORE) {
            // The current WIN page exposes no reward banner. This large fixed-position button is
            // specific enough to trust by itself only at a deliberately stricter threshold.
            battleResultNextScore
        } else {
            minimum(
                anchorScores[EntryAnchorId.BATTLE_RESULT_REWARD_BANNER],
                battleResultNextScore,
            )
        }
        val nodeHeaderScore = maxOf(
            anchorScores[EntryAnchorId.NODE_HEADER],
            anchorScores[EntryAnchorId.NODE_HEADER_STANDARD],
        )
        val nodeLeftControlScore = maxOf(
            anchorScores[EntryAnchorId.NODE_CHARACTERS],
            anchorScores[EntryAnchorId.NODE_CHARACTERS_STANDARD],
            anchorScores[EntryAnchorId.NODE_RELICS],
            anchorScores[EntryAnchorId.NODE_RELICS_STANDARD],
        )
        val nodeRightControlScore = maxOf(
            anchorScores[EntryAnchorId.NODE_RETREAT],
            anchorScores[EntryAnchorId.NODE_RETREAT_STANDARD],
            anchorScores[EntryAnchorId.NODE_RETURN],
            anchorScores[EntryAnchorId.NODE_RETURN_STANDARD],
        )
        val nodeSelectionScore = minimum(
            nodeHeaderScore,
            nodeLeftControlScore,
            nodeRightControlScore,
        )
        val nodeMapViewScore = minimum(
            nodeLeftControlScore,
            nodeRightControlScore,
            (1.0 - nodeHeaderScore).coerceIn(0.0, 1.0),
        )
        val stateScores = linkedMapOf(
            LabyrinthEntryPageState.TITLE_WAITING_TAP to if (titleIsConnecting) 0.0 else minimum(
                anchorScores[EntryAnchorId.TITLE_LOGO],
                anchorScores[EntryAnchorId.TITLE_TAP_PROMPT],
            ),
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS to minimum(
                maxOf(
                    anchorScores[EntryAnchorId.TITLE_LOGO],
                    anchorScores[EntryAnchorId.LOADING_NOTICE],
                    anchorScores[EntryAnchorId.LOADING_NOTICE_STANDARD],
                ),
                anchorScores[EntryAnchorId.LOADING_PROGRESS],
            ),
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING to maxOf(
                if (titleIsConnecting) titleConnectingScore else 0.0,
                minimum(
                    anchorScores[EntryAnchorId.DATA_CONNECTING],
                    anchorScores[EntryAnchorId.LOADING_INDICATOR],
                ),
            ),
            LabyrinthEntryPageState.HOME_ANNOUNCEMENT to minimum(
                anchorScores[EntryAnchorId.ANNOUNCEMENT_HEADER],
                anchorScores[EntryAnchorId.ANNOUNCEMENT_CLOSE],
            ),
            LabyrinthEntryPageState.HOME to minimum(
                anchorScores[EntryAnchorId.HOME_LEVEL],
                anchorScores[EntryAnchorId.HOME_ADVENTURE],
            ),
            LabyrinthEntryPageState.ADVENTURE to minimum(
                maxOf(
                    anchorScores[EntryAnchorId.ADVENTURE_HEADER],
                    anchorScores[EntryAnchorId.ADVENTURE_HEADER_STANDARD],
                ),
                anchorScores[EntryAnchorId.LABYRINTH_ENTRY],
            ),
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE to dawnHomeIdleScore(anchorScores),
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE to dawnHomeActiveScore(anchorScores),
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION to maxOf(
                minimum(
                    maxOf(
                        anchorScores[EntryAnchorId.SELECTION_HEADER],
                        anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD],
                    ),
                    maxOf(
                        anchorScores[EntryAnchorId.INVITE_DISABLED],
                        anchorScores[EntryAnchorId.INVITE_DISABLED_STANDARD],
                        anchorScores[EntryAnchorId.INVITE_ENABLED],
                        anchorScores[EntryAnchorId.INVITE_ENABLED_STANDARD],
                    ),
                ),
                minimum(
                    anchorScores[EntryAnchorId.ROLE_REWARD_TITLE],
                    anchorScores[EntryAnchorId.ROLE_REWARD_INSTRUCTION],
                ),
            ),
            LabyrinthEntryPageState.CHARACTER_JOINED to minimum(
                maxOf(
                    anchorScores[EntryAnchorId.JOINED_HEADER],
                    anchorScores[EntryAnchorId.JOINED_HEADER_STANDARD],
                ),
                maxOf(
                    anchorScores[EntryAnchorId.JOINED_CLOSE],
                    anchorScores[EntryAnchorId.JOINED_CLOSE_STANDARD],
                ),
            ),
            LabyrinthEntryPageState.LINK_CHOICE to minimum(
                anchorScores[EntryAnchorId.LINK_CHOICE_TITLE],
                anchorScores[EntryAnchorId.LINK_CHOICE_INSTRUCTION],
                anchorScores[EntryAnchorId.LINK_CHOICE_REMAINING],
            ),
            LabyrinthEntryPageState.RELIC_CHOICE to minimum(
                anchorScores[EntryAnchorId.LINK_CHOICE_TITLE],
                anchorScores[EntryAnchorId.RELIC_CHOICE_INSTRUCTION],
                anchorScores[EntryAnchorId.LINK_CHOICE_REMAINING],
            ),
            LabyrinthEntryPageState.EVENT_CHOICE to eventChoiceScore,
            LabyrinthEntryPageState.EVENT_ANIMATION to minimum(
                anchorScores[EntryAnchorId.EVENT_ANIMATION_TITLE],
                anchorScores[EntryAnchorId.EVENT_ANIMATION_SKIP],
            ),
            // 获得道具, 迷宫遗物效果 (迷宫大师 opening relic, 2026-09-17) and 遗物效果结果 (a relic
            // turned a defeat into a victory, 2026-09-22) are all closed by the same 关闭 button;
            // any one title pair identifies the page. The third is a short centred dialog, so it
            // carries its own close anchor instead of sharing the full-height one.
            LabyrinthEntryPageState.ITEM_REWARD to maxOf(
                minimum(
                    anchorScores[EntryAnchorId.ITEM_REWARD_TITLE],
                    anchorScores[EntryAnchorId.ITEM_REWARD_INSTRUCTION],
                    anchorScores[EntryAnchorId.ITEM_REWARD_CLOSE],
                ),
                minimum(
                    anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE],
                    anchorScores[EntryAnchorId.RELIC_EFFECT_INSTRUCTION],
                    anchorScores[EntryAnchorId.ITEM_REWARD_CLOSE],
                ),
                minimum(
                    anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE],
                    anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE],
                ),
            ),
            LabyrinthEntryPageState.SHOP to minimum(
                anchorScores[EntryAnchorId.SHOP_TITLE],
                anchorScores[EntryAnchorId.SHOP_INSTRUCTION],
                anchorScores[EntryAnchorId.SHOP_CLOSE],
                (1.0 - shopDialogScore).coerceIn(0.0, 1.0),
            ),
            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION to minimum(
                anchorScores[EntryAnchorId.SHOP_TITLE],
                anchorScores[EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE],
            ),
            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE to minimum(
                anchorScores[EntryAnchorId.SHOP_TITLE],
                anchorScores[EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE],
            ),
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION to minimum(
                anchorScores[EntryAnchorId.SHOP_TITLE],
                anchorScores[EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE],
            ),
            LabyrinthEntryPageState.NODE_SELECTION to nodeSelectionScore,
            LabyrinthEntryPageState.NODE_MAP_VIEW to nodeMapViewScore,
            LabyrinthEntryPageState.BATTLE_CHALLENGE to minimum(
                battleChallengeScore,
                anchorScores[EntryAnchorId.BATTLE_CHALLENGE_BUTTON],
            ),
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION to minimum(
                battleTeamScore,
                battleTeamStartScore,
            ),
            // "自动" is state-dependent and can disappear during a fight. The fixed
            // top-right menu is the stable battle identity; it is not an action target.
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS to anchorScores[
                EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON
            ],
            LabyrinthEntryPageState.BATTLE_RESULT to battleResultScore,
            LabyrinthEntryPageState.RUN_CLEAR_RESULT to minimum(
                anchorScores[EntryAnchorId.RUN_RESULT_LOGO],
                anchorScores[EntryAnchorId.RUN_RESULT_CLOSE_BUTTON],
            ),
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS to anchorScores[
                EntryAnchorId.RUN_CLEAR_CONGRATULATIONS_TITLE
            ],
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY to minimum(
                anchorScores[EntryAnchorId.RUN_CLEAR_CHARACTER_SUMMARY_TITLE],
                anchorScores[EntryAnchorId.RUN_CLEAR_NEXT_BUTTON],
            ),
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION to anchorScores[
                EntryAnchorId.RUN_CLEAR_REWARD_ANIMATION_TITLE
            ],
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION to anchorScores[
                EntryAnchorId.RUN_CLEAR_CHEST_ANIMATION_TITLE
            ],
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT to minimum(
                anchorScores[EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE],
                anchorScores[EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON],
            ),
        )
        // The link and relic pages share the title and remaining-count anchors. Their
        // instruction lines also differ by only a few glyphs after a resize. A clean
        // crop of the fixed pink link statue is therefore used only as a ranking
        // discriminator. It is deliberately not added to stateScores/confidence, so
        // the displayed confidence remains the score of the textual page anchors.
        val rankingScores = stateScores.mapValues { (state, score) ->
            when (state) {
                LabyrinthEntryPageState.LINK_CHOICE -> {
                    val cardSignature = anchorScores[EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE]
                    if (cardSignature < minScore) 0.0 else score + cardSignature * 0.20
                }
                else -> score
            }
        }
        val ranked = stateScores.entries.sortedByDescending { rankingScores[it.key] ?: 0.0 }
        val best = ranked.first()
        val second = ranked.getOrNull(1)
        val bestRankingScore = rankingScores[best.key] ?: 0.0
        val secondRankingScore = second?.let { rankingScores[it.key] ?: 0.0 } ?: 0.0
        // Events leave the map header and controls visible. A strong trigger banner plus a
        // select button owns that overlay even when the underlying map scores higher.
        val eventOverlayScore = minimum(
            anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE],
            anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON],
        )
        // 获得道具 is a centred modal drawn over the map; the map's header and side controls stay
        // visible and score almost as high as the modal's own anchors (2026-09-17 recording:
        // ITEM_REWARD 0.88 vs NODE_SELECTION 0.82, inside the ambiguity margin, so the final
        // reward popup read as UNKNOWN and the run never closed it). When every modal anchor is
        // strong, the modal owns the frame regardless of what is underneath.
        val itemRewardModalScore = stateScores.getValue(LabyrinthEntryPageState.ITEM_REWARD)
        // Character acquisition also leaves the map visible behind a dimmed modal. In the
        // 2026-09-24 live frame the modal scored .958 and the map .903, so the generic .08
        // ambiguity margin incorrectly disabled its close button and the route timed out.
        // Both the dedicated header and close button must be strong; a lone close button
        // is shared by other dialogs and must never be enough to take ownership.
        val characterJoinedModalScore = stateScores.getValue(LabyrinthEntryPageState.CHARACTER_JOINED)
        // The RESULT banner is a large, full-colour, one-of-a-kind crop: nothing else in the game
        // scores near 1.0 against it. The run-result page it announces is a modal over the map, so
        // the map's header and side controls stay visible underneath and score highly, exactly as
        // for 获得道具 above.
        //
        // The page score cannot express that on its own because it is min(logo, close button), and
        // the button is not always 关闭 — the 加入的角色 summary carries 下一步 in the same pill, so
        // the button anchor reads ~0.60 and drags the whole page down with it.
        //
        // 2026-09-20 bundle 110329: logo 0.995, close button 0.602, so RUN_CLEAR_RESULT scored
        // 0.602 against NODE_MAP_VIEW's 0.529 — a margin of 0.073 against the required 0.080. The
        // frame read as UNKNOWN, actions were disabled, and the run sat on the final screen for
        // the whole recording without pressing 下一步.
        val runResultLogoScore = anchorScores[EntryAnchorId.RUN_RESULT_LOGO]
        val state = when {
            characterJoinedModalScore >= maxOf(minScore, MODAL_OVERLAY_MIN_SCORE) && best.key in setOf(
                LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                LabyrinthEntryPageState.CHARACTER_JOINED,
            ) -> LabyrinthEntryPageState.CHARACTER_JOINED
            eventOverlayScore >= maxOf(minScore, EVENT_OVERLAY_MIN_SCORE) && best.key in setOf(
                LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                LabyrinthEntryPageState.EVENT_CHOICE,
            ) -> LabyrinthEntryPageState.EVENT_CHOICE
            itemRewardModalScore >= maxOf(minScore, MODAL_OVERLAY_MIN_SCORE) && best.key in setOf(
                LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                LabyrinthEntryPageState.ITEM_REWARD,
            ) -> LabyrinthEntryPageState.ITEM_REWARD
            runResultLogoScore >= RUN_RESULT_LOGO_DECISIVE_SCORE && best.key in setOf(
                LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                LabyrinthEntryPageState.RUN_CLEAR_RESULT,
            ) -> LabyrinthEntryPageState.RUN_CLEAR_RESULT
            best.value < minScore -> LabyrinthEntryPageState.UNKNOWN
            bestRankingScore - secondRankingScore < minMargin -> LabyrinthEntryPageState.UNKNOWN
            else -> best.key
        }
        return LabyrinthEntryPageObservation(
            state = state,
            confidence = if (state == LabyrinthEntryPageState.UNKNOWN) 0.0 else stateScores.getValue(state),
            stateScores = stateScores,
            anchorScores = anchorScores,
            reason = if (state == LabyrinthEntryPageState.UNKNOWN) "entry-page-untrusted" else null,
        )
    }

    private fun dawnHomeActiveScore(scores: LabyrinthAnchorScores): Double = minimum(
        dawnHomeBaseScore(scores),
        dawnActiveScore(scores),
    )

    private fun dawnHomeIdleScore(scores: LabyrinthAnchorScores): Double = minimum(
        dawnHomeBaseScore(scores),
        1.0 - dawnActiveScore(scores),
    )

    private fun dawnHomeBaseScore(scores: LabyrinthAnchorScores): Double = minimum(
        maxOf(
            scores[EntryAnchorId.DAWN_START],
            scores[EntryAnchorId.DAWN_START_STANDARD],
        ),
        maxOf(
            scores[EntryAnchorId.DAWN_TICKET_LABEL],
            scores[EntryAnchorId.DAWN_TICKET_LABEL_STANDARD],
        ),
    )

    private fun dawnActiveScore(scores: LabyrinthAnchorScores): Double = maxOf(
        scores[EntryAnchorId.DAWN_ACTIVE],
        scores[EntryAnchorId.DAWN_ACTIVE_STANDARD],
    )

    private fun minimum(vararg values: Double): Double = values.minOrNull() ?: 0.0

    private companion object {
        const val EVENT_OVERLAY_MIN_SCORE = 0.65
        /** All three item-reward anchors must be this strong before the modal may override the map. */
        const val MODAL_OVERLAY_MIN_SCORE = 0.80

        /**
         * How strong the RESULT banner must be to own the frame by itself.
         *
         * Deliberately near the top of the range: this bypasses the ambiguity margin, so it must
         * mean "this exact banner is on screen", not "something reddish is up there". Live frames
         * of the real page score 0.99.
         */
        const val RUN_RESULT_LOGO_DECISIVE_SCORE = 0.92
        const val BATTLE_CHALLENGE_STRONG_BUTTON_SCORE = 0.80
        const val BATTLE_TEAM_STRONG_START_BUTTON_SCORE = 0.80
        const val BATTLE_RESULT_STRONG_NEXT_SCORE = 0.80
        const val FINAL_PAGE_TITLE_SCORE = 0.70
    }
}
