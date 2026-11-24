package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Phone-sized Archify direction layer. The upstream project is a typed JSON IR plus a
 * Node CLI; this class keeps the IR idea and turns it into one self-contained interactive
 * HTML/SVG artifact with no Node, npm, or external asset. It is not the upstream validator.
 */
class ArchifyTool(private val workspace: Workspace) : Tool {
    override val name = "archify"
    override val description =
        "Create a beautiful interactive architecture/workflow/sequence/dataflow/lifecycle diagram from a typed JSON spec. " +
            "Writes a self-contained HTML file with SVG, click-to-focus, details, and a light/dark toggle. " +
            "Use this instead of visual_card when the diagram has several connected nodes or should be explored. " +
            "The spec is {\"type\",\"title\",\"subtitle\",\"nodes\":[{\"id\",\"label\",\"kind\",\"group\",\"detail\"}]," +
            "\"edges\":[{\"from\",\"to\",\"label\"}],\"groups\":[{\"id\",\"label\"}]}. " +
            "At most 16 nodes and 32 edges. No scripts or HTML in labels. This is not the upstream Node CLI and does not claim its 9-point validation."

    override val parameters = objectSchema(
        properties = mapOf(
            "spec" to stringProp("Typed JSON IR as described."),
            "path" to stringProp("Optional project-relative .html path; defaults to archify/<slug>.html."),
        ),
        required = listOf("spec"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args.stringArg("spec")?.trim().orEmpty()
        if (raw.isEmpty()) return ToolResult.error("spec is required JSON.")
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
            return ToolResult.error("spec is not a JSON object: ${it.message}")
        }
        val spec = runCatching { parse(parsed) }.getOrElse { return ToolResult.error(it.message ?: "Invalid spec.") }
        if (spec.nodes.size > 16) return ToolResult.error("At most 16 nodes; split the detail into a second diagram.")
        if (spec.edges.size > 32) return ToolResult.error("At most 32 edges.")
        val html = render(spec)
        if (html.length > 120_000) return ToolResult.error("The rendered page is too large; reduce nodes or labels.")
        val asked = args.stringArg("path")?.trim().orEmpty()
        val rel = if (asked.isNotBlank()) asked else "archify/${slug(spec.title)}.html"
        val out = runCatching { workspace.resolve(rel) }.getOrElse { return ToolResult.error(it.message ?: "Bad path.") }
        if (!rel.endsWith(".html", ignoreCase = true)) return ToolResult.error("path must end in .html.")
        out.parentFile?.mkdirs()
        out.writeText(html, Charsets.UTF_8)
        val inline = html.substringAfter("<section class=\"stage\">", "")
            .substringBefore("</section>", "").trim()
        val preview = if (inline.startsWith("<svg")) "```svg\n$inline\n```\n" else ""
        return ToolResult.ok(
            preview +
                "Archify ${spec.type}: ${spec.nodes.size} nodes, ${spec.edges.size} edges.\n" +
                "HTML: $rel\n" +
                "Open it with open_file path=$rel, or tap it in the project tree. " +
                "This is an interactive code diagram, not the upstream Node CLI output.",
        )
    }

    private data class Node(
        val id: String, val label: String, val kind: String,
        val group: String?, val detail: String,
    )
    private data class Edge(val from: String, val to: String, val label: String)
    private data class Group(val id: String, val label: String)
    private data class Spec(
        val type: String, val title: String, val subtitle: String,
        val nodes: List<Node>, val edges: List<Edge>, val groups: List<Group>,
    )

