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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dutu007.paceapp.data.EntryItem
import com.dutu007.paceapp.ui.theme.PacePalette

@Composable
fun EntriesScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
) {
    val pinnedCards = state.entries.filter { it.pinned && it.card != null }
    val normalEntries = state.entries.filterNot { it.pinned && it.card != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            QuickCaptureCard(state = state, viewModel = viewModel)
        }

        if (pinnedCards.isNotEmpty()) {
            item {
                Text(
                    "已固定展示看板",
                    style = MaterialTheme.typography.titleSmall,
                    color = PacePalette.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(pinnedCards, key = { "pin_${it.id}" }) { item ->
                item.card?.let { card ->
                    PaceCardView(
                        card = card,
                        actionLabel = "取消固定",
                        onAction = { viewModel.toggleEntryPinned(item) },
                        secondaryActionLabel = "删除",
                        onSecondaryAction = { viewModel.deleteEntry(item.id) },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.entrySearch,
                    onValueChange = viewModel::setEntrySearch,
                    placeholder = { Text("搜索记录内容...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val kinds = listOf(
                        "all" to "全部",
                        "note" to "随手记",
                        "todo" to "待办",
                        "diary" to "日记",
                        "idea" to "灵感",
                    )
                    kinds.forEach { (key, label) ->
                        FilterChip(
                            selected = state.entryFilterKind == key,
                            onClick = { viewModel.setEntryFilterKind(key) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        when {
            state.entriesLoading && state.entries.isEmpty() -> {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            normalEntries.isEmpty() -> {
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
                            Text("还没有记录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "在上方写一句话立即存入数据库，并可一键同步写入日记或笔记文档。",
                                style = MaterialTheme.typography.bodySmall,
                                color = PacePalette.TextSecondary,
                            )
                        }
                    }
                }
            }
            else -> {
                items(normalEntries, key = { it.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        onToggleComplete = { viewModel.toggleEntryCompleted(entry) },
                        onTogglePin = { viewModel.toggleEntryPinned(entry) },
                        onOpenDoc = { path -> viewModel.openDoc(path) },
                        onDelete = { viewModel.deleteEntry(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCaptureCard(state: PaceUiState, viewModel: PaceViewModel) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("写一句话记下来", style = MaterialTheme.typography.titleSmall, color = PacePalette.Primary)
            OutlinedTextField(
                value = state.captureText,
                onValueChange = viewModel::setCaptureText,
                placeholder = { Text("例如：今天慢跑 5 公里，状态不错；或者记一个待办...") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.captureTags,
                onValueChange = viewModel::setCaptureTags,
                placeholder = { Text("标签（空格或逗号分隔，如：跑步 科研）") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("note" to "随手记", "todo" to "待办", "diary" to "日记", "idea" to "灵感").forEach { (k, label) ->
                    FilterChip(
                        selected = state.captureKind == k,
                        onClick = { viewModel.setCaptureKind(k) },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("diary" to "同步今日日记", "note" to "同步随手记.md", "" to "仅存数据库").forEach { (mode, label) ->
                        FilterChip(
                            selected = state.captureSyncDoc == mode,
                            onClick = { viewModel.setCaptureSyncDoc(mode) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Button(
                    onClick = viewModel::submitQuickCapture,
                    enabled = state.captureText.isNotBlank() && !state.captureBusy,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (state.captureBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: EntryItem,
    onToggleComplete: () -> Unit,
    onTogglePin: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (kindLabel, badgeBg, badgeFg) = when (entry.kind) {
                    "todo" -> Triple("待办", PacePalette.AccentAmberSoft, PacePalette.AccentAmber)
                    "diary" -> Triple("日记", PacePalette.SuccessSoft, PacePalette.Success)
                    "idea" -> Triple("灵感", Color(0xFFEDE9FE), Color(0xFF6D28D9))
                    "card" -> Triple("卡片", PacePalette.PrimarySoft, PacePalette.Primary)
                    else -> Triple("随手记", PacePalette.PrimarySoft, PacePalette.Primary)
                }
                Surface(color = badgeBg, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (entry.source == "hermes") {
                    Surface(color = PacePalette.Muted, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "Hermes",
                            style = MaterialTheme.typography.labelSmall,
                            color = PacePalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    entry.createdAt.replace('T', ' ').take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.TextSecondary,
                )
            }

            if (entry.title.isNotBlank()) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.kind == "todo") {
                    Text(
                        text = if (entry.completed) "☑" else "☐",
                        color = if (entry.completed) PacePalette.Success else PacePalette.Primary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable(onClick = onToggleComplete),
                    )
                }
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (entry.kind == "todo" && entry.completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (entry.kind == "todo" && entry.completed) PacePalette.TextSecondary else PacePalette.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            if (entry.card != null) {
                PaceCardView(card = entry.card)
            }

            if (entry.tags.isNotEmpty() || !entry.docPath.isNullOrBlank()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entry.tags.forEach { tag ->
                        Surface(color = PacePalette.Muted, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = PacePalette.TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    entry.docPath?.takeIf { it.isNotBlank() }?.let { path ->
                        Surface(
                            color = PacePalette.PrimarySoft,
                            shape = RoundedCornerShape(8.dp),
                            onClick = { onOpenDoc(path) },
                        ) {
                            Text(
                                "📄 $path",
                                style = MaterialTheme.typography.labelSmall,
                                color = PacePalette.Primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onTogglePin, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (entry.pinned) "取消置顶" else "置顶", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
