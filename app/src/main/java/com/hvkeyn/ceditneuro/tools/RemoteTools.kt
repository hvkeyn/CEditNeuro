package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.data.RemoteServer
import com.hvkeyn.ceditneuro.net.RemoteClient
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private class RemoteBridge(
    private val server: () -> RemoteServer?,
    private val client: RemoteClient,
    private val workspace: Workspace? = null,
    private val onLocalWrite: (String) -> Unit = {},
) {
    fun requireServer(): RemoteServer =
        server() ?: throw IllegalStateException(
            "No remote server yet. Call remote_connect with the host, username, and password from the user.",
        )

    suspend fun list(path: String): String {
        val active = requireServer()
        val entries = client.list(active, path.ifBlank { "." })
        if (entries.isEmpty()) return "Empty directory."
        return entries
            .sortedWith(compareByDescending<com.hvkeyn.ceditneuro.net.RemoteEntry> { it.directory }.thenBy { it.name })
            .take(500)
            .joinToString("\n") { entry ->
                val kind = if (entry.directory) "dir " else "file"
                "$kind ${entry.size} ${entry.name}"
            }
    }

    suspend fun read(path: String): String {
        val bytes = client.read(requireServer(), path)
        if (bytes.any { it == 0.toByte() }) return "Remote file looks binary. Use remote_get to copy it into the project."
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.length <= 80_000) text else text.take(80_000) + "\n… truncated"
    }

    suspend fun write(path: String, content: String): String {
        val active = requireServer()
        client.write(active, path, content.toByteArray(Charsets.UTF_8))
        val site = active.webUrl.takeIf { it.isNotBlank() }?.let { " Then browse_page $it." }.orEmpty()
        return "Wrote $path on ${active.host}.$site"
    }

    suspend fun exec(command: String): String = client.exec(requireServer(), command)

    suspend fun mkdir(path: String): String {
        val active = requireServer()
        client.mkdir(active, path)
        return "Created $path on ${active.host}."
    }

    suspend fun delete(path: String, recursive: Boolean): String {
        val active = requireServer()
        client.delete(active, path, recursive)
        return "Deleted $path on ${active.host}."
    }

    suspend fun rename(from: String, to: String): String {
        val active = requireServer()
        client.rename(active, from, to)
        return "Renamed $from to $to on ${active.host}."
    }

    suspend fun put(localPath: String, remotePath: String): String {
        val ws = workspace ?: throw IllegalStateException("No project is open.")
        val file = ws.resolve(localPath)
        if (!file.isFile) throw IllegalStateException("$localPath is not a file.")
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val active = requireServer()
        client.write(active, remotePath, bytes)
        val site = active.webUrl.takeIf { it.isNotBlank() }?.let { " Then browse_page $it." }.orEmpty()
        return "Uploaded $localPath to $remotePath on ${active.host}.$site"
    }

    suspend fun get(remotePath: String, localPath: String): String {
        val ws = workspace ?: throw IllegalStateException("No project is open.")
        val bytes = client.read(requireServer(), remotePath)
        val file = ws.resolve(localPath)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        onLocalWrite(localPath)
        return "Downloaded $remotePath to $localPath (${bytes.size} bytes)."
    }
}

