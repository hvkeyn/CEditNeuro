package com.hvkeyn.ceditneuro.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hvkeyn.ceditneuro.agent.agentBars
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.AgentActivity
import com.hvkeyn.ceditneuro.ui.ChatEntry
import com.hvkeyn.ceditneuro.ui.ChatRole
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatPanel(
    state: WorkspaceUiState,
    settings: AgentSettings,
    onSend: (String, List<Uri>) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onWorkFocus: (String) -> Unit,
    onNetwork: (Boolean) -> Unit,
    onPrograms: (Boolean) -> Unit,
    onClose: () -> Unit,
    onDoctor: () -> String,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) attachments = (attachments + picked).take(8)
    }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val dictation = remember(context) {
        VoiceDictation(context.applicationContext, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(dictation) {
        onDispose { dictation.release() }
    }
    val startDictation = {
        listening = true
        voiceNote = "Listening…"
        dictation.start(
            onPartial = { heard -> input = heard },
            onFinal = { heard ->
                listening = false
                voiceNote = null
                input = ""
                if (!state.agentRunning && state.projectRoot != null) onSend(heard, emptyList())
                else input = heard
            },
            onNote = { note ->
                if (note == null || note != "Listening…") listening = false
                voiceNote = note
            },
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation()
        else voiceNote = "Microphone permission is needed to dictate."
    }
    val lastEntry = state.chat.lastOrNull()
    val listState = remember(state.projectRoot) {
        LazyListState(state.chat.lastIndex.coerceAtLeast(0), 0)
    }
    val followEnd = remember(state.projectRoot) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val steps = chatSteps(state.chat)
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 1f) {
                    followEnd.value = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState, state.projectRoot) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }
            .collect { (canForward, moving) ->
                if (!canForward && !moving) followEnd.value = true
            }
    }

    LaunchedEffect(state.chat.size, lastEntry?.id, lastEntry?.text?.length, followEnd.value) {
        if (!followEnd.value) return@LaunchedEffect
        val last = state.chat.lastIndex
        if (last >= 0) listState.scrollToItem(last)
    }

    val compact = LocalConfiguration.current.let { it.screenHeightDp < 520 && it.screenWidthDp > it.screenHeightDp }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            AgentToolbar(
                settings = settings,
                compact = compact,
                onWorkFocus = onWorkFocus,
                onNetwork = onNetwork,
                onPrograms = onPrograms,
                onSelectModel = onSelectModel,
                onClose = onClose,
                onDoctor = onDoctor,
                onSendReport = { text -> onSend(text, emptyList()) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .nestedScroll(userScroll)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = if (compact) 6.dp else 12.dp,
                            bottom = (if (compact) 6.dp else 12.dp) +
                                if (!followEnd.value && state.chat.isNotEmpty()) 40.dp else 0.dp,
                        ),
                    ) {
                        if (state.chat.isEmpty()) {
                            item { ChatHint() }
                        }
                        items(state.chat, key = { it.id }) { entry -> ChatBubble(entry) }
                    }
                    if (state.chat.size > 1) {
                        ChatScrubber(
                            steps = steps,
                            itemCount = state.chat.size,
                            firstVisible = listState.firstVisibleItemIndex,
                            onJump = { index ->
                                followEnd.value = index >= state.chat.lastIndex
                                scope.launch { listState.scrollToItem(index) }
                            },
                        )
                    }
                }
                if (!followEnd.value && state.chat.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .clickable {
                                followEnd.value = true
                                scope.launch { listState.scrollToItem(state.chat.lastIndex) }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Jump to latest",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "End",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            state.agentActivity?.let { activity ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                AgentActivityBar(activity, compact = compact)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (attachments.isNotEmpty()) {
                Text(
                    text = attachments.joinToString { it.lastPathSegment?.substringAfterLast('/') ?: "file" },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (listening) "Listening…" else if (compact) "Message" else "Ask the agent…") },
                    singleLine = compact,
                    maxLines = if (compact) 1 else 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                RoundAction(
                    icon = Icons.Filled.AttachFile,
                    description = "Attach a file",
                    filled = attachments.isNotEmpty(),
                    size = if (compact) 32.dp else 40.dp,
                    enabled = state.projectRoot != null && !state.agentRunning,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
                RoundAction(
                    icon = Icons.Filled.Mic,
                    description = if (listening) "Stop dictation" else "Dictate",
                    filled = listening,
                    danger = listening,
                    size = if (compact) 32.dp else 40.dp,
                    enabled = state.projectRoot != null && (!state.agentRunning || listening),
                    onClick = {
                        if (listening) {
                            dictation.stop()
                        } else if (
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startDictation()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
                RoundAction(
                    icon = Icons.Filled.PlayArrow,
                    description = "Continue",
                    filled = false,
                    size = if (compact) 32.dp else 40.dp,
                    enabled = !state.agentRunning && state.projectRoot != null && state.chat.isNotEmpty(),
                    onClick = onContinue,
                )
                RoundAction(
                    icon = Icons.Filled.Stop,
                    description = "Stop",
                    filled = state.agentRunning,
                    danger = true,
                    size = if (compact) 32.dp else 40.dp,
                    enabled = state.agentRunning,
                    onClick = onCancel,
                )
                RoundAction(
                    icon = Icons.AutoMirrored.Filled.Send,
                    description = "Send",
                    filled = true,
                    size = if (compact) 32.dp else 40.dp,
                    enabled = !state.agentRunning && state.projectRoot != null &&
                        (input.isNotBlank() || attachments.isNotEmpty()),
                    onClick = {
                        if (listening) {
                            dictation.stop()
                            return@RoundAction
                        }
                        val prompt = input.trim()
                        val files = attachments
                        if (prompt.isNotEmpty() || files.isNotEmpty()) {
                            input = ""
                            attachments = emptyList()
                            onSend(prompt, files)
                        }
                    },
                )
            }
            voiceNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentToolbar(
    settings: AgentSettings,
    compact: Boolean,
    onWorkFocus: (String) -> Unit,
    onNetwork: (Boolean) -> Unit,
    onPrograms: (Boolean) -> Unit,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    onClose: () -> Unit,
    onDoctor: () -> String,
    onSendReport: (String) -> Unit,
) {
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var workOpen by remember { mutableStateOf(false) }
    var doctorOpen by remember { mutableStateOf(false) }
    var doctorText by remember { mutableStateOf("") }
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
                Pill(text = settings.modelLabel(), onClick = { modelOpen = true }, maxWidth = if (compact) 108.dp else 132.dp, compact = compact)
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
                Pill(text = workLabel, onClick = { workOpen = true }, compact = compact)
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
            Pill(text = "Net", selected = settings.networkEnabled, compact = compact, onClick = { onNetwork(!settings.networkEnabled) })
            Pill(
                text = "Run",
                selected = settings.execAllowed == true,
                compact = compact,
                onClick = { onPrograms(settings.execAllowed != true) },
            )
            Pill(text = "Doctor", compact = compact, onClick = { doctorText = onDoctor(); doctorOpen = true })
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
        if (doctorOpen) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { doctorOpen = false },
                title = { Text("Agent doctor") },
                text = {
                    androidx.compose.foundation.rememberScrollState().let { scroll ->
                        Text(
                            text = doctorText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(scroll),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        doctorOpen = false
                        onSendReport(
                            "Fix the repeated agent failures in this doctor report. Change the project only where the report points at a real mistake.\n\n$doctorText",
                        )
                    }) { Text("Send to agent") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(doctorText))
                        doctorOpen = false
                    }) { Text("Copy") }
                },
            )
        }
    }
}

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    compact: Boolean = false,
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
            .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 4.dp),
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
    size: androidx.compose.ui.unit.Dp = 40.dp,
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
            .size(size)
            .background(background, CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AgentActivityBar(activity: AgentActivity, compact: Boolean) {
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
        if (!compact) {
            val bars = agentBars(activity.phase)
            LinearProgressIndicator(
                progress = { bars.overall / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

private data class ChatStep(val index: Int, val role: ChatRole)

private fun chatSteps(chat: List<ChatEntry>): List<ChatStep> {
    val steps = mutableListOf<ChatStep>()
    chat.forEachIndexed { index, entry ->
        when (entry.role) {
            ChatRole.User, ChatRole.Error -> steps += ChatStep(index, entry.role)
            ChatRole.Tool -> if (steps.lastOrNull()?.role != ChatRole.Tool) steps += ChatStep(index, entry.role)
            ChatRole.Assistant -> if (!entry.streaming) steps += ChatStep(index, entry.role)
            ChatRole.Reasoning -> Unit
        }
    }
    return steps
}

@Composable
private fun ChatScrubber(
    steps: List<ChatStep>,
    itemCount: Int,
    firstVisible: Int,
    onJump: (Int) -> Unit,
) {
    val active = steps.indexOfLast { it.index <= firstVisible }
    BoxWithConstraints(
        modifier = Modifier
            .width(18.dp)
            .fillMaxHeight()
            .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
            .pointerInput(itemCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                        onJump(((itemCount - 1) * fraction).toInt())
                    },
                    onDrag = { change, _ ->
                        val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                        onJump(((itemCount - 1) * fraction).toInt())
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        )
        steps.forEachIndexed { stepIndex, step ->
            val fraction = if (itemCount <= 1) 0f else step.index.toFloat() / (itemCount - 1).toFloat()
            val color = when (step.role) {
                ChatRole.User -> MaterialTheme.colorScheme.primary
                ChatRole.Tool -> MaterialTheme.colorScheme.tertiary
                ChatRole.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val dot = if (stepIndex == active) 10.dp else 7.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (maxHeight - dot) * fraction)
                    .size(dot)
                    .background(color, CircleShape)
                    .clickable { onJump(step.index) },
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
            markdown = true,
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
            val compact = LocalConfiguration.current.let {
                it.screenHeightDp < 520 && it.screenWidthDp > it.screenHeightDp
            }
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact && entry.streaming) 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
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
    markdown: Boolean = false,
) {
    val bubble = Modifier
        .padding(top = 2.dp)
        .widthIn(max = 560.dp)
        .then(if (markdown) Modifier.fillMaxWidth() else Modifier)
        .background(background, RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = labelColor)
        if (markdown) {
            MarkdownText(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = bubble,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = bubble,
            )
        }
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
