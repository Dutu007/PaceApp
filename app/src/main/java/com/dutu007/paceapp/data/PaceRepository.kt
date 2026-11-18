package com.dutu007.paceapp.data

import android.content.Context
import com.dutu007.paceapp.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
private data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
)

@Serializable
private data class SessionCreateResponseDto(
    val session: SessionDto,
)

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
private data class MessageListDto(
    val data: List<MessageDto> = emptyList(),
)

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

class PaceRepository(context: Context) {
    private val preferences = context.getSharedPreferences("paceapp_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
    }
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')

    fun hasSession(): Boolean = !preferences.getString("token", null).isNullOrBlank()
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
        runCatching {
            client.post("$baseUrl/api/v1/auth/logout") { auth() }.check()
        }
        clearSession()
    }

    suspend fun checkHermesHealth(): Boolean {
        return runCatching {
            client.get("$baseUrl/api/v1/health/hermes") { auth() }.check()
            true
        }.getOrDefault(false)
    }

    suspend fun listSessions(): List<SessionItem> {
        val dto = client.get("$baseUrl/api/v1/sessions") { auth() }.bodyChecked<SessionListDto>()
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

    suspend fun loadMessages(sessionId: String): List<ChatMessage> {
        val dto = client.get("$baseUrl/api/v1/sessions/$sessionId/messages") { auth() }
            .bodyChecked<MessageListDto>()
        return dto.data.mapIndexedNotNull { index, msg ->
            if (msg.role == "system") return@mapIndexedNotNull null
            val text = extractContentText(msg.content)
            if (text.isBlank() && msg.role != "assistant") return@mapIndexedNotNull null
            ChatMessage(
                id = msg.id?.let { if (it is JsonPrimitive) it.contentOrNull else null } ?: "msg_$index",
                role = msg.role,
                content = text,
                reasoning = msg.reasoning ?: msg.reasoningContent,
                toolName = msg.toolName,
                timestamp = msg.timestamp,
            )
        }
    }

    fun streamChat(sessionId: String, message: String): Flow<ChatStreamEvent> = flow {
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
            val payload = buildJsonObject { put("message", message) }.toString()
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
                        line.startsWith(":") -> {
                            // Keepalive comment; ignore.
                        }
                        line.startsWith("event:") -> {
                            eventName = line.removePrefix("event:").trim()
                        }
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

    suspend fun stopRun(runId: String) {
        client.post("$baseUrl/api/v1/runs/$runId/stop") { auth() }.check()
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
                    if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        obj["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }.joinToString("\n")
            }.getOrDefault("")
        }
    }

    private fun SessionDto.toDomain() = SessionItem(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: "未命名对话",
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
