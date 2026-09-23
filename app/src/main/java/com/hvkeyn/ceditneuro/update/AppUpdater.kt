package com.hvkeyn.ceditneuro.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdate(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val downloading: Boolean = false,
    val installing: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val received: Long = 0L,
    val total: Long = -1L,
    val error: String? = null,
)

/** Install failures the screen is still around to show. Success relaunches the app instead. */
object UpdateBus {
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val failures = _failures.asSharedFlow()

    fun fail(message: String) {
        _failures.tryEmit(message)
    }
}

/**
 * Looks at the public GitHub releases for this project. A newer APK is downloaded
 * and installed without an extra in-app confirmation. Android may still show its
 * own install prompt when a silent self-update is not allowed.
 */
object AppUpdater {
    private const val RELEASES =
        "https://api.github.com/repos/hvkeyn/CEditNeuro/releases/latest"

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    fun localVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Null when this install is already current, or the release list cannot be read. */
    fun latestNewerThan(localVersion: String): AppUpdate? {
        val request = Request.Builder()
            .url(RELEASES)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CEditNeuro")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isEmpty() || !isNewer(tag, localVersion)) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    if (apkUrl.isNotBlank()) break
                }
            }
            if (apkUrl.isBlank()) return null
            val notes = json.optString("body").trim().take(600)
            return AppUpdate(versionName = tag, notes = notes, apkUrl = apkUrl)
        }
    }

    fun download(context: Context, url: String, onProgress: (Long, Long) -> Unit): File {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CEditNeuro")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Could not download the update (${response.code}).")
            }
            val body = response.body ?: error("The update file was empty.")
            val dest = File(context.cacheDir, "CEditNeuro-update.apk")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var received = 0L
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            lastReport = now
                            onProgress(received, total)
                        }
                    }
                    onProgress(received, total)
                }
            }
            return dest
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Opens the system page where this app is allowed to install updates. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(context: Context, apk: File) {
        try {
            commit(context, apk, requireUserAction = false)
        } catch (error: IllegalArgumentException) {
            commit(context, apk, requireUserAction = true)
        } catch (error: SecurityException) {
            commit(context, apk, requireUserAction = true)
        }
    }

    fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun commit(context: Context, apk: File, requireUserAction: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 33) params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            val action = if (requireUserAction) {
                PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            } else {
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            }
            params.setRequireUserAction(action)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = Intent(context, UpdateResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        val left = parts(remote)
        val right = parts(local)
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val delta = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (delta != 0) return delta > 0
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").split('.', '-', '+').map { piece ->
            piece.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
