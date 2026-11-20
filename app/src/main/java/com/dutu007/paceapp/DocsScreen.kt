package com.dutu007.paceapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dutu007.paceapp.data.DocDetail
import com.dutu007.paceapp.data.DocSummary
import com.dutu007.paceapp.ui.theme.PacePalette

@Composable
fun DocsScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
) {
    var viewMode by remember { mutableStateOf("graph") }
    var previewDocTarget by remember { mutableStateOf<InAppPreviewTarget?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newNoteName by remember { mutableStateOf("") }
    var newNoteContent by remember { mutableStateOf("") }

    fun openSmartDoc(path: String, title: String, mime: String = "") {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("md", "json", "txt", "csv")) {
            previewDocTarget = InAppPreviewTarget.ServerDoc(
                title = title.ifBlank { path.substringAfterLast('/') },
                docPath = path,
                mimeType = mime.ifBlank { mimeTypeForFileName(path) },
            )
        } else {
            viewModel.openDoc(path)
        }
    }

    val filtered = state.docs.filter { doc ->
        val q = state.docSearch.trim().lowercase()
        q.isEmpty() || doc.title.lowercase().contains(q) || doc.path.lowercase().contains(q) || doc.preview.lowercase().contains(q)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("graph" to "知识图谱", "list" to "文档列表").forEach { (mode, label) ->
                val selected = viewMode == mode
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) PacePalette.Primary else PacePalette.Card,
                    border = BorderStroke(1.dp, if (selected) PacePalette.Primary else PacePalette.CardBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewMode = mode },
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color.White else PacePalette.TextSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (viewMode == "graph") {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.docSearch,
                    onValueChange = viewModel::setDocSearch,
                    placeholder = { Text("高亮/搜索知识图谱节点、文档、标签...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                KnowledgeGraphExplorer(
                    graph = state.graph,
                    loading = state.graphLoading,
                    searchQuery = state.docSearch,
                    onRefresh = {
                        viewModel.refreshDocs()
                        viewModel.refreshGraph()
                    },
                    onOpenDoc = { path, title -> openSmartDoc(path, title) },
                    onAskHermes = { prompt -> viewModel.askHermesAboutPrompt(prompt) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.docSearch,
                        onValueChange = viewModel::setDocSearch,
                        placeholder = { Text("搜索服务器笔记、日记、记忆...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "all" to "全部",
                            "note" to "笔记",
                            "diary" to "日记",
                            "upload" to "文档附件",
                            "memory" to "Hermes记忆",
                            "data" to "数据文件",
                        ).forEach { (cat, label) ->
                            FilterChip(
                                selected = state.docFilterCategory == cat,
                                onClick = { viewModel.setDocFilterCategory(cat) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            when {
                state.docsLoading && state.docs.isEmpty() -> {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                filtered.isEmpty() -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = PacePalette.Card,
                            border = BorderStroke(1.dp, PacePalette.CardBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("没有匹配的文档", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "这里实时同步服务器 personal-assistant/data 中的笔记、日记和 Hermes 记忆。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PacePalette.TextSecondary,
                                )
                            }
                        }
                    }
                }
                else -> {
                    items(filtered, key = { it.path }) { doc ->
                        DocRowCard(doc = doc, onClick = { openSmartDoc(doc.path, doc.title, doc.mimeType) })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = PacePalette.Primary,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) {
            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        }
        }
    }

    previewDocTarget?.let { target ->
        InAppDocumentViewerDialog(
            target = target,
            viewModel = viewModel,
            onDismiss = { previewDocTarget = null },
            onAskHermes = { path ->
                previewDocTarget = null
                viewModel.askHermesAboutDoc(path)
            },
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建 Markdown 笔记") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newNoteName,
                        onValueChange = { newNoteName = it },
                        label = { Text("文件名（将存于 notes/）") },
                        placeholder = { Text("例如：周计划") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newNoteContent,
                        onValueChange = { newNoteContent = it },
                        label = { Text("正文") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val safeName = newNoteName.trim().removeSuffix(".md")
                        if (safeName.isNotEmpty()) {
                            val path = "notes/$safeName.md"
                            val body = newNoteContent.ifBlank { "# $safeName\n\n" }
                            viewModel.saveDoc(path, body) {
                                newNoteName = ""
                                newNoteContent = ""
                                showCreateDialog = false
                            }
                        }
                    },
                    enabled = newNoteName.isNotBlank() && !state.docBusy,
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DocRowCard(doc: DocSummary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (badgeLabel, badgeBg, badgeFg) = when (doc.category) {
                    "diary" -> Triple("日记", PacePalette.SuccessSoft, PacePalette.Success)
                    "memory" -> Triple("记忆", PacePalette.AccentAmberSoft, PacePalette.AccentAmber)
                    "upload" -> {
                        val (fmt, col) = fileTypeBadge(doc.path)
                        Triple(fmt, col.copy(alpha = 0.14f), col)
                    }
                    "data" -> Triple("数据", PacePalette.Muted, PacePalette.TextSecondary)
                    else -> Triple("笔记", PacePalette.PrimarySoft, PacePalette.Primary)
                }
                Surface(color = badgeBg, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("›", fontSize = 22.sp, color = PacePalette.TextSecondary)
            }
            if (doc.preview.isNotBlank()) {
                Text(
                    doc.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${doc.path} · ${doc.updatedAt.replace('T', ' ').take(16)}",
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary.copy(alpha = 0.75f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailOverlay(
    doc: DocDetail,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onAskHermes: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var editing by remember(doc.path) { mutableStateOf(false) }
    var editBuffer by remember(doc.path, doc.content) { mutableStateOf(doc.content) }

    Box(Modifier.fillMaxSize().background(PacePalette.Background)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PacePalette.Card),
                    navigationIcon = {
                        AppBackButton(onClick = onBack)
                    },
                    title = {
                        Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    },
                    actions = {
                        if (doc.editable) {
                            if (editing) {
                                TextButton(
                                    onClick = {
                                        onSave(editBuffer)
                                        editing = false
                                    },
                                    enabled = !busy,
                                ) { Text("保存") }
                            } else {
                                TextButton(onClick = { editing = true }) { Text("编辑") }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        doc.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onAskHermes,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("让 Hermes 整理展示", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (editing) {
                    OutlinedTextField(
                        value = editBuffer,
                        onValueChange = { editBuffer = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = PacePalette.Card,
                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            ) {
                                MarkdownBlock(doc.content)
                            }
                        }
                    }
                }
            }
        }
    }
}
