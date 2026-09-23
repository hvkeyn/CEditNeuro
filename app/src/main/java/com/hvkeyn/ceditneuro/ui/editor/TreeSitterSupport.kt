package com.hvkeyn.ceditneuro.ui.editor

import android.content.Context
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.python.TSLanguagePython
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Tree-sitter highlighting for the grammars shipped with the app. A bad query or a
 * missing native library falls back to the line highlighter.
 */
object TreeSitterSupport {

    fun language(context: Context, path: String): Language {
        val key = grammarKey(path) ?: return SourceLanguage.forPath(path)
        return runCatching { build(context, key) }.getOrElse { SourceLanguage.forPath(path) }
    }

    fun grammar(path: String): TSLanguage? {
        val key = grammarKey(path) ?: return null
        return runCatching { nativeLanguage(key) }.getOrNull()
    }

    private fun build(context: Context, key: String): Language {
        val native = nativeLanguage(key)
        val highlights = read(context, "$key/highlights.scm")
        val blocks = read(context, "$key/blocks.scm")
        val brackets = read(context, "$key/brackets.scm")
        val spec = TsLanguageSpec(native, highlights, blocks, brackets)
        return TsLanguage(spec) {
            keyword applyTo arrayOf("keyword", "keyword.modifier", "keyword.type")
            comment applyTo "comment"
            stringStyle applyTo arrayOf("string", "string.special")
            function applyTo arrayOf("function", "function.method", "function.builtin", "method")
            type applyTo arrayOf("type", "type.builtin", "tag")
            attribute applyTo "attribute"
            number applyTo arrayOf("number", "constant", "constant.builtin", "variable.builtin")
        }
    }

    private fun read(context: Context, name: String): String =
        context.assets.open("tree-sitter/$name").bufferedReader().use { it.readText() }

    private fun grammarKey(path: String): String? {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "json" -> "json"
            "xml", "html", "htm", "svg" -> "xml"
            else -> null
        }
    }

    private fun nativeLanguage(key: String): TSLanguage = when (key) {
        "java" -> TSLanguageJava.getInstance()
        "kotlin" -> TSLanguageKotlin.getInstance()
        "python" -> TSLanguagePython.getInstance()
        "json" -> TSLanguageJson.getInstance()
        "xml" -> TSLanguageXml.getInstance()
        else -> error("No grammar for $key")
    }

    private val keyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
    private val comment = TextStyle.makeStyle(EditorColorScheme.COMMENT, true)
    private val stringStyle = TextStyle.makeStyle(EditorColorScheme.LITERAL)
    private val function = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
    private val type = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME)
    private val attribute = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
    private val number = TextStyle.makeStyle(EditorColorScheme.LITERAL)
}
