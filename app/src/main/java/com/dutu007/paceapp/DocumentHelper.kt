package com.dutu007.paceapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dutu007.paceapp.ui.theme.PacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ChatAttachmentChip(
    val label: String,
    val docPath: String?,
    val localUri: String?,
    val isImage: Boolean,
)

private val PaceDocLinkRegex = Regex("""\[([^\]]+)\]\((pace-(?:doc|local)://[^)\s]+)\)""")
private val HermesUploadedDocRegex = Regex(
    """\[附件文档已上传至服务器：/[^\]]*?/data/(uploads/[^（\]]+)（原文件名：([^）]+)）\](?:\n文档内容提取：\n[\s\S]*?(?=\n\n\[附件|\n\n[^\s\[]|$))?"""
)

fun encodePaceDocMarkdown(label: String, docPath: String, isImage: Boolean): String {
    val cleanLabel = label.replace("[", "").replace("]", "").ifBlank { "附件" }
    val encodedPath = URLEncoder.encode(docPath, StandardCharsets.UTF_8.name())
    val prefix = if (isImage) "🖼 " else "📄 "
    return "[$prefix$cleanLabel](pace-doc://$encodedPath)"
}

fun encodePaceLocalMarkdown(label: String, uriString: String, isImage: Boolean): String {
    val cleanLabel = label.replace("[", "").replace("]", "").ifBlank { "图片" }
    val encodedUri = URLEncoder.encode(uriString, StandardCharsets.UTF_8.name())
    val prefix = if (isImage) "🖼 " else "📄 "
    return "[$prefix$cleanLabel](pace-local://$encodedUri)"
}

fun extractChatAttachmentChips(rawContent: String): Pair<String, List<ChatAttachmentChip>> {
    val chips = mutableListOf<ChatAttachmentChip>()
    val firstPass = PaceDocLinkRegex.replace(rawContent) { match ->
        val rawLabel = match.groupValues[1].trim()
        val url = match.groupValues[2]
        val isImage = rawLabel.startsWith("🖼") || isImageFileName(rawLabel)
        val cleanLabel = rawLabel.removePrefix("🖼").removePrefix("📄").removePrefix("📎").trim()
        if (url.startsWith("pace-doc://")) {
            val decoded = runCatching {
                URLDecoder.decode(url.removePrefix("pace-doc://"), StandardCharsets.UTF_8.name())
            }.getOrDefault(url.removePrefix("pace-doc://"))
            chips += ChatAttachmentChip(
                label = cleanLabel.ifBlank { decoded.substringAfterLast('/') },
                docPath = decoded,
                localUri = null,
                isImage = isImage || isImageFileName(decoded),
            )
        } else if (url.startsWith("pace-local://")) {
            val decoded = runCatching {
                URLDecoder.decode(url.removePrefix("pace-local://"), StandardCharsets.UTF_8.name())
            }.getOrDefault(url.removePrefix("pace-local://"))
            chips += ChatAttachmentChip(
                label = cleanLabel.ifBlank { "图片" },
                docPath = null,
                localUri = decoded,
                isImage = isImage,
            )
        }
        ""
    }
    val remaining = HermesUploadedDocRegex.replace(firstPass) { match ->
        val relPath = match.groupValues[1].trim()
        val name = match.groupValues[2].trim().ifBlank { relPath.substringAfterLast('/') }
        chips += ChatAttachmentChip(
            label = name,
            docPath = relPath,
            localUri = null,
            isImage = isImageFileName(name),
        )
        ""
    }.trim()
    return remaining to chips
}

fun isImageFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
}

fun mimeTypeForFileName(name: String, fallback: String = "*/*"): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> fallback
    }
}

fun fileTypeBadge(name: String): Pair<String, Color> {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "PDF" to Color(0xFFC62828)
        "doc", "docx" -> "WORD" to Color(0xFF1D4ED8)
        "xls", "xlsx", "csv" -> "EXCEL" to Color(0xFF047857)
        "ppt", "pptx" -> "PPT" to Color(0xFFD97706)
        "md" -> "MD" to PacePalette.Primary
        "json" -> "JSON" to Color(0xFF6D28D9)
        "png", "jpg", "jpeg", "webp" -> "IMG" to Color(0xFF0891B2)
        else -> "FILE" to PacePalette.TextSecondary
    }
}

suspend fun loadScaledBitmapFromUri(context: Context, uriString: String, maxSizePx: Int = 512): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }
}

