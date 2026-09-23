package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.AgentActivity
import com.hvkeyn.ceditneuro.ui.ChatEntry
import com.hvkeyn.ceditneuro.ui.ChatRole
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import kotlinx.coroutines.delay

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
    val lastEntry = state.chat.lastOrNull()
    val listState = remember(state.projectRoot) {
        LazyListState(state.chat.lastIndex.coerceAtLeast(0), 0)
    }

    LaunchedEffect(state.chat.size, lastEntry?.id, lastEntry?.text?.length) {
        val last = state.chat.lastIndex
        if (last >= 0) listState.scrollToItem(last)
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

            state.agentActivity?.let { activity ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                AgentActivityBar(activity)
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
                    maxLines = if (LocalConfiguration.current.screenHeightDp < 500) 2 else 5,
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
private fun AgentActivityBar(activity: AgentActivity) {
    var now by remember(activity.startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity.startedAt) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = ((now - activity.startedAt) / 1000).coerceAtLeast(0)
    val clock = "%d:%02d".format(elapsed / 60, elapsed % 60)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "${activity.phase} · $clock",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = activity.context,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (activity.focus.isNotBlank()) {
            Text(
                text = activity.focus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    when (entry.role) {
        ChatRole.Reasoning -> ThinkingBlock(entry)
        ChatRole.User -> MessageBlock(
            label = "You",
            text = entry.text,
            labelColor = MaterialTheme.colorScheme.primary,
            background = MaterialTheme.colorScheme.primaryContainer,
            alignEnd = true,
        )
        ChatRole.Assistant -> MessageBlock(
            label = "Agent",
            text = entry.text,
            labelColor = MaterialTheme.colorScheme.secondary,
            background = MaterialTheme.colorScheme.surface,
            alignEnd = false,
        )
        ChatRole.Tool -> ToolBlock(entry.toolName ?: "Tool", entry.text, error = false)
        ChatRole.Error -> if (entry.toolName != null) {
            ToolBlock(entry.toolName, entry.text, error = true)
        } else {
            MessageBlock(
                label = "Error",
                text = entry.text,
                labelColor = MaterialTheme.colorScheme.error,
                background = MaterialTheme.colorScheme.errorContainer,
                alignEnd = false,
            )
        }
    }
}

@Composable
private fun ThinkingBlock(entry: ChatEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val open = entry.streaming || expanded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !entry.streaming) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (entry.streaming) "Thinking" else "Thought",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            if (!entry.streaming) {
                Icon(
                    imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (open) "Hide thought" else "Show thought",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = entry.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MessageBlock(
    label: String,
    text: String,
    labelColor: Color,
    background: Color,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = labelColor)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .widthIn(max = 560.dp)
                .background(background, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ToolBlock(label: String, text: String, error: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
