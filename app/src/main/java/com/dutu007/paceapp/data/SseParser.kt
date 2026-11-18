package com.dutu007.paceapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseFrame(eventName: String, rawData: String): ChatStreamEvent? {
        val trimmed = rawData.trim()
        if (trimmed.isEmpty()) {
            return if (eventName == "done") ChatStreamEvent.Done else null
        }
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return when (eventName) {
            "run.started" -> ChatStreamEvent.RunStarted(obj.str("run_id"))
            "assistant.delta" -> ChatStreamEvent.AssistantDelta(
                messageId = obj.str("message_id").orEmpty(),
                delta = obj.str("delta").orEmpty(),
            )
            "assistant.commentary" -> ChatStreamEvent.AssistantCommentary(
                text = obj.str("text").orEmpty(),
            )
            "tool.started", "tool.completed", "tool.failed", "tool.progress" -> ChatStreamEvent.ToolProgress(
                eventType = eventName,
                toolName = obj.str("tool_name") ?: "tool",
                preview = obj.str("preview") ?: obj.str("delta"),
            )
            "approval.request" -> {
                val runId = obj.str("run_id").orEmpty()
                val choices = (obj["choices"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: listOf("once", "deny")
                ChatStreamEvent.ApprovalNeeded(
                    ApprovalRequest(
                        runId = runId,
                        command = obj.str("command").orEmpty(),
                        description = obj.str("description").orEmpty(),
                        choices = choices,
                    ),
                )
            }
            "assistant.completed" -> ChatStreamEvent.AssistantCompleted(
                sessionId = obj.str("session_id").orEmpty(),
                messageId = obj.str("message_id").orEmpty(),
                content = obj.str("content").orEmpty(),
            )
            "error" -> ChatStreamEvent.Error(obj.str("message") ?: "对话出现错误")
            "done" -> ChatStreamEvent.Done
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
