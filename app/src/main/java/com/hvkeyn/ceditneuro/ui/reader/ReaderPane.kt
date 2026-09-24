package com.hvkeyn.ceditneuro.ui.reader

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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

private fun paper(theme: String): Paper = when (theme) {
    "night" -> Paper(Color(0xFF1C1A17), Color(0xFFE6E0D6), Color(0xFFB3AA9E))
    "day" -> Paper(Color(0xFFF7F4EF), Color(0xFF1E1A16), Color(0xFF5C564E))
    else -> Paper(Color(0xFFF3E6C8), Color(0xFF2B2416), Color(0xFF6B5E48))
}

private data class Spread(val ranges: List<IntRange>, val chapters: List<String>)

@Composable
fun ReaderPane(
    reader: ReaderView,
    pageText: String,
    onClose: () -> Unit,
    onStyle: (fontSp: Int, fontName: String, spacing: Float, theme: String) -> Unit,
    onProgress: (page: Int, pageCount: Int, chapter: String) -> Unit,
    onBookmark: () -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (ReaderNote) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paper = paper(reader.theme)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var chrome by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var page by remember(pageText) { mutableIntStateOf(reader.page) }
    val font = when (reader.fontName) {
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(paper.background)) {
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = side, vertical = 8.dp)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                shown.forEach { column ->
                    Text(
                        text = column,
                        style = style,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Text(
                text = "${page + 1} / $count  ·  ${((page + 1) * 100 / count)}%",
                color = paper.muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
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
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close reader", tint = paper.ink) }
                    Text(
                        text = reader.title,
                        color = paper.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { toc = true }) { Icon(Icons.Default.List, "Contents", tint = paper.ink) }
                    IconButton(onClick = onBookmark) {
                        Icon(
                            if (reader.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            "Bookmark",
                            tint = paper.ink,
                        )
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, "Text settings", tint = paper.ink) }
                    TextButton(onClick = { notes = true }) { Text("Notes", color = paper.ink) }
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
        if (toc) {
            AlertDialog(
                onDismissRequest = { toc = false },
                title = { Text("Contents") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
        if (text[start] == '\u0000') {
            val endTitle = text.indexOf('\n', start + 1).let { if (it < 0) text.length else it }
            chapter = text.substring(start + 1, endTitle).trim()
            start = (endTitle + 1).coerceAtMost(text.length)
            continue
        }
        val window = min(text.length, start + budget)
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
