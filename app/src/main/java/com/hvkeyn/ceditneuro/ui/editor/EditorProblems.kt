package com.hvkeyn.ceditneuro.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class EditorProblem(val line: Int, val message: String, val error: Boolean)

/**
 * A small, local problems pass. It catches structural errors that are cheap to prove:
 * unbalanced brackets outside strings/comments and unresolved merge markers.
 * It is not a compiler and does not pretend to replace the language server.
 */
fun findProblems(text: String, path: String = ""): List<EditorProblem> {
    val problems = mutableListOf<EditorProblem>()
    val markup = path.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "svg", "xml", "css", "scss")
    if (markup) {
        text.lineSequence().forEachIndexed { lineNumber, value ->
            val trimmed = value.trimStart()
            if (trimmed.startsWith("<<<<<<<") || trimmed.startsWith("=======") || trimmed.startsWith(">>>>>>>")) {
                problems += EditorProblem(lineNumber, "Unresolved merge marker", error = true)
            }
        }
        return problems
    }
    val stack = ArrayDeque<Pair<Char, Int>>()
    var line = 0
    var index = 0
    var quote: Char? = null
    var lineComment = false
    var blockComment = false
    var escaped = false

    while (index < text.length) {
        val c = text[index]
        if (c == '\n') {
            line++
            lineComment = false
            index++
            continue
        }
        if (lineComment) {
            index++
            continue
        }
        if (blockComment) {
            if (c == '*' && index + 1 < text.length && text[index + 1] == '/') {
                blockComment = false
                index += 2
            } else {
                index++
            }
            continue
        }
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quote) {
                quote = null
            }
            index++
            continue
        }
        if (c == '/' && index + 1 < text.length && text[index + 1] == '/') {
            lineComment = true
            index += 2
            continue
        }
        if (c == '/' && index + 1 < text.length && text[index + 1] == '*') {
            blockComment = true
            index += 2
            continue
        }
        if (c == '#' && (index == 0 || text[index - 1] == '\n' || text[index - 1].isWhitespace())) {
            lineComment = true
            index++
            continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            quote = c
            index++
            continue
        }
        when (c) {
            '{', '[', '(' -> stack.addLast(c to line)
            '}', ']', ')' -> {
                val expected = when (c) {
                    '}' -> '{'
                    ']' -> '['
                    else -> '('
                }
                if (stack.isEmpty() || stack.last().first != expected) {
                    problems += EditorProblem(line, "Unmatched $c", error = true)
                } else {
                    stack.removeLast()
                }
            }
        }
        index++
    }
    stack.forEach { (open, at) ->
        problems += EditorProblem(at, "Unclosed $open", error = true)
    }
    text.lineSequence().forEachIndexed { lineNumber, value ->
        val trimmed = value.trimStart()
        if (trimmed.startsWith("<<<<<<<") || trimmed.startsWith("=======") || trimmed.startsWith(">>>>>>>")) {
            problems += EditorProblem(lineNumber, "Unresolved merge marker", error = true)
        }
    }
    return problems.distinctBy { it.line to it.message }.take(200)
}

@Composable
fun ProblemsDialog(
    text: String,
    path: String = "",
    onSelectLine: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val problems = remember(text, path) { findProblems(text, path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (problems.isEmpty()) "Problems" else "Problems · ${problems.size}") },
        text = {
            if (problems.isEmpty()) {
                Text("No unbalanced brackets or merge markers found. Language-server errors still show in the editor.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(problems, key = { "${it.line}:${it.message}" }) { problem ->
                        Text(
                            text = "${problem.line + 1}: ${problem.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (problem.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLine(problem.line) }
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
        },
    )
}
