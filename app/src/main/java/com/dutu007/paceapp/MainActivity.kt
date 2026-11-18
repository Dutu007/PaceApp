package com.dutu007.paceapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dutu007.paceapp.ui.theme.PacePalette
import com.dutu007.paceapp.ui.theme.PaceTheme
import kotlinx.coroutines.delay
import java.io.File

private val ChatTabIcon = ImageVector.Builder(
    name = "ChatTab", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(9f, 16f); lineTo(4f, 20f); close()
    }
}.build()

private val EntriesTabIcon = ImageVector.Builder(
    name = "EntriesTab", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(9f, 9f); lineTo(15f, 9f)
        moveTo(9f, 13f); lineTo(15f, 13f)
        moveTo(9f, 17f); lineTo(12f, 17f)
    }
}.build()

private val DocsTabIcon = ImageVector.Builder(
    name = "DocsTab", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    }
}.build()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = Color.Transparent.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        setContent {
            PaceTheme {
                PaceAppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PaceAppRoot(viewModel: PaceViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    val notice = state.notice
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(if (notice.isError) 5_000L else 3_000L)
        viewModel.dismissNotice(id)
    }

    LaunchedEffect(state.updateReadyPath) {
        val path = state.updateReadyPath ?: return@LaunchedEffect
        viewModel.consumeReadyUpdate()
        runCatching { launchApkInstaller(context, path) }
    }

    when {
        state.loading -> {
            Box(Modifier.fillMaxSize().background(PacePalette.Background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        !state.authenticated -> {
            LoginScreen(
                state = state,
                onUsernameChange = viewModel::setUsername,
                onPasswordChange = viewModel::setPassword,
                onSubmit = viewModel::signIn,
                onDismissNotice = viewModel::dismissNotice,
            )
        }
        else -> {
            val activeSession = state.sessions.firstOrNull { it.id == state.activeSessionId }
            Scaffold(
                containerColor = PacePalette.Background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = PacePalette.Card),
                        title = {
                            val dotColor = when (state.hermesOnline) {
                                true -> PacePalette.Success
                                false -> MaterialTheme.colorScheme.error
                                null -> Color(0xFF9AA0A6)
                            }
                            if (state.currentTab == PaceTab.CHAT) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showSessionSheet = true }
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                                    Text(
                                        text = "${activeSession?.title ?: "新对话"} ▾",
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (state.currentTab == PaceTab.ENTRIES) "记录与看板" else "文档空间",
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                                }
                            }
                        },
                        actions = {
                            if (state.currentTab == PaceTab.CHAT) {
                                TextButton(onClick = viewModel::startNewSession, enabled = !state.streaming) {
                                    Text("+ 新建", color = PacePalette.Primary)
                                }
                            } else {
                                IconButton(onClick = viewModel::refreshAll, enabled = !state.streaming) {
                                    Icon(RefreshIcon, contentDescription = "刷新", tint = PacePalette.Primary)
                                }
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(SettingsIcon, contentDescription = "设置", tint = PacePalette.Primary)
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!imeVisible) {
                        PaceBottomBar(
                            currentTab = state.currentTab,
                            onSelectTab = viewModel::selectTab,
                        )
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) {
                    if (notice != null) {
                        Surface(
                            color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .clickable { viewModel.dismissNotice(notice.id) },
                        ) {
                            Text(notice.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    when (state.currentTab) {
                        PaceTab.CHAT -> ChatScreen(
                            state = state,
                            viewModel = viewModel,
                            showSessionSheet = showSessionSheet,
                            onDismissSessionSheet = { showSessionSheet = false },
                        )
                        PaceTab.ENTRIES -> EntriesScreen(state = state, viewModel = viewModel)
                        PaceTab.DOCS -> DocsScreen(state = state, viewModel = viewModel)
                    }
                }
            }

            state.openedDoc?.let { doc ->
                DocDetailOverlay(
                    doc = doc,
                    busy = state.docBusy,
                    onBack = viewModel::closeDoc,
                    onSave = { updated -> viewModel.saveDoc(doc.path, updated) },
                    onAskHermes = { viewModel.askHermesAboutDoc(doc) },
                )
            }

            if (showSettings) {
                SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { showSettings = false },
                )
            }
        }
    }

    val release = state.updateAvailable
    when {
        state.updateDownloading -> AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(24.dp),
            title = { Text("正在下载更新", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { state.updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${state.updateProgress}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        release != null -> AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            shape = RoundedCornerShape(24.dp),
            title = { Text("发现新版本 ${release.versionName}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (release.notes.isNotBlank()) Text(release.notes)
                    if (release.size > 0) {
                        Text(
                            "安装包约 ${release.size / 1024 / 1024 + 1} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        viewModel.noteInstallPermissionNeeded()
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    } else {
                        viewModel.downloadUpdate(context)
                    }
                }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun PaceBottomBar(
    currentTab: PaceTab,
    onSelectTab: (PaceTab) -> Unit,
) {
    Surface(
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = listOf(
                Triple(PaceTab.CHAT, "对话", ChatTabIcon),
                Triple(PaceTab.ENTRIES, "记录", EntriesTabIcon),
                Triple(PaceTab.DOCS, "文档", DocsTabIcon),
            )
            tabs.forEach { (tab, label, icon) ->
                val selected = currentTab == tab
                val tint = if (selected) PacePalette.Primary else PacePalette.TextSecondary
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                    )
                }
            }
        }
    }
}

private fun launchApkInstaller(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
