package com.dutu007.paceapp

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dutu007.paceapp.data.AnalyticsDashboard
import com.dutu007.paceapp.data.ApiException
import com.dutu007.paceapp.data.AppRelease
import com.dutu007.paceapp.data.ApprovalRequest
import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.ChatStreamEvent
import com.dutu007.paceapp.data.DocDetail
import com.dutu007.paceapp.data.DocSummary
import com.dutu007.paceapp.data.EntryItem
import com.dutu007.paceapp.data.KnowledgeGraphData
import com.dutu007.paceapp.data.PaceCardData
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.data.PendingAttachment
import com.dutu007.paceapp.data.SessionItem
import com.dutu007.paceapp.data.ToolActivity
import com.dutu007.paceapp.data.UserProfile
import com.dutu007.paceapp.widget.PaceWidgetUpdater
import com.dutu007.paceapp.widget.WidgetSyncManager
import com.dutu007.paceapp.widget.WidgetSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun appendTranscriptText(existingDraft: String, transcript: String): String {
    val clean = transcript.trim()
    if (clean.isEmpty()) return existingDraft
    val prefix = existingDraft.trimEnd()
    val needsSpace = prefix.isNotEmpty() && prefix.last().let {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
    }
    return when {
        prefix.isEmpty() -> clean
        needsSpace -> "$prefix $clean"
        else -> "$prefix$clean"
    }
}

