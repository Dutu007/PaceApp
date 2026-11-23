package com.dutu007.paceapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import com.dutu007.paceapp.push.PaceNotificationHelper
import com.dutu007.paceapp.push.PushConnectionState
import com.dutu007.paceapp.ui.theme.PacePalette

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dutu007.paceapp.widget.OriginOsKeepAliveHelper
import com.dutu007.paceapp.widget.WidgetDataStore

val SettingsIcon = ImageVector.Builder(
    name = "Settings",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(10f, 2.8f); lineTo(14f, 2.8f); lineTo(14.6f, 5.6f)
        lineTo(16.5f, 6.7f); lineTo(19.2f, 5.8f); lineTo(21.2f, 9.2f)
        lineTo(19.2f, 11.1f); lineTo(19.2f, 12.9f); lineTo(21.2f, 14.8f)
        lineTo(19.2f, 18.2f); lineTo(16.5f, 17.3f); lineTo(14.6f, 18.4f)
        lineTo(14f, 21.2f); lineTo(10f, 21.2f); lineTo(9.4f, 18.4f)
        lineTo(7.5f, 17.3f); lineTo(4.8f, 18.2f); lineTo(2.8f, 14.8f)
        lineTo(4.8f, 12.9f); lineTo(4.8f, 11.1f); lineTo(2.8f, 9.2f)
        lineTo(4.8f, 5.8f); lineTo(7.5f, 6.7f); lineTo(9.4f, 5.6f); close()
        moveTo(15.3f, 12f)
        arcTo(3.3f, 3.3f, 0f, true, true, 8.7f, 12f)
        arcTo(3.3f, 3.3f, 0f, true, true, 15.3f, 12f); close()
    }
}.build()

val RefreshIcon = ImageVector.Builder(
    name = "Refresh",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(20f, 7f)
        curveTo(17.8f, 3.2f, 12.8f, 2.5f, 8.9f, 4.7f)
        curveTo(5f, 6.9f, 3.5f, 11.6f, 5.1f, 15.5f)
        curveTo(6.8f, 19.6f, 11.5f, 21.4f, 15.7f, 19.4f)
        curveTo(18.2f, 18.2f, 19.8f, 15.8f, 20.2f, 13f)
        moveTo(20f, 3f); lineTo(20f, 7f); lineTo(16f, 7f)
    }
}.build()

val BackIcon = ImageVector.Builder(
    name = "BackChevron",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = true,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(15f, 18f)
        lineTo(9f, 12f)
        lineTo(15f, 6f)
    }
}.build()

val MicIcon = ImageVector.Builder(
    name = "Mic",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12f, 3f)
        curveTo(10.34f, 3f, 9f, 4.34f, 9f, 6f)
        lineTo(9f, 11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
        curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
        lineTo(15f, 6f)
        curveTo(15f, 4.34f, 13.66f, 3f, 12f, 3f)
        close()
        moveTo(5.5f, 11f)
        curveTo(5.5f, 14.59f, 8.41f, 17.5f, 12f, 17.5f)
        curveTo(15.59f, 17.5f, 18.5f, 14.59f, 18.5f, 11f)
        moveTo(12f, 17.5f); lineTo(12f, 21f)
        moveTo(9f, 21f); lineTo(15f, 21f)
    }
}.build()

@Composable
fun AppBackButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(BackIcon, contentDescription = "返回")
    }
}

val CameraIcon = ImageVector.Builder(
    name = "Camera",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5f); lineTo(15f, 5f); lineTo(16.5f, 8f); lineTo(20f, 8f)
        lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(15.2f, 13.5f)
        arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 13.5f)
        arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 13.5f); close()
    }
}.build()

val PlusIcon = ImageVector.Builder(
    name = "Plus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
}.build()

val GalleryIcon = ImageVector.Builder(
    name = "Gallery",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 15.5f); lineTo(16.5f, 13f); lineTo(20f, 16.5f)
    }
}.build()

