package com.hvkeyn.ceditneuro.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.update.AppUpdater
import com.hvkeyn.ceditneuro.ui.chat.ChatPanel
import com.hvkeyn.ceditneuro.ui.editor.EditorPane
import com.hvkeyn.ceditneuro.ui.settings.SettingsDialog
import com.hvkeyn.ceditneuro.ui.shell.ShellPanel
import com.hvkeyn.ceditneuro.workspace.FileEntry
import com.hvkeyn.ceditneuro.workspace.StoragePaths
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(viewModel: WorkspaceViewModel) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    val versionName = remember { AppUpdater.localVersion(context) }
    var projectPanelOpen by rememberSaveable { mutableStateOf(true) }
    var projectsMenu by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableIntStateOf(0) }
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(savedFlash) {
        if (savedFlash == 0) return@LaunchedEffect
        showSaved = true
        kotlinx.coroutines.delay(900)
        showSaved = false
    }
    var hasStorageAccess by remember { mutableStateOf(hasStorageAccess(context)) }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasStorageAccess = granted }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val folder = treeUriToFolder(uri)
            if (folder == null) {
                scope.launch {
                    snackbarHostState.showSnackbar("Only local storage folders are supported.")
                }
            } else {
                viewModel.openProject(folder)
            }
        }
    }

    LaunchedEffect(hasStorageAccess) {
        viewModel.restoreLastProject(hasStorageAccess)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.checkForUpdate()
    }

    state.appUpdate?.let { update ->
        val progress = if (update.total > 0) update.received.toFloat() / update.total else 0f
        val percent = if (update.total > 0) ((100 * update.received) / update.total).toInt() else null
        val waiting = !update.downloading && !update.installing && update.error == null
        AlertDialog(
            onDismissRequest = {
                if (!update.downloading && !update.installing) viewModel.dismissUpdate()
            },
            title = { Text("Update ${update.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            update.error != null -> update.error
                            update.installing -> "Installing. Confirm the system prompt if Android asks. The app will reopen when it finishes."
                            update.downloading && percent != null -> "Downloading… $percent%"
                            update.downloading -> "Downloading…"
                            else -> "Version ${update.versionName} is available. Download and install it?"
                        },
                    )
                    if (update.notes.isNotBlank() && update.error == null && !update.downloading) {
                        Text(update.notes)
                    }
                    if (update.downloading || update.installing) {
                        if (update.downloading && update.total > 0) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    update.replaceInstalled -> TextButton(onClick = viewModel::uninstallForUpdate) {
                        Text("Uninstall old app")
                    }
                    update.needsInstallPermission -> TextButton(onClick = viewModel::allowInstalls) {
                        Text("Allow installs")
                    }
                    update.error != null -> TextButton(onClick = viewModel::retryUpdate) { Text("Retry") }
                    waiting -> TextButton(onClick = viewModel::confirmUpdate) { Text("Update") }
                }
            },
            dismissButton = {
                if (!update.downloading && !update.installing) {
                    TextButton(onClick = viewModel::dismissUpdate) {
                        Text(if (update.error != null) "Close" else "Later")
                    }
                }
            },
        )
    }

    state.execPrompt?.let { reason ->
        AlertDialog(
            onDismissRequest = { viewModel.answerExecPrompt(false) },
            title = { Text("Run installed programs") },
            text = {
                Text(
                    "$reason Allow compilers and other programs to run inside this app. " +
                        "This is not root. Files on shared storage are copied into the app before they start.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.answerExecPrompt(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerExecPrompt(false) }) { Text("Don't allow") }
            },
        )
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    if (!hasStorageAccess) {
        StorageAccessGate(
            onRequestAccess = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    runCatching { context.startActivity(intent) }
                } else {
                    legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            onRecheck = { hasStorageAccess = hasStorageAccess(context) },
        )
        return
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onSettingsChange = viewModel::updateSettings,
            onTestRemote = viewModel::testRemote,
            versionName = versionName,
            profileName = viewModel.activeProfileName(),
            profileNames = viewModel.profileNames(),
            onSaveProfile = viewModel::saveProfileAs,
            onUseProfile = viewModel::useProfile,
            onCheckUpdate = { viewModel.checkForUpdate(manual = true) },
            onDismiss = { showSettings = false },
        )
    }

    state.hostPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.answerHostPrompt(false) },
            title = { Text("Trust this server") },
            text = {
                Text(
                    "First secure connection to ${prompt.host}.\n\nFingerprint:\n${prompt.fingerprint}",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.answerHostPrompt(true) }) { Text("Trust") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerHostPrompt(false) }) { Text("Don't trust") }
            },
        )
    }

    val screen = LocalConfiguration.current
    val shortLandscape = screen.screenHeightDp < 520 && screen.screenWidthDp > screen.screenHeightDp

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            val barButton = if (shortLandscape) 32.dp else 36.dp
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides barButton) {
            TopAppBar(
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    Box {
                        IconButton(
                            onClick = { projectsMenu = true },
                            modifier = Modifier.size(barButton),
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Projects")
                        }
                        ProjectTitleMenu(
                            expanded = projectsMenu,
                            onDismiss = { projectsMenu = false },
                            state = state,
                            filesOpen = projectPanelOpen,
                            onOpenProject = { path ->
                                viewModel.openProject(File(path))
                                projectPanelOpen = true
                            },
                            onChooseFolder = { folderPicker.launch(null) },
                            onToggleFiles = { projectPanelOpen = !projectPanelOpen },
                        )
                    }
                },
                title = {
                    Text(
                        text = state.projectName ?: "CEditNeuro",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { projectsMenu = true },
                    )
                },
                actions = {
                    Row {
                        BarIcon(
                            icon = Icons.Default.Terminal,
                            description = "Toggle shell",
                            active = state.shellVisible,
                            onClick = viewModel::toggleShell,
                            size = barButton,
                        )
                        BarIcon(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            description = "Toggle agent chat",
                            active = state.chatVisible,
                            onClick = viewModel::toggleChat,
                            size = barButton,
                        )
                            if (state.agentRunning && state.agentBrowsing) {
                                BarIcon(
                                    icon = Icons.Default.Public,
                                    description = "Agent browser",
                                    active = state.webVisible,
                                    onClick = viewModel::toggleWeb,
                                    size = barButton,
                                )
                            }
                            run {
                                val unsaved = state.dirtyPaths.size
                                if (unsaved > 0 || showSaved) {
                                    BarIcon(
                                        icon = if (showSaved && unsaved == 0) Icons.Default.Check else Icons.Default.Save,
                                        description = if (unsaved > 1) "Save $unsaved files" else "Save file",
                                        active = unsaved > 0,
                                        badge = unsaved,
                                        onClick = {
                                            if (viewModel.saveDirtyFiles()) savedFlash++
                                        },
                                        size = barButton,
                                    )
                                }
                            }
                        BarIcon(
                            icon = Icons.Default.Settings,
                            description = "Settings",
                            onClick = { showSettings = true },
                            size = barButton,
                        )
                    }
                },
            )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val compactHeight = maxHeight < 520.dp && maxWidth > maxHeight
            val panes = adaptivePanes(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                chatVisible = state.chatVisible,
                shellVisible = state.shellVisible,
                treeOpen = projectPanelOpen,
            )
            val showTree = if (panes.splitTree) {
                projectPanelOpen
            } else {
                projectPanelOpen || state.activePath == null
            }
            val editorColumn = panes.splitTree || !showTree
            val dockChat = editorColumn && panes.chatWidth != null
            val dockShell = editorColumn && panes.shellHeight != null

            val webOpen = state.webVisible
            val webFraction = when {
                compactHeight && (state.chatVisible || state.shellVisible) -> 0.34f
                compactHeight -> 0.46f
                state.chatVisible || state.shellVisible -> 0.38f
                else -> 0.62f
            }
            val webPanelHeight = if (webOpen) (maxHeight * webFraction).coerceAtLeast(96.dp) else 0.dp
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = webPanelHeight),
            ) {
            if (state.otherRuns.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.otherRuns, key = { it.path }) { run ->
                        Text(
                            text = if (run.running) {
                                "${run.name} · ${run.phase}" + if (run.focus.isBlank()) "" else " · ${run.focus}"
                            } else {
                                "${run.name} · ${run.phase}"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { viewModel.openProject(File(run.path)) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (showTree) {
                    FileTreePane(
                        state = state,
                        projectsMaxHeight = panes.projectsMaxHeight,
                        compactHeight = compactHeight,
                        onToggleDir = viewModel::toggleDirectory,
                        onOpenFile = { path ->
                            viewModel.openFile(path)
                            if (!panes.splitTree) projectPanelOpen = false
                        },
                        onOpenProject = { path -> viewModel.openProject(File(path)) },
                        onForgetProject = viewModel::forgetProject,
                        onChooseFolder = { folderPicker.launch(null) },
                        onCopy = viewModel::stageCopy,
                        onCut = viewModel::stageCut,
                        onPaste = viewModel::pasteEntry,
                        onRename = viewModel::renameEntry,
                        onDelete = viewModel::deleteEntry,
                        onClearClipboard = viewModel::clearFileClipboard,
                        modifier = if (panes.splitTree) {
                            Modifier.width(panes.treeWidth).fillMaxHeight()
                        } else {
                            Modifier.weight(1f).fillMaxHeight()
                        },
                    )
                    if (panes.splitTree) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                }

                if (editorColumn) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        EditorSurface(
                            viewModel,
                            state,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        AnimatedVisibility(
                            visible = state.shellVisible && dockShell,
                            enter = slideInVertically { height -> height },
                            exit = slideOutVertically { height -> height },
                        ) {
                            ShellPanel(
                                state = state,
                                onRun = viewModel::runShellCommand,
                                onClose = viewModel::toggleShell,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(panes.shellHeight ?: 140.dp),
                            )
                        }
                    }
                    if (dockChat) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline),
                        )
                        AgentChat(
                            viewModel = viewModel,
                            state = state,
                            settings = settings,
                            modifier = Modifier
                                .width(panes.chatWidth)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
            }
            AnimatedVisibility(
                visible = state.chatVisible && !dockChat,
                enter = slideInHorizontally { width -> width },
                exit = slideOutHorizontally { width -> width },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                AgentChat(
                    viewModel = viewModel,
                    state = state,
                    settings = settings,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = webPanelHeight)
                        .then(
                            panes.overlayChatWidth?.let { Modifier.width(it) }
                                ?: Modifier.fillMaxWidth(),
                        ),
                )
            }
            AnimatedVisibility(
                visible = state.shellVisible && !dockShell,
                enter = slideInHorizontally { width -> width },
                exit = slideOutHorizontally { width -> width },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                ShellPanel(
                    state = state,
                    onRun = viewModel::runShellCommand,
                    onClose = viewModel::toggleShell,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = webPanelHeight)
                        .then(
                            panes.shellWidth?.let { Modifier.width(it) }
                                ?: Modifier.fillMaxWidth(),
                        ),
                )
            }
            if (webOpen) {
                WebPreview(
                    url = state.webUrl,
                    generation = state.webGeneration,
                    onClose = viewModel::toggleWeb,
                    onLoaded = viewModel::onBrowseLoaded,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(webPanelHeight),
                )
            }
            }
        }
    }
}

