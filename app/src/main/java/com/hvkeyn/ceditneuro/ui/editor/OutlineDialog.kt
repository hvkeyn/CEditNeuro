package com.hvkeyn.ceditneuro.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class OutlineSymbol(val label: String, val line: Int)

private val symbolPattern = Regex(
    """^\s*(?:export\s+|public\s+|private\s+|protected\s+|internal\s+|static\s+|async\s+)*""" +
        """(?:class|interface|object|enum|struct|fun|function|def|fn|type|record)\s+([A-Za-z_][A-Za-z0-9_]*)""",
)
private val markdownHeading = Regex("""^(#{1,6})\s+(.+)$""")

fun outlineSymbols(text: String): List<OutlineSymbol> {
    val symbols = mutableListOf<OutlineSymbol>()
    text.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        if (line.isBlank()) return@forEachIndexed
        val heading = markdownHeading.find(line)
        if (heading != null) {
            symbols += OutlineSymbol("${"·".repeat(heading.groupValues[1].length)} ${heading.groupValues[2]}", index)
            return@forEachIndexed
        }
        if (line.trimStart().startsWith("//") || line.trimStart().startsWith("#!")) return@forEachIndexed
        val match = symbolPattern.find(line) ?: return@forEachIndexed
        val label = match.groupValues[1]
        symbols += OutlineSymbol(label.ifBlank { line.trim() }, index)
    }
    return symbols.take(400)
}

@Composable
fun OutlineDialog(
    text: String,
    onSelectLine: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val symbols = remember(text) { outlineSymbols(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Outline") },
        text = {
            if (symbols.isEmpty()) {
                Text("No classes, functions, or headings found.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(symbols, key = { "${it.line}:${it.label}" }) { symbol ->
                        Text(
                            text = symbol.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLine(symbol.line) }
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
