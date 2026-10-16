package com.hvkeyn.ceditneuro.video

/**
 * A cut of any video the phone can already play: in and out points, a caption,
 * and an optional short fade. The page is a composition render_video already encodes.
 */
object CutList {
    data class Piece(
        val start: Double,
        val end: Double,
        val caption: String = "",
        val fade: Boolean = false,
        val voice: String = "",
    )

    data class Sheet(val pieces: List<Piece>, val html: String, val seconds: Double)

    /**
     * One block on the timeline. `video` lane 0 is the main picture, lane 1 sits on top of it.
     * `music` is a separate bed. Effects are fade, gray, warm, zoom, mute, blur, and flip.
     * A transition is how this block enters: dissolve, wipe, or dip.
     */
    data class Clip(
        val kind: String,
        val lane: Int,
        val file: String,
        val at: Double,
        val start: Double,
        val end: Double,
        val effects: Set<String> = emptySet(),
        val caption: String = "",
        val speed: Double = 1.0,
        val volume: Double = 1.0,
        val transition: String = "",
    ) {
        /** How long this block lasts on the timeline. A faster speed shortens it. */
        fun span(): Double = (end - start) / speed.coerceIn(0.25, 4.0)
    }

    /** The edit that opens again in the reel editor. The picture stays a separate file. */
    data class Project(
        val source: String,
        val width: Int,
        val height: Int,
        val pieces: List<Piece>,
        val clips: List<Clip> = emptyList(),
    )

    val EFFECTS = listOf("fade", "gray", "warm", "zoom", "mute", "blur", "flip")
    val TRANSITIONS = listOf("dissolve", "wipe", "dip")

