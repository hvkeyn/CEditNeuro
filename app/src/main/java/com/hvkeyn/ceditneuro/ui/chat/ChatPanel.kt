package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.hvkeyn.ceditneuro.agent.agentBars
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
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onWorkFocus: (String) -> Unit,
    onNetwork: (Boolean) -> Unit,
    onPrograms: (Boolean) -> Unit,
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
            AgentToolbar(
                settings = settings,
                onWorkFocus = onWorkFocus,
                onNetwork = onNetwork,
                onPrograms = onPrograms,
                onSelectModel = onSelectModel,
                onClose = onClose,
            )
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
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent…") },
                    maxLines = if (LocalConfiguration.current.screenHeightDp < 500) 2 else 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                RoundAction(
                    icon = Icons.Filled.PlayArrow,
                    description = "Continue",
                    filled = false,
                    enabled = !state.agentRunning && state.projectRoot != null && state.chat.isNotEmpty(),
                    onClick = onContinue,
                )
                RoundAction(
                    icon = Icons.Filled.Stop,
                    description = "Stop",
                    filled = state.agentRunning,
                    danger = true,
                    enabled = state.agentRunning,
                    onClick = onCancel,
                )
                RoundAction(
                    icon = Icons.AutoMirrored.Filled.Send,
                    description = "Send",
                    filled = true,
                    enabled = !state.agentRunning && input.isNotBlank() && state.projectRoot != null,
                    onClick = {
                        val prompt = input.trim()
                        if (prompt.isNotEmpty()) {
                            input = ""
                            onSend(prompt)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentToolbar(
    settings: AgentSettings,
    onWorkFocus: (String) -> Unit,
    onNetwork: (Boolean) -> Unit,
    onPrograms: (Boolean) -> Unit,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    onClose: () -> Unit,
) {
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var workOpen by remember { mutableStateOf(false) }
    val workLabel = when (settings.workFocus) {
        AgentSettings.WORK_BUILD -> "Build"
        AgentSettings.WORK_REMOTE -> "Remote"
        else -> "Edit"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                Pill(text = settings.modelLabel(), onClick = { modelOpen = true }, maxWidth = 132.dp)
                DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                    settings.providers.forEach { provider ->
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        provider.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName.ifBlank { model.name }) },
                                onClick = {
                                    modelOpen = false
                                    onSelectModel(provider.id, model.name)
                                },
                            )
                        }
                    }
                }
            }
            Box {
                Pill(text = workLabel, onClick = { workOpen = true })
                DropdownMenu(expanded = workOpen, onDismissRequest = { workOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            workOpen = false
                            onWorkFocus(AgentSettings.WORK_EDIT)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Build") },
                        onClick = {
                            workOpen = false
                            onWorkFocus(AgentSettings.WORK_BUILD)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remote") },
                        onClick = {
                            workOpen = false
                            onWorkFocus(AgentSettings.WORK_REMOTE)
                        },
                    )
                }
            }
            Pill(text = "Net", selected = settings.networkEnabled, onClick = { onNetwork(!settings.networkEnabled) })
            Pill(
                text = "Run",
                selected = settings.execAllowed == true,
                onClick = { onPrograms(settings.execAllowed != true) },
            )
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Hide chat", modifier = Modifier.size(18.dp))
            }
        }
        if (settings.apiKey.isBlank()) {
            Text(
                text = "No API key. Add one in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    maxWidth: androidx.compose.ui.unit.Dp = 88.dp,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val border = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Boolean,
    danger: Boolean = false,
) {
    val background = when {
        !enabled -> Color.Transparent
        danger -> MaterialTheme.colorScheme.errorContainer
        filled -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.onErrorContainer
        filled -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .size(40.dp)
            .background(background, CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = buildString {
                    append(activity.phase)
                    append(" · ")
                    append(clock)
                    if (activity.focus.isNotBlank()) {
                        append(" · ")
                        append(activity.focus)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        val bars = agentBars(activity.phase)
        LinearProgressIndicator(
            progress = { bars.overall / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
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
