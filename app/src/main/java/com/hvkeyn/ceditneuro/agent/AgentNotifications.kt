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

/** What the status shade shows while one project's agent is working. */
data class AgentStatus(
    val key: String,
    val name: String,
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
    const val ACTION_REFRESH = "com.hvkeyn.ceditneuro.AGENT_REFRESH"
    const val EXTRA_PROJECT = "agent_project"
    const val EXTRA_RETIRE = "retire_id"

    private val running = LinkedHashMap<String, AgentStatus>()
    private val assignedIds = HashMap<String, Int>()
    private var nextNotificationId = 1_000

    @Volatile
    var foregroundKey: String? = null

    @Volatile
    var started: Boolean = false

    fun hasRunning(): Boolean = synchronized(running) { running.isNotEmpty() }

    fun foregroundStatus(): AgentStatus? = synchronized(running) {
        foregroundKey?.let { running[it] } ?: running.values.firstOrNull()
    }

    fun foregroundNotificationId(): Int? = foregroundStatus()?.key?.let { notificationId(it) }

    fun publish(context: Context, status: AgentStatus) {
        ensureChannel(context)
        val app = context.applicationContext
        val startService = synchronized(running) {
            running[status.key] = status
            manager(app).cancel(resultId(status.key))
            if (foregroundKey == null) foregroundKey = status.key
            !started
        }
        if (startService) {
            started = true
            val intent = Intent(app, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } else {
            refresh(app)
        }
    }

    /**
     * Leaves a swipeable result in the shade. Continue resumes the task.
     * Stop, or a swipe, clears it. A finished task that needs nothing calls [dismiss].
     */
    fun settle(context: Context, status: AgentStatus) {
        val app = context.applicationContext
        ensureChannel(app)
        val retire = retireRunning(status.key)
        manager(app).notify(resultId(status.key), buildOutcome(app, status))
        handoff(app, retire)
    }

    fun dismiss(context: Context, key: String) {
        val app = context.applicationContext
        val retire = retireRunning(key)
        manager(app).cancel(resultId(key))
        handoff(app, retire)
    }

    fun clearResult(context: Context, key: String) {
        manager(context).cancel(resultId(key))
    }

    fun refresh(context: Context) {
        val snapshot = synchronized(running) { running.values.toList() }
        val notifications = manager(context)
        snapshot.forEach { status ->
            notifications.notify(notificationId(status.key), build(context, status))
        }
    }

    fun build(context: Context): Notification {
        val status = foregroundStatus()
        return if (status == null) {
            build(
                context,
                AgentStatus("foreground", "Agent", "Working", "", "", System.currentTimeMillis(), ""),
            )
        } else {
            build(context, status)
        }
    }

    private fun build(context: Context, status: AgentStatus): Notification {
        ensureChannel(context)
        val phase = status.phase.ifBlank { "Working" }
        val clock = clock(status.startedAt)
        val bars = agentBars(phase)
        val detail = listOfNotNull(
            status.workLine.takeIf { it.isNotBlank() },
            status.detail.takeIf { it.isNotBlank() },
            status.focus.takeIf { it.isNotBlank() },
        ).joinToString("\n")

        val title = "${status.name} · $clock"
        val compact = RemoteViews(context.packageName, R.layout.notification_agent_compact)
        compact.setTextViewText(R.id.agent_title, title)
        compact.setTextViewText(R.id.agent_phase, phase)
        compact.setProgressBar(R.id.agent_progress, 100, bars.overall, false)

        val expanded = RemoteViews(context.packageName, R.layout.notification_agent)
        expanded.setTextViewText(R.id.agent_title, title)
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
            .setContentTitle(title)
            .setContentText(phase)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Continue", servicePending(context, ACTION_CONTINUE, status.key, foreground = true))
            .addAction(0, "Stop", servicePending(context, ACTION_STOP, status.key, foreground = false))
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

        val title = "${status.name} stopped · $clock"
        val compact = RemoteViews(context.packageName, R.layout.notification_agent_compact)
        compact.setTextViewText(R.id.agent_title, title)
        compact.setTextViewText(R.id.agent_phase, reason)
        compact.setProgressBar(R.id.agent_progress, 100, 100, false)

        val expanded = RemoteViews(context.packageName, R.layout.notification_agent)
        expanded.setTextViewText(R.id.agent_title, title)
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
            .setContentTitle(title)
            .setContentText(reason)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .addAction(0, "Continue", servicePending(context, ACTION_CONTINUE, status.key, foreground = true))
            .addAction(0, "Stop", servicePending(context, ACTION_DISMISS, status.key, foreground = false))
            .build()
    }

    private fun notificationId(key: String): Int = synchronized(assignedIds) {
        assignedIds.getOrPut(key) { nextNotificationId++ }
    }

    private fun resultId(key: String): Int = notificationId(key) + 10_000

    private fun retireRunning(key: String): Int? {
        val id = synchronized(running) {
            if (running.remove(key) == null && foregroundKey != key) return null
            val removedId = notificationId(key)
            if (foregroundKey == key) foregroundKey = running.keys.firstOrNull()
            removedId
        }
        return id
    }

    private fun handoff(app: Context, retire: Int?) {
        val empty = synchronized(running) { running.isEmpty() }
        if (empty) {
            foregroundKey = null
            started = false
            if (retire != null) manager(app).cancel(retire)
            manager(app).cancel(NOTIFICATION_ID)
            app.stopService(Intent(app, AgentService::class.java))
            return
        }
        val current = foregroundNotificationId()
        if (retire != null && retire != current) {
            manager(app).cancel(retire)
            refresh(app)
            return
        }
        val intent = Intent(app, AgentService::class.java).setAction(ACTION_REFRESH)
        if (retire != null) intent.putExtra(EXTRA_RETIRE, retire)
        app.startService(intent)
    }

    private fun clock(startedAt: Long): String {
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(elapsed / 60, elapsed % 60)
    }

    private fun servicePending(
        context: Context,
        action: String,
        projectKey: String,
        foreground: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AgentService::class.java)
            .setAction(action)
            .putExtra(EXTRA_PROJECT, projectKey)
        val request = (action.hashCode() * 31 + projectKey.hashCode()) and 0x7fffffff
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
