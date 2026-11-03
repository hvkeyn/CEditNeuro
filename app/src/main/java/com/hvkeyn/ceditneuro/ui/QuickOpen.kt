package com.hvkeyn.ceditneuro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class QuickCommand(
    val label: String,
    val detail: String,
    val action: () -> Unit,
)

@Composable
fun QuickOpenDialog(
    files: List<String>,
    commands: List<QuickCommand>,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val filteredCommands = remember(needle, commands) {
        if (needle.isEmpty()) commands
        else commands.filter {
            it.label.contains(needle, ignoreCase = true) || it.detail.contains(needle, ignoreCase = true)
        }
    }
    val filteredFiles = remember(needle, files) {
        if (needle.isEmpty()) files.take(80)
        else files.asSequence()
            .filter { it.contains(needle, ignoreCase = true) }
            .take(120)
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick open") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("File or command") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filteredCommands.isNotEmpty()) {
                    Text(
                        "Commands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    filteredCommands.take(6).forEach { command ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    command.action()
                                }
                                .padding(vertical = 7.dp),
                        ) {
                            Text(
                                text = command.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = command.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (filteredFiles.isNotEmpty()) {
                    Text(
                        if (needle.isEmpty()) "Project files" else "Files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filteredFiles, key = { it }) { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onOpenFile(path)
                                    }
                                    .padding(vertical = 7.dp),
                            )
                        }
                    }
                }
                if (filteredCommands.isEmpty() && filteredFiles.isEmpty()) {
                    Text(
                        "Nothing matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