private data class AdaptivePanes(
    val splitTree: Boolean,
    val treeWidth: Dp,
    val chatWidth: Dp?,
    val overlayChatWidth: Dp?,
    val shellWidth: Dp?,
    val shellHeight: Dp?,
    val projectsMaxHeight: Dp,
)

/**
 * Gives the editor a real share of a short landscape screen. Chat docks beside it
 * instead of covering it, and the shell sits under the editor. A portrait phone
 * keeps full-screen chat and shell.
 */
private fun adaptivePanes(
    maxWidth: Dp,
    maxHeight: Dp,
    chatVisible: Boolean,
    shellVisible: Boolean,
    treeOpen: Boolean,
): AdaptivePanes {
    val short = maxHeight < 520.dp && maxWidth > maxHeight
    val minEditor = if (short) 220.dp else 300.dp
    val wantTree = if (short) {
        (maxWidth * 0.22f).coerceIn(148.dp, 196.dp)
    } else {
        (maxWidth * 0.28f).coerceIn(200.dp, 280.dp)
    }
    val splitTree = treeOpen && maxWidth >= 560.dp && maxWidth - wantTree >= minEditor
    val treeWidth = if (splitTree) wantTree else 0.dp
    val wantChat = if (short) {
        (maxWidth * 0.32f).coerceIn(210.dp, 280.dp)
    } else {
        (maxWidth * 0.34f).coerceIn(280.dp, 400.dp)
    }
    val chatRoom = maxWidth - treeWidth - minEditor
    val chatWidth = if (!short && chatVisible && maxWidth >= 560.dp && chatRoom >= 200.dp) {
        wantChat.coerceAtMost(chatRoom)
    } else {
        null
    }
    val overlayChatWidth = when {
        chatWidth != null || !chatVisible || shellVisible || short -> null
        maxWidth >= 600.dp -> (maxWidth * 0.42f).coerceIn(260.dp, 440.dp)
        else -> null
    }
    val shellHeight = when {
        !shellVisible || short -> null
        maxHeight >= 440.dp && maxWidth >= 600.dp -> (maxHeight * 0.32f).coerceIn(140.dp, 240.dp)
        else -> null
    }
    val shellWidth = when {
        !shellVisible || shellHeight != null || short -> null
        maxWidth >= 600.dp -> (maxWidth * 0.42f).coerceIn(260.dp, 420.dp)
        else -> null
    }
    return AdaptivePanes(
        splitTree = splitTree,
        treeWidth = if (splitTree) treeWidth else wantTree,
        chatWidth = chatWidth,
        overlayChatWidth = overlayChatWidth,
        shellWidth = shellWidth,
        shellHeight = shellHeight,
        projectsMaxHeight = if (short) 52.dp else (maxHeight * 0.28f).coerceIn(96.dp, 220.dp),
    )
}

