package com.dutu007.paceapp

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: PaceUiState,
    viewModel: PaceViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

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
                        TextButton(onClick = onBack) {
                            Text("‹ 返回", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        }
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
