package com.hvkeyn.ceditneuro.net

import kotlinx.serialization.Serializable
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

/** The user's own proxy, used for their servers and checks. Off until they fill it in. */
@Serializable
data class NetProxy(
    val enabled: Boolean = false,
    val type: String = "socks",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
) {
    fun usable(): Boolean = enabled && host.isNotBlank() && port in 1..65535

    fun javaProxy(): Proxy? {
        if (!usable()) return null
        val kind = if (type == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS
        return Proxy(kind, InetSocketAddress(host, port))
    }

    fun applyCredentials() {
        if (!usable() || username.isBlank()) return
        val user = username
        val pass = password
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                return PasswordAuthentication(user, pass.toCharArray())
            }
        })
    }
}