class RemoteConnectTool(
    private val client: RemoteClient,
    private val adopt: (RemoteServer) -> RemoteServer,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "remote_connect"
    override val description =
        "Connect to an FTP, FTPS, or SFTP server with credentials the user just gave you in the chat. " +
            "Call this before remote_list, remote_read, remote_write, remote_put, remote_get, remote_mkdir, remote_delete, remote_rename, or ssh_exec. " +
            "ssh means SFTP. Do not ask the user to retype the same login into Settings."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("Optional ftp://, ftps://, or sftp:// URL including user and password."),
            "protocol" to stringProp("ftp, ftps, or sftp. Defaults from the URL, otherwise sftp."),
            "host" to stringProp("Server host name or IP."),
            "port" to intProp("Port. Defaults to 22 for sftp and 21 for ftp/ftps."),
            "username" to stringProp("Login."),
            "password" to stringProp("Password."),
            "start_path" to stringProp("Remote directory to start in. Defaults to /."),
            "web_url" to stringProp("Public http(s) URL of the site to open in the browser after edits."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!networkAllowed()) return ToolResult.error("Agent network is off. Turn on Agent network in Settings.")
        val parsed = parse(args)
        val saved = adopt(parsed)
        return remoteResult {
            val report = client.probe(saved)
            val site = saved.webUrl.takeIf { it.isNotBlank() }?.let { " Site: $it. Use browse_page on it after changes." }.orEmpty()
            report + site
        }
    }

    private fun parse(args: JsonObject): RemoteServer {
        val fromUrl = args.stringArg("url")?.let(::parseRemoteUrl)
        val protocol = (args.stringArg("protocol") ?: fromUrl?.protocol ?: "sftp").lowercase()
            .let { if (it == "ssh") "sftp" else it }
        if (protocol !in RemoteServer.PROTOCOLS) {
            throw IllegalArgumentException("Protocol must be ftp, ftps, or sftp.")
        }
        val host = args.stringArg("host")?.trim().orEmpty().ifBlank { fromUrl?.host.orEmpty() }
        if (host.isBlank()) throw IllegalArgumentException("Missing host.")
        val port = args.intArg("port") ?: fromUrl?.port ?: RemoteServer.defaultPort(protocol)
        if (port !in 1..65535) throw IllegalArgumentException("Port must be from 1 to 65535.")
        return RemoteServer(
            protocol = protocol,
            host = host,
            port = port,
            username = args.stringArg("username") ?: fromUrl?.username.orEmpty(),
            password = args.stringArg("password") ?: fromUrl?.password.orEmpty(),
            startPath = args.stringArg("start_path")?.trim()?.ifBlank { null } ?: fromUrl?.startPath ?: "/",
            webUrl = args.stringArg("web_url")?.trim().orEmpty(),
            name = host,
        )
    }
}

private data class ParsedUrl(
    val protocol: String,
    val host: String,
    val port: Int?,
    val username: String,
    val password: String,
    val startPath: String,
)

private fun parseRemoteUrl(raw: String): ParsedUrl {
    val uri = java.net.URI(raw.trim())
    val protocol = uri.scheme?.lowercase()?.let { if (it == "ssh") "sftp" else it }
        ?: throw IllegalArgumentException("URL needs a scheme such as sftp://.")
    val host = uri.host ?: throw IllegalArgumentException("URL has no host.")
    val userInfo = uri.userInfo.orEmpty()
    val username = userInfo.substringBefore(':')
    val password = if (userInfo.contains(':')) userInfo.substringAfter(':') else ""
    val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
    return ParsedUrl(protocol, host, uri.port.takeIf { it > 0 }, username, password, path)
}

class RemoteListTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_list"
    override val description =
        "List a directory on the connected FTP or SFTP server. path is remote, relative to the server start path, or absolute."
    override val parameters = objectSchema(
        properties = mapOf("path" to stringProp("Remote directory. Defaults to the server start path.")),
    )

    override suspend fun execute(args: JsonObject): ToolResult =
        remoteResult { bridge.list(args.stringArg("path").orEmpty()) }
}

class RemoteReadTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_read"
    override val description = "Read a text file from the selected FTP or SFTP server."
    override val parameters = objectSchema(
        properties = mapOf("path" to stringProp("Remote file path.")),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path") ?: return ToolResult.error("Missing 'path'.")
        return remoteResult { bridge.read(path) }
    }
}

class RemoteWriteTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_write"
    override val description =
        "Create or replace a text file on the connected FTP or SFTP server. " +
            "After uploading a site, open it with browse_page."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("Remote file path."),
            "content" to stringProp("Full new text of the file."),
        ),
        required = listOf("path", "content"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path") ?: return ToolResult.error("Missing 'path'.")
        val content = args.stringArg("content") ?: return ToolResult.error("Missing 'content'.")
        return remoteResult { bridge.write(path, content) }
    }
}

