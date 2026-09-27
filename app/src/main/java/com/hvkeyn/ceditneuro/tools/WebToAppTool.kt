package com.hvkeyn.ceditneuro.tools

import android.content.Context
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Builds one WebView APK from a page the user named.
 * It does not clone an installed app or change how TLS works.
 */
class WebToAppTool(
    private val context: Context,
    private val workspace: Workspace,
) : Tool {
    override val name = "web_to_app"
    override val description =
        "Build a signed WebView app from a website or a local HTML page and write the APK into the project. " +
            "name is the home-screen label, up to 22 characters. " +
            "Pass url (http or https) or html (a project file or a folder with index.html), not both. " +
            "A url opens live and needs a network. A local page is stored inside the APK and works offline. " +
            "The same name and the same page update that app. A different name installs another app. " +
            "This does not copy an installed app, change TLS, or hide the phone. " +
            "Returns the APK path and package id. Then call install_apk with that path. " +
            "Do not open the new app and tap through it."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Home-screen name, up to 22 characters."),
            "url" to stringProp("http or https page. Omit when html is set."),
            "html" to stringProp("Project file or folder with index.html. Omit when url is set."),
        ),
        required = listOf("name"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val name = args.stringArg("name").orEmpty()
        val url = args.stringArg("url")?.trim().orEmpty()
        val html = args.stringArg("html")?.trim().orEmpty()
        if (url.isNotEmpty() && html.isNotEmpty()) {
            return@withContext ToolResult.error("Pass url or html, not both.")
        }
        if (url.isEmpty() && html.isEmpty()) {
            return@withContext ToolResult.error("Pass url or html.")
        }
        val built = runCatching {
            val template = context.assets.open("web-shell.apk").use { it.readBytes() }
            val start: String
            val source: String
            val site: List<Pair<String, ByteArray>>
            if (url.isNotEmpty()) {
                start = WebAppPack.validateUrl(url)
                source = "url:$start"
                site = emptyList()
            } else {
                val file = workspace.resolve(html)
                val root = workspace.root.canonicalFile
                val canonical = file.canonicalFile
                val inside = canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
                if (!inside) throw IllegalArgumentException("html must stay inside the project.")
                site = WebAppPack.collectSite(canonical)
                start = "file:///android_asset/www/index.html"
                source = "html:" + workspace.relativize(canonical)
            }
            val id = WebAppPack.packageId(name, source)
            val output = workspace.resolve("dist/web-$id.apk")
            WebAppPack.pack(
                template = template,
                name = name,
                source = source,
                startUrl = start,
                site = site,
                identityDir = File(context.filesDir, "webapp-sign"),
                output = output,
            )
        }.getOrElse { error ->
            return@withContext ToolResult.error(error.message ?: "Could not build the app.")
        }
        val relative = workspace.relativize(built.file)
        val launcher = if (built.launcherLabel == built.label) {
            ""
        } else {
            " Home screen name: ${built.launcherLabel}."
        }
        ToolResult.ok(
            "Built ${built.packageId}. Label: ${built.label}.$launcher " +
                "File: ${built.file.absolutePath} (${built.file.length()} bytes). " +
                "Call install_apk with path $relative. " +
                "Ask the user to confirm the installer. Do not open the new app.",
        )
    }
}
