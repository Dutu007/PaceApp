package com.dutu007.paceapp

import com.dutu007.paceapp.data.MessageSegment
import com.dutu007.paceapp.data.PaceCardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceCardParserTest {
    @Test
    fun splitsMarkdownAndPaceCard() {
        val raw = """
            已帮你整理好：

            ```pace-card
            {
              "type": "checklist",
              "title": "本周待办",
              "items": [{"label": "慢跑 5km", "done": true}, {"label": "整理简历", "done": false}]
            }
            ```

            需要固定到看板可以直接点按钮。
        """.trimIndent()

        val segs = PaceCardParser.splitMessage(raw)
        assertEquals(3, segs.size)
        assertTrue(segs[0] is MessageSegment.Markdown)
        assertTrue(segs[1] is MessageSegment.Card)
        assertTrue(segs[2] is MessageSegment.Markdown)

        val card = (segs[1] as MessageSegment.Card).card
        assertEquals("checklist", card.type)
        assertEquals("本周待办", card.title)
        assertEquals(2, card.items.size)
        assertTrue(card.items[0].done)
    }
}
