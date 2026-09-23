package com.hvkeyn.ceditneuro.ui.editor

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** Line highlighter for the languages the editor opens. No extra grammar package. */
class SourceLanguage(private val kind: Kind) : EmptyLanguage() {

    private val analyzer = LineAnalyzer(kind)

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun destroy() {
        analyzer.destroy()
    }

    enum class Kind { KOTLIN, JAVA, JS, PYTHON, SHELL, MARKUP, JSON, MARKDOWN, PLAIN }

    companion object {
        fun forPath(path: String): Language {
            val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
            val kind = when (ext) {
                "kt", "kts", "gradle" -> Kind.KOTLIN
                "java" -> Kind.JAVA
                "js", "jsx", "ts", "tsx" -> Kind.JS
                "py" -> Kind.PYTHON
                "sh", "bash", "zsh" -> Kind.SHELL
                "xml", "html", "htm", "svg" -> Kind.MARKUP
                "json" -> Kind.JSON
                "md", "markdown" -> Kind.MARKDOWN
                else -> Kind.PLAIN
            }
            return SourceLanguage(kind)
        }
    }
}

private class LineAnalyzer(private val kind: SourceLanguage.Kind) : SimpleAnalyzeManager<Unit>() {

    override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
        val builder = MappedSpans.Builder()
        var block = false
        var line = 0
        var start = 0
        val length = text.length
        while (start <= length) {
            val end = text.indexOf('\n', start).let { if (it < 0) length else it }
            block = paintLine(text, start, end, line, block, kind, builder)
            builder.determine(line)
            if (end >= length) break
            start = end + 1
            line++
        }
        return Styles(builder.build())
    }
}

private fun paintLine(
    text: StringBuilder,
    from: Int,
    to: Int,
    line: Int,
    block: Boolean,
    kind: SourceLanguage.Kind,
    builder: MappedSpans.Builder,
): Boolean {
    var index = from
    var inBlock = block
    builder.addIfNeeded(line, 0, normal)
    if (kind == SourceLanguage.Kind.MARKDOWN && lineStartsWith(text, from, to, '#')) {
        builder.addIfNeeded(line, 0, keyword)
        return false
    }
    while (index < to) {
        if (inBlock) {
            val close = text.indexOf("*/", index)
            if (close < 0 || close >= to) {
                builder.addIfNeeded(line, index - from, comment)
                return true
            }
            builder.addIfNeeded(line, index - from, comment)
            index = close + 2
            inBlock = false
            builder.addIfNeeded(line, index - from, normal)
            continue
        }
        val char = text[index]
        if (char == '/' && index + 1 < to && text[index + 1] == '/') {
            builder.addIfNeeded(line, index - from, comment)
            return false
        }
        if (char == '#' && (kind == SourceLanguage.Kind.PYTHON || kind == SourceLanguage.Kind.SHELL)) {
            builder.addIfNeeded(line, index - from, comment)
            return false
        }
        if (char == '/' && index + 1 < to && text[index + 1] == '*') {
            inBlock = true
            index += 2
            continue
        }
        if (char == '"' || char == '\'' || char == '`') {
            val end = endOfString(text, index, to, char)
            builder.addIfNeeded(line, index - from, literal)
            index = end
            builder.addIfNeeded(line, index - from, normal)
            continue
        }
        if (char == '@' || char == '<') {
            val end = endOfWord(text, index + 1, to)
            builder.addIfNeeded(line, index - from, if (char == '<') markup else annotation)
            index = end
            builder.addIfNeeded(line, index - from, normal)
            continue
        }
        if (char.isDigit()) {
            val end = endOfWord(text, index, to)
            builder.addIfNeeded(line, index - from, literal)
            index = end
            builder.addIfNeeded(line, index - from, normal)
            continue
        }
        if (char.isLetter() || char == '_') {
            val end = endOfWord(text, index, to)
            val word = text.substring(index, end)
            val style = if (isKeyword(word)) keyword else normal
            if (style != normal) builder.addIfNeeded(line, index - from, style)
            index = end
            if (style != normal) builder.addIfNeeded(line, index - from, normal)
            continue
        }
        index++
    }
    return inBlock
}

private fun lineStartsWith(text: StringBuilder, from: Int, to: Int, mark: Char): Boolean {
    var index = from
    while (index < to && (text[index] == ' ' || text[index] == '\t')) index++
    return index < to && text[index] == mark
}

private fun endOfString(text: StringBuilder, start: Int, limit: Int, quote: Char): Int {
    var index = start + 1
    while (index < limit) {
        if (text[index] == '\\') {
            index += 2
            continue
        }
        if (text[index] == quote) return index + 1
        index++
    }
    return limit
}

private fun endOfWord(text: StringBuilder, start: Int, limit: Int): Int {
    var index = start
    while (index < limit && (text[index].isLetterOrDigit() || text[index] == '_')) index++
    return index
}

private fun isKeyword(word: String): Boolean = word in KEYWORDS

private val normal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
private val keyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
private val comment = TextStyle.makeStyle(EditorColorScheme.COMMENT, true)
private val literal = TextStyle.makeStyle(EditorColorScheme.LITERAL)
private val annotation = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
private val markup = TextStyle.makeStyle(EditorColorScheme.HTML_TAG)

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "package", "import",
    "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "true", "false", "null", "this", "super", "in", "is", "as", "by", "get", "set",
    "override", "private", "public", "protected", "internal", "open", "abstract",
    "data", "sealed", "enum", "companion", "suspend", "inline", "lateinit",
    "public", "private", "protected", "static", "final", "void", "new", "extends",
    "implements", "throws", "throw", "instanceof", "switch", "case", "break",
    "continue", "default", "int", "long", "boolean", "float", "double", "char",
    "const", "let", "function", "export", "from", "async", "await", "typeof",
    "def", "elif", "lambda", "pass", "with", "yield", "None", "True", "False",
    "and", "or", "not", "fi", "then", "esac",
)
