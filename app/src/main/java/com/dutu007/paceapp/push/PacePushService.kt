package com.dutu007.paceapp.push

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dutu007.paceapp.BuildConfig
import com.dutu007.paceapp.data.PaceRepository
import com.dutu007.paceapp.data.PushNotificationItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private val pushJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class PushNotificationWireDto(
    val id: Long = 0L,
    val title: String = "",
    val body: String = "",
    val category: String = "reminder",
    @SerialName("session_id") val sessionId: String = "",
    val source: String = "hermes",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

fun parsePushSseNotification(rawData: String): PushNotificationItem? {
    val trimmed = rawData.trim()
    if (trimmed.isEmpty()) return null
    val dto = runCatching { pushJson.decodeFromString<PushNotificationWireDto>(trimmed) }.getOrNull()
        ?: return null
    if (dto.id <= 0L || dto.body.isBlank()) return null
    return PushNotificationItem(
        id = dto.id,
        title = dto.title,
        body = dto.body,
        category = dto.category,
        sessionId = dto.sessionId,
        source = dto.source,
        isRead = dto.isRead,
        createdAt = dto.createdAt,
    )
}

fun parseConnectedLatestId(rawData: String): Long? {
    val obj = runCatching { pushJson.parseToJsonElement(rawData.trim()).jsonObject }.getOrNull() ?: return null
    return obj["latest_id"]?.jsonPrimitive?.longOrNull
        ?: obj["latest_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}

class PacePushService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private val networkWakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PaceNotificationHelper.ensureChannels(this)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkWakeSignal.trySend(Unit)
                }
            }
            networkCallback = cb
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = PaceRepository(this)
        val enabled = PacePushPrefs.isLiveEnabled(this)
        val hasSession = repo.hasSession()

        val initialState = if (enabled && hasSession) {
            PushConnectionState.CONNECTING
        } else if (!enabled) {
            PushConnectionState.DISABLED
        } else {
            PushConnectionState.DISCONNECTED
        }
        promoteForeground(initialState)

        if (!enabled || !hasSession) {
            PacePushPrefs.setConnectionState(initialState)
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (streamJob?.isActive != true) {
            streamJob = serviceScope.launch { runEventLoop() }
        } else {
            networkWakeSignal.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        streamJob?.cancel()
        serviceScope.cancel()
        if (PacePushPrefs.connectionState.value != PushConnectionState.DISABLED) {
            PacePushPrefs.setConnectionState(PushConnectionState.DISCONNECTED)
        }
        super.onDestroy()
    }

    private fun promoteForeground(state: PushConnectionState) {
        val notif = PaceNotificationHelper.buildServiceNotification(this, state)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, PaceNotificationHelper.SERVICE_NOTIFICATION_ID, notif, fgsType)
    }

    private fun updateServiceBanner(state: PushConnectionState) {
        PacePushPrefs.setConnectionState(state)
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            mgr.notify(
                PaceNotificationHelper.SERVICE_NOTIFICATION_ID,
                PaceNotificationHelper.buildServiceNotification(this, state),
            )
        }
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runEventLoop() {
        val backoffsMs = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        var attempt = 0
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')

        while (serviceScope.isActive && PacePushPrefs.isLiveEnabled(this)) {
            val token = PaceRepository(this).authToken()
            if (token.isBlank()) {
                PacePushPrefs.setConnectionState(PushConnectionState.DISCONNECTED)
                stopSelfCleanly()
                return
            }

            updateServiceBanner(PushConnectionState.CONNECTING)
            var conn: HttpURLConnection? = null
            var shouldStop = false
            try {
                val afterId = PacePushPrefs.lastSeenId(this)
                val url = URL("$baseUrl/api/v1/events/stream?after_id=$afterId")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    doInput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "text/event-stream")
                }

                val code = conn.responseCode
                if (code == 401) {
                    shouldStop = true
                } else if (code in 200..299) {
                    attempt = 0
                    BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        var eventName = "message"
                        val dataBuf = StringBuilder()
                        while (serviceScope.isActive && PacePushPrefs.isLiveEnabled(this)) {
                            val line = reader.readLine() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (dataBuf.isNotEmpty()) {
                                        handleFrame(eventName, dataBuf.toString())
                                    }
                                    eventName = "message"
                                    dataBuf.setLength(0)
                                }
                                line.startsWith(":") -> Unit
                                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                    dataBuf.append(line.removePrefix("data:").trimStart())
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Network dropped or read timed out; handled via exponential backoff below.
            } finally {
                runCatching { conn?.disconnect() }
            }

            if (shouldStop || !PacePushPrefs.isLiveEnabled(this)) {
                PacePushPrefs.setConnectionState(
                    if (PacePushPrefs.isLiveEnabled(this)) PushConnectionState.DISCONNECTED else PushConnectionState.DISABLED,
                )
                stopSelfCleanly()
                return
            }

            updateServiceBanner(PushConnectionState.DISCONNECTED)
            val waitMs = backoffsMs[attempt.coerceAtMost(backoffsMs.lastIndex)]
            attempt++
            while (networkWakeSignal.tryReceive().isSuccess) {
                // Drain stale wakeups before waiting
            }
            val wokeEarly = withTimeoutOrNull(waitMs) {
                networkWakeSignal.receive()
                true
            } ?: false
            if (wokeEarly) {
                attempt = 0
                delay(400L)
            }
        }
    }

    private fun handleFrame(eventName: String, rawData: String) {
        when (eventName) {
            "connected" -> {
                updateServiceBanner(PushConnectionState.CONNECTED)
                val latestId = parseConnectedLatestId(rawData) ?: 0L
                if (PacePushPrefs.lastSeenId(this) == 0L && latestId > 0L) {
                    PacePushPrefs.updateLastSeenId(this, latestId)
                    PacePushPrefs.updateLastNotifiedId(this, latestId)
                }
            }
            "notification" -> {
                val item = parsePushSseNotification(rawData) ?: return
                PaceNotificationHelper.showAlertNotification(this, item)
                PacePushPrefs.emitIncoming(item)
            }
        }
    }

    companion object {
        fun syncServiceState(context: Context) {
            val appCtx = context.applicationContext
            val hasSession = PaceRepository(appCtx).hasSession()
            val enabled = PacePushPrefs.isLiveEnabled(appCtx)
            if (hasSession && enabled) {
                runCatching {
                    ContextCompat.startForegroundService(appCtx, Intent(appCtx, PacePushService::class.java))
                }
            } else {
                if (!enabled) {
                    PacePushPrefs.setConnectionState(PushConnectionState.DISABLED)
                } else {
                    PacePushPrefs.setConnectionState(PushConnectionState.DISCONNECTED)
                }
                runCatching { appCtx.stopService(Intent(appCtx, PacePushService::class.java)) }
            }
        }
    }
}
