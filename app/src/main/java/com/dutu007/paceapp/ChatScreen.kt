package com.dutu007.paceapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dutu007.paceapp.data.ApprovalRequest
import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.SessionItem
import com.dutu007.paceapp.ui.theme.PacePalette
import java.io.File

private val QuickPrompts = listOf(
    "帮我记一下：今天完成了一项关键任务，并写入今日日记",
    "把我的荣誉清单整理成结构化卡片展示出来",
    "查看我最近的日记和跑步记录，做个简洁统计卡片",
    "列出我当前所有的笔记文档和待办事项",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
    showSessionSheet: Boolean,
    onDismissSessionSheet: () -> Unit,
) {
    val listState = rememberLazyListState()
    var sessionToManage by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var previewTarget by remember { mutableStateOf<InAppPreviewTarget?>(null) }
    val reversedMessages = remember(state.messages) { state.messages.asReversed() }

    LaunchedEffect(state.activeSessionId) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.id, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        state.pendingApproval?.let { approval ->
            ApprovalCard(approval = approval, onChoice = viewModel::respondApproval)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.messagesLoading && state.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = PacePalette.Card,
                            border = BorderStroke(1.dp, PacePalette.CardBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(
                                        color = PacePalette.Primary,
                                        shape = CircleShape,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("H", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Column {
                                        Text("你好，我是 Hermes", style = MaterialTheme.typography.titleMedium, color = PacePalette.TextPrimary)
                                        Text("知识库管理 · 运动与作息分析 · 智能卡片", style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
                                    }
                                }
                                Text(
                                    "你可以直接向我发送语音、文档或图片，让我分析数据、整理笔记并用清单或表格展示结果。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PacePalette.TextSecondary,
                                )
                                QuickPrompts.forEach { prompt ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PacePalette.Background,
                                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                                        onClick = { viewModel.sendQuickPrompt(prompt) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            prompt,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PacePalette.Primary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                    ) {
                        items(reversedMessages, key = { it.id }) { msg ->
                            MessageItem(
                                message = msg,
                                onOpenChip = { chip ->
                                    previewTarget = if (chip.docPath != null) {
                                        InAppPreviewTarget.ServerDoc(
                                            title = chip.label,
                                            docPath = chip.docPath,
                                            mimeType = mimeTypeForFileName(chip.label),
                                        )
                                    } else if (chip.localUri != null) {
                                        InAppPreviewTarget.LocalUri(
                                            title = chip.label,
                                            uriString = chip.localUri,
                                            mimeType = mimeTypeForFileName(chip.label, fallback = if (chip.isImage) "image/*" else "*/*"),
                                            isImage = chip.isImage,
                                        )
                                    } else {
                                        null
                                    }
                                },
                            )
                        }
                        if (state.hasOlderMessages) {
                            item(key = "load_older_header") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = PacePalette.Card,
                                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                                        onClick = { if (!state.messagesLoading) viewModel.loadOlderMessages() },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (state.messagesLoading) {
                                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
                                                Text(
                                                    "正在加载更早消息...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = PacePalette.TextSecondary,
                                                )
                                            } else {
                                                Text(
                                                    "加载更早消息（当前展示最近 ${state.messages.size} 条）",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = PacePalette.Primary,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sleek Doubao-style composer: tap to type, long-press mic for voice input
        DoubaoComposer(
            state = state,
            viewModel = viewModel,
            onOpenPreview = { previewTarget = it },
        )
    }

    previewTarget?.let { target ->
        InAppDocumentViewerDialog(
            target = target,
            viewModel = viewModel,
            onDismiss = { previewTarget = null },
            onAskHermes = { path ->
                previewTarget = null
                viewModel.askHermesAboutDoc(path)
            },
        )
    }

    if (showSessionSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissSessionSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PacePalette.Card,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("对话列表", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "保留上下文并在下次打开时自动续接；长历史支持窗口加载与压缩",
                            style = MaterialTheme.typography.labelSmall,
                            color = PacePalette.TextSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = state.sessionFilterPaceOnly,
                            onClick = viewModel::toggleSessionFilter,
                            label = { Text(if (state.sessionFilterPaceOnly) "仅应用对话" else "全部来源") },
                        )
                        Button(
                            onClick = {
                                viewModel.startNewSession()
                                onDismissSessionSheet()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("+ 新建") }
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(min = 160.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.sessions.isEmpty()) {
                        item {
                            Text(
                                "暂无会话，点击右上角「+ 新建」或直接发消息开始。",
                                style = MaterialTheme.typography.bodySmall,
                                color = PacePalette.TextSecondary,
                                modifier = Modifier.padding(vertical = 18.dp),
                            )
                        }
                    } else {
                        items(state.sessions, key = { it.id }) { session ->
                            val selected = session.id == state.activeSessionId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) PacePalette.PrimarySoft else PacePalette.Background,
                                border = BorderStroke(1.dp, if (selected) PacePalette.Primary else PacePalette.CardBorder),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.selectSession(session.id)
                                    onDismissSessionSheet()
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            session.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val sourceLabel = when (session.source) {
                                            "api_server", "paceapp" -> "PaceApp"
                                            else -> session.source.ifBlank { "hermes" }
                                        }
                                        Text(
                                            "$sourceLabel · ${session.messageCount} 条消息",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PacePalette.TextSecondary,
                                        )
                                    }
                                    TextButton(onClick = {
                                        renameText = session.title
                                        sessionToManage = session
                                    }) { Text("管理") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    sessionToManage?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionToManage = null },
            title = { Text("管理对话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("对话标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            viewModel.deleteSession(target.id)
                            sessionToManage = null
                        },
                    ) {
                        Text("删除此对话", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameSession(target.id, renameText)
                    sessionToManage = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToManage = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DoubaoComposer(
    state: PaceUiState,
    viewModel: PaceViewModel,
    onOpenPreview: (InAppPreviewTarget) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val cancelThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }

    var voiceListening by remember { mutableStateOf(false) }
    var voiceCancelling by remember { mutableStateOf(false) }
    var voiceAmplitude by remember { mutableFloatStateOf(0f) }
    var voiceElapsedSec by remember { mutableIntStateOf(0) }
    var showAttachmentTray by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    var draftValue by remember {
        mutableStateOf(TextFieldValue(text = state.draft, selection = TextRange(state.draft.length)))
    }
    var lastCursorTick by remember { mutableIntStateOf(state.draftCursorTick) }
    LaunchedEffect(state.draft, state.draftCursorTick) {
        if (draftValue.text != state.draft || lastCursorTick != state.draftCursorTick) {
            draftValue = TextFieldValue(text = state.draft, selection = TextRange(state.draft.length))
            lastCursorTick = state.draftCursorTick
        }
    }
    val activeDraftValue = if (draftValue.text == state.draft && lastCursorTick == state.draftCursorTick) {
        draftValue
    } else {
        TextFieldValue(text = state.draft, selection = TextRange(state.draft.length))
    }

    LaunchedEffect(voiceListening) {
        if (!voiceListening) {
            voiceElapsedSec = 0
            voiceAmplitude = 0f
            return@LaunchedEffect
        }
        voiceElapsedSec = 0
        while (voiceListening) {
            kotlinx.coroutines.delay(1000L)
            if (voiceListening) voiceElapsedSec += 1
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            viewModel.addAttachment(context, uri, isImage = true, fallbackName = "拍照图片.jpg")
            showAttachmentTray = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.addAttachment(context, uri, isImage = true, fallbackName = "相册图片.jpg")
            showAttachmentTray = false
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.addAttachment(context, uri, isImage = false, fallbackName = "文档附件")
            showAttachmentTray = false
        }
    }

    fun launchCamera() {
        val uri = createTempCameraUri(context)
        if (uri == null) {
            viewModel.notifyHint("无法创建拍照缓存文件", isError = true)
            return
        }
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.notifyHint("已授权麦克风，长按中上方麦克风即可语音输入")
        } else {
            viewModel.notifyHint("需要麦克风权限才能使用语音输入", isError = true)
        }
    }

    val voiceController = rememberVoiceInputController(
        context = context,
        onAmplitude = { voiceAmplitude = it },
        onAudioReady = { wavBytes ->
            voiceListening = false
            voiceAmplitude = 0f
            viewModel.transcribeVoiceAudio(wavBytes)
        },
        onError = { msg ->
            voiceListening = false
            voiceAmplitude = 0f
            viewModel.notifyHint(msg, isError = true)
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasInput = (state.draft.isNotBlank() || state.pendingAttachments.isNotEmpty()) && !state.voiceRefining

        if (voiceListening || state.voiceRefining) {
            val bg = when {
                voiceCancelling -> MaterialTheme.colorScheme.errorContainer
                state.voiceRefining -> PacePalette.PrimarySoft
                else -> PacePalette.Primary
            }
            val fg = when {
                voiceCancelling -> MaterialTheme.colorScheme.onErrorContainer
                state.voiceRefining -> PacePalette.Primary
                else -> Color.White
            }
            Surface(
                color = bg,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            voiceCancelling -> "松开手指取消发送"
                            state.voiceRefining -> "正在识别语音..."
                            else -> "正在录音 ${voiceElapsedSec}s · 上滑取消 · 松手转文字"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        modifier = Modifier.weight(1f),
                    )
                    if (voiceListening && !voiceCancelling) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf(0.45f, 0.8f, 1.0f, 0.75f, 0.5f).forEach { weight ->
                                val barH = (6f + 16f * (voiceAmplitude * weight).coerceIn(0f, 1f)).dp
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barH)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color.White.copy(alpha = 0.92f)),
                                )
                            }
                        }
                    } else if (state.voiceRefining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PacePalette.Primary,
                        )
                    }
                }
            }
        } else if (state.streaming) {
            val latestTool = state.toolActivities.lastOrNull()
            val statusLine = buildString {
                if (latestTool != null) {
                    append("⚙ ${latestTool.name}：${latestTool.preview ?: latestTool.status}")
                } else {
                    append("Hermes 思考中 · 可直接输入并发送补充指令")
                }
                if (state.queuedFollowUpCount > 0) {
                    append(" · 已排队 ${state.queuedFollowUpCount} 条")
                }
            }
            Surface(
                color = PacePalette.PrimarySoft,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = statusLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasInput) {
                        Text(
                            text = "停止",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = viewModel::stopActiveRun)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // Top-center floating Hold-to-Talk Mic Pill directly above input box
        Surface(
            color = when {
                voiceCancelling -> MaterialTheme.colorScheme.errorContainer
                voiceListening -> PacePalette.Primary
                else -> PacePalette.Card
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (voiceListening) PacePalette.Primary else PacePalette.CardBorder,
            ),
            shape = RoundedCornerShape(99.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val quickRelease = withTimeoutOrNull(160L) {
                        waitForUpOrCancellation()
                    }
                    if (quickRelease != null) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.notifyHint("请长按此处麦克风说话，松手识别填入输入框")
                        }
                        return@awaitEachGesture
                    }

                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    voiceAmplitude = 0f
                    voiceCancelling = false
                    val started = voiceController.start()
                    if (!started) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    voiceListening = true

                    val startY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            break
                        }
                        voiceCancelling = (startY - change.position.y) > cancelThresholdPx
                    }

                    val shouldCancel = voiceCancelling
                    voiceCancelling = false
                    voiceListening = false
                    voiceAmplitude = 0f
                    voiceController.finish(cancel = shouldCancel)
                }
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = MicIcon,
                    contentDescription = "按住说话",
                    tint = when {
                        voiceCancelling -> MaterialTheme.colorScheme.error
                        voiceListening -> Color.White
                        else -> PacePalette.Primary
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (voiceListening) "松手识别" else "按住说话",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        voiceCancelling -> MaterialTheme.colorScheme.error
                        voiceListening -> Color.White
                        else -> PacePalette.TextSecondary
                    },
                )
            }
        }

        if (state.pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.pendingAttachments.forEach { att ->
                    if (att.isImage) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onOpenPreview(
                                        InAppPreviewTarget.LocalUri(
                                            title = att.name,
                                            uriString = att.uriString,
                                            mimeType = att.mimeType.ifBlank { "image/*" },
                                            isImage = true,
                                        ),
                                    )
                                },
                        ) {
                            UriThumbnailImage(
                                uriString = att.uriString,
                                contentDescription = att.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .clickable { viewModel.removeAttachment(att.id) },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("×", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        val (badge, badgeColor) = fileTypeBadge(att.name)
                        Surface(
                            color = PacePalette.Card,
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable {
                                onOpenPreview(
                                    InAppPreviewTarget.LocalUri(
                                        title = att.name,
                                        uriString = att.uriString,
                                        mimeType = att.mimeType.ifBlank { mimeTypeForFileName(att.name) },
                                        isImage = false,
                                    ),
                                )
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(
                                    color = badgeColor.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PacePalette.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                                Text(
                                    text = "×",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PacePalette.TextSecondary,
                                    modifier = Modifier.clickable { viewModel.removeAttachment(att.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Doubao-style bottom input pill: [Camera] [Text Input] [Send/Stop] [+]
        Surface(
            color = PacePalette.Card,
            border = BorderStroke(1.dp, PacePalette.CardBorder),
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Leftmost Camera button
                Surface(
                    onClick = { launchCamera() },
                    color = PacePalette.Muted,
                    shape = CircleShape,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = CameraIcon,
                            contentDescription = "拍照",
                            tint = PacePalette.Primary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp, max = 120.dp)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.draft.isEmpty()) {
                        Text(
                            text = if (state.streaming) "继续补充指令（不中断思考）..." else "发消息...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PacePalette.TextSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = activeDraftValue,
                        onValueChange = { updated ->
                            draftValue = updated
                            if (updated.text != state.draft) {
                                viewModel.setDraft(updated.text)
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PacePalette.TextPrimary),
                        cursorBrush = SolidColor(PacePalette.Primary),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.width(6.dp))

                when {
                    hasInput -> {
                        Surface(
                            onClick = viewModel::sendMessage,
                            color = PacePalette.Primary,
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "↑",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    state.streaming -> {
                        Surface(
                            onClick = viewModel::stopActiveRun,
                            color = PacePalette.Muted,
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("■", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    else -> {
                        Surface(
                            onClick = {},
                            color = PacePalette.Muted,
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "↑",
                                    color = PacePalette.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Rightmost '+' attachment drawer toggle
                Surface(
                    onClick = { showAttachmentTray = !showAttachmentTray },
                    color = if (showAttachmentTray) PacePalette.PrimarySoft else PacePalette.Muted,
                    shape = CircleShape,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = PlusIcon,
                            contentDescription = "更多附件",
                            tint = PacePalette.Primary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }

        if (showAttachmentTray) {
            Surface(
                color = PacePalette.Card,
                border = BorderStroke(1.dp, PacePalette.CardBorder),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AttachmentActionTile(icon = CameraIcon, label = "拍照") {
                        launchCamera()
                    }
                    AttachmentActionTile(icon = GalleryIcon, label = "相册") {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    AttachmentActionTile(icon = FileDocIcon, label = "选择文件") {
                        fileLauncher.launch(arrayOf("*/*"))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = PacePalette.Background,
            border = BorderStroke(1.dp, PacePalette.CardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = PacePalette.Primary, modifier = Modifier.size(22.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextPrimary)
    }
}

private fun createTempCameraUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val photo = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
}.getOrNull()

@Composable
private fun MessageItem(
    message: ChatMessage,
    onOpenChip: (ChatAttachmentChip) -> Unit,
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"

    if (isUser) {
        val (cleanText, chips) = remember(message.content) { extractChatAttachmentChips(message.content) }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxBubbleWidth = maxWidth * 0.84f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    color = PacePalette.Primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.widthIn(max = maxBubbleWidth),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (chips.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                chips.forEach { chip ->
                                    if (chip.isImage && chip.localUri != null) {
                                        UriThumbnailImage(
                                            uriString = chip.localUri,
                                            contentDescription = chip.label,
                                            modifier = Modifier
                                                .size(86.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { onOpenChip(chip) },
                                        )
                                    } else {
                                        val (badge, _) = fileTypeBadge(chip.label)
                                        Surface(
                                            color = Color.White.copy(alpha = 0.16f),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.clickable { onOpenChip(chip) },
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Surface(
                                                    color = Color.White.copy(alpha = 0.22f),
                                                    shape = RoundedCornerShape(5.dp),
                                                ) {
                                                    Text(
                                                        badge,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                                Text(
                                                    chip.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 160.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (cleanText.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = cleanText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    if (isTool) {
        Surface(
            color = PacePalette.Muted,
            border = BorderStroke(1.dp, PacePalette.CardBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "⚙ 工具调用：${message.toolName ?: "tool"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.content.isNotBlank()) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = PacePalette.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }

    // Assistant message: full-width breathable editorial layout (RikkaHub style)
    val (cleanAssistantText, assistantChips) = remember(message.content) {
        extractChatAttachmentChips(message.content)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(PacePalette.Primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("H", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text("Hermes", style = MaterialTheme.typography.labelMedium, color = PacePalette.TextSecondary, fontWeight = FontWeight.SemiBold)
        }

        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    cleanAssistantText.isNotBlank() -> {
                        RichMessageBody(
                            rawText = cleanAssistantText,
                            textColor = PacePalette.TextPrimary,
                            onPinCard = null,
                        )
                    }
                    assistantChips.isNotEmpty() -> Unit
                    message.isStreaming -> {
                        Text("正在思考...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                    }
                }
            }
        }

        if (assistantChips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                assistantChips.forEach { chip ->
                    val (badge, badgeColor) = fileTypeBadge(chip.label)
                    Surface(
                        color = PacePalette.Card,
                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                        shape = RoundedCornerShape(14.dp),
                        onClick = { onOpenChip(chip) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                color = badgeColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    chip.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PacePalette.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "点击预览 / 本机打开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PacePalette.TextSecondary,
                                )
                            }
                            Text(
                                "打开 ›",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PacePalette.Primary,
                            )
                        }
                    }
                }
            }
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { copyPlainText(context, message.content) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("复制", style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalRequest, onChoice: (String) -> Unit) {
    Surface(
        color = PacePalette.AccentAmberSoft,
        border = BorderStroke(1.dp, PacePalette.AccentAmber.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hermes 请求操作授权", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (approval.description.isNotBlank()) Text(approval.description, style = MaterialTheme.typography.bodySmall)
            if (approval.command.isNotBlank()) {
                Text(approval.command, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("once" in approval.choices) Button(onClick = { onChoice("once") }) { Text("允许一次") }
                if ("session" in approval.choices) OutlinedButton(onClick = { onChoice("session") }) { Text("本会话允许") }
                OutlinedButton(onClick = { onChoice("deny") }) { Text("拒绝") }
            }
        }
    }
}
