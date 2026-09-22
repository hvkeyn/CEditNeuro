package com.hvkeyn.ceditneuro.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** A saved FTP, FTPS, or SFTP server. The password stays in encrypted settings. */
@Serializable
data class RemoteServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = "sftp",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val startPath: String = "/",
    val webUrl: String = "",
    val trustedFingerprint: String = "",
) {
    fun label(): String = name.ifBlank { "$protocol://$host" }

    override fun toString(): String =
        "RemoteServer(id=$id, name=$name, protocol=$protocol, host=$host, port=$port, username=$username)"

    companion object {
        val PROTOCOLS = listOf("sftp", "ftp", "ftps")

        fun defaultPort(protocol: String): Int = when (protocol) {
            "ftp" -> 21
            "ftps" -> 21
            else -> 22
        }
    }
}
