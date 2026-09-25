package com.hvkeyn.ceditneuro.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.LineWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hvkeyn.ceditneuro.data.InkPoint
import com.hvkeyn.ceditneuro.data.PageInk
import com.hvkeyn.ceditneuro.data.PageLabel

internal val markupColors = listOf(
    0xFFB42318.toInt(),
    0xFF175CD3.toInt(),
    0xFF067647.toInt(),
    0xFFB54708.toInt(),
    0xFF1D2939.toInt(),
    0xFFF2C94C.toInt(),
)

internal fun markupColor(argb: Long): Color = Color(argb.toInt())

@Composable
internal fun ZoomableImage(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale <= 1.01f) Offset.Zero else offset + pan
            }
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                transformOrigin = TransformOrigin.Center
            },
        ) { content() }
    }
}

@Composable
internal fun InkLayer(
    page: Int,
    ink: List<PageInk>,
    labels: List<PageLabel>,
    color: Long,
    width: Float,
    drawing: Boolean,
    erasing: Boolean,
    selecting: Boolean,
    selectedId: String?,
    onStroke: (List<InkPoint>) -> Unit,
    onMove: (PageLabel, Float, Float) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var live by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(drawing, erasing, selecting, page, color, width) {
                    if (selecting) {
                        detectTapGestures { offset ->
                            if (size.width == 0 || size.height == 0) return@detectTapGestures
                            val point = InkPoint(offset.x / size.width, offset.y / size.height)
                            val hit = ink.filter { it.page == page }.minByOrNull { stroke ->
                                stroke.points.minOfOrNull { candidate ->
                                    val dx = candidate.x - point.x
                                    val dy = candidate.y - point.y
                                    dx * dx + dy * dy
                                } ?: 99f
                            }
                            if (hit != null) onSelect(hit.id)
                        }
                        return@pointerInput
                    }
                    if (!drawing && !erasing) return@pointerInput
                    detectDragGestures(
                        onDragStart = { live = emptyList() },
                        onDrag = { change, _ ->
                            change.consume()
                            if (size.width == 0 || size.height == 0) return@detectDragGestures
                            val point = InkPoint(
                                change.position.x / size.width,
                                change.position.y / size.height,
                            )
                            if (erasing) {
                                ink.filter { it.page == page }.forEach { stroke ->
                                    if (stroke.points.any { near(it, point) }) onDelete(stroke.id)
                                }
                            } else {
                                live = live + point
                            }
                        },
                        onDragEnd = {
                            if (!erasing) onStroke(live)
                            live = emptyList()
                        },
                    )
                },
        ) {
            val strokes = ink.filter { it.page == page } + if (live.size > 1) {
                listOf(PageInk("live", page, color, width, live))
            } else {
                emptyList()
            }
            strokes.forEach { stroke ->
                val paint = markupColor(stroke.color)
                val points = stroke.points
                for (index in 1 until points.size) {
                    drawLine(
                        color = paint,
                        start = Offset(points[index - 1].x * this.size.width, points[index - 1].y * this.size.height),
                        end = Offset(points[index].x * this.size.width, points[index].y * this.size.height),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        labels.filter { it.page == page }.forEach { label ->
            MarkupWords(
                label = label,
                selected = label.id == selectedId,
                canvas = size,
                movable = selecting || erasing,
                erasing = erasing,
                onSelect = { onSelect(label.id) },
                onMove = { x, y -> onMove(label, x, y) },
                onDelete = { onDelete(label.id) },
            )
        }
    }
}

private fun near(a: InkPoint, b: InkPoint): Boolean {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy < 0.0016f
}

@Composable
private fun MarkupWords(
    label: PageLabel,
    selected: Boolean,
    canvas: IntSize,
    movable: Boolean,
    erasing: Boolean,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onDelete: () -> Unit,
) {
    val ink = markupColor(label.color)
    Text(
        text = label.text,
        color = ink,
        fontSize = (16 * label.scale).sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .graphicsLayer {
                if (canvas.width > 0) {
                    translationX = label.x * canvas.width
                    translationY = label.y * canvas.height
                    rotationZ = label.rotation
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
            .border(if (selected) 1.dp else 0.dp, ink, RoundedCornerShape(4.dp))
            .pointerInput(label.id, canvas, erasing, movable) {
                if (!movable) return@pointerInput
                if (erasing) {
                    detectDragGestures(onDragStart = { onDelete() }, onDrag = { change, _ -> change.consume() })
                    return@pointerInput
                }
                var x = label.x
                var y = label.y
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, drag ->
                        change.consume()
                        if (canvas.width == 0 || canvas.height == 0) return@detectDragGestures
                        x = (x + drag.x / canvas.width).coerceIn(0f, 0.92f)
                        y = (y + drag.y / canvas.height).coerceIn(0f, 0.92f)
                        onMove(x, y)
                    },
                )
            }
            .padding(2.dp),
    )
}

@Composable
internal fun MarkupDock(
    tool: String,
    panel: String,
    layerOn: Boolean,
    penColor: Int,
    width: Float,
    draft: String,
    selected: Boolean,
    textSelected: Boolean,
    onTool: (String) -> Unit,
    onPanel: (String) -> Unit,
    onLayer: (Boolean) -> Unit,
    onPenColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onDraft: (String) -> Unit,
    onPlace: () -> Unit,
    onBigger: () -> Unit,
    onSmaller: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onClearPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1D2939))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "draw" to Icons.Default.Edit,
                "select" to Icons.Default.NearMe,
                "text" to Icons.Default.TextFields,
                "erase" to Icons.Outlined.CleaningServices,
            ).forEach { (id, icon) ->
                IconButton(onClick = { onTool(id) }) {
                    Icon(icon, id, tint = if (tool == id) Color(0xFFF2C94C) else Color.White)
                }
            }
            IconButton(onClick = { onPanel(if (panel == "color") "" else "color") }) {
                Icon(Icons.Default.Palette, "Color", tint = Color.White)
            }
            IconButton(onClick = { onPanel(if (panel == "width") "" else "width") }) {
                Icon(Icons.Outlined.LineWeight, "Width", tint = Color.White)
            }
            IconButton(onClick = { onLayer(!layerOn) }) {
                Icon(if (layerOn) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Layer", tint = Color.White)
            }
            IconButton(onClick = onClearPage) { Icon(Icons.Default.Delete, "Clear", tint = Color.White) }
        }
        if (panel == "color") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                markupColors.forEach { swatch ->
                    Swatch(swatch, penColor == swatch) { onPenColor(swatch) }
                }
            }
        }
        if (panel == "width") {
            androidx.compose.material3.Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 1f..24f,
            )
        }
        if (textSelected) {
            Row {
                TextButton(onClick = onSmaller) { Text("Smaller", color = Color.White) }
                TextButton(onClick = onBigger) { Text("Larger", color = Color.White) }
                TextButton(onClick = onRotate) { Text("Rotate", color = Color.White) }
                TextButton(onClick = onDelete) { Text("Delete", color = Color.White) }
            }
        } else if (selected) {
            TextButton(onClick = onDelete) { Text("Delete selection", color = Color.White) }
        }
        if (tool == "text") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    label = { Text("Words on the page") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPlace, enabled = draft.isNotBlank()) { Text("Put", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x66FFFFFF), CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun WidthDot(size: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color(0xFF98A2B3))
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun NoteCard(title: String, body: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFFBF5))
            .border(1.dp, Color(0xFFE4E7EC), RoundedCornerShape(10.dp)),
    ) {
        Box(Modifier.fillMaxWidth().background(accent).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = Color(0xFF1D2939), fontSize = 14.sp, modifier = Modifier.padding(10.dp))
    }
}
