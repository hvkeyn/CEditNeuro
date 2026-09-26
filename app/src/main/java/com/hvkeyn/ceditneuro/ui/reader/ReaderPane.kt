package com.hvkeyn.ceditneuro.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hvkeyn.ceditneuro.data.ReaderNote
import com.hvkeyn.ceditneuro.ui.ReaderView
import kotlin.math.min

private data class Paper(val background: Color, val ink: Color, val muted: Color)

@Composable
private fun paperFieldColors(paper: Paper) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = paper.ink,
    unfocusedTextColor = paper.ink,
    cursorColor = paper.ink,
    focusedBorderColor = paper.ink,
    unfocusedBorderColor = paper.muted,
    focusedLabelColor = paper.ink,
    unfocusedLabelColor = paper.muted,
    focusedContainerColor = paper.background,
    unfocusedContainerColor = paper.background,
)

private fun screenBrightness(view: android.view.View, value: Float) {
    var context = view.context
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) {
            val attrs = context.window.attributes
            attrs.screenBrightness = value
            context.window.attributes = attrs
            return
        }
        context = context.baseContext
    }
}

private fun paper(theme: String): Paper = when (theme) {
    "night" -> Paper(Color(0xFF1C1A17), Color(0xFFE6E0D6), Color(0xFFB3AA9E))
    "day" -> Paper(Color(0xFFF7F4EF), Color(0xFF1E1A16), Color(0xFF5C564E))
    else -> Paper(Color(0xFFF3E6C8), Color(0xFF2B2416), Color(0xFF6B5E48))
}

private data class Spread(val ranges: List<IntRange>, val chapters: List<String>)

private const val FIND_LIMIT = 60

/** Page index and a short snippet for each match, found on the phone without the model. */
internal fun findPages(text: String, ranges: List<IntRange>, query: String): List<Pair<Int, String>> {
    val needle = query.trim()
    if (needle.length < 2 || ranges.isEmpty()) return emptyList()
    val hits = ArrayList<Pair<Int, String>>()
    var from = 0
    var rangeIndex = 0
    while (hits.size < FIND_LIMIT) {
        val at = text.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        while (rangeIndex < ranges.lastIndex && at > ranges[rangeIndex].last) rangeIndex++
        val start = (at - 40).coerceAtLeast(0)
        val end = (at + needle.length + 60).coerceAtMost(text.length)
        val snippet = text.substring(start, end)
            .replace('\u0000', ' ')
            .replace(Regex("\u0001[^\u0001]*\u0001"), " ")
            .replace('\n', ' ')
            .trim()
        hits += rangeIndex to snippet
        from = at + needle.length
    }
    return hits
}

