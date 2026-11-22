package com.dutu007.paceapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dutu007.paceapp.ui.theme.PacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val MAX_DOCX_IMAGES = 40
private val DOCX_RASTER_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

sealed interface DocxBlock {
    data class Heading(val level: Int, val text: String) : DocxBlock
    data class Paragraph(val text: String) : DocxBlock
    data class Table(val rows: List<List<String>>) : DocxBlock
    data class EmbeddedImage(val entryPath: String, val index: Int) : DocxBlock
}

fun docxPlainText(blocks: List<DocxBlock>): String {
    return blocks.mapNotNull { block ->
        when (block) {
            is DocxBlock.Heading -> block.text
            is DocxBlock.Paragraph -> block.text
            is DocxBlock.Table -> block.rows.joinToString("\n") { it.joinToString(" | ") }
            is DocxBlock.EmbeddedImage -> null
        }
    }.joinToString("\n\n")
}

fun parseDocxBlocks(file: File): List<DocxBlock> {
    if (!file.exists()) return emptyList()
    return runCatching {
        ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml") ?: return emptyList()
            val relsEntry = zip.getEntry("word/_rels/document.xml.rels")
            val mediaEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("word/media/", ignoreCase = true) }
                .map { it.name }
                .sorted()
                .toList()
            zip.getInputStream(docEntry).use { docStream ->
                val relsStream = relsEntry?.let { zip.getInputStream(it) }
                try {
                    parseDocxXmlStreams(docStream, relsStream, mediaEntries)
                } finally {
                    relsStream?.close()
                }
            }
        }
    }.getOrDefault(emptyList())
}

fun parseDocxBlocks(zipInput: InputStream): List<DocxBlock> {
    return runCatching {
        var docBytes: ByteArray? = null
        var relsBytes: ByteArray? = null
        val mediaEntries = mutableListOf<String>()
        ZipInputStream(zipInput).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name == "word/document.xml" -> docBytes = zis.readBytes()
                        entry.name == "word/_rels/document.xml.rels" -> relsBytes = zis.readBytes()
                        entry.name.startsWith("word/media/", ignoreCase = true) -> mediaEntries += entry.name
                    }
                }
                entry = zis.nextEntry
            }
        }
        val doc = docBytes ?: return emptyList()
        ByteArrayInputStream(doc).use { docStream ->
            val relsStream = relsBytes?.let(::ByteArrayInputStream)
            try {
                parseDocxXmlStreams(docStream, relsStream, mediaEntries.sorted())
            } finally {
                relsStream?.close()
            }
        }
    }.getOrDefault(emptyList())
}

