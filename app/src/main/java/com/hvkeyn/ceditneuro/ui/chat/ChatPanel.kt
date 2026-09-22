package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.ChatEntry
import com.hvkeyn.ceditneuro.ui.ChatRole
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState

@Composable
fun ChatPanel(
    state: WorkspaceUiState,
    settings: AgentSettings,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastEntry = state.chat.lastOrNull()

    LaunchedEffect(state.chat.size, lastEntry?.text?.length) {
        if (state.chat.isNotEmpty()) {
            listState.animateScrollToItem(state.chat.lastIndex)
        }
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Agent", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Built-in shell for commands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Hide chat")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ModelPicker(settings = settings, onSelectModel = onSelectModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                if (state.chat.isEmpty()) {
                    item { ChatHint() }
                }
                items(state.chat, key = { it.id }) { entry -> ChatBubble(entry) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent to change something…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                if (state.agentRunning) {
                    Button(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = {
                            val prompt = input.trim()
                            if (prompt.isNotEmpty()) {
                                input = ""
                                onSend(prompt)
                            }
                        },
                        enabled = input.isNotBlank() && state.projectRoot != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(
    settings: AgentSettings,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(settings.modelLabel())
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                settings.providers.forEach { provider ->
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    provider.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName.ifBlank { model.name }) },
                            onClick = {
                                open = false
                                onSelectModel(provider.id, model.name)
                            },
                        )
                    }
                }
            }
        }
        if (settings.apiKey.isBlank()) {
            Text(
                text = "No API key for ${settings.provider.name}. Add one in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatHint() {
    Text(
        text = "Describe what to change. The agent reads the project through its tools, " +
            "edits files, and reports back. Open files refresh automatically after each edit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val label = when (entry.role) {
        ChatRole.User -> "You"
        ChatRole.Assistant -> "Agent"
        ChatRole.Reasoning -> "Thinking"
        ChatRole.Tool -> entry.toolName ?: "Tool"
        ChatRole.Error -> "Error"
    }
    val labelColor = when (entry.role) {
        ChatRole.User -> MaterialTheme.colorScheme.primary
        ChatRole.Assistant -> MaterialTheme.colorScheme.secondary
        ChatRole.Reasoning -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatRole.Tool -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatRole.Error -> MaterialTheme.colorScheme.error
    }
    val monospaced = entry.role == ChatRole.Tool || entry.role == ChatRole.Reasoning

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Text(
            text = entry.text,
            style = if (monospaced) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(8.dp)
                .widthIn(max = 560.dp),
        )
    }
}
