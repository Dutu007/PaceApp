package com.dutu007.paceapp

import com.dutu007.paceapp.data.ChatStreamEvent
import com.dutu007.paceapp.data.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun parsesAssistantDeltaAndCompleted() {
        val delta = SseParser.parseFrame("assistant.delta", """{"message_id":"m1","delta":"你好"}""")
        assertTrue(delta is ChatStreamEvent.AssistantDelta)
        assertEquals("你好", (delta as ChatStreamEvent.AssistantDelta).delta)

        val done = SseParser.parseFrame("done", "{}")
        assertTrue(done is ChatStreamEvent.Done)
    }

    @Test
    fun parsesApprovalRequestChoices() {
        val ev = SseParser.parseFrame(
            "approval.request",
            """{"run_id":"run_1","command":"ls","description":"list files","choices":["once","session","deny"]}""",
        ) as ChatStreamEvent.ApprovalNeeded
        assertEquals("run_1", ev.request.runId)
        assertEquals(listOf("once", "session", "deny"), ev.request.choices)
    }
}
