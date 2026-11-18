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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dutu007.paceapp.ui.theme.PaceTheme
import kotlinx.coroutines.delay
import java.io.File

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

@Composable
private fun PaceAppRoot(viewModel: PaceViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            ChatScreen(state = state, viewModel = viewModel)
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
                }) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate) { Text("稍后") }
            },
        )
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
