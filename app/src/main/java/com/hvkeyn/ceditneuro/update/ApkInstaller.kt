package com.hvkeyn.ceditneuro.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File

/** Installs an APK the agent built, through the system installer. No Shizuku. */
object ApkInstaller {
    const val ACTION_INSTALL_APK = "com.hvkeyn.ceditneuro.INSTALL_APK"
    const val EXTRA_PACKAGE = "apk_package"

    data class Parsed(val file: File, val packageName: String)

    fun parse(context: Context, file: File): Parsed? {
        if (!file.isFile || file.length() <= 0L) return null
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        val name = info.packageName ?: return null
        return Parsed(file, name)
    }

    fun install(context: Context, file: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 33) params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("package.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = Intent(context, UpdateResultReceiver::class.java)
                .setAction(ACTION_INSTALL_APK)
                .setData(Uri.parse("package:$packageName"))
                .putExtra(EXTRA_PACKAGE, packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(pending.intentSender)
        }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
