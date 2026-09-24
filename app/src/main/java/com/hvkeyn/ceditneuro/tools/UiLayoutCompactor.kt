package com.hvkeyn.ceditneuro.tools

/**
 * Turns a uiautomator hierarchy into a short list the model can tap from.
 * Empty layouts, spacers, and duplicate bounds stay out of the prompt.
 */
internal object UiLayoutCompactor {
    data class Report(
        val text: String,
        val rawNodes: Int,
        val kept: Int,
        val rawChars: Int,
    )

    fun compact(xml: String, maxChars: Int = 8_000): Report {
        val parsed = parse(xml)
        val merged = mergeSameBounds(parsed.nodes)
        val useful = merged.filter { keep(it) }
        val folded = foldLabelsIntoTargets(useful)
        val ordered = folded.sortedWith(compareBy({ it.y1 }, { it.x1 }, { it.y2 }))
        val body = fit(ordered, maxChars)
        val header = buildString {
            append("screen ")
            append(parsed.width)
            append('x')
            append(parsed.height)
            append(" rotation=")
            append(parsed.rotation)
            append(" kept=")
            append(body.lines.size)
            append('/')
            append(parsed.nodes.size)
            append(" raw_chars=")
            append(xml.length)
            append('\n')
            append("tap=X,Y is the center of that row. Pass X and Y to execute_system_action.")
            if (body.omitted > 0) append(" omitted=").append(body.omitted)
            append('\n')
        }
        return Report(
            text = header + body.lines.joinToString("\n"),
            rawNodes = parsed.nodes.size,
            kept = body.lines.size,
            rawChars = xml.length,
        )
    }

    private fun keep(node: Node): Boolean {
        if (node.w < 2 || node.h < 2) return false
        if (node.simpleClass == "Space") return false
        if (node.pkg == "com.android.systemui" && !node.clickable && !node.longClickable) return false
        val chrome = node.simpleClass.contains("StatusBar") || node.simpleClass.contains("NavigationBar")
        if (chrome && !node.clickable && !node.longClickable) return false
        val labeled = node.text.isNotBlank() || node.desc.isNotBlank()
        val interactive = node.clickable || node.longClickable || node.checkable || node.scrollable || node.password
        if (!node.enabled && !labeled && !interactive) return false
        if (interactive || node.editable) return true
        if (!labeled) return false
        if (node.simpleClass.endsWith("Layout") || node.simpleClass.endsWith("ViewGroup")) return false
        return true
    }

    /** A text line that only names a button becomes that button's label. */
    private fun foldLabelsIntoTargets(nodes: List<Node>): List<Node> {
        val hosts = nodes.map { it }.toMutableList()
        val drop = mutableSetOf<Int>()
        for (index in hosts.indices) {
            val label = hosts[index]
            if (label.clickable || label.longClickable || label.checkable || label.scrollable) continue
            if (label.text.isBlank()) continue
            var best = -1
            var bestArea = Int.MAX_VALUE
            for (hostIndex in hosts.indices) {
                if (hostIndex == index) continue
                val host = hosts[hostIndex]
                if (!host.clickable && !host.longClickable && !host.checkable) continue
                if (!contains(host, label) || host.area >= bestArea) continue
                best = hostIndex
                bestArea = host.area
            }
            if (best < 0) continue
            val host = hosts[best]
            val coverage = if (host.area == 0) 0 else label.area * 100 / host.area
            if (coverage < 35 || host.text.isNotBlank()) continue
            hosts[best] = host.copy(
                text = label.text,
                desc = host.desc.ifBlank { label.desc },
                id = host.id.ifBlank { label.id },
            )
            drop += index
        }
        return hosts.filterIndexed { index, _ -> index !in drop }
    }

    private fun contains(host: Node, child: Node): Boolean {
        val cx = (child.x1 + child.x2) / 2
        val cy = (child.y1 + child.y2) / 2
        return cx in host.x1..host.x2 && cy in host.y1..host.y2 && child.area <= host.area
    }

    private fun mergeSameBounds(nodes: List<Node>): List<Node> {
        val order = linkedMapOf<String, Node>()
        for (node in nodes) {
            val key = "${node.x1},${node.y1},${node.x2},${node.y2}"
            val previous = order[key]
            order[key] = if (previous == null) node else previous.merge(node)
        }
        return order.values.toList()
    }

    private fun fit(nodes: List<Node>, maxChars: Int): Fitted {
        val interactive = nodes.filter { it.interactive || it.editable }
        val labels = nodes.filterNot { it.interactive || it.editable }
        val chosen = ArrayList<Node>(nodes.size)
        var used = 0
        var omitted = 0
        fun accept(node: Node): Boolean {
            val line = node.render()
            if (used + line.length + 1 > maxChars && chosen.isNotEmpty()) return false
            chosen += node
            used += line.length + 1
            return true
        }
        for (node in interactive) {
            if (!accept(node)) omitted++
        }
        for (node in labels) {
            if (!accept(node)) omitted++
        }
        val lines = chosen.sortedWith(compareBy({ it.y1 }, { it.x1 })).map { it.render() }.distinct()
        omitted += chosen.size - lines.size
        return Fitted(lines, omitted.coerceAtLeast(0))
    }

