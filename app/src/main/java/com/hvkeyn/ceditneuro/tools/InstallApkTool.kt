package com.hvkeyn.ceditneuro.tools

import android.content.Context
import com.hvkeyn.ceditneuro.update.ApkInstaller
import com.hvkeyn.ceditneuro.update.AppUpdater
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Installs an APK with the system installer and opens it after the user confirms.
 * This is the in-app replacement for `pm install` through Shizuku.
 */
class InstallApkTool(
    private val context: Context,
    private val workspace: Workspace,
) : Tool {
    override val name = "install_apk"
    override val description =
        "Install an APK that is already on the device and open it after the user confirms " +
            "the system installer. Use this instead of shizuku_exec or pm install. " +
            "path is project-relative or absolute, such as /sdcard/Download/app.apk. " +
            "The result is the real path and size. If the file is missing, do not tell the user it was saved."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("APK path. Relative to the project, or absolute on shared storage."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path")?.trim().orEmpty()
        if (path.isEmpty()) return@withContext ToolResult.error("Missing 'path'.")
        val file = runCatching { workspace.resolve(path) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad path.")
        }
        if (!file.isFile) {
            return@withContext ToolResult.error(
                "No file at ${file.absolutePath}. The request was `$path`. " +
                    "List that directory and copy the APK again before telling the user it is there.",
            )
        }
        val parsed = ApkInstaller.parse(context, file)
            ?: return@withContext ToolResult.error(
                "${file.absolutePath} is not an APK (${file.length()} bytes).",
            )
        if (!AppUpdater.canInstallPackages(context)) {
            withContext(Dispatchers.Main) { AppUpdater.requestInstallPermission(context) }
            return@withContext ToolResult.error(
                "Allow this app to install packages, then call install_apk again. " +
                    "The APK is ${parsed.file.absolutePath} (${parsed.file.length()} bytes), package ${parsed.packageName}.",
            )
        }
        val started = runCatching { ApkInstaller.install(context, parsed.file, parsed.packageName) }
        started.onFailure { error ->
            return@withContext ToolResult.error(
                error.message ?: "Could not start the installer for ${parsed.file.absolutePath}.",
            )
        }
        ToolResult.ok(
            "Opened the system installer for ${parsed.packageName}. " +
                "File: ${parsed.file.absolutePath} (${parsed.file.length()} bytes). " +
                "Ask the user to confirm the install. The app opens when it finishes.",
        )
    }
}
