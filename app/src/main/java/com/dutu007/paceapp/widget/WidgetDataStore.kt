package com.dutu007.paceapp.widget

import android.content.Context
import com.dutu007.paceapp.data.AnalyticsDashboard
import com.dutu007.paceapp.data.DailyMetricPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class WidgetDayPoint(
    val date: String,
    val label: String,
    val weekday: String,
    val value: Double,
    val count: Int = 0,
)

@Serializable
data class WidgetSnapshot(
    val todayLabMinutes: Int = 0,
    val weekLabMinutes: Int = 0,
    val totalLabMinutes: Int = 0,
    val labStreakDays: Int = 0,
    val labActive: Boolean = false,
    val labArrive: String = "",
    val labDailySeries: List<WidgetDayPoint> = emptyList(),
    val sleepHasData: Boolean = false,
    val avgSleepMinutes: Int = 0,
    val latestSleepMinutes: Int = 0,
    val sleepAvgScore: Int? = null,
    val sleepDailySeries: List<WidgetDayPoint> = emptyList(),
    val lastSyncEpochMs: Long = 0L,
    val lastError: String = "",
)

object WidgetDataStore {
    private const val PREF_NAME = "pace_widget_cache"
    private const val KEY_SNAPSHOT = "snapshot_json"
    private const val KEY_LAST_ERROR = "last_error"
    private val json = Json { ignoreUnknownKeys = true }

    fun saveDashboard(context: Context, dashboard: AnalyticsDashboard, nowMs: Long = System.currentTimeMillis()) {
        val snapshot = WidgetSnapshot(
            todayLabMinutes = dashboard.overview.todayLabMinutes,
            weekLabMinutes = dashboard.overview.weekLabMinutes,
            totalLabMinutes = dashboard.overview.totalLabMinutes,
            labStreakDays = dashboard.overview.labStreakDays,
            labActive = dashboard.lab.activeSession != null,
            labArrive = dashboard.lab.activeSession?.arrive.orEmpty(),
            labDailySeries = dashboard.lab.dailySeries.takeLast(7).map {
                WidgetDayPoint(it.date, it.label, it.weekday, it.value, it.count)
            },
            sleepHasData = dashboard.sleep.hasData,
            avgSleepMinutes = dashboard.sleep.avgDurationMinutes,
            latestSleepMinutes = dashboard.sleep.latestDurationMinutes,
            sleepAvgScore = dashboard.sleep.avgScore,
            sleepDailySeries = dashboard.sleep.dailySeries.takeLast(7).map {
                WidgetDayPoint(it.date, it.label, it.weekday, it.durationMinutes.toDouble(), if (it.durationMinutes > 0) 1 else 0)
            },
            lastSyncEpochMs = nowMs,
            lastError = "",
        )
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(snapshot))
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun loadSnapshot(context: Context): WidgetSnapshot? {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SNAPSHOT, null)
            ?: return null
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrNull()
    }

    fun recordError(context: Context, message: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ERROR, message)
            .apply()
    }

    fun lastError(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "")
            .orEmpty()
    }

    fun lastSyncFormatted(context: Context): String {
        val epoch = loadSnapshot(context)?.lastSyncEpochMs ?: 0L
        if (epoch <= 0L) return "暂未同步"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${fmt.format(Date(epoch))} 同步"
    }

    fun toDailyPoints(items: List<WidgetDayPoint>): List<DailyMetricPoint> {
        return items.map { DailyMetricPoint(it.date, it.label, it.weekday, it.value, it.count) }
    }
}
