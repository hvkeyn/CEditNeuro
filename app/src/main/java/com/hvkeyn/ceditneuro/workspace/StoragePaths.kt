package com.hvkeyn.ceditneuro.workspace

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/**
 * Shared-storage paths differ by phone. `/sdcard` may be a symlink, the Downloads
 * folder may be `Download` or `Downloads`, and the canonical path may be a mount
 * the file manager does not show. This asks the device which directories exist
 * and keeps the path the user can open.
 */
object StoragePaths {

    @Volatile
    var context: Context? = null

    fun describe(): String {
        val root = primaryRoot()
        val download = publicDownload()
        val volumes = discoverRoots().joinToString(", ") { it.absolutePath }
        return "On this phone shared storage is ${root.absolutePath}. " +
            "The Downloads folder is ${download.absolutePath}. " +
            "Other volumes: $volumes."
    }

    fun primaryRoot(): File = discoverRoots().first()

    fun publicDownload(): File = publicOn(primaryRoot(), "download")

    fun absolute(path: String): File {
        val normalized = path.trim().replace('\\', '/')
        val stripped = stripVolume(normalized)
        val placed = if (stripped == null) File(normalized) else place(stripped.first, stripped.second)
        return finish(placed)
    }

    /** Canonicalizes only an existing directory, and skips mounts the user cannot browse. */
    fun finish(file: File): File {
        val missing = ArrayDeque<String>()
        var cursor: File? = file
        while (cursor != null && !cursor.exists()) {
            val name = cursor.name
            if (name.isEmpty()) break
            missing.addFirst(name)
            cursor = cursor.parentFile
        }
        val base = if (cursor != null && cursor.exists()) visible(cursor) else file.absoluteFile
        return missing.fold(base) { parent, name -> File(parent, name) }
    }

    fun rewriteCommand(command: String): String {
        var text = command
        val primary = primaryRoot()
        for (alias in aliasPrefixes(primary).sortedByDescending { it.length }) {
            if (alias == primary.absolutePath) continue
            text = text.replace(alias, primary.absolutePath)
        }
        for (root in discoverRoots()) {
            val pattern = Regex(
                "${Regex.escape(root.absolutePath)}/(?i)(downloads|download|documents|dcim|pictures|movies|music)" +
                    "(?=/|\\s|\"|'|$)",
            )
            text = pattern.replace(text) { match ->
                publicOn(root, match.groupValues[1]).absolutePath
            }
        }
        return text
    }

    fun noteVisibleFiles(command: String): String {
        val files = mentionedFiles(command)
        if (files.isEmpty()) return ""
        return files.joinToString(prefix = "\nvisible:\n", separator = "\n") { file ->
            scan(file)
            val shown = if (file.exists()) visible(file) else file
            "${shown.absolutePath} (${file.length()} bytes)"
        }
    }

    fun scan(file: File) {
        if (!file.isFile) return
        val ctx = context?.applicationContext ?: return
        if (discoverRoots().none { root -> file.absolutePath.startsWith(root.absolutePath) }) return
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
    }

    fun volumeNamed(name: String): File? = discoverRoots().firstOrNull { root ->
        root.name.equals(name, ignoreCase = true) || root.absolutePath.endsWith("/$name")
    }

    private fun mentionedFiles(command: String): List<File> {
        val roots = discoverRoots().map { it.absolutePath }
        return command.split(Regex("[\\s\"']+")).mapNotNull { token ->
            if (roots.none { token.startsWith(it) }) return@mapNotNull null
            val file = File(token)
            if (file.isFile) file else null
        }
    }

