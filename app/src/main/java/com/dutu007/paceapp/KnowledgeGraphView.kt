package com.dutu007.paceapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dutu007.paceapp.data.GraphEdgeItem
import com.dutu007.paceapp.data.GraphNodeItem
import com.dutu007.paceapp.data.KnowledgeGraphData
import com.dutu007.paceapp.ui.theme.PacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val GraphCategoryColors = mapOf(
    "root" to Color(0xFF38BDF8),
    "memory" to Color(0xFFF59E0B),
    "note" to Color(0xFF2DD4BF),
    "diary" to Color(0xFFA78BFA),
    "data" to Color(0xFF34D399),
    "upload" to Color(0xFFFB7185),
    "entry" to Color(0xFF60A5FA),
    "tag" to Color(0xFFF472B6),
)

private val GraphCategoryFilters = listOf(
    "all" to "全部",
    "memory" to "记忆",
    "note" to "笔记",
    "diary" to "日记",
    "upload" to "文档",
    "entry" to "记录",
    "tag" to "标签",
    "data" to "数据",
)

private fun colorForNodeKind(kind: String, category: String): Color {
    return GraphCategoryColors[kind]
        ?: GraphCategoryColors[category]
        ?: Color(0xFF94A3B8)
}

private fun labelForNodeKind(kind: String, category: String): String = when (kind) {
    "root" -> "总览核心"
    "hub" -> "分类中心"
    "memory" -> "核心记忆"
    "note" -> "笔记"
    "diary" -> "日记"
    "data" -> "数据档案"
    "upload" -> "文档附件"
    "entry" -> "随手记"
    "tag" -> "主题标签"
    else -> category
}

private fun radiusForNode(node: GraphNodeItem): Float = when (node.kind) {
    "root" -> 38f
    "hub" -> 27f
    "memory" -> (18f + node.weight * 0.9f).coerceAtMost(28f)
    "tag" -> (15f + node.weight * 0.8f).coerceAtMost(24f)
    else -> (14f + node.weight * 0.85f).coerceAtMost(24f)
}

private data class GraphLayoutResult(
    val positions: Map<String, Offset>,
    val nodeMap: Map<String, GraphNodeItem>,
    val neighbors: Map<String, List<Pair<GraphNodeItem, String>>>,
)

