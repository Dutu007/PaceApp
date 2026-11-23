package com.dutu007.paceapp.push

import android.app.ActivityManager
import android.content.Context
import com.dutu007.paceapp.data.PushNotificationItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PushConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    DISABLED,
}

object PacePushPrefs {
    private const val PREF_NAME = "pace_push_state"
    private const val KEY_LIVE_ENABLED = "live_enabled"
    private const val KEY_LAST_SEEN_ID = "last_seen_id"
    private const val KEY_LAST_NOTIFIED_ID = "last_notified_id"
    private const val KEY_PERM_ASKED = "perm_asked"
    private const val KEY_HIDE_RECENTS = "hide_from_recents"

    private val _connectionState = MutableStateFlow(PushConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PushConnectionState> = _connectionState.asStateFlow()

    private val _incomingNotifications = MutableSharedFlow<PushNotificationItem>(extraBufferCapacity = 32)
    val incomingNotifications: SharedFlow<PushNotificationItem> = _incomingNotifications.asSharedFlow()

    fun isLiveEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_ENABLED, true)
    }

    fun setLiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
        if (!enabled) {
            _connectionState.value = PushConnectionState.DISABLED
        }
    }

    fun lastSeenId(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_SEEN_ID, 0L)
    }

    fun updateLastSeenId(context: Context, id: Long) {
        if (id <= 0L) return
        synchronized(this) {
            val current = prefs(context).getLong(KEY_LAST_SEEN_ID, 0L)
            if (id > current) {
                prefs(context).edit().putLong(KEY_LAST_SEEN_ID, id).apply()
            }
        }
    }

    fun lastNotifiedId(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_NOTIFIED_ID, 0L)
    }

    fun updateLastNotifiedId(context: Context, id: Long) {
        if (id <= 0L) return
        synchronized(this) {
            val current = prefs(context).getLong(KEY_LAST_NOTIFIED_ID, 0L)
            if (id > current) {
                prefs(context).edit().putLong(KEY_LAST_NOTIFIED_ID, id).apply()
            }
        }
    }

    fun hasAskedPermission(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PERM_ASKED, false)
    }

    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_PERM_ASKED, true).apply()
    }

    fun isHideFromRecents(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_RECENTS, false)
    }

    fun setHideFromRecents(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_RECENTS, enabled).apply()
        applyExcludeFromRecents(context, enabled)
    }

    fun applyExcludeFromRecents(context: Context, exclude: Boolean) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            am.appTasks.forEach { task ->
                runCatching { task.setExcludeFromRecents(exclude) }
            }
        }
    }

    fun resetSessionState(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_SEEN_ID)
            .remove(KEY_LAST_NOTIFIED_ID)
            .apply()
        _connectionState.value = PushConnectionState.DISCONNECTED
    }

    fun setConnectionState(state: PushConnectionState) {
        _connectionState.value = state
    }

    fun emitIncoming(item: PushNotificationItem) {
        _incomingNotifications.tryEmit(item)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