@Composable
private fun AgentChat(
    viewModel: WorkspaceViewModel,
    state: WorkspaceUiState,
    settings: AgentSettings,
    modifier: Modifier = Modifier,
) {
    ChatPanel(
        state = state,
        settings = settings,
        onSend = viewModel::sendPrompt,
        onContinue = viewModel::continueAgent,
        onCancel = viewModel::cancelAgent,
        onWorkFocus = { focus -> viewModel.updateSettings { it.copy(workFocus = focus) } },
        onNetwork = { enabled -> viewModel.updateSettings { it.copy(networkEnabled = enabled) } },
        onPrograms = { enabled -> viewModel.updateSettings { it.copy(execAllowed = enabled) } },
        onClose = viewModel::toggleChat,
        onSelectModel = { providerId, modelName ->
            viewModel.updateSettings {
                it.copy(activeProviderId = providerId, activeModel = modelName)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun EditorSurface(
    viewModel: WorkspaceViewModel,
    state: WorkspaceUiState,
    modifier: Modifier = Modifier,
) {
    EditorPane(
        state = state,
        contentProvider = viewModel::contentFor,
        onSelectTab = viewModel::setActiveFile,
        onCloseTab = viewModel::closeFile,
        onContentChanged = viewModel::onEditorTextChanged,
        modifier = modifier,
    )
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    active: Boolean = false,
    badge: Int = 0,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(if (size < 36.dp) 18.dp else 20.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (badge > 1) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun ProjectTitleMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: WorkspaceUiState,
    filesOpen: Boolean,
    onOpenProject: (String) -> Unit,
    onChooseFolder: () -> Unit,
    onToggleFiles: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (state.recentProjects.isEmpty()) {
            DropdownMenuItem(
                text = { Text("No saved projects") },
                onClick = onDismiss,
                enabled = false,
            )
        }
        state.recentProjects.forEach { path ->
            val run = state.otherRuns.firstOrNull { it.path == path }
            val phase = when {
                path == state.projectRoot && state.agentRunning -> state.agentActivity?.phase
                run != null -> run.phase
                else -> null
            }
            val current = path == state.projectRoot
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = File(path).name.ifBlank { path },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (!phase.isNullOrBlank()) {
                            Text(
                                text = phase,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                onClick = {
                    onDismiss()
                    onOpenProject(path)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Choose folder") },
            onClick = {
                onDismiss()
                onChooseFolder()
            },
        )
        DropdownMenuItem(
            text = { Text(if (filesOpen) "Hide files" else "Show files") },
            onClick = {
                onDismiss()
                onToggleFiles()
            },
        )
    }
}

@Composable
private fun FileTreePane(
    state: WorkspaceUiState,
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onForgetProject: (String) -> Unit,
    onChooseFolder: () -> Unit,
    onCopy: (String) -> Unit,
    onCut: (String) -> Unit,
    onPaste: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClearClipboard: () -> Unit,
    projectsMaxHeight: Dp,
    compactHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 12.dp,
                end = 12.dp,
                top = if (compactHeight) 4.dp else 16.dp,
                bottom = if (compactHeight) 2.dp else 8.dp,
            ),
        ) {
            Text(
                text = "Projects",
                style = if (compactHeight) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
            )
            if (!compactHeight) {
                Text(
                    text = "Chat and shell stay with each folder.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = projectsMaxHeight),
        ) {
            if (state.recentProjects.isEmpty()) {
                item {
                    Text(
                        text = "No saved projects yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(state.recentProjects, key = { it }) { path ->
                ProjectRow(
                    path = path,
                    selected = path == state.projectRoot,
                    compact = compactHeight,
                    onOpen = { onOpenProject(path) },
                    onForget = { onForgetProject(path) },
                )
            }
        }
        Button(
            onClick = onChooseFolder,
            contentPadding = if (compactHeight) {
                PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            } else {
                ButtonDefaults.ContentPadding
            },
            modifier = Modifier
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (compactHeight) 2.dp else 8.dp,
                    bottom = if (compactHeight) 4.dp else 12.dp,
                )
                .height(if (compactHeight) 32.dp else 40.dp),
        ) {
            Text("Choose folder", style = MaterialTheme.typography.labelLarge)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        state.fileClipboard?.let { clip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (clip.cut) "Cut: ${clip.name}" else "Copied: ${clip.name}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onPaste("") }) { Text("Paste") }
                TextButton(onClick = onClearClipboard) { Text("Clear") }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.projectRoot != null && state.rootEntries.isEmpty()) {
                item {
                    Text(
                        text = "This folder is empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            fileTree(
                entries = state.rootEntries,
                depth = 0,
                state = state,
                compact = compactHeight,
                onToggleDir = onToggleDir,
                onOpenFile = onOpenFile,
                clipboard = state.fileClipboard,
                onCopy = onCopy,
                onCut = onCut,
                onPaste = onPaste,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun ProjectRow(
    path: String,
    selected: Boolean,
    compact: Boolean,
    onOpen: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .padding(start = 12.dp, end = 0.dp, top = if (compact) 2.dp else 4.dp, bottom = if (compact) 2.dp else 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = File(path).name.ifBlank { path },
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onForget) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove project",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun LazyListScope.fileTree(
    entries: List<FileEntry>,
    depth: Int,
    state: WorkspaceUiState,
    compact: Boolean,
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    clipboard: FileClipboard?,
    onCopy: (String) -> Unit,
    onCut: (String) -> Unit,
    onPaste: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    entries.forEach { entry ->
        item(key = entry.relativePath) {
            FileRow(
                entry = entry,
                depth = depth,
                expanded = entry.relativePath in state.expandedDirs,
                active = entry.relativePath == state.activePath,
                dirty = entry.relativePath in state.dirtyPaths,
                compact = compact,
                canPaste = clipboard != null,
                onClick = {
                    if (entry.isDirectory) onToggleDir(entry.relativePath) else onOpenFile(entry.relativePath)
                },
                onCopy = { onCopy(entry.relativePath) },
                onCut = { onCut(entry.relativePath) },
                onPaste = {
                    val dir = if (entry.isDirectory) {
                        entry.relativePath
                    } else {
                        entry.relativePath.substringBeforeLast('/', "")
                    }
                    onPaste(dir)
                },
                onRename = { name -> onRename(entry.relativePath, name) },
                onDelete = { onDelete(entry.relativePath) },
            )
        }
        if (entry.isDirectory && entry.relativePath in state.expandedDirs) {
            fileTree(
                entries = state.dirContents[entry.relativePath].orEmpty(),
                depth = depth + 1,
                state = state,
                compact = compact,
                onToggleDir = onToggleDir,
                onOpenFile = onOpenFile,
                clipboard = clipboard,
                onCopy = onCopy,
                onCut = onCut,
                onPaste = onPaste,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    depth: Int,
    expanded: Boolean,
    active: Boolean,
    dirty: Boolean,
    compact: Boolean,
    canPaste: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var renameText by remember(entry.name) { mutableStateOf(entry.name) }
    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .background(
                if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .padding(
                start = (8 + depth * 14).dp,
                end = 8.dp,
                top = if (compact) 3.dp else 8.dp,
                bottom = if (compact) 3.dp else 8.dp,
            ),
    ) {
        if (entry.isDirectory) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = entry.name + if (dirty) " •" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menu = false
                    renameText = entry.name
                    renameOpen = true
                },
            )
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
            DropdownMenuItem(text = { Text("Cut") }, onClick = { menu = false; onCut() })
            if (canPaste) {
                DropdownMenuItem(
                    text = { Text(if (entry.isDirectory) "Paste inside" else "Paste here") },
                    onClick = { menu = false; onPaste() },
                )
            }
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleteOpen = true })
        }
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    onRename(renameText)
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete") },
            text = {
                Text(
                    if (entry.isDirectory) {
                        "Delete folder ${entry.name} and everything inside it?"
                    } else {
                        "Delete ${entry.name}?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StorageAccessGate(
    onRequestAccess: () -> Unit,
    onRecheck: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Storage access required", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "CEditNeuro edits project folders on shared storage, so it needs " +
                    "all-files access. The built-in shell uses the same folders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestAccess) { Text("Grant access") }
            Button(onClick = onRecheck) { Text("I already granted it") }
        }
    }
}

private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * Converts a SAF tree URI into a real path. Only the primary volume and removable volumes are
 * resolvable this way; cloud providers are rejected so the editor and the shell see the same files.
 */
private fun treeUriToFolder(uri: Uri): File? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = documentId.split(':', limit = 2)
    val volume = parts[0]
    val relative = parts.getOrNull(1).orEmpty()

    val base = when {
        volume.equals("primary", ignoreCase = true) -> StoragePaths.primaryRoot()
        volume.isBlank() -> return null
        else -> StoragePaths.volumeNamed(volume) ?: File("/storage/$volume")
    }
    if (!base.exists()) return null
    return if (relative.isBlank()) base else File(base, relative)
}
