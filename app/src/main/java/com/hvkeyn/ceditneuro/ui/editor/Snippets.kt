package com.hvkeyn.ceditneuro.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class EditorSnippet(val label: String, val detail: String, val text: String)

val editorSnippets = listOf(
    EditorSnippet(
        "If block",
        "Kotlin",
        "if (condition) {\n    \n}\n",
    ),
    EditorSnippet(
        "For loop",
        "Kotlin",
        "for (item in items) {\n    \n}\n",
    ),
    EditorSnippet(
        "Function",
        "Kotlin",
        "fun name(value: String): String {\n    return value\n}\n",
    ),
    EditorSnippet(
        "Composable",
        "Compose",
        "@Composable\nfun Name(modifier: Modifier = Modifier) {\n    \n}\n",
    ),
    EditorSnippet(
        "Try / catch",
        "Kotlin",
        "runCatching {\n    \n}.onFailure { error ->\n    \n}\n",
    ),
    EditorSnippet(
        "HTML5 page",
        "HTML",
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title></title>\n</head>\n<body>\n\n</body>\n</html>\n",
    ),
    EditorSnippet(
        "Markdown table",
        "Markdown",
        "| Name | Value |\n| --- | --- |\n|  |  |\n",
    ),
    EditorSnippet(
        "Test",
        "JUnit",
        "@Test\nfun name() {\n    assertEquals(expected, actual)\n}\n",
    ),
    EditorSnippet(
        "TODO",
        "Any",
        "TODO: ",
    ),
)

@Composable
fun SnippetDialog(
    onPick: (EditorSnippet) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert snippet") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(editorSnippets, key = { it.label }) { snippet ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onPick(snippet)
                            }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(snippet.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            snippet.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