    private fun parse(obj: JsonObject): Spec {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.US) ?: "architecture"
        val allowed = setOf("architecture", "workflow", "sequence", "dataflow", "lifecycle")
        if (type !in allowed) throw IllegalArgumentException("type must be one of ${allowed.joinToString()}.")
        val title = text(obj, "title", 80).ifBlank { "System diagram" }
        val subtitle = text(obj, "subtitle", 160)
        val groups = obj["groups"]?.jsonArray?.mapIndexed { i, el ->
            val g = el.jsonObject
            Group(text(g, "id", 40).ifBlank { "g$i" }, text(g, "label", 60))
        }.orEmpty()
        val groupIds = groups.map { it.id }.toSet()
        val nodes = obj["nodes"]?.jsonArray?.mapIndexed { i, el ->
            val n = el.jsonObject
            val id = text(n, "id", 40).ifBlank { "n$i" }
            val group = n["group"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            if (group != null && groupIds.isNotEmpty() && group !in groupIds) {
                throw IllegalArgumentException("node $id points at unknown group $group.")
            }
            Node(
                id = id,
                label = text(n, "label", 80).ifBlank { id },
                kind = text(n, "kind", 30).ifBlank { kindFor(type) },
                group = group,
                detail = text(n, "detail", 240),
            )
        }.orEmpty()
        if (nodes.isEmpty()) throw IllegalArgumentException("nodes must contain at least one item.")
        val ids = nodes.map { it.id }.toSet()
        if (ids.size != nodes.size) throw IllegalArgumentException("node ids must be unique.")
        val edges = obj["edges"]?.jsonArray?.mapIndexed { i, el ->
            val e = el.jsonObject
            val from = text(e, "from", 40)
            val to = text(e, "to", 40)
            if (from !in ids || to !in ids) throw IllegalArgumentException("edge ${i + 1} points at a missing node.")
            Edge(from, to, text(e, "label", 60))
        }.orEmpty()
        return Spec(type, title, subtitle, nodes, edges, groups)
    }

    private fun kindFor(type: String): String = when (type) {
        "sequence" -> "actor"
        "lifecycle" -> "state"
        "workflow" -> "step"
        else -> "backend"
    }

    private fun text(obj: JsonObject, key: String, max: Int): String =
        obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.take(max).orEmpty()

    private fun slug(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "diagram" }.take(50)

    private fun render(spec: Spec): String {
        val body = when (spec.type) {
            "sequence" -> sequenceSvg(spec)
            else -> graphSvg(spec)
        }
        return page(spec, body)
    }

