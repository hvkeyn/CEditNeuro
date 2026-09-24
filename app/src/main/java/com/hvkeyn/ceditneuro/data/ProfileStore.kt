package com.hvkeyn.ceditneuro.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.hvkeyn.ceditneuro.workspace.StoragePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A named copy of settings and project sessions on shared storage.
 * Uninstalling the app removes private files. This folder stays, so the next
 * install can load the same profile. The file holds API keys and server
 * passwords and is never written into the project tree.
 */
@Serializable
data class DeviceProfile(
    val name: String = DEFAULT_PROFILE,
    val settings: AgentSettings = AgentSettings(),
    val roots: List<String> = emptyList(),
    val sessions: Map<String, StoredSession> = emptyMap(),
)

private const val DEFAULT_PROFILE = "default"
private const val ACTIVE_FILE = "active.txt"

class ProfileStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun names(): List<String> {
        val folder = directory() ?: return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
    }

    fun activeName(): String {
        val folder = directory() ?: return DEFAULT_PROFILE
        val marked = File(folder, ACTIVE_FILE).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (marked.isNotEmpty()) {
            val safe = safeName(marked)
            if (File(folder, "$safe.json").isFile) return safe
        }
        return names().firstOrNull() ?: DEFAULT_PROFILE
    }

    fun readActive(): DeviceProfile? = read(activeName())

    fun read(name: String): DeviceProfile? {
        val folder = directory() ?: return null
        val file = File(folder, "${safeName(name)}.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<DeviceProfile>(file.readText()) }.getOrNull()
    }

    fun markActive(name: String) {
        val folder = directory() ?: return
        File(folder, ACTIVE_FILE).writeText(safeName(name))
    }

    fun write(name: String, settings: AgentSettings, roots: List<String>, sessions: Map<String, StoredSession>) {
        if (!storageReady()) return
        val folder = directory() ?: return
        val safe = safeName(name)
        val profile = DeviceProfile(name = safe, settings = settings, roots = roots, sessions = sessions)
        val dest = File(folder, "$safe.json")
        val tmp = File(folder, "$safe.json.tmp")
        tmp.writeText(json.encodeToString(profile))
        if (!tmp.renameTo(dest)) {
            dest.writeText(tmp.readText())
            tmp.delete()
        }
        File(folder, ACTIVE_FILE).writeText(safe)
    }

    fun safeName(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-', '.')
            .take(40)
        return cleaned.ifBlank { DEFAULT_PROFILE }
    }

    private fun directory(): File? {
        if (!storageReady()) return null
        return File(StoragePaths.primaryRoot(), "CEditNeuro/profiles").apply { mkdirs() }
    }

    private fun storageReady(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
