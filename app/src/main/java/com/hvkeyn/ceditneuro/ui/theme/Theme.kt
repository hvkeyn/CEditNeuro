package com.hvkeyn.ceditneuro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeuroAccent = Color(0xFF7DD3FC)
val NeuroAccentAlt = Color(0xFF34D399)
val NeuroSurface = Color(0xFF0E1116)
val NeuroSurfaceAlt = Color(0xFF151A21)
val NeuroPanel = Color(0xFF11151C)
val NeuroOutline = Color(0xFF2A323D)

/**
 * A code editor wants a dark, low-chroma shell by default, so the theme does not follow
 * the system light/dark setting during the early milestones.
 */
private val NeuroColorScheme = darkColorScheme(
    primary = NeuroAccent,
    onPrimary = Color(0xFF06222F),
    secondary = NeuroAccentAlt,
    onSecondary = Color(0xFF04211A),
    background = NeuroSurface,
    onBackground = Color(0xFFE6EAF0),
    surface = NeuroSurfaceAlt,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = NeuroPanel,
    onSurfaceVariant = Color(0xFFB4BEcb),
    outline = NeuroOutline,
    error = Color(0xFFF87171),
)

@Composable
fun CEditNeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeuroColorScheme,
        content = content,
    )
}
