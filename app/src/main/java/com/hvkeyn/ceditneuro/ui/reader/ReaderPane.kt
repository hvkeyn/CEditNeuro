package com.hvkeyn.ceditneuro.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hvkeyn.ceditneuro.data.ReaderNote
import com.hvkeyn.ceditneuro.ui.ReaderView

private data class Paper(val background: Color, val ink: Color, val muted: Color)

private fun paper(theme: String): Paper = when (theme) {
    "night" -> Paper(Color(0xFF1C1A17), Color(0xFFE6E0D6), Color(0xFFB3AA9E))
    "day" -> Paper(Color(0xFFF7F4EF), Color(0xFF1E1A16), Color(0xFF5C564E))
    else -> Paper(Color(0xFFF3E6C8), Color(0xFF2B2416), Color(0xFF6B5E48))
}

@Composable
fun ReaderPane(
    reader: ReaderView,
    pageText: String,
    onClose: () -> Unit,
    onTurn: (Int) -> Unit,
    onFont: (Int) -> Unit,
    onTheme: () -> Unit,
    onBookmark: () -> Unit,
    onGoTo: (Int) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (ReaderNote) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paper = paper(reader.theme)
    var chrome by remember { mutableStateOf(true) }
    var toc by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Box(modifier = modifier.fillMaxSize().background(paper.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(
                text = reader.chapter,
                color = paper.muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pageText,
                color = paper.ink,
                fontSize = reader.fontSp.sp,
                lineHeight = (reader.fontSp * 1.45f).sp,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            )
            Text(
                text = "${reader.page + 1} / ${reader.pageCount}  ·  ${reader.percent}%",
                color = paper.muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize().clickable { onTurn(-1) })
            Box(modifier = Modifier.weight(1.2f).fillMaxSize().clickable { chrome = !chrome })
            Box(modifier = Modifier.weight(1f).fillMaxSize().clickable { onTurn(1) })
        }
        if (chrome) {
            Surface(color = paper.background.copy(alpha = 0.94f), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    IconButton(onClick = { onFont(-1) }) { Icon(Icons.Default.FormatSize, "Smaller text", tint = paper.ink) }
                    IconButton(onClick = { onFont(1) }) { Icon(Icons.Default.Add, "Larger text", tint = paper.ink) }
                    TextButton(onClick = onTheme) { Text(reader.theme, color = paper.ink) }
                    TextButton(onClick = { notes = true }) { Text("Notes", color = paper.ink) }
                }
            }
        }
    }
    if (toc) {
        AlertDialog(
            onDismissRequest = { toc = false },
            title = { Text("Contents") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reader.toc.forEach { item ->
                        Text(
                            text = item.title,
                            modifier = Modifier.clickable { toc = false; onGoTo(item.page) },
                        )
                    }
                    if (reader.bookmarks.isNotEmpty()) {
                        Text("Bookmarks", style = MaterialTheme.typography.titleSmall)
                        reader.bookmarks.forEach { page ->
                            Text(
                                text = "Page ${page + 1}",
                                modifier = Modifier.clickable { toc = false; onGoTo(page) },
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
                            Text(
                                text = "p.${note.page + 1}  ${note.text}",
                                modifier = Modifier.weight(1f).clickable { notes = false; onGoTo(note.page) },
                            )
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
