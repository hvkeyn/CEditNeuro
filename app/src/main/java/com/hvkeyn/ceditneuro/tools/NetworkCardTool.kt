package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.chat.LayoutCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

/**
 * Draws a network lesson as a compact SVG card. The model supplies devices and links;
 * the geometry, labels, legend, and glyphs are generated here so a chat card does not
 * depend on hand-written SVG that may be too long or collide.
 */
class NetworkCardTool : Tool {
    override val name = "network_card"

    override val description =
        "Draw a clean network topology card in the chat. " +
            "Give a title and 1 to 8 devices, then links between their ids. " +
            "kind is cloud, router, firewall, modem, switch, ap, pc, laptop, phone, printer, or server. " +
            "link kind is copper, wifi, or fiber; path=true marks the packet path. " +
            "The tool lays out the card and returns an html block. The reply must include that block unchanged. " +
            "Do not write SVG or HTML by hand; this tool is the drawing."

    override val parameters = objectSchema(
        properties = mapOf(
            "title" to stringProp("Short title of the card."),
            "takeaway" to stringProp("One sentence under the drawing, optional."),
            "question" to stringProp("One question for the learner, optional."),
            "subnet" to stringProp("CIDR label for a dashed host boundary, for example 192.168.1.0/24."),
            "nodes" to arrayProp(
                "Devices, 1 to 8.",
                objectSchema(
                    properties = mapOf(
                        "id" to stringProp("Short id used by links, for example r1."),
                        "label" to stringProp("Name shown under the device."),
                        "kind" to stringProp("cloud, router, firewall, modem, switch, ap, pc, laptop, phone, printer, or server."),
                        "ip" to stringProp("Address or interface, optional."),
                    ),
                    required = listOf("id", "label", "kind"),
                ),
            ),
            "links" to arrayProp(
                "Cables, optional.",
                objectSchema(
                    properties = mapOf(
                        "from" to stringProp("Node id."),
                        "to" to stringProp("Node id."),
                        "label" to stringProp("One short label, optional."),
                        "kind" to stringProp("copper, wifi, or fiber. Default copper."),
                        "path" to boolProp("True when this link is part of the packet path."),
                    ),
                    required = listOf("from", "to"),
                ),
            ),
        ),
        required = listOf("title", "nodes"),
    )

    private data class Node(val id: String, val label: String, val kind: String, val ip: String)
    private data class Link(val from: String, val to: String, val label: String, val kind: String, val path: Boolean)

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.stringArg("title")?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult.error("title is required.")
        val nodesJson = args["nodes"] as? JsonArray ?: return ToolResult.error("nodes is required.")
        if (nodesJson.size !in 1..8) return ToolResult.error("Use 1 to 8 nodes.")
        val nodes = mutableListOf<Node>()
        for (element in nodesJson) {
            val obj = element as? JsonObject ?: return ToolResult.error("Each node must be an object.")
            val id = obj.stringArg("id")?.trim().orEmpty()
            val label = obj.stringArg("label")?.trim().orEmpty()
            val kind = obj.stringArg("kind")?.trim()?.lowercase().orEmpty()
            if (id.isEmpty() || label.isEmpty() || kind.isEmpty()) {
                return ToolResult.error("Each node needs id, label, and kind.")
            }
            if (kind !in KINDS) return ToolResult.error("Unknown node kind: $kind.")
            if (nodes.any { it.id == id }) return ToolResult.error("Duplicate node id: $id.")
            nodes += Node(id, label, kind, obj.stringArg("ip")?.trim().orEmpty())
        }

        val linksJson = args["links"] as? JsonArray ?: JsonArray(emptyList())
        if (linksJson.size > 12) return ToolResult.error("Use at most 12 links.")
        val links = mutableListOf<Link>()
        for (element in linksJson) {
            val obj = element as? JsonObject ?: return ToolResult.error("Each link must be an object.")
            val from = obj.stringArg("from")?.trim().orEmpty()
            val to = obj.stringArg("to")?.trim().orEmpty()
            if (nodes.none { it.id == from } || nodes.none { it.id == to }) {
                return ToolResult.error("Link $from -> $to names an unknown node.")
            }
            if (from == to) return ToolResult.error("A link needs two different nodes.")
            val kind = obj.stringArg("kind")?.trim()?.lowercase().orEmpty().ifEmpty { "copper" }
            if (kind !in LINK_KINDS) return ToolResult.error("Unknown link kind: $kind.")
            links += Link(from, to, obj.stringArg("label")?.trim().orEmpty(), kind, obj.boolArg("path") == true)
        }

