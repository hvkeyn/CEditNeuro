package com.hvkeyn.ceditneuro.background

import android.content.Context
import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts the shell-side virtual display prototype. It is only used after the person
 * enabled Shizuku/root and explicitly asked for background control.
 */
object AgentDisplayController {
    @Volatile
    var displayId: Int? = null
        private set

    private const val ID_FILE = "/data/local/tmp/ceditneuro-agent-display.id"
    private const val LOG_FILE = "/data/local/tmp/ceditneuro-agent-display.log"
    private const val FRAME_FILE = "/data/local/tmp/ceditneuro-agent-frame.png"
    private const val MAIN = "com.hvkeyn.ceditneuro.background.AgentDisplayMain"
    private val queue = ArrayDeque<String>()

    suspend fun start(
        context: Context,
        runner: ShizukuCommandRunner,
        prepare: suspend () -> String?,
    ): Result<Int> = withContext(Dispatchers.IO) {
        prepare()?.let { return@withContext Result.failure(IllegalStateException(it)) }
        val apk = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        }.getOrElse { return@withContext Result.failure(it) }
        val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
        val quotedMain = "'" + MAIN + "'"
        val command = "rm -f $ID_FILE $LOG_FILE; " +
            "CLASSPATH=$quotedApk nohup /system/bin/app_process /system/bin $quotedMain 1080 2400 420 " +
            ">$LOG_FILE 2>&1 &"
        val start = runCatching { runner.executeShizukuCommand(command, timeoutSeconds = 20) }
            .getOrElse { return@withContext Result.failure(it) }
        if (start.startsWith("timed out")) return@withContext Result.failure(IllegalStateException(start))
        repeat(20) {
            delay(300)
            val raw = runCatching { runner.executeShizukuCommand("cat $ID_FILE 2>/dev/null", timeoutSeconds = 8) }
                .getOrElse { "" }
            val value = raw.substringAfter('\n', "").trim().toIntOrNull()
            if (value != null && value > 0) {
                displayId = value
                return@withContext Result.success(value)
            }
            if (value != null && value < 0) {
                val log = runCatching { runner.executeShizukuCommand("cat $LOG_FILE 2>/dev/null", timeoutSeconds = 8) }
                    .getOrElse { "" }
                return@withContext Result.failure(IllegalStateException("Display helper failed. ${log.take(600)}"))
            }
        }
        val log = runCatching { runner.executeShizukuCommand("cat $LOG_FILE 2>/dev/null", timeoutSeconds = 8) }
            .getOrElse { "" }
        Result.failure(IllegalStateException("Display helper did not report an id. ${log.take(600)}"))
    }

    suspend fun launch(
        runner: ShizukuCommandRunner,
        prepare: suspend () -> String?,
        packageName: String,
    ): String? = withContext(Dispatchers.IO) {
        prepare()?.let { return@withContext it }
        val id = displayId ?: return@withContext "Start background display first."
        if (!packageName.matches(Regex("^[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+$"))) {
            return@withContext "Invalid package id."
        }
        val command = "am start --display $id --user 0 -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -p $packageName"
        val raw = runCatching { runner.executeShizukuCommand(command, timeoutSeconds = 25) }
            .getOrElse { return@withContext (it.message ?: "Launch failed.") }
        if (raw.startsWith("timed out") || raw.contains("Error:")) raw.take(600) else null
    }

    suspend fun captureFrame(
        context: Context,
        runner: ShizukuCommandRunner,
        prepare: suspend () -> String?,
    ): File? = withContext(Dispatchers.IO) {
        prepare()?.let { return@withContext null }
        if (displayId == null) return@withContext null
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(dir, "ceditneuro-agent-frame.png")
        target.delete()
        val quoted = "'" + target.absolutePath.replace("'", "'\\''") + "'"
        val raw = runCatching {
            runner.executeShizukuCommand("cp $FRAME_FILE $quoted", timeoutSeconds = 15)
        }.getOrNull().orEmpty()
        if (raw.startsWith("timed out") || raw.contains("No such file")) return@withContext null
        target.takeIf { it.isFile && it.length() > 0L }
    }

    fun enqueue(packageName: String): Int {
        synchronized(queue) {
            if (!queue.contains(packageName)) queue.addLast(packageName)
            return queue.size
        }
    }

    fun queued(): List<String> = synchronized(queue) { queue.toList() }

    suspend fun next(
        runner: ShizukuCommandRunner,
        prepare: suspend () -> String?,
    ): String? {
        val packageName = synchronized(queue) { queue.removeFirstOrNull() }
            ?: return "The queue is empty."
        return launch(runner, prepare, packageName)?.let { "Failed $packageName: $it" }
            ?: "Launched $packageName on display ${displayId}. Queue left: ${queued().size}."
    }

    suspend fun mainScreenSpill(
        runner: ShizukuCommandRunner,
        prepare: suspend () -> String?,
        packageName: String,
    ): String? = withContext(Dispatchers.IO) {
        prepare()?.let { return@withContext it }
        if (packageName.isBlank()) return@withContext null
        val raw = runCatching {
            runner.executeShizukuCommand("dumpsys activity activities", timeoutSeconds = 15)
        }.getOrNull().orEmpty()
        val main = raw.substringAfter("Display #0", "")
            .substringBefore("\nDisplay #", "")
        if (Regex("packageName=" + Regex.escape(packageName) + "\\b").containsMatchIn(main)) {
            "$packageName is on the main display, not the background display. Stop and hand it back to the person."
        } else null
    }

    suspend fun stop(runner: ShizukuCommandRunner) = withContext(Dispatchers.IO) {
        runCatching {
            runner.executeShizukuCommand(
                "pkill -f '$MAIN' 2>/dev/null; rm -f $ID_FILE $LOG_FILE $FRAME_FILE",
                timeoutSeconds = 8,
            )
        }
        displayId = null
        synchronized(queue) { queue.clear() }
    }

    fun clear() {
        displayId = null
    }

    fun attach(id: Int) {
        if (id > 0) displayId = id
    }

    fun logPath(): File = File(LOG_FILE)
}
