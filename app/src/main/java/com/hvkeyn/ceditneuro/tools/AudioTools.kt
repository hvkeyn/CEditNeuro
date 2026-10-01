package com.hvkeyn.ceditneuro.tools

import android.content.Context
import com.hvkeyn.ceditneuro.video.Composition
import com.hvkeyn.ceditneuro.video.MusicSynth
import com.hvkeyn.ceditneuro.video.SfxLibrary
import com.hvkeyn.ceditneuro.video.Speech
import com.hvkeyn.ceditneuro.video.Wav
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

private fun fmt(value: Double) = "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')

private fun audioTag(id: String, path: String, start: Double, seconds: Double?, volume: Double, track: Int): String =
    "<audio id=\"$id\" src=\"$path\" data-start=\"${fmt(start)}\"" +
        (seconds?.let { " data-duration=\"${fmt(it)}\"" } ?: "") +
        " data-track-index=\"$track\" data-volume=\"${fmt(volume)}\"></audio>"

private fun freeName(workspace: Workspace, path: String): String {
    if (!workspace.resolve(path).exists()) return path
    val stem = path.substringBeforeLast('.')
    val ext = path.substringAfterLast('.', "")
    var n = 2
    while (workspace.resolve("$stem-$n.$ext").exists()) n++
    return "$stem-$n.$ext"
}

/** The HyperFrames bundled effects, copied from the app into the project. */
class SoundEffectTool(
    private val context: Context,
    private val workspace: Workspace,
    private val onCreated: (String) -> Unit = {},
) : Tool {
    override val name = "sound_effect"
    override val description =
        "Copy a sound effect from the HyperFrames library bundled in the app (Pixabay license, free in videos) into the project. " +
            "name is a cue such as whoosh, pop, click, chime, riser, impact, glitch, typing, sparkle, ping, notification, error. " +
            "name=list prints every effect with its length and where it fits. at is the composition second it plays. " +
            "Prints the file and an audio tag to paste into the composition root. Nothing is downloaded or generated."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("The cue, or list."),
            "at" to stringProp("Optional second in the composition, for example 3.5."),
            "dir" to stringProp("Optional project folder. Defaults to assets/sfx."),
        ),
        required = listOf("name"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val library = SfxLibrary(context.assets.open("sfx/manifest.json").bufferedReader().readText())
        val cue = args.stringArg("name")?.trim().orEmpty()
        if (cue.isEmpty() || cue.equals("list", ignoreCase = true)) {
            return@withContext ToolResult.ok("Bundled effects (volume about 0.35, under voice and music):\n" + library.catalog())
        }
        val effect = library.match(cue)
            ?: return@withContext ToolResult.error("No bundled effect matches '$cue'. Call sound_effect name=list and pick one of those names.")
        val dir = args.stringArg("dir")?.trim()?.trim('/')?.ifBlank { null } ?: "assets/sfx"
        val path = "$dir/${effect.file}"
        val target = runCatching { workspace.resolve(path) }.getOrElse { return@withContext ToolResult.error(it.message ?: "Bad dir.") }
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            context.assets.open("sfx/${effect.file}").use { input -> target.outputStream().use { input.copyTo(it) } }
            onCreated(path)
        }
        val at = args.stringArg("at")?.trim()?.toDoubleOrNull() ?: 0.0
        val id = "sfx-${effect.key}-${fmt(at).replace('.', '-')}"
        val trigger = if (effect.key == "riser") " A riser peaks at its end: start it ${fmt(effect.seconds)} s before the reveal." else ""
        ToolResult.ok(
            "$path (${fmt(effect.seconds)} s). ${effect.description}$trigger\n" +
                "Paste inside the root:\n" + audioTag(id, path, at, effect.seconds, 0.35, 30),
        )
    }
}

