package com.hvkeyn.ceditneuro.ui.editor

import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.CodeEditor
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
) {
    val activePath = state.activePath
    if (activePath == null || state.openFiles.isEmpty()) {
        EmptyEditor(modifier)
        return
    }

    Column(modifier) {
        if (state.openFiles.isNotEmpty()) {
            EditorTabs(state = state, onSelect = onSelectTab, onClose = onCloseTab)
        }
        CodeEditorHost(
            activePath = activePath,
            projectRoot = state.projectRoot,
            contentProvider = contentProvider,
            reloadCounter = state.reloadCounter,
            onContentChanged = onContentChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun EmptyEditor(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No file open.\nUse the file tree to pick one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EditorTabs(
    state: WorkspaceUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    LazyRow(
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
                    .clickable { onSelect(file.path) }
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
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
                IconButton(onClick = { onClose(file.path) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close ${file.path}",
                        modifier = Modifier.size(14.dp),
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember { EditorHolder() }
    val currentPath by rememberUpdatedState(activePath)
    val currentListener by rememberUpdatedState(onContentChanged)

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
                    fitEditorText(view as CodeEditor, holder, currentPath)
                }
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    currentListener(currentPath, text.toString())
                }

                holder.editor = this
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
        val expected = contentProvider(activePath)
        if (editor.text.toString() != expected) {
            editor.setText(expected)
        }
        fitEditorText(editor, holder, activePath)
    }
}

/**
 * First layout of a file picks a pixel size that fits this editor's width.
 * [CodeEditor.setTextSize] treats the argument as sp, so the pixel size must go
 * through [CodeEditor.setTextSizePx]. A later pinch is left alone until the pane
 * width actually changes.
 */
private fun fitEditorText(editor: CodeEditor, holder: EditorHolder, path: String) {
    val width = editor.width
    if (width <= 0) return
    if (holder.fittedPath == path && abs(holder.fittedWidth - width) < 80) return
    holder.fittedPath = path
    holder.fittedWidth = width
    val metrics = editor.resources.displayMetrics
    val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, metrics)
    val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, metrics)
    val gutter = 36f * metrics.density
    val columns = 40f
    val size = ((width - gutter) / columns).coerceIn(minPx, maxPx)
    editor.setTextSizePx(size)
}
