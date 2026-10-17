package com.hvkeyn.ceditneuro.net

import kotlin.math.ln

/**
 * Matches one observation against trails, the way a trail sensor does.
 * A trail is a domain, a URL, an IP address, an IP and port, or a User-Agent.
 * A shorter name can match a listed parent. A URL is checked as host and path before the host alone.
 * A line that starts with ! is never reported. Heuristics cover shape, not a downloaded list.
 */
object TrailMatch {
    data class Observation(
        val src: String = "",
        val dst: String = "",
        val dport: Int = 0,
        val proto: String = "",
        val dns: String = "",
        val host: String = "",
        val path: String = "",
        val method: String = "",
        val agent: String = "",
    )

    data class Hit(val type: String, val trail: String, val info: String, val reference: String)

    class Book(
        val dns: Map<String, Hit>,
        val urls: List<Hit>,
        val ips: Map<String, Hit>,
        val ports: Map<String, Hit>,
        val agents: List<Hit>,
        private val allow: Set<String>,
    ) {
        fun allowed(value: String): Boolean = value.lowercase() in allow
    }

    fun parse(text: String): Book {
        val dns = LinkedHashMap<String, Hit>()
        val urls = ArrayList<Hit>()
        val ips = LinkedHashMap<String, Hit>()
        val ports = LinkedHashMap<String, Hit>()
        val agents = ArrayList<Hit>()
        val allow = LinkedHashSet<String>()
        var lines = 0
        for (raw in text.lineSequence()) {
            if (lines++ >= 8_000) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("!")) {
                val name = line.drop(1).trim().lowercase()
                if (name.isNotEmpty()) allow += name
                continue
            }
            val cut = split(line) ?: continue
            val hit = Hit(cut.type, cut.trail, cut.info, "(custom)")
            when (cut.type) {
                "DNS" -> dns.putIfAbsent(cut.trail, hit)
                "URL" -> if (urls.none { it.trail == cut.trail }) urls += hit
                "IP" -> ips.putIfAbsent(cut.trail, hit)
                "IPORT" -> ports.putIfAbsent(cut.trail, hit)
                "UA" -> if (agents.none { it.trail.equals(cut.trail, ignoreCase = true) }) agents += hit
            }
        }
        return Book(dns, urls, ips, ports, agents, allow)
    }

    fun scan(book: Book, observations: List<Observation>, heuristics: Boolean = true): List<Hit> {
        val hits = LinkedHashSet<Hit>()
        val ports = LinkedHashMap<String, MutableSet<Int>>()
        val hosts = LinkedHashMap<String, MutableSet<String>>()
        val names = LinkedHashSet<String>()
        for (obs in observations) {
            if (cleared(book, obs)) continue
            match(book, obs).forEach { hits += it }
            if (!heuristics) continue
            shape(obs).forEach { hits += it }
            if (obs.src.isNotBlank() && obs.dport > 0) ports.getOrPut(obs.src) { LinkedHashSet() }.add(obs.dport)
            if (obs.src.isNotBlank() && obs.dst.isNotBlank()) hosts.getOrPut(obs.src) { LinkedHashSet() }.add(obs.dst)
            if (obs.dns.isNotBlank()) names += obs.dns.lowercase()
        }
        if (heuristics) {
            ports.forEach { (src, set) ->
                if (set.size >= 12) hits += Hit("heuristic", src, "scan-ports ${set.size}", "(heuristic)")
            }
            hosts.forEach { (src, set) ->
                if (set.size >= 16) hits += Hit("heuristic", src, "scan-hosts ${set.size}", "(heuristic)")
            }
            if (names.size >= 40) hits += Hit("heuristic", "${names.size} names", "dns-exhaustion", "(heuristic)")
        }
        return hits.take(30)
    }

    fun report(hits: List<Hit>): String = buildString {
        append("trails\n")
        if (hits.isEmpty()) {
            append("none\n")
            return@buildString
        }
        hits.forEach { hit ->
            append(hit.type).append(' ').append(hit.trail.take(60))
            append(" \"").append(hit.info.take(40)).append("\" ")
            append(hit.reference).append('\n')
        }
    }

    private fun match(book: Book, obs: Observation): List<Hit> {
        val found = ArrayList<Hit>()
        val url = urlOf(obs.host, obs.path)
        if (url.isNotEmpty()) {
            val listed = book.urls.firstOrNull { urlCovers(url, it.trail) }
            if (listed != null) found += listed
        }
        if (found.none { it.type == "URL" }) {
            domainKeys(obs.host).firstNotNullOfOrNull { book.dns[it] }?.let { found += it }
        }
        domainKeys(obs.dns).firstNotNullOfOrNull { book.dns[it] }?.let { found += it }
        if (obs.dst.isNotBlank() && obs.dport > 0) {
            book.ports["${obs.dst}:${obs.dport}"]?.let { found += it }
        }
        if (obs.dst.isNotBlank()) book.ips[obs.dst]?.let { found += it }
        if (obs.agent.length >= 4) {
            book.agents.firstOrNull { obs.agent.contains(it.trail, ignoreCase = true) }?.let { found += it }
        }
        return found
    }

    private fun shape(obs: Observation): List<Hit> {
        val hits = ArrayList<Hit>()
        val name = obs.dns.ifBlank { obs.host }.lowercase()
        if (name.isNotBlank()) {
            if (name.length >= 48 || name.split('.').any { it.length >= 32 }) {
                hits += Hit("heuristic", name.take(60), "long-name", "(heuristic)")
            }
            val labels = name.split('.').filter { it.isNotEmpty() }
            if (labels.size >= 6 || labels.any { it.length >= 36 }) {
                hits += Hit("heuristic", name.take(60), "dns-tunnel", "(heuristic)")
            }
            labels.firstOrNull { it.length >= 12 && vowelShare(it) < 0.2 && entropy(it) >= 3.5 }?.let {
                hits += Hit("heuristic", name.take(60), "dga-like", "(heuristic)")
            }
        }
        if (obs.method.isNotEmpty()) {
            if (obs.host.isBlank()) hits += Hit("heuristic", obs.dst, "missing-host", "(heuristic)")
            if (obs.host.isNotBlank() && IPV4.matches(obs.host)) {
                hits += Hit("heuristic", obs.host, "direct-ip", "(heuristic)")
            }
            if (obs.agent.isBlank() || obs.agent.length < 8) {
                hits += Hit("heuristic", obs.host.ifBlank { obs.dst }, "short-agent", "(heuristic)")
            }
            if (obs.method.equals("CONNECT", ignoreCase = true)) {
                hits += Hit("heuristic", obs.host.ifBlank { obs.dst }, "proxy-probe", "(heuristic)")
            }
            val path = obs.path.lowercase()
            if (path.substringBefore('?').substringAfterLast('.') in PROGRAMS) {
                hits += Hit("heuristic", obs.host.ifBlank { obs.dst }, "program-download", "(heuristic)")
            }
            if ("../" in path || "%2e%2e" in path) {
                hits += Hit("heuristic", obs.host.ifBlank { obs.dst }, "traversal-shaped", "(heuristic)")
            }
            if ("<script" in path) {
                hits += Hit("heuristic", obs.host.ifBlank { obs.dst }, "script-shaped", "(heuristic)")
            }
        }
        return hits
    }

    private fun cleared(book: Book, obs: Observation): Boolean {
        if (obs.dst.isNotBlank() && book.allowed(obs.dst)) return true
        val names = domainKeys(obs.dns) + domainKeys(obs.host)
        return names.any { book.allowed(it) }
    }

    private data class Cut(val type: String, val trail: String, val info: String)

    private fun split(line: String): Cut? {
        if (line.startsWith("ua:", ignoreCase = true)) {
            val rest = line.substring(3).trim()
            val indicator = rest.substringBefore(' ').trim()
            if (indicator.length < 4) return null
            val info = rest.substringAfter(' ', "").trim().ifBlank { "custom" }
            return Cut("UA", indicator, info)
        }
        val indicator = line.substringBefore(' ').trim().lowercase()
        val info = line.substringAfter(' ', "").trim().ifBlank { "custom" }
        if (indicator.isEmpty()) return null
        IPV4_PORT.matchEntire(indicator)?.let { return Cut("IPORT", indicator, info) }
        if (IPV4.matches(indicator)) return Cut("IP", indicator, info)
        if ('/' in indicator) {
            val host = indicator.substringBefore('/').substringBefore(':')
            val path = "/" + indicator.substringAfter('/', "")
            if (domainKeys(host).isEmpty() && !IPV4.matches(host)) return null
            return Cut("URL", host + path.substringBefore('?'), info)
        }
        if (domainKeys(indicator).isEmpty()) return null
        return Cut("DNS", indicator, info)
    }

    internal fun domainKeys(name: String): List<String> {
        val host = name.lowercase().trim('.').substringBefore('/').substringBefore(':')
        if (host.isEmpty() || host.any { it !in "abcdefghijklmnopqrstuvwxyz0123456789.-" }) return emptyList()
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return emptyList()
        return (0 until labels.size - 1).map { labels.drop(it).joinToString(".") }
    }

    private fun urlCovers(url: String, trail: String): Boolean {
        if (url == trail) return true
        if (!url.startsWith(trail)) return false
        if (trail.endsWith("/")) return true
        val next = url.getOrNull(trail.length) ?: return true
        return next == '/' || next == '?'
    }

    private fun urlOf(host: String, path: String): String {
        val name = host.lowercase().substringBefore(':').trim()
        if (name.isEmpty()) return ""
        val suffix = path.substringBefore('?')
        val withSlash = if (suffix.startsWith("/")) suffix else "/$suffix"
        return name + if (withSlash == "/") "" else withSlash
    }

    private fun vowelShare(label: String): Double {
        if (label.isEmpty()) return 1.0
        val vowels = label.count { it in "aeiou" }
        return vowels.toDouble() / label.length
    }

    private fun entropy(label: String): Double {
        if (label.isEmpty()) return 0.0
        val counts = IntArray(128)
        label.forEach { ch -> if (ch.code < 128) counts[ch.code]++ }
        val n = label.length.toDouble()
        var sum = 0.0
        for (count in counts) {
            if (count == 0) continue
            val p = count / n
            sum -= p * (ln(p) / LN2)
        }
        return sum
    }

    private val LN2 = ln(2.0)
    private val IPV4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    private val IPV4_PORT = Regex("""\d{1,3}(?:\.\d{1,3}){3}:\d{1,5}""")
    private val PROGRAMS = setOf("exe", "dll", "scr", "bat", "apk", "jar")
}