    fun load(text: String): Project? {
        var source = ""
        var width = 1080
        var height = 1920
        val body = StringBuilder()
        val clips = ArrayList<Clip>()
        for (raw in text.replace("\r\n", "\n").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("source:") -> source = line.substringAfter(':').trim()
                line.startsWith("width:") -> width = line.substringAfter(':').trim().toIntOrNull() ?: width
                line.startsWith("height:") -> height = line.substringAfter(':').trim().toIntOrNull() ?: height
                line.startsWith("clip ") -> clips += parseClip(line) ?: return null
                else -> body.append(line).append('\n')
            }
        }
        if (!safeName(source) || clips.size > 32) return null
        val pieces = if (body.toString().isBlank()) emptyList() else parse(body.toString()) ?: return null
        val timeline = if (clips.isNotEmpty()) clips else clipsFrom(source, pieces)
        return Project(source, width.coerceIn(2, 1920), height.coerceIn(2, 1920), pieces, timeline)
    }

    fun write(project: Project): String = buildString {
        append("source: ").append(project.source).append('\n')
        append("width: ").append(project.width).append('\n')
        append("height: ").append(project.height).append('\n')
        if (project.clips.isEmpty()) {
            for (piece in project.pieces) {
                append(num(piece.start)).append(' ').append(num(piece.end))
                if (piece.fade) append(" fade")
                if (piece.voice.isNotEmpty()) append(" voice:").append(piece.voice)
                if (piece.caption.isNotEmpty()) append(' ').append(piece.caption)
                append('\n')
            }
        } else {
            for (clip in project.clips) {
                append("clip ").append(clip.kind).append(' ').append(clip.lane).append(' ')
                append(clip.file).append(' ').append(num(clip.at)).append(' ')
                append(num(clip.start)).append(' ').append(num(clip.end))
                for (effect in EFFECTS) if (effect in clip.effects) append(' ').append(effect)
                if (clip.speed != 1.0) append(" speed:").append(num(clip.speed))
                if (clip.volume != 1.0) append(" volume:").append(num(clip.volume))
                if (clip.transition in TRANSITIONS) append(" transition:").append(clip.transition)
                if (clip.caption.isNotEmpty()) append(" caption:").append(clip.caption)
                append('\n')
            }
        }
    }

    /** Old in/out lines become the main picture, laid end to end, with a voice line on the music lane. */
    fun clipsFrom(source: String, pieces: List<Piece>): List<Clip> {
        var cursor = 0.0
        val clips = ArrayList<Clip>()
        for (piece in pieces) {
            val overlap = if (piece.fade && cursor > 0.0) 0.35 else 0.0
            val at = (cursor - overlap).coerceAtLeast(0.0)
            val dur = piece.end - piece.start
            val effects = if (piece.fade) setOf("fade") else emptySet()
            clips += Clip("video", 0, source, at, piece.start, piece.end, effects, piece.caption)
            if (piece.voice.isNotEmpty()) clips += Clip("music", 0, piece.voice, at, 0.0, dur)
            cursor = at + dur
        }
        return clips
    }

    private fun parseClip(line: String): Clip? {
        val captionAt = line.indexOf(" caption:")
        val head = if (captionAt >= 0) line.substring(0, captionAt) else line
        val caption = if (captionAt >= 0) line.substring(captionAt + 9).trim().take(80) else ""
        val parts = head.trim().split(Regex("\\s+"))
        if (parts.size < 7 || parts[0] != "clip") return null
        val kind = parts[1]
        if (kind != "video" && kind != "music") return null
        val lane = parts[2].toIntOrNull() ?: return null
        if (lane !in 0..15) return null
        val file = parts[3]
        if (!safeName(file)) return null
        val at = parts[4].toDoubleOrNull() ?: return null
        val start = parts[5].toDoubleOrNull() ?: return null
        val end = parts[6].toDoubleOrNull() ?: return null
        if (!at.isFinite() || !start.isFinite() || !end.isFinite() || at < 0.0) return null
        if (end <= start || end - start > 120.0) return null
        var speed = 1.0
        var volume = 1.0
        var transition = ""
        val effects = ArrayList<String>()
        for (token in parts.drop(7)) {
            when {
                token.startsWith("speed:") -> speed = token.removePrefix("speed:").toDoubleOrNull() ?: return null
                token.startsWith("volume:") -> volume = token.removePrefix("volume:").toDoubleOrNull() ?: return null
                token.startsWith("transition:") -> {
                    transition = token.removePrefix("transition:")
                    if (transition !in TRANSITIONS) return null
                }
                token in EFFECTS -> effects += token
                else -> return null
            }
        }
        if (speed !in 0.25..4.0 || volume !in 0.0..1.0) return null
        return Clip(kind, lane, file, at, start, end, effects.toSet(), caption, speed, volume, transition)
    }

    private fun safeName(file: String): Boolean =
        file.isNotEmpty() && !file.contains("..") && !file.contains('\\') && !file.startsWith("/") && !file.contains(' ')

    fun parse(text: String): List<Piece>? {
        val pieces = ArrayList<Piece>()
        for (raw in text.replace("\r\n", "\n").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 4)
            if (parts.size < 2) return null
            val start = parts[0].toDoubleOrNull() ?: return null
            val end = parts[1].toDoubleOrNull() ?: return null
            if (!start.isFinite() || !end.isFinite() || end <= start) return null
            if (end - start > 120) return null
            var fade = false
            var voice = ""
            var caption = ""
            val rest = if (parts.size >= 3) parts.drop(2).joinToString(" ") else ""
            val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var index = 0
            if (words.getOrNull(0) == "fade" || words.getOrNull(0) == "cut") {
                fade = words[0] == "fade"
                index = 1
            }
            if (words.getOrNull(index)?.startsWith("voice:") == true) {
                voice = words[index].removePrefix("voice:").trim()
                if (voice.contains("..") || voice.startsWith("/") || voice.contains('\\')) return null
                index++
            }
            caption = words.drop(index).joinToString(" ").take(80)
            pieces += Piece(start, end, caption, fade, voice)
        }
        if (pieces.isEmpty() || pieces.size > 24) return null
        return pieces
    }

    /** Razor. Cuts one block at a source time, or in the middle when the playhead is outside it. */
    fun split(clip: Clip, atSource: Double): Pair<Clip, Clip>? {
        val cut = if (atSource > clip.start + 0.2 && atSource < clip.end - 0.2) atSource else (clip.start + clip.end) / 2.0
        if (cut <= clip.start + 0.2 || cut >= clip.end - 0.2) return null
        val left = clip.copy(end = cut)
        val right = clip.copy(start = cut, at = clip.at + left.span())
        return left to right
    }

    /** Pulls the blocks on one layer together so a gap does not sit between them. */
    fun closeGaps(clips: List<Clip>, kind: String, lane: Int): List<Clip> {
        val indexes = clips.indices.filter { clips[it].kind == kind && clips[it].lane == lane }.sortedBy { clips[it].at }
        if (indexes.size < 2) return clips
        val next = clips.toMutableList()
        var cursor = next[indexes.first()].at
        for (index in indexes) {
            val clip = next[index]
            next[index] = clip.copy(at = cursor)
            cursor += next[index].span()
        }
        return next
    }

    /** Moves one block to another time and another layer. The kind stays. */
    fun place(clips: List<Clip>, index: Int, at: Double, lane: Int): List<Clip> {
        if (index !in clips.indices) return clips
        val snapped = kotlin.math.round(at.coerceAtLeast(0.0) * 20.0) / 20.0
        val nextLane = lane.coerceIn(0, 15)
        return clips.mapIndexed { i, clip ->
            if (i == index) clip.copy(at = snapped, lane = nextLane) else clip
        }
    }

    /** What would make this edit fail: a missing file, an overlap, or a hole in the main picture. */
    fun review(project: Project, present: Set<String>): List<String> {
        val notes = ArrayList<String>()
        if (project.clips.none { it.kind == "video" }) notes += "Add a video layer."
        for (clip in project.clips) {
            if (clip.file !in present) notes += "Missing ${clip.file}."
        }
        val groups = project.clips.groupBy { it.kind to it.lane }
        for ((key, list) in groups) {
            val ordered = list.sortedBy { it.at }
            for (i in 1 until ordered.size) {
                val prevEnd = ordered[i - 1].at + ordered[i - 1].span()
                if (ordered[i].at < prevEnd - 0.05) notes += "Two ${key.first} blocks overlap."
            }
        }
        val base = project.clips.filter { it.kind == "video" && it.lane == 0 }.sortedBy { it.at }
        if (base.isNotEmpty()) {
            if (base.first().at > 0.25) notes += "The main picture starts late."
            var covered = 0.0
            for (clip in base) {
                if (clip.at > covered + 0.25) notes += "The main picture has a gap."
                val end = clip.at + clip.span()
                if (end > covered) covered = end
            }
        }
        return notes
    }

    /** A plain edit list another edit can read back. Times are 30 frames a second. */
    fun edl(project: Project): String = buildString {
        append("TITLE: cut\nFCM: NON-DROP FRAME\n\n")
        project.clips.forEachIndexed { index, clip ->
            val inn = tc(clip.at)
            val out = tc(clip.at + clip.span())
            val track = if (clip.kind == "music") "A" else "V"
            append("%03d  AX       %s     C        %s %s %s %s\n".format(index + 1, track, inn, out, inn, out))
            append("* FROM CLIP NAME: ").append(clip.file).append('\n')
            append("* KIND: ").append(clip.kind).append('\n')
            append("* LANE: ").append(clip.lane).append('\n')
            append("* SOURCE IN: ").append(num(clip.start)).append('\n')
            append("* SOURCE OUT: ").append(num(clip.end)).append('\n')
            append("* SPEED: ").append(num(clip.speed)).append('\n')
            append("* VOLUME: ").append(num(clip.volume)).append('\n')
            if (clip.effects.isNotEmpty()) append("* EFFECTS: ").append(clip.effects.joinToString(",")).append('\n')
            if (clip.transition in TRANSITIONS) append("* TRANSITION: ").append(clip.transition).append('\n')
            if (clip.caption.isNotEmpty()) append("* CAPTION: ").append(clip.caption).append('\n')
            append('\n')
        }
    }

    fun srt(project: Project): String = buildString {
        var n = 1
        for (clip in project.clips) {
            if (clip.kind != "video" || clip.caption.isEmpty()) continue
            append(n++).append('\n')
            append(srtTime(clip.at)).append(" --> ").append(srtTime(clip.at + clip.span())).append('\n')
            append(clip.caption).append("\n\n")
        }
    }

    /** Reads an edit list, a reel, or caption cues. Captions land on the picture that covers them. */
    fun takeIn(text: String, current: List<Clip>): List<Clip>? {
        val body = text.trim()
        if (body.isEmpty() || body.length > 64_000) return null
        if (body.contains("FROM CLIP NAME:")) return importEdl(body)
        if (body.contains("-->")) return applySrt(current, body)
        val asReel = if (body.startsWith("source:") || body.startsWith("clip ")) {
            load(if (body.startsWith("clip ")) "source: clip.mp4\n$body" else body)
        } else {
            null
        }
        return asReel?.clips?.takeIf { it.isNotEmpty() }
    }

    private fun importEdl(text: String): List<Clip>? {
        val clips = ArrayList<Clip>()
        var file = ""
        var kind = "video"
        var lane = 0
        var sourceIn = 0.0
        var sourceOut = 0.0
        var speed = 1.0
        var volume = 1.0
        var effects = emptySet<String>()
        var transition = ""
        var caption = ""
        var at = 0.0
        var open = false
        fun finish() {
            if (!open || !safeName(file) || sourceOut <= sourceIn) return
            clips += Clip(kind, lane.coerceIn(0, 15), file, at, sourceIn, sourceOut, effects, caption, speed, volume, transition)
            open = false
        }
        for (raw in text.replace("\r\n", "\n").lineSequence()) {
            val line = raw.trim()
            if (line.length >= 3 && line[0].isDigit() && line.take(3).all { it.isDigit() }) {
                finish()
                val times = Regex("""(\d\d:\d\d:\d\d:\d\d)""").findAll(line).map { it.value }.toList()
                at = times.getOrNull(2)?.let { tcSeconds(it) } ?: 0.0
                file = ""
                kind = if (" A " in line || line.contains(" A     ")) "music" else "video"
                lane = 0
                sourceIn = 0.0
                sourceOut = (times.getOrNull(3)?.let { tcSeconds(it) } ?: at) - at
                if (sourceOut < 0.2) sourceOut = 1.0
                speed = 1.0
                volume = 1.0
                effects = emptySet()
                transition = ""
                caption = ""
                open = true
            } else if (line.startsWith("* FROM CLIP NAME:")) {
                file = line.substringAfter(':').trim()
            } else if (line.startsWith("* KIND:")) {
                kind = if (line.substringAfter(':').trim() == "music") "music" else "video"
            } else if (line.startsWith("* LANE:")) {
                lane = line.substringAfter(':').trim().toIntOrNull() ?: 0
            } else if (line.startsWith("* SOURCE IN:")) {
                sourceIn = line.substringAfter(':').trim().toDoubleOrNull() ?: sourceIn
            } else if (line.startsWith("* SOURCE OUT:")) {
                sourceOut = line.substringAfter(':').trim().toDoubleOrNull() ?: sourceOut
            } else if (line.startsWith("* SPEED:")) {
                speed = (line.substringAfter(':').trim().toDoubleOrNull() ?: 1.0).coerceIn(0.25, 4.0)
            } else if (line.startsWith("* VOLUME:")) {
                volume = (line.substringAfter(':').trim().toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0)
            } else if (line.startsWith("* EFFECTS:")) {
                effects = line.substringAfter(':').split(',').map { it.trim() }.filter { it in EFFECTS }.toSet()
            } else if (line.startsWith("* TRANSITION:")) {
                val name = line.substringAfter(':').trim()
                transition = if (name in TRANSITIONS) name else ""
            } else if (line.startsWith("* CAPTION:")) {
                caption = line.substringAfter(':').trim().take(80)
            }
        }
        finish()
        return clips.takeIf { it.isNotEmpty() && it.size <= 32 }
    }

    private fun applySrt(clips: List<Clip>, text: String): List<Clip>? {
        val cues = ArrayList<Triple<Double, Double, String>>()
        val blocks = text.replace("\r\n", "\n").trim().split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val time = lines.firstOrNull { it.contains("-->") } ?: continue
            val parts = time.split("-->")
            if (parts.size < 2) continue
            val start = srtSeconds(parts[0].trim()) ?: continue
            val end = srtSeconds(parts[1].trim().substringBefore(' ')) ?: continue
            val words = lines.dropWhile { !it.contains("-->") }.drop(1).joinToString(" ").take(80)
            if (words.isNotEmpty() && end > start) cues += Triple(start, end, words)
        }
        if (cues.isEmpty()) return null
        return clips.map { clip ->
            if (clip.kind != "video" || clip.caption.isNotEmpty()) clip
            else {
                val cue = cues.firstOrNull { it.first < clip.at + clip.span() && it.second > clip.at }
                if (cue == null) clip else clip.copy(caption = cue.third)
            }
        }
    }

    private fun tc(seconds: Double): String {
        val frames = (seconds * 30.0).toInt().coerceAtLeast(0)
        val f = frames % 30
        val s = frames / 30 % 60
        val m = frames / 1800 % 60
        val h = frames / 108000
        return "%02d:%02d:%02d:%02d".format(h, m, s, f)
    }

    private fun tcSeconds(value: String): Double? {
        val p = value.split(":")
        if (p.size != 4) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = p[2].toIntOrNull() ?: return null
        val f = p[3].toIntOrNull() ?: return null
        return h * 3600.0 + m * 60.0 + s + f / 30.0
    }

    private fun srtTime(seconds: Double): String {
        val ms = (seconds * 1000.0).toInt().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    private fun srtSeconds(value: String): Double? {
        val cleaned = value.trim().replace(',', '.')
        val p = cleaned.split(":")
        if (p.size != 3) return null
        val h = p[0].toDoubleOrNull() ?: return null
        val m = p[1].toDoubleOrNull() ?: return null
        val s = p[2].toDoubleOrNull() ?: return null
        return h * 3600.0 + m * 60.0 + s
    }

    fun page(sourceName: String, width: Int, height: Int, pieces: List<Piece>): Sheet? =
        page(Project(sourceName, width, height, pieces))

    /** The main picture, a second picture on top, and music, as one composition. */
    fun page(project: Project): Sheet? {
        val clips = project.clips.ifEmpty { clipsFrom(project.source, project.pieces) }
        val visual = clips.filter { it.kind == "video" }
        if (visual.isEmpty() || clips.size > 32) return null
        if (clips.any { !safeName(it.file) }) return null
        val w = project.width.coerceIn(2, 1920) / 2 * 2
        val h = project.height.coerceIn(2, 1920) / 2 * 2
        val seconds = visual.maxOf { it.at + it.span() }
        if (seconds <= 0.0 || seconds > 300.0) return null
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
            append("html,body{margin:0;background:#000;width:100%;height:100%}")
            append("#cut{position:relative;overflow:hidden;background:#000;font-family:sans-serif}")
            append(".clip{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover;background:#000}")
            append(".cap{display:flex;align-items:flex-end;justify-content:center;padding:0 8% 12%;box-sizing:border-box;")
            append("color:#fff;font-size:")
            append((h * 0.045).toInt().coerceIn(28, 72))
            append("px;font-weight:700;text-align:center;text-shadow:0 2px 8px #000;z-index:40}")
            append("</style></head><body>")
            append("<div id=\"cut\" data-composition-id=\"cut\" data-start=\"0\" data-width=\"")
            append(w).append("\" data-height=\"").append(h).append("\" data-duration=\"")
            append(num(seconds)).append("\">")
            var audio = 0
            for (clip in clips.sortedWith(compareBy<Clip>({ if (it.kind == "music") 1 else 0 }, { it.lane }, { it.at }))) {
                val dur = clip.span()
                val rate = clip.speed.coerceIn(0.25, 4.0)
                val fade = if ("fade" in clip.effects) " data-fade=\"0.35\"" else ""
                val enter = if (clip.transition in TRANSITIONS) " data-transition=\"${clip.transition}\"" else ""
                if (clip.kind == "music") {
                    val loud = if ("mute" in clip.effects) 0.0 else clip.volume.coerceIn(0.0, 1.0)
                    append("<audio id=\"mix").append(audio++).append("\" src=\"").append(escape(clip.file))
                    append("\" data-start=\"").append(num(clip.at))
                    append("\" data-duration=\"").append(num(dur))
                    append("\" data-media-start=\"").append(num(clip.start))
                    append("\" data-playback-rate=\"").append(num(rate))
                    append("\" data-volume=\"").append(num(loud)).append('"')
                    append(level(clip, dur))
                    append(" data-track-index=\"4\"></audio>")
                    continue
                }
                val look = buildString {
                    append(frame(clip.lane))
                    val filters = ArrayList<String>()
                    if ("gray" in clip.effects) filters += "grayscale(1)"
                    if ("warm" in clip.effects) filters += "sepia(0.75)"
                    if ("blur" in clip.effects) filters += "blur(6px)"
                    if (filters.isNotEmpty()) append("filter:").append(filters.joinToString(" ")).append(';')
                    val motion = ArrayList<String>()
                    if ("zoom" in clip.effects) motion += "scale(1.18)"
                    if ("flip" in clip.effects) motion += "scaleX(-1)"
                    if (motion.isNotEmpty()) append("transform:").append(motion.joinToString(" ")).append(';')
                }
                val style = " style=\"$look\""
                append("<video class=\"clip\" src=\"").append(escape(clip.file))
                append("\" muted playsinline data-start=\"").append(num(clip.at))
                append("\" data-duration=\"").append(num(dur))
                append("\" data-media-start=\"").append(num(clip.start))
                append("\" data-playback-rate=\"").append(num(rate))
                append("\" data-track-index=\"").append(clip.lane).append('"')
                append(fade).append(enter).append(style).append("></video>")
                if ("mute" !in clip.effects) {
                    append("<audio id=\"mix").append(audio++).append("\" src=\"").append(escape(clip.file))
                    append("\" data-start=\"").append(num(clip.at))
                    append("\" data-duration=\"").append(num(dur))
                    append("\" data-media-start=\"").append(num(clip.start))
                    append("\" data-playback-rate=\"").append(num(rate))
                    append("\" data-volume=\"").append(num(clip.volume.coerceIn(0.0, 1.0))).append('"')
                    append(level(clip, dur))
                    append(" data-track-index=\"1\"></audio>")
                }
                if (clip.caption.isNotEmpty()) {
                    append("<div class=\"clip cap\" data-start=\"").append(num(clip.at))
                    append("\" data-duration=\"").append(num(dur))
                    append("\" data-track-index=\"3\"").append(fade).append(enter).append(">")
                    append(escape(clip.caption)).append("</div>")
                }
            }
            append("</div><script>")
            append("window.__timelines=window.__timelines||{};")
            append("window.__timelines[\"cut\"]={paused:function(){return true;},pause:function(){},seek:function(){},duration:function(){return ")
            append(num(seconds)).append(";}};")
            append("</script></body></html>")
        }
        return Sheet(project.pieces, html, seconds)
    }

    /** A short fade in, and a fade out when the fade effect is on. */
    private fun level(clip: Clip, dur: Double): String {
        val fadeOut = "fade" in clip.effects
        val fadeIn = fadeOut || clip.transition == "dissolve" || clip.transition == "dip"
        if (!fadeIn || dur <= 0.0) return ""
        val edge = minOf(0.35, dur / 3.0).coerceAtLeast(0.05)
        val hold = (dur - edge).coerceAtLeast(edge)
        val end = if (fadeOut) 0 else 1
        val json = "{\"lanes\":[{\"target\":\"volume\",\"points\":[" +
            "{\"t\":0,\"v\":0},{\"t\":${num(edge)},\"v\":1}," +
            "{\"t\":${num(hold)},\"v\":1},{\"t\":${num(dur)},\"v\":$end}]}]}"
        return " data-automation=\"" + escape(json) + "\""
    }

    /** Lane 0 fills the frame. Each later lane is another picture, tiled so several stay visible. */
    private fun frame(lane: Int): String {
        if (lane <= 0) return "z-index:1;"
        val col = (lane - 1) % 2
        val row = (lane - 1) / 2
        val side = if (col == 0) "right:3%" else "left:3%"
        return "width:42%;height:26%;$side;top:${4 + row * 30}%;z-index:${lane + 1};"
    }

    private fun num(value: Double): String {
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private fun escape(text: String): String = buildString {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}
