package com.dutu007.paceapp.widget

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dutu007.paceapp.data.AnalyticsDashboard
import com.dutu007.paceapp.data.ApiException
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.push.PaceNotificationHelper
import com.dutu007.paceapp.push.PacePushPrefs
import com.dutu007.paceapp.push.PacePushService
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = PaceRepository(applicationContext)
        if (!repo.hasSession()) {
            PaceWidgetUpdater.updateAllWidgets(applicationContext)
            return Result.success()
        }

        val action = inputData.getString(KEY_ACTION) ?: ACTION_REFRESH
        return try {
            if (action == ACTION_LAB_TOGGLE) {
                val before = runCatching { repo.getAnalytics("7d") }.getOrNull()
                val currentlyActive = before?.lab?.activeSession != null
                    || (before == null && WidgetDataStore.loadSnapshot(applicationContext)?.labActive == true)
                if (currentlyActive) {
                    repo.labCheckOut()
                } else {
                    repo.labCheckIn()
                }
            }
            val dashboard = repo.getAnalytics("7d")
            WidgetDataStore.saveDashboard(applicationContext, dashboard)
            PaceWidgetUpdater.updateAllWidgets(applicationContext)
            runCatching {
                val afterId = PacePushPrefs.lastSeenId(applicationContext)
                if (afterId > 0L) {
                    val missed = repo.listNotifications(limit = 20, unreadOnly = true, afterId = afterId)
                    missed.items.forEach { item ->
                        PaceNotificationHelper.showAlertNotification(applicationContext, item)
                    }
                }
            }
            PacePushService.syncServiceState(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.statusCode == 401) {
                WidgetDataStore.recordError(applicationContext, "登录已过期")
                PaceWidgetUpdater.updateAllWidgets(applicationContext)
                Result.success()
            } else {
                WidgetDataStore.recordError(applicationContext, "同步失败 (${e.statusCode})")
                PaceWidgetUpdater.updateAllWidgets(applicationContext)
                if (runAttemptCount < 2 && e.statusCode >= 500) Result.retry() else Result.failure()
            }
        } catch (_: Exception) {
            WidgetDataStore.recordError(applicationContext, "网络暂不可用")
            PaceWidgetUpdater.updateAllWidgets(applicationContext)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_ACTION = "widget_action"
        const val ACTION_REFRESH = "refresh"
        const val ACTION_LAB_TOGGLE = "lab_toggle"
    }
}

object WidgetSyncManager {
    private const val PERIODIC_WORK_NAME = "pace_widget_periodic_sync"
    private const val IMMEDIATE_WORK_NAME = "pace_widget_immediate_sync"

    fun ensurePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueImmediateSync(
        context: Context,
        action: String = WidgetSyncWorker.ACTION_REFRESH,
    ) {
        val builder = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
            .setInputData(workDataOf(WidgetSyncWorker.KEY_ACTION to action))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build(),
        )
    }

    fun onDashboardLoadedInApp(context: Context, dashboard: AnalyticsDashboard) {
        WidgetDataStore.saveDashboard(context.applicationContext, dashboard)
        PaceWidgetUpdater.updateAllWidgets(context.applicationContext)
        if (PaceWidgetUpdater.hasAnyWidgets(context.applicationContext)) {
            ensurePeriodicSync(context.applicationContext)
        }
    }
}
