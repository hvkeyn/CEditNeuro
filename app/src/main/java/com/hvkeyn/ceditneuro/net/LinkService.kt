package com.hvkeyn.ceditneuro.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hvkeyn.ceditneuro.CEditNeuroApp
import com.hvkeyn.ceditneuro.MainActivity

/**
 * Stays in the shade while a phone link is on, so minimizing the app does not
 * drop the beacon. It ends only when the user disconnects.
 */
class LinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra(EXTRA_CODE).orEmpty()
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
        if (intent?.action == ACTION_STOP) {
            (application as CEditNeuroApp).workspaceModel.disconnectLink()
            return START_NOT_STICKY
        }
        val note = build(this, code, detail)
        if (Build.VERSION.SDK_INT >= 29) {
            val started = runCatching {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            if (started.isFailure) startForeground(ID, note)
        } else {
            startForeground(ID, note)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.hvkeyn.ceditneuro.LINK_STOP"
        const val EXTRA_CODE = "link_code"
        const val EXTRA_DETAIL = "link_detail"
        private const val CHANNEL = "link"
        private const val ID = 78

        fun start(context: Context, code: String, detail: String) {
            val app = context.applicationContext
            val intent = Intent(app, LinkService::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DETAIL, detail)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun update(context: Context, code: String, detail: String) {
            val app = context.applicationContext
            val manager = app.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ID, build(app, code, detail))
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, LinkService::class.java))
        }

        private fun build(context: Context, code: String, detail: String): Notification {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL, "Phone link", NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, LinkService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = detail.ifBlank { "Code $code. Stays on until you disconnect." }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Linked · $code")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Disconnect", stop)
                .build()
        }
    }
}
