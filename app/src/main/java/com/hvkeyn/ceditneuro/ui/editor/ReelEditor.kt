package com.hvkeyn.ceditneuro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hvkeyn.ceditneuro.video.CutList
import java.io.File

/**
 * A timeline with as many picture layers and sound layers as the phone can hold.
 * Each block can be moved, trimmed, and given an effect. Save writes this same file.
 */
@Composable
fun ReelEditor(
    reelFile: File,
    text: String,
    onSave: (String) -> Unit,
    onRender: () -> Unit,
    onExport: () -> Unit = {},
    onImport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val opened = remember(text) { CutList.load(text) }
    if (opened == null) {
        Text(
            "This edit cannot be read. It needs a source: line naming the video beside it.",
            modifier = modifier.padding(16.dp),
        )
        return
    }
    var clips by remember(text) { mutableStateOf(opened.clips) }
    var width by remember(text) { mutableStateOf(opened.width) }
    var height by remember(text) { mutableStateOf(opened.height) }
    var selected by remember(text) { mutableIntStateOf(opened.clips.indices.firstOrNull() ?: -1) }
    var picking by remember(text) { mutableStateOf<String?>(null) }
    var position by remember(text) { mutableLongStateOf(0L) }
    var seekTo by remember(text) { mutableStateOf<Long?>(null) }
    var notes by remember(text) { mutableStateOf<List<String>>(emptyList()) }
    val library = remember(reelFile.path) { library(reelFile.parentFile) }
    val papers = if (picking == "import") papers(reelFile.parentFile, reelFile.name) else emptyList()
    val current = clips.getOrNull(selected)
    val previewName = current?.takeIf { it.kind == "video" }?.file ?: opened.source
    val preview = File(reelFile.parentFile, previewName)
    fun project() = CutList.Project(opened.source, width, height, emptyList(), clips)
    fun update(index: Int, clip: CutList.Clip) {
        clips = clips.mapIndexed { i, item -> if (i == index) clip else item }
    }
    fun add(kind: String, file: String, freshLayer: Boolean) {
        if (clips.size >= 32) return
        val same = clips.filter { it.kind == kind }
        val lane = if (freshLayer) {
            ((same.maxOfOrNull { it.lane } ?: -1) + 1).coerceAtMost(15)
        } else {
            current?.takeIf { it.kind == kind }?.lane ?: same.minOfOrNull { it.lane } ?: 0
        }
        val at = if (freshLayer) 0.0 else same.filter { it.lane == lane }.maxOfOrNull { it.at + (it.end - it.start) } ?: 0.0
        val length = if (kind == "music") 4.0 else 2.0
        clips = clips + CutList.Clip(kind, lane, file, at, 0.0, length)
        selected = clips.lastIndex
        if (kind == "video") seekTo = 0L
    }

    Column(modifier.fillMaxSize()) {
        if (picking != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (picking == "music") Icons.Filled.MusicNote else if (picking == "import") Icons.Filled.FileOpen else Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(start = 12.dp).size(22.dp),
                )
                IconButton(onClick = { picking = null }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(
                    if (picking == "import") papers.map { MediaFile(it, "import") }
                    else library.filter { if (picking == "music") it.kind == "music" else it.kind == "video" },
                ) { media ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (picking == "import") {
                                    onImport(media.path)
                                    picking = null
                                } else {
                                    val fresh = picking == "layer" || picking == "music"
                                    val kind = if (picking == "music") "music" else "video"
                                    add(kind, media.path, fresh)
                                    picking = null
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (media.kind) {
                                "music" -> Icons.Filled.MusicNote
                                "import" -> Icons.Filled.FileOpen
                                else -> Icons.Filled.Videocam
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF9DB7FF),
                        )
                        Text(media.path, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@Column
        }
        if (!preview.isFile) {
            Text(
                "The video named in this edit is not beside the file.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            VideoPreview(
                file = preview,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 140.dp),
                showEdit = false,
                startPlaying = false,
                startLoop = false,
                seekMs = seekTo,
                onClock = { at, _, w, h ->
                    position = at
                    if (w > 1 && h > 1 && current?.kind != "music") {
                        width = w
                        height = h
                    }
                },
            )
        }
        Timeline(clips, selected, onSelect = { index ->
            selected = index
            val clip = clips[index]
            if (clip.kind == "video") seekTo = (clip.start * 1000).toLong()
        }, onMove = { index, at, lane ->
            clips = CutList.place(clips, index, at, lane)
            selected = index
        })
        val clip = clips.getOrNull(selected)
        if (clip != null) {
            Text(
                clip.file.substringAfterLast('/'),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val offered = if (clip.kind == "music") listOf("fade", "mute") else CutList.EFFECTS
                for (effect in offered) {
                    ToolIcon(effectIcon(effect), effect, marked = effect in clip.effects) {
                        val effects = if (effect in clip.effects) clip.effects - effect else clip.effects + effect
                        update(selected, clip.copy(effects = effects))
                    }
                }
                val transitions = if (clip.kind == "music") listOf("dissolve", "dip") else CutList.TRANSITIONS
                ToolIcon(Icons.Filled.HorizontalRule, "Hard cut", marked = clip.transition.isEmpty()) {
                    update(selected, clip.copy(transition = ""))
                }
                for (name in transitions) {
                    ToolIcon(transitionIcon(name), name, marked = clip.transition == name) {
                        val next = if (clip.transition == name) "" else name
                        update(selected, clip.copy(transition = next))
                    }
                }
                ToolIcon(Icons.Filled.ContentCut, "Split") {
                    val parts = CutList.split(clip, position / 1000.0) ?: return@ToolIcon
                    clips = clips.flatMapIndexed { i, item -> if (i == selected) listOf(parts.first, parts.second) else listOf(item) }
                }
                ToolIcon(Icons.Filled.ContentCopy, "Duplicate") {
                    if (clips.size >= 32) return@ToolIcon
                    val copy = clip.copy(at = clip.at + clip.span())
                    clips = clips.take(selected + 1) + copy + clips.drop(selected + 1)
                    selected += 1
                }
                ToolIcon(Icons.Filled.Speed, "Speed") {
                    val steps = listOf(1.0, 1.5, 2.0, 0.5)
                    val next = steps[(steps.indexOfFirst { kotlin.math.abs(it - clip.speed) < 0.01 } + 1).coerceAtLeast(0) % steps.size]
                    update(selected, clip.copy(speed = next))
                }
                ToolIcon(Icons.AutoMirrored.Filled.VolumeUp, "Volume") {
                    val steps = listOf(1.0, 0.7, 0.4)
                    val next = steps[(steps.indexOfFirst { kotlin.math.abs(it - clip.volume) < 0.01 } + 1).coerceAtLeast(0) % steps.size]
                    update(selected, clip.copy(volume = next))
                }
                ToolIcon(Icons.Filled.Compress, "Close gap") {
                    clips = CutList.closeGaps(clips, clip.kind, clip.lane)
                }
                ToolIcon(Icons.Filled.ChevronLeft, "Earlier") {
                    update(selected, clip.copy(at = (clip.at - 0.5).coerceAtLeast(0.0)))
                }
                ToolIcon(Icons.Filled.ChevronRight, "Later") {
                    update(selected, clip.copy(at = clip.at + 0.5))
                }
                if (clip.kind == "video") {
                    ToolIcon(Icons.Filled.FirstPage, "In") {
                        val start = position / 1000.0
                        if (clip.end > start + 0.2) update(selected, clip.copy(start = start))
                    }
                    ToolIcon(Icons.AutoMirrored.Filled.LastPage, "Out") {
                        val end = position / 1000.0
                        if (end > clip.start + 0.2) update(selected, clip.copy(end = end))
                    }
                } else {
                    ToolIcon(Icons.Filled.Remove, "Shorter") {
                        val end = clip.start + (clip.end - clip.start - 0.5).coerceAtLeast(0.4)
                        update(selected, clip.copy(end = end))
                    }
                    ToolIcon(Icons.Filled.Add, "Longer") {
                        update(selected, clip.copy(end = clip.end + 0.5))
                    }
                }
                ToolIcon(Icons.Filled.Delete, "Drop") {
                    clips = clips.filterIndexed { i, _ -> i != selected }
                    selected = if (clips.isEmpty()) -1 else selected.coerceAtMost(clips.lastIndex)
                }
            }
            OutlinedTextField(
                value = clip.caption,
                onValueChange = { update(selected, clip.copy(caption = it.take(80))) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Title, contentDescription = "Caption") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolIcon(Icons.Filled.Videocam, "Add video") { picking = "layer" }
            ToolIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add on this track") { picking = "more" }
            ToolIcon(Icons.Filled.MusicNote, "Add music") { picking = "music" }
            ToolIcon(Icons.Filled.Save, "Save") { onSave(CutList.write(project())) }
            ToolIcon(Icons.Filled.Upload, "Export") {
                onSave(CutList.write(project()))
                onExport()
            }
            ToolIcon(Icons.Filled.FileOpen, "Import") { picking = "import" }
            ToolIcon(Icons.AutoMirrored.Filled.Rule, "Check") {
                notes = CutList.review(project(), library.map { it.path }.toSet()).ifEmpty { listOf("The edit checks out.") }
            }
            ToolIcon(Icons.Filled.FileDownload, "Render", enabled = clips.any { it.kind == "video" }) {
                onSave(CutList.write(project()))
                onRender()
            }
        }
        if (notes.isNotEmpty()) {
            Text(
                notes.joinToString(" "),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (notes.singleOrNull() == "The edit checks out.") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ToolIcon(
    image: ImageVector,
    description: String,
    enabled: Boolean = true,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(
            image,
            contentDescription = description,
            tint = when {
                !enabled -> Color(0xFF6A6A6A)
                marked -> Color(0xFF7AA2FF)
                else -> Color(0xFFE6E6E6)
            },
        )
    }
}

private fun effectIcon(effect: String): ImageVector = when (effect) {
    "fade" -> Icons.Filled.Opacity
    "gray" -> Icons.Filled.FilterBAndW
    "warm" -> Icons.Filled.WbSunny
    "zoom" -> Icons.Filled.ZoomIn
    "mute" -> Icons.AutoMirrored.Filled.VolumeOff
    "blur" -> Icons.Filled.BlurOn
    "flip" -> Icons.Filled.Flip
    else -> Icons.Filled.Title
}

private fun transitionIcon(name: String): ImageVector = when (name) {
    "dissolve" -> Icons.Filled.Opacity
    "wipe" -> Icons.AutoMirrored.Filled.ArrowForward
    "dip" -> Icons.Filled.DarkMode
    else -> Icons.Filled.HorizontalRule
}

@Composable
private fun Timeline(
    clips: List<CutList.Clip>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Double, Int) -> Unit,
) {
    val video = clips.mapIndexedNotNull { index, clip -> if (clip.kind == "video") clip.lane to index else null }
        .groupBy({ it.first }, { it.second })
    val audio = clips.mapIndexedNotNull { index, clip -> if (clip.kind == "music") clip.lane to index else null }
        .groupBy({ it.first }, { it.second })
    val videoLanes = openLane(video.keys)
    val audioLanes = openLane(audio.keys)
    val computed = videoLanes.sortedDescending().map { "video" to it } + audioLanes.sorted().map { "music" to it }
    var locked by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    val rows = locked ?: computed
    val latestClips = rememberUpdatedState(clips)
    val latestRows = rememberUpdatedState(rows)
    val span = (clips.maxOfOrNull { it.at + it.span() } ?: 4.0).coerceIn(4.0, 300.0)
    val per = 64.dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column {
                for ((kind, _) in rows) {
                    Box(Modifier.width(20.dp).height(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (kind == "music") Icons.Filled.MusicNote else Icons.Filled.Videocam,
                            contentDescription = if (kind == "music") "Music" else "Video",
                            modifier = Modifier.size(16.dp),
                            tint = if (kind == "music") Color(0xFFE0B27A) else Color(0xFF9DB7FF),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        fun hit(x: Float, y: Float): Int {
                            val rowPx = 40.dp.toPx()
                            val perPx = 64.dp.toPx()
                            val shown = latestRows.value
                            val row = (y / rowPx).toInt()
                            if (row !in shown.indices) return -1
                            val (kind, lane) = shown[row]
                            val time = x / perPx
                            val current = latestClips.value
                            return current.indices.firstOrNull { i ->
                                val clip = current[i]
                                clip.kind == kind && clip.lane == lane && time >= clip.at && time <= clip.at + clip.span()
                            } ?: -1
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val index = hit(down.position.x, down.position.y)
                            if (index < 0) return@awaitEachGesture
                            down.consume()
                            onSelect(index)
                            val shown = latestRows.value
                            locked = shown
                            val perPx = 64.dp.toPx()
                            val rowPx = 40.dp.toPx()
                            val grab = down.position.x / perPx - latestClips.value[index].at
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    val current = latestClips.value
                                    if (index !in current.indices) break
                                    val clip = current[index]
                                    val row = (change.position.y / rowPx).toInt()
                                    val target = shown.getOrNull(row)
                                    val lane = if (target != null && target.first == clip.kind) target.second else clip.lane
                                    val at = (change.position.x / perPx - grab).coerceAtLeast(0.0)
                                    onMove(index, at, lane)
                                }
                            } finally {
                                locked = null
                            }
                        }
                    },
            ) {
                Column {
                    for ((kind, lane) in rows) {
                        val indexes = if (kind == "video") video[lane].orEmpty() else audio[lane].orEmpty()
                        Box(Modifier.width(per * span.toFloat()).height(40.dp).background(Color(0xFF161616))) {
                            indexes.forEach { index ->
                                val clip = clips[index]
                                val color = if (index == selected) {
                                    if (kind == "music") Color(0xFFE0A15A) else Color(0xFF7AA2FF)
                                } else if (kind == "music") {
                                    Color(0xFF8A5A28)
                                } else {
                                    VIDEO_COLORS[lane % VIDEO_COLORS.size]
                                }
                                Box(
                                    Modifier
                                        .offset(x = per * clip.at.toFloat())
                                        .width((per * clip.span().toFloat()).coerceAtLeast(36.dp))
                                        .height(36.dp)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(color)
                                        .semantics { contentDescription = "$kind $lane ${clip.file.substringAfterLast('/')}" }
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        clip.file.substringAfterLast('/'),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The lanes that already hold a block, plus one empty lane to drag a block onto. */
private fun openLane(lanes: Set<Int>): List<Int> {
    val body = if (lanes.isEmpty()) listOf(0) else lanes.sorted()
    val next = (body.maxOrNull() ?: -1) + 1
    return if (next in 0..15 && next !in body) body + next else body
}

private val VIDEO_COLORS = listOf(
    Color(0xFF2A4E9A),
    Color(0xFF1E7A52),
    Color(0xFF6A3E9A),
    Color(0xFF8A3E3E),
    Color(0xFF2A6A7A),
)

private data class MediaFile(val path: String, val kind: String)

private fun papers(root: File?, skip: String): List<String> {
    if (root == null || !root.isDirectory) return emptyList()
    val out = ArrayList<String>()
    fun walk(dir: File, prefix: String, depth: Int) {
        if (depth > 2 || out.size >= 20) return
        val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
        for (child in kids) {
            if (child.name.startsWith('.')) continue
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) walk(child, rel, depth + 1)
            else if (rel != skip && child.extension.lowercase() in setOf("edl", "srt", "reel")) out += rel
        }
    }
    walk(root, "", 0)
    return out
}

private fun library(root: File?): List<MediaFile> {
    if (root == null || !root.isDirectory) return emptyList()
    val out = ArrayList<MediaFile>()
    fun walk(dir: File, prefix: String, depth: Int) {
        if (depth > 3 || out.size >= 40) return
        val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
        for (child in kids) {
            if (child.name.startsWith('.')) continue
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) walk(child, rel, depth + 1)
            else {
                val kind = when (child.extension.lowercase()) {
                    "mp4", "webm", "3gp", "mov", "mkv", "ts" -> "video"
                    "wav", "mp3", "ogg", "m4a" -> "music"
                    else -> null
                }
                if (kind != null) out += MediaFile(rel, kind)
            }
        }
    }
    walk(root, "", 0)
    return out
}
