package com.dutu007.paceapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dutu007.paceapp.data.MessageSegment
import com.dutu007.paceapp.data.PaceCardData
import com.dutu007.paceapp.data.PaceCardParser
import com.dutu007.paceapp.ui.theme.PacePalette

@Composable
fun RichMessageBody(
    rawText: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onPinCard: ((PaceCardData) -> Unit)? = null,
) {
    val segments = PaceCardParser.splitMessage(rawText)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.Markdown -> MarkdownBlock(seg.text, textColor)
                is MessageSegment.Card -> PaceCardView(
                    card = seg.card,
                    actionLabel = if (onPinCard != null) "固定到看板" else null,
                    onAction = onPinCard?.let { cb -> { cb(seg.card) } },
                )
            }
        }
    }
}

@Composable
fun MarkdownBlock(
    markdown: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val lines = markdown.replace("\r\n", "\n").lines()
    var i = 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim()
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        codeLines += lines[i]
                        i++
                    }
                    CodeBlockCard(lang = lang, code = codeLines.joinToString("\n"))
                }
                trimmed.isEmpty() -> {
                    Spacer(Modifier.height(2.dp))
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("### ")),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("## ")),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("# ")),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                }
                trimmed.startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PacePalette.Muted.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .background(PacePalette.Primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(trimmed.removePrefix("> ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PacePalette.TextSecondary,
                        )
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "• ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = PacePalette.Primary,
                        )
                        Text(
                            text = parseInlineMarkdown(trimmed.drop(2)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                    val dot = trimmed.indexOf('.')
                    val prefix = trimmed.substring(0, dot + 1)
                    val body = trimmed.substring(dot + 1).trimStart()
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "$prefix ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PacePalette.Primary,
                        )
                        Text(
                            text = parseInlineMarkdown(body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                trimmed == "---" || trimmed == "***" -> {
                    HorizontalDivider(color = PacePalette.CardBorder)
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
            i++
        }
    }
}

@Composable
private fun CodeBlockCard(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PacePalette.CodeBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.CodeText.copy(alpha = 0.65f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("复制", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7DD3FC))
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PacePalette.CodeText,
                )
            }
        }
    }
}

@Composable
fun PaceCardView(
    card: PaceCardData,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = PacePalette.PrimarySoft,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            val badge = when (card.type) {
                                "checklist" -> "清单"
                                "stats" -> "统计"
                                "table" -> "表格"
                                else -> "卡片"
                            }
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = PacePalette.Primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    if (card.subtitle.isNotBlank()) {
                        Text(
                            card.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = PacePalette.TextSecondary,
                        )
                    }
                }
            }

            if (card.metrics.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    card.metrics.forEach { m ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PacePalette.Background,
                            border = BorderStroke(1.dp, PacePalette.CardBorder),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(m.label, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(m.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PacePalette.Primary)
                                    if (m.unit.isNotBlank()) {
                                        Text(m.unit, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (card.items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PacePalette.Background)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (item.done) "☑" else "☐",
                                color = if (item.done) PacePalette.Success else PacePalette.Primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (item.done) PacePalette.TextSecondary else PacePalette.TextPrimary,
                                )
                                if (item.detail.isNotBlank()) {
                                    Text(
                                        item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PacePalette.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (card.columns.isNotEmpty() || card.rows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(10.dp))
                        .background(PacePalette.Background)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (card.columns.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            card.columns.forEach { col ->
                                Text(
                                    col,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PacePalette.Primary,
                                    modifier = Modifier.width(100.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = PacePalette.CardBorder)
                    }
                    card.rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { cell ->
                                Text(
                                    cell,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PacePalette.TextPrimary,
                                    modifier = Modifier.width(100.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (card.content.isNotBlank()) {
                MarkdownBlock(card.content)
            }

            if (actionLabel != null || secondaryActionLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        TextButton(onClick = onSecondaryAction, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(secondaryActionLabel, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(actionLabel, color = PacePalette.Primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun parseInlineMarkdown(input: String): AnnotatedString = buildAnnotatedString {
    var idx = 0
    while (idx < input.length) {
        when {
            input.startsWith("**", idx) -> {
                val end = input.indexOf("**", idx + 2)
                if (end > idx + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(input.substring(idx + 2, end))
                    }
                    idx = end + 2
                } else {
                    append("**")
                    idx += 2
                }
            }
            input[idx] == '`' -> {
                val end = input.indexOf('`', idx + 1)
                if (end > idx + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = PacePalette.Muted,
                            color = PacePalette.Primary,
                        ),
                    ) {
                        append(" ${input.substring(idx + 1, end)} ")
                    }
                    idx = end + 1
                } else {
                    append('`')
                    idx++
                }
            }
            else -> {
                append(input[idx])
                idx++
            }
        }
    }
}
