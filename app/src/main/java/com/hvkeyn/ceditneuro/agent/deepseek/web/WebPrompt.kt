package com.hvkeyn.ceditneuro.agent.deepseek.web

import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.FunctionCall
import com.hvkeyn.ceditneuro.agent.ToolCall
import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The DeepSeek web chat takes one prompt string and has no tool channel. This flattens the
 * chat into that prompt and reads tool calls back out of the reply text. The rules follow
 * Tsuev/opencode-deepseek `server/openai_format.py`, which is MIT licensed.
 */
object WebPrompt {
    private val json = Json { ignoreUnknownKeys = true }
    private val FENCE = Regex("```[a-zA-Z_]*\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val XML_BLOCK = Regex("<(tool_calls?|function_calls?)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val FUNC_TAG = Regex("<function\\b[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</function>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PARAM_TAG = Regex("<parameter\\b[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</parameter>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val DSML_SEP = Regex("function\\s*<\\|tool[_\\u2581]?sep\\|>\\s*([A-Za-z_][\\w\\-.]*)", RegexOption.IGNORE_CASE)
    private val DSML_TOKEN = Regex("<\\|[^|]*\\|>")
    private val TOOL_INTENT = Regex("(\"arguments\"|\"tool_calls\"|\"name\"\\s*:|```|<tool_call|<function_call|<\\|tool|function\\s*<\\|)", RegexOption.IGNORE_CASE)

    const val CONTINUE = "Your previous message was cut off by the output limit before it finished. " +
        "Continue EXACTLY from the character where it stopped. Output only the remaining characters — no preamble, " +
        "no apology, no repetition, and no opening code fence (only the missing closing one, if that is what was cut)."

    const val AFTER_TOOLS = "The Tool result lines above are the answers to your earlier calls; they already happened. " +
        "If they give you what the user asked for, reply to the user now in plain text with no tool_calls block. " +
        "Never repeat a call that already returned; call another tool only for the next step that is still missing."

    fun prompt(messages: List<ChatMessage>, tools: List<Tool>): String {
        val system = messages.filter { it.role == "system" }.mapNotNull { it.content?.takeIf(String::isNotBlank) }
        val convo = messages.filter { it.role != "system" }
        val instructions = toolInstructions(tools)
        if (system.isEmpty() && instructions.isEmpty() && convo.size == 1 && convo[0].role == "user") {
            return convo[0].content.orEmpty()
        }
        val lines = mutableListOf<String>()
        if (system.isNotEmpty()) lines += "System: " + system.joinToString("\n\n")
        convo.forEach { lines += render(it) }
        if (instructions.isNotEmpty()) lines += instructions
        if (convo.lastOrNull()?.role == "tool") lines += AFTER_TOOLS
        lines += "Assistant:"
        return lines.joinToString("\n\n")
    }

    private fun render(message: ChatMessage): String {
        val content = message.content.orEmpty()
        return when (message.role) {
            "assistant" -> {
                val calls = message.toolCalls.orEmpty()
                if (calls.isEmpty()) "Assistant: $content"
                else {
                    val called = "[called tools: " + calls.joinToString(", ") { it.function.name + "(" + it.function.arguments + ")" } + "]"
                    "Assistant: " + if (content.isBlank()) called else "$content $called"
                }
            }
            "tool" -> "Tool result (${message.name ?: "tool"}): $content"
            "user" -> "User: $content"
            else -> message.role.replaceFirstChar { it.uppercase() } + ": " + content
        }
    }

    fun toolInstructions(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        val rendered = tools.joinToString("\n") { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            }.toString()
        }
        val example = tools.first().name
        return "You can call functions (tools). Available tools, given as JSON Schema:\n\n" +
            rendered + "\n\n" +
            "To call one or more tools, your ENTIRE reply must be a single fenced code block in exactly this format and nothing else:\n\n" +
            "```tool_calls\n[{\"name\": \"<tool_name>\", \"arguments\": {<arguments>}}]\n```\n\n" +
            "Hard rules:\n" +
            "- The fenced `tool_calls` block must be the WHOLE reply. Never write any text, plan, explanation, or reasoning before or after it.\n" +
            "- Never describe the action you are about to take — perform it by emitting the block.\n" +
            "- Use the exact tool name and an `arguments` object matching that tool's schema. To call several tools, put multiple objects in the array.\n" +
            "- Never use XML/DSML tags such as <tool_call> or <|tool_calls|>.\n" +
            "- Only if no tool is needed, answer normally in plain text and do NOT emit a tool_calls block.\n\n" +
            "Example — to use the `$example` tool, reply with exactly:\n\n" +
            "```tool_calls\n[{\"name\": \"$example\", \"arguments\": {}}]\n```\n\n" +
            "REMINDER: if you still need to perform an action, respond with the fenced ```tool_calls block now, as your entire reply — no other text."
    }

    /** Splits a reply into the visible text and the tool calls, or null calls for plain text. */
    fun parse(text: String, names: Set<String>): Pair<String, List<ToolCall>?> {
        if (text.isBlank()) return text to null
        FENCE.findAll(text).map { it.groupValues[1] }.toList().asReversed().forEach { block ->
            coerce(block, names)?.let { return FENCE.replace(text, "").trim() to it }
        }
        XML_BLOCK.findAll(text).forEach { match ->
            xmlCalls(match.groupValues[2], names)?.let { return XML_BLOCK.replace(text, "").trim() to it }
        }
        dsml(text, names)?.let { (calls, start, end) ->
            return DSML_TOKEN.replace(text.substring(0, start) + text.substring(end), "").trim() to calls
        }
        jsonBlocks(text).forEach { block ->
            coerce(block, names)?.let { return text.replace(block, "").trim() to it }
        }
        pseudo(text, names)?.let { (calls, spans) ->
            var cleaned = text
            spans.sortedByDescending { it.first }.forEach { (s, e) -> cleaned = cleaned.substring(0, s) + cleaned.substring(e) }
            return cleaned.trim() to calls
        }
        coerce(text.trim(), names)?.let { return "" to it }
        return text to null
    }

    /** True when the reply looks cut off in the middle of a tool call. */
    fun truncated(text: String, names: Set<String>): Boolean {
        if (text.isEmpty()) return false
        if (Regex("```").findAll(text).count() % 2 == 1) return true
        for (tag in listOf("tool_call", "function_call")) {
            val opens = Regex("<$tag\\b", RegexOption.IGNORE_CASE).findAll(text).count()
            val closes = Regex("</$tag>", RegexOption.IGNORE_CASE).findAll(text).count()
            if (opens > closes) return true
        }
        return depth(text) > 0 && (TOOL_INTENT.containsMatchIn(text) || names.any { it in text })
    }

    private fun coerce(raw: String, names: Set<String>): List<ToolCall>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidates = linkedSetOf(trimmed).apply { addAll(jsonBlocks(trimmed)) }
        for (candidate in candidates) {
            val data = runCatching { json.parseToJsonElement(candidate) }.getOrNull() ?: continue
            fromData(data, names)?.let { return it }
        }
        return null
    }

    private fun fromData(data: JsonElement, names: Set<String>): List<ToolCall>? {
        var list: JsonElement = data
        if (data is JsonObject) {
            val key = listOf("tool_calls", "tool_call", "function_call", "calls").firstOrNull { data[it] is JsonArray }
            list = if (key != null) data.getValue(key) else JsonArray(listOf(data))
        }
        val items = list as? JsonArray ?: return null
        return items.mapNotNull { item(it, names) }.takeIf { it.isNotEmpty() }
    }

    private fun item(element: JsonElement, names: Set<String>): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val function = obj["function"] as? JsonObject
        fun str(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val name = str(obj, "name") ?: str(obj, "tool") ?: str(obj, "tool_name") ?: str(obj, "recipient_name") ?: str(function, "name")
            ?: return null
        if (names.isNotEmpty() && name !in names) return null
        val args = obj["arguments"] ?: function?.get("arguments") ?: obj["parameters"] ?: obj["args"] ?: obj["input"]
        val text = when {
            args == null -> "{}"
            args is JsonPrimitive && args.isString -> args.content
            else -> args.toString()
        }
        return call(name, text)
    }

    private fun call(name: String, arguments: String) =
        ToolCall(id = "call_" + UUID.randomUUID().toString().replace("-", "").take(24), function = FunctionCall(name, arguments))

    private fun xmlCalls(inner: String, names: Set<String>): List<ToolCall>? {
        val calls = FUNC_TAG.findAll(inner).mapNotNull { match ->
            val name = match.groupValues[1]
            if (names.isNotEmpty() && name !in names) return@mapNotNull null
            val params = PARAM_TAG.findAll(match.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val arguments = if (params.isNotEmpty()) {
                buildJsonObject { params.forEach { (k, v) -> put(k, v) } }.toString()
            } else {
                jsonBlocks(match.groupValues[2]).firstOrNull { runCatching { json.parseToJsonElement(it) }.isSuccess } ?: "{}"
            }
            call(name, arguments)
        }.toList()
        return calls.takeIf { it.isNotEmpty() } ?: coerce(inner, names)
    }

    private fun dsml(text: String, names: Set<String>): Triple<List<ToolCall>, Int, Int>? {
        val match = DSML_SEP.find(text) ?: return null
        val name = match.groupValues[1]
        if (names.isNotEmpty() && name !in names) return null
        var arguments = "{}"
        var end = match.range.last + 1
        val brace = (end until text.length).firstOrNull { text[it] == '{' || text[it] == '[' }
        if (brace != null) {
            val close = matchBracket(text, brace)
            if (close != null && runCatching { json.parseToJsonElement(text.substring(brace, close + 1)) }.isSuccess) {
                arguments = text.substring(brace, close + 1)
                end = close + 1
            }
        }
        return Triple(listOf(call(name, arguments)), match.range.first, end)
    }

    private fun pseudo(text: String, names: Set<String>): Pair<List<ToolCall>, List<Pair<Int, Int>>>? {
        if (names.isEmpty()) return null
        val calls = mutableListOf<ToolCall>()
        val spans = mutableListOf<Pair<Int, Int>>()
        for (name in names.sortedByDescending { it.length }) {
            Regex("(?<![\\w.])" + Regex.escape(name) + "\\s*\\(").findAll(text).forEach { match ->
                val open = match.range.last
                if (spans.any { open in it.first until it.second }) return@forEach
                val close = matchBracket(text, open) ?: return@forEach
                val args = pseudoArgs(text.substring(open + 1, close)) ?: return@forEach
                calls += call(name, args.toString())
                spans += match.range.first to close + 1
            }
        }
        return if (calls.isEmpty()) null else calls to spans
    }

    private fun pseudoArgs(inner: String): JsonObject? {
        val trimmed = inner.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        if (trimmed[0] == '{') return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        val out = LinkedHashMap<String, JsonElement>()
        for (part in splitTop(trimmed)) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            val key = part.substring(0, eq).trim().trim('\'', '"')
            if (key.isEmpty()) return null
            val value = part.substring(eq + 1).trim()
            out[key] = runCatching { json.parseToJsonElement(value) }.getOrNull()
                ?: JsonPrimitive(if (value.length >= 2 && value.first() == value.last() && value.first() in "'\"") value.substring(1, value.length - 1) else value)
        }
        return JsonObject(out)
    }

    private fun splitTop(text: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var escape = false
        for (c in text) {
            if (quote != null) {
                buf.append(c)
                if (escape) escape = false else if (c == '\\') escape = true else if (c == quote) quote = null
                continue
            }
            when {
                c == '\'' || c == '"' -> { quote = c; buf.append(c) }
                c in "{[(" -> { depth++; buf.append(c) }
                c in "}])" -> { depth--; buf.append(c) }
                c == ',' && depth == 0 -> { parts += buf.toString(); buf.clear() }
                else -> buf.append(c)
            }
        }
        parts += buf.toString()
        return parts
    }

    private fun jsonBlocks(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{' || text[i] == '[') {
                val end = matchBracket(text, i)
                if (end != null) {
                    out += text.substring(i, end + 1)
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun matchBracket(text: String, start: Int): Int? {
        val opener = text[start]
        val closer = when (opener) { '{' -> '}'; '[' -> ']'; '(' -> ')'; else -> return null }
        var depth = 0
        var quote: Char? = null
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (quote != null) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                opener -> depth++
                closer -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun depth(text: String): Int {
        var depth = 0
        var quote: Char? = null
        var escape = false
        for (c in text) {
            if (quote != null) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
            }
        }
        return depth
    }
}
