package com.hvkeyn.ceditneuro.desk

import com.hvkeyn.ceditneuro.net.NetProxy
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** IMAP inbox and SMTP send for the mailbox saved in settings. */
class Mailbox(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val smtpHost: String,
    private val smtpPort: Int,
    private val proxy: NetProxy,
) {
    fun list(limit: Int): String = imap { read, write ->
        command(read, write, "SELECT INBOX")
        val search = command(read, write, "UID SEARCH UNSEEN")
        val unseen = search.lineSequence()
            .firstOrNull { it.startsWith("* SEARCH") }
            ?.removePrefix("* SEARCH")
            ?.trim()
            .orEmpty()
            .split(' ')
            .filter { it.isNotBlank() }
        if (unseen.isEmpty()) return@imap "No unread mail."
        val picked = unseen.takeLast(limit.coerceIn(1, 20))
        val set = picked.joinToString(",")
        val fetched = command(read, write, "UID FETCH $set (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])")
        val letters = ArrayList<String>()
        val current = StringBuilder()
        fetched.lineSequence().forEach { line ->
            if (line.startsWith("* ") && line.contains("FETCH")) {
                if (current.isNotEmpty()) letters += current.toString().trim()
                current.clear()
                current.append(line.substringAfter("FETCH").trim())
            } else if (!line.startsWith(")") && current.isNotEmpty()) {
                current.append('\n').append(line)
            }
        }
        if (current.isNotEmpty()) letters += current.toString().trim()
        if (letters.isEmpty()) "Unread ids: ${picked.joinToString(" ")}" else letters.joinToString("\n---\n")
    }

    fun read(uid: String): String = imap { read, write ->
        val fetched = command(read, write, "UID FETCH $uid (BODY.PEEK[TEXT]<0.6000>)")
        fetched.lineSequence()
            .dropWhile { !it.contains("FETCH") }
            .joinToString("\n")
            .take(6_000)
            .ifBlank { "That message was empty." }
    }

    fun send(to: String, subject: String, body: String): String {
        val socket = open(smtpHost.ifBlank { host }, smtpPort, implicitTls = smtpPort == 465)
        return socket.use { raw ->
            val read = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.UTF_8))
            val write = BufferedWriter(OutputStreamWriter(raw.getOutputStream(), Charsets.UTF_8))
            expect(read, '2')
            smtp(read, write, "EHLO ceditneuro")
            if (smtpPort != 465) {
                smtp(read, write, "STARTTLS")
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, smtpHost.ifBlank { host }, smtpPort, true) as SSLSocket
                tls.startHandshake()
                return sendOn(tls, to, subject, body)
            }
            smtp(read, write, "AUTH LOGIN")
            smtp(read, write, android.util.Base64.encodeToString(username.toByteArray(), android.util.Base64.NO_WRAP))
            smtp(read, write, android.util.Base64.encodeToString(password.toByteArray(), android.util.Base64.NO_WRAP))
            deliver(read, write, to, subject, body)
            "Sent to $to."
        }
    }

    private fun sendOn(raw: SSLSocket, to: String, subject: String, body: String): String {
        val read = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.UTF_8))
        val write = BufferedWriter(OutputStreamWriter(raw.getOutputStream(), Charsets.UTF_8))
        expect(read, '2')
        smtp(read, write, "EHLO ceditneuro")
        smtp(read, write, "AUTH LOGIN")
        smtp(read, write, android.util.Base64.encodeToString(username.toByteArray(), android.util.Base64.NO_WRAP))
        smtp(read, write, android.util.Base64.encodeToString(password.toByteArray(), android.util.Base64.NO_WRAP))
        deliver(read, write, to, subject, body)
        return "Sent to $to."
    }

    private fun deliver(read: BufferedReader, write: BufferedWriter, to: String, subject: String, body: String) {
        smtp(read, write, "MAIL FROM:<$username>")
        smtp(read, write, "RCPT TO:<$to>")
        smtp(read, write, "DATA")
        write.write("Subject: $subject\r\nTo: $to\r\n\r\n$body\r\n.\r\n")
        write.flush()
        expect(read, '2')
        smtp(read, write, "QUIT")
    }

    private fun imap(block: (BufferedReader, BufferedWriter) -> String): String {
        val socket = open(host, port, implicitTls = true)
        socket.use { raw ->
            val read = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.UTF_8))
            val write = BufferedWriter(OutputStreamWriter(raw.getOutputStream(), Charsets.UTF_8))
            expect(read, '*')
            command(read, write, "LOGIN ${quote(username)} ${quote(password)}")
            val text = block(read, write)
            runCatching { command(read, write, "LOGOUT") }
            return text
        }
    }

    private fun command(read: BufferedReader, write: BufferedWriter, line: String): String {
        write.write("a $line\r\n")
        write.flush()
        val body = StringBuilder()
        while (true) {
            val next = read.readLine() ?: break
            body.append(next).append('\n')
            if (next.startsWith("a ")) {
                if (next.startsWith("a OK") || next.startsWith("a BAD") || next.startsWith("a NO")) {
                    if (!next.startsWith("a OK")) error(next.removePrefix("a ").take(240))
                    break
                }
            }
        }
        return body.toString()
    }

    private fun smtp(read: BufferedReader, write: BufferedWriter, line: String) {
        write.write("$line\r\n")
        write.flush()
        expect(read, '2', '3')
    }

    private fun expect(read: BufferedReader, vararg codes: Char) {
        val line = read.readLine() ?: error("The mail server closed the connection.")
        val mark = line.firstOrNull() ?: error(line.take(240))
        if (mark !in codes) error(line.take(240))
    }

    private fun open(host: String, port: Int, implicitTls: Boolean): Socket {
        val jump = proxy.javaProxy()
        proxy.applyCredentials()
        val plain = if (jump == null) Socket() else Socket(jump)
        plain.connect(InetSocketAddress(host, port), 20_000)
        plain.soTimeout = 30_000
        if (!implicitTls) return plain
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
        tls.startHandshake()
        return tls
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