private fun parseDocxXmlStreams(
    documentXml: InputStream,
    relsXml: InputStream?,
    mediaEntries: List<String>,
): List<DocxBlock> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }
    val builder = factory.newDocumentBuilder()
    val mediaByLower = mediaEntries.associateBy { it.lowercase() }
    val relMap = mutableMapOf<String, String>()

    if (relsXml != null) {
        val relsDoc = builder.parse(relsXml)
        val relNodes = relsDoc.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until relNodes.length) {
            val el = relNodes.item(i) as? Element ?: continue
            if (el.getAttribute("TargetMode").equals("External", ignoreCase = true)) continue
            val id = el.getAttribute("Id")
            val target = el.getAttribute("Target")
            if (id.isBlank() || target.isBlank()) continue
            val normalized = normalizeDocxTarget(target)
            val resolved = mediaByLower[normalized.lowercase()] ?: normalized
            if (resolved.startsWith("word/media/", ignoreCase = true)) {
                relMap[id] = resolved
            }
        }
    }

    val doc = builder.parse(documentXml)
    val bodyList = doc.getElementsByTagNameNS("*", "body")
    val body = (if (bodyList.length > 0) bodyList.item(0) else doc.documentElement) as? Element
        ?: return emptyList()

    val blocks = mutableListOf<DocxBlock>()
    val referencedMedia = linkedSetOf<String>()
    var imageCounter = 0

    fun nextImageBlock(relId: String): DocxBlock.EmbeddedImage? {
        if (imageCounter >= MAX_DOCX_IMAGES) return null
        val path = relMap[relId] ?: return null
        referencedMedia += path
        return DocxBlock.EmbeddedImage(entryPath = path, index = imageCounter++)
    }

    val bodyChildren = body.childNodes
    for (i in 0 until bodyChildren.length) {
        val child = bodyChildren.item(i) as? Element ?: continue
        when (child.localName) {
            "p" -> {
                val headingLevel = detectHeadingLevel(child)
                var emittedHeading = false
                val textBuf = StringBuilder()

                fun flushText() {
                    val clean = textBuf.toString().trim()
                    textBuf.setLength(0)
                    if (clean.isNotBlank()) {
                        if (headingLevel > 0 && !emittedHeading) {
                            blocks += DocxBlock.Heading(headingLevel, clean)
                            emittedHeading = true
                        } else {
                            blocks += DocxBlock.Paragraph(clean)
                        }
                    }
                }

                walkDocxParagraphNode(child, textBuf) { relId ->
                    flushText()
                    nextImageBlock(relId)?.let { blocks += it }
                }
                flushText()
            }
            "tbl" -> {
                val rows = mutableListOf<List<String>>()
                val tableImages = mutableListOf<DocxBlock.EmbeddedImage>()
                val trChildren = child.childNodes
                for (rIdx in 0 until trChildren.length) {
                    val tr = trChildren.item(rIdx) as? Element ?: continue
                    if (tr.localName != "tr") continue
                    val rowCells = mutableListOf<String>()
                    val tcChildren = tr.childNodes
                    for (cIdx in 0 until tcChildren.length) {
                        val tc = tcChildren.item(cIdx) as? Element ?: continue
                        if (tc.localName != "tc") continue
                        val cellLines = mutableListOf<String>()
                        val pChildren = tc.childNodes
                        for (pIdx in 0 until pChildren.length) {
                            val cellP = pChildren.item(pIdx) as? Element ?: continue
                            if (cellP.localName == "p") {
                                val cellBuf = StringBuilder()
                                walkDocxParagraphNode(cellP, cellBuf) { relId ->
                                    nextImageBlock(relId)?.let { tableImages += it }
                                }
                                val line = cellBuf.toString().trim()
                                if (line.isNotBlank()) cellLines += line
                            }
                        }
                        rowCells += cellLines.joinToString("\n")
                    }
                    if (rowCells.any { it.isNotBlank() }) {
                        rows += rowCells
                    }
                }
                if (rows.isNotEmpty()) {
                    blocks += DocxBlock.Table(rows)
                }
                blocks += tableImages
            }
        }
    }

    // Fallback: include unreferenced raster images in word/media/ so none are ever dropped.
    val unreferenced = mediaEntries.filter { entry ->
        entry !in referencedMedia &&
            entry.substringAfterLast('.', "").lowercase() in DOCX_RASTER_IMAGE_EXTS
    }
    for (entry in unreferenced) {
        if (imageCounter >= MAX_DOCX_IMAGES) break
        blocks += DocxBlock.EmbeddedImage(entryPath = entry, index = imageCounter++)
    }

    return blocks
}

private fun normalizeDocxTarget(target: String): String {
    val clean = target.trim().replace('\\', '/')
    return when {
        clean.startsWith("/") -> clean.trimStart('/')
        clean.startsWith("../") -> clean.removePrefix("../")
        clean.startsWith("word/", ignoreCase = true) -> clean
        else -> "word/" + clean.removePrefix("./")
    }
}

private fun detectHeadingLevel(p: Element): Int {
    val children = p.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i) as? Element ?: continue
        if (child.localName != "pPr") continue
        val pPrKids = child.childNodes
        for (j in 0 until pPrKids.length) {
            val styleEl = pPrKids.item(j) as? Element ?: continue
            if (styleEl.localName != "pStyle") continue
            val valAttr = findAttrByLocalName(styleEl, "val").lowercase()
            return when {
                valAttr == "title" || valAttr == "heading1" || valAttr == "heading 1" || valAttr == "1" -> 1
                valAttr == "subtitle" || valAttr == "heading2" || valAttr == "heading 2" || valAttr == "2" -> 2
                valAttr == "heading3" || valAttr == "heading 3" || valAttr == "3" -> 3
                valAttr.startsWith("heading") -> 2
                else -> 0
            }
        }
    }
    return 0
}

private fun walkDocxParagraphNode(
    node: Node,
    textBuf: StringBuilder,
    onImageRel: (String) -> Unit,
) {
    if (node !is Element) return
    when (node.localName) {
        "pPr", "rPr", "Fallback", "docPr", "nvPicPr" -> return
        "t" -> {
            textBuf.append(node.textContent.orEmpty())
            return
        }
        "tab" -> {
            textBuf.append('\t')
            return
        }
        "br", "cr" -> {
            textBuf.append('\n')
            return
        }
        "blip" -> {
            val relId = findAttrByLocalName(node, "embed").ifBlank { findAttrByLocalName(node, "link") }
            if (relId.isNotBlank()) onImageRel(relId)
            return
        }
        "imagedata" -> {
            val relId = findAttrByLocalName(node, "id")
            if (relId.isNotBlank()) onImageRel(relId)
            return
        }
    }
    val kids = node.childNodes
    for (i in 0 until kids.length) {
        walkDocxParagraphNode(kids.item(i), textBuf, onImageRel)
    }
}

