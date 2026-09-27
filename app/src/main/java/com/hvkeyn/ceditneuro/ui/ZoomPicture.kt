package com.hvkeyn.ceditneuro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A picture in the page. A tap opens it full screen. A pinch zooms in and back out,
 * and a drag moves the enlarged picture. The close control leaves the page as it was.
 */
@Composable
fun ExpandablePicture(
    modifier: Modifier = Modifier,
    /** Height divided by width, so the enlarged frame matches the picture. */
    aspect: Float? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        content()
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Enlarge") { open = true },
        )
    }
    if (open) {
        PictureStage(aspect = aspect, onClose = { open = false }, content = content)
    }
}

@Composable
fun PictureStage(
    onClose: () -> Unit,
    aspect: Float? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val ratio = (aspect ?: 0.72f).coerceIn(0.2f, 2.2f)
                val fittedHeight = maxWidth * ratio
                val frameHeight = fittedHeight.coerceAtMost(maxHeight * 0.92f)
                val frameWidth = frameHeight / ratio
                Box(
                    Modifier
                        .size(frameWidth, frameHeight)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    content()
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            scale = next
                            offset = if (next <= 1.05f) Offset.Zero else offset + pan
                        }
                    },
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/**
 * Full-screen WebView picture. The close control sits above the view, not on it,
 * so the WebView can take the pinch without covering the button.
 */
@Composable
fun WebPictureStage(
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
        }
    }
}