suspend fun loadScaledBitmapFromFile(file: File, maxSizePx: Int = 1440): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        }.getOrNull()
    }
}

private fun computeInSampleSize(width: Int, height: Int, maxSizePx: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= maxSizePx) {
        sample *= 2
    }
    return sample
}

suspend fun copyUriToDocCache(context: Context, uriString: String, preferredName: String): File? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val safeName = preferredName.replace(Regex("""[^A-Za-z0-9.\-_\u4E00-\u9FFF]"""), "_").ifBlank { "document" }
            val docsDir = File(context.cacheDir, "docs").apply { mkdirs() }
            val dst = File(docsDir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext null
            dst
        }.getOrNull()
    }
}

fun openUriWithSystemApp(
    context: Context,
    uri: Uri,
    mimeType: String,
    onError: (String) -> Unit,
) {
    val resolvedMime = mimeType.ifBlank { "*/*" }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, resolvedMime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "选择应用打开"))
    } catch (_: ActivityNotFoundException) {
        onError("手机上未找到可打开该格式的应用（如 PDF / WPS Office）")
    } catch (e: Exception) {
        onError(e.message ?: "无法调用外部应用打开该文件")
    }
}

fun openCachedFileWithSystemApp(
    context: Context,
    file: File,
    mimeType: String,
    onError: (String) -> Unit,
) {
    val contentUri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (contentUri == null) {
        onError("文件共享路径生成失败")
        return
    }
    openUriWithSystemApp(context, contentUri, mimeType, onError)
}

