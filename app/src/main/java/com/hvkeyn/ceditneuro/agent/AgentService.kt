package com.hvkeyn.ceditneuro.agent

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.hvkeyn.ceditneuro.CEditNeuroApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while any project agent is running, so
 * leaving the app does not stop those tasks. Each project has its own shade card.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                if (AgentNotifications.hasRunning()) {
                    AgentNotifications.refresh(this@AgentService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as CEditNeuroApp).workspaceModel
        when (intent?.action) {
            AgentNotifications.ACTION_STOP -> {
                val existing = AgentNotifications.foregroundNotificationId()
                    ?: AgentNotifications.NOTIFICATION_ID
                startInForeground(AgentNotifications.build(this), existing)
                model.cancelAgent(intent.getStringExtra(AgentNotifications.EXTRA_PROJECT))
                return bindOrStop()
            }
            AgentNotifications.ACTION_DISMISS -> {
                val key = intent.getStringExtra(AgentNotifications.EXTRA_PROJECT)
                if (!key.isNullOrBlank()) AgentNotifications.clearResult(this, key)
                return bindOrStop()
            }
            AgentNotifications.ACTION_CONTINUE -> {
                model.continueAgent(intent.getStringExtra(AgentNotifications.EXTRA_PROJECT))
                return bindOrStop()
            }
            AgentNotifications.ACTION_REFRESH -> {
                val result = bindOrStop()
                val current = AgentNotifications.foregroundNotificationId()
                val retire = intent.getIntExtra(AgentNotifications.EXTRA_RETIRE, -1)
                if (retire >= 0 && retire != current) {
                    notifications().cancel(retire)
                }
                return result
            }
        }
        return bindOrStop()
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        val restart = AgentNotifications.hasRunning()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (restart) AgentNotifications.started = false
        super.onDestroy()
        if (restart) {
            val again = Intent(this, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(again)
            } else {
                startService(again)
            }
        }
    }

    private fun bindOrStop(): Int {
        val id = AgentNotifications.foregroundNotificationId()
        if (id == null) {
            if (AgentNotifications.started) {
                startInForeground(AgentNotifications.build(this), AgentNotifications.NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                AgentNotifications.started = false
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(AgentNotifications.build(this), id)
        AgentNotifications.started = true
        AgentNotifications.refresh(this)
        return START_STICKY
    }

    private fun startInForeground(notification: android.app.Notification, id: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val started = runCatching {
                startForeground(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
            if (started.isSuccess) return
        }
        startForeground(id, notification)
    }

    private fun notifications(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
}