/** A music bed rendered on the phone from the mood, tempo, key, and length the agent chose. */
class MakeMusicTool(
    private val workspace: Workspace,
    private val onCreated: (String) -> Unit = {},
) : Tool {
    override val name = "make_music"
    override val description =
        "Generate a background music bed on the phone as a WAV (no network, no package). " +
            "prompt is the brief in words (mood, genre, topic; Russian or English); the mood is inferred from it unless mood is set. " +
            "mood is one of calm, uplifting, corporate, cinematic, epic, tense, playful, dark, ambient, electronic. " +
            "for is the composition html, whose data-duration sets the length; otherwise seconds (1 to 300). " +
            "bpm 50 to 180, key C to B, scale major, minor, dorian, harmonic_minor, pentatonic, minor_pentatonic, " +
            "intensity 0 to 100 (layers: pad, then arpeggio and bass, then drums, then a melody), seed changes the tune. " +
            "Prints the file, the settings, and an audio tag to paste into the composition root."
    override val parameters = objectSchema(
        properties = mapOf(
            "prompt" to stringProp("The brief in words."),
            "for" to stringProp("Optional composition html; its length is used."),
            "seconds" to intProp("Length when there is no composition."),
            "mood" to stringProp("Optional mood."),
            "bpm" to intProp("Optional tempo."),
            "key" to stringProp("Optional key, for example A or F#."),
            "scale" to stringProp("Optional scale."),
            "intensity" to intProp("Optional 0 to 100."),
            "seed" to intProp("Optional number; another seed is another tune."),
            "voice" to boolProp("True when a voiceover plays over the music; the bed volume drops to 0.12."),
            "out" to stringProp("Optional wav path. Defaults to assets/bgm/track.wav."),
        ),
        required = listOf("prompt"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Default) {
        val prompt = args.stringArg("prompt")?.trim().orEmpty()
        val forPath = args.stringArg("for")?.trim()?.trimStart('/')?.ifBlank { null }
        val fromComposition = forPath?.let { path ->
            val file = runCatching { workspace.resolve(path) }.getOrNull()
            if (file == null || !file.isFile) return@withContext ToolResult.error("No file at $path.")
            Composition.parse(file.readText())?.seconds?.takeIf { it > 0 }
                ?: return@withContext ToolResult.error("$path has no data-duration on its composition root.")
        }
        val seconds = fromComposition ?: args.intArg("seconds")?.toDouble()
            ?: return@withContext ToolResult.error("Give for (the composition html) or seconds.")
        if (seconds > 300) return@withContext ToolResult.error("A bed is at most 300 seconds.")
        val mood = args.stringArg("mood")?.trim()?.uppercase()?.ifBlank { null }?.let { name ->
            MusicSynth.Mood.entries.firstOrNull { it.name == name }
                ?: return@withContext ToolResult.error("Unknown mood. Use one of ${MusicSynth.Mood.entries.joinToString { it.name.lowercase() }}.")
        }
        val scale = args.stringArg("scale")?.trim()?.uppercase()?.replace(' ', '_')?.ifBlank { null }?.let { name ->
            MusicSynth.Scale.entries.firstOrNull { it.name == name }
                ?: return@withContext ToolResult.error("Unknown scale. Use one of ${MusicSynth.Scale.entries.joinToString { it.name.lowercase() }}.")
        }
        val key = args.stringArg("key")?.trim()?.ifBlank { null }
        if (key != null && MusicSynth.noteIndex(key) == null) return@withContext ToolResult.error("key is a note name from C to B.")
        val spec = MusicSynth.spec(
            prompt = prompt,
            seconds = seconds,
            mood = mood,
            bpm = args.intArg("bpm"),
            key = key,
            scale = scale,
            intensity = args.intArg("intensity")?.coerceIn(0, 100)?.div(100.0),
            seed = args.intArg("seed")?.toLong(),
        )
        val wanted = args.stringArg("out")?.trim()?.trimStart('/')?.ifBlank { null } ?: "assets/bgm/track.wav"
        if (!wanted.endsWith(".wav", ignoreCase = true)) return@withContext ToolResult.error("out must end with .wav.")
        val path = wanted
        val out = runCatching { workspace.resolve(path) }.getOrElse { return@withContext ToolResult.error(it.message ?: "Bad out.") }
        val rate = 44_100
        val frames = (spec.seconds * rate).toInt()
        withContext(Dispatchers.IO) {
            Wav.write(out, rate, 2, frames) { sink -> MusicSynth.render(spec, rate, sink) }
        }
        onCreated(path)
        val volume = if (args.boolArg("voice") == true) 0.12 else 0.9
        ToolResult.ok(
            "$path (${out.length() / 1024} KB). ${spec.describe()}. " +
                "Made on the phone from these settings; a different seed or intensity gives another take.\n" +
                "Paste inside the root:\n" + audioTag("bgm", path, 0.0, spec.seconds, volume, 20),
        )
    }
}

/** A voiceover line through the phone's text-to-speech engine. */
class VoiceoverTool(
    private val context: Context,
    private val workspace: Workspace,
    private val onCreated: (String) -> Unit = {},
) : Tool {
    override val name = "voiceover"
    override val description =
        "Speak one line with the phone's text-to-speech engine into a WAV in the project. " +
            "language is a tag such as ru-RU or en-US (Cyrillic text defaults to ru-RU). rate and pitch are percent, 50 to 200 (default 100). " +
            "Prints the file, its length, and an audio tag. One call per line keeps each line placeable."
    override val parameters = objectSchema(
        properties = mapOf(
            "text" to stringProp("The words to speak."),
            "at" to stringProp("Optional composition second where the line starts."),
            "language" to stringProp("Optional language tag."),
            "rate" to intProp("Optional speed percent."),
            "pitch" to intProp("Optional pitch percent."),
            "out" to stringProp("Optional wav path. Defaults to assets/vo/line.wav with a free number."),
        ),
        required = listOf("text"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.stringArg("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("Missing 'text'.")
        if (text.length > 3_900) return ToolResult.error("One line is at most 3900 characters. Split it.")
        val asked = args.stringArg("out")?.trim()?.trimStart('/')?.ifBlank { null }
        if (asked != null && !asked.endsWith(".wav", ignoreCase = true)) return ToolResult.error("out must end with .wav.")
        val path = asked ?: freeName(workspace, "assets/vo/line.wav")
        val out = runCatching { workspace.resolve(path) }.getOrElse { return ToolResult.error(it.message ?: "Bad out.") }
        val result = Speech.toFile(
            context,
            text,
            out,
            args.stringArg("language"),
            (args.intArg("rate") ?: 100) / 100f,
            (args.intArg("pitch") ?: 100) / 100f,
        )
        if (!result.ok) return ToolResult.error(result.text)
        onCreated(path)
        val at = args.stringArg("at")?.trim()?.toDoubleOrNull() ?: 0.0
        val seconds = result.seconds
        val id = "vo-" + File(path).nameWithoutExtension
        return ToolResult.ok(
            "$path (${seconds?.let { fmt(it) + " s" } ?: "length unknown"}, ${result.text}).\n" +
                "Paste inside the root:\n" + audioTag(id, path, at, seconds, 1.0, 10),
        )
    }
}
