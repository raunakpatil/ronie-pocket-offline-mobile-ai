package com.raunakpatil.roniepocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ronie's design language (2026-11-09, from the user's reference boards): a near-black canvas, dark glassy
 * tiles with big rounded corners and a faint edge, vivid gradients used sparingly (actions, timers,
 * "thinking"), big light numerals, dot-matrix digits for clocks, white pill buttons for the main action.
 * Always dark: it's Ronie's look, like the visor. Neon Tokyo-night palette (user, 2026-11-09): cyan, magenta,
 * violet, neon orange and acid lime on blue-black.
 */
object R {
    // Canvas and surfaces: blue-black, like Tokyo at night.
    val bg = Color(0xFF05050A)
    val surface = Color(0xFF0F0F17)
    val surface2 = Color(0xFF171722)
    val surface3 = Color(0xFF21212E)
    val edge = Color(0x1AFFFFFF)          // faint 1 dp border on tiles
    val text = Color(0xFFF2F3FA)
    val muted = Color(0xFF8B8CA3)
    val faint = Color(0xFF4F5068)

    // Neon accents (cyberpunk / Tokyo night).
    val orange = Color(0xFFFF8A1F)        // neon orange
    val pink = Color(0xFFFF2E97)          // magenta
    val red = Color(0xFFFF3358)
    val violet = Color(0xFF8A5CFF)
    val blue = Color(0xFF2F6BFF)
    val cyan = Color(0xFF00E5FF)          // electric cyan
    val lime = Color(0xFFC6FF3D)          // acid lime
    val green = Color(0xFF2BFFB0)         // neon mint

    // Gradients
    val sunset = listOf(Color(0xFFFF8A1F), Color(0xFFFF2E97))            // orange → magenta
    val aurora = listOf(Color(0xFF00E5FF), Color(0xFF8A5CFF), Color(0xFFFF2E97))  // cyan → violet → magenta
    val ocean = listOf(Color(0xFF00E5FF), Color(0xFF2F6BFF))             // cyan → blue
    val meadow = listOf(Color(0xFFC6FF3D), Color(0xFF2BFFB0))            // lime → mint
    val ember = listOf(Color(0xFFFF8A1F), Color(0xFFFF3358))             // orange → red
    val glassGlow = listOf(Color(0x33FF5E8E), Color(0x1A8A5CFF), Color(0x00000000))

    fun diagonal(colors: List<Color>) = Brush.linearGradient(colors)
    fun horizontal(colors: List<Color>) = Brush.horizontalGradient(colors)

    val tileShape = RoundedCornerShape(28.dp)
    val cardShape = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val colors = darkColorScheme(
    primary = R.text,                    // white primary buttons, like the references' "Next"
    onPrimary = R.bg,
    primaryContainer = R.surface3,
    onPrimaryContainer = R.text,
    secondary = R.orange,
    onSecondary = R.bg,
    tertiary = R.violet,
    background = R.bg,
    onBackground = R.text,
    surface = R.surface,
    onSurface = R.text,
    surfaceVariant = R.surface2,
    onSurfaceVariant = R.muted,
    surfaceContainer = R.surface,
    surfaceContainerHigh = R.surface2,
    surfaceContainerHighest = R.surface3,
    surfaceContainerLow = R.bg,
    outline = R.faint,
    outlineVariant = R.edge,
    error = R.red,
    onError = R.text,
)

private val type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Light),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Light),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Normal),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Small caps-style label used above numbers ("NEW USERS", "EARNINGS" in the references). */
val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)

@Composable
fun RonieTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = colors,
    typography = type,
    shapes = Shapes(small = RoundedCornerShape(14.dp), medium = R.cardShape, large = R.tileShape,
        extraLarge = RoundedCornerShape(32.dp)),
    content = content,
)
