package com.dutu007.paceapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.dutu007.paceapp.MainActivity
import com.dutu007.paceapp.R
import com.dutu007.paceapp.averageBedtimeClock
import com.dutu007.paceapp.averageWakeClock
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.extractClockHHMM
import com.dutu007.paceapp.formatCompactDuration
import com.dutu007.paceapp.formatDurationMinutes
import com.dutu007.paceapp.sleepScoreColorInt
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object PaceWidgetUpdater {
    const val ACTION_WIDGET_REFRESH = "com.dutu007.paceapp.widget.ACTION_REFRESH"
    const val ACTION_WIDGET_LAB_TOGGLE = "com.dutu007.paceapp.widget.ACTION_LAB_TOGGLE"

    private val shanghaiZone = ZoneId.of("Asia/Shanghai")
    private val arriveFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun hasAnyWidgets(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        val labIds = mgr.getAppWidgetIds(ComponentName(context, LabStudyWidgetProvider::class.java))
        val compactIds = mgr.getAppWidgetIds(ComponentName(context, LabCompactWidgetProvider::class.java))
        val sleepIds = mgr.getAppWidgetIds(ComponentName(context, SleepTrendWidgetProvider::class.java))
        val sleepCompactIds = mgr.getAppWidgetIds(ComponentName(context, SleepCompactWidgetProvider::class.java))
        val overviewIds = mgr.getAppWidgetIds(ComponentName(context, PaceOverviewWidgetProvider::class.java))
        return labIds.isNotEmpty() ||
            compactIds.isNotEmpty() ||
            sleepIds.isNotEmpty() ||
            sleepCompactIds.isNotEmpty() ||
            overviewIds.isNotEmpty()
    }

    fun updateAllWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val labIds = mgr.getAppWidgetIds(ComponentName(context, LabStudyWidgetProvider::class.java))
        if (labIds.isNotEmpty()) {
            mgr.updateAppWidget(labIds, buildLabRemoteViews(context))
        }
        val compactIds = mgr.getAppWidgetIds(ComponentName(context, LabCompactWidgetProvider::class.java))
        if (compactIds.isNotEmpty()) {
            mgr.updateAppWidget(compactIds, buildCompactLabRemoteViews(context))
        }
        val sleepIds = mgr.getAppWidgetIds(ComponentName(context, SleepTrendWidgetProvider::class.java))
        if (sleepIds.isNotEmpty()) {
            mgr.updateAppWidget(sleepIds, buildSleepRemoteViews(context))
        }
        val sleepCompactIds = mgr.getAppWidgetIds(ComponentName(context, SleepCompactWidgetProvider::class.java))
        if (sleepCompactIds.isNotEmpty()) {
            mgr.updateAppWidget(sleepCompactIds, buildCompactSleepRemoteViews(context))
        }
        val overviewIds = mgr.getAppWidgetIds(ComponentName(context, PaceOverviewWidgetProvider::class.java))
        if (overviewIds.isNotEmpty()) {
            mgr.updateAppWidget(overviewIds, buildOverviewRemoteViews(context))
        }
    }

    private fun buildOpenAnalyticsIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.dutu007.paceapp.OPEN_ANALYTICS"
            putExtra(MainActivity.EXTRA_OPEN_TAB, "ANALYTICS")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildProviderActionIntent(
        context: Context,
        providerClass: Class<*>,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, providerClass).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun liveExtraMinutes(snapshot: WidgetSnapshot): Int {
        if (!snapshot.labActive || snapshot.labArrive.isBlank() || snapshot.lastSyncEpochMs <= 0L) return 0
        return runCatching {
            val syncedAt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(snapshot.lastSyncEpochMs),
                shanghaiZone,
            )
            val now = LocalDateTime.now(shanghaiZone)
            val arrive = LocalDateTime.parse(snapshot.labArrive.trim(), arriveFmt)
            val fromSync = ChronoUnit.MINUTES.between(syncedAt, now).toInt().coerceAtLeast(0)
            val fromArrive = ChronoUnit.MINUTES.between(arrive, now).toInt().coerceAtLeast(0)
            minOf(fromSync, fromArrive)
        }.getOrDefault(0)
    }

    private fun buildLabRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lab_study)
        val hasSession = PaceRepository(context).hasSession()
        val snapshot = WidgetDataStore.loadSnapshot(context)
        val lastError = WidgetDataStore.lastError(context)

        views.setOnClickPendingIntent(R.id.widget_lab_root, buildOpenAnalyticsIntent(context, 101))
        views.setOnClickPendingIntent(
            R.id.widget_lab_refresh,
            buildProviderActionIntent(context, LabStudyWidgetProvider::class.java, ACTION_WIDGET_REFRESH, 102),
        )

        if (!hasSession) {
            views.setImageViewResource(R.id.widget_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_lab_badge, "未登录")
            views.setInt(R.id.widget_lab_badge, "setBackgroundResource", R.drawable.widget_pill_muted)
            views.setTextColor(R.id.widget_lab_badge, 0xFF5A6B78.toInt())
            views.setTextViewText(R.id.widget_lab_action, "登录")
            views.setOnClickPendingIntent(R.id.widget_lab_action, buildOpenAnalyticsIntent(context, 103))
            views.setImageViewBitmap(
                R.id.widget_lab_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF0E5A6F.toInt(),
                    emptyLabel = "点击打开 PaceApp 登录后自动同步",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                ),
            )
            views.setTextViewText(R.id.widget_lab_footer_left, "需要账号登录授权")
            views.setTextViewText(R.id.widget_lab_footer_right, "点卡片进入")
            return views
        }

        views.setOnClickPendingIntent(
            R.id.widget_lab_action,
            buildProviderActionIntent(context, LabStudyWidgetProvider::class.java, ACTION_WIDGET_LAB_TOGGLE, 104),
        )

        if (snapshot == null) {
            views.setImageViewResource(R.id.widget_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_lab_badge, "同步中")
            views.setInt(R.id.widget_lab_badge, "setBackgroundResource", R.drawable.widget_pill_teal)
            views.setTextColor(R.id.widget_lab_badge, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_lab_action, "打卡")
            views.setImageViewBitmap(
                R.id.widget_lab_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF0E5A6F.toInt(),
                    emptyLabel = if (lastError.isNotBlank()) lastError else "正在拉取实验室学习趋势...",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                ),
            )
            views.setTextViewText(R.id.widget_lab_footer_left, "单源同步 lab/sessions.json")
            views.setTextViewText(R.id.widget_lab_footer_right, "点击刷新")
            return views
        }

        val extraLive = liveExtraMinutes(snapshot)
        val displayToday = snapshot.todayLabMinutes + extraLive
        val displayWeek = snapshot.weekLabMinutes + extraLive

        if (snapshot.labActive) {
            views.setImageViewResource(R.id.widget_lab_dot, R.drawable.widget_dot_green)
            views.setTextViewText(R.id.widget_lab_badge, "学习中 · ${formatDurationMinutes(displayToday)}")
            views.setInt(R.id.widget_lab_badge, "setBackgroundResource", R.drawable.widget_pill_green)
            views.setTextColor(R.id.widget_lab_badge, 0xFF059669.toInt())
            views.setTextViewText(R.id.widget_lab_action, "签退")
        } else {
            views.setImageViewResource(R.id.widget_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_lab_badge, "今日 ${formatDurationMinutes(displayToday)}")
            views.setInt(R.id.widget_lab_badge, "setBackgroundResource", R.drawable.widget_pill_teal)
            views.setTextColor(R.id.widget_lab_badge, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_lab_action, "打卡")
        }

        val chartPoints = WidgetDataStore.toDailyPoints(snapshot.labDailySeries)
        views.setImageViewBitmap(
            R.id.widget_lab_chart,
            WidgetChartRenderer.renderSevenDayBarChart(
                context = context,
                points = chartPoints,
                accentColor = 0xFF0E5A6F.toInt(),
                emptyLabel = "近 7 天暂无实验室打卡",
                valueLabelFormatter = { formatCompactDuration(it.toInt()) },
            ),
        )

        val footerLeft = "本周 ${formatDurationMinutes(displayWeek)} · 连续 ${snapshot.labStreakDays} 天"
        val footerRight = if (lastError.isNotBlank()) "同步受限" else WidgetDataStore.lastSyncFormatted(context)
        views.setTextViewText(R.id.widget_lab_footer_left, footerLeft)
        views.setTextViewText(R.id.widget_lab_footer_right, footerRight)
        return views
    }

    private data class ResolvedSleepSummary(
        val nights: List<WidgetSleepNight>,
        val shortDate: String,
        val latestDurationMinutes: Int,
        val latestBedtime: String,
        val latestWakeTime: String,
        val latestScore: Int?,
        val avgBedtime: String,
        val avgWakeTime: String,
    )

    private fun resolveSleepSummary(snapshot: WidgetSnapshot): ResolvedSleepSummary {
        val nights = if (snapshot.sleepRecentNights.isNotEmpty()) {
            snapshot.sleepRecentNights
        } else {
            snapshot.sleepDailySeries.map { pt ->
                WidgetSleepNight(
                    date = pt.date,
                    durationMinutes = pt.value.toInt(),
                )
            }
        }
        val latest = nights.lastOrNull { it.durationMinutes > 0 }
        val latestDate = latest?.date?.ifBlank { snapshot.latestSleepDate } ?: snapshot.latestSleepDate
        val shortDate = if (latestDate.length >= 10) latestDate.substring(5) else latestDate
        val latestDuration = latest?.durationMinutes ?: snapshot.latestSleepMinutes
        val latestBed = extractClockHHMM(
            latest?.bedtime?.ifBlank { snapshot.latestSleepBedtime } ?: snapshot.latestSleepBedtime,
        )
        val latestWake = extractClockHHMM(
            latest?.wakeTime?.ifBlank { snapshot.latestSleepWakeTime } ?: snapshot.latestSleepWakeTime,
        )
        val latestScore = latest?.score ?: snapshot.latestSleepScore ?: snapshot.sleepAvgScore
        val activeNights = nights.filter { it.durationMinutes > 0 }
        return ResolvedSleepSummary(
            nights = nights,
            shortDate = shortDate,
            latestDurationMinutes = latestDuration,
            latestBedtime = latestBed,
            latestWakeTime = latestWake,
            latestScore = latestScore,
            avgBedtime = averageBedtimeClock(activeNights.map { it.bedtime }),
            avgWakeTime = averageWakeClock(activeNights.map { it.wakeTime }),
        )
    }

    private fun buildSleepRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sleep_trend)
        val hasSession = PaceRepository(context).hasSession()
        val snapshot = WidgetDataStore.loadSnapshot(context)
        val lastError = WidgetDataStore.lastError(context)

        views.setOnClickPendingIntent(R.id.widget_sleep_root, buildOpenAnalyticsIntent(context, 201))
        views.setOnClickPendingIntent(
            R.id.widget_sleep_refresh,
            buildProviderActionIntent(context, SleepTrendWidgetProvider::class.java, ACTION_WIDGET_REFRESH, 202),
        )

        if (!hasSession) {
            views.setTextViewText(R.id.widget_sleep_badge, "未登录")
            views.setViewVisibility(R.id.widget_sleep_score, View.GONE)
            views.setViewVisibility(R.id.widget_sleep_window, View.GONE)
            views.setImageViewBitmap(
                R.id.widget_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = emptyList(),
                    emptyLabel = "点击打开 Pace 登录后同步睡眠数据",
                ),
            )
            views.setTextViewText(R.id.widget_sleep_footer_left, "支持 COROS 手表同步")
            views.setTextViewText(R.id.widget_sleep_footer_right, "点卡片进入")
            return views
        }

        if (snapshot == null || !snapshot.sleepHasData) {
            views.setTextViewText(R.id.widget_sleep_badge, "待接入")
            views.setViewVisibility(R.id.widget_sleep_score, View.GONE)
            views.setViewVisibility(R.id.widget_sleep_window, View.GONE)
            views.setImageViewBitmap(
                R.id.widget_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = emptyList(),
                    emptyLabel = "待接入高驰 COROS 或手动睡眠数据",
                ),
            )
            views.setTextViewText(R.id.widget_sleep_footer_left, "在 Pace 同步高驰或手动录入后展示")
            views.setTextViewText(
                R.id.widget_sleep_footer_right,
                if (snapshot == null) "等待同步" else WidgetDataStore.lastSyncFormatted(context),
            )
            return views
        }

        val resolved = resolveSleepSummary(snapshot)
        val badgeText = buildString {
            if (resolved.shortDate.isNotBlank()) {
                append(resolved.shortDate)
                append(" ")
            }
            append(formatCompactDuration(resolved.latestDurationMinutes))
        }
        views.setTextViewText(R.id.widget_sleep_badge, badgeText)

        val score = resolved.latestScore
        if (score != null && score > 0) {
            views.setViewVisibility(R.id.widget_sleep_score, View.VISIBLE)
            views.setTextViewText(R.id.widget_sleep_score, "${score}分")
            views.setTextColor(R.id.widget_sleep_score, sleepScoreColorInt(score))
        } else {
            views.setViewVisibility(R.id.widget_sleep_score, View.GONE)
        }

        if (resolved.latestBedtime.isNotBlank() || resolved.latestWakeTime.isNotBlank()) {
            views.setViewVisibility(R.id.widget_sleep_window, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_sleep_window,
                "${resolved.latestBedtime.ifBlank { "--:--" }}→${resolved.latestWakeTime.ifBlank { "--:--" }}",
            )
        } else {
            views.setViewVisibility(R.id.widget_sleep_window, View.GONE)
        }

        views.setImageViewBitmap(
            R.id.widget_sleep_chart,
            WidgetChartRenderer.renderSleepTimelineChart(
                context = context,
                nights = resolved.nights,
                emptyLabel = "近 7 晚暂无睡眠记录",
            ),
        )
        val avgSummary = buildString {
            append("均睡 ${formatCompactDuration(snapshot.avgSleepMinutes)}")
            if (resolved.avgBedtime != "--:--" || resolved.avgWakeTime != "--:--") {
                append(" · 平均 ${resolved.avgBedtime}→${resolved.avgWakeTime}")
            }
        }
        views.setTextViewText(R.id.widget_sleep_footer_left, avgSummary)
        views.setTextViewText(
            R.id.widget_sleep_footer_right,
            if (lastError.isNotBlank()) "同步受限" else WidgetDataStore.lastSyncFormatted(context),
        )
        return views
    }

    private fun buildCompactLabRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lab_compact)
        val hasSession = PaceRepository(context).hasSession()
        val snapshot = WidgetDataStore.loadSnapshot(context)
        val lastError = WidgetDataStore.lastError(context)

        views.setOnClickPendingIntent(R.id.widget_compact_root, buildOpenAnalyticsIntent(context, 301))
        views.setOnClickPendingIntent(
            R.id.widget_compact_refresh,
            buildProviderActionIntent(context, LabCompactWidgetProvider::class.java, ACTION_WIDGET_REFRESH, 302),
        )

        if (!hasSession) {
            views.setImageViewResource(R.id.widget_compact_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_compact_value, "未登录")
            views.setTextColor(R.id.widget_compact_value, 0xFF5A6B78.toInt())
            views.setTextViewText(R.id.widget_compact_sub, "点卡片登录 Pace")
            views.setTextViewText(R.id.widget_compact_action, "打开 Pace")
            views.setOnClickPendingIntent(R.id.widget_compact_action, buildOpenAnalyticsIntent(context, 303))
            return views
        }

        views.setOnClickPendingIntent(
            R.id.widget_compact_action,
            buildProviderActionIntent(context, LabCompactWidgetProvider::class.java, ACTION_WIDGET_LAB_TOGGLE, 304),
        )

        if (snapshot == null) {
            views.setImageViewResource(R.id.widget_compact_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_compact_value, "--")
            views.setTextColor(R.id.widget_compact_value, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_compact_sub, if (lastError.isNotBlank()) lastError else "点刷新同步")
            views.setTextViewText(R.id.widget_compact_action, "到达打卡")
            return views
        }

        val extraLive = liveExtraMinutes(snapshot)
        val displayToday = snapshot.todayLabMinutes + extraLive
        val displayWeek = snapshot.weekLabMinutes + extraLive
        views.setTextViewText(R.id.widget_compact_value, formatDurationMinutes(displayToday))
        if (snapshot.labActive) {
            views.setImageViewResource(R.id.widget_compact_dot, R.drawable.widget_dot_green)
            views.setTextColor(R.id.widget_compact_value, 0xFF059669.toInt())
            views.setTextViewText(R.id.widget_compact_sub, "学习中 · 本周 ${formatDurationMinutes(displayWeek)}")
            views.setTextViewText(R.id.widget_compact_action, "离开签退")
        } else {
            views.setImageViewResource(R.id.widget_compact_dot, R.drawable.widget_dot_teal)
            views.setTextColor(R.id.widget_compact_value, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_compact_sub, "本周 ${formatDurationMinutes(displayWeek)} · ${snapshot.labStreakDays}天")
            views.setTextViewText(R.id.widget_compact_action, "到达打卡")
        }
        return views
    }

    private fun buildCompactSleepRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sleep_compact)
        val hasSession = PaceRepository(context).hasSession()
        val snapshot = WidgetDataStore.loadSnapshot(context)
        val lastError = WidgetDataStore.lastError(context)

        views.setOnClickPendingIntent(R.id.widget_sleep_compact_root, buildOpenAnalyticsIntent(context, 501))
        views.setOnClickPendingIntent(
            R.id.widget_sleep_compact_refresh,
            buildProviderActionIntent(context, SleepCompactWidgetProvider::class.java, ACTION_WIDGET_REFRESH, 502),
        )

        if (!hasSession) {
            views.setImageViewResource(R.id.widget_sleep_compact_dot, R.drawable.widget_dot_indigo)
            views.setTextViewText(R.id.widget_sleep_compact_duration, "未登录")
            views.setTextColor(R.id.widget_sleep_compact_duration, 0xFF5A6B78.toInt())
            views.setViewVisibility(R.id.widget_sleep_compact_score, View.GONE)
            views.setTextViewText(R.id.widget_sleep_compact_bedtime, "--:--")
            views.setTextViewText(R.id.widget_sleep_compact_wake, "--:--")
            views.setTextViewText(R.id.widget_sleep_compact_footer, "点卡片登录 Pace")
            views.setTextViewText(R.id.widget_sleep_compact_sync, "")
            return views
        }

        if (snapshot == null || !snapshot.sleepHasData) {
            views.setImageViewResource(R.id.widget_sleep_compact_dot, R.drawable.widget_dot_indigo)
            views.setTextViewText(R.id.widget_sleep_compact_duration, "--")
            views.setTextColor(R.id.widget_sleep_compact_duration, 0xFF5B5CE2.toInt())
            views.setViewVisibility(R.id.widget_sleep_compact_score, View.GONE)
            views.setTextViewText(R.id.widget_sleep_compact_bedtime, "--:--")
            views.setTextViewText(R.id.widget_sleep_compact_wake, "--:--")
            views.setTextViewText(
                R.id.widget_sleep_compact_footer,
                if (lastError.isNotBlank()) "同步受限" else "点刷新同步",
            )
            views.setTextViewText(
                R.id.widget_sleep_compact_sync,
                if (snapshot == null) "" else WidgetDataStore.lastSyncFormatted(context).removeSuffix(" 同步"),
            )
            return views
        }

        val resolved = resolveSleepSummary(snapshot)
        views.setImageViewResource(R.id.widget_sleep_compact_dot, R.drawable.widget_dot_indigo)
        views.setTextViewText(
            R.id.widget_sleep_compact_duration,
            formatCompactDuration(resolved.latestDurationMinutes),
        )
        views.setTextColor(R.id.widget_sleep_compact_duration, 0xFF5B5CE2.toInt())

        val score = resolved.latestScore
        if (score != null && score > 0) {
            views.setViewVisibility(R.id.widget_sleep_compact_score, View.VISIBLE)
            views.setTextViewText(R.id.widget_sleep_compact_score, "${score}分")
            views.setTextColor(R.id.widget_sleep_compact_score, sleepScoreColorInt(score))
        } else {
            views.setViewVisibility(R.id.widget_sleep_compact_score, View.GONE)
        }

        views.setTextViewText(R.id.widget_sleep_compact_bedtime, resolved.latestBedtime.ifBlank { "--:--" })
        views.setTextViewText(R.id.widget_sleep_compact_wake, resolved.latestWakeTime.ifBlank { "--:--" })

        val footerText = listOfNotNull(
            resolved.shortDate.ifBlank { null },
            "均 ${formatCompactDuration(snapshot.avgSleepMinutes)}",
        ).joinToString(" · ")
        views.setTextViewText(R.id.widget_sleep_compact_footer, footerText)
        views.setTextViewText(
            R.id.widget_sleep_compact_sync,
            if (lastError.isNotBlank()) "受限" else WidgetDataStore.lastSyncFormatted(context).removeSuffix(" 同步"),
        )
        return views
    }

    private fun buildOverviewRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pace_overview)
        val hasSession = PaceRepository(context).hasSession()
        val snapshot = WidgetDataStore.loadSnapshot(context)
        val lastError = WidgetDataStore.lastError(context)

        views.setOnClickPendingIntent(R.id.widget_overview_root, buildOpenAnalyticsIntent(context, 401))
        views.setOnClickPendingIntent(
            R.id.widget_overview_refresh,
            buildProviderActionIntent(context, PaceOverviewWidgetProvider::class.java, ACTION_WIDGET_REFRESH, 402),
        )

        if (!hasSession) {
            views.setImageViewResource(R.id.widget_overview_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_overview_lab_badge, "未登录")
            views.setInt(R.id.widget_overview_lab_badge, "setBackgroundResource", R.drawable.widget_pill_muted)
            views.setTextColor(R.id.widget_overview_lab_badge, 0xFF5A6B78.toInt())
            views.setTextViewText(R.id.widget_overview_lab_sub, "")
            views.setTextViewText(R.id.widget_overview_action, "登录")
            views.setOnClickPendingIntent(R.id.widget_overview_action, buildOpenAnalyticsIntent(context, 403))
            views.setImageViewBitmap(
                R.id.widget_overview_lab_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF0E5A6F.toInt(),
                    emptyLabel = "点击打开 Pace 登录授权",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                    heightDp = 56f,
                    maxDensityScale = 1.9f,
                ),
            )
            views.setTextViewText(R.id.widget_overview_sleep_badge, "未登录")
            views.setViewVisibility(R.id.widget_overview_sleep_score, View.GONE)
            views.setTextViewText(R.id.widget_overview_sleep_sub, "")
            views.setImageViewBitmap(
                R.id.widget_overview_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = emptyList(),
                    emptyLabel = "登录后同步 COROS / 睡眠记录",
                    heightDp = 102f,
                    widthDp = 310f,
                    maxDensityScale = 1.9f,
                    compactMode = true,
                ),
            )
            views.setTextViewText(R.id.widget_overview_footer, "点击进入 PaceApp")
            return views
        }

        views.setOnClickPendingIntent(
            R.id.widget_overview_action,
            buildProviderActionIntent(context, PaceOverviewWidgetProvider::class.java, ACTION_WIDGET_LAB_TOGGLE, 404),
        )

        if (snapshot == null) {
            views.setImageViewResource(R.id.widget_overview_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_overview_lab_badge, "同步中")
            views.setInt(R.id.widget_overview_lab_badge, "setBackgroundResource", R.drawable.widget_pill_teal)
            views.setTextColor(R.id.widget_overview_lab_badge, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_overview_lab_sub, "")
            views.setTextViewText(R.id.widget_overview_action, "打卡")
            views.setImageViewBitmap(
                R.id.widget_overview_lab_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF0E5A6F.toInt(),
                    emptyLabel = if (lastError.isNotBlank()) lastError else "正在拉取数据...",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                    heightDp = 56f,
                    maxDensityScale = 1.9f,
                ),
            )
            views.setTextViewText(R.id.widget_overview_sleep_badge, "同步中")
            views.setViewVisibility(R.id.widget_overview_sleep_score, View.GONE)
            views.setTextViewText(R.id.widget_overview_sleep_sub, "")
            views.setImageViewBitmap(
                R.id.widget_overview_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = emptyList(),
                    emptyLabel = "等待睡眠数据同步",
                    heightDp = 102f,
                    widthDp = 310f,
                    maxDensityScale = 1.9f,
                    compactMode = true,
                ),
            )
            views.setTextViewText(R.id.widget_overview_footer, "点击右上角刷新")
            return views
        }

        val extraLive = liveExtraMinutes(snapshot)
        val displayToday = snapshot.todayLabMinutes + extraLive
        val displayWeek = snapshot.weekLabMinutes + extraLive

        if (snapshot.labActive) {
            views.setImageViewResource(R.id.widget_overview_lab_dot, R.drawable.widget_dot_green)
            views.setTextViewText(R.id.widget_overview_lab_badge, "学习中 · ${formatDurationMinutes(displayToday)}")
            views.setInt(R.id.widget_overview_lab_badge, "setBackgroundResource", R.drawable.widget_pill_green)
            views.setTextColor(R.id.widget_overview_lab_badge, 0xFF059669.toInt())
            views.setTextViewText(R.id.widget_overview_action, "签退")
        } else {
            views.setImageViewResource(R.id.widget_overview_lab_dot, R.drawable.widget_dot_teal)
            views.setTextViewText(R.id.widget_overview_lab_badge, "今日 ${formatDurationMinutes(displayToday)}")
            views.setInt(R.id.widget_overview_lab_badge, "setBackgroundResource", R.drawable.widget_pill_teal)
            views.setTextColor(R.id.widget_overview_lab_badge, 0xFF0E5A6F.toInt())
            views.setTextViewText(R.id.widget_overview_action, "打卡")
        }
        views.setTextViewText(
            R.id.widget_overview_lab_sub,
            "本周 ${formatDurationMinutes(displayWeek)} · 连续 ${snapshot.labStreakDays}天",
        )
        views.setImageViewBitmap(
            R.id.widget_overview_lab_chart,
            WidgetChartRenderer.renderSevenDayBarChart(
                context = context,
                points = WidgetDataStore.toDailyPoints(snapshot.labDailySeries),
                accentColor = 0xFF0E5A6F.toInt(),
                emptyLabel = "近 7 天暂无实验室打卡",
                valueLabelFormatter = { formatCompactDuration(it.toInt()) },
                heightDp = 56f,
                maxDensityScale = 1.9f,
            ),
        )

        if (!snapshot.sleepHasData) {
            views.setTextViewText(R.id.widget_overview_sleep_badge, "待接入")
            views.setViewVisibility(R.id.widget_overview_sleep_score, View.GONE)
            views.setTextViewText(R.id.widget_overview_sleep_sub, "支持 COROS / 手动")
            views.setImageViewBitmap(
                R.id.widget_overview_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = emptyList(),
                    emptyLabel = "同步高驰或手动记录后展示作息图",
                    heightDp = 102f,
                    widthDp = 310f,
                    maxDensityScale = 1.9f,
                    compactMode = true,
                ),
            )
        } else {
            val resolved = resolveSleepSummary(snapshot)
            val badgeText = buildString {
                if (resolved.shortDate.isNotBlank()) {
                    append(resolved.shortDate)
                    append(" ")
                }
                append(formatCompactDuration(resolved.latestDurationMinutes))
            }
            views.setTextViewText(R.id.widget_overview_sleep_badge, badgeText)

            val score = resolved.latestScore
            if (score != null && score > 0) {
                views.setViewVisibility(R.id.widget_overview_sleep_score, View.VISIBLE)
                views.setTextViewText(R.id.widget_overview_sleep_score, "${score}分")
                views.setTextColor(R.id.widget_overview_sleep_score, sleepScoreColorInt(score))
            } else {
                views.setViewVisibility(R.id.widget_overview_sleep_score, View.GONE)
            }

            val subParts = buildList {
                if (resolved.latestBedtime.isNotBlank() || resolved.latestWakeTime.isNotBlank()) {
                    add("${resolved.latestBedtime.ifBlank { "--:--" }}→${resolved.latestWakeTime.ifBlank { "--:--" }}")
                }
                add("均 ${formatCompactDuration(snapshot.avgSleepMinutes)}")
            }
            views.setTextViewText(R.id.widget_overview_sleep_sub, subParts.joinToString(" · "))
            views.setImageViewBitmap(
                R.id.widget_overview_sleep_chart,
                WidgetChartRenderer.renderSleepTimelineChart(
                    context = context,
                    nights = resolved.nights,
                    emptyLabel = "近 7 晚暂无睡眠记录",
                    heightDp = 102f,
                    widthDp = 310f,
                    maxDensityScale = 1.9f,
                    compactMode = true,
                ),
            )
        }

        views.setTextViewText(
            R.id.widget_overview_footer,
            if (lastError.isNotBlank()) "同步受限" else WidgetDataStore.lastSyncFormatted(context),
        )
        return views
    }
}
