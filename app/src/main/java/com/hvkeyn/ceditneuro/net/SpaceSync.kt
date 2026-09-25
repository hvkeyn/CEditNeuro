package com.hvkeyn.ceditneuro.net

import com.hvkeyn.ceditneuro.data.RemoteServer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One remote folder shared by two phones. The link has no password.
 * The code is checked against a hash stored in that folder.
 */
class SpaceSync(private val client: RemoteClient) {
    fun link(server: RemoteServer): String {
        val path = server.startPath.ifBlank { "/" }
        return "${server.protocol}://${server.username}@${server.host}:${server.port}$path"
    }

    suspend fun publish(server: RemoteServer, code: String) {
        client.mkdir(server, SPACE)
        client.write(server, "$SPACE/code.sha256", hash(code).toByteArray())
    }

    suspend fun check(server: RemoteServer, code: String): Boolean {
        val stored = runCatching { client.read(server, "$SPACE/code.sha256").toString(Charsets.UTF_8).trim() }
            .getOrElse { return false }
        return stored == hash(code)
    }

    suspend fun sync(server: RemoteServer, root: File, device: String): String {
        client.mkdir(server, SPACE)
        val stampFile = File(root, ".ceditneuro/space-stamps.json")
        val stamps = readStamps(stampFile)
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        val localFiles = collect(root, root, 0)
        val remoteSizes = remoteFiles(server, "")
        localFiles.forEach { (relative, file) ->
            val size = file.length()
            val known = stamps.optLong(relative, -1L)
            val remote = remoteSizes[relative]
            when {
                remote == null || known < 0 || size != known -> {
                    putFile(server, relative, file)
                    stamps.put(relative, size)
                    pushed++
                }
                remote != known && size == known -> {
                    val bytes = client.read(server, relative)
                    file.writeBytes(bytes)
                    stamps.put(relative, bytes.size.toLong())
                    pulled++
                }
                remote != size -> {
                    val copy = File(file.parentFile, file.name + ".from-peer")
                    copy.writeBytes(client.read(server, relative))
                    conflicts++
                }
            }
        }
        remoteSizes.forEach { (relative, _) ->
            if (stamps.has(relative) || relative.startsWith("${SpaceSync.SPACE}/")) return@forEach
            val dest = File(root, relative)
            if (dest.exists()) return@forEach
            dest.parentFile?.mkdirs()
            val bytes = client.read(server, relative)
            dest.writeBytes(bytes)
            stamps.put(relative, bytes.size.toLong())
            pulled++
        }
        val note = "$device synced pushed=$pushed pulled=$pulled conflicts=$conflicts"
        client.write(server, "$SPACE/peer-$device.txt", note.toByteArray())
        stampFile.parentFile?.mkdirs()
        stampFile.writeText(stamps.toString())
        return note
    }

    private suspend fun putFile(server: RemoteServer, relative: String, file: File) {
        val parent = relative.substringBeforeLast('/', "")
        var built = ""
        parent.split('/').filter { it.isNotEmpty() }.forEach { part ->
            built = if (built.isEmpty()) part else "$built/$part"
            client.mkdir(server, built)
        }
        client.write(server, relative, file.readBytes())
    }

    private suspend fun remoteFiles(server: RemoteServer, relative: String): Map<String, Long> {
        val path = relative.ifBlank { "." }
        val entries = runCatching { client.list(server, path) }.getOrDefault(emptyList())
        val out = linkedMapOf<String, Long>()
        entries.forEach { entry ->
            if (entry.name.startsWith(".")) return@forEach
            val child = if (relative.isBlank()) entry.name else "$relative/${entry.name}"
            if (entry.directory) {
                if (out.size + child.length < 4_000) out.putAll(remoteFiles(server, child))
            } else if (entry.size <= MAX_FILE) {
                out[child] = entry.size
            }
        }
        return out
    }

    private fun collect(root: File, dir: File, depth: Int): List<Pair<String, File>> {
        if (depth > 6) return emptyList()
        val out = mutableListOf<Pair<String, File>>()
        dir.listFiles()?.forEach { child ->
            if (child.name in SKIP || child.name.startsWith(".")) return@forEach
            if (child.isDirectory) out += collect(root, child, depth + 1)
            else if (child.length() in 1..MAX_FILE && out.size < MAX_FILES) {
                out += child.relativeTo(root).invariantSeparatorsPath to child
            }
        }
        return out
    }

    private fun readStamps(file: File): JSONObject {
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun hash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SPACE = ".space"
        private const val MAX_FILE = 1_000_000L
        private const val MAX_FILES = 80
        private val SKIP = setOf("build", ".gradle", ".git", "node_modules", ".ceditneuro")
    }
}
