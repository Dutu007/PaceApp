package com.dutu007.paceapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.dutu007.paceapp.ui.theme.PacePalette
import com.dutu007.paceapp.widget.LabStudyWidgetProvider
import com.dutu007.paceapp.widget.OriginOsKeepAliveHelper
import com.dutu007.paceapp.widget.SleepTrendWidgetProvider
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
    var ignoringBattery by remember { mutableStateOf(OriginOsKeepAliveHelper.isIgnoringBatteryOptimizations(context)) }
    var lastWidgetSync by remember(state.analytics) { mutableStateOf(WidgetDataStore.lastSyncFormatted(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = OriginOsKeepAliveHelper.isIgnoringBatteryOptimizations(context)
                lastWidgetSync = WidgetDataStore.lastSyncFormatted(context)
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
                        title = "Hermes 服务状态",
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
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("桌面原子组件与后台保活（OriginOS / iQOO）", fontWeight = FontWeight.SemiBold)
                            Text(
                                "将实验室学习时长与睡眠曲线固定到桌面；若系统不支持直接推送，可在桌面长按或上滑原子组件库搜索 Pace 添加。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        val ok = OriginOsKeepAliveHelper.requestPinWidget(context, LabStudyWidgetProvider::class.java)
                                        viewModel.notifyHint(
                                            if (ok) "已发起添加「实验室学习时长」组件" else "请在桌面长按空白处 -> 原子组件添加 Pace 组件",
                                            isError = !ok,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("添加学习组件", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val ok = OriginOsKeepAliveHelper.requestPinWidget(context, SleepTrendWidgetProvider::class.java)
                                        viewModel.notifyHint(
                                            if (ok) "已发起添加「睡眠时长趋势」组件" else "请在桌面长按空白处 -> 原子组件添加 Pace 组件",
                                            isError = !ok,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("添加睡眠组件", fontSize = 12.sp)
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = PacePalette.CardBorder)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !ignoringBattery) {
                                        OriginOsKeepAliveHelper.requestIgnoreBatteryOptimizations(context)
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("忽略电池优化", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (ignoringBattery) "已开启（允许后台定时更新桌面组件）" else "未开启，点击去系统授权无限制后台",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ignoringBattery) PacePalette.Success else PacePalette.AccentAmber,
                                    )
                                }
                                if (!ignoringBattery) {
                                    Text("去开启 ›", style = MaterialTheme.typography.labelMedium, color = PacePalette.Primary)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { OriginOsKeepAliveHelper.openOriginOsAutostartSettings(context) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("iQOO 自启动与后台高耗电设置", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "开启自启动 + 允许后台高耗电 + 多任务下拉锁定，桌面每 15 分钟自动更新",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("打开 ›", style = MaterialTheme.typography.labelMedium, color = PacePalette.Primary)
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PacePalette.Background,
                                border = BorderStroke(1.dp, PacePalette.CardBorder),
                                onClick = {
                                    viewModel.triggerWidgetRefresh()
                                    lastWidgetSync = WidgetDataStore.lastSyncFormatted(context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("立即同步桌面组件", style = MaterialTheme.typography.labelLarge, color = PacePalette.Primary)
                                    Text(lastWidgetSync, style = MaterialTheme.typography.labelSmall, color = PacePalette.TextSecondary)
                                }
                            }
                        }
                    }
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
