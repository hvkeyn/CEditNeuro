package com.hvkeyn.ceditneuro.tools

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Checks for a page the agent reads. Adapted from Panniantong/agent-reach (MIT):
 * a challenge page is not content, and a reader service gets only a public address.
 */
object PageCheck {
    const val READER = "https://r.jina.ai/"

    private const val SCAN = 4096

    private val blockedHosts = setOf(
        "home.arpa",
        "instance-data",
        "internal",
        "ip6-localhost",
        "ip6-loopback",
        "lan",
        "local",
        "localdomain",
        "localhost",
        "metadata.google.internal",
    )
    private val blockedSuffixes = listOf(".home.arpa", ".internal", ".lan", ".local", ".localdomain", ".localhost")
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** A Cloudflare or reader captcha page answered instead of the page. */
    fun isChallenge(body: String): Boolean {
        val sample = body.take(SCAN).lowercase()
        val readerCaptcha = "warning:" in sample && "requiring captcha" in sample
        val structure = listOf(
            "title: just a moment...",
            "## performing security verification",
            "title: attention required! | cloudflare",
            "<title>just a moment...</title>",
            "<title>attention required! | cloudflare</title>",
        ).any { it in sample }
        val cloudflareBlock = ("attention required! | cloudflare" in sample) &&
            ("ray id" in sample || "/cdn-cgi/challenge-platform/" in sample)
        val cloudflareChallenge = "<title>just a moment...</title>" in sample &&
            ("/cdn-cgi/challenge-platform/" in sample || "cf-chl" in sample)
        return (readerCaptcha && structure) || cloudflareBlock || cloudflareChallenge
    }

    private val readerRefused = Regex("""(?m)^Warning: Target URL returned error (\d{3})""")

    /** The status the site gave the reader, when the reader printed that the site refused it. */
    fun readerRefusal(body: String): Int? =
        readerRefused.find(body.take(SCAN))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 400 }

    /** Only a public http(s) page with no login in the address. */
    fun isPublic(url: String): Boolean {
        val clean = url.trim()
        if (clean.isEmpty() || clean.contains('\\') || clean.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return false
        val uri = runCatching { URI(clean) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.isEmpty() || '%' in host) return false
        if (host in blockedHosts || blockedSuffixes.any { host.endsWith(it) }) return false
        val literal = ipv4.matches(host) || ':' in host
        if (!literal) return '.' in host
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return isGlobal(address)
    }

    /** The reader service URL for a public page, or null. */
    fun readerUrl(url: String): String? = if (isPublic(url)) READER + url.trim() else null

    /** The page a reader URL points at, or null when the URL is not a reader call. */
    fun readerTarget(url: String): String? =
        if (url.trim().startsWith(READER, ignoreCase = true)) url.trim().substring(READER.length) else null

    private fun isGlobal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0].toInt() and 0xff
                val b = bytes[1].toInt() and 0xff
                !(a == 0 || a == 10 || a == 127 || (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 192 && b == 0) ||
                    (a == 198 && b in 18..19) || a >= 224)
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xff
                first != 0xfc && first != 0xfd
            }
            else -> false
        }
    }
}
