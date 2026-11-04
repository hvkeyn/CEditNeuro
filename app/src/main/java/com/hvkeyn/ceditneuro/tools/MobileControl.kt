package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.access.MobileAccessibilityService
import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Mobile computer-use loop: a snapshot gives every actionable element a ref, an action
 * targets one ref, and the tool returns a fresh snapshot to verify the result.
 * It uses Android's accessibility hierarchy, not screenshot coordinates.
 */
data class MobileNode(
    val ref: String,
    val role: String,
    val text: String,
    val desc: String,
    val id: String,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val password: Boolean,
) {
    val cx: Int get() = (x1 + x2) / 2
    val cy: Int get() = (y1 + y2) / 2
    val label: String get() = text.ifBlank { desc }.ifBlank { id }
    val actions: List<String>
        get() = buildList {
            if (clickable || checkable) add("tap")
            if (longClickable) add("long_click")
            if (editable) add("set_text")
            if (scrollable) add("scroll")
            if (checkable) add(if (checked) "uncheck" else "check")
        }
}

data class MobileSnapshot(
    val id: String,
    val width: Int,
    val height: Int,
    val packageName: String,
    val nodes: List<MobileNode>,
) {
    fun render(maxChars: Int = 7_000): String = buildString {
        appendLine("screen ${width}x$height snapshot=$id pkg=${packageName.ifBlank { "?" }} nodes=${nodes.size}")
        appendLine("refs belong to this snapshot. After an action, use the new refs; never reuse an old one.")
        for (node in nodes) {
            val line = buildString {
                append(node.ref)
                append(' ')
                append(node.role)
                val label = node.label
                if (label.isNotBlank()) {
                    append(" \"")
                    append(label.take(80))
                    append('"')
                }
                append(" bounds=[")
                append(node.x1).append(',').append(node.y1).append(',')
                append(node.x2).append(',').append(node.y2).append(']')
                append(" center=").append(node.cx).append(',').append(node.cy)
                if (node.actions.isNotEmpty()) append(" actions=").append(node.actions.joinToString(","))
                if (!node.enabled) append(" disabled")
                if (node.checked) append(" checked")
                if (node.password) append(" password")
            }
            if (length + line.length + 1 > maxChars) {
                appendLine("… snapshot truncated")
                break
            }
            appendLine(line)
        }
    }
}

class MobileUiStore {
    @Volatile
    var last: MobileSnapshot? = null

    fun store(snapshot: MobileSnapshot): MobileSnapshot {
        last = snapshot
        return snapshot
    }

    fun requireCurrent(ref: String): MobileSnapshot? {
        val snapshot = last ?: return null
        if (!ref.startsWith("@${snapshot.id}:")) return null
        return snapshot
    }
}

