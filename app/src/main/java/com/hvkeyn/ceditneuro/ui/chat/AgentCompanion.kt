package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.ui.CompanionCharacter
import com.hvkeyn.ceditneuro.ui.CompanionMood
import com.hvkeyn.ceditneuro.ui.CompanionRoster
import com.hvkeyn.ceditneuro.ui.CompanionSpriteSheet
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import com.hvkeyn.ceditneuro.ui.companionStatus
import com.hvkeyn.ceditneuro.ui.idleAction
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Original chibi companions drawn with Canvas. They are a reflection of the
 * agent state, not another model and not an editor.
 */
@Composable
fun AgentCompanion(state: WorkspaceUiState, settings: AgentSettings, compact: Boolean) {
    if (!settings.companionEnabled) return
    val character = CompanionRoster.resolve(
        selection = settings.companionCharacter,
        projectRoot = state.projectRoot,
        workFocus = settings.workFocus,
    )
    val customBitmap = remember(settings.companionImage) {
        settings.companionImage.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { java.io.File(path).takeIf { it.isFile } }.getOrNull() }
            ?.let { file -> android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    }
    val context = LocalContext.current
    val spriteSheet = remember(settings.companionSprite, context) {
        val bitmap = when (settings.companionSprite) {
            "sample" -> runCatching {
                context.assets.open("companions/sample/atlas.png").use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
            else -> settings.companionSprite.takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { java.io.File(path).takeIf { it.isFile } }.getOrNull() }
                ?.let { file -> android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
        }
        bitmap?.asImageBitmap()?.takeIf { CompanionSpriteSheet.usable(it.width, it.height) }
    }
    val displayName = when {
        spriteSheet != null && settings.companionSprite == "sample" -> "Pixel"
        spriteSheet != null -> "Custom sprite"
        customBitmap != null -> "Custom"
        else -> character.name
    }
    var now by remember(state.projectRoot) { mutableLongStateOf(System.currentTimeMillis()) }
    var lastActive by remember(state.projectRoot) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.agentRunning, state.agentActivity?.phase, state.chat.size, state.team?.agents) {
        lastActive = System.currentTimeMillis()
    }
    LaunchedEffect(state.projectRoot) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (state.agentRunning) 250L else 900L)
        }
    }
    val sleeping = !state.agentRunning && now - lastActive > 20_000L
    val mood = companionStatus(
        online = state.online,
        agentRunning = state.agentRunning,
        sleeping = sleeping,
        team = state.team,
        activity = state.agentActivity,
        lastEntry = state.chat.lastOrNull(),
    )
    val seed = remember(state.projectRoot) { Random.nextLong() }
    val gesture = if (mood.mood == CompanionMood.IDLE || mood.mood == CompanionMood.SLEEPING) {
        idleAction(seed + (now / 1000L) * 1000L)
    } else {
        null
    }
    val blink = gesture?.name == "BLINK" || (mood.mood == CompanionMood.THINKING && (now / 2600L) % 4L == 0L)
    val look = when (gesture?.name) {
        "LOOK_LEFT" -> -1f
        "LOOK_RIGHT" -> 1f
        else -> 0f
    }
    val sway = if (gesture?.name == "EAR_TWITCH" || gesture?.name == "TAIL_SWISH") {
        sin(now / 150.0).toFloat() * 2.2f
    } else {
        0f
    }
    val beat = if (mood.mood == CompanionMood.SLEEPING) 0f else sin(now / (520.0 - mood.load * 55.0)).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(if (compact) 44.dp else 50.dp)) {
            when {
                spriteSheet != null -> Canvas(modifier = Modifier.size(if (compact) 44.dp else 50.dp)) {
                    drawSpriteFrame(spriteSheet, mood.mood, now, size.width, size.height)
                }
                customBitmap != null -> Image(
                    bitmap = customBitmap,
                    contentDescription = "Custom companion",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (compact) 44.dp else 50.dp)
                        .clip(CircleShape),
                )
                else -> Canvas(modifier = Modifier.size(if (compact) 44.dp else 50.dp)) {
                    drawChibi(character, mood.mood, beat, blink, look, sway)
                }
            }
            if (settings.companionEffects) {
                Canvas(modifier = Modifier.size(if (compact) 44.dp else 50.dp)) {
                    drawPixelFx(character, mood.mood, now, mood.load, beat)
                }
            }
            if (mood.mood == CompanionMood.SLEEPING) {
                Text(
                    "z z",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else if (mood.mood == CompanionMood.TEAM) {
                Text(
                    "${state.team?.agents?.size ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else if (mood.mood == CompanionMood.WORRIED) {
                Text(
                    "!",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(character.accent),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    mood.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = moodColor(mood.mood),
                )
                if (mood.load > 0) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "load ${mood.load}/3",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                mood.message.replace("Nib", displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun DrawScope.drawChibi(
    character: CompanionCharacter,
    mood: CompanionMood,
    beat: Float,
    blink: Boolean,
    look: Float,
    sway: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val skin = Color(character.skin)
    val hair = Color(character.hair)
    val eyes = Color(character.eyes)
    val outfit = Color(character.outfit)
    val accent = Color(character.accent)
    val ink = Color(0xFF2E2825)
    val bob = beat * 0.7f
    val headY = h * 0.37f + bob
    val headR = w * 0.255f

    // A different silhouette for each character.
    when (character.hairstyle) {
        "twin" -> {
            drawOval(
                hair.copy(alpha = 0.9f),
                topLeft = Offset(cx - headR * 1.55f - sway, headY - headR * 0.25f),
                size = Size(headR * 0.75f, headR * 1.15f),
            )
            drawOval(
                hair.copy(alpha = 0.9f),
                topLeft = Offset(cx + headR * 0.80f + sway, headY - headR * 0.25f),
                size = Size(headR * 0.75f, headR * 1.15f),
            )
        }
        "long" -> {
            drawOval(
                hair.copy(alpha = 0.9f),
                topLeft = Offset(cx - headR * 1.30f, headY - headR * 0.35f),
                size = Size(headR * 0.55f, headR * 2.1f),
            )
            drawOval(
                hair.copy(alpha = 0.9f),
                topLeft = Offset(cx + headR * 0.75f, headY - headR * 0.35f),
                size = Size(headR * 0.55f, headR * 2.1f),
            )
        }
        "bob" -> {
            drawOval(
                hair.copy(alpha = 0.92f),
                topLeft = Offset(cx - headR * 1.12f, headY - headR * 0.45f),
                size = Size(headR * 0.45f, headR * 1.35f),
            )
            drawOval(
                hair.copy(alpha = 0.92f),
                topLeft = Offset(cx + headR * 0.67f, headY - headR * 0.45f),
                size = Size(headR * 0.45f, headR * 1.35f),
            )
        }
        "ponytail" -> {
            drawOval(
                hair,
                topLeft = Offset(cx + headR * 1.05f + sway, headY - headR * 0.70f),
                size = Size(headR * 0.55f, headR * 1.55f),
            )
            drawCircle(accent.copy(alpha = 0.8f), radius = headR * 0.13f, center = Offset(cx + headR * 1.06f, headY - headR * 0.55f))
        }
        else -> {
            val spikes = listOf(-0.85f, -0.50f, -0.15f, 0.20f, 0.55f, 0.90f)
            spikes.forEachIndexed { index, at ->
                val tipY = headY - headR * (1.15f + (index % 2) * 0.18f) + sway
                val spike = Path().apply {
                    moveTo(cx + headR * at - headR * 0.22f, headY - headR * 0.70f)
                    lineTo(cx + headR * at, tipY)
                    lineTo(cx + headR * at + headR * 0.22f, headY - headR * 0.70f)
                    close()
                }
                drawPath(spike, hair)
            }
        }
    }

    // Body.
    drawRoundRect(
        outfit,
        topLeft = Offset(cx - w * 0.22f, h * 0.62f + bob),
        size = Size(w * 0.44f, h * 0.30f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f),
    )
    drawRoundRect(
        accent.copy(alpha = 0.85f),
        topLeft = Offset(cx - w * 0.22f, h * 0.68f + bob),
        size = Size(w * 0.44f, h * 0.045f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f, w * 0.03f),
    )

    // Hair cap + face.
    drawCircle(hair, radius = headR * 1.08f, center = Offset(cx, headY - headR * 0.18f))
    drawOval(
        skin,
        topLeft = Offset(cx - headR * 0.82f, headY - headR * 0.52f),
        size = Size(headR * 1.64f, headR * 1.48f),
    )
    val bangs = Path().apply {
        moveTo(cx - headR * 0.90f, headY - headR * 0.30f)
        quadraticTo(cx - headR * 0.35f, headY - headR * 0.95f, cx + headR * 0.02f, headY - headR * 0.55f)
        quadraticTo(cx + headR * 0.40f, headY - headR * 0.92f, cx + headR * 0.90f, headY - headR * 0.28f)
        lineTo(cx + headR * 0.72f, headY - headR * 0.55f)
        lineTo(cx + headR * 0.28f, headY - headR * 0.35f)
        lineTo(cx - headR * 0.05f, headY - headR * 0.60f)
        lineTo(cx - headR * 0.35f, headY - headR * 0.30f)
        lineTo(cx - headR * 0.66f, headY - headR * 0.52f)
        close()
    }
    drawPath(bangs, hair)

    // Eyes.
    val eyeY = headY + headR * 0.08f
    val eyeDx = headR * 0.36f
    val eyeW = headR * 0.25f
    val eyeH = headR * 0.34f
    when {
        mood == CompanionMood.SLEEPING || blink -> {
            drawLine(ink, Offset(cx - eyeDx - eyeW, eyeY), Offset(cx - eyeDx + eyeW, eyeY), strokeWidth = w * 0.025f)
            drawLine(ink, Offset(cx + eyeDx - eyeW, eyeY), Offset(cx + eyeDx + eyeW, eyeY), strokeWidth = w * 0.025f)
        }
        mood == CompanionMood.HAPPY -> {
            drawArc(
                ink, 200f, 140f, false,
                topLeft = Offset(cx - eyeDx - eyeW, eyeY - eyeH * 0.38f),
                size = Size(eyeW * 2f, eyeH * 0.8f),
                style = Stroke(width = w * 0.025f),
            )
            drawArc(
                ink, 200f, 140f, false,
                topLeft = Offset(cx + eyeDx - eyeW, eyeY - eyeH * 0.38f),
                size = Size(eyeW * 2f, eyeH * 0.8f),
                style = Stroke(width = w * 0.025f),
            )
        }
        else -> {
            drawOval(eyes, Offset(cx - eyeDx - eyeW, eyeY - eyeH / 2f), Size(eyeW * 2f, eyeH))
            drawOval(eyes, Offset(cx + eyeDx - eyeW, eyeY - eyeH / 2f), Size(eyeW * 2f, eyeH))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = eyeW * 0.32f, center = Offset(cx - eyeDx + look * eyeW * 0.4f, eyeY - eyeH * 0.25f))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = eyeW * 0.32f, center = Offset(cx + eyeDx + look * eyeW * 0.4f, eyeY - eyeH * 0.25f))
        }
    }

    // Blush, mouth, brows.
    drawCircle(Color(0xFFE98A7A).copy(alpha = 0.30f), radius = headR * 0.20f, center = Offset(cx - headR * 0.56f, eyeY + headR * 0.35f))
    drawCircle(Color(0xFFE98A7A).copy(alpha = 0.30f), radius = headR * 0.20f, center = Offset(cx + headR * 0.56f, eyeY + headR * 0.35f))
    drawArc(
        ink.copy(alpha = 0.7f), 200f, 140f, false,
        topLeft = Offset(cx - headR * 0.12f, eyeY + headR * 0.38f),
        size = Size(headR * 0.24f, headR * 0.15f),
        style = Stroke(width = w * 0.018f),
    )
    if (mood == CompanionMood.WORRIED) {
        drawLine(ink, Offset(cx - eyeDx - eyeW, eyeY - eyeH * 0.95f), Offset(cx - eyeDx + eyeW, eyeY - eyeH * 0.65f), strokeWidth = w * 0.022f)
        drawLine(ink, Offset(cx + eyeDx - eyeW, eyeY - eyeH * 0.65f), Offset(cx + eyeDx + eyeW, eyeY - eyeH * 0.95f), strokeWidth = w * 0.022f)
    }
    if (mood == CompanionMood.OFFLINE) {
        drawLine(ink.copy(alpha = 0.4f), Offset(cx - eyeDx - eyeW, eyeY), Offset(cx - eyeDx + eyeW, eyeY), strokeWidth = w * 0.025f)
        drawLine(ink.copy(alpha = 0.4f), Offset(cx + eyeDx - eyeW, eyeY), Offset(cx + eyeDx + eyeW, eyeY), strokeWidth = w * 0.025f)
    }

    // Accessory.
    when (character.accessory) {
        "goggles" -> {
            drawLine(accent, Offset(cx - headR * 0.85f, headY - headR * 0.72f), Offset(cx + headR * 0.85f, headY - headR * 0.72f), strokeWidth = w * 0.035f)
            drawCircle(accent, radius = headR * 0.16f, center = Offset(cx - headR * 0.34f, headY - headR * 0.72f), style = Stroke(width = w * 0.025f))
            drawCircle(accent, radius = headR * 0.16f, center = Offset(cx + headR * 0.34f, headY - headR * 0.72f), style = Stroke(width = w * 0.025f))
        }
        "glasses" -> {
            drawCircle(ink.copy(alpha = 0.7f), radius = eyeW * 1.15f, center = Offset(cx - eyeDx, eyeY), style = Stroke(width = w * 0.018f))
            drawCircle(ink.copy(alpha = 0.7f), radius = eyeW * 1.15f, center = Offset(cx + eyeDx, eyeY), style = Stroke(width = w * 0.018f))
            drawLine(ink.copy(alpha = 0.7f), Offset(cx - eyeDx + eyeW * 1.15f, eyeY), Offset(cx + eyeDx - eyeW * 1.15f, eyeY), strokeWidth = w * 0.015f)
        }
        "headset" -> {
            drawArc(accent, 200f, 140f, false, topLeft = Offset(cx - headR * 1.1f, headY - headR * 1.25f), size = Size(headR * 2.2f, headR * 1.8f), style = Stroke(width = w * 0.03f))
            drawLine(accent, Offset(cx + headR * 0.95f, headY - headR * 0.55f), Offset(cx + headR * 1.12f, headY + headR * 0.12f), strokeWidth = w * 0.025f)
        }
        "hood" -> {
            drawArc(outfit, 190f, 160f, false, topLeft = Offset(cx - headR * 1.28f, headY - headR * 1.15f), size = Size(headR * 2.56f, headR * 2.25f), style = Stroke(width = w * 0.04f))
        }
        "snowclip" -> {
            val x = cx + headR * 0.70f
            val y = headY - headR * 0.55f
            drawLine(accent, Offset(x - w * 0.05f, y), Offset(x + w * 0.05f, y), strokeWidth = w * 0.018f)
            drawLine(accent, Offset(x, y - w * 0.05f), Offset(x, y + w * 0.05f), strokeWidth = w * 0.018f)
        }
        "flower" -> {
            val x = cx - headR * 0.72f
            val y = headY - headR * 0.62f
            repeat(5) { petal ->
                val angle = petal * (Math.PI * 2 / 5).toFloat()
                drawCircle(
                    accent,
                    radius = w * 0.032f,
                    center = Offset(x + kotlin.math.cos(angle) * w * 0.035f, y + sin(angle) * w * 0.035f),
                )
            }
            drawCircle(Color(0xFFFFF3C4), radius = w * 0.022f, center = Offset(x, y))
        }
        "bandana" -> {
            drawRoundRect(
                accent,
                topLeft = Offset(cx - headR * 0.92f, headY - headR * 0.92f),
                size = Size(headR * 1.84f, headR * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(headR * 0.08f, headR * 0.08f),
            )
            drawLine(accent, Offset(cx - headR * 0.85f, headY - headR * 0.80f), Offset(cx - headR * 1.25f, headY - headR * 0.45f), strokeWidth = w * 0.025f)
        }
    }

    // A small activity spark for a working agent.
    if (mood == CompanionMood.WORKING || mood == CompanionMood.THINKING) {
        val spark = Offset(cx + headR * 1.35f, headY - headR * 0.1f + beat * 1.5f)
        drawCircle(accent, radius = w * 0.028f, center = spark)
        drawLine(accent, spark, spark + Offset(w * 0.06f, -h * 0.05f), strokeWidth = w * 0.015f)
    }
    if (mood == CompanionMood.TEAM) {
        drawCircle(Color(0xFFF2A65A), radius = w * 0.06f, center = Offset(cx - headR * 1.45f, h * 0.80f))
        drawCircle(Color(0xFF7DD3FC), radius = w * 0.05f, center = Offset(cx + headR * 1.45f, h * 0.80f))
    }
}

private fun DrawScope.drawSpriteFrame(
    sheet: ImageBitmap,
    mood: CompanionMood,
    now: Long,
    width: Float,
    height: Float,
) {
    val frameW = sheet.width / CompanionSpriteSheet.COLUMNS
    val frameH = sheet.height / CompanionSpriteSheet.ROWS
    val row = CompanionSpriteSheet.rowFor(mood)
    val col = CompanionSpriteSheet.frameFor(now)
    drawImage(
        image = sheet,
        srcOffset = IntOffset(col * frameW, row * frameH),
        srcSize = IntSize(frameW, frameH),
        dstOffset = IntOffset(0, 0),
        dstSize = IntSize(width.toInt(), height.toInt()),
        filterQuality = FilterQuality.None,
    )
}

private fun DrawScope.drawPixelFx(
    character: CompanionCharacter,
    mood: CompanionMood,
    now: Long,
    load: Int,
    beat: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val headY = h * 0.37f
    val accent = Color(character.accent)
    val cream = Color(0xFFFFE9C9)
    val red = Color(0xFFE36B5D)
    fun pixel(x: Float, y: Float, s: Float, color: Color) {
        drawRect(color, topLeft = Offset(x, y), size = Size(s, s))
    }
    fun z(x: Float, y: Float, s: Float, color: Color) {
        val rows = listOf("111", "001", "010", "100", "111")
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, bit ->
                if (bit == '1') pixel(x + col * s, y + row * s, s, color)
            }
        }
    }
    fun star(x: Float, y: Float, s: Float, color: Color) {
        pixel(x + s, y, s, color)
        pixel(x, y + s, s, color)
        pixel(x + s, y + s, s, color)
        pixel(x + s * 2f, y + s, s, color)
        pixel(x + s, y + s * 2f, s, color)
    }
    when (mood) {
        CompanionMood.SLEEPING -> {
            drawRect(Color.Black.copy(alpha = 0.06f), size = size)
            val t = ((now / 900L) % 3L).toInt()
            z(cx + w * 0.18f, headY - h * 0.22f - t * h * 0.03f, w * 0.035f, accent.copy(alpha = 0.75f))
            z(cx + w * 0.30f, headY - h * 0.38f + t * h * 0.01f, w * 0.025f, accent.copy(alpha = 0.45f))
        }
        CompanionMood.THINKING -> {
            repeat(3) { index ->
                val phase = (now / 700.0 + index * 0.8) % 1.0
                val x = cx + w * (0.18f + index * 0.08f)
                val y = headY - h * (0.16f + phase.toFloat() * 0.14f)
                pixel(x, y, w * 0.045f, cream.copy(alpha = 1f - phase.toFloat() * 0.7f))
            }
            pixel(cx + w * 0.34f, headY - h * 0.40f, w * 0.05f, accent)
        }
        CompanionMood.WORKING -> {
            repeat(8) { index ->
                val phase = ((now / 420.0) + index * 0.37) % 1.0
                val side = if (index % 2 == 0) -1f else 1f
                val x = cx + side * w * (0.22f + phase.toFloat() * 0.18f)
                val y = h * 0.72f - phase.toFloat() * h * 0.35f
                pixel(x, y, w * 0.035f, if (index % 3 == 0) cream else accent)
            }
            pixel(cx + w * 0.30f, h * 0.30f, w * 0.05f, accent.copy(alpha = 0.8f))
        }
        CompanionMood.TEAM -> {
            val left = cx - w * 0.38f
            val right = cx + w * 0.38f
            val y = h * 0.78f + beat * 0.8f
            pixel(left, y, w * 0.07f, Color(0xFFF2A65A))
            pixel(right, y - w * 0.03f, w * 0.06f, Color(0xFF7DD3FC))
            val dots = 3
            repeat(dots) { index ->
                val x = left + (right - left) * (index + 1) / (dots + 1).toFloat()
                pixel(x, y + w * 0.015f, w * 0.025f, accent.copy(alpha = 0.6f))
            }
        }
        CompanionMood.HAPPY -> {
            repeat(8) { index ->
                val phase = ((now / 650.0) + index * 0.125) % 1.0
                val angle = index * Math.PI / 4
                val radius = w * (0.20f + phase.toFloat() * 0.22f)
                star(
                    cx + kotlin.math.cos(angle).toFloat() * radius,
                    headY + kotlin.math.sin(angle).toFloat() * radius,
                    w * 0.022f,
                    if (index % 2 == 0) accent else cream,
                )
            }
        }
        CompanionMood.WORRIED -> {
            repeat(3) { index ->
                pixel(cx - w * 0.28f, headY - h * 0.17f + index * h * 0.045f, w * 0.12f, red.copy(alpha = 0.35f))
            }
            pixel(cx + w * 0.30f, headY - h * 0.30f, w * 0.055f, red)
            pixel(cx + w * 0.31f, headY - h * 0.22f, w * 0.04f, red)
        }
        CompanionMood.OFFLINE -> {
            val line = ((now / 1200L) % 4L).toInt()
            drawRect(Color(0xFF6F8098).copy(alpha = 0.10f), topLeft = Offset(0f, line * h / 4f), size = Size(w, h / 12f))
        }
        CompanionMood.IDLE -> {
            repeat(2) { index ->
                val phase = ((now / 2200.0) + index * 0.5) % 1.0
                pixel(cx + w * (0.25f + index * 0.10f), h * (0.75f - phase.toFloat() * 0.25f), w * 0.02f, accent.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
fun CompanionAvatar(character: CompanionCharacter, mood: CompanionMood, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawChibi(character, mood, 0f, false, 0f, 0f)
    }
}

@Composable
private fun moodColor(mood: CompanionMood): Color = when (mood) {
    CompanionMood.WORRIED -> MaterialTheme.colorScheme.error
    CompanionMood.TEAM -> MaterialTheme.colorScheme.tertiary
    CompanionMood.HAPPY -> MaterialTheme.colorScheme.primary
    CompanionMood.SLEEPING, CompanionMood.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}
