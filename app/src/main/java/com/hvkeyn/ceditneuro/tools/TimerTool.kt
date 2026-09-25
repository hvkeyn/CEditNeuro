package com.hvkeyn.ceditneuro.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hvkeyn.ceditneuro.agent.TimerReceiver
import kotlinx.serialization.json.JsonObject

/** Posts this app's notification now, or after a delay. Does not touch another app's alarms. */
class TimerTool(private val context: Context) : Tool {
    override val name = "set_timer"
    override val description =
        "Show a notification on this phone, now or after delay_seconds. " +
            "Use this for an alert, a timer, or an alarm. delay_seconds 0 shows it immediately. " +
            "This is this app's own notification. Do not use the shell or another app."
    override val parameters = objectSchema(
        properties = mapOf(
            "delay_seconds" to intProp("Wait this many seconds. 0 shows the notification now. Maximum is 14 days."),
            "title" to stringProp("Short title on the notification."),
            "text" to stringProp("The message under the title."),
        ),
        required = listOf("title"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val delay = args.intArg("delay_seconds") ?: 0
        if (delay !in 0..(14 * 24 * 60 * 60)) {
            return ToolResult.error("delay_seconds must be from 0 to 14 days.")
        }
        val title = args.stringArg("title")?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult.error("title is required.")
        val text = args.stringArg("text")?.trim().orEmpty().ifBlank { "Time is up." }
        val id = ((System.currentTimeMillis() / 1000L) % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        val app = context.applicationContext
        if (delay == 0) {
            TimerReceiver().onReceive(
                app,
                Intent(app, TimerReceiver::class.java)
                    .putExtra(TimerReceiver.EXTRA_TITLE, title)
                    .putExtra(TimerReceiver.EXTRA_TEXT, text)
                    .putExtra(TimerReceiver.EXTRA_ID, id),
            )
            return ToolResult.ok("Notification shown: $title")
        }
        val trigger = System.currentTimeMillis() + delay * 1000L
        val pending = PendingIntent.getBroadcast(
            app,
            id,
            Intent(app, TimerReceiver::class.java)
                .putExtra(TimerReceiver.EXTRA_TITLE, title)
                .putExtra(TimerReceiver.EXTRA_TEXT, text)
                .putExtra(TimerReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            return ToolResult.ok("Alarm set in $delay seconds: $title")
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        return ToolResult.ok(
            "Timer set in about $delay seconds: $title. Exact alarms are off, so it may arrive a little late.",
        )
    }
}
