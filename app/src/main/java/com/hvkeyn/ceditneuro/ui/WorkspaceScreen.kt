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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.chat.ChatPanel
import com.hvkeyn.ceditneuro.ui.editor.EditorPane
import com.hvkeyn.ceditneuro.ui.settings.SettingsDialog
import com.hvkeyn.ceditneuro.ui.shell.ShellPanel
import com.hvkeyn.ceditneuro.workspace.FileEntry
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
    var projectPanelOpen by rememberSaveable { mutableStateOf(true) }
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
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = { projectPanelOpen = !projectPanelOpen }) {
                        Icon(Icons.Default.Menu, contentDescription = "Project files")
                    }
                },
                title = {
                    ProjectTitleMenu(
                        state = state,
                        onOpenProject = { path -> viewModel.openProject(File(path)) },
                        onChooseFolder = { folderPicker.launch(null) },
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::saveActiveFile) {
                        Icon(Icons.Default.Save, contentDescription = "Save file")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = viewModel::toggleShell) {
                        Icon(Icons.Default.Terminal, contentDescription = "Toggle shell")
                    }
                    IconButton(onClick = viewModel::toggleChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Toggle agent chat")
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val wide = maxWidth >= 720.dp
            val showTree = if (wide) projectPanelOpen else projectPanelOpen || state.activePath == null

            Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showTree) {
                    FileTreePane(
                        state = state,
                        onToggleDir = viewModel::toggleDirectory,
                        onOpenFile = { path ->
                            viewModel.openFile(path)
                            if (!wide) projectPanelOpen = false
                        },
                        onOpenProject = { path -> viewModel.openProject(File(path)) },
                        onForgetProject = viewModel::forgetProject,
                        onChooseFolder = { folderPicker.launch(null) },
                        modifier = if (wide) {
                            Modifier.width(300.dp).fillMaxHeight()
                        } else {
                            Modifier.weight(1f).fillMaxHeight()
                        },
                    )
                    if (wide) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                }

                if (wide || !showTree) {
                    EditorStage(
                        viewModel = viewModel,
                        state = state,
                        settings = settings,
                        chatBesideEditor = wide && state.chatVisible,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
            AnimatedVisibility(
                visible = state.chatVisible && !wide,
                enter = slideInHorizontally { width -> width },
                exit = slideOutHorizontally { width -> width },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                AgentChat(
                    viewModel = viewModel,
                    state = state,
                    settings = settings,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                )
            }
            AnimatedVisibility(
                visible = state.shellVisible,
                enter = slideInHorizontally { width -> width },
                exit = slideOutHorizontally { width -> width },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                ShellPanel(
                    state = state,
                    onRun = viewModel::runShellCommand,
                    onClose = viewModel::toggleShell,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                )
            }
            }
        }
    }
}

@Composable
private fun EditorStage(
    viewModel: WorkspaceViewModel,
    state: WorkspaceUiState,
    settings: AgentSettings,
    chatBesideEditor: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (chatBesideEditor) {
            Row(modifier = Modifier.fillMaxSize()) {
                EditorSurface(
                    viewModel,
                    state,
                    modifier = Modifier.fillMaxHeight().weight(1f),
                )
                AgentChat(
                    viewModel = viewModel,
                    state = state,
                    settings = settings,
                    modifier = Modifier.fillMaxHeight().width(420.dp),
                )
            }
        } else {
            EditorSurface(viewModel, state, modifier = Modifier.fillMaxSize())
        }
    }
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
        onCancel = viewModel::cancelAgent,
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
private fun ProjectTitleMenu(
    state: WorkspaceUiState,
    onOpenProject: (String) -> Unit,
    onChooseFolder: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = state.projectName ?: "CEditNeuro",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.recentProjects.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No saved projects") },
                    onClick = { open = false },
                    enabled = false,
                )
            }
            state.recentProjects.forEach { path ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = File(path).name.ifBlank { path },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        open = false
                        onOpenProject(path)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Choose folder") },
                onClick = {
                    open = false
                    onChooseFolder()
                },
            )
        }
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text("Projects", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Chat and shell stay with each folder.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
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
                    onOpen = { onOpenProject(path) },
                    onForget = { onForgetProject(path) },
                )
            }
        }
        Button(
            onClick = onChooseFolder,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        ) {
            Text("Choose folder")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

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
                onToggleDir = onToggleDir,
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun ProjectRow(
    path: String,
    selected: Boolean,
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
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = File(path).name.ifBlank { path },
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    entries.forEach { entry ->
        item(key = entry.relativePath) {
            FileRow(
                entry = entry,
                depth = depth,
                expanded = entry.relativePath in state.expandedDirs,
                active = entry.relativePath == state.activePath,
                dirty = entry.relativePath in state.dirtyPaths,
                onClick = {
                    if (entry.isDirectory) onToggleDir(entry.relativePath) else onOpenFile(entry.relativePath)
                },
            )
        }
        if (entry.isDirectory && entry.relativePath in state.expandedDirs) {
            fileTree(
                entries = state.dirContents[entry.relativePath].orEmpty(),
                depth = depth + 1,
                state = state,
                onToggleDir = onToggleDir,
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    depth: Int,
    expanded: Boolean,
    active: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .padding(start = (12 + depth * 16).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
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
        volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
        volume.isBlank() -> return null
        else -> File("/storage/$volume")
    }
    if (!base.exists()) return null
    return if (relative.isBlank()) base else File(base, relative)
}
