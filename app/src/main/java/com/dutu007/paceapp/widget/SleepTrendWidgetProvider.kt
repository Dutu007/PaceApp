package com.dutu007.paceapp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class SleepTrendWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        PaceWidgetUpdater.updateAllWidgets(context)
        WidgetSyncManager.ensurePeriodicSync(context)
        WidgetSyncManager.enqueueImmediateSync(context, WidgetSyncWorker.ACTION_REFRESH)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        PaceWidgetUpdater.updateAllWidgets(context)
        WidgetSyncManager.ensurePeriodicSync(context)
        WidgetSyncManager.enqueueImmediateSync(context, WidgetSyncWorker.ACTION_REFRESH)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == PaceWidgetUpdater.ACTION_WIDGET_REFRESH) {
            WidgetSyncManager.enqueueImmediateSync(context, WidgetSyncWorker.ACTION_REFRESH)
        }
    }
}
