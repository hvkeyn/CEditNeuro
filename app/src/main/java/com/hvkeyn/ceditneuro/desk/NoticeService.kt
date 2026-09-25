package com.hvkeyn.ceditneuro.desk

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Notifications the user allowed this app to see. Messengers and mail apps appear here. */
class NoticeService : NotificationListenerService() {

    override fun onListenerConnected() {
        live = this
    }

    override fun onListenerDisconnected() {
        if (live === this) live = null
    }

    fun snapshot(): List<Notice> {
        val mine = packageName
        return activeNotifications.orEmpty()
            .filter { it.packageName != mine }
            .takeLast(40)
            .map { posted ->
                val extras = posted.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                Notice(
                    key = posted.key,
                    app = posted.packageName.substringAfterLast('.'),
                    title = title.take(120),
                    text = text.take(400),
                    canReply = replyAction(posted) != null,
                )
            }
    }

    fun dismiss(key: String) {
        cancelNotification(key)
    }

    fun reply(key: String, message: String): Boolean {
        val posted = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        val action = replyAction(posted) ?: return false
        val intent = Intent()
        val results = Bundle()
        results.putCharSequence(action.remoteInputs.first().resultKey, message)
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        action.actionIntent.send(this, 0, intent)
        return true
    }

    private fun replyAction(posted: StatusBarNotification): Notification.Action? =
        posted.notification.actions.orEmpty().firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    data class Notice(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val canReply: Boolean,
    )

    companion object {
        @Volatile
        var live: NoticeService? = null
    }
}
