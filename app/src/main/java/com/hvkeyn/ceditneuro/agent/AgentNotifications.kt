package com.hvkeyn.ceditneuro.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.hvkeyn.ceditneuro.MainActivity
import com.hvkeyn.ceditneuro.R

/** What the status shade shows while the agent is working. */
data class AgentStatus(
    val phase: String,
    val detail: String,
    val focus: String,
    val startedAt: Long,
    val workLine: String,
)

/**
 * Ongoing status for the agent. [AgentService] turns it into a foreground
 * notification so the process keeps running after the app is minimized.
 */
object AgentNotifications {
    const val CHANNEL_ID = "agent"
    const val RESULT_CHANNEL_ID = "agent_result"
    const val NOTIFICATION_ID = 42
    const val RESULT_ID = 43
    const val ACTION_STOP = "com.hvkeyn.ceditneuro.AGENT_STOP"
    const val ACTION_CONTINUE = "com.hvkeyn.ceditneuro.AGENT_CONTINUE"
    const val ACTION_DISMISS = "com.hvkeyn.ceditneuro.AGENT_DISMISS"

    @Volatile
    var latest: AgentStatus? = null

    @Volatile
    var started: Boolean = false

    fun publish(context: Context, status: AgentStatus) {
        latest = status
        ensureChannel(context)
        val app = context.applicationContext
        manager(app).cancel(RESULT_ID)
        if (!started) {
            started = true
            val intent = Intent(app, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } else {
            notify(app)
        }
    }

    /**
     * Leaves a swipeable result in the shade. Continue resumes the task.
     * Stop, or a swipe, clears it. A finished task that needs nothing calls [dismiss].
     */
    fun settle(context: Context, status: AgentStatus) {
        latest = null
        started = false
        val app = context.applicationContext
        ensureChannel(app)
        manager(app).notify(RESULT_ID, buildOutcome(app, status))
        app.stopService(Intent(app, AgentService::class.java))
    }

    fun dismiss(context: Context) {
        latest = null
        started = false
        val app = context.applicationContext
        app.stopService(Intent(app, AgentService::class.java))
        val notifications = manager(app)
        notifications.cancel(NOTIFICATION_ID)
        notifications.cancel(RESULT_ID)
    }

    fun notify(context: Context) {
        if (latest == null) return
        manager(context).notify(NOTIFICATION_ID, build(context))
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val status = latest
        val phase = status?.phase ?: "Working"
        val clock = clock(status?.startedAt ?: System.currentTimeMillis())
        val bars = agentBars(phase)
        val detail = listOfNotNull(
            status?.workLine?.takeIf { it.isNotBlank() },
            status?.detail?.takeIf { it.isNotBlank() },
            status?.focus?.takeIf { it.isNotBlank() },
        ).joinToString("\n")

        val compact = RemoteViews(context.packageName, R.layout.notification_agent_compact)
        compact.setTextViewText(R.id.agent_title, "Agent · $clock")
        compact.setTextViewText(R.id.agent_phase, phase)
        compact.setProgressBar(R.id.agent_progress, 100, bars.overall, false)

        val expanded = RemoteViews(context.packageName, R.layout.notification_agent)
        expanded.setTextViewText(R.id.agent_title, "Agent · $clock")
        expanded.setTextViewText(R.id.agent_phase, phase)
        expanded.setTextViewText(R.id.agent_detail, detail.ifBlank { "Working" })
        expanded.setProgressBar(R.id.agent_progress, 100, bars.overall, false)
        expanded.setProgressBar(R.id.agent_phase_bar, 100, bars.step, bars.stepIndeterminate)

        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle("Agent · $clock")
            .setContentText(phase)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Continue", servicePending(context, ACTION_CONTINUE, 2, foreground = true))
            .addAction(0, "Stop", servicePending(context, ACTION_STOP, 3, foreground = false))
            .build()
    }

    private fun buildOutcome(context: Context, status: AgentStatus): Notification {
        ensureChannel(context)
        val clock = clock(status.startedAt)
        val reason = status.phase.ifBlank { "Stopped" }
        val detail = listOfNotNull(
            status.workLine.takeIf { it.isNotBlank() },
            status.detail.takeIf { it.isNotBlank() },
            status.focus.takeIf { it.isNotBlank() },
        ).joinToString("\n")

        val compact = RemoteViews(context.packageName, R.layout.notification_agent_compact)
        compact.setTextViewText(R.id.agent_title, "Agent stopped · $clock")
        compact.setTextViewText(R.id.agent_phase, reason)
        compact.setProgressBar(R.id.agent_progress, 100, 100, false)

        val expanded = RemoteViews(context.packageName, R.layout.notification_agent)
        expanded.setTextViewText(R.id.agent_title, "Agent stopped · $clock")
        expanded.setTextViewText(R.id.agent_phase, reason)
        expanded.setTextViewText(R.id.agent_detail, detail.ifBlank { "Swipe to clear, or choose Continue or Stop." })
        expanded.setProgressBar(R.id.agent_progress, 100, 100, false)
        expanded.setProgressBar(R.id.agent_phase_bar, 100, 100, false)

        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle("Agent stopped")
            .setContentText(reason)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .addAction(0, "Continue", servicePending(context, ACTION_CONTINUE, 5, foreground = true))
            .addAction(0, "Stop", servicePending(context, ACTION_DISMISS, 6, foreground = false))
            .build()
    }

    private fun clock(startedAt: Long): String {
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(elapsed / 60, elapsed % 60)
    }

    private fun servicePending(
        context: Context,
        action: String,
        request: Int,
        foreground: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AgentService::class.java).setAction(action)
        val flags = pendingFlags()
        return if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, request, intent, flags)
        } else {
            PendingIntent.getService(context, request, intent, flags)
        }
    }

    private fun pendingFlags(): Int {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or mutable
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Shows what the agent is doing while the app is in the background."
        channel.setShowBadge(false)
        val notifications = manager(context)
        notifications.createNotificationChannel(channel)
        val result = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Agent result",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        result.description = "Stays after the agent stops, until you continue, confirm, or swipe it away."
        result.setShowBadge(false)
        notifications.createNotificationChannel(result)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
