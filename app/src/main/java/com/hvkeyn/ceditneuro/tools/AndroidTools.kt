package com.hvkeyn.ceditneuro.tools

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.hvkeyn.ceditneuro.shizuku.SuShell
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import rikka.shizuku.Shizuku

/** What this phone is and which shell modes the agent can use, in one short answer. */
class DeviceStatusTool(private val context: Context) : Tool {
    override val name = "device_status"
    override val description =
        "Report this phone: model, Android version, battery, free storage, memory, uptime, " +
            "and whether Shizuku or root is available for shizuku_exec. Call it once before choosing a shell mode."
    override val parameters = objectSchema(emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (Build.VERSION.SDK_INT >= 23) battery.isCharging else false
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val shizuku = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val root = runCatching { SuShell.available() }.getOrDefault(false)
        val mode = when {
            shizuku -> "shizuku_exec runs as the shell user through Shizuku."
            root -> "shizuku_exec runs through su as root."
            else -> "No Shizuku and no root. Use run_command and the app's own tools."
        }
        ToolResult.ok(
            buildString {
                append("model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("abi: ").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).append('\n')
                append("battery: ").append(level).append("%").append(if (charging) ", charging" else "").append('\n')
                if (stat != null) {
                    append("storage free: ").append(gib(stat.availableBytes)).append(" of ").append(gib(stat.totalBytes)).append('\n')
                }
                append("memory free: ").append(gib(memory.availMem)).append(" of ").append(gib(memory.totalMem)).append('\n')
                append("uptime: ").append(SystemClock.elapsedRealtime() / 3_600_000).append(" h\n")
                append("shizuku: ").append(shizuku).append(", root: ").append(root).append('\n')
                append(mode)
            },
        )
    }

    private fun gib(bytes: Long) = "%.1f GB".format(java.util.Locale.ROOT, bytes / 1_073_741_824.0)
}

class ListAppsTool(private val context: Context) : Tool {
    override val name = "list_apps"
    override val description =
        "List installed apps with their package names. Filters by a word in the label or package. " +
            "System apps are skipped unless system is true. It shows names only, not app data."
    override val parameters = objectSchema(
        properties = mapOf(
            "query" to stringProp("Optional word to match."),
            "system" to boolProp("Include system apps. Default false."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")?.trim()?.lowercase().orEmpty()
        val system = args.boolArg("system") ?: false
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
            .filter { system || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { info -> pm.getApplicationLabel(info).toString() to info.packageName }
            .filter { (label, pkg) -> query.isEmpty() || label.lowercase().contains(query) || pkg.contains(query) }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return@withContext ToolResult.ok("No installed app matched.")
        val shown = apps.take(80).joinToString("\n") { (label, pkg) -> "$label  $pkg" }
        ToolResult.ok(if (apps.size > 80) "$shown\n… ${apps.size - 80} more. Narrow the query." else shown)
    }
}

class OpenSettingsTool(private val context: Context) : Tool {
    override val name = "open_settings"
    override val description =
        "Open one Android settings screen for the user when they ask to change a setting. " +
            "screen: wifi, bluetooth, battery, display, sound, location, storage, developer, accessibility, " +
            "notifications, app (needs package), date, language, vpn. The user changes it; do not tap through it. " +
            "Not for install or uninstall."
    override val parameters = objectSchema(
        properties = mapOf(
            "screen" to stringProp("Which screen."),
            "package" to stringProp("Application id for screen app."),
        ),
        required = listOf("screen"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val screen = args.stringArg("screen")?.trim()?.lowercase().orEmpty()
        val action = when (screen) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> return@withContext ToolResult.error("Unknown screen '$screen'.")
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (screen == "app") {
            val pkg = args.stringArg("package")?.trim().orEmpty()
            if (!Regex("^[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+$").matches(pkg)) {
                return@withContext ToolResult.error("screen app needs a valid package.")
            }
            intent.data = Uri.parse("package:$pkg")
        }
        runCatching { context.startActivity(intent) }.fold(
            onSuccess = { ToolResult.ok("Opened $screen settings. The user makes the change.") },
            onFailure = { ToolResult.error("This phone has no $screen settings screen. Do not try it again.") },
        )
    }
}

class ClipboardTool(private val context: Context) : Tool {
    override val name = "clipboard"
    override val description =
        "Read or set the clipboard. action read returns the text while this app is in front. " +
            "action write puts text there. Never put a password in it."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("read or write."),
            "text" to stringProp("Text for write."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (args.stringArg("action")?.trim()?.lowercase()) {
            "read" -> {
                val text = clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrEmpty()) {
                    ToolResult.ok("The clipboard is empty, or Android hid it because this app is in the background.")
                } else {
                    ToolResult.ok(text.take(4_000))
                }
            }
            "write" -> {
                val text = args.stringArg("text").orEmpty()
                if (text.isEmpty()) return@withContext ToolResult.error("text is required for write.")
                clip.setPrimaryClip(ClipData.newPlainText("CEditNeuro", text))
                ToolResult.ok("Copied ${text.length} characters.")
            }
            else -> ToolResult.error("action is read or write.")
        }
    }
}

class OpenFileTool(private val context: Context, private val workspace: Workspace) : Tool {
    override val name = "open_file"
    override val description =
        "Show a file or a web page to the user in the phone's own viewer, or share it. " +
            "path is project-relative or absolute. url opens a web page. share true opens the share sheet. " +
            "Use this for a PDF, an image, or a report the user should see."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("File to show."),
            "url" to stringProp("http(s) page to open instead of a file."),
            "share" to boolProp("Open the share sheet instead of a viewer."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val url = args.stringArg("url")?.trim().orEmpty()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext ToolResult.error("url must start with http:// or https://.")
            }
            return@withContext launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url.")
        }
        val path = args.stringArg("path")?.trim().orEmpty()
        if (path.isEmpty()) return@withContext ToolResult.error("path or url is required.")
        val file = runCatching { workspace.resolve(path) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad path.")
        }
        if (!file.isFile) return@withContext ToolResult.error("No file at ${file.absolutePath}.")
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }.getOrElse { return@withContext ToolResult.error("That folder cannot be shared: ${file.absolutePath}.") }
        val type = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        val share = args.boolArg("share") ?: false
        val intent = if (share) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                file.name,
            )
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent, "Opened ${file.absolutePath} (${file.length()} bytes).")
    }

    private fun launch(intent: Intent, done: String): ToolResult =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.fold(
            onSuccess = { ToolResult.ok(done) },
            onFailure = { ToolResult.error("No app on this phone can open that. Do not try again.") },
        )
}