internal object MobileUiParser {
    private val nodeTag = Regex("""<node\b[^>]*>""")
    private val attr = Regex("""([a-zA-Z0-9_:-]+)="([^"]*)"""")

    fun parse(xml: String, id: String): MobileSnapshot {
        val raw = nodeTag.findAll(xml).mapNotNull { match ->
            val values = attr.findAll(match.value).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val bounds = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(values["bounds"].orEmpty())
                ?: return@mapNotNull null
            val x1 = bounds.groupValues[1].toInt()
            val y1 = bounds.groupValues[2].toInt()
            val x2 = bounds.groupValues[3].toInt()
            val y2 = bounds.groupValues[4].toInt()
            if (x2 - x1 < 2 || y2 - y1 < 2) return@mapNotNull null
            val simple = values["class"].orEmpty().substringAfterLast('.').ifBlank { "View" }
            val text = values["text"].orEmpty().trim()
            val desc = values["content-desc"].orEmpty().trim()
            val editable = simple.contains("EditText", ignoreCase = true)
            val clickable = values["clickable"] == "true"
            val longClickable = values["long-clickable"] == "true"
            val checkable = values["checkable"] == "true"
            val scrollable = values["scrollable"] == "true"
            val password = values["password"] == "true"
            val interactive = clickable || longClickable || checkable || scrollable || editable
            if (!interactive && text.isBlank() && desc.isBlank()) return@mapNotNull null
            if (values["package"].orEmpty() == "com.android.systemui" && !interactive) return@mapNotNull null
            Triple(
                values,
                simple,
                Bounds(x1, y1, x2, y2),
            )
        }.toList()
        val width = raw.maxOfOrNull { it.third.x2 } ?: 0
        val height = raw.maxOfOrNull { it.third.y2 } ?: 0
        val nodes = raw.take(100).mapIndexed { index, (values, simple, bounds) ->
            val password = values["password"] == "true"
            MobileNode(
                ref = "@$id:e$index",
                role = simple,
                text = if (password) "" else values["text"].orEmpty().trim(),
                desc = if (password) "" else values["content-desc"].orEmpty().trim(),
                id = values["resource-id"].orEmpty().substringAfter('/'),
                x1 = bounds.x1,
                y1 = bounds.y1,
                x2 = bounds.x2,
                y2 = bounds.y2,
                clickable = values["clickable"] == "true",
                longClickable = values["long-clickable"] == "true",
                editable = simple.contains("EditText", ignoreCase = true),
                scrollable = values["scrollable"] == "true",
                checkable = values["checkable"] == "true",
                checked = values["checked"] == "true",
                enabled = values["enabled"] != "false",
                password = password,
            )
        }
        val pkg = raw.firstOrNull()?.first?.get("package").orEmpty()
        return MobileSnapshot(id, width, height, pkg, nodes)
    }

    private fun unescape(value: String): String =
        value.replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&#10;", " ").replace("&amp;", "&")

    private data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
}

private suspend fun dumpMobileUi(
    runner: ShizukuCommandRunner,
    prepare: suspend () -> String?,
    dumpFile: File,
): Result<String> {
    val blocked = prepare()
    if (blocked != null) return Result.failure(IllegalStateException(blocked))
    val remote = "/data/local/tmp/ceditneuro-mobile.xml"
    dumpFile.parentFile?.mkdirs()
    dumpFile.delete()
    val quoted = { path: String -> "'" + path.replace("'", "'\\''") + "'" }
    val dumped = runCatching {
        runner.executeShizukuCommand(
            "uiautomator dump --compressed ${quoted(remote)} && cp ${quoted(remote)} ${quoted(dumpFile.absolutePath)} && rm -f ${quoted(remote)}",
            timeoutSeconds = 45,
        )
    }.getOrElse { return Result.failure(it) }
    if (shellFailed(dumped)) {
        dumpFile.delete()
        return Result.failure(IllegalStateException(shellPayload(dumped).ifBlank { dumped }))
    }
    if (!dumpFile.isFile || dumpFile.length() <= 0L) {
        return Result.failure(IllegalStateException("UI dump was not written. ${shellPayload(dumped)}"))
    }
    val xml = runCatching { dumpFile.readText().take(1_500_000) }
        .getOrElse { return Result.failure(it) }
    dumpFile.delete()
    if (!xml.contains("<hierarchy")) {
        return Result.failure(IllegalStateException("UI dump was empty or malformed."))
    }
    return Result.success(xml)
}

internal object MobileTextCollector {
    fun page(snapshot: MobileSnapshot): List<String> =
        snapshot.nodes.asSequence()
            .filter { !it.password }
            .mapNotNull { node ->
                val label = node.label.trim().replace(Regex("\\s+"), " ")
                if (label.isBlank()) null else (node.y1 to node.x1) to label
            }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .map { it.second }
            .distinct()
            .take(300)
            .toList()

    fun appendPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1)
            .firstOrNull { accumulated.takeLast(it) == page.take(it) } ?: 0
        return accumulated + page.drop(overlap)
    }

    fun prependPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1)
            .firstOrNull { page.takeLast(it) == accumulated.take(it) } ?: 0
        return page.dropLast(overlap) + accumulated
    }
}

