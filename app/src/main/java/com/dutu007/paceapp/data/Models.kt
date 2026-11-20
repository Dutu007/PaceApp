package com.dutu007.paceapp.data

import kotlinx.serialization.json.JsonObject

data class UserProfile(
    val username: String,
    val displayName: String,
)

data class SessionItem(
    val id: String,
    val title: String,
    val source: String,
    val messageCount: Int,
    val lastActive: Double?,
    val preview: String?,
    val pinned: Boolean,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val toolName: String? = null,
    val timestamp: Double? = null,
    val isStreaming: Boolean = false,
)

data class ToolActivity(
    val name: String,
    val status: String,
    val preview: String? = null,
)

data class ApprovalRequest(
    val runId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
)

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val sha256: String,
    val size: Long,
)

data class PaceCardItem(
    val label: String,
    val done: Boolean = false,
    val detail: String = "",
)

data class PaceCardMetric(
    val label: String,
    val value: String,
    val unit: String = "",
)

data class PaceCardData(
    val type: String,
    val title: String,
    val subtitle: String = "",
    val items: List<PaceCardItem> = emptyList(),
    val metrics: List<PaceCardMetric> = emptyList(),
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val content: String = "",
    val rawJson: JsonObject? = null,
)

data class EntryItem(
    val id: String,
    val kind: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val completed: Boolean,
    val pinned: Boolean,
    val source: String,
    val docPath: String?,
    val card: PaceCardData?,
    val createdAt: String,
    val updatedAt: String,
)

data class DocSummary(
    val path: String,
    val category: String,
    val title: String,
    val preview: String,
    val mimeType: String = "",
    val editable: Boolean,
    val size: Long,
    val updatedAt: String,
)

data class DocDetail(
    val path: String,
    val category: String,
    val title: String,
    val content: String,
    val mimeType: String = "",
    val rawAvailable: Boolean = false,
    val editable: Boolean,
    val updatedAt: String,
)

data class PendingAttachment(
    val id: String,
    val uriString: String,
    val name: String,
    val isImage: Boolean,
    val mimeType: String = "",
)

data class UploadedFile(
    val path: String,
    val hostPath: String,
    val name: String,
    val size: Long,
    val mimeType: String = "",
    val extractedText: String = "",
)

data class GraphNodeItem(
    val id: String,
    val kind: String,
    val category: String,
    val label: String,
    val subtitle: String = "",
    val preview: String = "",
    val docPath: String = "",
    val updatedAt: String = "",
    val weight: Int = 2,
)

data class GraphEdgeItem(
    val source: String,
    val target: String,
    val kind: String,
    val label: String = "",
)

data class GraphStatsItem(
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val docCount: Int = 0,
    val entryCount: Int = 0,
    val tagCount: Int = 0,
)

data class KnowledgeGraphData(
    val nodes: List<GraphNodeItem> = emptyList(),
    val edges: List<GraphEdgeItem> = emptyList(),
    val stats: GraphStatsItem = GraphStatsItem(),
)

sealed interface ChatStreamEvent {
    data class RunStarted(val runId: String?) : ChatStreamEvent
    data class AssistantDelta(val messageId: String, val delta: String) : ChatStreamEvent
    data class AssistantCommentary(val text: String) : ChatStreamEvent
    data class ToolProgress(val eventType: String, val toolName: String, val preview: String?) : ChatStreamEvent
    data class ApprovalNeeded(val request: ApprovalRequest) : ChatStreamEvent
    data class AssistantCompleted(val sessionId: String, val messageId: String, val content: String) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
    data object Done : ChatStreamEvent
}
