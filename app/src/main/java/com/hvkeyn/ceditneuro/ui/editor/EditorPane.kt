package com.hvkeyn.ceditneuro.ui.editor

import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.ui.theme.NeuroSelected
import androidx.compose.ui.viewinterop.AndroidView
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import java.io.File

/**
 * The editing surface: an open-file tab strip over a single Sora editor instance.
 *
 * A single instance is reused across tabs, so switching files re-runs `setText` and resets
 * the undo history. Per-file editor instances with retained scroll and undo state are a
 * later milestone.
 */
@Composable
fun EditorPane(
    state: WorkspaceUiState,
    contentProvider: (String) -> String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onContentChanged: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onRun: () -> Unit = {},
    onStop: () -> Unit = {},
    onRender: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onSaveReel: (String, String) -> Unit = { _, _ -> },
    onRenderReel: (String) -> Unit = {},
    onExportReel: (String) -> Unit = {},
    onImportReel: (String, String) -> Unit = { _, _ -> },
    onQuickOpen: () -> Unit = {},
    onGit: () -> Unit = {},
) {
    val activePath = state.activePath
    if (activePath == null || state.openFiles.isEmpty()) {
        EmptyEditor(modifier)
        return
    }
    val kind = previewKind(activePath)
    val runnable = com.hvkeyn.ceditneuro.ui.isRunnable(activePath)
    var previewing by remember(activePath) { mutableStateOf(kind != null) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var searchOpen by rememberSaveable(activePath) { mutableStateOf(false) }
    var searchText by rememberSaveable(activePath) { mutableStateOf("") }
    var replaceText by rememberSaveable(activePath) { mutableStateOf("") }
    var wrapped by rememberSaveable { mutableStateOf(true) }
    var lineNumbers by rememberSaveable { mutableStateOf(true) }
    var outlineOpen by rememberSaveable(activePath) { mutableStateOf(false) }
    var split by rememberSaveable(activePath) { mutableStateOf(false) }
    var fontScale by rememberSaveable { mutableFloatStateOf(1f) }
    val secondaryPath = state.openFiles.firstOrNull {
        it.path != activePath && previewKind(it.path) !in setOf("image", "video")
    }?.path
    val file = state.projectRoot?.let { File(it, activePath) }

    Column(modifier) {
        if (state.openFiles.isNotEmpty()) {
            EditorTabs(state = state, onSelect = onSelectTab, onClose = onCloseTab)
        }
        if (runnable || kind == "svg" || kind == "html") {
            val composition = kind == "html" &&
                com.hvkeyn.ceditneuro.video.Composition.isComposition(contentProvider(activePath))
            val rendering by com.hvkeyn.ceditneuro.video.VideoRenderHub.job.collectAsState()
            FileActions(
                runnable = runnable,
                running = state.shellRunning,
                canPreview = kind == "svg" || kind == "html",
                previewing = previewing,
                onRun = onRun,
                onStop = onStop,
                onTogglePreview = { previewing = !previewing },
                canRender = composition,
                rendering = rendering != null,
                onRender = { onRender(activePath) },
            )
        }
        val codeVisible = kind == null || (!previewing && kind != "image" && kind != "video")
        if (codeVisible) {
            EditorTools(
                onFind = { searchOpen = !searchOpen },
                onUndo = { editor?.undo() },
                onRedo = { editor?.redo() },
                onFormat = { editor?.formatCodeAsync() },
                wrapped = wrapped,
                onWrap = {
                    wrapped = !wrapped
                    editor?.setWordwrap(wrapped)
                },
                lineNumbers = lineNumbers,
                onLineNumbers = {
                    lineNumbers = !lineNumbers
                    editor?.setLineNumberEnabled(lineNumbers)
                },
                onQuickOpen = onQuickOpen,
                onGit = onGit,
                onOutline = { outlineOpen = true },
                splitAvailable = secondaryPath != null,
                split = split,
                onSplit = { split = !split },
                onFontSmaller = {
                    fontScale = (fontScale - 0.1f).coerceAtLeast(0.75f)
                    editor?.let { fitEditorText(it, EditorHolder(), activePath, fontScale) }
                },
                onFontLarger = {
                    fontScale = (fontScale + 0.1f).coerceAtMost(1.6f)
                    editor?.let { fitEditorText(it, EditorHolder(), activePath, fontScale) }
                },
            )
        }
        if (searchOpen && codeVisible) {
            SearchBar(
                query = searchText,
                replacement = replaceText,
                onQuery = { value ->
                    searchText = value
                    val target = editor ?: return@SearchBar
                    if (value.isBlank()) target.searcher.stopSearch()
                    else target.searcher.search(value, EditorSearcher.SearchOptions(false, false))
                },
                onReplacement = { replaceText = it },
                onPrevious = { editor?.searcher?.gotoPrevious() },
                onNext = { editor?.searcher?.gotoNext() },
                onReplace = { editor?.searcher?.replaceThis(replaceText) },
                onReplaceAll = { editor?.searcher?.replaceAll(replaceText) },
                onClose = { searchOpen = false },
            )
        }
        if (outlineOpen) {
            OutlineDialog(
                text = contentProvider(activePath),
                onSelectLine = { line ->
                    outlineOpen = false
                    val target = editor ?: return@OutlineDialog
                    val text = target.text
                    if (line in 0 until text.lineCount) {
                        val offset = text.getCharIndex(line, 0)
                        target.setSelection(offset, offset)
                        target.ensureSelectionVisible()
                    }
                },
                onDismiss = { outlineOpen = false },
            )
        }
        val body = Modifier
            .fillMaxWidth()
            .weight(1f)
        when {
            activePath.endsWith(".reel") && file != null -> ReelEditor(
                reelFile = file,
                text = contentProvider(activePath),
                onSave = { onSaveReel(activePath, it) },
                onRender = { onRenderReel(activePath) },
                onExport = { onExportReel(activePath) },
                onImport = { onImportReel(activePath, it) },
                modifier = body,
            )
            kind == "image" && file != null -> ImagePreview(file, body)
            kind == "video" && file != null -> VideoPreview(file, body, onEdit = { onEdit(activePath) })
            kind != null && previewing -> {
                val markup = contentProvider(activePath)
                if (kind == "html" && com.hvkeyn.ceditneuro.video.Composition.isComposition(markup)) {
                    CompositionPreview(markup, file?.parentFile, body)
                } else {
                    ExpandableMarkup(kind, markup, file?.parentFile, body)
                }
            }
            else -> if (split && secondaryPath != null) {
                BoxWithConstraints(modifier = body) {
                    val sideBySide = maxWidth >= 600.dp
                    if (sideBySide) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            CodeEditorHost(
                                activePath = activePath,
                                projectRoot = state.projectRoot,
                                contentProvider = contentProvider,
                                reloadCounter = state.reloadCounter,
                                onContentChanged = onContentChanged,
                                onEditorReady = { editor = it },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            CodeEditorHost(
                                activePath = secondaryPath,
                                projectRoot = state.projectRoot,
                                contentProvider = contentProvider,
                                reloadCounter = state.reloadCounter,
                                onContentChanged = onContentChanged,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CodeEditorHost(
                                activePath = activePath,
                                projectRoot = state.projectRoot,
                                contentProvider = contentProvider,
                                reloadCounter = state.reloadCounter,
                                onContentChanged = onContentChanged,
                                onEditorReady = { editor = it },
                                fontScale = fontScale,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            CodeEditorHost(
                                activePath = secondaryPath,
                                projectRoot = state.projectRoot,
                                contentProvider = contentProvider,
                                reloadCounter = state.reloadCounter,
                                onContentChanged = onContentChanged,
                                fontScale = fontScale,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            } else {
                CodeEditorHost(
                    activePath = activePath,
                    projectRoot = state.projectRoot,
                    contentProvider = contentProvider,
                    reloadCounter = state.reloadCounter,
                    onContentChanged = onContentChanged,
                    onEditorReady = { editor = it },
                    fontScale = fontScale,
                    modifier = body,
                )
            }
        }
    }
}

@Composable
private fun FileActions(
    runnable: Boolean,
    running: Boolean,
    canPreview: Boolean,
    previewing: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onTogglePreview: () -> Unit,
    canRender: Boolean = false,
    rendering: Boolean = false,
    onRender: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (runnable) {
            if (running) {
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    "Running · output in Shell",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = onRun, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(22.dp))
                }
            }
        }
        if (canPreview) {
            IconButton(onClick = onTogglePreview, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (previewing) Icons.Default.Code else Icons.Default.Visibility,
                    contentDescription = if (previewing) "Show code" else "Preview",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (canRender) {
            IconButton(onClick = onRender, enabled = !rendering, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Movie, contentDescription = "Render MP4", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorTools(
    onFind: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFormat: () -> Unit,
    wrapped: Boolean,
    onWrap: () -> Unit,
    lineNumbers: Boolean,
    onLineNumbers: () -> Unit,
    onQuickOpen: () -> Unit,
    onGit: () -> Unit,
    onOutline: () -> Unit,
    splitAvailable: Boolean,
    split: Boolean,
    onSplit: () -> Unit,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ToolButton(Icons.Default.FolderOpen, "Quick open", onQuickOpen)
        ToolButton(Icons.Default.FormatListBulleted, "Outline", onOutline)
        ToolButton(Icons.Default.AccountTree, "Git", onGit)
        ToolButton(
            Icons.Default.VerticalSplit,
            if (split) "Unsplit" else "Split",
            { if (splitAvailable) onSplit() },
            active = split,
        )
        ToolButton(Icons.Default.TextDecrease, "Smaller text", onFontSmaller)
        ToolButton(Icons.Default.TextIncrease, "Larger text", onFontLarger)
        ToolButton(Icons.Default.Search, "Find and replace", onFind)
        ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", onUndo)
        ToolButton(Icons.AutoMirrored.Filled.Redo, "Redo", onRedo)
        ToolButton(Icons.Default.AutoFixHigh, "Format", onFormat)
        ToolButton(Icons.Default.WrapText, "Word wrap", onWrap, active = wrapped)
        ToolButton(Icons.Default.FormatListNumbered, "Line numbers", onLineNumbers, active = lineNumbers)
    }
}

@Composable
private fun SearchBar(
    query: String,
    replacement: String,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Find") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = onReplacement,
            label = { Text("Replace with") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onPrevious) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
            IconButton(onClick = onReplace) { Icon(Icons.Default.FindReplace, "Replace current") }
            IconButton(onClick = onReplaceAll) { Icon(Icons.Default.DoneAll, "Replace all") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close search") }
        }
    }
}

@Composable
private fun EmptyEditor(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No file open", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Pick one from the file list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EditorTabs(
    state: WorkspaceUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.activePath, state.openFiles.size) {
        val index = state.openFiles.indexOfFirst { it.path == state.activePath }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        items(state.openFiles, key = { it.path }) { file ->
            val selected = file.path == state.activePath
            val dirty = file.path in state.dirtyPaths

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onSelect(file.path) }
                    .background(if (selected) NeuroSelected else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            ) {
                Text(
                    text = file.path.substringAfterLast('/') + if (dirty) " •" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                IconButton(onClick = { onClose(file.path) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close ${file.path}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private class EditorHolder {
    var editor: CodeEditor? = null
    var lspEditor: LspEditor? = null
    var fittedPath: String? = null
    var fittedWidth: Int = 0
}

@Composable
private fun CodeEditorHost(
    activePath: String,
    projectRoot: String?,
    contentProvider: (String) -> String,
    reloadCounter: Long,
    onContentChanged: (String, String) -> Unit,
    onEditorReady: (CodeEditor) -> Unit = {},
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember { EditorHolder() }
    val currentPath by rememberUpdatedState(activePath)
    val currentListener by rememberUpdatedState(onContentChanged)
    val cursors = remember { mutableMapOf<String, Pair<Int, Int>>() }
    var lastPath by remember { mutableStateOf(activePath) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                setTypefaceLineNumber(Typeface.MONOSPACE)
                setEditorLanguage(SourceLanguage.forPath(currentPath))
                colorScheme = SchemeDarcula()
                setWordwrap(true)
                props.autoIndent = true
                props.symbolPairAutoCompletion = true
                props.deleteEmptyLineFast = false

                setText(contentProvider(currentPath))
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    fitEditorText(view as CodeEditor, holder, currentPath, fontScale)
                }
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    currentListener(currentPath, text.toString())
                }

                holder.editor = this
                onEditorReady(this)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            val lsp = holder.lspEditor
            holder.lspEditor = null
            if (lsp != null) runCatching { lsp.dispose() }
        }
    }

    LaunchedEffect(activePath, projectRoot) {
        val editor = holder.editor ?: return@LaunchedEffect
        holder.lspEditor?.let { previous ->
            holder.lspEditor = null
            runCatching { previous.dispose() }
        }
        val highlight = TreeSitterSupport.language(context, activePath)
        val root = projectRoot
        val lsp = if (root.isNullOrBlank()) {
            null
        } else {
            val absolute = File(root, activePath).absolutePath
            runCatching { LspHost.open(root, absolute, highlight) }.getOrNull()
        }
        if (lsp == null) {
            editor.setEditorLanguage(highlight)
        } else {
            val connected = runCatching { lsp.connect(false) }.getOrDefault(false)
            if (connected) {
                lsp.editor = editor
                holder.lspEditor = lsp
            } else {
                lsp.wrapperLanguage = null
                runCatching { lsp.dispose() }
                editor.setEditorLanguage(highlight)
            }
        }
    }

    // Pull the file in when the tab changes, or when the agent rewrote the open file.
    LaunchedEffect(activePath, reloadCounter) {
        val editor = holder.editor ?: return@LaunchedEffect
        if (lastPath != activePath) {
            runCatching {
                editor.cursor?.let { cursors[lastPath] = it.leftLine to it.leftColumn }
            }
        }
        val expected = contentProvider(activePath)
        if (editor.text.toString() != expected) {
            editor.setText(expected)
        }
        if (lastPath != activePath) {
            cursors[activePath]?.let { (line, column) ->
                if (line in 0 until editor.text.lineCount) {
                    val offset = editor.text.getCharIndex(line, column.coerceAtMost(editor.text.getColumnCount(line)))
                    editor.setSelection(offset, offset)
                }
            }
        }
        lastPath = activePath
        fitEditorText(editor, holder, activePath, fontScale)
    }
}

/**
 * First layout of a file picks a pixel size that fits this editor's width.
 * [CodeEditor.setTextSize] treats the argument as sp, so the pixel size must go
 * through [CodeEditor.setTextSizePx]. A later pinch is left alone until the pane
 * width actually changes.
 */
private fun fitEditorText(editor: CodeEditor, holder: EditorHolder, path: String, scale: Float = 1f) {
    val width = editor.width
    if (width <= 0) return
    if (holder.fittedPath == path && abs(holder.fittedWidth - width) < 80) return
    holder.fittedPath = path
    holder.fittedWidth = width
    val metrics = editor.resources.displayMetrics
    val widthDp = width / metrics.density
    val minSp = if (widthDp < 360f) 11f else 10f
    val maxSp = if (widthDp >= 700f) 16f else 14f
    val columns = when {
        widthDp >= 900f -> 48f
        widthDp >= 600f -> 44f
        widthDp >= 400f -> 36f
        else -> 32f
    }
    val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, metrics)
    val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, metrics)
    val gutter = 36f * metrics.density
    val size = (((width - gutter) / columns) * scale).coerceIn(minPx * scale, maxPx * scale)
    editor.setTextSizePx(size)
}
