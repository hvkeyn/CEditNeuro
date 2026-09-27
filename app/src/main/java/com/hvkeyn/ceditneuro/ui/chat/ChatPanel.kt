package com.hvkeyn.ceditneuro.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.hvkeyn.ceditneuro.agent.SkillEntry
import com.hvkeyn.ceditneuro.agent.agentBars
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.AgentActivity
import com.hvkeyn.ceditneuro.ui.ChatEntry
import com.hvkeyn.ceditneuro.ui.ChatRole
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    state: WorkspaceUiState,
    settings: AgentSettings,
    onSend: (String, List<Uri>) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onDoctor: () -> String,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    onSelectFocus: (String) -> Unit,
    onListSkills: () -> List<SkillEntry>,
    modifier: Modifier = Modifier,
    onToggleSkill: (SkillEntry) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val context = LocalContext.current
    fun addPicked(picked: List<Uri>) {
        picked.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (picked.isNotEmpty()) attachments = (attachments + picked).distinct().take(8)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        addPicked(picked)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { picked ->
        addPicked(picked)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val shot = cameraFile
        if (saved && shot != null && shot.isFile && shot.length() > 0) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", shot)
            addPicked(listOf(uri))
        } else {
            shot?.delete()
        }
        cameraFile = null
    }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
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
                if (!state.online) {
                    input = heard
                    voiceNote = "No connection. The agent cannot work."
                } else if (!state.agentRunning && state.projectRoot != null) {
                    onSend(heard, emptyList())
                } else {
                    input = heard
                }
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
                onSelectModel = onSelectModel,
                onClose = onClose,
                onDoctor = onDoctor,
                onSendReport = { text -> onSend(text, emptyList()) },
                onSelectFocus = onSelectFocus,
                onListSkills = onListSkills,
                onUseSkill = onToggleSkill,
                activeSkills = state.activeSkills,
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
                        items(state.chat, key = { it.id }) { entry ->
                            ChatBubble(entry, state.projectRoot, onOpenLink)
                        }
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

            if (state.activeSkills.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.activeSkills.forEach { key ->
                        val name = key.substringAfter(':')
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                                .clickable { onToggleSkill(SkillEntry(name, key.substringBefore(':'), "")) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(
                                        if (state.agentRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                        CircleShape,
                                    ),
                            )
                            Text(
                                text = " $name" + if (state.agentRunning) " · working" else " · on",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Switch off $name",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(start = 8.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                Text(
                    text = attachments.joinToString { attachmentName(context, it) },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            if (!state.online) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = "No signal",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = " No connection. The agent cannot work.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val buttonSize = if (compact) 40.dp else 48.dp
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides buttonSize) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 0.dp)
                        .heightIn(max = if (compact) 48.dp else 96.dp),
                    placeholder = { Text(if (listening) "Listening…" else if (compact) "Message" else "Ask the agent…") },
                    singleLine = compact,
                    maxLines = if (compact) 1 else 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                ComposerActions(
                    state = state,
                    listening = listening,
                    input = input,
                    attachments = attachments,
                    buttonSize = buttonSize,
                    onCamera = {
                        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        if (attachments.size >= 8) {
                            voiceNote = "Eight files is the limit."
                        } else if (capture.resolveActivity(context.packageManager) == null) {
                            voiceNote = "This phone has no camera app."
                        } else {
                            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                            val shot = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", shot)
                            cameraFile = shot
                            camera.launch(uri)
                        }
                    },
                    onPhotos = {
                        if (attachments.size >= 8) voiceNote = "Eight files is the limit."
                        else photos.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    },
                    onFiles = {
                        if (attachments.size >= 8) voiceNote = "Eight files is the limit."
                        else files.launch(arrayOf("*/*"))
                    },
                    onMic = {
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
                    onContinue = onContinue,
                    onCancel = onCancel,
                    onSend = {
                        if (listening) {
                            dictation.stop()
                            return@ComposerActions
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

private fun attachmentName(context: android.content.Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Photo"
}

@Composable
private fun ComposerActions(
    state: WorkspaceUiState,
    listening: Boolean,
    input: String,
    attachments: List<Uri>,
    buttonSize: androidx.compose.ui.unit.Dp,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onMic: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    if (state.agentRunning && !listening) {
        RoundAction(
            icon = Icons.Filled.Stop,
            description = "Stop",
            filled = true,
            danger = true,
            size = buttonSize,
            enabled = true,
            onClick = onCancel,
        )
        return
    }
    var sources by remember { mutableStateOf(false) }
    Box {
        RoundAction(
            icon = Icons.Filled.AttachFile,
            description = "Attach",
            filled = attachments.isNotEmpty(),
            size = buttonSize,
            enabled = state.projectRoot != null,
            onClick = { sources = true },
        )
        DropdownMenu(expanded = sources, onDismissRequest = { sources = false }) {
            DropdownMenuItem(
                text = { Text("Camera") },
                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                onClick = { sources = false; onCamera() },
            )
            DropdownMenuItem(
                text = { Text("Photos") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                onClick = { sources = false; onPhotos() },
            )
            DropdownMenuItem(
                text = { Text("Files") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                onClick = { sources = false; onFiles() },
            )
        }
    }
    RoundAction(
        icon = Icons.Filled.Mic,
        description = if (listening) "Stop dictation" else "Dictate",
        filled = listening,
        danger = listening,
        size = buttonSize,
        enabled = state.projectRoot != null,
        onClick = onMic,
    )
    if (!state.online) {
        RoundAction(
            icon = Icons.Filled.WifiOff,
            description = "No connection",
            filled = false,
            size = buttonSize,
            enabled = false,
            onClick = {},
        )
        return
    }
    val hasDraft = input.isNotBlank() || attachments.isNotEmpty()
    if (!hasDraft && state.chat.isNotEmpty()) {
        RoundAction(
            icon = Icons.Filled.PlayArrow,
            description = "Continue",
            filled = false,
            size = buttonSize,
            enabled = state.projectRoot != null,
            onClick = onContinue,
        )
    } else {
        RoundAction(
            icon = Icons.AutoMirrored.Filled.Send,
            description = "Send",
            filled = true,
            size = buttonSize,
            enabled = state.projectRoot != null && hasDraft,
            onClick = onSend,
        )
    }
}

@Composable
private fun AgentToolbar(
    settings: AgentSettings,
    compact: Boolean,
    onSelectModel: (providerId: String, modelName: String) -> Unit,
    onClose: () -> Unit,
    onDoctor: () -> String,
    onSendReport: (String) -> Unit,
    onSelectFocus: (String) -> Unit,
    onListSkills: () -> List<SkillEntry>,
    onUseSkill: (SkillEntry) -> Unit,
    activeSkills: List<String>,
) {
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var focusOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var skillList by remember { mutableStateOf<List<SkillEntry>>(emptyList()) }
    var doctorOpen by remember { mutableStateOf(false) }
    var doctorText by remember { mutableStateOf("") }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                Pill(text = settings.modelLabel(), onClick = { modelOpen = true }, maxWidth = if (compact) 200.dp else 280.dp, compact = compact)
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
                Pill(text = focusLabel(settings.workFocus), compact = compact, onClick = { focusOpen = true })
                DropdownMenu(expanded = focusOpen, onDismissRequest = { focusOpen = false }) {
                    AgentSettings.WORK_FOCUSES.forEach { focus ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(focusLabel(focus))
                                    Text(
                                        focusHint(focus),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                focusOpen = false
                                onSelectFocus(focus)
                            },
                        )
                    }
                }
            }
            Box {
                Pill(
                    text = if (activeSkills.isEmpty()) "Skills" else "Skills ${activeSkills.size}",
                    selected = activeSkills.isNotEmpty(),
                    compact = compact,
                    onClick = { skillList = onListSkills(); skillsOpen = true },
                )
                DropdownMenu(expanded = skillsOpen, onDismissRequest = { skillsOpen = false }) {
                    Text(
                        if (skillList.isEmpty()) "No skills yet. Add one in Settings, or ask the agent to save_skill."
                        else "Tick any skills to keep them on. The agent also turns on every skill that fits the task.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 280.dp),
                    )
                    skillList.forEach { entry ->
                        val on = "${entry.scope}:${entry.name}" in activeSkills
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = if (on) "On" else "Off",
                                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = {
                                Column(modifier = Modifier.widthIn(max = 280.dp)) {
                                    Text("${entry.name} · ${entry.scope}")
                                    Text(
                                        entry.summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = { onUseSkill(entry) },
                        )
                    }
                }
            }
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

private fun focusLabel(focus: String): String = when (focus) {
    AgentSettings.WORK_BUILD -> "Build"
    AgentSettings.WORK_REMOTE -> "Remote"
    AgentSettings.WORK_STUDY -> "Study"
    else -> "Edit"
}

private fun focusHint(focus: String): String = when (focus) {
    AgentSettings.WORK_BUILD -> "Compile, test, and package"
    AgentSettings.WORK_REMOTE -> "Work on the connected server"
    AgentSettings.WORK_STUDY -> "Investigate, check, write a report"
    else -> "Change project files"
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
            .defaultMinSize(minHeight = if (compact) 40.dp else 48.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
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
        if (activity.goal.isNotBlank()) {
            Text(
                text = activity.goal,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
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
private fun ChatBubble(entry: ChatEntry, projectRoot: String?, onOpen: (String) -> Unit) {
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
            projectRoot = projectRoot,
            onOpen = onOpen,
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
            Copyable(entry.text) {
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
            }
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
    projectRoot: String? = null,
    onOpen: (String) -> Unit = {},
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
        Copyable(text, selectable = !markdown) {
            if (markdown) {
                MarkdownText(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = bubble,
                    projectRoot = projectRoot,
                    onOpen = onOpen,
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
}

@Composable
private fun Copyable(text: String, selectable: Boolean = true, content: @Composable () -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CopyLabel(text)
        }
        if (selectable) SelectionContainer { content() } else content()
    }
}

@Composable
private fun CopyLabel(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Text(
        text = "Copy",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            },
    )
}

@Composable
private fun ToolBlock(label: String, text: String, error: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            CopyLabel(text)
        }
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