    private fun graphSvg(spec: Spec): String {
        val nodes = spec.nodes
        val byId = nodes.associateBy { it.id }
        val incoming = nodes.associate { it.id to mutableListOf<String>() }
        val outgoing = nodes.associate { it.id to mutableListOf<String>() }
        spec.edges.forEach { e -> incoming[e.to]?.add(e.from); outgoing[e.from]?.add(e.to) }
        val depth = nodes.associate { it.id to 0 }.toMutableMap()
        val queue = ArrayDeque(nodes.filter { incoming[it.id].isNullOrEmpty() }.map { it.id })
        if (queue.isEmpty()) queue.add(nodes.first().id)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val d = depth[id] ?: 0
            outgoing[id].orEmpty().forEach { next ->
                if ((depth[next] ?: 0) <= d) depth[next] = (d + 1).coerceAtMost(3)
                if (next !in seen) queue.addLast(next)
            }
        }
        nodes.forEachIndexed { i, n -> if (n.id !in seen) depth[n.id] = i % 3 }
        val cols = nodes.groupBy { depth[it.id] ?: 0 }.toSortedMap()
        val maxCols = min(4, cols.size.coerceAtLeast(1))
        val rows = (0 until maxCols).map { col -> cols[col].orEmpty() }
        val maxRows = rows.maxOfOrNull { it.size } ?: 1
        val width = max(960, 180 + maxCols * 260)
        val height = max(560, 150 + maxRows * 114)
        val positions = mutableMapOf<String, Pair<Int, Int>>()
        rows.forEachIndexed { col, list ->
            list.forEachIndexed { row, node ->
                positions[node.id] = (100 + col * 260) to (130 + row * 114)
            }
        }
        val sb = StringBuilder()
        sb.append("<svg class=\"canvas\" viewBox=\"0 0 $width $height\" role=\"img\" aria-label=\"${esc(spec.title)}\">")
        sb.append("<defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#7c9cff\"/></marker>")
        sb.append("<linearGradient id=\"card\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#1b2742\"/><stop offset=\"1\" stop-color=\"#111a2d\"/></linearGradient></defs>")
        // group boundaries
        spec.groups.forEach { g ->
            val members = nodes.filter { it.group == g.id }
            if (members.isEmpty()) return@forEach
            val ps = members.mapNotNull { positions[it.id] }
            if (ps.isEmpty()) return@forEach
            val minX = (ps.minOf { it.first } - 26).coerceAtLeast(12)
            val minY = (ps.minOf { it.second } - 48).coerceAtLeast(12)
            val maxX = (ps.maxOf { it.first } + 236).coerceAtMost(width - 12)
            val maxY = (ps.maxOf { it.second } + 104).coerceAtMost(height - 12)
            sb.append("<g class=\"zone\"><rect x=\"$minX\" y=\"$minY\" width=\"${maxX - minX}\" height=\"${maxY - minY}\" rx=\"20\"/>")
            sb.append("<text x=\"${minX + 14}\" y=\"${minY + 24}\" class=\"zone-label\">${esc(g.label.ifBlank { g.id })}</text></g>")
        }
        // edges
        spec.edges.forEachIndexed { i, e ->
            val a = positions[e.from] ?: return@forEachIndexed
            val b = positions[e.to] ?: return@forEachIndexed
            val x1 = a.first + 212; val y1 = a.second + 34
            val x2 = b.first; val y2 = b.second + 34
            val same = a.first == b.first
            val path = if (same) {
                val x = a.first + 106; val y = a.second - 8
                "M${x1} ${y1} C${x + 60} $y ${x + 60} ${b.second - 22} $x2 ${y2}"
            } else {
                val mid = (x1 + x2) / 2
                "M$x1 $y1 C$mid $y1 $mid $y2 $x2 $y2"
            }
            sb.append("<g class=\"edge\" data-from=\"${esc(e.from)}\" data-to=\"${esc(e.to)}\">")
            sb.append("<path d=\"$path\" marker-end=\"url(#arrow)\"/>")
            if (e.label.isNotBlank()) {
                val lx = if (same) a.first + 112 else (x1 + x2) / 2
                val ly = if (same) (a.second + b.second) / 2 else (y1 + y2) / 2 - 6
                sb.append("<rect x=\"${lx - e.label.length * 3.2 - 6}\" y=\"${ly - 11}\" width=\"${e.label.length * 6.4 + 12}\" height=\"18\" rx=\"6\" class=\"edge-label-bg\"/>")
                sb.append("<text x=\"$lx\" y=\"${ly + 1}\" class=\"edge-label\" text-anchor=\"middle\">${esc(e.label)}</text>")
            }
            sb.append("</g>")
        }
        // nodes
        nodes.forEach { n ->
            val p = positions[n.id] ?: return@forEach
            val kind = kindColor(n.kind)
            sb.append("<g class=\"node\" tabindex=\"0\" role=\"button\" data-id=\"${esc(n.id)}\" data-label=\"${esc(n.label)}\" data-kind=\"${esc(n.kind)}\" data-group=\"${esc(n.group.orEmpty())}\" data-detail=\"${esc(n.detail)}\">")
            sb.append("<rect x=\"${p.first}\" y=\"${p.second}\" width=\"212\" height=\"68\" rx=\"14\" class=\"node-card\"/>")
            sb.append("<rect x=\"${p.first}\" y=\"${p.second}\" width=\"5\" height=\"68\" rx=\"2.5\" fill=\"$kind\"/>")
            sb.append("<circle cx=\"${p.first + 25}\" cy=\"${p.second + 22}\" r=\"6\" fill=\"$kind\"/>")
            sb.append("<text x=\"${p.first + 40}\" y=\"${p.second + 27}\" class=\"node-label\">${esc(n.label)}</text>")
            sb.append("<text x=\"${p.first + 18}\" y=\"${p.second + 50}\" class=\"node-kind\">${esc(n.kind)}</text>")
            sb.append("<text x=\"${p.first + 196}\" y=\"${p.second + 27}\" text-anchor=\"end\" class=\"node-arrow\">›</text>")
            sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun sequenceSvg(spec: Spec): String {
        val actors = spec.nodes
        val width = max(900, 130 + actors.size * 230)
        val height = max(520, 190 + spec.edges.size * 62 + 60)
        val xs = actors.mapIndexed { i, _ -> 120 + i * 230 }
        val sb = StringBuilder()
        sb.append("<svg class=\"canvas\" viewBox=\"0 0 $width $height\" role=\"img\" aria-label=\"${esc(spec.title)}\">")
        sb.append("<defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#7c9cff\"/></marker></defs>")
        actors.forEachIndexed { i, n ->
            val x = xs[i]
            sb.append("<g class=\"node\" tabindex=\"0\" role=\"button\" data-id=\"${esc(n.id)}\" data-label=\"${esc(n.label)}\" data-kind=\"${esc(n.kind)}\" data-detail=\"${esc(n.detail)}\">")
            sb.append("<rect x=\"${x - 88}\" y=\"38\" width=\"176\" height=\"58\" rx=\"14\" class=\"node-card\"/>")
            sb.append("<text x=\"$x\" y=\"64\" text-anchor=\"middle\" class=\"node-label\">${esc(n.label)}</text>")
            sb.append("<text x=\"$x\" y=\"83\" text-anchor=\"middle\" class=\"node-kind\">${esc(n.kind)}</text>")
            sb.append("</g>")
            sb.append("<line x1=\"$x\" y1=\"102\" x2=\"$x\" y2=\"${height - 28}\" class=\"lifeline\"/>")
        }
        spec.edges.forEachIndexed { i, e ->
            val from = actors.indexOfFirst { it.id == e.from }
            val to = actors.indexOfFirst { it.id == e.to }
            if (from < 0 || to < 0) return@forEachIndexed
            val y = 168 + i * 62
            val x1 = xs[from]; val x2 = xs[to]
            val dir = if (x2 >= x1) 1 else -1
            sb.append("<g class=\"edge\" data-from=\"${esc(e.from)}\" data-to=\"${esc(e.to)}\">")
            sb.append("<line x1=\"$x1\" y1=\"$y\" x2=\"${x2 - dir * 8}\" y2=\"$y\" marker-end=\"url(#arrow)\"/>")
            if (e.label.isNotBlank()) sb.append("<text x=\"${(x1 + x2) / 2}\" y=\"${y - 9}\" text-anchor=\"middle\" class=\"edge-label\">${esc(e.label)}</text>")
            sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun page(spec: Spec, svg: String): String {
        val legend = spec.nodes.map { it.kind }.distinct().joinToString("") { kind ->
            "<span class=\"chip\"><i style=\"background:${kindColor(kind)}\"></i>${esc(kind)}</span>"
        }
        return """<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(spec.title)} · Archify</title>
<style>
:root{--bg:#080c15;--panel:#0e1627;--line:#253553;--text:#e8eefc;--muted:#8ea3c8;--accent:#7c9cff;--zone:#132039}
[data-theme=light]{--bg:#f4f1e9;--panel:#fffdf8;--line:#d8d1c2;--text:#172033;--muted:#667085;--accent:#3557d6;--zone:#ece7dc}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(900px 500px at 12% -8%,#1b2a4a55,transparent),var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;min-height:100vh}
header{position:sticky;top:0;z-index:3;background:color-mix(in srgb,var(--bg) 86%,transparent);backdrop-filter:blur(16px);border-bottom:1px solid var(--line)}
.bar{display:flex;align-items:flex-start;gap:14px;padding:16px 18px;max-width:1400px;margin:auto}.title{flex:1}.eyebrow{font:600 11px ui-monospace,monospace;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);margin-bottom:6px}
h1{font-size:20px;line-height:1.25;margin:0 0 5px;letter-spacing:-.02em}.sub{color:var(--muted);font-size:12px;line-height:1.55;max-width:780px;margin:0}
button{font:600 12px system-ui;color:var(--text);background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:9px 11px;cursor:pointer}button:hover{border-color:var(--accent)}
main{max-width:1400px;margin:auto;padding:14px 14px 40px;display:grid;grid-template-columns:minmax(0,1fr) 290px;gap:14px}
.stage{background:linear-gradient(160deg,var(--panel),var(--bg));border:1px solid var(--line);border-radius:18px;overflow:auto;min-height:430px;box-shadow:0 20px 50px #0004}
.canvas{display:block;width:100%;height:auto;min-width:720px}
.node{cursor:pointer;outline:none}.node-card{fill:url(#card);stroke:var(--line);stroke-width:1.4;transition:.18s ease}.node:hover .node-card,.node.active .node-card{stroke:var(--accent);filter:drop-shadow(0 0 14px #7c9cff55)}
.node-label{font:600 13px system-ui;fill:var(--text)}.node-kind{font:10px ui-monospace,monospace;fill:var(--muted);text-transform:uppercase;letter-spacing:.08em}.node-arrow{font:20px system-ui;fill:var(--muted)}
.edge path,.edge line{fill:none;stroke:#49638f;stroke-width:1.7;transition:.18s}.edge.hot path,.edge.hot line{stroke:var(--accent);stroke-width:2.4}.edge-label{font:11px ui-monospace,monospace;fill:var(--text)}.edge-label-bg{fill:var(--panel);opacity:.92}
.zone rect{fill:var(--zone);stroke:var(--line);stroke-dasharray:5 6}.zone-label{font:600 10px ui-monospace,monospace;fill:var(--muted);letter-spacing:.12em;text-transform:uppercase}
.lifeline{stroke:var(--line);stroke-dasharray:3 6}
aside{border:1px solid var(--line);background:var(--panel);border-radius:18px;padding:16px;height:max-content;position:sticky;top:82px}
aside h2{font-size:13px;margin:0 0 8px}#detail{font-size:12px;line-height:1.65;color:var(--muted);min-height:52px}#detail b{color:var(--text)}.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:14px}.chip{font:10px ui-monospace,monospace;color:var(--muted);border:1px solid var(--line);border-radius:999px;padding:5px 8px;display:flex;align-items:center;gap:5px}.chip i{width:7px;height:7px;border-radius:50%}
.hint{font-size:10px;color:var(--muted);line-height:1.5;margin-top:12px;border-top:1px solid var(--line);padding-top:10px}
@media (max-width:820px){main{grid-template-columns:1fr}aside{position:static;order:2}.canvas{min-width:620px}h1{font-size:17px}}
</style>
</head>
<body>
<header><div class="bar"><div class="title"><div class="eyebrow">Archify · ${esc(spec.type)}</div><h1>${esc(spec.title)}</h1><p class="sub">${esc(spec.subtitle.ifBlank { "Click a node to focus its connections." })}</p></div><button id="theme" type="button">Light</button></div></header>
<main><section class="stage">$svg</section><aside><h2>Focus</h2><div id="detail">Click any node to see its detail and connections. The diagram is a static description; it does not inspect live traffic.</div><div class="chips">$legend</div><div class="hint">Interactive SVG · self-contained · generated on the phone. Archify-compatible direction layer, not the upstream Node validator.</div></aside></main>
<script>
const detail=document.getElementById('detail');
const nodes=[...document.querySelectorAll('.node')];
const edges=[...document.querySelectorAll('.edge')];
function clear(){nodes.forEach(n=>n.classList.remove('active'));edges.forEach(e=>e.classList.remove('hot'))}
nodes.forEach(n=>{n.addEventListener('click',()=>{clear();n.classList.add('active');const id=n.dataset.id;edges.forEach(e=>{if(e.dataset.from===id||e.dataset.to===id)e.classList.add('hot')});detail.innerHTML='<b>'+safe(n.dataset.label||id)+'</b><br>'+safe(n.dataset.kind||'')+' · '+safe(n.dataset.group||'ungrouped')+'<br><br>'+safe(n.dataset.detail||'No further detail.')});n.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();n.click()}})});
function safe(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
const theme=document.getElementById('theme');theme.onclick=()=>{const root=document.documentElement;const light=root.dataset.theme==='light';root.dataset.theme=light?'dark':'light';theme.textContent=light?'Light':'Dark'};
</script>
</body></html>"""
    }

    private fun kindColor(kind: String): String = when (kind.lowercase(Locale.US)) {
        "frontend", "actor", "user" -> "#67e8f9"
        "backend", "service", "step" -> "#7c9cff"
        "database", "store" -> "#c084fc"
        "cloud", "infra" -> "#5eead4"
        "security", "gate", "decision" -> "#fbbf24"
        "messagebus", "queue" -> "#f472b6"
        "external", "thirdparty" -> "#94a3b8"
        "state" -> "#a3e635"
        else -> "#7c9cff"
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