class RemotePutTool(
    server: () -> RemoteServer?,
    client: RemoteClient,
    workspace: Workspace,
) : Tool {
    private val bridge = RemoteBridge(server, client, workspace)
    override val name = "remote_put"
    override val description = "Upload a project file to the selected FTP or SFTP server."
    override val parameters = objectSchema(
        properties = mapOf(
            "local_path" to stringProp("File inside the open project."),
            "remote_path" to stringProp("Destination path on the server."),
        ),
        required = listOf("local_path", "remote_path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val local = args.stringArg("local_path") ?: return ToolResult.error("Missing 'local_path'.")
        val remote = args.stringArg("remote_path") ?: return ToolResult.error("Missing 'remote_path'.")
        return remoteResult { bridge.put(local, remote) }
    }
}

class RemoteGetTool(
    server: () -> RemoteServer?,
    client: RemoteClient,
    workspace: Workspace,
    onLocalWrite: (String) -> Unit,
) : Tool {
    private val bridge = RemoteBridge(server, client, workspace, onLocalWrite)
    override val name = "remote_get"
    override val description = "Download a file from the selected FTP or SFTP server into the project."
    override val parameters = objectSchema(
        properties = mapOf(
            "remote_path" to stringProp("File on the server."),
            "local_path" to stringProp("Destination inside the open project."),
        ),
        required = listOf("remote_path", "local_path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val remote = args.stringArg("remote_path") ?: return ToolResult.error("Missing 'remote_path'.")
        val local = args.stringArg("local_path") ?: return ToolResult.error("Missing 'local_path'.")
        return remoteResult { bridge.get(remote, local) }
    }
}

class RemoteMkdirTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_mkdir"
    override val description = "Create a directory on the connected FTP or SFTP server."
    override val parameters = objectSchema(
        properties = mapOf("path" to stringProp("Remote directory to create.")),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path") ?: return ToolResult.error("Missing 'path'.")
        return remoteResult { bridge.mkdir(path) }
    }
}

class RemoteDeleteTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_delete"
    override val description =
        "Delete a file or directory on the connected FTP or SFTP server. " +
            "Set recursive true to delete a directory and what is inside it. Refuses the remote root."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("Remote file or directory."),
            "recursive" to com.hvkeyn.ceditneuro.tools.boolProp("Delete a directory and its contents."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path") ?: return ToolResult.error("Missing 'path'.")
        return remoteResult { bridge.delete(path, args.boolArg("recursive") == true) }
    }
}

class RemoteRenameTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "remote_rename"
    override val description = "Rename or move a file on the connected FTP or SFTP server."
    override val parameters = objectSchema(
        properties = mapOf(
            "from" to stringProp("Current remote path."),
            "to" to stringProp("New remote path."),
        ),
        required = listOf("from", "to"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val from = args.stringArg("from") ?: return ToolResult.error("Missing 'from'.")
        val to = args.stringArg("to") ?: return ToolResult.error("Missing 'to'.")
        return remoteResult { bridge.rename(from, to) }
    }
}

class SshExecTool(server: () -> RemoteServer?, client: RemoteClient) : Tool {
    private val bridge = RemoteBridge(server, client)
    override val name = "ssh_exec"
    override val description =
        "Run one command on the connected SFTP server over SSH. FTP cannot do this. " +
            "Use it to reload a service, then check the site with browse_page."
    override val parameters = objectSchema(
        properties = mapOf("command" to stringProp("One remote shell command.")),
        required = listOf("command"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args.stringArg("command") ?: return ToolResult.error("Missing 'command'.")
        return remoteResult { bridge.exec(command) }
    }
}

private suspend fun remoteResult(block: suspend () -> String): ToolResult = try {
    ToolResult.ok(block())
} catch (error: Exception) {
    ToolResult.error(error.message ?: "Remote call failed.")
}
