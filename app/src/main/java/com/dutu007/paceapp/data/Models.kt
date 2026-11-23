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
    val isProactive: Boolean = false,
    val proactiveCategory: String? = null,
    val proactiveTitle: String? = null,
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

data class MessagePage(
    val messages: List<ChatMessage>,
    val rawCount: Int,
)

data class DailyMetricPoint(
    val date: String,
    val label: String,
    val weekday: String,
    val value: Double,
    val count: Int = 0,
)

data class LabSessionItem(
    val date: String,
    val arrive: String,
    val leave: String? = null,
    val minutes: Int? = null,
    val note: String = "",
)

data class LabAnalytics(
    val activeSession: LabSessionItem? = null,
    val activeElapsedMinutes: Int = 0,
    val todayMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val monthMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val sessionCount: Int = 0,
    val avgDailyMinutes: Int = 0,
    val streakDays: Int = 0,
    val dailySeries: List<DailyMetricPoint> = emptyList(),
    val recentSessions: List<LabSessionItem> = emptyList(),
)

data class SleepDailyPoint(
    val date: String,
    val label: String,
    val weekday: String,
    val durationMinutes: Int = 0,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val score: Int? = null,
    val bedtime: String = "",
    val wakeTime: String = "",
)

data class SleepRecordItem(
    val date: String,
    val bedtime: String = "",
    val wakeTime: String = "",
    val durationMinutes: Int = 0,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val score: Int? = null,
    val source: String = "",
    val note: String = "",
)

data class SleepAnalytics(
    val hasData: Boolean = false,
    val avgDurationMinutes: Int = 0,
    val latestDurationMinutes: Int = 0,
    val avgScore: Int? = null,
    val recordCount: Int = 0,
    val dailySeries: List<SleepDailyPoint> = emptyList(),
    val recentRecords: List<SleepRecordItem> = emptyList(),
)

data class RunningRecordItem(
    val date: String,
    val distanceKm: Double = 0.0,
    val durationMinutes: Int = 0,
    val pace: String = "",
    val source: String = "",
    val note: String = "",
    val imagePath: String = "",
)

data class RunningAnalytics(
    val totalRuns: Int = 0,
    val runsInRange: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val dailySeries: List<DailyMetricPoint> = emptyList(),
    val recentRuns: List<RunningRecordItem> = emptyList(),
)

data class CustomMetricSummary(
    val id: String,
    val name: String,
    val unit: String = "",
    val colorHex: String = "#0E5A6F",
    val goal: Double = 0.0,
    val latestValue: Double = 0.0,
    val avgValue: Double = 0.0,
    val dailySeries: List<DailyMetricPoint> = emptyList(),
)

data class AnalyticsOverview(
    val todayLabMinutes: Int = 0,
    val weekLabMinutes: Int = 0,
    val totalLabMinutes: Int = 0,
    val labActive: Boolean = false,
    val avgSleepMinutes: Int = 0,
    val latestSleepMinutes: Int = 0,
    val sleepHasData: Boolean = false,
    val runningCount30d: Int = 0,
    val runningTotalCount: Int = 0,
    val labStreakDays: Int = 0,
)

data class AnalyticsDashboard(
    val generatedAt: String = "",
    val range: String = "7d",
    val overview: AnalyticsOverview = AnalyticsOverview(),
    val lab: LabAnalytics = LabAnalytics(),
    val sleep: SleepAnalytics = SleepAnalytics(),
    val running: RunningAnalytics = RunningAnalytics(),
    val customMetrics: List<CustomMetricSummary> = emptyList(),
)

sealed interface ChatStreamEvent {
    data class RunStarted(val runId: String?) : ChatStreamEvent
    data class AssistantDelta(val messageId: String, val delta: String) : ChatStreamEvent
    data class AssistantCommentary(val text: String) : ChatStreamEvent
    data class ToolProgress(val eventType: String, val toolName: String, val preview: String?) : ChatStreamEvent
    data class ApprovalNeeded(val request: ApprovalRequest) : ChatStreamEvent
    data class AssistantCompleted(
        val sessionId: String,
        val messageId: String,
        val content: String,
        val pendingSteer: String? = null,
    ) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
    data object Done : ChatStreamEvent
}

data class PushNotificationItem(
    val id: Long,
    val title: String,
    val body: String,
    val category: String = "reminder",
    val sessionId: String = "",
    val source: String = "hermes",
    val isRead: Boolean = false,
    val createdAt: String = "",
)

data class NotificationPage(
    val items: List<PushNotificationItem> = emptyList(),
    val unreadCount: Long = 0L,
    val latestId: Long = 0L,
)

data class ProactiveConfig(
    val enabled: Boolean = true,
    val intensity: String = "balanced",
    val quietStart: String = "23:30",
    val quietEnd: String = "08:30",
    val labFocusProtect: Boolean = true,
    val strictSupervision: Boolean = true,
    val todayDate: String = "",
    val todayBudget: Int = 0,
    val todayMood: String = "",
    val sentToday: Int = 0,
    val lastSentAt: String = "",
    val nextEvalAt: String = "",
)

data class ProactiveTriggerResult(
    val sent: Boolean = false,
    val reason: String = "",
    val notification: PushNotificationItem? = null,
    val state: ProactiveConfig? = null,
)

data class CorosSyncResult(
    val ok: Boolean = false,
    val sleepSynced: Int = 0,
    val sleepAdded: Int = 0,
    val sleepUpdated: Int = 0,
    val runningSynced: Int = 0,
    val runningAdded: Int = 0,
    val runningUpdated: Int = 0,
    val cyclingSynced: Int = 0,
    val cyclingAdded: Int = 0,
    val cyclingUpdated: Int = 0,
    val latestSleepDate: String = "",
    val latestRunningDate: String = "",
    val syncedAt: String = "",
)
