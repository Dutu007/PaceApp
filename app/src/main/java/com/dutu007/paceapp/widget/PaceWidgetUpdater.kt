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
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.formatDurationMinutes
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
        val sleepIds = mgr.getAppWidgetIds(ComponentName(context, SleepTrendWidgetProvider::class.java))
        return labIds.isNotEmpty() || sleepIds.isNotEmpty()
    }

    fun updateAllWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val labIds = mgr.getAppWidgetIds(ComponentName(context, LabStudyWidgetProvider::class.java))
        if (labIds.isNotEmpty()) {
            val views = buildLabRemoteViews(context)
            mgr.updateAppWidget(labIds, views)
        }
        val sleepIds = mgr.getAppWidgetIds(ComponentName(context, SleepTrendWidgetProvider::class.java))
        if (sleepIds.isNotEmpty()) {
            val views = buildSleepRemoteViews(context)
            mgr.updateAppWidget(sleepIds, views)
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
                valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
            ),
        )

        val footerLeft = "本周 ${formatDurationMinutes(displayWeek)} · 连续 ${snapshot.labStreakDays} 天"
        val footerRight = if (lastError.isNotBlank()) "同步受限" else WidgetDataStore.lastSyncFormatted(context)
        views.setTextViewText(R.id.widget_lab_footer_left, footerLeft)
        views.setTextViewText(R.id.widget_lab_footer_right, footerRight)
        return views
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
            views.setImageViewBitmap(
                R.id.widget_sleep_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF5B5CE2.toInt(),
                    emptyLabel = "点击打开 PaceApp 登录后同步睡眠数据",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                ),
            )
            views.setTextViewText(R.id.widget_sleep_footer_left, "支持 COROS 手表同步")
            views.setTextViewText(R.id.widget_sleep_footer_right, "点卡片进入")
            return views
        }

        if (snapshot == null || !snapshot.sleepHasData) {
            views.setTextViewText(R.id.widget_sleep_badge, "待接入")
            views.setViewVisibility(R.id.widget_sleep_score, View.GONE)
            views.setImageViewBitmap(
                R.id.widget_sleep_chart,
                WidgetChartRenderer.renderSevenDayBarChart(
                    context = context,
                    points = emptyList(),
                    accentColor = 0xFF5B5CE2.toInt(),
                    emptyLabel = "待接入高驰 COROS 或手动睡眠数据",
                    valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
                ),
            )
            views.setTextViewText(R.id.widget_sleep_footer_left, "在 Pace 同步高驰或手动录入后展示")
            views.setTextViewText(
                R.id.widget_sleep_footer_right,
                if (snapshot == null) "等待同步" else WidgetDataStore.lastSyncFormatted(context),
            )
            return views
        }

        views.setTextViewText(R.id.widget_sleep_badge, "昨夜 ${formatDurationMinutes(snapshot.latestSleepMinutes)}")
        val score = snapshot.sleepAvgScore
        if (score != null && score > 0) {
            views.setViewVisibility(R.id.widget_sleep_score, View.VISIBLE)
            views.setTextViewText(R.id.widget_sleep_score, "${score}分")
        } else {
            views.setViewVisibility(R.id.widget_sleep_score, View.GONE)
        }

        val sleepPoints = WidgetDataStore.toDailyPoints(snapshot.sleepDailySeries)
        views.setImageViewBitmap(
            R.id.widget_sleep_chart,
            WidgetChartRenderer.renderSevenDayBarChart(
                context = context,
                points = sleepPoints,
                accentColor = 0xFF5B5CE2.toInt(),
                emptyLabel = "近 7 天暂无睡眠曲线",
                valueLabelFormatter = { formatDurationMinutes(it.toInt()) },
            ),
        )
        views.setTextViewText(
            R.id.widget_sleep_footer_left,
            "近期均睡 ${formatDurationMinutes(snapshot.avgSleepMinutes)}",
        )
        views.setTextViewText(
            R.id.widget_sleep_footer_right,
            if (lastError.isNotBlank()) "同步受限" else WidgetDataStore.lastSyncFormatted(context),
        )
        return views
    }
}
