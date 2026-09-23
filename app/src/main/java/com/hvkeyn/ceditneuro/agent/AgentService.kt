package com.hvkeyn.ceditneuro.agent

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
 * Keeps the process in the foreground while the agent is running, so leaving
 * the app does not stop the current task.
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
                if (AgentNotifications.latest != null) {
                    AgentNotifications.notify(this@AgentService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as CEditNeuroApp).workspaceModel
        when (intent?.action) {
            AgentNotifications.ACTION_STOP -> {
                startInForeground(AgentNotifications.build(this))
                model.cancelAgent()
                return START_NOT_STICKY
            }
            AgentNotifications.ACTION_DISMISS -> {
                AgentNotifications.dismiss(this)
                return START_NOT_STICKY
            }
            AgentNotifications.ACTION_CONTINUE -> {
                startInForeground(AgentNotifications.build(this))
                model.continueAgent()
                return START_NOT_STICKY
            }
        }
        if (AgentNotifications.latest == null) {
            startInForeground(AgentNotifications.build(this))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(AgentNotifications.build(this))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val started = runCatching {
                startForeground(
                    AgentNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
            if (started.isSuccess) return
        }
        startForeground(AgentNotifications.NOTIFICATION_ID, notification)
    }
}
