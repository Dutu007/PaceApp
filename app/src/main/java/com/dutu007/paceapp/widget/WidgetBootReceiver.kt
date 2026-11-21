package com.dutu007.paceapp.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PaceWidgetUpdater.updateAllWidgets(context)
            if (PaceWidgetUpdater.hasAnyWidgets(context)) {
                WidgetSyncManager.ensurePeriodicSync(context)
                WidgetSyncManager.enqueueImmediateSync(context, WidgetSyncWorker.ACTION_REFRESH)
            }
        }
    }
}
