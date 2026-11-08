package com.hvkeyn.ceditneuro.tools

import android.content.Context
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.nio.file.Files
import java.util.Locale

/**
 * Downloads the official MNN-Pocket GGUF and a llama.cpp Android server, then
 * starts it on 127.0.0.1:8091. The app's bundled provider is keyless and local.
 * No model bytes leave the phone.
 */
class PocketModelTool(private val context: Context) : Tool {
    override val name = "pocket_model"
    override val description =
        "Install, update, start, or stop the local MNN-Pocket-0.3B model. install downloads the official " +
            "Q4_1 GGUF (192 MB) and the llama.cpp arm64 server (78 MB) into this app, starts the server at " +
            "http://127.0.0.1:8091/v1, and prints the exact paths. update checks the Hugging Face commit. " +
            "Requires Android arm64, network for install/update, and about 600 MB free. The model is Apache-2.0; " +
            "the runtime is MIT. Do not upload a private prompt or model."

    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("status, install, update, start, or stop."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "pocket").apply { mkdirs() }
        val server = File(dir, "llama-server")
        val model = File(dir, "model.gguf")
        val marker = File(dir, "source.txt")
        when (args.stringArg("action")?.trim()?.lowercase(Locale.US)) {
            "status" -> ToolResult.ok(
                "server=${if (server.canExecute()) "installed" else "missing"} model=" +
                    "${if (model.isFile) "${model.length() / 1024 / 1024} MB" else "missing"} " +
                    "running=${isRunning()} url=http://127.0.0.1:8091/v1",
            )
            "install" -> {
                if (!server.canExecute()) {
                    download(RUNTIME_URL, File(dir, "runtime.tar.gz"))
                    extractTarGz(File(dir, "runtime.tar.gz"), dir)
                    File(dir, "runtime.tar.gz").delete()
                    File(dir, "llama-server").setExecutable(true, true)
                }
                if (!model.isFile || model.length() < 190_000_000L) download(MODEL_URL, model)
                marker.writeText(MODEL_COMMIT, Charsets.UTF_8)
                ToolResult.ok("Installed local MNN-Pocket. Start with pocket_model action=start. Base URL: http://127.0.0.1:8091/v1")
            }
            "update" -> {
                val latest = fetchText(API_URL).let { body ->
                    Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{7,40})\"").find(body)?.groupValues?.get(1).orEmpty()
                }
                val old = if (marker.isFile) marker.readText().trim() else ""
                if (latest.isNotBlank() && latest != old) {
                    download(MODEL_URL, model)
                    marker.writeText(latest, Charsets.UTF_8)
                    ToolResult.ok("Updated MNN-Pocket to commit $latest.")
                } else ToolResult.ok("MNN-Pocket is current (${old.ifBlank { "unknown" }}).")
            }
            "start" -> {
                if (!server.canExecute() || !model.isFile) return@withContext ToolResult.error("Install first: pocket_model action=install.")
                if (isRunning()) return@withContext ToolResult.ok("Already running: http://127.0.0.1:8091/v1")
                val log = File(dir, "server.log")
                val process = ProcessBuilder(
                    server.absolutePath, "-m", model.absolutePath,
                    "--host", "127.0.0.1", "--port", "8091", "-c", "2048", "-t", "4",
                ).directory(dir).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).apply {
                        environment()["LD_LIBRARY_PATH"] = dir.absolutePath
                    }.start()
                val ready = (1..40).any { runCatching { Socket("127.0.0.1", 8091).use { true } }.getOrDefault(false).also { if (!it) Thread.sleep(500) } }
                if (!ready) return@withContext ToolResult.error("Server did not open port 8091; see ${dir.absolutePath}/server.log")
                ToolResult.ok("Local MNN-Pocket running at http://127.0.0.1:8091/v1 (model mnn-pocket).")
            }
            "stop" -> {
                Runtime.getRuntime().exec(arrayOf("pkill", "-f", "llama-server -m ${model.absolutePath}")).waitFor()
                ToolResult.ok("Local MNN-Pocket stopped.")
            }
            else -> ToolResult.error("action is status, install, update, start, or stop.")
        }
    }

    private fun isRunning(): Boolean = runCatching { Socket("127.0.0.1", 8091).use { true } }.getOrDefault(false)

    private fun download(url: String, target: File) {
        val tmp = File(target.absolutePath + ".part")
        var connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "CEditNeuro")
        connection.connect()
        val finalUrl = connection.url.toString()
        if (connection.responseCode !in 200..299) {
            val second = URL(finalUrl).openConnection() as HttpURLConnection
            second.instanceFollowRedirects = true
            second.connect()
            connection = second
        }
        connection.inputStream.use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        if (tmp.length() < 1_000_000L) throw IllegalStateException("Download was empty: $finalUrl")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("Could not save ${target.name}")
    }

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "CEditNeuro")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun extractTarGz(archive: File, dir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(dir, entry.name).canonicalFile
                if (!target.path.startsWith(dir.canonicalPath + File.separator)) throw IllegalStateException("Unsafe archive path")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                    if (target.name == "llama-server" || target.extension == "so") target.setExecutable(true, true)
                }
            }
        }
    }

    private companion object {
        const val MODEL_COMMIT = "823b0034d7ffe900b71706ce105a16fcbe41426e"
        const val RUNTIME_URL = "https://github.com/ggml-org/llama.cpp/releases/download/b12207/llama-b12207-bin-android-arm64.tar.gz"
        const val MODEL_URL = "https://huggingface.co/taobao-mnn/MNN-Pocket-0.3B-GGUF/resolve/main/MNN-Pocket-0.3B-Instruct-Q4_1.gguf"
        const val API_URL = "https://huggingface.co/api/models/taobao-mnn/MNN-Pocket-0.3B-GGUF"
    }
}