@Composable
fun ReaderPane(
    reader: ReaderView,
    pageText: String,
    images: Map<String, ByteArray> = emptyMap(),
    onClose: () -> Unit,
    onStyle: (fontSp: Int, fontName: String, spacing: Float, theme: String) -> Unit,
    onProgress: (page: Int, pageCount: Int, chapter: String) -> Unit,
    onBookmark: () -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (ReaderNote) -> Unit,
    onInk: (page: Int, color: Long, width: Float, points: List<com.hvkeyn.ceditneuro.data.InkPoint>) -> Unit = { _, _, _, _ -> },
    onLabel: (page: Int, text: String, x: Float, y: Float, color: Long) -> Unit = { _, _, _, _, _ -> },
    onMoveLabel: (id: String, x: Float, y: Float, scale: Float, rotation: Float) -> Unit = { _, _, _, _, _ -> },
    onDeleteMarkup: (String) -> Unit = {},
    onClearPage: (Int) -> Unit = {},
    onRestyle: (id: String, color: Long?, width: Float?, scale: Float?, rotation: Float?) -> Unit = { _, _, _, _, _ -> },
    onExplain: (page: Int, passage: String) -> Unit = { _, _ -> },
    onAsk: (page: Int, passage: String, question: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val paper = paper(reader.theme)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var chrome by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("read") }
    var penColor by remember { mutableStateOf(markupColors[0]) }
    var textColor by remember { mutableStateOf(markupColors[1]) }
    var penWidth by remember { mutableFloatStateOf(7f) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var penTool by remember { mutableStateOf("draw") }
    var showLayer by remember { mutableStateOf(true) }
    var dockPanel by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    var bookQuestion by remember { mutableStateOf("") }
    val view = LocalView.current
    val context = LocalContext.current
    val aloud = remember { ReadAloud(context.applicationContext) }
    var speaking by remember { mutableStateOf(false) }
    val keys = remember { FocusRequester() }
    var brightness by remember { mutableFloatStateOf(0.7f) }
    DisposableEffect(view, aloud) {
        view.keepScreenOn = true
        onDispose {
            screenBrightness(view, -1f)
            view.keepScreenOn = false
            aloud.release()
        }
    }
    LaunchedEffect(mode) {
        if (mode == "read") runCatching { keys.requestFocus() }
    }
    var page by remember(pageText) { mutableIntStateOf(reader.page) }
    val font = when (reader.fontName) {
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }
    var turn by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(paper.background)
            .onPreviewKeyEvent { event ->
                if (mode != "read" || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.VolumeDown, Key.PageDown, Key.DirectionRight -> { turn = 1; true }
                    Key.VolumeUp, Key.PageUp, Key.DirectionLeft -> { turn = -1; true }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable(),
    ) {
        val landscape = maxWidth > maxHeight
        val columns = if (landscape && maxWidth > 640.dp) 2 else 1
        val side = if (landscape) 28.dp else 20.dp
        val textWidth = (maxWidth - side * 2) / columns - if (columns == 2) 12.dp else 0.dp
        val textHeight = maxHeight - 36.dp
        val style = TextStyle(
            fontFamily = font,
            fontSize = reader.fontSp.sp,
            lineHeight = (reader.fontSp * reader.spacing).sp,
            color = paper.ink,
        )
        val spread = remember(pageText, textWidth, textHeight, reader.fontSp, reader.fontName, reader.spacing) {
            with(density) {
                paginate(
                    pageText,
                    measurer,
                    style,
                    textWidth.roundToPx().coerceAtLeast(1),
                    textHeight.roundToPx().coerceAtLeast(1),
                )
            }
        }
        val count = spread.ranges.size.coerceAtLeast(1)
        if (page > count - 1) page = count - 1
        LaunchedEffect(count, page, spread.chapters.getOrNull(page)) {
            onProgress(page, count, spread.chapters.getOrNull(page).orEmpty())
        }
        val shown = (0 until columns).map { column ->
            val index = page + column
            spread.ranges.getOrNull(index)?.let { pageText.substring(it.first, it.last + 1).trim('\u0000', '\n', ' ') }.orEmpty()
        }
        LaunchedEffect(turn) {
            if (turn != 0) {
                page = (page + turn * columns).coerceIn(0, count - 1)
                turn = 0
            }
        }
        LaunchedEffect(speaking, page, count) {
            if (!speaking) {
                aloud.stop()
                return@LaunchedEffect
            }
            aloud.speak(shown.joinToString("\n")) {
                if (page + columns <= count - 1) page += columns else speaking = false
            }
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = side, vertical = 8.dp)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (column in shown) {
                    val imageId = Regex("^\u0001(.+)\u0001$").find(column)?.groupValues?.get(1)
                    val bitmap = imageId?.let { id ->
                        androidx.compose.runtime.remember(id, images[id]) {
                            images[id]?.let { bytes ->
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                    }
                    if (bitmap != null) {
                        ZoomableImage(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = imageId,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Text(
                            text = column,
                            style = style,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(paper.muted.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((page + 1).toFloat() / count)
                        .fillMaxHeight()
                        .background(paper.muted),
                )
            }
            Text(
                text = "${page + 1} / $count  ·  ${reader.chapter.ifBlank { "page" }}  ·  ${((page + 1) * 100 / count)}%" +
                    if (speaking) "  ·  reading aloud" else "",
                color = paper.muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
        if (showLayer) InkLayer(
                page = page,
                ink = reader.ink,
                labels = reader.labels,
                color = penColor.toLong(),
                width = penWidth,
                drawing = mode == "pen" && penTool == "draw",
                erasing = mode == "pen" && penTool == "erase",
                selecting = mode == "pen" && penTool == "select",
                selectedId = selectedLabel,
                onStroke = { points -> onInk(page, penColor.toLong(), penWidth, points) },
                onMove = { label, x, y -> onMoveLabel(label.id, x, y, label.scale, label.rotation) },
                onSelect = { selectedLabel = it },
                onDelete = {
                    onDeleteMarkup(it)
                    if (selectedLabel == it) selectedLabel = null
                },
        )
        if (mode == "read") Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(count, columns) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val threshold = 48.dp.toPx()
                            if (dragged < -threshold) page = (page + columns).coerceAtMost(count - 1)
                            else if (dragged > threshold) page = (page - columns).coerceAtLeast(0)
                        },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                },
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxSize().clickable {
                page = (page - columns).coerceAtLeast(0)
            })
            Box(modifier = Modifier.weight(1.1f).fillMaxSize().clickable { chrome = !chrome })
            Box(modifier = Modifier.weight(1f).fillMaxSize().clickable {
                page = (page + columns).coerceAtMost(count - 1)
            })
        }
        if (chrome) {
            Surface(color = paper.background.copy(alpha = 0.94f), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close reader", tint = paper.ink) }
                    Text(
                        text = reader.title,
                        color = paper.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    IconButton(onClick = { toc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Contents", tint = paper.ink) }
                    IconButton(onClick = { finding = true }) { Icon(Icons.Default.Search, "Find in book", tint = paper.ink) }
                    IconButton(onClick = { speaking = !speaking }) {
                        Icon(
                            if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            if (speaking) "Stop reading aloud" else "Read aloud",
                            tint = paper.ink,
                        )
                    }
                    IconButton(onClick = onBookmark) {
                        Icon(
                            if (reader.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            "Bookmark",
                            tint = paper.ink,
                        )
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, "Text settings", tint = paper.ink) }
                    TextButton(onClick = { mode = if (mode == "pen") "read" else "pen" }) { Text("Pen", color = paper.ink) }
                    TextButton(onClick = { showLayer = !showLayer }) { Text(if (showLayer) "Layer" else "Layer off", color = paper.ink) }
                    TextButton(onClick = { mode = if (mode == "notes") "read" else "notes" }) { Text("Notes", color = paper.ink) }
                    TextButton(onClick = {
                        mode = "book"
                        chrome = false
                    }) { Text("Book", color = paper.ink) }
                }
            }
        }
        if (mode == "pen") {
            val selected = reader.labels.find { it.id == selectedLabel }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            MarkupDock(
                tool = penTool,
                panel = dockPanel,
                layerOn = showLayer,
                penColor = penColor,
                width = penWidth,
                draft = draft,
                selected = selected != null || reader.ink.any { it.id == selectedLabel },
                textSelected = selected != null,
                onTool = { penTool = it },
                onPanel = { dockPanel = it },
                onLayer = { showLayer = it },
                onPenColor = { chosen ->
                    penColor = chosen
                    textColor = chosen
                    selectedLabel?.let { onRestyle(it, chosen.toLong(), null, null, null) }
                },
                onWidth = { value ->
                    penWidth = value
                    val ink = reader.ink.find { it.id == selectedLabel }
                    if (ink != null) onRestyle(ink.id, null, value, null, null)
                },
                onDraft = { draft = it },
                onPlace = {
                    onLabel(page, draft, 0.2f, 0.35f, penColor.toLong())
                    draft = ""
                },
                onClearPage = { onClearPage(page) },
                onBigger = { selected?.let { onMoveLabel(it.id, it.x, it.y, it.scale * 1.15f, it.rotation) } },
                onSmaller = { selected?.let { onMoveLabel(it.id, it.x, it.y, it.scale / 1.15f, it.rotation) } },
                onRotate = { selected?.let { onMoveLabel(it.id, it.x, it.y, it.scale, it.rotation + 15f) } },
                onDelete = {
                    selectedLabel?.let(onDeleteMarkup)
                    selectedLabel = null
                },
            )
            }
        }
        if (mode == "book") {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 420.dp)
                    .background(paper.background)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Book chat · page ${page + 1}", color = paper.ink, style = MaterialTheme.typography.titleMedium)
                Text("This chat stays with the book and reads the whole file.", color = paper.muted, style = MaterialTheme.typography.labelSmall)
                Column(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (reader.bookChat.isEmpty()) {
                        Text("Ask about this book.", color = paper.muted)
                    }
                    reader.bookChat.forEach { line ->
                        Text(
                            text = if (line.mine) "You: ${line.text}" else line.text,
                            color = paper.ink,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = bookQuestion,
                    onValueChange = { bookQuestion = it },
                    label = { Text("About this book", color = paper.ink) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = paperFieldColors(paper),
                )
                Row {
                    TextButton(onClick = {
                        onExplain(page, shown.joinToString("\n"))
                    }) { Text("Explain page", color = paper.ink) }
                    TextButton(
                        onClick = {
                            onAsk(page, shown.joinToString("\n"), bookQuestion)
                            bookQuestion = ""
                        },
                        enabled = bookQuestion.isNotBlank(),
                    ) { Text("Ask", color = paper.ink) }
                    TextButton(onClick = { mode = "read" }) { Text("Close", color = paper.ink) }
                }
            }
        }
        if (mode == "notes") {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .background(paper.background)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Notes · page ${page + 1}", color = paper.ink, style = MaterialTheme.typography.titleMedium)
                val pageNotes = reader.notes.filter { it.page == page }
                if (pageNotes.isEmpty()) {
                    Text("No records on this page yet.", color = paper.muted)
                }
                pageNotes.forEach { note ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { NoteCard("Saved", note.text, markupColor(0xFF175CD3)) }
                        TextButton(onClick = { onDeleteNote(note) }) { Text("Delete", color = paper.ink) }
                    }
                }
                val elsewhere = reader.notes.filter { it.page != page }.sortedBy { it.page }
                if (elsewhere.isNotEmpty()) {
                    Text("Other pages", color = paper.muted, style = MaterialTheme.typography.labelMedium)
                    elsewhere.forEach { note ->
                        Text(
                            text = "p.${note.page + 1} · ${note.text}",
                            color = paper.ink,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { page = note.page.coerceIn(0, count - 1) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    label = { Text("Record for this page", color = paper.ink) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = paperFieldColors(paper),
                )
                Row {
                    TextButton(
                        onClick = {
                            onAddNote(noteDraft)
                            noteDraft = ""
                        },
                        enabled = noteDraft.isNotBlank(),
                    ) { Text("Add", color = paper.ink) }
                    TextButton(onClick = { mode = "read" }) { Text("Close", color = paper.ink) }
                }
            }
        }
        if (settings) {
            AlertDialog(
                onDismissRequest = { settings = false },
                title = { Text("Text") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Size ${reader.fontSp}")
                        Row {
                            TextButton(onClick = { onStyle(reader.fontSp - 1, reader.fontName, reader.spacing, reader.theme) }) { Text("Smaller") }
                            TextButton(onClick = { onStyle(reader.fontSp + 1, reader.fontName, reader.spacing, reader.theme) }) { Text("Larger") }
                        }
                        Text("Font")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("serif" to "Serif", "sans" to "Sans", "mono" to "Mono").forEach { (id, label) ->
                                FilterChip(
                                    selected = reader.fontName == id,
                                    onClick = { onStyle(reader.fontSp, id, reader.spacing, reader.theme) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text("Spacing")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1.25f to "Tight", 1.45f to "Normal", 1.7f to "Wide").forEach { (value, label) ->
                                FilterChip(
                                    selected = reader.spacing == value,
                                    onClick = { onStyle(reader.fontSp, reader.fontName, value, reader.theme) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text("Brightness")
                        Slider(
                            value = brightness,
                            onValueChange = {
                                brightness = it
                                screenBrightness(view, it)
                            },
                            valueRange = 0.05f..1f,
                        )
                        Text("Paper")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("day", "sepia", "night").forEach { theme ->
                                FilterChip(
                                    selected = reader.theme == theme,
                                    onClick = { onStyle(reader.fontSp, reader.fontName, reader.spacing, theme) },
                                    label = { Text(theme) },
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } },
            )
        }
        if (finding) {
            val hits = remember(findQuery, pageText, spread) { findPages(pageText, spread.ranges, findQuery) }
            AlertDialog(
                onDismissRequest = { finding = false },
                title = { Text("Find in book") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            singleLine = true,
                            label = { Text("Word or phrase") },
                        )
                        Text(
                            when {
                                findQuery.trim().length < 2 -> "Type at least 2 letters."
                                hits.isEmpty() -> "Not found."
                                hits.size >= FIND_LIMIT -> "First $FIND_LIMIT matches."
                                else -> "${hits.size} matches."
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            hits.forEach { hit ->
                                Text(
                                    text = "p.${hit.first + 1} · ${hit.second}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { finding = false; page = hit.first.coerceIn(0, count - 1) }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { finding = false }) { Text("Close") } },
            )
        }
        if (toc) {
            AlertDialog(
                onDismissRequest = { toc = false },
                title = { Text("Contents") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("Page ${page + 1} of $count")
                        androidx.compose.material3.Slider(
                            value = page.toFloat(),
                            onValueChange = { page = it.toInt().coerceIn(0, count - 1) },
                            valueRange = 0f..(count - 1).coerceAtLeast(0).toFloat(),
                        )
                        if (reader.bookmarks.isNotEmpty()) {
                            Text("Moments")
                            reader.bookmarks.forEach { mark ->
                                Text(
                                    text = "p.${mark + 1}",
                                    modifier = Modifier.clickable { toc = false; page = mark.coerceIn(0, count - 1) }.padding(vertical = 4.dp),
                                )
                            }
                        }
                        spread.chapters.forEachIndexed { index, title ->
                            if (title.isNotBlank() && (index == 0 || title != spread.chapters[index - 1])) {
                                Text(
                                    text = title,
                                    modifier = Modifier.clickable { toc = false; page = index }.padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { toc = false }) { Text("Close") } },
            )
        }
        if (notes) {
            AlertDialog(
                onDismissRequest = { notes = false },
                title = { Text("Notes") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        reader.notes.forEach { note ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("p.${note.page + 1}  ${note.text}", modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDeleteNote(note) }) { Text("Delete") }
                            }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Note for this page") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (draft.isNotBlank()) onAddNote(draft.trim())
                        draft = ""
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { notes = false }) { Text("Close") } },
            )
        }
    }
}

private fun paginate(
    text: String,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    width: Int,
    height: Int,
): Spread {
    if (text.isBlank()) return Spread(listOf(0..0), listOf(""))
    val sample = "Строка для замера ширины страницы читалки. ".repeat(6)
    val layout = measurer.measure(sample, style = style, constraints = Constraints(maxWidth = width))
    val lines = layout.lineCount.coerceAtLeast(1)
    val lineHeight = (layout.size.height / lines).coerceAtLeast(1)
    val charsPerLine = (sample.length / lines).coerceAtLeast(12)
    val budget = (charsPerLine * (height / lineHeight).coerceAtLeast(1)).coerceIn(240, 3_200)
    val ranges = ArrayList<IntRange>()
    val chapters = ArrayList<String>()
    var chapter = ""
    var start = 0
    while (start < text.length) {
        if (text[start] == '\u0001') {
            val close = text.indexOf('\u0001', start + 1)
            if (close > start) {
                ranges.add(start..close)
                chapters.add(chapter)
                start = close + 1
                while (start < text.length && text[start].isWhitespace() && text[start] != '\u0000') start++
                continue
            }
        }
        if (text[start] == '\u0000') {
            val endTitle = text.indexOf('\n', start + 1).let { if (it < 0) text.length else it }
            chapter = text.substring(start + 1, endTitle).trim()
            start = (endTitle + 1).coerceAtMost(text.length)
            continue
        }
        val imageAt = text.indexOf('\u0001', start).takeIf { it > start }
        val window = minOf(text.length, start + budget, imageAt ?: text.length)
        val broken = if (window >= text.length) {
            window
        } else {
            text.lastIndexOf('\n', window - 1).takeIf { it > start + budget / 5 }
                ?: text.lastIndexOf(' ', window - 1).takeIf { it > start + budget / 5 }
                ?: window
        }
        val end = broken.coerceIn(start + 1, text.length)
        ranges.add(start until end)
        chapters.add(chapter)
        start = end
        while (start < text.length && text[start].isWhitespace() && text[start] != '\u0000') start++
    }
    if (ranges.isEmpty()) {
        ranges.add(0 until text.length.coerceAtLeast(1))
        chapters.add(chapter)
    }
    return Spread(ranges, chapters)
}
