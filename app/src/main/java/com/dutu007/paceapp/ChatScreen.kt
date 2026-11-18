package com.dutu007.paceapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
) {
    val listState = rememberLazyListState()
    var showSessionSheet by remember { mutableStateOf(false) }
    var sessionToManage by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    val activeSession = state.sessions.firstOrNull { it.id == state.activeSessionId }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sleek sub-header for switching/creating conversations
        Surface(
            color = PacePalette.Card,
            border = BorderStroke(1.dp, PacePalette.CardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).clickable { showSessionSheet = true }.padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "${activeSession?.title ?: "新对话"}  ▾",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (activeSession != null) "共 ${activeSession.messageCount} 条消息 · 点击切换会话" else "点击选择已有会话或直接发送开始",
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
                TextButton(
                    onClick = viewModel::startNewSession,
                    enabled = !state.streaming,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("+ 新对话")
                }
            }
        }

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
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = PacePalette.Card,
                            border = BorderStroke(1.dp, PacePalette.CardBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("你好，我是 Hermes", style = MaterialTheme.typography.titleMedium, color = PacePalette.Primary)
                                Text(
                                    "你可以直接让我记录一句话到数据库/日记，也可以让我把服务器上的笔记、日记、跑步记录按要求整理成卡片展示给你。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PacePalette.TextSecondary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("快捷试试：", style = MaterialTheme.typography.labelMedium, color = PacePalette.TextSecondary)
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                onPinCard = viewModel::pinCardToBoard,
                                onSaveText = viewModel::saveTextAsEntry,
                            )
                        }
                    }
                }
            }
        }

        // Composer bar
        Surface(
            color = PacePalette.Card,
            border = BorderStroke(1.dp, PacePalette.CardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (state.toolActivities.isNotEmpty() && state.streaming) {
                    val latestTool = state.toolActivities.last()
                    Surface(
                        color = PacePalette.PrimarySoft,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::setDraft,
                        placeholder = { Text("给 Hermes 发消息或下达展示指令...") },
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f),
                    )
                    if (state.streaming) {
                        OutlinedButton(
                            onClick = viewModel::stopActiveRun,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = viewModel::sendMessage,
                            enabled = state.draft.isNotBlank(),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }

    if (showSessionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSessionSheet = false },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.sessionFilterPaceOnly,
                            onClick = viewModel::toggleSessionFilter,
                            label = { Text(if (state.sessionFilterPaceOnly) "仅 Pace" else "全部来源") },
                        )
                        Button(
                            onClick = {
                                viewModel.startNewSession()
                                showSessionSheet = false
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("新建") }
                    }
                }

                LazyColumn(
                    modifier = Modifier.height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        val selected = session.id == state.activeSessionId
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) PacePalette.PrimarySoft else PacePalette.Background,
                            border = BorderStroke(1.dp, if (selected) PacePalette.Primary else PacePalette.CardBorder),
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.selectSession(session.id)
                                showSessionSheet = false
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
private fun MessageBubble(
    message: ChatMessage,
    onPinCard: (com.dutu007.paceapp.data.PaceCardData) -> Unit,
    onSaveText: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 350.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = when {
                    isUser -> PacePalette.Primary
                    isTool -> PacePalette.Muted
                    else -> PacePalette.Card
                },
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else PacePalette.TextPrimary,
                border = if (isUser) null else BorderStroke(1.dp, PacePalette.CardBorder),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp,
                ),
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        if (isTool && !message.toolName.isNullOrBlank()) {
                            Text(
                                "工具：${message.toolName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = PacePalette.Primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        when {
                            message.content.isNotBlank() && isUser -> {
                                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                            }
                            message.content.isNotBlank() -> {
                                RichMessageBody(
                                    rawText = message.content,
                                    textColor = PacePalette.TextPrimary,
                                    onPinCard = onPinCard,
                                )
                            }
                            message.isStreaming -> {
                                Text("Hermes 正在思考...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                            }
                        }
                    }
                }
            }

            if (!isUser && !isTool && !message.isStreaming && message.content.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
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
