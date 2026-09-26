package com.hvkeyn.ceditneuro.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.ui.ShellLine
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import kotlinx.coroutines.launch

@Composable
fun ShellPanel(
    state: WorkspaceUiState,
    onRun: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onClear: () -> Unit = {},
    onToggleElevated: () -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val history = remember(state.shellLines) { shellHistory(state.shellLines) }
    val listState = remember(state.projectRoot) {
        LazyListState(state.shellLines.lastIndex.coerceAtLeast(0), 0)
    }
    val followEnd = remember(state.projectRoot) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
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
    val submit = {
        val command = input.trim()
        if (command.isNotEmpty() && !state.shellRunning && state.projectRoot != null) {
            input = ""
            historyIndex = -1
            followEnd.value = true
            onRun(command)
        }
    }

    LaunchedEffect(listState, state.projectRoot) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }
            .collect { (canForward, moving) ->
                if (!canForward && !moving) followEnd.value = true
            }
    }

    LaunchedEffect(state.shellLines.size, state.shellLines.lastOrNull()?.output?.length, followEnd.value) {
        if (!followEnd.value) return@LaunchedEffect
        val last = state.shellLines.lastIndex
        if (last >= 0) listState.scrollToItem(last)
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Shell", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (state.shellElevated) "Root or Shizuku shell user · dumpsys, logcat, input"
                        else "Built into CEditNeuro · toybox",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ShellChip(
                    text = "Root",
                    selected = state.shellElevated,
                    enabled = !state.shellRunning,
                    onClick = onToggleElevated,
                )
                IconButton(onClick = onClear, enabled = !state.shellRunning && state.shellLines.isNotEmpty()) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear shell", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Hide shell")
                }
            }
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    ) {
                        if (state.shellLines.isEmpty()) {
                            item {
                                Text(
                                    text = "Commands run in the open project. This shell is part of the app. " +
                                        "It has toybox (ls, mkdir, grep, find) and fetch for downloads. java and kotlinc run from the app toolchain after you allow them. " +
                                        "Root runs the command as root, or as the Shizuku shell user when Shizuku is on.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(state.shellLines, key = { it.id }) { line ->
                            ShellEntry(line, onReuse = { input = it; historyIndex = -1 })
                        }
                    }
                    if (state.shellLines.size > 1) {
                        ShellScrubber(
                            commands = state.shellLines.map { it.command },
                            firstVisible = listState.firstVisibleItemIndex,
                            onJump = { index ->
                                followEnd.value = index >= state.shellLines.lastIndex
                                scope.launch { listState.scrollToItem(index) }
                            },
                        )
                    }
                }
                if (!followEnd.value && state.shellLines.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .clickable {
                                followEnd.value = true
                                scope.launch { listState.scrollToItem(state.shellLines.lastIndex) }
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val compact = LocalConfiguration.current.let {
                it.screenHeightDp < 520 && it.screenWidthDp > it.screenHeightDp
            }
            if (!compact && input.isBlank() && history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    history.take(RECENT_CHIPS).forEach { command ->
                        ShellChip(
                            text = command.lineSequence().first(),
                            selected = false,
                            enabled = true,
                            onClick = { input = command },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = {
                        if (history.isNotEmpty()) {
                            historyIndex = (historyIndex + 1) % history.size
                            input = history[historyIndex]
                        }
                    },
                    enabled = history.isNotEmpty(),
                ) {
                    Icon(Icons.Default.History, contentDescription = "Previous command")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && !event.isShiftPressed) {
                                if (event.type == KeyEventType.KeyDown) submit()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text(if (state.shellElevated) "dumpsys battery" else "echo hello") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = compact,
                    maxLines = if (compact) 1 else 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (state.shellRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = submit,
                        enabled = input.isNotBlank() && state.projectRoot != null,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Run",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Newest first, each command once. */
internal fun shellHistory(lines: List<ShellLine>): List<String> =
    lines.asReversed().map { it.command }.distinct()

@Composable
private fun ShellChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .widthIn(max = 180.dp)
            .background(if (selected) MaterialTheme.colorScheme.errorContainer else Color.Transparent, shape)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ShellScrubber(
    commands: List<String>,
    firstVisible: Int,
    onJump: (Int) -> Unit,
) {
    val count = commands.size
    val active = firstVisible.coerceIn(0, count - 1)
    BoxWithConstraints(
        modifier = Modifier
            .width(18.dp)
            .fillMaxHeight()
            .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
            .pointerInput(count) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                        onJump(((count - 1) * fraction).toInt())
                    },
                    onDrag = { change, _ ->
                        val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                        onJump(((count - 1) * fraction).toInt())
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
        commands.forEachIndexed { index, _ ->
            val fraction = if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
            val dot = if (index == active) 10.dp else 7.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (maxHeight - dot) * fraction)
                    .size(dot)
                    .background(
                        if (index == active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        CircleShape,
                    )
                    .clickable { onJump(index) },
            )
        }
    }
}

@Composable
private fun ShellEntry(line: ShellLine, onReuse: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(line.id) { mutableStateOf(false) }
    val commandLines = line.command.lines()
    val long = commandLines.size > COMMAND_PREVIEW_LINES
    val shownCommand = if (long && !expanded) {
        commandLines.take(COMMAND_PREVIEW_LINES).joinToString("\n") + "\n… ${commandLines.size - COMMAND_PREVIEW_LINES} more lines"
    } else {
        line.command
    }
    val failed = !line.running && line.output.lineSequence().firstOrNull()?.let {
        it.startsWith("timed out") || (it.startsWith("exit=") && it != "exit=0")
    } == true
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = (if (line.elevated) "# " else "$ ") + shownCommand,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (line.elevated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickable { if (long && !expanded) expanded = true else onReuse(line.command) },
            )
            if (!line.running) {
                Text(
                    text = "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(line.output)) }
                        .padding(start = 8.dp, top = 2.dp),
                )
            }
        }
        SelectionContainer {
            Text(
                text = line.output.ifBlank { if (line.running) "…" else "(no output)" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}

private const val COMMAND_PREVIEW_LINES = 3
private const val RECENT_CHIPS = 6