@Composable
fun UriThumbnailImage(
    uriString: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    maxSizePx: Int = 360,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriString, key2 = maxSizePx) {
        value = loadScaledBitmapFromUri(context, uriString, maxSizePx)
    }
    Box(
        modifier = modifier.background(PacePalette.Muted),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

sealed interface InAppPreviewTarget {
    val title: String
    val mimeType: String

    data class LocalUri(
        override val title: String,
        val uriString: String,
        override val mimeType: String,
        val isImage: Boolean,
    ) : InAppPreviewTarget

    data class ServerDoc(
        override val title: String,
        val docPath: String,
        override val mimeType: String,
        val initialTextContent: String = "",
    ) : InAppPreviewTarget
}

@Composable
fun InAppDocumentViewerDialog(
    target: InAppPreviewTarget,
    viewModel: PaceViewModel,
    onDismiss: () -> Unit,
    onAskHermes: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cachedFile by remember(target) { mutableStateOf<File?>(null) }
    var textContent by remember(target) {
        mutableStateOf(
            when (target) {
                is InAppPreviewTarget.ServerDoc -> target.initialTextContent
                is InAppPreviewTarget.LocalUri -> ""
            },
        )
    }
    var loading by remember(target) { mutableStateOf(true) }
    var errorMsg by remember(target) { mutableStateOf<String?>(null) }
    var resolvedMime by remember(target) {
        mutableStateOf(target.mimeType.ifBlank { mimeTypeForFileName(target.title) })
    }

    val isPdf = resolvedMime.contains("pdf", ignoreCase = true) ||
        target.title.endsWith(".pdf", ignoreCase = true)
    val isImg = (target is InAppPreviewTarget.LocalUri && target.isImage) ||
        resolvedMime.startsWith("image/") ||
        isImageFileName(target.title)

    LaunchedEffect(target) {
        loading = true
        errorMsg = null
        when (target) {
            is InAppPreviewTarget.LocalUri -> {
                if (isPdf || isImg) {
                    cachedFile = copyUriToDocCache(context, target.uriString, target.title)
                    if (cachedFile == null && isPdf) {
                        errorMsg = "无法读取本地 PDF 文件"
                    }
                } else {
                    textContent = "本地文件：${target.title}\n\n发送给 Hermes 后会自动上传并解析正文，也可直接点击上方「用其他应用打开」查看完整文档。"
                }
                loading = false
            }
            is InAppPreviewTarget.ServerDoc -> {
                runCatching {
                    if (isPdf || isImg) {
                        cachedFile = viewModel.downloadServerDocToCache(target.docPath, target.title)
                    } else if (textContent.isBlank()) {
                        val detail = viewModel.fetchDocDetailOnce(target.docPath)
                        textContent = detail.content
                        if (detail.mimeType.isNotBlank()) {
                            resolvedMime = detail.mimeType
                        }
                    }
                }.onFailure { e ->
                    errorMsg = e.message ?: "文档加载失败"
                }
                loading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 18.dp),
            shape = RoundedCornerShape(20.dp),
            color = PacePalette.Card,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (badge, badgeColor) = fileTypeBadge(target.title)
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        target.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                when (target) {
                                    is InAppPreviewTarget.LocalUri -> {
                                        val file = cachedFile ?: copyUriToDocCache(context, target.uriString, target.title)
                                        if (file != null) {
                                            openCachedFileWithSystemApp(context, file, resolvedMime) { msg ->
                                                viewModel.notifyHint(msg, isError = true)
                                            }
                                        } else {
                                            openUriWithSystemApp(context, Uri.parse(target.uriString), resolvedMime) { msg ->
                                                viewModel.notifyHint(msg, isError = true)
                                            }
                                        }
                                    }
                                    is InAppPreviewTarget.ServerDoc -> {
                                        runCatching {
                                            val f = cachedFile ?: viewModel.downloadServerDocToCache(target.docPath, target.title)
                                            cachedFile = f
                                            openCachedFileWithSystemApp(context, f, resolvedMime) { msg ->
                                                viewModel.notifyHint(msg, isError = true)
                                            }
                                        }.onFailure {
                                            viewModel.notifyHint("下载文件失败，请稍后重试", isError = true)
                                        }
                                    }
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("本机打开")
                    }
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("关闭")
                    }
                }

                // Body
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PacePalette.Background)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    when {
                        loading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator()
                                Text("正在准备文档预览...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                            }
                        }
                        errorMsg != null -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(errorMsg!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        isImg -> {
                            val imgBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = cachedFile, key2 = target) {
                                value = cachedFile?.let { loadScaledBitmapFromFile(it, 1800) }
                                    ?: (target as? InAppPreviewTarget.LocalUri)?.let { loadScaledBitmapFromUri(context, it.uriString, 1800) }
                            }
                            if (imgBitmap != null) {
                                Image(
                                    bitmap = imgBitmap!!,
                                    contentDescription = target.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        isPdf && cachedFile != null -> {
                            PdfDocumentPagesView(file = cachedFile!!)
                        }
                        else -> {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    MarkdownBlock(textContent.ifBlank { "（文档暂无可显示的纯文本内容，可点击右上角「本机打开」查看）" })
                                }
                            }
                        }
                    }
                }

                if (target is InAppPreviewTarget.ServerDoc && onAskHermes != null) {
                    Surface(
                        color = PacePalette.Card,
                        border = BorderStroke(1.dp, PacePalette.CardBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    copyPlainText(context, textContent.ifBlank { target.docPath })
                                    viewModel.notifyHint("已复制")
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("复制文本") }
                            Button(
                                onClick = {
                                    onAskHermes(target.docPath)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1.4f),
                            ) { Text("让 Hermes 分析文档") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfDocumentPagesView(file: File) {
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var openError by remember(file) { mutableStateOf<String?>(null) }

    DisposableEffect(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pr = PdfRenderer(fd)
            pfd = fd
            renderer = pr
            pageCount = pr.pageCount
        }.onFailure {
            openError = "PDF 格式解析失败，请点击右上角「本机打开」"
        }
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
        }
    }

    if (openError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(openError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    if (renderer == null || pageCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(pageCount) { pageIdx ->
            PdfPageCard(renderer = renderer!!, pageIndex = pageIdx, totalPages = pageCount)
        }
    }
}

@Composable
private fun PdfPageCard(
    renderer: PdfRenderer,
    pageIndex: Int,
    totalPages: Int,
) {
    val pageBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = renderer, key2 = pageIndex) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                synchronized(renderer) {
                    renderer.openPage(pageIndex).use { page ->
                        val targetWidth = 1080
                        val ratio = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        val targetHeight = (page.height * ratio).toInt().coerceIn(200, 2600)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (pageBitmap != null) {
                val aspect = pageBitmap!!.width.toFloat() / pageBitmap!!.height.coerceAtLeast(1)
                Image(
                    bitmap = pageBitmap!!,
                    contentDescription = "PDF 第 ${pageIndex + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect.coerceIn(0.4f, 2.2f)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
            Text(
                text = "第 ${pageIndex + 1} / $totalPages 页",
                style = MaterialTheme.typography.labelSmall,
                color = PacePalette.TextSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 6.dp),
            )
        }
    }
}