enum class PaceTab { CHAT, ANALYTICS, ENTRIES, DOCS }

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
    val currentTab: PaceTab = PaceTab.CHAT,
    val hermesOnline: Boolean? = null,

    // Chat state
    val sessions: List<SessionItem> = emptyList(),
    val sessionFilterPaceOnly: Boolean = true,
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val messageWindowLimit: Int = 80,
    val draft: String = "",
    val draftCursorTick: Int = 0,
    val streaming: Boolean = false,
    val activeRunId: String? = null,
    val toolActivities: List<ToolActivity> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val voiceRefining: Boolean = false,
    val pendingAttachments: List<PendingAttachment> = emptyList(),

    // Entries / Quick capture state
    val entries: List<EntryItem> = emptyList(),
    val entriesLoading: Boolean = false,
    val entryFilterKind: String = "all",
    val entrySearch: String = "",
    val captureText: String = "",
    val captureKind: String = "note",
    val captureTags: String = "",
    val captureSyncDoc: String = "diary",
    val captureBusy: Boolean = false,

    // Docs & Knowledge Graph state
    val docs: List<DocSummary> = emptyList(),
    val docsLoading: Boolean = false,
    val docFilterCategory: String = "all",
    val docSearch: String = "",
    val openedDoc: DocDetail? = null,
    val docBusy: Boolean = false,
    val graph: KnowledgeGraphData? = null,
    val graphLoading: Boolean = false,

    // Analytics dashboard state
    val analytics: AnalyticsDashboard? = null,
    val analyticsLoading: Boolean = false,
    val analyticsRange: String = "7d",
    val analyticsBusy: Boolean = false,

    // Global notice & updates
    val notice: PaceNotice? = null,
    val checkingUpdate: Boolean = false,
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
            WidgetSyncManager.ensurePeriodicSync(getApplication())
        } else {
            _uiState.update { it.copy(loading = false, authenticated = false) }
        }
    }

    fun selectTab(tab: PaceTab) {
        _uiState.update { it.copy(currentTab = tab) }
        when (tab) {
            PaceTab.ANALYTICS -> refreshAnalytics()
            PaceTab.ENTRIES -> refreshEntries()
            PaceTab.DOCS -> {
                refreshDocs()
                refreshGraph()
            }
            PaceTab.CHAT -> Unit
        }
    }
    fun setUsername(value: String) = _uiState.update { it.copy(usernameInput = value) }
    fun setPassword(value: String) = _uiState.update { it.copy(passwordInput = value) }
    fun setDraft(value: String) = _uiState.update { it.copy(draft = value) }

    fun notifyHint(text: String, isError: Boolean = false) = showNotice(text, isError = isError)

    fun addAttachment(context: Context, uri: Uri, isImage: Boolean, fallbackName: String = "附件") {
        val name = resolveDisplayName(context, uri) ?: fallbackName
        val detectedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val effectiveImage = isImage || detectedMime.startsWith("image/") || isImageFileName(name)
        val mime = detectedMime.ifBlank { mimeTypeForFileName(name, fallback = "") }
        val att = PendingAttachment(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            name = name,
            isImage = effectiveImage,
            mimeType = mime,
        )
        _uiState.update { s ->
            if (s.pendingAttachments.size >= 4) {
                s.copy(notice = PaceNotice(text = "单次最多附加 4 个图片或文件", isError = true))
            } else {
                s.copy(pendingAttachments = s.pendingAttachments + att)
            }
        }
    }

    fun refreshGraph() {
        viewModelScope.launch {
            _uiState.update { it.copy(graphLoading = true) }
            try {
                val g = repository.getGraph()
                _uiState.update { it.copy(graphLoading = false, graph = g) }
            } catch (e: Exception) {
                _uiState.update { it.copy(graphLoading = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    suspend fun fetchDocDetailOnce(path: String): DocDetail = repository.getDoc(path)

    suspend fun downloadServerDocToCache(path: String, displayName: String): File {
        val docsDir = File(getApplication<Application>().cacheDir, "docs").apply { mkdirs() }
        val base = displayName.ifBlank { path.substringAfterLast('/') }
        val safeName = base.replace(Regex("""[^A-Za-z0-9.\-_\u4E00-\u9FFF]"""), "_").ifBlank { "doc" }
        val hash = path.hashCode().toUInt().toString(16)
        val dst = File(docsDir, "${hash}_$safeName")
        return repository.downloadDocRaw(path, dst)
    }

    fun askHermesAboutDoc(docPath: String) {
        askHermesAboutPrompt("请查看并整理我在知识库中的文档 `$docPath`，提取重点并说明和其他记录的联系。")
    }

    fun askHermesAboutPrompt(prompt: String) {
        _uiState.update {
            it.copy(
                currentTab = PaceTab.CHAT,
                draft = prompt,
            )
        }
    }

    fun removeAttachment(id: String) {
        _uiState.update { s -> s.copy(pendingAttachments = s.pendingAttachments.filterNot { it.id == id }) }
    }

    private fun appendTranscriptToDraft(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _uiState.update { s ->
            s.copy(
                voiceRefining = false,
                draft = appendTranscriptText(s.draft, clean),
                draftCursorTick = s.draftCursorTick + 1,
            )
        }
    }

    fun transcribeVoiceAudio(wavBytes: ByteArray) {
        if (wavBytes.size < 1000) {
            showNotice("说话时间太短，请重试", isError = true)
            return
        }
        val existingDraft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(voiceRefining = true) }
            try {
                val text = repository.transcribeSpeech(wavBytes, existingDraft)
                if (text.isBlank()) {
                    _uiState.update { it.copy(voiceRefining = false) }
                    showNotice("未检测到清晰语音内容，请重试", isError = true)
                } else {
                    appendTranscriptToDraft(text)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(voiceRefining = false) }
                if (!handleAuthError(e)) showNotice("语音识别失败：${e.toDisplayMessage()}", isError = true)
            }
        }
    }

    fun finalizeVoiceInput(candidates: List<String>) {
        val primary = candidates.firstOrNull()?.trim().orEmpty()
        if (primary.isEmpty()) return
        val existingDraft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(voiceRefining = true) }
            val refined = runCatching {
                repository.refineSpeech(primary, candidates, existingDraft)
            }.getOrDefault(primary).trim()
            appendTranscriptToDraft(refined)
        }
    }
    fun setCaptureText(value: String) = _uiState.update { it.copy(captureText = value) }
    fun setCaptureKind(value: String) = _uiState.update { it.copy(captureKind = value) }
    fun setCaptureTags(value: String) = _uiState.update { it.copy(captureTags = value) }
    fun setCaptureSyncDoc(value: String) = _uiState.update { it.copy(captureSyncDoc = value) }

    fun setEntryFilterKind(kind: String) {
        _uiState.update { it.copy(entryFilterKind = kind) }
        refreshEntries()
    }

    fun setEntrySearch(query: String) {
        _uiState.update { it.copy(entrySearch = query) }
        refreshEntries()
    }

    fun setDocFilterCategory(cat: String) {
        _uiState.update { it.copy(docFilterCategory = cat) }
        refreshDocs()
    }

    fun setDocSearch(query: String) = _uiState.update { it.copy(docSearch = query) }

    fun toggleSessionFilter() {
        _uiState.update { it.copy(sessionFilterPaceOnly = !it.sessionFilterPaceOnly) }
        viewModelScope.launch { loadSessionsInternal(autoSelect = false) }
    }

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
                WidgetSyncManager.ensurePeriodicSync(getApplication())
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
            PaceWidgetUpdater.updateAllWidgets(getApplication())
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            val online = repository.checkHermesHealth()
            _uiState.update { it.copy(hermesOnline = online) }
            loadSessionsInternal(autoSelect = true)
            refreshAnalytics()
            refreshEntries()
            refreshDocs()
            refreshGraph()
        }
    }

    private suspend fun loadSessionsInternal(autoSelect: Boolean) {
        try {
            val source = if (_uiState.value.sessionFilterPaceOnly) "api_server" else null
            val sessions = repository.listSessions(source = source)
            val savedId = repository.lastSessionId()
            val currentId = _uiState.value.activeSessionId
            val targetId = when {
                currentId != null && sessions.any { it.id == currentId } -> currentId
                savedId != null && sessions.any { it.id == savedId } -> savedId
                autoSelect -> sessions.firstOrNull()?.id
                else -> currentId
            }
            val limit = if (targetId != currentId) 80 else _uiState.value.messageWindowLimit
            _uiState.update { it.copy(sessions = sessions, activeSessionId = targetId) }
            if (targetId != null) {
                repository.saveLastSessionId(targetId)
                loadMessagesInternal(targetId, limit = limit)
            } else {
                _uiState.update { it.copy(messages = emptyList(), hasOlderMessages = false, messageWindowLimit = 80) }
            }
        } catch (e: Exception) {
            if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.streaming) {
            showNotice("当前回复正在生成中，请先停止或等待完成")
            return
        }
        repository.saveLastSessionId(sessionId)
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                hasOlderMessages = false,
                messageWindowLimit = 80,
                toolActivities = emptyList(),
                pendingApproval = null,
            )
        }
        viewModelScope.launch { loadMessagesInternal(sessionId, limit = 80) }
    }

    private suspend fun loadMessagesInternal(
        sessionId: String,
        limit: Int = _uiState.value.messageWindowLimit,
    ) {
        _uiState.update { it.copy(messagesLoading = true) }
        try {
            val page = repository.loadMessages(sessionId, limit = limit)
            val hasOlder = limit < 500 && page.rawCount >= limit
            _uiState.update {
                it.copy(
                    messagesLoading = false,
                    messages = page.messages,
                    hasOlderMessages = hasOlder,
                    messageWindowLimit = limit,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(messagesLoading = false) }
            if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
        }
    }

    fun loadOlderMessages() {
        val sessionId = _uiState.value.activeSessionId ?: return
        if (_uiState.value.messagesLoading) return
        val nextLimit = (_uiState.value.messageWindowLimit + 80).coerceAtMost(500)
        if (nextLimit == _uiState.value.messageWindowLimit) {
            _uiState.update { it.copy(hasOlderMessages = false) }
            showNotice("已展示最近 $nextLimit 条历史消息")
            return
        }
        viewModelScope.launch { loadMessagesInternal(sessionId, limit = nextLimit) }
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
                        hasOlderMessages = false,
                        messageWindowLimit = 80,
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
                _uiState.update {
                    it.copy(
                        sessions = remaining,
                        activeSessionId = nextActive,
                        hasOlderMessages = false,
                        messageWindowLimit = 80,
                    )
                }
                if (nextActive != null) {
                    loadMessagesInternal(nextActive, limit = 80)
                } else {
                    _uiState.update { it.copy(messages = emptyList()) }
                }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun sendQuickPrompt(prompt: String) {
        _uiState.update { it.copy(currentTab = PaceTab.CHAT, draft = prompt) }
        sendMessage()
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.draft.trim()
        val attachments = state.pendingAttachments
        if ((text.isBlank() && attachments.isEmpty()) || state.streaming) return

        val appContext = getApplication<Application>().applicationContext
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var sessionId = _uiState.value.activeSessionId
            if (sessionId == null) {
                try {
                    val titleSeed = text.ifBlank { attachments.firstOrNull()?.name.orEmpty() }.take(24)
                    val created = repository.createSession(title = titleSeed)
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

            val imageUrls = mutableListOf<String>()
            val uploadedLines = mutableListOf<String>()
            val displayChips = mutableListOf<String>()
            var uploadedDocCount = 0
            try {
                for (att in attachments) {
                    val uri = Uri.parse(att.uriString)
                    if (att.isImage) {
                        val dataUrl = withContext(Dispatchers.IO) { encodeImageAsDataUrl(appContext, uri) }
                            ?: throw IllegalStateException("无法读取图片 ${att.name}")
                        imageUrls += dataUrl
                        val cachedThumbUri = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = File(appContext.cacheDir, "sent_images").apply { mkdirs() }
                                val dst = File(dir, "${System.currentTimeMillis()}_${att.id.take(8)}.jpg")
                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                    dst.outputStream().use { out -> input.copyTo(out) }
                                }
                                Uri.fromFile(dst).toString()
                            }.getOrDefault(att.uriString)
                        }
                        displayChips += encodePaceLocalMarkdown(att.name, cachedThumbUri, isImage = true)
                    } else {
                        val bytes = withContext(Dispatchers.IO) {
                            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        } ?: throw IllegalStateException("无法读取文件 ${att.name}")
                        if (bytes.size > 20 * 1024 * 1024) {
                            throw IllegalStateException("文件 ${att.name} 超过 20 MB 上限")
                        }
                        val uploaded = repository.uploadFileBytes(att.name, bytes)
                        uploadedDocCount++
                        val extracted = uploaded.extractedText.trim().take(6000)
                        uploadedLines += buildString {
                            append("[附件文档已上传至服务器：${uploaded.hostPath}（原文件名：${uploaded.name}）]")
                            if (extracted.isNotBlank()) {
                                append("\n文档内容提取：\n")
                                append(extracted)
                            }
                        }
                        displayChips += encodePaceDocMarkdown(uploaded.name, uploaded.path, isImage = false)
                    }
                }
            } catch (e: Exception) {
                showNotice(e.toDisplayMessage(), isError = true)
                return@launch
            }
            if (uploadedDocCount > 0) {
                refreshDocs()
                refreshGraph()
            }

            val finalMessage = buildString {
                if (uploadedLines.isNotEmpty()) {
                    append(uploadedLines.joinToString("\n\n"))
                    if (text.isNotBlank()) append("\n\n")
                }
                if (text.isNotBlank()) {
                    append(text)
                } else if (imageUrls.isNotEmpty()) {
                    append("请查看我发送的图片。")
                } else if (uploadedLines.isNotEmpty()) {
                    append("请查看并整理我上传的文档文件。")
                }
            }

            val displaySummary = buildString {
                if (displayChips.isNotEmpty()) {
                    append(displayChips.joinToString(" "))
                    if (text.isNotBlank()) append("\n")
                }
                append(text)
            }

            val userMsgId = "local_u_${UUID.randomUUID()}"
            val assistantMsgId = "local_a_${UUID.randomUUID()}"
            _uiState.update {
                it.copy(
                    draft = "",
                    pendingAttachments = emptyList(),
                    streaming = true,
                    activeRunId = null,
                    toolActivities = emptyList(),
                    pendingApproval = null,
                    messages = it.messages + listOf(
                        ChatMessage(id = userMsgId, role = "user", content = displaySummary),
                        ChatMessage(id = assistantMsgId, role = "assistant", content = "", isStreaming = true),
                    ),
                )
            }

            try {
                repository.streamChat(sessionId, finalMessage, imageUrls).collect { event ->
                    when (event) {
                        is ChatStreamEvent.RunStarted -> _uiState.update { it.copy(activeRunId = event.runId) }
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
                                        toolActivities = (s.toolActivities + ToolActivity("思考", "info", event.text)).takeLast(6),
                                    )
                                }
                            }
                        }
                        is ChatStreamEvent.ToolProgress -> {
                            _uiState.update { s ->
                                val activity = ToolActivity(event.toolName, event.eventType, event.preview)
                                val updated = (s.toolActivities.filterNot { it.name == event.toolName && it.status == "tool.started" } + activity).takeLast(6)
                                s.copy(toolActivities = updated)
                            }
                        }
                        is ChatStreamEvent.ApprovalNeeded -> _uiState.update { it.copy(pendingApproval = event.request) }
                        is ChatStreamEvent.AssistantCompleted -> {
                            _uiState.update { s ->
                                s.copy(
                                    messages = s.messages.map { m ->
                                        if (m.id == assistantMsgId) {
                                            m.copy(content = event.content.ifBlank { m.content }, isStreaming = false)
                                        } else m
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.Error -> showNotice(event.message, isError = true)
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
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
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
                // Refresh entries, analytics & docs in case Hermes wrote something during this turn!
                refreshEntries()
                refreshAnalytics()
                refreshDocs()
                runCatching {
                    val source = if (_uiState.value.sessionFilterPaceOnly) "api_server" else null
                    val refreshed = repository.listSessions(source = source)
                    _uiState.update { it.copy(sessions = refreshed) }
                }
            }
        }
    }

    fun stopActiveRun() {
        val runId = _uiState.value.activeRunId
        if (runId != null) {
            viewModelScope.launch { runCatching { repository.stopRun(runId) } }
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

    // -- Entries & Pinned Cards --

    fun refreshEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(entriesLoading = true) }
            try {
                val s = _uiState.value
                val items = repository.listEntries(kind = s.entryFilterKind, query = s.entrySearch)
                _uiState.update { it.copy(entriesLoading = false, entries = items) }
            } catch (e: Exception) {
                _uiState.update { it.copy(entriesLoading = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun submitQuickCapture() {
        val state = _uiState.value
        val content = state.captureText.trim()
        if (content.isBlank() || state.captureBusy) return
        val tags = state.captureTags
            .split(",", "，", " ")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() }

        viewModelScope.launch {
            _uiState.update { it.copy(captureBusy = true) }
            try {
                val created = repository.createEntry(
                    kind = state.captureKind,
                    title = "",
                    content = content,
                    tags = tags,
                    pinned = false,
                    syncDoc = state.captureSyncDoc,
                )
                _uiState.update {
                    it.copy(
                        captureBusy = false,
                        captureText = "",
                        captureTags = "",
                        entries = listOf(created) + it.entries,
                    )
                }
                showNotice(if (created.docPath != null) "已保存并同步到 ${created.docPath}" else "已保存到记录库")
                if (created.docPath != null) refreshDocs()
            } catch (e: Exception) {
                _uiState.update { it.copy(captureBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun saveTextAsEntry(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val created = repository.createEntry(
                    kind = "note",
                    title = "来自 Hermes 对话",
                    content = trimmed,
                    tags = listOf("hermes"),
                    pinned = false,
                    syncDoc = "",
                )
                _uiState.update { it.copy(entries = listOf(created) + it.entries) }
                showNotice("已存进记录库")
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun pinCardToBoard(card: PaceCardData) {
        viewModelScope.launch {
            try {
                val created = repository.createEntry(
                    kind = "card",
                    title = card.title,
                    content = card.subtitle.ifBlank { card.content },
                    tags = listOf("看板"),
                    pinned = true,
                    cardJson = card.rawJson,
                )
                _uiState.update { it.copy(entries = listOf(created) + it.entries) }
                showNotice("已固定到「记录」看板")
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun toggleEntryCompleted(entry: EntryItem) {
        viewModelScope.launch {
            try {
                val updated = repository.updateEntry(entry.id, completed = !entry.completed)
                _uiState.update { s -> s.copy(entries = s.entries.map { if (it.id == entry.id) updated else it }) }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun toggleEntryPinned(entry: EntryItem) {
        viewModelScope.launch {
            try {
                val updated = repository.updateEntry(entry.id, pinned = !entry.pinned)
                _uiState.update { s -> s.copy(entries = s.entries.map { if (it.id == entry.id) updated else it }) }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteEntry(id)
                _uiState.update { s -> s.copy(entries = s.entries.filterNot { it.id == id }) }
            } catch (e: Exception) {
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    // -- Analytics Dashboard --

    fun triggerWidgetRefresh() {
        WidgetSyncManager.enqueueImmediateSync(getApplication(), WidgetSyncWorker.ACTION_REFRESH)
        refreshAnalytics()
        showNotice("已触发桌面组件同步")
    }

    fun refreshAnalytics(range: String = _uiState.value.analyticsRange) {
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsLoading = true) }
            try {
                val dashboard = repository.getAnalytics(range)
                _uiState.update {
                    it.copy(
                        analyticsLoading = false,
                        analyticsRange = dashboard.range,
                        analytics = dashboard,
                    )
                }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), dashboard)
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsLoading = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun setAnalyticsRange(range: String) {
        if (_uiState.value.analyticsRange == range && _uiState.value.analytics != null) return
        _uiState.update { it.copy(analyticsRange = range) }
        refreshAnalytics(range)
    }

    fun labCheckIn(note: String = "") {
        if (_uiState.value.analyticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsBusy = true) }
            try {
                repository.labCheckIn(note)
                val refreshed = repository.getAnalytics(_uiState.value.analyticsRange)
                _uiState.update { it.copy(analyticsBusy = false, analytics = refreshed) }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), refreshed)
                showNotice("已记录到达实验室，开始计时")
                refreshDocs()
                refreshGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun labCheckOut(note: String = "") {
        if (_uiState.value.analyticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsBusy = true) }
            try {
                repository.labCheckOut(note)
                val refreshed = repository.getAnalytics(_uiState.value.analyticsRange)
                _uiState.update { it.copy(analyticsBusy = false, analytics = refreshed) }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), refreshed)
                val latest = refreshed.lab.recentSessions.firstOrNull { !it.leave.isNullOrBlank() }
                val durationLabel = latest?.minutes?.let { formatDurationMinutes(it) } ?: "本次学习"
                showNotice("已签退，$durationLabel 已计入实验室统计")
                refreshDocs()
                refreshGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun addManualLabSession(date: String, arrive: String, leave: String, note: String = "") {
        if (_uiState.value.analyticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsBusy = true) }
            try {
                repository.addLabSession(date = date, arrive = arrive, leave = leave, note = note)
                val refreshed = repository.getAnalytics(_uiState.value.analyticsRange)
                _uiState.update { it.copy(analyticsBusy = false, analytics = refreshed) }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), refreshed)
                showNotice("已补录实验室学习时段")
                refreshDocs()
                refreshGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun deleteLabSession(arrive: String) {
        if (_uiState.value.analyticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsBusy = true) }
            try {
                repository.deleteLabSession(arrive)
                val refreshed = repository.getAnalytics(_uiState.value.analyticsRange)
                _uiState.update { it.copy(analyticsBusy = false, analytics = refreshed) }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), refreshed)
                showNotice("已删除该学习记录")
                refreshDocs()
                refreshGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun saveManualSleepRecord(
        date: String,
        bedtime: String,
        wakeTime: String,
        durationMinutes: Int = 0,
        score: Int? = null,
        note: String = "",
    ) {
        if (_uiState.value.analyticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyticsBusy = true) }
            try {
                repository.upsertSleepRecord(
                    date = date,
                    bedtime = bedtime,
                    wakeTime = wakeTime,
                    durationMinutes = durationMinutes,
                    score = score,
                    note = note,
                )
                val refreshed = repository.getAnalytics(_uiState.value.analyticsRange)
                _uiState.update { it.copy(analyticsBusy = false, analytics = refreshed) }
                WidgetSyncManager.onDashboardLoadedInApp(getApplication(), refreshed)
                showNotice("已保存 $date 睡眠记录")
                refreshDocs()
                refreshGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyticsBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    // -- Docs --

    fun refreshDocs() {
        viewModelScope.launch {
            _uiState.update { it.copy(docsLoading = true) }
            try {
                val items = repository.listDocs(category = _uiState.value.docFilterCategory)
                _uiState.update { it.copy(docsLoading = false, docs = items) }
            } catch (e: Exception) {
                _uiState.update { it.copy(docsLoading = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun openDoc(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(docBusy = true, currentTab = PaceTab.DOCS) }
            try {
                val detail = repository.getDoc(path)
                _uiState.update { it.copy(docBusy = false, openedDoc = detail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(docBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun closeDoc() = _uiState.update { it.copy(openedDoc = null) }

    fun saveDoc(path: String, content: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(docBusy = true) }
            try {
                val updated = repository.saveDoc(path, content)
                _uiState.update { it.copy(docBusy = false, openedDoc = updated) }
                showNotice("已保存并同步到服务器")
                refreshDocs()
                refreshGraph()
                onSaved()
            } catch (e: Exception) {
                _uiState.update { it.copy(docBusy = false) }
                if (!handleAuthError(e)) showNotice(e.toDisplayMessage(), isError = true)
            }
        }
    }

    fun askHermesAboutDoc(doc: DocDetail) {
        _uiState.update {
            it.copy(
                openedDoc = null,
                currentTab = PaceTab.CHAT,
                draft = "请帮我读取并整理文档 ${doc.path} 的核心内容，用结构化卡片展示出来。",
            )
        }
        sendMessage()
    }

    // -- Updates --

    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            if (manual) _uiState.update { it.copy(checkingUpdate = true) }
            try {
                val release = repository.latestRelease()
                if (release.versionCode > BuildConfig.VERSION_CODE) {
                    _uiState.update { it.copy(checkingUpdate = false, updateAvailable = release) }
                } else {
                    _uiState.update { it.copy(checkingUpdate = false) }
                    if (manual) showNotice("已是最新版本 $currentVersionName")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(checkingUpdate = false) }
                if (handleAuthError(e)) return@launch
                if (manual) {
                    val msg = if (e is ApiException && e.statusCode == 404) "服务器还没有可下载的版本" else e.toDisplayMessage()
                    showNotice(msg, isError = true)
                }
            }
        }
    }

    fun dismissUpdate() = _uiState.update { it.copy(updateAvailable = null) }
    fun noteInstallPermissionNeeded() = showNotice("请先允许安装未知应用，返回后再点一次“立即更新”", isError = true)

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
                        if (total > 0) _uiState.update { s -> s.copy(updateProgress = (received * 100 / total).toInt()) }
                    }
                }
                if (release.sha256.isNotBlank() && !target.sha256Hex().equals(release.sha256, ignoreCase = true)) {
                    target.delete()
                    throw IllegalStateException("安装包校验失败，请重新下载")
                }
                _uiState.update {
                    it.copy(updateDownloading = false, updateReadyPath = target.absolutePath, updateAvailable = null)
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

    private fun resolveDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment
    }.getOrNull()

    private fun encodeImageAsDataUrl(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = (maxDim / 1600).coerceAtLeast(1)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val scale = minOf(1f, 1600f / maxOf(decoded.width, decoded.height).coerceAtLeast(1))
        val target = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        val out = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 84, out)
        if (target !== decoded) decoded.recycle()
        target.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

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