        val body = render(
            title = title,
            takeaway = args.stringArg("takeaway")?.trim().orEmpty(),
            question = args.stringArg("question")?.trim().orEmpty(),
            subnet = args.stringArg("subnet")?.trim().orEmpty(),
            nodes = nodes,
            links = links,
        )
        val page = LayoutCard.page(body) ?: return ToolResult.error("The card is empty or longer than 12000 characters.")
        return ToolResult.ok(LayoutCard.fence(page))
    }

    private fun render(
        title: String,
        takeaway: String,
        question: String,
        subnet: String,
        nodes: List<Node>,
        links: List<Link>,
    ): String {
        val placed = place(nodes)
        val byId = placed.associateBy { it.node.id }
        val svg = StringBuilder()
        svg.append("<svg viewBox=\"0 0 720 580\" role=\"img\" aria-label=\"")
        svg.append(esc(title)).append("\" style=\"width:100%;height:auto;display:block\">")
        svg.append("<defs><pattern id=\"grid\" width=\"24\" height=\"24\" patternUnits=\"userSpaceOnUse\">")
        svg.append("<circle cx=\"1\" cy=\"1\" r=\"1\" fill=\"#e6e8eb\"/></pattern></defs>")
        svg.append("<rect width=\"720\" height=\"580\" fill=\"url(#grid)\"/>")
        if (subnet.isNotEmpty()) {
            svg.append("<rect x=\"50\" y=\"365\" width=\"620\" height=\"130\" rx=\"14\" class=\"zone\"/>")
            svg.append("<text x=\"70\" y=\"385\" class=\"small\">").append(esc(subnet)).append(" · LAN</text>")
        }
        links.forEachIndexed { index, link ->
            drawLink(svg, link, byId[link.from], byId[link.to], index)
        }
        placed.forEach { drawNode(svg, it) }
        drawLegend(svg)
        svg.append("</svg>")

        return buildString {
            append("<style>")
            append(".nc{font-family:-apple-system,system-ui,sans-serif;color:#1c1b1f;line-height:1.4}")
            append(".nc h2{font-size:22px;line-height:1.2;margin:0 0 3px;letter-spacing:-.02em}")
            append(".nc .take{font-size:14px;margin:10px 0 0;padding:9px 11px;border-left:3px solid #0b6bcb;background:#f6f9fd}")
            append(".nc .q{font-size:14px;margin:10px 0 0}.nc .q b{color:#0b6bcb}")
            append(".nc .dev{font:12px -apple-system,system-ui,sans-serif;fill:#1c1b1f}")
            append(".nc .ip{font:11px ui-monospace,monospace;fill:#5f6368}")
            append(".nc .small{font:11px ui-monospace,monospace;fill:#5f6368}")
            append(".nc .wire{fill:none;stroke:#1c1b1f;stroke-width:1.8}")
            append(".nc .wire.wifi{stroke-dasharray:6 5}.nc .wire.fiber{stroke-width:2.8}")
            append(".nc .path{fill:none;stroke:#0b6bcb;stroke-width:2.8}")
            append(".nc .plate{fill:#fff;stroke:#d0d7de;stroke-width:1}")
            append(".nc .zone{fill:none;stroke:#9aa0a6;stroke-width:1.2;stroke-dasharray:4 5}")
            append(".nc .numbg{fill:#0b6bcb}.nc .num{font:bold 11px ui-monospace,monospace;fill:#fff}")
            append(".nc .glyph{fill:#fff;stroke:#1c1b1f;stroke-width:1.8}")
            append(".nc .dot{fill:#1c1b1f}")
            append("</style>")
            append("<div class=\"nc\"><h2>").append(esc(title)).append("</h2>")
            append("<div class=\"small\">Учебная схема · адреса примерные</div>")
            append(svg)
            append("<div class=\"small\" style=\"margin-top:6px\">")
            append("сплошная — медь · пунктир — Wi-Fi · двойная — оптика · синий — путь пакета")
            append("</div>")
            if (takeaway.isNotEmpty()) append("<p class=\"take\">").append(esc(takeaway)).append("</p>")
            if (question.isNotEmpty()) append("<p class=\"q\"><b>Вопрос:</b> ").append(esc(question)).append("</p>")
            append("</div>")
        }
    }

    private data class Placed(val node: Node, val x: Double, val y: Double)

    private fun place(nodes: List<Node>): List<Placed> {
        val row = { kind: String ->
            when (kind) {
                "cloud" -> 0
                "router", "firewall", "modem" -> 1
                "switch", "ap" -> 2
                else -> 3
            }
        }
        val ys = listOf(60.0, 170.0, 290.0, 410.0)
        val out = mutableListOf<Placed>()
        for (r in 0..3) {
            val group = nodes.filter { row(it.kind) == r }
            if (group.isEmpty()) continue
            group.forEachIndexed { i, node ->
                val x = if (group.size == 1) 360.0 else 90.0 + i * 540.0 / (group.size - 1)
                out += Placed(node, x, ys[r])
            }
        }
        return out
    }

    private fun drawLink(svg: StringBuilder, link: Link, a: Placed?, b: Placed?, index: Int) {
        if (a == null || b == null) return
        val sameRow = kotlin.math.abs(a.y - b.y) < 1.0
        val sx: Double
        val sy: Double
        val tx: Double
        val ty: Double
        if (sameRow) {
            sx = if (a.x < b.x) a.x + 48 else a.x - 48
            tx = if (a.x < b.x) b.x - 48 else b.x + 48
            sy = a.y + 8
            ty = b.y + 8
            svg.append("<path d=\"M").append(n(sx)).append(" ").append(n(sy))
                .append(" H").append(n(tx)).append("\" class=\"").append(wireClass(link)).append("\"/>")
        } else {
            sx = a.x
            sy = a.y + if (a.node.ip.isEmpty()) 58.0 else 68.0
            tx = b.x
            ty = b.y - 32.0
            val mid = (sy + ty) / 2
            svg.append("<path d=\"M").append(n(sx)).append(" ").append(n(sy))
                .append(" V").append(n(mid)).append(" H").append(n(tx)).append(" V").append(n(ty))
                .append("\" class=\"").append(wireClass(link)).append("\"/>")
        }
        val label = link.label.ifEmpty { if (link.kind == "wifi") "Wi-Fi" else "" }
        val mx = (sx + tx) / 2
        val my = if (sameRow) sy else (sy + ty) / 2
        if (label.isNotEmpty()) {
            val w = max(54.0, label.length * 6.6 + 14)
            val ly = my + (if (index % 2 == 0) -10.0 else 12.0)
            svg.append("<rect x=\"").append(n(mx - w / 2)).append("\" y=\"").append(n(ly - 9))
                .append("\" width=\"").append(n(w)).append("\" height=\"18\" rx=\"4\" class=\"plate\"/>")
            svg.append("<text x=\"").append(n(mx)).append("\" y=\"").append(n(ly + 3))
                .append("\" text-anchor=\"middle\" class=\"small\">").append(esc(label)).append("</text>")
        }
        if (link.path) {
            val nx = mx + 30
            val ny = my
            svg.append("<circle cx=\"").append(n(nx)).append("\" cy=\"").append(n(ny))
                .append("\" r=\"9\" class=\"numbg\"/>")
            svg.append("<text x=\"").append(n(nx)).append("\" y=\"").append(n(ny + 4))
                .append("\" text-anchor=\"middle\" class=\"num\">").append(index + 1).append("</text>")
        }
    }

    private fun wireClass(link: Link): String =
        if (link.path) "path" else "wire " + link.kind

    private fun drawNode(svg: StringBuilder, p: Placed) {
        val x = p.x
        val y = p.y
        svg.append("<g transform=\"translate(").append(n(x)).append(" ").append(n(y)).append(")\">")
        when (p.node.kind) {
            "cloud" -> svg.append(
                "<path d=\"M-42 0c-8-20 8-36 26-32 7-16 30-20 41-5 18-5 34 7 30 25 14 3 18 21 5 29h-96c-12-4-15-12-6-17z\" class=\"glyph\"/>",
            )
            "router" -> {
                svg.append("<rect x=\"-48\" y=\"-22\" width=\"96\" height=\"44\" rx=\"9\" class=\"glyph\"/>")
                svg.append("<path d=\"M-34 -22l-9 -13M34 -22l9 -13\" class=\"wire\"/>")
                svg.append("<circle cx=\"-43\" cy=\"-35\" r=\"2.4\" class=\"dot\"/><circle cx=\"43\" cy=\"-35\" r=\"2.4\" class=\"dot\"/>")
                for (i in 0..4) svg.append("<rect x=\"").append(-34 + i * 14).append("\" y=\"13\" width=\"8\" height=\"5\" class=\"dot\"/>")
            }
            "firewall" -> {
                svg.append("<rect x=\"-50\" y=\"-26\" width=\"100\" height=\"52\" rx=\"4\" class=\"glyph\"/>")
                for (i in 0..3) svg.append("<path d=\"M-50 ").append(-13 + i * 13).append("H50\" class=\"wire\" opacity=\".35\"/>")
                for (i in 0..3) svg.append("<path d=\"M").append(-25 + i * 25).append(" -26v13M").append(-12 + i * 25).append(" -13v13M").append(-25 + i * 25).append(" 0v13M").append(-12 + i * 25).append(" 13v13\" class=\"wire\" opacity=\".35\"/>")
            }
            "switch" -> {
                svg.append("<rect x=\"-52\" y=\"-18\" width=\"104\" height=\"36\" rx=\"5\" class=\"glyph\"/>")
                for (i in 0..7) svg.append("<rect x=\"").append(-42 + i * 11).append("\" y=\"11\" width=\"7\" height=\"5\" class=\"dot\"/>")
            }
            "ap" -> {
                svg.append("<rect x=\"-30\" y=\"-16\" width=\"60\" height=\"32\" rx=\"5\" class=\"glyph\"/>")
                svg.append("<path d=\"M-18 -26c8-8 28-8 36 0M-24 -34c12-12 36-12 48 0\" class=\"wire\"/>")
            }
            "pc" -> {
                svg.append("<rect x=\"-30\" y=\"-24\" width=\"60\" height=\"40\" rx=\"3\" class=\"glyph\"/>")
                svg.append("<path d=\"M-10 16v8h20v-8M-18 24h36\" class=\"wire\"/>")
            }
            "laptop" -> {
                svg.append("<path d=\"M-34 -22h68v32h-68z\" class=\"glyph\"/>")
                svg.append("<path d=\"M-42 10h84l-6 9h-72z\" class=\"glyph\"/>")
            }
            "phone" -> {
                svg.append("<rect x=\"-14\" y=\"-24\" width=\"28\" height=\"48\" rx=\"5\" class=\"glyph\"/>")
                svg.append("<circle cx=\"0\" cy=\"17\" r=\"2\" class=\"dot\"/>")
            }
            "printer" -> {
                svg.append("<rect x=\"-32\" y=\"-18\" width=\"64\" height=\"38\" rx=\"3\" class=\"glyph\"/>")
                svg.append("<rect x=\"-22\" y=\"-30\" width=\"44\" height=\"12\" class=\"glyph\"/>")
                svg.append("<path d=\"M-20 10h40\" class=\"wire\"/>")
            }
            "server" -> {
                svg.append("<rect x=\"-22\" y=\"-28\" width=\"44\" height=\"56\" rx=\"4\" class=\"glyph\"/>")
                for (i in 0..3) svg.append("<path d=\"M-14 ").append(-18 + i * 12).append("h28\" class=\"wire\"/>")
            }
            else -> svg.append("<circle cx=\"0\" cy=\"0\" r=\"20\" class=\"glyph\"/>")
        }
        svg.append("</g>")
        svg.append("<text x=\"").append(n(x)).append("\" y=\"").append(n(y + 36))
            .append("\" text-anchor=\"middle\" class=\"dev\">").append(esc(p.node.label)).append("</text>")
        if (p.node.ip.isNotEmpty()) {
            svg.append("<text x=\"").append(n(x)).append("\" y=\"").append(n(y + 51))
                .append("\" text-anchor=\"middle\" class=\"ip\">").append(esc(p.node.ip)).append("</text>")
        }
    }

    private fun drawLegend(svg: StringBuilder) {
        val y = 548.0
        svg.append("<path d=\"M70 ").append(n(y)).append("h26\" class=\"wire\"/>")
        svg.append("<text x=\"104\" y=\"").append(n(y + 4)).append("\" class=\"small\">медь</text>")
        svg.append("<path d=\"M190 ").append(n(y)).append("h26\" class=\"wire wifi\"/>")
        svg.append("<text x=\"224\" y=\"").append(n(y + 4)).append("\" class=\"small\">Wi-Fi</text>")
        svg.append("<path d=\"M310 ").append(n(y)).append("h26\" class=\"wire\" stroke-width=\"2.8\"/>")
        svg.append("<text x=\"344\" y=\"").append(n(y + 4)).append("\" class=\"small\">оптика</text>")
        svg.append("<circle cx=\"430\" cy=\"").append(n(y)).append("\" r=\"5\" fill=\"#0b6bcb\"/>")
        svg.append("<text x=\"442\" y=\"").append(n(y + 4)).append("\" class=\"small\">путь</text>")
    }

    private fun n(value: Double): String = ((value * 10).toInt() / 10.0).toString()

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        val KINDS = setOf("cloud", "router", "firewall", "modem", "switch", "ap", "pc", "laptop", "phone", "printer", "server")
        val LINK_KINDS = setOf("copper", "wifi", "fiber")
    }
}