private suspend fun computeGraphLayout(graph: KnowledgeGraphData): GraphLayoutResult {
    return withContext(Dispatchers.Default) {
        val nodeMap = graph.nodes.associateBy { it.id }
        val adj = mutableMapOf<String, MutableList<Pair<String, String>>>()
        graph.edges.forEach { edge ->
            adj.getOrPut(edge.source) { mutableListOf() }.add(edge.target to edge.label)
            adj.getOrPut(edge.target) { mutableListOf() }.add(edge.source to edge.label)
        }

        val pos = mutableMapOf<String, Offset>()
        val rootNode = graph.nodes.firstOrNull { it.kind == "root" }
        if (rootNode != null) {
            pos[rootNode.id] = Offset.Zero
        }

        val hubs = graph.nodes.filter { it.kind == "hub" }
        val hubAngles = mutableMapOf<String, Double>()
        val hubRadius = 240f
        hubs.forEachIndexed { idx, hub ->
            val angle = (2.0 * PI * idx / hubs.size.coerceAtLeast(1)) - PI / 2.0
            hubAngles[hub.id] = angle
            pos[hub.id] = Offset(
                (cos(angle) * hubRadius).toFloat(),
                (sin(angle) * hubRadius).toFloat(),
            )
        }

        // Assign children evenly in fan arcs around their primary connected hub
        val childrenByHub = mutableMapOf<String, MutableList<GraphNodeItem>>()
        val unassigned = mutableListOf<GraphNodeItem>()
        for (node in graph.nodes) {
            if (node.kind == "root" || node.kind == "hub") continue
            val parentHub = adj[node.id]
                ?.mapNotNull { nodeMap[it.first] }
                ?.firstOrNull { it.kind == "hub" }
                ?: nodeMap["hub:${node.category}"]
            if (parentHub != null && parentHub.id in pos) {
                childrenByHub.getOrPut(parentHub.id) { mutableListOf() }.add(node)
            } else {
                unassigned += node
            }
        }

        val sectorSpan = (2.0 * PI / hubs.size.coerceAtLeast(1)) * 0.9
        childrenByHub.forEach { (hubId, children) ->
            val hubPos = pos[hubId] ?: Offset.Zero
            val baseAngle = hubAngles[hubId] ?: 0.0
            val total = children.size.coerceAtLeast(1)
            children.forEachIndexed { idx, child ->
                val ring = idx / 7
                val inRingIdx = idx % 7
                val ringCount = min(7, total - ring * 7).coerceAtLeast(1)
                val offsetFraction = if (ringCount == 1) 0.0 else (inRingIdx.toDouble() / (ringCount - 1) - 0.5)
                val angle = baseAngle + offsetFraction * sectorSpan + (ring * 0.14)
                val dist = 175f + ring * 105f
                pos[child.id] = Offset(
                    hubPos.x + (cos(angle) * dist).toFloat(),
                    hubPos.y + (sin(angle) * dist).toFloat(),
                )
            }
        }

        // Place tags / unassigned nodes near centroid of their neighbors
        unassigned.forEachIndexed { idx, node ->
            val nbOffsets = adj[node.id].orEmpty().mapNotNull { pos[it.first] }
            if (nbOffsets.isNotEmpty()) {
                val cx = nbOffsets.sumOf { it.x.toDouble() }.toFloat() / nbOffsets.size
                val cy = nbOffsets.sumOf { it.y.toDouble() }.toFloat() / nbOffsets.size
                val angle = idx * 1.15
                pos[node.id] = Offset(cx + cos(angle).toFloat() * 75f, cy + sin(angle).toFloat() * 75f)
            } else {
                val angle = 2.0 * PI * idx / unassigned.size.coerceAtLeast(1)
                pos[node.id] = Offset(cos(angle).toFloat() * 145f, sin(angle).toFloat() * 145f)
            }
        }

        // Gentle deterministic force relaxation to remove overlaps while respecting cluster pulls
        val mutableIds = graph.nodes.filter { it.kind != "root" }.map { it.id }
        val iterations = if (mutableIds.size > 80) 32 else 54
        repeat(iterations) {
            val disp = mutableMapOf<String, Offset>()
            for (i in mutableIds.indices) {
                val idA = mutableIds[i]
                val pA = pos[idA] ?: continue
                var fx = 0f
                var fy = 0f

                for (j in graph.nodes.indices) {
                    val idB = graph.nodes[j].id
                    if (idA == idB) continue
                    val pB = pos[idB] ?: continue
                    val dx = pA.x - pB.x
                    val dy = pA.y - pB.y
                    val dist = hypot(dx, dy).coerceAtLeast(1f)
                    val minSep = 112f
                    if (dist < minSep) {
                        val push = (minSep - dist) * 0.28f
                        fx += (dx / dist) * push
                        fy += (dy / dist) * push
                    } else if (dist < 260f) {
                        val rep = 1800f / (dist * dist + 400f)
                        fx += (dx / dist) * rep
                        fy += (dy / dist) * rep
                    }
                }

                // Spring attraction along edges
                for ((nbId, _) in adj[idA].orEmpty()) {
                    val pB = pos[nbId] ?: continue
                    val dx = pB.x - pA.x
                    val dy = pB.y - pA.y
                    val dist = hypot(dx, dy).coerceAtLeast(1f)
                    val rest = if (nodeMap[idA]?.kind == "hub") 220f else 155f
                    val pull = (dist - rest) * 0.045f
                    fx += (dx / dist) * pull
                    fy += (dy / dist) * pull
                }

                // Gentle gravity toward origin so outer nodes don't drift away
                val dOrigin = hypot(pA.x, pA.y).coerceAtLeast(1f)
                if (dOrigin > 520f) {
                    fx -= (pA.x / dOrigin) * 6f
                    fy -= (pA.y / dOrigin) * 6f
                }

                val stepLen = sqrt(fx * fx + fy * fy).coerceAtLeast(1f)
                val clamped = min(stepLen, 16f) / stepLen
                disp[idA] = Offset(fx * clamped, fy * clamped)
            }
            for (id in mutableIds) {
                val cur = pos[id] ?: continue
                val d = disp[id] ?: continue
                pos[id] = cur + d
            }
        }

        val neighborDetails = graph.nodes.associate { node ->
            val items = adj[node.id].orEmpty()
                .distinctBy { it.first }
                .mapNotNull { (nbId, lbl) -> nodeMap[nbId]?.let { it to lbl } }
            node.id to items
        }
        GraphLayoutResult(pos, nodeMap, neighborDetails)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KnowledgeGraphExplorer(
    graph: KnowledgeGraphData?,
    loading: Boolean,
    searchQuery: String,
    onRefresh: () -> Unit,
    onOpenDoc: (String, String) -> Unit,
    onAskHermes: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeFilter by remember { mutableStateOf("all") }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var inspectSheetNode by remember { mutableStateOf<GraphNodeItem?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(0.78f) }
    var canvasSize by remember { mutableStateOf(IntSize(1080, 1200)) }

    if (loading && graph == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("正在构建知识图谱...", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
            }
        }
        return
    }

    val safeGraph = graph
    if (safeGraph == null || safeGraph.nodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("知识图谱暂无节点", style = MaterialTheme.typography.titleMedium)
                Text("新增日记、笔记、随手记或上传文档后会自动生成知识关联。", style = MaterialTheme.typography.bodySmall, color = PacePalette.TextSecondary)
                OutlinedButton(onClick = onRefresh) { Text("刷新图谱") }
            }
        }
        return
    }

    val layout by produceState<GraphLayoutResult?>(initialValue = null, key1 = safeGraph) {
        value = computeGraphLayout(safeGraph)
    }

    val textMeasurer = rememberTextMeasurer()
    val needle = searchQuery.trim().lowercase()

    // Determine matching/highlighted nodes cleanly
    val matchedIds = remember(safeGraph, activeFilter, needle) {
        safeGraph.nodes.filter { n ->
            val catOk = activeFilter == "all" || n.kind == activeFilter || n.category == activeFilter || n.kind == "root"
            val queryOk = needle.isBlank() ||
                n.label.lowercase().contains(needle) ||
                n.preview.lowercase().contains(needle) ||
                n.docPath.lowercase().contains(needle)
            catOk && queryOk
        }.map { it.id }.toSet()
    }

    @Composable
    fun renderGraphWorkspace(fullscreenMode: Boolean, containerModifier: Modifier) {
        Column(modifier = containerModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Filter chips strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GraphCategoryFilters.forEach { (key, label) ->
                    val selected = activeFilter == key
                    val accent = GraphCategoryColors[key] ?: Color(0xFF38BDF8)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) accent.copy(alpha = if (fullscreenMode) 0.24f else 0.16f)
                        else if (fullscreenMode) Color(0xFF132234) else PacePalette.Card,
                        border = BorderStroke(1.dp, if (selected) accent else if (fullscreenMode) Color(0xFF28415C) else PacePalette.CardBorder),
                        modifier = Modifier.clickable {
                            activeFilter = key
                            if (key == "all") selectedNodeId = null
                        },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (fullscreenMode) Color(0xFFE2E8F0)
                            else if (selected) PacePalette.TextPrimary else PacePalette.TextSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Beautified Interactive Starlight Slate Canvas
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(if (fullscreenMode) 16.dp else 22.dp),
                border = BorderStroke(1.dp, Color(0xFF23364D)),
                color = Color(0xFF0D1623),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it },
                ) {
                    val curLayout = layout
                    if (curLayout == null) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFF38BDF8))
                    } else {
                        val selectedNeighbors = selectedNodeId?.let { id ->
                            curLayout.neighbors[id]?.map { it.first.id }?.toSet().orEmpty() + id
                        } ?: emptySet()

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(curLayout) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val oldZoom = zoomScale
                                        val newZoom = (oldZoom * zoom).coerceIn(0.36f, 2.8f)
                                        val scaleChange = newZoom / oldZoom
                                        val cShift = centroid - center - panOffset
                                        panOffset = panOffset + pan + cShift * (1f - scaleChange)
                                        zoomScale = newZoom
                                    }
                                }
                                .pointerInput(curLayout, zoomScale, panOffset) {
                                    detectTapGestures { tap ->
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val worldTap = (tap - center - panOffset) / zoomScale
                                        var hit: GraphNodeItem? = null
                                        var bestDist = Float.MAX_VALUE
                                        for (node in safeGraph.nodes) {
                                            val p = curLayout.positions[node.id] ?: continue
                                            val d = hypot(worldTap.x - p.x, worldTap.y - p.y)
                                            val hitR = radiusForNode(node) + (24f / zoomScale)
                                            if (d <= hitR && d < bestDist) {
                                                bestDist = d
                                                hit = node
                                            }
                                        }
                                        if (hit != null) {
                                            selectedNodeId = hit.id
                                            inspectSheetNode = hit
                                        } else {
                                            selectedNodeId = null
                                            activeFilter = "all"
                                        }
                                    }
                                },
                        ) {
                            val center = Offset(size.width / 2f, size.height / 2f)

                            // Deep radial ambient background
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFF16273B), Color(0xFF0C141F)),
                                    center = center,
                                    radius = maxOf(size.width, size.height) * 0.72f,
                                ),
                            )

                            // Subtle spatial dot matrix
                            val spacing = (56f * zoomScale).coerceIn(28f, 96f)
                            val offX = ((center.x + panOffset.x) % spacing + spacing) % spacing
                            val offY = ((center.y + panOffset.y) % spacing + spacing) % spacing
                            var gx = offX
                            while (gx < size.width) {
                                var gy = offY
                                while (gy < size.height) {
                                    drawCircle(
                                        color = Color(0xFF29415A).copy(alpha = 0.28f),
                                        radius = 1.3f,
                                        center = Offset(gx, gy),
                                    )
                                    gy += spacing
                                }
                                gx += spacing
                            }

                            fun toScreen(world: Offset): Offset = center + panOffset + world * zoomScale

                            // Draw curved Bezier edges
                            for (edge in safeGraph.edges) {
                                val wA = curLayout.positions[edge.source] ?: continue
                                val wB = curLayout.positions[edge.target] ?: continue
                                val sA = toScreen(wA)
                                val sB = toScreen(wB)

                                val touchesSelection = selectedNodeId != null &&
                                    (edge.source == selectedNodeId || edge.target == selectedNodeId)
                                val matchesFilter = edge.source in matchedIds && edge.target in matchedIds
                                val alpha = when {
                                    selectedNodeId != null -> if (touchesSelection) 0.88f else 0.08f
                                    !matchesFilter -> 0.10f
                                    edge.kind == "linked_doc" || edge.kind == "tagged" -> 0.48f
                                    else -> 0.26f
                                }
                                val edgeColor = when {
                                    touchesSelection -> Color(0xFF38BDF8)
                                    edge.kind == "linked_doc" -> Color(0xFF2DD4BF)
                                    edge.kind == "tagged" -> Color(0xFFF472B6)
                                    edge.kind == "memory_section" -> Color(0xFFF59E0B)
                                    else -> Color(0xFF64748B)
                                }

                                val mid = Offset((sA.x + sB.x) / 2f, (sA.y + sB.y) / 2f)
                                val dx = sB.x - sA.x
                                val dy = sB.y - sA.y
                                val bend = 0.14f
                                val ctrl = Offset(mid.x - dy * bend, mid.y + dx * bend)

                                val path = Path().apply {
                                    moveTo(sA.x, sA.y)
                                    quadraticTo(ctrl.x, ctrl.y, sB.x, sB.y)
                                }
                                drawPath(
                                    path = path,
                                    color = edgeColor.copy(alpha = alpha),
                                    style = Stroke(
                                        width = (if (touchesSelection) 2.8f else 1.4f),
                                        cap = StrokeCap.Round,
                                    ),
                                )
                            }

                            // Draw nodes & clean pill labels
                            val showDetailLabels = zoomScale >= 0.56f
                            for (node in safeGraph.nodes) {
                                val worldPos = curLayout.positions[node.id] ?: continue
                                val sPos = toScreen(worldPos)
                                if (sPos.x < -140f || sPos.x > size.width + 140f || sPos.y < -140f || sPos.y > size.height + 140f) {
                                    continue
                                }

                                val baseColor = colorForNodeKind(node.kind, node.category)
                                val isSelected = node.id == selectedNodeId
                                val isNeighbor = node.id in selectedNeighbors
                                val isMatched = node.id in matchedIds
                                val nodeAlpha = when {
                                    selectedNodeId != null -> if (isNeighbor) 1f else 0.22f
                                    isMatched -> 1f
                                    else -> 0.22f
                                }
                                val r = radiusForNode(node) * zoomScale.coerceIn(0.62f, 1.35f)

                                // Soft outer halo
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            baseColor.copy(alpha = (if (isSelected) 0.52f else 0.26f) * nodeAlpha),
                                            Color.Transparent,
                                        ),
                                        center = sPos,
                                        radius = r * (if (isSelected) 2.5f else 1.9f),
                                    ),
                                    radius = r * (if (isSelected) 2.5f else 1.9f),
                                    center = sPos,
                                )

                                // Inner core node
                                drawCircle(
                                    color = baseColor.copy(alpha = nodeAlpha),
                                    radius = r,
                                    center = sPos,
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = (if (isSelected) 0.95f else 0.45f) * nodeAlpha),
                                    radius = r,
                                    center = sPos,
                                    style = Stroke(width = if (isSelected) 3.2f else 1.4f),
                                )

                                val shouldDrawLabel = node.kind == "root" || node.kind == "hub" || isSelected || isNeighbor || showDetailLabels
                                if (shouldDrawLabel && nodeAlpha > 0.2f) {
                                    val shortTitle = if (node.label.length > 14) node.label.take(13) + "…" else node.label
                                    val measured = textMeasurer.measure(
                                        text = shortTitle,
                                        style = TextStyle(
                                            color = Color(0xFFEAF2FA).copy(alpha = nodeAlpha),
                                            fontSize = if (node.kind == "root" || node.kind == "hub") 12.sp else 10.5.sp,
                                            fontWeight = if (node.kind == "root" || node.kind == "hub" || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                    )
                                    val tx = sPos.x - measured.size.width / 2f
                                    val ty = sPos.y + r + 5f
                                    drawRoundRect(
                                        color = Color(0xFF0A121C).copy(alpha = 0.72f * nodeAlpha),
                                        topLeft = Offset(tx - 6f, ty - 2f),
                                        size = androidx.compose.ui.geometry.Size(
                                            measured.size.width + 12f,
                                            measured.size.height + 4f,
                                        ),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                                    )
                                    drawText(
                                        textLayoutResult = measured,
                                        topLeft = Offset(tx, ty),
                                    )
                                }
                            }
                        }
                    }

                    // Top-left stats / filter hint badge
                    val statusHint = if (activeFilter != "all" || selectedNodeId != null) {
                        "筛选中 · 点空白处返回全部"
                    } else {
                        "${safeGraph.stats.nodeCount} 节点 · ${safeGraph.stats.edgeCount} 关联"
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF0E1928).copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, Color(0xFF28415C)),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .widthIn(max = 200.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = activeFilter != "all" || selectedNodeId != null) {
                                activeFilter = "all"
                                selectedNodeId = null
                            },
                    ) {
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeFilter != "all" || selectedNodeId != null) Color(0xFF7DD3FC) else Color(0xFFBFD3E6),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }

                    // Top-right refresh & fullscreen toggle pills
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF0E1928).copy(alpha = 0.86f),
                            border = BorderStroke(1.dp, Color(0xFF28415C)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { onRefresh() },
                        ) {
                            Text(
                                text = if (loading) "同步中" else "刷新",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF7DD3FC),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF12324B).copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.65f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { isFullscreen = !fullscreenMode },
                        ) {
                            Text(
                                text = if (fullscreenMode) "退出全屏" else "全屏",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }

                    // Bottom-right Zoom & Reset controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GraphHudButton("+") { zoomScale = (zoomScale * 1.25f).coerceAtMost(2.8f) }
                        GraphHudButton("−") { zoomScale = (zoomScale * 0.8f).coerceAtLeast(0.36f) }
                        GraphHudButton("⟲") {
                            zoomScale = 0.78f
                            panOffset = Offset.Zero
                            selectedNodeId = null
                            activeFilter = "all"
                        }
                    }

                    // Bottom-left compact color legend
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0E1928).copy(alpha = 0.84f),
                        border = BorderStroke(1.dp, Color(0xFF253B52)),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LegendDot(Color(0xFFF59E0B), "记忆")
                            LegendDot(Color(0xFF2DD4BF), "笔记")
                            LegendDot(Color(0xFFA78BFA), "日记")
                            LegendDot(Color(0xFFFB7185), "文档")
                            LegendDot(Color(0xFFF472B6), "标签")
                        }
                    }
                }
            }
        }
    }

    renderGraphWorkspace(
        fullscreenMode = false,
        containerModifier = modifier.fillMaxSize(),
    )

    if (!isFullscreen) {
        inspectSheetNode?.let { node ->
            GraphNodeInspectSheet(
                node = node,
                layout = layout,
                onDismiss = { inspectSheetNode = null },
                onJumpNeighbor = { nb, targetWorld ->
                    selectedNodeId = nb.id
                    inspectSheetNode = nb
                    if (targetWorld != null) {
                        panOffset = -targetWorld * zoomScale
                    }
                },
                onFocusCategory = { cat ->
                    activeFilter = cat
                    inspectSheetNode = null
                },
                onOpenDoc = { path, title ->
                    inspectSheetNode = null
                    isFullscreen = false
                    onOpenDoc(path, title)
                },
                onAskHermes = { targetNode ->
                    val targetDesc = targetNode.docPath.ifBlank { targetNode.label }
                    inspectSheetNode = null
                    isFullscreen = false
                    onAskHermes("请帮我查看并整理知识库中与「${targetNode.label}」（$targetDesc）相关的记录和脉络，如果有值得串联的灵感请一并归纳。")
                },
            )
        }
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            BackHandler { isFullscreen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A111A))
                    .systemBarsPadding()
                    .padding(10.dp),
            ) {
                renderGraphWorkspace(
                    fullscreenMode = true,
                    containerModifier = Modifier.fillMaxSize(),
                )
            }
            inspectSheetNode?.let { node ->
                GraphNodeInspectSheet(
                    node = node,
                    layout = layout,
                    onDismiss = { inspectSheetNode = null },
                    onJumpNeighbor = { nb, targetWorld ->
                        selectedNodeId = nb.id
                        inspectSheetNode = nb
                        if (targetWorld != null) {
                            panOffset = -targetWorld * zoomScale
                        }
                    },
                    onFocusCategory = { cat ->
                        activeFilter = cat
                        inspectSheetNode = null
                    },
                    onOpenDoc = { path, title ->
                        inspectSheetNode = null
                        isFullscreen = false
                        onOpenDoc(path, title)
                    },
                    onAskHermes = { targetNode ->
                        val targetDesc = targetNode.docPath.ifBlank { targetNode.label }
                        inspectSheetNode = null
                        isFullscreen = false
                        onAskHermes("请帮我查看并整理知识库中与「${targetNode.label}」（$targetDesc）相关的记录和脉络，如果有值得串联的灵感请一并归纳。")
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GraphNodeInspectSheet(
    node: GraphNodeItem,
    layout: GraphLayoutResult?,
    onDismiss: () -> Unit,
    onJumpNeighbor: (GraphNodeItem, Offset?) -> Unit,
    onFocusCategory: (String) -> Unit,
    onOpenDoc: (String, String) -> Unit,
    onAskHermes: (GraphNodeItem) -> Unit,
) {
    val neighbors = layout?.neighbors?.get(node.id).orEmpty()
    val accent = colorForNodeKind(node.kind, node.category)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PacePalette.Card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = labelForNodeKind(node.kind, node.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = node.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.updatedAt.isNotBlank()) {
                    Text(
                        text = node.updatedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = PacePalette.TextSecondary,
                    )
                }
            }

            if (node.subtitle.isNotBlank() || node.docPath.isNotBlank()) {
                Text(
                    text = node.docPath.ifBlank { node.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = PacePalette.TextSecondary,
                )
            }

            if (node.preview.isNotBlank()) {
                Surface(
                    color = PacePalette.Background,
                    border = BorderStroke(1.dp, PacePalette.CardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = node.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (neighbors.isNotEmpty()) {
                Text(
                    text = "关联节点 (${neighbors.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = PacePalette.TextSecondary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    neighbors.take(16).forEach { (nb, relLabel) ->
                        val nbColor = colorForNodeKind(nb.kind, nb.category)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = nbColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, nbColor.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable {
                                onJumpNeighbor(nb, layout?.positions?.get(nb.id))
                            },
                        ) {
                            Text(
                                text = if (relLabel.isNotBlank()) "${nb.label} · $relLabel" else nb.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = PacePalette.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (node.docPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onOpenDoc(node.docPath, node.label) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("打开查看内容")
                    }
                } else if (node.kind == "hub") {
                    OutlinedButton(
                        onClick = { onFocusCategory(node.category) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("聚焦该分类")
                    }
                }

                Button(
                    onClick = { onAskHermes(node) },
                    modifier = Modifier.weight(1.25f),
                ) {
                    Text("让小洛分析")
                }
            }
        }
    }
}

@Composable
private fun GraphHudButton(
    symbol: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xFF0E1928).copy(alpha = 0.88f),
        border = BorderStroke(1.dp, Color(0xFF28415C)),
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), fontSize = 10.sp)
    }
}
