package com.hvkeyn.ceditneuro.tools

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Places archive entries without following an existing symlink.
 * Following one turns a library file into a link that points at itself, and the
 * next install then dies with "too many symbolic links".
 */
internal object ArchivePaths {

    fun output(root: File, rel: String): Path {
        val base = root.toPath().toAbsolutePath().normalize()
        val path = base.resolve(rel).normalize()
        if (!path.startsWith(base)) {
            throw IllegalStateException("Archive entry escapes the toolchain: $rel")
        }
        return path
    }

    fun directory(path: Path) {
        Files.createDirectories(path)
    }

    fun symlink(link: Path, toolchain: File, rawTarget: String?) {
        if (rawTarget.isNullOrBlank()) return
        val parent = link.parent
        if (parent != null) Files.createDirectories(parent)
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
        val target = storedTarget(toolchain, rawTarget)
        val targetPath = Paths.get(target)
        val resolved = if (targetPath.isAbsolute) {
            targetPath.normalize()
        } else {
            (parent ?: link).resolve(targetPath).normalize()
        }
        if (resolved == link.normalize()) return
        val base = toolchain.toPath().toAbsolutePath().normalize()
        if (targetPath.isAbsolute && !resolved.startsWith(base)) {
            throw IllegalStateException("Refusing symlink outside the toolchain: $rawTarget")
        }
        Files.createSymbolicLink(link, if (targetPath.isAbsolute) resolved else targetPath)
    }

    fun openNewFile(path: Path): OutputStream {
        val parent = path.parent
        if (parent != null) Files.createDirectories(parent)
        if (Files.isSymbolicLink(path)) Files.delete(path)
        return Files.newOutputStream(path)
    }

    private fun storedTarget(toolchain: File, raw: String): String {
        val normalized = raw.replace('\\', '/')
        val marker = "com.termux/files/usr/"
        val index = normalized.indexOf(marker)
        if (index >= 0) {
            return File(toolchain, normalized.substring(index + marker.length)).path
        }
        if (normalized.startsWith("/")) {
            throw IllegalStateException("Refusing symlink outside the toolchain: $raw")
        }
        return raw
    }
}
