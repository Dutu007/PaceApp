package com.dutu007.paceapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dutu007.paceapp.data.AnalyticsOverview
import com.dutu007.paceapp.data.CustomMetricSummary
import com.dutu007.paceapp.data.DailyMetricPoint
import com.dutu007.paceapp.data.LabAnalytics
import com.dutu007.paceapp.data.LabSessionItem
import com.dutu007.paceapp.data.RunningAnalytics
import com.dutu007.paceapp.data.SleepAnalytics
import com.dutu007.paceapp.data.SleepDailyPoint
import com.dutu007.paceapp.data.SleepRecordItem
import com.dutu007.paceapp.ui.theme.PacePalette
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val SleepAccent = Color(0xFF5B5CE2)
private val SleepAccentSoft = Color(0xFFECECFF)
private val RunningAccent = Color(0xFF059669)
private val RunningAccentSoft = Color(0xFFD1FAE5)
private val ZoneShanghai: ZoneId = ZoneId.of("Asia/Shanghai")
private val DateTimeMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatDurationMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val rem = safe % 60
    return when {
        hours == 0 -> "${rem}m"
        rem == 0 -> "${hours}h"
        else -> "${hours}h ${rem}m"
    }
}

private fun formatClockSlice(dateTime: String?): String {
    val clean = dateTime?.trim().orEmpty()
    if (clean.isBlank()) return "--:--"
    val spaceIdx = clean.indexOf(' ')
    return if (spaceIdx in 0 until clean.lastIndex) clean.substring(spaceIdx + 1) else clean
}

private fun computeLiveElapsedMinutes(arrive: String, fallback: Int): Int {
    return runCatching {
        val start = LocalDateTime.parse(arrive.trim(), DateTimeMinuteFormatter)
        val now = LocalDateTime.now(ZoneShanghai)
        val diff = ChronoUnit.MINUTES.between(start, now).toInt()
        maxOf(fallback, diff.coerceAtLeast(0))
    }.getOrDefault(fallback.coerceAtLeast(0))
}

private fun parseColorHex(hex: String, fallback: Color = PacePalette.Primary): Color {
    return runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrDefault(fallback)
}

