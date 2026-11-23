package com.dutu007.paceapp.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dutu007.paceapp.MainActivity
import com.dutu007.paceapp.R
import com.dutu007.paceapp.data.PushNotificationItem

object PaceNotificationHelper {
    const val CHANNEL_ALERTS = "pace_hermes_alerts"
    const val CHANNEL_SERVICE = "pace_live_connection"
    const val SERVICE_NOTIFICATION_ID = 1001
    private val notifyLock = Any()

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "小洛主动提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "接收小洛定时汇报、任务提醒与状态通知"
            enableVibration(true)
            setShowBadge(true)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "小洛实时连接状态",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "保持与服务器的长连接以秒级接收通知（静音折叠）"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(alerts)
        mgr.createNotificationChannel(service)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isServiceChannelHidden(context: Context): Boolean {
        ensureChannels(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val ch = mgr.getNotificationChannel(CHANNEL_SERVICE) ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun openServiceChannelSettings(context: Context) {
        ensureChannels(context)
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_SERVICE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(channelIntent) }.onFailure {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    fun buildServiceNotification(context: Context, state: PushConnectionState): Notification {
        ensureChannels(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            5100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, body) = if (state == PushConnectionState.DISABLED) {
            "小洛实时连接已关闭" to ""
        } else {
            "小洛后台通道" to "静默保活中（可在设置一键隐藏此常驻条）"
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_pace_notif)
            .setColor(0xFF0E5A6F.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(pi)
            .build()
    }

    fun showAlertNotification(context: Context, item: PushNotificationItem) {
        synchronized(notifyLock) {
            val prevNotified = PacePushPrefs.lastNotifiedId(context)
            if (item.id <= prevNotified) return
            PacePushPrefs.updateLastNotifiedId(context, item.id)
            PacePushPrefs.updateLastSeenId(context, item.id)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.dutu007.paceapp.OPEN_NOTIFICATION"
            putExtra(MainActivity.EXTRA_OPEN_TAB, "CHAT")
            putExtra(MainActivity.EXTRA_OPEN_NOTIFICATION_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            6000 + (item.id % 1000).toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = item.title.ifBlank { "小洛提醒" }
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_pace_notif)
            .setColor(0xFF0E5A6F.toInt())
            .setContentTitle(title)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify((2000 + (item.id % 10000)).toInt(), notif)
        } catch (_: SecurityException) {
            // Permission may be revoked concurrently on Android 13+.
        }
    }
}
