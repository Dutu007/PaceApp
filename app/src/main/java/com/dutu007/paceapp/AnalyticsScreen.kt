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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dutu007.paceapp.widget.LabStudyWidgetProvider
import com.dutu007.paceapp.widget.OriginOsKeepAliveHelper
import com.dutu007.paceapp.widget.SleepTrendWidgetProvider
import com.dutu007.paceapp.data.AnalyticsOverview
import com.dutu007.paceapp.data.CustomMetricSummary
import com.dutu007.paceapp.data.DailyMetricPoint
import com.dutu007.paceapp.data.LabAnalytics
import com.dutu007.paceapp.data.LabSessionItem
import com.dutu007.paceapp.data.RunningAnalytics
import com.dutu007.paceapp.data.SleepAnalytics
import com.dutu007.paceapp.ui.theme.PacePalette
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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
    val context = LocalContext.current
    var showLabSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showSpecDialog by remember { mutableStateOf(false) }
    var showWidgetDialog by remember { mutableStateOf(false) }
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
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PacePalette.PrimarySoft,
                        border = BorderStroke(1.dp, PacePalette.Primary.copy(alpha = 0.25f)),
                        onClick = { showWidgetDialog = true },
                    ) {
                        Text(
                            text = "桌面组件",
                            style = MaterialTheme.typography.labelMedium,
                            color = PacePalette.Primary,
                            fontWeight = FontWeight.SemiBold,
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

    if (showWidgetDialog) {
        WidgetQuickSetupDialog(
            onDismiss = { showWidgetDialog = false },
            onPinLab = {
                val ok = OriginOsKeepAliveHelper.requestPinWidget(context, LabStudyWidgetProvider::class.java)
                viewModel.notifyHint(
                    if (ok) "已向桌面发送「实验室学习时长」组件添加请求" else "当前桌面不支持直接推送，请在桌面长按/上滑原子组件库添加 Pace 组件",
                    isError = !ok,
                )
            },
            onPinSleep = {
                val ok = OriginOsKeepAliveHelper.requestPinWidget(context, SleepTrendWidgetProvider::class.java)
                viewModel.notifyHint(
                    if (ok) "已向桌面发送「睡眠时长趋势」组件添加请求" else "当前桌面不支持直接推送，请在桌面长按/上滑原子组件库添加 Pace 组件",
                    isError = !ok,
                )
            },
            onOpenBattery = { OriginOsKeepAliveHelper.requestIgnoreBatteryOptimizations(context) },
            onOpenAutostart = { OriginOsKeepAliveHelper.openOriginOsAutostartSettings(context) },
        )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricMiniChip("均值", formatDurationMinutes(sleep.avgDurationMinutes), SleepAccentSoft, SleepAccent)
                    MetricMiniChip("最近一晚", formatDurationMinutes(sleep.latestDurationMinutes), PacePalette.Muted, PacePalette.TextPrimary)
                    sleep.avgScore?.let {
                        MetricMiniChip("平均评分", "$it 分", PacePalette.AccentAmberSoft, PacePalette.AccentAmber)
                    }
                }

                val chartPoints = remember(sleep.dailySeries) {
                    sleep.dailySeries.map {
                        DailyMetricPoint(
                            date = it.date,
                            label = it.label,
                            weekday = it.weekday,
                            value = it.durationMinutes.toDouble(),
                            count = if (it.durationMinutes > 0) 1 else 0,
                        )
                    }
                }
                DailyBarChart(
                    points = chartPoints,
                    barColor = SleepAccent,
                    valueFormatter = { v -> formatDurationMinutes(v.toInt()) },
                    emptyHint = "区间内暂无睡眠记录",
                )

                HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sleep.recentRecords.take(5).forEach { rec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PacePalette.Background)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = buildString {
                                        append(rec.date)
                                        if (rec.bedtime.isNotBlank() || rec.wakeTime.isNotBlank()) {
                                            append(" · ${rec.bedtime.ifBlank { "--:--" }} - ${rec.wakeTime.ifBlank { "--:--" }}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PacePalette.TextPrimary,
                                )
                                val detailParts = buildList {
                                    rec.deepMinutes?.let { add("深睡 ${formatDurationMinutes(it)}") }
                                    rec.remMinutes?.let { add("REM ${formatDurationMinutes(it)}") }
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                rec.score?.let { sc ->
                                    Surface(shape = RoundedCornerShape(999.dp), color = PacePalette.AccentAmberSoft) {
                                        Text(
                                            "${sc}分",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PacePalette.AccentAmber,
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
private fun WidgetQuickSetupDialog(
    onDismiss: () -> Unit,
    onPinLab: () -> Unit,
    onPinSleep: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenAutostart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OriginOS 桌面原子组件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "将学习时长与睡眠曲线添加到 iQOO 桌面（也可用手指在桌面空白处长按 -> 原子组件 -> 搜索 Pace）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextSecondary,
                )
                Button(onClick = onPinLab, modifier = Modifier.fillMaxWidth()) {
                    Text("添加「实验室学习时长」组件")
                }
                OutlinedButton(onClick = onPinSleep, modifier = Modifier.fillMaxWidth()) {
                    Text("添加「睡眠时长趋势」组件")
                }
                HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                Text(
                    "后台保活建议：忽略电池优化 + 开启自启动与允许后台高耗电，并在多任务界面下拉锁定 Pace。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBattery, modifier = Modifier.weight(1f)) {
                        Text("电池白名单", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onOpenAutostart, modifier = Modifier.weight(1f)) {
                        Text("自启动管理", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
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
