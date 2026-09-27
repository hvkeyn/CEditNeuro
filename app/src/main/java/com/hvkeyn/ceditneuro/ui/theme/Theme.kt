package com.hvkeyn.ceditneuro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeuroAccent = Color(0xFF7DD3FC)
val NeuroAccentAlt = Color(0xFF34D399)
val NeuroSurface = Color(0xFF0E1116)
val NeuroSurfaceAlt = Color(0xFF151A21)
/** Raised panel. Sits above [NeuroSurfaceAlt], the way a surface container does in Material 3. */
val NeuroPanel = Color(0xFF1C2430)
val NeuroOutline = Color(0xFF3D4754)
/** Selected row and active icon. 16% of the accent, readable on the dark surface. */
val NeuroSelected = Color(0x297DD3FC)

/**
 * A code editor wants a dark, low-chroma shell by default, so the theme does not follow
 * the system light/dark setting during the early milestones.
 */
private val NeuroColorScheme = darkColorScheme(
    primary = NeuroAccent,
    onPrimary = Color(0xFF06222F),
    primaryContainer = Color(0xFF164E63),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = NeuroAccentAlt,
    onSecondary = Color(0xFF04211A),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    background = NeuroSurface,
    onBackground = Color(0xFFE6EAF0),
    surface = NeuroSurfaceAlt,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = NeuroPanel,
    onSurfaceVariant = Color(0xFFC5CED9),
    outline = NeuroOutline,
    outlineVariant = Color(0xFF2A323D),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

@Composable
fun CEditNeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeuroColorScheme,
        content = content,
    )
}
