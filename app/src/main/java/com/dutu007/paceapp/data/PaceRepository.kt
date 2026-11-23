package com.dutu007.paceapp.data

import android.content.Context
import com.dutu007.paceapp.BuildConfig
import com.dutu007.paceapp.cleanDisplayUserMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@Serializable
private data class LoginRequestDto(val username: String, val password: String)

@Serializable
private data class UserDto(
    val username: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
private data class LoginResponseDto(
    val token: String,
    val user: UserDto,
)

@Serializable
private data class SessionDto(
    val id: String,
    val title: String? = null,
    val source: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_active") val lastActive: Double? = null,
    val preview: String? = null,
    val pinned: Boolean = false,
)

@Serializable
private data class SessionListDto(val data: List<SessionDto> = emptyList())

@Serializable
private data class SessionCreateResponseDto(val session: SessionDto)

@Serializable
private data class MessageDto(
    val id: JsonElement? = null,
    val role: String = "assistant",
    val content: JsonElement? = null,
    val reasoning: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: Double? = null,
)

@Serializable
private data class MessageListDto(val data: List<MessageDto> = emptyList())

@Serializable
private data class EntryDto(
    val id: String,
    val kind: String = "note",
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val completed: Boolean = false,
    val pinned: Boolean = false,
    val source: String = "app",
    @SerialName("doc_path") val docPath: String? = null,
    @SerialName("card_json") val cardJson: JsonObject? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class EntryListDto(val items: List<EntryDto> = emptyList())

@Serializable
private data class CreateEntryRequestDto(
    val kind: String,
    val title: String = "",
    val content: String,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    @SerialName("sync_doc") val syncDoc: String = "",
    @SerialName("doc_path") val docPath: String = "",
    @SerialName("card_json") val cardJson: JsonObject? = null,
)

@Serializable
private data class UpdateEntryRequestDto(
    val completed: Boolean? = null,
    val pinned: Boolean? = null,
    val content: String? = null,
    val title: String? = null,
)

@Serializable
private data class DocSummaryDto(
    val path: String,
    val category: String = "note",
    val title: String = "",
    val preview: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    val editable: Boolean = false,
    val size: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class DocListDto(val items: List<DocSummaryDto> = emptyList())

@Serializable
private data class DocDetailDto(
    val path: String,
    val category: String = "note",
    val title: String = "",
    val content: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("raw_available") val rawAvailable: Boolean = false,
    val editable: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class GraphNodeDto(
    val id: String,
    val kind: String = "note",
    val category: String = "note",
    val label: String = "",
    val subtitle: String = "",
    val preview: String = "",
    @SerialName("doc_path") val docPath: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val weight: Int = 2,
)

@Serializable
private data class GraphEdgeDto(
    val source: String,
    val target: String,
    val kind: String = "related",
    val label: String = "",
)

@Serializable
private data class GraphStatsDto(
    @SerialName("node_count") val nodeCount: Int = 0,
    @SerialName("edge_count") val edgeCount: Int = 0,
    @SerialName("doc_count") val docCount: Int = 0,
    @SerialName("entry_count") val entryCount: Int = 0,
    @SerialName("tag_count") val tagCount: Int = 0,
)

@Serializable
private data class KnowledgeGraphDto(
    val nodes: List<GraphNodeDto> = emptyList(),
    val edges: List<GraphEdgeDto> = emptyList(),
    val stats: GraphStatsDto = GraphStatsDto(),
)

@Serializable
private data class SaveDocRequestDto(val path: String, val content: String)

@Serializable
private data class ErrorDto(val error: String)

@Serializable
private data class AppReleaseDto(
    val version_code: Int,
    val version_name: String,
    val notes: String = "",
    val sha256: String = "",
    val size: Long = 0,
)

@Serializable
private data class ApprovalSubmitDto(val choice: String)

@Serializable
private data class SteerRunRequestDto(val input: String)

@Serializable
private data class PushNotificationDto(
    val id: Long = 0L,
    val title: String = "",
    val body: String = "",
    val category: String = "reminder",
    @SerialName("session_id") val sessionId: String = "",
    val source: String = "hermes",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
private data class NotificationPageDto(
    val items: List<PushNotificationDto> = emptyList(),
    @SerialName("unread_count") val unreadCount: Long = 0L,
    @SerialName("latest_id") val latestId: Long = 0L,
)

@Serializable
private data class ProactiveConfigDto(
    val enabled: Boolean = true,
    val intensity: String = "balanced",
    @SerialName("quiet_start") val quietStart: String = "23:30",
    @SerialName("quiet_end") val quietEnd: String = "08:30",
    @SerialName("lab_focus_protect") val labFocusProtect: Boolean = true,
    @SerialName("strict_supervision") val strictSupervision: Boolean = true,
    @SerialName("today_date") val todayDate: String = "",
    @SerialName("today_budget") val todayBudget: Int = 0,
    @SerialName("today_mood") val todayMood: String = "",
    @SerialName("sent_today") val sentToday: Int = 0,
    @SerialName("last_sent_at") val lastSentAt: String? = null,
    @SerialName("next_eval_at") val nextEvalAt: String? = null,
)

@Serializable
private data class ProactiveTriggerRequestDto(val force: Boolean = true)

@Serializable
private data class ProactiveTriggerResponseDto(
    val sent: Boolean = false,
    val reason: String = "",
    val notification: PushNotificationDto? = null,
    val state: ProactiveConfigDto? = null,
)

@Serializable
private data class SttRefineRequestDto(
    val text: String,
    val candidates: List<String> = emptyList(),
    val context: String = "",
)

@Serializable
private data class SttRefineResponseDto(
    val text: String = "",
    val refined: Boolean = false,
)

@Serializable
private data class UploadFileResponseDto(
    val path: String,
    @SerialName("host_path") val hostPath: String,
    val name: String,
    val size: Long = 0,
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("extracted_text") val extractedText: String = "",
)

@Serializable
private data class DailyMetricPointDto(
    val date: String = "",
    val label: String = "",
    val weekday: String = "",
    val value: Double = 0.0,
    val count: Int = 0,
)

@Serializable
private data class LabSessionItemDto(
    val date: String = "",
    val arrive: String = "",
    val leave: String? = null,
    val minutes: Int? = null,
    val note: String = "",
)

@Serializable
private data class LabAnalyticsDto(
    @SerialName("active_session") val activeSession: LabSessionItemDto? = null,
    @SerialName("active_elapsed_minutes") val activeElapsedMinutes: Int = 0,
    @SerialName("today_minutes") val todayMinutes: Int = 0,
    @SerialName("week_minutes") val weekMinutes: Int = 0,
    @SerialName("month_minutes") val monthMinutes: Int = 0,
    @SerialName("total_minutes") val totalMinutes: Int = 0,
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("avg_daily_minutes") val avgDailyMinutes: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("daily_series") val dailySeries: List<DailyMetricPointDto> = emptyList(),
    @SerialName("recent_sessions") val recentSessions: List<LabSessionItemDto> = emptyList(),
)

@Serializable
private data class SleepDailyPointDto(
    val date: String = "",
    val label: String = "",
    val weekday: String = "",
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("deep_minutes") val deepMinutes: Int? = null,
    @SerialName("light_minutes") val lightMinutes: Int? = null,
    @SerialName("rem_minutes") val remMinutes: Int? = null,
    val score: Int? = null,
    val bedtime: String = "",
    @SerialName("wake_time") val wakeTime: String = "",
)

@Serializable
private data class SleepRecordItemDto(
    val date: String = "",
    val bedtime: String = "",
    @SerialName("wake_time") val wakeTime: String = "",
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("deep_minutes") val deepMinutes: Int? = null,
    @SerialName("light_minutes") val lightMinutes: Int? = null,
    @SerialName("rem_minutes") val remMinutes: Int? = null,
    @SerialName("awake_minutes") val awakeMinutes: Int? = null,
    val score: Int? = null,
    val source: String = "",
    val note: String = "",
)

@Serializable
private data class SleepAnalyticsDto(
    @SerialName("has_data") val hasData: Boolean = false,
    @SerialName("avg_duration_minutes") val avgDurationMinutes: Int = 0,
    @SerialName("latest_duration_minutes") val latestDurationMinutes: Int = 0,
    @SerialName("avg_score") val avgScore: Int? = null,
    @SerialName("record_count") val recordCount: Int = 0,
    @SerialName("daily_series") val dailySeries: List<SleepDailyPointDto> = emptyList(),
    @SerialName("recent_records") val recentRecords: List<SleepRecordItemDto> = emptyList(),
)

@Serializable
private data class RunningRecordItemDto(
    val date: String = "",
    @SerialName("distance_km") val distanceKm: Double = 0.0,
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    val pace: String = "",
    val source: String = "",
    val note: String = "",
    @SerialName("image_path") val imagePath: String = "",
)

@Serializable
private data class RunningAnalyticsDto(
    @SerialName("total_runs") val totalRuns: Int = 0,
    @SerialName("runs_in_range") val runsInRange: Int = 0,
    @SerialName("total_distance_km") val totalDistanceKm: Double = 0.0,
    @SerialName("daily_series") val dailySeries: List<DailyMetricPointDto> = emptyList(),
    @SerialName("recent_runs") val recentRuns: List<RunningRecordItemDto> = emptyList(),
)

@Serializable
private data class CustomMetricSummaryDto(
    val id: String = "",
    val name: String = "",
    val unit: String = "",
    val color: String = "#0E5A6F",
    val goal: Double = 0.0,
    @SerialName("latest_value") val latestValue: Double = 0.0,
    @SerialName("avg_value") val avgValue: Double = 0.0,
    @SerialName("daily_series") val dailySeries: List<DailyMetricPointDto> = emptyList(),
)

@Serializable
private data class AnalyticsOverviewDto(
    @SerialName("today_lab_minutes") val todayLabMinutes: Int = 0,
    @SerialName("week_lab_minutes") val weekLabMinutes: Int = 0,
    @SerialName("total_lab_minutes") val totalLabMinutes: Int = 0,
    @SerialName("lab_active") val labActive: Boolean = false,
    @SerialName("avg_sleep_minutes") val avgSleepMinutes: Int = 0,
    @SerialName("latest_sleep_minutes") val latestSleepMinutes: Int = 0,
    @SerialName("sleep_has_data") val sleepHasData: Boolean = false,
    @SerialName("running_count_30d") val runningCount30d: Int = 0,
    @SerialName("running_total_count") val runningTotalCount: Int = 0,
    @SerialName("lab_streak_days") val labStreakDays: Int = 0,
)

@Serializable
private data class AnalyticsDashboardDto(
    @SerialName("generated_at") val generatedAt: String = "",
    val range: String = "7d",
    val overview: AnalyticsOverviewDto = AnalyticsOverviewDto(),
    val lab: LabAnalyticsDto = LabAnalyticsDto(),
    val sleep: SleepAnalyticsDto = SleepAnalyticsDto(),
    val running: RunningAnalyticsDto = RunningAnalyticsDto(),
    @SerialName("custom_metrics") val customMetrics: List<CustomMetricSummaryDto> = emptyList(),
)

@Serializable
private data class LabCheckNoteDto(val note: String = "")

@Serializable
private data class LabManualSessionRequestDto(
    val date: String,
    val arrive: String,
    val leave: String,
    val note: String = "",
)

@Serializable
private data class SleepUpsertRequestDto(
    val date: String,
    val bedtime: String = "",
    @SerialName("wake_time") val wakeTime: String = "",
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    val score: Int? = null,
    val source: String = "manual",
    val note: String = "",
)

class PaceRepository(context: Context) {
    private val preferences = context.getSharedPreferences("paceapp_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
    }
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')

    fun hasSession(): Boolean = !preferences.getString("token", null).isNullOrBlank()
    fun authToken(): String = preferences.getString("token", "").orEmpty()
    fun currentUser(): UserProfile? {
        val username = preferences.getString("username", null) ?: return null
        val display = preferences.getString("display_name", null) ?: username
        return UserProfile(username, display)
    }

    fun lastSessionId(): String? = preferences.getString("last_session_id", null)
    fun saveLastSessionId(id: String?) {
        preferences.edit().apply {
            if (id.isNullOrBlank()) remove("last_session_id") else putString("last_session_id", id)
        }.apply()
    }

    fun clearSession() {
        preferences.edit().clear().apply()
    }

    suspend fun signIn(username: String, password: String): UserProfile {
        val resp = client.post("$baseUrl/api/v1/auth/login") {
            jsonBody(LoginRequestDto(username.trim(), password))
        }.bodyChecked<LoginResponseDto>()
        preferences.edit()
            .putString("token", resp.token)
            .putString("username", resp.user.username)
            .putString("display_name", resp.user.displayName)
            .apply()
        return UserProfile(resp.user.username, resp.user.displayName)
    }

    suspend fun signOut() {
        runCatching { client.post("$baseUrl/api/v1/auth/logout") { auth() }.check() }
        clearSession()
    }

    suspend fun checkHermesHealth(): Boolean {
        return runCatching {
            client.get("$baseUrl/api/v1/health/hermes") { auth() }.check()
            true
        }.getOrDefault(false)
    }

    suspend fun listSessions(source: String? = null): List<SessionItem> {
        val dto = client.get("$baseUrl/api/v1/sessions") {
            auth()
            if (!source.isNullOrBlank()) parameter("source", source)
        }.bodyChecked<SessionListDto>()
        return dto.data.map { it.toDomain() }
    }

    suspend fun createSession(title: String? = null): SessionItem {
        val body = buildJsonObject {
            put("source", "paceapp")
            if (!title.isNullOrBlank()) put("title", title.trim())
        }
        val dto = client.post("$baseUrl/api/v1/sessions") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyChecked<SessionCreateResponseDto>()
        return dto.session.toDomain()
    }

    suspend fun renameSession(sessionId: String, title: String): SessionItem {
        val body = buildJsonObject { put("title", title.trim()) }
        val dto = client.patch("$baseUrl/api/v1/sessions/$sessionId") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyChecked<SessionCreateResponseDto>()
        return dto.session.toDomain()
    }

    suspend fun deleteSession(sessionId: String) {
        client.delete("$baseUrl/api/v1/sessions/$sessionId") { auth() }.check()
    }

    suspend fun loadMessages(sessionId: String, limit: Int = 80): MessagePage {
        val dto = client.get("$baseUrl/api/v1/sessions/$sessionId/messages") {
            auth()
            parameter("limit", limit)
            parameter("order", "latest")
        }.bodyChecked<MessageListDto>()
        val seenIds = mutableMapOf<String, Int>()
        val items = dto.data.mapNotNull { msg ->
            if (msg.role == "system" || msg.role == "tool") return@mapNotNull null
            val rawText = extractContentText(msg.content)
            val text = if (msg.role == "user") cleanDisplayUserMessage(rawText) else rawText
            if (text.isBlank()) return@mapNotNull null
            val explicitId = msg.id?.let { if (it is JsonPrimitive) it.contentOrNull else null }?.takeIf { it.isNotBlank() }
            val baseId = explicitId ?: "m_${msg.timestamp?.toString().orEmpty()}_${msg.role}_${text.hashCode()}"
            val occ = seenIds.getOrDefault(baseId, 0)
            seenIds[baseId] = occ + 1
            val stableId = if (explicitId != null && occ == 0) explicitId else "${baseId}_$occ"
            ChatMessage(
                id = stableId,
                role = msg.role,
                content = text,
                reasoning = msg.reasoning ?: msg.reasoningContent,
                toolName = msg.toolName,
                timestamp = msg.timestamp,
            )
        }
        return MessagePage(messages = items, rawCount = dto.data.size)
    }

    fun streamChat(
        sessionId: String,
        message: String,
        imageDataUrls: List<String> = emptyList(),
    ): Flow<ChatStreamEvent> = flow {
        val token = preferences.getString("token", "").orEmpty()
        val url = URL("$baseUrl/api/v1/sessions/$sessionId/chat/stream")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val payload = if (imageDataUrls.isEmpty()) {
                buildJsonObject { put("message", message) }.toString()
            } else {
                buildJsonObject {
                    put(
                        "message",
                        buildJsonArray {
                            imageDataUrls.forEach { dataUrl ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", dataUrl) }
                                }
                            }
                            if (message.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message)
                                }
                            }
                        },
                    )
                }.toString()
            }
            conn.outputStream.use { out ->
                out.write(payload.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText()
                }.getOrNull().orEmpty()
                val errCode = runCatching { json.decodeFromString<ErrorDto>(errBody).error }.getOrNull()
                throw ApiException(code, errCode ?: "chat_failed")
            }

            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var eventName = "message"
                val dataBuffer = StringBuilder()
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (dataBuffer.isNotEmpty() || eventName == "done") {
                                val event = SseParser.parseFrame(eventName, dataBuffer.toString())
                                if (event != null) emit(event)
                                if (event is ChatStreamEvent.Done) break
                            }
                            eventName = "message"
                            dataBuffer.setLength(0)
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun submitApproval(runId: String, choice: String) {
        client.post("$baseUrl/api/v1/runs/$runId/approval") {
            auth()
            jsonBody(ApprovalSubmitDto(choice))
        }.check()
    }

    suspend fun steerRun(runId: String, input: String): Boolean {
        val response = client.post("$baseUrl/api/v1/runs/$runId/steer") {
            auth()
            jsonBody(SteerRunRequestDto(input))
        }
        if (response.status.value in 200..299) {
            return true
        }
        if (response.status.value == 409) {
            return false
        }
        response.check()
        return false
    }

    suspend fun stopRun(runId: String) {
        client.post("$baseUrl/api/v1/runs/$runId/stop") { auth() }.check()
    }

    suspend fun refineSpeech(text: String, candidates: List<String>, currentDraft: String): String {
        val resp = client.post("$baseUrl/api/v1/stt/refine") {
            auth()
            jsonBody(SttRefineRequestDto(text = text, candidates = candidates, context = currentDraft))
        }.bodyChecked<SttRefineResponseDto>()
        return resp.text.ifBlank { text }
    }

    suspend fun transcribeSpeech(wavBytes: ByteArray, currentDraft: String = ""): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val boundary = "PaceAudio${System.currentTimeMillis()}"
        val token = preferences.getString("token", "").orEmpty()
        val url = URL("$baseUrl/api/v1/stt/transcribe")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                if (currentDraft.isNotBlank()) {
                    val field = "--$boundary\r\nContent-Disposition: form-data; name=\"context\"\r\n\r\n$currentDraft\r\n"
                    out.write(field.toByteArray(StandardCharsets.UTF_8))
                }
                val header = "--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(wavBytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) {
                val errCode = runCatching { json.decodeFromString<ErrorDto>(raw).error }.getOrNull()
                throw ApiException(code, errCode ?: "stt_failed")
            }
            val dto = json.decodeFromString<SttRefineResponseDto>(raw)
            dto.text.trim()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun uploadFileBytes(fileName: String, bytes: ByteArray): UploadedFile = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val boundary = "PaceBoundary${System.currentTimeMillis()}"
        val token = preferences.getString("token", "").orEmpty()
        val url = URL("$baseUrl/api/v1/uploads")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                val header = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${fileName.replace("\"", "_")}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) {
                val errCode = runCatching { json.decodeFromString<ErrorDto>(raw).error }.getOrNull()
                throw ApiException(code, errCode ?: "upload_failed")
            }
            val dto = json.decodeFromString<UploadFileResponseDto>(raw)
            UploadedFile(dto.path, dto.hostPath, dto.name, dto.size, dto.mimeType, dto.extractedText)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun listEntries(kind: String? = null, query: String? = null): List<EntryItem> {
        val dto = client.get("$baseUrl/api/v1/entries") {
            auth()
            if (!kind.isNullOrBlank() && kind != "all") parameter("kind", kind)
            if (!query.isNullOrBlank()) parameter("q", query.trim())
        }.bodyChecked<EntryListDto>()
        return dto.items.map { it.toDomain() }
    }

    suspend fun createEntry(
        kind: String,
        title: String,
        content: String,
        tags: List<String>,
        pinned: Boolean = false,
        syncDoc: String = "",
        docPath: String = "",
        cardJson: JsonObject? = null,
    ): EntryItem {
        val dto = client.post("$baseUrl/api/v1/entries") {
            auth()
            jsonBody(CreateEntryRequestDto(kind, title, content, tags, pinned, syncDoc, docPath, cardJson))
        }.bodyChecked<EntryDto>()
        return dto.toDomain()
    }

    suspend fun updateEntry(
        id: String,
        completed: Boolean? = null,
        pinned: Boolean? = null,
        content: String? = null,
    ): EntryItem {
        val dto = client.patch("$baseUrl/api/v1/entries/$id") {
            auth()
            jsonBody(UpdateEntryRequestDto(completed = completed, pinned = pinned, content = content))
        }.bodyChecked<EntryDto>()
        return dto.toDomain()
    }

    suspend fun deleteEntry(id: String) {
        client.delete("$baseUrl/api/v1/entries/$id") { auth() }.check()
    }

    suspend fun listDocs(category: String? = null): List<DocSummary> {
        val dto = client.get("$baseUrl/api/v1/docs") {
            auth()
            if (!category.isNullOrBlank() && category != "all") parameter("category", category)
        }.bodyChecked<DocListDto>()
        return dto.items.map {
            DocSummary(it.path, it.category, it.title, it.preview, it.mimeType, it.editable, it.size, it.updatedAt)
        }
    }

    suspend fun getDoc(path: String): DocDetail {
        val dto = client.get("$baseUrl/api/v1/docs/content") {
            auth()
            parameter("path", path)
        }.bodyChecked<DocDetailDto>()
        return DocDetail(dto.path, dto.category, dto.title, dto.content, dto.mimeType, dto.rawAvailable, dto.editable, dto.updatedAt)
    }

    suspend fun saveDoc(path: String, content: String): DocDetail {
        val dto = client.put("$baseUrl/api/v1/docs/content") {
            auth()
            jsonBody(SaveDocRequestDto(path, content))
        }.bodyChecked<DocDetailDto>()
        return DocDetail(dto.path, dto.category, dto.title, dto.content, dto.mimeType, dto.rawAvailable, dto.editable, dto.updatedAt)
    }

    suspend fun latestRelease(): AppRelease {
        val dto = client.get("$baseUrl/api/v1/app/latest") { auth() }.bodyChecked<AppReleaseDto>()
        return AppRelease(dto.version_code, dto.version_name, dto.notes, dto.sha256, dto.size)
    }

    suspend fun downloadApk(destination: File, onProgress: (Long, Long) -> Unit) {
        client.prepareGet("$baseUrl/api/v1/app/apk") { auth() }.execute { response ->
            if (response.status.value !in 200..299) throw ApiException(response.status.value, "download_failed")
            val total = response.contentLength() ?: -1L
            destination.parentFile?.mkdirs()
            val channel = response.bodyAsChannel()
            destination.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    received += read
                    onProgress(received, total)
                }
            }
        }
    }

    private fun extractContentText(element: JsonElement?): String {
        if (element == null) return ""
        return when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> runCatching {
                element.jsonArray.mapNotNull { part ->
                    val obj = part.jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "text", "input_text", "output_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        "image_url", "input_image" -> "[图片]"
                        else -> null
                    }
                }.joinToString("\n")
            }.getOrDefault("")
        }
    }

    suspend fun getGraph(): KnowledgeGraphData {
        val dto = client.get("$baseUrl/api/v1/graph") { auth() }.bodyChecked<KnowledgeGraphDto>()
        return KnowledgeGraphData(
            nodes = dto.nodes.map {
                GraphNodeItem(
                    id = it.id,
                    kind = it.kind,
                    category = it.category,
                    label = it.label,
                    subtitle = it.subtitle,
                    preview = it.preview,
                    docPath = it.docPath,
                    updatedAt = it.updatedAt,
                    weight = it.weight,
                )
            },
            edges = dto.edges.map {
                GraphEdgeItem(
                    source = it.source,
                    target = it.target,
                    kind = it.kind,
                    label = it.label,
                )
            },
            stats = GraphStatsItem(
                nodeCount = dto.stats.nodeCount,
                edgeCount = dto.stats.edgeCount,
                docCount = dto.stats.docCount,
                entryCount = dto.stats.entryCount,
                tagCount = dto.stats.tagCount,
            ),
        )
    }

    suspend fun downloadDocRaw(path: String, destFile: File): File {
        destFile.parentFile?.mkdirs()
        client.prepareGet("$baseUrl/api/v1/docs/raw") {
            auth()
            parameter("path", path)
        }.execute { resp ->
            resp.check()
            val channel = resp.bodyAsChannel()
            destFile.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
        }
        return destFile
    }

    suspend fun getAnalytics(range: String = "7d"): AnalyticsDashboard {
        val dto = client.get("$baseUrl/api/v1/analytics") {
            auth()
            parameter("range", range)
        }.bodyChecked<AnalyticsDashboardDto>()
        return AnalyticsDashboard(
            generatedAt = dto.generatedAt,
            range = dto.range,
            overview = AnalyticsOverview(
                todayLabMinutes = dto.overview.todayLabMinutes,
                weekLabMinutes = dto.overview.weekLabMinutes,
                totalLabMinutes = dto.overview.totalLabMinutes,
                labActive = dto.overview.labActive,
                avgSleepMinutes = dto.overview.avgSleepMinutes,
                latestSleepMinutes = dto.overview.latestSleepMinutes,
                sleepHasData = dto.overview.sleepHasData,
                runningCount30d = dto.overview.runningCount30d,
                runningTotalCount = dto.overview.runningTotalCount,
                labStreakDays = dto.overview.labStreakDays,
            ),
            lab = LabAnalytics(
                activeSession = dto.lab.activeSession?.toDomain(),
                activeElapsedMinutes = dto.lab.activeElapsedMinutes,
                todayMinutes = dto.lab.todayMinutes,
                weekMinutes = dto.lab.weekMinutes,
                monthMinutes = dto.lab.monthMinutes,
                totalMinutes = dto.lab.totalMinutes,
                sessionCount = dto.lab.sessionCount,
                avgDailyMinutes = dto.lab.avgDailyMinutes,
                streakDays = dto.lab.streakDays,
                dailySeries = dto.lab.dailySeries.map { it.toDomain() },
                recentSessions = dto.lab.recentSessions.map { it.toDomain() },
            ),
            sleep = SleepAnalytics(
                hasData = dto.sleep.hasData,
                avgDurationMinutes = dto.sleep.avgDurationMinutes,
                latestDurationMinutes = dto.sleep.latestDurationMinutes,
                avgScore = dto.sleep.avgScore,
                recordCount = dto.sleep.recordCount,
                dailySeries = dto.sleep.dailySeries.map {
                    SleepDailyPoint(
                        date = it.date,
                        label = it.label,
                        weekday = it.weekday,
                        durationMinutes = it.durationMinutes,
                        deepMinutes = it.deepMinutes,
                        lightMinutes = it.lightMinutes,
                        remMinutes = it.remMinutes,
                        score = it.score,
                        bedtime = it.bedtime,
                        wakeTime = it.wakeTime,
                    )
                },
                recentRecords = dto.sleep.recentRecords.map {
                    SleepRecordItem(
                        date = it.date,
                        bedtime = it.bedtime,
                        wakeTime = it.wakeTime,
                        durationMinutes = it.durationMinutes,
                        deepMinutes = it.deepMinutes,
                        lightMinutes = it.lightMinutes,
                        remMinutes = it.remMinutes,
                        awakeMinutes = it.awakeMinutes,
                        score = it.score,
                        source = it.source,
                        note = it.note,
                    )
                },
            ),
            running = RunningAnalytics(
                totalRuns = dto.running.totalRuns,
                runsInRange = dto.running.runsInRange,
                totalDistanceKm = dto.running.totalDistanceKm,
                dailySeries = dto.running.dailySeries.map { it.toDomain() },
                recentRuns = dto.running.recentRuns.map {
                    RunningRecordItem(
                        date = it.date,
                        distanceKm = it.distanceKm,
                        durationMinutes = it.durationMinutes,
                        pace = it.pace,
                        source = it.source,
                        note = it.note,
                        imagePath = it.imagePath,
                    )
                },
            ),
            customMetrics = dto.customMetrics.map { m ->
                CustomMetricSummary(
                    id = m.id,
                    name = m.name,
                    unit = m.unit,
                    colorHex = m.color,
                    goal = m.goal,
                    latestValue = m.latestValue,
                    avgValue = m.avgValue,
                    dailySeries = m.dailySeries.map { it.toDomain() },
                )
            },
        )
    }

    suspend fun labCheckIn(note: String = "") {
        client.post("$baseUrl/api/v1/analytics/lab/checkin") {
            auth()
            jsonBody(LabCheckNoteDto(note))
        }.check()
    }

    suspend fun labCheckOut(note: String = "") {
        client.post("$baseUrl/api/v1/analytics/lab/checkout") {
            auth()
            jsonBody(LabCheckNoteDto(note))
        }.check()
    }

    suspend fun addLabSession(date: String, arrive: String, leave: String, note: String = "") {
        client.post("$baseUrl/api/v1/analytics/lab/sessions") {
            auth()
            jsonBody(LabManualSessionRequestDto(date, arrive, leave, note))
        }.check()
    }

    suspend fun deleteLabSession(arrive: String) {
        client.delete("$baseUrl/api/v1/analytics/lab/sessions") {
            auth()
            parameter("arrive", arrive)
        }.check()
    }

    suspend fun upsertSleepRecord(
        date: String,
        bedtime: String,
        wakeTime: String,
        durationMinutes: Int = 0,
        score: Int? = null,
        note: String = "",
    ) {
        client.post("$baseUrl/api/v1/analytics/sleep") {
            auth()
            jsonBody(SleepUpsertRequestDto(date, bedtime, wakeTime, durationMinutes, score, "manual", note))
        }.check()
    }

    suspend fun listNotifications(
        limit: Int = 50,
        unreadOnly: Boolean = false,
        afterId: Long = 0L,
    ): NotificationPage {
        val dto = client.get("$baseUrl/api/v1/notifications") {
            auth()
            parameter("limit", limit)
            if (unreadOnly) parameter("unread_only", "1")
            if (afterId > 0L) parameter("after_id", afterId)
        }.bodyChecked<NotificationPageDto>()
        return NotificationPage(
            items = dto.items.map { it.toDomain() },
            unreadCount = dto.unreadCount,
            latestId = dto.latestId,
        )
    }

    suspend fun markNotificationRead(id: Long) {
        client.post("$baseUrl/api/v1/notifications/$id/read") { auth() }.check()
    }

    suspend fun markAllNotificationsRead() {
        client.post("$baseUrl/api/v1/notifications/read-all") { auth() }.check()
    }

    suspend fun sendTestNotification(): PushNotificationItem {
        val dto = client.post("$baseUrl/api/v1/push/test") { auth() }.bodyChecked<PushNotificationDto>()
        return dto.toDomain()
    }

    suspend fun getProactiveConfig(): ProactiveConfig {
        val dto = client.get("$baseUrl/api/v1/proactive/config") { auth() }.bodyChecked<ProactiveConfigDto>()
        return dto.toDomain()
    }

    suspend fun updateProactiveConfig(
        enabled: Boolean? = null,
        intensity: String? = null,
        quietStart: String? = null,
        quietEnd: String? = null,
        labFocusProtect: Boolean? = null,
        strictSupervision: Boolean? = null,
    ): ProactiveConfig {
        val body = buildJsonObject {
            enabled?.let { put("enabled", it) }
            intensity?.let { put("intensity", it) }
            quietStart?.let { put("quiet_start", it) }
            quietEnd?.let { put("quiet_end", it) }
            labFocusProtect?.let { put("lab_focus_protect", it) }
            strictSupervision?.let { put("strict_supervision", it) }
        }
        val dto = client.patch("$baseUrl/api/v1/proactive/config") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyChecked<ProactiveConfigDto>()
        return dto.toDomain()
    }

    suspend fun triggerProactiveNow(force: Boolean = true): ProactiveTriggerResult {
        val dto = client.post("$baseUrl/api/v1/proactive/trigger") {
            auth()
            jsonBody(ProactiveTriggerRequestDto(force = force))
        }.bodyChecked<ProactiveTriggerResponseDto>()
        return ProactiveTriggerResult(
            sent = dto.sent,
            reason = dto.reason,
            notification = dto.notification?.toDomain(),
            state = dto.state?.toDomain(),
        )
    }

    private fun ProactiveConfigDto.toDomain() = ProactiveConfig(
        enabled = enabled,
        intensity = intensity,
        quietStart = quietStart,
        quietEnd = quietEnd,
        labFocusProtect = labFocusProtect,
        strictSupervision = strictSupervision,
        todayDate = todayDate,
        todayBudget = todayBudget,
        todayMood = todayMood,
        sentToday = sentToday,
        lastSentAt = lastSentAt.orEmpty(),
        nextEvalAt = nextEvalAt.orEmpty(),
    )

    private fun PushNotificationDto.toDomain() = PushNotificationItem(
        id = id,
        title = title,
        body = body,
        category = category,
        sessionId = sessionId,
        source = source,
        isRead = isRead,
        createdAt = createdAt,
    )

    private fun DailyMetricPointDto.toDomain() = DailyMetricPoint(date, label, weekday, value, count)

    private fun LabSessionItemDto.toDomain() = LabSessionItem(date, arrive, leave, minutes, note)

    private fun EntryDto.toDomain() = EntryItem(
        id = id,
        kind = kind,
        title = title,
        content = content,
        tags = tags,
        completed = completed,
        pinned = pinned,
        source = source,
        docPath = docPath,
        card = cardJson?.let { PaceCardParser.parseCardObject(it) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun SessionDto.toDomain() = SessionItem(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: "新对话",
        source = source.orEmpty(),
        messageCount = messageCount,
        lastActive = lastActive,
        preview = preview,
        pinned = pinned,
    )

    private fun HttpRequestBuilder.auth() {
        header("Authorization", "Bearer ${preferences.getString("token", "")}")
    }

    private fun HttpRequestBuilder.jsonBody(value: Any) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }

    private suspend inline fun <reified T> HttpResponse.bodyChecked(): T {
        check()
        return body()
    }

    private suspend fun HttpResponse.check() {
        if (status.value !in 200..299) {
            val error = runCatching { body<ErrorDto>() }.getOrNull()?.error
            throw ApiException(status.value, error ?: "request_failed")
        }
    }
}

class ApiException(val statusCode: Int, val code: String) : Exception(code)