    private fun parse(xml: String): Parsed {
        val rotation = Regex("""<hierarchy\b[^>]*\brotation="(\d+)"""").find(xml)?.groupValues?.get(1) ?: "0"
        val nodes = ArrayList<Node>()
        var index = 0
        while (index < xml.length) {
            val start = xml.indexOf("<node", index)
            if (start < 0) break
            val tag = readTag(xml, start) ?: break
            nodeFrom(tag.first)?.let { nodes += it }
            index = tag.second
        }
        val width = nodes.maxOfOrNull { it.x2 } ?: 0
        val height = nodes.maxOfOrNull { it.y2 } ?: 0
        return Parsed(nodes, width, height, rotation)
    }

    private fun readTag(xml: String, start: Int): Pair<String, Int>? {
        var quote = false
        var index = start
        while (index < xml.length) {
            when (xml[index]) {
                '"' -> quote = !quote
                '>' -> if (!quote) return xml.substring(start, index + 1) to (index + 1)
            }
            index++
        }
        return null
    }

    private fun nodeFrom(tag: String): Node? {
        val values = HashMap<String, String>()
        for (match in ATTR.findAll(tag)) {
            values[match.groupValues[1]] = unescape(match.groupValues[2])
        }
        val bounds = BOUNDS.find(values["bounds"].orEmpty()) ?: return null
        val x1 = bounds.groupValues[1].toInt()
        val y1 = bounds.groupValues[2].toInt()
        val x2 = bounds.groupValues[3].toInt()
        val y2 = bounds.groupValues[4].toInt()
        val className = values["class"].orEmpty()
        val simple = className.substringAfterLast('.')
        val password = values["password"] == "true"
        return Node(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            simpleClass = simple.ifBlank { "View" },
            text = if (password) "" else values["text"].orEmpty().trim(),
            desc = if (password) "" else values["content-desc"].orEmpty().trim(),
            id = values["resource-id"].orEmpty().substringAfter('/'),
            pkg = values["package"].orEmpty(),
            clickable = values["clickable"] == "true",
            longClickable = values["long-clickable"] == "true",
            checkable = values["checkable"] == "true",
            checked = values["checked"] == "true",
            scrollable = values["scrollable"] == "true",
            enabled = values["enabled"] != "false",
            password = password,
            selected = values["selected"] == "true",
            editable = simple.contains("EditText"),
        )
    }

    private fun unescape(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#10;", " ")
            .replace("&amp;", "&")

    private data class Parsed(
        val nodes: List<Node>,
        val width: Int,
        val height: Int,
        val rotation: String,
    )

    private data class Fitted(val lines: List<String>, val omitted: Int)

    private data class Node(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val simpleClass: String,
        val text: String,
        val desc: String,
        val id: String,
        val pkg: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val password: Boolean,
        val selected: Boolean,
        val editable: Boolean,
    ) {
        val w: Int get() = x2 - x1
        val h: Int get() = y2 - y1
        val area: Int get() = w.coerceAtLeast(0) * h.coerceAtLeast(0)
        val interactive: Boolean
            get() = clickable || longClickable || checkable || scrollable || password

        fun merge(other: Node): Node = copy(
            simpleClass = if (other.text.isNotBlank() || other.clickable) other.simpleClass else simpleClass,
            text = text.ifBlank { other.text },
            desc = desc.ifBlank { other.desc },
            id = id.ifBlank { other.id },
            clickable = clickable || other.clickable,
            longClickable = longClickable || other.longClickable,
            checkable = checkable || other.checkable,
            checked = checked || other.checked,
            scrollable = scrollable || other.scrollable,
            enabled = enabled && other.enabled,
            password = password || other.password,
            selected = selected || other.selected,
            editable = editable || other.editable,
        )

        fun render(): String = buildString {
            append("tap=")
            append((x1 + x2) / 2)
            append(',')
            append((y1 + y2) / 2)
            append(" [")
            append(x1).append(',').append(y1).append(',').append(x2).append(',').append(y2)
            append("] ")
            append(simpleClass)
            if (text.isNotBlank()) append(" \"").append(escape(text.take(80))).append('"')
            if (desc.isNotBlank() && desc != text) append(" desc=\"").append(escape(desc.take(80))).append('"')
            if (id.isNotBlank()) append(" id=").append(id.take(40))
            if (clickable) append(" click")
            if (longClickable) append(" long")
            if (scrollable) append(" scroll")
            if (checkable) append(" check")
            if (checked) append(" checked")
            if (password) append(" password")
            if (selected) append(" selected")
            if (!enabled) append(" disabled")
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private val ATTR = Regex("""([\w:-]+)="([^"]*)"""")
    private val BOUNDS = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
}
