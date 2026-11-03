package com.hvkeyn.ceditneuro.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AgentEditContext(
    val path: String,
    val code: String,
    val start: Int,
    val end: Int,
    val selectedText: String,
)

private data class AgentMode(val id: String, val label: String)

private val agentModes = listOf(
    AgentMode("complete", "Complete"),
    AgentMode("rewrite", "Rewrite"),
    AgentMode("snippet", "Snippet"),
    AgentMode("explain", "Explain"),
)

@Composable
fun AgentEditDialog(
    context: AgentEditContext,
    onRequest: (mode: String, instruction: String, onResult: (Result<String>) -> Unit) -> Unit,
    onApply: (text: String, replaceSelection: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val initialMode = if (context.selectedText.isNotBlank()) "rewrite" else "complete"
    var mode by remember(context) { mutableStateOf(initialMode) }
    var instruction by remember(context) { mutableStateOf("") }
    var result by remember(context) { mutableStateOf("") }
    var error by remember(context) { mutableStateOf("") }
    var loading by remember(context) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            onCancel()
            onDismiss()
        },
        title = { Text("Agent edit") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.path.substringAfterLast('/') +
                        if (context.selectedText.isBlank()) " · cursor" else " · ${context.selectedText.length} selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    agentModes.forEach { item ->
                        FilterChip(
                            selected = mode == item.id,
                            onClick = { mode = item.id },
                            label = { Text(item.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("What should change?") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        Text("Asking the active model…", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (result.isNotBlank()) {
                    Text("Result", style = MaterialTheme.typography.labelLarge)
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            if (result.isNotBlank() && mode != "explain") {
                val replace = context.selectedText.isNotBlank() && mode == "rewrite"
                IconButton(onClick = { onApply(result, replace) }) {
                    Icon(
                        if (replace) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = if (replace) "Replace selection" else "Insert at cursor",
                    )
                }
            }
            IconButton(
                enabled = !loading,
                onClick = {
                    loading = true
                    error = ""
                    result = ""
                    onRequest(mode, instruction) { outcome ->
                        loading = false
                        outcome.onSuccess { result = it.trim() }
                            .onFailure { error = it.message ?: "The model request failed." }
                    }
                },
            ) { Icon(Icons.Default.PlayArrow, contentDescription = "Run agent edit") }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
        },
    )
}
