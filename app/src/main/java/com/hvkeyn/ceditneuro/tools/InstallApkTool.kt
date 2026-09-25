package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
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
            "The result is the real path and size. If the file is missing, do not tell the user it was saved. " +
            "To remove an installed app, call uninstall_apk. Do not open the app and tap through it."
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
                "Ask the user to confirm the install. The app opens when it finishes. " +
                "Do not open the package and tap through its screens.",
        )
    }
}

/** Opens Android's own uninstall screen. This app cannot silently remove another package. */
class UninstallApkTool(private val context: Context) : Tool {
    override val name = "uninstall_apk"
    override val description =
        "Open the system uninstall screen for an installed package. " +
            "Use this instead of Settings, the launcher, or tapping inside the app. " +
            "package is the application id. Android asks the user to confirm. " +
            "If the package is not installed, stop. Do not go looking for it."
    override val parameters = objectSchema(
        properties = mapOf(
            "package" to stringProp("Application id, such as com.example.app."),
        ),
        required = listOf("package"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.stringArg("package")?.trim().orEmpty()
        if (!name.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+"""))) {
            return ToolResult.error("package must be an application id.")
        }
        val installed = runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
        if (!installed) {
            return ToolResult.error("$name is not installed. Do not open the launcher or Settings to look for it.")
        }
        val screen = Intent(Intent.ACTION_DELETE)
            .setData(Uri.parse("package:$name"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(screen) }
        return opened.fold(
            onSuccess = {
                ToolResult.ok(
                    "Opened the system uninstall screen for $name. The user confirms it. " +
                        "Do not open the app.",
                )
            },
            onFailure = { ToolResult.error(it.message ?: "Could not open the uninstall screen for $name.") },
        )
    }
}