private fun normalizeLabTimestamp(dateStr: String, raw: String): String {
    val clean = raw.trim()
    return if (clean.contains(" ")) clean else "${dateStr.trim()} $clean"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
) {
    val dashboard = state.analytics
    var showLabSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showSpecDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<LabSessionItem?>(null) }
    var previewTarget by remember { mutableStateOf<InAppPreviewTarget?>(null) }
    var liveTick by remember { mutableIntStateOf(0) }

    val activeArrive = dashboard?.lab?.activeSession?.arrive
    LaunchedEffect(activeArrive) {
        if (activeArrive != null) {
            while (true) {
                delay(30_000L)
                liveTick++
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("7d" to "近 7 天", "30d" to "近 30 天", "all" to "全部").forEach { (key, label) ->
                        FilterChip(
                            selected = state.analyticsRange == key,
                            onClick = { viewModel.setAnalyticsRange(key) },
                            label = { Text(label) },
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PacePalette.Card,
                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                        onClick = { showSpecDialog = true },
                    ) {
                        Text(
                            text = "数据规范",
                            style = MaterialTheme.typography.labelMedium,
                            color = PacePalette.Primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                if (state.analyticsLoading && dashboard != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }

        if (dashboard == null) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = PacePalette.Card,
                    border = BorderStroke(1.dp, PacePalette.CardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.analyticsLoading) {
                            CircularProgressIndicator()
                            Text("正在汇总个人数据...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                        } else {
                            Text("暂未获取到数据分析结果", style = MaterialTheme.typography.titleSmall, color = PacePalette.TextPrimary)
                            Button(onClick = { viewModel.refreshAnalytics() }) {
                                Text("重新加载")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                LabHeroCard(
                    lab = dashboard.lab,
                    busy = state.analyticsBusy,
                    liveTick = liveTick,
                    onCheckIn = { viewModel.labCheckIn() },
                    onCheckOut = { viewModel.labCheckOut() },
                    onOpenManualEntry = { showLabSheet = true },
                )
            }

            item {
                OverviewKpiGrid(overview = dashboard.overview, lab = dashboard.lab)
            }

            item {
                LabAnalyticsCard(
                    lab = dashboard.lab,
                    busy = state.analyticsBusy,
                    onAskHermes = {
                        viewModel.askHermesAboutPrompt(
                            "请读取 data/lab/sessions.json，分析我最近在实验室的学习时段分布、专注时长稳定性，并给出可执行建议。",
                        )
                    },
                    onDeleteRequest = { sessionToDelete = it },
                )
            }

            item {
                SleepAnalyticsCard(
                    sleep = dashboard.sleep,
                    onAddSleep = { showSleepSheet = true },
                    onOpenSpec = { showSpecDialog = true },
                )
            }

            item {
                RunningAnalyticsCard(
                    running = dashboard.running,
                    onOpenImage = { path ->
                        previewTarget = InAppPreviewTarget.ServerDoc(
                            title = path.substringAfterLast('/'),
                            docPath = path,
                            mimeType = mimeTypeForFileName(path, fallback = "image/jpeg"),
                        )
                    },
                )
            }

            items(dashboard.customMetrics, key = { it.id }) { metric ->
                CustomMetricCard(metric = metric)
            }
        }
    }

    if (showLabSheet) {
        LabManualEntrySheet(
            busy = state.analyticsBusy,
            onDismiss = { showLabSheet = false },
            onSubmit = { date, arrive, leave, note ->
                viewModel.addManualLabSession(
                    date = date,
                    arrive = normalizeLabTimestamp(date, arrive),
                    leave = normalizeLabTimestamp(date, leave),
                    note = note,
                )
                showLabSheet = false
            },
        )
    }

    if (showSleepSheet) {
        SleepManualEntrySheet(
            busy = state.analyticsBusy,
            onDismiss = { showSleepSheet = false },
            onSubmit = { date, bedtime, wakeTime, score, note ->
                viewModel.saveManualSleepRecord(
                    date = date,
                    bedtime = bedtime,
                    wakeTime = wakeTime,
                    score = score,
                    note = note,
                )
                showSleepSheet = false
            },
        )
    }

    if (showSpecDialog) {
        AnalyticsSpecDialog(onDismiss = { showSpecDialog = false })
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("删除该学习时段？") },
            text = {
                val rangeLabel = "${session.date} ${formatClockSlice(session.arrive)} - ${
                    if (session.leave.isNullOrBlank()) "进行中" else formatClockSlice(session.leave)
                }"
                Text("将从 lab/sessions.json 移除「$rangeLabel」，操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLabSession(session.arrive)
                        sessionToDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("取消") }
            },
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
}

@Composable
private fun LabHeroCard(
    lab: LabAnalytics,
    busy: Boolean,
    liveTick: Int,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    onOpenManualEntry: () -> Unit,
) {
    val active = lab.activeSession
    val elapsedMinutes = remember(active?.arrive, lab.activeElapsedMinutes, liveTick) {
        if (active != null) computeLiveElapsedMinutes(active.arrive, lab.activeElapsedMinutes) else 0
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (active != null) Color(0xFF0B3F4F) else PacePalette.Card,
        border = BorderStroke(1.dp, if (active != null) Color(0xFF0E5A6F) else PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (active != null) Color(0xFF34D399) else PacePalette.TextSecondary.copy(alpha = 0.45f)),
                    )
                    Text(
                        text = if (active != null) "实验室学习中" else "实验室学习打卡",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active != null) Color.White else PacePalette.TextPrimary,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active != null) Color.White.copy(alpha = 0.16f) else PacePalette.PrimarySoft,
                ) {
                    Text(
                        text = if (active != null) "${formatClockSlice(active.arrive)} 到达" else "今日 ${formatDurationMinutes(lab.todayMinutes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active != null) Color.White else PacePalette.Primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (active != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = formatDurationMinutes(elapsedMinutes),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = buildString {
                            append("本次进行中")
                            if (active.note.isNotBlank()) append(" · ${active.note}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }
            } else {
                Text(
                    text = "单源同步 data/lab/sessions.json，对话告知 Hermes 到达/离开也会自动累计到此处。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (active != null) {
                    Button(
                        onClick = onCheckOut,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = PacePalette.Primary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "提交中..." else "离开签退", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenManualEntry,
                        enabled = !busy,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("补录")
                    }
                } else {
                    Button(
                        onClick = onCheckIn,
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "提交中..." else "到达实验室打卡", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenManualEntry,
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("补录时段")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewKpiGrid(
    overview: AnalyticsOverview,
    lab: LabAnalytics,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KpiTile(
                title = "今日实验室",
                value = formatDurationMinutes(overview.todayLabMinutes),
                subtitle = "本周 ${formatDurationMinutes(overview.weekLabMinutes)}",
                accentColor = PacePalette.Primary,
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                title = "实验室累计",
                value = formatDurationMinutes(overview.totalLabMinutes),
                subtitle = "连续 ${overview.labStreakDays} 天 · ${lab.sessionCount} 次",
                accentColor = PacePalette.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KpiTile(
                title = "近期睡眠均值",
                value = if (overview.sleepHasData) formatDurationMinutes(overview.avgSleepMinutes) else "待接入",
                subtitle = if (overview.sleepHasData) {
                    "最近一晚 ${formatDurationMinutes(overview.latestSleepMinutes)}"
                } else {
                    "预留手表 / 手动"
                },
                accentColor = SleepAccent,
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                title = "跑步打卡",
                value = "${overview.runningTotalCount} 次",
                subtitle = "近 30 天 ${overview.runningCount30d} 次",
                accentColor = RunningAccent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KpiTile(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = PacePalette.TextSecondary)
            Text(
                text = value,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LabAnalyticsCard(
    lab: LabAnalytics,
    busy: Boolean,
    onAskHermes: () -> Unit,
    onDeleteRequest: (LabSessionItem) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("实验室学习趋势", style = MaterialTheme.typography.titleMedium, color = PacePalette.TextPrimary)
                    Text(
                        "有记录日均 ${formatDurationMinutes(lab.avgDailyMinutes)} · 近 30 天 ${formatDurationMinutes(lab.monthMinutes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
                TextButton(onClick = onAskHermes, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("让 Hermes 分析", fontSize = 12.sp)
                }
            }

            DailyBarChart(
                points = lab.dailySeries,
                barColor = PacePalette.Primary,
                valueFormatter = { v -> formatDurationMinutes(v.toInt()) },
                emptyHint = "暂无实验室打卡记录",
            )

            if (lab.recentSessions.isNotEmpty()) {
                HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                Text("最近学习时段", style = MaterialTheme.typography.labelMedium, color = PacePalette.TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lab.recentSessions.take(8).forEach { item ->
                        val ongoing = item.leave.isNullOrBlank()
                        val mins = item.minutes ?: computeLiveElapsedMinutes(item.arrive, lab.activeElapsedMinutes)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PacePalette.Background)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${item.date} · ${formatClockSlice(item.arrive)} - ${
                                        if (ongoing) "进行中" else formatClockSlice(item.leave)
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PacePalette.TextPrimary,
                                )
                                if (item.note.isNotBlank()) {
                                    Text(
                                        text = item.note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PacePalette.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (ongoing) PacePalette.SuccessSoft else PacePalette.PrimarySoft,
                                ) {
                                    Text(
                                        text = formatDurationMinutes(mins),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (ongoing) PacePalette.Success else PacePalette.Primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Text(
                                    text = "删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PacePalette.TextSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !busy) { onDeleteRequest(item) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepAnalyticsCard(
    sleep: SleepAnalytics,
    onAddSleep: () -> Unit,
    onOpenSpec: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("睡眠监测", style = MaterialTheme.typography.titleMedium, color = PacePalette.TextPrimary)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (sleep.hasData) SleepAccentSoft else PacePalette.Muted,
                    ) {
                        Text(
                            text = if (sleep.hasData) "${sleep.recordCount} 晚" else "待接入数据源",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sleep.hasData) SleepAccent else PacePalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                TextButton(onClick = onAddSleep, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("+ 记一晚", fontSize = 12.sp, color = SleepAccent)
                }
            }

            if (!sleep.hasData) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PacePalette.Background,
                    border = BorderStroke(1.dp, PacePalette.CardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "尚未检测到 sleep/records.json（为保持目录整洁，未创建空白占位文件）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = PacePalette.TextPrimary,
                        )
                        Text(
                            "接入方式：\n1) 对 Hermes 说“昨晚 23:40 睡，今早 07:35 起”自动归档；\n2) 后续手表数据通过 Webhook / 导入写入同份 JSON（含深浅睡、REM 与睡眠评分）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = PacePalette.TextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAddSleep, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text("手动录入", fontSize = 12.sp)
                            }
                            TextButton(onClick = onOpenSpec) {
                                Text("查看字段规范", fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                val calendarNights = remember(sleep.dailySeries, sleep.recentRecords) {
                    buildCalendarSleepNights(sleep.dailySeries, sleep.recentRecords)
                }
                val recentNights = remember(sleep.recentRecords) {
                    buildRecentSleepNights(sleep.recentRecords, limit = 7)
                }
                val calendarActiveCount = remember(calendarNights) {
                    calendarNights.count { it.durationMinutes > 0 }
                }
                var useRecentMode by remember(sleep.dailySeries.size, sleep.recentRecords.size) {
                    mutableStateOf(calendarActiveCount < 3 && recentNights.size > 1)
                }
                val displayedNights = if (useRecentMode && recentNights.isNotEmpty()) {
                    recentNights
                } else {
                    calendarNights
                }
                val displayedActive = remember(displayedNights) {
                    displayedNights.filter { it.durationMinutes > 0 }
                }
                val kpiAvgDuration = remember(useRecentMode, displayedActive, sleep.avgDurationMinutes) {
                    if (useRecentMode && displayedActive.isNotEmpty()) {
                        displayedActive.map { it.durationMinutes }.average().roundToInt()
                    } else {
                        sleep.avgDurationMinutes
                    }
                }
                val kpiAvgScore = remember(useRecentMode, displayedActive, sleep.avgScore) {
                    if (useRecentMode) {
                        val scores = displayedActive.mapNotNull { it.score?.takeIf { s -> s > 0 } }
                        if (scores.isNotEmpty()) scores.average().roundToInt() else sleep.avgScore
                    } else {
                        sleep.avgScore
                    }
                }
                val kpiAvgBedtime = remember(displayedActive, sleep.recentRecords) {
                    val beds = displayedActive.mapNotNull { it.bedtimeHHMM.ifBlank { null } }
                        .ifEmpty { sleep.recentRecords.mapNotNull { extractClockHHMM(it.bedtime).ifBlank { null } } }
                    averageBedtimeClock(beds)
                }
                val kpiAvgWake = remember(displayedActive, sleep.recentRecords) {
                    val wakes = displayedActive.mapNotNull { it.wakeHHMM.ifBlank { null } }
                        .ifEmpty { sleep.recentRecords.mapNotNull { extractClockHHMM(it.wakeTime).ifBlank { null } } }
                    averageWakeClock(wakes)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SleepOverviewKpiTile(
                            label = if (useRecentMode) "近 ${displayedActive.size} 晚均睡" else "区间均睡",
                            value = formatDurationMinutes(kpiAvgDuration),
                            bg = SleepAccentSoft,
                            fg = SleepAccent,
                            modifier = Modifier.weight(1f),
                        )
                        val scoreColor = kpiAvgScore?.let { Color(sleepScoreColorInt(it)) } ?: PacePalette.AccentAmber
                        SleepOverviewKpiTile(
                            label = "平均睡眠分数",
                            value = kpiAvgScore?.let { "$it 分" } ?: "--",
                            bg = scoreColor.copy(alpha = 0.12f),
                            fg = scoreColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SleepOverviewKpiTile(
                            label = "平均入睡",
                            value = kpiAvgBedtime,
                            bg = PacePalette.Muted,
                            fg = PacePalette.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        SleepOverviewKpiTile(
                            label = "平均醒来",
                            value = kpiAvgWake,
                            bg = PacePalette.Muted,
                            fg = PacePalette.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (recentNights.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (useRecentMode) "按最近 7 次有记录夜晚排列" else "按所选日历区间排列",
                            style = MaterialTheme.typography.labelSmall,
                            color = PacePalette.TextSecondary,
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(PacePalette.Muted)
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(true to "近 7 晚", false to "按日历").forEach { (targetMode, label) ->
                                val selected = useRecentMode == targetMode
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) PacePalette.Card else Color.Transparent,
                                    modifier = Modifier.clickable { useRecentMode = targetMode },
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) SleepAccent else PacePalette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                SleepScheduleChart(
                    nights = displayedNights,
                    emptyHint = "区间内暂无睡眠记录",
                )

                HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val orderedRecent = remember(sleep.recentRecords) {
                        sleep.recentRecords.sortedByDescending { it.date }.take(10)
                    }
                    orderedRecent.forEach { rec ->
                        val bedClock = extractClockHHMM(rec.bedtime)
                        val wakeClock = extractClockHHMM(rec.wakeTime)
                        val weekdayLabel = chineseWeekday(rec.date)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PacePalette.Background)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = if (weekdayLabel.isNotBlank()) "${rec.date} ($weekdayLabel)" else rec.date,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PacePalette.TextPrimary,
                                    )
                                    if (bedClock.isNotBlank() || wakeClock.isNotBlank()) {
                                        Text(
                                            text = "${bedClock.ifBlank { "--:--" }} → ${wakeClock.ifBlank { "--:--" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                            color = SleepAccent,
                                        )
                                    }
                                }
                                val detailParts = buildList {
                                    rec.deepMinutes?.let { add("深睡 ${formatDurationMinutes(it)}") }
                                    rec.lightMinutes?.let { add("浅睡 ${formatDurationMinutes(it)}") }
                                    rec.remMinutes?.let { add("REM ${formatDurationMinutes(it)}") }
                                    rec.awakeMinutes?.takeIf { it > 0 }?.let { add("清醒 ${formatDurationMinutes(it)}") }
                                    if (rec.note.isNotBlank()) add(rec.note)
                                }
                                if (detailParts.isNotEmpty()) {
                                    Text(
                                        text = detailParts.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PacePalette.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.padding(start = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rec.score?.takeIf { it > 0 }?.let { sc ->
                                    val scColor = Color(sleepScoreColorInt(sc))
                                    Surface(shape = RoundedCornerShape(999.dp), color = scColor.copy(alpha = 0.14f)) {
                                        Text(
                                            "${sc}分",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = scColor,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                                Surface(shape = RoundedCornerShape(999.dp), color = SleepAccentSoft) {
                                    Text(
                                        formatDurationMinutes(rec.durationMinutes),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SleepAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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

private data class SleepChartNight(
    val date: String,
    val shortDate: String,
    val weekday: String,
    val durationMinutes: Int,
    val bedtimeHHMM: String,
    val wakeHHMM: String,
    val score: Int?,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val remMinutes: Int?,
    val awakeMinutes: Int?,
    val note: String,
)

private fun chineseWeekday(dateStr: String): String {
    return runCatching {
        when (LocalDate.parse(dateStr.trim()).dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
            else -> ""
        }
    }.getOrDefault("")
}

private fun shortDateLabel(dateStr: String): String {
    return if (dateStr.length >= 10) dateStr.substring(5) else dateStr
}

private fun buildRecentSleepNights(records: List<SleepRecordItem>, limit: Int = 7): List<SleepChartNight> {
    return records
        .sortedByDescending { it.date }
        .take(limit)
        .sortedBy { it.date }
        .map { rec ->
            SleepChartNight(
                date = rec.date,
                shortDate = shortDateLabel(rec.date),
                weekday = chineseWeekday(rec.date).ifBlank { shortDateLabel(rec.date) },
                durationMinutes = rec.durationMinutes,
                bedtimeHHMM = extractClockHHMM(rec.bedtime),
                wakeHHMM = extractClockHHMM(rec.wakeTime),
                score = rec.score,
                deepMinutes = rec.deepMinutes,
                lightMinutes = rec.lightMinutes,
                remMinutes = rec.remMinutes,
                awakeMinutes = rec.awakeMinutes,
                note = rec.note,
            )
        }
}

private fun buildCalendarSleepNights(
    series: List<SleepDailyPoint>,
    records: List<SleepRecordItem>,
): List<SleepChartNight> {
    val byDate = records.associateBy { it.date }
    return series.map { pt ->
        val rec = byDate[pt.date]
        SleepChartNight(
            date = pt.date,
            shortDate = pt.label.ifBlank { shortDateLabel(pt.date) },
            weekday = pt.weekday.ifBlank { chineseWeekday(pt.date) },
            durationMinutes = pt.durationMinutes,
            bedtimeHHMM = extractClockHHMM(pt.bedtime.ifBlank { rec?.bedtime.orEmpty() }),
            wakeHHMM = extractClockHHMM(pt.wakeTime.ifBlank { rec?.wakeTime.orEmpty() }),
            score = pt.score ?: rec?.score,
            deepMinutes = pt.deepMinutes ?: rec?.deepMinutes,
            lightMinutes = pt.lightMinutes ?: rec?.lightMinutes,
            remMinutes = pt.remMinutes ?: rec?.remMinutes,
            awakeMinutes = rec?.awakeMinutes,
            note = rec?.note.orEmpty(),
        )
    }
}

@Composable
private fun SleepOverviewKpiTile(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, fontSize = 10.sp, color = fg.copy(alpha = 0.78f), maxLines = 1)
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SleepScheduleChart(
    nights: List<SleepChartNight>,
    emptyHint: String,
) {
    val hasAny = nights.any { it.durationMinutes > 0 }
    if (nights.isEmpty() || !hasAny) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PacePalette.Background),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
        }
        return
    }

    val defaultIndex = remember(nights) {
        nights.indexOfLast { it.durationMinutes > 0 }.coerceAtLeast(0)
    }
    var selectedIndex by remember(nights) { mutableIntStateOf(defaultIndex) }
    val safeIndex = selectedIndex.coerceIn(0, nights.lastIndex)
    val selected = nights[safeIndex]

    val validWindows = remember(nights) {
        nights.map { night ->
            val bedMin = parseClockMinutes(night.bedtimeHHMM)?.let(::sleepAxisOffsetMinutes)
            val wakeRawMin = parseClockMinutes(night.wakeHHMM)?.let(::sleepAxisOffsetMinutes)
            if (bedMin != null && wakeRawMin != null) {
                val wakeMin = if (wakeRawMin <= bedMin) wakeRawMin + 24 * 60 else wakeRawMin
                bedMin to wakeMin
            } else {
                null
            }
        }
    }
    val hasAnyWindow = validWindows.any { it != null }
    val minAxisMinutes = remember(validWindows) {
        val rawMin = validWindows.filterNotNull().minOfOrNull { it.first } ?: (22 * 60)
        (floor(rawMin / 30.0).toInt() * 30).coerceAtLeast(18 * 60)
    }
    val maxAxisMinutes = remember(validWindows, minAxisMinutes) {
        val rawMax = validWindows.filterNotNull().maxOfOrNull { it.second } ?: (32 * 60)
        maxOf(minAxisMinutes + 4 * 60, ceil(rawMax / 30.0).toInt() * 30)
    }
    val maxDuration = remember(nights) {
        nights.maxOfOrNull { it.durationMinutes }?.coerceAtLeast(60) ?: 480
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Selected-night detailed callout card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PacePalette.Background,
            border = BorderStroke(1.dp, SleepAccent.copy(alpha = 0.22f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selected.date} ${selected.weekday}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PacePalette.TextPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        selected.score?.takeIf { it > 0 }?.let { sc ->
                            val scColor = Color(sleepScoreColorInt(sc))
                            Surface(shape = RoundedCornerShape(999.dp), color = scColor.copy(alpha = 0.14f)) {
                                Text(
                                    text = "评分 ${sc}分",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scColor,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = SleepAccentSoft) {
                            Text(
                                text = if (selected.durationMinutes > 0) formatDurationMinutes(selected.durationMinutes) else "无记录",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleepAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                if (selected.durationMinutes > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "入睡 ${selected.bedtimeHHMM.ifBlank { "--:--" }}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = SleepAccent,
                        )
                        Text(
                            text = "→",
                            fontSize = 12.sp,
                            color = PacePalette.TextSecondary,
                        )
                        Text(
                            text = "醒来 ${selected.wakeHHMM.ifBlank { "--:--" }}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = SleepAccent,
                        )
                    }

                    val deep = selected.deepMinutes ?: 0
                    val light = selected.lightMinutes ?: 0
                    val rem = selected.remMinutes ?: 0
                    val awake = selected.awakeMinutes ?: 0
                    val stageSum = deep + light + rem + awake
                    if (stageSum > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(PacePalette.Muted),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            if (deep > 0) Box(Modifier.weight(deep.toFloat()).fillMaxSize().background(Color(0xFF4338CA)))
                            if (light > 0) Box(Modifier.weight(light.toFloat()).fillMaxSize().background(Color(0xFF818CF8)))
                            if (rem > 0) Box(Modifier.weight(rem.toFloat()).fillMaxSize().background(Color(0xFF06B6D4)))
                            if (awake > 0) Box(Modifier.weight(awake.toFloat()).fillMaxSize().background(Color(0xFFF59E0B)))
                        }
                        val parts = buildList {
                            if (deep > 0) add("深睡 ${formatDurationMinutes(deep)}")
                            if (light > 0) add("浅睡 ${formatDurationMinutes(light)}")
                            if (rem > 0) add("REM ${formatDurationMinutes(rem)}")
                            if (awake > 0) add("清醒 ${formatDurationMinutes(awake)}")
                        }
                        Text(
                            text = parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = PacePalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = "当日暂无睡眠数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
            }
        }

        val isSevenOrFewer = nights.size <= 7
        val gridColor = PacePalette.CardBorder
        val textMeasurer = rememberTextMeasurer()
        val (scoreMin, scoreMax) = remember(nights) {
            computeSleepScoreRange(nights.mapNotNull { it.score })
        }

        // Compact 1-line micro-legend distinguishing floating sleep bars and background score curve
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4344CE).copy(alpha = 0.78f)),
                )
                Text(
                    text = "睡眠时段/时长",
                    fontSize = 9.5.sp,
                    color = PacePalette.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF59E0B)),
                )
                Text(
                    text = "分数走势",
                    fontSize = 9.5.sp,
                    color = PacePalette.TextSecondary,
                )
            }
        }

        if (isSevenOrFewer) {
            // Top uniform Score row across all columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                nights.forEachIndexed { idx, night ->
                    val sc = night.score?.takeIf { it > 0 }
                    val scColor = sc?.let { Color(sleepScoreColorInt(it)) } ?: PacePalette.TextSecondary.copy(alpha = 0.55f)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (idx == safeIndex && sc != null) scColor.copy(alpha = 0.12f) else Color.Transparent,
                        ) {
                            Text(
                                text = sc?.let { "${it}分" } ?: "--",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = scColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // Middle full-width layered plot: background score trend line + semi-translucent floating bars
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(204.dp)
                    .pointerInput(nights) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / nights.size.coerceAtLeast(1)
                            val idx = (offset.x / slot).toInt().coerceIn(0, nights.lastIndex)
                            selectedIndex = idx
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val count = nights.size.coerceAtLeast(1)
                val slotW = w / count
                val barW = minOf(38.dp.toPx(), maxOf(22.dp.toPx(), slotW * 0.72f))
                val topLabelSpace = 16.dp.toPx()
                val botLabelSpace = 16.dp.toPx()
                val barZoneTop = topLabelSpace
                val barZoneBottom = h - botLabelSpace
                val barZoneH = (barZoneBottom - barZoneTop).coerceAtLeast(40.dp.toPx())
                val minBarH = 26.dp.toPx()
                val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

                // Layer 1: Dashed horizontal reference guides
                listOf(0f, 0.33f, 0.67f, 1f).forEach { frac ->
                    val y = barZoneTop + barZoneH * frac
                    drawLine(
                        color = gridColor.copy(alpha = 0.72f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }

                // Layer 2: Background sleep score trend line + gradient area (behind bars)
                drawSleepScoreTrendLayer(
                    nights = nights,
                    slotW = slotW,
                    barZoneTop = barZoneTop,
                    barZoneBottom = barZoneBottom,
                    scoreMin = scoreMin,
                    scoreMax = scoreMax,
                    denseMode = false,
                )

                val axisSpan = (maxAxisMinutes - minAxisMinutes).coerceAtLeast(1).toFloat()

                // Layer 3 & 4: Semi-translucent floating sleep bars + crisp foreground labels
                nights.forEachIndexed { idx, night ->
                    val centerX = idx * slotW + slotW / 2f
                    val left = centerX - barW / 2f
                    val isSelected = idx == safeIndex
                    val win = validWindows[idx]

                    if (night.durationMinutes <= 0) {
                        drawCircle(
                            color = gridColor,
                            radius = 2.5.dp.toPx(),
                            center = Offset(centerX, (barZoneTop + barZoneBottom) / 2f),
                        )
                        return@forEachIndexed
                    }

                    val (topY, botY) = if (win != null) {
                        val rawTop = barZoneTop + ((win.first - minAxisMinutes) / axisSpan).coerceIn(0f, 1f) * barZoneH
                        val rawBot = barZoneTop + ((win.second - minAxisMinutes) / axisSpan).coerceIn(0f, 1f) * barZoneH
                        if (rawBot - rawTop < minBarH) {
                            val mid = (rawTop + rawBot) / 2f
                            val clampedTop = (mid - minBarH / 2f).coerceIn(barZoneTop, barZoneBottom - minBarH)
                            clampedTop to (clampedTop + minBarH)
                        } else {
                            rawTop to rawBot
                        }
                    } else {
                        val ratio = (night.durationMinutes.toFloat() / maxDuration.toFloat()).coerceIn(0.15f, 1f)
                        val barH = maxOf(minBarH, barZoneH * ratio)
                        (barZoneBottom - barH) to barZoneBottom
                    }
                    val barH = botY - topY

                    if (isSelected) {
                        drawRoundRect(
                            color = Color(0xFF4344CE).copy(alpha = 0.78f),
                            topLeft = Offset(left, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = SleepAccent,
                            topLeft = Offset(left, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    } else {
                        drawRoundRect(
                            color = Color(0xFFE2E4FF).copy(alpha = 0.62f),
                            topLeft = Offset(left, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = Color(0xFFB8BCFA).copy(alpha = 0.78f),
                            topLeft = Offset(left, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }

                    val clockStyle = TextStyle(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) SleepAccent else PacePalette.TextSecondary,
                    )
                    if (night.bedtimeHHMM.isNotBlank()) {
                        val bedLayout = textMeasurer.measure(
                            text = night.bedtimeHHMM,
                            style = clockStyle,
                            softWrap = false,
                            maxLines = 1,
                        )
                        val bedX = (centerX - bedLayout.size.width / 2f)
                            .coerceIn(0f, (w - bedLayout.size.width).coerceAtLeast(0f))
                        val bedY = (topY - bedLayout.size.height - 2.5.dp.toPx()).coerceAtLeast(0f)
                        drawText(bedLayout, topLeft = Offset(bedX, bedY))
                    }
                    if (night.wakeHHMM.isNotBlank()) {
                        val wakeLayout = textMeasurer.measure(
                            text = night.wakeHHMM,
                            style = clockStyle,
                            softWrap = false,
                            maxLines = 1,
                        )
                        val wakeX = (centerX - wakeLayout.size.width / 2f)
                            .coerceIn(0f, (w - wakeLayout.size.width).coerceAtLeast(0f))
                        val wakeY = (botY + 2.5.dp.toPx())
                            .coerceAtMost((h - wakeLayout.size.height).coerceAtLeast(0f))
                        drawText(wakeLayout, topLeft = Offset(wakeX, wakeY))
                    }

                    val insideColor = if (isSelected) Color.White else Color(0xFF3537B0)
                    val centerY = (topY + botY) / 2f
                    val (line1, line2) = formatBarDurationLines(night.durationMinutes)
                    if (line2 != null && barH >= 28.dp.toPx()) {
                        val l1 = textMeasurer.measure(
                            text = line1,
                            style = TextStyle(
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = insideColor,
                            ),
                            softWrap = false,
                            maxLines = 1,
                        )
                        val l2 = textMeasurer.measure(
                            text = line2,
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = insideColor.copy(alpha = 0.9f),
                            ),
                            softWrap = false,
                            maxLines = 1,
                        )
                        val gap = (-1).dp.toPx()
                        val totalH = l1.size.height + l2.size.height + gap
                        val top1 = centerY - totalH / 2f
                        drawText(l1, topLeft = Offset(centerX - l1.size.width / 2f, top1))
                        drawText(l2, topLeft = Offset(centerX - l2.size.width / 2f, top1 + l1.size.height + gap))
                    } else {
                        val single = textMeasurer.measure(
                            text = formatCompactDuration(night.durationMinutes),
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = insideColor,
                            ),
                            softWrap = false,
                            maxLines = 1,
                        )
                        drawText(
                            single,
                            topLeft = Offset(centerX - single.size.width / 2f, centerY - single.size.height / 2f),
                        )
                    }
                }
            }

            // Bottom X-axis: date + weekday
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                nights.forEachIndexed { idx, night ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = night.shortDate,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (idx == safeIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == safeIndex) SleepAccent else PacePalette.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = night.weekday,
                            fontSize = 9.sp,
                            color = if (idx == safeIndex) SleepAccent else PacePalette.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        } else {
            // Multi-week calendar mode (> 7 days): background score curve + translucent floating capsules + 5 date ticks
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(nights) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / nights.size.coerceAtLeast(1)
                            val idx = (offset.x / slot).toInt().coerceIn(0, nights.lastIndex)
                            selectedIndex = idx
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val count = nights.size.coerceAtLeast(1)
                val slotW = w / count
                val capsuleW = minOf(12.dp.toPx(), maxOf(4.dp.toPx(), slotW * 0.56f))
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                val padY = h * 0.06f
                val usableH = h - 2f * padY

                listOf(0f, 0.33f, 0.67f, 1f).forEach { frac ->
                    val y = padY + usableH * frac
                    drawLine(
                        color = gridColor.copy(alpha = 0.72f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }

                drawSleepScoreTrendLayer(
                    nights = nights,
                    slotW = slotW,
                    barZoneTop = padY,
                    barZoneBottom = h - padY,
                    scoreMin = scoreMin,
                    scoreMax = scoreMax,
                    denseMode = true,
                )

                val axisSpan = (maxAxisMinutes - minAxisMinutes).coerceAtLeast(1).toFloat()
                nights.forEachIndexed { idx, night ->
                    val centerX = idx * slotW + slotW / 2f
                    val left = centerX - capsuleW / 2f
                    val isSelected = idx == safeIndex
                    val win = validWindows[idx]

                    if (night.durationMinutes > 0 && win != null) {
                        val topY = padY + ((win.first - minAxisMinutes) / axisSpan).coerceIn(0f, 1f) * usableH
                        val rawBotY = padY + ((win.second - minAxisMinutes) / axisSpan).coerceIn(0f, 1f) * usableH
                        val capH = maxOf(10.dp.toPx(), rawBotY - topY)
                        drawRoundRect(
                            color = if (isSelected) Color(0xFF4344CE).copy(alpha = 0.80f) else SleepAccent.copy(alpha = 0.36f),
                            topLeft = Offset(left, topY),
                            size = Size(capsuleW, capH),
                            cornerRadius = CornerRadius(capsuleW / 2f, capsuleW / 2f),
                        )
                    } else if (night.durationMinutes > 0) {
                        val ratio = (night.durationMinutes.toFloat() / maxDuration.toFloat()).coerceIn(0.12f, 1f)
                        val barH = maxOf(8.dp.toPx(), usableH * ratio)
                        drawRoundRect(
                            color = if (isSelected) Color(0xFF4344CE).copy(alpha = 0.80f) else SleepAccent.copy(alpha = 0.30f),
                            topLeft = Offset(left, h - padY - barH),
                            size = Size(capsuleW, barH),
                            cornerRadius = CornerRadius(capsuleW / 2f, capsuleW / 2f),
                        )
                    } else {
                        drawCircle(
                            color = gridColor,
                            radius = 2.dp.toPx(),
                            center = Offset(centerX, h * 0.5f),
                        )
                    }
                }
            }

            val tickIndices = remember(nights.size) {
                listOf(
                    0,
                    nights.lastIndex / 4,
                    nights.lastIndex / 2,
                    (nights.lastIndex * 3) / 4,
                    nights.lastIndex,
                ).distinct()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                tickIndices.forEach { idx ->
                    val night = nights[idx]
                    Text(
                        text = night.shortDate,
                        fontSize = 10.sp,
                        color = if (idx == safeIndex) SleepAccent else PacePalette.TextSecondary,
                        fontWeight = if (idx == safeIndex) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSleepScoreTrendLayer(
    nights: List<SleepChartNight>,
    slotW: Float,
    barZoneTop: Float,
    barZoneBottom: Float,
    scoreMin: Int,
    scoreMax: Int,
    denseMode: Boolean,
) {
    val scoreTop = barZoneTop + 4.dp.toPx()
    val scoreBottom = barZoneBottom - 4.dp.toPx()
    if (scoreBottom <= scoreTop) return
    val scoreSpan = (scoreMax - scoreMin).coerceAtLeast(1).toFloat()
    val scoreH = scoreBottom - scoreTop
    val points = buildList {
        nights.forEachIndexed { idx, night ->
            val sc = night.score?.takeIf { it > 0 } ?: return@forEachIndexed
            val cx = idx * slotW + slotW / 2f
            val norm = ((sc - scoreMin) / scoreSpan).coerceIn(0f, 1f)
            val cy = scoreTop + (1f - norm) * scoreH
            add(Triple(Offset(cx, cy), sc, night))
        }
    }
    if (points.isEmpty()) return

    val amberLine = Color(0xFFF59E0B)
    if (points.size >= 2) {
        val linePath = Path().apply {
            val first = points.first().first
            moveTo(first.x, first.y)
            for (i in 1 until points.size) {
                val prev = points[i - 1].first
                val cur = points[i].first
                val midX = (prev.x + cur.x) / 2f
                cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
            }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().first.x, barZoneBottom)
            lineTo(points.first().first.x, barZoneBottom)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(amberLine.copy(alpha = 0.15f), amberLine.copy(alpha = 0.01f)),
                startY = scoreTop,
                endY = barZoneBottom,
            ),
        )
        drawPath(
            path = linePath,
            color = amberLine.copy(alpha = 0.80f),
            style = Stroke(
                width = (if (denseMode) 1.7.dp else 2.2.dp).toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    points.forEach { (pt, sc, _) ->
        if (denseMode) {
            drawCircle(
                color = amberLine.copy(alpha = 0.88f),
                radius = 2.dp.toPx(),
                center = pt,
            )
        } else {
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 3.8.dp.toPx(),
                center = pt,
            )
            drawCircle(
                color = Color(sleepScoreColorInt(sc)).copy(alpha = 0.92f),
                radius = 2.4.dp.toPx(),
                center = pt,
            )
        }
    }
}

@Composable
private fun RunningAnalyticsCard(
    running: RunningAnalytics,
    onOpenImage: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("跑步记录", style = MaterialTheme.typography.titleMedium, color = PacePalette.TextPrimary)
                    Text(
                        text = buildString {
                            append("累计 ${running.totalRuns} 次")
                            if (running.totalDistanceKm > 0.0) {
                                val distLabel = String.format(Locale.US, "%.2f", running.totalDistanceKm)
                                    .trimEnd('0').trimEnd('.')
                                append(" · 已统计 $distLabel km")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
                Surface(shape = RoundedCornerShape(999.dp), color = RunningAccentSoft) {
                    Text(
                        "近阶段 ${running.runsInRange} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = RunningAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            DailyBarChart(
                points = running.dailySeries,
                barColor = RunningAccent,
                valueFormatter = { v ->
                    val km = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
                    "$km km"
                },
                emptyHint = "区间内暂无跑步距离记录",
            )

            if (running.recentRuns.isEmpty()) {
                Text(
                    "暂无跑步记录，可上传运动截图给 Hermes 或写入 running/records.json。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextSecondary,
                )
            } else {
                HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    running.recentRuns.take(15).forEach { run ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PacePalette.Background)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        run.date,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PacePalette.TextPrimary,
                                    )
                                    if (run.distanceKm > 0.0) {
                                        Surface(shape = RoundedCornerShape(999.dp), color = RunningAccentSoft) {
                                            Text(
                                                "${String.format(Locale.US, "%.2f", run.distanceKm).trimEnd('0').trimEnd('.')} km",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = RunningAccent,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    if (run.durationMinutes > 0) {
                                        Text(
                                            formatDurationMinutes(run.durationMinutes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PacePalette.TextSecondary,
                                        )
                                    }
                                    if (run.pace.isNotBlank()) {
                                        Surface(shape = RoundedCornerShape(999.dp), color = PacePalette.Muted) {
                                            Text(
                                                run.pace,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PacePalette.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    val srcTag = when (run.source.lowercase(Locale.ROOT)) {
                                        "coros" -> "COROS"
                                        "keep" -> "Keep"
                                        else -> ""
                                    }
                                    if (srcTag.isNotEmpty()) {
                                        Surface(shape = RoundedCornerShape(999.dp), color = PacePalette.PrimarySoft) {
                                            Text(
                                                srcTag,
                                                fontSize = 10.sp,
                                                color = PacePalette.Primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                if (run.note.isNotBlank()) {
                                    Text(
                                        run.note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PacePalette.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (run.imagePath.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PacePalette.PrimarySoft,
                                    onClick = { onOpenImage(run.imagePath) },
                                ) {
                                    Text(
                                        "看图",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PacePalette.Primary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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

@Composable
private fun CustomMetricCard(metric: CustomMetricSummary) {
    val accent = remember(metric.colorHex) { parseColorHex(metric.colorHex, PacePalette.Primary) }
    val unitSuffix = if (metric.unit.isNotBlank()) " ${metric.unit}" else ""
    fun formatNum(v: Double): String {
        val text = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
        return "$text$unitSuffix"
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(metric.name, style = MaterialTheme.typography.titleMedium, color = PacePalette.TextPrimary)
                    Text(
                        buildString {
                            append("均值 ${formatNum(metric.avgValue)}")
                            if (metric.goal > 0.0) append(" · 目标 ${formatNum(metric.goal)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
                Text(
                    formatNum(metric.latestValue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }

            DailyBarChart(
                points = metric.dailySeries,
                barColor = accent,
                valueFormatter = { formatNum(it) },
                emptyHint = "暂无数据点",
            )
        }
    }
}

@Composable
private fun MetricMiniChip(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
) {
    Surface(shape = RoundedCornerShape(10.dp), color = bg) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, fontSize = 10.sp, color = fg.copy(alpha = 0.8f))
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg)
        }
    }
}

@Composable
private fun DailyBarChart(
    points: List<DailyMetricPoint>,
    barColor: Color,
    valueFormatter: (Double) -> String,
    emptyHint: String,
) {
    val hasAnyNonZero = points.any { it.value > 0.0 }
    if (points.isEmpty() || !hasAnyNonZero) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PacePalette.Background),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
        }
        return
    }

    var selectedIndex by remember(points) {
        mutableIntStateOf(points.indexOfLast { it.value > 0.0 }.coerceAtLeast(0))
    }
    val safeIndex = selectedIndex.coerceIn(0, points.lastIndex)
    val selected = points[safeIndex]
    val maxValue = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PacePalette.Background)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${selected.date} (${selected.weekday})",
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary,
            )
            Text(
                text = buildString {
                    append(valueFormatter(selected.value))
                    if (selected.count > 1) append(" · ${selected.count} 次")
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PacePalette.Background)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            val gridColor = PacePalette.CardBorder
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            if (size.width > 0 && points.isNotEmpty()) {
                                val tapped = ((offset.x / size.width.toFloat()) * points.size)
                                    .toInt()
                                    .coerceIn(0, points.lastIndex)
                                selectedIndex = tapped
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                drawLine(
                    color = gridColor,
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.dp.toPx(),
                )

                val slotW = w / points.size.toFloat()
                val barW = (slotW * 0.58f).coerceIn(3.dp.toPx(), 24.dp.toPx())
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

                points.forEachIndexed { idx, pt ->
                    val ratio = (pt.value / maxValue).toFloat().coerceIn(0f, 1f)
                    val barH = if (pt.value > 0.0) maxOf(6.dp.toPx(), h * ratio) else 2.dp.toPx()
                    val left = idx * slotW + (slotW - barW) / 2f
                    val top = h - barH
                    val color = when {
                        pt.value <= 0.0 -> gridColor.copy(alpha = 0.65f)
                        idx == safeIndex -> barColor
                        else -> barColor.copy(alpha = 0.42f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = radius,
                    )
                }
            }
        }

        val labelIndices = remember(points.size) {
            when {
                points.size <= 7 -> points.indices.toList()
                else -> listOf(
                    0,
                    points.lastIndex / 4,
                    points.lastIndex / 2,
                    (points.lastIndex * 3) / 4,
                    points.lastIndex,
                ).distinct()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labelIndices.forEach { idx ->
                val pt = points[idx]
                Text(
                    text = if (points.size <= 7) pt.weekday.ifBlank { pt.label } else pt.label,
                    fontSize = 10.sp,
                    color = if (idx == safeIndex) barColor else PacePalette.TextSecondary,
                    fontWeight = if (idx == safeIndex) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabManualEntrySheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (date: String, arrive: String, leave: String, note: String) -> Unit,
) {
    val today = remember { LocalDate.now(ZoneShanghai).toString() }
    var date by remember { mutableStateOf(today) }
    var arrive by remember { mutableStateOf("09:00") }
    var leave by remember { mutableStateOf("12:00") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PacePalette.Card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("补录实验室学习时段", style = MaterialTheme.typography.titleMedium)
            Text(
                "统一写入 data/lab/sessions.json，离开时间支持跨午夜，将自动计算时长。",
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary,
            )
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("日期 (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = arrive,
                    onValueChange = { arrive = it },
                    label = { Text("到达 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = leave,
                    onValueChange = { leave = it },
                    label = { Text("离开 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选，例如：精读论文）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSubmit(date, arrive, leave, note) },
                    enabled = !busy && date.isNotBlank() && arrive.isNotBlank() && leave.isNotBlank(),
                ) {
                    Text(if (busy) "保存中..." else "保存记录")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepManualEntrySheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (date: String, bedtime: String, wakeTime: String, score: Int?, note: String) -> Unit,
) {
    val today = remember { LocalDate.now(ZoneShanghai).toString() }
    var date by remember { mutableStateOf(today) }
    var bedtime by remember { mutableStateOf("23:30") }
    var wakeTime by remember { mutableStateOf("07:30") }
    var scoreText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PacePalette.Card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("记录睡眠数据", style = MaterialTheme.typography.titleMedium)
            Text(
                "按醒来日期归档至 data/sleep/records.json，跨零点睡眠将自动推算时长。",
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary,
            )
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("醒来日期 (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = bedtime,
                    onValueChange = { bedtime = it },
                    label = { Text("入睡 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = wakeTime,
                    onValueChange = { wakeTime = it },
                    label = { Text("醒来 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = scoreText,
                onValueChange = { s -> scoreText = s.filter { it.isDigit() }.take(3) },
                label = { Text("睡眠评分 0-100（可选）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val score = scoreText.toIntOrNull()?.coerceIn(0, 100)
                        onSubmit(date, bedtime, wakeTime, score, note)
                    },
                    enabled = !busy && date.isNotBlank() && bedtime.isNotBlank() && wakeTime.isNotBlank(),
                ) {
                    Text(if (busy) "保存中..." else "保存")
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSpecDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hermes + PaceApp 数据规范") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "已与 Hermes Skill（lab-time-tracking / personal-metrics-spec）约定单一 JSON 数据源，不生成重复文档：",
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextPrimary,
                )
                SpecBlock(
                    path = "lab/sessions.json",
                    desc = "实验室学习打卡\n{date, arrive: 'YYYY-MM-DD HH:MM', leave: null|str, minutes, note}",
                )
                SpecBlock(
                    path = "sleep/records.json",
                    desc = "睡眠与手表导入\n{date, bedtime, wake_time, duration_minutes, deep_minutes?, light_minutes?, rem_minutes?, score?, source}",
                )
                SpecBlock(
                    path = "running/records.json + images/running/notes.json",
                    desc = "跑步距离/配速/时长与照片打卡（自动合并展示）",
                )
                SpecBlock(
                    path = "metrics/<slug>.json",
                    desc = "任意可扩展新指标（如专注/喝水/体重），按 {name, unit, color, goal, records:[{date,value,note}]} 自动新增看板卡片",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

@Composable
private fun SpecBlock(path: String, desc: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PacePalette.Background,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PacePalette.Primary,
            )
            Text(desc, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
        }
    }
}
