package com.hvkeyn.ceditneuro.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The HyperFrames bundled sound effects (Pixabay Content License), shipped in assets/sfx.
 * A cue resolves by manifest key, file name, or slug, then by one of its words, then by a synonym.
 */
class SfxLibrary(manifestJson: String) {
    data class Effect(val key: String, val file: String, val seconds: Double, val description: String)

    val effects: List<Effect> = Json.parseToJsonElement(manifestJson).jsonObject.map { (key, value) ->
        val entry = value.jsonObject
        Effect(
            key = key,
            file = entry["file"]?.jsonPrimitive?.content ?: "$key.mp3",
            seconds = entry["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0,
            description = entry["description"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    fun match(cue: String): Effect? {
        val slug = slug(cue.removeSuffix(".mp3"))
        if (slug.isEmpty()) return null
        effects.firstOrNull { it.key == slug || slug(it.file.removeSuffix(".mp3")) == slug }?.let { return it }
        val words = slug.split('-')
        for (word in words.reversed()) {
            effects.firstOrNull { it.key == word }?.let { return it }
            SYNONYMS[word]?.let { key -> effects.firstOrNull { it.key == key } }?.let { return it }
        }
        effects.firstOrNull { it.key.startsWith(slug) }?.let { return it }
        return null
    }

    fun catalog(): String = effects.joinToString("\n") { "${it.key} (${"%.2f".format(java.util.Locale.US, it.seconds)} s): ${it.description}" }

    companion object {
        fun slug(text: String): String =
            text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        private val SYNONYMS = mapOf(
            "swoosh" to "whoosh", "swish" to "whoosh", "swipe" to "whoosh-short", "transition" to "whoosh",
            "hit" to "impact-bass-1", "impact" to "impact-bass-1", "boom" to "impact-bass-1", "slam" to "impact-bass-1", "bass" to "impact-bass-1",
            "success" to "chime", "confirm" to "chime", "bell" to "chime",
            "alert" to "notification", "message" to "notification", "toast" to "notification",
            "type" to "typing", "keyboard" to "typing", "keys" to "typing",
            "keystroke" to "key-press", "key" to "key-press",
            "rise" to "riser", "build" to "riser", "buildup" to "riser", "swell" to "riser",
            "shimmer" to "sparkle", "shine" to "sparkle", "magic" to "sparkle",
            "fail" to "error", "wrong" to "error", "failure" to "error",
            "tap" to "click", "button" to "click", "ui" to "click",
            "spawn" to "pop", "appear" to "pop", "bubble" to "pop",
            "beep" to "ping", "blip" to "ping",
            "glitch" to "glitch-1", "distort" to "glitch-2",
        )
    }
}
