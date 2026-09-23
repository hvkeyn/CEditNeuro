package com.hvkeyn.ceditneuro.ui.editor

import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSParser
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.client.connection.SocketStreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * One in-process language server for every open project. Tree-sitter reports syntax
 * errors. Completion offers words already used in the file.
 */
object LspHost {
    private val extensions = listOf(
        "kt", "kts", "java", "py", "json", "xml", "html", "htm", "svg",
        "js", "jsx", "ts", "tsx", "md", "sh", "gradle",
    )
    private val lock = Any()
    private var port = -1
    private var project: LspProject? = null
    private var projectRoot: String? = null

    fun open(root: String, absolutePath: String, highlight: Language): LspEditor {
        val listen = ensurePort()
        val lspProject = synchronized(lock) {
            if (project == null || projectRoot != root) {
                project?.dispose()
                project = LspProject(root).also { created ->
                    created.init()
                    extensions.forEach { ext ->
                        created.addServerDefinition(serverDefinition(ext, listen))
                    }
                }
                projectRoot = root
            }
            project!!
        }
        val editor = lspProject.createEditor(absolutePath)
        editor.wrapperLanguage = highlight
        editor.completionTriggers = listOf(".", ":")
        return editor
    }

    private fun ensurePort(): Int {
        synchronized(lock) {
            if (port >= 0) return port
            val socket = ServerSocket(0)
            port = socket.localPort
            thread(name = "lsp-accept", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "lsp-client", isDaemon = true) {
                        runCatching {
                            val server = LocalLanguageServer()
                            val launcher = Launcher.createLauncher(
                                server,
                                LanguageClient::class.java,
                                client.getInputStream(),
                                client.getOutputStream(),
                            )
                            server.connect(launcher.remoteProxy)
                            launcher.startListening().get()
                        }
                        runCatching { client.close() }
                    }
                }
            }
            return port
        }
    }

    private fun serverDefinition(ext: String, listen: Int) =
        object : CustomLanguageServerDefinition(
            ext,
            ServerConnectProvider { SocketStreamConnectionProvider(listen) },
        ) {}
}

private class LocalLanguageServer : LanguageServer, LanguageClientAware {
    private val documents = LocalDocuments()
    private val workspace = EmptyWorkspace()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val sync = TextDocumentSyncOptions()
        sync.openClose = true
        sync.change = TextDocumentSyncKind.Full
        val capabilities = ServerCapabilities()
        capabilities.textDocumentSync = Either.forRight(sync)
        capabilities.setCompletionProvider(org.eclipse.lsp4j.CompletionOptions(false, listOf(".", ":")))
        capabilities.setHoverProvider(true)
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)

    override fun exit() = Unit

    override fun getTextDocumentService(): TextDocumentService = documents

    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        documents.client = client
    }
}

private class EmptyWorkspace : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
}

private class LocalDocuments : TextDocumentService {
    var client: LanguageClient? = null
    private val texts = ConcurrentHashMap<String, String>()

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        texts[uri] = params.textDocument.text
        publish(uri, params.textDocument.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.contentChanges.lastOrNull()?.text ?: return
        texts[uri] = text
        publish(uri, text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        texts.remove(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val text = params.text
        if (text != null) texts[params.textDocument.uri] = text
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val text = texts[params.textDocument.uri].orEmpty()
        val prefix = prefixAt(text, params.position)
        val items = words(text)
            .filter { it.startsWith(prefix) && it != prefix }
            .distinct()
            .take(40)
            .map { word ->
                CompletionItem(word).apply {
                    kind = CompletionItemKind.Text
                    insertText = word
                }
            }
            .toList()
        return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, items)))
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        val text = texts[params.textDocument.uri].orEmpty()
        val word = wordAt(text, params.position)
        val hover = Hover(
            MarkupContent(MarkupKind.PLAINTEXT, if (word.isBlank()) " " else word),
        )
        return CompletableFuture.completedFuture(hover)
    }

    private fun publish(uri: String, text: String) {
        val path = uri.substringAfterLast('/').substringBefore('?')
        val language = TreeSitterSupport.grammar(path) ?: return
        val diagnostics = syntaxDiagnostics(language, text)
        client?.publishDiagnostics(
            org.eclipse.lsp4j.PublishDiagnosticsParams(uri, diagnostics),
        )
    }
}

private fun syntaxDiagnostics(language: TSLanguage, text: String): List<Diagnostic> {
    if (text.length > 400_000) return emptyList()
    val parser = TSParser.create()
    return try {
        parser.setLanguage(language)
        val tree = parser.parseString(text) ?: return emptyList()
        try {
            val found = ArrayList<Diagnostic>(8)
            collectErrors(tree.rootNode, found)
            found
        } finally {
            tree.close()
        }
    } catch (_: Throwable) {
        emptyList()
    } finally {
        parser.close()
    }
}

private fun collectErrors(node: com.itsaky.androidide.treesitter.TSNode, out: MutableList<Diagnostic>) {
    if (out.size >= 40) return
    val type = node.type
    if (type == "ERROR" || type == "MISSING") {
        val start = node.startPoint
        val end = node.endPoint
        out += Diagnostic(
            Range(
                Position(start.row, start.column),
                Position(end.row, end.column),
            ),
            "Syntax error",
            DiagnosticSeverity.Error,
            "tree-sitter",
        )
        return
    }
    for (index in 0 until node.childCount) {
        collectErrors(node.getChild(index), out)
        if (out.size >= 40) return
    }
}

private fun prefixAt(text: String, position: Position): String {
    val line = text.lineSequence().drop(position.line).firstOrNull().orEmpty()
    val column = position.character.coerceIn(0, line.length)
    val head = line.substring(0, column)
    return head.takeLastWhile { it.isLetterOrDigit() || it == '_' }
}

private fun wordAt(text: String, position: Position): String {
    val line = text.lineSequence().drop(position.line).firstOrNull().orEmpty()
    if (line.isEmpty()) return ""
    val column = position.character.coerceIn(0, line.length)
    var start = column
    var end = column
    while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
    return line.substring(start, end)
}

private fun words(text: String): Sequence<String> =
    Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text).map { it.value }
