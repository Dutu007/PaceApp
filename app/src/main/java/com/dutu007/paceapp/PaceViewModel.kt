package com.dutu007.paceapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dutu007.paceapp.data.ApiException
import com.dutu007.paceapp.data.AppRelease
import com.dutu007.paceapp.data.ApprovalRequest
import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.ChatStreamEvent
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.data.SessionItem
import com.dutu007.paceapp.data.ToolActivity
import com.dutu007.paceapp.data.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PaceNotice(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isError: Boolean = false,
)

data class PaceUiState(
    val loading: Boolean = true,
    val authenticated: Boolean = false,
    val user: UserProfile? = null,
    val usernameInput: String = "",
    val passwordInput: String = "",
    val authBusy: Boolean = false,
    val hermesOnline: Boolean? = null,
    val sessions: List<SessionItem> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val draft: String = "",
    val streaming: Boolean = false,
    val activeRunId: String? = null,
    val toolActivities: List<ToolActivity> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val notice: PaceNotice? = null,
    val updateAvailable: AppRelease? = null,
    val updateDownloading: Boolean = false,
    val updateProgress: Int = 0,
    val updateReadyPath: String? = null,
)

class PaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PaceRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(PaceUiState())
    val uiState: StateFlow<PaceUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    init {
        if (repository.hasSession()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    authenticated = true,
                    user = repository.currentUser(),
                )
            }
            refreshAll()
            checkForUpdate(manual = false)
        } else {
            _uiState.update { it.copy(loading = false, authenticated = false) }
        }
    }

    fun setUsername(value: String) = _uiState.update { it.copy(usernameInput = value) }
    fun setPassword(value: String) = _uiState.update { it.copy(passwordInput = value) }
    fun setDraft(value: String) = _uiState.update { it.copy(draft = value) }

    fun dismissNotice(id: String) = _uiState.update { state ->
        if (state.notice?.id == id) state.copy(notice = null) else state
    }

    private fun showNotice(text: String, isError: Boolean = false) {
        _uiState.update { it.copy(notice = PaceNotice(text = text, isError = isError)) }
    }

    fun signIn() {
        val state = _uiState.value
        val username = state.usernameInput.trim()
        val password = state.passwordInput
        if (username.isBlank() || password.isBlank()) {
            showNotice("请输入账号和密码", isError = true)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(authBusy = true, notice = null) }
            try {
                val user = repository.signIn(username, password)
                _uiState.update {
                    it.copy(
                        authBusy = false,
                        authenticated = true,
                        user = user,
                        passwordInput = "",
                    )
                }
                refreshAll()
                checkForUpdate(manual = false)
            } catch (e: Exception) {
                _uiState.update { it.copy(authBusy = false) }
                showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun signOut() {
        streamJob?.cancel()
        viewModelScope.launch {
            repository.signOut()
            _uiState.value = PaceUiState(loading = false, authenticated = false)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            val online = repository.checkHermesHealth()
            _uiState.update { it.copy(hermesOnline = online) }
            loadSessionsInternal(autoSelect = true)
        }
    }

    private suspend fun loadSessionsInternal(autoSelect: Boolean) {
        try {
            val sessions = repository.listSessions()
            val savedId = repository.lastSessionId()
            val currentId = _uiState.value.activeSessionId
            val targetId = when {
                currentId != null && sessions.any { it.id == currentId } -> currentId
                savedId != null && sessions.any { it.id == savedId } -> savedId
                autoSelect -> sessions.firstOrNull()?.id
                else -> currentId
            }
            _uiState.update { it.copy(sessions = sessions, activeSessionId = targetId) }
            if (targetId != null) {
                repository.saveLastSessionId(targetId)
                loadMessagesInternal(targetId)
            } else {
                _uiState.update { it.copy(messages = emptyList()) }
            }
        } catch (e: Exception) {
            if (handleAuthError(e)) return
            showNotice(e.toDisplayMessage(), isError = true)
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.streaming) {
            showNotice("当前回复正在生成中，请先停止或等待完成")
            return
        }
        repository.saveLastSessionId(sessionId)
        _uiState.update { it.copy(activeSessionId = sessionId, toolActivities = emptyList(), pendingApproval = null) }
        viewModelScope.launch { loadMessagesInternal(sessionId) }
    }

    private suspend fun loadMessagesInternal(sessionId: String) {
        _uiState.update { it.copy(messagesLoading = true) }
        try {
            val msgs = repository.loadMessages(sessionId)
            _uiState.update { it.copy(messagesLoading = false, messages = msgs) }
        } catch (e: Exception) {
            _uiState.update { it.copy(messagesLoading = false) }
            if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
        }
    }

    fun startNewSession() {
        if (_uiState.value.streaming) return
        viewModelScope.launch {
            try {
                val created = repository.createSession()
                repository.saveLastSessionId(created.id)
                _uiState.update {
                    it.copy(
                        sessions = listOf(created) + it.sessions,
                        activeSessionId = created.id,
                        messages = emptyList(),
                        toolActivities = emptyList(),
                        pendingApproval = null,
                    )
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val updated = repository.renameSession(sessionId, trimmed)
                _uiState.update { state ->
                    state.copy(sessions = state.sessions.map { if (it.id == sessionId) updated else it })
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        if (_uiState.value.streaming && _uiState.value.activeSessionId == sessionId) {
            showNotice("请先停止当前对话再删除", isError = true)
            return
        }
        viewModelScope.launch {
            try {
                repository.deleteSession(sessionId)
                val remaining = _uiState.value.sessions.filterNot { it.id == sessionId }
                val nextActive = if (_uiState.value.activeSessionId == sessionId) remaining.firstOrNull()?.id else _uiState.value.activeSessionId
                repository.saveLastSessionId(nextActive)
                _uiState.update { it.copy(sessions = remaining, activeSessionId = nextActive) }
                if (nextActive != null) {
                    loadMessagesInternal(nextActive)
                } else {
                    _uiState.update { it.copy(messages = emptyList()) }
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isBlank() || state.streaming) return

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var sessionId = _uiState.value.activeSessionId
            if (sessionId == null) {
                try {
                    val created = repository.createSession(title = text.take(24))
                    sessionId = created.id
                    repository.saveLastSessionId(sessionId)
                    _uiState.update {
                        it.copy(
                            sessions = listOf(created) + it.sessions,
                            activeSessionId = sessionId,
                        )
                    }
                } catch (e: Exception) {
                    if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
                    return@launch
                }
            }

            val userMsgId = "local_u_${UUID.randomUUID()}"
            val assistantMsgId = "local_a_${UUID.randomUUID()}"
            _uiState.update {
                it.copy(
                    draft = "",
                    streaming = true,
                    activeRunId = null,
                    toolActivities = emptyList(),
                    pendingApproval = null,
                    messages = it.messages + listOf(
                        ChatMessage(id = userMsgId, role = "user", content = text),
                        ChatMessage(id = assistantMsgId, role = "assistant", content = "", isStreaming = true),
                    ),
                )
            }

            try {
                repository.streamChat(sessionId, text).collect { event ->
                    when (event) {
                        is ChatStreamEvent.RunStarted -> {
                            _uiState.update { it.copy(activeRunId = event.runId) }
                        }
                        is ChatStreamEvent.AssistantDelta -> {
                            _uiState.update { s ->
                                s.copy(
                                    messages = s.messages.map { m ->
                                        if (m.id == assistantMsgId) m.copy(content = m.content + event.delta) else m
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.AssistantCommentary -> {
                            if (event.text.isNotBlank()) {
                                _uiState.update { s ->
                                    s.copy(
                                        toolActivities = s.toolActivities + ToolActivity(
                                            name = "思考",
                                            status = "info",
                                            preview = event.text,
                                        ),
                                    )
                                }
                            }
                        }
                        is ChatStreamEvent.ToolProgress -> {
                            _uiState.update { s ->
                                val activity = ToolActivity(
                                    name = event.toolName,
                                    status = event.eventType,
                                    preview = event.preview,
                                )
                                val updated = (s.toolActivities.filterNot { it.name == event.toolName && it.status == "tool.started" } + activity).takeLast(6)
                                s.copy(toolActivities = updated)
                            }
                        }
                        is ChatStreamEvent.ApprovalNeeded -> {
                            _uiState.update { it.copy(pendingApproval = event.request) }
                        }
                        is ChatStreamEvent.AssistantCompleted -> {
                            _uiState.update { s ->
                                s.copy(
                                    messages = s.messages.map { m ->
                                        if (m.id == assistantMsgId) {
                                            m.copy(
                                                content = event.content.ifBlank { m.content },
                                                isStreaming = false,
                                            )
                                        } else m
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.Error -> {
                            showNotice(event.message, isError = true)
                        }
                        ChatStreamEvent.Done -> {
                            _uiState.update { s ->
                                s.copy(
                                    streaming = false,
                                    activeRunId = null,
                                    pendingApproval = null,
                                    messages = s.messages.map { m ->
                                        if (m.id == assistantMsgId) m.copy(isStreaming = false) else m
                                    },
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) {
                    showNotice(e.toDisplayMessage(), isError = true)
                }
            } finally {
                _uiState.update { s ->
                    s.copy(
                        streaming = false,
                        activeRunId = null,
                        pendingApproval = null,
                        messages = s.messages.map { m ->
                            if (m.id == assistantMsgId) m.copy(isStreaming = false) else m
                        },
                    )
                }
                // Refresh session metadata so title/messageCount stay synced.
                runCatching {
                    val refreshed = repository.listSessions()
                    _uiState.update { it.copy(sessions = refreshed) }
                }
            }
        }
    }

    fun stopActiveRun() {
        val runId = _uiState.value.activeRunId
        if (runId != null) {
            viewModelScope.launch {
                runCatching { repository.stopRun(runId) }
            }
        } else {
            streamJob?.cancel()
        }
    }

    fun respondApproval(choice: String) {
        val approval = _uiState.value.pendingApproval ?: return
        viewModelScope.launch {
            try {
                repository.submitApproval(approval.runId, choice)
                _uiState.update { it.copy(pendingApproval = null) }
            } catch (e: Exception) {
                showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            try {
                val release = repository.latestRelease()
                if (release.versionCode > BuildConfig.VERSION_CODE) {
                    _uiState.update { it.copy(updateAvailable = release) }
                } else if (manual) {
                    showNotice("已是最新版本 $currentVersionName")
                }
            } catch (e: Exception) {
                if (handleAuthError(e)) return@launch
                if (manual) {
                    val msg = if (e is ApiException && e.statusCode == 404) {
                        "服务器还没有可下载的版本"
                    } else {
                        e.toDisplayMessage()
                    }
                    showNotice(msg, isError = true)
                }
            }
        }
    }

    fun dismissUpdate() = _uiState.update { it.copy(updateAvailable = null) }

    fun noteInstallPermissionNeeded() {
        showNotice("请先允许安装未知应用，返回后再点一次“立即更新”", isError = true)
    }

    fun downloadUpdate(context: Context) {
        val release = _uiState.value.updateAvailable ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(updateDownloading = true, updateProgress = 0) }
            try {
                val dir = File(context.cacheDir, "updates").apply {
                    mkdirs()
                    listFiles()?.forEach { it.delete() }
                }
                val target = File(dir, "pace-${release.versionName}.apk")
                withContext(Dispatchers.IO) {
                    repository.downloadApk(target) { received, total ->
                        if (total > 0) {
                            _uiState.update { s -> s.copy(updateProgress = (received * 100 / total).toInt()) }
                        }
                    }
                }
                if (release.sha256.isNotBlank() && !target.sha256Hex().equals(release.sha256, ignoreCase = true)) {
                    target.delete()
                    throw IllegalStateException("安装包校验失败，请重新下载")
                }
                _uiState.update {
                    it.copy(
                        updateDownloading = false,
                        updateReadyPath = target.absolutePath,
                        updateAvailable = null,
                    )
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) {
                    _uiState.update { it.copy(updateDownloading = false) }
                    showNotice(e.toDisplayMessage(), isError = true)
                }
            }
        }
    }

    fun consumeReadyUpdate() = _uiState.update { it.copy(updateReadyPath = null) }

    private fun handleAuthError(error: Exception): Boolean {
        if (error is ApiException && error.statusCode == 401) {
            repository.clearSession()
            _uiState.value = PaceUiState(
                loading = false,
                authenticated = false,
                notice = PaceNotice(text = "登录状态已过期，请重新登录", isError = true),
            )
            return true
        }
        return false
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.toDisplayMessage(): String {
        if (this is ApiException) {
            return when (code) {
                "invalid_credentials" -> "账号或密码错误"
                "unauthorized" -> "登录状态已失效，请重新登录"
                "hermes_unreachable" -> "无法连接到服务器上的 Hermes，请检查 hermes-gateway 状态"
                "release_not_found" -> "服务器还没有发布安装包"
                else -> "请求失败 ($code)"
            }
        }
        return message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试"
    }
}