class UiFindTool(private val store: MobileUiStore) : Tool {
    override val name = "ui_find"
    override val description =
        "Find elements in the latest ui_snapshot by text, resource id, or role. " +
            "Returns refs that ui_act can use; call ui_snapshot first. No shell or screen dump."
    override val parameters = objectSchema(
        properties = mapOf(
            "text" to stringProp("Case-insensitive text or accessibility description."),
            "resourceId" to stringProp("Full or partial Android resource id."),
            "role" to stringProp("Full or partial class/role."),
            "limit" to intProp("Maximum matches, 1-50 (default 20)."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val snapshot = store.last ?: return@withContext ToolResult.error("Call ui_snapshot first.")
        val text = args.stringArg("text")?.trim().orEmpty()
        val id = args.stringArg("resourceId")?.trim().orEmpty()
        val role = args.stringArg("role")?.trim().orEmpty()
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 50)
        val matches = snapshot.nodes.filter { node ->
            (text.isEmpty() || node.label.contains(text, ignoreCase = true)) &&
                (id.isEmpty() || node.id.contains(id, ignoreCase = true)) &&
                (role.isEmpty() || node.role.contains(role, ignoreCase = true))
        }
        if (matches.isEmpty()) return@withContext ToolResult.ok("No match in snapshot ${snapshot.id}.")
        ToolResult.ok(
            buildString {
                appendLine("snapshot=${snapshot.id} matches=${matches.size} (${matches.size.coerceAtMost(limit)})")
                matches.take(limit).forEach { node ->
                    append(node.ref).append(' ').append(node.role)
                    if (node.label.isNotBlank()) append(" \"").append(node.label.take(100)).append('"')
                    append(" center=").append(node.cx).append(',').append(node.cy)
                    if (node.actions.isNotEmpty()) append(" actions=").append(node.actions.joinToString(","))
                    appendLine()
                }
            },
        )
    }
}

class UiCollectTool(
    private val store: MobileUiStore,
) : Tool {
    override val name = "ui_collect"
    override val description =
        "Collect text from several screens by scrolling in the current app, merging overlapping pages. " +
            "Uses the user-enabled accessibility service. direction up/down, pages 1-20, maxItems 1-500. " +
            "Stops when the app changes or two pages add nothing. Call ui_snapshot first."
    override val parameters = objectSchema(
        properties = mapOf(
            "direction" to stringProp("down for newer/lower content, up for older/upper content."),
            "pages" to intProp("Maximum screens to inspect, 1-20 (default 8)."),
            "maxItems" to intProp("Maximum lines to return, 1-500 (default 300)."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!MobileAccessibilityService.connected) {
            return@withContext ToolResult.error(
                "ui_collect needs the user-enabled CEdiNeuro Accessibility service. Enable it in Settings > Accessibility, then take ui_snapshot first.",
            )
        }
        val direction = args.stringArg("direction")?.trim()?.lowercase().orEmpty()
        if (direction !in setOf("up", "down")) return@withContext ToolResult.error("direction is up or down.")
        val pages = (args.intArg("pages") ?: 8).coerceIn(1, 20)
        val maxItems = (args.intArg("maxItems") ?: 300).coerceIn(1, 500)
        var snapshot = store.last ?: MobileAccessibilityService.snapshotNow()
            ?: return@withContext ToolResult.error("Call ui_snapshot first.")
        store.store(snapshot)
        val app = snapshot.packageName
        var collected = MobileTextCollector.page(snapshot)
        var inspected = 1
        var scrolls = 0
        var noNew = 0
        var stopped = "page_limit"
        while (inspected < pages && collected.size < maxItems) {
            val scrollNode = snapshot.nodes.firstOrNull { it.scrollable }
            if (scrollNode == null) {
                stopped = "no_scrollable"
                break
            }
            val forward = direction == "down"
            val error = MobileAccessibilityService.actNow(
                scrollNode.ref,
                if (forward) "scroll_forward" else "scroll_backward",
                "",
            )
            if (error != null) { stopped = "scroll_failed"; break }
            delay(450)
            val next = MobileAccessibilityService.snapshotNow()
            if (next == null) {
                stopped = "snapshot_failed"
                break
            }
            store.store(next)
            if (next.packageName != app) { stopped = "app_switched"; break }
            val pageText = MobileTextCollector.page(next)
            val before = collected.size
            collected = if (forward) {
                MobileTextCollector.appendPage(collected, pageText)
            } else {
                MobileTextCollector.prependPage(collected, pageText)
            }
            inspected++
            scrolls++
            snapshot = next
            noNew = if (collected.size == before) noNew + 1 else 0
            if (noNew >= 2) { stopped = "no_new_content"; break }
            if (collected.size >= maxItems) { stopped = "item_limit"; break }
        }
        val output = collected.take(maxItems)
        ToolResult.ok(
            buildString {
                appendLine("snapshot=${snapshot.id} app=${app.ifBlank { "?" }} pages=$inspected scrolls=$scrolls")
                appendLine("stoppedBecause=$stopped items=${output.size} truncated=${collected.size > maxItems}")
                append(output.joinToString("\n").take(24_000))
            },
        )
    }
}

class UiSnapshotTool(
    private val runner: ShizukuCommandRunner,
    private val prepare: suspend () -> String?,
    private val dumpFile: File,
    private val store: MobileUiStore,
) : Tool {
    override val name = "ui_snapshot"
    override val description =
        "Read the current phone screen as accessibility elements with snapshot-scoped refs. " +
            "Returns role, label, bounds, center, and available actions for each element. " +
            "Use a ref with ui_act, then read the returned new snapshot. Prefers the user-enabled CEdiNeuro accessibility service; falls back to Shizuku or root."
    override val parameters = objectSchema(emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        MobileAccessibilityService.snapshotNow()?.let { snapshot ->
            store.store(snapshot)
            return@withContext ToolResult.ok("source=accessibility\n" + snapshot.render())
        }
        val xml = dumpMobileUi(runner, prepare, dumpFile).getOrElse {
            return@withContext ToolResult.error(it.message ?: "UI snapshot failed.")
        }
        val id = "s" + Integer.toHexString(xml.hashCode()) + System.currentTimeMillis().toString(36).takeLast(3)
        val snapshot = MobileUiParser.parse(xml, id)
        if (snapshot.nodes.isEmpty()) return@withContext ToolResult.error("The screen had no readable controls.")
        store.store(snapshot)
        ToolResult.ok(snapshot.render())
    }
}

class UiActTool(
    private val runner: ShizukuCommandRunner,
    private val prepare: suspend () -> String?,
    private val dumpFile: File,
    private val store: MobileUiStore,
) : Tool {
    override val name = "ui_act"
    override val description =
        "Act on a ref from the latest ui_snapshot, then return a fresh snapshot. " +
            "action: tap, long_click, set_text, clear_text, scroll_forward, scroll_backward, check, uncheck, launch, back, home, recents, wait. " +
            "Prefers the user-enabled accessibility service; falls back to Shizuku/root. " +
            "ref is required for element actions. set_text uses text. " +
            "Refs are snapshot-scoped; stale refs are refused. Never type a password unless the user asked and the field is focused."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("Action name."),
            "ref" to stringProp("Ref from ui_snapshot, e.g. @s1a2b:e4."),
            "text" to stringProp("Text for set_text."),
            "clear_first" to boolProp("Clear the field before set_text. Default false."),
            "package" to stringProp("Application id for launch."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.stringArg("action")?.trim()?.lowercase().orEmpty()
        if (action.isBlank()) return@withContext ToolResult.error("action is required.")
        val accessibility = MobileAccessibilityService.connected
        if (accessibility) {
            val ref = args.stringArg("ref")?.trim().orEmpty()
            val text = args.stringArg("text").orEmpty()
            val error = when (action) {
                "back", "home", "recents" -> MobileAccessibilityService.globalNow(action)
                "wait" -> { delay(800); null }
                "launch" -> {
                    val pkg = args.stringArg("package")?.trim().orEmpty()
                    if (!Regex("^[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+$").matches(pkg)) {
                        return@withContext ToolResult.error("launch requires a valid package id.")
                    }
                    MobileAccessibilityService.launchNow(pkg)
                }
                else -> {
                    if (ref.isBlank()) return@withContext ToolResult.error("ref is required for $action.")
                    MobileAccessibilityService.actNow(ref, action, text)
                }
            }
            if (error != null) return@withContext ToolResult.error(error)
            delay(300)
            val fresh = MobileAccessibilityService.snapshotNow()
                ?: return@withContext ToolResult.ok("$action completed; the fresh snapshot is empty.")
            store.store(fresh)
            return@withContext ToolResult.ok("source=accessibility $action\n\n" + fresh.render(4_500))
        }
        val command: String
        val detail: String
        if (action in setOf("back", "home", "recents", "wait", "launch")) {
            command = when (action) {
                "back" -> "input keyevent 4"
                "home" -> "input keyevent 3"
                "recents" -> "input keyevent 187"
                "launch" -> {
                    val pkg = args.stringArg("package")?.trim().orEmpty()
                    if (!Regex("^[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+$").matches(pkg)) {
                        return@withContext ToolResult.error("launch requires a valid package id.")
                    }
                    "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                }
                else -> ""
            }
            detail = if (action == "wait") "waited 800 ms" else "system $action"
        } else {
            val ref = args.stringArg("ref")?.trim().orEmpty()
            val snapshot = store.requireCurrent(ref)
                ?: return@withContext ToolResult.error("STALE_REF: call ui_snapshot again and use a ref from that snapshot.")
            val node = snapshot.nodes.firstOrNull { it.ref == ref }
                ?: return@withContext ToolResult.error("Unknown ref $ref. Call ui_snapshot again.")
            if (!node.enabled) return@withContext ToolResult.error("That element is disabled.")
            val text = args.stringArg("text").orEmpty()
            command = when (action) {
                "tap" -> "input tap ${node.cx} ${node.cy}"
                "long_click" -> "input swipe ${node.cx} ${node.cy} ${node.cx} ${node.cy} 650"
                "check", "uncheck" -> "input tap ${node.cx} ${node.cy}"
                "scroll_forward" -> "input swipe ${node.cx} ${(node.y2 - (node.y2 - node.y1) / 5).coerceAtLeast(node.y1 + 4)} ${node.cx} ${(node.y1 + (node.y2 - node.y1) / 5).coerceAtMost(node.y2 - 4)} 300"
                "scroll_backward" -> "input swipe ${node.cx} ${(node.y1 + (node.y2 - node.y1) / 5).coerceAtMost(node.y2 - 4)} ${node.cx} ${(node.y2 - (node.y2 - node.y1) / 5).coerceAtLeast(node.y1 + 4)} 300"
                "set_text" -> {
                    if (!node.editable) return@withContext ToolResult.error("That element is not editable.")
                    if (text.isEmpty()) return@withContext ToolResult.error("text is required for set_text.")
                    val quoted = "'" + text.replace("'", "'\\''").replace("%", "\\%").replace(" ", "%s") + "'"
                    if (args.boolArg("clear_first") == true) {
                        "input tap ${node.cx} ${node.cy}; input keyevent 123; for i in $(seq 1 120); do input keyevent 67; done; input text $quoted"
                    } else {
                        "input tap ${node.cx} ${node.cy}; input text $quoted"
                    }
                }
                "clear_text" -> {
                    if (!node.editable) return@withContext ToolResult.error("That element is not editable.")
                    "input tap ${node.cx} ${node.cy}; input keyevent 123; for i in $(seq 1 120); do input keyevent 67; done"
                }
                else -> return@withContext ToolResult.error("Unknown action '$action'.")
            }
            detail = "$action ${node.ref} ${node.label.take(60)}"
        }
        if (command.isNotBlank()) {
            val raw = runCatching { runner.executeShizukuCommand(command, timeoutSeconds = 30) }
                .getOrElse { return@withContext ToolResult.error(it.message ?: "UI action failed.") }
            if (shellFailed(raw)) return@withContext ToolResult.error("UI action failed: ${shellPayload(raw).ifBlank { raw }}")
        }
        delay(500)
        val xml = dumpMobileUi(runner, prepare, dumpFile).getOrElse {
            return@withContext ToolResult.ok("$detail. New snapshot failed: ${it.message}")
        }
        val id = "s" + Integer.toHexString(xml.hashCode()) + System.currentTimeMillis().toString(36).takeLast(3)
        val snapshot = MobileUiParser.parse(xml, id)
        store.store(snapshot)
        ToolResult.ok("$detail\n\n${snapshot.render(4_500)}")
    }
}
