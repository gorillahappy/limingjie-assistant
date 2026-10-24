package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventLayoutTemplateResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.NormalizedOcrRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEventRecognitionTest {
    @Test
    fun `structured trust failure distinguishes margin from low score and missing candidates`() {
        val event = guildEvents()[2]
        val text = event.name + event.choices.joinToString(" ") { it.name + it.description }
        assertEquals(LabyrinthEventTextTrustFailure.NONE, LabyrinthEventTextRecognizer(guildEvents()).recognize(text).trustFailure)
        val tied = LabyrinthEventTextRecognizer(listOf(event, event.copy(id = "same-text"))).recognize(text)
        assertEquals(LabyrinthEventTextTrustFailure.RIVAL_MARGIN, tied.trustFailure)
        assertEquals(0.0, tied.margin, 0.0)
        val low = LabyrinthEventTextRecognizer(guildEvents(), minimumTrustedScore = 1.0)
            .recognize(text.replace("阿尔法金币", "阿尔去金币"))
        assertEquals(LabyrinthEventTextTrustFailure.LOW_SCORE, low.trustFailure)
        assertEquals(LabyrinthEventTextTrustFailure.NO_CANDIDATE,
            LabyrinthEventTextRecognizer(guildEvents()).recognize("").trustFailure)
    }

    @Test
    fun `shared title and generic rewards cannot authorize a guild choice`() {
        val match = LabyrinthEventTextRecognizer(guildEvents()).recognize(
            "这里是公会管理协会！请问要找什么样的伙伴？" +
                "想要型成为伙伴！型职能的随机印记x2阿尔法金币200".repeat(2),
        )
        assertFalse("score=${match.score}, margin=${match.margin}", match.trusted)
    }

    @Test
    fun `conflicting profession pairs remain ambiguous regardless of catalog order`() {
        val events = guildEvents()
        val text = events[1].name + (events[1].choices + events[2].choices)
            .joinToString(" ") { it.name + it.description }
        for (catalog in listOf(events, events.reversed())) {
            val match = LabyrinthEventTextRecognizer(catalog).recognize(text)
            assertFalse(match.trusted)
            assertEquals(0.0, match.margin, 0.00001)
        }
    }

    @Test
    fun `traditional profession forms are canonicalized without guessing different characters`() {
        assertEquals("减扰强疗击滅王", normalizeEventText("減擾強療擊滅王"))
        val events = guildEvents()
        val recognizer = LabyrinthEventTextRecognizer(events)
        for (event in events) {
            val text = (event.name + event.choices.joinToString(" ") { it.name + it.description })
                .replace('减', '減').replace('扰', '擾').replace('强', '強')
                .replace('疗', '療').replace('击', '擊')
            val match = recognizer.recognize(text)
            assertEquals(event.id, match.event?.id)
            assertTrue("event=${event.id}, score=${match.score}, margin=${match.margin}", match.trusted)
        }
    }

    @Test
    fun `profession words without the guild title cannot authorize a guild choice`() {
        val match = LabyrinthEventTextRecognizer(guildEvents()).recognize(
            "这是一个其他页面" + guildEvents()[2].choices.joinToString(" ") { it.name + it.description },
        )
        assertFalse(match.trusted)
    }

    @Test
    fun `guild reference text resolves attacker breaker event rather than shared-title siblings`() {
        val recognizer = LabyrinthEventTextRecognizer(guildEvents())

        val match = recognizer.recognize(
            "触发事件！这里是公会管理协会！请问要找什么样的伙伴？\n" +
                "想要攻击型 成为伙伴！攻击型职能的随机印记×2＋阿尔法金币×200\n" +
                "想要破防型 成为伙伴！破防型职能的随机印记×2＋阿尔法金币×200 选择",
        )

        assertEquals("1104", match.event?.id)
        assertTrue(match.trusted)
        assertTrue(match.margin >= 0.045)
    }

    @Test
    fun `guild strengthen buff screenshot text resolves event 1102`() {
        val recognizer = LabyrinthEventTextRecognizer(guildEvents())

        val match = recognizer.recognize(
            "触发事件！这里是公会管理协会！请问要找什么样的伙伴？" +
                "想要强化型成为伙伴！强化型职能的随机印记×2＋阿尔法金币×200" +
                "想要增益型成为伙伴！增益型职能的随机印记×2＋阿尔法金币×200",
        )

        assertEquals("1102", match.event?.id)
        assertTrue(match.trusted)
        assertTrue(match.margin >= 0.045)
    }

    @Test
    fun `arena reward numbers separate otherwise identical event variants`() {
        val recognizer = LabyrinthEventTextRecognizer(arenaEvents())

        val match = recognizer.recognize(
            "发现了魔物们聚集的斗技场！一试身手吧！" +
                "向战斗格子普通难度的敌人发起挑战阿尔法金币200" +
                "与强敌战斗真是令人兴奋向战斗格子极难的敌人发起挑战阿尔法金币300",
        )

        assertEquals("1105", match.event?.id)
        assertTrue(match.trusted)
    }

    @Test
    fun `calibrated two-choice layout maps both buttons in left-to-right order`() {
        val mapper = LabyrinthEventLayoutMapper(
            listOf(
                EventLayoutTemplateResource(
                    id = "two",
                    optionCount = 2,
                    titleRoi = NormalizedOcrRect(0.22, 0.10, 0.78, 0.22),
                    optionTextRois = listOf(
                        NormalizedOcrRect(0.17, 0.20, 0.49, 0.74),
                        NormalizedOcrRect(0.51, 0.20, 0.83, 0.74),
                    ),
                    selectButtonRois = listOf(
                        NormalizedOcrRect(0.276, 0.75, 0.443, 0.91),
                        NormalizedOcrRect(0.557, 0.75, 0.724, 0.91),
                    ),
                    calibrationRequired = false,
                ),
            ),
        )

        val rects = mapper.buttonRects(2, 1920, 1080)

        assertEquals(2, rects.size)
        assertEquals(529, rects[0].left)
        assertEquals(1069, rects[1].left)
        assertTrue(rects[0].left + rects[0].width < rects[1].left)
        assertEquals(326, mapper.ocrBounds(2, 1920, 1080)?.left)
        assertEquals(108, mapper.ocrBounds(2, 1920, 1080)?.top)
    }

    @Test
    fun `complete three-choice layout outranks overlapping centered one-choice layout`() {
        val count = labyrinthCompleteEventLayoutCount(
            confidencesByOptionCount = mapOf(
                1 to listOf(0.96),
                2 to listOf(0.03, 0.04),
                3 to listOf(0.88, 0.93, 0.90),
            ),
            minimumButtonConfidence = 0.12,
        )

        assertEquals(3, count)
    }

    @Test
    fun `grey conditional option makes layout incomplete so caller can fall back to generic OCR`() {
        val count = labyrinthCompleteEventLayoutCount(
            confidencesByOptionCount = mapOf(
                2 to listOf(0.91, 0.03),
                3 to listOf(0.05, 0.91, 0.04),
            ),
            minimumButtonConfidence = 0.12,
        )

        assertEquals(null, count)
    }

    private fun guildEvents(): List<EventResource> = listOf(
        guild("1101", "11011", "坦克", "11012", "治疗"),
        guild("1102", "11021", "强化", "11022", "增益"),
        guild("1103", "11031", "减益", "11032", "干扰"),
        guild("1104", "11041", "攻击", "11042", "破防"),
    )

    private fun guild(
        eventId: String,
        leftId: String,
        leftClass: String,
        rightId: String,
        rightClass: String,
    ) = EventResource(
        id = eventId,
        name = "这里是公会管理协会！请问要找什么样的伙伴？",
        area = 1,
        choices = listOf(
            EventChoiceResource(
                id = leftId,
                slot = 1,
                name = "想要${leftClass}型 成为伙伴！",
                description = "${leftClass}型职能的 随机印记×2 ＋阿尔法金币×200",
            ),
            EventChoiceResource(
                id = rightId,
                slot = 2,
                name = "想要${rightClass}型 成为伙伴！",
                description = "${rightClass}型职能的 随机印记×2 ＋阿尔法金币×200",
            ),
        ),
    )

    private fun arenaEvents(): List<EventResource> = listOf(
        arena("1105", 200, 300),
        arena("1109", 300, 450),
        arena("1113", 400, 600),
        arena("1117", 500, 750),
    )

    private fun arena(eventId: String, normal: Int, extreme: Int) = EventResource(
        id = eventId,
        name = "发现了魔物们聚集的斗技场！",
        area = 1,
        choices = listOf(
            EventChoiceResource(
                id = "${eventId}1",
                slot = 1,
                name = "一试身手吧！",
                description = "向战斗格子（普通难度）的敌人发起挑战＋阿尔法金币×$normal",
            ),
            EventChoiceResource(
                id = "${eventId}2",
                slot = 2,
                name = "与强敌战斗真是令人兴奋！",
                description = "向战斗格子（极难）的敌人发起挑战＋阿尔法金币×$extreme",
            ),
        ),
    )
}