private fun findAttrByLocalName(el: Element, localName: String): String {
    val attrs = el.attributes ?: return ""
    for (i in 0 until attrs.length) {
        val item = attrs.item(i)
        if (item.localName == localName || item.nodeName.substringAfter(':') == localName) {
            return item.nodeValue.orEmpty()
        }
    }
    return ""
}

private suspend fun loadDocxEntryBitmap(
    file: File,
    entryPath: String,
    maxSizePx: Int = 1440,
): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val ext = entryPath.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext !in DOCX_RASTER_IMAGE_EXTS) return@runCatching null
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return@use null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.isEmpty()) return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / (sample * 2) >= maxSizePx) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }
}

@Composable
fun DocxRichDocumentView(
    file: File,
    fallbackText: String,
    onTextReady: (String) -> Unit,
) {
    var parsing by remember(file) { mutableStateOf(true) }
    var blocks by remember(file) { mutableStateOf<List<DocxBlock>>(emptyList()) }
    var zoomedImagePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        parsing = true
        val parsed = withContext(Dispatchers.IO) { parseDocxBlocks(file) }
        blocks = parsed
        val plain = docxPlainText(parsed)
        if (plain.isNotBlank()) {
            onTextReady(plain)
        }
        parsing = false
    }

    when {
        parsing -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在解析 Word 图文内容...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                }
            }
        }
        blocks.isEmpty() -> {
            SelectionContainer {
                Column(modifier = Modifier.fillMaxSize()) {
                    MarkdownBlock(fallbackText.ifBlank { "（Word 文档暂无可显示的纯文本或图片，可点击右上角「本机打开」）" })
                }
            }
        }
        else -> {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(blocks) { block ->
                        when (block) {
                            is DocxBlock.Heading -> {
                                Text(
                                    text = block.text,
                                    style = if (block.level <= 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PacePalette.TextPrimary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            is DocxBlock.Paragraph -> {
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PacePalette.TextPrimary,
                                )
                            }
                            is DocxBlock.Table -> {
                                DocxTableCard(rows = block.rows)
                            }
                            is DocxBlock.EmbeddedImage -> {
                                DocxImageCard(
                                    file = file,
                                    block = block,
                                    onZoom = { zoomedImagePath = block.entryPath },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    zoomedImagePath?.let { path ->
        Dialog(
            onDismissRequest = { zoomedImagePath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val zoomedBmp by produceState<ImageBitmap?>(initialValue = null, key1 = file, key2 = path) {
                value = loadDocxEntryBitmap(file, path, maxSizePx = 2048)
            }
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                color = Color.Black.copy(alpha = 0.92f),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (zoomedBmp != null) {
                        Image(
                            bitmap = zoomedBmp!!,
                            contentDescription = "文档大图预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
                    }
                    TextButton(
                        onClick = { zoomedImagePath = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    ) {
                        Text("关闭", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocxTableCard(rows: List<List<String>>) {
    val maxCols = rows.maxOfOrNull { it.size } ?: 0
    if (maxCols == 0) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIdx, row ->
                val bg = if (rowIdx == 0) PacePalette.PrimarySoft.copy(alpha = 0.55f) else Color.Transparent
                Row(
                    modifier = Modifier
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (c in 0 until maxCols) {
                        Text(
                            text = row.getOrNull(c).orEmpty().ifBlank { " " },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (rowIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (rowIdx == 0) PacePalette.Primary else PacePalette.TextPrimary,
                            modifier = Modifier.widthIn(min = 64.dp, max = 220.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocxImageCard(
    file: File,
    block: DocxBlock.EmbeddedImage,
    onZoom: () -> Unit,
) {
    val ext = block.entryPath.substringAfterLast('.', "").lowercase()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file, key2 = block.entryPath) {
        value = loadDocxEntryBitmap(file, block.entryPath, maxSizePx = 1440)
    }
    var settled by remember(block.entryPath) { mutableStateOf(false) }
    LaunchedEffect(file, block.entryPath) {
        loadDocxEntryBitmap(file, block.entryPath, maxSizePx = 1440)
        settled = true
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PacePalette.Card,
        border = BorderStroke(1.dp, PacePalette.CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            bitmap != null -> {
                val bmp = bitmap!!
                val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.45f, 2.4f)
                Column {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Word 图片 ${block.index + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .clickable(onClick = onZoom),
                    )
                    Text(
                        text = "图片 ${block.index + 1} · 点击放大",
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 5.dp),
                    )
                }
            }
            !settled && (ext.isEmpty() || ext in DOCX_RASTER_IMAGE_EXTS) -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            else -> {
                Text(
                    text = "暂不支持内置渲染该图片格式（$ext），请点击右上角「本机打开」查看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PacePalette.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