val FileDocIcon = ImageVector.Builder(
    name = "FileDoc",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close()
        moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
        moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(9f, 16f); lineTo(13f, 16f)
    }
}.build()

private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        viewModel.refreshProactiveConfig()
    }
    var ignoringBattery by remember { mutableStateOf(OriginOsKeepAliveHelper.isIgnoringBatteryOptimizations(context)) }
    var lastWidgetSync by remember(state.analytics) { mutableStateOf(WidgetDataStore.lastSyncFormatted(context)) }
    var hasNotifPermission by remember { mutableStateOf(PaceNotificationHelper.hasNotificationPermission(context)) }
    var serviceChannelHidden by remember { mutableStateOf(PaceNotificationHelper.isServiceChannelHidden(context)) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPermission = granted || PaceNotificationHelper.hasNotificationPermission(context)
        if (granted) {
            PaceNotificationHelper.ensureChannels(context)
        } else if (!hasNotifPermission) {
            openAppNotificationSettings(context)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = OriginOsKeepAliveHelper.isIgnoringBatteryOptimizations(context)
                lastWidgetSync = WidgetDataStore.lastSyncFormatted(context)
                hasNotifPermission = PaceNotificationHelper.hasNotificationPermission(context)
                serviceChannelHidden = PaceNotificationHelper.isServiceChannelHidden(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hermesStatus = when (state.hermesOnline) {
        true -> "在线"
        false -> "离线"
        null -> "检测中..."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        AppBackButton(onClick = onBack)
                    },
                    title = {
                        Text("设置", fontWeight = FontWeight.Bold)
                    },
                    actions = {
                        IconButton(onClick = viewModel::refreshAll, enabled = !state.streaming) {
                            Icon(RefreshIcon, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val notice = state.notice
                if (notice != null) {
                    item {
                        Surface(
                            color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.dismissNotice(notice.id) },
                        ) {
                            Text(notice.text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    SettingsRow(
                        title = "当前账号",
                        subtitle = "${state.user?.displayName ?: "Dutu"} (@${state.user?.username ?: "dutu"})",
                        showArrow = false,
                        onClick = {},
                    )
                }

                item {
                    SettingsRow(
                        title = "小洛服务状态",
                        subtitle = "$hermesStatus · ${BuildConfig.API_BASE_URL}",
                        onClick = viewModel::refreshAll,
                    )
                }

                item {
                    val updateSubtitle = if (state.checkingUpdate) {
                        "正在检查更新..."
                    } else {
                        "当前版本 ${viewModel.currentVersionName}"
                    }
                    SettingsRow(
                        title = "检查更新",
                        subtitle = updateSubtitle,
                        onClick = { if (!state.checkingUpdate) viewModel.checkForUpdate(manual = true) },
                    )
                }

                item {
                    Text(
                        text = "主动提醒与实时通知",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                item {
                    val connSummary = when (state.pushConnectionState) {
                        PushConnectionState.CONNECTED -> "已在线 · 保持长连接可秒级接收主动提醒"
                        PushConnectionState.CONNECTING -> "连接中... · 正在建立实时连接"
                        PushConnectionState.DISCONNECTED -> "已断开 · 网络恢复后自动重连"
                        PushConnectionState.DISABLED -> "已关闭 · 仅通过后台周期同步拉取提醒"
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openAppNotificationSettings(context)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "通知权限",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = if (hasNotifPermission) {
                                            "已授权 · 点击可调整系统通知渠道"
                                        } else {
                                            "未授权 · 点击开启以接收小洛提醒弹窗"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = if (hasNotifPermission) "已开启" else "去授权",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "小洛随时在线长连接",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = connSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.livePushEnabled,
                                    onCheckedChange = viewModel::toggleLivePush,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { PaceNotificationHelper.openServiceChannelSettings(context) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "隐藏通知栏常驻条",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "仅关闭“小洛实时连接状态”分类即可彻底隐藏常驻通知，不影响后台保活与小洛消息提醒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = if (serviceChannelHidden) "已隐藏" else "去隐藏",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (serviceChannelHidden) PacePalette.Success else MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            val cfg = state.proactiveConfig
                            val moodLabel = when (cfg.todayMood) {
                                "energetic" -> "活跃"
                                "strict" -> "严厉督学日"
                                "quiet" -> "安静少打扰"
                                else -> "温和陪伴"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "小洛自主冒泡",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = if (cfg.enabled) {
                                            "今日 $moodLabel · 已冒泡 ${cfg.sentToday}/${cfg.todayBudget} 次 · 夜间 ${cfg.quietStart}-${cfg.quietEnd} 静默"
                                        } else {
                                            "已关闭 · 仅保留 COROS 每日同步提醒"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = cfg.enabled,
                                    enabled = !state.proactiveBusy,
                                    onCheckedChange = { viewModel.updateProactiveConfig(enabled = it) },
                                )
                            }
                            if (cfg.enabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "冒泡节奏（每天随机波动，不按固定闹钟响）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "quiet" to "偏安静",
                                            "balanced" to "适中·多变",
                                            "lively" to "活泼粘人",
                                        ).forEach { (key, label) ->
                                            FilterChip(
                                                selected = cfg.intensity == key,
                                                enabled = !state.proactiveBusy,
                                                onClick = { viewModel.updateProactiveConfig(intensity = key) },
                                                label = { Text(label, fontSize = 12.sp) },
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "严厉督学模式",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "检测到熬夜、摸鱼久不去实验室或久不运动时严厉提醒",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = cfg.strictSupervision,
                                        enabled = !state.proactiveBusy,
                                        onCheckedChange = { viewModel.updateProactiveConfig(strictSupervision = it) },
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "实验室专注期免闲聊",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "打卡学习中默认安静陪伴，签退后或久坐超 3 小时再开口",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = cfg.labFocusProtect,
                                        enabled = !state.proactiveBusy,
                                        onCheckedChange = { viewModel.updateProactiveConfig(labFocusProtect = it) },
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.proactiveBusy) { viewModel.triggerProactiveBubbleNow() }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "让小洛现在冒个泡",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = if (state.proactiveBusy) "小洛正在想说什么好..." else "结合当前作息、学习与最近对话立即生成一条冒泡消息",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = if (state.proactiveBusy) "思考中" else "试一下",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.sendTestPush() }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "发送测试通知",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "验证服务器推送通道与手机端实时弹窗提醒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "测试",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "桌面组件与后台保活",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "隐藏最近任务卡片",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "退回桌面后在多任务界面隐藏 Pace 窗口，防止顺手划掉；可从桌面图标、原子组件或通知回到应用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.hideFromRecents,
                                onCheckedChange = viewModel::toggleHideFromRecents,
                            )
                        }
                    }
                }

                item {
                    SettingsRow(
                        title = "电池优化白名单",
                        subtitle = if (ignoringBattery) "已开启（允许后台定时刷新桌面组件）" else "未开启，点击前往系统授权忽略电池优化",
                        showArrow = !ignoringBattery,
                        onClick = { OriginOsKeepAliveHelper.requestIgnoreBatteryOptimizations(context) },
                    )
                }

                item {
                    SettingsRow(
                        title = "iQOO 自启动与后台高耗电",
                        subtitle = "开启自启动、允许后台高耗电，并在多任务下拉锁定 Pace",
                        onClick = { OriginOsKeepAliveHelper.openOriginOsAutostartSettings(context) },
                    )
                }

                item {
                    SettingsRow(
                        title = "立即同步桌面组件",
                        subtitle = "$lastWidgetSync · 共 5 款（实验室/睡眠 2×2 · 4×2 · 4×4），桌面长按 -> 原子组件 -> Pace 添加",
                        onClick = {
                            viewModel.triggerWidgetRefresh()
                            lastWidgetSync = WidgetDataStore.lastSyncFormatted(context)
                        },
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        TextButton(
                            onClick = {
                                onBack()
                                viewModel.signOut()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    showArrow: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = showArrow, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showArrow) {
                Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
