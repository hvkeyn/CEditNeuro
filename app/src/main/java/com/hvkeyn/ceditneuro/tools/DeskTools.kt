package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.desk.Mailbox
import com.hvkeyn.ceditneuro.desk.NoticeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class NotificationsTool : Tool {
    override val name = "notifications"
    override val description =
        "List notifications this phone is showing, including mail and messengers, after the user allows notification access. " +
            "Each row has a key. Use notification_reply or notification_dismiss with that key."
    override val parameters = objectSchema(properties = emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = NoticeService.live
            ?: return ToolResult.error("Notification access is off. The user turns it on in Settings, Desk.")
        val rows = service.snapshot()
        if (rows.isEmpty()) return ToolResult.ok("No notifications.")
        val text = rows.joinToString("\n") { row ->
            val reply = if (row.canReply) " reply" else ""
            "${row.key}\t${row.app}$reply\t${row.title} — ${row.text}"
        }
        return ToolResult.ok(text)
    }
}

class NotificationReplyTool : Tool {
    override val name = "notification_reply"
    override val description =
        "Reply to a messenger or mail notification that offers a reply. key comes from notifications."
    override val parameters = objectSchema(
        properties = mapOf(
            "key" to stringProp("The key from notifications."),
            "text" to stringProp("The reply to send."),
        ),
        required = listOf("key", "text"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = NoticeService.live
            ?: return ToolResult.error("Notification access is off. The user turns it on in Settings, Desk.")
        val key = args.stringArg("key") ?: return ToolResult.error("Missing 'key'.")
        val text = args.stringArg("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("text is required.")
        val sent = runCatching { service.reply(key, text) }.getOrDefault(false)
        return if (sent) ToolResult.ok("Reply sent.") else ToolResult.error("That notification has no reply.")
    }
}

class NotificationDismissTool : Tool {
    override val name = "notification_dismiss"
    override val description = "Clear one notification. key comes from notifications."
    override val parameters = objectSchema(
        properties = mapOf("key" to stringProp("The key from notifications.")),
        required = listOf("key"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = NoticeService.live
            ?: return ToolResult.error("Notification access is off. The user turns it on in Settings, Desk.")
        val key = args.stringArg("key") ?: return ToolResult.error("Missing 'key'.")
        service.dismiss(key)
        return ToolResult.ok("Notification cleared.")
    }
}

class MailListTool(private val settings: () -> AgentSettings) : Tool {
    override val name = "mail_list"
    override val description = "List unread mail from the mailbox saved in Settings. Does not print the password."
    override val parameters = objectSchema(
        properties = mapOf("limit" to intProp("How many unread messages. Defaults to 10.")),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val box = mailbox() ?: return@withContext ToolResult.error("Set the mailbox host, login, and password in Settings, Desk.")
        val limit = args.intArg("limit") ?: 10
        runCatching { ToolResult.ok(box.list(limit)) }
            .getOrElse { ToolResult.error(it.message ?: "Mail list failed.") }
    }

    private fun mailbox(): Mailbox? {
        val current = settings()
        if (current.mailHost.isBlank() || current.mailUsername.isBlank()) return null
        return Mailbox(
            host = current.mailHost,
            port = current.mailPort,
            username = current.mailUsername,
            password = current.mailPassword,
            smtpHost = current.smtpHost,
            smtpPort = current.smtpPort,
            proxy = current.proxy,
        )
    }
}

class MailReadTool(private val settings: () -> AgentSettings) : Tool {
    override val name = "mail_read"
    override val description = "Read one message by the uid shown in mail_list."
    override val parameters = objectSchema(
        properties = mapOf("uid" to stringProp("Message uid from mail_list.")),
        required = listOf("uid"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val current = settings()
        if (current.mailHost.isBlank() || current.mailUsername.isBlank()) {
            return@withContext ToolResult.error("Set the mailbox in Settings, Desk.")
        }
        val uid = args.stringArg("uid")?.trim().orEmpty()
        if (uid.isEmpty() || !uid.all { it.isDigit() }) return@withContext ToolResult.error("uid must be the number from mail_list.")
        val box = Mailbox(
            current.mailHost, current.mailPort, current.mailUsername, current.mailPassword,
            current.smtpHost, current.smtpPort, current.proxy,
        )
        runCatching { ToolResult.ok(box.read(uid)) }
            .getOrElse { ToolResult.error(it.message ?: "Mail read failed.") }
    }
}

class MailSendTool(private val settings: () -> AgentSettings) : Tool {
    override val name = "mail_send"
    override val description = "Send one email through the mailbox saved in Settings."
    override val parameters = objectSchema(
        properties = mapOf(
            "to" to stringProp("Recipient address."),
            "subject" to stringProp("Subject."),
            "body" to stringProp("Message text."),
        ),
        required = listOf("to", "subject", "body"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val current = settings()
        if (current.mailHost.isBlank() || current.mailUsername.isBlank()) {
            return@withContext ToolResult.error("Set the mailbox in Settings, Desk.")
        }
        val to = args.stringArg("to")?.trim().orEmpty()
        val subject = args.stringArg("subject")?.trim().orEmpty()
        val body = args.stringArg("body").orEmpty()
        if (!to.contains('@') || subject.isEmpty()) return@withContext ToolResult.error("to and subject are required.")
        val box = Mailbox(
            current.mailHost, current.mailPort, current.mailUsername, current.mailPassword,
            current.smtpHost, current.smtpPort, current.proxy,
        )
        runCatching { ToolResult.ok(box.send(to, subject, body)) }
            .getOrElse { ToolResult.error(it.message ?: "Mail send failed.") }
    }
}
