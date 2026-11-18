package com.dutu007.paceapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dutu007.paceapp.data.ApprovalRequest
import com.dutu007.paceapp.data.ChatMessage
import com.dutu007.paceapp.data.SessionItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sessionToManage by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val activeSession = state.sessions.firstOrNull { it.id == state.activeSessionId }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 310.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Hermes 对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val statusText = when (state.hermesOnline) {
                                true -> "在线"
                                false -> "离线"
                                null -> "检测中..."
                            }
                            Text(
                                "${state.user?.displayName ?: "Dutu"} · $statusText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.startNewSession()
                                scope.launch { drawerState.close() }
                            },
                            enabled = !state.streaming,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("新对话")
                        }
                    }

                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            NavigationDrawerItem(
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            session.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = {
                                                renameText = session.title
                                                sessionToManage = session
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                        ) {
                                            Text("···")
                                        }
                                    }
                                },
                                selected = session.id == state.activeSessionId,
                                onClick = {
                                    viewModel.selectSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = {
                            scope.launch { drawerState.close() }
                            showSettings = true
                        }) {
                            Text("设置")
                        }
                        TextButton(onClick = viewModel::signOut) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dotColor = when (state.hermesOnline) {
                                true -> Color(0xFF1E8E3E)
                                false -> MaterialTheme.colorScheme.error
                                null -> Color(0xFF9AA0A6)
                            }
                            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                            Text(
                                activeSession?.title ?: "Hermes",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refreshAll, enabled = !state.streaming) {
                            Icon(RefreshIcon, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = viewModel::startNewSession, enabled = !state.streaming) {
                            Text("新建")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(SettingsIcon, contentDescription = "设置", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (state.toolActivities.isNotEmpty() && state.streaming) {
                            val latestTool = state.toolActivities.last()
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            ) {
                                Text(
                                    text = "${latestTool.name}: ${latestTool.preview ?: latestTool.status}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                placeholder = { Text("给 Hermes 发消息...") },
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
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                val notice = state.notice
                if (notice != null) {
                    Surface(
                        color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable { viewModel.dismissNotice(notice.id) },
                    ) {
                        Text(notice.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                val approval = state.pendingApproval
                if (approval != null) {
                    ApprovalCard(approval = approval, onChoice = viewModel::respondApproval)
                }

                when {
                    state.messagesLoading && state.messages.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.messages.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("随时开始对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "直接输入一句话，即可通过服务器上的 Hermes 进行对话与工具调用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.messages, key = { it.id }) { msg ->
                                MessageBubble(msg)
                            }
                        }
                    }
                }
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
                }) {
                    Text("保存标题")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToManage = null }) { Text("取消") }
            },
        )
    }

    if (showSettings) {
        SettingsScreen(
            state = state,
            viewModel = viewModel,
            onBack = { showSettings = false },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                isTool -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                isUser -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isTool && !message.toolName.isNullOrBlank()) {
                        Text(
                            "工具：${message.toolName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    val display = when {
                        message.content.isNotBlank() -> message.content
                        message.isStreaming -> "思考中..."
                        else -> ""
                    }
                    if (display.isNotBlank()) {
                        Text(display, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalRequest, onChoice: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hermes 请求操作授权", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (approval.description.isNotBlank()) {
                Text(approval.description, style = MaterialTheme.typography.bodySmall)
            }
            if (approval.command.isNotBlank()) {
                Text(approval.command, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("once" in approval.choices) {
                    Button(onClick = { onChoice("once") }) { Text("允许一次") }
                }
                if ("session" in approval.choices) {
                    OutlinedButton(onClick = { onChoice("session") }) { Text("本会话允许") }
                }
                OutlinedButton(onClick = { onChoice("deny") }) { Text("拒绝") }
            }
        }
    }
}
