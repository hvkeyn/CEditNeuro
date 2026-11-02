package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.ui.CompanionMood
import com.hvkeyn.ceditneuro.ui.CompanionStatus
import com.hvkeyn.ceditneuro.ui.WorkspaceUiState
import com.hvkeyn.ceditneuro.ui.companionStatus
import com.hvkeyn.ceditneuro.ui.idleAction
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Nib: an original paper-fox status pet. It is a reflection of the agent state,
 * not another model and not an editor.
 */
@Composable
fun AgentCompanion(state: WorkspaceUiState, compact: Boolean) {
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
    val earTwitch = if (gesture?.name == "EAR_TWITCH") sin(now / 90.0).toFloat() * 1.2f else 0f
    val swish = if (gesture?.name == "TAIL_SWISH") sin(now / 180.0).toFloat() * 2.4f else 0f
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
        Box(modifier = Modifier.size(if (compact) 42.dp else 48.dp)) {
            Canvas(modifier = Modifier.size(if (compact) 42.dp else 48.dp)) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val bodyTop = h * 0.28f - beat * 0.9f
                val bodyW = w * 0.62f
                val bodyH = h * 0.56f
                val body = Color(0xFFEADDC6)
                val ink = Color(0xFF2D2823)
                val earColor = Color(0xFFC97B5A)
                val scarf = Color(0xFFB14F3A)
                val active = mood.mood != CompanionMood.SLEEPING && mood.mood != CompanionMood.OFFLINE

                // Tail behind the body.
                val tail = Path().apply {
                    moveTo(cx + bodyW * 0.28f, bodyTop + bodyH * 0.72f)
                    cubicTo(
                        cx + bodyW * 0.82f + swish, bodyTop + bodyH * 0.62f,
                        cx + bodyW * 0.78f + swish, bodyTop + bodyH * 0.18f,
                        cx + bodyW * 0.5f + swish, bodyTop + bodyH * 0.08f,
                    )
                }
                drawPath(tail, body.copy(alpha = 0.9f), style = Stroke(width = w * 0.085f))

                // Ears.
                val leftEar = Path().apply {
                    moveTo(cx - bodyW * 0.44f, bodyTop + bodyH * 0.18f)
                    lineTo(cx - bodyW * 0.58f, bodyTop - h * 0.18f + earTwitch)
                    lineTo(cx - bodyW * 0.12f, bodyTop + bodyH * 0.02f)
                    close()
                }
                val rightEar = Path().apply {
                    moveTo(cx + bodyW * 0.12f, bodyTop + bodyH * 0.02f)
                    lineTo(cx + bodyW * 0.58f, bodyTop - h * 0.18f - earTwitch)
                    lineTo(cx + bodyW * 0.44f, bodyTop + bodyH * 0.18f)
                    close()
                }
                drawPath(leftEar, body)
                drawPath(rightEar, body)
                drawPath(leftEar, earColor.copy(alpha = 0.45f), style = Stroke(width = w * 0.025f))
                drawPath(rightEar, earColor.copy(alpha = 0.45f), style = Stroke(width = w * 0.025f))

                // Body and paws.
                drawOval(
                    color = body,
                    topLeft = Offset(cx - bodyW / 2f, bodyTop),
                    size = androidx.compose.ui.geometry.Size(bodyW, bodyH),
                )
                drawCircle(ink.copy(alpha = 0.12f), radius = w * 0.05f, center = Offset(cx - bodyW * 0.26f, bodyTop + bodyH * 0.88f))
                drawCircle(ink.copy(alpha = 0.12f), radius = w * 0.05f, center = Offset(cx + bodyW * 0.26f, bodyTop + bodyH * 0.88f))

                // Scarf.
                drawArc(
                    color = scarf,
                    startAngle = 12f,
                    sweepAngle = 156f,
                    useCenter = false,
                    topLeft = Offset(cx - bodyW * 0.40f, bodyTop + bodyH * 0.55f),
                    size = androidx.compose.ui.geometry.Size(bodyW * 0.80f, bodyH * 0.34f),
                    style = Stroke(width = w * 0.07f),
                )

                // Eyes.
                val eyeY = bodyTop + bodyH * 0.40f
                val eyeDx = bodyW * 0.17f
                val eyeR = w * 0.045f
                if (mood.mood == CompanionMood.SLEEPING || blink) {
                    drawLine(ink, Offset(cx - eyeDx - eyeR, eyeY), Offset(cx - eyeDx + eyeR, eyeY), strokeWidth = w * 0.035f)
                    drawLine(ink, Offset(cx + eyeDx - eyeR, eyeY), Offset(cx + eyeDx + eyeR, eyeY), strokeWidth = w * 0.035f)
                } else if (mood.mood == CompanionMood.HAPPY) {
                    drawArc(
                        ink, 200f, 140f, false,
                        topLeft = Offset(cx - eyeDx - eyeR, eyeY - eyeR),
                        size = androidx.compose.ui.geometry.Size(eyeR * 2f, eyeR * 1.6f),
                        style = Stroke(width = w * 0.035f),
                    )
                    drawArc(
                        ink, 200f, 140f, false,
                        topLeft = Offset(cx + eyeDx - eyeR, eyeY - eyeR),
                        size = androidx.compose.ui.geometry.Size(eyeR * 2f, eyeR * 1.6f),
                        style = Stroke(width = w * 0.035f),
                    )
                } else {
                    drawCircle(ink, radius = eyeR, center = Offset(cx - eyeDx + look * eyeR * 0.5f, eyeY))
                    drawCircle(ink, radius = eyeR, center = Offset(cx + eyeDx + look * eyeR * 0.5f, eyeY))
                }

                // Nose and brows.
                drawCircle(scarf, radius = w * 0.035f, center = Offset(cx, bodyTop + bodyH * 0.52f))
                if (mood.mood == CompanionMood.WORRIED) {
                    drawLine(ink, Offset(cx - eyeDx - eyeR, eyeY - eyeR * 2.2f), Offset(cx - eyeDx + eyeR, eyeY - eyeR * 1.2f), strokeWidth = w * 0.03f)
                    drawLine(ink, Offset(cx + eyeDx - eyeR, eyeY - eyeR * 1.2f), Offset(cx + eyeDx + eyeR, eyeY - eyeR * 2.2f), strokeWidth = w * 0.03f)
                }
                if (mood.mood == CompanionMood.OFFLINE) {
                    drawLine(ink.copy(alpha = 0.35f), Offset(cx - eyeDx - eyeR, eyeY), Offset(cx - eyeDx + eyeR, eyeY), strokeWidth = w * 0.035f)
                    drawLine(ink.copy(alpha = 0.35f), Offset(cx + eyeDx - eyeR, eyeY), Offset(cx + eyeDx + eyeR, eyeY), strokeWidth = w * 0.035f)
                }
                if (mood.mood == CompanionMood.TEAM) {
                    drawCircle(Color(0xFFF2A65A), radius = w * 0.055f, center = Offset(cx + bodyW * 0.34f, bodyTop + bodyH * 0.76f))
                    drawCircle(Color(0xFF7DD3FC), radius = w * 0.045f, center = Offset(cx - bodyW * 0.38f, bodyTop + bodyH * 0.72f))
                }
                if (mood.mood == CompanionMood.WORKING || mood.mood == CompanionMood.THINKING) {
                    val spark = Offset(cx + bodyW * 0.58f, bodyTop + bodyH * 0.10f + beat * 1.2f)
                    drawCircle(Color(0xFFF2A65A), radius = w * 0.035f, center = spark)
                    drawLine(Color(0xFFF2A65A), spark, spark + Offset(w * 0.10f, -h * 0.07f), strokeWidth = w * 0.025f)
                }
                if (!active) {
                    drawCircle(body.copy(alpha = 0.16f), radius = w * 0.5f, center = Offset(cx, bodyTop + bodyH * 0.55f))
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
                    "NIB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
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
                mood.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
