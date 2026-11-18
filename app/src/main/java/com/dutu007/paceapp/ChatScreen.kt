package com.dutu007.paceapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dutu007.paceapp.data.ApprovalRequest
import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.SessionItem
import com.dutu007.paceapp.ui.theme.PacePalette

private val QuickPrompts = listOf(
    "记住：今天完成了一项关键任务，帮我同步存入记录和日记",
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

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
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
                                        Text("随手记录 · 同步文档 · 按要求卡片展示", style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
                                    }
                                }
                                Text(
                                    "你可以直接让我记一句话到数据库或日记，也可以让我读取服务器笔记并整理成清单、统计或表格展示。",
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            MessageItem(
                                message = msg,
                                onPinCard = viewModel::pinCardToBoard,
                                onSaveText = viewModel::saveTextAsEntry,
                            )
                        }
                    }
                }
            }
        }

        // Sleek Doubao-style composer: tap to type, long-press mic (left of send) for refined voice input
        DoubaoComposer(
            state = state,
            viewModel = viewModel,
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
                    Text("对话列表", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = state.sessionFilterPaceOnly,
                            onClick = viewModel::toggleSessionFilter,
                            label = { Text(if (state.sessionFilterPaceOnly) "仅 Pace" else "全部来源") },
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
                                        Text(
                                            "${session.source.ifBlank { "hermes" }} · ${session.messageCount} 条消息",
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
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val cancelThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }

    var voiceListening by remember { mutableStateOf(false) }
    var voiceCancelling by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.notifyHint("已授权麦克风，长按右侧麦克风即可语音输入")
        } else {
            viewModel.notifyHint("需要麦克风权限才能使用语音输入", isError = true)
        }
    }

    val voiceController = rememberVoiceInputController(
        context = context,
        onPartial = { voicePartial = it },
        onResult = { candidates ->
            voiceListening = false
            voicePartial = ""
            viewModel.finalizeVoiceInput(candidates)
        },
        onError = { msg ->
            voiceListening = false
            voicePartial = ""
            viewModel.notifyHint(msg, isError = true)
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when {
                            voiceCancelling -> "松开手指取消输入"
                            state.voiceRefining -> "正在优化语音识别与标点..."
                            else -> "正在聆听 · 上滑取消 · 松手填入输入框"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                    if (voicePartial.isNotBlank() && !voiceCancelling) {
                        Text(
                            text = voicePartial,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.92f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else if (state.toolActivities.isNotEmpty() && state.streaming) {
            val latestTool = state.toolActivities.last()
            Surface(
                color = PacePalette.PrimarySoft,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "⚙ ${latestTool.name}：${latestTool.preview ?: latestTool.status}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.Primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Surface(
            color = PacePalette.Card,
            border = BorderStroke(
                width = 1.dp,
                color = if (voiceListening) PacePalette.Primary else PacePalette.CardBorder,
            ),
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp, max = 120.dp)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.draft.isEmpty()) {
                        Text(
                            "发消息或长按右侧说话...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PacePalette.TextSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = state.draft,
                        onValueChange = viewModel::setDraft,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PacePalette.TextPrimary),
                        cursorBrush = SolidColor(PacePalette.Primary),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Always-present Mic button immediately left of Send/Stop (Doubao style)
                Surface(
                    color = when {
                        voiceCancelling -> MaterialTheme.colorScheme.errorContainer
                        voiceListening -> PacePalette.Primary
                        else -> PacePalette.Muted
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .size(38.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val quickRelease = withTimeoutOrNull(160L) {
                                    waitForUpOrCancellation()
                                }
                                if (quickRelease != null) {
                                    viewModel.notifyHint("长按麦克风说话，松手转写到输入框")
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
                                voicePartial = ""
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
                                voiceController.finish(cancel = shouldCancel)
                                if (shouldCancel) {
                                    voicePartial = ""
                                }
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MicIcon,
                            contentDescription = "长按语音输入",
                            tint = when {
                                voiceCancelling -> MaterialTheme.colorScheme.error
                                voiceListening -> Color.White
                                else -> PacePalette.Primary
                            },
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                if (state.streaming) {
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
                } else {
                    val canSend = state.draft.isNotBlank() && !state.voiceRefining
                    Surface(
                        onClick = { if (canSend) viewModel.sendMessage() },
                        color = if (canSend) PacePalette.Primary else PacePalette.Muted,
                        shape = CircleShape,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "↑",
                                color = if (canSend) Color.White else PacePalette.TextSecondary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    onPinCard: (com.dutu007.paceapp.data.PaceCardData) -> Unit,
    onSaveText: (String) -> Unit,
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = PacePalette.Primary,
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
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
                    message.content.isNotBlank() -> {
                        RichMessageBody(
                            rawText = message.content,
                            textColor = PacePalette.TextPrimary,
                            onPinCard = onPinCard,
                        )
                    }
                    message.isStreaming -> {
                        Text("正在思考...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
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
                TextButton(
                    onClick = { onSaveText(message.content) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("存为记录", style = MaterialTheme.typography.labelSmall, color = PacePalette.Primary) }
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