    private fun discoverRoots(): List<File> {
        val found = LinkedHashMap<String, File>()
        fun add(file: File?) {
            if (file == null || !file.isDirectory) return
            val shown = visible(file)
            found.putIfAbsent(shown.absolutePath, shown)
        }
        context?.getExternalFilesDirs(null)?.forEach { dir ->
            add(dir?.parentFile?.parentFile?.parentFile?.parentFile)
        }
        add(runCatching { Environment.getExternalStorageDirectory() }.getOrNull())
        add(File("/sdcard"))
        add(File("/mnt/sdcard"))
        add(File("/storage/self/primary"))
        add(File("/storage/emulated/0"))
        File("/storage").listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != "emulated" && child.name != "self") add(child)
        }
        if (found.isEmpty()) return listOf(File("/sdcard"))
        val primary = runCatching { visible(Environment.getExternalStorageDirectory()).absolutePath }.getOrNull()
        return found.values.sortedByDescending { it.absolutePath == primary }
    }

    private fun stripVolume(path: String): Pair<File, String>? {
        val roots = discoverRoots()
        val prefixes = roots.flatMap { root -> aliasPrefixes(root).map { prefix -> prefix to root } }
            .sortedByDescending { it.first.length }
        val match = prefixes.firstOrNull { (prefix, _) -> path == prefix || path.startsWith("$prefix/") }
            ?: return null
        return match.second to path.removePrefix(match.first).trimStart('/')
    }

    private fun aliasPrefixes(root: File): List<String> {
        val aliases = mutableListOf(root.absolutePath)
        if (root.absolutePath != primaryRoot().absolutePath) return aliases
        aliases += listOf("/sdcard", "/mnt/sdcard", "/storage/emulated/0", "/storage/self/primary")
        aliases += listOf(
            "/mnt/runtime/write/emulated/0",
            "/mnt/runtime/read/emulated/0",
            "/mnt/runtime/default/emulated/0",
            "/mnt/user/0/emulated/0",
        )
        return aliases.distinct()
    }

    private fun place(root: File, rest: String): File {
        if (rest.isEmpty()) return root
        val name = rest.substringBefore('/')
        val tail = rest.substringAfter('/', "")
        if (!isPublicName(name)) return File(root, rest)
        val dir = publicOn(root, name)
        return if (tail.isEmpty()) dir else File(dir, tail)
    }

    private fun publicOn(root: File, name: String): File {
        val wanted = publicNames(name)
        val existing = root.listFiles()?.firstOrNull { child ->
            child.isDirectory && wanted.any { it.equals(child.name, ignoreCase = true) }
        }
        if (existing != null) return visible(existing)
        val system = systemPublicDir(name)
        if (system != null && system.isDirectory && system.absolutePath.startsWith(root.absolutePath)) {
            return visible(system)
        }
        return File(root, wanted.first())
    }

    private fun isPublicName(name: String): Boolean = publicNames(name).isNotEmpty()

    private fun publicNames(name: String): List<String> = when (name.lowercase()) {
        "download", "downloads" -> listOf("Download", "Downloads")
        "documents" -> listOf("Documents")
        "dcim" -> listOf("DCIM", "Dcim")
        "pictures" -> listOf("Pictures")
        "movies" -> listOf("Movies")
        "music" -> listOf("Music")
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun systemPublicDir(name: String): File? {
        val kind = when (name.lowercase()) {
            "download", "downloads" -> Environment.DIRECTORY_DOWNLOADS
            "documents" -> Environment.DIRECTORY_DOCUMENTS
            "dcim" -> Environment.DIRECTORY_DCIM
            "pictures" -> Environment.DIRECTORY_PICTURES
            "movies" -> Environment.DIRECTORY_MOVIES
            "music" -> Environment.DIRECTORY_MUSIC
            else -> return null
        }
        return Environment.getExternalStoragePublicDirectory(kind)
    }

    private fun visible(file: File): File {
        val absolute = file.absoluteFile
        val canonical = runCatching { absolute.canonicalFile }.getOrDefault(absolute)
        val canonicalHidden = HIDDEN_MOUNT.containsMatchIn(canonical.absolutePath)
        val absoluteHidden = HIDDEN_MOUNT.containsMatchIn(absolute.absolutePath)
        return if (canonicalHidden && !absoluteHidden && absolute.exists()) absolute else canonical
    }

    private val HIDDEN_MOUNT = Regex(
        "^(?:/mnt/runtime|/mnt/user|/mnt/media_rw|/mnt/pass_through|/mnt/androidwritable)(?:/|$)",
    )
}
