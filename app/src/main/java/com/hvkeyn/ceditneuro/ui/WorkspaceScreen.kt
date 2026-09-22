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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
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
import com.hvkeyn.ceditneuro.ui.chat.ChatPanel
import com.hvkeyn.ceditneuro.ui.editor.EditorPane
import com.hvkeyn.ceditneuro.ui.settings.SettingsDialog
import com.hvkeyn.ceditneuro.workspace.FileEntry
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(viewModel: WorkspaceViewModel) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSettings by rememberSaveable { mutableStateOf(false) }
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                FileTreePane(
                    state = state,
                    onToggleDir = viewModel::toggleDirectory,
                    onOpenFile = { path ->
                        viewModel.openFile(path)
                        scope.launch { drawerState.close() }
                    },
                    onChooseFolder = { folderPicker.launch(null) },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Files")
                        }
                    },
                    title = {
                        Text(
                            text = state.projectName ?: "CEditNeuro",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        IconButton(onClick = viewModel::saveActiveFile) {
                            Icon(Icons.Default.Save, contentDescription = "Save file")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                val sideBySide = maxWidth >= 720.dp

                if (sideBySide && state.chatVisible) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        EditorSurface(viewModel, state, modifier = Modifier.fillMaxHeight().weight(1f))
                        ChatPanel(
                            state = state,
                            onSend = viewModel::sendPrompt,
                            onCancel = viewModel::cancelAgent,
                            onClose = viewModel::toggleChat,
                            modifier = Modifier.fillMaxHeight().width(420.dp),
                        )
                    }
                } else {
                    EditorSurface(viewModel, state, modifier = Modifier.fillMaxSize())
                    AnimatedVisibility(
                        visible = state.chatVisible,
                        enter = slideInHorizontally { width -> width },
                        exit = slideOutHorizontally { width -> width },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        ChatPanel(
                            state = state,
                            onSend = viewModel::sendPrompt,
                            onCancel = viewModel::cancelAgent,
                            onClose = viewModel::toggleChat,
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
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
private fun FileTreePane(
    state: WorkspaceUiState,
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onChooseFolder: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Project", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.projectRoot ?: "No folder chosen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onChooseFolder,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Choose folder")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
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
                text = "CEditNeuro edits project folders on shared storage, and Termux needs to " +
                    "see the same files. Grant all-files access to continue.",
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
 * resolvable this way; cloud providers are rejected so that Termux sees the same files.
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
