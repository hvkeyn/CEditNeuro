package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCardToolTest {
    private fun node(id: String, label: String, kind: String, ip: String = "") = buildJsonObject {
        put("id", id)
        put("label", label)
        put("kind", kind)
        if (ip.isNotEmpty()) put("ip", ip)
    }

    private fun args(
        nodes: List<kotlinx.serialization.json.JsonObject>,
        links: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    ) = buildJsonObject {
        put("title", "Домашняя сеть")
        put("takeaway", "Пакет идёт на шлюз.")
        put("question", "Кто меняет IP?")
        put("subnet", "192.168.1.0/24")
        put("nodes", buildJsonArray { nodes.forEach { add(it) } })
        put("links", buildJsonArray { links.forEach { add(it) } })
    }

    @Test
    fun drawsACompactCardWithAddressesAndAPath() = runBlocking {
        val out = NetworkCardTool().execute(
            args(
                listOf(
                    node("net", "Интернет", "cloud"),
                    node("r1", "Роутер", "router", "192.168.1.1/24"),
                    node("sw", "Коммутатор", "switch"),
                    node("pc", "PC", "pc", "192.168.1.10"),
                ),
                listOf(
                    buildJsonObject { put("from", "net"); put("to", "r1"); put("label", "WAN"); put("path", true) },
                    buildJsonObject { put("from", "r1"); put("to", "sw"); put("label", "LAN") },
                    buildJsonObject { put("from", "sw"); put("to", "pc"); put("label", "eth0") },
                ),
            ),
        )
        assertTrue(!out.isError)
        assertTrue(out.content.startsWith("```html"))
        assertTrue(out.content.contains("<svg"))
        assertTrue(out.content.contains("192.168.1.1/24"))
        assertTrue(out.content.contains("class=\"path\""))
        assertTrue(!out.content.contains("<script"))
        assertTrue(out.content.length < 12_000)
    }

    @Test
    fun refusesAnUnknownLinkEndpoint() = runBlocking {
        val out = NetworkCardTool().execute(
            args(
                listOf(node("pc", "PC", "pc")),
                listOf(buildJsonObject { put("from", "pc"); put("to", "missing") }),
            ),
        )
        assertTrue(out.isError)
    }

    @Test
    fun refusesTooManyNodes() = runBlocking {
        val nodes = (1..9).map { node("n$it", "Node $it", "pc") }
        val out = NetworkCardTool().execute(args(nodes))
        assertTrue(out.isError)
    }
}
